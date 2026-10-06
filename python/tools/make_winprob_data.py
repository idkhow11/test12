"""승률 예측망의 학습 자료(2인 대전 기록)를 Java 대전 러너로 만든다 (docs/TRAINING.md 10장).

    python python/tools/make_winprob_data.py --run wp01 --matches 40000

여러 세대의 가치망(기본: runs/r01/models)과 기준 AI 를 여러 온도로 섞은 선수 명단을 만들고, 판마다 두 선수를 뽑아
계획 파일(plan-1, TRAINING.md 6.1)을 묶음 단위로 만들어 실행의 러너 사본(runs/<실행 이름>/runner, TRAINING.md 12.3)으로
실행한다. 결과는

    runs/<실행 이름>/data/plan-<묶음>.json      계획 파일
    runs/<실행 이름>/data/chunk-<묶음>/         기록 w<스레드>.jsonl.gz 와 summary.json
    runs/<실행 이름>/data/config.json           이 스크립트의 설정

이다. 학습·검증·시험 나누기는 판의 시드로 정한다(tetris_ai.winprob.split_of). 이미 있는 묶음은 건너뛰므로,
중간에 멈췄으면 같은 명령을 다시 실행하면 이어서 만든다.

선수 짝 짓기: 반은 명단 전체에서 아무나 두 명, 반은 같은 온도에서 세대가 이웃한(같은 것 포함) 두 명을 뽑는다.
한쪽으로 크게 기우는 판과 팽팽한 판이 모두 들어가게 하기 위해서다. 자리는 무작위다.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai.runner import rel, run_java  # noqa: E402

BASELINE = "baseline"


def player_pool(models_dir: Path, gens: list[int], temps: list[float], plan_dir: Path) -> tuple[dict, list[list[str]]]:
    """선수 명단(plan-1 의 players)과, 온도마다 세대 순서로 늘어놓은 선수 이름 표 [온도][세대]."""
    players: dict[str, dict] = {}
    table: list[list[str]] = []
    for t in temps:
        row = []
        tag = f"t{int(round(t * 10)):02d}"
        names = [(BASELINE, None)] + [(f"g{g:03d}", g) for g in gens]
        for label, g in names:
            name = f"{label}@{tag}"
            if g is None:
                players[name] = {"model": BASELINE, "temperature": t}
            else:
                model = models_dir / f"value_ai00_gen-{g:03d}.onnx"
                if not model.exists():
                    raise SystemExit(f"모델이 없음: {model}")
                players[name] = {"persona": "neutral", "model": rel(model, plan_dir), "temperature": t}
            row.append(name)
        table.append(row)
    return players, table


def pair(rng: np.random.Generator, table: list[list[str]], close_ratio: float) -> tuple[str, str]:
    """두 선수. close_ratio 확률로 같은 온도·이웃 세대(같은 선수 포함), 아니면 명단 전체에서 아무나."""
    ti = int(rng.integers(len(table)))
    gi = int(rng.integers(len(table[ti])))
    a = table[ti][gi]
    if rng.random() < close_ratio:
        gj = int(np.clip(gi + rng.integers(-1, 2), 0, len(table[ti]) - 1))
        b = table[ti][gj]
    else:
        tj = int(rng.integers(len(table)))
        b = table[tj][int(rng.integers(len(table[tj])))]
    return (a, b) if rng.random() < 0.5 else (b, a)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", default="wp01", help="결과 폴더 runs/<run>")
    p.add_argument("--models-run", default="r01", help="가치망을 가져올 학습 실행 runs/<models-run>/models")
    p.add_argument("--gens", default="0,2,5,10,20,38,55,79", help="쓸 세대 (쉼표로 구분)")
    p.add_argument("--temps", default="0,0.3,0.6,1.0", help="고르기 온도 τ (쉼표로 구분)")
    p.add_argument("--matches", type=int, default=40000, help="전체 판 수")
    p.add_argument("--chunk", type=int, default=2000, help="계획 파일 하나의 판 수")
    p.add_argument("--threads", type=int, default=3, help="Java 러너 스레드 수")
    p.add_argument("--max-turns", type=int, default=1000)
    p.add_argument("--close-ratio", type=float, default=0.5, help="이웃한 선수끼리 짝 짓는 비율")
    p.add_argument("--seed-base", type=int, default=500_000_000, help="판 시드 = seed-base + 판 번호")
    p.add_argument("--seed", type=int, default=0, help="짝 짓기 난수 시드")
    args = p.parse_args()

    data_dir = ROOT / "runs" / args.run / "data"
    data_dir.mkdir(parents=True, exist_ok=True)
    gens = [int(x) for x in args.gens.split(",")]
    temps = [float(x) for x in args.temps.split(",")]
    players, table = player_pool(ROOT / "runs" / args.models_run / "models", gens, temps, data_dir)
    config = {**vars(args), "players": len(players)}
    (data_dir / "config.json").write_text(json.dumps(config, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    rng = np.random.default_rng(args.seed)
    pairs = [pair(rng, table, args.close_ratio) for _ in range(args.matches)]  # 묶음과 상관없이 같은 짝
    chunks = (args.matches + args.chunk - 1) // args.chunk
    start = time.time()
    for k in range(chunks):
        out = f"chunk-{k:03d}"
        if (data_dir / out / "summary.json").exists():
            print(f"[data] {out} 있음, 건너뜀")
            continue
        lo, hi = k * args.chunk, min((k + 1) * args.chunk, args.matches)
        plan = {
            "format": "plan-1", "run": args.run, "gen": k, "max_turns": args.max_turns, "threads": args.threads,
            "out": out, "players": players,
            "matches": [{"seed": args.seed_base + i, "p1": pairs[i][0], "p2": pairs[i][1]} for i in range(lo, hi)],
        }
        summary = run_java(plan, data_dir / f"plan-{k:03d}.json", data_dir.parent)
        print(f"[data] {out}: {hi - lo}판, 누적 {time.time() - start:.0f}초 | {summary[:160]}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
