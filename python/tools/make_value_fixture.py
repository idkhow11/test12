"""작은 가치망으로 Java 실행 확인용 픽스처를 만든다 (docs/MODEL_IO.md 7장, FIXTURES.md 5.9).

    python python/tools/make_value_fixture.py      # PyTorch 가 필요하다 (python/requirements-train.txt)

만드는 파일:
    fixtures/model_io/value_tiny.onnx   실제 가치망과 같은 구조·연산(합성곱, 누적합, 보드 통계, 보상 항목, 임베딩)을
                                        아주 작은 크기로 만들어 내보낸 모델. 가중치는 시드로 정한 무작위값이다
    fixtures/model_io/value_tiny.json   run / metadata 사례. 기대값은 Python onnxruntime 이 계산한 값

linear_v2 는 입력 형식만 확인한다. 이 모델은 학습 루프가 내보내는 모델의 연산을 Java ONNX Runtime 이 실행할 수 있는지,
Python 과 같은 값을 내는지 확인한다. PyTorch 버전이 다르면 파일의 바이트는 달라질 수 있다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "python"))
sys.path.insert(0, str(ROOT / "python" / "tools"))

from make_test_model import OPPONENTS, SAMPLES  # noqa: E402
from tetris_ai import net as netlib  # noqa: E402
from tetris_ai.model_io import RULES_VERSION, afterstate_from_json, encode_batch, opponent_from_json  # noqa: E402

OUT_DIR = ROOT / "fixtures" / "model_io"
MODEL_NAME = "value_tiny.onnx"
TOLERANCE = 1e-5
# 모든 보상 항목이 0 이 아니어야 비교·형 변환 연산까지 확인된다.
WEIGHTS = {"attack": 0.1, "tspin": 0.2, "tetris": 0.3, "combo": 0.4, "height": -0.5, "holes": -0.05}


def main() -> None:
    torch.manual_seed(20261001)
    net = netlib.ValueNet(n_ai=2, channels=(2, 2, 2), board_hidden=4, feat_hidden=4, opp_hidden=4, emb_dim=2,
                          head_hidden=4)
    # 기본 초기화의 임베딩은 0 에 가깝고 마지막 층은 0 이라(학습 전 = 기준 평가) 신경망 경로가 값에 드러나지 않는다.
    # 모든 연산이 결과에 영향을 주도록 값을 넣는다.
    with torch.no_grad():
        net.embedding.weight.copy_(torch.tensor([[0.5, -1.0], [2.0, 0.25]]))
        torch.nn.init.normal_(net.head[-1].weight, std=0.5)
        torch.nn.init.normal_(net.head[-1].bias, std=0.5)
    module = netlib.ScoreModule(net, 1, netlib.style_weights(WEIGHTS), gamma=0.9, prior_weight=0.05).eval()
    path = OUT_DIR / MODEL_NAME
    metadata = {
        "tetris.persona": "neutral",
        "tetris.stage": "test-tiny",
        "tetris.description": "Java 실행 확인용 작은 가치망. 가중치는 make_value_fixture.py 의 시드로 정한 무작위값이다.",
    }
    netlib.export_onnx(module, path, metadata)
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    meta = session.get_modelmeta().custom_metadata_map

    def run(names: list[str], opp: str | None) -> list[float]:
        states = [afterstate_from_json(SAMPLES[s]) for s in names]
        inputs = dict(zip(netlib.INPUT_NAMES, encode_batch(states, opponent_from_json(OPPONENTS[opp] if opp else None))))
        (values,) = session.run(["value"], inputs)
        with torch.no_grad():
            want = module(*(torch.from_numpy(inputs[k]) for k in netlib.INPUT_NAMES)).numpy()
        diff = float(np.max(np.abs(values - want)))
        if values.shape != (len(states), 1) or diff > TOLERANCE:
            raise SystemExit(f"onnxruntime 과 PyTorch 가 다름: {values.shape}, 최대 차이 {diff}")
        return [round(float(v), 7) for v in values[:, 0]]

    cases = []
    for case_id, names, opp in [
        ("run-batch-danger", list(SAMPLES), "danger"),
        ("run-single-calm", ["garbage-rises"], "calm"),
        ("run-single-no-opponent", ["garbage-rises"], None),
    ]:
        case = {"id": case_id, "kind": "run", "afterstates": [SAMPLES[s] for s in names]}
        if opp:
            case["opponent"] = OPPONENTS[opp]
        case["expect"] = {"values": run(names, opp), "tolerance": TOLERANCE}
        cases.append(case)
    cases.append({"id": "metadata", "kind": "metadata",
                  "expect": {k: meta[k] for k in ("tetris.model_io", "tetris.rules", "tetris.persona", "tetris.stage")}})
    fixture = {
        "rules_version": RULES_VERSION,
        "type": "model_io",
        "description": "학습 루프와 같은 구조의 작은 가치망을 Java ONNX Runtime 이 Python 과 같은 값으로 실행하는지 "
        "(python/tools/make_value_fixture.py 로 생성)",
        "source": ["python"],
        "model": MODEL_NAME,
        "cases": cases,
    }
    json_path = OUT_DIR / "value_tiny.json"
    json_path.write_text(json.dumps(fixture, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"모델 {path.relative_to(ROOT)} ({path.stat().st_size} bytes, 매개변수 {netlib.parameter_count(net)}개)")
    print(f"사례 {json_path.relative_to(ROOT)}: {len(cases)}개")
    for case in cases:
        if case["kind"] == "run":
            print(f"  {case['id']}: {case['expect']['values']}")


if __name__ == "__main__":
    main()
