"""승률 막대 보정층 (python/tools/fit_winprob_calibration.py, TRAINING.md 10.11 의 다음 과제).

    python -m unittest discover -s python/tests -v

블록 수(GameModel.pieces())를 timed-1 기록에서 세는 방법, 속도 비, 보정 식의 자리 대칭과 경계를 손으로 만든 판으로 확인한다.
손으로 만든 판은 GameModel 의 규칙을 따른다: 가방은 고정 뒤 스폰과 홀드 칸이 빈 채로 한 홀드에서만 나아가고, pieces() 는
락 아웃이 아닌 고정마다 1 오르며, 기록은 누구든 고정한 틱마다 두 상태를 남긴다(train.TimedRecorder).
판이 끝난 틱의 기록만 빠지는지(live_mask), runs/wpcal01·wpcal02 의 결과 파일이 있으면 calibration.json 이 parity_cases.json 의
보정 사례를 그대로 내는지도 본다.

Java 와 함께 쓰는 공통 픽스처 fixtures/winprob_calibration/cases.jsonl (Java WinCalibrationTest, WinRateCalibrationTest 도
읽는다)의 모든 사례, 게임 묶음 models/winprob_calibration.json 이 픽스처와 같은 보정층인지, make_bundle.py 가 모델이 맞을
때만 보정층을 넣는지도 본다(TRAINING.md 10.12).
"""

from __future__ import annotations

import gzip
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

import fit_winprob_calibration as fc  # noqa: E402
import make_bundle as mb  # noqa: E402
from tetris_ai import model_io  # noqa: E402
from tetris_ai import winprob as wp  # noqa: E402

# 두 플레이어가 같은 시드로 받는 블록 순서 (7-bag 세 개)
SEQUENCE = "TOISZJL" + "LJOTSIZ" + "ZTOLJIS" + "OSTZLIJ"


class Player:
    """GameModel 의 가방·홀드·고정만 흉내 낸다."""

    def __init__(self) -> None:
        self.drawn = 1  # 처음 스폰에서 SEQUENCE[0] 을 꺼냈다
        self.current = SEQUENCE[0]
        self.hold = None
        self.rows = [0] * model_io.HEIGHT
        self.pieces = 0  # Java GameModel.pieces()

    def _next(self) -> str:
        piece = SEQUENCE[self.drawn]
        self.drawn += 1
        return piece

    def hold_now(self) -> None:
        if self.hold is None:  # 빈 홀드: 지금 블록을 넣고 다음 블록을 꺼낸다(가방이 나아감)
            self.hold, self.current = self.current, self._next()
        else:  # 맞바꿈: 가방은 그대로
            self.hold, self.current = self.current, self.hold

    def lock(self, rows: list[int], lock_out: bool = False) -> None:
        self.rows = list(rows)
        if lock_out:  # 보드는 바뀌지만 pieces 는 그대로, 스폰도 없다
            return
        self.pieces += 1
        self.current = self._next()

    def state(self) -> dict:
        return {"rows": list(self.rows), "current": self.current, "queue": SEQUENCE[self.drawn:self.drawn + 5],
                "hold": self.hold, "combo": -1, "b2b": False, "pending": 0}


def bottom(*bits: int, row: int = model_io.HEIGHT - 1, base: list[int] | None = None) -> list[int]:
    rows = list(base) if base is not None else [0] * model_io.HEIGHT
    for b in bits:
        rows[row] |= 1 << b
    return rows


