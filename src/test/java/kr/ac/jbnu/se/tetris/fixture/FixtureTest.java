package kr.ac.jbnu.se.tetris.fixture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.Afterstate;
import kr.ac.jbnu.se.tetris.ai.FeatureEncoder;
import kr.ac.jbnu.se.tetris.ai.OnnxScorer;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.ai.PlayerState;
import kr.ac.jbnu.se.tetris.ai.WinRate;
import kr.ac.jbnu.se.tetris.core.Attack;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.GarbageQueue;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Locking;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder;
import kr.ac.jbnu.se.tetris.core.Rules;
import kr.ac.jbnu.se.tetris.core.SevenBag;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.train.MatchReplay;

/**
 * fixtures/ 의 공통 픽스처를 core 규칙 엔진으로 검사한다 (docs/FIXTURES.md).
 * <p>
 * 파일마다 묶음 하나, 사례마다 테스트 하나를 만든다. 모든 형식에서 {@code expect} 에 적힌 필드만 비교한다.
 * 픽스처 폴더는 시스템 속성 {@code fixtures.dir}(Gradle 이 넘겨 준다)이고, 없으면 작업 폴더의 {@code fixtures} 이다.
 */
class FixtureTest {

	private static final Set<String> SOURCES = Set.of("hand", "reference-ps", "java", "python");
	private static final ObjectMapper JSON = new ObjectMapper();

	@TestFactory
	Stream<DynamicNode> fixtures() throws IOException {
		Path root = Path.of(System.getProperty("fixtures.dir", "fixtures"));
		List<Path> files;
		try (Stream<Path> s = Files.walk(root)) {
			files = s.filter(p -> p.toString().endsWith(".json")).sorted().collect(Collectors.toList());
		}
		assertFalse(files.isEmpty(), "픽스처 파일이 없음: " + root.toAbsolutePath());
		return files.stream().map(f -> fileTests(root, f));
	}

	private static DynamicNode fileTests(Path root, Path file) {
		String name = root.relativize(file).toString().replace('\\', '/');
		JsonNode doc;
		try {
			doc = JSON.readTree(file.toFile());
		} catch (IOException e) {
			return dynamicTest(name, () -> fail("JSON 을 읽을 수 없음: " + e.getMessage(), e));
		}
		String type = doc.path("type").asText();
		List<DynamicNode> tests = new ArrayList<>();
		tests.add(dynamicTest("(파일 머리)", () -> checkHeader(name, doc)));
		for (JsonNode c : doc.path("cases")) {
			tests.add(dynamicTest(c.path("id").asText("(id 없음)"), () -> checkCase(type, c, file.getParent(), doc)));
		}
		return dynamicContainer(name, file.toUri(), tests.stream());
	}

	/** FIXTURES.md 3장: 규칙 버전, 디렉터리와 같은 type, 배열인 source, 파일 안에서 고유한 id. */
	private static void checkHeader(String name, JsonNode doc) {
		assertEquals(Rules.VERSION, text(doc, "rules_version"), "rules_version");
		assertEquals(name.substring(0, name.indexOf('/')), text(doc, "type"), "type 은 디렉터리 이름과 같아야 함");
		JsonNode source = req(doc, "source");
		assertTrue(source.isArray() && source.size() > 0, "source 는 비어 있지 않은 배열이어야 함");
		for (JsonNode s : source) {
			assertTrue(SOURCES.contains(s.asText()), "알 수 없는 source: " + s);
		}
		Set<String> ids = new HashSet<>();
		for (JsonNode c : req(doc, "cases")) {
			assertTrue(ids.add(text(c, "id")), "id 중복: " + text(c, "id"));
		}
		assertFalse(ids.isEmpty(), "사례가 없음");
	}

