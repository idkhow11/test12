"""승률 예측망 (docs/MODEL_IO.md 9장 형식 winprob-1, docs/TRAINING.md 10장).

두 플레이어의 결정 시점 상태(보드, 지금 블록, 미리보기, 홀드, 콤보, B2B, 대기 가비지)로 P1 의 기대 점수
p = P(P1 승) + 0.5·P(무승부) 를 낸다. 입력을 만들 때는 정수를 float32 로 바꾸는 것 말고는 계산하지 않고(정규화와
보드 통계는 모델 안에서), Java 쪽 kr.ac.jbnu.se.tetris.ai.WinRate 와 같은 값을 만들어야 한다(fixtures/winprob).

인코더는 numpy 만 쓴다. 모델·내보내기(WinProbNet, export_onnx)는 PyTorch 가 있을 때만 쓸 수 있다.
"""

from __future__ import annotations

import gzip
import json
import warnings
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np

from .model_io import CELL_CHARS, EMPTY_BOARD, HEIGHT, PIECE_SET, PIECES, QUEUE_SLOTS, RULES_VERSION, WIDTH, pad_board
from .records import FORMAT as MATCH_FORMAT, TIMED_FORMAT, check_header, rows_to_board

FORMAT = "winprob-1"
FEATURES = 52

# features 의 위치 (MODEL_IO.md 9.4)
CURRENT = 0
QUEUE_OFFSET = CURRENT + len(PIECES)  # 7
HOLD_OFFSET = QUEUE_OFFSET + QUEUE_SLOTS * len(PIECES)  # 42
COMBO = HOLD_OFFSET + len(PIECES)  # 49
B2B = COMBO + 1  # 50
PENDING = B2B + 1  # 51
assert PENDING + 1 == FEATURES

INPUT_NAMES = ("p1_board", "p1_features", "p2_board", "p2_features")
OUTPUT_NAME = "p1_win"
OPSET = 17

SPLITS = ("train", "val", "test")


def split_of(seed: int) -> str:
    """판 단위 나누기 (TRAINING.md 10.1): 시드의 끝자리 0~7 학습, 8 검증, 9 시험."""
    d = int(seed) % 10
    return "test" if d == 9 else "val" if d == 8 else "train"


# ---------------------------------------------------------------------- 상태와 인코더


@dataclass(frozen=True)
class PlayerState:
    """한 플레이어의 결정 시점 상태 (MODEL_IO.md 9.2). Java ai.PlayerState 와 같다."""

    board: tuple[str, ...]  # 24 개 문자열, 각 10 글자 (FIXTURES.md 4장 표기)
    current: str
    queue: tuple[str, ...] = ()
    hold: str | None = None
    combo: int = -1
    b2b: bool = False
    pending: int = 0

    def __post_init__(self) -> None:
        # Java(PlayerState, FixtureTest)가 거부하는 입력은 여기서도 거부한다.
        if len(self.board) != HEIGHT or any(len(row) != WIDTH for row in self.board):
            raise ValueError(f"board 는 {HEIGHT} 행 x {WIDTH} 열이어야 함")
        bad = {ch for row in self.board for ch in row} - CELL_CHARS
        if bad:
            raise ValueError(f"보드에 알 수 없는 칸 문자: {sorted(bad)}")
        if len(self.queue) > QUEUE_SLOTS:
            raise ValueError(f"queue 는 최대 {QUEUE_SLOTS} 개")
        if self.current not in PIECE_SET:
            raise ValueError(f"알 수 없는 current: {self.current!r}")
        for p in (*self.queue, self.hold):
            if p is not None and p not in PIECE_SET:
                raise ValueError(f"알 수 없는 블록: {p!r}")
        if self.combo < -1 or self.pending < 0:
            raise ValueError("combo >= -1, pending >= 0 이어야 함")


