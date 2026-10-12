package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import ai.onnxruntime.OrtException;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.train.MatchRunner;

/**
 * 승률 예측망 (MODEL_IO.md 9장). 인코딩의 손 계산, 게임 상태와 기록 state 의 인코딩 일치, 불러올 때의 확인, 자리 대칭,
 * 끝난 판 처리, 여러 스레드에서의 사용. 모델 실행 결과 자체는 fixtures/winprob 가 Python 과 비교한다.
 * ONNX Runtime 이 없는 플랫폼에서는 모델이 필요한 시험만 건너뛴다.
 */
class WinRateTest {

	private static final Path FIXTURES = Path.of(System.getProperty("fixtures.dir", "fixtures"));
	private static final Path DIR = FIXTURES.resolve("winprob");
	private static final BaselineScorer BASELINE = new BaselineScorer();

	private static void assumeOnnxRuntime() {
		assumeTrue(WinRate.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
	}

	private static PlayerState empty(PieceType current, int pending) {
		return new PlayerState(new Grid(), current, List.of(PieceType.I, PieceType.O, PieceType.S, PieceType.L,
				PieceType.Z), null, -1, false, pending);
	}

	// ------------------------------------------------------------------ 인코딩

	@Test
	void encodesFeaturesByHand() {
		Grid g = new Grid();
		g.set(23, 0, Grid.GARBAGE);
		g.set(0, 9, 1);
		PlayerState s = new PlayerState(g, PieceType.T, List.of(PieceType.I, PieceType.O), PieceType.Z, 3, true, 7);
		float[] f = new float[WinRate.FEATURES];
		WinRate.encodeFeatures(s, f, 0);
		float[] want = new float[WinRate.FEATURES];
		want[5] = 1; // current T (I J L O S T Z 의 5번)
		want[7 + 0] = 1; // 미리보기 0칸 I
		want[7 + 7 + 3] = 1; // 미리보기 1칸 O
		want[42 + 6] = 1; // hold Z
		want[49] = 4; // combo 3 + 1
		want[50] = 1; // b2b
		want[51] = 7; // pending
		assertArrayEquals(want, f, 0f);
		float[] board = new float[WinRate.BOARD_SIZE];
		WinRate.encodeBoard(s, board, 0);
		float[] wantBoard = new float[WinRate.BOARD_SIZE];
		wantBoard[23 * 10] = 1; // 23행 0열
		wantBoard[9] = 1; // 0행 9열 (숨김 영역)
		assertArrayEquals(wantBoard, board, 0f);
	}

	@Test
	void playerStateRejectsBadValues() {
		Grid g = new Grid();
		List<PieceType> six = List.of(PieceType.I, PieceType.J, PieceType.L, PieceType.O, PieceType.S, PieceType.T);
		assertThrows(IllegalArgumentException.class, () -> new PlayerState(g, PieceType.T, six, null, -1, false, 0));
		assertThrows(IllegalArgumentException.class, () -> new PlayerState(g, PieceType.T, List.of(), null, -2, false, 0));
		assertThrows(IllegalArgumentException.class, () -> new PlayerState(g, PieceType.T, List.of(), null, -1, false, -1));
		assertThrows(NullPointerException.class, () -> new PlayerState(g, null, List.of(), null, -1, false, 0));
	}

	/**
	 * Python 은 기록의 state 로 학습 자료를 만들고(fixtures/winprob 의 encode_record 로 Java 와 비교), Java 는 게임 중에
	 * {@link PlayerState#of(GameModel)} 로 입력을 만든다. 대전 기록을 다시 두며 두 입력이 모든 턴에 같은지 확인한다.
	 */
	@Test
	void gameStatesEncodeLikeRecordStates() {
		for (long seed = 21; seed <= 23; seed++) {
			JsonNode m = MatchRunner.play("t", 0, seed, 200, baseline("a", 0.3), baseline("b", 2.0));
			GameModel[] games = { new GameModel(seed, 1, GameModel.Scheduler.NONE),
					new GameModel(seed, 2, GameModel.Scheduler.NONE) };
			int t = 0;
			for (JsonNode step : m.get("steps")) {
				JsonNode[] turn = { step.get("p1"), step.get("p2") };
				GameModel.LockResult[] r = new GameModel.LockResult[2];
				for (int i = 0; i < 2; i++) {
					String at = "시드 " + seed + " 턴 " + t + (i == 0 ? " p1" : " p2");
					assertSameEncoding(PlayerState.of(games[i]), recordState(turn[i].get("state")), at);
				}
				for (int i = 0; i < 2; i++) {
					r[i] = games[i].applyKey(turn[i].get("key").asText());
				}
				if (!games[0].isOver() && !games[1].isOver()) {
					games[1].receive(r[0].sent());
					games[0].receive(r[1].sent());
				}
				t++;
			}
			assertTrue(t > 0, "기록에 턴이 있어야 함");
		}
	}

	private static MatchRunner.Player baseline(String name, double temperature) {
		return new MatchRunner.Player(name, "neutral", "baseline", temperature, BASELINE);
	}

	private static void assertSameEncoding(PlayerState a, PlayerState b, String at) {
		float[] fa = new float[WinRate.FEATURES];
		float[] fb = new float[WinRate.FEATURES];
		WinRate.encodeFeatures(a, fa, 0);
		WinRate.encodeFeatures(b, fb, 0);
		assertArrayEquals(fb, fa, 0f, at + " 특징");
		float[] ba = new float[WinRate.BOARD_SIZE];
		float[] bb = new float[WinRate.BOARD_SIZE];
		WinRate.encodeBoard(a, ba, 0);
		WinRate.encodeBoard(b, bb, 0);
		assertArrayEquals(bb, ba, 0f, at + " 보드");
	}

	/** 기록 match-1 의 state 객체 (TRAINING.md 7.3). */
	private static PlayerState recordState(JsonNode s) {
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
				s.get("b2b").asBoolean(), s.get("pending").asInt());
	}

