"""페르소나 임베딩 군집 분석 (docs/TRAINING.md 9.5, 9.6, PLAN 8.3). scikit-learn 이 필요하다(python/requirements-train.txt).

임베딩(학습 가중치)과 행동 지표를 따로 묶고, 군집끼리, 군집과 설계 라벨이 얼마나 맞는지(ARI, NMI) 본다.
군집마다 대표 AI 를 고르고 행동 지표로 이름을 붙인다.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np
from scipy.optimize import linear_sum_assignment
from scipy.stats import spearmanr
from sklearn.cluster import KMeans
from sklearn.decomposition import PCA
from sklearn.metrics import adjusted_rand_score, normalized_mutual_info_score, silhouette_score
from sklearn.mixture import GaussianMixture

from .metrics import DANGER_HEIGHT, OWN_DANGER_HEIGHT, PRESSURE_PENDING, heights_and_holes
from .model_io import ATTACK, COMBO, LINES
from .records import Trajectory

# 행동 군집에 넣는 지표 (TRAINING.md 9.5). 승률·턴 수는 실력이라 넣지 않는다(PLAN 8.2).
BEHAVIOR_KEYS = (
    "app", "tetris_rate", "tspin_rate", "combo_rate", "max_height_mean", "holes_mean", "bumpiness_mean", "hold_rate",
    "cancel_ratio", "pressure_response", "defense_response", "risen_per_piece", "danger_rate",
)

# 군집 이름을 붙일 때 보는 지표와 방향 (TRAINING.md 9.6). 점수는 군집 평균 z-score 에 방향을 곱해 평균한 값이다.
SIGNATURES: dict[str, dict[str, float]] = {
    "attacker": {"app": 1.0, "tetris_rate": 1.0},
    "defender": {"risen_per_piece": -1.0, "danger_rate": -1.0},  # 10-04 수비 원형(위험할 때만 비용)에 맞춤
    "tspin": {"tspin_rate": 1.0},
    "combo": {"combo_rate": 1.0},
}


def kmeans(x: np.ndarray, k: int, seed: int = 0) -> tuple[np.ndarray, np.ndarray]:
    """k-means (시작점 20 번). (군집 번호 [n], 중심 [k, d])."""
    km = KMeans(n_clusters=k, n_init=20, random_state=seed).fit(x)
    return km.labels_.astype(int), km.cluster_centers_


def gmm(x: np.ndarray, k: int, seed: int = 0) -> np.ndarray:
    """가우스 혼합 (구형 공분산, 시작점 10 번). 점이 12 개뿐이라 군집마다 분산 하나만 둔다."""
    g = GaussianMixture(n_components=k, covariance_type="spherical", n_init=10, reg_covar=1e-6, random_state=seed).fit(x)
    return g.predict(x).astype(int)


def silhouette(x: np.ndarray, labels: np.ndarray) -> float:
    """실루엣 점수. 군집이 하나뿐이거나 점마다 군집이면 정의되지 않아 nan."""
    k = len(set(labels.tolist()))
    return float(silhouette_score(x, labels)) if 2 <= k < len(x) else float("nan")


def scan(x: np.ndarray, ks: Sequence[int], seed: int = 0) -> list[dict]:
    """k 마다 k-means·GMM 군집과 실루엣 점수."""
    rows = []
    for k in ks:
        km, _ = kmeans(x, k, seed)
        gm = gmm(x, k, seed)
        rows.append({"k": k, "kmeans": km, "kmeans_silhouette": silhouette(x, km), "gmm": gm,
                     "gmm_silhouette": silhouette(x, gm)})
    return rows


def best_k(rows: list[dict], key: str = "kmeans_silhouette") -> int:
    """실루엣이 가장 높은 k (같으면 작은 k)."""
    scores = [(-r[key] if not np.isnan(r[key]) else np.inf, r["k"]) for r in rows]
    return min(scores)[1]


def zscore(m: np.ndarray) -> np.ndarray:
    """열마다 z-score (모표준편차). nan 은 그 열의 평균으로 채우고, 표준편차가 0 인 열은 0 이 된다."""
    m = np.array(m, dtype=np.float64)
    nan = np.isnan(m)
    counts = (~nan).sum(axis=0)
    col_mean = np.where(nan, 0.0, m).sum(axis=0) / np.maximum(counts, 1)
    m = np.where(nan, col_mean, m)
    sd = m.std(axis=0)
    return np.where(sd > 0, (m - m.mean(axis=0)) / np.where(sd > 0, sd, 1.0), 0.0)


def pca2(x: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """PCA 2 차원 좌표 [n, 2] 와 설명된 분산 비율 [2]."""
    p = PCA(n_components=2).fit(x)
    return p.transform(x), p.explained_variance_ratio_


def compare(a: Sequence[int], b: Sequence[int]) -> dict[str, float]:
    """두 군집 결과(또는 라벨)가 얼마나 같은지. ARI 는 우연이면 0, 같으면 1. NMI 는 0~1."""
    return {"ari": float(adjusted_rand_score(a, b)), "nmi": float(normalized_mutual_info_score(a, b))}


def representatives(x: np.ndarray, labels: np.ndarray) -> dict[int, int]:
    """군집마다 군집 평균에 가장 가까운 점의 번호 (TRAINING.md 9.6 의 대표 AI)."""
    out = {}
    for c in sorted(set(labels.tolist())):
        idx = np.flatnonzero(labels == c)
        out[c] = int(idx[np.argmin(np.linalg.norm(x[idx] - x[idx].mean(axis=0), axis=1))])
    return out


def name_clusters(z: np.ndarray, keys: Sequence[str], labels: np.ndarray) -> tuple[dict[int, str], dict[int, dict]]:
    """군집마다 행동 지표로 이름을 붙인다 (TRAINING.md 9.6).

    z 는 행동 지표 z-score [AI 수, 지표 수], keys 는 그 열 이름이다. 군집 × 이름 점수(SIGNATURES)의 합이 가장 크도록
    헝가리안 방법으로 하나씩 짝짓고, 이름보다 군집이 많으면 남는 군집은 mixed1, mixed2, … 다.
    (이름표, {군집: {이름: 점수}}) 를 돌려준다.
    """
    keys = list(keys)
    clusters = sorted(set(labels.tolist()))
    names = list(SIGNATURES)
    score = np.zeros((len(clusters), len(names)))
    for i, c in enumerate(clusters):
        mean = z[labels == c].mean(axis=0)
        for j, name in enumerate(names):
            sig = SIGNATURES[name]
            score[i, j] = sum(w * mean[keys.index(k)] for k, w in sig.items()) / len(sig)
    rows, cols = linear_sum_assignment(-score)
    out = {clusters[r]: names[c] for r, c in zip(rows, cols)}
    for n, c in enumerate([c for c in clusters if c not in out], 1):
        out[c] = f"mixed{n}"
    return out, {c: dict(zip(names, map(float, score[i]))) for i, c in enumerate(clusters)}


# 판 하나에서 모으는 합과 개수. 지표는 모두 이 합들의 비율이라, 판을 다시 뽑아도 뽑은 판의 합만 더하면 된다.
PARTS = (
    "games", "score", "turns", "pieces", "attack", "sent", "lines", "tspin", "tetris", "max_height", "holes", "bumpiness",
    "hold", "danger", "sent_danger", "safe", "sent_safe", "pressured", "clear_pressured", "calm", "clear_calm", "combo",
    "clears", "starts", "risen", "own_danger",
)


def trajectory_parts(t: Trajectory) -> np.ndarray:
    """궤적(판) 하나의 합과 개수 [len(PARTS)]. 정의는 metrics.summarize, personas.persona_metrics 와 같다."""
    lines, attack, combo_plus_one = t.features[:, LINES], t.features[:, ATTACK], t.features[:, COMBO]
    heights, holes = heights_and_holes(t.boards)
    opp_heights, _ = heights_and_holes(t.opp_boards)
    danger = opp_heights.max(axis=1) >= DANGER_HEIGHT
    pressured = t.pending_before >= PRESSURE_PENDING
    sent = t.sent.astype(np.float64)
    clears = lines > 0
    return np.array([
        1, (t.outcome > 0) + 0.5 * (t.outcome == 0), t.turns, len(lines), attack.sum(), sent.sum(), lines.sum(),
        ((t.spin > 0) & clears).sum(), (lines == 4).sum(), heights.max(axis=1).sum(), holes.sum(),
        np.abs(np.diff(heights, axis=1)).sum(), t.hold_used.sum(), danger.sum(), sent[danger].sum(), (~danger).sum(),
        sent[~danger].sum(), pressured.sum(), clears[pressured].sum(), (~pressured).sum(), clears[~pressured].sum(),
        (clears & (combo_plus_one >= 2)).sum(), clears.sum(), (clears & (combo_plus_one == 1)).sum(),
        t.risen.sum(), (heights.max(axis=1) > OWN_DANGER_HEIGHT).sum(),
    ], dtype=np.float64)


def metrics_from_parts(s: np.ndarray) -> dict[str, np.ndarray]:
    """합 [..., len(PARTS)] → 지표. 앞쪽 축(부트스트랩 반복 등)은 그대로 둔다. 분모가 0 이면 nan."""
    p = {name: s[..., i] for i, name in enumerate(PARTS)}

    def ratio(a: np.ndarray, b: np.ndarray) -> np.ndarray:
        return np.where(b > 0, a / np.where(b > 0, b, 1.0), np.nan)

    m = {
        "win_rate": ratio(p["score"], p["games"]),
        "turns_mean": ratio(p["turns"], p["games"]),
        "app": ratio(p["attack"], p["pieces"]),
        "sent_per_piece": ratio(p["sent"], p["pieces"]),
        "lines_per_piece": ratio(p["lines"], p["pieces"]),
        "tspin_rate": ratio(p["tspin"], p["pieces"]),
        "tetris_rate": ratio(p["tetris"], p["pieces"]),
        "max_height_mean": ratio(p["max_height"], p["pieces"]),
        "holes_mean": ratio(p["holes"], p["pieces"]),
        "bumpiness_mean": ratio(p["bumpiness"], p["pieces"]),
        "hold_rate": ratio(p["hold"], p["pieces"]),
        "risen_per_piece": ratio(p["risen"], p["pieces"]),
        "danger_rate": ratio(p["own_danger"], p["pieces"]),
        "cancel_ratio": ratio(p["attack"] - p["sent"], p["attack"]),
        "combo_rate": ratio(p["combo"], p["pieces"]),
        "combo_len": ratio(p["clears"], p["starts"]),
        "pressure_response": ratio(p["sent_danger"], p["danger"]) - ratio(p["sent_safe"], p["safe"]),
        "defense_response": ratio(p["clear_pressured"], p["pressured"]) - ratio(p["clear_calm"], p["calm"]),
    }
    return m


def bootstrap_se(parts: np.ndarray, keys: Sequence[str], n_boot: int = 200, seed: int = 0) -> dict[str, float]:
    """판 단위 부트스트랩으로 지표마다 표준오차 (TRAINING.md 9.5 잡음 확인). parts 는 판별 합 [판 수, len(PARTS)].

    다시 뽑은 판들의 합으로 지표를 계산한다. persona_metrics 를 n_boot 번 부르는 것과 같은 값이지만 보드 통계를 다시
    계산하지 않아 훨씬 빠르다. 판을 서로 독립으로 보므로, 모든 AI 가 같은 시드로 둔 판끼리 비교할 때는 잡음을 크게 잡는다.
    """
    idx = np.random.default_rng(seed).integers(len(parts), size=(n_boot, len(parts)))
    m = metrics_from_parts(parts[idx].sum(axis=1))
    return {k: float(np.nanstd(m[k], ddof=1)) if np.sum(~np.isnan(m[k])) > 1 else float("nan") for k in keys}


def permutation_spread_test(parts: np.ndarray, keys: Sequence[str], n_perm: int = 1000,
                            seed: int = 0) -> dict[str, dict[str, float]]:
    """AI 사이에 지표 차이가 있는지 보는 짝지은 순열 검정 (TRAINING.md 9.5).

    parts 는 판별 합 [AI 수, 판 수, len(PARTS)] 이고, 판 j 는 모든 AI 가 같은 시드·같은 자리로 같은 상대와 둔 판이다.
    "AI 가 상관없다"는 귀무가설에서는 판마다 AI 이름을 섞어도 되므로, 섞은 뒤의 AI 평균 표준편차가 실제 이상일 확률이 p 다.
    같은 시드로 둔 판끼리의 상관을 그대로 쓰므로 독립 부트스트랩보다 작은 차이도 잡는다.
    """
    n_ai, n_games, _ = parts.shape

    def spread(p: np.ndarray) -> dict[str, float]:
        m = metrics_from_parts(p.sum(axis=1))
        return {k: float(np.nanstd(m[k], ddof=1)) for k in keys}

    observed = spread(parts)
    rng = np.random.default_rng(seed)
    hits = {k: 0 for k in keys}
    for _ in range(n_perm):
        order = np.argsort(rng.random((n_ai, n_games)), axis=0)  # 판마다 AI 순서를 따로 섞는다
        s = spread(np.take_along_axis(parts, order[:, :, None], axis=0))
        for k in keys:
            hits[k] += s[k] >= observed[k] - 1e-12
    return {k: {"spread": observed[k], "p": (hits[k] + 1) / (n_perm + 1)} for k in keys}


def distance_matrix(x: np.ndarray) -> np.ndarray:
    """점 사이 유클리드 거리 [n, n]."""
    x = np.asarray(x, dtype=np.float64)
    return np.linalg.norm(x[:, None, :] - x[None, :, :], axis=2)


def mantel(a: np.ndarray, b: np.ndarray, n_perm: int = 2000, seed: int = 0) -> dict[str, float]:
    """두 거리 행렬의 스피어만 순위 상관과 한쪽 순열 p 값 (Mantel 검정, TRAINING.md 9.5).

    점 n 개의 짝 n(n-1)/2 개에서 a 의 거리와 b 의 거리가 함께 커지는지 본다. p 는 한쪽 행렬의 점 순서를 섞었을 때
    상관이 지금 이상일 확률이다. 군집 ARI 보다 점이 적을 때 힘이 세다.
    """
    iu = np.triu_indices(len(a), 1)
    r = float(spearmanr(a[iu], b[iu]).statistic)
    rng = np.random.default_rng(seed)
    hits = 0
    for _ in range(n_perm):
        p = rng.permutation(len(a))
        hits += spearmanr(a[iu], b[p][:, p][iu]).statistic >= r - 1e-12
    return {"r": r, "p": (hits + 1) / (n_perm + 1)}


def dose_response(ratios: dict[str, np.ndarray], values: dict[str, np.ndarray], n_perm: int = 2000,
                  seed: int = 0) -> dict[str, dict[str, float]]:
    """원형마다 그 원형의 비율과 대표 지표(SIGNATURES)가 AI 사이에서 함께 움직이는지 (TRAINING.md 9.5).

    ratios 는 {원형: AI 마다의 비율}, values 는 {지표: AI 마다의 값}. 스피어만 ρ 에 지표 방향(수비는 낮을수록)을 곱하고,
    지표 값의 AI 순서를 n_perm 번 섞어 ρ 가 실제 이상일 확률을 한쪽 p 로 낸다. 점이 12 개라 |ρ| 가 0.5 는 넘어야 한다.
    """
    rng = np.random.default_rng(seed)
    out = {}
    for name, signature in SIGNATURES.items():
        x = np.asarray(ratios[name], dtype=np.float64)
        for key, sign in signature.items():
            y = sign * np.asarray(values[key], dtype=np.float64)
            rho = float(spearmanr(x, y).statistic)
            if np.isnan(rho):
                out[f"{name}~{key}"] = {"rho": rho, "p": float("nan")}
                continue
            null = np.array([spearmanr(x, rng.permutation(y)).statistic for _ in range(n_perm)])
            out[f"{name}~{key}"] = {"rho": rho, "p": float((np.sum(null >= rho - 1e-12) + 1) / (n_perm + 1))}
    return out


def embedding_trend(emb_by_gen: dict[int, np.ndarray], ratios: np.ndarray, names: Sequence[str],
                    n_perm: int = 1000) -> list[dict]:
    """세대마다 임베딩이 설계를 얼마나 담는지 (TRAINING.md 9.8): 임베딩 사이 평균 거리, 임베딩 거리 ~ 비율 거리 순위 상관과
    p, 임베딩 PC1 과 원형 비율마다의 상관 절댓값. emb_by_gen 은 {세대: [AI 수, 차원]}, ratios 는 [AI 수, 원형 수]."""
    d_ratio = distance_matrix(ratios)
    rows = []
    for gen in sorted(emb_by_gen):
        e = emb_by_gen[gen]
        m = mantel(distance_matrix(e), d_ratio, n_perm=n_perm)
        n = len(e)
        spread = float(distance_matrix(e).sum() / (n * (n - 1)))
        pc, var = pca2(e)
        corr = correlations(pc[:, :1], {name: ratios[:, j] for j, name in enumerate(names)})
        rows.append({"gen": gen, "emb_spread": spread, "mantel_r": m["r"], "mantel_p": m["p"], "pc1_var": float(var[0]),
                     **{f"pc1_abs_r_{name}": abs(v[0]) for name, v in corr.items()}})
    return rows


def correlations(coords: np.ndarray, columns: dict[str, Sequence[float]]) -> dict[str, list[float]]:
    """좌표 축마다 각 열과의 피어슨 상관. {열 이름: [축 1 과의 r, 축 2 와의 r, …]}."""
    out = {}
    for name, values in columns.items():
        v = np.asarray(values, dtype=np.float64)
        out[name] = [float(np.corrcoef(coords[:, j], v)[0, 1]) if v.std() > 0 else float("nan")
                     for j in range(coords.shape[1])]
    return out


def spread_over_noise(means: np.ndarray, ses: np.ndarray) -> float:
    """AI 평균들의 표준편차 ÷ 표준오차의 RMS. 1 근처면 AI 사이 차이가 잡음 수준이다."""
    means, ses = np.asarray(means, float), np.asarray(ses, float)
    ok = ~np.isnan(means) & ~np.isnan(ses)
    if ok.sum() < 2:
        return float("nan")
    noise = float(np.sqrt(np.mean(ses[ok] ** 2)))
    return float(np.std(means[ok], ddof=1) / noise) if noise > 0 else float("inf")
