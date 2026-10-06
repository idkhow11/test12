"""애프터스테이트 가치망 (docs/TRAINING.md 3장), 보상 (4장), ONNX 내보내기 (MODEL_IO.md 6장).

입력은 MODEL_IO.md 형식 2 의 네 텐서다. 정규화와 보드 통계는 모두 이 모듈 안에서 계산하므로,
Java 와 Python 은 정수를 실수로 바꾼 입력만 똑같이 만들면 된다.
"""

from __future__ import annotations

import warnings
from pathlib import Path

import torch
from torch import nn

from .model_io import (
    ATTACK,
    B2B,
    COMBO,
    FEATURES,
    GARBAGE_RISES,
    HEIGHT,
    LINES,
    MODEL_IO_VERSION,
    OPP_B2B,
    OPP_COMBO,
    OPP_FEATURES,
    OPP_PENDING,
    PENDING,
    RULES_VERSION,
    WIDTH,
)

STATS = 24  # 보드 통계 개수 (TRAINING.md 3.3)
STAT_MAX_HEIGHT = 20
STAT_TOTAL_HOLES = 21
STAT_BUMPINESS = 22

# 보상 항목 (TRAINING.md 4.3). 순서가 가중치 벡터의 순서다. 뒤의 넷은 10-04 에 더했다(앞 여섯의 순서는 그대로).
REWARD_TERMS = ("attack", "tspin", "tetris", "combo", "height", "holes", "survive", "garbage_rise", "pending", "danger")
DANGER_HEIGHT = 12  # 위험 높이 항목이 0 보다 커지는 최고 높이 (TRAINING.md 4.3)
GARBAGE_RISE_MAX = 8  # 한 번의 고정에서 올라오는 가비지 줄 수의 상한 (RULES 10.3)

# 스타일 원형 가중치 [초안] (TRAINING.md 4.3). 승패(±1)에 더한다. 수비는 10-04 에 "위험할 때만 비용 + 생존"으로 바꿨다
# (10-02 표의 수비는 height −0.003, holes −0.0002 였고 p01~p03 의 config.json 에 남아 있다).
ARCHETYPES: dict[str, dict[str, float]] = {
    "neutral": {},
    "attacker": {"attack": 0.004, "tetris": 0.02},
    "defender": {"survive": 0.0005, "garbage_rise": -0.004, "pending": -0.004, "danger": -0.01},
    "tspin": {"tspin": 0.05},
    "combo": {"combo": 0.01},
}

INPUT_NAMES = ("board", "features", "opp_board", "opp_features")
OPSET = 17

# 기준 평가 H(x): Java BaselineScorer 와 같은 식과 가중치 (TRAINING.md 3.5).
# 배치 점수 = R + γ·V + PRIOR_WEIGHT·H. H 는 고르는 점수에만 더하고 V 의 학습 목표에는 넣지 않는다.
BASELINE_HEIGHT = -0.510066
BASELINE_LINES = 0.760666
BASELINE_HOLES = -0.35663
BASELINE_BUMPINESS = -0.184483
PRIOR_WEIGHT = 0.05  # [초안] 구멍 하나가 점수를 약 0.018 낮추는 크기


def style_weights(persona: str | dict[str, float]) -> torch.Tensor:
    """페르소나 이름이나 {항목: 가중치} → 보상 가중치 벡터 [len(REWARD_TERMS)]."""
    w = ARCHETYPES[persona] if isinstance(persona, str) else persona
    unknown = set(w) - set(REWARD_TERMS)
    if unknown:
        raise ValueError(f"모르는 보상 항목: {sorted(unknown)}")
    return torch.tensor([w.get(k, 0.0) for k in REWARD_TERMS], dtype=torch.float32)


def board_stats(board: torch.Tensor) -> torch.Tensor:
    """보드 [N, 24, 10] (0/1) → 통계 [N, 24]: 열 높이 10, 열별 구멍 10, 최고 높이, 구멍 합, 울퉁불퉁함, 구멍 있는 줄.

    정의는 TRAINING.md 3.3. 구멍은 열마다 맨 위 찬 칸보다 아래의 빈칸이다(BaselineScorer 와 같다).
    """
    covered = (torch.cumsum(board, dim=1) > 0).to(board.dtype)  # 맨 위 찬 칸과 그 아래
    heights = covered.sum(dim=1)
    hole_cells = covered - board  # 찬 칸은 언제나 covered 안에 있다
    holes = hole_cells.sum(dim=1)
    max_height = heights.amax(dim=1, keepdim=True)
    total_holes = holes.sum(dim=1, keepdim=True)
    bumpiness = (heights[:, 1:] - heights[:, :-1]).abs().sum(dim=1, keepdim=True)
    rows_with_holes = (hole_cells.sum(dim=2) > 0).to(board.dtype).sum(dim=1, keepdim=True)
    return torch.cat([heights, holes, max_height, total_holes, bumpiness, rows_with_holes], dim=1)