def synthetic_game() -> tuple[dict, list[tuple[int, int]], list[tuple[int, int]]]:
    """(기록, 기록마다 기대하는 pieces(), 기록마다 기대하는 다음 블록 목록 셈)."""
    p1, p2 = Player(), Player()
    steps = []

    def record(t: int) -> None:
        steps.append({"t": t, "p1": {"state": p1.state()}, "p2": {"state": p2.state()}})

    p1.hold_now()                                  # t=300: P1 첫 홀드(기록 없음, 가방 1칸)
    p2.lock(bottom(0, 1, 2, 3)); record(716)       # 기록 0: P2 만 고정. P1 은 보드가 비어 0개
    p1.lock(bottom(0, 1, 2, 3)); record(850)       # 기록 1: P1 고정
    p2.hold_now()                                  # t=900: P2 첫 홀드(기록 없음, 가방 1칸)
    p1.lock(bottom(4, 5, 6, 7, base=p1.rows)); record(1000)  # 기록 2: P1 고정. P2 는 목록만 밀림(고정 아님)
    p2.lock(bottom(4, 5, 6, 7, base=p2.rows)); record(1100)  # 기록 3: P2 고정
    p1.lock(bottom(0, 1, 2, 3, row=model_io.HEIGHT - 2, base=p1.rows))  # 기록 4: 같은 틱에 둘 다 고정
    p2.lock(bottom(0, 1, 2, 3, row=model_io.HEIGHT - 2, base=p2.rows)); record(1300)
    p1.lock(bottom(0, 1, row=model_io.HEIGHT - 1)); record(1500)  # 기록 5: P1 줄 지움(보드가 다른 모양으로)
    p2.hold_now()                                  # t=1700: P2 맞바꿈 홀드(가방 그대로)
    p2.lock(bottom(3, 4, 5, 6, row=0, base=p2.rows), lock_out=True); record(1800)  # 기록 6: P2 락 아웃
    record_obj = {
        "format": "timed-1", "rules": model_io.RULES_VERSION, "model_io": model_io.MODEL_IO_VERSION, "run": "test",
        "seed": 600000009, "players": [{"ai": "a", "model": "m", "temperature": 0.0}, {"ai": "b", "model": "m", "temperature": 0.0}],
        "result": "p1", "ended": True, "time_ms": 1800, "topout": {"p1": None, "p2": "lock_out"}, "turns": len(steps),
        "steps": steps,
    }
    # Java pieces(): 손으로 센 값이 Player.pieces 와 같은지도 본다(마지막 락 아웃은 세지 않음)
    expected = [(0, 1), (1, 1), (2, 1), (2, 2), (3, 3), (4, 3), (4, 3)]
    assert (p1.pieces, p2.pieces) == expected[-1]
    legacy = [(1, 1), (2, 1), (3, 2), (3, 3), (4, 4), (5, 4), (5, 4)]
    return record_obj, expected, legacy


