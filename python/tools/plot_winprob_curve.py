"""승률 예측망 학습 곡선(winprob_learning_curve.py 의 curve.json)을 그림으로 그린다 (TRAINING.md 10.10).

    python python/tools/plot_winprob_curve.py --run wplc01 --png docs/figures/winprob_learning_curve.png

그림 세 칸: ① 실시간 학습 판 수에 따른 시험 log loss ② 같은 축의 ECE ③ 시험 판 수에 따른 측정 정밀도
(사람 판 몇 판이면 성적을 잴 수 있는가). matplotlib 이 필요하다. 학습 환경(requirements*.txt)에는 넣지 않았으므로
그림이 필요할 때만 `python -m pip install matplotlib` 으로 설치한다.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

LABELS = {"scratch": "실시간 판만으로 학습", "mixed": "블록 동기 3.2만 판 + 실시간 판"}
COLORS = {"scratch": "#1f77b4", "mixed": "#d62728", "current": "#555555"}


def by_mode(rows: list[dict], key: str) -> dict[str, tuple[list[int], list[float], list[list[float]]]]:
    """모드마다 (판 수, 반복의 평균, 반복 값들)."""
    groups: dict[str, dict[int, list[float]]] = defaultdict(lambda: defaultdict(list))
    for r in rows:
        groups[r["mode"]][int(r["games"])].append(float(r[key]))
    out = {}
    for mode, sizes in groups.items():
        xs = sorted(sizes)
        out[mode] = (xs, [sum(sizes[x]) / len(sizes[x]) for x in xs], [sizes[x] for x in xs])
    return out


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", default="wplc01", help="학습 곡선 결과 폴더 runs/<run> (curve.json)")
    p.add_argument("--png", default=None, help="저장할 그림 (기본: runs/<run>/curve.png)")
    p.add_argument("--test-games", type=int, default=None,
                   help="제목에 쓸 시험 판 수. curve.json 에 data.test_games 가 없는 옛 결과(wplc01, wplc02)에만 준다")
    args = p.parse_args()

    import matplotlib

    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    from matplotlib.ticker import FixedLocator, FuncFormatter, NullLocator

    # 깔린 한글 글꼴 하나만 고른다 (목록으로 주면 없는 글꼴마다 글자를 찾을 때 경고가 나온다)
    installed = {f.name for f in font_manager.fontManager.ttflist}
    korean = next((f for f in ("Malgun Gothic", "AppleGothic", "NanumGothic", "Noto Sans CJK KR") if f in installed), None)
    if korean:
        plt.rcParams["font.family"] = korean
    plt.rcParams["axes.unicode_minus"] = False

    run_dir = ROOT / "runs" / args.run
    res = json.loads((run_dir / "curve.json").read_text(encoding="utf-8"))
    rows = res["curve"]
    cur = res["baselines"]["current_model"]
    # 비교 기준 모델(--base-run). wp01 은 10-04 까지 게임에 쓴 블록 동기 모델이다(지금 게임은 wp02, TRAINING.md 10.11)
    base = res["settings"].get("base_run", "wp01")
    base_label = f"비교 기준 {base}" + (" (블록 동기 4만 판)" if base == "wp01" else "")
    budget = int(res["settings"]["target_samples"])

    fig, axes = plt.subplots(1, 3, figsize=(15, 4.8), gridspec_kw={"width_ratios": [1.15, 1.15, 1]})
    games_fmt = FuncFormatter(lambda v, _: f"{int(v):,}")

    for ax, key, title in ((axes[0], "log_loss", "① 시험 log loss (낮을수록 좋다)"),
                           (axes[1], "ece", "② 시험 ECE: 보정 오차 (낮을수록 좋다)")):
        curves = by_mode(rows, key)
        for mode in ("scratch", "mixed"):
            if mode not in curves:
                continue
            xs, means, reps = curves[mode]
            ax.plot(xs, means, "o-", color=COLORS[mode], label=LABELS[mode], lw=2, ms=5, zorder=3)
            for x, vals in zip(xs, reps):
                if len(vals) > 1:
                    ax.scatter([x] * len(vals), vals, s=12, color=COLORS[mode], alpha=0.35, zorder=2)
        lo, hi = (cur["ll_lo"], cur["ll_hi"]) if key == "log_loss" else (cur["ece_lo"], cur["ece_hi"])
        ax.axhline(cur[key], color=COLORS["current"], ls="--", lw=1.5, label=base_label)
        ax.axhspan(lo, hi, color=COLORS["current"], alpha=0.12, lw=0)
        ax.set_xscale("log")
        all_x = sorted({int(r["games"]) for r in rows})
        ax.xaxis.set_major_locator(FixedLocator(all_x))
        ax.xaxis.set_minor_locator(NullLocator())
        ax.xaxis.set_major_formatter(games_fmt)
        ax.tick_params(axis="x", labelsize=8)
        ax.set_xlabel("실시간 학습 판 수 (로그 눈금)")
        ax.set_title(title, fontsize=11)
        ax.grid(alpha=0.3)
    axes[0].set_ylabel("log loss (늘 0.5라고 하면 0.693)")

    # 예산 한도에 걸린 점: scratch 의 반복 횟수 = 예산 ÷ 학습 표본(30번 미만)이고 가장 좋은 반복이 마지막 무렵인 점.
    # 학습률을 코사인으로 0 까지 줄이므로 마지막 무렵이 가장 좋은 것만으로 덜 학습했다고 단정할 수는 없다(TRAINING.md 10.10)
    stopped = [r for r in rows if r["mode"] == "scratch" and int(r["epochs"]) < 30
               and int(r["best_epoch"]) >= int(r["epochs"]) - 1]
    if stopped:
        axes[0].scatter([int(r["games"]) for r in stopped], [float(r["log_loss"]) for r in stopped], s=140,
                        facecolors="none", edgecolors="#ff7f0e", linewidths=1.8, zorder=4,
                        label=f"학습 예산({budget / 1e4:,.0f}만 표본) 때문에 반복이 "
                              + "·".join(str(r["epochs"]) for r in stopped) + "번뿐이었던 점 (가장 좋은 반복이 마지막 무렵)")
    # ①② 가 같은 선을 쓰므로 범례는 그림 아래에 하나만 둔다
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=2, fontsize=9, frameon=False, bbox_to_anchor=(0.36, 0.0))

    # ③ 시험 판 수에 따른 측정 정밀도. 새 모델(가장 많이 배운 실시간만 모델)의 값과, 두 모델을 같은 판으로 비교한
    # 차이의 범위가 있으면 함께 그린다(winprob_learning_curve.py 의 eval_precision_largest, paired_vs_current).
    ax = axes[2]
    prec = res["eval_precision_current_model"]
    ns = [row["games"] for row in prec]
    lines = [(prec, f"비교 기준 {base}: 측정 오차", "#2ca02c", "o-")]
    largest = res.get("eval_precision_largest")
    if largest:
        lines.append((largest["rows"], f"새 모델(실시간 {largest['train_games']:,}판): 측정 오차", COLORS["scratch"], "s-"))
    for data, label, color, style in lines:
        ax.plot([r["games"] for r in data], [r["log_loss_95_halfwidth"] for r in data], style, color=color, lw=2, ms=5,
                label=label)
    for row in (largest["rows"] if largest else prec):
        if row["games"] in (100, 400):
            ax.annotate(f"±{row['log_loss_95_halfwidth']:.3f}", (row["games"], row["log_loss_95_halfwidth"]),
                        xytext=(6, 6), textcoords="offset points", fontsize=8.5)
    # 같은 판 비교: 차이(새 모델 - 비교 기준)의 95% 범위가 0 을 넘지 않는 가장 적은 판 수에 세로선을 긋는다.
    # 판이 적으면 범위가 한쪽으로 치우치므로 반폭이 아니라 0 쪽 끝(diff_95[1])으로 판단한다.
    paired = res.get("paired_vs_current")
    if paired:
        enough = [r["games"] for r in paired["rows"] if r["diff_95"][1] < 0]
        if enough:
            ax.axvline(min(enough), color="#9467bd", ls="--", lw=1.4,
                       label=f"같은 판 비교로 가려지는 판 수: {min(enough):,}판\n(차이의 95% 범위가 0 미만)")
    ax.set_xscale("log")
    ax.xaxis.set_major_locator(FixedLocator(ns))
    ax.xaxis.set_minor_locator(NullLocator())
    ax.xaxis.set_major_formatter(games_fmt)
    ax.tick_params(axis="x", labelsize=8)
    ax.set_xlabel("시험 판 수 (로그 눈금)")
    ax.set_ylabel("± (log loss 측정 오차, 95% 범위의 반폭)")
    ax.set_title("③ 시험 판 수에 따른 측정 정밀도", fontsize=11)
    ax.grid(alpha=0.3)
    ax.legend(fontsize=7.5, loc="upper right")

    test_games = (res.get("data") or {}).get("test_games") or args.test_games
    shown = f"시험 판 {test_games:,}판 고정, 판 단위로 나눔" if test_games else "판 단위로 나눈 시험 판 고정"
    fig.suptitle(f"승률 예측망 학습 곡선: 실시간 판 ({shown})", fontsize=12.5)
    fig.tight_layout(rect=(0, 0.1, 1, 1))
    out = Path(args.png) if args.png else run_dir / "curve.png"
    if not out.is_absolute():
        out = ROOT / out
    out.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(out, dpi=130)
    print(f"[그림] {out.relative_to(ROOT).as_posix() if out.is_relative_to(ROOT) else out}")


if __name__ == "__main__":
    main()
