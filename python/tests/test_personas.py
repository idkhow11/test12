"""페르소나 학습·분석 부품의 시험 (docs/TRAINING.md 9장). Java 대전은 돌리지 않는다.

    python -m unittest discover -s python/tests -v

PyTorch 가 없으면 모두 건너뛰고, scikit-learn 이 없으면 군집 분석 시험만 건너뛴다.
"""

from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from collections import Counter
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

try:
    import torch

    import persona_probe as probe
    import persona_select as select
    import persona_signatures as sig
    import train_personas as tp
    from tetris_ai import net as netlib
    from tetris_ai import personas as pl
    from tetris_ai.model_io import ATTACK, B2B, COMBO, FEATURES, GARBAGE_RISES, LINES, OPP_FEATURES, PENDING
    from tetris_ai.records import Trajectory
    from tetris_ai.train import Dataset, lambda_returns, rewards
except ImportError:  # 학습 도구를 설치하지 않은 PC
    torch = None

try:
    from tetris_ai import persona_analysis as pa
except ImportError:  # scikit-learn 이 없는 PC
    pa = None

# 손 계산이 쉬운 원형 가중치. 원형마다 다른 항목 하나씩(수비만 음수).
SIMPLE = {"attacker": {"attack": 1.0}, "defender": {"holes": -1.0}, "tspin": {"tspin": 2.0}, "combo": {"combo": 4.0}}


def features(lines: int = 0, attack: int = 0, b2b: bool = False, combo: int = -1) -> np.ndarray:
    f = np.zeros(FEATURES, np.float32)
    f[LINES], f[ATTACK], f[B2B], f[COMBO] = lines, attack, float(b2b), combo + 1
    return f


def trajectory(feats: list[np.ndarray], boards: np.ndarray | None = None, outcome: float = 1.0) -> "Trajectory":
    t = len(feats)
    return Trajectory(
        ai="combo", persona="combo", model="m.onnx", side="p1", outcome=outcome, turns=t,
        boards=np.zeros((t, 24, 10), np.uint8) if boards is None else boards,
        features=np.stack(feats), opp_boards=np.zeros((t, 24, 10), np.uint8),
        opp_features=np.zeros((t, OPP_FEATURES), np.float32), pending_before=np.zeros(t, np.int16),
        sent=np.zeros(t, np.int16), risen=np.zeros(t, np.int16), spin=np.zeros(t, np.int8),
        hold_used=np.zeros(t, bool), pc=np.zeros(t, bool))