class PieceCountTest(unittest.TestCase):
    def test_exact_counts_match_game_model(self) -> None:
        m, expected, _ = synthetic_game()
        got = fc.piece_counts(m["steps"], m["topout"])
        self.assertEqual([tuple(int(v) for v in row) for row in got], expected)

    def test_first_step_is_not_off_by_one(self) -> None:
        # 첫 기록에서 고정하지 않은 쪽(P1, 홀드만 함)은 0 이다. 다음 블록 목록 셈은 그쪽도 1 로 센다.
        m, expected, legacy = synthetic_game()
        self.assertEqual(tuple(fc.piece_counts(m["steps"])[0]), expected[0])
        self.assertEqual(tuple(fc.legacy_queue_counts(m["steps"])[0]), (1, 1))

    def test_lock_out_is_not_counted(self) -> None:
        m, _, _ = synthetic_game()
        without = fc.piece_counts(m["steps"], None)  # topout 을 모르면 보드가 바뀐 락 아웃도 센다
        self.assertEqual(int(without[-1, 1]), 4)
        self.assertEqual(int(fc.piece_counts(m["steps"], m["topout"])[-1, 1]), 3)
        self.assertEqual(int(fc.piece_counts(m["steps"], m["topout"])[-1, 0]), 4)  # 이긴 쪽은 그대로

    def test_legacy_counts(self) -> None:
        m, _, legacy = synthetic_game()
        got = fc.legacy_queue_counts(m["steps"])
        self.assertEqual([tuple(int(v) for v in row) for row in got], legacy)

    def test_bag_check(self) -> None:
        m, _, _ = synthetic_game()
        counts = fc.piece_counts(m["steps"], m["topout"])
        self.assertEqual(fc.bag_check(m["steps"], counts, m["topout"]), 0)
        # 다음 블록 목록 셈은 첫 홀드를 고정으로 세므로 가방 진행과 맞지 않는다
        self.assertGreater(fc.bag_check(m["steps"], fc.legacy_queue_counts(m["steps"]), m["topout"]), 0)

    def test_queue_shift(self) -> None:
        self.assertEqual(fc.queue_shift("ISZJL", "ISZJL"), 0)
        self.assertEqual(fc.queue_shift("ISZJL", "SZJLL"), 1)
        self.assertEqual(fc.queue_shift("ISZJL", "ZJLLJ"), 2)
        self.assertIsNone(fc.queue_shift("ISZJL", "TTTTT"))

    def test_game_arrays_match_training_reader(self) -> None:
        # 보정층의 표본이 학습 자료(read_records, 판마다 3개에 하나)와 같은 상태·순서인지
        m, expected, _ = synthetic_game()
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "chunk-000" / "w0.jsonl.gz"
            path.parent.mkdir()
            with gzip.open(path, "wt", encoding="utf-8") as fh:
                fh.write(json.dumps(m) + "\n")
            ref = wp.read_records([path], timed_stride=3)
            info, arrays = fc.game_arrays(m, 3, str(path))
            read = fc.read_splits([path], 3, ("test",), workers=1)["test"]
        for key in ("b1", "f1", "b2", "f2", "y", "turn"):
            np.testing.assert_array_equal(arrays[key], getattr(ref, key), err_msg=key)
            np.testing.assert_array_equal(read[key], getattr(ref, key), err_msg=key)
        self.assertEqual(arrays["turn"].tolist(), [0, 3, 6])
        self.assertEqual(list(zip(arrays["n1"].tolist(), arrays["n2"].tolist())), [expected[0], expected[3], expected[6]])
        self.assertEqual(arrays["t"].tolist(), [716, 1100, 1800])
        self.assertEqual(info["pieces_final"], [4, 3])
        self.assertEqual(info["legacy_final"], [5, 4])
        self.assertEqual(info["bag_mismatch"], 0)
        self.assertEqual(info["path"], "chunk-000/w0.jsonl.gz")


def as_split(m: dict, stride: int = 1) -> dict:
    """기록 한 판 → read_splits 의 나누기 하나와 같은 모양(p 는 가짜)."""
    info, arrays = fc.game_arrays(m, stride, "chunk-000/w0.jsonl.gz")
    d = {k: arrays[k] for k in ("y", "turn", "n1", "n2", "t")}
    d["match"] = np.zeros(len(arrays["y"]), np.int32)
    d["games"] = [info]
    d["p"] = {"wp02": np.linspace(0.1, 0.9, len(arrays["y"])).astype(np.float32)}
    return d


