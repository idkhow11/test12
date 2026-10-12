package kr.ac.jbnu.se.tetris.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.Afterstate;
import kr.ac.jbnu.se.tetris.ai.AfterstateBuilder;
import kr.ac.jbnu.se.tetris.ai.FeatureEncoder;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;
import kr.ac.jbnu.se.tetris.core.Rules;

/**
 * 기록 {@code match-1} 을 시드와 정규화 키만으로 처음부터 다시 두어, 기록의 모든 값이 엔진과 같은지 확인한다 (TRAINING.md 7.5).
 * <p>
 * 엔진과의 비교와 별도로, 기록 값만으로 블록 동기 모드의 순서(RULES 12.2)도 확인한다: 다음 턴의 대기 가비지는
 * "이번 턴 상쇄 뒤 대기 − 올라온 줄 + 상대가 이번 턴에 보낸 줄"이어야 한다.
 */
public final class MatchReplay {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private MatchReplay() {
	}

	/**
	 * @return 턴마다 {@code {"p1": 입력, "p2": 입력}}. 입력은 그 턴에 고른 배치의 모델 입력(MODEL_IO.md 형식 2)이고, 모든 배치가
	 *         락 아웃이었으면 null 이다. fixtures/match 의 기대값 형식(FIXTURES.md 5.10)
	 */
	public static List<ObjectNode> check(JsonNode match) {
		assertEquals(MatchRunner.FORMAT, match.path("format").asText(), "format");
		assertEquals(Rules.VERSION, match.path("rules").asText(), "rules");
		assertEquals(FeatureEncoder.MODEL_IO_VERSION, match.path("model_io").asText(), "model_io");
		long seed = match.get("seed").asLong();
		int maxTurns = match.get("max_turns").asInt();
		JsonNode steps = match.get("steps");
		assertEquals(steps.size(), match.get("turns").asInt(), "turns 와 steps 개수");

		GameModel[] games = { new GameModel(seed, 1, GameModel.Scheduler.NONE),
				new GameModel(seed, 2, GameModel.Scheduler.NONE) };
		List<ObjectNode> inputs = new ArrayList<>();
		for (int t = 0; t < steps.size(); t++) {
			String at = "턴 " + t + " ";
			assertTrue(!games[0].isOver() && !games[1].isOver(), at + "이미 끝난 판인데 기록이 이어짐");
			JsonNode[] turn = { steps.get(t).get("p1"), steps.get(t).get("p2") };
			Opponent[] views = { Opponent.of(games[1]), Opponent.of(games[0]) };
			Placement[] chosen = new Placement[2];
			ObjectNode in = JSON.objectNode();
			for (int i = 0; i < 2; i++) {
				String who = at + (i == 0 ? "p1 " : "p2 ");
				checkState(games[i], turn[i].get("state"), who);
				chosen[i] = games[i].placements().get(turn[i].get("key").asText());
				assertNotNull(chosen[i], who + "둘 수 없는 배치 " + turn[i].get("key"));
				in.set(i == 0 ? "p1" : "p2", checkAfter(games[i], chosen[i], turn[i], views[i], who));
			}
			inputs.add(in);
			GameModel.LockResult[] results = { games[0].apply(chosen[0]), games[1].apply(chosen[1]) };
			for (int i = 0; i < 2; i++) {
				String who = at + (i == 0 ? "p1 " : "p2 ");
				assertEquals(results[i].sent(), turn[i].get("sent").asInt(), who + "sent");
				assertEquals(results[i].garbageRisen(), turn[i].get("risen").asInt(), who + "risen");
				if (turn[i].hasNonNull("after")) {
					assertEquals(results[i].perfectClear(), turn[i].get("after").get("pc").asBoolean(), who + "pc");
				}
			}
			if (!games[0].isOver() && !games[1].isOver()) {
				games[1].receive(results[0].sent());
				games[0].receive(results[1].sent());
				checkPendingOrder(steps, t);
			}
		}

		boolean over1 = games[0].isOver();
		boolean over2 = games[1].isOver();
		assertEquals(over1 == over2 ? "draw" : over1 ? "p2" : "p1", match.get("result").asText(), "result");
		for (int i = 0; i < 2; i++) {
			JsonNode want = match.get("topout").get(i == 0 ? "p1" : "p2");
			if (games[i].isOver()) {
				assertEquals(games[i].topout().json(), want.asText(), "topout");
			} else {
				assertTrue(want.isNull(), "지지 않은 쪽의 topout 은 null");
			}
		}
		if (!over1 && !over2) {
			assertEquals(maxTurns, steps.size(), "아무도 지지 않았으면 턴 상한까지 둔 것");
		}
		return inputs;
	}

	/** 기록 값만으로 RULES 12.2 의 순서를 확인한다. 이번 턴의 송신은 턴 끝에 도착하고, 같은 턴의 공격끼리는 상쇄되지 않는다. */
	private static void checkPendingOrder(JsonNode steps, int t) {
		if (t + 1 >= steps.size()) {
			return;
		}
		for (int i = 0; i < 2; i++) {
			JsonNode me = steps.get(t).get(i == 0 ? "p1" : "p2");
			JsonNode other = steps.get(t).get(i == 0 ? "p2" : "p1");
			JsonNode next = steps.get(t + 1).get(i == 0 ? "p1" : "p2").get("state");
			int expected = me.get("after").get("pending").asInt() - me.get("risen").asInt() + other.get("sent").asInt();
			assertEquals(expected, next.get("pending").asInt(), "턴 " + (t + 1) + " 의 대기 가비지 (RULES 12.2 순서)");
		}
	}

