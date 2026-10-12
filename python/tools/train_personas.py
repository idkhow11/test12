"""페르소나 학습 루프 (docs/TRAINING.md 9장, 단계 B): AI 12 개가 가치망 하나를 공유하며 리그 대전으로 학습한다.

    python python/tools/train_personas.py --run p01 --gens 16             # 세대 0(시작점)과 세대 1~15
    python python/tools/train_personas.py --run p01 --gens 31 --resume    # 마지막으로 끝난 세대 다음부터
    python python/tools/train_personas.py --run p02 --gens 121 --config <설정.json>   # 학습 수치를 설정 파일에서

세대 0 은 단계 A 체크포인트에서 임베딩만 12 행으로 늘린 모델이다(학습 전, 내보내기만 한다). 세대 g 의 대전은 세대 g-1
모델이 두고, 그 기록으로 학습해 세대 g 모델을 만든다. 설정은 기본값(tetris_ai.personas.DEFAULTS) ← --config 파일 ←
명령행 인자 순서로 덮어쓴다. 결과는 runs/<run>/ 에 남는다 (git 제외).
p05 설정(runs/p05-design/configs, 저장소에는 같은 바이트의 사본 python/configs/p05a·p05b·p05f.json)은 원형 복제(replicas)·
중립 AI(n_neutral)·보상 중심 빼기(center_style)를 켠다.
기본값이면 p01~p04 와 같은 학습이다.

    config.json, personas.json        실행 설정(원형 가중치 포함)과 AI 표
    log.csv, train.log                세대마다 한 줄 요약과 자세한 기록
    ai_log.csv                        세대 × AI 마다 학습 대전 지표(source=play), 평가 지표(source=eval)
    embeddings.csv                    세대마다 AI 임베딩
    models/value_<AI>_gen-XXX.onnx    세대마다 AI 마다 내보낸 모델
    checkpoints/gen-XXX.pt            신경망·최적화기 상태와 AI 표
    gen-XXX/plan.json, matches/       리그 대전 계획과 기록. gen-XXX/eval/ 은 평가
"""

from __future__ import annotations

import argparse
import copy
import csv
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai import net as netlib  # noqa: E402
from tetris_ai import personas as pl  # noqa: E402
from tetris_ai.records import read_matches, trajectories  # noqa: E402
from tetris_ai.train import Dataset, lambda_returns, predict_values, rewards, train_epochs  # noqa: E402

LOG_FIELDS = [
    "gen", "samples", "loss_first", "loss_last", "emb_spread", "style_min", "style_max", "play_win_rate_vs_baseline",
    "eval_win_rate_mean", "eval_win_rate_min", "export_diff", "sec_play", "sec_train", "sec_eval", "play_turns_mean",
]
METRIC_FIELDS = [
    "matches", "win_rate", "turns_mean", "app", "sent_per_piece", "lines_per_piece", "tspin_rate", "tetris_rate",
    "combo_rate", "combo_len", "max_height_mean", "holes_mean", "bumpiness_mean", "hold_rate", "cancel_ratio",
    "pressure_response", "defense_response",
]
AI_FIELDS = ["gen", "model_gen", "source", "ai", "label", *METRIC_FIELDS, "win_rate_vs_baseline", "style_per_game"]


def log(run_dir: Path, text: str) -> None:
    line = time.strftime("%H:%M:%S ") + text
    print(line, flush=True)
    with open(run_dir / "train.log", "a", encoding="utf-8") as f:
        f.write(line + "\n")


def append_csv(path: Path, fields: list[str], rows: list[dict]) -> None:
    new = not path.exists()
    with open(path, "a", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields, restval="")
        if new:
            w.writeheader()
        w.writerows(rows)