class LiveMaskTest(unittest.TestCase):
    """게임(GameSession.advance)은 어느 한쪽이라도 끝난 틱에 모델을 부르지 않는다. 그 기록만 빠져야 한다."""

    def test_topout_at_a_lock_tick_is_terminal(self) -> None:
        m, _, _ = synthetic_game()  # 마지막 기록(t=1800)이 P2 락 아웃, time_ms = 1800
        live = fc.live_mask(as_split(m))
        self.assertEqual(live.tolist(), [True] * (len(m["steps"]) - 1) + [False])

    def test_hold_block_out_after_the_last_record_keeps_it_live(self) -> None:
        # 홀드로 꺼낸 블록의 블록 아웃은 고정이 없는 틱(기록 없음)에 끝난다: 마지막 기록은 진행 중 상태다
        m, _, _ = synthetic_game()
        m["steps"] = m["steps"][:-1]
        m["turns"] = len(m["steps"])
        m["time_ms"] = 1750
        m["topout"] = {"p1": None, "p2": "block_out"}
        self.assertTrue(fc.live_mask(as_split(m)).all())

    def test_time_limit_keeps_everything_live(self) -> None:
        m, _, _ = synthetic_game()
        m["steps"] = m["steps"][:-1]
        m["turns"] = len(m["steps"])
        m["time_ms"] = m["steps"][-1]["t"]  # 마지막 틱에 고정이 있었어도 아무도 지지 않았다
        m.update(result="draw", ended=False, topout={"p1": None, "p2": None})
        self.assertTrue(fc.live_mask(as_split(m)).all())

    def test_stride_sample_only_drops_the_terminal_record(self) -> None:
        m, _, _ = synthetic_game()  # 기록 7개, 3개에 하나면 턴 0, 3, 6 (6 이 끝난 순간)
        d = as_split(m, stride=3)
        live = fc.live_mask(d)
        self.assertEqual(d["turn"][live].tolist(), [0, 3])
        sub = fc.subset(d, live)
        self.assertEqual(sub["turn"].tolist(), [0, 3])
        self.assertEqual(len(sub["p"]["wp02"]), 2)
        self.assertIs(sub["games"], d["games"])


class ReadAllTest(unittest.TestCase):
    def test_read_all_ignores_the_split(self) -> None:
        a, _, _ = synthetic_game()
        b, _, _ = synthetic_game()
        b["seed"] = 600000010  # 학습 나누기(끝자리 0)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "chunk-000" / "w0.jsonl.gz"
            path.parent.mkdir()
            with gzip.open(path, "wt", encoding="utf-8") as fh:
                fh.write(json.dumps(a) + "\n" + json.dumps(b) + "\n")
            only_test = fc.read_splits([path], 1, ("test",), workers=1)["test"]
            everything = fc.read_all([path], 1, workers=1)
        self.assertEqual([g["seed"] for g in only_test["games"]], [a["seed"]])
        self.assertEqual([g["seed"] for g in everything["games"]], [a["seed"], b["seed"]])
        self.assertEqual(everything["match"].tolist(), [0] * len(a["steps"]) + [1] * len(b["steps"]))
        self.assertEqual(everything["t"].tolist(), [s["t"] for s in a["steps"]] * 2)


class RatioTest(unittest.TestCase):
    def test_speed_ratio(self) -> None:
        self.assertEqual(float(fc.speed_ratio(0, 0)), 1.0)  # 둘 다 0 이면 1
        self.assertEqual(float(fc.speed_ratio(3, 6)), 0.5)
        self.assertEqual(float(fc.speed_ratio(6, 3)), 0.5)
        self.assertEqual(float(fc.speed_ratio(0, 5)), 0.0)
        self.assertAlmostEqual(float(fc.speed_ratio(0, 1, k=8)), 8 / 9)

    def test_pace_ratio(self) -> None:
        self.assertEqual(float(fc.pace_ratio(0, 0)), 1.0)
        self.assertEqual(float(fc.pace_ratio(5, 0)), float("inf"))
        self.assertEqual(float(fc.pace_ratio(4, 5)), 1.25)


def general_spec(**coef) -> dict:
    c = {k: 0.0 for k in fc.COEFFS}
    c.update(coef)
    return {"kind": "general", "eps": fc.EPS, "k": 8.0, "tau_ms": 40_000.0, "coefficients": c}


