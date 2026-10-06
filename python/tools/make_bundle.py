"""게임용 모델 묶음 models/ 를 만든다 (docs/GAME.md 6.3): 학습 실행에서 모델 파일을 복사하고 manifest.json 을 쓴다.

    python python/tools/make_bundle.py
    python python/tools/make_bundle.py --p04-personas      # p04 세대 10 원형 넷 (TRAINING.md 9.9)
    python python/tools/make_bundle.py --persona tspin=runs/p04/models/value_tspin_gen-010.onnx --persona ...
    python python/tools/make_bundle.py --personas-dir runs/p04/personas

난이도 표(LEVELS)는 난이도 사다리(TRAINING.md 11.1, runs/ladder01/ladder.json)를 보고 정한 것이다. 두뇌는 학습량으로
나누고(학습 0 세대 → 10 → 38 → a2 최종 → 최종 + 2수 탐색), 조작 속도와 중력 상한은 PLAN 7장을 따른다. 페르소나는
--persona <id>=<파일> 로 하나씩 정하거나, --personas-dir 로 analyze_personas.py 가 만든 게임용 파일
(value_<페르소나>_gen-XXX.onnx)을 모두 가져온다. 메뉴의 이름과 한 줄 설명은 PERSONAS 표에서 온다. 묶음에 없는
value_*.onnx 는 지운다(이전 묶음의 남은 파일). 결과:

    models/manifest.json     형식 models-1 (Java AiCatalog 가 읽는다)
    models/value_*.onnx      가치망 (MODEL_IO 형식 2)
    models/winprob.onnx      승률 예측망 (형식 winprob-1)
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# [초안] 난이도 표 (TRAINING.md 11.1). 기준 AI 상대 승률(200판, runs/ladder01·02): 1단계 0.165(τ 0.2), 2단계 0.675,
# 3단계 0.755, 4단계 0.900, 5단계 0.985.
# 10-04: 사용자가 해 보고 어렵다고 해서 1~3단계의 조작 속도만 약 20% 늦췄다(두뇌·온도·중력 상한은 그대로, 4·5단계는 그대로).
# PLAN 7장대로 느림은 생각 지연으로 만들고 초당 입력은 5 이상으로 둔다(1단계는 생각 지연만 늘림).
# 바꾸기 전: 생각 지연 900/500/300ms, 초당 입력 5/8/12 (GAME.md 6.1, 6.4).
LEVELS: list[dict] = [
    {"id": 1, "name": "초급", "source": "runs/r01/models/value_ai00_gen-000.onnx", "file": "value_r01-g000.onnx",
     "temperature": 0.2, "beam": 0, "think_ms": 1450, "inputs_per_sec": 5, "gravity_cap": 2,
     "description": "처음 해 보는 분께 알맞은 느긋한 AI", "personas": False},
    {"id": 2, "name": "중급", "source": "runs/r01/models/value_ai00_gen-010.onnx", "file": "value_r01-g010.onnx",
     "temperature": 0.0, "beam": 0, "think_ms": 700, "inputs_per_sec": 6, "gravity_cap": 3,
     "description": "기본기를 익히는 단계", "personas": False},
    {"id": 3, "name": "상급", "source": "runs/r01/models/value_ai00_gen-038.onnx", "file": "value_r01-g038.onnx",
     "temperature": 0.0, "beam": 0, "think_ms": 400, "inputs_per_sec": 10, "gravity_cap": 5,
     "description": "꾸준히 줄을 지우는 AI", "personas": False},
    {"id": 4, "name": "최상급", "source": "runs/a2/models/value_ai00_gen-052.onnx", "file": "value_a2-g052.onnx",
     "temperature": 0.0, "beam": 0, "think_ms": 180, "inputs_per_sec": 16, "gravity_cap": 8,
     "description": "빠르고 정확한 AI", "personas": True},
    {"id": 5, "name": "불가능", "source": "runs/a2/models/value_ai00_gen-052.onnx", "file": "value_a2-g052.onnx",
     "temperature": 0.0, "beam": 8, "think_ms": 100, "inputs_per_sec": 25, "gravity_cap": 12,
     "description": "다음 블록까지 내다보는 가장 강한 AI", "personas": True},
]
# 페르소나의 메뉴 이름과 짧은 설명 [초안]. 플레이어에게는 가볍게, 학습 방향만 말한다(무엇을 좋아하도록 배웠는지).
# 시험 결과(확실한 행동 차이는 T-spin형뿐)는 TRAINING.md 9.9 에 있고 메뉴에서는 약속하지 않는다.
PERSONAS: dict[str, dict[str, str]] = {
    "neutral": {"name": "중립", "description": "이기는 것만 배운 기본 AI"},
    "attacker": {"name": "공격형", "description": "공격을 좋아하도록 배운 AI"},
    "defender": {"name": "수비형", "description": "버티기를 좋아하도록 배운 AI"},
    "tspin": {"name": "T-spin형", "description": "T-spin을 노리도록 배운 AI"},
    "combo": {"name": "콤보형", "description": "연속 클리어를 좋아하도록 배운 AI"},
}
# 페르소나 파일 (TRAINING.md 9.9): p04 세대 10 의 원형 AI 넷. 세대 10 은 계획한 k = 4 임베딩 군집에서 원형 넷이 서로 다른
# 군집에 드는 마지막 세대이고, 쓰지 않은 시드(970001~970100, a2 세대 52 상대 200판)로 다시 재어 확인했다.
P04_GEN10 = [f"{ai}=runs/p04/models/value_{ai}_gen-010.onnx" for ai in ("attacker", "defender", "tspin", "combo")]
# 승률 예측망: 실시간 판만으로 배운 wp02 (TRAINING.md 10.11). 블록 동기 판으로 배운 옛 모델은 runs/wp01/winprob_wp01.onnx
WINPROB = "runs/wp02/winprob_wp02.onnx"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="models", help="묶음 폴더 (저장소 기준)")
    p.add_argument("--persona", action="append", default=[], metavar="ID=FILE",
                   help="페르소나 하나 (저장소 기준 파일). 여러 번 줄 수 있다. 예: tspin=runs/p04/models/value_tspin_gen-030.onnx")
    p.add_argument("--personas-dir", help="페르소나 게임용 파일 폴더. --persona 와 함께 쓰지 않는다. 주의: runs/p04/personas "
                   "의 자동 이름 파일은 이름이 설계와 맞지 않는다 (TRAINING.md 9.9)")
    p.add_argument("--p04-personas", action="store_true", help="p04 세대 10 원형 넷을 페르소나로 넣는다 (TRAINING.md 9.9)")
    p.add_argument("--winprob", default=WINPROB, help="승률 예측 모델 (없으면 넣지 않는다)")
    args = p.parse_args()

    out = ROOT / args.out
    out.mkdir(parents=True, exist_ok=True)
    difficulties = []
    for lv in LEVELS:
        src = ROOT / lv["source"]
        if not src.exists():
            raise SystemExit(f"모델이 없음: {src}")
        shutil.copy2(src, out / lv["file"])
        difficulties.append({k: v for k, v in lv.items() if k not in ("source", "file")} | {"model": lv["file"],
                                                                                          "source": lv["source"]})
    if args.p04_personas:
        args.persona = P04_GEN10 + list(args.persona)
    if args.persona and args.personas_dir:
        raise SystemExit("--persona 와 --personas-dir 는 함께 쓰지 않는다")
    sources: list[tuple[str, Path]] = []
    for spec in args.persona:
        pid, sep, path = spec.partition("=")
        if not sep or not pid or not path:
            raise SystemExit(f"--persona 는 <id>=<파일> 이어야 함: {spec}")
        sources.append((pid, ROOT / path))
    if args.personas_dir:
        for f in sorted((ROOT / args.personas_dir).glob("value_*_gen-*.onnx")):
            m = re.fullmatch(r"value_(.+)_gen-(\d+)\.onnx", f.name)
            if m:
                sources.append((m.group(1), f))
    personas = [{"id": "neutral", "model": None, **PERSONAS["neutral"]}]
    for pid, f in sources:
        if not f.exists():
            raise SystemExit(f"페르소나 모델이 없음: {f}")
        if pid == "neutral" or any(q["id"] == pid for q in personas):
            raise SystemExit(f"페르소나 id 가 겹침: {pid}")
        shutil.copy2(f, out / f"value_{pid}.onnx")
        info = PERSONAS.get(pid, {"name": pid, "description": ""})
        personas.append({"id": pid, "name": info["name"], "description": info["description"],
                         "model": f"value_{pid}.onnx", "source": f.relative_to(ROOT).as_posix()})
    winprob = None
    if args.winprob and (ROOT / args.winprob).exists():
        shutil.copy2(ROOT / args.winprob, out / "winprob.onnx")
        winprob = "winprob.onnx"
    used = {d["model"] for d in difficulties} | {q["model"] for q in personas if q["model"]}
    for old in out.glob("value_*.onnx"):
        if old.name not in used:
            old.unlink()
            print(f"이전 묶음의 남은 파일을 지움: {old.name}")
    manifest = {"format": "models-1", "difficulties": difficulties, "personas": personas, "winprob": winprob}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8",
                                       newline="\n")
    print(f"→ {out}: 난이도 {len(difficulties)}개, 페르소나 {len(personas)}개, 승률 모델 {'있음' if winprob else '없음'}")


if __name__ == "__main__":
    main()
