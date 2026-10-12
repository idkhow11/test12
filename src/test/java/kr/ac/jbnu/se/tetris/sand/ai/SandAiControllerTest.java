package kr.ac.jbnu.se.tetris.sand.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 모래 AI 조작기 {@link SandAiController}: 단계 속도, 생각 지연과 입력 간격, 모든 입력이 성공하는지, 일꾼 스레드가 판을 바꾸지
 * 않는지(결정론), 고르다 난 오류, 무작위 손보다 오래 버티는지.
 */
class SandAiControllerTest {

	/** 판을 진행하고 결과를 남긴다. {@code pool} 이 있으면 일꾼 스레드에서 고른다. */
	private record Result(short[] grains, long time, long score, int pieces, int spawns, boolean over, int planned,
			int prefetched) {
	}

	private static Result play(long seed, SandAiLevel level, long ms, java.util.concurrent.Executor pool) {
		return play(seed, level, ms, pool, new SandBrain(level));
	}

	private static Result play(long seed, SandAiLevel level, long ms, java.util.concurrent.Executor pool,
			Planner planner) {
		SandGame g = new SandGame(seed, 1, 0);
		SandAiController ai = new SandAiController(g, null, planner, level.thinkMs(), level.inputsPerSec(),
				SandAiController.rng(seed, 1));
		ai.prefetchWith(pool);
		while (!g.over() && g.time() < ms) {
			g.advance(ai.poll());
		}
		short[] grains = new short[SandRules.WIDTH * SandRules.HEIGHT];
		g.copyGrains(grains);
		return new Result(grains, g.time(), g.score(), g.pieces(), g.spawns(), g.over(), ai.planned(), ai.prefetched());
	}

	/**
	 * 기본 단계의 조작 속도는 docs/GAME.md 6.1 의 고전 AI 단계와 같은 1,450/700/400/180/100ms, 초당 5/6/10/16/25 이다. 고전 단계를
	 * 넘기면({@link SandAiLevel#of(AiCatalog.Difficulty)}) 이름과 속도는 그 단계(모델 묶음의 값)를 따르고 두뇌 설정은 같은 번호의
	 * 기본값이다.
	 */
	@Test
	void levelSpeedsFollowTheClassicCatalog() {
		assertEquals(List.of("초급", "중급", "상급", "최상급", "불가능"),
				SandAiLevel.LEVELS.stream().map(SandAiLevel::name).toList());
		assertEquals(List.of(1450, 700, 400, 180, 100), SandAiLevel.LEVELS.stream().map(SandAiLevel::thinkMs).toList());
		assertEquals(List.of(5.0, 6.0, 10.0, 16.0, 25.0),
				SandAiLevel.LEVELS.stream().map(SandAiLevel::inputsPerSec).toList());
		assertEquals(List.of(200, 167, 100, 63, 40), SandAiLevel.LEVELS.stream().map(SandAiLevel::intervalMs).toList());
		assertEquals(List.of(3, 3, 6, 6, 6), SandAiLevel.LEVELS.stream().map(SandAiLevel::lookahead).toList(),
				"sand-0.2 부터 모든 단계가 다음 블록까지 본다");
		for (AiCatalog.Difficulty d : AiCatalog.load(Path.of("models")).difficulties()) {
			SandAiLevel s = SandAiLevel.of(d);
			SandAiLevel base = SandAiLevel.of(d.id());
			assertEquals(d.name(), s.name());
			assertEquals(d.thinkMs(), s.thinkMs(), d.name());
			assertEquals(d.inputsPerSec(), s.inputsPerSec(), d.name());
			assertEquals(base.id(), s.id());
			assertEquals(base.hold(), s.hold());
			assertEquals(base.temperature(), s.temperature());
			assertEquals(base.lookahead(), s.lookahead());
		}
	}

