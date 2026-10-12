"""승률 예측망 (docs/MODEL_IO.md 9장 winprob-1, TRAINING.md 10장, FIXTURES.md 5.11).

    python -m unittest discover -s python/tests -v

WinprobFixtures: fixtures/winprob 의 사례를 Python 인코더와 onnxruntime 으로 돌린다(Java FixtureTest 와 같은 사례).
나머지: 인코더·평가 지표의 손 계산, 판 단위 나누기, 기록 읽기, 모델의 자리 대칭과 ONNX 내보내기(PyTorch 가 있을 때).
"""

from __future__ import annotations

import gzip
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai import model_io  # noqa: E402
from tetris_ai import winprob as wp  # noqa: E402

FIXTURES = ROOT / "fixtures"


def board_from_text(rows) -> np.ndarray:
    return np.array([[1 if ch != "." else 0 for ch in row] for row in rows], dtype=np.uint8)


class WinprobFixtures(unittest.TestCase):
    def test_cases(self) -> None:
        import onnxruntime as ort

        files = sorted((FIXTURES / "winprob").glob("*.json"))
        self.assertEqual([p.name for p in files], ["winprob_linear.json", "winprob_tiny.json"])
        for path in files:
            doc = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(doc["rules_version"], model_io.RULES_VERSION)
            self.assertEqual(doc["type"], "winprob")
            session = ort.InferenceSession(str(path.parent / doc["model"]), providers=["CPUExecutionProvider"])
            for case in doc["cases"]:
                with self.subTest(file=path.name, id=case["id"]):
                    self.check_case(case, session)

    def check_case(self, case: dict, session) -> None:
        kind, expect = case["kind"], case["expect"]
        if kind == "encode":
            s = wp.state_from_json(case["state"])
            self.check_tensors(wp.encode_board(s), wp.encode_features(s), board_from_text(s.board), expect)
        elif kind == "encode_record":
            # 기대 보드는 expect 의 board_bottom (rows 를 푸는 방법이 Java 와 같은지)
            want = board_from_text(model_io.pad_board(expect.get("board_bottom", ()), expect.get("board_top", ())))
            board, feats = wp.encode_record(case["state"])
            self.check_tensors(board.astype(np.float32), feats, want, expect)
            s = wp.state_from_record(case["state"])  # 두 Python 경로가 같은 값
            self.check_tensors(wp.encode_board(s), wp.encode_features(s), want, expect)
        elif kind == "run":
            pairs = [(wp.state_from_json(p["p1"]), wp.state_from_json(p["p2"])) for p in case["pairs"]]
            (values,) = session.run([wp.OUTPUT_NAME], wp.encode_pairs(pairs))
            self.assertEqual(values.shape, (len(pairs), 1))
            np.testing.assert_allclose(values[:, 0], expect["values"], atol=expect["tolerance"], rtol=0)
        elif kind == "metadata":
            meta = session.get_modelmeta().custom_metadata_map
            for k, v in expect.items():
                self.assertEqual(meta.get(k), v, k)
        else:
            self.fail(f"모르는 kind: {kind}")

    def check_tensors(self, board: np.ndarray, features: np.ndarray, want_board: np.ndarray, expect: dict) -> None:
        # Java FixtureTest 처럼 보드는 언제나, features 와 board_filled 는 적힌 경우에 비교한다.
        if "features" in expect:
            np.testing.assert_array_equal(features, np.array(expect["features"], dtype=np.float32))
        np.testing.assert_array_equal(board, want_board.astype(np.float32))
        if "board_filled" in expect:
            self.assertEqual(int(board.sum()), expect["board_filled"])


