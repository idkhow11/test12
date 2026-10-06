package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.core.GameModel;

/** 사람 대 AI 판의 기록 (TRAINING.md 10.9): 끝난 판은 timed-1 로 남고, 기록을 끄면 남지 않는다. */
class GameSessionTest {

	private static GameSession versus(AiCatalog catalog) {
		AiCatalog.Difficulty d = catalog.difficulties().get(4); // 빠른 AI 라 판이 금방 끝난다
		return new GameSession(new GameSession.Settings(true, d, catalog.personas().get(0), 0, GameModel.Scheduler.NONE),
				catalog, 12345L, s -> {
				});
	}

	@Test
	void finishedHumanGamesAreSavedAsTimedRecords(@TempDir Path dir) throws Exception {
		String old = System.getProperty("tetris.records");
		System.setProperty("tetris.records", dir.toString());
		try (AiCatalog catalog = AiCatalog.load(dir.resolve("no-models"))) {
			GameSession s = versus(catalog);
			s.saveRecord(); // 아직 끝나지 않은 판은 남기지 않는다
			try (Stream<Path> files = Files.list(dir)) {
				assertEquals(0, files.count());
			}
			while (!s.match.isOver() && s.match.time() < 600_000) {
				s.advance(TestKeys.HARD_DROP_EVERY_200MS.at(s.match.time())); // 사람은 하드드롭만 한다
			}
			assertTrue(s.match.isOver());
			s.saveRecord();
			List<Path> saved;
			try (Stream<Path> files = Files.list(dir)) {
				saved = files.toList();
			}
			assertEquals(1, saved.size());
			try (BufferedReader r = new BufferedReader(new InputStreamReader(
					new GZIPInputStream(Files.newInputStream(saved.get(0))), StandardCharsets.UTF_8))) {
				JsonNode rec = new ObjectMapper().readTree(r.readLine());
				assertEquals("timed-1", rec.get("format").asText());
				assertEquals("human", rec.get("players").get(0).get("ai").asText());
				assertEquals("p2", rec.get("result").asText(), "하드드롭만 하는 사람은 진다");
				assertTrue(rec.get("steps").size() > 10);
				assertEquals(rec.get("steps").size(), rec.get("turns").asInt());
				JsonNode first = rec.get("steps").get(0);
				assertEquals(24, first.get("p1").get("state").get("rows").size());
			}
		} finally {
			if (old == null) {
				System.clearProperty("tetris.records");
			} else {
				System.setProperty("tetris.records", old);
			}
		}
	}

	@Test
	void recordsCanBeTurnedOff() {
		String old = System.getProperty("tetris.records");
		System.setProperty("tetris.records", "off");
		try {
			assertEquals(null, GameSession.recordsDir());
		} finally {
			if (old == null) {
				System.clearProperty("tetris.records");
			} else {
				System.setProperty("tetris.records", old);
			}
		}
	}

	/** 시험용 사람 입력. */
	private enum TestKeys {
		HARD_DROP_EVERY_200MS;

		kr.ac.jbnu.se.tetris.core.TimedPlayer.Input at(long t) {
			return t % 200 == 0 ? kr.ac.jbnu.se.tetris.core.TimedPlayer.Input.HD : null;
		}
	}
}