	/**
	 * 블록이 나온 뒤 생각 지연 동안은 아무것도 하지 않고, 블록을 본 틱부터 정확히 생각 지연 뒤의 틱에 고른 결과를 받아 첫 입력을
	 * 할 수 있다(목표에 이미 있고 아래 모래가 흐르면 그때부터 기다린다). 입력 사이는 입력 간격 이상이다. 생각하는 동안 진행률이
	 * 0 에서 1 로 오른다.
	 */
	@Test
	void itThinksForTheThinkDelayAndThenInputsNoFasterThanTheInterval() {
		SandAiLevel level = SandAiLevel.of(3);
		SandGame g = new SandGame(7, 1, 0);
		SandAiController ai = new SandAiController(g, level, 7, 1);
		int seen = -1;
		long seenAt = -1;
		long lastInput = -1;
		int firstInputs = 0;
		double lastProgress = -1;
		while (!g.over() && g.time() < 30_000) {
			if (g.pieces() != seen && g.piece() != null) {
				seen = g.pieces();
				seenAt = g.time();
				lastInput = -1;
				lastProgress = -1;
			}
			boolean decided = ai.plan() != null;
			Input in = ai.poll();
			double progress = ai.thinkProgress();
			if (in == null && progress >= 0) {
				assertTrue(progress >= lastProgress, "생각 진행률은 오르기만 한다");
				lastProgress = progress;
			}
			if (!decided && ai.plan() != null) {
				assertEquals(seenAt + level.thinkMs(), g.time(), "생각 지연이 끝나는 틱에 고른다");
			}
			if (in != null) {
				assertNotNull(ai.plan(), "고르기 전에 조작하지 않는다");
				if (lastInput < 0) {
					assertTrue(g.time() >= seenAt + level.thinkMs(), "첫 입력은 생각 지연 뒤");
					firstInputs++;
				} else {
					assertTrue(g.time() - lastInput >= level.intervalMs(), "입력 간격: " + (g.time() - lastInput));
				}
				lastInput = g.time();
			}
			g.advance(in);
		}
		assertTrue(firstInputs > 20, "블록마다 확인했다: " + firstInputs);
	}

	/**
	 * 보내는 조작은 모두 그 틱에 성공한다(엔진과 같은 칸 판에서 core {@link Movement} 로 확인). 블록이 없을 때는 아무것도 보내지
	 * 않는다. 홀드는 이번 블록에서 아직 쓰지 않았을 때만 보내고, 실패한 홀드(꺼낼 블록의 스폰 자리를 모래가 덮음)는 조작기가 센
	 * 수와 같다.
	 */
	@Test
	void everyInputItSendsSucceeds() {
		for (int lv : new int[] { 1, 5 }) {
			SandGame g = new SandGame(11 + lv, 1, 0);
			SandAiController ai = new SandAiController(g, SandAiLevel.of(lv), 11 + lv, 1);
			int moves = 0;
			int holds = 0;
			int failedHolds = 0;
			while (!g.over() && g.time() < 60_000) {
				Input in = ai.poll();
				if (in != null) {
					assertNotNull(g.piece(), "블록이 없을 때 조작");
					if (in == Input.H) {
						assertFalse(g.holdUsed(), "이번 블록에서 홀드를 이미 썼다");
						g.advance(in);
						holds++;
						failedHolds += g.holdUsed() ? 0 : 1;
						continue;
					}
					if (in != Input.HD) {
						Op op = Op.valueOf(in.name());
						assertNotNull(Movement.apply(g.cellGrid(), g.piece(), op), "실패하는 조작: " + in + " at " + g.time());
						moves++;
					}
				}
				g.advance(in);
			}
			assertTrue(moves > 30, "단계 " + lv + " 조작 수: " + moves);
			assertEquals(failedHolds, ai.holdFailures());
			if (lv == 5) {
				assertTrue(holds > 0, "불가능은 홀드를 쓴다");
			} else {
				assertEquals(0, holds, "초급은 홀드를 쓰지 않는다");
			}
			assertNull(ai.problem());
		}
	}

