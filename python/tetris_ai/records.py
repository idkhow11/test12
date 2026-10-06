"""대전 기록 match-1 읽기 (docs/TRAINING.md 7장).

기록 한 판에서 플레이어 한쪽의 궤적(턴마다 고른 애프터스테이트와 그때의 상대 상태, 판 결과)을 꺼내
모델 입력 배열(MODEL_IO.md 형식 2)로 바꾼다. 값은 tetris_ai.model_io 와 Java FeatureEncoder 와 같아야 하며,
fixtures/match 로 Java 인코더가 만든 값과 비교한다.
"""

from __future__ import annotations

import gzip
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

import numpy as np

from .model_io import (
    ATTACK,
    B2B,
    COMBO,
    FEATURES,
    GARBAGE_RISES,
    HEIGHT,
    HOLD_OFFSET,
    LINES,
    MODEL_IO_VERSION,
    OPP_B2B,
    OPP_COMBO,
    OPP_FEATURES,
    OPP_HOLD,
    OPP_PENDING,
    PENDING,
    PIECES,
    QUEUE_OFFSET,
    RULES_VERSION,
    WIDTH,
)

FORMAT = "match-1"
SIDES = ("p1", "p2")
SPINS = ("none", "mini", "full")

_BITS = 1 << np.arange(WIDTH, dtype=np.int64)


def rows_to_board(rows: Iterable[int]) -> np.ndarray:
    """기록의 rows(정수 24 개, 비트 c 가 c 열)를 보드 [24, 10] uint8 로 (TRAINING.md 7.3)."""
    r = np.asarray(list(rows), dtype=np.int64)
    if r.shape != (HEIGHT,):
        raise ValueError(f"rows 는 정수 {HEIGHT} 개여야 함: {r.shape}")
    return ((r[:, None] & _BITS) != 0).astype(np.uint8)


def after_features(after: dict) -> np.ndarray:
    """애프터스테이트 → features [48] (MODEL_IO.md 3.2). tetris_ai.model_io.encode_features 와 같은 값."""
    f = np.zeros(FEATURES, dtype=np.float32)
    for k, piece in enumerate(after["queue"]):
        f[QUEUE_OFFSET + 7 * k + PIECES.index(piece)] = 1
    if after["hold"] is not None:
        f[HOLD_OFFSET + PIECES.index(after["hold"])] = 1
    f[COMBO] = after["combo"] + 1
    f[B2B] = 1 if after["b2b"] else 0
    f[LINES] = after["lines"]
    f[ATTACK] = after["attack"]
    f[PENDING] = after["pending"]
    f[GARBAGE_RISES] = 1 if after["lines"] == 0 and after["pending"] > 0 else 0
    return f


def opponent_features(state: dict) -> np.ndarray:
    """결정 시점의 상대 상태 → opp_features [10] (MODEL_IO.md 3.4)."""
    f = np.zeros(OPP_FEATURES, dtype=np.float32)
    f[OPP_PENDING] = state["pending"]
    f[OPP_COMBO] = state["combo"] + 1
    f[OPP_B2B] = 1 if state["b2b"] else 0
    if state["hold"] is not None:
        f[OPP_HOLD + PIECES.index(state["hold"])] = 1
    return f


@dataclass
class Trajectory:
    """한 판에서 한 플레이어가 고른 배치들. 모든 배열의 첫 축은 턴(배치가 있던 턴만)이다."""

    ai: str
    persona: str
    model: str
    side: str  # "p1" 또는 "p2"
    outcome: float  # 이 플레이어 기준 승패: 이김 +1, 짐 -1, 무승부 0 (TRAINING.md 4.1)
    turns: int  # 판의 턴 수
    boards: np.ndarray  # uint8 [T, 24, 10] 고른 애프터스테이트 보드
    features: np.ndarray  # float32 [T, 48]
    opp_boards: np.ndarray  # uint8 [T, 24, 10] 결정 시점의 상대 보드
    opp_features: np.ndarray  # float32 [T, 10]
    pending_before: np.ndarray  # int16 [T] 결정 시점(상쇄 전)의 내 대기 가비지
    sent: np.ndarray  # int16 [T] 상쇄하고 남은 송신량
    risen: np.ndarray  # int16 [T] 이번 고정에서 올라온 가비지 줄
    spin: np.ndarray  # int8 [T] 0 none, 1 mini, 2 full
    hold_used: np.ndarray  # bool [T]
    pc: np.ndarray  # bool [T] 퍼펙트 클리어

    def __len__(self) -> int:
        return len(self.features)


