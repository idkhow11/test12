"""승률 예측망 wp02 의 사후 보정층: 게임이 아는 블록 수와 판 시간으로 출력을 고친다 (TRAINING.md 10.11 의 다음 과제).

    python python/tools/fit_winprob_calibration.py --out wpcal02 --refresh \
        --extra-test ood_versus=runs/wpcal02_ood/data/chunk-*/w*.jsonl.gz

wp02 는 두 보드만 보고 속도 차이를 쌓인 양의 차이로 읽는다. 두 플레이어의 실제 속도가 비슷하면(초당 블록 수 비율 1.25배 미만)
확신이 지나치다. 게임은 매 순간 두 플레이어가 고정한 블록 수(GameModel.pieces())와 판 시간(TimedMatch.time())을 안다. 그 값으로
모델 출력 p 를 고치는 작은 보정층 후보들을 **검증 판으로만** 맞추고 골라, 시험 판에서 wp01, wp02 와 비교한다.

자료는 runs/<timed-run>/data 의 timed-1 기록(wp02 가 학습한 묶음 0~83)이다. 판 단위 나누기(시드 끝자리 8 검증, 9 시험)와
표본(판마다 고정 3번에 하나, 턴 번호가 3의 배수)은 train_winprob.py 와 같다. 표본마다 Java 가 그 순간 계산할 수 있는 값을 만든다.

  n1, n2  두 플레이어의 GameModel.pieces(): 패배 없이 고정한 블록 수. 기록에는 없어서 보드가 바뀐 횟수로 센다(piece_counts)
  t       TimedMatch.time(): 판 시작부터 흐른 ms. 기록의 steps[].t 와 같은 값이다(게임이 승률 막대를 다시 재는 틱)

**판이 끝난 순간의 기록은 맞추지도 재지도 않는다(live_mask).** 게임(GameSession.advance)은 어느 한쪽이라도 끝난 틱에는 모델을
부르지 않고 결과(1, 0, 0.5)를 바로 막대에 놓으므로, 보정층이 쓰이는 순간은 그 기록을 뺀 나머지다. wp02 의 metrics.json 과 같은
표본인지는 빼기 전에 확인한다.

후보(모두 자리 대칭: P1 과 P2 를 바꾸면 1 − p 가 된다)와 고르는 규칙은 CANDIDATES 와 choose() 에 있다. 결과 (runs/<out>/):

  calibration.json   고른 보정층. Java 구현용(식, 계수, 입력 정의, 경계 처리, 맞춘 모델과 그 파일의 SHA-256)
  metrics.json       후보마다 검증 성적, 시험 판의 전체·속도 구간·판 단계별 비교, 판 단위 짝 부트스트랩, 자료 확인 값,
                     --extra-test 로 준 다른 기록의 성적(extra_test)
  parity_cases.json  Java 와 같은 값인지 확인할 사례: 보정 식만(p, n1, n2, t → p_cal), 시험 판의 실제 상태 쌍(→ p, p_cal)
  fit.log            진행 기록과 마크다운 표
  cache.npz          읽은 자료와 예측의 캐시 (--refresh 로 다시 만든다)

--extra-test 이름=글롭 (여러 번 줄 수 있다): 맞추는 데 쓰지 않은 다른 timed-1 기록(예: 지금 모델 묶음으로 새로 만든 판
runs/wpcal02_ood, 사람 판 runs/human/data/games/*.jsonl.gz)에서 시드와 상관없이 모든 판의 **모든 막대 순간**(고정마다,
끝난 순간 제외)을 고른 보정층과 wp02, wp01 로 잰다. 자료를 만든 모델 묶음이 다르면(예: 10-04 뒤 1~3단계가 느려짐) 분포 밖 시험이다.

게임에 넣는 순서 (TRAINING.md 10.12):

    python python/tools/make_bundle.py --calibration-only --winprob-calibration runs/<out>/calibration.json
    python python/tools/fit_winprob_calibration.py --fixture-from <out>

둘째 명령은 다시 맞추지 않고 runs/<out>/ 의 calibration.json, parity_cases.json 으로 Java 와 함께 쓰는 공통 픽스처
fixtures/winprob_calibration/cases.jsonl 을 다시 쓴다(write_fixture). 확장자가 .json 이 아닌 것은 fixtures/ 의 모든 .json 을
규칙 픽스처로 읽는 FixtureTest 가 이 파일을 모르는 형식으로 거부하지 않게 하려는 것이다.
"""

from __future__ import annotations

import argparse
import glob
import gzip
import hashlib
import json
import re
import sys
import time
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

from tetris_ai import winprob as wp  # noqa: E402
from tetris_ai.model_io import RULES_VERSION, WIDTH  # noqa: E402

SIDES = ("p1", "p2")
FORMAT = "winprob-calibration-1"

# ---------------------------------------------------------------------- 게임이 아는 값 (Java 와 같은 정의)


def piece_counts(steps: list[dict], topout: dict | None = None) -> np.ndarray:
    """기록의 고정마다 두 플레이어의 GameModel.pieces() [len(steps), 2].

    pieces() 는 GameModel.lockState 가 락 아웃이 아닌 고정마다 1 올린다. 기록에는 그 값이 없어서 보드가 바뀐 횟수로 센다.
    보드는 자기 고정(블록을 놓고 줄을 지우고 가비지가 올라옴)에서만 바뀌고(받은 공격은 대기열 pending 에만 쌓인다), 기록은
    누구든 고정한 틱마다 남으므로 두 기록 사이에 한 플레이어가 고정하는 것은 많아야 한 번이다. 판은 빈 보드에서 시작하므로
    첫 기록에서 보드가 비어 있지 않으면 그 플레이어가 그때 고정한 것이다. 홀드는 보드를 바꾸지 않는다.

    다음 블록 목록(queue)이 바뀐 횟수(legacy_queue_counts)는 첫 기록을 늘 1로 세고(아직 고정하지 않았어도), 홀드 칸이 빈
    채로 한 첫 홀드도 센다. 그래서 판 끝에서 pieces() 보다 0~2 크다.

    락 아웃(블록이 모두 숨은 줄에 고정)은 보드는 바꾸지만 pieces() 를 올리지 않는다. 판이 그 틱에 끝나므로 마지막 기록에서만
    생기고, topout 에 그 플레이어의 lock_out 이 있으면 마지막 값에서 1 을 뺀다(끝난 판에는 Java 가 보정층을 쓰지 않는다).
    """
    out = np.zeros((len(steps), 2), np.int32)
    for i, side in enumerate(SIDES):
        prev = None
        count = 0
        changed = False
        for k, s in enumerate(steps):
            rows = s[side]["state"]["rows"]
            changed = any(rows) if prev is None else rows != prev
            count += int(changed)
            out[k, i] = count
            prev = rows
        if steps and topout and topout.get(side) == "lock_out" and changed:
            out[-1, i] -= 1
    return out


def legacy_queue_counts(steps: list[dict]) -> np.ndarray:
    """winprob_speed_subsets.py 의 셈: 고정마다 다음 블록 목록이 바뀐 횟수(첫 기록은 늘 1) [len(steps), 2]. 판 끝의 속도
    구간을 TRAINING.md 10.11 의 표와 같게 나누려고 그대로 둔다."""
    out = np.zeros((len(steps), 2), np.int32)
    for i, side in enumerate(SIDES):
        last, count = None, 0
        for k, s in enumerate(steps):
            q = s[side]["state"]["queue"]
            if q != last:
                count += 1
                last = q
            out[k, i] = count
    return out


def queue_shift(prev: str, cur: str) -> int | None:
    """두 기록 사이에 가방이 몇 칸 나아갔는가(다음 블록 목록이 앞으로 밀린 칸 수). 7-bag 에서는 다섯 칸 안에 같은 블록이
    셋 들어갈 수 없어서 0~2칸은 헷갈리지 않는다. 겹치는 블록이 하나도 없는 밀림(5칸 이상)은 확인할 수 없으므로, 맞는 칸
    수가 없을 때와 같이 None."""
    for s in range(len(prev)):
        if cur[: len(cur) - s] == prev[s:]:
            return s
    return None


def bag_check(steps: list[dict], counts: np.ndarray, topout: dict | None = None) -> int:
    """piece_counts 를 다른 길로 확인한다: 첫 기록 뒤로 가방이 나아간 칸 수 = 그동안의 고정 수 + 첫 홀드(홀드 칸이 비어
    있다가 참). GameModel 에서 가방은 고정 뒤 스폰과 홀드 칸이 빈 채로 한 홀드에서만 나아간다. 맞지 않는 (기록, 플레이어) 수.
    패배로 끝난 판의 마지막 기록은 가비지 아웃(고정은 셌지만 스폰하지 않음)일 수 있어 빼고 센다."""
    bad = 0
    last_ok = len(steps) - (1 if topout and any(topout.get(s) for s in SIDES) else 0)
    for i, side in enumerate(SIDES):
        h0 = steps[0][side]["state"]["hold"] is not None
        shifted, prev = 0, steps[0][side]["state"]["queue"]
        for k in range(1, last_ok):
            st = steps[k][side]["state"]
            s = queue_shift(prev, st["queue"])
            if s is None:
                bad += 1
                break
            shifted += s
            prev = st["queue"]
            if shifted != (counts[k, i] - counts[0, i]) + int(st["hold"] is not None) - int(h0):
                bad += 1
    return bad


