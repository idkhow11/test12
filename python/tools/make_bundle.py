"""게임용 모델 묶음 models/ 를 만든다 (docs/GAME.md 6.3): 학습 실행에서 모델 파일을 복사하고 manifest.json 을 쓴다.

    python python/tools/make_bundle.py
    python python/tools/make_bundle.py --p05-personas      # p05a 세대 30 원형 넷 (지금 게임, TRAINING.md 9.10)
    python python/tools/make_bundle.py --p04-personas      # p04 세대 10 원형 넷 (10-04~10-11 게임, TRAINING.md 9.9)
    python python/tools/make_bundle.py --persona tspin=runs/p04/models/value_tspin_gen-010.onnx --persona ...
    python python/tools/make_bundle.py --personas-dir runs/p04/personas
    python python/tools/make_bundle.py --calibration-only  # 지금 묶음에 승률 막대 보정층만 다시 넣는다 (TRAINING.md 10.12)

난이도 표(LEVELS)는 난이도 사다리(TRAINING.md 11.1, runs/ladder01/ladder.json)를 보고 정한 것이다. 두뇌는 학습량으로
나누고(학습 0 세대 → 10 → 38 → a2 최종 → 최종 + 2수 탐색), 조작 속도와 중력 상한은 PLAN 7장을 따른다. 페르소나는
--persona <id>=<파일> 로 하나씩 정하거나, --personas-dir 로 analyze_personas.py 가 만든 게임용 파일
(value_<페르소나>_gen-XXX.onnx)을 모두 가져온다. 메뉴의 이름과 한 줄 설명은 PERSONAS 표에서 온다(--p05-personas 의 설명은
P05_DESCRIPTIONS 표). 묶음에 없는 value_*.onnx 는 지운다(이전 묶음의 남은 파일). 결과:

    models/manifest.json                형식 models-1 (Java AiCatalog 가 읽는다)
    models/value_*.onnx                 가치망 (MODEL_IO 형식 2)
    models/winprob.onnx                 승률 예측망 (형식 winprob-1)
    models/winprob_calibration.json     승률 막대 보정층 (형식 winprob-calibration-1, Java WinCalibration)

보정층은 계수를 맞춘 모델 파일의 SHA-256(model_sha256)이 묶음의 winprob.onnx 와 같을 때만 넣고, 다르면 넣지 않는다(이전
묶음의 보정층도 지운다). Java WinRate 는 모델 옆의 <모델 이름>_calibration.json 을 같은 확인을 거쳐 붙이므로 manifest 에는
적지 않는다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# [초안] 난이도 표 (TRAINING.md 11.1). 기준 AI 상대 승률(200판, runs/ladder01·02): 1단계 0.165(τ 0.2), 2단계 0.675,
# 3단계 0.755, 4단계 0.900, 5단계 0.985.
# 10-04: 사용자가 해 보고 어렵다고 해서 1~3단계의 조작 속도만 약 20% 늦췄다(900/500/300ms, 초당 5/8/12 에서).
# 10-06: 다시 해 보고 다섯 단계 모두 AI 속도를 약 22% 더 늦췄다(생각 지연 1450/700/400/180/100ms 에서).
# 두뇌·온도·중력 상한은 그대로다. PLAN 7장대로 느림은 생각 지연으로 만들고 초당 입력은 바꾸지 않는다(GAME.md 6.1, 6.4).
LEVELS: list[dict] = [
    {"id": 1, "name": "초급", "source": "runs/r01/models/value_ai00_gen-000.onnx", "file": "value_r01-g000.onnx",
     "temperature": 0.2, "beam": 0, "think_ms": 2200, "inputs_per_sec": 5, "gravity_cap": 2,
     "description": "처음 해 보는 분께 알맞은 느긋한 AI", "personas": False},
    {"id": 2, "name": "중급", "source": "runs/r01/models/value_ai00_gen-010.onnx", "file": "value_r01-g010.onnx",
     "temperature": 0.0, "beam": 0, "think_ms": 1150, "inputs_per_sec": 6, "gravity_cap": 3,
     "description": "기본기를 익히는 단계", "personas": False},
    {"id": 3, "name": "상급", "source": "runs/r01/models/value_ai00_gen-038.onnx", "file": "value_r01-g038.onnx",
     "temperature": 0.0, "beam": 0, "think_ms": 700, "inputs_per_sec": 10, "gravity_cap": 5,
     "description": "꾸준히 줄을 지우는 AI", "personas": False},
    {"id": 4, "name": "최상급", "source": "runs/a2/models/value_ai00_gen-052.onnx", "file": "value_a2-g052.onnx",
     "temperature": 0.0, "beam": 0, "think_ms": 360, "inputs_per_sec": 16, "gravity_cap": 8,
     "description": "빠르고 정확한 AI", "personas": True},
    {"id": 5, "name": "불가능", "source": "runs/a2/models/value_ai00_gen-052.onnx", "file": "value_a2-g052.onnx",
     "temperature": 0.0, "beam": 8, "think_ms": 225, "inputs_per_sec": 25, "gravity_cap": 12,
     "description": "다음 블록까지 내다보는 가장 강한 AI", "personas": True},
]
# 페르소나의 메뉴 이름과 짧은 설명 [초안]. 플레이어에게는 가볍게, 학습 방향만 말한다(무엇을 좋아하도록 배웠는지).
# 시험 결과(p04 는 확실한 행동 차이가 T-spin형뿐, p05a 는 T-spin형·콤보형이 미리 정한 기준을 넘음)는 TRAINING.md 9.9·9.10 에
# 있고 메뉴에서는 약속하지 않는다.
PERSONAS: dict[str, dict[str, str]] = {
    "neutral": {"name": "중립", "description": "이기는 것만 배운 기본 AI"},
    "attacker": {"name": "공격형", "description": "공격을 좋아하도록 배운 AI"},
    "defender": {"name": "수비형", "description": "버티기를 좋아하도록 배운 AI"},
    "tspin": {"name": "T-spin형", "description": "T-spin을 노리도록 배운 AI"},
    "combo": {"name": "콤보형", "description": "연속 클리어를 좋아하도록 배운 AI"},
}
# p05 의 보상(TRAINING.md 9.10)은 공격형·수비형·콤보형이 p04 와 달라서 --p05-personas 로 묶을 때는 설명을 이 표로 바꾼다.
# T-spin형은 보상이 p04 와 같아(tspin 0.05) 설명도 그대로다. 공격형은 확인 판에서 싱글이 줄고(−15%) 한 번에 지우는 줄이
# 늘었지만(+7%) 테트리스는 뚜렷이 늘지 않아서(+2%, p 0.38) "테트리스"가 아니라 "여러 줄"이라고 쓴다.
P05_DESCRIPTIONS: dict[str, str] = {
    "attacker": "한 번에 여러 줄을 지우도록 배운 AI",
    "defender": "보드를 낮고 깔끔하게 지키도록 배운 AI",
    "combo": "연속 클리어를 길게 이어 가도록 배운 AI",
}
ARCHETYPES = ("attacker", "defender", "tspin", "combo")
# 페르소나 파일 (TRAINING.md 9.9): p04 세대 10 의 원형 AI 넷. 세대 10 은 계획한 k = 4 임베딩 군집에서 원형 넷이 서로 다른
# 군집에 드는 마지막 세대이고, 쓰지 않은 시드(970001~970100, a2 세대 52 상대 200판)로 다시 재어 확인했다.
P04_GEN10 = [f"{ai}=runs/p04/models/value_{ai}_gen-010.onnx" for ai in ARCHETYPES]
# 페르소나 파일 (TRAINING.md 9.10): p05a 세대 30 에서 원형마다 첫 AI(복제 0 번은 원형 이름 그대로다, personas.replica_name).
# 복제 <원형>_b 와 중립 neutral·neutral_b 는 넣지 않는다. 세대 30 은 결과를 보기 전에 정한 규칙
# (python/configs/p05_selection.json)으로 골랐고, 쓰지 않은 시드(980001~980200, a2 세대 52 상대 AI 마다 400판)로 다시 재어
# 확인했다(runs/p05a/analysis/gen-030-confirm).
P05A_GEN30 = [f"{ai}=runs/p05a/models/value_{ai}_gen-030.onnx" for ai in ARCHETYPES]
# 승률 예측망: 실시간 판만으로 배운 wp02 (TRAINING.md 10.11). 블록 동기 판으로 배운 옛 모델은 runs/wp01/winprob_wp01.onnx
WINPROB = "runs/wp02/winprob_wp02.onnx"
# 승률 막대 보정층: wp02 전용, 독립 검증을 거친 runs/wpcal02 (TRAINING.md 10.12, fit_winprob_calibration.py)
WINPROB_CALIBRATION = "runs/wpcal02/calibration.json"
CALIBRATION_FORMAT = "winprob-calibration-1"
CALIBRATION_FILE = "winprob_calibration.json"  # Java WinCalibration.fileFor(winprob.onnx)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bundle_calibration(out: Path, calibration: Path | None) -> str:
    """묶음 out 의 winprob.onnx 에 맞는 보정층을 out/winprob_calibration.json 으로 쓴다(LF, 계산과 출처 항목은 그대로).
    winprob.onnx 나 보정층이 없거나, 보정층의 model_sha256 이 winprob.onnx 와 다르면 쓰지 않고 이전 묶음의 보정층을 지운다.
    돌려주는 값은 결과를 적은 한 줄이다."""
    target = out / CALIBRATION_FILE
    model = out / "winprob.onnx"
    spec = None
    if not model.exists():
        why = "묶음에 승률 모델이 없음"
    elif calibration is None:
        why = "보정층을 주지 않음"
    elif not calibration.exists():
        why = f"보정층 파일이 없음: {calibration.name}"
    else:
        try:
            spec = json.loads(calibration.read_text(encoding="utf-8"))
            why = None
        except (OSError, ValueError) as e:  # 읽을 수 없는 파일 때문에 묶음 만들기가 중간에 멈추지 않게 한다
            why = f"보정층을 읽을 수 없음: {e}"
        if why is None:
            actual = sha256(model)
            if not isinstance(spec, dict) or spec.get("format") != CALIBRATION_FORMAT:
                why = f"보정층의 format 이 {CALIBRATION_FORMAT} 이 아님"
            elif spec.get("model_sha256") != actual:
                why = (f"보정층의 모델({spec.get('model')}, sha256 {str(spec.get('model_sha256'))[:12]}…)이 묶음의 "
                       f"winprob.onnx(sha256 {actual[:12]}…)와 다름")
    if why is None:
        try:
            spec["source"] = calibration.resolve().relative_to(ROOT).as_posix()
        except ValueError:  # 저장소 밖의 파일은 경로를 남기지 않는다
            spec["source"] = calibration.name
        target.write_text(json.dumps(spec, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
        return f"승률 보정층 {spec.get('candidate')} ({spec.get('model')}) → {target.name}"
    if target.exists():
        target.unlink()
        print(f"맞지 않는 이전 보정층을 지움: {target.name}")
    return f"승률 보정층 없음 ({why})"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="models", help="묶음 폴더 (저장소 기준)")
    p.add_argument("--persona", action="append", default=[], metavar="ID=FILE",
                   help="페르소나 하나 (저장소 기준 파일). 여러 번 줄 수 있다. 예: tspin=runs/p04/models/value_tspin_gen-030.onnx")
    p.add_argument("--personas-dir", help="페르소나 게임용 파일 폴더. --persona 와 함께 쓰지 않는다. 주의: runs/p04/personas "
                   "의 자동 이름 파일은 이름이 설계와 맞지 않는다 (TRAINING.md 9.9)")
    sets = p.add_mutually_exclusive_group()
    sets.add_argument("--p05-personas", action="store_true",
                      help="p05a 세대 30 의 원형마다 첫 AI 넷을 페르소나로 넣는다. 설명은 P05_DESCRIPTIONS (TRAINING.md 9.10)")
    sets.add_argument("--p04-personas", action="store_true", help="p04 세대 10 원형 넷을 페르소나로 넣는다 (TRAINING.md 9.9)")
    p.add_argument("--winprob", default=WINPROB, help="승률 예측 모델 (없으면 넣지 않는다)")
    p.add_argument("--winprob-calibration", default=WINPROB_CALIBRATION,
                   help="승률 막대 보정층 calibration.json (저장소 기준). 묶음의 winprob.onnx 와 맞을 때만 넣는다. 빈 값이면 넣지 않는다")
    p.add_argument("--calibration-only", action="store_true",
                   help="다른 파일은 그대로 두고 지금 묶음의 winprob.onnx 에 맞춰 보정층만 넣거나 지운다")
    args = p.parse_args()

    out = ROOT / args.out
    calibration = ROOT / args.winprob_calibration if args.winprob_calibration else None
    if args.calibration_only:
        if not (out / "manifest.json").exists():
            raise SystemExit(f"묶음이 없음: {out}")
        print(f"→ {out}: {bundle_calibration(out, calibration)}")
        return
    out.mkdir(parents=True, exist_ok=True)
    difficulties = []
    for lv in LEVELS:
        src = ROOT / lv["source"]
        if not src.exists():
            raise SystemExit(f"모델이 없음: {src}")
        shutil.copy2(src, out / lv["file"])
        difficulties.append({k: v for k, v in lv.items() if k not in ("source", "file")} | {"model": lv["file"],
                                                                                          "source": lv["source"]})
    descriptions: dict[str, str] = {}  # PERSONAS 의 설명 대신 쓸 것 (페르소나 묶음마다)
    if args.p04_personas:
        args.persona = P04_GEN10 + list(args.persona)
    elif args.p05_personas:
        args.persona = P05A_GEN30 + list(args.persona)
        descriptions = P05_DESCRIPTIONS
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
        personas.append({"id": pid, "name": info["name"], "description": descriptions.get(pid, info["description"]),
                         "model": f"value_{pid}.onnx", "source": f.relative_to(ROOT).as_posix()})
    winprob = None
    if args.winprob and (ROOT / args.winprob).exists():
        shutil.copy2(ROOT / args.winprob, out / "winprob.onnx")
        winprob = "winprob.onnx"
    calibration_note = bundle_calibration(out, calibration if winprob else None)
    used = {d["model"] for d in difficulties} | {q["model"] for q in personas if q["model"]}
    for old in out.glob("value_*.onnx"):
        if old.name not in used:
            old.unlink()
            print(f"이전 묶음의 남은 파일을 지움: {old.name}")
    manifest = {"format": "models-1", "difficulties": difficulties, "personas": personas, "winprob": winprob}
    (out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1) + "\n", encoding="utf-8",
                                       newline="\n")
    print(f"→ {out}: 난이도 {len(difficulties)}개, 페르소나 {len(personas)}개, 승률 모델 {'있음' if winprob else '없음'}, "
          f"{calibration_note}")


if __name__ == "__main__":
    main()
