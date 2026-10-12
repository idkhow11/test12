package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 승률 막대 보정층 (TRAINING.md 10.12). 식이 Python 의 {@code calibrate()} 와 같은 값인지(fixtures/winprob_calibration 의
 * 모든 보정 식 사례, 허용 오차 1e-12), 자리 대칭, 경계 처리, 파일 읽기와 모델 짝 짓기. ONNX Runtime 이 필요 없다.
 */
class WinCalibrationTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	// ------------------------------------------------------------------ Python 과 같은 값

	@Test
	void formulaMatchesPythonForEveryFixtureCase() throws IOException {
		WinCalibrationFixture fx = WinCalibrationFixture.read();
		double tolerance = fx.tolerance("p_cal");
		assertTrue(tolerance <= 1e-9, "보정 식은 double 계산이라 1e-9 보다 엄격해야 함: " + tolerance);
		Map<String, WinCalibration> cals = new HashMap<>();
		fx.specs.keySet().forEach(name -> cals.put(name, fx.calibration(name)));
		Map<String, Integer> perSpec = new HashMap<>();
		Set<String> ids = new HashSet<>();
		double worst = 0;
		for (JsonNode c : fx.formula) {
			String id = c.get("id").asText();
			assertTrue(ids.add(id), "id 중복: " + id);
			String spec = c.get("spec").asText();
			double got = cals.get(spec).apply(c.get("p").asDouble(), c.get("n1").asInt(), c.get("n2").asInt(),
					c.get("t_ms").asLong());
			double want = c.get("p_cal").asDouble();
			assertEquals(want, got, tolerance, spec + " " + id);
			worst = Math.max(worst, Math.abs(got - want));
			perSpec.merge(spec, 1, Integer::sum);
		}
		// 게임 묶음의 보정층, 모든 항을 쓰는 일반 식, 구간 온도(k > 0, k = 0)를 모두 확인했는가
		for (String name : new String[] { "bundle", "all_terms", "rbin", "rbin_k0" }) {
			assertTrue(perSpec.getOrDefault(name, 0) >= 50, name + " 사례 " + perSpec.get(name) + "개");
		}
		assertEquals("general", cals.get("all_terms").kind());
		assertEquals("rbin_temperature", cals.get("rbin").kind());
		System.out.printf("보정 식 사례 %d개, Python 과의 차이 최대 %.3g%n", fx.formula.size(), worst);
	}

	@Test
	void realStateCasesUseTheSameFormula() throws IOException {
		// 상태 쌍 사례의 p(onnxruntime 출력)를 보정한 값도 Python 과 같다. 모델 실행은 WinRateCalibrationTest 가 본다.
		WinCalibrationFixture fx = WinCalibrationFixture.read();
		WinCalibration bundle = fx.calibration("bundle");
		assertTrue(fx.states.size() >= 100, "상태 쌍 " + fx.states.size() + "개");
		for (JsonNode c : fx.states) {
			assertEquals(c.get("p_cal").asDouble(), bundle.apply(c.get("p").asDouble(), c.get("n1").asInt(),
					c.get("n2").asInt(), c.get("t_ms").asLong()), fx.tolerance("p_cal"), c.get("id").asText());
		}
	}

	@Test
	void swappingPlayersGivesOneMinusP() throws IOException {
		WinCalibrationFixture fx = WinCalibrationFixture.read();
		for (String name : fx.specs.keySet()) {
			WinCalibration cal = fx.calibration(name);
			for (JsonNode c : fx.formula) {
				double p = c.get("p").asDouble();
				int n1 = c.get("n1").asInt();
				int n2 = c.get("n2").asInt();
				long t = c.get("t_ms").asLong();
				assertEquals(1.0, cal.apply(p, n1, n2, t) + cal.apply(1.0 - p, n2, n1, t), 1e-12,
						name + " " + c.get("id").asText());
			}
		}
	}

	// ------------------------------------------------------------------ 경계

	@Test
	void startOfGameAndGuards() throws IOException {
		WinCalibration cal = WinCalibrationFixture.read().calibration("bundle");
		assertEquals(0.5, cal.apply(0.5, 0, 0, 0), 0.0, "판 시작은 정확히 0.5");
		assertEquals(0.5, cal.apply(0.5, 7, 7, 12_000), 0.0, "블록 수가 같고 p = 0.5 면 0.5");
		assertTrue(Double.isNaN(cal.apply(Double.NaN, 3, 4, 1000)), "NaN 은 그대로(막대를 숨긴다)");
		assertEquals(0.73, cal.apply(0.73, -1, 4, 1000), 0.0, "블록 수를 모르면 그대로");
		assertEquals(0.73, cal.apply(0.73, 4, -1, 1000), 0.0);
		assertEquals(0.73, cal.apply(0.73, 4, 4, -1), 0.0, "시간이 음수면 그대로");
		for (double p : new double[] { 0.0, 1.0 }) { // float32 출력이 정확히 0, 1 이어도 eps 로 잘라 유한하다
			double v = cal.apply(p, 10, 10, 20_000);
			assertTrue(v > 0 && v < 1, "p " + p + " → " + v);
		}
		assertEquals(cal.apply(1.0, 10, 10, 20_000), cal.apply(1.5, 10, 10, 20_000), 0.0, "범위 밖도 Python 처럼 자른다");
		assertTrue(cal.apply(0.5, 10, 0, 5_000) > 0.5, "블록을 더 놓은 쪽으로 기운다");
		assertTrue(cal.apply(0.5, 0, 10, 5_000) < 0.5);
		assertTrue(cal.apply(0.9, 1_000_000, 0, Long.MAX_VALUE) <= 1.0, "아주 큰 값도 넘치지 않는다");
	}

	@Test
	void overflowFallsBackToTheModelOutput() {
		// 계수가 터무니없이 크면 exp(S) = ∞ 이고 p = 0.5(logit 0)에서 ∞ · 0 = NaN 이다. Python 은 NaN 을 내지만 게임은 p 를 쓴다.
		ObjectNode spec = general();
		((ObjectNode) spec.get("coefficients")).put("s0", 800.0);
		WinCalibration huge = WinCalibration.parse(spec);
		assertEquals(0.5, huge.apply(0.5, 3, 3, 0), 0.0);
		assertEquals(1.0, huge.apply(0.6, 3, 3, 0), 0.0);
	}

	// ------------------------------------------------------------------ 읽기

	/** fit_winprob_calibration.py 가 쓰는 모양의 일반 식 (계수는 speed5 와 비슷한 값). */
	private static ObjectNode general() {
		ObjectNode spec = JSON.createObjectNode();
		spec.put("format", WinCalibration.FORMAT).put("candidate", "speed5").put("kind", "general").put("eps", 1e-6)
				.put("k", 8.0).put("tau_ms", 40_000.0);
		ObjectNode c = spec.putObject("coefficients");
		c.put("s0", 0.66).put("s_r", -1.33).put("s_r2", 0.0).put("s_r3", 0.0).put("s_e", -0.69).put("s_er", 0.0)
				.put("s_er2", 0.0).put("b0", 3.25).put("b_e", 6.94);
		spec.put("model", "wp02");
		return spec;
	}

	private static ObjectNode rbin() {
		ObjectNode spec = JSON.createObjectNode();
		spec.put("format", WinCalibration.FORMAT).put("kind", "rbin_temperature").put("eps", 1e-6).put("k", 2.0);
		ArrayNode edges = spec.putArray("edges");
		for (double e : new double[] { 0.0, 0.25, 0.5, 0.65, 0.8, 0.9 }) {
			edges.add(e);
		}
		ArrayNode scale = spec.putArray("log_scale");
		for (double s : new double[] { 1.2, 0.8, 0.2, -0.1, -0.7, -1.0 }) {
			scale.add(s);
		}
		return spec;
	}

	private static void assertRejected(ObjectNode base, Consumer<ObjectNode> change, String expected) {
		ObjectNode spec = base.deepCopy();
		change.accept(spec);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WinCalibration.parse(spec));
		assertTrue(e.getMessage().contains(expected), expected + " 가 없음: " + e.getMessage());
	}

	@Test
	void rejectsMalformedSpecs() {
		WinCalibration ok = WinCalibration.parse(general());
		assertEquals("speed5", ok.candidate());
		assertEquals("wp02", ok.model());
		assertNull(ok.modelSha256());
		assertEquals("rbin_temperature", WinCalibration.parse(rbin()).candidate(), "candidate 가 없으면 kind");
		assertRejected(general(), s -> s.put("format", "winprob-calibration-2"), "format");
		assertRejected(general(), s -> s.remove("format"), "format");
		assertRejected(general(), s -> s.put("kind", "spline"), "kind");
		assertRejected(general(), s -> ((ObjectNode) s.get("coefficients")).remove("b_e"), "b_e");
		assertRejected(general(), s -> ((ObjectNode) s.get("coefficients")).put("s0", "0.66"), "s0");
		assertRejected(general(), s -> s.put("k", 0.0), "k > 0");
		assertRejected(general(), s -> s.put("tau_ms", 0.0), "tau_ms");
		assertRejected(general(), s -> s.remove("tau_ms"), "tau_ms");
		assertRejected(general(), s -> s.put("eps", 0.5), "eps");
		assertRejected(general(), s -> s.put("model_sha256", "abc"), "model_sha256");
		assertRejected(general(), s -> s.put("model", 2), "model");
		assertRejected(rbin(), s -> s.putArray("edges").add(0.0).add(0.5).add(0.25), "오름차순");
		assertRejected(rbin(), s -> s.putArray("log_scale"), "log_scale");
		assertRejected(rbin(), s -> s.put("k", -1.0), "k");
		assertThrows(IllegalArgumentException.class, () -> WinCalibration.parse(JSON.createArrayNode()));
		assertThrows(IllegalArgumentException.class, () -> WinCalibration.parse(null));
	}

	@Test
	void loadReadsTheBundleFileShape(@TempDir Path dir) throws IOException {
		// calibration.json 처럼 계산에 쓰지 않는 항목(맞춘 자료, 성적, 설명)이 있어도 읽는다. 해시는 소문자로 맞춘다.
		ObjectNode spec = general();
		spec.put("model_sha256", "AB".repeat(32));
		spec.putObject("fit").put("games", 8400);
		spec.putArray("formula").add("pc = min(max(p, eps), 1 - eps)");
		Path file = dir.resolve("winprob_calibration.json");
		Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(spec), StandardCharsets.UTF_8);
		WinCalibration cal = WinCalibration.load(file);
		assertEquals("ab".repeat(32), cal.modelSha256());
		assertEquals(WinCalibration.parse(general()).apply(0.8, 30, 20, 25_000), cal.apply(0.8, 30, 20, 25_000), 0.0);
		Files.writeString(file, "{\"format\": ", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> WinCalibration.load(file), "JSON 이 아니면 IOException");
	}

	// ------------------------------------------------------------------ 모델과 짝 짓기

	@Test
	void calibrationFileSitsNextToTheModel() {
		assertEquals(Path.of("models", "winprob_calibration.json"), WinCalibration.fileFor(Path.of("models", "winprob.onnx")));
		assertEquals(Path.of("runs", "wp02", "winprob_wp02_calibration.json"),
				WinCalibration.fileFor(Path.of("runs", "wp02", "winprob_wp02.onnx")));
		assertEquals(Path.of("winprob_calibration.json"), WinCalibration.fileFor(Path.of("winprob.onnx")));
		assertEquals(Path.of("m", "model_calibration.json"), WinCalibration.fileFor(Path.of("m", "model")));
	}

	@Test
	void sha256IsTheUsualHexDigest(@TempDir Path dir) throws IOException {
		Path f = dir.resolve("abc.bin");
		Files.write(f, "abc".getBytes(StandardCharsets.US_ASCII));
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", WinCalibration.sha256(f));
	}

	@Test
	void forModelUsesOnlyTheCalibrationOfThatModel(@TempDir Path dir) throws IOException {
		Path model = dir.resolve("winprob.onnx");
		Files.write(model, new byte[] { 1, 2, 3, 4 }); // 짝 짓기는 바이트만 본다
		Path file = WinCalibration.fileFor(model);
		assertNull(WinCalibration.forModel(model), "보정 파일이 없으면 null(보정하지 않음)");

		ObjectNode spec = general();
		Files.writeString(file, JSON.writeValueAsString(spec), StandardCharsets.UTF_8);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WinCalibration.forModel(model));
		assertTrue(e.getMessage().contains("model_sha256"), e.getMessage());

		spec.put("model_sha256", "0".repeat(64));
		Files.writeString(file, JSON.writeValueAsString(spec), StandardCharsets.UTF_8);
		e = assertThrows(IllegalArgumentException.class, () -> WinCalibration.forModel(model));
		assertTrue(e.getMessage().contains("sha256"), e.getMessage());

		spec.put("model_sha256", WinCalibration.sha256(model));
		Files.writeString(file, JSON.writeValueAsString(spec), StandardCharsets.UTF_8);
		WinCalibration cal = WinCalibration.forModel(model);
		assertEquals(WinCalibration.sha256(model), cal.modelSha256());

		Files.write(model, new byte[] { 1, 2, 3, 5 }); // 모델을 바꾸면 이전 보정층은 쓰지 않는다
		assertThrows(IllegalArgumentException.class, () -> WinCalibration.forModel(model));
	}
}