class EncoderTest(unittest.TestCase):
    def test_hand_example(self) -> None:
        # Java WinRateTest.encodesFeaturesByHand 와 같은 상태.
        rows = ["." * 10] * 24
        rows[0] = "." * 9 + "#"
        rows[23] = "G" + "." * 9
        s = wp.PlayerState(board=tuple(rows), current="T", queue=("I", "O"), hold="Z", combo=3, b2b=True, pending=7)
        want = np.zeros(52, np.float32)
        want[5] = 1  # current T
        want[7 + 0] = 1  # 미리보기 0 칸 I
        want[7 + 7 + 3] = 1  # 미리보기 1 칸 O
        want[42 + 6] = 1  # hold Z
        want[49], want[50], want[51] = 4, 1, 7
        np.testing.assert_array_equal(wp.encode_features(s), want)
        board = wp.encode_board(s)
        self.assertEqual(int(board.sum()), 2)
        self.assertEqual((board[0, 9], board[23, 0]), (1.0, 1.0))

    def test_rejects_bad_states(self) -> None:
        with self.assertRaises(ValueError):
            wp.PlayerState(board=model_io.EMPTY_BOARD, current="X")
        with self.assertRaises(ValueError):
            wp.PlayerState(board=model_io.EMPTY_BOARD, current="T", queue=tuple("IJLOST"))
        with self.assertRaises(ValueError):
            wp.PlayerState(board=model_io.EMPTY_BOARD, current="T", combo=-2)

    def test_read_records_matches_encoder(self) -> None:
        # fixtures/match/basic.json 의 기록(Java 대전 러너가 씀)을 gz 로 써서 학습 자료 경로로 읽는다.
        doc = json.loads((FIXTURES / "match" / "basic.json").read_text(encoding="utf-8"))
        matches = [c["match"] for c in doc["cases"]]
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "w0.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as f:
                for m in matches:
                    f.write(json.dumps(m) + "\n")
            data = wp.read_records([path])
        self.assertEqual(len(data), sum(m["turns"] for m in matches))
        self.assertEqual(len(data.matches), len(matches))
        k = 0
        for mi, m in enumerate(matches):
            want_y = wp.RESULT_VALUE[m["result"]]
            for t, step in enumerate(m["steps"]):
                self.assertEqual((data.match[k], data.turn[k], data.y[k]), (mi, t, want_y))
                for side, (b, f) in (("p1", (data.b1, data.f1)), ("p2", (data.b2, data.f2))):
                    s = wp.state_from_record(step[side]["state"])
                    np.testing.assert_array_equal(b[k], wp.encode_board(s).astype(np.uint8))
                    np.testing.assert_array_equal(f[k], wp.encode_features(s))
                k += 1
        np.testing.assert_array_equal(data.turns, np.repeat([m["turns"] for m in matches], [m["turns"] for m in matches]))


