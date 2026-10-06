"""승률 예측망을 학습하고 평가해 ONNX 로 내보낸다 (docs/TRAINING.md 10장, MODEL_IO.md 9장).

    python python/tools/train_winprob.py --run wp01

자료는 make_winprob_data.py 가 만든 runs/<run>/data/chunk-*/w*.jsonl.gz 다. 판의 시드로 학습·검증·시험을 나누고
(tetris_ai.winprob.split_of), 검증 log loss 가 가장 낮은 반복의 가중치를 쓴다. 다른 분포 시험으로 가치망 학습 실행의
마지막 4세대 기록(기본 runs/r01/gen-076~079)도 평가한다. 결과:

    runs/<run>/winprob_<run>.onnx   내보낸 모델 (onnxruntime 으로 PyTorch 와 같은 값인지 확인함)
    runs/<run>/winprob_<run>.pt     체크포인트
    runs/<run>/metrics.json         모든 지표 (보정 표, 판의 단계별, 비교 기준, 부트스트랩 95% 범위)
    runs/<run>/train.log            진행 기록과 마크다운 표 (--eval-only 는 eval.log)
    runs/<run>/dataset.npz          읽은 자료의 캐시. external.npz 는 다른 분포 시험의 캐시다. 옆의 *.sources.json 에
                                    읽은 기록 파일 목록(경로, 크기, 수정 시각)을 적고, 목록이 다르면 다시 읽는다
"""

from __future__ import annotations

import argparse
import copy
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai import winprob as wp  # noqa: E402


class Log:
    def __init__(self, path: Path) -> None:
        self.f = path.open("w", encoding="utf-8")

    def __call__(self, *parts) -> None:
        line = " ".join(str(p) for p in parts)
        print(line, flush=True)
        self.f.write(line + "\n")
        self.f.flush()


def load_cached(cache: Path, files: list[Path], log: Log, timed_stride: int = 1) -> wp.Data:
    """기록 파일들을 읽는다. 같은 파일 목록(경로, 크기, 수정 시각)으로 만든 캐시가 있으면 그것을 쓴다. timed_stride 는
    실시간 기록(timed-1)을 판마다 몇 개에 하나씩 읽을지이고, 캐시 이름에 붙는다."""
    if timed_stride > 1:
        cache = cache.with_name(f"{cache.stem}_s{timed_stride}{cache.suffix}")
    if not files:
        raise SystemExit(f"기록 파일이 없음 ({cache.name})")
    sources = [[f.resolve().relative_to(ROOT).as_posix(), f.stat().st_size, f.stat().st_mtime_ns] for f in files]
    listing = cache.with_suffix(".sources.json")
    if cache.exists() and listing.exists() and json.loads(listing.read_text(encoding="utf-8")) == sources:
        log(f"[자료] 캐시 {cache.name}")
        return wp.Data.load(cache)
    start = time.time()
    data = wp.read_records(files, timed_stride)
    data.save(cache)
    listing.write_text(json.dumps(sources) + "\n", encoding="utf-8")
    log(f"[자료] 기록 {len(files)}개 파일 → {len(data.matches)}판, 표본 {len(data)}개 ({time.time() - start:.0f}초)")
    return data


def record_files(data_dir: Path, pattern: str) -> list[Path]:
    """data_dir 아래에서 pattern 에 맞는 기록 파일. 생성기의 묶음(chunk-*)은 summary.json 이 있는 것, 곧 끝까지 만든 것만
    쓴다. 도중에 멈춘 묶음의 파일은 gzip 이 잘려 있을 수 있다. 사람 판(games/)처럼 묶음이 아닌 폴더는 그대로 쓴다."""
    return [f for f in sorted(data_dir.glob(pattern))
            if not f.parent.name.startswith("chunk-") or (f.parent / "summary.json").exists()]


def describe(name: str, d: wp.Data) -> dict:
    ids = np.unique(d.match)
    results = [d.matches[i]["result"] for i in ids]
    turns = np.array([d.matches[i]["turns"] for i in ids])
    return {
        "name": name, "matches": int(len(ids)), "samples": int(len(d)),
        "p1_wins": results.count("p1"), "p2_wins": results.count("p2"), "draws": results.count("draw"),
        "turns_mean": float(turns.mean()), "turns_median": float(np.median(turns)), "turns_max": int(turns.max()),
    }