def state_from_json(d: dict) -> PlayerState:
    """픽스처의 상태 객체를 읽는다 (FIXTURES.md 5.11). queue 는 블록 문자의 배열이어야 한다(Java FixtureTest 와 같다)."""
    if not isinstance(d.get("queue", []), list):
        raise ValueError(f"queue 는 배열이어야 함: {d.get('queue')!r}")
    return PlayerState(
        board=pad_board(d.get("board_bottom", ()), d.get("board_top", ())),
        current=d["current"],
        queue=tuple(d.get("queue", ())),
        hold=d.get("hold"),
        combo=d.get("combo", -1),
        b2b=d.get("b2b", False),
        pending=d.get("pending", 0),
    )


def state_from_record(s: dict) -> PlayerState:
    """기록 match-1 의 state 객체(TRAINING.md 7.3)를 읽는다. 픽스처의 encode_record 사례가 쓰는 경로이고,
    학습 자료는 read_records 가 같은 값을 판 단위로 한꺼번에 만든다(test_winprob 가 두 경로를 비교한다)."""
    board = rows_to_board(s["rows"])
    return PlayerState(
        board=tuple("".join("#" if v else "." for v in row) for row in board),
        current=s["current"],
        queue=tuple(s["queue"]),
        hold=s["hold"],
        combo=s["combo"],
        b2b=s["b2b"],
        pending=s["pending"],
    )


def _features(current: str, queue: Iterable[str], hold: str | None, combo: int, b2b: bool, pending: int) -> np.ndarray:
    f = np.zeros(FEATURES, dtype=np.float32)
    f[CURRENT + PIECES.index(current)] = 1
    queue = list(queue)
    if len(queue) > QUEUE_SLOTS:  # Java PlayerState 처럼 거부한다. 넣는 쪽이 앞 5 개만 넣는다 (MODEL_IO.md 9.4)
        raise ValueError(f"queue 는 최대 {QUEUE_SLOTS} 개: {queue}")
    for k, piece in enumerate(queue):
        f[QUEUE_OFFSET + 7 * k + PIECES.index(piece)] = 1
    if hold is not None:
        f[HOLD_OFFSET + PIECES.index(hold)] = 1
    f[COMBO] = combo + 1
    f[B2B] = 1 if b2b else 0
    f[PENDING] = pending
    return f


def encode_board(s: PlayerState) -> np.ndarray:
    """보드 텐서 한 개: float32 [24, 10], 채워진 칸 1.0 (MODEL_IO.md 9.3)."""
    return np.array([[0.0 if ch == "." else 1.0 for ch in row] for row in s.board], dtype=np.float32)


def encode_features(s: PlayerState) -> np.ndarray:
    """특징 텐서 한 개: float32 [52] (MODEL_IO.md 9.4)."""
    return _features(s.current, s.queue, s.hold, s.combo, s.b2b, s.pending)


def encode_record(s: dict) -> tuple[np.ndarray, np.ndarray]:
    """기록의 state 객체 → (보드 uint8 [24, 10], 특징 float32 [52]). PlayerState 를 거치지 않는 경로다."""
    return rows_to_board(s["rows"]), _features(s["current"], s["queue"], s["hold"], s["combo"], s["b2b"], s["pending"])


def encode_pairs(pairs: Sequence[tuple[PlayerState, PlayerState]]) -> dict[str, np.ndarray]:
    """상태 쌍 N 개 → 모델 입력 네 개 (MODEL_IO.md 9.3)."""
    n = len(pairs)
    out = {
        "p1_board": np.zeros((n, HEIGHT, WIDTH), np.float32),
        "p1_features": np.zeros((n, FEATURES), np.float32),
        "p2_board": np.zeros((n, HEIGHT, WIDTH), np.float32),
        "p2_features": np.zeros((n, FEATURES), np.float32),
    }
    for i, (a, b) in enumerate(pairs):
        out["p1_board"][i], out["p1_features"][i] = encode_board(a), encode_features(a)
        out["p2_board"][i], out["p2_features"][i] = encode_board(b), encode_features(b)
    return out


EMPTY_STATE = PlayerState(board=EMPTY_BOARD, current="T")


# ---------------------------------------------------------------------- 학습 자료


