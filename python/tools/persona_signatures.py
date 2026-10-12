"""미리 정한 페르소나 행동 서명 확인 (p05, python/configs/p05_signatures.json).

    python python/tools/persona_signatures.py --run p05b --gen 10
    python python/tools/persona_signatures.py --run p05b --gen 30 --tag confirm     # analysis/gen-030-confirm (안 쓴 시드)

analyze_personas.py 가 남긴 분석 판(runs/<run>/analysis/gen-XXX[-tag]/matches)을 읽는다. 모든 AI 가 같은 고정 상대와 같은 시드·
자리로 두었으므로 판을 (시드, 자리)로 짝짓는다. AI 를 라벨(설계)로 묶어 복제의 판을 합치고, 기준 라벨(중립)과 견준다.

    지표          판별 합의 비율 (분모는 블록 수, 콤보 길이는 연속 시작 수, 줄/클리어는 클리어 수)
    rel           설계 값 / 기준 값 − 1
    p             한쪽 짝지은 순열: 판(시드, 자리)마다 설계와 기준의 합을 바꿔 넣어 rel 이 관측 이상(방향대로)일 확률
    통과           primary 가 모두 문턱을 넘고 p < alpha, guard 도 넘음, rank_first 지표가 모든 라벨 중 1 등
    세기          라벨마다 첫 AI 와 합친 승률이 strength.min~max 안
    임베딩        그 세대 체크포인트의 임베딩: 설계 거리와의 Mantel, 복제의 변위 cos·거리비, k-means ARI (spec.clustering)

결과는 그 분석 폴더의 signatures.json 이다. 판 기록이 없으면(정리됨) 다시 analyze_personas.py --reuse 없이 돌린다.
"""

from __future__ import annotations

import argparse
import json
import sys
import zlib
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai.metrics import OWN_DANGER_HEIGHT, heights_and_holes  # noqa: E402
from tetris_ai.model_io import ATTACK, COMBO, LINES  # noqa: E402
from tetris_ai.records import read_matches, trajectories  # noqa: E402

DEFAULT_SPEC = "python/configs/p05_signatures.json"
PARTS = ("games", "score", "turns", "pieces", "attack", "sent", "lines", "clears", "singles", "tetrises", "tspins",
         "combo_clears", "starts", "max_height", "holes", "danger", "risen")
METRICS = {  # 이름: (분자, 분모)
    "win_rate": ("score", "games"), "turns_mean": ("turns", "games"), "app": ("attack", "pieces"),
    "sent_pp": ("sent", "pieces"), "single_rate": ("singles", "pieces"), "lines_per_clear": ("lines", "clears"),
    "tetris_rate": ("tetrises", "pieces"), "tspin_rate": ("tspins", "pieces"), "combo_rate": ("combo_clears", "pieces"),
    "combo_len": ("clears", "starts"), "max_height": ("max_height", "pieces"), "holes": ("holes", "pieces"),
    "danger_rate": ("danger", "pieces"), "risen_pp": ("risen", "pieces"),
}
IDX = {p: i for i, p in enumerate(PARTS)}


def game_parts(t) -> np.ndarray:
    """궤적(판) 하나의 합 [len(PARTS)]. 정의는 metrics.summarize·persona_metrics 와 같다(T-spin 은 스핀 + 줄)."""
    lines, attack, combo1 = t.features[:, LINES], t.features[:, ATTACK], t.features[:, COMBO]
    heights, holes = heights_and_holes(t.boards)
    max_h = heights.max(axis=1)
    clears = lines > 0
    return np.array([
        1, (t.outcome > 0) + 0.5 * (t.outcome == 0), t.turns, len(lines), attack.sum(), t.sent.astype(np.float64).sum(),
        lines.sum(), clears.sum(), (lines == 1).sum(), (lines == 4).sum(), ((t.spin > 0) & clears).sum(),
        (clears & (combo1 >= 2)).sum(), (clears & (combo1 == 1)).sum(), max_h.sum(), holes.sum(),
        (max_h > OWN_DANGER_HEIGHT).sum(), t.risen.astype(np.float64).sum(),
    ], dtype=np.float64)


