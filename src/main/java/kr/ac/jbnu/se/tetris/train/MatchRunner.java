package kr.ac.jbnu.se.tetris.train;

import java.util.List;
import java.util.function.IntBinaryOperator;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.Afterstate;
import kr.ac.jbnu.se.tetris.ai.BeamSearch;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Brain.Decision;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.ai.FeatureEncoder;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.ai.PlacementScorer;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.Rules;
import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 블록 동기 모드(RULES 12.2) 대전 한 판을 두고 기록 {@code match-1}(docs/TRAINING.md 7장)을 만든다.
 * <p>
 * 두 플레이어는 같은 시드로 같은 순서의 블록을 받는다. 매 턴 두 플레이어 모두 턴 시작 시점의 두 상태를 보고 배치를 고르고,
 * 각자 고정한 뒤 이번 턴의 송신을 상대 대기열에 붙인다. 같은 턴에 서로 보낸 공격끼리는 직접 상쇄되지 않는다.
 */
public final class MatchRunner {

	public static final String FORMAT = "match-1";

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	/** 대전하는 한쪽. 점수 매기기는 여러 판이 동시에 써도 되는 구현이어야 한다. */
	public static final class Player {
		final String ai;
		final String persona;
		final String model;
		final double temperature;
		final PlacementScorer scorer;
		final BeamSearch search;

		/**
		 * 1수만 보고 고르는 플레이어.
		 *
		 * @param ai          AI 이름 (기록의 {@code players[].ai})
		 * @param persona     페르소나 이름
		 * @param model       모델 파일 이름, 기준 AI 면 {@code "baseline"}
		 * @param temperature 고르기 온도 τ (TRAINING.md 6장)
		 */
		public Player(String ai, String persona, String model, double temperature, PlacementScorer scorer) {
			this(ai, persona, model, temperature, scorer, null);
		}

		/** @param search 2수 탐색 (TRAINING.md 6.2). null 이면 1수만 본다 */
		public Player(String ai, String persona, String model, double temperature, PlacementScorer scorer,
				BeamSearch search) {
			this.ai = ai;
			this.persona = persona;
			this.model = model;
			this.temperature = temperature;
			this.scorer = scorer;
			this.search = search;
		}

		public String ai() {
			return ai;
		}
	}

	private MatchRunner() {
	}

	/**
	 * 한 판을 끝까지(또는 턴 상한까지) 둔다.
	 *
	 * @param maxTurns 턴 상한. 넘으면 무승부 (RULES 12.2 5)
	 * @return 기록 한 판 (TRAINING.md 7.2)
	 */
	public static ObjectNode play(String run, int gen, long seed, int maxTurns, Player p1, Player p2) {
		Player[] players = { p1, p2 };
		GameModel[] games = { new GameModel(seed, 1, GameModel.Scheduler.NONE),
				new GameModel(seed, 2, GameModel.Scheduler.NONE) };
		SplitMix64[] rngs = { Chooser.rng(seed, 1), Chooser.rng(seed, 2) };
		ArrayNode steps = JSON.arrayNode();
		int turns = 0;
		while (turns < maxTurns && !games[0].isOver() && !games[1].isOver()) {
			// 1. 턴 시작 시점의 두 상태를 먼저 모두 본다. 상대가 이번 턴에 고르는 배치는 모른다.
			Opponent[] views = { Opponent.of(games[1]), Opponent.of(games[0]) };
			ObjectNode[] states = { state(games[0]), state(games[1]) };
			Decision[] decisions = new Decision[2];
			for (int i = 0; i < 2; i++) {
				decisions[i] = decide(games[i], views[i], players[i], rngs[i]);
			}
			// 2. 각자 고정한다. 상쇄는 턴 시작 시점의 자기 대기열로 한다.
			GameModel.LockResult[] results = new GameModel.LockResult[2];
			for (int i = 0; i < 2; i++) {
				results[i] = games[i].apply(decisions[i].placement());
			}
			// 3. 턴 끝에 이번 턴의 송신을 상대 대기열에 붙인다. 판이 끝났으면 붙이지 않는다(결과에 영향이 없다).
			if (!games[0].isOver() && !games[1].isOver()) {
				games[1].receive(results[0].sent());
				games[0].receive(results[1].sent());
			}
			ObjectNode step = steps.addObject();
			for (int i = 0; i < 2; i++) {
				step.set(i == 0 ? "p1" : "p2", playerTurn(states[i], decisions[i], results[i]));
			}
			turns++;
		}

		ObjectNode match = JSON.objectNode();
		match.put("format", FORMAT);
		match.put("rules", Rules.VERSION);
		match.put("model_io", FeatureEncoder.MODEL_IO_VERSION);
		match.put("run", run);
		match.put("gen", gen);
		match.put("seed", seed);
		match.put("max_turns", maxTurns);
		ArrayNode ps = match.putArray("players");
		for (Player p : players) {
			ObjectNode o = ps.addObject();
			o.put("ai", p.ai);
			o.put("persona", p.persona);
			o.put("model", p.model);
			o.put("temperature", p.temperature);
			if (p.search != null) {
				o.putObject("search").put("beam", p.search.beam());
			}
		}
		boolean over1 = games[0].isOver();
		boolean over2 = games[1].isOver();
		// 같은 턴에 둘 다 지거나 턴 상한에 닿으면 무승부 (RULES 12.2 4, 5).
		match.put("result", over1 == over2 ? "draw" : over1 ? "p2" : "p1");
		ObjectNode topout = match.putObject("topout");
		for (int i = 0; i < 2; i++) {
			String key = i == 0 ? "p1" : "p2";
			if (games[i].isOver()) {
				topout.put(key, games[i].topout().json());
			} else {
				topout.putNull(key);
			}
		}
		match.put("turns", turns);
		match.set("steps", steps);
		return match;
	}

