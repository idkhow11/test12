package kr.ac.jbnu.se.tetris.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 가상 시간 판의 기록 {@code timed-1} 과 사람 판 파일 쓰기 (TRAINING.md 10.9). 파일은 임시 파일에 다 쓴 뒤 이름을 바꿔
 * 끼우므로 반쯤 쓴 기록이 남지 않고, 쓰는 스레드가 끼어들기(interrupt) 상태여도 끝까지 쓴다.
 */
class TimedRecorderTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	/** 두 사람이 200ms 마다 하드드롭만 하는 판의 기록 (곧 끝난다). */
	private static ObjectNode playedRecord(long seed) {
		TimedMatch m = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, 2000);
		TimedRecorder rec = new TimedRecorder("human", seed, TimedRecorder.player("human", "human", 0),
				TimedRecorder.player("level1", "시험", 0.2));
		while (!m.isOver() && m.time() < 600_000) {
			Input in = m.time() % 200 == 0 ? Input.HD : null;
			m.tick(in, m.time() % 300 == 0 ? Input.HD : null);
			rec.afterTick(m);
		}
		return rec.finish(m);
	}

	/** 글로 썼다가 다시 읽은 모양 (숫자 노드의 종류까지 같게 비교하려고). */
	private static JsonNode parsed(ObjectNode rec) throws IOException {
		return JSON.readTree(JSON.writeValueAsString(rec));
	}

	private static JsonNode readSingle(Path file) throws IOException {
		try (BufferedReader r = new BufferedReader(
				new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
			JsonNode first = JSON.readTree(r.readLine());
			assertEquals(null, r.readLine(), "한 줄짜리 파일");
			return first;
		}
	}

	private static List<String> names(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	@Test
	void writesOneCompleteLineAndLeavesNoTemporaryFile(@TempDir Path dir) throws Exception {
		ObjectNode rec = playedRecord(3);
		Path file = dir.resolve("games").resolve("human-20261007-120000-3.jsonl.gz"); // 폴더가 없으면 만든다
		TimedRecorder.writeSingle(rec, file);
		JsonNode back = readSingle(file);
		assertEquals(parsed(rec), back);
		assertEquals(TimedRecorder.FORMAT, back.get("format").asText());
		assertTrue(back.get("steps").size() > 5);
		assertEquals(List.of("human-20261007-120000-3.jsonl.gz"), names(file.getParent()), "임시 파일이 남지 않는다");
	}

	@Test
	void anInterruptedWriterStillWritesTheWholeRecordAndKeepsTheInterrupt(@TempDir Path dir) throws Exception {
		ObjectNode rec = playedRecord(4);
		Path file = dir.resolve("human-4.jsonl.gz");
		Thread.currentThread().interrupt(); // 판을 떠나며 누가 진행 스레드에 끼어든 것처럼
		try {
			TimedRecorder.writeSingle(rec, file);
			assertTrue(Thread.currentThread().isInterrupted(), "끼어들기 상태는 되돌려 둔다");
		} finally {
			Thread.interrupted(); // 다음 시험을 위해 지운다
		}
		assertEquals(parsed(rec), readSingle(file), "끼어들기에 끊기지 않고 다 썼다");
		assertEquals(List.of("human-4.jsonl.gz"), names(dir));
	}

	@Test
	void aFailedWriteLeavesNeitherAPartialRecordNorATemporaryFile(@TempDir Path dir) throws Exception {
		ObjectNode rec = playedRecord(5);
		Path blocked = dir.resolve("human-5.jsonl.gz");
		Files.createDirectories(blocked.resolve("inside")); // 같은 이름의 폴더(비어 있지 않음): 바꿔 끼울 수 없다
		assertThrows(IOException.class, () -> TimedRecorder.writeSingle(rec, blocked));
		assertTrue(Files.isDirectory(blocked), "있던 것은 그대로");
		assertEquals(List.of("human-5.jsonl.gz"), names(dir), "임시 파일을 지웠다");
		assertFalse(Thread.currentThread().isInterrupted());
	}

	@Test
	void anExistingFileIsReplacedWhole(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("human-6.jsonl.gz");
		Files.writeString(file, "반쯤 쓴 옛 파일");
		ObjectNode rec = playedRecord(6);
		TimedRecorder.writeSingle(rec, file);
		assertEquals(parsed(rec), readSingle(file));
	}

	@Test
	void theTemporaryNameIsNotARecordName() {
		assertTrue(TimedRecorder.PART.endsWith(".part"));
		assertFalse(("." + "human-1.jsonl.gz" + ".123" + TimedRecorder.PART).endsWith(".jsonl.gz"),
				"읽는 쪽의 *.jsonl.gz 에 걸리지 않는다");
	}
}