@unittest.skipIf(torch is None, "PyTorch 가 없음 (python/requirements-train.txt)")
class PersonaTableTest(unittest.TestCase):
    def test_archetypes_then_mixtures(self) -> None:
        ps = pl.make_personas(netlib.ARCHETYPES, 8, 1.0, 0)
        self.assertEqual([p.name for p in ps], ["attacker", "defender", "tspin", "combo",
                                                "mix01", "mix02", "mix03", "mix04", "mix05", "mix06", "mix07", "mix08"])
        for i, p in enumerate(ps[:4]):  # 원형은 자기 비율 1, 가중치는 4.3 표 그대로
            self.assertEqual(p.ratios, tuple(1.0 if j == i else 0.0 for j in range(4)))
            self.assertEqual(p.label, p.name)
            np.testing.assert_allclose(p.weights, netlib.style_weights(p.name).numpy(), rtol=1e-6)
        for p in ps[4:]:
            self.assertAlmostEqual(sum(p.ratios), 1.0, places=12)
            self.assertTrue(all(r > 0 for r in p.ratios))

    def test_mixtures_are_reproducible(self) -> None:
        a = pl.make_personas(netlib.ARCHETYPES, 8, 1.0, 0)
        self.assertEqual(a, pl.make_personas(netlib.ARCHETYPES, 8, 1.0, 0))
        self.assertNotEqual(a[4].ratios, pl.make_personas(netlib.ARCHETYPES, 8, 1.0, 1)[4].ratios)
        # TRAINING.md 9.1 의 표: 시드 0 의 첫 섞은 AI 는 (0.395, 0.592, 0.012, 0.001), 라벨 수비
        np.testing.assert_allclose(a[4].ratios, [0.395, 0.592, 0.012, 0.001], atol=5e-4)
        self.assertEqual([p.label for p in a[4:]],
                         ["defender", "defender", "defender", "attacker", "attacker", "tspin", "combo", "attacker"])

    def test_mixture_weights_hand_example(self) -> None:
        # 가중치 = 비율 @ 원형 표. SIMPLE 에서 비율 (r0, r1, r2, r3) 이면
        # REWARD_TERMS(attack, tspin, tetris, combo, height, holes, 뒤의 여섯) 순서로 (r0, 2·r2, 0, 4·r3, 0, −r1, 0 × 6)
        p = pl.make_personas(SIMPLE, 2, 1.0, 5)[5]
        r0, r1, r2, r3 = p.ratios
        np.testing.assert_allclose(p.weights, [r0, 2 * r2, 0.0, 4 * r3, 0.0, -r1] + [0.0] * 6, atol=1e-12)
        np.testing.assert_allclose(p.ratios, pl.mixture_ratios(2, 1.0, 5)[1], atol=0)

    def test_replicas_and_neutral(self) -> None:
        # p05 표: 원형 4 개를 복제마다(이름 뒤 _b), 다음에 중립(비율·가중치 0), 다음에 섞은 AI
        ps = pl.make_personas(SIMPLE, 0, 1.0, 0, replicas=2, n_neutral=2)
        self.assertEqual([p.name for p in ps], ["attacker", "defender", "tspin", "combo", "attacker_b", "defender_b",
                                                "tspin_b", "combo_b", "neutral", "neutral_b"])
        self.assertEqual([p.label for p in ps], [*pl.ARCHETYPE_NAMES] * 2 + [pl.NEUTRAL] * 2)
        for a, b in zip(ps[:4], ps[4:8]):  # 복제는 비율·가중치가 같다
            self.assertEqual((a.ratios, a.weights), (b.ratios, b.weights))
        self.assertEqual(ps[1].weights[netlib.REWARD_TERMS.index("holes")], -1.0)
        for p in ps[8:]:
            self.assertEqual(p.ratios, (0.0,) * 4)
            self.assertEqual(p.weights, (0.0,) * len(netlib.REWARD_TERMS))
        self.assertEqual(pl.weight_matrix(ps)[8:].abs().sum().item(), 0)
        # 섞은 AI 는 중립 뒤에 오고, 비율은 복제·중립 수와 상관없다
        mixed = pl.make_personas(SIMPLE, 3, 1.0, 5, replicas=2, n_neutral=1)
        self.assertEqual([p.name for p in mixed[8:]], ["neutral", "mix01", "mix02", "mix03"])
        self.assertEqual([p.ratios for p in mixed[9:]], [p.ratios for p in pl.make_personas(SIMPLE, 3, 1.0, 5)[4:]])
        self.assertEqual([p.name for p in pl.make_personas(SIMPLE, 0, 1.0, 0, replicas=3)][8:], ["attacker_c", "defender_c",
                                                                                                "tspin_c", "combo_c"])
        with self.assertRaises(ValueError):
            pl.make_personas(SIMPLE, 0, 1.0, 0, replicas=0)

    @staticmethod
    def old_table(archetypes: dict, n_mix: int, alpha: float, seed: int) -> list[dict]:
        """p04 까지의 make_personas 를 그대로 옮긴 것(보상 항목 10 개). 옛 personas.json 의 모양이다."""
        terms = netlib.REWARD_TERMS[:10]
        table = np.array([[float(archetypes[a].get(t, 0.0)) for t in terms] for a in pl.ARCHETYPE_NAMES])
        ratios = np.concatenate([np.eye(4), pl.mixture_ratios(n_mix, alpha, seed)])
        names = [*pl.ARCHETYPE_NAMES, *(f"mix{i + 1:02d}" for i in range(n_mix))]
        return [{"name": n, "label": pl.ARCHETYPE_NAMES[int(np.argmax(r))], "ratios": [float(x) for x in r],
                 "weights": dict(zip(terms, (float(x) for x in r @ table)))} for n, r in zip(names, ratios)]

    def test_default_table_matches_old_table(self) -> None:
        # 기본값(복제 1, 중립 0)이면 p04 까지의 표와 같다. 옛 표(항목 10 개)와 비교해도 같다고 본다.
        old = self.old_table(netlib.ARCHETYPES, 8, 1.0, 0)
        ps = pl.make_personas(netlib.ARCHETYPES, 8, 1.0, 0)
        new = [p.to_json() for p in ps]
        self.assertEqual([p["name"] for p in new], [p["name"] for p in old])
        self.assertTrue(pl.same_table(old, new))
        self.assertTrue(pl.same_table(new, old))
        self.assertTrue(all(p.weights[10:] == (0.0, 0.0) for p in ps))
        for p, q in zip(new, old):  # 옛 항목의 값은 비트까지 같다
            self.assertEqual({k: p["weights"][k] for k in q["weights"]}, q["weights"])
            self.assertEqual(p["ratios"], q["ratios"])
        # 하나라도 다르면 다른 표다
        changes = [
            lambda t: t[5]["weights"].__setitem__("danger", t[5]["weights"]["danger"] + 1e-6),
            lambda t: t[0]["weights"].__setitem__("single", -0.06),  # 옛 표에 없는 항목이 0 이 아님
            lambda t: t[2].__setitem__("name", "tspin_b"),
            lambda t: t[4].__setitem__("label", "attacker"),
            lambda t: t[7]["ratios"].__setitem__(1, t[7]["ratios"][1] + 1e-6),
            lambda t: t.pop(),
        ]
        for change in changes:
            t = copy.deepcopy(new)
            change(t)
            self.assertFalse(pl.same_table(old, t))
            self.assertFalse(pl.same_table(t, old))

    @unittest.skipUnless((ROOT / "runs" / "p04" / "personas.json").exists(), "runs/p04 가 없음 (git 제외)")
    def test_p04_run_table_still_matches(self) -> None:
        # 실제 p04 실행: 설정으로 다시 만든 표가 personas.json 과 체크포인트의 표와 같다 (이어 하기·분석이 그대로 된다)
        run = ROOT / "runs" / "p04"
        cfg = json.loads((run / "config.json").read_text(encoding="utf-8"))
        self.assertNotIn("replicas", cfg)  # 옛 설정에는 새 키가 없다
        table = [q.to_json() for q in pl.personas_from_config(cfg)]
        self.assertEqual(len(table), 12)
        self.assertTrue(pl.same_table(json.loads((run / "personas.json").read_text(encoding="utf-8"))["personas"], table))
        ckpt_path = run / "checkpoints" / "gen-010.pt"
        if ckpt_path.exists():
            self.assertTrue(pl.same_table(torch.load(ckpt_path, map_location="cpu")["personas"], table))

    def test_unknown_term_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            pl.archetype_matrix({**SIMPLE, "combo": {"stall": 1.0}})


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class ConfigTest(unittest.TestCase):
    def test_merge_config(self) -> None:
        base = pl.default_config()
        self.assertEqual(base["archetypes"]["tspin"], netlib.ARCHETYPES["tspin"])
        cfg, ignored = pl.merge_config(base, {"lr": 1, "matches": 256.0, "shaping": 0.005, "persona": "neutral"})
        self.assertEqual((cfg["lr"], type(cfg["lr"])), (1.0, float))
        self.assertEqual((cfg["matches"], type(cfg["matches"])), (256, int))
        self.assertEqual(ignored, ["persona", "shaping"])  # 단계 A 설정에만 있는 키는 경고용으로 돌려준다
        self.assertEqual(base["lr"], 1e-4)  # 원본은 그대로
        # 실행 관리·평가 크기 키는 이름이 같아도 설정 파일로 바꾸지 않는다 (단계 A 의 eval_seeds 는 AI 하나의 시드 수)
        cfg, ignored = pl.merge_config(base, {"threads": 6, "eval_seeds": 100, "prior_weight": 0.01, "lr_final": 2e-5})
        self.assertEqual((cfg["threads"], cfg["eval_seeds"]), (base["threads"], base["eval_seeds"]))
        self.assertEqual((cfg["prior_weight"], cfg["lr_final"]), (0.01, 2e-5))
        self.assertEqual(ignored, ["eval_seeds", "threads"])

    def test_p05_keys(self) -> None:
        base = pl.default_config()
        self.assertEqual((base["replicas"], base["n_neutral"], base["center_style"]), (1, 0, 0))  # 기본은 p04 와 같다
        cfg, ignored = pl.merge_config(base, {"replicas": 2.0, "n_neutral": 2, "center_style": 1, "n_mix": 0,
                                              "archetypes": {**SIMPLE, "attacker": {"single": -0.06, "tetris": 0.2}}})
        self.assertEqual(ignored, [])
        self.assertEqual([(cfg[k], type(cfg[k])) for k in ("replicas", "n_neutral", "center_style", "n_mix")],
                         [(2, int), (2, int), (1, int), (0, int)])
        names = [q.name for q in pl.personas_from_config(cfg)]
        self.assertEqual(len(names), 10)
        self.assertEqual(names[-2:], ["neutral", "neutral_b"])
        old = {k: v for k, v in base.items() if k not in ("replicas", "n_neutral", "center_style")}  # p04 의 config.json
        self.assertEqual(len(pl.personas_from_config(old)), 12)

    def test_p05_config_files(self) -> None:
        # 파일럿 설정 두 개: 쓰지 않는 키는 설명(_note)뿐이고, 둘은 온도만 다르다.
        # 규칙 파일이 가리키는 runs/p05-design/configs 가 없으면(새로 받은 저장소) python/configs 의 사본을 읽는다
        spec = json.loads((ROOT / select.DEFAULT_SPEC).read_text(encoding="utf-8"))
        cfgs = {}
        for name in ("p05a", "p05b"):
            raw = json.loads((ROOT / select.config_path(spec["pilots"][name]["config"])).read_text(encoding="utf-8"))
            cfgs[name], ignored = pl.merge_config(pl.default_config(), raw)
            self.assertEqual(ignored, ["_note"])
            ps = pl.personas_from_config(cfgs[name])
            self.assertEqual([p.label for p in ps], [*pl.ARCHETYPE_NAMES] * 2 + [pl.NEUTRAL] * 2)
        self.assertEqual((cfgs["p05a"]["temperature"], cfgs["p05b"]["temperature"]), (0.3, 0.2))
        self.assertEqual({k for k in cfgs["p05a"] if cfgs["p05a"][k] != cfgs["p05b"][k]}, {"temperature"})

    def test_p05_config_copies(self) -> None:
        # runs/ 에 원본이 있으면 그것을, 없으면 python/configs 의 같은 이름 사본을, 둘 다 없으면 받은 경로를 그대로 쓴다
        rel = "runs/p05-design/configs/p05a.json"
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            self.assertEqual(select.config_path(rel, root), rel)
            (root / "python" / "configs").mkdir(parents=True)
            (root / "python" / "configs" / "p05a.json").write_text("{}", encoding="utf-8")
            self.assertEqual(select.config_path(rel, root), "python/configs/p05a.json")
            (root / "runs" / "p05-design" / "configs").mkdir(parents=True)
            (root / "runs" / "p05-design" / "configs" / "p05a.json").write_text("{}", encoding="utf-8")
            self.assertEqual(select.config_path(rel, root), rel)
        # 사본(파일럿 둘과 FiLM 비상안)은 원본과 같은 바이트다. 원본은 runs/ 가 있는 PC 에서만 견준다
        for name in ("p05a", "p05b", "p05f"):
            copied = ROOT / "python" / "configs" / f"{name}.json"
            self.assertTrue(copied.exists(), name)
            original = ROOT / "runs" / "p05-design" / "configs" / f"{name}.json"
            if original.exists():
                self.assertEqual(copied.read_bytes(), original.read_bytes(), name)

    def test_learning_rate_schedule(self) -> None:
        # 1e-4 에서 2e-5 까지 20 세대: 세대 1 = 1e-4, 세대 11 = 중간 6e-5, 세대 21 부터 2e-5
        cfg = {"lr": 1e-4, "lr_final": 2e-5, "lr_decay_gens": 20}
        self.assertAlmostEqual(pl.learning_rate(cfg, 1), 1e-4)
        self.assertAlmostEqual(pl.learning_rate(cfg, 11), 6e-5)
        self.assertAlmostEqual(pl.learning_rate(cfg, 21), 2e-5)
        self.assertAlmostEqual(pl.learning_rate(cfg, 40), 2e-5)
        self.assertEqual(pl.learning_rate({"lr": 3e-4}, 7), 3e-4)  # 일정이 없는 옛 설정(p01, p02)은 고정


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class PerAiRewardTest(unittest.TestCase):
    def test_each_sample_uses_its_ai_weights(self) -> None:
        ps = pl.make_personas(netlib.ARCHETYPES, 8, 1.0, 0)
        idx = {p.name: i for i, p in enumerate(ps)}
        tall = np.zeros((24, 10), np.uint8)
        tall[6, 0] = 1  # 0 열: 6 행이 맨 위 → 높이 18, 그 아래는 구멍. 최고 높이 18
        rising = features()
        rising[PENDING], rising[GARBAGE_RISES] = 5, 1  # 줄을 못 지워 대기 가비지 5 줄이 모두 올라온다
        tetris = features(lines=4, attack=4, b2b=True, combo=0)
        samples = [  # (AI, 보드, 특징, 손 계산한 보상)
            ("attacker", None, tetris, 4 * 0.004 + 0.02),  # 공격 4 줄 + 테트리스 = 0.036
            ("defender", None, tetris, 0.0005),  # 안전한 블록은 생존 보상만
            ("defender", tall, rising, 0.0005 - 0.004 * 5 - 0.01 * (18 - 12) / 12),  # 생존 − 상승 5 줄 − 위험 0.5 = −0.0245
            ("tspin", None, features(lines=2, attack=4, b2b=True, combo=0), 0.05),  # TSD
            ("combo", None, features(lines=1, attack=2, combo=1), 0.01),  # 연속 두 번째 클리어
            ("mix01", None, tetris, ps[idx["mix01"]].ratios[0] * 0.036 + ps[idx["mix01"]].ratios[1] * 0.0005),  # 비율만큼
        ]
        m = len(samples)
        ds = Dataset(
            boards=np.stack([np.zeros((24, 10), np.uint8) if b is None else b for _, b, _, _ in samples]),
            features=np.stack([f for _, _, f, _ in samples]), opp_boards=np.zeros((m, 24, 10), np.uint8),
            opp_features=np.zeros((m, OPP_FEATURES), np.float32),
            ai=np.array([idx[a] for a, _, _, _ in samples], np.int64),
            starts=np.array([0]), lengths=np.array([m]), outcomes=np.array([1.0], np.float32))
        got = rewards(ds, pl.weight_matrix(ps), torch.device("cpu"))
        np.testing.assert_allclose(got, [r for _, _, _, r in samples], atol=1e-7)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class StyleCenteringTest(unittest.TestCase):
    """p05 의 center_style: AI 마다 즉시 보상의 블록당 평균을 빼고 λ-리턴을 만든다 (train_personas.center_rewards)."""

    def test_hand_example(self) -> None:
        # AI 0 의 판은 표본 0~2 다. 첫 표본(9)은 목표에 쓰이지 않으므로 평균은 (1 + 3) / 2 = 2. AI 1 의 판은 표본 3~4 → 4
        rews = np.array([9, 1, 3, 5, 4], np.float32)
        centers = tp.center_rewards(rews, np.array([0, 0, 0, 1, 1]), np.array([0, 3]), 2)
        np.testing.assert_allclose(centers, [2.0, 4.0])
        np.testing.assert_allclose(rews, [7, -1, 1, 1, 0])  # 그 자리에서 고친다
        self.assertEqual(rews.dtype, np.float32)
        # 표본이 없는 AI(0, 2)는 0 을 뺀다
        rews = np.array([1.0, 2.0, 6.0], np.float32)
        np.testing.assert_allclose(tp.center_rewards(rews, np.array([1, 1, 1]), np.array([0]), 3), [0.0, 4.0, 0.0])
        np.testing.assert_allclose(rews, [-3.0, -2.0, 2.0])

    def test_constant_reward_becomes_outcome_only(self) -> None:
        # 블록마다 같은 보상(AI 0 은 +0.02, AI 1 은 −0.03)을 받으면, 중심을 뺀 λ-리턴은 승패만의 λ-리턴과 같다.
        # 빼지 않으면 판이 길수록 쌓이는 몫이 남는다.
        lengths = np.array([5, 9, 3])
        starts = np.concatenate([[0], np.cumsum(lengths)[:-1]])
        ai = np.repeat([0, 1, 0], lengths)
        outcomes = np.array([1.0, -1.0, 1.0], np.float32)
        values = np.random.default_rng(0).normal(0, 0.3, lengths.sum()).astype(np.float32)
        rews = np.where(ai == 0, 0.02, -0.03).astype(np.float32)
        raw = lambda_returns(values, rews, starts, lengths, outcomes, 1.0, 0.8)
        want = lambda_returns(values, np.zeros_like(rews), starts, lengths, outcomes, 1.0, 0.8)
        np.testing.assert_allclose(tp.center_rewards(rews, ai, starts, 2), [0.02, -0.03], rtol=1e-6)
        np.testing.assert_allclose(lambda_returns(values, rews, starts, lengths, outcomes, 1.0, 0.8), want, atol=1e-6)
        self.assertGreater(np.abs(raw - want).max(), 0.05)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class ProbeToolTest(unittest.TestCase):
    """결정 단위 지표 도구(tools/persona_probe.py)의 부품."""

    def test_choices_ties_and_padding(self) -> None:
        # 결정 0: 후보 0~2 (점수 1, 3, 3 → 같으면 앞 후보 1), 결정 1: 후보 3 하나(나머지 칸은 패딩 −1), 결정 2: 후보 4, 5
        scores = torch.tensor([1.0, 3.0, 3.0, -2.0, -5.0, -7.0])
        pad = torch.tensor([[0, 1, 2], [3, -1, -1], [4, 5, -1]])
        self.assertEqual(probe.choices(scores, pad).tolist(), [1, 3, 4])

    def test_weight_vector(self) -> None:
        w = probe.weight_vector({"attack": 0.5, "single": -0.06})  # 없는 항목(옛 표의 새 항목 등)은 0
        self.assertEqual(tuple(w.shape), (len(netlib.REWARD_TERMS),))
        self.assertAlmostEqual(w[netlib.REWARD_TERMS.index("single")].item(), -0.06, places=6)
        self.assertEqual(w[netlib.REWARD_TERMS.index("combo_len")].item(), 0.0)
        with self.assertRaises(SystemExit):
            probe.weight_vector({"stall": 1.0})


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class SignatureToolTest(unittest.TestCase):
    """미리 정한 서명 확인 도구(tools/persona_signatures.py)의 부품."""

    def test_paired_test(self) -> None:
        parts = np.stack([sig.game_parts(t) for t in AnalysisTest.random_trajectories(30, seed=8)])
        rel, p = sig.paired_test(parts, parts.copy(), "app", 1.0, 500, seed=0)
        self.assertEqual(rel, 0.0)
        self.assertGreater(p, 0.99)  # 섞어도 언제나 0 ≥ 0 → p = 1
        more = parts.copy()
        more[:, sig.IDX["attack"]] += 3  # 모든 판에서 공격 3 줄 더
        rel, p = sig.paired_test(more, parts, "app", 1.0, 500, seed=0)
        self.assertGreater(rel, 0.0)
        self.assertLess(p, 0.01)  # (0 + 1) / 501
        self.assertGreater(sig.paired_test(more, parts, "app", -1.0, 500, seed=0)[1], 0.99)  # 반대 방향으로 물으면 p 가 크다

    def test_metric_nan_on_zero_denominator(self) -> None:
        s = np.zeros(len(sig.PARTS))
        self.assertTrue(np.isnan(sig.metric(s, "app")))
        s[sig.IDX["pieces"]], s[sig.IDX["attack"]], s[sig.IDX["lines"]], s[sig.IDX["clears"]] = 4, 2, 6, 3
        self.assertAlmostEqual(float(sig.metric(s, "app")), 0.5)
        self.assertAlmostEqual(float(sig.metric(s, "lines_per_clear")), 2.0)
        np.testing.assert_allclose(sig.metric(np.stack([s, np.zeros(len(sig.PARTS))]), "app"), [0.5, np.nan])

    def test_game_parts_match_persona_metrics(self) -> None:
        # 서명 도구의 판별 합이 학습·분석의 지표(persona_metrics)와 같은 정의다
        trajs = AnalysisTest.random_trajectories(20, seed=3)
        s = np.stack([sig.game_parts(t) for t in trajs]).sum(axis=0)
        m = pl.persona_metrics(trajs)
        pairs = [("win_rate", "win_rate"), ("turns_mean", "turns_mean"), ("app", "app"), ("sent_pp", "sent_per_piece"),
                 ("tetris_rate", "tetris_rate"), ("tspin_rate", "tspin_rate"), ("combo_rate", "combo_rate"),
                 ("combo_len", "combo_len"), ("max_height", "max_height_mean"), ("holes", "holes_mean"),
                 ("danger_rate", "danger_rate"), ("risen_pp", "risen_per_piece")]
        for mine, theirs in pairs:
            self.assertTrue(np.isclose(sig.metric(s, mine), m[theirs], rtol=1e-6, equal_nan=True), mine)
        feats = np.concatenate([t.features for t in trajs])
        lines = feats[:, LINES]
        self.assertAlmostEqual(float(sig.metric(s, "single_rate")), float((lines == 1).mean()), places=12)
        self.assertAlmostEqual(float(sig.metric(s, "lines_per_clear")), float(lines.sum() / (lines > 0).sum()), places=12)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class SelectionRuleTest(unittest.TestCase):
    """파일럿 고르기 규칙(tools/persona_select.py, python/configs/p05_selection.json)."""

    spec = {"gen": 10, "prefer": "p05a", "tie_ratio": 1.2,
            "decision": {"labels": ["attacker", "defender", "combo"], "vs_reference_min": 0.06, "noise_ratio_min": 2.0},
            "strength": {"min": 0.40, "max": 0.60}, "evaluation": {"floor": 0.85, "consecutive": 2}}

    @staticmethod
    def pilot(vs: tuple, within: tuple = (0.02, 0.02, 0.02), wins: dict | None = None,
              evals: tuple = (0.9, 0.9)) -> dict:
        labels = ("attacker", "defender", "combo")
        return {"probe": {"reference": "neutral",
                          "by_label": {lab: {"vs_reference": v, "within_label": w} for lab, v, w in zip(labels, vs, within)}},
                "wins": wins or {"attacker": 0.5, "defender": 0.45, "tspin": 0.5, "combo": 0.55, "neutral": 0.52},
                "evals": list(evals)}

    def judge(self, **pilots) -> dict:
        return select.choose({name: select.judge(p["probe"], p["wins"], p["evals"], self.spec)
                              for name, p in pilots.items()}, self.spec)

    def test_larger_margin_wins(self) -> None:
        # 점수 = min(중립과 다른 결정 − 복제 차이): p05a 0.08 − 0.02 = 0.06, p05b 0.12 − 0.02 = 0.10 → 1.67 배 → p05b
        out = self.judge(p05a=self.pilot((0.08, 0.10, 0.09)), p05b=self.pilot((0.12, 0.13, 0.15)))
        self.assertEqual(out["winner"], "p05b")
        self.assertAlmostEqual(out["scores"]["p05a"], 0.06)

    def test_close_scores_prefer_p04_temperature(self) -> None:
        out = self.judge(p05a=self.pilot((0.08, 0.10, 0.09)), p05b=self.pilot((0.09, 0.10, 0.09)))  # 0.06 대 0.07
        self.assertEqual(out["winner"], "p05a")

    def test_failures(self) -> None:
        weak = {"attacker": 0.5, "defender": 0.38, "tspin": 0.5, "combo": 0.55, "neutral": 0.52}
        strong = {"attacker": 0.5, "defender": 0.45, "tspin": 0.5, "combo": 0.63, "neutral": 0.52}
        cases = {
            "decision_min": self.pilot((0.05, 0.10, 0.09)),  # 공격 5% < 6%
            "decision_noise": self.pilot((0.08, 0.10, 0.09), within=(0.05, 0.02, 0.02)),  # 0.08 < 2 × 0.05
            "strength_min": self.pilot((0.08, 0.10, 0.09), wins=weak),
            "evaluation": self.pilot((0.08, 0.10, 0.09), evals=(0.84, 0.83)),
        }
        for why, bad in cases.items():
            out = self.judge(p05a=bad, p05b=self.pilot((0.07, 0.10, 0.09)))
            self.assertEqual(out["winner"], "p05b", why)
            self.assertFalse(out["pilots"]["p05a"]["eligible"], why)
        out = self.judge(p05a=cases["strength_min"], p05b=cases["decision_min"])
        self.assertIsNone(out["winner"])
        self.assertTrue(out["fallback"])
        # 세기 위쪽(0.60 초과)은 표시만 하고 막지 않는다. 평가 승률은 한 번만 낮으면 괜찮다
        out = self.judge(p05a=self.pilot((0.08, 0.10, 0.09), wins=strong, evals=(0.84, 0.9)))
        self.assertEqual(out["winner"], "p05a")
        self.assertTrue(out["pilots"]["p05a"]["strength_above_band"])

    @staticmethod
    def signatures(passed: tuple, wins: dict) -> dict:
        """persona_signatures 결과의 모양 (세대 고르기에 쓰는 칸만). wins 는 {라벨: (첫 AI, 합친 승률)}."""
        designs = {lab: {"pass": lab in passed,
                         "strength": {"win_rate_first": wins[lab][0], "win_rate_pooled": wins[lab][1]}}
                   for lab in ("attacker", "defender", "tspin", "combo")}
        return {"reference": "neutral", "designs": designs,
                "reference_strength": {"win_rate_first": wins["neutral"][0], "win_rate_pooled": wins["neutral"][1]}}

    def test_generation_choice(self) -> None:
        fine = {lab: (0.5, 0.5) for lab in ("attacker", "defender", "tspin", "combo", "neutral")}
        weak = {**fine, "defender": (0.45, 0.39)}  # 합친 승률이 띠 밑
        probe_gen = lambda v: {"by_label": {lab: {"vs_reference": v, "within_label": 0.02}  # noqa: E731
                                           for lab in ("attacker", "defender", "combo")}}
        rows = {
            10: select.judge_generation(self.signatures(("tspin", "combo"), fine), probe_gen(0.09), self.spec),
            15: select.judge_generation(self.signatures(("tspin", "combo", "attacker"), weak), probe_gen(0.12), self.spec),
            20: select.judge_generation(self.signatures(("tspin", "combo", "attacker"), fine), probe_gen(0.10), self.spec),
            25: select.judge_generation(self.signatures(("tspin", "combo", "attacker"), fine), probe_gen(0.10), self.spec),
            30: select.judge_generation(self.signatures(("tspin", "combo", "defender"), fine), None, self.spec),
        }
        self.assertFalse(rows[15]["strength_ok"])
        self.assertAlmostEqual(rows[20]["decision_score"], 0.08)
        # 15 는 세기 밖, 20·25·30 은 통과 3 개로 같고, 결정 점수가 없는 30 이 밀리고, 20·25 는 같아 이른 20
        self.assertEqual(select.choose_generation(rows), 20)
        self.assertIsNone(select.choose_generation({15: rows[15]}))


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class LeaguePlanTest(unittest.TestCase):
    names = [f"a{i}" for i in range(12)]

    def plan(self, gen: int, matches: int = 48, seed: int = 0) -> dict:
        cfg = {**pl.DEFAULTS, "matches": matches, "seed": seed}
        return pl.league_plan(self.names, cfg, "t", gen, "../models")

    def test_first_generation(self) -> None:
        plan = self.plan(1)
        self.assertEqual(len(plan["matches"]), 48)
        self.assertEqual(plan["players"]["a3"]["model"], "../models/value_a3_gen-000.onnx")
        seats = Counter()
        for i, m in enumerate(plan["matches"]):
            me = self.names[i % 12]  # 판 i 는 i mod 12 번째 AI 의 판
            self.assertIn(me, (m["p1"], m["p2"]))
            self.assertNotEqual(m["p1"], m["p2"])  # 자기 자신과는 두지 않는다
            self.assertEqual(m["seed"], 1_000_000 + i)
            self.assertNotIn("@gen-", m["p1"] + m["p2"])  # 세대 1 에는 이전 세대가 없다
            seats[me, "p1" if m["p1"] == me else "p2"] += 1
        for name in self.names:  # AI 마다 4 판: i div 12 = 0, 1, 2, 3 → P1, P2, P1, P2
            self.assertEqual((seats[name, "p1"], seats[name, "p2"]), (2, 2))

    def test_past_generations_and_players(self) -> None:
        plan = self.plan(7, matches=600)
        used = {n for m in plan["matches"] for n in (m["p1"], m["p2"])}
        self.assertEqual(set(plan["players"]), used | set(self.names) | {pl.BASELINE})
        past = sorted({n for n in used if "@gen-" in n})
        self.assertTrue(past)
        for name in past:  # 세대 7 의 이전 세대 상대는 세대 1~5 (지금 세대 모델은 6)
            ai, g = name.split("@gen-")
            self.assertIn(int(g), range(1, 6))
            self.assertEqual(plan["players"][name]["model"], f"../models/value_{ai}_gen-{g}.onnx")
        self.assertEqual(plan["players"]["a0"]["model"], "../models/value_a0_gen-006.onnx")
        self.assertEqual(plan["threads"], pl.DEFAULTS["threads"])

    def test_pool_fractions_and_determinism(self) -> None:
        plan = self.plan(9, matches=6000)
        kinds = Counter()
        for i, m in enumerate(plan["matches"]):
            me = self.names[i % 12]
            opp = m["p2"] if m["p1"] == me else m["p1"]
            kinds["past" if "@gen-" in opp else "baseline" if opp == pl.BASELINE else "self"] += 1
        self.assertAlmostEqual(kinds["self"] / 6000, 0.5, delta=0.03)
        self.assertAlmostEqual(kinds["past"] / 6000, 0.3, delta=0.03)
        self.assertAlmostEqual(kinds["baseline"] / 6000, 0.2, delta=0.03)
        self.assertEqual(self.plan(9, matches=60), self.plan(9, matches=60))
        self.assertNotEqual(self.plan(9, matches=60)["matches"], self.plan(9, matches=60, seed=1)["matches"])

    def test_fixed_opponent_plan(self) -> None:
        plan = pl.fixed_opponent_plan({"x": "mx.onnx", "y": "my.onnx"}, "ref", "ref.onnx", range(5, 8), "t", 3, 1000, 3)
        self.assertEqual(len(plan["matches"]), 2 * 3 * 2)
        self.assertEqual(plan["matches"][:2], [{"seed": 5, "p1": "x", "p2": "ref"}, {"seed": 5, "p1": "ref", "p2": "x"}])
        self.assertTrue(all(p["temperature"] == 0.0 for p in plan["players"].values()))
        with self.assertRaises(ValueError):
            pl.fixed_opponent_plan({"ref": "a.onnx"}, "ref", "ref.onnx", [1], "t", 0, 1000, 3)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class StageAStartTest(unittest.TestCase):
    def stage_a(self) -> dict:
        torch.manual_seed(0)
        net = netlib.ValueNet(n_ai=1)
        with torch.no_grad():
            torch.nn.init.normal_(net.head[-1].weight, std=0.1)
            net.embedding.weight.copy_(torch.arange(8, dtype=torch.float32) / 10)
        opt = torch.optim.AdamW(net.parameters(), lr=3e-4, weight_decay=1e-4)
        x = self.inputs(4)
        net(*x, torch.zeros(4, dtype=torch.long)).sum().backward()
        opt.step()  # 최적화기 상태(모멘트, step)를 만든다
        return {"net": net.state_dict(), "optimizer": opt.state_dict(), "config": net.config}

    @staticmethod
    def inputs(n: int) -> tuple:
        rng = np.random.default_rng(1)
        return (torch.from_numpy((rng.random((n, 24, 10)) < 0.3).astype(np.float32)),
                torch.from_numpy(rng.integers(0, 3, (n, FEATURES)).astype(np.float32)),
                torch.from_numpy((rng.random((n, 24, 10)) < 0.3).astype(np.float32)),
                torch.from_numpy(rng.integers(0, 3, (n, OPP_FEATURES)).astype(np.float32)))

    def test_embedding_rows_are_source_plus_noise(self) -> None:
        ckpt = self.stage_a()
        net = pl.net_from_stage_a(ckpt, 12, 0, 0.01, seed=3)
        emb = net.embedding.weight.detach()
        self.assertEqual(tuple(emb.shape), (12, 8))
        noise = emb - ckpt["net"]["embedding.weight"][0]
        self.assertLess(noise.abs().max().item(), 0.05)
        self.assertGreater(noise.std().item(), 0.005)
        torch.testing.assert_close(emb, pl.net_from_stage_a(ckpt, 12, 0, 0.01, seed=3).embedding.weight.detach())
        self.assertFalse(torch.equal(emb, pl.net_from_stage_a(ckpt, 12, 0, 0.01, seed=4).embedding.weight.detach()))
        for k, v in net.state_dict().items():  # 임베딩 말고는 단계 A 와 같다
            if k != "embedding.weight":
                torch.testing.assert_close(v, ckpt["net"][k], rtol=0, atol=0)

    def test_without_noise_every_ai_is_stage_a(self) -> None:
        ckpt = self.stage_a()
        old = netlib.ValueNet(n_ai=1)
        old.load_state_dict(ckpt["net"])
        new = pl.net_from_stage_a(ckpt, 12, 0, 0.0, seed=0)
        x = self.inputs(5)
        with torch.no_grad():
            want = old(*x, torch.zeros(5, dtype=torch.long))
            for ai in (0, 7, 11):
                torch.testing.assert_close(new(*x, torch.full((5,), ai, dtype=torch.long)), want)

    def test_optimizer_state_is_transferred(self) -> None:
        ckpt = self.stage_a()
        net = pl.net_from_stage_a(ckpt, 12, 0, 0.01, seed=0)
        opt = pl.make_optimizer(net, 3e-4, 1e-4, 10.0)
        np.testing.assert_allclose([g["lr"] for g in opt.param_groups], [3e-4, 3e-3])  # 임베딩만 10 배
        pl.transfer_optimizer_state(opt, net, ckpt["optimizer"], 12, 0)
        old = ckpt["optimizer"]["state"]
        names = [n for n, _ in net.named_parameters()]
        emb_state = opt.state[net.embedding.weight]
        old_emb = old[names.index("embedding.weight")]
        self.assertEqual(tuple(emb_state["exp_avg"].shape), (12, 8))
        for row in emb_state["exp_avg_sq"]:
            torch.testing.assert_close(row, old_emb["exp_avg_sq"][0])
        torch.testing.assert_close(opt.state[net.head[0].weight]["exp_avg"], old[names.index("head.0.weight")]["exp_avg"])
        self.assertEqual(emb_state["step"].item(), 1.0)
        self.assertEqual(emb_state["step"].device.type, "cpu")
        net(*self.inputs(3), torch.tensor([0, 5, 11])).sum().backward()
        opt.step()  # 옮긴 상태로 학습이 이어진다
        self.assertEqual(opt.state[net.embedding.weight]["step"].item(), 2.0)


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class PersonaExportTest(unittest.TestCase):
    def test_export_uses_that_ai_embedding_and_weights(self) -> None:
        torch.manual_seed(2)
        net = netlib.ValueNet(n_ai=3)
        with torch.no_grad():
            torch.nn.init.normal_(net.head[-1].weight, std=0.5)
            net.embedding.weight.copy_(torch.randn(3, 8))
        net.eval()
        weights = pl.weight_matrix(pl.make_personas(netlib.ARCHETYPES, 0, 1.0, 0)[:3])  # 공격, 수비, T-spin
        inputs = pl.synthetic_inputs(16, seed=4)
        inputs["features"][:, LINES], inputs["features"][:, ATTACK] = 2, 4
        inputs["features"][:, B2B] = 1  # 2 줄 + B2B → T-spin 보상이 0.05 씩 들어간다
        x = [torch.from_numpy(inputs[k]) for k in netlib.INPUT_NAMES]
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "value_tspin_gen-007.onnx"
            module = pl.export_ai(net, 2, weights[2], 1.0, 0.05, path, "tspin", "gen-007", "시험")
            self.assertLess(pl.verify_onnx(module, path, inputs), 1e-5)
            import onnxruntime as ort

            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            meta = session.get_modelmeta().custom_metadata_map
            self.assertEqual((meta["tetris.persona"], meta["tetris.stage"], meta["tetris.model_io"]), ("tspin", "gen-007", "2"))
            (got,) = session.run(["value"], inputs)
        with torch.no_grad():  # 손으로 조립한 점수: V(임베딩 2) + R(가중치 2) + β·H
            stats = netlib.board_stats(x[0])
            v = net.value(*x, net.embedding.weight[2].expand(16, -1))
            r = netlib.reward_terms(x[1], stats) @ weights[2].reshape(-1, 1)
            want = v + r + 0.05 * netlib.baseline_score(x[1], stats)
            other = netlib.ScoreModule(net, 0, weights[0], 1.0, 0.05)(*x)
        np.testing.assert_allclose(got, want.numpy(), atol=1e-5)
        np.testing.assert_allclose(r.numpy(), 0.05, atol=1e-7)
        self.assertGreater(np.abs(got - other.numpy()).max(), 1e-2)  # 다른 AI 의 임베딩·가중치와는 다르다


