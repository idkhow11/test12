package kr.ac.jbnu.se.tetris.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.BeamSearch;
import kr.ac.jbnu.se.tetris.ai.OnnxScorer;

/** 블록 동기 대전 러너와 기록 match-1 (TRAINING.md 6, 7장). 기록은 모두 {@link MatchReplay} 로 엔진과 대조한다. */
class MatchRunnerTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final BaselineScorer BASELINE = new BaselineScorer();

	private static MatchRunner.Player baseline(String name, double temperature) {
		return new MatchRunner.Player(name, "neutral", "baseline", temperature, BASELINE);
	}

	@Test
	void sameSeedGivesSameRecord() {
		ObjectNode a = MatchRunner.play("t", 0, 5, 40, baseline("a", 0.3), baseline("b", 0.3));
		ObjectNode b = MatchRunner.play("t", 0, 5, 40, baseline("a", 0.3), baseline("b", 0.3));
		assertEquals(a, b);
		ObjectNode c = MatchRunner.play("t", 0, 6, 40, baseline("a", 0.3), baseline("b", 0.3));
		assertNotEquals(a.get("steps"), c.get("steps"), "다른 시드면 다른 판");
	}

	@Test
	void recordsReplayExactlyAndFollowBlockSyncOrder() {
		for (long seed = 1; seed <= 4; seed++) {
			MatchReplay.check(MatchRunner.play("t", 0, seed, 60, baseline("a", 0.3), baseline("b", 0.3)));
		}
	}

	@Test
	void nearRandomPlayEndsInTopoutAndReplays() {
		Set<String> results = new HashSet<>();
		for (long seed = 10; seed < 16; seed++) {
			ObjectNode m = MatchRunner.play("t", 0, seed, 400, baseline("a", 50), baseline("b", 50));
			MatchReplay.check(m);
			assertTrue(m.get("turns").asInt() < 400, "거의 무작위로 두면 턴 상한 전에 진다");
			results.add(m.get("result").asText());
		}
		assertTrue(results.contains("p1") || results.contains("p2"), "승패가 난 판이 있어야 함: " + results);
	}

	@Test
	void seatsAreRecordedInOrder() {
		ObjectNode m = MatchRunner.play("run7", 3, 9, 5, baseline("left", 0.0), baseline("right", 1.0));
		assertEquals("run7", m.get("run").asText());
		assertEquals(3, m.get("gen").asInt());
		assertEquals("left", m.get("players").get(0).get("ai").asText());
		assertEquals("right", m.get("players").get(1).get("ai").asText());
		assertEquals(1.0, m.get("players").get(1).get("temperature").asDouble());
		// 탐험이 없는 쪽은 매 턴 1등을 고른다.
		for (JsonNode step : m.get("steps")) {
			assertEquals(0, step.get("p1").get("rank").asInt());
		}
	}

	@Test
	void searchingPlayerIsRecordedAndReplays() {
		// 2수 탐색(TRAINING.md 6.2)은 고르는 방법만 바꾼다. 기록은 같은 형식이고 재생 시험을 그대로 통과한다.
		MatchRunner.Player searching = new MatchRunner.Player("s", "neutral", "baseline", 0.0, BASELINE,
				new BeamSearch(3));
		ObjectNode m = MatchRunner.play("t", 0, 21, 40, searching, baseline("b", 0.0));
		MatchReplay.check(m);
		assertEquals(3, m.get("players").get(0).get("search").get("beam").asInt());
		assertNull(m.get("players").get(1).get("search"), "탐색하지 않는 쪽은 필드가 없다");
		for (JsonNode step : m.get("steps")) {
			JsonNode t = step.get("p1");
			if (!t.get("rank").isNull()) {
				assertEquals(0, t.get("rank").asInt(), "τ = 0 이면 펼친 후보 중 1등");
			}
		}
	}

	@Test
	void planSearchFieldIsChecked() {
		assertNull(MatchMain.search(null));
		assertEquals(4, MatchMain.search(JSON.createObjectNode().put("beam", 4)).beam());
		assertThrows(IllegalArgumentException.class, () -> MatchMain.search(JSON.createObjectNode().put("beam", 0)));
		assertThrows(IllegalArgumentException.class, () -> MatchMain.search(JSON.createObjectNode().put("beam", "8")));
	}

	@Test
	void mainRunsPlanAcrossThreadsAndWritesRecordsAndSummary(@TempDir Path dir) throws Exception {
		ObjectNode plan = JSON.createObjectNode();
		plan.put("format", MatchMain.PLAN_FORMAT);
		plan.put("run", "tmp");
		plan.put("gen", 0);
		plan.put("max_turns", 30);
		plan.put("threads", 3);
		plan.put("out", "matches");
		ObjectNode players = plan.putObject("players");
		players.putObject("a").put("model", "baseline").put("temperature", 0.3);
		players.putObject("b").put("model", "baseline").put("temperature", 0.3).put("persona", "defender");
		ArrayNode matches = plan.putArray("matches");
		for (int i = 0; i < 7; i++) {
			matches.addObject().put("seed", 100 + i).put("p1", i % 2 == 0 ? "a" : "b").put("p2", i % 2 == 0 ? "b" : "a");
		}
		matches.addObject().put("seed", 200).put("p1", "a").put("p2", "a"); // 같은 AI 끼리 한 판
		Path planFile = dir.resolve("plan.json");
		JSON.writeValue(planFile.toFile(), plan);

		MatchMain.main(new String[] { planFile.toString() });

		List<JsonNode> records = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir.resolve("matches"))) {
			for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".jsonl.gz"))::iterator) {
				try (BufferedReader r = new BufferedReader(
						new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
					String line;
					while ((line = r.readLine()) != null) {
						records.add(JSON.readTree(line));
					}
				}
			}
		}
		assertEquals(8, records.size(), "모든 판이 한 줄씩");
		Set<Long> seeds = new HashSet<>();
		for (JsonNode m : records) {
			MatchReplay.check(m);
			seeds.add(m.get("seed").asLong());
		}
		assertEquals(8, seeds.size());
		JsonNode summary = JSON.readTree(dir.resolve("matches/summary.json").toFile());
		assertEquals(8, summary.get("matches").asInt());
		JsonNode a = summary.get("players").get("a");
		JsonNode b = summary.get("players").get("b");
		// a 와 b 가 둔 7 판만 승패로 세고, a 끼리 둔 판은 self_play 로 따로 센다.
		assertEquals(7, a.get("wins").asInt() + a.get("losses").asInt() + a.get("draws").asInt());
		assertEquals(1, a.get("self_play").asInt());
		assertEquals(0, b.get("self_play").asInt());
		assertEquals(a.get("wins").asInt(), b.get("losses").asInt());
		for (JsonNode m : records) {
			for (JsonNode p : m.get("players")) {
				assertEquals(p.get("ai").asText().equals("b") ? "defender" : "neutral", p.get("persona").asText());
			}
		}
	}

	@Test
	void readsBackGzipLinesWithUtf8() throws IOException {
		// 기록에는 한글이 없지만, 계획 파일의 이름에 한글이 들어가도 깨지지 않아야 한다.
		ObjectNode m = MatchRunner.play("한글", 0, 1, 2, baseline("가", 0), baseline("나", 0));
		assertEquals("한글", JSON.readTree(JSON.writeValueAsString(m)).get("run").asText());
	}

	@Test
	void modelPathWithManyParentStepsIsNormalized(@TempDir Path dir) throws Exception {
		// 계획 파일이 저장소 밖에 있으면 모델 경로에 ".." 가 겹겹이 붙는다. 정리하지 않고 쓰면 260 자를 넘어
		// 긴 경로를 허용하지 않는 Windows 에서 ONNX Runtime 이 파일을 찾지 못했다 (2026-10-02).
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Path models = Files.createDirectories(dir.resolve("models"));
		Files.copy(Path.of(System.getProperty("fixtures.dir", "fixtures"), "model_io", "value_tiny.onnx"),
				models.resolve("value_tiny.onnx"));
		String detour = "x/../".repeat(60) + "models/value_tiny.onnx"; // 정리하면 models/value_tiny.onnx
		assertTrue(dir.resolve(detour).toString().length() > 260, "정리하기 전 경로가 260 자를 넘어야 시험이 된다");

		ObjectNode plan = JSON.createObjectNode();
		plan.put("format", MatchMain.PLAN_FORMAT);
		plan.put("run", "tmp");
		plan.put("max_turns", 3);
		plan.put("threads", 1);
		plan.put("out", "x/../matches");
		ObjectNode players = plan.putObject("players");
		players.putObject("tiny").put("model", detour);
		players.putObject("base").put("model", "baseline");
		plan.putArray("matches").addObject().put("seed", 1).put("p1", "tiny").put("p2", "base");
		Path planFile = dir.resolve("plan.json");
		JSON.writeValue(planFile.toFile(), plan);

		MatchMain.main(new String[] { planFile.toString() });

		JsonNode summary = JSON.readTree(dir.resolve("matches/summary.json").toFile());
		assertEquals(1, summary.get("matches").asInt());
	}
}
