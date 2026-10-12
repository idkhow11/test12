"""승률 예측망의 실시간 자료를 만든다 (docs/TRAINING.md 10.9): 게임의 AI 와 사람 흉내 AI 를 가상 시간 모드로 둔다.

    python python/tools/make_timed_data.py --run wpt01 --matches 20000

자료 생성은 Java 의 kr.ac.jbnu.se.tetris.train.TimedDataMain 이 한다(짝 짓기와 사람 흉내의 속도는 그 클래스 설명).
게임과 같은 AI 를 쓰도록 지금의 모델 묶음 models/ 를 실행 폴더에 복사해 두고 끝까지 그 사본을 쓴다(러너 사본과 같은
이유, TRAINING.md 12장). 결과:

    runs/<run>/models/                   모델 묶음 사본 (manifest.json 포함)
    runs/<run>/data/plan-<묶음>.json     계획 파일 (timedplan-1)
    runs/<run>/data/chunk-<묶음>/        기록 w<스레드>.jsonl.gz (timed-1) 와 summary.json
    runs/<run>/data/config.json          이 스크립트의 설정

이미 있는 묶음은 건너뛰므로, 중간에 멈췄으면 같은 명령을 다시 실행하면 이어서 만든다. 판의 시드는 seed-base + 판 번호이고,
학습·검증·시험 나누기는 시드의 끝자리로 한다(tetris_ai.winprob.split_of).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai.runner import run_java  # noqa: E402

MAIN_CLASS = "kr.ac.jbnu.se.tetris.train.TimedDataMain"


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", default="wpt01", help="결과 폴더 runs/<run>")
    p.add_argument("--matches", type=int, default=20000, help="전체 판 수")
    p.add_argument("--chunk", type=int, default=1000, help="계획 파일 하나의 판 수")
    p.add_argument("--threads", type=int, default=6, help="Java 스레드 수")
    p.add_argument("--max-ms", type=int, default=600_000, help="한 판의 시간 상한 (넘으면 무승부)")
    p.add_argument("--seed-base", type=int, default=600_000_000, help="판 시드 = seed-base + 판 번호")
    p.add_argument("--models", default="models", help="복사해 쓸 모델 묶음 (저장소 기준)")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    data_dir = run_dir / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    models = run_dir / "models"
    if not (models / "manifest.json").exists():
        src = ROOT / args.models
        if not (src / "manifest.json").exists():
            raise SystemExit(f"모델 묶음이 없음: {src} (python python/tools/make_bundle.py --p05-personas)")
        shutil.copytree(src, models, dirs_exist_ok=True)
    (data_dir / "config.json").write_text(json.dumps(vars(args), ensure_ascii=False, indent=1) + "\n",
                                          encoding="utf-8")

    chunks = (args.matches + args.chunk - 1) // args.chunk
    start = time.time()
    for k in range(chunks):
        out = f"chunk-{k:03d}"
        if (data_dir / out / "summary.json").exists():
            print(f"[timed] {out} 있음, 건너뜀", flush=True)
            continue
        lo, hi = k * args.chunk, min((k + 1) * args.chunk, args.matches)
        plan = {"format": "timedplan-1", "run": args.run, "models": "../models", "seed_base": args.seed_base,
                "first": lo, "count": hi - lo, "threads": args.threads, "max_ms": args.max_ms, "out": out}
        summary = run_java(plan, data_dir / f"plan-{k:03d}.json", run_dir, main_class=MAIN_CLASS,
                           done_prefix="[timed] 끝")
        print(f"[timed] {out}: {hi - lo}판, 누적 {time.time() - start:.0f}초 | {summary}", flush=True)


if __name__ == "__main__":
    main()
