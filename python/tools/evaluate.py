"""모델 하나를 많은 판으로 다시 잰다 (docs/TRAINING.md 8.1): 고정 시드마다 자리를 바꿔 두 판, 양쪽 τ = 0.

    python python/tools/evaluate.py --model runs/r01/models/value_ai00_gen-038.onnx --seeds 930001:930500
    python python/tools/evaluate.py --checkpoint runs/r01/checkpoints/gen-038.pt --prior-weight 0.02 --seeds 930001:930500
    python python/tools/evaluate.py --model <a.onnx> --opponent <b.onnx> --seeds 930001:930500 --search '{"beam": 8}'

학습 루프의 세대 평가(200판)는 최고 세대를 고르는 데 쓰므로 운이 섞인다. 최종 실력은 학습·평가에 쓰지 않은 시드로
이 도구를 써서 잰다. 결과는 --out 폴더(기본 runs/eval/<시각>)의 eval.json 에 남는다.
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
sys.path.insert(0, str(Path(__file__).resolve().parent))

import train_loop  # noqa: E402
from tetris_ai.metrics import summarize  # noqa: E402
from tetris_ai.records import read_matches, trajectories  # noqa: E402

ME = "ai00"
OPP = "opp"


def parse_seeds(text: str) -> range:
    """'A:B' (양 끝 포함) → range."""
    a, b = (int(s) for s in text.split(":"))
    if b < a:
        raise ValueError(f"시드 범위가 거꾸로임: {text}")
    return range(a, b + 1)


def interval95(p: float, n: int) -> float:
    """승률 p 의 95% 범위 반폭(정규 근사). 무승부를 0.5 로 센 승률에도 그대로 쓴다(PROGRESS 0.5 와 같은 방법)."""
    return 1.96 * math.sqrt(max(p * (1 - p), 0.0) / n) if n > 0 else float("nan")


def export_checkpoint(ckpt_path: Path, prior_weight: float | None, out: Path) -> Path:
    """체크포인트를 그 실행의 config.json 설정으로 내보낸다. prior_weight 를 주면 β 만 바꿔 내보낸다(학습은 그대로)."""
    import torch

    from tetris_ai import net as netlib

    run_dir = ckpt_path.parent.parent
    cfg = json.loads((run_dir / "config.json").read_text(encoding="utf-8"))
    ckpt = torch.load(ckpt_path, map_location="cpu")
    net = netlib.ValueNet(**ckpt["config"])
    net.load_state_dict(ckpt["net"])
    gen = int(ckpt["gen"])
    beta = train_loop.prior_at(cfg, gen) if prior_weight is None else prior_weight
    module = netlib.ScoreModule(net.eval(), 0, train_loop.train_weights(cfg, gen), cfg["gamma"], beta)
    path = out / f"value_{ME}_{run_dir.name}_gen-{gen:03d}_beta-{beta:g}.onnx"
    netlib.export_onnx(module, path, {
        "tetris.persona": cfg["persona"],
        "tetris.stage": f"gen-{gen:03d}",
        "tetris.description": f"TD(λ) 가치망, 실행 {run_dir.name}, 세대 {gen}, β {beta:g}",
    })
    return path


def player(model: str, out: Path, search: dict | None) -> dict:
    p = {"model": "baseline" if model == "baseline" else train_loop.rel(Path(model).resolve(), out), "temperature": 0.0}
    if search:
        p["search"] = search
    return p


def evaluate(model: str, opponent: str, seeds: range, out: Path, threads: int, max_turns: int,
             search: dict | None, opp_search: dict | None, keep_matches: bool = False,
             runner_home: Path | None = None) -> dict:
    """runner_home/runner 의 러너 사본으로 잰다(TRAINING.md 12.3). 없으면 out 에 만든다."""
    out.mkdir(parents=True, exist_ok=True)
    matches = []
    for seed in seeds:
        matches.append({"seed": seed, "p1": ME, "p2": OPP})
        matches.append({"seed": seed, "p1": OPP, "p2": ME})
    plan = {
        "format": "plan-1", "run": "eval", "gen": 0, "max_turns": max_turns, "threads": threads, "out": "matches",
        "players": {ME: player(model, out, search), OPP: player(opponent, out, opp_search)}, "matches": matches,
    }
    t0 = time.time()
    line = train_loop.run_java(plan, out / "plan.json", runner_home or out)
    seconds = time.time() - t0
    trajs = []
    for m in read_matches(sorted((out / "matches").glob("*.jsonl.gz"))):
        trajs.extend(trajectories(m, {ME}))
    ev = summarize(trajs)
    by_seat = {side: summarize([t for t in trajs if t.side == side]) for side in ("p1", "p2")}
    result = {
        "model": model, "opponent": opponent, "search": search, "opp_search": opp_search,
        "seeds": f"{seeds.start}:{seeds.stop - 1}", "games": ev["matches"],
        "win_rate": ev["win_rate"], "ci95": interval95(ev["win_rate"], ev["matches"]),
        "win_rate_p1": by_seat["p1"]["win_rate"], "win_rate_p2": by_seat["p2"]["win_rate"],
        "seconds": round(seconds, 1), "java": line, "metrics": ev,
    }
    (out / "eval.json").write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    if not keep_matches:
        train_loop.rmtree(out / "matches")
    return result


def main() -> None:
    p =argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--model", help="잴 ONNX 모델 파일")
    src.add_argument("--checkpoint", help="잴 체크포인트(.pt). 그 실행의 config.json 으로 내보낸 뒤 잰다")
    p.add_argument("--prior-weight", type=float, default=None, help="--checkpoint 를 내보낼 때 쓸 β (기본: 실행 설정)")
    p.add_argument("--opponent", default="baseline", help="상대: baseline 또는 ONNX 모델 파일 (기본 baseline)")
    p.add_argument("--seeds", required=True, help="시드 범위 A:B (양 끝 포함). 시드마다 자리를 바꿔 두 판")
    p.add_argument("--search", default=None, help='잴 모델의 탐색 설정 JSON, 예: \'{"beam": 8}\' (TRAINING.md 6.2)')
    p.add_argument("--opp-search", default=None, help="상대 모델의 탐색 설정 JSON")
    p.add_argument("--threads", type=int, default=6)
    p.add_argument("--max-turns", type=int, default=1000)
    p.add_argument("--out", default=None, help="결과 폴더 (기본 runs/eval/<시각>)")
    p.add_argument("--keep-matches", action="store_true", help="대전 기록을 지우지 않고 남긴다")
    p.add_argument("--runner-home", default=None, help="러너 사본을 둘(또는 이미 있는) 폴더. 기본: --out 폴더")
    args = p.parse_args()

    out = Path(args.out).resolve() if args.out else ROOT / "runs" / "eval" / time.strftime("%Y%m%d-%H%M%S")
    model = args.model
    if args.checkpoint:
        model = str(export_checkpoint(Path(args.checkpoint).resolve(), args.prior_weight, out))
    r = evaluate(model, args.opponent, parse_seeds(args.seeds), out, args.threads, args.max_turns,
                 json.loads(args.search) if args.search else None,
                 json.loads(args.opp_search) if args.opp_search else None, args.keep_matches,
                 Path(args.runner_home).resolve() if args.runner_home else None)
    m = r["metrics"]
    print(f"{Path(r['model']).name} vs {Path(r['opponent']).name}: 승률 {r['win_rate']:.3f} ± {r['ci95']:.3f} "
          f"({r['games']}판, 이김 {m['wins']} 짐 {m['losses']} 비김 {m['draws']}, P1 {r['win_rate_p1']:.3f} "
          f"P2 {r['win_rate_p2']:.3f}) | 평균 {m['turns_mean']:.0f}턴, APP {m['app']:.3f}, 최고 높이 "
          f"{m['max_height_mean']:.2f}, 구멍 {m['holes_mean']:.2f} | {r['seconds']:.0f}초 → {out / 'eval.json'}")


if __name__ == "__main__":
    main()
