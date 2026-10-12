"""학습 루프 (docs/TRAINING.md 2장): 세대마다 대전(Java) → TD(λ) 학습 → ONNX 내보내기 → 평가(Java).

    python python/tools/train_loop.py --run r01 --gens 30          # 세대 0 부터 새로 시작
    python python/tools/train_loop.py --run r01 --gens 60 --resume # 마지막으로 끝난 세대 다음부터
    python python/tools/train_loop.py --run a1 --gens 69 --init-from r01/38 --prior-weight 0.01
                                                                   # 다른 실행의 세대 38 에서 이어 새 실행으로

세대 g 의 대전은 세대 g-1 이 끝날 때 내보낸 모델이 둔다(세대 0 은 기준 AI 끼리). 그 기록으로 학습해 세대 g 의 모델을 만든다.
결과는 runs/<run>/ 에 남는다 (git 제외, TRAINING.md 7.1, 12장).

    config.json                       실행 설정
    log.csv, train.log                세대마다 한 줄 요약과 자세한 기록
    models/value_ai00_gen-XXX.onnx    세대마다 내보낸 모델 (이전 세대 상대로도 쓴다)
    checkpoints/gen-XXX.pt            신경망·최적화기 상태 (최근 3개와 최고 세대만 남긴다. --init-from 실행의 첫 세대는
                                      원래 실행의 체크포인트에서 시작한다)
    gen-XXX/plan.json, matches/       학습용 대전 계획과 기록 (기록은 최근 window 세대만 남긴다)
    gen-XXX/eval/plan.json, eval.json 평가 대전 계획과 결과
"""

from __future__ import annotations

import argparse
import copy
import csv
import json
import os
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai import net as netlib  # noqa: E402
from tetris_ai.metrics import summarize  # noqa: E402
from tetris_ai.records import read_matches, trajectories  # noqa: E402
from tetris_ai.runner import rel, rmtree, run_java  # noqa: E402,F401  (evaluate.py 가 train_loop.run_java 로 쓴다)
from tetris_ai.train import Dataset, lambda_returns, predict_values, rewards, train_epochs  # noqa: E402

LEARNER = "ai00"
BASELINE = "baseline"
EVAL_SEED_START = 900001  # TRAINING.md 8장: 900001 부터 eval_seeds 개

# [초안] 수치 (TRAINING.md 4~6, 8장). 실행할 때 config.json 에 그대로 남긴다.
DEFAULTS = dict(
    matches=512,
    max_turns=1000,
    threads=os.cpu_count() or 4,
    temperature=0.3,
    pool_self=0.5,
    pool_past=0.3,
    past_window=5,
    gamma=1.0,
    lam=0.8,
    window=4,
    epochs=2,
    epochs_gen0=5,
    lr=3e-4,
    weight_decay=1e-4,
    batch=1024,
    shaping=0.005,
    shaping_until=20,
    prior_weight=netlib.PRIOR_WEIGHT,
    # 아래 None 은 "바꾸지 않음"이다. 기본값은 r01 과 같은 학습을 한다 (TRAINING.md 5.2, 3.5).
    prior_final=None,  # 주면 β 를 prior_from 세대의 prior_weight 에서 prior_until 세대의 이 값으로 곧게 바꾼다
    prior_from=0,
    prior_until=0,
    lr_final=None,  # 주면 학습률을 lr_from 세대의 lr 에서 lr_until 세대의 이 값으로 등비로 줄인다
    lr_from=0,
    lr_until=0,
    eval_seeds=100,  # 세대 평가의 시드 수. 자리를 바꿔 두 판씩 두므로 판 수는 두 배
    persona="neutral",
    seed=0,
)

LOG_FIELDS = [
    "gen", "samples", "loss_first", "loss_last", "win_rate", "turns_mean", "app", "sent_per_piece",
    "lines_per_piece", "tspin_rate", "tetris_rate", "max_height_mean", "holes_mean",
    "sec_play", "sec_train", "sec_eval", "play_turns_mean", "best_gen", "win_rate_vs_best",
    "loss_new", "loss_seen", "prior_weight", "lr",
]
BEST = f"{LEARNER}@best"  # 평가에서 지금까지의 최고 세대 (TRAINING.md 8장)


def log(run_dir: Path, text: str) -> None:
    line = time.strftime("%H:%M:%S ") + text
    print(line, flush=True)
    with open(run_dir / "train.log", "a", encoding="utf-8") as f:
        f.write(line + "\n")


def model_path(run_dir: Path, gen: int) -> Path:
    return run_dir / "models" / f"value_{LEARNER}_gen-{gen:03d}.onnx"