class SplitTest(unittest.TestCase):
    def test_split_by_last_digit(self) -> None:
        self.assertEqual([wp.split_of(500_000_000 + i) for i in (0, 7, 8, 9, 10, 19)],
                         ["train", "train", "val", "test", "train", "test"])

    def test_no_match_in_two_splits(self) -> None:
        n = 30
        matches = [{"seed": 1000 + i, "turns": 2, "result": "p1", "players": []} for i in range(n)]
        z = np.zeros((2 * n, 24, 10), np.uint8)
        data = wp.Data(z, np.zeros((2 * n, 52), np.float32), z, np.zeros((2 * n, 52), np.float32),
                       np.ones(2 * n, np.float32), np.tile([0, 1], n).astype(np.int32),
                       np.repeat(np.arange(n), 2).astype(np.int32), matches)
        parts = {name: set(data.split(name).match.tolist()) for name in wp.SPLITS}
        self.assertEqual(sum(len(v) for v in parts.values()), n)
        self.assertEqual(set.union(*parts.values()), set(range(n)))
        self.assertEqual(parts["test"], {i for i in range(n) if (1000 + i) % 10 == 9})

    @staticmethod
    def tiny(seeds: list[int], turns: int, y: float) -> "wp.Data":
        n = len(seeds)
        m = n * turns
        z = np.zeros((m, 24, 10), np.uint8)
        matches = [{"seed": s, "turns": turns, "result": "p1", "players": []} for s in seeds]
        return wp.Data(z, np.zeros((m, 52), np.float32), z, np.zeros((m, 52), np.float32), np.full(m, y, np.float32),
                       np.tile(np.arange(turns), n).astype(np.int32), np.repeat(np.arange(n), turns).astype(np.int32),
                       matches)

    def test_timed_records_are_read_and_thinned_at_read_time(self) -> None:
        # timed-1 기록(TRAINING.md 10.9) 한 판: 상태 5개. 2개에 하나씩 읽으면 0, 2, 4 번째가 남고 턴 번호는 원래 순번이다.
        state = {"rows": [0] * 24, "current": "T", "queue": "IOJLS", "hold": None, "combo": -1, "b2b": False,
                 "pending": 0}
        rec = {"format": "timed-1", "rules": model_io.RULES_VERSION, "model_io": model_io.MODEL_IO_VERSION,
               "run": "t", "seed": 600000009, "result": "p2", "turns": 5,
               "players": [{"ai": "human", "model": "human", "temperature": 0}] * 2,
               "steps": [{"t": 10 * i, "p1": {"state": state}, "p2": {"state": state}} for i in range(5)]}
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "w0.jsonl.gz"
            with gzip.open(path, "wt", encoding="utf-8") as f:
                f.write(json.dumps(rec) + "\n")
            full = wp.read_records([path])
            thin = wp.read_records([path], timed_stride=2)
        self.assertEqual(full.turn.tolist(), [0, 1, 2, 3, 4])
        self.assertEqual(thin.turn.tolist(), [0, 2, 4])
        self.assertTrue((thin.y == 0.0).all(), "P2 승")
        self.assertEqual(wp.split_of(thin.matches[0]["seed"]), "test")

    def test_concat_shifts_match_numbers_and_thin_keeps_every_kth_turn(self) -> None:
        # 블록 동기 자료(판 2개 × 3턴)와 실시간 자료(판 3개 × 4번)를 잇는다. 뒤 자료의 판 번호는 2 부터.
        a = self.tiny([10, 11], 3, 1.0)
        b = self.tiny([29, 30, 39], 4, 0.0)
        both = wp.Data.concat([a, b])
        self.assertEqual(len(both), 6 + 12)
        self.assertEqual(len(both.matches), 5)
        self.assertEqual(both.match[6:].tolist(), [2] * 4 + [3] * 4 + [4] * 4)
        self.assertEqual([both.matches[i]["seed"] for i in set(both.split("test").match.tolist())], [29, 39])
        thin = b.thin(2)
        self.assertEqual(thin.turn.tolist(), [0, 2] * 3, "판마다 0, 2 번째만")
        self.assertIs(b.thin(1), b)


class MetricsTest(unittest.TestCase):
    def test_log_loss_and_brier_by_hand(self) -> None:
        # 표본 셋: (p, y) = (0.8, 1), (0.5, 0.5), (0.25, 0)
        # log loss = [−ln 0.8 + ln 2 − ln 0.75] / 3,  Brier = [0.04 + 0 + 0.0625] / 3
        p = np.array([0.8, 0.5, 0.25])
        y = np.array([1.0, 0.5, 0.0])
        self.assertAlmostEqual(wp.log_loss(p, y), (-math.log(0.8) + math.log(2) - math.log(0.75)) / 3, places=9)
        self.assertAlmostEqual(wp.brier(p, y), (0.04 + 0.0625) / 3, places=12)

    def test_reliability_and_ece_by_hand(self) -> None:
        # 구간 [0.1, 0.2): 예측 0.15 두 개, 실제 0 과 1 → 평균 예측 0.15, 실제 0.5, 차이 0.35
        # 구간 [0.9, 1.0]: 예측 0.95, 1.0 (1.0 은 마지막 구간), 실제 1, 1 → 평균 예측 0.975, 차이 0.025
        # ECE = (2·0.35 + 2·0.025) / 4 = 0.1875
        p = np.array([0.15, 0.15, 0.95, 1.0])
        y = np.array([0.0, 1.0, 1.0, 1.0])
        table = wp.reliability(p, y)
        self.assertEqual([r["n"] for r in table], [0, 2, 0, 0, 0, 0, 0, 0, 0, 2])
        self.assertAlmostEqual(table[1]["actual"], 0.5)
        self.assertAlmostEqual(table[9]["pred"], 0.975)
        self.assertAlmostEqual(wp.ece(p, y), 0.1875)

    def test_hand_features(self) -> None:
        # 0 열: 21~23 행 → 높이 3. 1 열: 20 행만 → 높이 4, 구멍 3. 울퉁불퉁함 |4 − 3| + |0 − 4| = 5
        b = np.zeros((1, 24, 10), np.uint8)
        b[0, 21:24, 0] = 1
        b[0, 20, 1] = 1
        f = np.zeros((1, 52), np.float32)
        f[0, wp.PENDING] = 6
        np.testing.assert_array_equal(wp.hand_features(b, f), [[4, 3, 5, 6]])