	/**
	 * @param dir 픽스처 파일이 있는 폴더 (model_io, winprob 의 모델 파일 경로 기준)
	 * @param doc 픽스처 파일 전체 (model_io, winprob 의 파일 단위 필드 {@code model})
	 */
	private static void checkCase(String type, JsonNode c, Path dir, JsonNode doc) throws Exception {
		switch (type) {
		case "model_io":
			modelIo(c, dir, doc);
			break;
		case "match":
			match(c);
			break;
		case "winprob":
			winprob(c, dir, doc);
			break;
		case "game":
			game(c);
			break;
		case "rng":
			rng(c);
			break;
		case "rotation":
			rotation(c);
			break;
		case "tspin":
			tspin(c);
			break;
		case "attack":
			attack(c);
			break;
		case "garbage":
			garbage(c);
			break;
		case "placement":
			placement(c);
			break;
		case "topout":
			topout(c);
			break;
		default:
			// 모르는 형식을 조용히 건너뛰지 않는다 (FIXTURES.md 6장).
			fail("모르는 type: " + type);
		}
	}

	// ------------------------------------------------------------------ 형식별 검사

	private static void rng(JsonNode c) {
		String kind = text(c, "kind");
		switch (kind) {
		case "splitmix_raw":
			assertOutputs(new SplitMix64(Long.parseUnsignedLong(text(c, "state"))), req(c, "expect_next"));
			break;
		case "stream_first":
			assertOutputs(SplitMix64.stream(lng(c, "seed"), integer(c, "stream")), req(c, "expect_next"));
			break;
		case "bag_sequence": {
			String expected = text(c, "expect").replace(" ", "");
			SevenBag bag = new SevenBag(lng(c, "seed"));
			StringBuilder actual = new StringBuilder();
			for (int i = 0; i < expected.length(); i++) {
				actual.append(bag.next().letter());
			}
			assertEquals(expected, actual.toString(), "가방 순서");
			break;
		}
		case "garbage_holes": {
			List<Integer> expected = ints(req(c, "expect"));
			GarbageQueue q = new GarbageQueue(SplitMix64.stream(lng(c, "seed"), integer(c, "stream")));
			for (int i = 0; i < expected.size(); i++) {
				q.receive(1);
			}
			assertEquals(expected, q.chunks().stream().map(GarbageQueue.Chunk::hole).collect(Collectors.toList()),
					"구멍 열");
			break;
		}
		case "scheduler_mix": {
			List<Integer> expected = ints(req(c, "expect"));
			SplitMix64 r = SplitMix64.stream(lng(c, "seed"), SplitMix64.STREAM_SCHEDULER);
			List<Integer> actual = new ArrayList<>();
			for (int i = 0; i < expected.size(); i++) {
				actual.add(2 + r.nextInt(3));
			}
			assertEquals(expected, actual, "S-mix 줄 수");
			break;
		}
		default:
			fail("모르는 rng kind: " + kind);
		}
	}

	private static void assertOutputs(SplitMix64 r, JsonNode expected) {
		List<String> want = new ArrayList<>();
		List<String> got = new ArrayList<>();
		for (JsonNode e : expected) {
			want.add(e.asText());
			got.add(Long.toUnsignedString(r.next()));
		}
		assertEquals(want, got, "출력(부호 없는 10진수)");
	}

	private static void rotation(JsonNode c) {
		Grid grid = board(c, "board_bottom");
		PieceState s = state(req(c, "start"));
		requireFits(grid, s);
		JsonNode expect = req(c, "expect");
		boolean unchanged = expect.path("unchanged").asBoolean(false);
		for (JsonNode o : req(c, "ops")) {
			Op op = Op.valueOf(o.asText());
			PieceState next = Movement.apply(grid, s, op);
			if (unchanged) {
				assertNull(next, () -> op + " 가 실패해야 하는데 " + next + " 로 성공");
				continue;
			}
			PieceState before = s;
			assertNotNull(next, () -> op + " 가 " + before + " 에서 실패");
			s = next;
		}
		if (!unchanged) {
			expectInt(expect, "rotation", s.rotation());
			expectInt(expect, "x", s.x());
			expectInt(expect, "y", s.y());
			expectInt(expect, "last_kick", s.kick());
		}
	}