	/** 학습 대전의 고르기는 게임의 AI 와 같은 코드다 ({@link Brain}). */
	private static Decision decide(GameModel g, Opponent view, Player player, SplitMix64 rng) {
		return Brain.decide(g, g.placements(), view, player.scorer, player.temperature, player.search, rng);
	}

	/** 결정 시점(턴 시작)의 상태 (TRAINING.md 7.3 {@code state}). 가상 시간 기록({@link TimedRecorder})도 같은 모양을 쓴다. */
	static ObjectNode state(GameModel g) {
		ObjectNode s = JSON.objectNode();
		s.set("rows", rows(g::cell));
		s.put("current", g.current().name());
		s.put("queue", letters(g.preview()));
		putPiece(s, "hold", g.hold());
		s.put("combo", g.combo());
		s.put("b2b", g.b2b());
		s.put("pending", g.pendingLines());
		return s;
	}

	private static ObjectNode playerTurn(ObjectNode state, Decision d, GameModel.LockResult r) {
		ObjectNode t = JSON.objectNode();
		t.set("state", state);
		t.put("key", d.placement().key());
		if (d.after() == null) {
			t.putNull("after");
		} else {
			Afterstate a = d.after();
			ObjectNode o = t.putObject("after");
			o.set("rows", rows(a::cell));
			o.put("queue", letters(a.queue()));
			putPiece(o, "hold", a.hold());
			o.put("combo", a.combo());
			o.put("b2b", a.b2b());
			o.put("lines", a.lines());
			o.put("attack", a.attack());
			o.put("pending", a.pending());
			o.put("spin", d.placement().spin().json());
			o.put("pc", r.perfectClear());
		}
		t.put("sent", r.sent());
		t.put("risen", r.garbageRisen());
		t.put("n", d.n());
		if (d.rank() < 0) {
			t.putNull("rank");
		} else {
			t.put("rank", d.rank());
		}
		return t;
	}

	/** 보드를 정수 24 개로: {@code rows[r]} 의 비트 c 가 1 이면 r 행 c 열이 차 있다 (TRAINING.md 7.3). */
	static ArrayNode rows(IntBinaryOperator cell) {
		ArrayNode rows = JSON.arrayNode();
		for (int r = 0; r < Grid.HEIGHT; r++) {
			int bits = 0;
			for (int c = 0; c < Grid.WIDTH; c++) {
				if (cell.applyAsInt(r, c) != Grid.EMPTY) {
					bits |= 1 << c;
				}
			}
			rows.add(bits);
		}
		return rows;
	}

	private static String letters(List<PieceType> pieces) {
		StringBuilder sb = new StringBuilder(pieces.size());
		for (PieceType p : pieces) {
			sb.append(p.name());
		}
		return sb.toString();
	}

	private static void putPiece(ObjectNode o, String field, PieceType p) {
		if (p == null) {
			o.putNull(field);
		} else {
			o.put(field, p.name());
		}
	}
}