@unittest.skipIf(wp.torch is None, "PyTorch 가 없음 (python/requirements-train.txt)")
class PairingTest(unittest.TestCase):
    def test_pairing_masks_by_hand(self) -> None:
        # 명단 순서: baseline, g000, g002, g005, … (make_winprob_data.py). 판마다 턴 2 개.
        sys.path.insert(0, str(ROOT / "python" / "tools"))
        from train_winprob import pairing_masks

        names = [("g000@t00", "baseline@t00"),  # 이웃 → 팽팽한 짝
                 ("g005@t03", "g038@t03"),  # 같은 온도지만 멀다 → 나머지
                 ("g010@t06", "g010@t06"),  # 같은 선수 → 팽팽한 짝이자 같은 선수
                 ("g002@t00", "g005@t10")]  # 온도가 다르다 → 나머지
        matches = [{"seed": i, "turns": 2, "result": "p1", "players": [{"ai": a}, {"ai": b}]} for i, (a, b) in enumerate(names)]
        n = 2 * len(names)
        z = np.zeros((n, 24, 10), np.uint8)
        f = np.zeros((n, 52), np.float32)
        data = wp.Data(z, f, z, f, np.ones(n, np.float32), np.tile([0, 1], len(names)).astype(np.int32),
                       np.repeat(np.arange(len(names)), 2).astype(np.int32), matches)
        masks = pairing_masks(data, [0, 2, 5, 10, 20, 38, 55, 79])
        np.testing.assert_array_equal(masks["close"], np.repeat([True, False, True, False], 2))
        np.testing.assert_array_equal(masks["same_player"], np.repeat([False, False, True, False], 2))
        np.testing.assert_array_equal(masks["other"], ~masks["close"])
        matches[0]["players"][0]["ai"] = "ai00"  # 다른 형식의 이름(예: r01 기록)이면 나누지 않는다
        self.assertIsNone(pairing_masks(data, [0, 2, 5, 10, 20, 38, 55, 79]))