	private static void tspin(JsonNode c) {
		Grid grid = board(c, "board_bottom");
		PieceType type = PieceType.valueOf(text(c, "piece"));
		PieceState s = "spawn".equals(c.path("start").asText()) ? PieceState.spawn(type) : state(req(c, "start"));
		requireFits(grid, s);
		Locking.Outcome out = null;
		for (JsonNode o : req(c, "ops")) {
			String op = o.asText();
			assertNull(out, "HD 뒤에 조작이 더 있음");
			if (op.equals("HD")) {
				out = Locking.lock(grid, Movement.hardDrop(grid, s));
				continue;
			}
			PieceState next = Movement.apply(grid, s, Op.valueOf(op));
			PieceState before = s;
			assertNotNull(next, () -> op + " 가 " + before + " 에서 실패");
			s = next;
		}
		assertNotNull(out, "ops 가 HD 로 끝나지 않음");
		JsonNode expect = req(c, "expect");
		if (expect.has("locked_cells")) {
			assertEquals(cellsText(expect.get("locked_cells")), cellsText(out.cells()), "locked_cells");
		}
		if (expect.has("spin")) {
			assertEquals(text(expect, "spin"), out.spin().json(), "spin");
		}
		expectInt(expect, "lines", out.lines());
		expectBoard(expect, grid);
	}

	private static void attack(JsonNode c) {
		JsonNode before = req(c, "before");
		JsonNode ev = req(c, "event");
		JsonNode expect = req(c, "expect");
		Attack a = Attack.compute(integer(before, "combo"), bool(before, "b2b"), integer(ev, "lines"),
				Spin.fromJson(text(ev, "spin")), bool(ev, "perfect_clear"));
		expectInt(expect, "attack", a.attack());
		expectInt(expect, "combo", a.combo());
		if (expect.has("b2b")) {
			assertEquals(bool(expect, "b2b"), a.b2b(), "b2b");
		}
	}

	private static void garbage(JsonNode c) {
		JsonNode action = req(c, "action");
		JsonNode expect = req(c, "expect");
		String kind = text(action, "type");
		GarbageQueue q;
		if (kind.equals("receive")) {
			// prev_hole 은 반드시 적는다(FIXTURES.md 5.5). 아직 null 만 지원한다.
			assertTrue(req(action, "prev_hole").isNull(), "null 이 아닌 prev_hole 은 아직 지원하지 않음 (FIXTURES.md 6장)");
			int player = integer(action, "player");
			assertTrue(player == 1 || player == 2, "player 는 1 또는 2: " + player);
			int stream = player == 1 ? SplitMix64.STREAM_GARBAGE_P1 : SplitMix64.STREAM_GARBAGE_P2;
			q = new GarbageQueue(SplitMix64.stream(lng(action, "seed"), stream));
		} else {
			q = new GarbageQueue(new SplitMix64(0));
		}
		for (JsonNode ch : c.path("queue")) {
			q.addChunk(integer(ch, "lines"), integer(ch, "hole"));
		}
		switch (kind) {
		case "cancel":
			expectInt(expect, "sent", q.cancel(integer(action, "attack")));
			break;
		case "rise": {
			Grid grid = board(c, "board_bottom");
			boolean out = q.rise(grid);
			if (expect.has("topout")) {
				assertEquals(text(expect, "topout"), out ? "garbage_out" : "none", "topout");
			}
			expectBoard(expect, grid);
			break;
		}
		case "receive":
			for (JsonNode a : req(action, "attacks")) {
				q.receive(a.asInt());
			}
			break;
		default:
			fail("모르는 garbage action: " + kind);
		}
		if (expect.has("queue")) {
			String want = stream(expect.get("queue")).map(ch -> integer(ch, "lines") + "@" + integer(ch, "hole"))
					.collect(Collectors.joining(" "));
			String got = q.chunks().stream().map(ch -> ch.lines() + "@" + ch.hole()).collect(Collectors.joining(" "));
			assertEquals(want, got, "queue (줄 수@구멍 열)");
		}
	}