def shaping_at(cfg: dict, gen: int) -> float:
    """보조 보상(공격 1줄당) — 처음 세대들에만 주고 shaping_until 세대까지 0 으로 줄인다 (TRAINING.md 4.2)."""
    return cfg["shaping"] * max(0.0, 1.0 - gen / cfg["shaping_until"])


def ramp(gen: int, start: int, until: int) -> float:
    """세대 start 까지 0, until 부터 1, 그 사이는 곧게 오른다. until <= start 면 start 세대부터 바로 1."""
    if until <= start:
        return 1.0 if gen >= start else 0.0
    return min(1.0, max(0.0, (gen - start) / (until - start)))


def prior_at(cfg: dict, gen: int) -> float:
    """세대 gen 의 기준 평가 비중 β (TRAINING.md 3.5). prior_final 이 없으면 prior_weight 그대로."""
    final = cfg.get("prior_final")
    if final is None:
        return cfg["prior_weight"]
    t = ramp(gen, cfg.get("prior_from", 0), cfg.get("prior_until", 0))
    return cfg["prior_weight"] + (final - cfg["prior_weight"]) * t


def lr_at(cfg: dict, gen: int) -> float:
    """세대 gen 의 학습률 (TRAINING.md 5.2). lr_final 이 없으면 lr 그대로, 있으면 등비로 줄인다."""
    final = cfg.get("lr_final")
    if final is None:
        return cfg["lr"]
    return cfg["lr"] * (final / cfg["lr"]) ** ramp(gen, cfg.get("lr_from", 0), cfg.get("lr_until", 0))


def train_weights(cfg: dict, gen: int) -> torch.Tensor:
    w = netlib.style_weights(cfg["persona"])
    w[netlib.REWARD_TERMS.index("attack")] += shaping_at(cfg, gen)
    return w


def make_plan(cfg: dict, run: str, gen: int, run_dir: Path, gen_dir: Path) -> dict:
    """학습용 대전 계획 (TRAINING.md 6장). 상대는 지금 세대·이전 세대·기준 AI 를 섞고, 자리는 번갈아 맡는다."""
    tau = cfg["temperature"]
    players: dict[str, dict] = {BASELINE: {"model": "baseline", "temperature": tau}}
    matches = []
    if gen == 0:
        for i in range(cfg["matches"]):
            matches.append({"seed": i, "p1": BASELINE, "p2": BASELINE})
    else:
        persona = cfg["persona"]
        players[LEARNER] = {"persona": persona, "model": rel(model_path(run_dir, gen - 1), gen_dir), "temperature": tau}
        past = list(range(max(0, gen - 1 - cfg["past_window"]), gen - 1))
        for k in past:
            players[f"{LEARNER}@gen-{k:03d}"] = {
                "persona": persona, "model": rel(model_path(run_dir, k), gen_dir), "temperature": tau}
        rng = np.random.default_rng([cfg["seed"], gen])
        for i in range(cfg["matches"]):
            u = rng.random()
            if u < cfg["pool_self"] or (not past and u < cfg["pool_self"] + cfg["pool_past"]):
                opp = LEARNER
            elif u < cfg["pool_self"] + cfg["pool_past"]:
                opp = f"{LEARNER}@gen-{int(rng.choice(past)):03d}"
            else:
                opp = BASELINE
            p1, p2 = (LEARNER, opp) if i % 2 == 0 else (opp, LEARNER)
            matches.append({"seed": gen * 1_000_000 + i, "p1": p1, "p2": p2})
    return {
        "format": "plan-1", "run": run, "gen": gen, "max_turns": cfg["max_turns"], "threads": cfg["threads"],
        "out": "matches", "players": players, "matches": matches,
    }


def make_eval_plan(cfg: dict, run: str, gen: int, run_dir: Path, eval_dir: Path, best_gen: int | None) -> dict:
    """평가 (TRAINING.md 8장): 기준 AI, 그리고 지금까지의 최고 세대와 고정 시드마다 자리를 바꿔 두 판씩. 양쪽 τ = 0."""
    players = {
        LEARNER: {"persona": cfg["persona"], "model": rel(model_path(run_dir, gen), eval_dir), "temperature": 0.0},
        BASELINE: {"model": "baseline", "temperature": 0.0},
    }
    opponents = [BASELINE]
    if best_gen is not None and best_gen != gen:
        players[BEST] = {"persona": cfg["persona"], "model": rel(model_path(run_dir, best_gen), eval_dir),
                         "temperature": 0.0}
        opponents.append(BEST)
    matches = []
    seeds = range(EVAL_SEED_START, EVAL_SEED_START + cfg.get("eval_seeds", 100))
    for opp in opponents:
        for seed in seeds:
            matches.append({"seed": seed, "p1": LEARNER, "p2": opp})
            matches.append({"seed": seed, "p1": opp, "p2": LEARNER})
    return {
        "format": "plan-1", "run": run, "gen": gen, "max_turns": cfg["max_turns"], "threads": cfg["threads"],
        "out": "matches", "players": players, "matches": matches,
    }


