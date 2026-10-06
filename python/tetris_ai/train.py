"""TD(λ) 학습 (docs/TRAINING.md 5장).

한 플레이어의 궤적 (x_0, o_0) … (x_{T-1}, o_{T-1}) 에서 V(x_t, o_t) 의 목표 G_t 를 판의 끝에서부터 계산한다.

    G_{T-1} = 승패 값 (+1, -1, 0)
    G_t     = R(x_{t+1}) + γ · [ (1 − λ) · V(x_{t+1}, o_{t+1}) + λ · G_{t+1} ]

목표 안의 V 는 세대의 학습을 시작할 때 한 번 계산해 고정한다.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import torch

from .net import REWARD_TERMS, ValueNet, board_stats, reward_terms
from .records import Trajectory


@dataclass
class Dataset:
    """여러 궤적을 이어 붙인 학습 자료. episode 경계는 starts / lengths 로 안다."""

    boards: np.ndarray  # uint8 [M, 24, 10]
    features: np.ndarray  # float32 [M, 48]
    opp_boards: np.ndarray  # uint8 [M, 24, 10]
    opp_features: np.ndarray  # float32 [M, 10]
    ai: np.ndarray  # int64 [M] AI 번호 (임베딩, 보상 가중치)
    starts: np.ndarray  # int64 [E]
    lengths: np.ndarray  # int64 [E]
    outcomes: np.ndarray  # float32 [E]

    def __len__(self) -> int:
        return len(self.features)

    @staticmethod
    def build(trajs: list[Trajectory], ai_index: dict[str, int]) -> "Dataset":
        trajs = [t for t in trajs if len(t) > 0]
        lengths = np.array([len(t) for t in trajs], dtype=np.int64)
        starts = np.concatenate([[0], np.cumsum(lengths)[:-1]]).astype(np.int64) if len(trajs) else np.zeros(0, np.int64)
        return Dataset(
            boards=np.concatenate([t.boards for t in trajs]) if trajs else np.zeros((0, 24, 10), np.uint8),
            features=np.concatenate([t.features for t in trajs]) if trajs else np.zeros((0, 48), np.float32),
            opp_boards=np.concatenate([t.opp_boards for t in trajs]) if trajs else np.zeros((0, 24, 10), np.uint8),
            opp_features=np.concatenate([t.opp_features for t in trajs]) if trajs else np.zeros((0, 10), np.float32),
            ai=np.concatenate([np.full(len(t), ai_index[t.ai], np.int64) for t in trajs]) if trajs else np.zeros(0, np.int64),
            starts=starts,
            lengths=lengths,
            outcomes=np.array([t.outcome for t in trajs], dtype=np.float32),
        )

    @staticmethod
    def concat(parts: list["Dataset"]) -> "Dataset":
        parts = [p for p in parts if len(p) > 0]
        offsets = np.cumsum([0] + [len(p) for p in parts[:-1]])
        return Dataset(
            boards=np.concatenate([p.boards for p in parts]),
            features=np.concatenate([p.features for p in parts]),
            opp_boards=np.concatenate([p.opp_boards for p in parts]),
            opp_features=np.concatenate([p.opp_features for p in parts]),
            ai=np.concatenate([p.ai for p in parts]),
            starts=np.concatenate([p.starts + o for p, o in zip(parts, offsets)]),
            lengths=np.concatenate([p.lengths for p in parts]),
            outcomes=np.concatenate([p.outcomes for p in parts]),
        )


def _tensors(ds: Dataset, idx: np.ndarray, device: torch.device):
    return (
        torch.from_numpy(ds.boards[idx]).to(device, torch.float32),
        torch.from_numpy(ds.features[idx]).to(device),
        torch.from_numpy(ds.opp_boards[idx]).to(device, torch.float32),
        torch.from_numpy(ds.opp_features[idx]).to(device),
        torch.from_numpy(ds.ai[idx]).to(device),
    )


@torch.no_grad()
def predict_values(net: ValueNet, ds: Dataset, device: torch.device, batch: int = 8192) -> np.ndarray:
    """모든 표본의 V(x_t, o_t) [M]."""
    net.eval()
    out = np.zeros(len(ds), np.float32)
    for s in range(0, len(ds), batch):
        idx = np.arange(s, min(s + batch, len(ds)))
        out[idx] = net(*_tensors(ds, idx, device)).squeeze(1).cpu().numpy()
    return out


@torch.no_grad()
def rewards(ds: Dataset, weights_by_ai: torch.Tensor, device: torch.device, batch: int = 8192) -> np.ndarray:
    """모든 표본의 즉시 보상 R(x_t) [M]. weights_by_ai 는 [AI 수, len(REWARD_TERMS)] (REWARD_TERMS 순서)."""
    assert weights_by_ai.shape[1] == len(REWARD_TERMS)
    w = weights_by_ai.to(device)
    out = np.zeros(len(ds), np.float32)
    for s in range(0, len(ds), batch):
        idx = np.arange(s, min(s + batch, len(ds)))
        board, features, _, _, ai = _tensors(ds, idx, device)
        terms = reward_terms(features, board_stats(board))
        out[idx] = (terms * w[ai]).sum(dim=1).cpu().numpy()
    return out


def lambda_returns(
    values: np.ndarray, rews: np.ndarray, starts: np.ndarray, lengths: np.ndarray, outcomes: np.ndarray, gamma: float, lam: float
) -> np.ndarray:
    """λ-리턴 G_t [M] (모듈 설명의 식). values, rews 는 표본 순서."""
    targets = np.zeros_like(values, dtype=np.float32)
    for s, n, z in zip(starts, lengths, outcomes):
        g = float(z)
        targets[s + n - 1] = g
        for t in range(s + n - 2, s - 1, -1):
            g = float(rews[t + 1]) + gamma * ((1.0 - lam) * float(values[t + 1]) + lam * g)
            targets[t] = g
    return targets


def train_epochs(
    net: ValueNet,
    optimizer: torch.optim.Optimizer,
    ds: Dataset,
    targets: np.ndarray,
    epochs: int,
    batch_size: int,
    device: torch.device,
    rng: np.random.Generator,
) -> list[float]:
    """평균 제곱 오차로 epochs 번 학습한다. 반복마다의 평균 손실을 돌려준다."""
    net.train()
    target_t = torch.from_numpy(targets)
    losses = []
    for _ in range(epochs):
        order = rng.permutation(len(ds))
        total, count = 0.0, 0
        for s in range(0, len(order), batch_size):
            idx = np.sort(order[s : s + batch_size])
            pred = net(*_tensors(ds, idx, device)).squeeze(1)
            loss = torch.mean((pred - target_t[idx].to(device)) ** 2)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(net.parameters(), 5.0)
            optimizer.step()
            total += loss.item() * len(idx)
            count += len(idx)
        losses.append(total / max(count, 1))
    return losses