	private static void checkState(GameModel g, JsonNode s, String who) {
		assertEquals(rows(g::cell), s.get("rows"), who + "state.rows");
		assertEquals(g.current().name(), s.get("current").asText(), who + "state.current");
		assertEquals(letters(g.preview()), s.get("queue").asText(), who + "state.queue");
		assertEquals(g.hold() == null ? null : g.hold().name(), s.get("hold").isNull() ? null : s.get("hold").asText(),
				who + "state.hold");
		assertEquals(g.combo(), s.get("combo").asInt(), who + "state.combo");
		assertEquals(g.b2b(), s.get("b2b").asBoolean(), who + "state.b2b");
		assertEquals(g.pendingLines(), s.get("pending").asInt(), who + "state.pending");
	}

	/** 고른 배치의 애프터스테이트를 엔진으로 다시 만들어 기록과 비교하고, 그 모델 입력을 돌려준다. */
	private static JsonNode checkAfter(GameModel g, Placement p, JsonNode turn, Opponent view, String who) {
		AfterstateBuilder.Context ctx = AfterstateBuilder.contextOf(g);
		Grid grid = g.grid();
		int candidates = 0;
		for (Map.Entry<String, Placement> e : g.placements().entrySet()) {
			if (AfterstateBuilder.build(grid, e.getValue(), ctx) != null) {
				candidates++;
			}
		}
		assertEquals(candidates, turn.get("n").asInt(), who + "n (락 아웃이 아닌 후보 수)");
		Afterstate a = AfterstateBuilder.build(grid, p, ctx);
		if (!turn.hasNonNull("after")) {
			assertEquals(0, candidates, who + "after 가 null 이면 모든 배치가 락 아웃이어야 함");
			assertNull(a, who + "락 아웃 배치");
			assertTrue(turn.get("rank").isNull(), who + "rank");
			return JSON.nullNode();
		}
		assertNotNull(a, who + "락 아웃 배치를 골랐음");
		int rank = turn.get("rank").asInt();
		assertTrue(rank >= 0 && rank < candidates, who + "rank 범위");
		JsonNode after = turn.get("after");
		assertEquals(rows(a::cell), after.get("rows"), who + "after.rows");
		assertEquals(letters(a.queue()), after.get("queue").asText(), who + "after.queue");
		assertEquals(a.hold() == null ? null : a.hold().name(), after.get("hold").isNull() ? null : after.get("hold").asText(),
				who + "after.hold");
		assertEquals(a.combo(), after.get("combo").asInt(), who + "after.combo");
		assertEquals(a.b2b(), after.get("b2b").asBoolean(), who + "after.b2b");
		assertEquals(a.lines(), after.get("lines").asInt(), who + "after.lines");
		assertEquals(a.attack(), after.get("attack").asInt(), who + "after.attack");
		assertEquals(a.pending(), after.get("pending").asInt(), who + "after.pending");
		assertEquals(p.spin().json(), after.get("spin").asText(), who + "after.spin");

		ObjectNode in = JSON.objectNode();
		float[] board = new float[FeatureEncoder.BOARD_SIZE];
		FeatureEncoder.encodeBoard(a, board, 0);
		in.set("board", boardText(board));
		float[] features = new float[FeatureEncoder.FEATURES];
		FeatureEncoder.encodeFeatures(a, features, 0);
		in.set("features", ints(features));
		float[] oppBoard = new float[FeatureEncoder.BOARD_SIZE];
		FeatureEncoder.encodeOpponentBoard(view, oppBoard, 0);
		in.set("opp_board", boardText(oppBoard));
		float[] oppFeatures = new float[FeatureEncoder.OPP_FEATURES];
		FeatureEncoder.encodeOpponentFeatures(view, oppFeatures, 0);
		in.set("opp_features", ints(oppFeatures));
		return in;
	}

	/** 보드 텐서를 픽스처 보드 표기(FIXTURES.md 4장)로: 24 개 문자열, 1 은 {@code #}, 0 은 {@code .}. */
	private static ArrayNode boardText(float[] tensor) {
		ArrayNode out = JSON.arrayNode();
		for (int r = 0; r < Grid.HEIGHT; r++) {
			StringBuilder row = new StringBuilder(Grid.WIDTH);
			for (int c = 0; c < Grid.WIDTH; c++) {
				row.append(tensor[r * Grid.WIDTH + c] == 1f ? '#' : '.');
			}
			out.add(row.toString());
		}
		return out;
	}

	private static ArrayNode ints(float[] values) {
		ArrayNode out = JSON.arrayNode();
		for (float v : values) {
			assertEquals(Math.rint(v), v, 0.0, "모델 입력은 정수를 실수로 바꾼 값이어야 함");
			out.add((int) v);
		}
		return out;
	}

	private static ArrayNode rows(java.util.function.IntBinaryOperator cell) {
		return MatchRunner.rows(cell);
	}

	private static String letters(List<PieceType> pieces) {
		StringBuilder sb = new StringBuilder();
		pieces.forEach(p -> sb.append(p.name()));
		return sb.toString();
	}
}