@unittest.skipIf(torch is None, "PyTorch 가 없음")
class PersonaMetricsTest(unittest.TestCase):
    def test_combo_metrics_hand_example(self) -> None:
        # 블록 6 개: 클리어(콤보 0) → 클리어(1) → 클리어(2) → 없음 → 클리어(0) → 없음
        # 줄 지운 블록 4 개, 연속 시작 2 번 → 평균 길이 2. 콤보 클리어(콤보 1 이상)는 2 개 → 비율 2/6
        feats = [features(1, 1, combo=0), features(1, 2, combo=1), features(2, 3, combo=2), features(),
                 features(1, 1, combo=0), features()]
        boards = np.zeros((6, 24, 10), np.uint8)
        boards[:, 23, 0] = 1  # 열 높이 1, 0, 0, … → 울퉁불퉁함 1
        boards[:, 22:24, 9] = 1  # 9 열 높이 2 → 8·9 열 사이 2 → 합 3
        t = trajectory(feats, boards)
        m = pl.persona_metrics([t])
        self.assertAlmostEqual(m["combo_len"], 2.0)
        self.assertAlmostEqual(m["combo_rate"], 2 / 6)
        self.assertAlmostEqual(m["bumpiness_mean"], 3.0)
        # 콤보 원형(콤보 클리어 0.01)의 한 판 스타일 보상 = 0.01 × 2
        self.assertAlmostEqual(pl.style_return([t], pl.weight_matrix(pl.make_personas(netlib.ARCHETYPES, 0, 1, 0))[3]),
                               0.02, places=6)

    def test_mean_pairwise_distance(self) -> None:
        self.assertAlmostEqual(pl.mean_pairwise_distance(np.array([[0.0, 0.0], [3.0, 4.0]])), 5.0)
        # 세 점 (0,0), (3,4), (0,4): 거리 5, 3, 4 → 평균 4
        self.assertAlmostEqual(pl.mean_pairwise_distance(np.array([[0.0, 0.0], [3.0, 4.0], [0.0, 4.0]])), 4.0)


