package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.onnxruntime.OrtException;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.train.MatchRunner;

/**
 * 승률 예측망에 붙는 보정층 (TRAINING.md 10.12). 보정 파일이 없으면 지금과 똑같고, 그 모델의 보정 파일이면 붙고, 다른 모델의
 * 것이거나 깨졌으면 붙이지 않은 채 모델을 그대로 쓴다. 게임 묶음(models/)으로는 Python 이 낸 실제 상태 쌍의 p 와 보정값을
 * Java 가 그대로 내는지(fixtures/winprob_calibration) 끝에서 끝까지 본다. ONNX Runtime 이 없거나 묶음이 없으면 그 시험만
 * 건너뛴다.
 */
class WinRateCalibrationTest {

	private static final Path FIXTURES = Path.of(System.getProperty("fixtures.dir", "fixtures"));
	private static final Path LINEAR = FIXTURES.resolve("winprob").resolve("winprob_linear.onnx");
	private static final Path BUNDLE = Path.of("models", "winprob.onnx");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final BaselineScorer BASELINE = new BaselineScorer();

	private static void assumeOnnxRuntime() {
		assumeTrue(WinRate.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
	}

	/** 빈 보드, 대기 줄 pending, 블록 수 pieces 인 상태. 선형 시험 모델에서 대기 줄은 로짓을 줄마다 0.05 바꾼다. */
	private static PlayerState state(int pending, int pieces) {
		return new PlayerState(new Grid(), PieceType.T, List.of(PieceType.I, PieceType.O, PieceType.S, PieceType.L,
				PieceType.Z), null, -1, false, pending, pieces);
	}

	/** 시험 모델을 dir/winprob.onnx 로 복사하고, 그 옆에 calibration 을 winprob_calibration.json 으로 쓴다(null 이면 쓰지 않음). */
	private static Path modelWith(Path dir, String calibration) throws IOException {
		Path model = dir.resolve("winprob.onnx");
		Files.copy(LINEAR, model);
		if (calibration != null) {
			Files.writeString(WinCalibration.fileFor(model), calibration, StandardCharsets.UTF_8);
		}
		return model;
	}

	/** 픽스처의 all_terms 보정층(모든 항을 쓰는 일반 식)에 이 모델 파일의 SHA-256 을 붙인 것. */
	private static String calibrationFor(Path model, String sha) throws IOException {
		ObjectNode spec = WinCalibrationFixture.read().specs.get("all_terms").deepCopy();
		spec.put("model", "linear").put("model_sha256", sha == null ? WinCalibration.sha256(model) : sha);
		return JSON.writeValueAsString(spec);
	}

	// ------------------------------------------------------------------ 보정 파일과 모델

	@Test
	void withoutACalibrationFileNothingChanges(@TempDir Path dir) throws Exception {
		assumeOnnxRuntime();
		try (WinRate win = WinRate.load(modelWith(dir, null), 1)) {
			assertNull(win.calibration());
			assertNull(win.calibrationProblem(), "보정 파일이 없는 것은 문제가 아니다");
			for (int pending = 0; pending <= 8; pending += 4) {
				PlayerState a = state(pending, 12);
				PlayerState b = state(0, 3);
				assertEquals(win.p1Win(a, b), win.p1Win(a, b, 9_000), 0.0, "모델 출력 그대로");
			}
		}
	}

	@Test
	void theCalibrationOfThisModelIsAttached(@TempDir Path dir) throws Exception {
		assumeOnnxRuntime();
		Path model = modelWith(dir, null);
		Files.writeString(WinCalibration.fileFor(model), calibrationFor(model, null), StandardCharsets.UTF_8);
		WinCalibration expected = WinCalibrationFixture.read().calibration("all_terms");
		try (WinRate win = WinRate.load(model, 1)) {
			assertNotNull(win.calibration());
			assertNull(win.calibrationProblem());
			assertEquals("all_terms", win.calibration().candidate());
			for (int[] c : new int[][] { { 4, 12, 3, 9_000 }, { 0, 0, 0, 0 }, { 8, 40, 41, 61_000 }, { 2, 1, 0, 700 } }) {
				PlayerState a = state(c[0], c[1]);
				PlayerState b = state(0, c[2]);
				double p = win.p1Win(a, b);
				assertEquals(expected.apply(p, c[1], c[2], c[3]), win.p1Win(a, b, c[3]), 0.0);
				assertEquals(p, win.p1Win(state(c[0], -1), b, c[3]), 0.0, "블록 수를 모르면 모델 출력 그대로");
				assertEquals(p, win.p1Win(List.of(a), List.of(b))[0], 0.0, "시간을 받지 않는 메서드는 그대로");
			}
			// 판 시작: 두 상태가 같으면 모델 출력이 0.5 이고 블록 수 0, 시간 0 이라 보정해도 0.5
			assertEquals(0.5, win.p1Win(state(0, 0), state(0, 0), 0), 1e-7);
		}
	}

	@Test
	void aCalibrationOfAnotherModelIsNotUsed(@TempDir Path dir) throws Exception {
		assumeOnnxRuntime();
		Path model = modelWith(dir, null);
		Files.writeString(WinCalibration.fileFor(model), calibrationFor(model, "f".repeat(64)), StandardCharsets.UTF_8);
		try (WinRate win = WinRate.load(model, 1)) {
			assertNull(win.calibration());
			assertTrue(win.calibrationProblem().contains("sha256"), win.calibrationProblem());
			PlayerState a = state(4, 12);
			PlayerState b = state(0, 3);
			assertEquals(win.p1Win(a, b), win.p1Win(a, b, 9_000), 0.0);
		}
	}

	@Test
	void aBrokenCalibrationFileDoesNotStopTheModel(@TempDir Path dir) throws Exception {
		assumeOnnxRuntime();
		for (String broken : new String[] { "{", "{\"format\": \"winprob-calibration-1\", \"kind\": \"general\"}", "[]" }) {
			Path sub = Files.createDirectory(dir.resolve("m" + broken.length()));
			try (WinRate win = WinRate.load(modelWith(sub, broken), 1)) {
				assertNull(win.calibration());
				assertNotNull(win.calibrationProblem(), broken);
				assertTrue(win.calibrationProblem().startsWith("winprob_calibration.json"), win.calibrationProblem());
				assertEquals(win.p1Win(state(4, 1), state(0, 2)), win.p1Win(state(4, 1), state(0, 2), 100), 0.0);
			}
		}
	}

	@Test
	void finishedGamesGiveTheResultWithoutCallingTheModel(@TempDir Path dir) throws Exception {
		assumeOnnxRuntime();
		Path model = modelWith(dir, null);
		Files.writeString(WinCalibration.fileFor(model), calibrationFor(model, null), StandardCharsets.UTF_8);
		Grid blocked = new Grid();
		for (int c = 0; c < Grid.WIDTH; c++) {
			blocked.set(2, c, Grid.GARBAGE); // 스폰 칸이 막혀 시작하자마자 블록 아웃
		}
		GameModel over = new GameModel(1, 1, GameModel.Scheduler.NONE, blocked);
		GameModel live = new GameModel(1, 2, GameModel.Scheduler.NONE);
		WinRate win = WinRate.load(model, 1);
		assertNotNull(win.calibration());
		win.close(); // 닫힌 모델이라도 끝난 판에는 모델을 부르지 않는다
		assertEquals(0.0, win.p1Win(over, live, 5_000));
		assertEquals(1.0, win.p1Win(live, over, 5_000));
		assertEquals(0.5, win.p1Win(over, over, 5_000));
		assertThrows(IllegalStateException.class, () -> win.p1Win(live, live, 5_000));
	}

	// ------------------------------------------------------------------ 블록 수

	@Test
	void playerStateCopiesThePiecesPlacedSoFar() {
		assertEquals(-1, state(0, -1).pieces());
		assertEquals(-1, new PlayerState(new Grid(), PieceType.T, List.of(), null, -1, false, 0).pieces(), "옛 생성자는 모름");
		assertThrows(IllegalArgumentException.class,
				() -> new PlayerState(new Grid(), PieceType.T, List.of(), null, -1, false, 0, -2));
		// 기준 AI 끼리 둔 판을 두 게임으로 다시 두며(WinRateTest 와 같은 방법) 고정할 때마다 복사한 블록 수를 본다
		JsonNode m = MatchRunner.play("t", 0, 31, 60, baseline("a", 0.3), baseline("b", 0.3));
		GameModel[] games = { new GameModel(31, 1, GameModel.Scheduler.NONE), new GameModel(31, 2, GameModel.Scheduler.NONE) };
		assertEquals(0, PlayerState.of(games[0]).pieces());
		List<Integer> seen = new ArrayList<>();
		for (JsonNode step : m.get("steps")) {
			JsonNode[] turn = { step.get("p1"), step.get("p2") };
			GameModel.LockResult[] r = new GameModel.LockResult[2];
			for (int i = 0; i < 2; i++) {
				r[i] = games[i].applyKey(turn[i].get("key").asText());
			}
			if (games[0].isOver() || games[1].isOver()) {
				break;
			}
			games[1].receive(r[0].sent());
			games[0].receive(r[1].sent());
			for (GameModel g : games) {
				assertEquals(g.pieces(), PlayerState.of(g).pieces());
			}
			seen.add(games[0].pieces());
		}
		assertTrue(seen.size() > 10 && seen.get(seen.size() - 1) > 10, "블록 수 " + seen);
	}

	private static MatchRunner.Player baseline(String name, double temperature) {
		return new MatchRunner.Player(name, "neutral", "baseline", temperature, BASELINE);
	}

	// ------------------------------------------------------------------ 게임 묶음: Python 과 끝에서 끝까지

	/** 묶음의 승률 모델이 픽스처의 모델과 같고 보정 파일이 있을 때만 (아니면 건너뜀). */
	private static WinCalibrationFixture assumeBundle() throws IOException {
		assumeOnnxRuntime();
		assumeTrue(Files.isRegularFile(BUNDLE), "models/winprob.onnx 가 없어 건너뜀");
		WinCalibrationFixture fx = WinCalibrationFixture.read();
		assumeTrue(fx.header.path("onnx_sha256").asText().equals(WinCalibration.sha256(BUNDLE)),
				"models/winprob.onnx 가 픽스처의 모델과 달라 건너뜀 (fit_winprob_calibration.py --fixture-from 으로 다시 만든다)");
		assumeTrue(Files.isRegularFile(WinCalibration.fileFor(BUNDLE)), "models/ 에 보정층이 없어 건너뜀");
		return fx;
	}

	@Test
	void theBundleGivesThePythonValuesOnRealStates() throws IOException, OrtException {
		WinCalibrationFixture fx = assumeBundle();
		double tolP = fx.tolerance("p_onnx");
		double tolCal = fx.tolerance("p_cal");
		double tolEnd = fx.tolerance("p_cal_end_to_end");
		// p 는 CPU 마다 onnxruntime 커널이 달라 다른 ONNX 픽스처와 같은 1e-5, 보정값은 그 차이를 키우므로 1e-4 까지 (TRAINING.md 10.12)
		assertTrue(tolP <= 1e-5 && tolCal <= 1e-9 && tolEnd <= 1e-4, "허용 오차 " + fx.header.get("tolerance"));
		try (WinRate win = WinRate.load(BUNDLE, 1)) {
			assertNull(win.calibrationProblem());
			WinCalibration cal = win.calibration();
			assertNotNull(cal, "묶음의 보정층이 붙어야 함");
			// 묶음의 보정층 = 픽스처의 bundle (다르면 픽스처를 다시 만든다: fit_winprob_calibration.py --fixture-from <실행>)
			for (JsonNode c : fx.formula) {
				if (c.get("spec").asText().equals("bundle")) {
					assertEquals(c.get("p_cal").asDouble(), cal.apply(c.get("p").asDouble(), c.get("n1").asInt(),
							c.get("n2").asInt(), c.get("t_ms").asLong()), tolCal, c.get("id").asText());
				}
			}
			double worstP = 0;
			double worstEnd = 0;
			for (JsonNode c : fx.states) {
				String id = c.get("id").asText();
				int n1 = c.get("n1").asInt();
				int n2 = c.get("n2").asInt();
				long t = c.get("t_ms").asLong();
				PlayerState s1 = WinCalibrationFixture.recordState(c.get("p1"), n1);
				PlayerState s2 = WinCalibrationFixture.recordState(c.get("p2"), n2);
				double p = win.p1Win(s1, s2);
				double end = win.p1Win(s1, s2, t); // 게임이 막대에 쓰는 값
				assertEquals(c.get("p").asDouble(), p, tolP, id + " 모델 출력");
				assertEquals(cal.apply(p, n1, n2, t), end, 0.0, id);
				assertEquals(c.get("p_cal").asDouble(), end, tolEnd, id + " 보정값");
				assertEquals(p, win.p1Win(WinCalibrationFixture.recordState(c.get("p1"), -1), s2, t), 0.0, id + " 블록 수 모름");
				worstP = Math.max(worstP, Math.abs(p - c.get("p").asDouble()));
				worstEnd = Math.max(worstEnd, Math.abs(end - c.get("p_cal").asDouble()));
			}
			System.out.printf("상태 쌍 %d개: p 차이 최대 %.3g, 보정값 차이 최대 %.3g%n", fx.states.size(), worstP, worstEnd);
		}
	}

	@Test
	void theBundleBarStartsAtExactlyHalf() throws IOException, OrtException {
		assumeBundle();
		try (WinRate win = WinRate.load(BUNDLE, 1)) {
			assertNotNull(win.calibration());
			for (long seed : new long[] { 1, 6, 8 }) { // GameSession 생성자의 첫 막대와 같은 순간
				TimedMatch match = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, 8_000);
				GameModel g1 = match.player(0).game();
				GameModel g2 = match.player(1).game();
				assertEquals(0.5, win.p1Win(PlayerState.of(g1), PlayerState.of(g2), match.time()), 0.0, "시드 " + seed);
				assertEquals(0.5, win.p1Win(g1, g2, match.time()), 0.0);
			}
		}
	}
}
