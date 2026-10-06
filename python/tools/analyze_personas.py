"""페르소나 분석 (docs/TRAINING.md 9.5, 9.6): 임베딩 군집, 고정 상대와 둔 행동 지표, ARI·NMI, PCA, 게임용 파일.

    python python/tools/analyze_personas.py --run p01 --gen 15
    python python/tools/analyze_personas.py --run p01 --gen 0 --no-export     # 대조: 학습 전(임베딩은 잡음뿐)
    python python/tools/analyze_personas.py --run p01 --trend                 # 대전 없이 세대마다 임베딩 ~ 비율 순위 상관

AI 마다 그 세대 모델로 고정 상대(단계 A 모델)와 고정 시드에서 자리를 바꿔 두 판씩 둔다(양쪽 τ = 0). 결과는
runs/<run>/analysis/gen-XXX/ 에 남는다.

    plan.json, matches/   고정 상대와 둔 대전 (--reuse 면 있는 기록을 다시 쓴다)
    metrics.csv           AI 별 지표와 부트스트랩 표준오차(se_*)
    clusters.csv          공간(embedding, behavior)과 k 마다 k-means·GMM 실루엣
    pca.csv               임베딩과 행동 z-score 의 PCA 2 차원 좌표, 군집 번호
    report.json           위 내용과 ARI·NMI, 거리 순위 상관(Mantel), 임베딩 PC 상관, 지표별 차이/잡음 비와 순열 검정 p,
                          원형 비율 ~ 대표 지표 상관, 대표 AI 와 이름

게임용 파일은 runs/<run>/personas/value_<페르소나>_gen-XXX.onnx 와 personas_gen-XXX.json 이다(--no-export 면 만들지 않는다).
"""

from __future__ import annotations

import argparse
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
from tetris_ai import persona_analysis as pa  # noqa: E402
from tetris_ai import personas as pl  # noqa: E402
from tetris_ai.records import read_matches, trajectories  # noqa: E402

KS = range(2, 7)  # 실루엣을 보는 k (TRAINING.md 9.5)
TABLE_KEYS = ("win_rate", "turns_mean", *pa.BEHAVIOR_KEYS, "combo_len")


def latest_checkpoint_gen(run_dir: Path) -> int:
    gens = [int(c.stem.split("-")[1]) for c in (run_dir / "checkpoints").glob("gen-*.pt")]
    if not gens:
        raise SystemExit(f"{run_dir} 에 체크포인트가 없음")
    return max(gens)


def write_csv(path: Path, rows: list[dict]) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def fmt(x: float, digits: int = 3) -> str:
    return "-" if x is None or np.isnan(x) else f"{x:.{digits}f}"


def inputs_of(trajs: list, n: int, seed: int) -> dict[str, np.ndarray]:
    """궤적들에서 표본 n 개를 뽑아 모델 입력으로 (내보내기 확인용)."""
    boards = np.concatenate([t.boards for t in trajs])
    idx = np.sort(np.random.default_rng(seed).choice(len(boards), size=min(n, len(boards)), replace=False))
    return {"board": boards[idx].astype(np.float32),
            "features": np.concatenate([t.features for t in trajs])[idx],
            "opp_board": np.concatenate([t.opp_boards for t in trajs])[idx].astype(np.float32),
            "opp_features": np.concatenate([t.opp_features for t in trajs])[idx]}