def live_mask(d: dict) -> np.ndarray:
    """표본마다 게임이 그 순간 승률 예측망과 보정층을 쓰는가(True). 판이 끝난 틱의 기록이면 False.

    TimedRecorder 는 누구든 고정한 틱마다 기록하고 TimedMatch 는 패배가 난 틱에서 멈춘다. 그래서 패배가 고정한 틱에 났으면(락
    아웃, 가비지 아웃, 고정 뒤 스폰의 블록 아웃) 마지막 기록이 그 틱(t == time_ms)이고, GameSession.advance 는 그 틱에 모델을
    부르지 않고 결과를 막대에 놓는다. 홀드로 꺼낸 블록의 블록 아웃은 고정이 없는 틱에 끝나므로 마지막 기록이 그 전의 진행 중
    상태다(t < time_ms). 시간 상한으로 끝난 판(topout 없음)과 기록 상한(MAX_STEPS)에서 잘린 판의 기록도 모두 진행 중이다."""
    games = d["games"]
    turns = np.array([g["turns"] for g in games], np.int64)
    time_ms = np.array([g["time_ms"] for g in games], np.int64)
    over = np.array([bool(g.get("topout")) and any(g["topout"].get(s) for s in SIDES) for g in games])
    m = d["match"]
    return ~((d["turn"] == turns[m] - 1) & (d["t"] == time_ms[m]) & over[m])


def subset(d: dict, mask: np.ndarray) -> dict:
    """나누기 하나(load_cache 의 모양)에서 표본만 고른다. 판 정보와 match 번호는 그대로다."""
    out = {k: d[k][mask] for k in SAMPLE_KEYS}
    out["p"] = {run: v[mask] for run, v in d["p"].items()}
    out["games"] = d["games"]
    return out


def speed_ratio(n1, n2, k: float = 0.0):
    """지금까지의 속도 비(느린 쪽 ÷ 빠른 쪽, 0~1). 두 플레이어의 판 시간이 같으므로 초당 블록 수의 비는 블록 수의 비와 같다.
    k 는 두 수에 더하는 가상의 블록 수(판 초반의 흔들림을 1 쪽으로 줄인다). 둘 다 0 이면(k = 0) 1."""
    n1 = np.asarray(n1, np.float64)
    n2 = np.asarray(n2, np.float64)
    lo, hi = np.minimum(n1, n2) + k, np.maximum(n1, n2) + k
    return np.where(hi > 0, lo / np.where(hi > 0, hi, 1.0), 1.0)


def pace_ratio(n1, n2):
    """빠른 쪽 ÷ 느린 쪽(1 이상, 느린 쪽이 0 이면 무한). 속도 구간(1.25, 2, 4배)을 나눌 때 쓴다."""
    n1 = np.asarray(n1, np.float64)
    n2 = np.asarray(n2, np.float64)
    lo, hi = np.minimum(n1, n2), np.maximum(n1, n2)
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.where(lo > 0, hi / np.where(lo > 0, lo, 1.0), np.where(hi > 0, np.inf, 1.0))


# ---------------------------------------------------------------------- 보정층 (Java 가 같은 순서로 계산할 식)

EPS = 1e-6  # logit 전에 p 를 [EPS, 1 − EPS] 로 자른다(float32 출력의 1.0, 0.0 대비)
COEFFS = ("s0", "s_r", "s_r2", "s_r3", "s_e", "s_er", "s_er2", "b0", "b_e")
FORMULA = [
    "pc = min(max(p, eps), 1 - eps)",
    "z  = ln(pc / (1 - pc))",
    "r  = (min(n1, n2) + k) / (max(n1, n2) + k)",
    "e  = exp(-t_ms / tau_ms)",
    "d  = ln((n1 + k) / (n2 + k))",
    "S  = s0 + s_r*r + s_r2*r*r + s_r3*r*r*r + e*(s_e + s_er*r + s_er2*r*r)",
    "zc = exp(S)*z + (b0 + b_e*e)*d",
    "p_cal = 1 / (1 + exp(-zc))",
]


def calibrate(spec: dict, p, n1, n2, t_ms) -> np.ndarray:
    """보정한 P1 승률 (calibration.json 의 식). p 는 모델 출력(float32 를 그대로 double 로), n1, n2 는 두 플레이어의 블록 수,
    t_ms 는 판 시간이다. Java 는 FORMULA 를 이 순서 그대로 double 로 계산한다. 자리를 바꾸면(p → 1 − p, n1 ↔ n2) z 와 d 의
    부호만 바뀌고 r, e 는 그대로라서 결과가 1 − p_cal 이 된다(rbin_temperature 도 같다)."""
    p = np.asarray(p, np.float64)
    n1 = np.asarray(n1, np.float64)
    n2 = np.asarray(n2, np.float64)
    t = np.asarray(t_ms, np.float64)
    eps, k = spec["eps"], spec["k"]
    pc = np.minimum(np.maximum(p, eps), 1.0 - eps)
    z = np.log(pc / (1.0 - pc))
    if spec["kind"] == "rbin_temperature":
        r = speed_ratio(n1, n2, k)
        b = np.clip(np.searchsorted(np.asarray(spec["edges"]), r, side="right") - 1, 0, len(spec["log_scale"]) - 1)
        return 1.0 / (1.0 + np.exp(-(np.exp(np.asarray(spec["log_scale"])[b]) * z)))
    if not k > 0:
        raise ValueError("일반 식은 k > 0 이어야 함")
    c = spec["coefficients"]
    r = (np.minimum(n1, n2) + k) / (np.maximum(n1, n2) + k)
    e = np.exp(-t / spec["tau_ms"])
    d = np.log((n1 + k) / (n2 + k))
    s = c["s0"] + c["s_r"] * r + c["s_r2"] * r * r + c["s_r3"] * r * r * r + e * (c["s_e"] + c["s_er"] * r + c["s_er2"] * r * r)
    zc = np.exp(s) * z + (c["b0"] + c["b_e"] * e) * d
    return 1.0 / (1.0 + np.exp(-zc))


# ---------------------------------------------------------------------- 후보 (검증 판으로만 맞춘다)

RBIN_EDGES = (0.0, 0.25, 0.5, 0.65, 0.8, 0.9)  # r 구간의 아래 끝. 0.8 = 1.25배, 0.5 = 2배, 0.25 = 4배
K_GRID = (1.0, 2.0, 4.0, 8.0, 16.0)
TAU_GRID = (10_000.0, 20_000.0, 40_000.0, 80_000.0)
SIMPLER_WITHIN = 0.001  # 검증 log loss 가 가장 좋은 후보와 이만큼 안이면 계수가 적은 후보를 고른다

# (이름, 종류, 맞추는 계수, k 격자, tau 격자, 설명). 일반 식(FORMULA)의 다른 계수는 0 이다.
CANDIDATES = [
    ("temp_rbin", "rbin_temperature", None, (0.0, 1.0, 2.0, 4.0, 8.0), (None,),
     "속도 비 r 구간(6개)마다 온도: logit p' = logit p / T_b"),
    ("temp_rt", "general", ("s0", "s_r", "s_r2", "s_e", "s_er"), K_GRID, TAU_GRID,
     "온도 T(r, t): logit p' = exp(s0 + s_r r + s_r2 r² + e (s_e + s_er r)) · logit p"),
    ("speed2", "general", ("s0", "b0"), K_GRID, (40_000.0,),
     "로지스틱 2계수: logit p' = exp(s0) · logit p + b0 · ln((n1+k)/(n2+k))"),
    ("speed3", "general", ("s0", "s_r", "b0"), K_GRID, (40_000.0,),
     "로지스틱 3계수: logit p' = exp(s0 + s_r r) · logit p + b0 · d"),
    ("speed5", "general", ("s0", "s_r", "s_e", "b0", "b_e"), K_GRID, TAU_GRID,
     "로지스틱 5계수: logit p' = exp(s0 + s_r r + s_e e) · logit p + (b0 + b_e e) · d"),
    ("speed9", "general", COEFFS, K_GRID, TAU_GRID,
     "로지스틱 9계수: 일반 식 전체 (r 의 3차, e·r 의 2차, b(t))"),
]


