package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/** AI 조작기 (PLAN 4.3, docs/GAME.md 5장). */
class AiControllerTest {

	private static Brain baseline(long seed) {
		return new Brain(new BaselineScorer(), 0.0, null, Chooser.rng(seed, 1));
	}

	/** 한 틱: 조작기에게 묻고 그 조작으로 진행한다. */
	private static GameModel.LockResult tick(AiController ai) {
		return ai.player().tick(ai.poll());
	}

	@Test
	void withoutGravityTheAiPlaysExactlyTheTimelessChoices() {
		// 중력이 없으면 생각이 끝날 때 블록은 스폰 위치에 있으므로, 시간 없는 모드와 같은 배치를 같은 순서로 둔다.
		long seed = 77;
		GameModel timeless = new GameModel(seed, 1, GameModel.Scheduler.S_MIX);
		Brain plain = baseline(seed);
		TimedPlayer p = new TimedPlayer(new GameModel(seed, 1, GameModel.Scheduler.S_MIX), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, baseline(seed), 50, 30);
		for (int piece = 0; piece < 40 && !timeless.isOver(); piece++) {
			timeless.apply(plain.decide(timeless, timeless.placements(), Opponent.NONE).placement());
			int ticks = 0;
			while (p.game().pieces() == piece && !p.game().isOver()) {
				tick(ai);
				assertTrue(++ticks < 10_000, "블록 하나에 10초 넘게 걸림");
			}
			assertTrue(timeless.grid().sameOccupancy(p.game().grid()), (piece + 1) + " 번째 블록 뒤 보드가 다름");
			assertEquals(timeless.hold(), p.game().hold());
		}
		assertEquals(0, ai.failed(), "중력이 없으면 목표를 바꿀 일이 없다");
		assertTrue(ai.planned() >= 40);
	}

	@Test
	void respectsThinkDelayAndInputInterval() {
		TimedPlayer p = new TimedPlayer(new GameModel(3, 1, GameModel.Scheduler.NONE), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, baseline(3), 300, 10); // 100ms 간격
		List<Long> inputTicks = new ArrayList<>();
		while (p.game().pieces() == 0) {
			Input in = ai.poll();
			if (in != null) {
				inputTicks.add(p.ticks());
			}
			p.tick(in);
		}
		assertEquals(300L, inputTicks.get(0), "300 틱(0 부터) 동안 생각하고 다음 틱에 첫 입력");
		for (int i = 1; i < inputTicks.size(); i++) {
			assertEquals(100L, inputTicks.get(i) - inputTicks.get(i - 1), "입력 사이 100ms");
		}
	}