def baseline_score(features: torch.Tensor, stats: torch.Tensor) -> torch.Tensor:
    """기준 평가 H(x) [N, 1]: 높이 합, 지운 줄, 구멍, 울퉁불퉁함의 가중합. Java BaselineScorer.evaluate 와 같다."""
    aggregate = stats[:, :WIDTH].sum(dim=1)
    h = (
        BASELINE_HEIGHT * aggregate
        + BASELINE_LINES * features[:, LINES]
        + BASELINE_HOLES * stats[:, STAT_TOTAL_HOLES]
        + BASELINE_BUMPINESS * stats[:, STAT_BUMPINESS]
    )
    return h.unsqueeze(1)


def reward_terms(features: torch.Tensor, stats: torch.Tensor) -> torch.Tensor:
    """애프터스테이트로만 계산하는 보상 항목 [N, len(REWARD_TERMS)] (TRAINING.md 4.3).

    T-spin 클리어: 1~3 줄을 지운 뒤 B2B 가 참이면 T-spin 이다(테트리스는 4 줄이고, 일반 클리어는 B2B 를 끊는다, RULES 9.3).
    콤보 클리어: 줄을 지웠고 배치 뒤 콤보가 1 이상(features 의 값은 combo + 1 이므로 2 이상).
    가비지 상승: 이번 고정에서 가비지가 올라오면(features[47]) 대기열 앞에서 최대 8 줄이 올라온다(RULES 10.3).
    남은 대기 가비지: 상쇄하고 올라온 뒤에도 대기열에 남는 줄 수.
    """
    lines = features[:, LINES]
    attack = features[:, ATTACK]
    b2b = features[:, B2B]
    combo_plus_one = features[:, COMBO]
    tspin = ((lines >= 1) & (lines <= 3) & (b2b > 0.5)).to(features.dtype)
    tetris = (lines == 4).to(features.dtype)
    combo = ((lines > 0) & (combo_plus_one >= 2)).to(features.dtype)
    height = stats[:, STAT_MAX_HEIGHT] / HEIGHT
    holes = stats[:, STAT_TOTAL_HOLES]
    survive = torch.ones_like(lines)
    pending = features[:, PENDING]
    garbage_rise = features[:, GARBAGE_RISES] * pending.clamp(max=GARBAGE_RISE_MAX)
    remaining = pending - garbage_rise
    danger = (stats[:, STAT_MAX_HEIGHT] - DANGER_HEIGHT).clamp(min=0) / (HEIGHT - DANGER_HEIGHT)
    return torch.stack([attack, tspin, tetris, combo, height, holes, survive, garbage_rise, remaining, danger], dim=1)


def _scale(n: int, pairs: dict[int, float]) -> torch.Tensor:
    s = torch.ones(n)
    for i, v in pairs.items():
        s[i] = v
    return s