	// ------------------------------------------------------------------ 불러오기

	@Test
	void rejectsModelsThatAreNotWinprob1() {
		assumeOnnxRuntime();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> WinRate.load(DIR.resolve("winprob_linear_wrong_model_io.onnx")));
		assertTrue(e.getMessage().contains("tetris.model_io"), e.getMessage());
		e = assertThrows(IllegalArgumentException.class, () -> WinRate.load(DIR.resolve("winprob_linear_wrong_rules.onnx")));
		assertTrue(e.getMessage().contains("tetris.rules"), e.getMessage());
		// 출력이 [N] 인 모델은 불러올 때 거부해야 게임 도중에 깨지지 않는다.
		e = assertThrows(IllegalArgumentException.class, () -> WinRate.load(DIR.resolve("winprob_linear_flat_output.onnx")));
		assertTrue(e.getMessage().contains("p1_win"), e.getMessage());
		assertThrows(OrtException.class, () -> WinRate.load(DIR.resolve("winprob_linear.json")));
	}

	@Test
	void valueModelsAndWinRateModelsRejectEachOther() {
		assumeOnnxRuntime();
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> WinRate.load(FIXTURES.resolve("model_io").resolve("linear_v2.onnx")));
		assertTrue(e.getMessage().contains("tetris.model_io"), e.getMessage());
		e = assertThrows(IllegalArgumentException.class, () -> OnnxScorer.load(DIR.resolve("winprob_linear.onnx")));
		assertTrue(e.getMessage().contains("tetris.model_io"), e.getMessage());
	}

	// ------------------------------------------------------------------ 실행

	@Test
	void linearModelGivesHandCheckedValues() throws OrtException {
		// make_winprob_fixture.py 의 선형 모델: p1_win = σ(w_board · (b1 − b2) + w_features · (f1 − f2)).
		// w_features[51] = (51 % 5 − 2)·0.05 = −0.05, w_board[230](23 행 0 열) = (230 % 7 − 3)·0.01 = 0.03.
		assumeOnnxRuntime();
		Grid oneCell = new Grid();
		oneCell.set(23, 0, Grid.GARBAGE);
		PlayerState withCell = new PlayerState(oneCell, PieceType.T, empty(PieceType.T, 0).queue(), null, -1, false, 0);
		try (WinRate model = WinRate.load(DIR.resolve("winprob_linear.onnx"), 1)) {
			assertEquals(0.5, model.p1Win(empty(PieceType.T, 0), empty(PieceType.T, 0)), 1e-7);
			assertEquals(1 / (1 + Math.exp(0.2)), model.p1Win(empty(PieceType.T, 4), empty(PieceType.T, 0)), 1e-6);
			assertEquals(1 / (1 + Math.exp(0.03)), model.p1Win(empty(PieceType.T, 0), withCell), 1e-6);
			assertTrue(model.name().contains("winprob_linear.onnx"), model.name());
			assertEquals("winprob-1", model.metadata().get("tetris.model_io"));
		}
	}

	@Test
	void swappingPlayersGivesOneMinusP() throws OrtException {
		// 학습에 쓰는 구조(작은 모델)는 로짓이 g(a, b) − g(b, a) 라서 자리를 바꾸면 1 − p 다 (TRAINING.md 10.3).
		assumeOnnxRuntime();
		List<PlayerState> a = sampleStates(5);
		List<PlayerState> b = sampleStates(6);
		int n = Math.min(a.size(), b.size());
		a = a.subList(0, n);
		b = b.subList(0, n);
		try (WinRate model = WinRate.load(DIR.resolve("winprob_tiny.onnx"))) {
			double[] ab = model.p1Win(a, b);
			double[] ba = model.p1Win(b, a);
			for (int i = 0; i < ab.length; i++) {
				assertEquals(1.0, ab[i] + ba[i], 1e-6, "쌍 " + i);
				// 한 번에 계산한 값과 하나씩 계산한 값이 같다.
				assertEquals(ab[i], model.p1Win(a.get(i), b.get(i)), 1e-6, "쌍 " + i);
			}
		}
	}

	/** 기준 AI 끼리 둔 판의 상태들. 시드마다 다른 판이다. */
	private static List<PlayerState> sampleStates(long seed) {
		List<PlayerState> out = new ArrayList<>();
		JsonNode m = MatchRunner.play("t", 0, seed, 40, baseline("a", 1.0), baseline("b", 1.0));
		for (JsonNode step : m.get("steps")) {
			out.add(recordState(step.get("p1").get("state")));
		}
		return out.subList(0, Math.min(out.size(), 30));
	}

	@Test
	void finishedGamesGiveResultWithoutCallingModel() throws OrtException {
		assumeOnnxRuntime();
		Grid blocked = new Grid();
		for (int c = 0; c < Grid.WIDTH; c++) {
			blocked.set(2, c, Grid.GARBAGE); // 스폰 칸(2~3행)이 막혀 시작하자마자 블록 아웃
		}
		GameModel over = new GameModel(1, 1, GameModel.Scheduler.NONE, blocked);
		GameModel live = new GameModel(1, 2, GameModel.Scheduler.NONE);
		assertTrue(over.isOver());
		WinRate model = WinRate.load(DIR.resolve("winprob_linear.onnx"));
		model.close(); // 닫힌 모델이라도 끝난 판에는 모델을 부르지 않는다
		assertEquals(0.0, model.p1Win(over, live));
		assertEquals(1.0, model.p1Win(live, over));
		assertEquals(0.5, model.p1Win(over, over));
		assertThrows(IllegalStateException.class, () -> model.p1Win(live, live));
		model.close(); // 두 번 닫아도 된다
	}

	@Test
	void emptyAndMismatchedBatches() throws OrtException {
		assumeOnnxRuntime();
		try (WinRate model = WinRate.load(DIR.resolve("winprob_linear.onnx"))) {
			assertEquals(0, model.p1Win(List.of(), List.of()).length);
			assertThrows(IllegalArgumentException.class, () -> model.p1Win(List.of(empty(PieceType.T, 0)), List.of()));
		}
	}

	@Test
	void sharedByThreadsGivesSameResults() throws Exception {
		assumeOnnxRuntime();
		List<PlayerState> a = sampleStates(7);
		List<PlayerState> b = sampleStates(8);
		int n = Math.min(a.size(), b.size());
		try (WinRate model = WinRate.load(DIR.resolve("winprob_tiny.onnx"), 1)) {
			double[] want = model.p1Win(a.subList(0, n), b.subList(0, n));
			ExecutorService pool = Executors.newFixedThreadPool(4);
			try {
				List<Future<double[]>> futures = new ArrayList<>();
				for (int w = 0; w < 4; w++) {
					futures.add(pool.submit(() -> {
						double[] got = new double[n];
						for (int rep = 0; rep < 10; rep++) {
							for (int i = 0; i < n; i++) {
								got[i] = model.p1Win(a.get(i), b.get(i));
							}
						}
						return got;
					}));
				}
				for (Future<double[]> f : futures) {
					assertArrayEquals(want, f.get(), 1e-6);
				}
			} finally {
				pool.shutdown();
			}
		}
	}
}