@dataclass
class Data:
    """턴 단위 표본. 표본 i 는 판 match[i] 의 턴 turn[i] 에서 두 플레이어의 상태다."""

    b1: np.ndarray  # uint8 [M, 24, 10]
    f1: np.ndarray  # float32 [M, 52]
    b2: np.ndarray
    f2: np.ndarray
    y: np.ndarray  # float32 [M] P1 승 1, 무승부 0.5, P2 승 0
    turn: np.ndarray  # int32 [M] 턴 번호 (0 부터)
    match: np.ndarray  # int32 [M] 판 번호 (matches 의 위치)
    matches: list[dict] = field(default_factory=list)  # 판마다 seed, turns, result, players(이름·모델·온도)

    def __len__(self) -> int:
        return len(self.y)

    @property
    def turns(self) -> np.ndarray:
        """표본마다 그 판의 전체 턴 수."""
        return np.array([m["turns"] for m in self.matches], dtype=np.int32)[self.match]

    def subset(self, mask: np.ndarray) -> "Data":
        """표본 mask 만 남긴다. 판 목록은 그대로 둔다(match 번호가 바뀌지 않게)."""
        return Data(self.b1[mask], self.f1[mask], self.b2[mask], self.f2[mask], self.y[mask], self.turn[mask],
                    self.match[mask], self.matches)

    def split(self, name: str) -> "Data":
        seeds = np.array([m["seed"] for m in self.matches], dtype=np.int64)
        in_split = np.array([split_of(s) == name for s in seeds], dtype=bool)
        return self.subset(in_split[self.match])

    def save(self, path: Path) -> None:
        np.savez(path, b1=self.b1, f1=self.f1, b2=self.b2, f2=self.f2, y=self.y, turn=self.turn, match=self.match,
                 matches=np.array(json.dumps(self.matches, ensure_ascii=False)))

    @staticmethod
    def load(path: Path) -> "Data":
        z = np.load(path)
        return Data(z["b1"], z["f1"], z["b2"], z["f2"], z["y"], z["turn"], z["match"], json.loads(str(z["matches"])))

    @staticmethod
    def concat(parts: Sequence["Data"]) -> "Data":
        """여러 자료를 하나로 잇는다. 판 번호는 앞 자료의 판 수만큼 밀어 겹치지 않게 한다."""
        offsets = np.cumsum([0] + [len(d.matches) for d in parts[:-1]])
        matches = [m for d in parts for m in d.matches]
        return Data(np.concatenate([d.b1 for d in parts]), np.concatenate([d.f1 for d in parts]),
                    np.concatenate([d.b2 for d in parts]), np.concatenate([d.f2 for d in parts]),
                    np.concatenate([d.y for d in parts]), np.concatenate([d.turn for d in parts]),
                    np.concatenate([d.match + off for d, off in zip(parts, offsets)]).astype(np.int32), matches)

    def thin(self, stride: int) -> "Data":
        """판마다 표본을 stride 개에 하나씩만 남긴다(턴 번호가 stride 의 배수인 것). 1 이면 그대로."""
        return self if stride <= 1 else self.subset(self.turn % stride == 0)


RESULT_VALUE = {"p1": 1.0, "draw": 0.5, "p2": 0.0}


def read_records(paths: Iterable[Path], timed_stride: int = 1) -> Data:
    """match-1(블록 동기)·timed-1(가상 시간) 기록 파일들 → 표본. 모든 판, 양쪽 플레이어, 모든 턴(timed-1 은 고정이 있던
    모든 순간)을 쓴다 (TRAINING.md 10.1, 10.9). timed-1 은 판마다 timed_stride 개에 하나만 읽어 메모리를 아낀다(턴 번호는
    원래 순번을 그대로 둔다)."""
    b1, f1, b2, f2, ys, turns, mids = [], [], [], [], [], [], []
    matches: list[dict] = []
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as fh:
            for line in fh:
                m = json.loads(line)
                check_header(m, (MATCH_FORMAT, TIMED_FORMAT))
                k = len(matches)
                matches.append({
                    "seed": m["seed"], "turns": m["turns"], "result": m["result"], "file": Path(path).name,
                    "players": [{"ai": p["ai"], "model": p["model"], "temperature": p["temperature"]} for p in m["players"]],
                })
                stride = timed_stride if m.get("format") == TIMED_FORMAT else 1
                index = np.arange(0, len(m["steps"]), max(1, stride), dtype=np.int32)
                if len(index) == 0:
                    continue
                steps = [m["steps"][i] for i in index]
                y = RESULT_VALUE[m["result"]]
                for side, (bs, fs) in (("p1", (b1, f1)), ("p2", (b2, f2))):
                    rows = np.array([s[side]["state"]["rows"] for s in steps], dtype=np.int64)
                    bs.append(((rows[:, :, None] >> np.arange(WIDTH)) & 1).astype(np.uint8))
                    fs.append(np.stack([
                        _features(st["current"], st["queue"], st["hold"], st["combo"], st["b2b"], st["pending"])
                        for st in (s[side]["state"] for s in steps)
                    ]))
                ys.append(np.full(len(index), y, np.float32))
                turns.append(index)
                mids.append(np.full(len(index), k, np.int32))
    if not ys:
        raise ValueError("기록에 표본이 없음")
    return Data(np.concatenate(b1), np.concatenate(f1), np.concatenate(b2), np.concatenate(f2), np.concatenate(ys),
                np.concatenate(turns), np.concatenate(mids), matches)


