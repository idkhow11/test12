"""가치망 부품의 손 계산 시험 (docs/TRAINING.md 3~5장). PyTorch 가 없으면 건너뛴다.

    python -m unittest discover -s python/tests -v
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

try:
    import torch

    from tetris_ai import net as netlib
    from tetris_ai.model_io import ATTACK, B2B, COMBO, FEATURES, GARBAGE_RISES, LINES, OPP_FEATURES, PENDING
    from tetris_ai.train import lambda_returns
except ImportError:  # 학습 도구를 설치하지 않은 PC
    torch = None


@unittest.skipIf(torch is None, "PyTorch 가 없음 (python/requirements-train.txt)")
class BoardStatsTest(unittest.TestCase):
    def test_hand_example(self) -> None:
        # 0 열: 21~23 행이 참 → 높이 3, 구멍 0.
        # 1 열: 20 행만 참 → 높이 4, 그 아래 21~23 행이 구멍 3 개.
        # 나머지 열은 비었다. 울퉁불퉁함 |4 - 3| + |0 - 4| = 5, 구멍 있는 줄은 21, 22, 23 행 3 개.
        b = torch.zeros(1, 24, 10)
        b[0, 21:24, 0] = 1
        b[0, 20, 1] = 1
        s = netlib.board_stats(b)[0]
        self.assertEqual(s[:10].tolist(), [3, 4, 0, 0, 0, 0, 0, 0, 0, 0])
        self.assertEqual(s[10:20].tolist(), [0, 3, 0, 0, 0, 0, 0, 0, 0, 0])
        self.assertEqual(s[20:].tolist(), [4, 3, 5, 3])

    def test_matches_numpy_metrics(self) -> None:
        from tetris_ai.metrics import heights_and_holes

        rng = np.random.default_rng(1)
        boards = (rng.random((50, 24, 10)) < 0.3).astype(np.uint8)
        s = netlib.board_stats(torch.from_numpy(boards).float()).numpy()
        h, holes = heights_and_holes(boards)
        np.testing.assert_array_equal(s[:, :10], h)
        np.testing.assert_array_equal(s[:, 10:20], holes)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class BaselinePriorTest(unittest.TestCase):
    def test_hand_example(self) -> None:
        # BoardStatsTest 의 보드: 높이 합 3 + 4 = 7, 구멍 3, 울퉁불퉁함 5, 지운 줄 0
        # H = -0.510066·7 - 0.35663·3 - 0.184483·5 = -3.570462 - 1.06989 - 0.922415 = -5.562767
        b = torch.zeros(1, 24, 10)
        b[0, 21:24, 0] = 1
        b[0, 20, 1] = 1
        h = netlib.baseline_score(torch.zeros(1, FEATURES), netlib.board_stats(b))
        self.assertAlmostEqual(h.item(), -5.562767, places=5)

    def test_untrained_score_is_weighted_baseline(self) -> None:
        # 마지막 층이 0 에서 시작하므로 학습 전 V = 0 이다. 중립(스타일 보상 0)이면 점수는 β·H 뿐이고
        # 상대·AI 와 상관없다. 즉 학습 전 모델은 기준 AI 와 같은 배치를 고른다.
        torch.manual_seed(1)
        net = netlib.ValueNet(n_ai=3)
        module = netlib.ScoreModule(net, 2, netlib.style_weights("neutral"), gamma=1.0, prior_weight=0.05)
        rng = np.random.default_rng(2)
        board = torch.from_numpy((rng.random((20, 24, 10)) < 0.3).astype(np.float32))
        features = torch.from_numpy(rng.integers(0, 3, (20, FEATURES)).astype(np.float32))
        opp = torch.from_numpy((rng.random((20, 24, 10)) < 0.5).astype(np.float32))
        opp_f = torch.from_numpy(rng.integers(0, 5, (20, OPP_FEATURES)).astype(np.float32))
        with torch.no_grad():
            v = net(board, features, opp, opp_f, torch.randint(0, 3, (20,)))
            score = module(board, features, opp, opp_f)
            h = netlib.baseline_score(features, netlib.board_stats(board))
        torch.testing.assert_close(v, torch.zeros_like(v))
        torch.testing.assert_close(score, 0.05 * h)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class RewardTermsTest(unittest.TestCase):
    def features(self, lines: int, attack: int, b2b: bool, combo: int) -> torch.Tensor:
        f = torch.zeros(1, FEATURES)
        f[0, LINES], f[0, ATTACK], f[0, B2B], f[0, COMBO] = lines, attack, float(b2b), combo + 1
        return f

    def terms(self, **kw) -> dict[str, float]:
        t = netlib.reward_terms(self.features(**kw), torch.zeros(1, netlib.STATS))[0]
        return dict(zip(netlib.REWARD_TERMS, t.tolist()))

    def test_tspin_is_one_to_three_lines_with_b2b(self) -> None:
        self.assertEqual(self.terms(lines=2, attack=4, b2b=True, combo=0)["tspin"], 1)  # TSD
        self.assertEqual(self.terms(lines=1, attack=1, b2b=True, combo=0)["tspin"], 1)  # mini 싱글
        self.assertEqual(self.terms(lines=2, attack=2, b2b=False, combo=0)["tspin"], 0)  # 일반 더블은 B2B 를 끊는다
        t = self.terms(lines=4, attack=4, b2b=True, combo=0)
        self.assertEqual((t["tspin"], t["tetris"]), (0, 1))
        self.assertEqual(self.terms(lines=0, attack=0, b2b=True, combo=-1)["tspin"], 0)  # 줄 없는 배치

    def test_combo_clear_needs_combo_one_or_more(self) -> None:
        self.assertEqual(self.terms(lines=1, attack=1, b2b=False, combo=0)["combo"], 0)  # 연속의 첫 클리어
        self.assertEqual(self.terms(lines=1, attack=2, b2b=False, combo=1)["combo"], 1)

    def test_defender_terms_hand_example(self) -> None:
        # 대기 가비지 11 줄, 줄을 못 지움 → 8 줄이 올라오고 3 줄이 남는다. 최고 높이 18 → 위험 (18 − 12) ÷ 12 = 0.5
        f = torch.zeros(3, FEATURES)
        f[0, PENDING], f[0, GARBAGE_RISES] = 11, 1
        f[1, PENDING] = 2  # 줄을 지워 상쇄하고도 2 줄이 남음 → 이번에는 올라오지 않는다
        stats = torch.zeros(3, netlib.STATS)
        stats[:, netlib.STAT_MAX_HEIGHT] = torch.tensor([18.0, 12.0, 3.0])
        t = [dict(zip(netlib.REWARD_TERMS, row.tolist())) for row in netlib.reward_terms(f, stats)]
        self.assertEqual([r["garbage_rise"] for r in t], [8, 0, 0])
        self.assertEqual([r["pending"] for r in t], [3, 2, 0])
        self.assertEqual([r["danger"] for r in t], [0.5, 0, 0])
        self.assertEqual([r["survive"] for r in t], [1, 1, 1])
        # 수비 원형: 0.0005 − 0.004·8 − 0.004·3 − 0.01·0.5 = −0.0485, 안전한 블록(세 번째)은 +0.0005
        r = netlib.reward_terms(f, stats) @ netlib.style_weights("defender")
        torch.testing.assert_close(r, torch.tensor([-0.0485, 0.0005 - 0.008, 0.0005]))

    def test_style_weights_reject_unknown_terms(self) -> None:
        self.assertEqual(netlib.style_weights("neutral").abs().sum().item(), 0)
        with self.assertRaises(ValueError):
            netlib.style_weights({"stall": 1.0})

    def test_single_and_combo_len_terms(self) -> None:
        # (줄, 공격, B2B, 배치 뒤 콤보) → (싱글, 콤보 길이). 콤보 길이는 줄을 지운 배치의 배치 뒤 콤보 수다.
        cases = [
            ((1, 1, False, 0), (1, 0)),  # 연속의 첫 클리어(싱글)
            ((1, 3, False, 2), (1, 2)),  # 연속 셋째 클리어
            ((2, 4, True, 0), (0, 0)),  # TSD 는 싱글이 아니다
            ((1, 2, True, 0), (1, 0)),  # T-spin 싱글도 싱글이다
            ((0, 0, False, -1), (0, 0)),  # 줄 없는 배치
            ((0, 0, False, 2), (0, 0)),  # 줄을 못 지우면 콤보 값이 남아 있어도 0
            ((4, 4, True, 3), (0, 3)),  # 테트리스로 이은 연속
        ]
        for (lines, attack, b2b, combo), want in cases:
            t = self.terms(lines=lines, attack=attack, b2b=b2b, combo=combo)
            self.assertEqual((t["single"], t["combo_len"]), want, (lines, attack, b2b, combo))
        self.assertEqual(self.terms(lines=2, attack=4, b2b=True, combo=0)["tspin"], 1)
        t = self.terms(lines=1, attack=2, b2b=True, combo=0)
        self.assertEqual((t["single"], t["tspin"]), (1, 1))

    def test_new_terms_keep_old_rewards(self) -> None:
        # p05 의 두 항목은 끝에 붙었다. 앞 10 개의 순서는 그대로이고 원형 가중치의 새 항목은 0 이라, 옛 실행(p01~p04)의
        # 보상이 같다.
        self.assertEqual(netlib.REWARD_TERMS[:10], ("attack", "tspin", "tetris", "combo", "height", "holes", "survive",
                                                    "garbage_rise", "pending", "danger"))
        self.assertEqual(netlib.REWARD_TERMS[10:], ("single", "combo_len"))
        rng = np.random.default_rng(0)
        f = torch.from_numpy(rng.integers(-1, 5, (64, FEATURES)).astype(np.float32))
        b = torch.from_numpy((rng.random((64, 24, 10)) < 0.3).astype(np.float32))
        terms = netlib.reward_terms(f, netlib.board_stats(b))
        self.assertEqual(tuple(terms.shape), (64, 12))
        for name in netlib.ARCHETYPES:
            w = netlib.style_weights(name)
            self.assertEqual(w[10:].abs().sum().item(), 0)
            torch.testing.assert_close(terms @ w, terms[:, :10] @ w[:10], rtol=0, atol=1e-7)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class LambdaReturnTest(unittest.TestCase):
    def test_hand_example(self) -> None:
        # V = [0.5, 0.2, 0.1], R = [0, 0.1, 0.2], 패배(-1), γ = 1, λ = 0.5
        # G2 = -1
        # G1 = R2 + 0.5·V2 + 0.5·G2 = 0.2 + 0.05 - 0.5 = -0.25
        # G0 = R1 + 0.5·V1 + 0.5·G1 = 0.1 + 0.1 - 0.125 = 0.075
        g = lambda_returns(np.array([0.5, 0.2, 0.1]), np.array([0, 0.1, 0.2]), np.array([0]), np.array([3]),
                           np.array([-1.0]), gamma=1.0, lam=0.5)
        np.testing.assert_allclose(g, [0.075, -0.25, -1.0], atol=1e-6)

    def test_episodes_do_not_leak(self) -> None:
        # 두 판을 이어 붙여도 앞 판의 목표는 뒤 판의 값(9)과 무관하다. γ = 1, λ = 0.8
        # 앞 판(승리): G1 = 1, G0 = R1 + 0.2·V1 + 0.8·G1 = 0 + 0 + 0.8 = 0.8
        # 뒤 판(무승부): G3 = 0, G2 = R3 + 0.2·V3 + 0.8·G3 = 9 + 1.8 + 0 = 10.8
        v = np.array([0.0, 0.0, 9.0, 9.0])
        r = np.array([0.0, 0.0, 9.0, 9.0])
        g = lambda_returns(v, r, np.array([0, 2]), np.array([2, 2]), np.array([1.0, 0.0]), gamma=1.0, lam=0.8)
        np.testing.assert_allclose(g, [0.8, 1.0, 10.8, 0.0], atol=1e-6)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class ExportTest(unittest.TestCase):
    def test_onnx_matches_torch_and_has_tags(self) -> None:
        import onnxruntime as ort

        torch.manual_seed(0)
        net = netlib.ValueNet(n_ai=2)
        with torch.no_grad():  # 마지막 층이 0 이면 신경망 경로가 값에 드러나지 않는다
            torch.nn.init.normal_(net.head[-1].weight, std=0.1)
        weights = netlib.style_weights("attacker")
        module = netlib.ScoreModule(net, 1, weights, gamma=1.0, prior_weight=0.05).eval()
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "m.onnx"
            netlib.export_onnx(module, path, {"tetris.persona": "attacker", "tetris.stage": "gen-000"})
            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            meta = session.get_modelmeta().custom_metadata_map
            self.assertEqual(meta["tetris.model_io"], "2")
            self.assertEqual(meta["tetris.persona"], "attacker")
            self.assertEqual([i.name for i in session.get_inputs()], list(netlib.INPUT_NAMES))
            rng = np.random.default_rng(3)
            for n in (1, 7, 42):  # N 은 dynamic axis
                inputs = {
                    "board": (rng.random((n, 24, 10)) < 0.3).astype(np.float32),
                    "features": rng.integers(0, 3, (n, FEATURES)).astype(np.float32),
                    "opp_board": (rng.random((n, 24, 10)) < 0.3).astype(np.float32),
                    "opp_features": rng.integers(0, 3, (n, OPP_FEATURES)).astype(np.float32),
                }
                (got,) = session.run(["value"], inputs)
                with torch.no_grad():
                    want = module(*(torch.from_numpy(inputs[k]) for k in netlib.INPUT_NAMES)).numpy()
                self.assertEqual(got.shape, (n, 1))
                np.testing.assert_allclose(got, want, atol=1e-5, rtol=1e-5)

    def test_onnx_new_reward_terms(self) -> None:
        # p05 가중치(싱글 −0.06, 콤보 길이 0.04 등)를 넣어 내보낸 모델이 손 계산과 같은 즉시 보상을 낸다.
        # 학습 전 신경망은 V = 0 이므로 점수 = R + 0.05·H 다.
        import onnxruntime as ort

        torch.manual_seed(0)
        net = netlib.ValueNet(n_ai=1)
        weights = netlib.style_weights({"attack": 0.015, "tetris": 0.2, "single": -0.06, "combo": 0.01, "combo_len": 0.04})
        module = netlib.ScoreModule(net, 0, weights, gamma=1.0, prior_weight=0.05).eval()
        rows = [(1, 1, False, 0), (1, 3, False, 2), (4, 4, True, 3), (2, 4, True, 0), (0, 0, False, -1)]
        f = np.zeros((len(rows), FEATURES), np.float32)
        for i, (lines, attack, b2b, combo) in enumerate(rows):
            f[i, LINES], f[i, ATTACK], f[i, B2B], f[i, COMBO] = lines, attack, float(b2b), combo + 1
        # 싱글 −0.06 + 공격 0.015: 첫째 −0.045, 둘째 −0.06 + 0.045 + 콤보 0.01 + 길이 2 × 0.04 = 0.075,
        # 테트리스 0.2 + 공격 0.06 + 콤보 0.01 + 길이 3 × 0.04 = 0.39, TSD 공격 0.06, 빈 배치 0
        want_r = np.array([-0.045, 0.075, 0.39, 0.06, 0.0], np.float32)
        inputs = {"board": np.zeros((len(rows), 24, 10), np.float32), "features": f,
                  "opp_board": np.zeros((len(rows), 24, 10), np.float32),
                  "opp_features": np.zeros((len(rows), OPP_FEATURES), np.float32)}
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "m.onnx"
            netlib.export_onnx(module, path, {"tetris.persona": "attacker", "tetris.stage": "gen-000"})
            (got,) = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]).run(["value"], inputs)
        with torch.no_grad():
            h = netlib.baseline_score(torch.from_numpy(f), netlib.board_stats(torch.from_numpy(inputs["board"])))
        np.testing.assert_allclose(got[:, 0] - 0.05 * h[:, 0].numpy(), want_r, atol=1e-6)


if __name__ == "__main__":
    unittest.main()