	private static void placement(JsonNode c) {
		Grid grid = board(c, "board_bottom");
		PieceType type = PieceType.valueOf(text(c, "piece"));
		if (c.path("hold_allowed").asBoolean(false)) {
			fail("hold_allowed = true 는 아직 지원하지 않음 (FIXTURES.md 6장)");
		}
		PieceState start = "spawn".equals(c.path("start").asText()) ? PieceState.spawn(type) : state(req(c, "start"));
		requireFits(grid, start);
		Set<String> actual = new TreeSet<>(PlacementFinder.find(grid, start, false).keySet());
		JsonNode expect = req(c, "expect");
		// 아무것도 확인하지 않고 통과하는 사례를 막는다.
		assertTrue(expect.has("keys") || expect.has("count") || expect.has("must_include") || expect.has("must_exclude"),
				"placement 의 expect 에는 keys, count, must_include, must_exclude 중 하나 이상이 있어야 함");
		if (expect.has("keys")) {
			assertEquals(new TreeSet<>(texts(expect.get("keys"))), actual, "keys");
		}
		expectInt(expect, "count", actual.size());
		for (String k : texts(expect.path("must_include"))) {
			assertTrue(actual.contains(k), () -> "빠진 배치 " + k + " / 실제 " + actual);
		}
		for (String k : texts(expect.path("must_exclude"))) {
			assertFalse(actual.contains(k), () -> "있으면 안 되는 배치 " + k);
		}
	}

	private static void topout(JsonNode c) {
		Grid grid = board(c, "board_bottom");
		JsonNode ev = req(c, "event");
		String actual;
		switch (text(ev, "type")) {
		case "spawn":
			actual = Locking.isBlockOut(grid, PieceType.valueOf(text(ev, "piece"))) ? "block_out" : "none";
			break;
		case "lock":
			actual = Locking.lock(grid, state(req(ev, "state"))).lockOut() ? "lock_out" : "none";
			break;
		default:
			actual = null;
			fail("모르는 topout event: " + text(ev, "type"));
		}
		assertEquals(text(req(c, "expect"), "topout"), actual, "topout");
	}

	/** FIXTURES.md 5.8: 시드와 처음 보드에서 단계(배치 키 또는 공격 받기)를 차례로 적용한 뒤의 상태. */
	private static void game(JsonNode c) {
		assertEquals("timeless", c.path("mode").asText("timeless"), "지금은 시간 없는 모드(timeless)만 지원");
		GameModel g = new GameModel(lng(c, "seed"), c.path("player").asInt(1),
				GameModel.Scheduler.fromJson(c.path("scheduler").asText("none")), board(c, "board_bottom"));
		int n = 0;
		for (JsonNode step : req(c, "steps")) {
			final int no = ++n;
			assertFalse(g.isOver(), () -> no + "단계 전에 이미 게임이 끝남: " + g.topout().json());
			if (step.isTextual()) {
				String key = step.asText();
				assertTrue(g.placements().containsKey(key), () -> no + "단계: 지금 둘 수 없는 배치 " + key);
				g.applyKey(key);
			} else if (step.has("receive")) {
				g.receive(integer(step, "receive"));
			} else {
				fail(no + "단계: 모르는 단계 " + step);
			}
		}
		JsonNode expect = req(c, "expect");
		if (expect.has("topout")) {
			assertEquals(text(expect, "topout"), g.topout().json(), "topout");
		}
		expectBoard(expect, g.grid());
		if (expect.has("current")) {
			assertEquals(text(expect, "current"), g.current().name(), "current");
		}
		if (expect.has("queue_preview")) {
			assertEquals(texts(expect.get("queue_preview")),
					g.preview().stream().map(PieceType::name).collect(Collectors.toList()), "queue_preview");
		}
		if (expect.has("hold")) {
			String want = expect.get("hold").isNull() ? null : expect.get("hold").asText();
			assertEquals(want, g.hold() == null ? null : g.hold().name(), "hold");
		}
		expectInt(expect, "combo", g.combo());
		if (expect.has("b2b")) {
			assertEquals(bool(expect, "b2b"), g.b2b(), "b2b");
		}
		if (expect.has("pending")) {
			String want = stream(expect.get("pending")).map(ch -> integer(ch, "lines") + "@" + integer(ch, "hole"))
					.collect(Collectors.joining(" "));
			String got = g.pending().stream().map(ch -> ch.lines() + "@" + ch.hole()).collect(Collectors.joining(" "));
			assertEquals(want, got, "pending (줄 수@구멍 열)");
		}
		expectInt(expect, "pieces", g.pieces());
		expectInt(expect, "lines_total", g.linesTotal());
		expectInt(expect, "attack_total", g.attackTotal());
		expectInt(expect, "sent_total", g.sentTotal());
	}