	@Test
	void holdPlacementsPressHoldFirst() {
		// 홀드 칸에 I 가 남는 배치만 좋아하는 점수: 시드 0(현재 I, 다음 S)에서는 S 를 두고 I 를 홀드하는 배치를 고른다.
		PlacementScorer likesHeldI = new PlacementScorer() {
			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				double[] out = new double[candidates.size()];
				for (int i = 0; i < out.length; i++) {
					out[i] = candidates.get(i).hold() == PieceType.I ? 1 : 0;
				}
				return out;
			}

			@Override
			public String name() {
				return "held-I";
			}
		};
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, new Brain(likesHeldI, 0, null, Chooser.rng(0, 1)),
				0, 1000);
		assertEquals(Input.H, ai.poll());
		p.tick(Input.H);
		while (p.game().pieces() == 0) {
			tick(ai);
		}
		assertEquals(PieceType.I, p.game().hold());
		assertEquals(0, ai.failed());
	}

	@Test
	void fastGravityForcesReplansButThePlayKeepsGoing() {
		TimedMatch m = new TimedMatch(11, 1, GameModel.Scheduler.NONE, 12000); // 처음부터 30초마다 오르는 중력
		TimedPlayer p = m.player(0);
		AiController ai = new AiController(p, () -> Opponent.NONE, baseline(11), 600, 2); // 아주 느린 손
		while (!m.isOver() && m.time() < 300_000) {
			m.tick(ai.poll());
		}
		assertTrue(p.game().pieces() > 10, "블록을 계속 둔다: " + p.game().pieces());
		assertTrue(ai.failed() > 0, "느린 손으로는 목표를 바꿔야 할 때가 있다");
		assertTrue(ai.failureRate() <= 1.0);
	}

	@Test
	void twoAisPlayAFullTimedMatch() {
		TimedMatch m = new TimedMatch(5, 2, GameModel.Scheduler.NONE, 12000);
		AiController a = new AiController(m.player(0), () -> Opponent.of(m.player(1).game()), baseline(5), 100, 25);
		AiController b = new AiController(m.player(1), () -> Opponent.of(m.player(0).game()),
				new Brain(new BaselineScorer(), 0.0, null, Chooser.rng(5, 2)), 400, 8);
		while (!m.isOver() && m.time() < 1_200_000) {
			m.tick(a.poll(), b.poll());
		}
		assertTrue(m.isOver(), "20분 안에 끝난다");
		assertNotNull(m.result());
		assertTrue(m.player(0).game().sentTotal() + m.player(1).game().sentTotal() > 0);
	}

	/** 몇 번은 기준 AI 처럼 점수를 매기다가 그 뒤로는 실행 오류를 내는 모델 (판 도중의 ONNX 오류 흉내). */
	private static final class BreaksAfter implements PlacementScorer {
		private final PlacementScorer inner = new BaselineScorer();
		private final int calls;
		private int seen;

		BreaksAfter(int calls) {
			this.calls = calls;
		}

		@Override
		public double[] score(List<Afterstate> candidates, Opponent opponent) {
			if (seen++ >= calls) {
				throw new IllegalStateException("모델 실행 실패: 시험용");
			}
			return inner.score(candidates, opponent);
		}

		@Override
		public String name() {
			return "breaks-after-" + calls;
		}
	}

	@Test
	void modelErrorSwitchesToBaselineAndKeepsPlaying() {
		TimedMatch m = new TimedMatch(21, 1, GameModel.Scheduler.NONE, 3000);
		AiController ai = new AiController(m.player(0), () -> Opponent.NONE,
				new Brain(new BreaksAfter(5), 0, null, Chooser.rng(21, 1)), 50, 20);
		while (!m.isOver() && m.player(0).game().pieces() < 30) {
			m.tick(ai.poll());
		}
		assertTrue(m.player(0).game().pieces() >= 30, "모델이 오류를 낸 뒤에도 계속 둔다");
		assertNotNull(ai.fallback());
		assertTrue(ai.fallback().contains("breaks-after-5"), ai.fallback());
		assertInstanceOf(BaselineScorer.class, ai.brain().scorer());
	}

	@Test
	void nanScoresAlsoFallBackToBaseline() {
		PlacementScorer nan = new PlacementScorer() {
			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				double[] out = new double[candidates.size()];
				Arrays.fill(out, Double.NaN);
				return out;
			}

			@Override
			public String name() {
				return "nan";
			}
		};
		TimedPlayer p = new TimedPlayer(new GameModel(4, 1, GameModel.Scheduler.NONE), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, new Brain(nan, 0, null, Chooser.rng(4, 1)), 0, 100);
		while (p.game().pieces() < 5) {
			p.tick(ai.poll());
		}
		assertNotNull(ai.fallback(), "망가진 점수(NaN)도 모델 오류로 본다");
	}

	/** 기준 AI 처럼 점수를 매기되 부를 때마다 조금 쉰다: 일꾼이 아직 고르는 중일 때 생각이 끝나는 경우를 만든다. */
	private static final class Slow implements PlacementScorer {
		private final PlacementScorer inner = new BaselineScorer();

		@Override
		public double[] score(List<Afterstate> candidates, Opponent opponent) {
			// 0.3ms 바쁘게 기다린다. Thread.sleep(1) 은 Windows 의 타이머 간격 때문에 15ms 까지 늘어날 수 있다.
			long until = System.nanoTime() + 300_000;
			while (System.nanoTime() < until) {
				Thread.onSpinWait();
			}
			return inner.score(candidates, opponent);
		}

		@Override
		public String name() {
			return "slow-baseline";
		}
	}

	/**
	 * 두 AI 의 대전 한 판을 고정마다 적는다(틱, 자리, 보드). 일꾼이 있으면 생각 지연 동안 미리 고른다. {@code paced} 면 틱마다
	 * 일꾼이 끝나기를 기다린다(실제 게임처럼 일꾼에게 시간이 넉넉한 경우). 아니면 틱을 최대한 빨리 돌려 일꾼이 늦는 경우를 만든다.
	 */
	private static List<String> playMatch(long seed, ExecutorService poolA, ExecutorService poolB, boolean paced,
			int[] prefetched) {
		TimedMatch m = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, 8000);
		AiController a = new AiController(m.player(0), () -> Opponent.of(m.player(1).game()),
				new Brain(new Slow(), 0, new BeamSearch(4), Chooser.rng(seed, 1)), 100, 25);
		AiController b = new AiController(m.player(1), () -> Opponent.of(m.player(0).game()),
				new Brain(new Slow(), 0, null, Chooser.rng(seed, 2)), 40, 30);
		a.prefetchWith(poolA);
		b.prefetchWith(poolB);
		List<String> log = new ArrayList<>();
		while (!m.isOver() && m.time() < 20_000) {
			Input in1 = a.poll();
			Input in2 = b.poll();
			while (paced && !(a.prefetchSettled() && b.prefetchSettled())) {
				Thread.onSpinWait();
			}
			m.tick(in1, in2);
			for (int i = 0; i < 2; i++) {
				if (m.lastLock(i) != null) {
					log.add(m.time() + " P" + (i + 1) + "\n" + m.player(i).game().grid().toText());
				}
			}
		}
		log.add("결과 " + m.result() + " " + m.time() + ", 재결정 " + a.replans() + "/" + b.replans());
		prefetched[0] = a.prefetched();
		prefetched[1] = b.prefetched();
		return log;
	}

	@Test
	void prefetchingDuringTheThinkDelayPlaysExactlyTheSameMatch() {
		ExecutorService poolA = Executors.newSingleThreadExecutor();
		ExecutorService poolB = Executors.newSingleThreadExecutor();
		try {
			int[] none = new int[2];
			int[] used = new int[2];
			int[] rushed = new int[2];
			List<String> plain = playMatch(31, null, null, false, none);
			List<String> fetched = playMatch(31, poolA, poolB, true, used);
			List<String> hurried = playMatch(31, poolA, poolB, false, rushed);
			assertTrue(plain.size() > 40, "블록이 충분히 놓여야 한다: " + plain.size());
			assertEquals(plain, fetched, "미리 고르기는 판을 바꾸지 않는다");
			assertEquals(plain, hurried, "일꾼이 늦어(기다리거나 버리고 그 자리에서 골라도) 판은 같다");
			assertEquals(0, none[0] + none[1]);
			assertTrue(used[0] > 0 && used[1] > 0, "미리 고른 답을 실제로 썼다: " + used[0] + ", " + used[1]);
		} finally {
			poolA.shutdownNow();
			poolB.shutdownNow();
		}
	}

	/**
	 * 저장소의 학습 모델로 본다: 게임처럼 모델 하나에 연산 스레드 4 개를 쓰고 생각 지연 동안 미리 고른 판이, 학습 대전처럼
	 * 스레드 하나로 그 자리에서 고른 판과 블록 하나하나까지 같다. 불가능(2수 탐색) 대 최상급 12초. models/ 가 없으면 건너뛴다.
	 */
	@Test
	void realModelsPlayTheSameMatchWithGameThreadsAndPrefetch() {
		Path models = Path.of("models");
		assumeTrue(OnnxScorer.isAvailable() && Files.isRegularFile(models.resolve(AiCatalog.MANIFEST)),
				"models/ 나 ONNX Runtime 이 없어 건너뜀");
		ExecutorService poolA = Executors.newSingleThreadExecutor();
		ExecutorService poolB = Executors.newSingleThreadExecutor();
		try (AiCatalog one = AiCatalog.load(models, 1); AiCatalog four = AiCatalog.load(models, 4)) {
			assumeTrue(one.problem() == null, "models/manifest.json 을 읽지 못함: " + one.problem());
			int[] none = new int[2];
			int[] used = new int[2];
			List<String> plain = realMatch(one, 61, null, null, none);
			List<String> fetched = realMatch(four, 61, poolA, poolB, used);
			assertTrue(plain.size() > 20, "블록이 충분히 놓여야 한다: " + plain.size());
			assertEquals(plain, fetched, "연산 스레드 수와 미리 고르기는 판을 바꾸지 않는다");
			assertTrue(used[0] > 0 && used[1] > 0, "미리 고른 답을 실제로 썼다: " + used[0] + ", " + used[1]);
		} finally {
			poolA.shutdownNow();
			poolB.shutdownNow();
		}
	}

	/** 카탈로그의 불가능(P1) 대 최상급(P2) 12초를 고정마다 적는다. 일꾼이 있으면 틱마다 일꾼이 끝나기를 기다린다. */
	private static List<String> realMatch(AiCatalog catalog, long seed, ExecutorService poolA, ExecutorService poolB,
			int[] prefetched) {
		AiCatalog.Difficulty top = catalog.difficulties().get(4);
		AiCatalog.Difficulty high = catalog.difficulties().get(3);
		AiCatalog.Persona neutral = catalog.personas().get(0);
		TimedMatch m = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, Math.max(top.gravityCapG(), high.gravityCapG()));
		AiController a = new AiController(m.player(0), () -> Opponent.of(m.player(1).game()),
				catalog.choose(top, neutral, seed, 1).brain(), top.thinkMs(), top.inputsPerSec());
		AiController b = new AiController(m.player(1), () -> Opponent.of(m.player(0).game()),
				catalog.choose(high, neutral, seed, 2).brain(), high.thinkMs(), high.inputsPerSec());
		assertInstanceOf(OnnxScorer.class, a.brain().scorer());
		a.prefetchWith(poolA);
		b.prefetchWith(poolB);
		List<String> log = new ArrayList<>();
		while (!m.isOver() && m.time() < 12_000) {
			Input in1 = a.poll();
			Input in2 = b.poll();
			while (poolA != null && !(a.prefetchSettled() && b.prefetchSettled())) {
				Thread.onSpinWait();
			}
			m.tick(in1, in2);
			for (int i = 0; i < 2; i++) {
				if (m.lastLock(i) != null) {
					log.add(m.time() + " P" + (i + 1) + "\n" + m.player(i).game().grid().toText());
				}
			}
		}
		log.add("결과 " + m.result() + " " + m.time() + ", 재결정 " + a.replans() + "/" + b.replans());
		prefetched[0] = a.prefetched();
		prefetched[1] = b.prefetched();
		return log;
	}

	@Test
	void firstInputTurnsPathsIntoInputs() {
		assertEquals(Input.HD, AiController.firstInput(List.of()));
		assertEquals(Input.HD, AiController.firstInput(List.of(Op.D, Op.D)), "내리기뿐이면 하드드롭");
		assertEquals(Input.D, AiController.firstInput(List.of(Op.D, Op.CW)), "끼워 넣기 전에 내려가야 하면 한 칸씩");
		assertEquals(Input.CCW, AiController.firstInput(List.of(Op.CCW, Op.D)));
	}
}
