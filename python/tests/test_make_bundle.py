"""게임용 모델 묶음 도구의 페르소나 묶음 (python/tools/make_bundle.py, docs/GAME.md 6.3).

    python -m unittest discover -s python/tests -p "test_make_bundle.py" -v

학습 결과 runs/ 가 없어도(새로 받은 저장소) 돈다: 가짜 모델 파일로 만든 임시 저장소에서 묶음을 만든다. 저장소에 든
models/manifest.json 이 `make_bundle.py --p05-personas` 가 쓰는 것과 바이트까지 같은지도 본다(표를 고치고 묶음을 다시
만들지 않았거나, 다른 페르소나 묶음으로 만들었으면 실패한다).
"""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python" / "tools"))

import make_bundle as mb  # noqa: E402

ARCHETYPES = ["attacker", "defender", "tspin", "combo"]


def fake_repo(root: Path) -> Path:
    """묶음에 들어갈 모델 파일(난이도, p04·p05 페르소나, 승률 모델)을 내용만 다른 가짜 파일로 root 아래에 만든다."""
    files = {lv["source"] for lv in mb.LEVELS} | {mb.WINPROB}
    files |= {spec.partition("=")[2] for spec in mb.P04_GEN10 + mb.P05A_GEN30}
    for i, rel in enumerate(sorted(files)):
        (root / rel).parent.mkdir(parents=True, exist_ok=True)
        (root / rel).write_bytes(f"가짜 모델 {i}: {rel}".encode())
    return root


def build(root: Path, *args: str) -> str:
    """저장소 root 에서 make_bundle.py 를 인자 args 로 돌리고 화면 출력을 돌려준다."""
    out = io.StringIO()
    with mock.patch.object(mb, "ROOT", root), mock.patch.object(sys, "argv", ["make_bundle.py", *args]), \
            contextlib.redirect_stdout(out):
        mb.main()
    return out.getvalue()


class PersonaSetTest(unittest.TestCase):
    def test_p05_set_is_the_first_ai_of_each_archetype(self) -> None:
        # 복제 0 번은 원형 이름 그대로이고(personas.replica_name) 복제 <원형>_b 와 중립 neutral·neutral_b 는 넣지 않는다
        self.assertEqual(mb.P05A_GEN30, [f"{ai}=runs/p05a/models/value_{ai}_gen-030.onnx" for ai in ARCHETYPES])
        self.assertEqual(mb.P04_GEN10, [f"{ai}=runs/p04/models/value_{ai}_gen-010.onnx" for ai in ARCHETYPES])

    def test_p05_descriptions_replace_only_the_changed_rewards(self) -> None:
        # p05 에서 보상이 바뀐 공격·수비·콤보형만 바꾼다. T-spin형은 보상이 p04 와 같아(tspin 0.05) PERSONAS 의 설명을 쓴다
        self.assertEqual(set(mb.P05_DESCRIPTIONS), {"attacker", "defender", "combo"})
        self.assertTrue(all(text.endswith("배운 AI") for text in mb.P05_DESCRIPTIONS.values()))

    def test_p05_bundle_from_a_fresh_folder(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fake_repo(Path(tmp))
            stale = root / "models" / "value_old.onnx"
            stale.parent.mkdir()
            stale.write_bytes(b"old")
            note = build(root, "--p05-personas", "--winprob-calibration", "")
            out = root / "models"
            manifest = json.loads((out / "manifest.json").read_text(encoding="utf-8"))
            personas = manifest["personas"]
            self.assertEqual([q["id"] for q in personas], ["neutral", *ARCHETYPES])
            self.assertEqual([q["name"] for q in personas], ["중립", "공격형", "수비형", "T-spin형", "콤보형"])
            self.assertEqual([q["description"] for q in personas],
                             [mb.PERSONAS["neutral"]["description"], mb.P05_DESCRIPTIONS["attacker"],
                              mb.P05_DESCRIPTIONS["defender"], mb.PERSONAS["tspin"]["description"],
                              mb.P05_DESCRIPTIONS["combo"]])
            self.assertIsNone(personas[0]["model"], "중립은 난이도의 모델을 쓴다")
            for q in personas[1:]:
                self.assertEqual(q["model"], f"value_{q['id']}.onnx")
                self.assertEqual(q["source"], f"runs/p05a/models/value_{q['id']}_gen-030.onnx")
                self.assertEqual((out / q["model"]).read_bytes(), (root / q["source"]).read_bytes())
            self.assertEqual([d["model"] for d in manifest["difficulties"]], [lv["file"] for lv in mb.LEVELS])
            self.assertEqual(manifest["winprob"], "winprob.onnx")
            self.assertFalse(stale.exists(), "이전 묶음의 남은 파일은 지운다")
            self.assertNotIn(b"\r", (out / "manifest.json").read_bytes())
            self.assertIn("페르소나 5개", note)

    def test_only_the_persona_entries_differ_from_a_p04_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = fake_repo(Path(tmp))
            build(root, "--p04-personas", "--winprob-calibration", "", "--out", "p04")
            build(root, "--p05-personas", "--winprob-calibration", "", "--out", "p05")
            a = json.loads((root / "p04" / "manifest.json").read_text(encoding="utf-8"))
            b = json.loads((root / "p05" / "manifest.json").read_text(encoding="utf-8"))
            a_rest, b_rest = ({k: v for k, v in m.items() if k != "personas"} for m in (a, b))
            self.assertEqual(a_rest, b_rest, "난이도·승률 모델 항목은 같다")
            self.assertEqual(a["personas"][0], b["personas"][0], "중립은 그대로")
            self.assertEqual(a["personas"][3]["description"], b["personas"][3]["description"], "T-spin형 설명은 그대로")
            self.assertEqual(a["personas"][1]["description"], mb.PERSONAS["attacker"]["description"], "p04 는 옛 설명")
            same = sorted(f.name for f in (root / "p04").iterdir()
                          if (root / "p05" / f.name).read_bytes() == f.read_bytes())
            self.assertEqual(same, sorted({lv["file"] for lv in mb.LEVELS} | {"winprob.onnx"}), "모델 파일도 페르소나만 다르다")

    def test_p04_and_p05_sets_cannot_be_mixed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp, contextlib.redirect_stderr(io.StringIO()):
            root = fake_repo(Path(tmp))
            with self.assertRaises(SystemExit) as e:
                build(root, "--p04-personas", "--p05-personas")
            self.assertEqual(e.exception.code, 2)
            self.assertFalse((root / "models").exists(), "아무것도 만들지 않는다")


class BundledManifestTest(unittest.TestCase):
    """저장소의 models/manifest.json 은 `python python/tools/make_bundle.py --p05-personas` 가 쓴 것이다 (GAME.md 6.3)."""

    def test_bundled_manifest_is_what_the_tool_writes(self) -> None:
        bundled = ROOT / "models" / "manifest.json"
        if not bundled.exists():
            self.skipTest("models/ 가 없음")
        self.maxDiff = None
        with tempfile.TemporaryDirectory() as tmp:
            root = fake_repo(Path(tmp))
            build(root, "--p05-personas", "--winprob-calibration", "")
            fresh = (root / "models" / "manifest.json").read_bytes()
        manifest = json.loads(bundled.read_text(encoding="utf-8"))
        self.assertEqual(manifest, json.loads(fresh.decode("utf-8")))
        self.assertEqual(bundled.read_bytes(), fresh, "줄바꿈(LF)과 들여쓰기까지 같다")
        for q in manifest["personas"][1:]:
            self.assertTrue((bundled.parent / q["model"]).is_file(), q["model"])


if __name__ == "__main__":
    unittest.main()