	/**
	 * 목표에 닿은 블록은 판의 모래가 멈춘 뒤에 떨어뜨린다({@link SandGame#sandStill()}). 멈추지 않으면 목표에 닿은 뒤
	 * {@value SandAiController#MAX_PACE_MS}ms 를 기다리고 떨어뜨린다. 두뇌가 멈춘 판에 놓는다고 보고 고르기 때문이다(규칙 sand-0.2
	 * 에서는 이것이 없으면 빠른 단계가 판을 메운다). 목표에 닿기 전 마지막 행동(고른 틱이나 마지막 조작)부터 센다. 큰 덩어리가
	 * 지워진 뒤에는 높은 절벽이 모래 스텝마다 한 알씩 무너지므로 판이 몇 초 동안 계속 움직일 수 있고, 그때는 기다리다 떨어뜨린다
	 * (10-07, 시드 61 은 블록 55개 중 44개가 그랬다. 이 시험의 시드 62 는 98개 중 12개).
	 */
	@Test
	void itDropsOnlyOnStillSandOrAfterWaitingLongEnough() {
		SandAiLevel level = SandAiLevel.of(5);
		SandGame g = new SandGame(62, 1, 0);
		SandAiController ai = new SandAiController(g, level, 62, 1);
		long lastAct = 0;
		int seenPlans = 0;
		int onStill = 0;
		int late = 0;
		while (!g.over() && g.time() < 60_000) {
			Input in = ai.poll();
			if (ai.planned() != seenPlans) {
				seenPlans = ai.planned();
				lastAct = g.time();
			}
			if (in == Input.HD && ai.target() != null) {
				if (g.sandStill()) {
					onStill++;
				} else {
					assertTrue(g.time() - lastAct >= SandAiController.MAX_PACE_MS,
							"움직이는 모래 위에 일찍 떨어뜨렸다: " + g.time() + "ms, 마지막 행동 " + lastAct + "ms");
					late++;
				}
			}
			if (in != null) {
				lastAct = g.time();
			}
			g.advance(in);
		}
		assertTrue(onStill > 40, "멈춘 모래 위에 떨어뜨린 블록: " + onStill);
		assertTrue(late * 3 < onStill, "기다리다 상한이 되어 떨어뜨린 블록은 드물다: " + late + " / " + onStill);
		assertTrue(ai.paced() > 10, "모래를 기다린 블록: " + ai.paced());
		assertFalse(g.over(), "불가능은 1분을 버틴다");
	}