	/**
	 * FIXTURES.md 5.10: 대전 기록을 시드와 배치 키로 다시 두어 엔진과 맞는지 확인하고(TRAINING.md 7.5), 턴마다의 모델 입력이
	 * 기대값과 같은지 본다. 기대값은 같은 Java 인코더가 만든 것이라 Java 에게는 회귀 검사이고, Python 과의 교차 확인이 목적이다.
	 */
	private static void match(JsonNode c) {
		List<ObjectNode> got = MatchReplay.check(req(c, "match"));
		JsonNode want = req(req(c, "expect"), "inputs");
		assertEquals(want.size(), got.size(), "턴 수");
		for (int t = 0; t < got.size(); t++) {
			assertEquals(want.get(t), got.get(t), "턴 " + t + " 의 모델 입력");
		}
	}

	/** docs/MODEL_IO.md 7장: 인코딩, 모델 실행(Python onnxruntime 결과와 비교), 모델 꼬리표. */
	private static void modelIo(JsonNode c, Path dir, JsonNode doc) throws Exception {
		JsonNode expect = req(c, "expect");
		String kind = text(c, "kind");
		switch (kind) {
		case "encode": {
			JsonNode d = req(c, "afterstate");
			Afterstate a = afterstate(d);
			float[] features = new float[FeatureEncoder.FEATURES];
			FeatureEncoder.encodeFeatures(a, features, 0);
			float[] boardTensor = new float[FeatureEncoder.BOARD_SIZE];
			FeatureEncoder.encodeBoard(a, boardTensor, 0);
			expectTensors(expect, board(d, "board_bottom"), boardTensor, features, "features");
			break;
		}
		case "encode_opponent": {
			JsonNode d = req(c, "opponent");
			Opponent o = opponent(d);
			float[] features = new float[FeatureEncoder.OPP_FEATURES];
			FeatureEncoder.encodeOpponentFeatures(o, features, 0);
			float[] boardTensor = new float[FeatureEncoder.BOARD_SIZE];
			FeatureEncoder.encodeOpponentBoard(o, boardTensor, 0);
			expectTensors(expect, board(d, "board_bottom"), boardTensor, features, "opp_features");
			break;
		}
		case "run": {
			assumeOnnxRuntime();
			List<Afterstate> states = new ArrayList<>();
			for (JsonNode d : req(c, "afterstates")) {
				states.add(afterstate(d));
			}
			// opponent 가 없으면 상대가 없는 것이다 (FIXTURES.md 5.9).
			Opponent opponent = c.has("opponent") ? opponent(c.get("opponent")) : Opponent.NONE;
			double tolerance = req(expect, "tolerance").asDouble();
			JsonNode want = req(expect, "values");
			try (OnnxScorer scorer = OnnxScorer.load(dir.resolve(text(doc, "model")))) {
				double[] got = scorer.score(states, opponent);
				assertEquals(want.size(), got.length, "출력 개수");
				for (int i = 0; i < got.length; i++) {
					assertEquals(want.get(i).asDouble(), got[i], tolerance, "value[" + i + "]");
				}
			}
			break;
		}
		case "metadata": {
			assumeOnnxRuntime();
			try (OnnxScorer scorer = OnnxScorer.load(dir.resolve(text(doc, "model")))) {
				expect.fieldNames().forEachRemaining(
						k -> assertEquals(expect.get(k).asText(), scorer.metadata().get(k), "꼬리표 " + k));
			}
			break;
		}
		default:
			fail("모르는 model_io kind: " + kind);
		}
	}

