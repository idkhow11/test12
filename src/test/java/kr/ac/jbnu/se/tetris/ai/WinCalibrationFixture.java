package kr.ac.jbnu.se.tetris.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 공통 픽스처 fixtures/winprob_calibration/cases.jsonl (형식 {@code winprob-calibration-fixture-1}, TRAINING.md 10.12).
 * python/tools/fit_winprob_calibration.py 의 {@code fixture_records} 가 쓰고 python/tests/test_winprob_calibration.py 도 읽는다.
 * 한 줄에 JSON 객체 하나다: 첫 줄은 머리(허용 오차, 사례의 모델), {@code kind} 가 spec 인 줄은 보정층, formula 와 state 인 줄은
 * 사례다. 확장자가 .json 이 아니라서 모든 .json 을 규칙 픽스처로 읽는 FixtureTest 는 이 파일을 읽지 않는다.
 */
final class WinCalibrationFixture {

	static final Path FILE = Path.of(System.getProperty("fixtures.dir", "fixtures"), "winprob_calibration", "cases.jsonl");
	static final String FORMAT = "winprob-calibration-fixture-1";

	private static final ObjectMapper JSON = new ObjectMapper();

	final JsonNode header;
	/** 이름 → 보정층 객체 (calibration.json 의 계산 항목). bundle 이 게임 묶음의 보정층이다. */
	final Map<String, JsonNode> specs;
	/** 보정 식 사례: spec, id, p, n1, n2, t_ms, p_cal. */
	final List<JsonNode> formula;
	/** 실제 상태 쌍 사례: spec, id, p1, p2(기록 state 모양), n1, n2, t_ms, p(onnxruntime), p_cal. */
	final List<JsonNode> states;

	private WinCalibrationFixture(JsonNode header, Map<String, JsonNode> specs, List<JsonNode> formula,
			List<JsonNode> states) {
		this.header = header;
		this.specs = Collections.unmodifiableMap(specs);
		this.formula = Collections.unmodifiableList(formula);
		this.states = Collections.unmodifiableList(states);
	}

	static WinCalibrationFixture read() throws IOException {
		List<String> lines = Files.readAllLines(FILE, StandardCharsets.UTF_8);
		JsonNode header = null;
		Map<String, JsonNode> specs = new LinkedHashMap<>();
		List<JsonNode> formula = new ArrayList<>();
		List<JsonNode> states = new ArrayList<>();
		for (String line : lines) {
			if (line.isBlank()) {
				continue;
			}
			JsonNode node = JSON.readTree(line);
			if (header == null) {
				header = node;
				if (!FORMAT.equals(node.path("format").asText())) {
					throw new IOException(FILE + " 의 첫 줄 format 이 " + FORMAT + " 이 아님");
				}
				continue;
			}
			switch (node.path("kind").asText()) {
			case "spec":
				specs.put(node.get("name").asText(), node.get("spec"));
				break;
			case "formula":
				formula.add(node);
				break;
			case "state":
				states.add(node);
				break;
			default:
				throw new IOException(FILE + ": 모르는 kind " + node.path("kind"));
			}
		}
		if (header == null) {
			throw new IOException(FILE + " 가 비어 있음");
		}
		return new WinCalibrationFixture(header, specs, formula, states);
	}

	/** 머리의 허용 오차 (p_cal, p_onnx, p_cal_end_to_end). */
	double tolerance(String key) {
		JsonNode v = header.path("tolerance").get(key);
		if (v == null || !v.isNumber()) {
			throw new IllegalStateException("픽스처 머리에 tolerance." + key + " 가 없음");
		}
		return v.doubleValue();
	}

	WinCalibration calibration(String name) {
		JsonNode spec = specs.get(name);
		if (spec == null) {
			throw new IllegalStateException("픽스처에 보정층 " + name + " 이 없음: " + specs.keySet());
		}
		return WinCalibration.parse(spec);
	}

	/** 기록 match-1·timed-1 의 state 객체(rows 는 행마다 정수, 비트 c 가 c 열)와 그 순간의 블록 수. */
	static PlayerState recordState(JsonNode s, int pieces) {
		Grid g = new Grid();
		for (int r = 0; r < Grid.HEIGHT; r++) {
			int bits = s.get("rows").get(r).asInt();
			for (int c = 0; c < Grid.WIDTH; c++) {
				if ((bits >> c & 1) != 0) {
					g.set(r, c, Grid.GARBAGE);
				}
			}
		}
		List<PieceType> queue = new ArrayList<>();
		s.get("queue").asText().chars().forEach(ch -> queue.add(PieceType.fromLetter((char) ch)));
		PieceType hold = s.get("hold").isNull() ? null : PieceType.valueOf(s.get("hold").asText());
		return new PlayerState(g, PieceType.valueOf(s.get("current").asText()), queue, hold, s.get("combo").asInt(),
				s.get("b2b").asBoolean(), s.get("pending").asInt(), pieces);
	}
}