class CalibrateTest(unittest.TestCase):
    SPEC = general_spec(s0=0.66, s_r=-1.32, s_e=-0.69, b0=3.25, b_e=6.95, s_r2=0.1, s_r3=-0.05, s_er=0.2, s_er2=-0.1)

    def test_identity(self) -> None:
        p = np.array([0.0, 1e-7, 0.2, 0.5, 0.9, 1.0])
        got = fc.calibrate(general_spec(), p, [3, 0, 5, 9, 1, 2], [1, 4, 5, 2, 0, 7], [100, 5000, 9000, 1, 0, 600000])
        np.testing.assert_allclose(got, np.clip(p, fc.EPS, 1 - fc.EPS), rtol=0, atol=1e-12)

    def test_symmetry(self) -> None:
        rng = np.random.default_rng(3)
        p = rng.random(500).astype(np.float32).astype(np.float64)
        n1, n2 = rng.integers(0, 120, 500), rng.integers(0, 120, 500)
        t = rng.integers(0, 600_000, 500)
        for spec in (self.SPEC, {"kind": "rbin_temperature", "eps": fc.EPS, "k": 2.0, "edges": list(fc.RBIN_EDGES),
                                 "log_scale": [1.2, 0.8, 0.2, -0.1, -0.7, -1.0]}):
            a = fc.calibrate(spec, p, n1, n2, t)
            b = fc.calibrate(spec, 1.0 - p, n2, n1, t)
            np.testing.assert_allclose(a + b, 1.0, rtol=0, atol=1e-12)

    def test_start_of_game(self) -> None:
        # 판 시작: 두 상태가 같아 p = 0.5, 블록 수 0 → 0.5
        self.assertEqual(float(fc.calibrate(self.SPEC, 0.5, 0, 0, 0)), 0.5)
        self.assertEqual(float(fc.calibrate(self.SPEC, 0.5, 7, 7, 12_000)), 0.5)

    def test_extremes_are_finite(self) -> None:
        for p in (0.0, 1.0):
            v = float(fc.calibrate(self.SPEC, p, 10, 10, 20_000))
            self.assertTrue(0.0 < v < 1.0)
        self.assertGreater(float(fc.calibrate(self.SPEC, 0.5, 10, 0, 5_000)), 0.5)  # 블록을 더 놓은 쪽으로
        self.assertLess(float(fc.calibrate(self.SPEC, 0.5, 0, 10, 5_000)), 0.5)

    def test_general_formula_requires_positive_k(self) -> None:
        spec = general_spec()
        spec["k"] = 0.0
        with self.assertRaises(ValueError):
            fc.calibrate(spec, 0.5, 1, 0, 100)


RUNS = [ROOT / "runs" / name for name in ("wpcal01", "wpcal02")
        if (ROOT / "runs" / name / "calibration.json").exists() and (ROOT / "runs" / name / "parity_cases.json").exists()]


@unittest.skipUnless(RUNS, "runs/wpcal01, runs/wpcal02 결과 없음 (fit_winprob_calibration.py 를 먼저 실행)")
class SavedCalibrationTest(unittest.TestCase):
    """runs/ 에 있는 보정층 결과마다: calibration.json 이 parity_cases.json 의 값을 그대로 내고, 판 시작은 0.5 다."""

    def test_parity_cases(self) -> None:
        for run in RUNS:
            with self.subTest(run=run.name):
                spec = json.loads((run / "calibration.json").read_text(encoding="utf-8"))
                cases = json.loads((run / "parity_cases.json").read_text(encoding="utf-8"))
                self.assertEqual(spec["format"], fc.FORMAT)
                self.assertEqual(spec["model"], "wp02")
                for c in cases["calibration"] + cases["states"]:
                    got = float(fc.calibrate(spec, c["p"], c["n1"], c["n2"], c["t_ms"]))
                    self.assertAlmostEqual(got, c["p_cal"], delta=1e-12, msg=c["id"])
                for c in cases["states"]:
                    self.assertEqual(c["p1"]["rows"].__len__(), model_io.HEIGHT)
                self.assertEqual(float(fc.calibrate(spec, 0.5, 0, 0, 0)), 0.5)