	/** docs/MODEL_IO.md 9.7 (FIXTURES.md 5.11): 승률 예측망의 인코딩, 모델 실행(Python onnxruntime 결과와 비교), 꼬리표. */
	private static void winprob(JsonNode c, Path dir, JsonNode doc) throws Exception {
		JsonNode expect = req(c, "expect");
		String kind = text(c, "kind");
		switch (kind) {
		case "encode": {
			JsonNode d = req(c, "state");
			encodePlayer(expect, playerState(d), board(d, "board_bottom"));
			break;
		}
		case "encode_record": {
			// 기대 보드는 expect 의 board_bottom / board_top 이다. rows 를 푸는 방법이 Python 과 같은지 본다.
			assertTrue(expect.has("board_bottom") || expect.has("board_top"), "encode_record 의 expect 에는 board_bottom 이 있어야 함");
			encodePlayer(expect, recordState(req(c, "state")), board(expect, "board_bottom"));
			break;
		}
		case "run": {
			assumeOnnxRuntime();
			List<PlayerState> p1 = new ArrayList<>();
			List<PlayerState> p2 = new ArrayList<>();
			for (JsonNode pair : req(c, "pairs")) {
				p1.add(playerState(req(pair, "p1")));
				p2.add(playerState(req(pair, "p2")));
			}
			double tolerance = req(expect, "tolerance").asDouble();
			JsonNode want = req(expect, "values");
			try (WinRate model = WinRate.load(dir.resolve(text(doc, "model")))) {
				double[] got = model.p1Win(p1, p2);
				assertEquals(want.size(), got.length, "출력 개수");
				for (int i = 0; i < got.length; i++) {
					assertEquals(want.get(i).asDouble(), got[i], tolerance, "p1_win[" + i + "]");
				}
			}
			break;
		}
		case "metadata": {
			assumeOnnxRuntime();
			try (WinRate model = WinRate.load(dir.resolve(text(doc, "model")))) {
				expect.fieldNames().forEachRemaining(
						k -> assertEquals(expect.get(k).asText(), model.metadata().get(k), "꼬리표 " + k));
			}
			break;
		}
		default:
			fail("모르는 winprob kind: " + kind);
		}
	}

	/** 한 플레이어의 보드 텐서와 특징 52개를 기대값과 비교한다. 보드 텐서는 g 의 채워짐 여부와 같아야 한다. */
	private static void encodePlayer(JsonNode expect, PlayerState s, Grid g) {
		float[] features = new float[WinRate.FEATURES];
		WinRate.encodeFeatures(s, features, 0);
		float[] boardTensor = new float[WinRate.BOARD_SIZE];
		WinRate.encodeBoard(s, boardTensor, 0);
		expectTensors(expect, g, boardTensor, features, "p_features");
	}

	/** 픽스처의 상태 객체 (FIXTURES.md 5.11). 적지 않은 값은 MODEL_IO.md 의 기본값(콤보 -1 등). */
	private static PlayerState playerState(JsonNode d) {
		assertTrue(d.path("queue").isMissingNode() || d.path("queue").isArray(), "queue 는 블록 문자의 배열이어야 함");
		List<PieceType> queue = texts(d.path("queue")).stream().map(PieceType::valueOf).collect(Collectors.toList());
		return new PlayerState(board(d, "board_bottom"), PieceType.valueOf(text(d, "current")), queue, piece(d, "hold"),
				d.path("combo").asInt(-1), bool(d, "b2b"), d.path("pending").asInt(0));
	}

	/** 기록 match-1 의 state 객체 그대로 (TRAINING.md 7.3). 모든 필드가 있어야 한다. */
	private static PlayerState recordState(JsonNode d) {
		JsonNode rows = req(d, "rows");
		assertEquals(Grid.HEIGHT, rows.size(), "rows 개수");
		Grid g = new Grid();
		for (int r = 0; r < Grid.HEIGHT; r++) {
			int bits = rows.get(r).asInt();
			for (int col = 0; col < Grid.WIDTH; col++) {
				if ((bits >> col & 1) != 0) {
					g.set(r, col, 9);
				}
			}
		}
		List<PieceType> queue = text(d, "queue").chars().mapToObj(ch -> PieceType.fromLetter((char) ch))
				.collect(Collectors.toList());
		assertTrue(req(d, "hold").isNull() || req(d, "hold").isTextual(), "hold 는 문자 또는 null");
		return new PlayerState(g, PieceType.valueOf(text(d, "current")), queue, piece(d, "hold"), integer(d, "combo"),
				req(d, "b2b").asBoolean(), integer(d, "pending"));
	}