def load_dataset(gen_dir: Path, gen: int) -> Dataset:
    trainable = {BASELINE} if gen == 0 else {LEARNER}
    files = sorted((gen_dir / "matches").glob("*.jsonl.gz"))
    trajs = [t for m in read_matches(files) for t in trajectories(m, trainable)]
    return Dataset.build(trajs, {name: 0 for name in trainable})


def export_and_verify(net: netlib.ValueNet, weights: torch.Tensor, cfg: dict, gen: int, path: Path, ds: Dataset) -> float:
    """세대 모델을 내보내고(MODEL_IO.md 6장), 학습 자료 일부로 PyTorch 와 onnxruntime 의 점수가 같은지 확인한다."""
    module = netlib.ScoreModule(copy.deepcopy(net).cpu().eval(), 0, weights, cfg["gamma"], prior_at(cfg, gen))
    netlib.export_onnx(module, path, {
        "tetris.persona": cfg["persona"],
        "tetris.stage": f"gen-{gen:03d}",
        "tetris.description": f"TD(λ) 가치망, 실행 {path.parent.parent.name}, 세대 {gen}",
    })
    rng = np.random.default_rng(gen)
    idx = np.sort(rng.choice(len(ds), size=min(256, len(ds)), replace=False))
    inputs = {
        "board": ds.boards[idx].astype(np.float32),
        "features": ds.features[idx],
        "opp_board": ds.opp_boards[idx].astype(np.float32),
        "opp_features": ds.opp_features[idx],
    }
    with torch.no_grad():
        want = module(*(torch.from_numpy(inputs[k]) for k in netlib.INPUT_NAMES)).numpy()
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    (got,) = session.run(["value"], inputs)
    diff = float(np.max(np.abs(got - want)))
    if got.shape != (len(idx), 1) or diff > 1e-4:
        raise RuntimeError(f"내보낸 모델이 PyTorch 와 다름: 모양 {got.shape}, 최대 차이 {diff}")
    return diff


def held_out_losses(values: np.ndarray, targets: np.ndarray, new: int) -> tuple[float, float]:
    """학습 전 신경망의 손실: 이번 세대 기록(아직 배우지 않은 끝의 new 개)과 이전 세대 기록(이미 배운 것).

    둘의 차이가 크면 판의 결과를 외우고 있다는 뜻이다(과적합, TRAINING.md 5.3). 없는 쪽은 nan.
    """
    err = (values - targets) ** 2
    seen = err[: len(err) - new]
    return (float(err[len(err) - new:].mean()) if new else float("nan"),
            float(seen.mean()) if len(seen) else float("nan"))


def copy_init(src_dir: Path, src_gen: int, run_dir: Path, past_window: int) -> None:
    """--init-from: 원래 실행의 모델 중 이전 세대 상대로 쓸 것(src_gen - past_window ~ src_gen)을 새 실행으로 복사한다."""
    (run_dir / "models").mkdir(parents=True, exist_ok=True)
    for k in range(max(0, src_gen - past_window), src_gen + 1):
        shutil.copy2(model_path(src_dir, k), model_path(run_dir, k))


def last_finished_gen(run_dir: Path) -> int:
    path = run_dir / "log.csv"
    if not path.exists():
        return -1
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return int(rows[-1]["gen"]) if rows else -1


def append_log(run_dir: Path, row: dict) -> None:
    """log.csv 에 한 줄을 붙인다. 열이 늘어난 옛 파일이면 새 머리로 다시 쓴다(빈 칸은 비워 둔다)."""
    path = run_dir / "log.csv"
    rows = []
    if path.exists():
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            if reader.fieldnames != LOG_FIELDS:
                rows = list(reader)
    mode = "w" if rows or not path.exists() else "a"
    with open(path, mode, encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=LOG_FIELDS, restval="")
        if mode == "w":
            w.writeheader()
            w.writerows({k: r.get(k, "") for k in LOG_FIELDS} for r in rows)
        w.writerow(row)