FIXTURE = fc.FIXTURE_DIR / fc.FIXTURE_FILE
BUNDLE_MODEL = ROOT / "models" / "winprob.onnx"
BUNDLE_CALIBRATION = ROOT / "models" / mb.CALIBRATION_FILE


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@unittest.skipUnless(FIXTURE.exists(), "fixtures/winprob_calibration/cases.jsonl 없음")
class FixtureFileTest(unittest.TestCase):
    """공통 픽스처: Java 가 같은 파일로 같은 값을 내는지 본다(WinCalibrationTest, WinRateCalibrationTest)."""

    @classmethod
    def setUpClass(cls) -> None:
        cls.header, cls.specs, cls.cases = fc.read_fixture(FIXTURE)

    def test_header_and_ids(self) -> None:
        h = self.header
        self.assertEqual(h["type"], "winprob_calibration")
        self.assertEqual(h["rules_version"], model_io.RULES_VERSION)
        self.assertLessEqual(h["tolerance"]["p_cal"], 1e-9)
        self.assertLessEqual(h["tolerance"]["p_onnx"], 1e-5)  # 다른 ONNX 픽스처와 같은 값 (CPU 마다 커널이 다르다)
        self.assertEqual(set(self.specs), {"bundle", "all_terms", "rbin", "rbin_k0"})
        ids = [c["id"] for c in self.cases]
        self.assertEqual(len(ids), len(set(ids)), "id 중복")
        self.assertGreaterEqual(sum(c["kind"] == "state" for c in self.cases), 100)
        # 줄바꿈은 LF (Java 와 Python 이 같은 바이트를 읽는다)
        self.assertNotIn(b"\r", FIXTURE.read_bytes())

    def test_every_formula_case(self) -> None:
        tol = self.header["tolerance"]["p_cal"]
        for c in self.cases:
            got = float(fc.calibrate(self.specs[c["spec"]], c["p"], c["n1"], c["n2"], c["t_ms"]))
            self.assertAlmostEqual(got, c["p_cal"], delta=tol, msg=c["id"])

    def test_rbin_edges_are_hit_exactly(self) -> None:
        # 픽스처가 구간 경계(r 이 정확히 경계값)와 k = 0 의 0 ÷ 0 을 실제로 담고 있는가
        k = self.specs["rbin"]["k"]
        ratios = {float(fc.speed_ratio(c["n1"], c["n2"], k)) for c in self.cases if c["spec"] == "rbin"}
        self.assertTrue(set(fc.RBIN_EDGES[1:]) <= ratios, sorted(ratios))
        self.assertIn((0, 0), {(c["n1"], c["n2"]) for c in self.cases if c["spec"] == "rbin_k0"})

    def test_state_cases_run_the_bundled_model(self) -> None:
        try:
            import onnxruntime as ort
        except ImportError:
            self.skipTest("onnxruntime 없음")
        if not BUNDLE_MODEL.exists() or sha256(BUNDLE_MODEL) != self.header["onnx_sha256"]:
            self.skipTest("models/winprob.onnx 가 픽스처의 모델과 다름")
        session = ort.InferenceSession(str(BUNDLE_MODEL), providers=["CPUExecutionProvider"])
        states = [c for c in self.cases if c["kind"] == "state"]
        enc = [(wp.encode_record(c["p1"]), wp.encode_record(c["p2"])) for c in states]
        (p,) = session.run([wp.OUTPUT_NAME], {
            "p1_board": np.stack([a[0] for a, _ in enc]).astype(np.float32), "p1_features": np.stack([a[1] for a, _ in enc]),
            "p2_board": np.stack([b[0] for _, b in enc]).astype(np.float32), "p2_features": np.stack([b[1] for _, b in enc])})
        for c, pv in zip(states, p[:, 0]):
            self.assertAlmostEqual(float(pv), c["p"], delta=self.header["tolerance"]["p_onnx"], msg=c["id"])

    def test_fixture_is_up_to_date_with_its_run(self) -> None:
        run = ROOT / "runs" / self.header["run"]
        if not (run / "calibration.json").exists() or not (run / "parity_cases.json").exists():
            self.skipTest(f"runs/{self.header['run']} 없음")
        spec = json.loads((run / "calibration.json").read_text(encoding="utf-8"))
        parity = json.loads((run / "parity_cases.json").read_text(encoding="utf-8"))
        records = fc.fixture_records(spec, parity, self.header["run"])
        self.assertEqual(records, [json.loads(line) for line in FIXTURE.read_text(encoding="utf-8").splitlines()])

    @unittest.skipUnless(BUNDLE_CALIBRATION.exists(), "models/winprob_calibration.json 없음 (묶음에 보정층이 없다)")
    def test_bundle_calibration_is_the_fixture_bundle(self) -> None:
        bundled = json.loads(BUNDLE_CALIBRATION.read_text(encoding="utf-8"))
        self.assertEqual(bundled["model_sha256"], sha256(BUNDLE_MODEL), "묶음의 winprob.onnx 의 보정층이어야 함")
        self.assertEqual(fc.spec_core(bundled), self.specs["bundle"])
        self.assertNotIn(b"\r", BUNDLE_CALIBRATION.read_bytes())