def fit_candidate(cand: tuple, val: dict, log) -> dict:
    """후보 하나를 검증 판으로 맞춘다. k, tau 는 격자에서 검증 log loss 가 가장 낮은 것. 계수는 L-BFGS(float64, CPU)로
    0 에서 시작한다(0 이면 보정하지 않은 모델과 같다). 돌려주는 spec 은 calibrate() 와 calibration.json 의 모양이다."""
    import torch

    name, kind, free, k_grid, tau_grid, desc = cand
    torch.manual_seed(0)
    dt = torch.float64
    p = np.minimum(np.maximum(val["p"]["wp02"].astype(np.float64), EPS), 1.0 - EPS)
    z = torch.tensor(np.log(p / (1.0 - p)), dtype=dt)
    y = torch.tensor(val["y"].astype(np.float64), dtype=dt)
    n1 = torch.tensor(val["n1"].astype(np.float64), dtype=dt)
    n2 = torch.tensor(val["n2"].astype(np.float64), dtype=dt)
    t = torch.tensor(val["t"].astype(np.float64), dtype=dt)
    best = None
    for k in k_grid:
        r = torch.tensor(speed_ratio(val["n1"], val["n2"], k), dtype=dt)
        d = torch.log((n1 + k) / (n2 + k)) if k > 0 else None
        for tau in tau_grid:
            e = torch.exp(-t / tau) if tau else None
            if kind == "rbin_temperature":
                b = torch.tensor(np.clip(np.searchsorted(np.asarray(RBIN_EDGES), r.numpy(), side="right") - 1, 0,
                                         len(RBIN_EDGES) - 1))
                th = torch.zeros(len(RBIN_EDGES), dtype=dt, requires_grad=True)

                def forward(th=th, b=b):
                    return torch.exp(th[b]) * z
            else:
                th = torch.zeros(len(free), dtype=dt, requires_grad=True)
                terms = {"s0": None, "s_r": r, "s_r2": r * r, "s_r3": r * r * r, "s_e": e, "s_er": e * r, "s_er2": e * r * r}

                def forward(th=th, terms=terms, d=d, e=e):
                    s = torch.zeros_like(z)
                    speed = torch.zeros_like(z)
                    for i, nm in enumerate(free):
                        if nm == "b0":
                            speed = speed + th[i]
                        elif nm == "b_e":
                            speed = speed + th[i] * e
                        else:
                            s = s + (th[i] if terms[nm] is None else th[i] * terms[nm])
                    return torch.exp(s) * z + speed * d
            opt = torch.optim.LBFGS([th], max_iter=500, line_search_fn="strong_wolfe", tolerance_grad=1e-11,
                                    tolerance_change=1e-15, history_size=20)

            def closure(forward=forward, opt=opt):
                opt.zero_grad()
                loss = torch.nn.functional.binary_cross_entropy_with_logits(forward(), y)
                loss.backward()
                return loss

            for _ in range(3):
                opt.step(closure)
            loss = float(closure().detach())
            grad = float(th.grad.abs().max())
            row = {"k": k, "tau_ms": tau, "val_log_loss": loss, "grad_max": grad, "theta": th.detach().numpy().copy()}
            if best is None or row["val_log_loss"] < best["val_log_loss"]:
                best = row
    spec = {"format": FORMAT, "candidate": name, "kind": kind, "description": desc, "eps": EPS, "k": best["k"]}
    if kind == "rbin_temperature":
        spec.update(edges=list(RBIN_EDGES), log_scale=[float(v) for v in best["theta"]])
    else:
        spec["tau_ms"] = best["tau_ms"]
        spec["coefficients"] = {nm: 0.0 for nm in COEFFS}
        for nm, v in zip(free, best["theta"]):
            spec["coefficients"][nm] = float(v)
    n_coef = len(RBIN_EDGES) if kind == "rbin_temperature" else len(free)
    pc = calibrate(spec, val["p"]["wp02"], val["n1"], val["n2"], val["t"])
    out = {"name": name, "description": desc, "coefficients": n_coef, "spec": spec,
           "val": {"log_loss": wp.log_loss(pc, val["y"]), "brier": wp.brier(pc, val["y"]), "ece": wp.ece(pc, val["y"])},
           "fit_log_loss": best["val_log_loss"], "grad_max": best["grad_max"]}
    if abs(out["val"]["log_loss"] - best["val_log_loss"]) > 1e-6:  # torch 와 numpy(Java 기준 식)가 같은 값인지
        raise SystemExit(f"{name}: calibrate() 와 학습 식이 다름 {out['val']['log_loss']} vs {best['val_log_loss']}")
    log(f"[후보] {name:9s} 계수 {n_coef} k={best['k']:g} tau={best['tau_ms'] or 0:.0f}ms | 검증 log loss "
        f"{out['val']['log_loss']:.5f} ECE {out['val']['ece']:.4f} (기울기 최대 {best['grad_max']:.1e})")
    return out


def choose(fits: list[dict]) -> dict:
    """검증 log loss 가 가장 낮은 후보와 SIMPLER_WITHIN 안인 후보 가운데 계수가 가장 적은 것(같으면 log loss 가 낮은 것)."""
    best = min(f["val"]["log_loss"] for f in fits)
    near = [f for f in fits if f["val"]["log_loss"] <= best + SIMPLER_WITHIN]
    return min(near, key=lambda f: (f["coefficients"], f["val"]["log_loss"]))


# ---------------------------------------------------------------------- 자료 읽기

SEED_RE = re.compile(rb'"seed"\s*:\s*(\d+)')


def game_arrays(m: dict, stride: int, path: str) -> tuple[dict, dict]:
    """timed-1 기록 한 판 → (판 정보, 표본 배열). 표본은 tetris_ai.winprob.read_records 와 같은 순서·값이다(턴 번호가 stride 의
    배수인 기록). 블록 수와 시간은 모든 기록으로 센 뒤 표본 위치의 값을 쓴다."""
    steps = m["steps"]
    n = len(steps)
    topout = m.get("topout")
    counts = piece_counts(steps, topout) if n else np.zeros((0, 2), np.int32)
    legacy = legacy_queue_counts(steps) if n else np.zeros((0, 2), np.int32)
    info = {
        "seed": m["seed"], "turns": m["turns"], "result": m["result"], "file": Path(path).name,
        "path": "/".join(Path(path).parts[-2:]), "time_ms": m["time_ms"], "ended": m.get("ended"), "topout": topout,
        "players": [{k: p.get(k) for k in ("ai", "temperature", "think_ms", "inputs_per_sec")} for p in m["players"]],
        "pieces_final": counts[-1].tolist() if n else [0, 0],
        "legacy_final": legacy[-1].tolist() if n else [0, 0],
        "bag_mismatch": bag_check(steps, counts, topout) if n else 0,
    }
    idx = np.arange(0, n, max(1, stride), dtype=np.int32)
    sel = [steps[i] for i in idx]
    arrays = {"turn": idx, "n1": counts[idx, 0], "n2": counts[idx, 1],
              "t": np.array([s["t"] for s in sel], np.int64),
              "y": np.full(len(idx), wp.RESULT_VALUE[m["result"]], np.float32)}
    for side, (bk, fk) in zip(SIDES, (("b1", "f1"), ("b2", "f2"))):
        rows = np.array([s[side]["state"]["rows"] for s in sel], dtype=np.int64).reshape(len(sel), -1)
        arrays[bk] = ((rows[:, :, None] >> np.arange(WIDTH)) & 1).astype(np.uint8)
        arrays[fk] = (np.stack([wp._features(st["current"], st["queue"], st["hold"], st["combo"], st["b2b"], st["pending"])
                                for st in (s[side]["state"] for s in sel)]) if sel else np.zeros((0, wp.FEATURES), np.float32))
    return info, arrays


def _read_file(job: tuple[str, int, tuple[str, ...]]) -> list[tuple[dict, dict]]:
    """splits 가 비어 있으면 나누기와 상관없이 모든 판을 읽는다(--extra-test)."""
    path, stride, splits = job
    out = []
    with gzip.open(path, "rb") as fh:
        for line in fh:
            if splits:
                hit = SEED_RE.search(line, 0, 600) or SEED_RE.search(line)
                if hit is None or wp.split_of(int(hit.group(1))) not in splits:
                    continue
            elif not line.strip():
                continue
            out.append(game_arrays(json.loads(line), stride, path))
    return out


def timed_files(data_dir: Path, chunks: str) -> list[Path]:
    """끝까지 만든 묶음(summary.json 이 있는 chunk-*)의 기록 파일 중 묶음 번호가 chunks("0-83" 처럼 범위)에 드는 것."""
    lo, hi = (int(x) for x in chunks.split("-"))
    files = []
    for f in sorted(data_dir.glob("chunk-*/w*.jsonl.gz")):
        k = int(f.parent.name.split("-")[1])
        if lo <= k <= hi and (f.parent / "summary.json").exists():
            files.append(f)
    return files


ARRAY_KEYS = ("b1", "f1", "b2", "f2", "y", "turn", "n1", "n2", "t")


def _read_all_files(files: list[Path], stride: int, splits: tuple[str, ...], workers: int) -> list:
    jobs = [(str(f), stride, splits) for f in files]
    if workers > 1 and len(jobs) > 1:
        with ProcessPoolExecutor(max_workers=workers) as ex:
            return list(ex.map(_read_file, jobs, chunksize=4))
    return [_read_file(j) for j in jobs]


def _group(results: list, accept) -> dict:
    """파일 순서와 파일 안의 순서대로, accept(판 정보) 인 판을 하나로 모은다."""
    games, parts, match = [], {k: [] for k in ARRAY_KEYS}, []
    for per_file in results:
        for info, arrays in per_file:
            if not accept(info):
                continue
            match.append(np.full(len(arrays["y"]), len(games), np.int32))
            games.append(info)
            for k in ARRAY_KEYS:
                parts[k].append(arrays[k])
    if not games:
        raise SystemExit("읽은 판이 없음")
    d = {k: np.concatenate(v) for k, v in parts.items()}
    d["match"] = np.concatenate(match)
    d["games"] = games
    return d