def opponent_of(m: dict, me: str) -> str:
    """기록 한 판에서 me 의 상대 이름. 같은 AI 끼리면 me."""
    names = [p["ai"] for p in m["players"]]
    return names[1] if names[0] == me else names[0]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True, help="실행 이름 (runs/<run>)")
    p.add_argument("--gens", type=int, required=True, help="이 세대 번호 전까지 학습한다")
    p.add_argument("--resume", action="store_true", help="마지막으로 끝난 세대 다음부터 이어서")
    p.add_argument("--init-from", default=None,
                   help="다른 실행의 세대 체크포인트에서 새 실행을 시작한다: <실행>/<세대>, 예 r01/38. "
                        "설정은 그 실행의 config.json 을 바탕으로 하고 세대 번호를 이어 간다")
    for k, v in DEFAULTS.items():
        kind = float if v is None else type(v)
        p.add_argument(f"--{k.replace('_', '-')}", type=kind, default=None, help=f"기본 {v}")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    if args.resume:
        cfg = {**DEFAULTS, **json.loads((run_dir / "config.json").read_text(encoding="utf-8"))}
    else:
        if (run_dir / "config.json").exists():
            raise SystemExit(f"{run_dir} 에 이미 실행이 있음. 이어서 하려면 --resume")
        cfg = dict(DEFAULTS)
        if args.init_from:
            src_name, src_gen = args.init_from.split("/")
            cfg.update(json.loads((ROOT / "runs" / src_name / "config.json").read_text(encoding="utf-8")))
            cfg["init_from"] = f"{src_name}/{int(src_gen)}"
    for k in DEFAULTS:
        v = getattr(args, k)
        if v is not None:
            cfg[k] = v
    # --init-from 실행은 원래 실행의 세대 init_gen 다음부터 세대 번호를 잇는다(시드, 보조 보상 일정이 그대로 이어진다).
    init_gen, init_dir = -1, None
    if cfg.get("init_from"):
        src_name, src_gen = cfg["init_from"].split("/")
        init_gen, init_dir = int(src_gen), ROOT / "runs" / src_name
    start = max(last_finished_gen(run_dir), init_gen) + 1
    run_dir.mkdir(parents=True, exist_ok=True)
    if init_dir is not None and start == init_gen + 1:
        copy_init(init_dir, init_gen, run_dir, cfg["past_window"])
    (run_dir / "config.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(cfg["seed"])
    net = netlib.ValueNet(n_ai=1).to(device)
    opt = torch.optim.AdamW(net.parameters(), lr=cfg["lr"], weight_decay=cfg["weight_decay"])
    if start > 0:
        ckpt_dir = run_dir if start - 1 > init_gen else init_dir
        ckpt = torch.load(ckpt_dir / "checkpoints" / f"gen-{start - 1:03d}.pt", map_location=device)
        net.load_state_dict(ckpt["net"])
        opt.load_state_dict(ckpt["optimizer"])
    log(run_dir, f"실행 {args.run}: 세대 {start} 부터 {args.gens} 전까지, 장치 {device}"
        f"{' (' + torch.cuda.get_device_name(0) + ')' if device.type == 'cuda' else ''}, "
        f"매개변수 {netlib.parameter_count(net):,}개")

    rng = np.random.default_rng(cfg["seed"])
    cache: dict[int, Dataset] = {}
    best = None  # (세대, 기준 AI 상대 승률). 이어서 할 때는 best.json 에서 읽는다
    if start > 0 and (run_dir / "best.json").exists():
        b = json.loads((run_dir / "best.json").read_text(encoding="utf-8"))
        best = (int(b["gen"]), float(b["win_rate"]))
    for gen in range(start, args.gens):
        gen_dir = run_dir / f"gen-{gen:03d}"
        t0 = time.time()
        play_summary = run_java(make_plan(cfg, args.run, gen, run_dir, gen_dir), gen_dir / "plan.json", run_dir)
        t1 = time.time()

        for k in range(max(0, gen - cfg["window"] + 1), gen + 1):
            if k not in cache:
                cache[k] = load_dataset(run_dir / f"gen-{k:03d}", k)
        for k in [k for k in cache if k <= gen - cfg["window"]]:
            del cache[k]
        ds = Dataset.concat([cache[k] for k in sorted(cache)])
        weights = train_weights(cfg, gen)
        values = predict_values(net, ds, device)
        rews = rewards(ds, weights[None], device)
        targets = lambda_returns(values, rews, ds.starts, ds.lengths, ds.outcomes, cfg["gamma"], cfg["lam"])
        loss_new, loss_seen = held_out_losses(values, targets, len(cache[gen]))
        for group in opt.param_groups:
            group["lr"] = lr_at(cfg, gen)
        epochs = cfg["epochs_gen0"] if gen == 0 else cfg["epochs"]
        losses = train_epochs(net, opt, ds, targets, epochs, cfg["batch"], device, rng)
        diff = export_and_verify(net, weights, cfg, gen, model_path(run_dir, gen), ds)
        t2 = time.time()

        eval_dir = gen_dir / "eval"
        best_gen = best[0] if best else None
        run_java(make_eval_plan(cfg, args.run, gen, run_dir, eval_dir, best_gen), eval_dir / "plan.json", run_dir)
        by_opp: dict[str, list] = {BASELINE: [], BEST: []}
        for m in read_matches(sorted((eval_dir / "matches").glob("*.jsonl.gz"))):
            by_opp[opponent_of(m, LEARNER)].extend(trajectories(m, {LEARNER}))
        ev = summarize(by_opp[BASELINE])
        ev_best = summarize(by_opp[BEST]) if by_opp[BEST] else None
        (eval_dir / "eval.json").write_text(json.dumps(
            {"vs_baseline": ev, "vs_best": ev_best, "best_gen": best_gen}, ensure_ascii=False, indent=1) + "\n",
            encoding="utf-8")
        t3 = time.time()

        ckpt_dir = run_dir / "checkpoints"
        ckpt_dir.mkdir(exist_ok=True)
        torch.save({"net": net.state_dict(), "optimizer": opt.state_dict(), "config": net.config, "gen": gen},
                   ckpt_dir / f"gen-{gen:03d}.pt")
        if best is None or ev["win_rate"] >= best[1]:
            best = (gen, ev["win_rate"])
            (run_dir / "best.json").write_text(json.dumps({"gen": gen, "win_rate": ev["win_rate"]}) + "\n", encoding="utf-8")
        row = {
            "gen": gen, "samples": len(ds), "loss_first": round(losses[0], 5), "loss_last": round(losses[-1], 5),
            **{k: round(ev[k], 4) for k in ("win_rate", "turns_mean", "app", "sent_per_piece", "lines_per_piece",
                                            "tspin_rate", "tetris_rate", "max_height_mean", "holes_mean")},
            "sec_play": round(t1 - t0, 1), "sec_train": round(t2 - t1, 1), "sec_eval": round(t3 - t2, 1),
            "play_turns_mean": play_summary.split("평균 ")[1].split("턴")[0] if "평균 " in play_summary else "",
            "best_gen": "" if best_gen is None else best_gen,
            "win_rate_vs_best": "" if ev_best is None else round(ev_best["win_rate"], 4),
            "loss_new": round(loss_new, 5), "loss_seen": "" if np.isnan(loss_seen) else round(loss_seen, 5),
            "prior_weight": prior_at(cfg, gen), "lr": f"{lr_at(cfg, gen):.3g}",
        }
        append_log(run_dir, row)

        # 정리: 기록은 최근 window 세대만, 체크포인트는 최근 3개와 최고 세대만 남긴다 (TRAINING.md 12장).
        old = gen - cfg["window"]
        if old >= 0:
            rmtree(run_dir / f"gen-{old:03d}" / "matches")
            rmtree(run_dir / f"gen-{old:03d}" / "eval" / "matches")
        for c in ckpt_dir.glob("gen-*.pt"):
            k = int(c.stem.split("-")[1])
            if k <= gen - 3 and (best is None or k != best[0]):
                c.unlink()

        log(run_dir, f"[세대 {gen:03d}] 대전 {t1 - t0:.0f}초 ({play_summary.removeprefix('[match] 끝: ')}) | "
            f"학습 표본 {len(ds):,}개, 손실 {losses[0]:.4f}→{losses[-1]:.4f} (새 기록 {loss_new:.4f}, 배운 기록 "
            f"{loss_seen:.4f}), β {prior_at(cfg, gen):g}, 내보내기 차이 {diff:.1e}, {t2 - t1:.0f}초 | "
            f"평가 vs 기준 AI: 승률 {ev['win_rate']:.3f}, 평균 {ev['turns_mean']:.0f}턴, APP {ev['app']:.3f}, "
            f"T-spin {ev['tspin_rate']:.3f}, 최고 높이 {ev['max_height_mean']:.1f}"
            + (f" | vs 최고 세대 {best_gen}: 승률 {ev_best['win_rate']:.3f}" if ev_best else "")
            + f", {t3 - t2:.0f}초")


if __name__ == "__main__":
    main()