def read_matches(paths: Iterable[Path]) -> Iterator[dict]:
    """*.jsonl.gz 파일들에서 기록을 한 판씩 읽는다. 형식·규칙·입력 형식 버전이 다르면 거부한다."""
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            for line in f:
                m = json.loads(line)
                check_header(m)
                yield m


TIMED_FORMAT = "timed-1"  # 가상 시간 모드 기록 (TRAINING.md 10.9). steps[].p1/p2.state 는 match-1 과 같은 모양이다


def check_header(m: dict, formats: tuple[str, ...] = (FORMAT,)) -> None:
    """기록의 형식·규칙·입력 형식 버전을 확인한다. 가치망 학습은 match-1 만, 승률 예측망은 timed-1 도 받는다."""
    if m.get("format") not in formats:
        raise ValueError(f"기록 형식이 {', '.join(formats)} 이 아님: {m.get('format')}")
    if m.get("rules") != RULES_VERSION or m.get("model_io") != MODEL_IO_VERSION:
        raise ValueError(f"규칙 {m.get('rules')} / 입력 형식 {m.get('model_io')} 기록은 읽지 않음")


def trajectory(m: dict, side: str) -> Trajectory:
    """기록 한 판에서 한쪽(side)의 궤적. 모든 배치가 락 아웃이던 마지막 턴(after 가 null)은 빠진다."""
    other = "p2" if side == "p1" else "p1"
    seat = SIDES.index(side)
    player = m["players"][seat]
    result = m["result"]
    outcome = 0.0 if result == "draw" else (1.0 if result == side else -1.0)
    steps = [s for s in m["steps"] if s[side]["after"] is not None]
    t = len(steps)
    boards = np.zeros((t, HEIGHT, WIDTH), np.uint8)
    feats = np.zeros((t, FEATURES), np.float32)
    opp_boards = np.zeros((t, HEIGHT, WIDTH), np.uint8)
    opp_feats = np.zeros((t, OPP_FEATURES), np.float32)
    pending_before = np.zeros(t, np.int16)
    sent = np.zeros(t, np.int16)
    risen = np.zeros(t, np.int16)
    spin = np.zeros(t, np.int8)
    hold_used = np.zeros(t, bool)
    pc = np.zeros(t, bool)
    for i, s in enumerate(steps):
        me, them = s[side], s[other]
        after = me["after"]
        boards[i] = rows_to_board(after["rows"])
        feats[i] = after_features(after)
        opp_boards[i] = rows_to_board(them["state"]["rows"])
        opp_feats[i] = opponent_features(them["state"])
        pending_before[i] = me["state"]["pending"]
        sent[i] = me["sent"]
        risen[i] = me["risen"]
        spin[i] = SPINS.index(after["spin"])
        hold_used[i] = me["key"].startswith("h1")
        pc[i] = after["pc"]
    return Trajectory(
        ai=player["ai"],
        persona=player["persona"],
        model=player["model"],
        side=side,
        outcome=outcome,
        turns=m["turns"],
        boards=boards,
        features=feats,
        opp_boards=opp_boards,
        opp_features=opp_feats,
        pending_before=pending_before,
        sent=sent,
        risen=risen,
        spin=spin,
        hold_used=hold_used,
        pc=pc,
    )


def trajectories(m: dict, ais: set[str] | None = None) -> list[Trajectory]:
    """기록 한 판에서 두 쪽의 궤적. ais 를 주면 그 이름의 AI 가 둔 쪽만."""
    out = []
    for seat, side in enumerate(SIDES):
        if ais is None or m["players"][seat]["ai"] in ais:
            out.append(trajectory(m, side))
    return out
