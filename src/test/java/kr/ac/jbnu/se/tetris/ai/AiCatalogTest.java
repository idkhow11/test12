package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.ac.jbnu.se.tetris.core.GameModel;

/** 게임의 난이도·페르소나 목록과 모델 불러오기 (docs/GAME.md 6.3). */
class AiCatalogTest {

	private static final String MANIFEST = """
			{"format": "models-1",
			 "difficulties": [
			  {"id": 1, "name": "쉬움", "model": "baseline", "temperature": 0.2, "beam": 0,
			   "think_ms": 900, "inputs_per_sec": 5, "gravity_cap": 2},
			  {"id": 2, "name": "어려움", "model": "tiny.onnx", "temperature": 0, "beam": 4,
			   "think_ms": 100, "inputs_per_sec": 25, "gravity_cap": 12.5, "personas": true}],
			 "personas": [{"id": "neutral", "name": "중립", "model": null},
			              {"id": "ghost", "name": "없는 파일", "model": "missing.onnx", "description": "설명"}],
			 "winprob": null}
			""";

	@Test
	void missingManifestGivesBaselineDefaults(@TempDir Path dir) {
		try (AiCatalog c = AiCatalog.load(dir)) {
			assertNotNull(c.problem());
			assertEquals(5, c.difficulties().size());
			assertEquals(AiCatalog.NEUTRAL, c.personas().get(0).id());
			assertNull(c.winprob());
			AiCatalog.Choice choice = c.choose(c.difficulties().get(4), c.personas().get(0), 1, 2);
			assertInstanceOf(BaselineScorer.class, choice.brain().scorer());
			assertNull(choice.fallback(), "처음부터 기준 AI 라 대신한 것이 아니다");
		}
	}