	/**
	 * encode 계열 사례: {@code features} 는 값이 정확히 같아야 하고, 보드 텐서는 보드의 채워짐 여부를 행 우선(0 행이 맨 위)으로
	 * 편 것과 같아야 한다. {@code board_filled} 는 1 의 개수다.
	 */
	private static void expectTensors(JsonNode expect, Grid g, float[] actualBoard, float[] actualFeatures, String what) {
		if (expect.has("features")) {
			JsonNode want = expect.get("features");
			assertEquals(actualFeatures.length, want.size(), what + " 길이");
			float[] expected = new float[actualFeatures.length];
			for (int i = 0; i < expected.length; i++) {
				expected[i] = (float) want.get(i).asDouble();
			}
			assertArrayEquals(expected, actualFeatures, 0f, what);
		}
		float[] expectedBoard = new float[FeatureEncoder.BOARD_SIZE];
		int filled = 0;
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int col = 0; col < Grid.WIDTH; col++) {
				boolean on = g.get(r, col) != Grid.EMPTY;
				expectedBoard[r * Grid.WIDTH + col] = on ? 1f : 0f;
				filled += on ? 1 : 0;
			}
		}
		assertArrayEquals(expectedBoard, actualBoard, 0f, "보드 텐서");
		expectInt(expect, "board_filled", filled);
	}

	/** 픽스처의 afterstate 객체 (FIXTURES.md 5.9). 적지 않은 값은 MODEL_IO.md 의 기본값(콤보 -1 등). */
	private static Afterstate afterstate(JsonNode d) {
		List<PieceType> queue = texts(d.path("queue")).stream().map(PieceType::valueOf).collect(Collectors.toList());
		return new Afterstate(board(d, "board_bottom"), queue, piece(d, "hold"), d.path("combo").asInt(-1), bool(d, "b2b"),
				d.path("lines").asInt(0), d.path("attack").asInt(0), d.path("pending").asInt(0));
	}

	/** 픽스처의 opponent 객체 (FIXTURES.md 5.9, MODEL_IO.md 2.2). 적지 않은 값은 상대가 없을 때와 같다. */
	private static Opponent opponent(JsonNode d) {
		return new Opponent(board(d, "board_bottom"), d.path("pending").asInt(0), d.path("combo").asInt(-1),
				bool(d, "b2b"), piece(d, "hold"));
	}

	/** 블록 문자 필드. 없거나 null 이면 null. */
	private static PieceType piece(JsonNode d, String field) {
		return d.hasNonNull(field) ? PieceType.valueOf(d.get(field).asText()) : null;
	}

	// ------------------------------------------------------------------ 보드

	/**
	 * key 로 적은 아래쪽 행들과 {@code board_top} 으로 적은 위쪽 행들을 합쳐 24 행 보드를 만든다 (FIXTURES.md 4장).
	 * 적지 않은 행은 빈 행이고, 위아래를 합쳐 24 행을 넘으면 픽스처 오류다.
	 */
	private static Grid board(JsonNode m, String key) {
		List<String> bottom = texts(m.path(key));
		List<String> top = texts(m.path("board_top"));
		assertTrue(top.size() + bottom.size() <= Grid.HEIGHT,
				() -> "board_top " + top.size() + "행 + " + key + " " + bottom.size() + "행이 " + Grid.HEIGHT + "행을 넘음");
		Grid g = new Grid();
		fillRows(g, key, bottom, Grid.HEIGHT - bottom.size());
		fillRows(g, "board_top", top, 0);
		return g;
	}

	private static void fillRows(Grid g, String key, List<String> rows, int top) {
		for (int i = 0; i < rows.size(); i++) {
			String row = rows.get(i);
			int r = top + i;
			assertEquals(Grid.WIDTH, row.length(), () -> key + " 의 " + r + "행 길이");
			for (int col = 0; col < Grid.WIDTH; col++) {
				int v = cellValue(row.charAt(col));
				if (v != Grid.EMPTY) {
					g.set(r, col, v);
				}
			}
		}
	}

	private static int cellValue(char ch) {
		switch (ch) {
		case '.':
			return Grid.EMPTY;
		case 'G':
			return Grid.GARBAGE;
		case '#':
			return 9;
		default:
			return PieceType.fromLetter(ch).code();
		}
	}

	/** expect 에 board_bottom 이나 board_top 이 있으면 보드 전체를 채워짐/비어 있음으로 비교한다. */
	private static void expectBoard(JsonNode expect, Grid actual) {
		if (expect.has("board_bottom") || expect.has("board_top")) {
			assertEquals(occupancy(board(expect, "board_bottom")), occupancy(actual), "보드 (# 채워짐, . 비어 있음)");
		}
	}

	private static String occupancy(Grid g) {
		StringBuilder sb = new StringBuilder("\n");
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				sb.append(g.get(r, c) == Grid.EMPTY ? '.' : '#');
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	private static void requireFits(Grid grid, PieceState s) {
		assertTrue(grid.fits(s.type(), s.rotation(), s.x(), s.y()), () -> "시작 상태 " + s + " 가 보드에 놓일 수 없음 (픽스처 오류)");
	}

	// ------------------------------------------------------------------ JSON 값

	/**
	 * 블록 상태 (FIXTURES.md 4장). {@code last_rotation}(기본 false)과 {@code last_kick}(기본 -1)은 블록 도중 재결정처럼
	 * 마지막 조작을 이어받는 시작 상태를 적을 때 쓴다(RULES 14.1).
	 */
	private static PieceState state(JsonNode m) {
		boolean lastRotation = m.path("last_rotation").asBoolean(false);
		int kick = m.has("last_kick") ? integer(m, "last_kick") : -1;
		assertEquals(lastRotation, kick >= 0, "last_kick 은 last_rotation 이 true 일 때만 0 이상");
		return new PieceState(PieceType.valueOf(text(m, "piece")), integer(m, "rotation"), integer(m, "x"),
				integer(m, "y"), lastRotation, kick);
	}

	/** ONNX Runtime 이 없는 플랫폼(예: Intel Mac)에서는 모델 실행 사례를 실패 대신 건너뛴다. */
	private static void assumeOnnxRuntime() {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀 (README.md 참고)");
	}

	private static void expectInt(JsonNode expect, String field, int actual) {
		if (expect.has(field)) {
			assertEquals(integer(expect, field), actual, field);
		}
	}

	private static String cellsText(int[][] cells) {
		return Arrays.stream(cells).map(a -> a[0] + "," + a[1]).sorted().collect(Collectors.joining(";"));
	}

	private static String cellsText(JsonNode cells) {
		return stream(cells).map(a -> a.get(0).asInt() + "," + a.get(1).asInt()).sorted().collect(Collectors.joining(";"));
	}

	private static JsonNode req(JsonNode m, String field) {
		JsonNode v = m.get(field);
		assertNotNull(v, () -> "필드 " + field + " 없음");
		return v;
	}

	private static String text(JsonNode m, String field) {
		JsonNode v = req(m, field);
		assertTrue(v.isTextual(), () -> "필드 " + field + " 는 문자열이어야 함");
		return v.asText();
	}

	private static long lng(JsonNode m, String field) {
		JsonNode v = req(m, field);
		assertTrue(v.canConvertToLong(), () -> "필드 " + field + " 는 정수여야 함");
		return v.asLong();
	}

	private static int integer(JsonNode m, String field) {
		JsonNode v = req(m, field);
		assertTrue(v.canConvertToInt(), () -> "필드 " + field + " 는 정수여야 함");
		return v.asInt();
	}

	private static boolean bool(JsonNode m, String field) {
		return m.path(field).asBoolean(false);
	}

	private static List<Integer> ints(JsonNode array) {
		return stream(array).map(JsonNode::asInt).collect(Collectors.toList());
	}

	private static List<String> texts(JsonNode array) {
		return stream(array).map(JsonNode::asText).collect(Collectors.toList());
	}

	private static Stream<JsonNode> stream(JsonNode array) {
		List<JsonNode> out = new ArrayList<>();
		array.forEach(out::add);
		return out.stream();
	}
}