def to_device(d: wp.Data, device: torch.device) -> dict[str, torch.Tensor]:
    return {
        "b1": torch.from_numpy(d.b1).to(device), "f1": torch.from_numpy(d.f1).to(device),
        "b2": torch.from_numpy(d.b2).to(device), "f2": torch.from_numpy(d.f2).to(device),
        "y": torch.from_numpy(d.y).to(device),
    }


@torch.no_grad()
def predict(net: torch.nn.Module, d: wp.Data, device: torch.device, swap: bool = False, batch: int = 8192) -> np.ndarray:
    """표본마다 p1_win. swap 이면 P1 과 P2 를 바꿔 넣는다."""
    net.eval()
    out = np.zeros(len(d), np.float32)
    for s in range(0, len(d), batch):
        sl = slice(s, s + batch)
        a = (torch.from_numpy(d.b1[sl]).to(device).float(), torch.from_numpy(d.f1[sl]).to(device))
        b = (torch.from_numpy(d.b2[sl]).to(device).float(), torch.from_numpy(d.f2[sl]).to(device))
        x = (*b, *a) if swap else (*a, *b)
        out[sl] = torch.sigmoid(net(*x)).squeeze(1).cpu().numpy()
    return out


def train(args, train_d: wp.Data, val_d: wp.Data, device: torch.device, log: Log) -> tuple[torch.nn.Module, list[dict]]:
    torch.manual_seed(args.seed)
    rng = torch.Generator(device=device)
    rng.manual_seed(args.seed)
    net = wp.WinProbNet(dropout=args.dropout).to(device)
    log(f"[모델] WinProbNet 매개변수 {wp.parameter_count(net):,}개")
    opt = torch.optim.AdamW(net.parameters(), lr=args.lr, weight_decay=args.weight_decay)
    t = to_device(train_d, device)
    n = len(train_d)
    steps_per_epoch = math.ceil(n / args.batch)
    total = steps_per_epoch * args.epochs
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda k: 0.5 * (1 + math.cos(math.pi * min(k, total) / total)))
    history = []
    best, best_state = float("inf"), None
    for epoch in range(args.epochs):
        net.train()
        start = time.time()
        order = torch.randperm(n, device=device, generator=rng)
        total_loss = 0.0
        for s in range(0, n, args.batch):
            idx = order[s : s + args.batch]
            logit = net(t["b1"][idx].float(), t["f1"][idx], t["b2"][idx].float(), t["f2"][idx]).squeeze(1)
            loss = F.binary_cross_entropy_with_logits(logit, t["y"][idx])
            opt.zero_grad(set_to_none=True)
            loss.backward()
            opt.step()
            sched.step()
            total_loss += loss.item() * len(idx)
        p = predict(net, val_d, device)
        row = {"epoch": epoch + 1, "train_loss": total_loss / n, **wp.scores(p, val_d.y), "sec": time.time() - start}
        history.append(row)
        log(f"[학습] {epoch + 1:2d}/{args.epochs} 학습 손실 {row['train_loss']:.4f} | 검증 log loss {row['log_loss']:.4f} "
            f"Brier {row['brier']:.4f} ECE {row['ece']:.4f} | {row['sec']:.0f}초")
        if row["log_loss"] < best:
            best, best_state = row["log_loss"], copy.deepcopy(net.state_dict())
    net.load_state_dict(best_state)
    log(f"[학습] 검증 log loss 가 가장 낮은 반복: {min(history, key=lambda r: r['log_loss'])['epoch']}")
    return net, history


def logistic_baseline(train_d: wp.Data, device: torch.device) -> tuple[np.ndarray, np.ndarray]:
    """비교 기준: 최고 높이, 구멍 합, 울퉁불퉁함, 대기 가비지의 차이 4개로 맞춘 로지스틱 회귀 (TRAINING.md 10.5).

    로짓 = w · ((x₁ − x₂) / scale) 이라 승률 예측망처럼 자리 대칭이다(편향 없음). 돌려주는 값은 (w, scale) 이다.
    """
    x = wp.hand_features(train_d.b1, train_d.f1) - wp.hand_features(train_d.b2, train_d.f2)
    scale = x.std(axis=0) + 1e-6
    xt = torch.from_numpy(x / scale).to(device)
    yt = torch.from_numpy(train_d.y).to(device)
    w = torch.zeros(x.shape[1], device=device, requires_grad=True)
    opt = torch.optim.LBFGS([w], max_iter=200, line_search_fn="strong_wolfe")

    def closure():
        opt.zero_grad()
        loss = F.binary_cross_entropy_with_logits(xt @ w, yt)
        loss.backward()
        return loss

    opt.step(closure)
    return w.detach().cpu().numpy(), scale