def read_splits(files: list[Path], stride: int, splits: tuple[str, ...], workers: int) -> dict[str, dict]:
    """splits 에 드는 판만 읽어 나누기마다 {배열..., "match": 판 위치, "games": 판 정보 목록} 을 만든다. 파일 순서와 파일 안의
    순서를 지키므로 표본 순서가 train_winprob.py 의 캐시를 나눈 것(Data.split)과 같다."""
    results = _read_all_files(files, stride, splits, workers)
    return {name: _group(results, lambda info, name=name: wp.split_of(info["seed"]) == name) for name in splits}


def read_all(files: list[Path], stride: int, workers: int) -> dict:
    """나누기와 상관없이 모든 판을 read_splits 의 나누기 하나와 같은 모양으로 읽는다(--extra-test)."""
    return _group(_read_all_files(files, stride, (), workers), lambda info: True)


def as_data(d: dict) -> "wp.Data":
    matches = [{"seed": g["seed"], "turns": g["turns"], "result": g["result"], "file": g["file"], "players": g["players"]}
               for g in d["games"]]
    return wp.Data(d["b1"], d["f1"], d["b2"], d["f2"], d["y"], d["turn"], d["match"], matches)


def predict_runs(runs: list[str], data: dict[str, dict]) -> dict[str, dict[str, np.ndarray]]:
    """runs/<run>/winprob_<run>.pt 체크포인트로 나누기마다 p1_win (float32 sigmoid 출력).

    GPU 의 cuDNN 은 기본으로 합성곱을 TF32 로 계산해 p 가 내보낸 ONNX(= Java)와 6e-5 까지 다르다. 보정층은 Java 가 보는 값으로
    맞춰야 하므로 TF32 를 끈다(그러면 ONNX 와 3e-7 안). train_winprob.py 의 지표는 TF32 로 낸 것이라 log loss 가 1e-6 쯤 다르다.
    """
    import torch
    import train_winprob as tw

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    old = torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32
    torch.backends.cudnn.allow_tf32 = torch.backends.cuda.matmul.allow_tf32 = False
    out = {}
    try:
        for run in runs:
            saved = torch.load(ROOT / "runs" / run / f"winprob_{run}.pt", map_location=device)
            net = wp.WinProbNet(**saved["config"]).to(device)
            net.load_state_dict(saved["state_dict"])
            out[run] = {name: tw.predict(net, as_data(d), device) for name, d in data.items()}
    finally:
        torch.backends.cudnn.allow_tf32, torch.backends.cuda.matmul.allow_tf32 = old
    return out


SAMPLE_KEYS = ("y", "turn", "n1", "n2", "t", "match")


def save_cache(path: Path, data: dict[str, dict], preds: dict[str, dict[str, np.ndarray]], meta: dict) -> None:
    arrays = {}
    for name, d in data.items():
        for k in SAMPLE_KEYS:
            arrays[f"{name}/{k}"] = d[k]
        for run, per in preds.items():
            arrays[f"{name}/p_{run}"] = per[name]
    games = {name: d["games"] for name, d in data.items()}
    np.savez(path, meta=np.array(json.dumps(meta)), games=np.array(json.dumps(games, ensure_ascii=False)), **arrays)


def load_cache(path: Path) -> tuple[dict[str, dict], dict]:
    z = np.load(path)
    meta = json.loads(str(z["meta"]))
    games = json.loads(str(z["games"]))
    out = {}
    for name in games:
        d = {k: z[f"{name}/{k}"] for k in SAMPLE_KEYS}
        d["games"] = games[name]
        d["p"] = {run: z[f"{name}/p_{run}"] for run in meta["runs"]}
        out[name] = d
    return out, meta


# ---------------------------------------------------------------------- 평가

SPEED_BINS = (("<1.25", 1.0, 1.25), ("1.25-2", 1.25, 2.0), ("2-4", 2.0, 4.0), (">=4", 4.0, float("inf")))
TIME_BINS = ((0, 5), (5, 10), (10, 20), (20, 40), (40, 80), (80, None))  # 초


def final_ratio(d: dict, key: str) -> np.ndarray:
    """판마다 판 끝의 초당 블록 수 비(빠른 쪽 ÷ 느린 쪽)를 표본에 펼친 값. key 가 legacy_final 이면 winprob_speed_subsets.py
    와 같은 값(TRAINING.md 10.11 의 표), pieces_final 이면 Java 의 pieces() 로 잰 값이다."""
    counts = np.array([g[key] for g in d["games"]], np.float64)
    sec = np.array([max(1e-3, g["time_ms"] / 1000) for g in d["games"]])
    speed = counts / sec[:, None]
    return (speed.max(1) / np.maximum(speed.min(1), 1e-9))[d["match"]]


def summary(p: np.ndarray, y: np.ndarray, match: np.ndarray) -> dict:
    decided = y != 0.5
    return {"games": int(len(np.unique(match))), "n": int(len(y)), "log_loss": wp.log_loss(p, y), "brier": wp.brier(p, y),
            "ece": wp.ece(p, y), "accuracy": float(((p[decided] > 0.5) == (y[decided] > 0.5)).mean()) if decided.any() else None}