class MakeBundleCalibrationTest(unittest.TestCase):
    """make_bundle.bundle_calibration: 묶음의 winprob.onnx 와 SHA-256 이 같은 보정층만 넣고, 아니면 이전 것을 지운다."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.out = self.dir / "models"
        self.out.mkdir()
        (self.out / "winprob.onnx").write_bytes(b"not really a model")
        self.spec = {"format": mb.CALIBRATION_FORMAT, "candidate": "speed5", "kind": "general", "model": "wp02",
                     "model_sha256": sha256(self.out / "winprob.onnx"), "eps": 1e-6, "k": 8.0, "tau_ms": 40000.0}
        self.cal = self.dir / "calibration.json"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def write_cal(self, **changes) -> None:
        self.cal.write_text(json.dumps({**self.spec, **changes}), encoding="utf-8")

    def test_matching_calibration_is_written_with_lf(self) -> None:
        self.write_cal()
        note = mb.bundle_calibration(self.out, self.cal)
        target = self.out / mb.CALIBRATION_FILE
        self.assertIn("speed5", note)
        written = json.loads(target.read_text(encoding="utf-8"))
        self.assertEqual({k: v for k, v in written.items() if k != "source"}, self.spec)
        self.assertEqual(written["source"], "calibration.json", "저장소 밖 파일은 이름만 남긴다")
        self.assertNotIn(b"\r", target.read_bytes())

    def test_calibration_of_another_model_is_left_out_and_stale_one_removed(self) -> None:
        self.write_cal()
        mb.bundle_calibration(self.out, self.cal)
        self.write_cal(model_sha256="0" * 64)
        note = mb.bundle_calibration(self.out, self.cal)
        self.assertIn("없음", note)
        self.assertFalse((self.out / mb.CALIBRATION_FILE).exists())

    def test_wrong_format_or_missing_pieces_write_nothing(self) -> None:
        self.write_cal(format="winprob-calibration-0")
        self.assertIn("format", mb.bundle_calibration(self.out, self.cal))
        self.cal.write_text("[]", encoding="utf-8")
        self.assertIn("format", mb.bundle_calibration(self.out, self.cal))
        self.cal.write_text("{", encoding="utf-8")  # 깨진 파일 때문에 묶음 만들기가 멈추지 않는다
        self.assertIn("읽을 수 없음", mb.bundle_calibration(self.out, self.cal))
        self.assertIn("주지 않음", mb.bundle_calibration(self.out, None))
        self.assertIn("파일이 없음", mb.bundle_calibration(self.out, self.dir / "nope.json"))
        (self.out / "winprob.onnx").unlink()
        self.write_cal()
        self.assertIn("승률 모델이 없음", mb.bundle_calibration(self.out, self.cal))
        self.assertFalse((self.out / mb.CALIBRATION_FILE).exists())


if __name__ == "__main__":
    unittest.main()