@unittest.skipIf(pa is None or torch is None, "scikit-learn 또는 PyTorch 가 없음")
class AnalysisTest(unittest.TestCase):
    def blobs(self) -> tuple[np.ndarray, np.ndarray]:
        rng = np.random.default_rng(0)
        centers = np.array([[5.0] * 8, [-5.0] * 8, [5.0] * 4 + [-5.0] * 4])
        labels = np.repeat([0, 1, 2], 4)
        return centers[labels] + rng.normal(0, 0.1, (12, 8)), labels

    def test_scan_finds_three_clusters(self) -> None:
        x, labels = self.blobs()
        rows = pa.scan(x, range(2, 7))
        self.assertEqual(pa.best_k(rows), 3)
        three = rows[1]
        self.assertEqual(pa.compare(three["kmeans"], labels), {"ari": 1.0, "nmi": 1.0})
        self.assertEqual(pa.compare(three["gmm"], labels)["ari"], 1.0)
        self.assertEqual(pa.compare([0, 0, 1, 1], [5, 5, 2, 2]), {"ari": 1.0, "nmi": 1.0})  # 번호만 다른 같은 묶음

    def test_zscore_hand_example(self) -> None:
        # 열 [1, 2, 3]: 평균 2, 모표준편차 √(2/3) → ±1.2247. 상수 열은 0. nan 은 평균(2)으로 채워 0
        z = pa.zscore(np.array([[1.0, 7.0, 1.0], [2.0, 7.0, np.nan], [3.0, 7.0, 3.0]]))
        np.testing.assert_allclose(z[:, 0], [-1.224745, 0.0, 1.224745], atol=1e-6)
        np.testing.assert_allclose(z[:, 1], 0.0)
        self.assertAlmostEqual(z[1, 2], 0.0)

    def test_representatives_and_names(self) -> None:
        x = np.array([[0.0], [1.0], [2.0], [10.0], [11.0]])
        self.assertEqual(pa.representatives(x, np.array([0, 0, 0, 1, 1])), {0: 1, 1: 3})  # 1 군집은 같은 거리 → 앞 번호
        keys = list(pa.BEHAVIOR_KEYS)
        z = np.zeros((5, len(keys)))
        z[0, keys.index("tspin_rate")] = 2  # 군집 3: T-spin
        z[1, keys.index("app")] = z[1, keys.index("tetris_rate")] = 2  # 군집 2: 공격
        z[2, keys.index("risen_per_piece")] = z[2, keys.index("danger_rate")] = -2  # 군집 1: 수비
        z[3, keys.index("combo_rate")] = 2  # 군집 0: 콤보
        names, scores = pa.name_clusters(z, keys, np.array([3, 2, 1, 0, 4]))
        self.assertEqual(names, {3: "tspin", 2: "attacker", 1: "defender", 0: "combo", 4: "mixed1"})
        self.assertAlmostEqual(scores[1]["defender"], 2.0)

    def test_mantel_and_correlations(self) -> None:
        x, _ = self.blobs()
        d = pa.distance_matrix(x)
        self.assertAlmostEqual(d[0, 0], 0.0)
        same = pa.mantel(d, d, n_perm=200)
        self.assertAlmostEqual(same["r"], 1.0)
        self.assertLess(same["p"], 0.02)  # 순서를 섞으면 거의 언제나 상관이 1 보다 작다: p = (0 + 1) / 201 근처
        rng = np.random.default_rng(5)
        unrelated = pa.mantel(d, pa.distance_matrix(rng.normal(size=(12, 3))), n_perm=200)
        self.assertGreater(unrelated["p"], 0.02)
        c = pa.correlations(np.array([[0.0, 1.0], [1.0, 0.0], [2.0, 1.0]]), {"u": [0.0, 2.0, 4.0]})
        np.testing.assert_allclose(c["u"], [1.0, 0.0], atol=1e-12)  # 축 1 과 정비례, 축 2 와 무관

    @staticmethod
    def random_trajectories(n: int, seed: int) -> list:
        """판 n 개: 무작위 보드·특징·송신·스핀·홀드와 승패 (부트스트랩 합 시험용)."""
        rng = np.random.default_rng(seed)
        out = []
        for i in range(n):
            t = int(rng.integers(3, 12))
            feats = [features(int(rng.integers(0, 5)), int(rng.integers(0, 6)), bool(rng.integers(0, 2)),
                              int(rng.integers(-1, 4))) for _ in range(t)]
            tr = trajectory(feats, (rng.random((t, 24, 10)) < 0.2).astype(np.uint8), outcome=float(rng.integers(-1, 2)))
            tr.opp_boards = (rng.random((t, 24, 10)) < 0.4).astype(np.uint8)
            tr.pending_before = rng.integers(0, 8, t).astype(np.int16)
            tr.risen = rng.integers(0, 3, t).astype(np.int16)
            tr.sent = rng.integers(0, 4, t).astype(np.int16)
            tr.spin = rng.integers(0, 3, t).astype(np.int8)
            tr.hold_used = rng.random(t) < 0.4
            tr.turns = t + int(rng.integers(0, 3))
            out.append(tr)
        return out

    def test_parts_reproduce_persona_metrics(self) -> None:
        # 판별 합으로 계산한 지표가 persona_metrics(= metrics.summarize + 페르소나 지표)와 같아야 부트스트랩이 맞다
        trajs = self.random_trajectories(30, seed=7)
        want = pl.persona_metrics(trajs)
        got = pa.metrics_from_parts(np.stack([pa.trajectory_parts(t) for t in trajs]).sum(axis=0))
        self.assertEqual(set(got) - set(want), set())
        for key, value in got.items():
            self.assertTrue(np.isclose(value, want[key], rtol=1e-6, equal_nan=True), key)  # summarize 는 float32 로 평균한다

    def test_dose_response(self) -> None:
        # AI 12 개: T-spin 비율이 클수록 T-spin 비율도 크고(ρ = 1), 수비 비율이 클수록 올라온 가비지가 많다(방향 −1 이라 ρ = −1)
        r = np.linspace(0.0, 1.0, 12)
        ratios = {"attacker": np.zeros(12), "defender": r, "tspin": r, "combo": r[::-1]}
        values = {"app": np.ones(12), "tetris_rate": np.ones(12), "danger_rate": np.ones(12), "risen_per_piece": 3 * r,
                  "tspin_rate": r ** 2, "combo_rate": r}
        out = pa.dose_response(ratios, values, n_perm=200)
        self.assertAlmostEqual(out["tspin~tspin_rate"]["rho"], 1.0)
        self.assertLess(out["tspin~tspin_rate"]["p"], 0.02)
        self.assertAlmostEqual(out["defender~risen_per_piece"]["rho"], -1.0)
        self.assertAlmostEqual(out["combo~combo_rate"]["rho"], -1.0)
        self.assertGreater(out["defender~risen_per_piece"]["p"], 0.9)
        self.assertTrue(np.isnan(out["attacker~app"]["rho"]))  # 값이 모두 같으면 정의되지 않는다

    def test_embedding_trend(self) -> None:
        # 세대 1 의 임베딩 = 비율 그대로(거리 순위가 같다 → r = 1), 세대 0 = 무관한 잡음
        ratios = np.random.default_rng(0).dirichlet(np.ones(4), size=12)
        noise = np.random.default_rng(1).normal(size=(12, 8))
        rows = pa.embedding_trend({1: np.hstack([ratios, np.zeros((12, 4))]), 0: noise}, ratios, ["a", "b", "c", "d"],
                                  n_perm=200)
        self.assertEqual([r["gen"] for r in rows], [0, 1])
        self.assertAlmostEqual(rows[1]["mantel_r"], 1.0)
        self.assertLess(rows[1]["mantel_p"], 0.02)
        self.assertAlmostEqual(rows[1]["emb_spread"], pa.distance_matrix(ratios).sum() / (12 * 11))
        self.assertTrue(0.0 <= rows[0]["pc1_abs_r_a"] <= 1.0)

    def test_noise_helpers(self) -> None:
        self.assertAlmostEqual(pa.spread_over_noise([1.0, 3.0], [1.0, 1.0]), np.sqrt(2))  # 표준편차 √2 ÷ 1
        same = np.stack([pa.trajectory_parts(t) for t in self.random_trajectories(1, seed=3) * 10])  # 같은 판 10 개
        for value in pa.bootstrap_se(same, ["app", "win_rate"], n_boot=20).values():
            self.assertAlmostEqual(value, 0.0, places=12)  # 다시 뽑아도 지표가 같다
        parts = np.stack([pa.trajectory_parts(t) for t in self.random_trajectories(40, seed=4)])
        self.assertGreater(pa.bootstrap_se(parts, ["app"], n_boot=300)["app"], 0.0)

    def test_permutation_spread_test(self) -> None:
        base = np.stack([pa.trajectory_parts(t) for t in self.random_trajectories(30, seed=8)])  # 판 30 개
        same = np.stack([base] * 4)  # AI 4 개가 모든 판을 똑같이 두었다 → 차이 0, 섞어도 같으므로 p = 1
        r = pa.permutation_spread_test(same, ["app", "holes_mean"], n_perm=50)
        self.assertEqual((r["app"]["spread"], r["app"]["p"]), (0.0, 1.0))
        differ = same.copy()
        attack = pa.PARTS.index("attack")
        differ[0, :, attack] += 3  # AI 0 만 모든 판에서 공격 3 줄을 더 했다 → 섞으면 차이가 줄어 p 가 가장 작은 값
        r = pa.permutation_spread_test(differ, ["app", "holes_mean"], n_perm=200)
        self.assertGreater(r["app"]["spread"], 0.0)
        self.assertLess(r["app"]["p"], 0.02)  # (0 + 1) / 201
        self.assertEqual(r["holes_mean"]["p"], 1.0)  # 구멍은 그대로다


if __name__ == "__main__":
    unittest.main()
