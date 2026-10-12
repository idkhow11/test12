"""실시간 시험 판을 두 플레이어의 속도 차이로 나눠 승률 예측망 둘을 같은 판으로 비교한다 (TRAINING.md 10.11).

    python python/tools/winprob_speed_subsets.py --timed-run wpt01 --models wp01,wp02

시험 판은 10.1의 나누기(시드 끝자리 9)를 따르고, 표본은 학습과 같이 판마다 고정 3번에 하나다(--stride). 두 플레이어의
속도 비(느린 쪽 ÷ 빠른 쪽)로 판을 묶는다. 속도는 --group-by 로 고른다.
  pps   (기본) 실제 초당 블록 수: 기록의 고정마다 플레이어의 다음 블록 목록(queue)이 바뀐 횟수 ÷ 판 시간
  think 기록 머리말의 생각 지연(players[].think_ms)만. 초당 입력 수를 무시하므로 '같은 속도'를 잘못 묶는다(10.11)

모델은 runs/<run>/winprob_<run>.pt 체크포인트를 읽는다. 결과는 표로 찍기만 하고 파일은 쓰지 않는다(자료 캐시는 학습과 같이
runs/<timed-run>/dataset_s<stride>.npz 를 쓴다).
"""

from __future__ import annotations

import argparse
import gzip
import json
import sys
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

import train_winprob as tw  # noqa: E402
from tetris_ai import winprob as wp  # noqa: E402

GROUPS = [("1.25배 미만 (거의 같은 속도)", 1.0, 1.25), ("1.25~2배", 1.25, 2.0), ("2~4배", 2.0, 4.0),
          ("4배 이상", 4.0, float("inf"))]


def metrics(p: np.ndarray, y: np.ndarray) -> tuple[float, float, float]:
    """log loss, ECE(10구간), 승패가 난 표본의 적중률."""
    pc = np.clip(p, 1e-7, 1 - 1e-7)
    ll = float(np.mean(-(y * np.log(pc) + (1 - y) * np.log(1 - pc))))
    bins = np.minimum((p * 10).astype(int), 9)
    ece = sum(abs(p[bins == b].mean() - y[bins == b].mean()) * (bins == b).sum()
              for b in range(10) if (bins == b).any()) / len(p)
    decided = y != 0.5
    return ll, float(ece), float(((p[decided] > 0.5) == (y[decided] > 0.5)).mean())


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--timed-run", default="wpt01", help="실시간 기록 실행 runs/<run>/data")
    p.add_argument("--models", default="wp01,wp02", help="비교할 승률 예측망 실행 (쉼표로)")
    p.add_argument("--stride", type=int, default=3, help="판마다 표본을 이만큼에 하나씩 (학습과 같게)")
    p.add_argument("--group-by", choices=("pps", "think"), default="pps", help="속도 비를 무엇으로 잴지 (위 설명)")
    p.add_argument("--bootstrap", type=int, default=1000, help="두 모델 차이의 판 단위 부트스트랩 횟수 (첫 두 모델)")
    args = p.parse_args()

    files = tw.record_files(ROOT / "runs" / args.timed_run / "data", "chunk-*/w*.jsonl.gz")
    speed = {}  # 시드 → [P1 속도, P2 속도] (클수록 빠르다)
    for f in files:
        with gzip.open(f, "rt", encoding="utf-8") as fh:
            for line in fh:
                m = json.loads(line)
                if m["seed"] % 10 != 9:
                    continue
                if args.group_by == "think":
                    speed[m["seed"]] = [1.0 / pl["think_ms"] for pl in m["players"]]
                    continue
                pieces = [0, 0]
                last = [None, None]
                for step in m["steps"]:
                    for i, key in enumerate(("p1", "p2")):
                        q = step[key]["state"]["queue"]
                        if q != last[i]:
                            pieces[i] += 1
                            last[i] = q
                seconds = max(1e-3, m["time_ms"] / 1000)
                speed[m["seed"]] = [k / seconds for k in pieces]
    data = tw.load_cached(ROOT / "runs" / args.timed_run / "dataset.npz", files, print, args.stride)
    test = data.split("test")
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    runs = args.models.split(",")
    preds = {}
    for run in runs:
        saved = torch.load(ROOT / "runs" / run / f"winprob_{run}.pt", map_location=device)
        net = wp.WinProbNet(**saved["config"]).to(device)
        net.load_state_dict(saved["state_dict"])
        preds[run] = tw.predict(net, test, device)

    seeds = np.array([test.matches[i]["seed"] for i in test.match])
    ratio = np.array([max(speed[s]) / max(min(speed[s]), 1e-9) for s in seeds])
    print(f"[속도] {args.group_by} 기준으로 묶는다")
    title = "실제 초당 블록 수 비율" if args.group_by == "pps" else "생각 지연 비율"
    paired = len(runs) >= 2
    print(f"| {title} | 판 | " + " | ".join(f"`{r}` log loss / ECE / 적중률" for r in runs)
          + (f" | log loss 차이 `{runs[1]}` − `{runs[0]}` (판 단위 95%)" if paired else "") + " |")
    print("|---|---|" + "---|" * len(runs) + ("---|" if paired else ""))
    rng = np.random.default_rng(0)
    groups = [(name, (ratio >= lo) & (ratio < hi)) for name, lo, hi in GROUPS] + [("전체", np.ones(len(ratio), bool))]
    for name, mask in groups:
        games = len(np.unique(test.match[mask]))
        cells = []
        for run in runs:
            ll, ece, acc = metrics(preds[run][mask], test.y[mask])
            cells.append(f"{ll:.3f} / {ece:.3f} / {acc:.3f}")
        if paired:
            pc = [np.clip(preds[r][mask], 1e-7, 1 - 1e-7) for r in runs[:2]]
            y = test.y[mask]
            gap = -(y * np.log(pc[1]) + (1 - y) * np.log(1 - pc[1])) + (y * np.log(pc[0]) + (1 - y) * np.log(1 - pc[0]))
            ids, inv = np.unique(test.match[mask], return_inverse=True)
            per_game, count = np.bincount(inv, gap, len(ids)), np.bincount(inv, minlength=len(ids)).astype(float)
            draws = [((k := np.bincount(rng.integers(0, len(ids), len(ids)), minlength=len(ids))) @ per_game) / (k @ count)
                     for _ in range(args.bootstrap)]
            cells.append(f"{gap.mean():+.3f} ({np.percentile(draws, 2.5):+.3f}~{np.percentile(draws, 97.5):+.3f})")
        print(f"| {name} | {games:,} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