@unittest.skipIf(wp.torch is None, "PyTorch 가 없음 (python/requirements-train.txt)")
class NetTest(unittest.TestCase):
    def random_inputs(self, n: int, seed: int) -> dict[str, np.ndarray]:
        rng = np.random.default_rng(seed)
        out = {}
        for side in ("p1", "p2"):
            out[f"{side}_board"] = (rng.random((n, 24, 10)) < 0.3).astype(np.float32)
            f = np.zeros((n, 52), np.float32)
            f[np.arange(n), rng.integers(0, 7, n)] = 1
            f[:, 49] = rng.integers(0, 6, n)
            f[:, 51] = rng.integers(0, 12, n)
            out[f"{side}_features"] = f
        return out

    def test_parameter_count(self) -> None:
        # 합성곱 160 + 4,640 + 9,248 + 264, 보드 960·128 + 128, 특징 76·64 + 64, 합침 192·128 + 128,
        # 머리 256·128 + 128, 128·64 + 64, 64 + 1 → 208,169
        self.assertEqual(wp.parameter_count(wp.WinProbNet()), 208_169)

    def test_board_stats_hand_example(self) -> None:
        torch = wp.torch
        b = torch.zeros(1, 24, 10)
        b[0, 21:24, 0] = 1
        b[0, 20, 1] = 1
        s = wp.board_stats(b)[0]
        self.assertEqual(s[:10].tolist(), [3, 4, 0, 0, 0, 0, 0, 0, 0, 0])
        self.assertEqual(s[10:20].tolist(), [0, 3, 0, 0, 0, 0, 0, 0, 0, 0])
        self.assertEqual(s[20:].tolist(), [4, 3, 5, 3])

    def test_swapping_players_gives_one_minus_p(self) -> None:
        torch = wp.torch
        torch.manual_seed(0)
        module = wp.ProbModule(wp.WinProbNet()).eval()
        x = {k: torch.from_numpy(v) for k, v in self.random_inputs(64, 1).items()}
        with torch.no_grad():
            p = module(x["p1_board"], x["p1_features"], x["p2_board"], x["p2_features"])
            q = module(x["p2_board"], x["p2_features"], x["p1_board"], x["p1_features"])
            same = module(x["p1_board"], x["p1_features"], x["p1_board"], x["p1_features"])
        torch.testing.assert_close(p + q, torch.ones_like(p), atol=1e-6, rtol=0)
        torch.testing.assert_close(same, torch.full_like(same, 0.5), atol=1e-7, rtol=0)

    def test_dropout_is_training_only(self) -> None:
        # dropout 은 학습 중에만 값을 흔들고, 평가(eval)에서는 자리 대칭이 그대로다. 0 이면 층 자체가 없다.
        torch = wp.torch
        torch.manual_seed(2)
        net = wp.WinProbNet(dropout=0.5)
        self.assertEqual(wp.parameter_count(net), 208_169)
        self.assertEqual(len(wp.WinProbNet().head), 5)
        x = [torch.from_numpy(v) for v in self.random_inputs(32, 3).values()]  # p1_board, p1_features, p2_board, p2_features
        with torch.no_grad():
            net.train()
            self.assertFalse(torch.equal(net(*x), net(*x)))
            net.eval()
            p = torch.sigmoid(net(*x))
            q = torch.sigmoid(net(x[2], x[3], x[0], x[1]))
        torch.testing.assert_close(p + q, torch.ones_like(p), atol=1e-6, rtol=0)

    def test_onnx_matches_torch_and_has_tags(self) -> None:
        import onnxruntime as ort

        torch = wp.torch
        torch.manual_seed(1)
        module = wp.ProbModule(wp.WinProbNet()).eval()
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "m.onnx"
            wp.export_onnx(module, path, {"tetris.stage": "test"})
            session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            meta = session.get_modelmeta().custom_metadata_map
            self.assertEqual((meta["tetris.model_io"], meta["tetris.rules"], meta["tetris.stage"]),
                             ("winprob-1", model_io.RULES_VERSION, "test"))
            self.assertEqual([i.name for i in session.get_inputs()], list(wp.INPUT_NAMES))
            self.assertEqual([o.name for o in session.get_outputs()], [wp.OUTPUT_NAME])
            for n in (1, 9):  # N 은 dynamic axis
                inputs = self.random_inputs(n, n)
                (got,) = session.run([wp.OUTPUT_NAME], inputs)
                with torch.no_grad():
                    want = module(*(torch.from_numpy(inputs[k]) for k in wp.INPUT_NAMES)).numpy()
                self.assertEqual(got.shape, (n, 1))
                np.testing.assert_allclose(got, want, atol=1e-6, rtol=0)


if __name__ == "__main__":
    unittest.main()