def logistic_predict(w: np.ndarray, scale: np.ndarray, d: wp.Data) -> np.ndarray:
    x = wp.hand_features(d.b1, d.f1) - wp.hand_features(d.b2, d.f2)
    return 1.0 / (1.0 + np.exp(-((x / scale) @ w)))


def bootstrap(p: np.ndarray, y: np.ndarray, match: np.ndarray, reps: int, seed: int) -> dict:
    """판 단위 부트스트랩 95% 범위 (log loss, Brier, ECE). 한 판의 턴들은 서로 닮아서 판을 단위로 다시 뽑는다."""
    ids, inv = np.unique(match, return_inverse=True)
    m = len(ids)
    pc = np.clip(p.astype(np.float64), 1e-7, 1 - 1e-7)
    ll = -(y * np.log(pc) + (1 - y) * np.log(1 - pc))
    br = (p - y) ** 2
    bins = np.minimum((p * 10).astype(np.int64), 9)
    cnt = np.bincount(inv, minlength=m).astype(np.float64)
    sums = {"ll": np.bincount(inv, ll, m), "br": np.bincount(inv, br, m)}
    bin_n = np.zeros((m, 10))
    bin_p = np.zeros((m, 10))
    bin_y = np.zeros((m, 10))
    np.add.at(bin_n, (inv, bins), 1)
    np.add.at(bin_p, (inv, bins), p)
    np.add.at(bin_y, (inv, bins), y)
    rng = np.random.default_rng(seed)
    out = {"log_loss": [], "brier": [], "ece": []}
    for _ in range(reps):
        k = np.bincount(rng.integers(0, m, m), minlength=m).astype(np.float64)
        n = (k * cnt).sum()
        out["log_loss"].append((k * sums["ll"]).sum() / n)
        out["brier"].append((k * sums["br"]).sum() / n)
        bn, bp, by = k @ bin_n, k @ bin_p, k @ bin_y
        nz = bn > 0
        out["ece"].append(float(np.abs(bp[nz] - by[nz]).sum() / n))
    return {key: [float(np.percentile(v, 2.5)), float(np.percentile(v, 97.5))] for key, v in out.items()}


def pairing_masks(d: wp.Data, gens: list[int]) -> dict[str, np.ndarray] | None:
    """make_winprob_data.py 의 짝 짓기로 나눈 표본 표시 (TRAINING.md 10.6). 선수 이름이 그 형식이 아니면 None.

    팽팽한 짝(close): 같은 온도에서 명단(기준 AI, 세대 순서)의 이웃이거나 같은 선수. 나머지(other): 그 밖의 판.
    """
    order = ["baseline"] + [f"g{g:03d}" for g in gens]
    close, same = [], []
    for m in d.matches:
        try:
            (la, ta), (lb, tb) = (pl["ai"].split("@") for pl in m["players"])
            ia, ib = order.index(la), order.index(lb)
        except ValueError:
            return None
        close.append(ta == tb and abs(ia - ib) <= 1)
        same.append(m["players"][0]["ai"] == m["players"][1]["ai"])
    close_s = np.array(close)[d.match]
    return {"close": close_s, "same_player": np.array(same)[d.match], "other": ~close_s}


