"""결정 단위 페르소나 차이 (p05 의 1차 지표, runs/p05-design): 고정한 결정 묶음(probe)에서 AI 마다 고르는 배치를 비교한다.

    python python/tools/persona_probe.py --run p05b --gens 0,2,4,6,8,10
    python python/tools/persona_probe.py --run p04 --gens 0,10,30 --probe runs/p05-design/probe/ana10

probe 는 대전 기록을 다시 두며 결정마다 모든 후보 배치의 모델 입력을 덤프한 것이다(runs/p05-design/diag/scripts/
DumpCandidates.java: decisions.csv, cand_board.u8 [후보,24,10], cand_feat.i16 [후보,48] 빅엔디언, opp_board.u8 [결정,24,10],
opp_feat.i16 [결정,10]). 후보는 정규화 키 사전 순이라 argmax 의 첫 위치가 Java Chooser(τ = 0)의 동점 처리와 같다.

AI 마다 내보내는 점수 모듈(net.ScoreModule: R + γ·V + β·H)로 점수를 매겨 고른 후보를 구하고, 다음을 낸다.
같은 결정 묶음을 모든 실행과 세대에 쓰므로 실행·세대 사이 비교가 공정하다(상태 분포는 그 AI 자신의 것이 아니다).

    disagree            AI × AI: 두 AI 가 다른 배치를 고른 결정의 비율
    vs_reference        AI 마다 기준과 다른 배치를 고른 비율. 기준은 라벨이 neutral 인 AI 들(있으면), 없으면
                        S0(모든 AI 임베딩의 평균, 즉시 보상 없음)
    vs_s0               AI 마다 S0 와 다른 비율 (언제나)
    by_label            라벨마다 위 값의 평균, 같은 라벨 AI 끼리(복제)의 차이, 다른 라벨과의 최소 차이
    direction           AI 마다 기준이 고른 배치 대비 고른 배치의 항목 차이 평균 (결정당)
    cluster_ari         disagree 를 거리로 평균 연결 계층 군집 (라벨 수로 자름) ~ 라벨 ARI

결과는 runs/<run>/probe.json 에 세대별로 덧붙인다.
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
from tetris_ai.model_io import ATTACK, COMBO, LINES  # noqa: E402

NEUTRAL = "neutral"
DEFAULT_PROBE = "runs/p05-design/probe/ana10"


def load_probe(path: Path) -> dict:
    with open(path / "decisions.csv", encoding="utf-8") as f:
        dec = list(csv.DictReader(f))
    n = np.array([int(r["n"]) for r in dec], dtype=np.int64)
    start = np.array([int(r["start"]) for r in dec], dtype=np.int64)
    nd, nc = len(dec), int(n.sum())
    if nd == 0 or start[-1] + n[-1] != nc:
        raise SystemExit(f"{path}: decisions.csv 의 후보 수가 맞지 않음")
    return {
        "n": n, "start": start, "nd": nd, "nc": nc,
        "boards": np.fromfile(path / "cand_board.u8", dtype=np.uint8).reshape(nc, 24, 10),
        "features": np.fromfile(path / "cand_feat.i16", dtype=">i2").reshape(nc, 48).astype(np.float32),
        "opp_boards": np.fromfile(path / "opp_board.u8", dtype=np.uint8).reshape(nd, 24, 10),
        "opp_features": np.fromfile(path / "opp_feat.i16", dtype=">i2").reshape(nd, 10).astype(np.float32),
    }


def weight_vector(weights: dict[str, float]) -> torch.Tensor:
    """personas.json 의 {항목: 가중치} → net.REWARD_TERMS 순서 벡터. 없는 항목(옛 표)은 0, 모르는 항목은 거부한다."""
    unknown = set(weights) - set(netlib.REWARD_TERMS)
    if unknown:
        raise SystemExit(f"모르는 보상 항목 {sorted(unknown)} (net.REWARD_TERMS 를 확인)")
    return torch.tensor([float(weights.get(k, 0.0)) for k in netlib.REWARD_TERMS], dtype=torch.float32)


def choices(scores: torch.Tensor, pad: torch.Tensor) -> torch.Tensor:
    """후보 점수 [후보] → 결정마다 고른 후보 번호 [결정]. 같으면 앞(정규화 키 순) 후보."""
    m = torch.full(pad.shape, -torch.inf, device=scores.device)
    ok = pad >= 0
    m[ok] = scores[pad[ok]]
    return pad[torch.arange(len(pad), device=pad.device), torch.argmax(m, dim=1)]


def candidate_terms(features: torch.Tensor, boards: torch.Tensor) -> dict[str, torch.Tensor]:
    """방향을 볼 후보 항목 [후보]."""
    stats = netlib.board_stats(boards)
    lines, attack, combo1 = features[:, LINES], features[:, ATTACK], features[:, COMBO]
    rt = netlib.reward_terms(features, stats)
    return {
        "attack": attack, "lines": lines, "clear": (lines > 0).float(), "single": (lines == 1).float(),
        "tetris": (lines == 4).float(), "tspin": rt[:, netlib.REWARD_TERMS.index("tspin")],
        "combo_clear": rt[:, netlib.REWARD_TERMS.index("combo")],
        "combo_len": torch.where(lines > 0, (combo1 - 1).clamp(min=0), torch.zeros_like(lines)),
        "max_height": stats[:, netlib.STAT_MAX_HEIGHT], "holes": stats[:, netlib.STAT_TOTAL_HOLES],
        "bumpiness": stats[:, netlib.STAT_BUMPINESS], "agg_height": stats[:, :10].sum(dim=1),
    }


@torch.no_grad()
def probe_gen(run_dir: Path, gen: int, probe: dict, device: torch.device, batch: int = 8192) -> dict:
    cfg = json.loads((run_dir / "config.json").read_text(encoding="utf-8"))
    table = json.loads((run_dir / "personas.json").read_text(encoding="utf-8"))["personas"]
    names = [p["name"] for p in table]
    labels = [p["label"] for p in table]
    ckpt = torch.load(run_dir / "checkpoints" / f"gen-{gen:03d}.pt", map_location="cpu", weights_only=False)
    net = netlib.ValueNet(**ckpt["config"])
    net.load_state_dict(ckpt["net"])
    net = net.to(device).eval()
    if net.embedding.weight.shape[0] != len(names):
        raise SystemExit(f"체크포인트 임베딩 {net.embedding.weight.shape[0]}행 != AI 표 {len(names)}개")
    nd, nc = probe["nd"], probe["nc"]
    n, start = probe["n"], probe["start"]
    pad = np.full((nd, int(n.max())), -1, dtype=np.int64)
    dec_of = np.repeat(np.arange(nd), n)
    pad[dec_of, np.arange(nc) - start[dec_of]] = np.arange(nc)
    pad_t = torch.from_numpy(pad).to(device)
    dec_t = torch.from_numpy(dec_of).to(device)
    B = torch.from_numpy(probe["boards"]).to(device)
    F = torch.from_numpy(probe["features"]).to(device)
    OB = torch.from_numpy(probe["opp_boards"]).to(device)
    OF = torch.from_numpy(probe["opp_features"]).to(device)

    gamma, beta = float(cfg["gamma"]), float(cfg["prior_weight"])
    emb = net.embedding.weight.detach()
    refs = {f"ai:{name}": (emb[i], weight_vector(p["weights"])) for i, (name, p) in enumerate(zip(names, table))}
    refs["s0"] = (emb.mean(dim=0), torch.zeros(len(netlib.REWARD_TERMS)))
    chosen = {}
    for key, (e, w) in refs.items():
        w = w.to(device)
        score = torch.empty(nc, device=device)
        for s in range(0, nc, batch):
            sl = slice(s, min(s + batch, nc))
            board = B[sl].float()
            feats = F[sl]
            d = dec_t[sl]
            stats = netlib.board_stats(board)
            v = net.value(board, feats, OB[d].float(), OF[d], e.unsqueeze(0).expand(board.shape[0], -1)).squeeze(1)
            r = netlib.reward_terms(feats, stats) @ w
            score[sl] = r + gamma * v + beta * netlib.baseline_score(feats, stats).squeeze(1)
        chosen[key] = choices(score, pad_t)

    terms = {}
    for s in range(0, nc, batch * 4):
        sl = slice(s, min(s + batch * 4, nc))
        for k, v in candidate_terms(F[sl], B[sl].float()).items():
            terms.setdefault(k, []).append(v)
    terms = {k: torch.cat(v) for k, v in terms.items()}

    ai_keys = [f"ai:{name}" for name in names]
    c = torch.stack([chosen[k] for k in ai_keys])  # [AI, 결정]
    disagree = (c[:, None, :] != c[None, :, :]).float().mean(dim=2).cpu().numpy()
    neutral = [i for i, lab in enumerate(labels) if lab == NEUTRAL]
    vs_s0 = (c != chosen["s0"][None]).float().mean(dim=1).cpu().numpy()
    if neutral:
        vs_ref = disagree[:, neutral].mean(axis=1)
        ref_choice = chosen[ai_keys[neutral[0]]]
        ref_name = "neutral"
    else:
        vs_ref = vs_s0
        ref_choice = chosen["s0"]
        ref_name = "s0"
    direction = {name: {k: float((t[c[i]] - t[ref_choice]).mean()) for k, t in terms.items()}
                 for i, name in enumerate(names)}
    base = {k: float(t[ref_choice].mean()) for k, t in terms.items()}

    by_label = {}
    for lab in dict.fromkeys(labels):
        idx = [i for i, x in enumerate(labels) if x == lab]
        others = [i for i, x in enumerate(labels) if x != lab]
        within = [disagree[i, j] for i in idx for j in idx if i < j]
        by_label[lab] = {
            "ais": [names[i] for i in idx],
            "vs_reference": float(np.mean(vs_ref[idx])),
            "vs_s0": float(np.mean(vs_s0[idx])),
            "within_label": float(np.mean(within)) if within else None,
            "nearest_other_label": float(min(disagree[i, j] for i in idx for j in others)) if others else None,
        }
    ari = None
    if len(set(labels)) >= 2 and len(set(labels)) < len(names):
        from scipy.cluster.hierarchy import fcluster, linkage
        from scipy.spatial.distance import squareform
        from sklearn.metrics import adjusted_rand_score

        k = len(set(labels))
        z = linkage(squareform(disagree, checks=False), method="average")
        clusters = fcluster(z, t=k, criterion="maxclust")
        ari = float(adjusted_rand_score(labels, clusters))
    return {
        "gen": gen, "decisions": nd, "candidates": nc, "reference": ref_name, "names": names, "labels": labels,
        "disagree": np.round(disagree, 5).tolist(), "vs_reference": dict(zip(names, np.round(vs_ref, 5).tolist())),
        "vs_s0": dict(zip(names, np.round(vs_s0, 5).tolist())), "by_label": by_label, "cluster_ari": ari,
        "direction": direction, "reference_choice_mean": base,
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", required=True, help="페르소나 실행 이름 (runs/<run>)")
    p.add_argument("--gens", required=True, help="세대 목록, 쉼표로 (체크포인트가 남은 세대)")
    p.add_argument("--probe", default=DEFAULT_PROBE, help=f"결정 묶음 폴더 (저장소 기준, 기본 {DEFAULT_PROBE})")
    p.add_argument("--out", default=None, help="결과 JSON (기본 runs/<run>/probe.json, 세대별로 덧붙임)")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    out = Path(args.out) if args.out else run_dir / "probe.json"
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    t0 = time.time()
    probe = load_probe(ROOT / args.probe)
    results = json.loads(out.read_text(encoding="utf-8")) if out.exists() else {}
    for gen in (int(g) for g in args.gens.split(",")):
        r = probe_gen(run_dir, gen, probe, device)
        r["probe"] = args.probe
        results[str(gen)] = r
        out.write_text(json.dumps(results, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        labs = r["by_label"]
        print(f"[{args.run} 세대 {gen}] 결정 {r['decisions']}, 기준 {r['reference']} | 라벨: 기준과 다른 결정 (S0 와) "
              f"/ 복제끼리 / 가장 가까운 다른 라벨 | 군집 ARI {r['cluster_ari']}")
        for lab, v in labs.items():
            within = "-" if v["within_label"] is None else f"{v['within_label']:.3f}"
            near = "-" if v["nearest_other_label"] is None else f"{v['nearest_other_label']:.3f}"
            print(f"  {lab:9s} {v['vs_reference']:.3f} ({v['vs_s0']:.3f}) / {within} / {near}  {', '.join(v['ais'])}")
    print(f"→ {out} ({time.time() - t0:.0f}초)")


if __name__ == "__main__":
    main()
