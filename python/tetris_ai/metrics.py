"""대전 기록에서 재는 지표 (docs/TRAINING.md 8장): 승률, 행동 지표, 상호작용 지표."""

from __future__ import annotations

import numpy as np

from .model_io import ATTACK, LINES
from .records import Trajectory

DANGER_HEIGHT = 16  # 상대가 위험하다고 보는 최고 높이
OWN_DANGER_HEIGHT = 12  # 자기 보드가 위험하다고 보는 최고 높이. 수비 원형의 위험 높이 비용과 같다 (net.DANGER_HEIGHT, TRAINING.md 4.3)
PRESSURE_PENDING = 4  # 받을 가비지가 많다고 보는 줄 수


def heights_and_holes(boards: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """보드 [T, 24, 10] → 열 높이 [T, 10], 열별 구멍 [T, 10]. 정의는 TRAINING.md 3.3 (net.board_stats 와 같다)."""
    covered = np.cumsum(boards, axis=1) > 0
    heights = covered.sum(axis=1)
    holes = (covered & (boards == 0)).sum(axis=1)
    return heights, holes


def summarize(trajs: list[Trajectory]) -> dict[str, float]:
    """한 AI 가 둔 궤적들의 지표. 비율은 블록당이다."""
    if not trajs:
        return {"matches": 0}
    outcomes = np.array([t.outcome for t in trajs])
    feats = np.concatenate([t.features for t in trajs])
    boards = np.concatenate([t.boards for t in trajs])
    opp_boards = np.concatenate([t.opp_boards for t in trajs])
    sent = np.concatenate([t.sent for t in trajs]).astype(np.float64)
    spin = np.concatenate([t.spin for t in trajs])
    pending_before = np.concatenate([t.pending_before for t in trajs])
    hold_used = np.concatenate([t.hold_used for t in trajs])
    risen = np.concatenate([t.risen for t in trajs]).astype(np.float64)
    pieces = len(feats)
    lines = feats[:, LINES]
    attack = feats[:, ATTACK]
    heights, holes = heights_and_holes(boards)
    opp_heights, _ = heights_and_holes(opp_boards)
    danger = opp_heights.max(axis=1) >= DANGER_HEIGHT
    pressure = pending_before >= PRESSURE_PENDING

    def per_piece(values: np.ndarray, mask: np.ndarray) -> float:
        return float(values[mask].mean()) if mask.any() else float("nan")

    total_attack = float(attack.sum())
    return {
        "matches": len(trajs),
        "win_rate": float(((outcomes > 0) + 0.5 * (outcomes == 0)).mean()),
        "wins": int((outcomes > 0).sum()),
        "losses": int((outcomes < 0).sum()),
        "draws": int((outcomes == 0).sum()),
        "turns_mean": float(np.mean([t.turns for t in trajs])),
        "pieces": pieces,
        "app": total_attack / pieces,
        "sent_per_piece": float(sent.mean()),
        "lines_per_piece": float(lines.mean()),
        "tspin_rate": float(((spin > 0) & (lines > 0)).mean()),
        "tetris_rate": float((lines == 4).mean()),
        "max_height_mean": float(heights.max(axis=1).mean()),
        "holes_mean": float(holes.sum(axis=1).mean()),
        "hold_rate": float(hold_used.mean()),
        "risen_per_piece": float(risen.mean()),
        "danger_rate": float((heights.max(axis=1) > OWN_DANGER_HEIGHT).mean()),
        "cancel_ratio": (total_attack - float(sent.sum())) / total_attack if total_attack > 0 else float("nan"),
        "sent_when_opp_danger": per_piece(sent, danger),
        "sent_when_opp_safe": per_piece(sent, ~danger),
        "clear_when_pressured": per_piece((lines > 0).astype(np.float64), pressure),
        "clear_when_calm": per_piece((lines > 0).astype(np.float64), ~pressure),
    }