def evaluate(name: str, net: torch.nn.Module, d: wp.Data, device: torch.device, base, args,
             pairing: dict[str, np.ndarray] | None = None) -> dict:
    p = predict(net, d, device)
    q = predict(net, d, device, swap=True)
    decided = d.y != 0.5
    res = {
        "data": describe(name, d),
        "model": wp.scores(p, d.y),
        "accuracy": float(((p[decided] > 0.5) == (d.y[decided] > 0.5)).mean()),
        "symmetry_max_error": float(np.max(np.abs(p + q - 1))),
        "reliability": wp.reliability(p, d.y),
        "phase": wp.by_phase(p, d.y, d.turn, d.turns),
        "bootstrap95": bootstrap(p, d.y, d.match, args.bootstrap, args.seed),
        "constant": wp.scores(np.full(len(d), 0.5), d.y),
    }
    if base is not None:
        pb = logistic_predict(*base, d)
        res["logistic"] = {**wp.scores(pb, d.y), "phase": wp.by_phase(pb, d.y, d.turn, d.turns)}
    if pairing is not None:
        res["pairing"] = {
            k: {"matches": int(len(np.unique(d.match[mask]))), **wp.scores(p[mask], d.y[mask]),
                "reliability": wp.reliability(p[mask], d.y[mask])}
            for k, mask in pairing.items()
        }
    return res


def markdown(name: str, r: dict) -> list[str]:
    lines = [f"### {name}", "", f"판 {r['data']['matches']:,}개, 표본(턴) {r['data']['samples']:,}개, "
             f"P1 승 {r['data']['p1_wins']} / P2 승 {r['data']['p2_wins']} / 무승부 {r['data']['draws']}, "
             f"평균 {r['data']['turns_mean']:.1f}턴", ""]
    lines += ["| 모델 | log loss | Brier | ECE |", "|---|---|---|---|"]
    b = r["bootstrap95"]
    m = r["model"]
    lines.append(f"| 승률 예측망 | {m['log_loss']:.4f} ({b['log_loss'][0]:.4f}~{b['log_loss'][1]:.4f}) | "
                 f"{m['brier']:.4f} ({b['brier'][0]:.4f}~{b['brier'][1]:.4f}) | {m['ece']:.4f} ({b['ece'][0]:.4f}~{b['ece'][1]:.4f}) |")
    if "logistic" in r:
        lg = r["logistic"]
        lines.append(f"| 로지스틱 회귀 (특징 4개) | {lg['log_loss']:.4f} | {lg['brier']:.4f} | {lg['ece']:.4f} |")
    c = r["constant"]
    lines.append(f"| 늘 0.5 | {c['log_loss']:.4f} | {c['brier']:.4f} | — |")
    lines += ["", f"승패가 난 표본의 적중률 {r['accuracy']:.3f}, 자리 대칭 오차 최대 {r['symmetry_max_error']:.1e}", ""]
    lines += ["| 예측 구간 | 표본 | 평균 예측 | 실제 |", "|---|---|---|---|"]
    for row in r["reliability"]:
        if row["n"]:
            lines.append(f"| {row['lo']:.1f}~{row['hi']:.1f} | {row['n']:,} | {row['pred']:.3f} | {row['actual']:.3f} |")
    lines += ["", "| 턴 | 표본 | log loss | Brier | ECE |", "|---|---|---|---|---|"]
    for row in r["phase"]["turn"]:
        lines.append(f"| {row['bucket']} | {row['n']:,} | {row['log_loss']:.4f} | {row['brier']:.4f} | {row['ece']:.4f} |")
    lines += ["", "| 판 안의 위치 | 표본 | log loss | Brier | ECE |", "|---|---|---|---|---|"]
    for row in r["phase"]["relative"]:
        lines.append(f"| {row['bucket']} | {row['n']:,} | {row['log_loss']:.4f} | {row['brier']:.4f} | {row['ece']:.4f} |")
    if "logistic" in r:
        lines += ["", "로지스틱 회귀의 턴별 log loss: " + ", ".join(
            f"{row['bucket']} {row['log_loss']:.4f}" for row in r["logistic"]["phase"]["turn"])]
    if "pairing" in r:
        names = {"close": "팽팽한 짝 (같은 온도, 이웃 세대)", "same_player": "같은 선수끼리", "other": "나머지 (아무나 짝)"}
        lines += ["", "| 짝 짓기 | 판 | 표본 | log loss | Brier | ECE | 예측 → 실제 (표본 300개 이상인 구간) |",
                  "|---|---|---|---|---|---|---|"]
        for k, row in r["pairing"].items():
            rel = ", ".join(f"{b['pred']:.2f}→{b['actual']:.2f}" for b in row["reliability"] if b["n"] >= 300)
            lines.append(f"| {names[k]} | {row['matches']:,} | {row['n']:,} | {row['log_loss']:.4f} | {row['brier']:.4f} | "
                         f"{row['ece']:.4f} | {rel} |")
    return lines + [""]