# ---------------------------------------------------------------------- 평가 지표 (TRAINING.md 10.5)


def log_loss(p: np.ndarray, y: np.ndarray, eps: float = 1e-7) -> float:
    """−[y ln p + (1 − y) ln(1 − p)] 의 평균 (자연로그). 무승부(y = 0.5)도 같은 식이다."""
    p = np.clip(np.asarray(p, np.float64), eps, 1 - eps)
    y = np.asarray(y, np.float64)
    return float(np.mean(-(y * np.log(p) + (1 - y) * np.log(1 - p))))


def brier(p: np.ndarray, y: np.ndarray) -> float:
    """(p − y)² 의 평균."""
    return float(np.mean((np.asarray(p, np.float64) - np.asarray(y, np.float64)) ** 2))


def reliability(p: np.ndarray, y: np.ndarray, bins: int = 10) -> list[dict]:
    """보정 표: 예측을 [0, 1/bins), …, [(bins−1)/bins, 1] 구간으로 나눠 구간마다 표본 수, 평균 예측, 실제 결과 평균."""
    p = np.asarray(p, np.float64)
    y = np.asarray(y, np.float64)
    idx = np.minimum((p * bins).astype(np.int64), bins - 1)
    out = []
    for b in range(bins):
        sel = idx == b
        n = int(sel.sum())
        out.append({
            "lo": b / bins, "hi": (b + 1) / bins, "n": n,
            "pred": float(p[sel].mean()) if n else float("nan"),
            "actual": float(y[sel].mean()) if n else float("nan"),
        })
    return out


def ece(p: np.ndarray, y: np.ndarray, bins: int = 10) -> float:
    """보정 표 구간들의 |평균 예측 − 실제| 를 표본 수로 가중 평균한 값."""
    table = reliability(p, y, bins)
    total = sum(r["n"] for r in table)
    return float(sum(r["n"] * abs(r["pred"] - r["actual"]) for r in table if r["n"]) / max(total, 1))


def scores(p: np.ndarray, y: np.ndarray) -> dict:
    return {"n": int(len(y)), "log_loss": log_loss(p, y), "brier": brier(p, y), "ece": ece(p, y)}


TURN_BUCKETS = ((0, 10), (10, 20), (20, 40), (40, 80), (80, None))


def by_phase(p: np.ndarray, y: np.ndarray, turn: np.ndarray, turns: np.ndarray) -> dict:
    """판의 단계별 지표: 턴 구간(TURN_BUCKETS)과 판 안의 상대 위치(4등분)."""
    out = {"turn": [], "relative": []}
    for lo, hi in TURN_BUCKETS:
        sel = (turn >= lo) & ((turn < hi) if hi is not None else True)
        if sel.any():
            out["turn"].append({"bucket": f"{lo}-{hi - 1}" if hi is not None else f"{lo}+", **scores(p[sel], y[sel])})
    rel = turn / np.maximum(turns, 1)
    for q in range(4):
        sel = (rel >= q / 4) & (rel < (q + 1) / 4)
        if sel.any():
            out["relative"].append({"bucket": f"{25 * q}-{25 * (q + 1)}%", **scores(p[sel], y[sel])})
    return out


