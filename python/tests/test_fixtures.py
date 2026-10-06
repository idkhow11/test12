"""공통 픽스처를 Python 쪽에서 실행한다 (docs/FIXTURES.md 5.9, 5.10).

    python -m unittest discover -s python/tests -v

model_io: Python 인코더와 onnxruntime 이 사례의 기대값을 내는지.
match: Java 대전 러너가 쓴 기록을 Python 으로 읽어 만든 모델 입력이 Java 인코더가 만든 값과 같은지(교차 확인).
"""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai import model_io, records  # noqa: E402

FIXTURES = ROOT / "fixtures"


def board_from_text(rows: list[str]) -> np.ndarray:
    """픽스처 보드 표기(# 채움, . 빔) 24 행 → [24, 10] uint8."""
    return np.array([[1 if ch != "." else 0 for ch in row] for row in rows], dtype=np.uint8)


def load(path: Path) -> dict:
    doc = json.loads(path.read_text(encoding="utf-8"))
    assert doc["rules_version"] == model_io.RULES_VERSION, f"{path.name}: rules_version"
    assert doc["type"] == path.parent.name, f"{path.name}: type 은 폴더 이름과 같아야 함"
    return doc


class ModelIoFixtures(unittest.TestCase):
    def test_cases(self) -> None:
        import onnxruntime as ort

        files = sorted((FIXTURES / "model_io").glob("*.json"))
        self.assertTrue(files)
        for path in files:
            doc = load(path)
            session = ort.InferenceSession(str(path.parent / doc["model"]), providers=["CPUExecutionProvider"])
            for case in doc["cases"]:
                with self.subTest(file=path.name, id=case["id"]):
                    self.check_case(case, session)

    def check_case(self, case: dict, session) -> None:
        kind, expect = case["kind"], case["expect"]
        if kind == "encode":
            a = model_io.afterstate_from_json(case["afterstate"])
            self.check_tensors(model_io.encode_board(a), model_io.encode_features(a), a.board, expect)
        elif kind == "encode_opponent":
            o = model_io.opponent_from_json(case["opponent"])
            self.check_tensors(model_io.encode_opponent_board(o), model_io.encode_opponent_features(o), o.board, expect)
        elif kind == "run":
            states = [model_io.afterstate_from_json(d) for d in case["afterstates"]]
            opponent = model_io.opponent_from_json(case.get("opponent"))
            boards, feats, opp_boards, opp_feats = model_io.encode_batch(states, opponent)
            (values,) = session.run(
                ["value"], {"board": boards, "features": feats, "opp_board": opp_boards, "opp_features": opp_feats}
            )
            np.testing.assert_allclose(values[:, 0], expect["values"], atol=expect["tolerance"], rtol=0)
        elif kind == "metadata":
            meta = session.get_modelmeta().custom_metadata_map
            for k, v in expect.items():
                self.assertEqual(meta.get(k), v, k)
        else:
            self.fail(f"모르는 kind: {kind}")

    def check_tensors(self, board: np.ndarray, features: np.ndarray, rows: tuple[str, ...], expect: dict) -> None:
        if "features" in expect:
            np.testing.assert_array_equal(features, np.array(expect["features"], dtype=np.float32))
        np.testing.assert_array_equal(board, board_from_text(list(rows)).astype(np.float32))
        if "board_filled" in expect:
            self.assertEqual(int(board.sum()), expect["board_filled"])


class MatchFixtures(unittest.TestCase):
    def test_cases(self) -> None:
        files = sorted((FIXTURES / "match").glob("*.json"))
        self.assertTrue(files)
        for path in files:
            for case in load(path)["cases"]:
                with self.subTest(file=path.name, id=case["id"]):
                    self.check_case(case)

    def check_case(self, case: dict) -> None:
        m = case["match"]
        records.check_header(m)
        inputs = case["expect"]["inputs"]
        self.assertEqual(len(inputs), m["turns"])
        for side in records.SIDES:
            traj = records.trajectory(m, side)
            k = 0  # 궤적은 배치가 있던 턴만 담는다
            for t, want_turn in enumerate(inputs):
                want = want_turn[side]
                if want is None:
                    self.assertIsNone(m["steps"][t][side]["after"])
                    continue
                at = f"턴 {t} {side}"
                np.testing.assert_array_equal(traj.boards[k], board_from_text(want["board"]), err_msg=at + " board")
                np.testing.assert_array_equal(traj.features[k], np.array(want["features"], np.float32), err_msg=at)
                np.testing.assert_array_equal(traj.opp_boards[k], board_from_text(want["opp_board"]), err_msg=at)
                np.testing.assert_array_equal(traj.opp_features[k], np.array(want["opp_features"], np.float32), err_msg=at)
                # 같은 기록을 model_io 의 인코더로도 만들어 두 Python 경로가 같은지 본다.
                after = m["steps"][t][side]["after"]
                a = model_io.Afterstate(
                    board=tuple("".join("#" if v else "." for v in row) for row in traj.boards[k]),
                    queue=tuple(after["queue"]), hold=after["hold"], combo=after["combo"], b2b=after["b2b"],
                    lines=after["lines"], attack=after["attack"], pending=after["pending"],
                )
                np.testing.assert_array_equal(model_io.encode_features(a), traj.features[k], err_msg=at)
                k += 1
            self.assertEqual(k, len(traj))
            expected_outcome = 0.0 if m["result"] == "draw" else (1.0 if m["result"] == side else -1.0)
            self.assertEqual(traj.outcome, expected_outcome)


if __name__ == "__main__":
    unittest.main()