def export_and_verify(net: torch.nn.Module, path: Path, d: wp.Data, args, log: Log) -> float:
    import onnxruntime as ort

    module = wp.ProbModule(copy.deepcopy(net).cpu().eval())
    wp.export_onnx(module, path, {
        "tetris.stage": args.run,
        "tetris.description": f"승률 예측망 (TRAINING.md 10장), 실행 {args.run}, 자료 {len(d.matches)}판",
    })
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    rng = np.random.default_rng(args.seed)
    idx = np.sort(rng.choice(len(d), size=min(2048, len(d)), replace=False))
    inputs = {"p1_board": d.b1[idx].astype(np.float32), "p1_features": d.f1[idx],
              "p2_board": d.b2[idx].astype(np.float32), "p2_features": d.f2[idx]}
    (got,) = session.run([wp.OUTPUT_NAME], inputs)
    with torch.no_grad():
        want = module(*(torch.from_numpy(inputs[k]) for k in wp.INPUT_NAMES)).numpy()
    diff = float(np.max(np.abs(got - want)))
    meta = session.get_modelmeta().custom_metadata_map
    log(f"[내보내기] {path.relative_to(ROOT)} ({path.stat().st_size:,} bytes), 꼬리표 {meta.get('tetris.model_io')}/"
        f"{meta.get('tetris.rules')}, onnxruntime 과 PyTorch 의 최대 차이 {diff:.2e} (표본 {len(idx)}개)")
    if got.shape != (len(idx), 1) or diff > 1e-5:
        raise SystemExit("내보낸 모델이 PyTorch 와 다름")
    return diff


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", default="wp01", help="결과 폴더 runs/<run>")
    p.add_argument("--data-run", default=None, help="자료를 읽을 실행 (기본: --run). 설정을 바꿔 비교할 때 자료를 함께 쓴다")
    p.add_argument("--data-stride", type=int, default=1,
                   help="주 자료가 실시간 기록(timed-1)이면 판마다 표본을 이만큼에 하나씩 쓴다. 실시간 판만으로 학습할 때 3 "
                        "(TRAINING.md 10.10). 블록 동기 기록(match-1)에는 영향이 없다")
    p.add_argument("--extra-data", action="append", default=[], metavar="RUN",
                   help="자료를 더할 실행 (runs/<RUN>/data/*/*.jsonl.gz, 예: 실시간 자료 wpt01, 사람 판 human). 여러 번 줄 수 있다")
    p.add_argument("--extra-stride", type=int, default=3,
                   help="더한 자료는 판마다 표본을 이만큼에 하나씩만 쓴다 (실시간 기록은 고정마다 상태가 있어 많다)")
    p.add_argument("--external", default="runs/r01/gen-07[6-9]/matches/*.jsonl.gz",
                   help="다른 분포 시험 기록 (저장소 기준 glob). 빈 문자열이면 건너뜀")
    p.add_argument("--epochs", type=int, default=2, help="학습 자료를 도는 횟수 (TRAINING.md 10.4)")
    p.add_argument("--batch", type=int, default=1024)
    p.add_argument("--lr", type=float, default=1e-3)
    p.add_argument("--weight-decay", type=float, default=1e-4)
    p.add_argument("--dropout", type=float, default=0.0, help="학습 중 dropout 비율 (WinProbNet)")
    p.add_argument("--bootstrap", type=int, default=200, help="부트스트랩 반복 수")
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--eval-only", action="store_true",
                   help="학습하지 않고 runs/<run>/winprob_<run>.pt 를 다시 평가·내보내기만 한다")
    args = p.parse_args()

    run_dir = ROOT / "runs" / args.run
    data_dir = ROOT / "runs" / (args.data_run or args.run)
    run_dir.mkdir(parents=True, exist_ok=True)
    log = Log(run_dir / ("eval.log" if args.eval_only else "train.log"))  # 평가만 할 때는 학습 기록을 덮어쓰지 않는다
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    torch.backends.cudnn.benchmark = True
    log(f"[설정] {json.dumps(vars(args), ensure_ascii=False)} | 장치 {device}")

    data = load_cached(data_dir / "dataset.npz", record_files(data_dir / "data", "chunk-*/w*.jsonl.gz"), log,
                       args.data_stride)
    extras = []
    for extra_run in args.extra_data:
        extra_dir = ROOT / "runs" / extra_run
        # 생성기의 묶음(chunk-*/w*.jsonl.gz)과 게임이 남긴 사람 판(games/*.jsonl.gz)을 모두 읽는다
        extra = load_cached(extra_dir / "dataset.npz", record_files(extra_dir / "data", "*/*.jsonl.gz"), log,
                            args.extra_stride)
        log(f"[자료] 더한 자료 {extra_run}: {len(extra.matches)}판, 표본 {len(extra)}개 (판마다 {args.extra_stride}개에 하나)")
        extras.append(extra)
    timed_test = wp.Data.concat(extras).split("test") if extras else None
    if extras:
        data = wp.Data.concat([data] + extras)
    splits = {name: data.split(name) for name in wp.SPLITS}
    for name, d in splits.items():
        log(f"[자료] {name}: {json.dumps(describe(name, d), ensure_ascii=False)}")
    external = None
    if args.external:
        files = sorted(ROOT.glob(args.external))
        external = load_cached(data_dir / "external.npz", files, log) if files else None

    checkpoint = run_dir / f"winprob_{args.run}.pt"
    train_config = vars(args)
    if args.eval_only:
        saved = torch.load(checkpoint, map_location=device)
        net = wp.WinProbNet(**saved["config"]).to(device)
        net.load_state_dict(saved["state_dict"])
        train_config = saved["args"]
        old = run_dir / "metrics.json"
        old = json.loads(old.read_text(encoding="utf-8")) if old.exists() else {}
        history, train_sec = old.get("history", []), old.get("train_seconds", 0.0)
        log(f"[모델] {checkpoint.relative_to(ROOT)} 를 다시 평가한다 (학습 설정 {json.dumps(train_config, ensure_ascii=False)})")
    else:
        start = time.time()
        net, history = train(args, splits["train"], splits["val"], device, log)
        train_sec = time.time() - start
    base = logistic_baseline(splits["train"], device)
    log(f"[비교 기준] 로지스틱 회귀 가중치(정규화한 차이: 최고 높이, 구멍, 울퉁불퉁함, 대기 가비지) "
        f"{np.round(base[0], 3).tolist()}")

    results = {"config": train_config, "eval_config": vars(args), "parameters": wp.parameter_count(net),
               "train_seconds": train_sec, "history": history, "data": {k: describe(k, d) for k, d in splits.items()}}
    data_config = data_dir / "data" / "config.json"
    # 블록 동기 자료(make_winprob_data.py)의 선수 세대 목록. 실시간 자료(make_timed_data.py)의 설정에는 없다
    gens_text = json.loads(data_config.read_text(encoding="utf-8")).get("gens", "") if data_config.exists() else ""
    gens = [int(g) for g in gens_text.split(",") if g]
    results["val"] = evaluate("val", net, splits["val"], device, base, args)
    results["test"] = evaluate("test", net, splits["test"], device, base, args, pairing_masks(splits["test"], gens))
    if external is not None:
        results["external"] = evaluate("external", net, external, device, base, args)
    if timed_test is not None:
        results["test_extra"] = evaluate("test_extra", net, timed_test, device, base, args)
    model_path = run_dir / f"winprob_{args.run}.onnx"
    results["export_max_diff"] = export_and_verify(net, model_path, splits["test"], args, log)
    if not args.eval_only:
        torch.save({"state_dict": net.state_dict(), "config": net.config, "args": vars(args)}, checkpoint)
    (run_dir / "metrics.json").write_text(json.dumps(results, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    for key, title in (("test", "시험 (판 단위로 나눈 시험 판)"), ("test_extra", "더한 자료의 시험 판 (실시간)"),
                       ("external", "다른 분포 시험 (r01 세대 76~79 기록)")):
        if key in results:
            for line in markdown(title, results[key]):
                log(line)
    log(f"[끝] 학습 {train_sec:.0f}초" + (" (이번에는 평가만)" if args.eval_only else ""))


if __name__ == "__main__":
    main()