class ValueNet(nn.Module):
    """V(애프터스테이트, 상대 상태, AI 임베딩) (TRAINING.md 3.4). 승패(±1)와 보상만 배운다.

    마지막 층은 0 에서 시작해 학습 전에는 V = 0 이다. 그때 배치 점수(ScoreModule)는 기준 평가 H 만 남아 기준 AI 와
    같은 배치를 고른다.
    """

    def __init__(
        self,
        n_ai: int = 1,
        channels: tuple[int, int, int] = (16, 32, 32),
        board_hidden: int = 128,
        feat_hidden: int = 64,
        opp_hidden: int = 32,
        emb_dim: int = 8,
        head_hidden: int = 128,
    ) -> None:
        super().__init__()
        c1, c2, c3 = channels
        self.config = dict(
            n_ai=n_ai,
            channels=list(channels),
            board_hidden=board_hidden,
            feat_hidden=feat_hidden,
            opp_hidden=opp_hidden,
            emb_dim=emb_dim,
            head_hidden=head_hidden,
        )
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
        self.opp_fc = nn.Sequential(nn.Linear(OPP_FEATURES + STATS, opp_hidden), nn.ReLU())
        self.embedding = nn.Embedding(n_ai, emb_dim)
        nn.init.normal_(self.embedding.weight, std=0.01)
        self.head = nn.Sequential(
            nn.Linear(board_hidden + feat_hidden + opp_hidden + emb_dim, head_hidden), nn.ReLU(), nn.Linear(head_hidden, 1)
        )
        nn.init.zeros_(self.head[-1].weight)
        nn.init.zeros_(self.head[-1].bias)
        # 입력 정규화 상수. 원-핫과 0/1 값은 그대로, 개수는 대략 0~1 범위가 되도록 나눈다.
        self.register_buffer(
            "f_scale", _scale(FEATURES, {COMBO: 0.1, B2B: 1.0, LINES: 0.25, ATTACK: 0.1, PENDING: 0.1, GARBAGE_RISES: 1.0})
        )
        self.register_buffer("o_scale", _scale(OPP_FEATURES, {OPP_PENDING: 0.1, OPP_COMBO: 0.1, OPP_B2B: 1.0}))
        self.register_buffer(
            "s_scale", torch.cat([torch.full((20,), 1.0 / HEIGHT), torch.tensor([1.0 / HEIGHT, 0.02, 0.02, 1.0 / HEIGHT])])
        )

    def value(self, board, features, opp_board, opp_features, emb):
        """V [N, 1]. emb 는 AI 임베딩 [N, emb_dim]."""
        b = self.board_fc(self.conv(board.unsqueeze(1)).flatten(1))
        f = self.feat_fc(torch.cat([features * self.f_scale, board_stats(board) * self.s_scale], dim=1))
        o = self.opp_fc(torch.cat([opp_features * self.o_scale, board_stats(opp_board) * self.s_scale], dim=1))
        return self.head(torch.cat([b, f, o, emb], dim=1))

    def forward(self, board, features, opp_board, opp_features, ai):
        """ai 는 AI 번호 [N] (long)."""
        return self.value(board, features, opp_board, opp_features, self.embedding(ai))


class ScoreModule(nn.Module):
    """한 AI 의 배치 점수 score = R(x) + γ·V(x, o) + β·H(x) (TRAINING.md 3.1, 3.5). ONNX 로 내보내는 모양 그대로다.

    β·H 는 기준 평가를 사전 지식으로 더한 것이다. 학습 목표에는 들어가지 않으므로, V 가 승패를 잘 배울수록 V 의 차이가
    배치를 가른다.
    """

    def __init__(self, net: ValueNet, ai: int, weights: torch.Tensor, gamma: float, prior_weight: float) -> None:
        super().__init__()
        self.net = net
        self.register_buffer("emb", net.embedding.weight[ai].detach().clone().unsqueeze(0))
        self.register_buffer("weights", weights.detach().clone().reshape(-1, 1))
        self.gamma = float(gamma)
        self.prior_weight = float(prior_weight)

    def forward(self, board, features, opp_board, opp_features):
        v = self.net.value(board, features, opp_board, opp_features, self.emb.expand(board.shape[0], -1))
        stats = board_stats(board)
        r = reward_terms(features, stats) @ self.weights
        return r + self.gamma * v + self.prior_weight * baseline_score(features, stats)


def export_onnx(module: ScoreModule, path: Path, metadata: dict[str, str]) -> None:
    """MODEL_IO.md 6장대로 내보내고 꼬리표(5장)를 단다. 입력 네 개와 출력 value [N, 1], N 은 dynamic axis."""
    import onnx  # 학습 쪽에서만 필요하다

    module = module.eval().cpu()
    n = 3
    dummy = (
        torch.zeros(n, HEIGHT, WIDTH),
        torch.zeros(n, FEATURES),
        torch.zeros(n, HEIGHT, WIDTH),
        torch.zeros(n, OPP_FEATURES),
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp.onnx")
    # opset 17 그대로 내보내려고 PyTorch 의 옛 내보내기(TorchScript)를 쓴다. 2.9 부터 "없어질 기능" 경고가 나오지만
    # 고정한 버전(python/requirements-train.txt)에서는 동작하고, 결과는 test_net.ExportTest 가 onnxruntime 과 비교한다.
    # PyTorch 를 올릴 때 새 내보내기(dynamo=True)로 옮긴다.
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", DeprecationWarning)
        torch.onnx.export(
            module,
            dummy,
            str(tmp),
            input_names=list(INPUT_NAMES),
            output_names=["value"],
            dynamic_axes={name: {0: "N"} for name in (*INPUT_NAMES, "value")},
            opset_version=OPSET,
            dynamo=False,
        )
    model = onnx.load(str(tmp))
    tmp.unlink()
    del model.metadata_props[:]
    for k, v in {"tetris.model_io": MODEL_IO_VERSION, "tetris.rules": RULES_VERSION, **metadata}.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = k, str(v)
    onnx.checker.check_model(model)
    onnx.save(model, str(path))


def parameter_count(net: nn.Module) -> int:
    return sum(p.numel() for p in net.parameters())