def metric(s: np.ndarray, name: str) -> np.ndarray:
    """합 [..., len(PARTS)] → 지표 [...]. 분모가 0 이면 nan."""
    num, den = METRICS[name]
    a, b = s[..., IDX[num]], s[..., IDX[den]]
    return np.where(b > 0, a / np.where(b > 0, b, 1.0), np.nan)


def paired_test(d: np.ndarray, r: np.ndarray, name: str, sign: float, n_perm: int, seed: int) -> tuple[float, float]:
    """d, r: 짝지은 판별 합 [판, len(PARTS)] (설계, 기준). (rel, 한쪽 p). sign = +1 이면 클수록, -1 이면 작을수록."""
    rel = float(metric(d.sum(0), name) / metric(r.sum(0), name) - 1.0)
    flips = np.random.default_rng(seed).random((n_perm, len(d))) < 0.5
    sd = (~flips).astype(np.float64) @ d + flips.astype(np.float64) @ r
    sr = flips.astype(np.float64) @ d + (~flips).astype(np.float64) @ r
    null = metric(sd, name) / metric(sr, name) - 1.0
    p = (1 + np.sum(sign * null >= sign * rel - 1e-12)) / (n_perm + 1)
    return rel, float(p)


def check(value: float, op: str, threshold: float) -> bool:
    return bool(value <= threshold) if op == "<=" else bool(value >= threshold)


