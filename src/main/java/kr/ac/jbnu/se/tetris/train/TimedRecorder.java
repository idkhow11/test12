package kr.ac.jbnu.se.tetris.train;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.FeatureEncoder;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Rules;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 가상 시간 모드(RULES 12.3) 한 판의 기록 {@code timed-1} (docs/TRAINING.md 10.9). 블록이 고정될 때마다(어느 쪽이든) 두
 * 플레이어의 상태를 남기고, 판이 끝나면 결과를 적는다. 승률 예측망의 자료이며, 게임이 승률 막대를 다시 재는 순간과 같은
 * 순간의 상태다. 모양은 블록 동기 기록 {@code match-1}(7장)의 {@code steps[].p1.state} 와 같아서 Python 의
 * {@code tetris_ai.winprob.read_records} 가 그대로 읽는다.
 */
public final class TimedRecorder {

	public static final String FORMAT = "timed-1";
	/** 한 판에 남기는 상태의 상한. 넘으면 그 뒤는 남기지 않는다(10분 판도 보통 1,000개를 넘지 않는다). */
	public static final int MAX_STEPS = 4000;
	/** {@link #writeSingle} 이 쓰는 동안의 임시 파일 끝 ({@code .<이름>.<수>.part}). */
	public static final String PART = ".part";
	private static final int MOVE_RETRIES = 4;

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ObjectNode record = JSON.objectNode();
	private final ArrayNode steps;

	/**
	 * @param run 실행 이름 (사람이 둔 판은 {@code "human"})
	 * @param p1  P1 설명 ({@code ai}, {@code model}, {@code temperature} 는 꼭 넣는다)
	 * @param p2  P2 설명
	 */
	public TimedRecorder(String run, long seed, ObjectNode p1, ObjectNode p2) {
		record.put("format", FORMAT);
		record.put("rules", Rules.VERSION);
		record.put("model_io", FeatureEncoder.MODEL_IO_VERSION);
		record.put("run", run);
		record.put("seed", seed);
		ArrayNode players = record.putArray("players");
		players.add(p1);
		players.add(p2);
		steps = JSON.arrayNode();
	}

	/** 선수 설명 하나를 만든다. */
	public static ObjectNode player(String ai, String model, double temperature) {
		ObjectNode p = JSON.objectNode();
		p.put("ai", ai);
		p.put("model", model);
		p.put("temperature", temperature);
		return p;
	}

	/** 틱이 끝난 뒤 부른다. 이번 틱에 누군가 블록을 고정했으면 두 상태를 남긴다. */
	public void afterTick(TimedMatch m) {
		if (m.size() != 2 || steps.size() >= MAX_STEPS) {
			return;
		}
		if (m.lastLock(0) == null && m.lastLock(1) == null) {
			return;
		}
		ObjectNode step = steps.addObject();
		step.put("t", m.time());
		step.putObject("p1").set("state", MatchRunner.state(m.player(0).game()));
		step.putObject("p2").set("state", MatchRunner.state(m.player(1).game()));
	}

	/** 판을 마치고 기록을 돌려준다. 끝나지 않았으면(시간 상한) 무승부로 적는다. */
	public ObjectNode finish(TimedMatch m) {
		String result;
		switch (m.result()) {
		case P1:
			result = "p1";
			break;
		case P2:
			result = "p2";
			break;
		default:
			result = "draw";
			break;
		}
		record.put("result", result);
		record.put("ended", m.isOver());
		record.put("time_ms", m.time());
		ObjectNode topout = record.putObject("topout");
		for (int i = 0; i < 2; i++) {
			GameModel g = m.player(i).game();
			if (g.isOver()) {
				topout.put(i == 0 ? "p1" : "p2", g.topout().json());
			} else {
				topout.putNull(i == 0 ? "p1" : "p2");
			}
		}
		record.put("turns", steps.size());
		record.set("steps", steps);
		return record;
	}

	/**
	 * 기록 하나를 한 줄짜리 gzip 파일로 쓴다(사람이 둔 판). 폴더가 없으면 만든다.
	 * <ul>
	 * <li><b>원자적으로:</b> 같은 폴더의 임시 파일({@code .<이름>.<수>.part}, 읽는 쪽의 {@code *.jsonl.gz} 에 걸리지 않는다)에
	 * 다 쓰고 디스크에 내린 뒤 이름을 바꿔 끼운다. 쓰는 도중에 실패하거나 프로그램이 꺼져도 {@code file} 은 없거나 완전하다.
	 * 실패하면 임시 파일을 지운다.</li>
	 * <li><b>끼어들기에 끊기지 않게:</b> 부른 스레드가 끼어들기(interrupt) 상태여도 끝까지 쓴다. 끼어들기를 받는 파일 채널을
	 * 쓰지 않고, 그 상태는 잠깐 지웠다가 돌아갈 때 되돌린다.</li>
	 * <li>Windows 에서 백신·검색 색인·클라우드 동기화가 파일을 잠깐 잡고 있으면(접근 거부) 조금 기다렸다가 다시 바꿔 끼운다
	 * (모두 합쳐 0.3초 안).</li>
	 * </ul>
	 */
	public static void writeSingle(ObjectNode record, Path file) throws IOException {
		boolean interrupted = Thread.interrupted(); // 쓰는 동안은 지워 둔다 (끝나면 되돌린다)
		try {
			Path dir = file.toAbsolutePath().getParent();
			Files.createDirectories(dir);
			byte[] line = (MAPPER.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8);
			Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", PART);
			try {
				try (FileOutputStream raw = new FileOutputStream(tmp.toFile()); // 끼어들기를 받지 않는 스트림
						GZIPOutputStream gz = new GZIPOutputStream(raw, 1 << 16)) {
					gz.write(line);
					gz.finish();
					raw.getFD().sync(); // 이름을 바꾸기 전에 내용을 디스크에
				}
				moveIntoPlace(tmp, file);
			} finally {
				Files.deleteIfExists(tmp); // 바꿔 끼웠으면 이미 없다
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** 임시 파일을 제 이름으로 바꿔 끼운다. 접근 거부·공유 위반이면 25·50·75·100ms 기다렸다가 다시 해 본다. */
	private static void moveIntoPlace(Path tmp, Path target) throws IOException {
		for (int attempt = 0;; attempt++) {
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (NoSuchFileException e) {
				throw e; // 폴더가 사라짐: 기다려도 소용없다
			} catch (FileSystemException e) { // AccessDeniedException 과 공유 위반
				if (attempt >= MOVE_RETRIES) {
					throw e;
				}
				LockSupport.parkNanos(25_000_000L * (attempt + 1)); // 끼어들기에 깨어도 다시 해 본다
			}
		}
	}
}
