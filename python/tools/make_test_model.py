"""시험용 선형 모델과 공통 픽스처를 만든다 (docs/MODEL_IO.md 7장).

    python python/tools/make_test_model.py

만드는 파일:
    fixtures/model_io/linear_v2.onnx               가중치를 식으로 정한 선형 모델 (학습하지 않음)
    fixtures/model_io/linear_v2.json               encode / encode_opponent / run / metadata 사례
    fixtures/model_io/linear_v2_wrong_rules.onnx     꼬리표의 규칙 버전만 틀린 모델 (거부 시험용)
    fixtures/model_io/linear_v2_wrong_model_io.onnx  꼬리표의 형식 버전만 틀린 모델 (거부 시험용)
    fixtures/model_io/linear_v2_flat_output.onnx     출력 value 가 [N, 1] 이 아니라 [N] 인 모델 (거부 시험용)
    fixtures/model_io/linear_v2_two_inputs.onnx      꼬리표는 형식 2 인데 입력이 형식 1 처럼 두 개뿐인 모델 (거부 시험용)

run 사례의 기대값은 Python onnxruntime 이 계산한 값이다. 같은 값을 numpy 로 직접 계산해
두 값이 일치하는지 먼저 확인하고, Java 쪽 테스트는 이 기대값과 자기 onnxruntime 결과를 비교한다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))

from tetris_ai.model_io import (  # noqa: E402
    FEATURES,
    HEIGHT,
    MODEL_IO_VERSION,
    OPP_FEATURES,
    RULES_VERSION,
    WIDTH,
    afterstate_from_json,
    encode_batch,
    encode_board,
    encode_features,
    encode_opponent_board,
    encode_opponent_features,
    opponent_from_json,
)

OUT_DIR = ROOT / "fixtures" / "model_io"
MODEL_NAME = "linear_v2.onnx"
OPSET = 17
TOLERANCE = 1e-5

METADATA = {
    "tetris.model_io": MODEL_IO_VERSION,
    "tetris.rules": RULES_VERSION,
    "tetris.persona": "neutral",
    "tetris.stage": "test-linear",
    "tetris.description": "시험용 선형 모델. 가중치는 make_test_model.py 의 식으로 정했고 학습하지 않았다.",
}


def weights() -> dict[str, np.ndarray]:
    """칸·특징마다 다른 값이 되도록 식으로 정한 가중치. 순서가 틀리거나 내 것과 상대 것이 바뀌면 결과가 달라진다."""
    return {
        "w_board": np.array([((i % 7) - 3) * 0.01 for i in range(HEIGHT * WIDTH)], dtype=np.float32).reshape(-1, 1),
        "w_features": np.array([((i % 5) - 2) * 0.05 for i in range(FEATURES)], dtype=np.float32).reshape(-1, 1),
        "w_opp_board": np.array([((i % 11) - 5) * 0.003 for i in range(HEIGHT * WIDTH)], dtype=np.float32).reshape(-1, 1),
        "w_opp_features": np.array([(i - 4) * 0.03 for i in range(OPP_FEATURES)], dtype=np.float32).reshape(-1, 1),
        "bias": np.array([0.25], dtype=np.float32),
    }


def build_model(metadata: dict[str, str] = METADATA, flat_output: bool = False, two_inputs: bool = False) -> onnx.ModelProto:
    """flat_output=True 이면 value 를 [N, 1] 대신 [N] 으로 내보낸다(MODEL_IO.md 4장 위반, 거부 시험용).
    two_inputs=True 이면 상대 입력 없이 board, features 만 받는다(MODEL_IO.md 3장 위반, 거부 시험용)."""
    w = weights()
    inputs = [
        helper.make_tensor_value_info("board", TensorProto.FLOAT, ["N", HEIGHT, WIDTH]),
        helper.make_tensor_value_info("features", TensorProto.FLOAT, ["N", FEATURES]),
    ]
    if not two_inputs:
        inputs += [
            helper.make_tensor_value_info("opp_board", TensorProto.FLOAT, ["N", HEIGHT, WIDTH]),
            helper.make_tensor_value_info("opp_features", TensorProto.FLOAT, ["N", OPP_FEATURES]),
        ]
    value = helper.make_tensor_value_info("value", TensorProto.FLOAT, ["N"] if flat_output else ["N", 1])
    inits = [numpy_helper.from_array(np.array([-1, HEIGHT * WIDTH], dtype=np.int64), "flat_shape")]
    inits += [numpy_helper.from_array(w[k], k) for k in ("w_board", "w_features", "bias")]
    nodes = [
        helper.make_node("Reshape", ["board", "flat_shape"], ["board_flat"]),
        helper.make_node("MatMul", ["board_flat", "w_board"], ["board_score"]),
        helper.make_node("MatMul", ["features", "w_features"], ["feature_score"]),
        helper.make_node("Add", ["board_score", "feature_score"], ["mine"]),
    ]
    last_sum = "mine"
    if not two_inputs:
        inits += [numpy_helper.from_array(w[k], k) for k in ("w_opp_board", "w_opp_features")]
        nodes += [
            helper.make_node("Reshape", ["opp_board", "flat_shape"], ["opp_board_flat"]),
            helper.make_node("MatMul", ["opp_board_flat", "w_opp_board"], ["opp_board_score"]),
            helper.make_node("MatMul", ["opp_features", "w_opp_features"], ["opp_feature_score"]),
            helper.make_node("Add", ["opp_board_score", "opp_feature_score"], ["theirs"]),
            helper.make_node("Add", ["mine", "theirs"], ["both"]),
        ]
        last_sum = "both"
    last = "value_2d" if flat_output else "value"
    nodes.append(helper.make_node("Add", [last_sum, "bias"], [last]))
    if flat_output:
        inits.append(numpy_helper.from_array(np.array([-1], dtype=np.int64), "value_flat_shape"))
        nodes.append(helper.make_node("Reshape", ["value_2d", "value_flat_shape"], ["value"]))
    graph = helper.make_graph(nodes, "tetris_linear_v2", inputs, [value], inits)
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", OPSET)], producer_name="make_test_model.py")
    model.ir_version = 9  # ONNX Runtime 1.15 이상이 읽을 수 있는 IR 버전
    for k, v in metadata.items():
        entry = model.metadata_props.add()
        entry.key, entry.value = k, v
    onnx.checker.check_model(model)
    return model


# 사례로 쓸 애프터스테이트. 칸이 흩어져 있어야 행·열 순서가 틀렸을 때 값이 달라진다.
SAMPLES = {
    "empty": {"board_bottom": [], "queue": ["T", "I", "O", "S", "L"]},
    "tsd-after": {
        "board_bottom": ["####......", ".#########"],
        "queue": ["I", "J", "L"],
        "hold": "T",
        "combo": 2,
        "b2b": True,
        "lines": 2,
        "attack": 5,
        "pending": 0,
    },
    "garbage-rises": {
        "board_top": ["..........", "..........", "..........", "....#....."],
        "board_bottom": ["#.#.#.#.#.", ".#.#.#.#.#", "##########"],
        "queue": ["Z", "S", "O", "J"],
        "hold": "S",
        "combo": -1,
        "b2b": True,
        "lines": 0,
        "attack": 0,
        "pending": 3,
    },
    # 테트리스(4) + B2B(1) + COMBO[10](5) = 10. 실제로 나올 수 있는 값만 쓴다(RULES 9.3).
    "big-combo": {
        "board_bottom": ["#########.", "#########.", "#.#######."],
        "queue": ["L", "L", "Z", "T", "O"],
        "combo": 10,
        "b2b": True,
        "lines": 4,
        "attack": 10,
        "pending": 1,
    },
}

# 사례로 쓸 상대 상태 (MODEL_IO.md 2.2). 숨김 영역 행과 가비지 칸, 비대칭 모양을 넣어 순서 오류가 드러나게 한다.
OPPONENTS = {
    "danger": {
        "board_top": ["..........", "..........", "..........", "..........", "...##....."],
        "board_bottom": ["#.#.......", "##.#......", "###.#.....", "####.#....", "G########."],
        "pending": 6,
        "combo": 3,
        "b2b": True,
        "hold": "T",
    },
    "calm": {"board_bottom": ["......####"], "pending": 0, "combo": -1, "b2b": False, "hold": None},
}


def closed_form(boards, feats, opp_boards, opp_feats) -> np.ndarray:
    """onnxruntime 과 비교할 numpy 직접 계산 (float64)."""
    w = weights()
    n = boards.shape[0]
    out = boards.reshape(n, -1).astype(np.float64) @ w["w_board"].astype(np.float64)
    out += feats.astype(np.float64) @ w["w_features"].astype(np.float64)
    out += opp_boards.reshape(n, -1).astype(np.float64) @ w["w_opp_board"].astype(np.float64)
    out += opp_feats.astype(np.float64) @ w["w_opp_features"].astype(np.float64)
    return out + float(w["bias"][0])


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    model_path = OUT_DIR / MODEL_NAME
    onnx.save(build_model(), model_path)
    # Java 가 거부해야 하는 모델들 (OnnxScorerTest).
    onnx.save(build_model({**METADATA, "tetris.rules": "0.1"}), OUT_DIR / "linear_v2_wrong_rules.onnx")
    onnx.save(build_model({**METADATA, "tetris.model_io": "1"}), OUT_DIR / "linear_v2_wrong_model_io.onnx")
    onnx.save(build_model(flat_output=True), OUT_DIR / "linear_v2_flat_output.onnx")
    onnx.save(build_model(two_inputs=True), OUT_DIR / "linear_v2_two_inputs.onnx")

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    meta = session.get_modelmeta().custom_metadata_map
    # 읽어 온 꼬리표가 쓴 것과 같은지 먼저 확인한다. 그래야 metadata 사례가 "쓴 값을 그대로 읽는지"만이 아니라
    # "필요한 값이 들어 있는지"도 확인하게 된다.
    if meta != METADATA:
        raise SystemExit(f"꼬리표가 다름: {meta}")

    def run(sample_names: list[str], opponent_name: str | None) -> list[float]:
        states = [afterstate_from_json(SAMPLES[s]) for s in sample_names]
        opponent = opponent_from_json(OPPONENTS[opponent_name] if opponent_name else None)
        boards, feats, opp_boards, opp_feats = encode_batch(states, opponent)
        (values,) = session.run(
            ["value"], {"board": boards, "features": feats, "opp_board": opp_boards, "opp_features": opp_feats}
        )
        if values.shape != (len(states), 1):
            raise SystemExit(f"출력 모양이 [N, 1] 이 아님: {values.shape}")
        diff = np.max(np.abs(values.astype(np.float64) - closed_form(boards, feats, opp_boards, opp_feats)))
        if diff > TOLERANCE:
            raise SystemExit(f"onnxruntime 결과와 직접 계산이 다름: 최대 차이 {diff}")
        return [round(float(v), 7) for v in values[:, 0]]

    cases = []
    for name, d in SAMPLES.items():
        a = afterstate_from_json(d)
        cases.append({
            "id": f"encode-{name}",
            "kind": "encode",
            "afterstate": d,
            "expect": {"board_filled": int(encode_board(a).sum()), "features": [int(x) for x in encode_features(a)]},
        })
    for name, d in OPPONENTS.items():
        o = opponent_from_json(d)
        cases.append({
            "id": f"encode-opponent-{name}",
            "kind": "encode_opponent",
            "opponent": d,
            "expect": {
                "board_filled": int(encode_opponent_board(o).sum()),
                "features": [int(x) for x in encode_opponent_features(o)],
            },
        })
    runs = [
        ("run-batch-danger", list(SAMPLES), "danger"),
        ("run-single-calm", ["garbage-rises"], "calm"),
        ("run-single-no-opponent", ["garbage-rises"], None),
    ]
    for case_id, names, opp in runs:
        case = {"id": case_id, "kind": "run", "afterstates": [SAMPLES[s] for s in names]}
        if opp:
            case["opponent"] = OPPONENTS[opp]
        case["expect"] = {"values": run(names, opp), "tolerance": TOLERANCE}
        cases.append(case)
    cases.append({
        "id": "metadata",
        "kind": "metadata",
        "expect": {k: meta[k] for k in ("tetris.model_io", "tetris.rules", "tetris.persona")},
    })

    fixture = {
        "rules_version": RULES_VERSION,
        "type": "model_io",
        "description": "MODEL_IO.md 형식 2 의 인코딩(애프터스테이트, 상대 상태)과 시험용 선형 모델 실행 "
        "(python/tools/make_test_model.py 로 생성)",
        "source": ["python"],
        "model": MODEL_NAME,
        "cases": cases,
    }
    json_path = OUT_DIR / "linear_v2.json"
    json_path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")

    print(f"모델 {model_path.relative_to(ROOT)} ({model_path.stat().st_size} bytes), opset {OPSET}")
    print(f"사례 {json_path.relative_to(ROOT)}: {len(cases)}개")
    for case in cases:
        if case["kind"] == "run":
            print(f"  {case['id']}: {case['expect']['values']}")


if __name__ == "__main__":
    main()