def embedding_checks(run_dir: Path, gen: int, names: list[str], label_of: dict[str, str], ref: str, rule: dict) -> dict:
    """체크포인트의 AI 임베딩(학습한 가중치)이 설계를 담는가 (spec.clustering).

    설계 거리(같은 라벨 0, 기준 라벨과 1, 그 밖 √2)와 임베딩 거리의 스피어만 순위 상관(Mantel, 점 순서 섞기 2,000 번의
    한쪽 p), 라벨마다 복제의 기준 중심 대비 변위 cos 와 '복제 거리 ÷ 가장 가까운 다른 라벨 거리', 라벨 수로 자른 k-means ARI.
    """
    import torch
    from scipy.stats import spearmanr
    from sklearn.cluster import KMeans
    from sklearn.metrics import adjusted_rand_score

    ckpt = torch.load(run_dir / "checkpoints" / f"gen-{gen:03d}.pt", map_location="cpu", weights_only=False)
    emb = ckpt["net"]["embedding.weight"].double().numpy()
    labels = [label_of[a] for a in names]
    d_emb = np.linalg.norm(emb[:, None] - emb[None], axis=2)
    d_des = np.array([[0.0 if a == b else (1.0 if ref in (a, b) else np.sqrt(2.0)) for b in labels] for a in labels])
    iu = np.triu_indices(len(names), 1)
    r = float(spearmanr(d_emb[iu], d_des[iu]).statistic)
    rng = np.random.default_rng(0)
    hits = sum(spearmanr(d_emb[iu], d_des[np.ix_(q, q)][iu]).statistic >= r - 1e-12
               for q in (rng.permutation(len(names)) for _ in range(2000)))
    mantel_p = (hits + 1) / 2001
    center = emb[[i for i, lab in enumerate(labels) if lab == ref]].mean(axis=0)
    per_label = {}
    for lab in dict.fromkeys(labels):
        idx = [i for i, x in enumerate(labels) if x == lab]
        others = [i for i, x in enumerate(labels) if x != lab]
        disp = emb[idx] - center
        norms = np.linalg.norm(disp, axis=1)
        # 기준 라벨의 변위는 자기 평균에서 잰 것이라 복제 둘이면 언제나 반대 방향(cos −1)이다. 뜻이 없으므로 내지 않는다
        cos = [] if lab == ref else [float(disp[i] @ disp[j] / (norms[i] * norms[j])) for i in range(len(idx))
                                     for j in range(i + 1, len(idx)) if norms[i] > 0 and norms[j] > 0]
        within = [d_emb[i, j] for i in idx for j in idx if i < j]
        nearest = float(d_emb[np.ix_(idx, others)].min()) if others else float("nan")
        per_label[lab] = {"displacement": norms.round(4).tolist(), "replica_cos": float(np.mean(cos)) if cos else None,
                          "replica_distance": float(np.mean(within)) if within else None, "nearest_other": nearest,
                          "replica_ratio": float(np.mean(within)) / nearest if within and nearest > 0 else None}
    k = len(set(labels))
    ari = None
    if 2 <= k < len(names):
        ari = float(adjusted_rand_score(labels, KMeans(n_clusters=k, n_init=20, random_state=0).fit(emb).labels_))
    checks = [{"metric": "mantel_r", "value": r, "p": mantel_p,
               "ok": bool(r >= rule["mantel_r_min"] and mantel_p <= rule["mantel_p_max"])}]
    for lab in rule.get("replica_labels", []):
        v = per_label.get(lab, {})
        ok = bool(v.get("replica_cos") is not None and v["replica_cos"] >= rule["replica_cos_min"]
                  and v.get("replica_ratio") is not None and v["replica_ratio"] <= rule["replica_ratio_max"])
        checks.append({"metric": f"replicas_{lab}", "cos": v.get("replica_cos"), "ratio": v.get("replica_ratio"), "ok": ok})
    stretch = {"metric": f"kmeans_k{k}_ari", "value": ari, "ok": bool(ari is not None and ari >= rule["kmeans_ari_stretch"])}
    return {"gen": gen, "mantel_r": r, "mantel_p": mantel_p, "per_label": per_label, "kmeans_ari": ari, "checks": checks,
            "stretch": stretch, "pass": all(c["ok"] for c in checks)}


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True)
    p.add_argument("--gen", type=int, required=True)
    p.add_argument("--tag", default=None, help="분석 폴더 꼬리 (analysis/gen-XXX-<tag>)")
    p.add_argument("--spec", default=DEFAULT_SPEC)
    p.add_argument("--out", default=None, help="결과 JSON (기본: 분석 폴더의 signatures.json)")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    spec = json.loads((ROOT / args.spec).read_text(encoding="utf-8"))
    table = json.loads((run_dir / "personas.json").read_text(encoding="utf-8"))["personas"]
    names = [q["name"] for q in table]
    label_of = {q["name"]: q["label"] for q in table}
    out_dir = run_dir / "analysis" / (f"gen-{args.gen:03d}" + (f"-{args.tag}" if args.tag else ""))
    games: dict[str, dict[tuple, np.ndarray]] = {ai: {} for ai in names}
    for m in read_matches(sorted((out_dir / "matches").glob("*.jsonl.gz"))):
        for t in trajectories(m, set(names)):
            games[t.ai][(m["seed"], t.side)] = game_parts(t)
    keys = sorted(games[names[0]])
    if not keys or any(sorted(games[ai]) != keys for ai in names):
        raise SystemExit(f"{out_dir}: AI 마다 둔 판(시드, 자리)이 다르거나 기록이 없음")
    parts = {ai: np.stack([games[ai][k] for k in keys]) for ai in names}  # [판, PARTS]
    labels = list(dict.fromkeys(label_of[ai] for ai in names))
    pooled = {lab: sum(parts[ai] for ai in names if label_of[ai] == lab) for lab in labels}
    ref = spec["reference_label"]
    if ref not in pooled:
        raise SystemExit(f"기준 라벨 {ref} 인 AI 가 없음 (라벨: {labels})")
    table_out = {lab: {k: float(metric(pooled[lab].sum(0), k)) for k in METRICS} for lab in labels}
    per_ai = {ai: {k: float(metric(parts[ai].sum(0), k)) for k in METRICS} for ai in names}
    n_perm, alpha = int(spec["permutations"]), float(spec["alpha"])
    result = {"run": args.run, "gen": args.gen, "analysis": str(out_dir.relative_to(ROOT)), "games_per_ai": len(keys),
              "reference": ref, "metrics": table_out, "per_ai": per_ai, "designs": {}}
    smin, smax = spec["strength"]["min"], spec["strength"]["max"]
    for lab, rule in spec["designs"].items():
        if lab not in pooled:
            continue
        out = {"checks": [], "pass": True}
        for kind in ("primary", "guard", "secondary"):
            for name, op, thr in rule.get(kind, []):
                sign = -1.0 if op == "<=" else 1.0
                rel, pv = paired_test(pooled[lab], pooled[ref], name, sign, n_perm, seed=zlib.crc32(f"{lab}:{name}".encode()))
                ok = check(rel, op, thr) and (kind != "primary" or pv < alpha)
                out["checks"].append({"kind": kind, "metric": name, "rel": rel, "op": op, "threshold": thr, "p": pv,
                                      "ok": ok})
                if kind in ("primary", "guard") and not ok:
                    out["pass"] = False
        for name, ratio in rule.get("ratio_over_other_archetypes", []):
            others = [x for x in spec["designs"] if x != lab and x in pooled]
            mine = table_out[lab][name]
            other = float(np.mean([table_out[x][name] for x in others]))
            ok = bool(mine >= ratio * other)
            out["checks"].append({"kind": "primary", "metric": f"{name}/others", "value": mine / other if other else None,
                                  "op": ">=", "threshold": ratio, "ok": ok})
            out["pass"] &= ok
        for name, how in rule.get("rank_first", []):
            vals = {x: table_out[x][name] for x in labels}
            best = (max if how == "max" else min)(vals, key=vals.get)
            ok = best == lab
            out["checks"].append({"kind": "rank_first", "metric": name, "best": best, "ok": ok})
            out["pass"] &= ok
        first = next(ai for ai in names if label_of[ai] == lab)
        wr_first, wr_pooled = per_ai[first]["win_rate"], table_out[lab]["win_rate"]
        out["strength"] = {"first_ai": first, "win_rate_first": wr_first, "win_rate_pooled": wr_pooled,
                           "ok": bool(smin <= wr_first <= smax and smin <= wr_pooled <= smax)}
        result["designs"][lab] = out
    ref_first = next(ai for ai in names if label_of[ai] == ref)
    result["reference_strength"] = {"first_ai": ref_first, "win_rate_first": per_ai[ref_first]["win_rate"],
                                    "win_rate_pooled": table_out[ref]["win_rate"]}
    if "clustering" in spec and (run_dir / "checkpoints" / f"gen-{args.gen:03d}.pt").exists():
        result["clustering"] = embedding_checks(run_dir, args.gen, names, label_of, ref, spec["clustering"])
    out_path = Path(args.out) if args.out else out_dir / "signatures.json"
    out_path.write_text(json.dumps(result, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    print(f"{args.run} 세대 {args.gen} ({result['analysis']}, AI 마다 {len(keys)}판): 기준 라벨 {ref}")
    print("라벨 | " + " | ".join(METRICS))
    for lab in labels:
        print(f"{lab:9s}| " + " | ".join(f"{table_out[lab][k]:.4f}" for k in METRICS))
    for lab, out in result["designs"].items():
        cells = []
        for c in out["checks"]:
            if "rel" in c:
                pv = f" (p {c['p']:.3f})" if c["kind"] == "primary" else ""
                cells.append(f"{c['metric']} {c['rel']:+.3f}{pv}{'✓' if c['ok'] else '✗'}")
            elif "value" in c:
                cells.append(f"{c['metric']} {c['value']:.2f}x{'✓' if c['ok'] else '✗'}")
            else:
                cells.append(f"1등 {c['metric']}={c['best']}{'✓' if c['ok'] else '✗'}")
        s = out["strength"]
        print(f"[{'통과' if out['pass'] else '미달'}] {lab}: " + ", ".join(cells)
              + f" | 승률 {s['win_rate_first']:.3f}/{s['win_rate_pooled']:.3f}{'✓' if s['ok'] else '✗'}")
    if "clustering" in result:
        c = result["clustering"]

        def r2(x):
            return "-" if x is None else f"{x:.2f}"

        print(f"[{'통과' if c['pass'] else '미달'}] 임베딩: Mantel r {c['mantel_r']:+.2f} (p {c['mantel_p']:.4f}) | "
              + ", ".join(f"{lab} 복제 cos {r2(v['replica_cos'])}·거리비 {r2(v['replica_ratio'])}"
                          for lab, v in c["per_label"].items())
              + f" | k-means ARI {r2(c['kmeans_ari'])} (목표 {spec['clustering']['kmeans_ari_stretch']})")
    print(f"→ {out_path}")


if __name__ == "__main__":
    main()
