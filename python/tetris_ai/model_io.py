"""학습 모델 입력 형식 (docs/MODEL_IO.md, 형식 버전 2).

Java 쪽 kr.ac.jbnu.se.tetris.ai.FeatureEncoder 와 같은 값을 만들어야 한다.
입력을 만들 때는 정수를 float32 로 바꾸는 것 말고는 계산하지 않는다(정규화는 모델 안에서).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Sequence

import numpy as np

MODEL_IO_VERSION = "2"
RULES_VERSION = "0.3"

HEIGHT = 24
WIDTH = 10
PIECES = "IJLOSTZ"  # RULES 5.3 기준 순서, Java PieceType 선언 순서
PIECE_SET = frozenset(PIECES)  # "IJ" 같은 부분 문자열이 통과하지 않도록 한 글자씩 비교한다
CELL_CHARS = frozenset(".#G" + PIECES)
QUEUE_SLOTS = 5
FEATURES = 48
OPP_FEATURES = 10

# features 의 위치 (MODEL_IO.md 3.2)
QUEUE_OFFSET = 0
HOLD_OFFSET = QUEUE_SLOTS * len(PIECES)  # 35
COMBO = HOLD_OFFSET + len(PIECES)  # 42
B2B = COMBO + 1  # 43
LINES = B2B + 1  # 44
ATTACK = LINES + 1  # 45
PENDING = ATTACK + 1  # 46
GARBAGE_RISES = PENDING + 1  # 47
assert GARBAGE_RISES + 1 == FEATURES

# opp_features 의 위치 (MODEL_IO.md 3.4)
OPP_PENDING = 0
OPP_COMBO = 1
OPP_B2B = 2
OPP_HOLD = 3
assert OPP_HOLD + len(PIECES) == OPP_FEATURES

EMPTY_BOARD: tuple[str, ...] = ("." * WIDTH,) * HEIGHT


def _check_board(board: Sequence[str]) -> None:
    if len(board) != HEIGHT or any(len(row) != WIDTH for row in board):
        raise ValueError(f"board 는 {HEIGHT} 행 x {WIDTH} 열이어야 함")
    bad = {ch for row in board for ch in row} - CELL_CHARS
    if bad:
        raise ValueError(f"보드에 알 수 없는 칸 문자: {sorted(bad)}")


def _check_piece(p: str | None) -> None:
    if p is not None and p not in PIECE_SET:
        raise ValueError(f"알 수 없는 블록: {p!r}")


@dataclass(frozen=True)
class Afterstate:
    """블록을 놓고 RULES 7장 1~7단계까지 진행한 상태 (MODEL_IO.md 2.1)."""

    board: tuple[str, ...]  # 24 개 문자열, 각 10 글자. '.' 빈칸, IJLOSTZ 블록, G 가비지, # 종류 무관 (FIXTURES.md 4장)
    queue: tuple[str, ...] = ()
    hold: str | None = None
    combo: int = -1
    b2b: bool = False
    lines: int = 0
    attack: int = 0
    pending: int = 0

    def __post_init__(self) -> None:
        # Java(Afterstate, FixtureTest)가 거부하는 입력은 여기서도 거부한다. 조용히 다르게 인코딩하면 안 된다.
        _check_board(self.board)
        if len(self.queue) > QUEUE_SLOTS:
            raise ValueError(f"queue 는 최대 {QUEUE_SLOTS} 개")
        for p in (*self.queue, self.hold):
            _check_piece(p)
        if self.combo < -1 or not 0 <= self.lines <= 4 or self.attack < 0 or self.pending < 0:
            raise ValueError("combo >= -1, 0 <= lines <= 4, attack >= 0, pending >= 0 이어야 함")


@dataclass(frozen=True)
class Opponent:
    """결정 시점의 상대 상태 (MODEL_IO.md 2.2). 상대가 없으면 NO_OPPONENT 를 쓴다(입력이 모두 0)."""

    board: tuple[str, ...] = EMPTY_BOARD
    pending: int = 0
    combo: int = -1
    b2b: bool = False
    hold: str | None = None

    def __post_init__(self) -> None:
        _check_board(self.board)
        _check_piece(self.hold)
        if self.combo < -1 or self.pending < 0:
            raise ValueError("combo >= -1, pending >= 0 이어야 함")


NO_OPPONENT = Opponent()


def pad_board(bottom: Sequence[str] = (), top: Sequence[str] = ()) -> tuple[str, ...]:
    """픽스처 표기(FIXTURES.md 4장)의 board_bottom / board_top 을 24 행으로 채운다."""
    if len(top) + len(bottom) > HEIGHT:
        raise ValueError("board_top + board_bottom 이 24 행을 넘음")
    rows = ["." * WIDTH] * HEIGHT
    for i, row in enumerate(top):
        rows[i] = row
    for i, row in enumerate(bottom):
        rows[HEIGHT - len(bottom) + i] = row
    return tuple(rows)


def _encode_rows(board: Sequence[str]) -> np.ndarray:
    return np.array([[0.0 if ch == "." else 1.0 for ch in row] for row in board], dtype=np.float32)


def encode_board(a: Afterstate) -> np.ndarray:
    """board 텐서 한 개: float32 [24, 10], 채워진 칸 1.0 (MODEL_IO.md 3.1)."""
    return _encode_rows(a.board)


def encode_features(a: Afterstate) -> np.ndarray:
    """features 텐서 한 개: float32 [48] (MODEL_IO.md 3.2)."""
    f = np.zeros(FEATURES, dtype=np.float32)
    for k, piece in enumerate(a.queue):
        f[QUEUE_OFFSET + 7 * k + PIECES.index(piece)] = 1
    if a.hold is not None:
        f[HOLD_OFFSET + PIECES.index(a.hold)] = 1
    f[COMBO] = a.combo + 1
    f[B2B] = 1 if a.b2b else 0
    f[LINES] = a.lines
    f[ATTACK] = a.attack
    f[PENDING] = a.pending
    f[GARBAGE_RISES] = 1 if a.lines == 0 and a.pending > 0 else 0
    return f


def encode_opponent_board(o: Opponent) -> np.ndarray:
    """opp_board 텐서 한 개: float32 [24, 10] (MODEL_IO.md 3.3)."""
    return _encode_rows(o.board)


def encode_opponent_features(o: Opponent) -> np.ndarray:
    """opp_features 텐서 한 개: float32 [10] (MODEL_IO.md 3.4)."""
    f = np.zeros(OPP_FEATURES, dtype=np.float32)
    f[OPP_PENDING] = o.pending
    f[OPP_COMBO] = o.combo + 1
    f[OPP_B2B] = 1 if o.b2b else 0
    if o.hold is not None:
        f[OPP_HOLD + PIECES.index(o.hold)] = 1
    return f


def encode_batch(
    states: Iterable[Afterstate], opponent: Opponent = NO_OPPONENT
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """후보 N 개를 한 번에: board [N, 24, 10], features [N, 48], opp_board [N, 24, 10], opp_features [N, 10].

    상대 상태는 후보와 상관없으므로 N 번 반복한다(MODEL_IO.md 1장).
    """
    states = list(states)
    n = len(states)
    boards = np.stack([encode_board(a) for a in states]) if states else np.zeros((0, HEIGHT, WIDTH), np.float32)
    feats = np.stack([encode_features(a) for a in states]) if states else np.zeros((0, FEATURES), np.float32)
    opp_boards = np.repeat(encode_opponent_board(opponent)[None], n, axis=0)
    opp_feats = np.repeat(encode_opponent_features(opponent)[None], n, axis=0)
    return boards, feats, opp_boards, opp_feats


def afterstate_from_json(d: dict) -> Afterstate:
    """픽스처의 afterstate 객체를 읽는다 (FIXTURES.md 5.9)."""
    return Afterstate(
        board=pad_board(d.get("board_bottom", ()), d.get("board_top", ())),
        queue=tuple(d.get("queue", ())),
        hold=d.get("hold"),
        combo=d.get("combo", -1),
        b2b=d.get("b2b", False),
        lines=d.get("lines", 0),
        attack=d.get("attack", 0),
        pending=d.get("pending", 0),
    )


def opponent_from_json(d: dict | None) -> Opponent:
    """픽스처의 opponent 객체를 읽는다 (FIXTURES.md 5.9). 없으면 상대가 없는 것이다."""
    if d is None:
        return NO_OPPONENT
    return Opponent(
        board=pad_board(d.get("board_bottom", ()), d.get("board_top", ())),
        pending=d.get("pending", 0),
        combo=d.get("combo", -1),
        b2b=d.get("b2b", False),
        hold=d.get("hold"),
    )
