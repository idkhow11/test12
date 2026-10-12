"""승률 예측망 형식 winprob-1 의 공통 픽스처와 시험용 모델을 만든다 (docs/MODEL_IO.md 9.7, FIXTURES.md 5.11).

    python python/tools/make_winprob_fixture.py      # winprob_tiny 는 PyTorch 가 필요하다

만드는 파일 (fixtures/winprob/):
    winprob_linear.onnx                 가중치를 식으로 정한 선형 모델 σ(w · (P1 입력 − P2 입력)). 학습하지 않음
    winprob_linear.json                 encode / encode_record / run / metadata 사례
    winprob_linear_wrong_model_io.onnx  꼬리표의 형식이 2 (가치 모델 형식)인 모델 (거부 시험용)
    winprob_linear_wrong_rules.onnx     꼬리표의 규칙 버전만 틀린 모델 (거부 시험용)
    winprob_linear_flat_output.onnx     출력 p1_win 이 [N, 1] 이 아니라 [N] 인 모델 (거부 시험용)
    winprob_tiny.onnx                   학습에 쓰는 구조(WinProbNet)를 아주 작게 만든 모델. 가중치는 시드로 정한 무작위값
    winprob_tiny.json                   run / metadata 사례

run 사례의 기대값은 Python onnxruntime 이 계산한 값이다. 선형 모델은 numpy 직접 계산과, 작은 모델은 PyTorch 와
먼저 비교한다. 선형 모델의 사례 몇 개는 손으로 확인할 수 있다(같은 두 상태 → 0.5 등, 사례의 description).
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai import winprob as wp  # noqa: E402
from tetris_ai.model_io import HEIGHT, RULES_VERSION, WIDTH  # noqa: E402

OUT_DIR = ROOT / "fixtures" / "winprob"
OPSET = 17
TOLERANCE = 1e-5

LINEAR_METADATA = {
    "tetris.model_io": wp.FORMAT,
    "tetris.rules": RULES_VERSION,
    "tetris.stage": "test-linear",
    "tetris.description": "시험용 선형 승률 모델. 가중치는 make_winprob_fixture.py 의 식으로 정했고 학습하지 않았다.",
}


def linear_weights() -> tuple[np.ndarray, np.ndarray]:
    """칸·특징마다 다른 값. 순서가 틀리거나 P1·P2 가 바뀌면 결과가 달라진다."""
    w_board = np.array([((i % 7) - 3) * 0.01 for i in range(HEIGHT * WIDTH)], dtype=np.float32).reshape(-1, 1)
    w_features = np.array([((i % 5) - 2) * 0.05 for i in range(wp.FEATURES)], dtype=np.float32).reshape(-1, 1)
    return w_board, w_features


def build_linear(metadata: dict[str, str] = LINEAR_METADATA, flat_output: bool = False) -> onnx.ModelProto:
    """p1_win = σ(w_board · (b1 − b2) + w_features · (f1 − f2)). flat_output=True 이면 [N] 으로 내보낸다(거부 시험용)."""
    w_board, w_features = linear_weights()
    inputs = [
        helper.make_tensor_value_info("p1_board", TensorProto.FLOAT, ["N", HEIGHT, WIDTH]),
        helper.make_tensor_value_info("p1_features", TensorProto.FLOAT, ["N", wp.FEATURES]),
        helper.make_tensor_value_info("p2_board", TensorProto.FLOAT, ["N", HEIGHT, WIDTH]),
        helper.make_tensor_value_info("p2_features", TensorProto.FLOAT, ["N", wp.FEATURES]),
    ]
    output = helper.make_tensor_value_info("p1_win", TensorProto.FLOAT, ["N"] if flat_output else ["N", 1])
    inits = [
        numpy_helper.from_array(np.array([-1, HEIGHT * WIDTH], dtype=np.int64), "flat_shape"),
        numpy_helper.from_array(w_board, "w_board"),
        numpy_helper.from_array(w_features, "w_features"),
    ]
    nodes = [
        helper.make_node("Sub", ["p1_board", "p2_board"], ["board_diff"]),
        helper.make_node("Reshape", ["board_diff", "flat_shape"], ["board_diff_flat"]),
        helper.make_node("MatMul", ["board_diff_flat", "w_board"], ["board_score"]),
        helper.make_node("Sub", ["p1_features", "p2_features"], ["features_diff"]),
        helper.make_node("MatMul", ["features_diff", "w_features"], ["features_score"]),
        helper.make_node("Add", ["board_score", "features_score"], ["logit"]),
    ]
    last = "p1_win_2d" if flat_output else "p1_win"
    nodes.append(helper.make_node("Sigmoid", ["logit"], [last]))
    if flat_output:
        inits.append(numpy_helper.from_array(np.array([-1], dtype=np.int64), "flat_out_shape"))
        nodes.append(helper.make_node("Reshape", ["p1_win_2d", "flat_out_shape"], ["p1_win"]))
    graph = helper.make_graph(nodes, "tetris_winprob_linear", inputs, [output], inits)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", OPSET)], producer_name="make_winprob_fixture.py")
    model.ir_version = 9  # ONNX Runtime 1.15 이상이 읽을 수 있는 IR 버전
    for k, v in metadata.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = k, v
    onnx.checker.check_model(model)
    return model


def linear_closed_form(inputs: dict[str, np.ndarray]) -> np.ndarray:
    """onnxruntime 과 비교할 numpy 직접 계산 (float64)."""
    w_board, w_features = (w.astype(np.float64) for w in linear_weights())
    n = inputs["p1_board"].shape[0]
    logit = (inputs["p1_board"] - inputs["p2_board"]).reshape(n, -1).astype(np.float64) @ w_board
    logit += (inputs["p1_features"] - inputs["p2_features"]).astype(np.float64) @ w_features
    return 1.0 / (1.0 + np.exp(-logit))


# 사례로 쓸 상태 (MODEL_IO.md 9.2). 블록이 모두 달라야 원-핫 순서가 틀렸을 때 드러난다.
STATES = {
    "empty": {"board_bottom": [], "current": "T", "queue": ["I", "O", "S", "L", "Z"]},
    "empty-pending4": {"board_bottom": [], "current": "T", "queue": ["I", "O", "S", "L", "Z"], "pending": 4},
    "one-cell": {"board_bottom": ["#........."], "current": "T", "queue": ["I", "O", "S", "L", "Z"]},
    "rich": {
        "board_top": ["..........", "..........", "..........", "...##....."],
        "board_bottom": ["#.#.......", "##.#......", "G########.", "GGGG.GGGGG"],
        "current": "Z",
        "queue": ["I", "J", "L", "O", "S"],
        "hold": "T",
        "combo": 2,
        "b2b": True,
        "pending": 5,
    },
    "short-queue": {
        "board_bottom": ["....##....", "...####..#"],
        "current": "O",
        "queue": ["J", "S", "T"],
        "hold": None,
        "combo": -1,
        "b2b": False,
        "pending": 0,
    },
    "tall": {
        "board_bottom": ["#........#"] * 6 + [".#########"] * 10,
        "current": "I",
        "queue": ["L", "L", "Z", "T", "O"],
        "hold": "I",
        "combo": 0,
        "b2b": False,
        "pending": 12,
    },
}

# encode_record: Java 대전 러너가 쓴 fixtures/match/basic.json 의 state (판 id, 턴, 자리).
RECORD_STATES = [("baseline-cap", 7, "p1"), ("baseline-cap", 7, "p2"), ("random-topout", 0, "p1"),
                 ("random-topout", 22, "p2")]


def record_states() -> list[tuple[str, dict]]:
    doc = json.loads((ROOT / "fixtures" / "match" / "basic.json").read_text(encoding="utf-8"))
    cases = {c["id"]: c["match"] for c in doc["cases"]}
    return [(f"{cid}-t{t}-{side}", cases[cid]["steps"][t][side]["state"]) for cid, t, side in RECORD_STATES]


def pairs_inputs(pairs: list[tuple[str, str]]) -> dict[str, np.ndarray]:
    return wp.encode_pairs([(wp.state_from_json(STATES[a]), wp.state_from_json(STATES[b])) for a, b in pairs])


def run_case(case_id: str, pairs: list[tuple[str, str]], values: list[float], description: str | None = None) -> dict:
    case = {"id": case_id, "kind": "run"}
    if description:
        case["description"] = description
    case["pairs"] = [{"p1": STATES[a], "p2": STATES[b]} for a, b in pairs]
    case["expect"] = {"values": values, "tolerance": TOLERANCE}
    return case


def write_fixture(name: str, model: str, description: str, cases: list[dict]) -> Path:
    fixture = {
        "rules_version": RULES_VERSION,
        "type": "winprob",
        "description": description,
        "source": ["python"],
        "model": model,
        "cases": cases,
    }
    path = OUT_DIR / name
    path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return path


def make_linear() -> None:
    path = OUT_DIR / "winprob_linear.onnx"
    onnx.save(build_linear(), path)
    # Java 가 거부해야 하는 모델들 (WinRateTest).
    onnx.save(build_linear({**LINEAR_METADATA, "tetris.model_io": "2"}), OUT_DIR / "winprob_linear_wrong_model_io.onnx")
    onnx.save(build_linear({**LINEAR_METADATA, "tetris.rules": "0.1"}), OUT_DIR / "winprob_linear_wrong_rules.onnx")
    onnx.save(build_linear(flat_output=True), OUT_DIR / "winprob_linear_flat_output.onnx")

    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    meta = session.get_modelmeta().custom_metadata_map
    if meta != LINEAR_METADATA:
        raise SystemExit(f"꼬리표가 다름: {meta}")

    def run(pairs: list[tuple[str, str]]) -> list[float]:
        inputs = pairs_inputs(pairs)
        (values,) = session.run([wp.OUTPUT_NAME], inputs)
        if values.shape != (len(pairs), 1):
            raise SystemExit(f"출력 모양이 [N, 1] 이 아님: {values.shape}")
        diff = np.max(np.abs(values.astype(np.float64) - linear_closed_form(inputs)))
        if diff > TOLERANCE:
            raise SystemExit(f"onnxruntime 결과와 직접 계산이 다름: 최대 차이 {diff}")
        return [round(float(v), 7) for v in values[:, 0]]

    # 손 계산과 맞는지 먼저 확인한다. w_features[51] = (51 % 5 − 2)·0.05 = −0.05,
    # w_board[230](23 행 0 열) = (230 % 7 − 3)·0.01 = 0.03.
    hand = {
        ("empty", "empty"): 0.5,
        ("empty-pending4", "empty"): 1 / (1 + math.exp(0.2)),
        ("empty", "one-cell"): 1 / (1 + math.exp(0.03)),
    }
    for pair, want in hand.items():
        got = run([pair])[0]
        if abs(got - want) > 1e-6:
            raise SystemExit(f"손 계산과 다름 {pair}: {got} != {want}")

    cases = []
    for name, d in STATES.items():
        s = wp.state_from_json(d)
        cases.append({
            "id": f"encode-{name}",
            "kind": "encode",
            "state": d,
            "expect": {"board_filled": int(wp.encode_board(s).sum()), "features": [int(x) for x in wp.encode_features(s)]},
        })
    for case_id, st in record_states():
        board, feats = wp.encode_record(st)
        # 두 Python 경로(기록에서 바로, PlayerState 를 거쳐)가 같은지 먼저 본다.
        s = wp.state_from_record(st)
        if not (np.array_equal(wp.encode_board(s), board.astype(np.float32)) and np.array_equal(wp.encode_features(s), feats)):
            raise SystemExit(f"기록 인코딩 경로가 서로 다름: {case_id}")
        # 기대 보드는 rows 를 푼 보드를 4장 표기(board_bottom, 맨 위 빈 행은 생략)로 적는다. 이 값과 비교해야
        # rows 를 푸는 방법(비트 c 가 c 열, 0 행이 맨 위)이 언어마다 같은지 드러난다.
        rows = ["".join("#" if v else "." for v in row) for row in board]
        first = next((r for r, row in enumerate(rows) if "#" in row), HEIGHT)
        cases.append({
            "id": f"encode-record-{case_id}",
            "kind": "encode_record",
            "state": st,
            "expect": {"board_bottom": rows[first:], "board_filled": int(board.sum()),
                       "features": [int(x) for x in feats]},
        })
    cases.append(run_case("run-same-state", [("rich", "rich")], run([("rich", "rich")]),
                          "같은 두 상태: 로짓 0, p1_win = 0.5"))
    cases.append(run_case("run-pending4", [("empty-pending4", "empty")], run([("empty-pending4", "empty")]),
                          "P1 의 대기 가비지 4 줄만 다름: σ(−0.05 · 4) = 0.450166"))
    cases.append(run_case("run-one-cell", [("empty", "one-cell")], run([("empty", "one-cell")]),
                          "P2 의 23 행 0 열만 다름: σ(−0.03) = 0.492501"))
    batch = [("rich", "short-queue"), ("short-queue", "rich"), ("tall", "empty"), ("empty", "tall"), ("rich", "tall")]
    cases.append(run_case("run-batch", batch, run(batch), "자리를 바꾼 쌍은 합이 1"))
    cases.append({"id": "metadata", "kind": "metadata",
                  "expect": {k: meta[k] for k in ("tetris.model_io", "tetris.rules", "tetris.stage")}})
    out = write_fixture("winprob_linear.json", path.name,
                        "MODEL_IO.md 9장 winprob-1 의 인코딩과 시험용 선형 모델 실행 (python/tools/make_winprob_fixture.py 로 생성)",
                        cases)
    print(f"모델 {path.relative_to(ROOT)} ({path.stat().st_size} bytes), 사례 {out.relative_to(ROOT)}: {len(cases)}개")


def make_tiny() -> None:
    import torch

    torch.manual_seed(7)
    net = wp.WinProbNet(channels=(2, 2, 2), board_hidden=4, feat_hidden=4, player_dim=4, head_hidden=(4, 4))
    # 기본 초기화로는 출력이 0.5 근처(0.49~0.51)에 몰려 연산 차이가 값에 잘 드러나지 않는다.
    # 모든 가중치 행렬을 3 배로 키우면 사례의 출력이 0.09~0.91 로 퍼진다.
    with torch.no_grad():
        for p in net.parameters():
            if p.dim() > 1:
                p.mul_(3.0)
    module = wp.ProbModule(net).eval()
    path = OUT_DIR / "winprob_tiny.onnx"
    wp.export_onnx(module, path, {
        "tetris.stage": "test-tiny",
        "tetris.description": "Java 실행 확인용 작은 승률 모델. 가중치는 make_winprob_fixture.py 의 시드로 정한 무작위값이다.",
    })
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    meta = session.get_modelmeta().custom_metadata_map

    def run(pairs: list[tuple[str, str]]) -> list[float]:
        inputs = pairs_inputs(pairs)
        (values,) = session.run([wp.OUTPUT_NAME], inputs)
        with torch.no_grad():
            want = module(*(torch.from_numpy(inputs[k]) for k in wp.INPUT_NAMES)).numpy()
        diff = float(np.max(np.abs(values - want)))
        if values.shape != (len(pairs), 1) or diff > TOLERANCE:
            raise SystemExit(f"onnxruntime 과 PyTorch 가 다름: {values.shape}, 최대 차이 {diff}")
        return [round(float(v), 7) for v in values[:, 0]]

    batch = [("rich", "short-queue"), ("short-queue", "rich"), ("tall", "empty"), ("empty", "tall"), ("rich", "tall"),
             ("empty-pending4", "empty"), ("rich", "rich"), ("one-cell", "empty")]
    cases = [
        run_case("run-batch", batch, run(batch), "자리를 바꾼 쌍은 합이 1, 같은 두 상태는 0.5 (구조로 보장, TRAINING.md 10.3)"),
        run_case("run-single", [("tall", "rich")], run([("tall", "rich")])),
        {"id": "metadata", "kind": "metadata", "expect": {k: meta[k] for k in ("tetris.model_io", "tetris.rules", "tetris.stage")}},
    ]
    out = write_fixture("winprob_tiny.json", path.name,
                        "학습에 쓰는 구조(WinProbNet)의 작은 승률 모델을 Java ONNX Runtime 이 Python 과 같은 값으로 실행하는지 "
                        "(python/tools/make_winprob_fixture.py 로 생성)", cases)
    print(f"모델 {path.relative_to(ROOT)} ({path.stat().st_size} bytes, 매개변수 {wp.parameter_count(net)}개), "
          f"사례 {out.relative_to(ROOT)}: {len(cases)}개")
    for case in cases:
        if case["kind"] == "run":
            print(f"  {case['id']}: {case['expect']['values']}")


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    make_linear()
    make_tiny()


if __name__ == "__main__":
    main()