def last_finished_gen(run_dir: Path) -> int:
    path = run_dir / "log.csv"
    if not path.exists():
        return -1
    with open(path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    return int(rows[-1]["gen"]) if rows else -1


def checkpoint_path(run_dir: Path, gen: int) -> Path:
    return run_dir / "checkpoints" / f"gen-{gen:03d}.pt"


def model_path(run_dir: Path, ai: str, gen: int) -> Path:
    return run_dir / "models" / pl.model_file(ai, gen)


def r4(x: float) -> float | int | str:
    """CSV 한 칸: nan 은 빈칸, 정수는 그대로, 실수는 소수 5 자리."""
    if x is None or (isinstance(x, float) and np.isnan(x)):
        return ""
    return int(x) if isinstance(x, (int, np.integer)) else round(float(x), 5)


def center_rewards(rews: np.ndarray, ai: np.ndarray, starts: np.ndarray, n_ai: int) -> np.ndarray:
    """AI 마다 즉시 보상의 블록당 평균을 빼 그 자리에서 고치고(p05 의 center_style), 평균 [n_ai] 를 돌려준다.

    블록마다 받는 스타일 보상은 판 길이에 비례해 쌓이므로, 평균이 음수인 AI(수비)는 판을 빨리 끝내고 양수인 AI(콤보)는
    판을 끌 유인을 받는다(p05 가중치로 p04 학습 대전을 계산하면 한 판 합이 수비 −1.50, 콤보 +1.03 으로 승패보다 크다,
    runs/p05-design/calib). 평균을 빼면 한 판 합에서 판 길이에 비례하는 몫이 없어진다.
    평균은 λ-리턴에 들어가는 표본으로만 낸다: 궤적의 첫 표본의 보상은 목표에 쓰이지 않는다(train.lambda_returns 는
    G_t 에 R(x_{t+1}) 을 쓴다). 표본이 없는 AI 는 0 을 뺀다. 고르는 점수(내보내는 ScoreModule)에는 원래 가중치가 그대로
    들어간다. 결정 안의 모든 후보에 같은 상수를 더해도 고르는 배치는 바뀌지 않는다.
    """
    used = np.ones(len(rews), dtype=bool)
    used[np.asarray(starts, dtype=np.int64)] = False
    ai = np.asarray(ai, dtype=np.int64)
    sums = np.bincount(ai[used], weights=rews[used].astype(np.float64), minlength=n_ai)
    counts = np.bincount(ai[used], minlength=n_ai)
    centers = np.where(counts > 0, sums / np.maximum(counts, 1), 0.0)
    rews -= centers[ai].astype(rews.dtype)
    return centers


def load_gen(gen_dir: Path, ai_index: dict[str, int]) -> tuple[Dataset, list[tuple]]:
    """세대 기록에서 지금 세대 AI 가 둔 쪽의 궤적. (학습 자료, [(궤적, 상대 이름)])."""
    files = sorted((gen_dir / "matches").glob("*.jsonl.gz"))
    pairs = []
    for m in read_matches(files):
        names = [p["ai"] for p in m["players"]]
        for t in trajectories(m, set(ai_index)):
            pairs.append((t, names[1] if t.side == "p1" else names[0]))
    return Dataset.build([t for t, _ in pairs], ai_index), pairs


def ai_rows(gen: int, model_gen: int, source: str, pairs: list[tuple], personas: list[pl.Persona],
            weights: torch.Tensor) -> list[dict]:
    """AI 마다 지표 한 줄. pairs 는 [(궤적, 상대 이름)]."""
    rows = []
    for i, p in enumerate(personas):
        mine = [(t, opp) for t, opp in pairs if t.ai == p.name]
        trajs = [t for t, _ in mine]
        m = pl.persona_metrics(trajs)
        vs_base = [t.outcome for t, opp in mine if opp == pl.BASELINE]
        rows.append({
            "gen": gen, "model_gen": model_gen, "source": source, "ai": p.name, "label": p.label,
            **{k: r4(m.get(k, float("nan"))) for k in METRIC_FIELDS},
            "win_rate_vs_baseline": r4(np.mean([(o > 0) + 0.5 * (o == 0) for o in vs_base]) if vs_base else float("nan")),
            "style_per_game": r4(pl.style_return(trajs, weights[i])),
        })
    return rows


def export_all(net: netlib.ValueNet, personas: list[pl.Persona], weights: torch.Tensor, cfg: dict, run: str, run_dir: Path,
               gen: int, ds: Dataset | None) -> float:
    """AI 마다 세대 모델을 내보내고(TRAINING.md 9.4) 그 AI 의 학습 표본(없으면 합성 입력)으로 onnxruntime 과 비교한다."""
    net_cpu = copy.deepcopy(net).cpu().eval()
    rng = np.random.default_rng(gen)
    worst = 0.0
    for i, p in enumerate(personas):
        path = model_path(run_dir, p.name, gen)
        module = pl.export_ai(net_cpu, i, weights[i], cfg["gamma"], cfg["prior_weight"], path, p.name, f"gen-{gen:03d}",
                              f"페르소나 학습 {run}: AI {p.name}(라벨 {p.label}), 세대 {gen}")
        rows = np.flatnonzero(ds.ai == i) if ds is not None else np.zeros(0, np.int64)
        if len(rows):
            idx = np.sort(rng.choice(rows, size=min(128, len(rows)), replace=False))
            inputs = {"board": ds.boards[idx].astype(np.float32), "features": ds.features[idx],
                      "opp_board": ds.opp_boards[idx].astype(np.float32), "opp_features": ds.opp_features[idx]}
        else:
            inputs = pl.synthetic_inputs(64, gen * 100 + i)
        worst = max(worst, pl.verify_onnx(module, path, inputs))
    return worst


def evaluate(cfg: dict, run: str, gen: int, run_dir: Path, personas: list[pl.Persona]) -> list[tuple]:
    """AI 마다 기준 AI 와 고정 시드 eval_seeds 개 × 자리 2 판 (TRAINING.md 9.4). [(궤적, 상대 이름)]."""
    eval_dir = run_dir / f"gen-{gen:03d}" / "eval"
    models = {p.name: pl.rel(model_path(run_dir, p.name, gen), eval_dir) for p in personas}
    seeds = range(pl.EVAL_SEED0, pl.EVAL_SEED0 + cfg["eval_seeds"])
    plan = pl.fixed_opponent_plan(models, pl.BASELINE, "baseline", seeds, run, gen, cfg["max_turns"], cfg["threads"])
    pl.run_java(plan, eval_dir / "plan.json", run_dir)
    names = set(models)
    return [(t, pl.BASELINE) for m in read_matches(sorted((eval_dir / "matches").glob("*.jsonl.gz")))
            for t in trajectories(m, names)]


def save_state(run_dir: Path, gen: int, net: netlib.ValueNet, opt: torch.optim.Optimizer, personas: list[pl.Persona],
               cfg: dict, extra: dict | None = None) -> np.ndarray:
    """체크포인트와 임베딩 기록을 남기고 임베딩 [AI 수, 8] 을 돌려준다. extra 는 체크포인트에 더 넣을 값."""
    path = checkpoint_path(run_dir, gen)
    path.parent.mkdir(parents=True, exist_ok=True)
    torch.save({"net": net.state_dict(), "optimizer": opt.state_dict(), "config": net.config, "gen": gen,
                "personas": [p.to_json() for p in personas], "run_config": cfg, **(extra or {})}, path)
    emb = net.embedding.weight.detach().cpu().numpy().astype(np.float64)
    append_csv(run_dir / "embeddings.csv", ["gen", "ai", *(f"e{j}" for j in range(emb.shape[1]))],
               [{"gen": gen, "ai": p.name, **{f"e{j}": round(float(v), 6) for j, v in enumerate(emb[i])}}
                for i, p in enumerate(personas)])
    return emb


def cleanup(run_dir: Path, gen: int, cfg: dict, names: list[str]) -> None:
    """기록은 최근 window 세대만, 체크포인트는 세대 0·최근 3 개·keep_every 의 배수만, 모델은 이전 세대 상대로 쓰는 동안과
    세대 0·keep_every 의 배수만 남긴다 (TRAINING.md 9.4). 지금 세대 모델은 언제나 남는다."""

    def kept(k: int) -> bool:
        return k == 0 or (cfg["keep_every"] > 0 and k % cfg["keep_every"] == 0)

    old = gen - cfg["window"]
    if old >= 1:
        pl.rmtree(run_dir / f"gen-{old:03d}" / "matches")
        pl.rmtree(run_dir / f"gen-{old:03d}" / "eval" / "matches")
    for c in (run_dir / "checkpoints").glob("gen-*.pt"):
        k = int(c.stem.split("-")[1])
        if k <= gen - 3 and not kept(k):
            c.unlink()
    for k in range(1, max(0, gen - cfg["past_window"])):  # 다음 세대의 이전 세대 상대는 gen - past_window 부터
        if not kept(k):
            for ai in names:
                model_path(run_dir, ai, k).unlink(missing_ok=True)


def load_config(args: argparse.Namespace, run_dir: Path) -> tuple[dict, int, list[str]]:
    if args.resume:
        cfg = json.loads((run_dir / "config.json").read_text(encoding="utf-8"))
        start = last_finished_gen(run_dir) + 1
        if start == 0:
            raise SystemExit(f"{run_dir} 에 끝난 세대가 없음. --resume 없이 새로 시작")
    else:
        if (run_dir / "config.json").exists():
            raise SystemExit(f"{run_dir} 에 이미 실행이 있음. 이어서 하려면 --resume")
        cfg = pl.default_config()
        start = 0
    ignored: list[str] = []
    if args.config:
        cfg, ignored = pl.merge_config(cfg, json.loads(Path(args.config).read_text(encoding="utf-8")))
        cfg["config_file"] = str(args.config)
    for k in pl.DEFAULTS:
        v = getattr(args, k)
        if v is not None:
            cfg[k] = v
    if ignored:
        cfg["ignored"] = sorted(set(cfg.get("ignored", [])) | set(ignored))
    return cfg, start, ignored


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True, help="실행 이름 (runs/<run>)")
    p.add_argument("--gens", type=int, required=True, help="이 세대 번호 전까지 (세대 0 = 시작점)")
    p.add_argument("--resume", action="store_true", help="마지막으로 끝난 세대 다음부터 이어서")
    p.add_argument("--config", help="학습 수치를 담은 JSON 파일 (예: 단계 A 실행의 config.json). 명령행 인자가 이긴다")
    for k, v in pl.DEFAULTS.items():
        p.add_argument(f"--{k.replace('_', '-')}", type=type(v), default=None, help=f"기본 {v}")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    cfg, start, ignored = load_config(args, run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "config.json").write_text(json.dumps(cfg, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    personas = pl.personas_from_config(cfg)
    table = {"personas": [q.to_json() for q in personas]}
    table_path = run_dir / "personas.json"
    if start > 0 and not pl.same_table(json.loads(table_path.read_text(encoding="utf-8"))["personas"], table["personas"]):
        raise SystemExit("personas.json 이 설정으로 만든 AI 표와 다름 (원형 가중치, 섞는 시드, 복제·중립 수가 바뀌었나?)")
    table_path.write_text(json.dumps(table, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    names = [q.name for q in personas]
    ai_index = {name: i for i, name in enumerate(names)}
    weights = pl.weight_matrix(personas)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.manual_seed(cfg["seed"])
    if start == 0:
        stage_a = torch.load(ROOT / cfg["init"], map_location="cpu")
        net = pl.net_from_stage_a(stage_a, len(personas), cfg["init_ai"], cfg["emb_noise"], cfg["seed"]).to(device)
        opt = pl.make_optimizer(net, cfg["lr"], cfg["weight_decay"], cfg["emb_lr_scale"])
        if cfg["reuse_optimizer"]:
            pl.transfer_optimizer_state(opt, net, stage_a["optimizer"], len(personas), cfg["init_ai"])
    else:
        ckpt = torch.load(checkpoint_path(run_dir, start - 1), map_location="cpu")
        net = netlib.ValueNet(**ckpt["config"]).to(device)
        net.load_state_dict(ckpt["net"])
        opt = pl.make_optimizer(net, cfg["lr"], cfg["weight_decay"], cfg["emb_lr_scale"])
        opt.load_state_dict(ckpt["optimizer"])
        pl.set_learning_rates(opt, cfg["lr"], cfg["weight_decay"], cfg["emb_lr_scale"])
    log(run_dir, f"페르소나 실행 {args.run}: 세대 {start} 부터 {args.gens} 전까지, AI {len(personas)}개, 장치 {device}"
        f"{' (' + torch.cuda.get_device_name(0) + ')' if device.type == 'cuda' else ''}, 시작점 {cfg['init']}"
        + (f" | 설정 파일에서 쓰지 않은 키: {', '.join(ignored)}" if ignored else ""))

    if start == 0:  # 세대 0: 늘린 직후의 모델을 내보내기만 한다
        t0 = time.time()
        diff = export_all(net, personas, weights, cfg, args.run, run_dir, 0, None)
        emb = save_state(run_dir, 0, net, opt, personas, cfg)
        append_csv(run_dir / "log.csv", LOG_FIELDS, [{"gen": 0, "samples": 0, "emb_spread": r4(pl.mean_pairwise_distance(emb)),
                                                     "export_diff": f"{diff:.1e}", "sec_train": round(time.time() - t0, 1)}])
        log(run_dir, f"[세대 000] 시작점 내보내기, 임베딩 사이 평균 거리 {pl.mean_pairwise_distance(emb):.4f}, 차이 {diff:.1e}")
        start = 1

    rng = np.random.default_rng(cfg["seed"])
    cache: dict[int, Dataset] = {}
    for gen in range(start, args.gens):
        gen_dir = run_dir / f"gen-{gen:03d}"
        t0 = time.time()
        plan = pl.league_plan(names, cfg, args.run, gen, pl.rel(run_dir / "models", gen_dir))
        play_summary = pl.run_java(plan, gen_dir / "plan.json", run_dir)
        t1 = time.time()

        new_pairs: list[tuple] = []
        for k in range(max(1, gen - cfg["window"] + 1), gen + 1):
            if k not in cache:
                cache[k], pairs = load_gen(run_dir / f"gen-{k:03d}", ai_index)
                if k == gen:
                    new_pairs = pairs
        for k in [k for k in cache if k <= gen - cfg["window"]]:
            del cache[k]
        ds = Dataset.concat([cache[k] for k in sorted(cache)])
        values = predict_values(net, ds, device)
        rews = rewards(ds, weights, device)
        centers = center_rewards(rews, ds.ai, ds.starts, len(personas)) if cfg.get("center_style", 0) else None
        targets = lambda_returns(values, rews, ds.starts, ds.lengths, ds.outcomes, cfg["gamma"], cfg["lam"])
        lr = pl.learning_rate(cfg, gen)  # 학습률 일정 (TRAINING.md 9.3). 임베딩은 emb_lr_scale 배
        pl.set_learning_rates(opt, lr, cfg["weight_decay"], cfg["emb_lr_scale"])
        losses = train_epochs(net, opt, ds, targets, cfg["epochs"], cfg["batch"], device, rng)
        diff = export_all(net, personas, weights, cfg, args.run, run_dir, gen, ds)
        t2 = time.time()

        rows = ai_rows(gen, gen - 1, "play", new_pairs, personas, weights)
        eval_rows: list[dict] = []
        if cfg["eval_every"] > 0 and (gen % cfg["eval_every"] == 0 or gen == args.gens - 1):
            eval_rows = ai_rows(gen, gen, "eval", evaluate(cfg, args.run, gen, run_dir, personas), personas, weights)
        t3 = time.time()

        emb = save_state(run_dir, gen, net, opt, personas, cfg,
                         {"style_centers": centers.tolist()} if centers is not None else None)
        append_csv(run_dir / "ai_log.csv", AI_FIELDS, rows + eval_rows)
        styles = [r["style_per_game"] for r in rows if r["style_per_game"] != ""]
        base_outcomes = [t.outcome for t, opp in new_pairs if opp == pl.BASELINE]
        eval_wins = [r["win_rate"] for r in eval_rows if r["win_rate"] != ""]
        spread = pl.mean_pairwise_distance(emb)
        append_csv(run_dir / "log.csv", LOG_FIELDS, [{
            "gen": gen, "samples": len(ds), "loss_first": r4(losses[0]), "loss_last": r4(losses[-1]),
            "emb_spread": r4(spread), "style_min": r4(min(styles)) if styles else "",
            "style_max": r4(max(styles)) if styles else "",
            "play_win_rate_vs_baseline": r4(np.mean([(o > 0) + 0.5 * (o == 0) for o in base_outcomes]))
            if base_outcomes else "",
            "eval_win_rate_mean": r4(np.mean(eval_wins)) if eval_wins else "",
            "eval_win_rate_min": r4(min(eval_wins)) if eval_wins else "",
            "export_diff": f"{diff:.1e}", "sec_play": round(t1 - t0, 1), "sec_train": round(t2 - t1, 1),
            "sec_eval": round(t3 - t2, 1),
            "play_turns_mean": play_summary.split("평균 ")[1].split("턴")[0] if "평균 " in play_summary else "",
        }])
        cleanup(run_dir, gen, cfg, names)

        by_ai = {r["ai"]: r for r in rows}
        log(run_dir, f"[세대 {gen:03d}] 대전 {t1 - t0:.0f}초 | 학습 표본 {len(ds):,}개, 학습률 {lr:.1e}, "
            f"손실 {losses[0]:.4f}→{losses[-1]:.4f}, 내보내기 차이 {diff:.1e}, {t2 - t1:.0f}초 | 임베딩 거리 {spread:.4f} | "
            "한 판 스타일 보상 "
            + (f"{min(styles):+.3f}~{max(styles):+.3f}" if styles else "-")
            + " | 학습 대전 APP " + " ".join(f"{a[:3]}{by_ai[a]['app']}" for a in pl.ARCHETYPE_NAMES)
            + f", T-spin {by_ai['tspin']['tspin_rate']}, 콤보 {by_ai['combo']['combo_rate']}, "
            f"구멍 수비 {by_ai['defender']['holes_mean']} 공격 {by_ai['attacker']['holes_mean']}"
            + (" | 뺀 블록당 보상 " + " ".join(f"{a[:3]}{centers[ai_index[a]]:+.4f}" for a in pl.ARCHETYPE_NAMES)
               if centers is not None else "")
            + (f" | 평가 vs 기준 AI 승률 평균 {np.mean(eval_wins):.3f} (최저 {min(eval_wins):.3f}), {t3 - t2:.0f}초"
               if eval_wins else ""))


if __name__ == "__main__":
    main()
