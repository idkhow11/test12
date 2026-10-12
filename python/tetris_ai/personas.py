"""페르소나 학습 (docs/TRAINING.md 9장, 단계 B): AI 표, 단계 A 가중치에서 시작하기, 리그 대전 계획, 내보내기, 지표.

AI 12 개(스타일 원형 4 개 + 원형 가중치를 디리클레 비율로 섞은 8 개)가 가치망 하나를 공유하고 AI 마다 임베딩을 학습한다.
p05(runs/p05-design)부터는 원형을 복제(replicas)하고 스타일 보상이 없는 중립 AI(n_neutral)를 둘 수 있다. 기본값이면 표는
예전과 같다. 학습 루프(tools/train_personas.py)와 분석(tools/analyze_personas.py)이 함께 쓴다. 군집 분석은
persona_analysis.py 에 있다. Java 대전 러너를 부르는 도구(rel, rmtree, run_java)는 runner.py 에 있고, 여기서 다시 내보낸다.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import numpy as np
import torch

from . import net as netlib
from .metrics import heights_and_holes, summarize
from .model_io import COMBO, FEATURES, HEIGHT, LINES, OPP_FEATURES, WIDTH
from .records import Trajectory
from .runner import rel, rmtree, run_java  # noqa: F401  (tools 에서 pl.run_java 로 쓴다)

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "baseline"
REFERENCE = "reference"  # 분석의 고정 상대 이름 (TRAINING.md 9.5)
ARCHETYPE_NAMES = ("attacker", "defender", "tspin", "combo")
NEUTRAL = "neutral"  # 스타일 보상이 없는 AI 의 라벨 (p05 의 비교 기준)
LABELS = (*ARCHETYPE_NAMES, NEUTRAL)  # 분석에서 쓰는 라벨 번호의 순서
EMBEDDING = "embedding.weight"
PAST_SEP = "@gen-"  # 이전 세대 모델의 이름: <AI>@gen-<세대>
EVAL_SEED0 = 900001  # 평가 시드 (TRAINING.md 8장)
ANALYSIS_SEED0 = 920001  # 분석 시드 (TRAINING.md 9.5). 학습·평가 시드와 겹치지 않는다

# [초안] 수치 (TRAINING.md 9장). 실행할 때 config.json 에 그대로 남긴다. 학습 수치는 단계 A 의 고친 절차(10-02, 실행 a2:
# β 0.01, 학습률 1e-4 → 2e-5 를 20 세대에 걸쳐 줄임, 세대당 1,024 판, 1 번 반복)를 따른다. 검증 실행 p01·p02 는 그 전의
# 값(β 0.05, 학습률 3e-4 고정, 512 판, 2 번 반복)으로 돌렸고 각자의 config.json 에 남아 있다.
DEFAULTS: dict = dict(
    init="runs/r01/checkpoints/gen-038.pt",  # 단계 A 체크포인트 (저장소 기준 경로, 절대 경로도 된다)
    init_ai=0,  # 그 체크포인트에서 가져올 임베딩 행
    emb_noise=0.01,
    n_mix=8,
    mix_alpha=1.0,
    mix_seed=0,
    replicas=1,  # 원형마다 AI 수 (p05: 2. 같은 보상의 복제가 임베딩에서 모이는지 본다)
    n_neutral=0,  # 스타일 보상 없는 중립 AI 수 (p05: 2)
    center_style=0,  # 1 이면 세대마다 AI 별 즉시 보상의 블록당 평균을 빼고 λ-리턴을 만든다 (train_personas.center_rewards)
    matches=1024,
    max_turns=1000,
    threads=3,
    temperature=0.3,
    pool_self=0.5,
    pool_past=0.3,
    past_window=5,
    gamma=1.0,
    lam=0.8,
    window=4,
    epochs=1,
    lr=1e-4,  # 세대 1 의 학습률
    lr_final=2e-5,  # lr_decay_gens 세대 뒤의 학습률. 그 사이는 선형으로 줄인다
    lr_decay_gens=20,  # 0 이면 학습률을 줄이지 않는다
    emb_lr_scale=10.0,
    weight_decay=1e-4,
    batch=1024,
    prior_weight=0.01,  # β (TRAINING.md 3.5). 0.05 면 기준 평가가 가치망의 판단을 누른다(단계 A, 10-02)
    reuse_optimizer=1,  # 1 이면 단계 A 최적화기 상태를 이어받는다
    eval_every=5,  # 0 이면 평가하지 않는다
    eval_seeds=25,
    keep_every=5,
    seed=0,
)
# --config 파일(예: 단계 A 실행의 config.json)로는 바꾸지 않는 키. 실행 관리와 평가 크기라 이름이 같아도 뜻이 다르다
# (단계 A 의 eval_seeds 는 AI 하나의 평가 시드 수). 바꾸려면 명령행 인자를 쓴다.
PROTECTED = ("threads", "eval_every", "eval_seeds", "keep_every", "init", "init_ai")


def learning_rate(cfg: dict, gen: int) -> float:
    """세대 gen(1 부터)의 학습률: lr 에서 lr_final 까지 lr_decay_gens 세대에 걸쳐 선형으로 줄인다 (TRAINING.md 9.3).

    lr_final 이나 lr_decay_gens 가 없는 옛 설정(p01, p02)은 lr 고정이다.
    """
    lr, final, gens = cfg["lr"], cfg.get("lr_final", cfg["lr"]), cfg.get("lr_decay_gens", 0)
    if gens <= 0:
        return lr
    t = min(max(gen - 1, 0) / gens, 1.0)
    return lr + (final - lr) * t


def default_config() -> dict:
    """기본 설정. 원형 가중치(TRAINING.md 4.3)를 복사해 넣어, 뒤에 표가 바뀌어도 실행을 다시 만들 수 있게 한다."""
    cfg = dict(DEFAULTS)
    cfg["archetypes"] = {a: dict(netlib.ARCHETYPES[a]) for a in ARCHETYPE_NAMES}
    return cfg


def merge_config(base: dict, overrides: dict) -> tuple[dict, list[str]]:
    """설정 파일의 값을 base 위에 얹는다. 숫자는 base 의 자료형으로 바꾼다. 모르는 키와 PROTECTED 키는 쓰지 않고 이름을
    돌려준다(경고용)."""
    cfg = dict(base)
    ignored = []
    for k, v in overrides.items():
        if k not in base or k in PROTECTED:
            ignored.append(k)
        elif isinstance(base[k], float):
            cfg[k] = float(v)
        elif isinstance(base[k], int):
            cfg[k] = int(v)
        else:
            cfg[k] = v
    return cfg, sorted(ignored)


# ---------------------------------------------------------------- AI 표 (TRAINING.md 9.1)


@dataclass(frozen=True)
class Persona:
    """학습하는 AI 하나."""

    name: str  # AI 이름 (기록의 players[].ai, 꼬리표 tetris.persona)
    label: str  # 비율이 가장 큰 원형 (분석에서 비교하는 설계 의도). 중립 AI 는 NEUTRAL
    ratios: tuple[float, ...]  # 원형 4 개의 비율 (ARCHETYPE_NAMES 순서, 합 1. 중립 AI 는 모두 0)
    weights: tuple[float, ...]  # 보상 가중치 (net.REWARD_TERMS 순서)

    def to_json(self) -> dict:
        return {"name": self.name, "label": self.label, "ratios": list(self.ratios),
                "weights": dict(zip(netlib.REWARD_TERMS, self.weights))}


def archetype_matrix(archetypes: dict[str, dict[str, float]]) -> np.ndarray:
    """원형 가중치 표 [4, 항목 수] (ARCHETYPE_NAMES × net.REWARD_TERMS). 모르는 보상 항목은 거부한다."""
    rows = []
    for a in ARCHETYPE_NAMES:
        w = archetypes[a]
        unknown = set(w) - set(netlib.REWARD_TERMS)
        if unknown:
            raise ValueError(f"{a}: 모르는 보상 항목 {sorted(unknown)}")
        rows.append([float(w.get(t, 0.0)) for t in netlib.REWARD_TERMS])
    return np.array(rows, dtype=np.float64)


def mixture_ratios(n: int, alpha: float, seed: int) -> np.ndarray:
    """섞은 AI 의 원형 비율 [n, 4]. 디리클레 분포(4 성분 모두 alpha)에서 시드로 정해지게 뽑는다."""
    return np.random.default_rng(seed).dirichlet(np.full(len(ARCHETYPE_NAMES), float(alpha)), size=n)


def replica_name(base: str, r: int) -> str:
    """복제 r 번(0 부터)의 이름: 0 이면 base, 1 이면 base_b, 2 이면 base_c, …"""
    return base if r == 0 else f"{base}_{chr(ord('a') + r)}"


def make_personas(archetypes: dict[str, dict[str, float]], n_mix: int, alpha: float, seed: int, replicas: int = 1,
                  n_neutral: int = 0) -> list[Persona]:
    """AI 표: 원형 4 개(자기 원형 비율 1)를 replicas 번(attacker, defender, tspin, combo, attacker_b, …), 다음에 중립
    n_neutral 개(neutral, neutral_b, …; 비율 0, 가중치 0), 다음에 섞은 AI n_mix 개(mix01, mix02, …).
    보상 가중치 = 비율 @ 원형 표. 기본값(replicas 1, n_neutral 0)이면 예전 표와 같다."""
    if replicas < 1 or n_neutral < 0 or n_mix < 0:
        raise ValueError(f"replicas ≥ 1, n_neutral ≥ 0, n_mix ≥ 0 이어야 함: {replicas}, {n_neutral}, {n_mix}")
    table = archetype_matrix(archetypes)
    eye = np.eye(len(ARCHETYPE_NAMES))
    out = [Persona(replica_name(a, r), a, tuple(float(x) for x in eye[j]), tuple(float(x) for x in eye[j] @ table))
           for r in range(replicas) for j, a in enumerate(ARCHETYPE_NAMES)]
    zero_r, zero_w = (0.0,) * len(ARCHETYPE_NAMES), (0.0,) * len(netlib.REWARD_TERMS)
    out += [Persona(replica_name(NEUTRAL, r), NEUTRAL, zero_r, zero_w) for r in range(n_neutral)]
    for i, r in enumerate(mixture_ratios(n_mix, alpha, seed)):
        out.append(Persona(f"mix{i + 1:02d}", ARCHETYPE_NAMES[int(np.argmax(r))], tuple(float(x) for x in r),
                           tuple(float(x) for x in r @ table)))
    return out


def personas_from_config(cfg: dict) -> list[Persona]:
    """실행 설정의 AI 표. replicas·n_neutral 이 없는 옛 설정(p01~p04)은 1·0 이다."""
    return make_personas(cfg["archetypes"], cfg["n_mix"], cfg["mix_alpha"], cfg["mix_seed"], int(cfg.get("replicas", 1)),
                         int(cfg.get("n_neutral", 0)))


def same_table(a: list[dict], b: list[dict], rel_tol: float = 1e-12) -> bool:
    """AI 표 두 개(Persona.to_json 의 목록, 예: personas.json 이나 체크포인트의 personas)가 같은가.

    이름·라벨·비율이 같고, 보상 가중치는 한쪽에 없는 항목을 0 으로 보아 같으면 참이다. 보상 항목을 끝에 더하기 전에
    만든 표(p01~p04, 항목 10 개)도 지금 표와 같다고 본다. 실수는 상대 오차 rel_tol 까지 같다고 본다(행렬 곱의 순서만
    다른 경우).
    """

    def close(x: float, y: float) -> bool:
        return abs(float(x) - float(y)) <= rel_tol * max(1.0, abs(float(x)), abs(float(y)))

    if len(a) != len(b):
        return False
    for p, q in zip(a, b):
        if p["name"] != q["name"] or p["label"] != q["label"] or len(p["ratios"]) != len(q["ratios"]):
            return False
        if not all(close(x, y) for x, y in zip(p["ratios"], q["ratios"])):
            return False
        wp, wq = p["weights"], q["weights"]
        if not all(close(wp.get(k, 0.0), wq.get(k, 0.0)) for k in set(wp) | set(wq)):
            return False
    return True


def weight_matrix(personas: list[Persona]) -> torch.Tensor:
    """AI 별 보상 가중치 [AI 수, 항목 수]. train.rewards 의 weights_by_ai 이고, 행 번호가 임베딩 번호다."""
    return torch.tensor([p.weights for p in personas], dtype=torch.float32)


# ---------------------------------------------------------------- 시작점 (TRAINING.md 9.2)


def expand_embedding(state: dict, n_ai: int, src: int, noise_std: float, seed: int) -> dict:
    """단계 A state_dict 의 임베딩 표를 n_ai 행으로 늘린 사본. 행마다 src 행 + 정규분포 잡음(시드 고정). 나머지는 그대로."""
    row = state[EMBEDDING][src].detach().float().cpu()
    noise = torch.randn(n_ai, row.numel(), generator=torch.Generator().manual_seed(int(seed))) * float(noise_std)
    out = dict(state)
    out[EMBEDDING] = row.unsqueeze(0) + noise
    return out


def net_from_stage_a(ckpt: dict, n_ai: int, src: int, noise_std: float, seed: int) -> netlib.ValueNet:
    """단계 A 체크포인트 → 임베딩만 n_ai 행으로 늘린 ValueNet. net.py 를 고치지 않고 늘린 state_dict 를 불러온다."""
    net = netlib.ValueNet(**{**ckpt["config"], "n_ai": n_ai})
    net.load_state_dict(expand_embedding(ckpt["net"], n_ai, src, noise_std, seed))
    return net


def make_optimizer(net: netlib.ValueNet, lr: float, weight_decay: float, emb_lr_scale: float) -> torch.optim.AdamW:
    """AdamW. 묶음 0 은 공유 신경망, 묶음 1 은 임베딩이고 임베딩만 학습률이 emb_lr_scale 배다 (TRAINING.md 9.2)."""
    rest = [p for name, p in net.named_parameters() if name != EMBEDDING]
    groups = [{"params": rest, "lr": lr}, {"params": [net.embedding.weight], "lr": lr * emb_lr_scale}]
    return torch.optim.AdamW(groups, lr=lr, weight_decay=weight_decay)


def set_learning_rates(opt: torch.optim.Optimizer, lr: float, weight_decay: float, emb_lr_scale: float) -> None:
    """이어서 학습할 때 불러온 최적화기 상태의 학습률을 지금 설정으로 맞춘다."""
    for i, group in enumerate(opt.param_groups):
        group["lr"] = lr * (emb_lr_scale if i == 1 else 1.0)
        group["weight_decay"] = weight_decay


def transfer_optimizer_state(opt: torch.optim.Optimizer, net: netlib.ValueNet, old: dict, n_ai: int, src: int) -> None:
    """단계 A 최적화기 상태(묶음 하나, net.named_parameters 순서)를 새 최적화기로 옮긴다.

    임베딩의 모멘트는 src 행을 n_ai 행으로 복사한다. step 은 CPU 에 둔다(PyTorch 의 기본 AdamW 와 같다).
    """
    params = list(net.named_parameters())
    state = old["state"]
    if len(state) != len(params):
        raise ValueError(f"최적화기 상태의 매개변수 수가 다름: {len(state)} != {len(params)}")
    for i, (name, p) in enumerate(params):
        moved = {}
        for k, v in state[i].items():
            if not torch.is_tensor(v):
                moved[k] = v
            elif k == "step":
                moved[k] = v.detach().clone().cpu()
            else:
                if name == EMBEDDING:
                    v = v[src : src + 1].expand(n_ai, -1)
                if v.shape != p.shape:
                    raise ValueError(f"{name}.{k}: 모양 {tuple(v.shape)} != {tuple(p.shape)}")
                moved[k] = v.detach().clone().to(p.device)
        opt.state[p] = moved


# ---------------------------------------------------------------- 대전 계획 (TRAINING.md 6.1, 9.3, 9.5)


def model_file(ai: str, gen: int) -> str:
    """세대 모델 파일 이름 (TRAINING.md 2장, 9.4)."""
    return f"value_{ai}_gen-{gen:03d}.onnx"


def past_name(ai: str, gen: int) -> str:
    return f"{ai}{PAST_SEP}{gen:03d}"


def league_plan(names: list[str], cfg: dict, run: str, gen: int, models_rel: str) -> dict:
    """세대 gen(1 이상)의 학습용 리그 대전 계획 plan-1 (TRAINING.md 9.3). 지금 세대 모델은 세대 gen-1 것이다.

    판 i 는 names[i mod n] 의 판이다. 상대는 지금 세대의 다른 AI(pool_self), 최근 past_window 세대 중 무작위 세대의
    무작위 AI(pool_past), 기준 AI(나머지). 이전 세대가 없으면 그 몫도 지금 세대 AI 다. AI 마다 자기 판에서 자리를 번갈아
    맡는다. models_rel 은 계획 파일이 있는 폴더에서 본 models 폴더 경로다.
    """
    names = list(names)
    n = len(names)
    if n < 2 or gen < 1:
        raise ValueError("AI 가 둘 이상이고 세대가 1 이상이어야 함")
    tau = cfg["temperature"]

    def player(ai: str, g: int) -> dict:
        return {"persona": ai, "model": f"{models_rel}/{model_file(ai, g)}", "temperature": tau}

    players = {ai: player(ai, gen - 1) for ai in names}
    players[BASELINE] = {"model": "baseline", "temperature": tau}
    past = list(range(max(0, gen - 1 - cfg["past_window"]), gen - 1))
    pool_self, pool_past = cfg["pool_self"], cfg["pool_past"]
    rng = np.random.default_rng([cfg["seed"], gen])
    matches = []
    for i in range(cfg["matches"]):
        me = i % n
        u = rng.random()
        if u < pool_self or (not past and u < pool_self + pool_past):
            opp = names[(me + 1 + int(rng.integers(n - 1))) % n]  # 자기 자신을 뺀 n-1 개 중 하나
        elif u < pool_self + pool_past:
            ai, k = names[int(rng.integers(n))], int(rng.choice(past))
            opp = past_name(ai, k)
            players.setdefault(opp, player(ai, k))
        else:
            opp = BASELINE
        p1, p2 = (names[me], opp) if (i // n) % 2 == 0 else (opp, names[me])
        matches.append({"seed": gen * 1_000_000 + i, "p1": p1, "p2": p2})
    return {
        "format": "plan-1", "run": run, "gen": gen, "max_turns": cfg["max_turns"], "threads": cfg["threads"],
        "out": "matches", "players": players, "matches": matches,
    }


def fixed_opponent_plan(models: dict[str, str], opponent: str, opponent_model: str, seeds: Iterable[int], run: str,
                        gen: int, max_turns: int, threads: int) -> dict:
    """AI 마다 같은 상대와 같은 시드로, 자리를 바꿔 두 판씩 두는 계획 (TRAINING.md 9.4 평가, 9.5 분석). 양쪽 τ = 0.

    models 는 {AI 이름: 계획 파일 폴더에서 본 모델 경로}. 상대 모델이 "baseline" 이면 기준 AI 다.
    """
    if opponent in models:
        raise ValueError(f"상대 이름 {opponent} 이 AI 이름과 겹침")
    players = {ai: {"persona": ai, "model": m, "temperature": 0.0} for ai, m in models.items()}
    players[opponent] = {"persona": "neutral", "model": opponent_model, "temperature": 0.0}
    seeds = list(seeds)
    matches = [{"seed": s, "p1": a, "p2": b} for ai in models for s in seeds for a, b in ((ai, opponent), (opponent, ai))]
    return {
        "format": "plan-1", "run": run, "gen": gen, "max_turns": max_turns, "threads": threads,
        "out": "matches", "players": players, "matches": matches,
    }


# ---------------------------------------------------------------- 내보내기 (TRAINING.md 9.4, 9.6, MODEL_IO 8장)


def export_ai(net_cpu: netlib.ValueNet, ai: int, weights: torch.Tensor, gamma: float, prior_weight: float, path: Path,
              persona: str, stage: str, description: str) -> netlib.ScoreModule:
    """AI 번호 ai 의 임베딩과 보상 가중치 weights [항목 수] 를 상수로 넣은 배치 점수 모듈을 ONNX 로 내보낸다."""
    module = netlib.ScoreModule(net_cpu, ai, weights, gamma, prior_weight).eval()
    netlib.export_onnx(module, path, {"tetris.persona": persona, "tetris.stage": stage, "tetris.description": description})
    return module


def verify_onnx(module: netlib.ScoreModule, path: Path, inputs: dict[str, np.ndarray], tol: float = 1e-4) -> float:
    """같은 입력에서 PyTorch 모듈과 onnxruntime 의 점수를 비교한다. 모양이 다르거나 차이가 tol 을 넘으면 멈춘다."""
    import onnxruntime as ort  # 학습·분석 쪽에서만 필요하다

    with torch.no_grad():
        want = module(*(torch.from_numpy(inputs[k]) for k in netlib.INPUT_NAMES)).numpy()
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    (got,) = session.run(["value"], inputs)
    diff = float(np.max(np.abs(got - want)))
    if got.shape != want.shape or diff > tol:
        raise RuntimeError(f"{path.name}: 내보낸 모델이 PyTorch 와 다름: 모양 {got.shape}, 최대 차이 {diff}")
    return diff


def synthetic_inputs(n: int, seed: int) -> dict[str, np.ndarray]:
    """학습 표본이 없을 때(세대 0) 내보내기 확인에 쓰는 입력. 아래쪽이 차 있는 보드와 작은 정수 특징."""
    rng = np.random.default_rng(seed)
    depth = np.arange(HEIGHT)[None, :, None]

    def boards() -> np.ndarray:
        top = rng.integers(8, HEIGHT, size=(n, 1, WIDTH))
        return ((depth >= top) & (rng.random((n, HEIGHT, WIDTH)) < 0.85)).astype(np.float32)

    return {
        "board": boards(),
        "features": rng.integers(0, 3, (n, FEATURES)).astype(np.float32),
        "opp_board": boards(),
        "opp_features": rng.integers(0, 3, (n, OPP_FEATURES)).astype(np.float32),
    }


# ---------------------------------------------------------------- 지표 (TRAINING.md 9.4, 9.5)


def persona_metrics(trajs: list[Trajectory]) -> dict[str, float]:
    """metrics.summarize 의 지표에 페르소나 분석용 지표를 더한다.

    combo_rate         블록당 콤보 클리어 비율 (줄을 지웠고 배치 뒤 콤보 1 이상, TRAINING.md 4.3 의 콤보 항목)
    combo_len          연속 클리어 한 번의 평균 길이 = 줄을 지운 블록 수 ÷ 연속 클리어를 시작한 블록 수(배치 뒤 콤보 0)
    bumpiness_mean     애프터스테이트 보드의 울퉁불퉁함 평균
    pressure_response  상대가 위험할 때 블록당 송신 − 평소 (TRAINING.md 8장 상호작용 지표)
    defense_response   받을 가비지가 많을 때 줄 지우는 비율 − 평소
    """
    out = summarize(trajs)
    if not trajs:
        return out
    feats = np.concatenate([t.features for t in trajs])
    heights, _ = heights_and_holes(np.concatenate([t.boards for t in trajs]))
    clears = feats[:, LINES] > 0
    combo_plus_one = feats[:, COMBO]
    starts = int((clears & (combo_plus_one == 1)).sum())
    out["combo_rate"] = float((clears & (combo_plus_one >= 2)).mean())
    out["combo_len"] = float(clears.sum() / starts) if starts else float("nan")
    out["bumpiness_mean"] = float(np.abs(np.diff(heights, axis=1)).sum(axis=1).mean())
    out["pressure_response"] = out["sent_when_opp_danger"] - out["sent_when_opp_safe"]
    out["defense_response"] = out["clear_when_pressured"] - out["clear_when_calm"]
    return out


def style_return(trajs: list[Trajectory], weights: torch.Tensor) -> float:
    """궤적들의 한 판 스타일 보상 합의 평균 (TRAINING.md 4.3 의 크기 원칙 확인용). weights 는 [항목 수]."""
    if not trajs:
        return float("nan")
    features = torch.from_numpy(np.concatenate([t.features for t in trajs]))
    boards = torch.from_numpy(np.concatenate([t.boards for t in trajs])).float()
    with torch.no_grad():
        r = netlib.reward_terms(features, netlib.board_stats(boards)) @ weights.float().reshape(-1, 1)
    return float(r.sum()) / len(trajs)


def mean_pairwise_distance(emb: np.ndarray) -> float:
    """임베딩 행 사이 유클리드 거리의 평균 (자기 자신 제외). 임베딩이 갈라지는 정도를 세대마다 본다."""
    n = len(emb)
    if n < 2:
        return 0.0
    d = np.linalg.norm(emb[:, None, :] - emb[None, :, :], axis=2)
    return float(d.sum() / (n * (n - 1)))
