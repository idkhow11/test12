"""승률 예측망의 학습 곡선 (docs/TRAINING.md 10.10): 실시간 판을 몇 판 모으면 예측을 믿을 수 있는가.

    python python/tools/winprob_learning_curve.py --timed-run wpt01 --base-run wp01 --out wplc01

실시간 자료(timed-1, 10.9)의 시험 판(시드 끝자리 9)을 고정해 두고, 학습 판에서 N 판을 무작위로 뽑아 승률 예측망을 학습한
뒤 시험 판 성적(log loss, Brier, ECE, 판 단위 부트스트랩 95% 범위)을 잰다. 학습 방식은 둘이다.

    scratch   실시간 N 판만으로 학습 (반복 횟수는 표본이 적을수록 늘린다, 검증 log loss 가 가장 낮은 반복을 쓴다)
    mixed     블록 동기 4만 판(wp01 자료) + 실시간 N 판으로 학습 (지금 계획한 방식)

기준: 비교 기준 모델(--base-run, 기본 wp01: 10-04 까지 게임에 쓴 모델)을 그대로 잰 값, 늘 0.5. 검증은 언제나 실시간 검증 판(시드 끝자리 8)이다. 결과는
runs/<out>/curve.csv(한 줄이 한 번의 학습), curve.json, curve.log 이고, 이미 있는 줄은 건너뛰어 이어서 할 수 있다.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

from tetris_ai import winprob as wp  # noqa: E402
import train_winprob as tw  # noqa: E402

FIELDS = ["mode", "games", "repeat", "train_samples", "epochs", "best_epoch", "log_loss", "brier", "ece", "ll_lo",
          "ll_hi", "ece_lo", "ece_hi", "accuracy", "seconds"]


def files_of(run: str, pattern: str = "*/*.jsonl.gz") -> list[Path]:
    return tw.record_files(ROOT / "runs" / run / "data", pattern)  # 끝까지 만든 묶음만


def match_ids(d: wp.Data) -> np.ndarray:
    return np.unique(d.match)


def pick_games(d: wp.Data, n: int, seed: int) -> wp.Data:
    """판 n 개를 무작위로 뽑아 그 판의 표본만 남긴다."""
    ids = match_ids(d)
    chosen = np.random.default_rng(seed).choice(ids, size=min(n, len(ids)), replace=False)
    return d.subset(np.isin(d.match, chosen))


def score(name: str, p: np.ndarray, d: wp.Data, reps: int) -> dict:
    s = wp.scores(p, d.y)
    b = tw.bootstrap(p, d.y, d.match, reps, 0)
    decided = d.y != 0.5
    return {"log_loss": s["log_loss"], "brier": s["brier"], "ece": s["ece"], "ll_lo": b["log_loss"][0],
            "ll_hi": b["log_loss"][1], "ece_lo": b["ece"][0], "ece_hi": b["ece"][1],
            "accuracy": float(((p[decided] > 0.5) == (d.y[decided] > 0.5)).mean())}


def eval_precision(p: np.ndarray, d: wp.Data, sizes: list[int], reps: int) -> list[dict]:
    """시험 판을 n 판만 모았을 때 잰 값이 얼마나 흔들리는가: 시험 판에서 n 판을 다시 뽑아 log loss·ECE 의 95% 범위 폭."""
    ids = match_ids(d)
    rng = np.random.default_rng(7)
    rows = []
    for n in sizes:
        if n > len(ids):
            continue
        lls, eces = [], []
        for _ in range(reps):
            chosen = rng.choice(ids, size=n, replace=True)
            counts = np.bincount(np.searchsorted(ids, chosen), minlength=len(ids))
            w = counts[np.searchsorted(ids, d.match)].astype(np.float64)
            pc = np.clip(p, 1e-7, 1 - 1e-7)
            ll = -(d.y * np.log(pc) + (1 - d.y) * np.log(1 - pc))
            lls.append(float((w * ll).sum() / w.sum()))
            bins = np.minimum((p * 10).astype(np.int64), 9)
            bn = np.bincount(bins, w, 10)
            bp = np.bincount(bins, w * p, 10)
            by = np.bincount(bins, w * d.y, 10)
            eces.append(float(np.abs(bp - by).sum() / w.sum()))
        rows.append({"games": n, "log_loss_95_halfwidth": (np.percentile(lls, 97.5) - np.percentile(lls, 2.5)) / 2,
                     "ece_mean": float(np.mean(eces)), "ece_95": [float(np.percentile(eces, 2.5)), float(np.percentile(eces, 97.5))]})
    return rows


def paired_precision(p_new: np.ndarray, p_old: np.ndarray, d: wp.Data, sizes: list[int], reps: int) -> list[dict]:
    """두 모델을 같은 시험 판 n 판으로 비교할 때: 판을 다시 뽑아 log loss 차이(새 - 옛)의 95% 범위와 새 모델이 더 낮게
    나온 비율. 같은 판으로 재므로 한 모델의 범위(eval_precision)보다 좁을 수 있다."""
    ids = match_ids(d)
    inv = np.searchsorted(ids, d.match)
    rng = np.random.default_rng(11)

    def sample_ll(p: np.ndarray) -> np.ndarray:
        pc = np.clip(p, 1e-7, 1 - 1e-7)
        return -(d.y * np.log(pc) + (1 - d.y) * np.log(1 - pc))

    gap = sample_ll(p_new) - sample_ll(p_old)
    rows = []
    for n in sizes:
        if n > len(ids):
            continue
        diffs = np.empty(reps)
        for i in range(reps):
            w = np.bincount(np.searchsorted(ids, rng.choice(ids, size=n, replace=True)), minlength=len(ids))[inv]
            diffs[i] = (w * gap).sum() / w.sum()
        rows.append({"games": n, "diff_mean": float(diffs.mean()),
                     "diff_95": [float(np.percentile(diffs, 2.5)), float(np.percentile(diffs, 97.5))],
                     "new_better_share": float((diffs < 0).mean())})
    return rows


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--timed-run", default="wpt01")
    p.add_argument("--timed-glob", default="*/*.jsonl.gz",
                   help="실시간 실행의 data/ 아래에서 읽을 기록 (예: 'chunk-0[01]?/*.jsonl.gz' 는 앞 2만 판)")
    p.add_argument("--base-run", default="wp01", help="블록 동기 자료와 비교 기준 모델(winprob_<run>.pt)이 있는 실행. 기본 wp01 은 10-04 까지 게임에 쓴 모델")
    p.add_argument("--out", default="wplc01")
    p.add_argument("--sizes", default="250,500,1000,2000,4000,8000,16000", help="실시간 학습 판 수 (쉼표로)")
    p.add_argument("--repeats-small", type=int, default=3, help="2,000판 이하에서 판을 다시 뽑아 반복할 횟수")
    p.add_argument("--modes", default="scratch,mixed")
    p.add_argument("--stride", type=int, default=3, help="실시간 학습·검증 판은 판마다 표본을 이만큼에 하나씩")
    p.add_argument("--target-samples", type=int, default=3_000_000,
                   help="scratch 의 반복 횟수 = 이 표본 수 ÷ 학습 표본 (2~30번)")
    p.add_argument("--bootstrap", type=int, default=200)
    args = p.parse_args()

    out = ROOT / "runs" / args.out
    out.mkdir(parents=True, exist_ok=True)
    log = tw.Log(out / "curve.log")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    log(f"[설정] {json.dumps(vars(args), ensure_ascii=False)} | 장치 {device}")

    # 실시간 기록은 읽을 때부터 판마다 stride 개에 하나만 읽는다(10만 판이면 상태를 모두 읽을 때 RAM 이 20GB 를 넘는다).
    # 그래서 시험 판도 같은 간격의 표본이다.
    timed = tw.load_cached(ROOT / "runs" / args.timed_run / "dataset.npz", files_of(args.timed_run, args.timed_glob), log,
                           args.stride)
    t_train, t_val, t_test = (timed.split(s) for s in wp.SPLITS)
    t_val = t_val.thin(args.stride)
    log(f"[자료] 실시간 학습 판 {len(match_ids(t_train))}, 검증 판 {len(match_ids(t_val))}, "
        f"시험 판 {len(match_ids(t_test))} (시험 표본 {len(t_test):,}개)")
    base_train = None
    if "mixed" in args.modes:
        base = tw.load_cached(ROOT / "runs" / args.base_run / "dataset.npz", files_of(args.base_run, "chunk-*/w*.jsonl.gz"),
                              log)
        base_train = base.split("train")
        log(f"[자료] 블록 동기 학습 판 {len(match_ids(base_train))} (표본 {len(base_train):,}개)")

    results = {"settings": vars(args), "baselines": {},
               "data": {"train_games": int(len(match_ids(t_train))), "val_games": int(len(match_ids(t_val))),
                        "test_games": int(len(match_ids(t_test))), "test_samples": int(len(t_test))}}
    # 기준 1: 지금 모델
    ckpt = ROOT / "runs" / args.base_run / f"winprob_{args.base_run}.pt"
    saved = torch.load(ckpt, map_location=device)
    cur = wp.WinProbNet(**saved["config"]).to(device)
    cur.load_state_dict(saved["state_dict"])
    p_cur = tw.predict(cur, t_test, device)
    results["baselines"]["current_model"] = score("current", p_cur, t_test, args.bootstrap)
    results["baselines"]["constant_0.5"] = wp.scores(np.full(len(t_test), 0.5), t_test.y)
    log(f"[기준] 지금 모델({args.base_run}) 실시간 시험 판: {json.dumps(results['baselines']['current_model'])}")
    log(f"[기준] 늘 0.5: {json.dumps(results['baselines']['constant_0.5'])}")
    # 시험 판 수에 따른 측정 정밀도 (사람 판으로 확인할 때 몇 판이 필요한가)
    results["eval_precision_current_model"] = eval_precision(p_cur, t_test, [25, 50, 100, 200, 400, 800, 1600], 400)
    for row in results["eval_precision_current_model"]:
        log(f"[정밀도] 시험 {row['games']}판: log loss ±{row['log_loss_95_halfwidth']:.4f}, "
            f"ECE 평균 {row['ece_mean']:.4f} (95% {row['ece_95'][0]:.4f}~{row['ece_95'][1]:.4f})")

    csv_path = out / "curve.csv"
    done = set()
    if csv_path.exists():
        with open(csv_path, encoding="utf-8") as f:
            done = {(r["mode"], int(r["games"]), int(r["repeat"])) for r in csv.DictReader(f)}
    else:
        with open(csv_path, "w", newline="", encoding="utf-8") as f:
            csv.DictWriter(f, FIELDS).writeheader()

    sizes = [int(x) for x in args.sizes.split(",")]
    train_games = len(match_ids(t_train))
    largest = None  # 판 수가 가장 많은 실시간만(scratch) 모델의 시험 예측: 새 모델의 측정 정밀도를 직접 잰다
    for mode in args.modes.split(","):
        for n in sizes:
            for r in range(args.repeats_small if n <= 2000 else 1):
                if (mode, min(n, train_games), r) in done:  # CSV 에는 실제로 뽑힌 판 수가 적힌다
                    continue
                sub = pick_games(t_train, n, seed=1000 * r + n).thin(args.stride)
                train_d = sub if mode == "scratch" else wp.Data.concat([base_train, sub])
                epochs = (min(30, max(2, math.ceil(args.target_samples / max(1, len(train_d)))))
                          if mode == "scratch" else 2)
                ns = SimpleNamespace(epochs=epochs, batch=1024, lr=1e-3, weight_decay=1e-4, dropout=0.0, seed=r)
                start = time.time()
                net, history = tw.train(ns, train_d, t_val, device, log)
                p_test = tw.predict(net, t_test, device)
                if mode == "scratch" and r == 0 and (largest is None or n > largest[2]):
                    largest = (len(match_ids(sub)), p_test, n)  # (실제 판 수, 시험 예측, 요청한 판 수)
                row = {"mode": mode, "games": len(match_ids(sub)), "repeat": r, "train_samples": len(train_d),
                       "epochs": epochs, "best_epoch": min(history, key=lambda h: h["log_loss"])["epoch"],
                       **score(mode, p_test, t_test, args.bootstrap), "seconds": round(time.time() - start, 1)}
                with open(csv_path, "a", newline="", encoding="utf-8") as f:
                    csv.DictWriter(f, FIELDS).writerow({k: (round(v, 5) if isinstance(v, float) else v)
                                                        for k, v in row.items()})
                log(f"[곡선] {mode} {row['games']}판 #{r}: log loss {row['log_loss']:.4f} "
                    f"({row['ll_lo']:.4f}~{row['ll_hi']:.4f}), ECE {row['ece']:.4f}, 적중 {row['accuracy']:.3f}, "
                    f"{row['seconds']:.0f}초")
    # 이어서 실행하면 이번에 학습한 모델이 없거나 더 작을 수 있다. 그때는 이전 curve.json 의 값을 그대로 둔다.
    previous = {}
    if (out / "curve.json").exists():
        try:
            previous = json.loads((out / "curve.json").read_text(encoding="utf-8"))
        except ValueError:
            previous = {}
    previous_games = (previous.get("eval_precision_largest") or {}).get("train_games", -1)
    if largest is not None and largest[0] < previous_games:
        largest = None
    if largest is None and previous_games >= 0:
        results["eval_precision_largest"] = previous["eval_precision_largest"]
        results["paired_vs_current"] = previous.get("paired_vs_current")
        log(f"[정밀도·새 모델] 이전 실행에서 잰 {previous_games}판 모델의 값을 그대로 둔다")
    elif largest is not None:
        precision_sizes = [25, 50, 100, 200, 400, 800, 1600]
        results["eval_precision_largest"] = {"train_games": largest[0],
                                             "rows": eval_precision(largest[1], t_test, precision_sizes, 400)}
        results["paired_vs_current"] = {"train_games": largest[0],
                                        "rows": paired_precision(largest[1], p_cur, t_test, precision_sizes, 400)}
        for row, pair in zip(results["eval_precision_largest"]["rows"], results["paired_vs_current"]["rows"]):
            log(f"[정밀도·새 모델 {largest[0]}판] 시험 {row['games']}판: log loss ±{row['log_loss_95_halfwidth']:.4f}, "
                f"ECE 평균 {row['ece_mean']:.4f} | 비교 기준과의 차이 {pair['diff_mean']:+.4f} "
                f"(95% {pair['diff_95'][0]:+.4f}~{pair['diff_95'][1]:+.4f}), 새 모델이 나은 비율 {pair['new_better_share']:.3f}")
    else:
        log("[정밀도·새 모델] 이번 실행에서 학습한 실시간만 모델이 없어(이미 한 줄은 건너뜀) 재지 않았다")
    with open(csv_path, encoding="utf-8") as f:
        results["curve"] = list(csv.DictReader(f))
    (out / "curve.json").write_text(json.dumps(results, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    log("[끝]")


if __name__ == "__main__":
    main()