	/**
	 * 판은 고르기를 어디서 언제 하든 비트 단위로 똑같다: 일꾼이 없을 때, 일꾼 스레드에서 할 때, 일꾼이 느려(블록마다 30ms 더 걸림)
	 * 진행 스레드가 생각 지연이 끝나는 틱에 기다릴 때, 일꾼이 늦게 시작해(30ms 쉰 뒤) 그 틱에 그 자리에서 다시 고를 때, 부르는
	 * 자리에서 바로 할 때. 고르는 데 쓰는 것은 블록이 나온 틱의 복사본과 그 블록의 난수 값뿐이기 때문이다. (이 시험은 판을 실제
	 * 시간보다 훨씬 빨리 돌리므로 일꾼이 늘 늦는 쪽이다.)
	 */
	@Test
	void theWorkerThreadNeverChangesTheGame() throws Exception {
		SandAiLevel level = SandAiLevel.of(5);
		SandBrain brain = new SandBrain(level);
		Planner slowBrain = (s, noise) -> {
			sleep(30);
			return brain.plan(s, noise);
		};
		ExecutorService worker = Executors.newSingleThreadExecutor();
		ExecutorService late = Executors.newSingleThreadExecutor();
		try {
			Result none = play(21, level, 20_000, null);
			Result pooled = play(21, level, 20_000, worker);
			Result waited = play(21, level, 20_000, worker, slowBrain);
			Result restarted = play(21, level, 20_000, r -> late.execute(() -> {
				sleep(30);
				r.run();
			}));
			Result inline = play(21, level, 20_000, Runnable::run);
			assertTrue(none.pieces() > 20, "판이 충분히 진행됐다: " + none.pieces());
			assertEquals(0, none.prefetched());
			for (Result r : List.of(pooled, waited, restarted, inline)) {
				assertArrayEquals(none.grains(), r.grains());
				assertEquals(none.time(), r.time());
				assertEquals(none.score(), r.score());
				assertEquals(none.pieces(), r.pieces());
				assertEquals(none.spawns(), r.spawns());
				assertEquals(none.over(), r.over());
				assertEquals(none.planned(), r.planned());
			}
			assertTrue(pooled.prefetched() > 0, "일꾼이 고른 답을 썼다");
			assertTrue(waited.prefetched() > 0, "느린 일꾼을 기다려 그 답을 썼다");
			assertEquals(inline.planned(), inline.prefetched());
		} finally {
			worker.shutdownNow();
			late.shutdownNow();
			assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 같은 시드면 같은 판, 다른 시드면 다른 판. */
	@Test
	void sameSeedSameGame() {
		Result a = play(31, SandAiLevel.of(3), 30_000, null);
		Result b = play(31, SandAiLevel.of(3), 30_000, null);
		Result c = play(32, SandAiLevel.of(3), 30_000, null);
		assertArrayEquals(a.grains(), b.grains());
		assertEquals(a.score(), b.score());
		assertFalse(java.util.Arrays.equals(a.grains(), c.grains()));
	}

	/**
	 * 고르다 예외가 나면(세 번째 블록의 판에서는 언제나) 일꾼이 낸 오류는 버리고 그 자리에서 다시 골라 보고, 그래도 나면 그 블록은
	 * 그 자리에 떨어뜨리고 이유를 남긴다. 판은 계속되고 다음 블록부터는 다시 고른다. 일꾼이 있든 없든 같다.
	 */
	@Test
	void aPlannerErrorDropsThatPieceInPlaceAndTheGameGoesOn() {
		SandAiLevel level = SandAiLevel.of(4);
		SandBrain brain = new SandBrain(level);
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try {
			for (ExecutorService pool : new ExecutorService[] { null, worker }) {
				List<Situation> seen = new ArrayList<>();
				Planner flaky = (s, noise) -> {
					synchronized (seen) {
						if (!seen.contains(s)) {
							seen.add(s);
						}
						if (seen.indexOf(s) == 2) {
							throw new IllegalStateException("일부러 낸 오류");
						}
					}
					return brain.plan(s, noise);
				};
				SandGame g = new SandGame(41, 1, 0);
				SandAiController ai = new SandAiController(g, brain, flaky, level.thinkMs(), level.inputsPerSec(),
						SandAiController.rng(41, 1));
				ai.prefetchWith(pool);
				int piecesAtError = -1;
				while (!g.over() && g.time() < 30_000) {
					g.advance(ai.poll());
					if (piecesAtError < 0 && ai.problem() != null) {
						piecesAtError = g.pieces();
					}
				}
				assertNotNull(ai.problem(), "이유를 남긴다");
				assertTrue(ai.problem().contains("일부러 낸 오류"), ai.problem());
				assertEquals(3, piecesAtError, "세 번째 블록을 그 자리에 떨어뜨렸다");
				assertTrue(g.pieces() > piecesAtError + 15, "판은 계속된다: " + g.pieces());
				assertFalse(g.over());
			}
		} finally {
			worker.shutdownNow();
		}
	}

	/**
	 * 무작위 손(같은 조작기와 상급 속도, 고르기만 갈 수 있는 자리 중 아무 데나)은 1분 안팎에 판을 메우지만(10-07 벤치마크,
	 * sand-0.2: 평균 50초), 상급 AI 는 같은 시드에서 2분을 버티고 점수도 훨씬 높다.
	 */
	@Test
	void itSurvivesLongerThanARandomBaseline() {
		SandAiLevel level = SandAiLevel.of(3);
		List<String> report = new ArrayList<>();
		for (long seed = 51; seed <= 52; seed++) {
			Result ai = play(seed, level, 120_000, null);
			SandGame g = new SandGame(seed, 1, 0);
			SandAiController random = new SandAiController(g, SandBrain.random(level), level.thinkMs(),
					level.inputsPerSec(), SandAiController.rng(seed, 1));
			while (!g.over() && g.time() < 120_000) {
				g.advance(random.poll());
			}
			report.add(seed + ": AI " + ai.time() + "ms " + ai.score() + "점, 무작위 " + g.time() + "ms " + g.score() + "점");
			assertFalse(ai.over(), "AI 는 2분을 버틴다: " + report);
			assertTrue(g.over(), "무작위 손은 2분 안에 진다: " + report);
			assertTrue(ai.score() > 3 * g.score(), report.toString());
		}
	}
}
