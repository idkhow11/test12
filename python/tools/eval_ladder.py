"""난이도 사다리 재기 (docs/TRAINING.md 11장): 학습량이 다른 모델들의 실력을 같은 시드로 잰다.

    python python/tools/eval_ladder.py --run ladder01
    python python/tools/eval_ladder.py --run ladder01 --seeds 1:50 --threads 6

후보마다 기준 AI 와 둔다(시드마다 자리를 바꿔 두 판, 양쪽 τ 는 후보 설정대로, 기준 AI 는 τ = 0). 그다음 후보를 정한 순서로
이웃끼리(앞 ↔ 뒤) 맞붙여, 뒤 후보가 앞 후보를 정말 이기는지 본다. 결과는

    runs/<run>/candidates.json        후보 목록 (이 스크립트의 CANDIDATES 또는 --candidates 파일)
    runs/<run>/vs_baseline/           계획과 기록, summary.json
    runs/<run>/neighbors/             이웃 대전 계획과 기록
    runs/<run>/ladder.json            후보마다 기준 AI 상대 승률·95% 범위·행동 지표, 이웃 대전 승률

이다. 이미 끝난 단계(summary.json 이 있는 폴더)는 건너뛴다. Java 는 실행의 러너 사본(runs/<run>/runner)으로 돌린다.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai.metrics import summarize  # noqa: E402
from tetris_ai.records import read_matches, trajectories  # noqa: E402
from tetris_ai.runner import rel, run_java  # noqa: E402

BASELINE = "baseline"
SEED0 = 940001  # 사다리 시드. 학습(세대 × 1,000,000 대)·평가(900001~)·분석(920001~)과 겹치지 않는다

# 학습량 순서의 후보 [초안]. model 은 저장소 기준 경로 또는 "baseline". 세대 0 은 가치망이 0 이라 기준 AI 와 같다.
CANDIDATES: list[dict] = [
    {"name": "r01-g000-t1.0", "model": "runs/r01/models/value_ai00_gen-000.onnx", "temperature": 1.0},
    {"name": "r01-g000-t0.5", "model": "runs/r01/models/value_ai00_gen-000.onnx", "temperature": 0.5},
    {"name": "r01-g000", "model": "runs/r01/models/value_ai00_gen-000.onnx", "temperature": 0.0},
    {"name": "r01-g003", "model": "runs/r01/models/value_ai00_gen-003.onnx", "temperature": 0.0},
    {"name": "r01-g010", "model": "runs/r01/models/value_ai00_gen-010.onnx", "temperature": 0.0},
    {"name": "r01-g038", "model": "runs/r01/models/value_ai00_gen-038.onnx", "temperature": 0.0},
    {"name": "a2-g052", "model": "runs/a2/models/value_ai00_gen-052.onnx", "temperature": 0.0},
    {"name": "a2-g052-beam8", "model": "runs/a2/models/value_ai00_gen-052.onnx", "temperature": 0.0,
     "search": {"beam": 8}},
]


def interval95(p: float, n: int) -> float:
    return 1.96 * math.sqrt(max(p * (1 - p), 0.0) / n) if n > 0 else float("nan")


def player(c: dict, plan_dir: Path) -> dict:
    model = c["model"] if c["model"] == BASELINE else rel(ROOT / c["model"], plan_dir)
    p = {"persona": "neutral", "model": model, "temperature": c.get("temperature", 0.0)}
    if c.get("search"):
        p["search"] = c["search"]
    return p


def play(stage_dir: Path, run_dir: Path, run: str, players: dict, pairs: list[tuple[str, str]], seeds: range,
         threads: int, max_turns: int) -> None:
    """pairs 의 (a, b) 마다 시드별로 자리를 바꿔 두 판. 이미 끝났으면 건너뛴다."""
    if (stage_dir / "matches" / "summary.json").exists():
        print(f"[ladder] {stage_dir.name} 있음, 건너뜀")
        return
    matches = [{"seed": s, "p1": x, "p2": y} for a, b in pairs for s in seeds for x, y in ((a, b), (b, a))]
    plan = {"format": "plan-1", "run": run, "gen": 0, "max_turns": max_turns, "threads": threads, "out": "matches",
            "players": players, "matches": matches}
    t0 = time.time()
    line = run_java(plan, stage_dir / "plan.json", run_dir)
    print(f"[ladder] {stage_dir.name}: {len(matches)}판 {time.time() - t0:.0f}초 | {line[:200]}", flush=True)


def head_to_head(stage_dir: Path) -> dict[tuple[str, str], dict]:
    """기록에서 (AI, 상대) 마다의 궤적 지표. 같은 AI 끼리 둔 판은 없다."""
    by_pair: dict[tuple[str, str], list] = {}
    for m in read_matches(sorted((stage_dir / "matches").glob("*.jsonl.gz"))):
        names = [p["ai"] for p in m["players"]]
        for t in trajectories(m):
            other = names[1] if t.side == "p1" else names[0]
            by_pair.setdefault((t.ai, other), []).append(t)
    return {k: summarize(v) for k, v in by_pair.items()}


def parse_seeds(text: str) -> range:
    a, b = (int(x) for x in text.split(":"))
    return range(a, b + 1)


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True, help="결과 폴더 runs/<run>")
    p.add_argument("--candidates", help="후보 목록 JSON 파일 (기본: 이 스크립트의 CANDIDATES)")
    p.add_argument("--seeds", default="1:100", help="시드 번호 범위 A:B (SEED0 에 더한다). 시드마다 두 판")
    p.add_argument("--threads", type=int, default=6)
    p.add_argument("--max-turns", type=int, default=1000)
    p.add_argument("--no-neighbors", action="store_true", help="이웃 대전을 하지 않는다")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    run_dir.mkdir(parents=True, exist_ok=True)
    cands = json.loads(Path(args.candidates).read_text(encoding="utf-8")) if args.candidates else CANDIDATES
    names = [c["name"] for c in cands]
    if len(set(names)) != len(names) or BASELINE in names:
        raise SystemExit("후보 이름이 겹치거나 baseline 임")
    (run_dir / "candidates.json").write_text(json.dumps(cands, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    sn = parse_seeds(args.seeds)
    seeds = range(SEED0 + sn.start - 1, SEED0 + sn.stop - 1)

    stage = run_dir / "vs_baseline"
    players = {c["name"]: player(c, stage) for c in cands}
    players[BASELINE] = {"persona": "neutral", "model": BASELINE, "temperature": 0.0}
    play(stage, run_dir, args.run, players, [(n, BASELINE) for n in names], seeds, args.threads, args.max_turns)
    vs_base = head_to_head(stage)

    neighbors = {}
    if not args.no_neighbors and len(cands) > 1:
        stage = run_dir / "neighbors"
        players = {c["name"]: player(c, stage) for c in cands}
        pairs = list(zip(names[1:], names[:-1]))
        play(stage, run_dir, args.run, players, pairs, seeds, args.threads, args.max_turns)
        neighbors = head_to_head(stage)

    rows = []
    print(f"\n{'후보':16s} {'기준 AI 상대':>14s} {'앞 후보 상대':>14s}  턴    APP   최고높이 구멍")
    for i, c in enumerate(cands):
        ev = vs_base[(c["name"], BASELINE)]
        row = {**c, "games": ev["matches"], "win_rate": ev["win_rate"], "ci95": interval95(ev["win_rate"], ev["matches"]),
               "metrics": ev}
        line = f"{c['name']:16s} {ev['win_rate']:6.3f} ± {row['ci95']:.3f}"
        if i > 0 and (c["name"], names[i - 1]) in neighbors:
            nb = neighbors[(c["name"], names[i - 1])]
            row["vs_previous"] = {"opponent": names[i - 1], "games": nb["matches"], "win_rate": nb["win_rate"],
                                  "ci95": interval95(nb["win_rate"], nb["matches"])}
            line += f"   {nb['win_rate']:6.3f} ± {row['vs_previous']['ci95']:.3f}"
        else:
            line += " " * 17
        rows.append(row)
        print(f"{line}  {ev['turns_mean']:5.1f} {ev['app']:.3f}  {ev['max_height_mean']:5.2f}  {ev['holes_mean']:5.2f}")
    out = {"seeds": f"{seeds.start}:{seeds.stop - 1}", "candidates": rows}
    (run_dir / "ladder.json").write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"→ {run_dir / 'ladder.json'}")


if __name__ == "__main__":
    main()