def sample_losses(p: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    pc = np.clip(np.asarray(p, np.float64), 1e-7, 1 - 1e-7)
    y = np.asarray(y, np.float64)
    return -(y * np.log(pc) + (1 - y) * np.log(1 - pc)), (np.asarray(p, np.float64) - y) ** 2


def paired_bootstrap(a: np.ndarray, b: np.ndarray, y: np.ndarray, match: np.ndarray, reps: int, seed: int) -> dict:
    """판 단위로 다시 뽑은 (a − b) 의 평균 차이 95% 범위: log loss, Brier. 판의 표본 수로 가중한 비(합 ÷ 합)다."""
    lla, bra = sample_losses(a, y)
    llb, brb = sample_losses(b, y)
    ids, inv = np.unique(match, return_inverse=True)
    m = len(ids)
    cnt = np.bincount(inv, minlength=m).astype(np.float64)
    rng = np.random.default_rng(seed)
    k = np.stack([np.bincount(rng.integers(0, m, m), minlength=m) for _ in range(reps)]).astype(np.float64)
    out = {}
    for name, diff in (("log_loss", lla - llb), ("brier", bra - brb)):
        per = np.bincount(inv, diff, m)
        draws = (k @ per) / (k @ cnt)
        out[name] = {"mean": float(diff.mean()), "lo": float(np.percentile(draws, 2.5)), "hi": float(np.percentile(draws, 97.5)),
                     "share_below_zero": float((draws < 0).mean())}
    return out


def compare(models: dict[str, np.ndarray], y: np.ndarray, match: np.ndarray, groups: list[tuple[str, np.ndarray]],
            pairs: list[tuple[str, str]], reps: int, seed: int) -> list[dict]:
    rows = []
    for gi, (name, mask) in enumerate(groups):
        if not mask.any():
            continue
        row = {"group": name, **{m: summary(p[mask], y[mask], match[mask]) for m, p in models.items()}}
        row["paired"] = {f"{a}-{b}": paired_bootstrap(models[a][mask], models[b][mask], y[mask], match[mask], reps, seed + gi)
                         for a, b in pairs}
        rows.append(row)
    return rows


def groupings(d: dict) -> dict[str, list[tuple[str, np.ndarray]]]:
    """평가 묶음: 판 끝 속도 비(10.11 방식, Java pieces 방식), 지금까지의 속도 비, 판 시간, 턴, 판 안의 위치."""
    out = {}
    for key, title in (("legacy_final", "final_pps_ratio_legacy"), ("pieces_final", "final_pps_ratio_exact")):
        fr = final_ratio(d, key)
        out[title] = [(nm, (fr >= lo) & (fr < hi)) for nm, lo, hi in SPEED_BINS]
    run = pace_ratio(d["n1"], d["n2"])
    out["running_ratio"] = [(nm, (run >= lo) & (run < hi)) for nm, lo, hi in SPEED_BINS]
    sec = d["t"] / 1000.0
    out["time_s"] = [(f"{lo}-{hi}s" if hi else f"{lo}s+", (sec >= lo) & ((sec < hi) if hi else True)) for lo, hi in TIME_BINS]
    out["turn"] = [(f"{lo}-{hi - 1}" if hi else f"{lo}+", (d["turn"] >= lo) & ((d["turn"] < hi) if hi else True))
                   for lo, hi in wp.TURN_BUCKETS]
    turns = np.array([g["turns"] for g in d["games"]])[d["match"]]
    rel = d["turn"] / np.maximum(turns, 1)
    out["relative"] = [(f"{25 * q}-{25 * (q + 1)}%", (rel >= q / 4) & (rel < (q + 1) / 4)) for q in range(4)]
    fr = final_ratio(d, "legacy_final")
    near = fr < 1.25
    out["near_equal_by_time"] = [(f"{lo}-{hi}s" if hi else f"{lo}s+", near & (sec >= lo) & ((sec < hi) if hi else True))
                                 for lo, hi in TIME_BINS]
    # 판 맨 앞: 첫 고정(턴 0)에서는 보정층이 wp02 보다 조금 나쁘다(k 때문에 r ≈ 0.9 라 확신을 줄이는데, 먼저 고정한 쪽이
    # 이기는 비율이 높다). 판마다 표본을 3개에 하나 쓰면 턴 0, 3, 6, … 만 있다.
    t = d["turn"]
    out["early"] = [("0", t == 0), ("1-5", (t >= 1) & (t <= 5)), ("6-11", (t >= 6) & (t <= 11)), ("12+", t >= 12)]
    return out


# ---------------------------------------------------------------------- Java 와 맞춰 볼 사례


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def parity_cases(spec: dict, test: dict, timed_dir: Path, onnx_path: Path, n_games: int, log) -> dict:
    """Java 용 사례 두 가지. calibration: (p, n1, n2, t_ms) → p_cal (경계 사례 + 시험 표본). states: 시험 판의 실제 상태 쌍
    (판마다 첫 고정, 가운데, 끝 무렵 세 순간, 아무도 지지 않은 때) → onnxruntime 으로 낸 p 와 보정한 p_cal."""
    import onnxruntime as ort

    rng = np.random.default_rng(1)
    f32 = lambda v: float(np.float32(v))  # noqa: E731  (모델 출력은 float32)
    edge = [
        (0.5, 0, 0, 0), (0.5, 3, 3, 4000), (f32(0.73), 0, 1, 650), (f32(0.27), 1, 0, 650), (1.0, 40, 20, 30000),
        (0.0, 20, 40, 30000), (f32(1 - 6e-8), 12, 12, 9000), (f32(3e-7), 12, 12, 9000), (f32(0.9), 300, 150, 600000),
        (f32(0.1), 150, 300, 600000), (f32(0.6), 55, 54, 61000), (f32(0.6), 54, 55, 61000), (f32(0.999), 2, 30, 15000),
        (f32(0.001), 30, 2, 15000), (f32(0.5), 10, 0, 5000), (f32(0.5), 0, 10, 5000),
    ]
    idx = np.sort(rng.choice(len(test["y"]), 200, replace=False))
    sample = [(float(test["p"]["wp02"][i]), int(test["n1"][i]), int(test["n2"][i]), int(test["t"][i])) for i in idx]
    cal = []
    for j, (p, a, b, t) in enumerate(edge + sample):
        cal.append({"id": f"{'edge' if j < len(edge) else 'test'}-{j:03d}", "p": p, "n1": a, "n2": b, "t_ms": t,
                    "p_cal": float(calibrate(spec, p, a, b, t))})
    # 실제 상태 쌍
    games = sorted(test["games"], key=lambda g: g["seed"])
    picked = games[:: max(1, len(games) // n_games)][:n_games]
    want = {g["seed"]: g for g in picked}
    by_file: dict[str, list[int]] = {}
    for g in picked:
        by_file.setdefault(g["path"], []).append(g["seed"])
    states = []
    session = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    for rel, seeds in sorted(by_file.items()):
        with gzip.open(timed_dir / rel, "rb") as fh:
            for line in fh:
                hit = SEED_RE.search(line, 0, 600)
                if hit is None or int(hit.group(1)) not in seeds:
                    continue
                m = json.loads(line)
                steps = m["steps"]
                counts = piece_counts(steps, m.get("topout"))
                last = len(steps) - 2  # 마지막 기록은 패배 순간일 수 있다(Java 는 끝난 판에 모델을 부르지 않는다)
                for k in sorted({0, last // 2, max(0, last - 3)}):
                    s1, s2 = steps[k]["p1"]["state"], steps[k]["p2"]["state"]
                    b1, f1 = wp.encode_record(s1)
                    b2, f2 = wp.encode_record(s2)
                    (p,) = session.run([wp.OUTPUT_NAME], {
                        "p1_board": b1[None].astype(np.float32), "p1_features": f1[None],
                        "p2_board": b2[None].astype(np.float32), "p2_features": f2[None]})
                    pv = float(p[0, 0])
                    n1, n2, t = int(counts[k, 0]), int(counts[k, 1]), int(steps[k]["t"])
                    states.append({"id": f"seed{m['seed']}-step{k}", "seed": m["seed"], "step": k, "t_ms": t, "n1": n1,
                                   "n2": n2, "p1": s1, "p2": s2, "p": pv, "p_cal": float(calibrate(spec, pv, n1, n2, t))})
    log(f"[사례] 보정 식 {len(cal)}개, 실제 상태 쌍 {len(states)}개 ({len(want)}판)")
    return {
        "format": "winprob-calibration-parity-1", "calibration_file": "calibration.json", "model": spec.get("model"),
        "onnx": onnx_path.relative_to(ROOT).as_posix(), "onnx_sha256": sha256(onnx_path),
        "tolerance": {"p_cal_from_p": 1e-12, "p_onnx": 1e-6, "p_cal_end_to_end": 1e-4},
        "notes": [
            "calibration: Java 의 보정 함수에 p, n1, n2, t_ms 를 넣어 p_cal 과 1e-12 안에서 같아야 한다(double 계산).",
            "states: p1/p2 는 기록 match-1·timed-1 의 state 모양(rows 는 행마다 정수, 비트 c 가 c 열). Java WinRate 의 p 가 "
            "p 와 1e-6 안, Java p 로 낸 보정값이 calibrate(Java p) 와 1e-12 안, p_cal 과 1e-4 안이어야 한다.",
            "n1, n2 는 그 순간 GameModel.pieces(), t_ms 는 TimedMatch.time() 이다(기록에서 piece_counts 로 셈).",
        ],
        "calibration": cal, "states": states,
    }


# ---------------------------------------------------------------------- 공통 픽스처 (Java WinCalibration 과 같은 값인지)

FIXTURE_FORMAT = "winprob-calibration-fixture-1"
FIXTURE_DIR = ROOT / "fixtures" / "winprob_calibration"
FIXTURE_FILE = "cases.jsonl"
# p_cal 은 같은 double 식이라 빡빡하게 둔다. p_onnx 는 다른 ONNX 픽스처와 같은 1e-5 다: onnxruntime 의 합성곱 커널이 CPU 마다
# 다르게 묶어 더하므로(AVX-512 와 AVX2, ARM) float32 출력이 약 2e-7 까지 달라질 수 있다. 보정이 p 의 차이를 최대 약 12배
# 키우므로 p_cal_end_to_end 는 parity_cases.json 과 같은 1e-4 다. 이 PC(AVX-512)에서는 두 차이가 모두 0 에 가깝다.
FIXTURE_TOLERANCE = {"p_cal": 1e-12, "p_onnx": 1e-5, "p_cal_end_to_end": 1e-4}
# 보정 파일에서 계산에 쓰는 항목 (Java WinCalibration.parse 가 읽는 것). 나머지(맞춘 자료, 성적, 설명)는 픽스처에 넣지 않는다.
SPEC_KEYS = ("format", "candidate", "kind", "eps", "k", "tau_ms", "coefficients", "edges", "log_scale", "model", "model_sha256")
# 고른 보정층(speed5)은 계수 4개가 0 이다. 식의 모든 항과 구간 온도(rbin_temperature)도 Java 에서 확인하려고 계수를 지어낸
# 보정층을 함께 넣는다(게임에는 쓰지 않는다). rbin_k0 은 k = 0 이라 두 블록 수가 모두 0 이면 r = 1 로 정한 경우를 본다.
SYNTHETIC_SPECS = {
    "all_terms": {"format": FORMAT, "candidate": "all_terms", "kind": "general", "eps": EPS, "k": 4.0, "tau_ms": 20_000.0,
                  "coefficients": {"s0": 0.31, "s_r": -0.83, "s_r2": 0.27, "s_r3": -0.12, "s_e": -0.44, "s_er": 0.21,
                                   "s_er2": -0.09, "b0": 2.1, "b_e": 4.4}},
    "rbin": {"format": FORMAT, "candidate": "rbin", "kind": "rbin_temperature", "eps": EPS, "k": 2.0,
             "edges": list(RBIN_EDGES), "log_scale": [1.2, 0.8, 0.2, -0.1, -0.7, -1.0]},
    "rbin_k0": {"format": FORMAT, "candidate": "rbin_k0", "kind": "rbin_temperature", "eps": EPS, "k": 0.0,
                "edges": list(RBIN_EDGES), "log_scale": [1.2, 0.8, 0.2, -0.1, -0.7, -1.0]},
}
# 일반 식: 첫 고정, 블록 수 0 에서 p ≠ 0.5, 아주 긴 판
GENERAL_EXTRA_INPUTS = [(0.5, 1, 0, 0), (0.5, 0, 1, 0), (0.97, 0, 0, 100), (0.2, 2000, 1500, 3_600_000), (0.8, 1, 1, 250)]
# 구간 온도: 속도 비가 구간 경계에 정확히 걸리는 경우(k = 2 에서 6/12 = 0.5, 2/8 = 0.25, 8/10 = 0.8, 13/20 = 0.65,
# 9/10 = 0.9)와 블록 수 0 (k = 0 이면 r 이 0 ÷ 0 이라 1 로 정한다), 한쪽만 0
RBIN_EXTRA_INPUTS = [(0.7, 4, 10, 1000), (0.7, 10, 4, 1000), (0.7, 0, 6, 1000), (0.3, 6, 8, 2000), (0.3, 11, 18, 2000),
                     (0.6, 7, 8, 3000), (0.6, 8, 7, 3000), (0.6, 0, 0, 0), (0.6, 0, 5, 500), (0.6, 5, 10, 900),
                     (0.6, 10, 5, 900)]


def spec_core(spec: dict) -> dict:
    return {k: spec[k] for k in SPEC_KEYS if k in spec}


def fixture_records(spec: dict, parity: dict, source: str) -> list[dict]:
    """공통 픽스처 fixtures/winprob_calibration/cases.jsonl 의 줄(JSON 객체)들. 첫 줄은 머리, 다음은 보정층(kind spec),
    그다음은 사례다.

      formula  보정층 spec 에 (p, n1, n2, t_ms) 를 넣으면 p_cal 이다(double 계산, tolerance.p_cal 안).
      state    시험 판의 실제 상태 쌍(기록 state 모양)과 그 순간의 블록 수, 판 시간. p 는 onnxruntime 으로 낸 models/winprob.onnx
               (onnx_sha256)의 출력(tolerance.p_onnx 안), p_cal 은 그 p 를 보정한 값이다. Java 는 WinRate 로 p 를 내고,
               묶음의 보정층을 붙인 WinRate.p1Win(상태, 상태, t_ms) 가 p_cal 과 tolerance.p_cal_end_to_end 안이어야 한다.

    bundle 보정층(spec 의 계산 항목)과 그 사례는 parity_cases.json 과 같은 값이어야 한다(다시 계산해 1e-12 안인지 확인)."""
    specs = {"bundle": spec_core(spec), **SYNTHETIC_SPECS}
    header = {
        "type": "winprob_calibration", "format": FIXTURE_FORMAT, "rules_version": RULES_VERSION, "source": ["python"],
        "run": source, "generator": f"python python/tools/fit_winprob_calibration.py --fixture-from {source}",
        "onnx": parity["onnx"], "onnx_sha256": parity["onnx_sha256"], "tolerance": FIXTURE_TOLERANCE,
        "notes": [
            "TRAINING.md 10.12. 첫 줄이 머리, kind spec 줄이 보정층, 나머지가 사례다(fixture_records 의 설명).",
            "bundle 은 게임 묶음 models/winprob_calibration.json 과 같은 보정층이고 나머지 보정층은 식의 모든 경로를 보려고 "
            "계수를 지어낸 것이다.",
            "states 의 n1, n2 는 그 순간의 GameModel.pieces(), t_ms 는 TimedMatch.time() 이다(기록에서 piece_counts 로 셈).",
        ],
    }
    recs: list[dict] = [header] + [{"kind": "spec", "name": n, "spec": s} for n, s in specs.items()]

    def formula(name: str, sp: dict, case_id: str, p: float, a: int, b: int, t: int) -> dict:
        return {"kind": "formula", "spec": name, "id": case_id, "p": p, "n1": a, "n2": b, "t_ms": t,
                "p_cal": float(calibrate(sp, p, a, b, t))}

    for c in parity["calibration"]:
        rec = formula("bundle", spec, c["id"], c["p"], c["n1"], c["n2"], c["t_ms"])
        if abs(rec["p_cal"] - c["p_cal"]) > 1e-12:
            raise SystemExit(f"{c['id']}: calibration.json 과 parity_cases.json 의 p_cal 이 다름")
        recs.append(rec)
    base = [(c["p"], c["n1"], c["n2"], c["t_ms"]) for c in parity["calibration"]]
    for name, sp in SYNTHETIC_SPECS.items():
        extra = RBIN_EXTRA_INPUTS if sp["kind"] == "rbin_temperature" else GENERAL_EXTRA_INPUTS
        for j, (p, a, b, t) in enumerate(base[:16] + base[16::5] + extra):
            recs.append(formula(name, sp, f"{name}-{j:03d}", p, a, b, t))
    for c in parity["states"]:
        p_cal = float(calibrate(spec, c["p"], c["n1"], c["n2"], c["t_ms"]))
        if abs(p_cal - c["p_cal"]) > 1e-12:
            raise SystemExit(f"{c['id']}: calibration.json 과 parity_cases.json 의 p_cal 이 다름")
        recs.append({"kind": "state", "spec": "bundle", "id": c["id"], "n1": c["n1"], "n2": c["n2"], "t_ms": c["t_ms"],
                     "p1": c["p1"], "p2": c["p2"], "p": c["p"], "p_cal": p_cal})
    return recs


def write_fixture(run: str, fixture_dir: Path = FIXTURE_DIR) -> Path:
    """runs/<run>/ 의 calibration.json, parity_cases.json 으로 공통 픽스처를 쓴다. 한 줄에 객체 하나, LF, UTF-8."""
    run_dir = ROOT / "runs" / run
    spec = json.loads((run_dir / "calibration.json").read_text(encoding="utf-8"))
    parity = json.loads((run_dir / "parity_cases.json").read_text(encoding="utf-8"))
    if spec.get("format") != FORMAT or parity.get("onnx_sha256") != spec.get("model_sha256"):
        raise SystemExit(f"runs/{run}: 형식이 {FORMAT} 이 아니거나 사례의 모델과 보정층의 모델이 다름")
    recs = fixture_records(spec, parity, run)
    fixture_dir.mkdir(parents=True, exist_ok=True)
    out = fixture_dir / FIXTURE_FILE
    out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in recs), encoding="utf-8", newline="\n")
    return out


def read_fixture(path: Path = FIXTURE_DIR / FIXTURE_FILE) -> tuple[dict, dict[str, dict], list[dict]]:
    """(머리, 이름 → 보정층, 사례 목록)."""
    lines = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    header, rest = lines[0], lines[1:]
    if header.get("format") != FIXTURE_FORMAT:
        raise ValueError(f"{path}: 형식이 {FIXTURE_FORMAT} 이 아님")
    specs = {r["name"]: r["spec"] for r in rest if r["kind"] == "spec"}
    return header, specs, [r for r in rest if r["kind"] != "spec"]


# ---------------------------------------------------------------------- 출력


class Log:
    def __init__(self, path: Path) -> None:
        self.f = path.open("w", encoding="utf-8")

    def __call__(self, *parts) -> None:
        line = " ".join(str(p) for p in parts)
        try:
            print(line, flush=True)
        except UnicodeEncodeError:  # 한글을 못 쓰는 콘솔
            print(line.encode("ascii", "replace").decode(), flush=True)
        self.f.write(line + "\n")
        self.f.flush()


def cell(s: dict) -> str:
    return f"{s['log_loss']:.4f} / {s['ece']:.4f} / {s['accuracy']:.3f}"


def diff_cell(dd: dict) -> str:
    x = dd["log_loss"]
    return f"{x['mean']:+.4f} ({x['lo']:+.4f}~{x['hi']:+.4f})"


def markdown_compare(title: str, rows: list[dict], models: list[str], pairs: list[str]) -> list[str]:
    head = "| " + title + " | 판 | 표본 | " + " | ".join(f"`{m}` log loss / ECE / 적중률" for m in models) + " | " \
        + " | ".join(f"log loss 차이 {p} (판 단위 95%)" for p in pairs) + " |"
    lines = [head, "|---|---|---|" + "---|" * (len(models) + len(pairs))]
    for r in rows:
        first = r[models[0]]
        lines.append(f"| {r['group']} | {first['games']:,} | {first['n']:,} | " + " | ".join(cell(r[m]) for m in models)
                     + " | " + " | ".join(diff_cell(r["paired"][p]) for p in pairs) + " |")
    return lines + [""]


def prepare(args, out_dir: Path, log) -> tuple[dict[str, dict], dict]:
    cache = out_dir / "cache.npz"
    files = timed_files(ROOT / "runs" / args.timed_run / "data", args.chunks)
    sources = [f.resolve().relative_to(ROOT).as_posix() for f in files]
    if cache.exists() and not args.refresh:
        data, meta = load_cache(cache)
        if (meta.get("sources") == sources and meta.get("stride") == args.stride and meta.get("runs") == args.runs
                and "path" in data["test"]["games"][0]):
            log(f"[자료] 캐시 {cache.relative_to(ROOT).as_posix()}")
            return data, meta
    if not files:
        raise SystemExit(f"기록 파일이 없음: runs/{args.timed_run}/data (묶음 {args.chunks})")
    start = time.time()
    raw = read_splits(files, args.stride, ("val", "test"), args.workers)
    log(f"[자료] 기록 {len(files)}개 파일에서 검증 {len(raw['val']['games'])}판·표본 {len(raw['val']['y'])}개, "
        f"시험 {len(raw['test']['games'])}판·표본 {len(raw['test']['y'])}개 ({time.time() - start:.0f}초)")
    start = time.time()
    preds = predict_runs(args.runs, raw)
    log(f"[예측] {', '.join(args.runs)} ({time.time() - start:.0f}초)")
    meta = {"sources": sources, "stride": args.stride, "runs": args.runs, "timed_run": args.timed_run, "chunks": args.chunks}
    save_cache(cache, raw, preds, meta)
    return load_cache(cache)


def evaluate(d: dict, fits: list[dict], cal_key: str, pure_key: str, reps: int) -> dict:
    """진행 중 표본만 남은 나누기 하나에서 wp01, wp02 와 후보 보정층들을 잰다: 전체(판 단위 부트스트랩 95%), 짝 비교(판 단위),
    묶음별(groupings), 판 끝 속도가 거의 같은 판의 보정 표."""
    import train_winprob as tw

    models = {"wp01": d["p"]["wp01"], "wp02": d["p"]["wp02"]}
    for f in fits:
        models[f"cal:{f['name']}"] = calibrate(f["spec"], d["p"]["wp02"], d["n1"], d["n2"], d["t"]).astype(np.float64)
    y, match = d["y"], d["match"]
    overall = {m: {**summary(pr, y, match), "bootstrap95": tw.bootstrap(pr, y, match, reps, 0),
                   "reliability": wp.reliability(pr, y)} for m, pr in models.items()}
    pairs = [(cal_key, "wp02"), (cal_key, "wp01"), (pure_key, "wp02"), (pure_key, "wp01")]
    show = ["wp01", "wp02", pure_key, cal_key]
    by_group = {g: compare({m: models[m] for m in show}, y, match, rows, pairs, reps, 100 * i)
                for i, (g, rows) in enumerate(groupings(d).items())}
    paired = {f"{a}-{b}": paired_bootstrap(models[a], models[b], y, match, reps, 7) for a, b in pairs}
    near = final_ratio(d, "legacy_final") < 1.25
    near_rel = {m: wp.reliability(models[m][near], y[near]) for m in show}
    return {"overall": overall, "paired": paired, "groups": by_group, "near_reliability": near_rel, "show": show,
            "pairs": [f"{a}-{b}" for a, b in pairs]}


TITLES = {"final_pps_ratio_legacy": "판 끝 초당 블록 수 비 (10.11 방식)", "final_pps_ratio_exact": "판 끝 비 (pieces 방식)",
          "running_ratio": "지금까지의 블록 수 비", "time_s": "판 시간", "turn": "턴", "relative": "판 안의 위치",
          "near_equal_by_time": "거의 같은 속도(<1.25) 판의 판 시간", "early": "판 맨 앞의 턴"}


def markdown_eval(heading: str, ev: dict, groups: tuple[str, ...] | None = None) -> list[str]:
    show = ev["show"]
    lines = [f"### {heading}: 전체", "", "| 모델 | log loss (판 단위 95%) | Brier | ECE | 적중률 |", "|---|---|---|---|---|"]
    for m in show:
        o = ev["overall"][m]
        b = o["bootstrap95"]
        acc = "" if o["accuracy"] is None else f"{o['accuracy']:.3f}"
        lines.append(f"| {m} | {o['log_loss']:.4f} ({b['log_loss'][0]:.4f}~{b['log_loss'][1]:.4f}) | {o['brier']:.4f} | "
                     f"{o['ece']:.4f} | {acc} |")
    lines += [""] + [f"- {k}: log loss {v['log_loss']['mean']:+.4f} ({v['log_loss']['lo']:+.4f}~{v['log_loss']['hi']:+.4f}), "
                     f"Brier {v['brier']['mean']:+.4f}" for k, v in ev["paired"].items()] + [""]
    for g, rows in ev["groups"].items():
        if groups is None or g in groups:
            lines += [f"### {heading}: {TITLES[g]}", ""] + markdown_compare(TITLES[g], rows, show, ev["pairs"][:2])
    near_rel = ev["near_reliability"]
    lines += [f"### {heading}: 거의 같은 속도 판의 보정 표 (예측 구간: 표본 / 평균 예측 / 실제)", "",
              "| 구간 | " + " | ".join(show) + " |", "|---|" + "---|" * len(show)]
    for b in range(10):
        lines.append(f"| {b / 10:.1f}~{(b + 1) / 10:.1f} | " + " | ".join(
            (f"{near_rel[m][b]['n']:,} / {near_rel[m][b]['pred']:.3f} / {near_rel[m][b]['actual']:.3f}" if near_rel[m][b]["n"]
             else "-") for m in show) + " |")
    return lines + [""]


def extra_test(item: str, args, fits: list[dict], cal_key: str, pure_key: str, log) -> tuple[str, dict, dict]:
    """--extra-test 이름=글롭: 그 기록의 모든 판, 모든 막대 순간(고정마다, 끝난 순간 제외)을 잰다."""
    name, _, pattern = item.partition("=")
    files = sorted(Path(f) for f in glob.glob(str(ROOT / pattern)))
    if not name or not pattern or not files:
        raise SystemExit(f"--extra-test 는 이름=글롭 (저장소 기준) 이어야 하고 파일이 있어야 함: {item}")
    start = time.time()
    d = read_all(files, 1, args.workers)
    preds = predict_runs(args.runs, {name: d})
    d["p"] = {run: preds[run][name] for run in args.runs}
    live = live_mask(d)
    ev = evaluate(subset(d, live), fits, cal_key, pure_key, args.bootstrap)
    seeds = [g["seed"] for g in d["games"]]
    info = {"pattern": pattern, "files": len(files), "games": len(d["games"]), "seeds": [min(seeds), max(seeds)],
            "samples": int(live.sum()), "terminal_excluded": int((~live).sum()),
            "bag_mismatch": int(sum(g["bag_mismatch"] for g in d["games"])),
            "results": {r: sum(g["result"] == r for g in d["games"]) for r in ("p1", "p2", "draw")}}
    o = ev["overall"]
    log(f"[다른 기록] {name}: {info['games']}판, 막대 순간 {info['samples']}개 (끝난 순간 {info['terminal_excluded']}개 뺌, 가방 진행과 "
        f"안 맞는 블록 수 {info['bag_mismatch']}) | log loss wp01 {o['wp01']['log_loss']:.4f}, wp02 {o['wp02']['log_loss']:.4f}, "
        f"{cal_key} {o[cal_key]['log_loss']:.4f} ({time.time() - start:.0f}초)")
    return name, info, ev


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--out", default="wpcal02", help="결과 폴더 runs/<out>")
    p.add_argument("--timed-run", default="wpt01", help="실시간 기록 실행 runs/<run>/data")
    p.add_argument("--chunks", default="0-83", help="쓸 묶음 번호 범위 (wp02 가 학습한 묶음 0~83)")
    p.add_argument("--stride", type=int, default=3, help="판마다 표본을 이만큼에 하나씩 (학습과 같게)")
    p.add_argument("--runs", default="wp02,wp01", type=lambda s: s.split(","),
                   help="예측을 낼 승률 예측망. 첫째(wp02)를 보정하고 둘째(wp01)는 비교 기준")
    p.add_argument("--onnx", default="models/winprob.onnx", help="Java 가 쓰는 모델 (사례의 p 를 이것으로 낸다)")
    p.add_argument("--workers", type=int, default=6, help="기록을 읽는 프로세스 수")
    p.add_argument("--bootstrap", type=int, default=1000, help="판 단위 부트스트랩 횟수")
    p.add_argument("--parity-games", type=int, default=40, help="Java 사례로 쓸 시험 판 수 (판마다 상태 쌍 3개)")
    p.add_argument("--refresh", action="store_true", help="캐시를 버리고 기록을 다시 읽는다")
    p.add_argument("--extra-test", action="append", default=[], metavar="이름=글롭",
                   help="맞추는 데 쓰지 않은 다른 timed-1 기록(저장소 기준 글롭)의 모든 막대 순간에서도 잰다. 여러 번 줄 수 있다")
    p.add_argument("--fixture-from", metavar="RUN",
                   help="맞추지 않고 runs/<RUN>/ 의 결과로 공통 픽스처 fixtures/winprob_calibration/cases.jsonl 만 다시 쓴다")
    args = p.parse_args()
    if args.fixture_from:
        out = write_fixture(args.fixture_from)
        _, specs, cases = read_fixture(out)
        print(f"→ {out.relative_to(ROOT).as_posix()}: 보정층 {len(specs)}개, 사례 {len(cases)}개")
        return
    if args.runs[0] != "wp02" or "wp01" not in args.runs:
        raise SystemExit("--runs 의 첫째는 보정할 모델 wp02 이고 비교 기준 wp01 도 있어야 함")
    out_dir = ROOT / "runs" / args.out
    out_dir.mkdir(parents=True, exist_ok=True)
    log = Log(out_dir / "fit.log")
    log(f"[설정] {json.dumps(vars(args), ensure_ascii=False)}")
    start_all = time.time()
    data, meta = prepare(args, out_dir, log)

    # 자료 확인(모든 표본): 표본이 wp02 의 metrics.json 과 같은가, 블록 수 셈이 가방 진행과 맞는가
    ref = json.loads((ROOT / "runs" / "wp02" / "metrics.json").read_text(encoding="utf-8"))
    checks = {}
    for name, d in data.items():
        mine = wp.log_loss(d["p"]["wp02"], d["y"])
        checks[name] = {"samples": int(len(d["y"])), "games": len(d["games"]), "wp02_log_loss": mine,
                        "metrics_json_samples": ref[name]["model"]["n"], "metrics_json_log_loss": ref[name]["model"]["log_loss"],
                        "bag_mismatch": int(sum(g["bag_mismatch"] for g in d["games"])),
                        "legacy_minus_exact_final": dict(zip(*(v.tolist() for v in np.unique(
                            np.array([g["legacy_final"] for g in d["games"]]).ravel()
                            - np.array([g["pieces_final"] for g in d["games"]]).ravel(), return_counts=True))))}
        log(f"[확인] {name}: 표본 {len(d['y'])} (metrics.json {ref[name]['model']['n']}), wp02 log loss {mine:.6f} "
            f"(metrics.json {ref[name]['model']['log_loss']:.6f}), 가방 진행과 안 맞는 블록 수 {checks[name]['bag_mismatch']}")
        if len(d["y"]) != ref[name]["model"]["n"] or abs(mine - ref[name]["model"]["log_loss"]) > 1e-5:
            raise SystemExit("표본이 wp02 학습·평가와 다름")
    first = [tuple(int(v) for v in (data["val"]["n1"][i], data["val"]["n2"][i]))
             for i in np.flatnonzero(data["val"]["turn"] == 0)]
    checks["first_step_counts"] = {str(k): first.count(k) for k in sorted(set(first))}

    # 이제부터는 게임이 보정층을 쓰는 순간만: 판이 끝난 틱의 기록을 뺀다 (live_mask)
    for name in list(data):
        live = live_mask(data[name])
        checks[name]["terminal_excluded"] = int((~live).sum())
        checks[name]["live_samples"] = int(live.sum())
        checks[name]["wp02_log_loss_live"] = wp.log_loss(data[name]["p"]["wp02"][live], data[name]["y"][live])
        log(f"[진행 중] {name}: 판이 끝난 순간의 기록 {int((~live).sum())}개를 뺌 → 표본 {int(live.sum())}개, "
            f"wp02 log loss {checks[name]['wp02_log_loss_live']:.6f}")
        data[name] = subset(data[name], live)
    val, test = data["val"], data["test"]

    # 후보를 검증 판으로 맞추고 고른다
    start = time.time()
    fits = [fit_candidate(c, val, log) for c in CANDIDATES]
    chosen = choose(fits)
    log(f"[고름] {chosen['name']} (검증 log loss 가 가장 낮은 후보와 {SIMPLER_WITHIN} 안에서 계수가 가장 적음, "
        f"{time.time() - start:.0f}초)")
    onnx_path = ROOT / args.onnx
    spec = dict(chosen["spec"])
    spec.update(model="wp02", model_sha256=sha256(ROOT / "runs" / "wp02" / "winprob_wp02.onnx"))
    if sha256(onnx_path) != spec["model_sha256"]:
        log(f"[주의] {args.onnx} 가 runs/wp02/winprob_wp02.onnx 와 다르다")

    # 시험 판 평가
    cal_key = f"cal:{chosen['name']}"
    pure = min((f for f in fits if f["name"].startswith("temp_")), key=lambda f: f["val"]["log_loss"])
    pure_key = f"cal:{pure['name']}"
    ev = evaluate(test, fits, cal_key, pure_key, args.bootstrap)
    overall, by_group = ev["overall"], ev["groups"]

    spec["fit"] = {
        "data": f"runs/{args.timed_run} 묶음 {args.chunks}, 검증 판(시드 끝자리 8), 판마다 고정 {args.stride}번에 하나, "
                f"판이 끝난 순간의 기록은 뺌",
        "games": len(val["games"]), "samples": int(len(val["y"])), "terminal_excluded": checks["val"]["terminal_excluded"],
        "selection": f"검증 log loss 최소 후보와 {SIMPLER_WITHIN} 안인 후보 중 계수가 가장 적은 것 (후보 {len(fits)}개)",
        "val_log_loss_raw": wp.log_loss(val["p"]["wp02"], val["y"]), "val_log_loss": chosen["val"]["log_loss"],
        "val_ece_raw": wp.ece(val["p"]["wp02"], val["y"]), "val_ece": chosen["val"]["ece"]}
    spec["test"] = {m: {k: overall[m][k] for k in ("log_loss", "brier", "ece", "accuracy")} for m in ("wp01", "wp02", cal_key)}
    near_rows = {r["group"]: r for r in by_group["final_pps_ratio_legacy"]}
    spec["test_near_equal_speed"] = {m: {k: near_rows["<1.25"][m][k] for k in ("log_loss", "ece", "accuracy")}
                                     for m in ("wp01", "wp02", cal_key)}
    spec["inputs"] = {
        "p": "WinRate 의 원래 출력 p1Win(P1 상태, P2 상태): float32 를 그대로 double 로. 두 게임 중 하나라도 끝났으면 보정하지 않는다",
        "n1": "match.player(0).game().pieces() (P1 이 패배 없이 고정한 블록 수)",
        "n2": "match.player(1).game().pieces()",
        "t_ms": "match.time() (판 시작부터 흐른 ms, 틱 수)",
    }
    spec["formula"] = FORMULA if spec["kind"] == "general" else ["r 구간 b 의 온도: zc = exp(log_scale[b]) * z"]
    spec["guards"] = [
        "model_sha256 이 모델 파일의 SHA-256 과 다르면(없거나 보정 파일이 깨졌어도) Java 는 보정층을 붙이지 않고 원래 p 를 "
        "쓴다(계수는 이 모델 전용)",
        "p 는 logit 전에 [eps, 1 - eps] 로 자른다(float32 출력이 정확히 0 이나 1 일 수 있다)",
        "k > 0 이라 n1 = n2 = 0 에서도 r = 1, d = 0 으로 정의된다(따로 처리할 것 없음). 판 시작(t = 0)의 두 상태는 같아 p = 0.5, p_cal = 0.5",
        "판 초반의 흔들림은 k(가상의 블록 수)가 줄인다. 판 초반만 따로 끄는 규칙은 없다",
        "판이 끝난 순간(어느 한쪽이라도 isOver)의 기록은 맞추지도 재지도 않았다. 게임은 그때 모델 없이 결과를 막대에 놓는다",
        "출력 p_cal 은 [0, 1] 이다(자르지 않는다). Java WinCalibration.apply 는 p 가 NaN 이거나 블록 수·시간이 음수(모름)이면, "
        "또 계산이 넘쳐 NaN 이 되면(계수가 아주 클 때만) 보정하지 않은 p 를 돌려준다",
    ]
    spec["symmetry"] = "P1 과 P2 를 바꾸면(p → 1 − p, n1 ↔ n2) 결과가 정확히 1 − p_cal 이다"
    (out_dir / "calibration.json").write_text(json.dumps(spec, ensure_ascii=False, indent=1) + "\n", encoding="utf-8",
                                              newline="\n")

    parity = parity_cases(spec, test, ROOT / "runs" / args.timed_run / "data", onnx_path, args.parity_games, log)
    torch_vs_onnx = []
    (out_dir / "parity_cases.json").write_text(json.dumps(parity, ensure_ascii=False, indent=1) + "\n", encoding="utf-8",
                                               newline="\n")
    seed_index = {g["seed"]: i for i, g in enumerate(test["games"])}
    for c in parity["states"]:
        gi = seed_index[c["seed"]]
        hit = np.flatnonzero((test["match"] == gi) & (test["turn"] == c["step"]))
        if len(hit):
            torch_vs_onnx.append(abs(float(test["p"]["wp02"][hit[0]]) - c["p"]))
    if torch_vs_onnx:
        log(f"[사례] 표본에 든 상태 {len(torch_vs_onnx)}개에서 PyTorch 와 onnxruntime 의 p 차이 최대 {max(torch_vs_onnx):.1e}")

    extra, extra_ev = {}, {}
    for item in args.extra_test:
        name, info, xev = extra_test(item, args, fits, cal_key, pure_key, log)
        extra[name] = {**info, **{k: xev[k] for k in ("overall", "paired", "groups", "near_reliability")}}
        extra_ev[name] = xev

    metrics = {"config": vars(args), "data_checks": checks, "candidates": fits, "chosen": chosen["name"],
               "selection_rule": spec["fit"]["selection"], "test_overall": overall, "test_overall_paired": ev["paired"],
               "test_groups": by_group, "test_near_equal_reliability": ev["near_reliability"], "extra_test": extra,
               "parity_torch_vs_onnx_max": max(torch_vs_onnx) if torch_vs_onnx else None,
               "seconds": time.time() - start_all}
    (out_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=1, default=float) + "\n",
                                          encoding="utf-8", newline="\n")

    # 마크다운 표
    lines = ["", "### 후보 (검증 판에서 맞추고 고름)", "", "| 후보 | 계수 | k | tau | 검증 log loss | 검증 ECE | 시험 log loss | 시험 ECE |",
             "|---|---|---|---|---|---|---|---|",
             f"| 보정 없음 (wp02) | 0 | | | {wp.log_loss(val['p']['wp02'], val['y']):.4f} | {wp.ece(val['p']['wp02'], val['y']):.4f} | "
             f"{overall['wp02']['log_loss']:.4f} | {overall['wp02']['ece']:.4f} |"]
    for f in fits:
        sp = f["spec"]
        mark = " **(고름)**" if f["name"] == chosen["name"] else ""
        o = overall[f"cal:{f['name']}"]
        lines.append(f"| {f['name']}{mark} | {f['coefficients']} | {sp['k']:g} | {sp.get('tau_ms') or 0:.0f} | "
                     f"{f['val']['log_loss']:.4f} | {f['val']['ece']:.4f} | {o['log_loss']:.4f} | {o['ece']:.4f} |")
    lines += [""] + markdown_eval("시험 판", ev)
    for name, xev in extra_ev.items():
        lines += markdown_eval(f"다른 기록 {name}", xev, ("final_pps_ratio_exact", "time_s", "early"))
    for line in lines:
        log(line)
    log(f"[끝] {time.time() - start_all:.0f}초, 결과 runs/{args.out}/")


if __name__ == "__main__":
    main()
