"""학습 루프의 일정과 진단 계산 (docs/TRAINING.md 3.6, 5.2, 5.3, 8.1). PyTorch 가 없으면 건너뛴다.

    python -m unittest discover -s python/tests -v
"""

from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

try:
    import torch  # noqa: F401

    import evaluate
    import train_loop
except ImportError:  # 학습 도구를 설치하지 않은 PC
    train_loop = None


@unittest.skipIf(train_loop is None, "PyTorch 가 없음 (python/requirements-train.txt)")
class ScheduleTest(unittest.TestCase):
    def test_defaults_keep_r01_values(self) -> None:
        # 기본 설정이면 β 와 학습률은 세대와 상관없이 그대로다(r01 과 같은 학습).
        cfg = dict(train_loop.DEFAULTS)
        for gen in (0, 10, 79):
            self.assertEqual(train_loop.prior_at(cfg, gen), 0.05)
            self.assertEqual(train_loop.lr_at(cfg, gen), 3e-4)

    def test_old_config_without_new_keys(self) -> None:
        # 새 열쇠가 없는 옛 config.json(r01)도 그대로 읽는다.
        cfg = {"prior_weight": 0.05, "lr": 3e-4}
        self.assertEqual(train_loop.prior_at(cfg, 40), 0.05)
        self.assertEqual(train_loop.lr_at(cfg, 40), 3e-4)

    def test_prior_ramp_by_hand(self) -> None:
        # 세대 10 에서 0.05, 세대 20 에서 0.01: 세대 15 는 가운데 0.03, 그 앞뒤는 끝값.
        cfg = {"prior_weight": 0.05, "prior_final": 0.01, "prior_from": 10, "prior_until": 20}
        self.assertAlmostEqual(train_loop.prior_at(cfg, 5), 0.05)
        self.assertAlmostEqual(train_loop.prior_at(cfg, 15), 0.03)
        self.assertAlmostEqual(train_loop.prior_at(cfg, 20), 0.01)
        self.assertAlmostEqual(train_loop.prior_at(cfg, 99), 0.01)
        # until <= from 이면 from 세대부터 바로 바뀐다.
        step = {"prior_weight": 0.05, "prior_final": 0.01, "prior_from": 39, "prior_until": 0}
        self.assertAlmostEqual(train_loop.prior_at(step, 38), 0.05)
        self.assertAlmostEqual(train_loop.prior_at(step, 39), 0.01)

    def test_lr_decays_geometrically(self) -> None:
        # 3e-4 → 3e-5 를 세대 0~10 에 등비로: 세대 5 는 3e-4 × 0.1^0.5 ≈ 9.487e-5.
        cfg = {"lr": 3e-4, "lr_final": 3e-5, "lr_from": 0, "lr_until": 10}
        self.assertAlmostEqual(train_loop.lr_at(cfg, 5), 3e-4 * math.sqrt(0.1))
        self.assertAlmostEqual(train_loop.lr_at(cfg, 10), 3e-5)
        self.assertAlmostEqual(train_loop.lr_at(cfg, 0), 3e-4)


@unittest.skipIf(train_loop is None, "PyTorch 가 없음")
class DiagnosticsTest(unittest.TestCase):
    def test_held_out_losses_by_hand(self) -> None:
        # 끝의 2 개가 이번 세대(아직 배우지 않음): 오차 (1, 3) → 평균 제곱 5. 앞의 3 개: 오차 (0, 1, 2) → 5/3.
        values = np.array([0.0, 1.0, 2.0, 1.0, 3.0], np.float32)
        targets = np.zeros(5, np.float32)
        new, seen = train_loop.held_out_losses(values, targets, 2)
        self.assertAlmostEqual(new, 5.0)
        self.assertAlmostEqual(seen, 5.0 / 3.0)
        new, seen = train_loop.held_out_losses(values, targets, 5)
        self.assertTrue(math.isnan(seen), "이전 세대 기록이 없으면 nan")

    def test_interval_and_seeds(self) -> None:
        # PROGRESS 0.5 의 값: p = 0.758, 1,000 판 → 1.96 × √(0.758 × 0.242 / 1000) ≈ 0.0265.
        self.assertAlmostEqual(evaluate.interval95(0.758, 1000), 0.02655, places=4)
        self.assertEqual(evaluate.parse_seeds("970001:970500"), range(970001, 970501))
        with self.assertRaises(ValueError):
            evaluate.parse_seeds("5:4")


if __name__ == "__main__":
    unittest.main()