def trend(run_dir: Path, personas: list) -> None:
    """세대마다 임베딩이 설계(원형 비율)를 얼마나 담는지 (TRAINING.md 9.8). 학습 루프의 embeddings.csv 만 쓴다."""
    names = [q.name for q in personas]
    with open(run_dir / "embeddings.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    dims = sorted((c for c in rows[0] if c.startswith("e")), key=lambda c: int(c[1:]))
    by_gen: dict[int, dict[str, list[float]]] = {}
    for r in rows:
        by_gen.setdefault(int(r["gen"]), {})[r["ai"]] = [float(r[c]) for c in dims]
    emb_by_gen = {g: np.array([e[ai] for ai in names]) for g, e in by_gen.items()}
    ratios = np.array([q.ratios for q in personas])
    out = pa.embedding_trend(emb_by_gen, ratios, pl.ARCHETYPE_NAMES)
    (run_dir / "analysis").mkdir(exist_ok=True)
    write_csv(run_dir / "analysis" / "embedding_trend.csv",
              [{k: (round(v, 5) if isinstance(v, float) else v) for k, v in r.items()} for r in out])
    print("세대 | 임베딩 거리 | 임베딩 ~ 비율 순위 상관 (p) | PC1 분산 | |PC1 ~ 비율 r|: " + ", ".join(pl.ARCHETYPE_NAMES))
    for r in out:
        print(f"{r['gen']:3d} | {r['emb_spread']:.3f} | {r['mantel_r']:+.2f} ({r['mantel_p']:.3f}) | {r['pc1_var']:.2f} | "
              + ", ".join(f"{r['pc1_abs_r_' + a]:.2f}" for a in pl.ARCHETYPE_NAMES))


def parse_names(text: str | None) -> dict[int, str]:
    if not text:
        return {}
    out = {}
    for part in text.split(","):
        c, name = part.split("=")
        out[int(c)] = name.strip()
    return out


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True, help="페르소나 실행 이름 (runs/<run>)")
    p.add_argument("--gen", type=int, default=None, help="분석할 세대 (기본: 마지막 체크포인트)")
    p.add_argument("--reference", default="runs/r01/models/value_ai00_gen-038.onnx", help="고정 상대 모델 (저장소 기준)")
    p.add_argument("--seeds", type=int, default=100, help=f"고정 시드 수 ({pl.ANALYSIS_SEED0} 부터), AI 마다 2 배의 판")
    p.add_argument("--threads", type=int, default=None, help="Java 스레드 (기본: 실행 설정)")
    p.add_argument("--k", type=int, default=4, help="검증 분석과 게임용 파일의 군집 수 (기본: 원형 수 4)")
    p.add_argument("--boot", type=int, default=200, help="부트스트랩 횟수")
    p.add_argument("--perm", type=int, default=2000, help="AI 차이 순열 검정의 반복 수")
    p.add_argument("--reuse", action="store_true", help="이미 있는 분석 대전 기록을 다시 쓴다")
    p.add_argument("--no-export", action="store_true", help="게임용 파일을 만들지 않는다")
    p.add_argument("--names", help="군집 이름을 직접 정한다. 예: 0=attacker,1=defender (군집 번호는 출력 참고)")
    p.add_argument("--trend", action="store_true",
                   help="대전 없이 embeddings.csv 로 세대마다 임베딩 ~ 비율 순위 상관만 계산한다 (analysis/embedding_trend.csv)")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    cfg = json.loads((run_dir / "config.json").read_text(encoding="utf-8"))
    personas = pl.personas_from_config(cfg)
    if args.trend:
        trend(run_dir, personas)
        return
    gen = latest_checkpoint_gen(run_dir) if args.gen is None else args.gen
    ckpt = torch.load(run_dir / "checkpoints" / f"gen-{gen:03d}.pt", map_location="cpu")
    if [q.to_json() for q in personas] != ckpt["personas"]:
        raise SystemExit("체크포인트의 AI 표가 설정으로 만든 표와 다름")
    names = [q.name for q in personas]
    weights = pl.weight_matrix(personas)
    emb = ckpt["net"][pl.EMBEDDING].numpy().astype(np.float64)
    out_dir = run_dir / "analysis" / f"gen-{gen:03d}"
    t0 = time.time()

    # 1. 행동 측정: AI 마다 고정 상대와 고정 시드 (TRAINING.md 9.5)
    reference = ROOT / args.reference
    models = {ai: pl.rel(run_dir / "models" / pl.model_file(ai, gen), out_dir) for ai in names}
    missing = [m for m in models.values() if not (out_dir / m).exists()]
    if missing:
        raise SystemExit(f"모델 파일이 없음: {missing[:3]} … (정리된 세대인지 확인)")
    seeds = range(pl.ANALYSIS_SEED0, pl.ANALYSIS_SEED0 + args.seeds)
    plan = pl.fixed_opponent_plan(models, pl.REFERENCE, pl.rel(reference, out_dir), seeds, args.run, gen,
                                  cfg["max_turns"], args.threads or cfg["threads"])
    if args.reuse and (out_dir / "matches").exists():
        summary = "(기존 기록)"
    else:
        summary = pl.run_java(plan, out_dir / "plan.json", run_dir)
    # 판을 (시드, AI 의 자리)로 맞춘다. 모든 AI 가 같은 판들을 두었으므로 짝지은 비교(순열 검정)를 할 수 있다
    games: dict[str, dict[tuple, object]] = {ai: {} for ai in names}
    for m in read_matches(sorted((out_dir / "matches").glob("*.jsonl.gz"))):
        for t in trajectories(m, set(names)):
            games[t.ai][(m["seed"], t.side)] = t
    order = sorted(games[names[0]])
    if any(sorted(games[ai]) != order for ai in names):
        raise RuntimeError("AI 마다 둔 판(시드, 자리)이 다름")
    by_ai = {ai: [games[ai][key] for key in order] for ai in names}
    metrics = {ai: pl.persona_metrics(by_ai[ai]) for ai in names}
    for i, ai in enumerate(names):
        metrics[ai]["style_per_game"] = pl.style_return(by_ai[ai], weights[i])
    parts = np.stack([np.stack([pa.trajectory_parts(t) for t in by_ai[ai]]) for ai in names])  # [AI, 판, 합]
    for i, ai in enumerate(names):  # 판별 합이 persona_metrics 와 같은 지표를 내는지 먼저 확인한다
        full = pa.metrics_from_parts(parts[i].sum(axis=0))
        bad = [key for key in TABLE_KEYS if not np.isclose(full[key], metrics[ai][key], rtol=1e-6, equal_nan=True)]
        if bad:
            raise RuntimeError(f"{ai}: 판별 합으로 계산한 지표가 다름: {bad}")
    se = {ai: pa.bootstrap_se(parts[i], TABLE_KEYS, args.boot, seed=i) for i, ai in enumerate(names)}
    perm = pa.permutation_spread_test(parts, TABLE_KEYS, n_perm=args.perm)
    t1 = time.time()

    # 2. 군집: 임베딩(원래 좌표)과 행동 지표(z-score). 점이 12 개뿐이라 공유 PC 에서는 스레드 하나로 충분하다
    from threadpoolctl import threadpool_limits

    limits = threadpool_limits(limits=1)
    behavior = np.array([[metrics[ai][k] for k in pa.BEHAVIOR_KEYS] for ai in names])
    z = pa.zscore(behavior)
    emb_scan, beh_scan = pa.scan(emb, KS), pa.scan(z, KS)
    k = args.k
    k_best = pa.best_k(emb_scan)
    by_k = {r["k"]: r for r in emb_scan}
    beh_by_k = {r["k"]: r for r in beh_scan}
    emb_k = by_k[k]["kmeans"] if k in by_k else pa.kmeans(emb, k)[0]
    emb_g = by_k[k]["gmm"] if k in by_k else pa.gmm(emb, k)
    beh_k = beh_by_k[k]["kmeans"] if k in beh_by_k else pa.kmeans(z, k)[0]
    labels = np.array([pl.ARCHETYPE_NAMES.index(q.label) for q in personas])
    ratios = np.array([q.ratios for q in personas])
    ratio_k, _ = pa.kmeans(ratios, k)
    emb_best = by_k[k_best]["kmeans"]
    beh_best = beh_by_k[k_best]["kmeans"]
    comparisons = {
        f"k={k}": {
            "embedding_kmeans_vs_label": pa.compare(emb_k, labels),
            "embedding_gmm_vs_label": pa.compare(emb_g, labels),
            "embedding_kmeans_vs_behavior": pa.compare(emb_k, beh_k),
            "embedding_gmm_vs_behavior": pa.compare(emb_g, beh_k),
            "embedding_kmeans_vs_ratio": pa.compare(emb_k, ratio_k),
            "behavior_vs_label": pa.compare(beh_k, labels),
        },
        f"best k={k_best}": {
            "embedding_kmeans_vs_label": pa.compare(emb_best, labels),
            "embedding_kmeans_vs_behavior": pa.compare(emb_best, beh_best),
        },
    }
    emb_pc, emb_var = pa.pca2(emb)
    beh_pc, beh_var = pa.pca2(z)
    noise = {key: pa.spread_over_noise([metrics[ai][key] for ai in names], [se[ai][key] for ai in names])
             for key in TABLE_KEYS}
    # 거리 행렬끼리의 순위 상관: 설계(비율)가 비슷한 AI 가 임베딩도, 행동도 비슷한가 (TRAINING.md 9.5)
    d_emb, d_beh, d_ratio = pa.distance_matrix(emb), pa.distance_matrix(z), pa.distance_matrix(ratios)
    mantel = {"embedding_vs_ratio": pa.mantel(d_emb, d_ratio), "embedding_vs_behavior": pa.mantel(d_emb, d_beh),
              "behavior_vs_ratio": pa.mantel(d_beh, d_ratio)}
    dose = pa.dose_response({a: ratios[:, j] for j, a in enumerate(pl.ARCHETYPE_NAMES)},
                            {key: np.array([metrics[ai][key] for ai in names]) for key in TABLE_KEYS})
    pc_corr = pa.correlations(emb_pc, {**{f"r_{a}": ratios[:, j] for j, a in enumerate(pl.ARCHETYPE_NAMES)},
                                       "style_per_game": [metrics[ai]["style_per_game"] for ai in names],
                                       **{key: behavior[:, j] for j, key in enumerate(pa.BEHAVIOR_KEYS)}})
    limits.restore_original_limits()
    t2 = time.time()

    # 3. 대표 AI 와 이름 (TRAINING.md 9.6)
    reps = pa.representatives(emb, emb_k)
    auto_names, name_scores = pa.name_clusters(z, pa.BEHAVIOR_KEYS, emb_k)
    manual = parse_names(args.names)
    if set(manual) - set(auto_names):
        raise SystemExit(f"없는 군집 번호: {sorted(set(manual) - set(auto_names))} (군집은 {sorted(auto_names)})")
    cluster_names = {**auto_names, **manual}
    if len(set(cluster_names.values())) != len(cluster_names):
        raise SystemExit(f"군집 이름이 겹침: {cluster_names}")

    # 4. 기록
    out_dir.mkdir(parents=True, exist_ok=True)
    write_csv(out_dir / "metrics.csv", [{
        "ai": ai, "label": q.label, **{f"r_{a}": round(r, 4) for a, r in zip(pl.ARCHETYPE_NAMES, q.ratios)},
        "matches": metrics[ai]["matches"], **{key: round(metrics[ai][key], 5) for key in TABLE_KEYS},
        "style_per_game": round(metrics[ai]["style_per_game"], 5),
        **{f"se_{key}": round(se[ai][key], 5) for key in TABLE_KEYS},
    } for ai, q in zip(names, personas)])
    write_csv(out_dir / "clusters.csv", [
        {"space": space, "k": r["k"], "kmeans_silhouette": round(r["kmeans_silhouette"], 4),
         "gmm_silhouette": round(r["gmm_silhouette"], 4)}
        for space, rows in (("embedding", emb_scan), ("behavior", beh_scan)) for r in rows])
    write_csv(out_dir / "pca.csv", [{
        "ai": ai, "label": q.label, "emb_pc1": round(emb_pc[i, 0], 5), "emb_pc2": round(emb_pc[i, 1], 5),
        "beh_pc1": round(beh_pc[i, 0], 5), "beh_pc2": round(beh_pc[i, 1], 5), f"emb_kmeans_k{k}": int(emb_k[i]),
        f"emb_gmm_k{k}": int(emb_g[i]), f"beh_kmeans_k{k}": int(beh_k[i]), f"ratio_kmeans_k{k}": int(ratio_k[i]),
        f"emb_kmeans_k{k_best}": int(emb_best[i]),
    } for i, (ai, q) in enumerate(zip(names, personas))])

    exported = []
    if not args.no_export:
        net = netlib.ValueNet(**ckpt["config"])
        net.load_state_dict(ckpt["net"])
        net.eval()
        persona_dir = run_dir / "personas"
        stage = f"gen-{gen:03d}"
        import onnxruntime as ort

        for c in sorted(reps):
            ai_i = reps[c]
            ai = names[ai_i]
            members = [names[i] for i in np.flatnonzero(emb_k == c)]
            persona = cluster_names[c]
            path = persona_dir / f"value_{persona}_{stage}.onnx"
            module = pl.export_ai(net, ai_i, weights[ai_i], cfg["gamma"], cfg["prior_weight"], path, persona, stage,
                                  f"페르소나 {persona}: 실행 {args.run} 세대 {gen}, 대표 AI {ai} (k-means 군집 {c}, "
                                  f"구성 {', '.join(members)})")
            inputs = inputs_of(by_ai[ai], 256, seed=c)
            diff = pl.verify_onnx(module, path, inputs)
            # 학습 루프가 내보낸 그 AI 의 세대 모델과 같은 점수를 내야 한다 (같은 임베딩과 가중치)
            (a,) = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"]).run(["value"], inputs)
            (b,) = ort.InferenceSession(str(run_dir / "models" / pl.model_file(ai, gen)),
                                        providers=["CPUExecutionProvider"]).run(["value"], inputs)
            same = float(np.max(np.abs(a - b)))
            if same > 1e-5:
                raise RuntimeError(f"{path.name} 가 학습 루프의 {ai} 모델과 다름: {same}")
            exported.append({"persona": persona, "file": path.name, "cluster": int(c), "representative": ai,
                             "members": members, "embedding": [float(v) for v in emb[ai_i]],
                             "weights": dict(zip(netlib.REWARD_TERMS, personas[ai_i].weights)),
                             "name_scores": name_scores[c], "export_diff": diff, "diff_vs_training_model": same})
        (persona_dir / f"personas_{stage}.json").write_text(json.dumps(
            {"run": args.run, "gen": gen, "stage": stage, "k": k, "personas": exported}, ensure_ascii=False, indent=1)
            + "\n", encoding="utf-8")

    report = {
        "run": args.run, "gen": gen, "reference": args.reference, "seeds": [seeds.start, seeds.stop - 1],
        "matches_per_ai": 2 * args.seeds, "summary": summary, "k": k, "best_k": k_best,
        "silhouette": {"embedding": [{key: r[key] for key in ("k", "kmeans_silhouette", "gmm_silhouette")} for r in emb_scan],
                       "behavior": [{key: r[key] for key in ("k", "kmeans_silhouette", "gmm_silhouette")} for r in beh_scan]},
        "comparisons": comparisons,
        "mantel": mantel,
        "embedding_pc_correlations": pc_corr,
        "pca_explained": {"embedding": emb_var.tolist(), "behavior": beh_var.tolist()},
        "spread_over_noise": noise,
        "permutation": perm,
        "dose_response": dose,
        "emb_spread": pl.mean_pairwise_distance(emb),
        "clusters": {int(c): {"name": cluster_names[c], "representative": names[reps[c]],
                              "members": [names[i] for i in np.flatnonzero(emb_k == c)]} for c in sorted(reps)},
        "exported": exported,
    }
    (out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    # 5. 요약 출력
    print(f"페르소나 분석 {args.run} 세대 {gen}: AI 마다 고정 상대 {args.reference} 와 {2 * args.seeds}판 "
          f"({summary.removeprefix('[match] 끝: ')[:60]}), 측정 {t1 - t0:.0f}초, 군집 {t2 - t1:.0f}초, "
          f"내보내기 {time.time() - t2:.0f}초")
    head = ["AI", "라벨", "군집"] + [key[:9] for key in TABLE_KEYS] + ["스타일"]
    print(" | ".join(head))
    for i, (ai, q) in enumerate(zip(names, personas)):
        cells = [ai, q.label, str(emb_k[i])] + [fmt(metrics[ai][key]) for key in TABLE_KEYS]
        print(" | ".join(cells + [fmt(metrics[ai]["style_per_game"])]))
    print("차이/잡음 비: " + ", ".join(f"{key} {fmt(v, 1)}" for key, v in noise.items()))
    print("AI 차이 순열 검정 p (같은 판끼리 AI 이름을 섞음): " + ", ".join(f"{key} {v['p']:.3f}" for key, v in perm.items()))
    print("원형 비율 ~ 대표 지표 (스피어만 ρ, 한쪽 p): " + ", ".join(f"{key} {v['rho']:+.2f} ({v['p']:.3f})" for key, v in dose.items()))
    print("실루엣 (k: 임베딩 k-means / GMM, 행동 k-means / GMM): " + ", ".join(
        f"{e['k']}: {fmt(e['kmeans_silhouette'], 2)}/{fmt(e['gmm_silhouette'], 2)}, "
        f"{b['kmeans_silhouette']:.2f}/{b['gmm_silhouette']:.2f}" for e, b in zip(emb_scan, beh_scan)))
    for title, comp in comparisons.items():
        print(f"{title}: " + ", ".join(f"{key} ARI {v['ari']:.2f} NMI {v['nmi']:.2f}" for key, v in comp.items()))
    print("거리 순위 상관(Mantel): " + ", ".join(f"{key} r {v['r']:+.2f} (p {v['p']:.3f})" for key, v in mantel.items()))
    print("임베딩 PC1·PC2 와의 상관: " + ", ".join(f"{key} {v[0]:+.2f}/{v[1]:+.2f}" for key, v in pc_corr.items()))
    print(f"임베딩 사이 평균 거리 {pl.mean_pairwise_distance(emb):.4f}, PCA 설명 분산 임베딩 {emb_var.round(2).tolist()}, "
          f"행동 {beh_var.round(2).tolist()}")
    for c in sorted(reps):
        print(f"군집 {c} '{cluster_names[c]}': 대표 {names[reps[c]]}, 구성 "
              f"{', '.join(names[i] for i in np.flatnonzero(emb_k == c))}, 이름 점수 "
              + ", ".join(f"{n} {s:+.2f}" for n, s in name_scores[c].items()))
    if exported:
        print("게임용 파일: " + ", ".join(e["file"] for e in exported))


if __name__ == "__main__":
    main()
