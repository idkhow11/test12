package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