def hand_features(board: np.ndarray, f: np.ndarray) -> np.ndarray:
    """비교 기준(로지스틱 회귀)의 특징: 최고 높이, 구멍 합, 울퉁불퉁함, 대기 가비지 [M, 4]. 정의는 TRAINING.md 3.3."""
    covered = np.cumsum(board, axis=1) > 0
    heights = covered.sum(axis=1)
    holes = (covered & (board == 0)).sum(axis=(1, 2))
    bump = np.abs(np.diff(heights, axis=1)).sum(axis=1)
    return np.stack([heights.max(axis=1), holes, bump, f[:, PENDING]], axis=1).astype(np.float32)


# ---------------------------------------------------------------------- 모델 (PyTorch)

try:
    import torch
    from torch import nn
except ImportError:  # 게임 실행이나 픽스처 확인에는 필요 없다
    torch = None
    nn = None

STATS = 24  # 보드 통계 개수 (TRAINING.md 3.3)


if torch is not None:

    def board_stats(board: "torch.Tensor") -> "torch.Tensor":
        """보드 [N, 24, 10] (0/1) → 통계 [N, 24]: 열 높이 10, 열별 구멍 10, 최고 높이, 구멍 합, 울퉁불퉁함, 구멍 있는 줄.

        정의는 TRAINING.md 3.3 으로 tetris_ai.net.board_stats 와 같다. 가치망 코드가 바뀌어도 이 형식의 모델이
        흔들리지 않도록 따로 둔다.
        """
        covered = (torch.cumsum(board, dim=1) > 0).to(board.dtype)
        heights = covered.sum(dim=1)
        hole_cells = covered - board
        holes = hole_cells.sum(dim=1)
        max_height = heights.amax(dim=1, keepdim=True)
        total_holes = holes.sum(dim=1, keepdim=True)
        bumpiness = (heights[:, 1:] - heights[:, :-1]).abs().sum(dim=1, keepdim=True)
        rows_with_holes = (hole_cells.sum(dim=2) > 0).to(board.dtype).sum(dim=1, keepdim=True)
        return torch.cat([heights, holes, max_height, total_holes, bumpiness, rows_with_holes], dim=1)

    class PlayerEncoder(nn.Module):
        """한 플레이어의 상태 → 인코딩 [N, out_dim]. 두 플레이어가 같은 가중치를 쓴다 (TRAINING.md 10.3)."""

        def __init__(self, channels=(16, 32, 32), board_hidden=128, feat_hidden=64, out_dim=128) -> None:
            super().__init__()
            c1, c2, c3 = channels
            self.conv = nn.Sequential(
                nn.Conv2d(1, c1, 3, padding=1),
                nn.ReLU(),
                nn.Conv2d(c1, c2, 3, padding=1),
                nn.ReLU(),
                nn.Conv2d(c2, c3, 3, stride=(2, 1), padding=1),
                nn.ReLU(),
                nn.Conv2d(c3, 8, 1),
                nn.ReLU(),
            )
            self.board_fc = nn.Sequential(nn.Linear(8 * (HEIGHT // 2) * WIDTH, board_hidden), nn.ReLU())
            self.feat_fc = nn.Sequential(nn.Linear(FEATURES + STATS, feat_hidden), nn.ReLU())
            self.out = nn.Sequential(nn.Linear(board_hidden + feat_hidden, out_dim), nn.ReLU())
            # 입력 정규화 상수. 원-핫과 0/1 은 그대로, 개수는 대략 0~1 범위가 되도록 나눈다.
            f_scale = torch.ones(FEATURES)
            f_scale[COMBO] = 0.1
            f_scale[PENDING] = 0.1
            self.register_buffer("f_scale", f_scale)
            self.register_buffer(
                "s_scale", torch.cat([torch.full((20,), 1.0 / HEIGHT), torch.tensor([1.0 / HEIGHT, 0.02, 0.02, 1.0 / HEIGHT])])
            )

        def forward(self, board: "torch.Tensor", features: "torch.Tensor") -> "torch.Tensor":
            b = self.board_fc(self.conv(board.unsqueeze(1)).flatten(1))
            f = self.feat_fc(torch.cat([features * self.f_scale, board_stats(board) * self.s_scale], dim=1))
            return self.out(torch.cat([b, f], dim=1))

    class WinProbNet(nn.Module):
        """P1 이 이길 로짓 = g(e₁, e₂) − g(e₂, e₁) (TRAINING.md 10.3). 자리를 바꾸면 부호만 바뀐다.

        dropout 은 학습 중에만 쓰는 정규화다(플레이어 인코딩과 머리의 숨은 층). 평가·내보내기(eval)에서는 없는 것과 같아
        자리 대칭이 정확히 지켜진다. 0 이면 층을 만들지 않는다.
        """

        def __init__(self, channels=(16, 32, 32), board_hidden=128, feat_hidden=64, player_dim=128,
                     head_hidden=(128, 64), dropout: float = 0.0) -> None:
            super().__init__()
            self.config = dict(channels=list(channels), board_hidden=board_hidden, feat_hidden=feat_hidden,
                               player_dim=player_dim, head_hidden=list(head_hidden), dropout=dropout)
            self.encoder = PlayerEncoder(channels, board_hidden, feat_hidden, player_dim)
            h1, h2 = head_hidden

            def drop() -> list:
                return [nn.Dropout(dropout)] if dropout > 0 else []

            self.drop = nn.Dropout(dropout) if dropout > 0 else nn.Identity()
            self.head = nn.Sequential(nn.Linear(2 * player_dim, h1), nn.ReLU(), *drop(), nn.Linear(h1, h2), nn.ReLU(), *drop(),
                                      nn.Linear(h2, 1))

        def forward(self, p1_board, p1_features, p2_board, p2_features):
            """로짓 [N, 1]. 확률은 sigmoid(로짓)이다."""
            e1 = self.drop(self.encoder(p1_board, p1_features))
            e2 = self.drop(self.encoder(p2_board, p2_features))
            return self.head(torch.cat([e1, e2], dim=1)) - self.head(torch.cat([e2, e1], dim=1))

    class ProbModule(nn.Module):
        """ONNX 로 내보내는 모양: 입력 네 개 → p1_win [N, 1] (MODEL_IO.md 9.5)."""

        def __init__(self, net: nn.Module) -> None:
            super().__init__()
            self.net = net

        def forward(self, p1_board, p1_features, p2_board, p2_features):
            return torch.sigmoid(self.net(p1_board, p1_features, p2_board, p2_features))

    def export_onnx(module: nn.Module, path: Path, metadata: dict[str, str]) -> None:
        """MODEL_IO.md 9.5 대로 내보내고 꼬리표를 단다. 입력 네 개와 출력 p1_win [N, 1], N 은 dynamic axis."""
        import onnx  # 학습 쪽에서만 필요하다

        module = module.eval().cpu()
        n = 3
        dummy = (torch.zeros(n, HEIGHT, WIDTH), torch.zeros(n, FEATURES), torch.zeros(n, HEIGHT, WIDTH),
                 torch.zeros(n, FEATURES))
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp.onnx")
        # net.export_onnx 와 같은 이유로 옛 내보내기(TorchScript)를 쓴다(opset 17, TRAINING.md 12장).
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            torch.onnx.export(
                module,
                dummy,
                str(tmp),
                input_names=list(INPUT_NAMES),
                output_names=[OUTPUT_NAME],
                dynamic_axes={name: {0: "N"} for name in (*INPUT_NAMES, OUTPUT_NAME)},
                opset_version=OPSET,
                dynamo=False,
            )
        model = onnx.load(str(tmp))
        tmp.unlink()
        del model.metadata_props[:]
        for k, v in {"tetris.model_io": FORMAT, "tetris.rules": RULES_VERSION, **metadata}.items():
            entry = model.metadata_props.add()
            entry.key, entry.value = k, str(v)
        onnx.checker.check_model(model)
        onnx.save(model, str(path))

    def parameter_count(net: nn.Module) -> int:
        return sum(p.numel() for p in net.parameters())