	@Test
	void readsManifestAndFallsBackWhenAModelIsMissing(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("manifest.json"), MANIFEST, StandardCharsets.UTF_8);
		try (AiCatalog c = AiCatalog.load(dir)) {
			assertNull(c.problem());
			AiCatalog.Difficulty hard = c.difficulties().get(1);
			assertEquals("어려움", hard.name());
			assertEquals(4, hard.beam());
			assertEquals(12500, hard.gravityCapG());
			assertEquals("설명", c.personas().get(1).description());
			assertEquals("", c.personas().get(0).description(), "설명이 없으면 빈 문자열");
			assertTrue(hard.personas());
			AiCatalog.Difficulty easy = c.difficulties().get(0);
			assertFalse(easy.personas(), "적지 않으면 페르소나를 고를 수 없다");
			AiCatalog.Choice locked = c.choose(easy, c.personas().get(1), 1, 2);
			assertNull(locked.fallback(), "잠긴 단계에서는 페르소나 대신 그 단계의 두뇌(여기서는 기준 AI)를 쓴다");
			assertEquals("쉬움 · 중립 (기준 AI)", locked.label());
			AiCatalog.Choice ghost = c.choose(hard, c.personas().get(1), 1, 2);
			assertInstanceOf(BaselineScorer.class, ghost.brain().scorer());
			assertNotNull(ghost.fallback());
			assertTrue(ghost.label().contains("기준 AI"));
			// 두뇌는 그래도 둘 수 있다.
			GameModel g = new GameModel(1, 2, GameModel.Scheduler.NONE);
			assertNotNull(ghost.brain().decide(g, g.placements(), Opponent.NONE).placement());
		}
	}

	private static Path fixture(String folder, String file) {
		return Path.of(System.getProperty("fixtures.dir", "fixtures"), folder, file);
	}

	@Test
	void preloadFindsUnusableModelsOnceAndRemembersWhy(@TempDir Path dir) throws Exception {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Files.writeString(dir.resolve("manifest.json"), MANIFEST.replace("\"winprob\": null", "\"winprob\": \"wp.onnx\""),
				StandardCharsets.UTF_8);
		Files.copy(fixture("model_io", "value_tiny.onnx"), dir.resolve("tiny.onnx"));
		Files.copy(fixture("winprob", "winprob_tiny.onnx"), dir.resolve("wp.onnx"));
		try (AiCatalog c = AiCatalog.load(dir, 2)) {
			AiCatalog.Difficulty easy = c.difficulties().get(0);
			AiCatalog.Difficulty hard = c.difficulties().get(1);
			AiCatalog.Persona neutral = c.personas().get(0);
			AiCatalog.Persona ghost = c.personas().get(1);
			assertTrue(c.preload(), "없는 페르소나 모델이 하나 있다");
			assertNull(c.modelProblem(hard, neutral));
			assertNull(c.modelProblem(easy, ghost), "잠긴 단계는 페르소나 모델을 쓰지 않는다");
			String why = c.modelProblem(hard, ghost);
			assertNotNull(why);
			assertTrue(why.contains("missing.onnx"), why);
			assertNotNull(c.winRate(), "승률 예측망도 미리 불러 둔다");
			assertNull(c.winRateProblem());
			assertTrue(c.winRate() == c.winRate(), "판마다 같은 것을 함께 쓴다");
			AiCatalog.Choice fallback = c.choose(hard, ghost, 1, 2);
			assertNotNull(fallback.fallback());
			assertTrue(fallback.fallback().contains("missing.onnx"), "불러 보지 않고 미리 안 이유를 쓴다: " + fallback.fallback());
			assertInstanceOf(OnnxScorer.class, c.choose(hard, neutral, 1, 2).brain().scorer(),
					"연산 스레드 2 개로도 같은 모델을 불러온다");
			assertTrue(c.choose(hard, neutral, 1, 1).brain().scorer() == c.choose(hard, neutral, 2, 2).brain().scorer(),
					"같은 모델 파일은 한 번만 불러 함께 쓴다");
		}
	}

	@Test
	void anUnusableWinRateModelIsReportedInsteadOfThrown(@TempDir Path dir) throws Exception {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Files.writeString(dir.resolve("manifest.json"), MANIFEST.replace("\"winprob\": null", "\"winprob\": \"wp.onnx\""),
				StandardCharsets.UTF_8);
		Files.copy(fixture("winprob", "winprob_linear_wrong_rules.onnx"), dir.resolve("wp.onnx"));
		try (AiCatalog c = AiCatalog.load(dir)) {
			assertNull(c.winRateProblem(), "아직 불러 보지 않았다");
			assertNull(c.winRate(), "쓸 수 없으면 승률 막대를 숨긴다");
			assertNotNull(c.winRateProblem());
			assertTrue(c.winRateProblem().contains("tetris.rules"), c.winRateProblem());
		}
	}

	@Test
	void withoutAWinRateModelThereIsNoProblemToReport(@TempDir Path dir) {
		try (AiCatalog c = AiCatalog.load(dir)) {
			assertNull(c.winRate());
			assertNull(c.winRateProblem(), "묶음에 없을 뿐 고장이 아니다");
			assertFalse(c.preload(), "기본값은 모두 기준 AI 라 쓸 수 없는 모델이 없다");
		}
	}

	// ------------------------------------------------------------------ AI 속도 (사용자 설정, 50~150%)

	/** 기본값과 모델 묶음(models/manifest.json)의 모든 단계. */
	private static java.util.List<AiCatalog.Difficulty> allLevels() {
		java.util.List<AiCatalog.Difficulty> out = new java.util.ArrayList<>(
				AiCatalog.load(Path.of("no-models-here")).difficulties());
		out.addAll(AiCatalog.load(Path.of("models")).difficulties());
		return out;
	}

	/** 블록 하나에 드는 시간 (ms): 생각 지연 + 6000 / 초당 입력. */
	private static double pieceMs(AiCatalog.Difficulty d) {
		return d.thinkMs() + AiCatalog.Difficulty.INPUTS_PER_PIECE * 1000.0 / d.inputsPerSec();
	}

	@Test
	void aiSpeedHundredPercentIsExactlyTheLevelItself() {
		for (AiCatalog.Difficulty d : allLevels()) {
			assertSame(d, d.atSpeed(100), d.name());
		}
		AiCatalog.Difficulty top = AiCatalog.load(Path.of("no-models-here")).difficulties().get(4);
		assertEquals(225, top.atSpeed(100).thinkMs(), "불가능은 그대로 225ms, 초당 25");
		assertEquals(25, top.atSpeed(100).inputsPerSec());
	}

	@Test
	void slowerAiThinksLongerButNeverInputsSlower() {
		AiCatalog.Difficulty easy = AiCatalog.load(Path.of("no-models-here")).difficulties().get(0); // 2200ms, 초당 5
		AiCatalog.Difficulty slow = easy.atSpeed(50);
		assertEquals(5600, slow.thinkMs(), "T = 2200 + 1200 = 3400 → 6800, 생각 지연 6800 - 1200");
		assertEquals(5, slow.inputsPerSec(), "느림은 생각 지연으로 만든다 (PLAN 7장)");
		AiCatalog.Difficulty top = AiCatalog.load(Path.of("no-models-here")).difficulties().get(4); // 225ms, 초당 25
		AiCatalog.Difficulty fast = top.atSpeed(150);
		assertEquals(70, fast.thinkMs(), "T = 225 + 240 = 465 → 310, 생각 지연 310 - 240");
		assertEquals(25, fast.inputsPerSec());
		assertEquals(easy.model(), slow.model(), "결정 품질과 중력 상한은 그대로");
		assertEquals(easy.temperature(), slow.temperature());
		assertEquals(easy.gravityCapG(), slow.gravityCapG());
		assertEquals(top.beam(), fast.beam());
		assertEquals(top.personas(), fast.personas());
		assertEquals(top.name(), fast.name());
	}

	@Test
	void whenThinkingWouldDropBelowFiftyMillisTheInputsGetFaster() {
		AiCatalog.Difficulty quick = new AiCatalog.Difficulty(9, "시험", AiCatalog.BASELINE, 0, 0, 100, 25, 12000, "", false);
		AiCatalog.Difficulty d = quick.atSpeed(130); // T = 340 → 261.5, 생각 지연 21.5 < 50
		assertEquals(AiCatalog.Difficulty.MIN_THINK_MS, d.thinkMs());
		assertEquals(6000 / (340 * 100 / 130.0 - 50), d.inputsPerSec(), 1e-9, "남은 시간 안에 입력 6번");
		assertEquals(340 * 100 / 130.0, pieceMs(d), 1e-9, "블록 하나의 시간은 정확히 100/130 배");
		AiCatalog.Difficulty capped = new AiCatalog.Difficulty(9, "시험", AiCatalog.BASELINE, 0, 0, 60, 25, 12000, "", false)
				.atSpeed(150); // T = 300 → 200: 초당 40 이 필요하지만 30 까지
		assertEquals(AiCatalog.Difficulty.MIN_THINK_MS, capped.thinkMs());
		assertEquals(AiCatalog.Difficulty.MAX_INPUTS_PER_SEC, capped.inputsPerSec());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> quick.atSpeed(0));
	}

	@Test
	void aiSpeedIsMonotonicAndStaysInsideTheLimits() {
		for (AiCatalog.Difficulty d : allLevels()) {
			AiCatalog.Difficulty prev = null;
			for (int percent = 50; percent <= 150; percent += 10) {
				AiCatalog.Difficulty s = d.atSpeed(percent);
				String at = d.name() + " " + percent + "%";
				assertTrue(s.thinkMs() >= AiCatalog.Difficulty.MIN_THINK_MS, at + " 생각 지연 " + s.thinkMs());
				assertTrue(s.inputsPerSec() >= 5 && s.inputsPerSec() <= AiCatalog.Difficulty.MAX_INPUTS_PER_SEC,
						at + " 초당 입력 " + s.inputsPerSec());
				assertEquals(pieceMs(d) * 100 / percent, pieceMs(s), 0.5, at + " 블록 하나의 시간 (생각 지연은 ms 로 반올림)");
				if (prev != null) {
					assertTrue(s.thinkMs() <= prev.thinkMs(), at + " 빠를수록 생각 지연은 짧거나 같다");
					assertTrue(s.inputsPerSec() >= prev.inputsPerSec(), at + " 빠를수록 초당 입력은 많거나 같다");
					assertTrue(pieceMs(s) < pieceMs(prev), at + " 빠를수록 블록 하나의 시간이 짧다");
				}
				prev = s;
			}
		}
	}

	@Test
	void gameThreadCountIsBetweenOneAndFour() {
		assertTrue(AiCatalog.GAME_THREADS >= 1 && AiCatalog.GAME_THREADS <= 4, "" + AiCatalog.GAME_THREADS);
	}

	// ------------------------------------------------------------------ 기다리지 않고 고르기 (화면 스레드)

	@Test
	void chooseIfReadyNeverWaitsForALoadingModel(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("manifest.json"), MANIFEST.replace("\"winprob\": null", "\"winprob\": \"wp.onnx\""),
				StandardCharsets.UTF_8);
		try (AiCatalog c = AiCatalog.load(dir)) {
			AiCatalog.Difficulty easy = c.difficulties().get(0);
			AiCatalog.Difficulty hard = c.difficulties().get(1);
			AiCatalog.Persona neutral = c.personas().get(0);
			AiCatalog.Persona ghost = c.personas().get(1);
			// 배경 스레드가 모델을 불러오는 중인 것처럼 잠금을 쥔다
			CountDownLatch held = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			Thread busy = new Thread(() -> {
				synchronized (c) {
					held.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			});
			busy.start();
			held.await();
			try {
				long t0 = System.nanoTime();
				AiCatalog.Choice now = c.chooseIfReady(easy, neutral, 1, 2);
				assertNotNull(now, "기준 AI 단계는 불러올 것이 없다");
				assertEquals("쉬움 · 중립 (기준 AI)", now.label());
				assertNull(c.chooseIfReady(hard, neutral, 1, 2), "아직 불러 보지 않은 모델");
				assertNull(c.chooseIfReady(hard, ghost, 1, 2));
				assertFalse(c.winRateReady(), "승률 예측망도 아직");
				assertTrue(System.nanoTime() - t0 < 500_000_000L, "잠금을 기다리지 않는다");
			} finally {
				release.countDown();
				busy.join();
			}
			AiCatalog.Choice loaded = c.choose(hard, ghost, 1, 2); // 불러 본다 (없는 파일: 기준 AI 로 대신)
			AiCatalog.Choice ready = c.chooseIfReady(hard, ghost, 1, 2);
			assertNotNull(ready, "한 번 불러 본 모델은 기다리지 않고 고른다");
			assertEquals(loaded.label(), ready.label());
			assertEquals(loaded.fallback(), ready.fallback());
			assertInstanceOf(BaselineScorer.class, ready.brain().scorer());
			c.winRate();
			assertTrue(c.winRateReady());
			// 이미 불러 본 것은 다른 스레드가 잠금을 쥐고 있어도 바로 돌아온다
			CountDownLatch again = new CountDownLatch(1);
			CountDownLatch done = new CountDownLatch(1);
			Thread holder = new Thread(() -> {
				synchronized (c) {
					again.countDown();
					try {
						done.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			});
			holder.start();
			again.await();
			try {
				long t0 = System.nanoTime();
				assertNotNull(c.choose(hard, ghost, 2, 2));
				c.winRate();
				assertTrue(System.nanoTime() - t0 < 500_000_000L, "불러 본 모델로 고를 때는 잠그지 않는다");
			} finally {
				done.countDown();
				holder.join();
			}
		}
	}

	@Test
	void chooseIfReadyGivesTheSameBrainAsChoose(@TempDir Path dir) throws Exception {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Files.writeString(dir.resolve("manifest.json"), MANIFEST, StandardCharsets.UTF_8);
		Files.copy(Path.of(System.getProperty("fixtures.dir", "fixtures"), "model_io", "value_tiny.onnx"),
				dir.resolve("tiny.onnx"));
		try (AiCatalog c = AiCatalog.load(dir)) {
			AiCatalog.Difficulty hard = c.difficulties().get(1);
			AiCatalog.Persona neutral = c.personas().get(0);
			assertNull(c.chooseIfReady(hard, neutral, 1, 2));
			AiCatalog.Choice a = c.choose(hard, neutral, 7, 2);
			AiCatalog.Choice b = c.chooseIfReady(hard, neutral, 7, 2);
			assertSame(a.brain().scorer(), b.brain().scorer(), "같은 모델을 함께 쓴다");
			assertEquals(a.label(), b.label());
			assertNull(b.fallback());
			// 같은 시드면 고르기도 같다 (온도 0 이라 난수와 상관없지만, 탐색과 온도를 같이 받는다)
			GameModel g = new GameModel(3, 2, GameModel.Scheduler.NONE);
			assertEquals(a.brain().decide(g, g.placements(), Opponent.NONE).placement().key(),
					b.brain().decide(g, g.placements(), Opponent.NONE).placement().key());
		}
	}

	@Test
	void loadsAnOnnxModelForTheNeutralPersona(@TempDir Path dir) throws Exception {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Files.writeString(dir.resolve("manifest.json"), MANIFEST, StandardCharsets.UTF_8);
		Files.copy(Path.of(System.getProperty("fixtures.dir", "fixtures"), "model_io", "value_tiny.onnx"),
				dir.resolve("tiny.onnx"));
		try (AiCatalog c = AiCatalog.load(dir)) {
			AiCatalog.Choice choice = c.choose(c.difficulties().get(1), c.personas().get(0), 1, 2);
			assertInstanceOf(OnnxScorer.class, choice.brain().scorer());
			assertNull(choice.fallback());
			assertEquals("어려움 · 중립", choice.label());
		}
	}
}
