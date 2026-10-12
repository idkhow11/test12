package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.ac.jbnu.se.tetris.ai.Afterstate;
import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.BeamSearch;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.ai.OnnxScorer;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.ai.PlacementScorer;
import kr.ac.jbnu.se.tetris.ai.PlayerState;
import kr.ac.jbnu.se.tetris.ai.WinRate;
import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.SoundCues;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.train.TimedRecorder;

/**
 * 한 판의 진행 (GAME.md 3, 4장): 사람 대 AI 판의 기록(TRAINING.md 10.9)과 플레이 기록(전적, 혼자 연습 최고 기록, 기권), AI
 * 속도, 화면 효과용 사건, 소리(사람의 틱 소리와 판 끝의 결과 소리), 보정한 승률 막대, 모델을 화면 스레드에서 불러오지 않는 것,
 * 판 도중 모델 오류와 예상하지 못한 오류, 일시정지와 다시 가기 카운트다운(GO! 는 처음에만), 같은 판 다시, 스레드와 미리 고르기가
 * 판을 바꾸지 않는 것과 판이 끝나면 스스로 끝나는 것. 사람 자리는 아무것도 하지 않거나(가만히 두면 블록이 쌓여 언젠가 진다)
 * 시험용 손({@link #pilot})이 둔다.
 */
class GameSessionTest {

	private static final AiCatalog CATALOG = AiCatalog.load(Path.of("no-models-here")); // 기본값: 모두 기준 AI
	private static String recordsBefore;

	/** 사람 판 기록(runs/human)을 시험이 남기지 않게: 필요한 시험만 임시 폴더를 준다({@link #withRecords}). */
	@BeforeAll
	static void recordsOff() {
		recordsBefore = System.getProperty("tetris.records");
		System.setProperty("tetris.records", "off");
	}

	@AfterAll
	static void restoreRecords() {
		if (recordsBefore == null) {
			System.clearProperty("tetris.records");
		} else {
			System.setProperty("tetris.records", recordsBefore);
		}
	}

	private static AiCatalog.Difficulty level(int id) {
		return CATALOG.difficulties().get(id - 1);
	}

	private static AiCatalog.Persona neutral() {
		return CATALOG.personas().get(0);
	}

	private static GameSession versus(AiCatalog catalog) {
		AiCatalog.Difficulty d = catalog.difficulties().get(4); // 빠른 AI 라 판이 금방 끝난다
		return new GameSession(GameSession.Settings.versus(d, catalog.personas().get(0)), catalog, 12345L, s -> {
		});
	}

	/** 사람 자리를 대신 두는 시험용 손: 기준 AI 두뇌, 생각 200ms, 초당 10번. 잘 버틴다. */
	private static AiController pilot(GameSession s, long seed) {
		return new AiController(s.match.player(0),
				() -> s.match.size() == 2 ? Opponent.of(s.match.player(1).game()) : Opponent.NONE,
				new Brain(new BaselineScorer(), 0, null, Chooser.rng(seed, 1)), 200, 10);
	}

	/** 시험용 손으로 판 시각 {@code untilMs} 까지(또는 끝날 때까지) 직접 진행한다. */
	private static void play(GameSession s, AiController p1, long untilMs) {
		while (!s.over() && s.match.time() < untilMs) {
			s.advance(p1.poll());
		}
	}

	private static long ms(long v) {
		return v * 1_000_000L;
	}

	/** 조건이 될 때까지 기다린다(진행 스레드를 쓰는 시험). */
	private static void await(BooleanSupplier done, String what) throws InterruptedException {
		long until = System.nanoTime() + 20_000_000_000L;
		while (!done.getAsBoolean()) {
			assertTrue(System.nanoTime() < until, "20초 안에 " + what);
			Thread.sleep(2);
		}
	}

	private static long time(GameSession s) {
		synchronized (s.lock) {
			return s.match.time();
		}
	}

	private static void withRecords(Path dir, ThrowingRunnable body) throws Exception {
		String old = System.getProperty("tetris.records");
		System.setProperty("tetris.records", dir.toString());
		try {
			body.run();
		} finally {
			if (old == null) {
				System.clearProperty("tetris.records");
			} else {
				System.setProperty("tetris.records", old);
			}
		}
	}

	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	/** 기준 AI 두뇌로 자리마다의 선택을 만든다(카탈로그 없이 시험용 생성자에 넘긴다). */
	private static AiCatalog.Choice[] choices(GameSession.Settings st, long seed) {
		AiCatalog.Choice[] out = new AiCatalog.Choice[st.players()];
		for (int i = 0; i < out.length; i++) {
			GameSession.Seat seat = st.ai(i);
			if (seat != null) {
				AiCatalog.Difficulty d = seat.difficulty();
				out[i] = new AiCatalog.Choice(new Brain(new BaselineScorer(), d.temperature(), null, Chooser.rng(seed, i + 1)),
						d.name(), null);
			}
		}
		return out;
	}

	/** 카탈로그(기본값, 기준 AI)로 만든 자리마다의 두뇌. 불가능 단계는 2수 탐색을 쓴다. */
	private static AiCatalog.Choice[] catalogChoices(GameSession.Settings st, long seed) {
		AiCatalog.Choice[] out = new AiCatalog.Choice[st.players()];
		for (int i = 0; i < out.length; i++) {
			GameSession.Seat seat = st.ai(i);
			if (seat != null) {
				out[i] = CATALOG.choose(seat.difficulty(), seat.persona(), seed, i + 1);
			}
		}
		return out;
	}

	private static JsonNode readSingleRecord(Path dir) throws Exception {
		List<Path> saved;
		try (Stream<Path> files = Files.list(dir)) {
			saved = files.toList();
		}
		assertEquals(1, saved.size());
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(Files.newInputStream(saved.get(0))), StandardCharsets.UTF_8))) {
			return new ObjectMapper().readTree(r.readLine());
		}
	}

	/** 판이 내는 소리를 적는다 (진짜 소리 엔진 대신). */
	private static List<Cue> listen(GameSession s) {
		List<Cue> heard = new CopyOnWriteArrayList<>(); // 진행 스레드도 적는다
		s.soundsTo(heard::addAll);
		return heard;
	}

	private static long count(List<Cue> cues, Sfx sfx) {
		return cues.stream().filter(c -> c.sfx() == sfx).count();
	}

	/** 판 끝 소리 (K.O., 승리·패배 음악, 새 기록)만. */
	private static List<Sfx> results(List<Cue> cues) {
		List<Sfx> out = new ArrayList<>();
		for (Cue c : cues) {
			if (c.sfx() == Sfx.KO || c.sfx() == Sfx.WIN || c.sfx() == Sfx.LOSE || c.sfx() == Sfx.NEW_RECORD) {
				out.add(c.sfx());
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ 사람 대 AI 의 판 기록 (승률 자료)

	@Test
	void finishedHumanGamesAreSavedAsTimedRecords(@TempDir Path dir) throws Exception {
		withRecords(dir, () -> {
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
				s.saveRecord(); // 한 판에 한 번만 남긴다
				s.finishIfOver(); // 판 끝의 처리도 같은 기록을 다시 쓰지 않는다
				JsonNode rec = readSingleRecord(dir);
				assertEquals("timed-1", rec.get("format").asText());
				assertEquals("human", rec.get("players").get(0).get("ai").asText());
				assertEquals("p2", rec.get("result").asText(), "하드드롭만 하는 사람은 진다");
				assertTrue(rec.get("steps").size() > 10);
				assertEquals(rec.get("steps").size(), rec.get("turns").asInt());
				JsonNode first = rec.get("steps").get(0);
				assertEquals(24, first.get("p1").get("state").get("rows").size());
				JsonNode ai = rec.get("players").get(1);
				assertEquals(100, ai.get("ai_speed_pct").asInt(), "AI 속도");
				assertEquals(225, ai.get("think_ms").asInt(), "그 속도의 생각 지연 (불가능, 100%)");
				assertEquals(25.0, ai.get("inputs_per_sec").asDouble());
			}
		});
	}

	@Test
	void theRecordKeepsTheAiSpeedAndItsEffectiveTiming(@TempDir Path dir) throws Exception {
		withRecords(dir, () -> {
			GameSession.Settings st = GameSession.Settings.versus(level(2), neutral(), 50);
			GameSession s = new GameSession(st, CATALOG, 77L, x -> {
			});
			while (!s.match.isOver() && s.match.time() < 600_000) {
				s.advance(TestKeys.HARD_DROP_EVERY_200MS.at(s.match.time()));
			}
			s.saveRecord();
			JsonNode ai = readSingleRecord(dir).get("players").get(1);
			assertEquals(50, ai.get("ai_speed_pct").asInt());
			assertEquals(level(2).atSpeed(50).thinkMs(), ai.get("think_ms").asInt(), "1150 + 1000 = 2150 → 4300, 생각 3300");
			assertEquals(3300, ai.get("think_ms").asInt());
			assertEquals(6.0, ai.get("inputs_per_sec").asDouble());
		});
	}

	@Test
	void recordsCanBeTurnedOff() throws Exception {
		withRecords(Path.of("off"), () -> assertEquals(null, GameSession.recordsDir()));
	}

	@Test
	void theFirstRecordOnAFreshCloneCreatesTheRecordFolder(@TempDir Path clone) throws Exception {
		// 새로 받은 저장소에는 runs/ 가 없다: 게임의 기본 폴더(TetrisApp.HUMAN_RECORDS)를 임시 폴더 아래에 두고 첫 기록이 폴더째 남는지
		Path games = clone.resolve(TetrisApp.HUMAN_RECORDS);
		assertFalse(Files.exists(clone.resolve("runs")));
		withRecords(games, () -> {
			GameSession s = versus(CATALOG);
			while (!s.match.isOver() && s.match.time() < 600_000) {
				s.advance(TestKeys.HARD_DROP_EVERY_200MS.at(s.match.time()));
			}
			assertTrue(s.match.isOver());
			s.saveRecord();
			assertEquals("human", readSingleRecord(games).get("players").get(0).get("ai").asText());
		});
	}

	@Test
	void humanRecordsAreOffUnlessAFolderIsGiven() {
		// IDE 에서 바로 돌린 시험·도구(Gradle 의 -Dtetris.records=off 가 없다)가 봇 판을 사람 판 자료에 남기지 않게: 게임 실행
		// (TetrisApp.main)만 폴더를 정해 켠다. 판은 돌리지 않는다(잘못돼도 진짜 폴더에 쓰지 않게).
		String old = System.getProperty("tetris.records");
		try {
			System.clearProperty("tetris.records");
			assertNull(GameSession.recordsDir(), "속성이 없으면 남기지 않는다");
			System.setProperty("tetris.records", " ");
			assertNull(GameSession.recordsDir(), "비어 있어도");
			System.setProperty("tetris.records", " OFF ");
			assertNull(GameSession.recordsDir());
			System.setProperty("tetris.records", " build/records-here ");
			assertEquals(Path.of("build/records-here"), GameSession.recordsDir(), "폴더를 주면 그곳");
		} finally {
			if (old == null) {
				System.clearProperty("tetris.records");
			} else {
				System.setProperty("tetris.records", old);
			}
		}
	}

	@Test
	void leavingRightWhenTheGameEndsStillLeavesTheWholeRecord(@TempDir Path dir) throws Exception {
		// 판이 끝나자마자 떠나도(결과 카드에서 바로 메뉴로, 창 닫기) 진행 스레드가 쓰던 기록은 끊기지 않고 다 쓰인다
		withRecords(dir, () -> {
			for (long seed = 61; seed <= 62; seed++) {
				GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
				AtomicLong now = new AtomicLong();
				GameSession s = new GameSession(st, seed, x -> {
				}, choices(st, seed), null, now::get);
				s.start();
				now.set(ms(GameSession.COUNTDOWN_MS + 900_000)); // 사람은 가만히 있으므로 곧 진다
				await(s::over, "판이 끝난다");
				s.forfeit(); // 창이 판을 떠날 때 부르는 것 (진행 스레드를 끼어들기로 깨우지 않는다)
				assertFalse(s.forfeited(), "이미 끝난 판은 기권이 아니다");
			}
			List<String> names;
			try (Stream<Path> files = Files.list(dir)) {
				names = files.map(p -> p.getFileName().toString()).sorted().toList();
			}
			assertEquals(2, names.size(), "두 판 모두 남았다: " + names);
			for (String name : names) {
				assertTrue(name.startsWith("human-") && name.endsWith(".jsonl.gz"), name);
				assertFalse(name.endsWith(TimedRecorder.PART), "쓰다 만 임시 파일이 없다");
				try (BufferedReader r = new BufferedReader(new InputStreamReader(
						new GZIPInputStream(Files.newInputStream(dir.resolve(name))), StandardCharsets.UTF_8))) {
					JsonNode rec = new ObjectMapper().readTree(r.readLine());
					assertEquals("p2", rec.get("result").asText(), "끝까지 읽힌다");
					assertTrue(rec.get("ended").asBoolean());
				}
			}
		});
	}

	@Test
	void aMidGameFallbackIsMarkedInTheHumanRecord(@TempDir Path dir) throws Exception {
		withRecords(dir, () -> {
			GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
			AiCatalog.Choice[] seats = choices(st, 9L);
			seats[1] = new AiCatalog.Choice(new Brain(new BreaksAfter(1, false), 0, null, Chooser.rng(9, 2)), "시험", null);
			GameSession s = new GameSession(st, 9L, x -> {
			}, seats, null, System::nanoTime);
			while (!s.match.isOver() && s.match.time() < 600_000) {
				s.advance(TestKeys.HARD_DROP_EVERY_200MS.at(s.match.time()));
			}
			s.saveRecord();
			JsonNode rec = readSingleRecord(dir);
			assertFalse(rec.get("players").get(0).has("fallback_ms"));
			long at = rec.get("players").get(1).get("fallback_ms").asLong();
			assertTrue(at > 0 && at < rec.get("time_ms").asLong(), "기준 AI 로 바뀐 판 시각: " + at);
		});
	}

	@Test
	void practiceGamesAreNeverSavedAsTimedRecords(@TempDir Path dir) throws Exception {
		withRecords(dir, () -> {
			GameSession s = new GameSession(GameSession.Settings.solo(12_000, GameModel.Scheduler.S_3), CATALOG, 3L, x -> {
			});
			while (!s.over() && s.match.time() < 600_000) {
				s.advance(TestKeys.HARD_DROP_EVERY_200MS.at(s.match.time()));
			}
			assertTrue(s.over());
			s.saveRecord();
			try (Stream<Path> files = Files.list(dir)) {
				assertEquals(0, files.count(), "혼자 연습은 승률 자료가 아니다");
			}
		});
	}

	// ------------------------------------------------------------------ 판 설정

	@Test
	void settingsFactoriesFillTheSeats() {
		GameSession.Settings v = GameSession.Settings.versus(level(2), neutral());
		assertTrue(v.versusAi());
		assertEquals(2, v.players());
		assertNull(v.ai(0));
		assertEquals(level(2), v.ai(1).difficulty());
		assertEquals(100, v.aiSpeed(), "AI 속도를 주지 않으면 100%");
		assertSame(level(2), v.aiDifficulty(), "100% 면 난이도 그대로");
		assertNull(v.seed(), "판마다 새 시드");
		GameSession.Settings solo = GameSession.Settings.solo(3000, GameModel.Scheduler.S_3);
		assertEquals(1, solo.players());
		assertNull(solo.ai(0));
		assertNull(solo.difficulty(), "혼자 연습에는 AI 가 없다");
		assertNull(solo.aiDifficulty());
		assertEquals(GameModel.Scheduler.S_3, solo.scheduler());
		assertEquals(GameModel.Scheduler.NONE, GameSession.Settings.solo(2000, null).scheduler(), "스케줄러를 주지 않으면 없음");
		assertThrows(IllegalArgumentException.class, () -> new GameSession.Settings(GameSession.Mode.VERSUS, null, 0,
				GameModel.Scheduler.NONE, 100, null), "사람 대 AI 에는 AI 자리가 있다");
		assertThrows(IllegalArgumentException.class, () -> GameSession.Settings.versus(level(2), neutral(), 0));
		GameSession.Settings fast = GameSession.Settings.versus(level(5), neutral(), 150);
		assertEquals(70, fast.aiDifficulty().thinkMs(), "불가능 150%: 465ms → 310ms, 생각 70ms");
		assertEquals(150, v.withAiSpeed(150).aiSpeed());
	}

	@Test
	void sameSeedReplaysAndNewSeedIsFresh() {
		GameSession.Settings st = GameSession.Settings.versus(level(3), neutral());
		GameSession first = new GameSession(st, CATALOG, 1234L, x -> {
		});
		assertEquals(1234L, first.seed, "정한 시드가 없으면 준 새 시드");
		GameSession.Settings again = st.withSeed(first.seed);
		assertEquals(Long.valueOf(1234L), again.seed());
		GameSession replay = new GameSession(again, CATALOG, 999L, x -> {
		});
		assertEquals(1234L, replay.seed, "같은 판 다시: 정한 시드가 이긴다");
		assertEquals(first.match.player(0).game().preview(), replay.match.player(0).game().preview(), "같은 블록 순서");
		GameSession fresh = new GameSession(again.withNewSeed(), CATALOG, 999L, x -> {
		});
		assertEquals(999L, fresh.seed, "다시 하기: 새 시드");
		assertSame(st, st.withNewSeed(), "시드를 정하지 않은 설정은 그대로");
		assertEquals(st, again.withNewSeed());
	}

	@Test
	void sameSeedRetryReplaysTheWholeSetupAndTheSameGame() {
		// "같은 판 다시" 는 지난 판의 시드에 난이도·페르소나·AI 속도를 그대로 쓰고, 같은 손이면 같은 판이 된다
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral(), 70);
		GameSession first = new GameSession(st, CATALOG, 8_888L, x -> {
		});
		GameSession.Settings again = st.withSeed(first.seed);
		assertEquals(st.difficulty(), again.difficulty());
		assertEquals(st.persona(), again.persona());
		assertEquals(70, again.aiSpeed());
		assertEquals(st.aiDifficulty(), again.aiDifficulty(), "AI 의 조작 속도도 같다");
		GameSession replay = new GameSession(again, CATALOG, 1L, x -> {
		});
		assertEquals(first.seed, replay.seed);
		AiController a = pilot(first, 3L);
		AiController b = pilot(replay, 3L);
		while (!first.over() && first.match.time() < 25_000) {
			first.advance(a.poll());
			replay.advance(b.poll());
		}
		for (int i = 0; i < 2; i++) {
			GameModel x = first.match.player(i).game();
			GameModel y = replay.match.player(i).game();
			assertEquals(x.pieces(), y.pieces(), "P" + (i + 1));
			assertTrue(x.grid().sameOccupancy(y.grid()), "P" + (i + 1));
		}
		assertTrue(first.match.player(1).game().pieces() > 10);
		GameSession.Settings fresh = again.withNewSeed();
		assertNull(fresh.seed(), "다시 하기: 판마다 새 시드");
		assertEquals(70, fresh.aiSpeed());
		assertEquals(st, fresh, "시드 말고는 그대로");
		assertEquals(4_242L, new GameSession(fresh, CATALOG, 4_242L, x -> {
		}).seed);
	}

	@Test
	void aSeatWithoutAPersonaPlaysNeutral() {
		GameSession s = new GameSession(GameSession.Settings.versus(level(5), null), CATALOG, 3L, x -> {
		});
		for (int t = 0; t < 3000; t++) {
			s.advance(null);
		}
		assertTrue(s.match.player(1).game().pieces() > 0, "페르소나 없이도 둔다");
	}

	@Test
	void namesAreMeAiAndPractice() {
		GameSession v = versus(CATALOG);
		assertEquals("나", v.name(0));
		assertEquals("AI", v.name(1));
		assertNull(v.seat(0), "사람 대 AI 의 P1 은 사람");
		assertNotNull(v.seat(1));
		GameSession solo = new GameSession(GameSession.Settings.solo(2000, GameModel.Scheduler.NONE), CATALOG, 1L, x -> {
		});
		assertEquals("연습", solo.name(0));
		assertNull(solo.ai(0));
	}

	// ------------------------------------------------------------------ AI 속도

	/** 사람은 가만히 두고 AI 만 판 시각 {@code untilMs} 까지 둔다. AI 가 놓은 블록 수. */
	private static int aiPiecesBy(GameSession.Settings st, long seed, long untilMs) {
		GameSession s = new GameSession(st, seed, x -> {
		}, choices(st, seed), null, System::nanoTime);
		while (!s.over() && s.match.time() < untilMs) {
			s.advance(null);
		}
		return s.match.player(1).game().pieces();
	}

	@Test
	void aiSpeedChangesHowFastTheAiPlays() {
		int slow = aiPiecesBy(GameSession.Settings.versus(level(2), neutral(), 50), 5L, 20_000);
		int normal = aiPiecesBy(GameSession.Settings.versus(level(2), neutral(), 100), 5L, 20_000);
		int fast = aiPiecesBy(GameSession.Settings.versus(level(2), neutral(), 150), 5L, 20_000);
		assertTrue(slow < normal && normal < fast, "20초에 놓은 블록: 50% " + slow + ", 100% " + normal + ", 150% " + fast);
	}

	@Test
	void atHundredPercentTheAiPlaysExactlyAsBefore() {
		// AI 속도가 생기기 전처럼 난이도의 생각 지연·초당 입력을 그대로 넣은 판과 블록 하나하나까지 같다
		AiCatalog.Difficulty d = level(4);
		GameSession.Settings st = GameSession.Settings.versus(d, neutral());
		GameSession s = new GameSession(st, 21L, x -> {
		}, choices(st, 21L), null, System::nanoTime);
		TimedMatch raw = new TimedMatch(21L, 2, GameModel.Scheduler.NONE, d.gravityCapG());
		AiController ai = new AiController(raw.player(1), () -> Opponent.of(raw.player(0).game()),
				choices(st, 21L)[1].brain(), d.thinkMs(), d.inputsPerSec());
		while (!s.over() && s.match.time() < 40_000) {
			s.advance(null);
			raw.tick(null, ai.poll());
		}
		for (int i = 0; i < 2; i++) {
			assertEquals(raw.player(i).game().pieces(), s.match.player(i).game().pieces());
			assertTrue(raw.player(i).game().grid().sameOccupancy(s.match.player(i).game().grid()), "P" + (i + 1));
		}
		assertTrue(s.match.player(1).game().pieces() > 20);
	}

	// ------------------------------------------------------------------ 화면 효과용 사건

	@Test
	void effectEventsDescribeEveryLockClearAndGarbageWithoutChangingTheGame() {
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		GameSession s = new GameSession(st, 31L, x -> {
		}, choices(st, 31L), null, System::nanoTime);
		AiController p1 = pilot(s, 31L);
		// 같은 손과 같은 AI 로 세션 없이 진행한 판 (화면 효과용 사건을 적지 않는다)
		TimedMatch raw = new TimedMatch(31L, 2, GameModel.Scheduler.NONE, level(4).gravityCapG());
		AiController rawP1 = new AiController(raw.player(0), () -> Opponent.of(raw.player(1).game()),
				new Brain(new BaselineScorer(), 0, null, Chooser.rng(31L, 1)), 200, 10);
		AiController rawP2 = new AiController(raw.player(1), () -> Opponent.of(raw.player(0).game()),
				choices(st, 31L)[1].brain(), level(4).thinkMs(), level(4).inputsPerSec());
		int[] locks = new int[2];
		int clears = 0;
		int hardDrops = 0;
		int allGone = 0; // 칸이 모두 지워진 고정 (cells 는 비고 landed 는 4칸)
		while (!s.over() && s.match.time() < 90_000) {
			s.advance(p1.poll());
			raw.tick(rawP1.poll(), rawP2.poll());
			long now = s.match.time();
			for (int i = 0; i < 2; i++) {
				List<GameSession.LockFx> lf = s.lockFx(i);
				assertTrue(lf.size() <= GameSession.FX_KEEP);
				if (s.match.lastLock(i) == null) {
					continue;
				}
				locks[i]++;
				GameSession.LockFx l = lf.get(lf.size() - 1);
				assertEquals(now, l.at(), "고정한 틱에 적는다");
				GameModel.LockResult r = s.match.lastLock(i);
				assertEquals(r.type().code(), l.code());
				assertEquals(4 - countIn(r.placedCells(), r.clearedRows()), l.cells().length, "지운 줄의 칸은 빠진다");
				int[][] placed = r.placedCells();
				int[][] landed = l.landed();
				assertEquals(placed.length, landed.length, "내려앉은 자리는 지운 줄의 칸도 남긴다 (하드드롭 꼬리)");
				for (int k = 0; k < placed.length; k++) { // 지우기 전의 자리에서 오른 방해 줄만큼 올린다
					assertArrayEquals(new int[] { placed[k][0] - r.garbageRisen(), placed[k][1] }, landed[k]);
				}
				allGone += l.cells().length == 0 ? 1 : 0;
				// 방해 줄 아웃으로 끝난 고정은 오르다 만 줄 수를 규칙 결과가 알려 주지 않는다 (LockFx 설명)
				for (int[] c : r.topout() == GameModel.Topout.GARBAGE_OUT ? new int[0][] : l.cells()) {
					// 최종 자리에는 그 블록의 칸이 있다(줄을 지우고 내려온 뒤, 방해 줄이 오른 뒤)
					assertEquals(l.code(), s.match.player(i).game().cell(c[0], c[1]), "P" + (i + 1) + " " + c[0] + "," + c[1]);
				}
				if (l.hardDrop()) {
					hardDrops++;
					assertTrue(l.dropRows() >= 0);
				} else {
					assertEquals(0, l.dropRows());
				}
				if (r.lines() > 0) {
					clears++;
					List<GameSession.ClearFx> cf = s.clearFx(i);
					GameSession.ClearFx cl = cf.get(cf.size() - 1);
					assertEquals(now, cl.at());
					assertEquals(r.lines(), cl.lines());
					assertEquals(r.lines(), cl.rows().length);
					for (int[] row : cl.codes()) {
						assertEquals(Grid.WIDTH, row.length);
						for (int v : row) {
							assertTrue(v != Grid.EMPTY, "지운 줄은 가득 차 있었다");
						}
					}
					assertEquals(s.b2bStreak(i), cl.b2bStreak());
				}
			}
		}
		assertTrue(locks[0] > 30 && locks[1] > 30,
				"고정 " + locks[0] + " / " + locks[1] + " (칸이 모두 지워진 고정 " + allGone + ")");
		assertTrue(clears > 5, "줄 지우기 " + clears);
		assertTrue(hardDrops > 10, "하드드롭 " + hardDrops);
		for (int i = 0; i < 2; i++) {
			GameModel g = s.match.player(i).game();
			GameModel r = raw.player(i).game();
			assertEquals(r.pieces(), g.pieces(), "효과용 사건을 적어도 판은 그대로");
			assertTrue(r.grid().sameOccupancy(g.grid()));
			assertEquals(r.sentTotal(), g.sentTotal());
			synchronized (s.lock) {
				GameSession.PlayerStats st0 = s.stats(i);
				int cancelled = g.attackTotal() - g.sentTotal();
				int cancelSeen = 0;
				for (GameSession.CancelFx c : s.cancelFx(i)) {
					cancelSeen += c.lines();
				}
				assertTrue(cancelSeen <= cancelled, "최근 상쇄는 모든 상쇄의 일부");
				assertEquals(g.pieces(), st0.pieces());
				assertEquals(g.linesTotal(), st0.lines());
				assertEquals(g.attackTotal(), st0.attack());
				assertEquals(g.sentTotal(), st0.sent());
				assertEquals(s.match.time(), st0.timeMs());
				assertEquals(g.pieces() / (s.match.time() / 1000.0), st0.pps(), 1e-9);
				assertEquals(g.attackTotal() * 60.0 / (s.match.time() / 1000.0), st0.apm(), 1e-9);
				assertTrue(st0.maxB2bStreak() >= 0 && st0.quads() >= 0 && st0.tspins() >= 0 && st0.maxCombo() >= 0);
			}
		}
		if (!s.match.isOver()) { // 보낸 줄은 모두 상대가 받았다 (끝나는 틱의 송신은 붙지 않는다)
			assertEquals(s.match.player(1).game().sentTotal(), s.stats(0).garbageReceived());
			assertEquals(s.match.player(0).game().sentTotal(), s.stats(1).garbageReceived());
		}
	}

	private static int countIn(int[][] cells, int[] rows) {
		int n = 0;
		for (int[] c : cells) {
			for (int r : rows) {
				n += c[0] == r ? 1 : 0;
			}
		}
		return n;
	}

	@Test
	void practiceGarbageIsReceivedAndCancelsAreCounted() {
		GameSession s = new GameSession(GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX), CATALOG, 8L, x -> {
		});
		AiController me = pilot(s, 8L);
		int received = 0;
		int cancelled = 0;
		int receives = 0;
		while (!s.over() && s.match.time() < 120_000) {
			List<GameSession.ReceiveFx> rb = s.receiveFx(0);
			List<GameSession.CancelFx> cb = s.cancelFx(0);
			s.advance(me.poll());
			List<GameSession.ReceiveFx> ra = s.receiveFx(0);
			List<GameSession.CancelFx> ca = s.cancelFx(0);
			assertTrue(ra.size() <= GameSession.FX_KEEP && ca.size() <= GameSession.FX_KEEP);
			if (ra != rb) { // 새 사건이 붙을 때만 목록이 바뀐다 (읽는 쪽은 복사하지 않아도 된다)
				received += ra.get(ra.size() - 1).lines();
				receives++;
				assertEquals(s.match.time(), ra.get(ra.size() - 1).at());
			}
			if (ca != cb) {
				cancelled += ca.get(ca.size() - 1).lines();
			}
		}
		GameModel g = s.match.player(0).game();
		assertTrue(receives > 3, "스케줄러 방해 줄 " + receives + "번");
		assertEquals(received, s.stats(0).garbageReceived(), "받은 줄의 합");
		assertEquals(g.attackTotal() - g.sentTotal(), cancelled, "상쇄한 줄의 합 = 공격 − 보냄");
	}

	@Test
	void theSessionClockMarksWhenTheGameEnded() {
		AtomicLong now = new AtomicLong(ms(5));
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		GameSession s = new GameSession(st, 4L, x -> {
		}, choices(st, 4L), null, now::get);
		assertEquals(ms(5), s.clockNanos());
		synchronized (s.lock) {
			assertEquals(GameSession.NOT_OVER, s.overAtNanos());
		}
		while (!s.over()) {
			now.set(ms(1_000 + s.match.time())); // 실제 시간이 판 시계를 따라간다
			s.advance(null);
		}
		synchronized (s.lock) {
			assertEquals(ms(1_000 + s.match.time() - 1), s.overAtNanos(), "끝난 틱의 시계 값");
		}
		now.set(ms(999_999));
		assertEquals(ms(999_999), s.clockNanos(), "판이 끝나도 시계는 간다 (결과 카드의 움직임)");
	}

	// ------------------------------------------------------------------ 소리

	@Test
	void theAiPlaysSilentlyWhileTheHumanBoardIsHeard() {
		// 사람은 가만히 있다: 블록은 중력으로만 떨어져 고정된다. AI 는 옮기고 돌리고 줄을 지운다
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		GameSession s = new GameSession(st, 41L, x -> {
		}, choices(st, 41L), null, System::nanoTime);
		List<Cue> heard = listen(s);
		int humanLocks = 0;
		int aiLocks = 0;
		int aiClears = 0;
		while (!s.over() && s.match.time() < 60_000) {
			s.advance(null);
			humanLocks += s.match.lastLock(0) != null ? 1 : 0;
			GameModel.LockResult r = s.match.lastLock(1);
			if (r != null) {
				aiLocks++;
				aiClears += r.lines() > 0 ? 1 : 0;
			}
		}
		assertTrue(humanLocks >= 2 && aiLocks > 30 && aiClears > 3, "사람 " + humanLocks + ", AI " + aiLocks + "/" + aiClears);
		assertEquals(humanLocks, count(heard, Sfx.LOCK), "사람의 블록이 고정될 때마다 한 번 (하드드롭은 없었다)");
		assertEquals(0, count(heard, Sfx.HARD_DROP));
		assertEquals(0, count(heard, Sfx.MOVE) + count(heard, Sfx.ROTATE) + count(heard, Sfx.HOLD), "AI 의 조작은 소리가 없다");
		assertEquals(0, count(heard, Sfx.CLEAR) + count(heard, Sfx.TSPIN) + count(heard, Sfx.COMBO), "AI 가 지운 줄도");
		assertTrue(count(heard, Sfx.LAND) >= humanLocks, "사람의 블록이 바닥에 닿음");
	}

	@Test
	void everyHumanLockClearAndIncomingAttackIsHeardOnce() {
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		GameSession s = new GameSession(st, 43L, x -> {
		}, choices(st, 43L), null, System::nanoTime);
		List<Cue> heard = listen(s);
		AiController me = pilot(s, 43L);
		int locks = 0;
		int clears = 0;
		while (!s.over() && s.match.time() < 90_000) {
			s.advance(me.poll());
			GameModel.LockResult r = s.match.lastLock(0);
			if (r != null) {
				locks++;
				clears += r.lines() > 0 ? 1 : 0;
			}
		}
		assertTrue(locks > 30 && clears > 5, "고정 " + locks + ", 줄 지우기 " + clears);
		assertEquals(locks, count(heard, Sfx.LOCK) + count(heard, Sfx.HARD_DROP), "고정마다 고정 소리나 하드드롭 소리 하나");
		assertEquals(clears, count(heard, Sfx.CLEAR) + count(heard, Sfx.TSPIN), "줄을 지울 때마다 하나");
		assertTrue(count(heard, Sfx.MOVE) > 20 && count(heard, Sfx.ROTATE) > 10, "성공한 조작");
		int warned = 0;
		for (Cue c : heard) {
			if (c.sfx() == Sfx.GARBAGE) {
				warned += c.intensity();
				assertEquals(SoundCues.ATTACK_FLIGHT_MS, c.delayMs(), "대전은 공격 칩이 막대에 닿을 때 울린다");
			}
		}
		synchronized (s.lock) {
			assertTrue(s.stats(0).garbageReceived() > 0, "AI 가 공격했다");
			assertEquals(s.stats(0).garbageReceived(), warned, "받은 방해 줄마다 경고");
		}
		assertEquals(List.of(), results(heard), "판 끝 소리는 틱마다 내지 않는다");
	}

	@Test
	void theEndOfAGamePlaysTheResultOnceButAForfeitIsSilent() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		GameSession lost = new GameSession(st, 4L, save, x -> {
		}, choices(st, 4L), null, System::nanoTime);
		List<Cue> heard = listen(lost);
		while (!lost.over()) {
			lost.advance(null); // 가만히 있는 사람은 진다
		}
		assertEquals(List.of(), results(heard), "결과 소리는 판이 끝난 뒤의 처리에서 낸다");
		lost.finishIfOver();
		assertEquals(List.of(Sfx.KO, Sfx.LOSE), results(heard), "K.O. 뒤 패배 음악");
		Cue lose = heard.stream().filter(c -> c.sfx() == Sfx.LOSE).findFirst().orElseThrow();
		assertEquals(SoundCues.RESULT_GAP_MS, lose.delayMs());
		lost.finishIfOver();
		assertEquals(2, results(heard).size(), "한 번만");
		assertEquals(1, save.records().versus(5).losses());

		GameSession left = new GameSession(st, 5L, save, x -> {
		}, choices(st, 5L), null, System::nanoTime);
		List<Cue> leftHeard = listen(left);
		play(left, pilot(left, 5L), 5_000);
		int during = leftHeard.size();
		assertTrue(during > 0, "판 도중의 소리");
		left.forfeit();
		left.finishIfOver();
		assertEquals(List.of(), results(leftHeard), "기권한 판은 결과 소리가 없다");
		assertEquals(during, leftHeard.size(), "떠난 판은 더 소리를 내지 않는다");
		assertEquals(2, save.records().versus(5).losses(), "기권은 패배로 센다");
	}

	@Test
	void practiceEndsWithAKnockOutAndANewBestAddsTheRecordSound() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.solo(3000, GameModel.Scheduler.S_3);
		GameSession first = new GameSession(st, 13L, save, x -> {
		}, new AiCatalog.Choice[1], null, System::nanoTime);
		List<Cue> one = listen(first);
		while (!first.over()) {
			first.advance(TestKeys.HARD_DROP_EVERY_200MS.at(first.match.time())); // 금방 쌓여 진다
		}
		first.finishIfOver();
		assertEquals(List.of(Sfx.KO), results(one), "혼자 연습은 K.O. 만 (첫 판은 새 기록이 아니다)");
		GameSession second = new GameSession(st, 13L, save, x -> {
		}, new AiCatalog.Choice[1], null, System::nanoTime);
		List<Cue> two = listen(second);
		play(second, pilot(second, 13L), 10_000); // 10초 버틴 뒤
		while (!second.over()) {
			second.advance(TestKeys.HARD_DROP_EVERY_200MS.at(second.match.time()));
		}
		second.finishIfOver();
		assertTrue(second.newBest(), "더 오래 버텼다");
		assertEquals(List.of(Sfx.KO, Sfx.NEW_RECORD), results(two));
		assertEquals(TimedMatch.Result.OVER, second.result());
	}

	@Test
	void theGameThreadRecordsAndSoundsTheEndBeforeTellingTheWindow() throws Exception {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		AtomicLong now = new AtomicLong();
		AtomicReference<String> seenAtOver = new AtomicReference<>();
		List<Cue> heard = new CopyOnWriteArrayList<>();
		GameSession s = new GameSession(st, 14L, save, x -> seenAtOver.set(results(heard) + " " + save.records()
				.versus(5).losses()), choices(st, 14L), null, now::get);
		s.soundsTo(heard::addAll);
		s.start();
		try {
			now.set(ms(GameSession.COUNTDOWN_MS + 900_000));
			await(() -> seenAtOver.get() != null, "판이 끝났다고 알린다");
		} finally {
			s.stop();
		}
		assertEquals("[KO, LOSE] 1", seenAtOver.get(), "창에 알릴 때는 기록과 결과 소리가 이미 끝났다 (창은 다시 그리기만)");
		assertEquals(List.of(Sfx.KO, Sfx.LOSE), results(heard), "한 번만");
	}

	@Test
	void aWinPlaysTheWinJingleAndANewBestStreakAddsTheRecordSound() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(1), neutral()); // 느린 초급
		for (int game = 1; game <= 2; game++) {
			GameSession s = new GameSession(st, 11L, save, x -> {
			}, choices(st, 11L), null, System::nanoTime);
			List<Cue> heard = listen(s);
			AiController fast = new AiController(s.match.player(0), () -> Opponent.of(s.match.player(1).game()),
					new Brain(new BaselineScorer(), 0, null, Chooser.rng(11L, 1)), 50, 30);
			while (!s.over() && s.match.time() < 900_000) {
				s.advance(fast.poll());
			}
			s.finishIfOver();
			assertEquals(TimedMatch.Result.P1, s.result());
			assertEquals(game == 1 ? List.of(Sfx.KO, Sfx.WIN) : List.of(Sfx.KO, Sfx.WIN, Sfx.NEW_RECORD), results(heard),
					game + "번째 판");
		}
	}

	@Test
	void afterLeavingASessionMakesNoMoreSound() {
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		GameSession s = new GameSession(st, 15L, x -> {
		}, choices(st, 15L), null, System::nanoTime);
		List<Cue> heard = listen(s);
		AiController me = pilot(s, 15L);
		play(s, me, 3_000);
		assertTrue(heard.size() > 0);
		int before = heard.size();
		s.stop(); // 메뉴로: 뒤에 남은 틱이 있어도 메뉴 소리 뒤에 판 소리가 나지 않게
		play(s, me, 6_000);
		assertEquals(before, heard.size());
	}

	// ------------------------------------------------------------------ 승률 막대 (보정)

	@Test
	void theWinBarShowsTheCalibratedPrediction() throws Exception {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Path model = Path.of("models", "winprob.onnx");
		assumeTrue(Files.isRegularFile(model), "models/winprob.onnx 가 없어 건너뜀");
		try (WinRate wr = WinRate.load(model, 1)) {
			assumeTrue(wr.calibration() != null, "models/ 에 이 모델의 보정층이 없어 건너뜀: " + wr.calibrationProblem());
			GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
			GameSession s = new GameSession(st, 16L, x -> {
			}, choices(st, 16L), wr, System::nanoTime);
			AiController me = pilot(s, 16L);
			int checked = 0;
			int moved = 0;
			while (!s.over() && s.match.time() < 40_000) {
				s.advance(me.poll());
				if ((s.match.lastLock(0) != null || s.match.lastLock(1) != null) && !s.match.isOver()) {
					PlayerState a = PlayerState.of(s.match.player(0).game());
					PlayerState b = PlayerState.of(s.match.player(1).game());
					double calibrated = wr.p1Win(a, b, s.match.time());
					assertEquals(calibrated, s.p1Win(), 1e-12, "막대는 블록 수와 판 시각으로 보정한 값");
					moved += Math.abs(calibrated - wr.p1Win(a, b)) > 1e-4 ? 1 : 0;
					checked++;
				}
			}
			assertTrue(checked > 20, "고정마다 다시 잰다: " + checked);
			assertTrue(moved > checked / 2, "보정으로 값이 달라진다: " + moved + "/" + checked);
			synchronized (s.lock) {
				assertEquals(0.5, s.winHistory.get(0)[1], 1e-9, "판 시작 막대는 0.5");
				assertEquals(0, s.winHistory.get(0)[0]);
			}
		}
	}

	@Test
	void winSwingTagsNeedACauseAndRisenGarbageSaysItRose() {
		assertFalse(GameSession.isSwing(0.5, 0.85, ""), "원인 없는 움직임(처음 놓은 블록 등)은 꼬리표를 띄우지 않는다");
		assertTrue(GameSession.isSwing(0.5, 0.56, "AI 공격 3줄"));
		assertTrue(GameSession.isSwing(0.5, 0.44, "나 1줄 지움"));
		assertFalse(GameSession.isSwing(0.5, 0.53, "AI 공격 3줄"), "5%p 보다 작다");
		assertFalse(GameSession.isSwing(Double.NaN, 0.9, "AI 공격 3줄"), "첫 예측");
		// 원인 문구: 방해 줄이 올라온 고정은 "나 방해 줄 2줄 올라옴" (공격 "AI 공격 3줄", 지우기 "나 1줄 지움" 처럼 무슨 일인지 말한다)
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		GameSession s = new GameSession(st, 43L, x -> {
		}, choices(st, 43L), null, System::nanoTime);
		AiController me = pilot(s, 43L);
		int risen = 0;
		while (!s.over() && s.match.time() < 90_000) {
			s.advance(me.poll());
			synchronized (s.lock) {
				GameModel.LockResult r0 = s.match.lastLock(0);
				GameModel.LockResult r1 = s.match.lastLock(1);
				boolean sent = r0 != null && r0.sent() > 0 || r1 != null && r1.sent() > 0;
				int who = r0 != null && r0.garbageRisen() > 0 ? 0 : r1 != null && r1.garbageRisen() > 0 ? 1 : -1;
				if (!sent && who >= 0) {
					int n = s.match.lastLock(who).garbageRisen();
					assertEquals(s.name(who) + " 방해 줄 " + n + "줄 올라옴", s.swingCause());
					risen++;
				}
			}
		}
		assertTrue(risen > 0, "방해 줄이 올라온 고정");
	}

	// ------------------------------------------------------------------ 모델 불러오기 (화면 스레드는 기다리지 않는다)

	/** 1단계는 기준 AI, 2단계는 없는 모델 파일 (불러오면 실패해 기준 AI 로 대신한다). 승률 예측망은 없다. */
	private static final String MANIFEST = """
			{"format": "models-1",
			 "difficulties": [
			  {"id": 1, "name": "쉬움", "model": "baseline", "temperature": 0, "beam": 0,
			   "think_ms": 300, "inputs_per_sec": 10, "gravity_cap": 2},
			  {"id": 2, "name": "어려움", "model": "missing.onnx", "temperature": 0, "beam": 0,
			   "think_ms": 200, "inputs_per_sec": 16, "gravity_cap": 5}],
			 "personas": [{"id": "neutral", "name": "중립", "model": null}],
			 "winprob": null}
			""";

	@Test
	void aSessionMadeBeforeTheModelIsLoadedDoesNotWaitAndTheCountdownHoldsUntilItIs(@TempDir Path dir)
			throws Exception {
		Files.writeString(dir.resolve(AiCatalog.MANIFEST), MANIFEST, StandardCharsets.UTF_8);
		try (AiCatalog catalog = AiCatalog.load(dir)) {
			AiCatalog.Difficulty hard = catalog.difficulties().get(1);
			GameSession.Settings st = GameSession.Settings.versus(hard, catalog.personas().get(0));
			// 배경 스레드가 다른 모델을 불러오는 중인 것처럼 카탈로그의 잠금을 쥔다
			CountDownLatch held = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			Thread loading = new Thread(() -> {
				synchronized (catalog) {
					held.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}, "busy-preload");
			loading.start();
			held.await();
			AtomicLong now = new AtomicLong();
			GameSession s;
			try {
				long t0 = System.nanoTime();
				s = new GameSession(st, catalog, 17L, null, x -> {
				}, now::get);
				assertTrue(System.nanoTime() - t0 < 500_000_000L, "화면 스레드(판을 만드는 곳)는 모델을 기다리지 않는다");
				assertTrue(s.aiLoading());
				s.start();
				now.set(ms(5_000)); // 카운트다운이 끝날 때까지 다 불러오지 못했다
				Thread.sleep(50);
				assertTrue(s.aiLoading(), "진행 스레드가 기다린다");
				assertEquals(1, s.countdown(), "카운트다운은 1 에서 멈춘다");
				assertEquals(0, time(s), "판의 시계도 가지 않는다");
				assertEquals(-1, s.goAgeMs());
			} finally {
				release.countDown();
				loading.join();
			}
			try {
				await(() -> !s.aiLoading(), "모델을 정한다");
				assertEquals(0, s.countdown(), "다 불러오면 바로 시작한다");
				assertEquals(0, time(s));
				now.set(ms(5_400));
				await(() -> time(s) == 400, "판의 시계는 불러온 때부터 간다");
				synchronized (s.lock) {
					assertTrue(s.loadFallback(), "없는 모델이라 기준 AI 가 대신 둔다");
					assertEquals(new GameSession.AiStatus(true, "고른 AI의 모델을 쓸 수 없어 기준 AI로 둬요"), s.aiStatus(),
							"메뉴의 알림과 같은 문구");
				}
				assertNotNull(s.ai(1));
			} finally {
				s.stop();
			}
		}
	}

	@Test
	void leavingWhileTheModelIsStillLoadingDoesNotWaitForIt(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(AiCatalog.MANIFEST), MANIFEST, StandardCharsets.UTF_8);
		SaveData save = SaveData.inMemory();
		try (AiCatalog catalog = AiCatalog.load(dir)) {
			GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(1), catalog.personas().get(0));
			CountDownLatch held = new CountDownLatch(1);
			CountDownLatch release = new CountDownLatch(1);
			Thread loading = new Thread(() -> {
				synchronized (catalog) {
					held.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				}
			}, "busy-preload");
			loading.start();
			held.await();
			AtomicLong now = new AtomicLong();
			GameSession s = new GameSession(st, catalog, 20L, save, x -> {
			}, now::get);
			List<Cue> heard = listen(s);
			try {
				s.start();
				now.set(ms(1_000));
				Thread.sleep(30);
				assertTrue(s.aiLoading());
				long t0 = System.nanoTime();
				s.forfeit(); // 카운트다운 중에 메뉴로
				assertTrue(System.nanoTime() - t0 < 500_000_000L, "화면 스레드는 모델 불러오기를 기다리지 않는다");
				assertTrue(s.over());
			} finally {
				release.countDown();
				loading.join();
			}
			now.set(ms(60_000));
			Thread.sleep(100); // 진행 스레드는 불러오기를 마치고 판을 두지 않고 끝난다
			assertEquals(0, time(s), "떠난 판은 진행하지 않는다");
			assertEquals(List.of(), heard);
			assertEquals(PlayRecords.empty(), save.records(), "카운트다운 중에 떠난 판은 남기지 않는다");
		}
	}

	@Test
	void modelsThatWereAlreadyTriedAreUsedRightAway(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve(AiCatalog.MANIFEST), MANIFEST, StandardCharsets.UTF_8);
		try (AiCatalog catalog = AiCatalog.load(dir)) {
			AiCatalog.Persona neutral = catalog.personas().get(0);
			GameSession easy = new GameSession(GameSession.Settings.versus(catalog.difficulties().get(0), neutral), catalog,
					18L, x -> {
					});
			assertFalse(easy.aiLoading(), "기준 AI 단계는 불러올 것이 없다");
			assertNotNull(easy.ai(1));
			catalog.preload(); // 메뉴가 배경에서 미리 불러 둔다
			GameSession hard = new GameSession(GameSession.Settings.versus(catalog.difficulties().get(1), neutral), catalog,
					18L, x -> {
					});
			assertFalse(hard.aiLoading(), "이미 불러 본 모델은 바로 쓴다");
			synchronized (hard.lock) {
				assertTrue(hard.aiStatus().fallback());
			}
		}
		try (AiCatalog fresh = AiCatalog.load(dir)) { // 스레드 없이 진행하는 도구는 첫 틱에 정한다
			GameSession tool = new GameSession(GameSession.Settings.versus(fresh.difficulties().get(1),
					fresh.personas().get(0)), fresh, 19L, x -> {
					});
			assertTrue(tool.aiLoading());
			assertNull(tool.ai(1));
			tool.advance(null);
			assertFalse(tool.aiLoading());
			assertNotNull(tool.ai(1));
		}
	}

	// ------------------------------------------------------------------ 플레이 기록 (전적, 혼자 연습 최고 기록, 기권)

	@Test
	void aForfeitIsALossAndIsRecordedOnce() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(3), neutral());
		GameSession s = new GameSession(st, 5L, save, x -> {
		}, choices(st, 5L), null, System::nanoTime);
		play(s, pilot(s, 5L), 10_000);
		assertFalse(s.over());
		s.forfeit();
		assertTrue(s.over());
		assertTrue(s.forfeited());
		assertEquals(TimedMatch.Result.P2, s.result(), "기권은 AI 의 승리");
		PlayRecords.Versus v = save.records().versus(3);
		assertEquals(1, v.losses());
		assertEquals(0, v.wins());
		s.forfeit(); // 여러 번 불러도 한 번만
		s.finishIfOver();
		assertEquals(1, save.records().versus(3).losses());
		assertSame(save.records(), s.records());
		long t = s.match.time();
		s.advance(null);
		assertEquals(t, s.match.time(), "기권한 판은 더 가지 않는다");
		assertFalse(s.newBest());
	}

	@Test
	void leavingDuringTheCountdownOrAfterAnErrorIsNotRecorded() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(3), neutral());
		GameSession early = new GameSession(st, 6L, save, x -> {
		}, choices(st, 6L), null, System::nanoTime);
		early.forfeit(); // 판 시계 0: 카운트다운 중에 나갔다
		assertTrue(early.over());
		GameSession broken = new GameSession(st, 6L, save, x -> {
		}, choices(st, 6L), null, System::nanoTime);
		play(broken, pilot(broken, 6L), 5_000);
		broken.fail(new IllegalStateException("시험용"));
		broken.forfeit();
		broken.finishIfOver();
		assertEquals(PlayRecords.empty(), save.records(), "카운트다운 중에 나간 판, 오류로 멈춘 판은 남기지 않는다");
	}

	@Test
	void aGameAgainstAFallbackAiIsNotRecorded() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		AiCatalog.Choice[] seats = choices(st, 7L);
		seats[1] = new AiCatalog.Choice(seats[1].brain(), "시험", "모델 파일이 없음"); // 불러오지 못해 기준 AI 로 대신함
		GameSession s = new GameSession(st, 7L, save, x -> {
		}, seats, null, System::nanoTime);
		synchronized (s.lock) {
			assertTrue(s.aiStatus().fallback());
			assertEquals("고른 AI의 모델을 쓸 수 없어 기준 AI로 둬요", s.aiStatus().reason());
		}
		play(s, pilot(s, 7L), 5_000);
		s.forfeit();
		assertEquals(PlayRecords.empty(), save.records(), "기준 AI 로 둔 판은 그 난이도의 전적이 아니다");
	}

	@Test
	void anUnreadableModelBundleIsShownAsTheBaselineAndNotRecorded() {
		// 모델 묶음(manifest)을 읽지 못하면 카탈로그는 모든 단계를 기준 AI 로 둔다(고른 모델의 대신이 아니므로 fallback 이 없다).
		// 판은 메뉴처럼 기준 AI 라고 알리고, 그 판은 난이도의 전적에 남기지 않는다.
		assertNotNull(CATALOG.problem(), "시험용 카탈로그는 모델 묶음이 없다");
		SaveData save = SaveData.inMemory();
		GameSession s = new GameSession(GameSession.Settings.versus(level(3), neutral()), CATALOG, 8L, save, x -> {
		});
		synchronized (s.lock) {
			assertTrue(s.baseline(1), "AI 이름표의 기준 AI 꼬리표");
			assertFalse(s.baseline(0), "사람 자리는 아니다");
			assertTrue(s.loadFallback());
			assertEquals(new GameSession.AiStatus(true, "학습한 AI를 불러오지 못해 기준 AI로 둬요"), s.aiStatus(),
					"메뉴의 알림과 같은 문구");
		}
		play(s, pilot(s, 8L), 5_000);
		s.forfeit();
		assertEquals(PlayRecords.empty(), save.records(), "기준 AI 로 둔 판은 그 난이도의 전적이 아니다");

		GameSession solo = new GameSession(GameSession.Settings.solo(3000, GameModel.Scheduler.S_3), CATALOG, 8L, save,
				x -> {
				});
		synchronized (solo.lock) {
			assertSame(GameSession.AiStatus.OK, solo.aiStatus(), "혼자 연습에는 AI 가 없다");
			assertFalse(solo.baseline(0));
		}
		play(solo, pilot(solo, 8L), 5_000);
		solo.forfeit();
		assertEquals(1, save.records().solo(PlayRecords.soloSetup(3000, "S-3")).games(), "혼자 연습은 그대로 남긴다");
	}

	@Test
	void winsAreRecordedAndASecondStraightWinIsANewBest() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.versus(level(1), neutral()); // 느린 초급
		for (int game = 1; game <= 2; game++) {
			GameSession s = new GameSession(st, 11L, save, x -> {
			}, choices(st, 11L), null, System::nanoTime);
			AiController fast = new AiController(s.match.player(0), () -> Opponent.of(s.match.player(1).game()),
					new Brain(new BaselineScorer(), 0, null, Chooser.rng(11L, 1)), 50, 30);
			while (!s.over() && s.match.time() < 900_000) {
				s.advance(fast.poll());
			}
			assertEquals(TimedMatch.Result.P1, s.result(), game + "번째 판: 빠른 손이 이긴다 (" + s.match.time() + "ms)");
			s.finishIfOver();
			s.finishIfOver(); // 한 번만 더한다
			PlayRecords.Versus v = save.records().versus(1);
			assertEquals(game, v.wins());
			assertEquals(s.match.time(), v.fastestWinMs());
			assertEquals(game == 2, s.newBest(), "첫 승은 새 기록이 아니고, 두 번째 연승은 최고 연승");
			assertEquals(v, s.records().versus(1));
			assertEquals(game, v.streak());
			assertEquals(v.bestStreak(), v.streak(), "이 판까지의 연승이 가장 긴 연승 (결과 카드의 \"2연승\")");
		}
	}

	@Test
	void aNewBestIsJudgedAgainstWhatAnotherWindowSaved(@TempDir Path dir) {
		// 두 창이 같은 저장 파일을 쓴다. 이 창(a)이 판을 두는 동안 다른 창(b)이 더 빠른 승리와 더 긴 연승을 남겼으면, 이 창이 이긴
		// 판은 새 기록이 아니다 (이 창이 처음 읽은 낡은 기록과 비교하지 않는다)
		SaveData a = SaveData.open(dir);
		SaveData b = SaveData.open(dir);
		a.updateRecords(r -> r.withVersus(1, PlayRecords.Outcome.WIN, 890_000)); // 이 창이 아는 기록: 1연승, 14분 50초
		b.updateRecords(r -> r.withVersus(1, PlayRecords.Outcome.WIN, 1_000) // 다른 창: 3연승(합쳐 4연승), 1초 승리, 그리고 패배
				.withVersus(1, PlayRecords.Outcome.WIN, 1_000).withVersus(1, PlayRecords.Outcome.WIN, 1_000)
				.withVersus(1, PlayRecords.Outcome.LOSS, 0));
		GameSession.Settings st = GameSession.Settings.versus(level(1), neutral()); // 느린 초급
		GameSession s = new GameSession(st, 11L, a, x -> {
		}, choices(st, 11L), null, System::nanoTime);
		AiController fast = new AiController(s.match.player(0), () -> Opponent.of(s.match.player(1).game()),
				new Brain(new BaselineScorer(), 0, null, Chooser.rng(11L, 1)), 50, 30);
		while (!s.over() && s.match.time() < 880_000) {
			s.advance(fast.poll());
		}
		assertEquals(TimedMatch.Result.P1, s.result(), "빠른 손이 이긴다 (" + s.match.time() + "ms)");
		s.finishIfOver();
		PlayRecords.Versus v = s.records().versus(1);
		assertEquals(5, v.wins(), "다른 창이 남긴 판 위에 더했다");
		assertEquals(1_000, v.fastestWinMs());
		assertEquals(4, v.bestStreak());
		assertFalse(s.newBest(), "다른 창이 남긴 가장 빠른 승리·가장 긴 연승을 넘지 못했다");
	}

	@Test
	void practiceRecordsLinesAndTimePerSetup() {
		SaveData save = SaveData.inMemory();
		GameSession.Settings st = GameSession.Settings.solo(3000, GameModel.Scheduler.S_3);
		String setup = PlayRecords.soloSetup(3000, "S-3");
		GameSession first = new GameSession(st, 12L, save, x -> {
		}, new AiCatalog.Choice[1], null, System::nanoTime);
		play(first, pilot(first, 12L), 20_000);
		first.forfeit(); // 그만둔 혼자 연습도 남긴다
		PlayRecords.Solo a = save.records().solo(setup);
		assertEquals(1, a.games());
		assertEquals(first.match.player(0).game().linesTotal(), a.bestLines());
		assertEquals(20_000, a.bestTimeMs());
		assertFalse(first.newBest(), "첫 판은 새 기록이 아니다");
		GameSession second = new GameSession(st, 12L, save, x -> {
		}, new AiCatalog.Choice[1], null, System::nanoTime);
		play(second, pilot(second, 12L), 40_000);
		second.forfeit();
		assertEquals(2, save.records().solo(setup).games());
		assertEquals(40_000, save.records().solo(setup).bestTimeMs());
		assertTrue(second.newBest(), "더 오래 버텼다");
		// 새 기록이면 records() 는 이 판을 더한 기록이고 이 판의 버틴 시간이 그 기록의 값이다 (결과 카드의 "최장 시간 0:40")
		assertEquals(save.records(), second.records());
		assertEquals(second.match.time(), second.records().solo(setup).bestTimeMs());
		assertEquals(TimedMatch.Result.OVER, second.result());
	}

	// ------------------------------------------------------------------ 결정론: 스레드와 미리 고르기

	@Test
	void threadedVersusWithPrefetchPlaysTheSameGameAsDirectStepping() throws Exception {
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		GameSession direct = new GameSession(st, 77L, x -> {
		}, catalogChoices(st, 77L), null, System::nanoTime);
		while (!direct.over()) {
			direct.advance(null); // 사람은 가만히 있다
		}
		AtomicLong now = new AtomicLong();
		AtomicInteger overs = new AtomicInteger();
		GameSession threaded = new GameSession(st, 77L, x -> overs.incrementAndGet(), catalogChoices(st, 77L), null,
				now::get);
		threaded.start();
		try {
			now.set(ms(GameSession.COUNTDOWN_MS + 900_000)); // 실제 시간이 한꺼번에 흘렀다: 판 끝까지 따라잡는다
			await(threaded::over, "판이 끝난다");
			await(() -> overs.get() == 1, "끝났다고 한 번 알린다");
		} finally {
			threaded.stop();
		}
		assertEquals(direct.result(), threaded.result());
		assertEquals(direct.match.time(), threaded.match.time());
		for (int i = 0; i < 2; i++) {
			GameModel a = direct.match.player(i).game();
			GameModel b = threaded.match.player(i).game();
			assertEquals(a.pieces(), b.pieces(), "P" + (i + 1) + " 블록 수");
			assertTrue(a.grid().sameOccupancy(b.grid()), "P" + (i + 1) + " 보드");
			assertEquals(a.sentTotal(), b.sentTotal());
		}
		assertEquals(0, direct.ai(1).prefetched(), "스레드 없이 진행하면 미리 고르지 않는다");
	}

	@Test
	void theGameThreadAndTheAiWorkerEndOnTheirOwnWhenTheGameEnds() throws Exception {
		// 결과 카드를 띄워 둔 동안(아직 떠나지 않음) 진행 스레드가 1ms 마다 깨어나거나 미리 고르기 일꾼이 남지 않는다
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		AtomicLong now = new AtomicLong();
		AtomicInteger overs = new AtomicInteger();
		GameSession s = new GameSession(st, 24L, x -> overs.incrementAndGet(), catalogChoices(st, 24L), null, now::get);
		s.start();
		try {
			assertFalse(s.threadsDone());
			now.set(ms(GameSession.COUNTDOWN_MS + 900_000));
			await(s::over, "판이 끝난다");
			await(s::threadsDone, "진행 스레드와 일꾼이 스스로 끝난다");
			assertEquals(1, overs.get(), "끝났다고 한 번 알린 뒤에 끝난다");
			assertTrue(s.ai(1).planned() > 10, "AI 가 두었다");
		} finally {
			s.stop(); // 떠날 때: 이미 끝난 스레드여도 된다
		}
		assertTrue(s.threadsDone());
		assertEquals(1, overs.get());
	}

	// ------------------------------------------------------------------ 모델 오류와 예상하지 못한 오류

	/** 몇 번은 기준 AI 처럼 점수를 매기다가 그 뒤로는 오류를 던지는 모델. */
	private static final class BreaksAfter implements PlacementScorer {
		private final PlacementScorer inner = new BaselineScorer();
		private final int calls;
		private final boolean error; // true 면 모델 오류가 아닌 버그(Error)를 흉내 낸다
		private int seen;

		BreaksAfter(int calls, boolean error) {
			this.calls = calls;
			this.error = error;
		}

		@Override
		public double[] score(List<Afterstate> candidates, Opponent opponent) {
			if (seen++ >= calls) {
				if (error) {
					throw new AssertionError("시험용 버그");
				}
				throw new IllegalStateException("모델 실행 실패: 시험용");
			}
			return inner.score(candidates, opponent);
		}

		@Override
		public String name() {
			return "breaks-after-" + calls;
		}
	}

	/** 사람 대 AI(최상급 속도): AI 는 {@code scorer} 로 점수를 매긴다. 사람은 시험이 정한다. */
	private static GameSession versusWith(PlacementScorer scorer, LongSupplier clock, AtomicInteger overs) {
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		AiCatalog.Choice[] seats = choices(st, 5L);
		seats[1] = new AiCatalog.Choice(new Brain(scorer, 0, null, Chooser.rng(5, 2)), "시험", null);
		return new GameSession(st, 5L, x -> overs.incrementAndGet(), seats, null, clock);
	}

	@Test
	void modelErrorMidGameSwitchesToBaselineAndShowsANotice() {
		GameSession s = versusWith(new BreaksAfter(3, false), System::nanoTime, new AtomicInteger());
		AiController me = pilot(s, 5L);
		boolean noticed = false;
		long firstAge = -2; // 알림을 처음 본 틱의 noticeAge
		synchronized (s.lock) {
			assertFalse(s.baseline(1));
			assertSame(GameSession.AiStatus.OK, s.aiStatus());
			assertEquals(-1, s.noticeAge(1), "아직 바뀌지 않았다");
		}
		while (!s.over() && s.match.time() < 60_000) {
			s.advance(me.poll());
			synchronized (s.lock) {
				long age = s.noticeAge(1);
				boolean shown = s.notice(1);
				assertEquals(age >= 0 && age < GameSession.NOTICE_MS, shown, () -> "알림은 바뀐 뒤 NOTICE_MS 동안: " + age);
				assertEquals(-1, s.noticeAge(0), "사람 자리는 바뀌지 않는다");
				if (shown && !noticed) {
					firstAge = age;
				}
				noticed |= shown;
			}
		}
		assertTrue(noticed, "기준 AI로 바뀌었다는 알림을 잠시 보인다");
		assertEquals(0, firstAge, "바뀐 그 틱에 알림이 뜬다 (화면의 알림이 처음부터 나타나며 들어온다)");
		synchronized (s.lock) {
			assertTrue(s.baseline(1));
			assertFalse(s.notice(1), "알림은 잠깐만");
			assertTrue(s.noticeAge(1) >= GameSession.NOTICE_MS, "알림이 끝난 뒤에도 바뀐 뒤 흐른 시간은 센다: " + s.noticeAge(1));
			assertTrue(s.aiStatus().fallback());
			assertEquals("AI 모델에 문제가 생겨 기준 AI로 바꿨어요", s.aiStatus().reason());
		}
		assertNull(s.failure(), "모델 오류로 판이 멈추지 않는다");
		assertTrue(s.match.player(1).game().pieces() > 30, "AI 는 계속 둔다: " + s.match.player(1).game().pieces());
		assertFalse(s.loadFallback(), "불러올 때의 대신이 아니다");
	}

	@Test
	void aiDecisionsNeverRunWhileHoldingThePaintLock() {
		AtomicReference<Object> paintLock = new AtomicReference<>();
		AtomicInteger calls = new AtomicInteger();
		AtomicInteger underLock = new AtomicInteger();
		PlacementScorer watched = new PlacementScorer() {
			private final PlacementScorer inner = new BaselineScorer();

			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				calls.incrementAndGet();
				if (Thread.holdsLock(paintLock.get())) {
					underLock.incrementAndGet();
				}
				return inner.score(candidates, opponent);
			}

			@Override
			public String name() {
				return "watched";
			}
		};
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		AiCatalog.Choice[] seats = choices(st, 8L);
		seats[1] = new AiCatalog.Choice(new Brain(watched, 0, new BeamSearch(4), Chooser.rng(8, 2)), "시험", null);
		GameSession s = new GameSession(st, 8L, x -> {
		}, seats, null, System::nanoTime);
		paintLock.set(s.lock);
		play(s, pilot(s, 8L), 20_000);
		assertTrue(calls.get() > 50, "점수 매기기 " + calls.get() + "번");
		assertEquals(0, underLock.get(), "화면이 기다리는 잠금을 잡은 채 고르지 않는다");
	}

	@Test
	void aBrokenWinRateModelHidesTheBarAndTheGameGoesOn() throws Exception {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
		Path model = Path.of(System.getProperty("fixtures.dir", "fixtures"), "winprob", "winprob_tiny.onnx");
		GameSession.Settings st = GameSession.Settings.versus(level(4), neutral());
		try (WinRate working = WinRate.load(model, 1)) {
			GameSession ok = new GameSession(st, 6L, x -> {
			}, choices(st, 6L), working, System::nanoTime);
			play(ok, pilot(ok, 6L), 15_000);
			double p = ok.p1Win();
			assertTrue(p >= 0 && p <= 1, "막대 값 " + p);
			synchronized (ok.lock) {
				assertTrue(ok.winHistory.size() > 10, "블록이 고정될 때마다 다시 잰다");
			}
		}
		WinRate broken = WinRate.load(model, 1);
		broken.close(); // 닫힌 모델은 부를 때마다 오류를 낸다
		GameSession s = new GameSession(st, 6L, x -> {
		}, choices(st, 6L), broken, System::nanoTime);
		play(s, pilot(s, 6L), 15_000);
		assertTrue(Double.isNaN(s.p1Win()), "승률 막대를 숨긴다");
		assertNull(s.failure(), "승률 예측이 고장 나도 판은 멈추지 않는다");
		assertTrue(s.over() || s.match.time() == 15_000, "끝까지 둔다");
	}

	@Test
	void stopWaitsUntilTheWorkerHasFinishedThinking() throws Exception {
		AtomicInteger busy = new AtomicInteger();
		AtomicInteger calls = new AtomicInteger();
		PlacementScorer slow = new PlacementScorer() {
			private final PlacementScorer inner = new BaselineScorer();

			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				busy.incrementAndGet();
				calls.incrementAndGet();
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt(); // 끊겨도 끝까지 매긴다(모델 실행처럼)
				}
				try {
					return inner.score(candidates, opponent);
				} finally {
					busy.decrementAndGet();
				}
			}

			@Override
			public String name() {
				return "slow";
			}
		};
		GameSession.Settings st = GameSession.Settings.versus(level(5), neutral());
		AiCatalog.Choice[] seats = { null, new AiCatalog.Choice(new Brain(slow, 0, null, Chooser.rng(4, 2)), "시험", null) };
		AtomicLong now = new AtomicLong();
		GameSession s = new GameSession(st, 4L, x -> {
		}, seats, null, now::get);
		s.start();
		now.set(ms(GameSession.COUNTDOWN_MS + 600_000));
		await(() -> calls.get() > 20, "AI 가 고르기 시작한다");
		s.stop();
		assertEquals(0, busy.get(), "멈춘 뒤에는 모델을 쓰는 스레드가 없다(카탈로그를 닫아도 된다)");
		int after = calls.get();
		Thread.sleep(60);
		assertEquals(after, calls.get(), "더 고르지 않는다");
		s.stop(); // 두 번 불러도 된다
	}

	@Test
	void anUnexpectedErrorStopsTheGameWithAFailureCardInsteadOfFreezing() throws Exception {
		AtomicLong now = new AtomicLong();
		AtomicInteger overs = new AtomicInteger();
		GameSession s = versusWith(new BreaksAfter(2, true), now::get, overs);
		s.start();
		try {
			now.set(ms(GameSession.COUNTDOWN_MS + 30_000));
			await(() -> s.failure() != null, "오류를 알린다");
			await(() -> overs.get() == 1, "끝났다고 한 번 알린다");
		} finally {
			s.stop();
		}
		assertTrue(s.over());
		assertEquals(TimedMatch.Result.NONE, s.result());
		synchronized (s.lock) {
			assertTrue(s.overAtNanos() != GameSession.NOT_OVER, "멈춘 순간을 적는다");
		}
		long t = time(s);
		s.advance(null);
		assertEquals(t, time(s), "멈춘 판은 더 가지 않는다");
	}

	// ------------------------------------------------------------------ 일시정지와 카운트다운

	@Test
	void pauseFreezesTheClockAndResumeCountsDown() throws Exception {
		AtomicLong now = new AtomicLong();
		GameSession.Settings st = GameSession.Settings.solo(1000, GameModel.Scheduler.NONE);
		GameSession s = new GameSession(st, 3L, x -> {
		}, new AiCatalog.Choice[1], null, now::get);
		assertEquals(0, s.countdownMs(), "시작하기 전에는 카운트다운이 없다");
		s.start();
		try {
			assertEquals(3, s.countdown());
			assertEquals(3000, s.countdownMs());
			assertEquals(-1, s.goAgeMs());
			now.set(ms(1_500));
			assertEquals(2, s.countdown());
			assertEquals(1500, s.countdownMs());
			now.set(ms(3_500));
			await(() -> time(s) == 500, "판의 시계가 0.5초");
			assertEquals(0, s.countdown());
			assertEquals(0, s.countdownMs());
			assertEquals(500, s.goAgeMs(), "GO! 가 뜬 지 0.5초");
			assertTrue(s.showGo());
			assertTrue(s.autoPause(), "창을 벗어나면 혼자 연습은 멈춘다");
			assertFalse(s.autoPause(), "이미 멈췄다");
			assertTrue(s.paused());
			assertEquals(0, s.countdownMs(), "멈춘 동안은 카운트다운이 없다");
			now.set(ms(13_500));
			Thread.sleep(30);
			assertEquals(500, time(s), "멈춘 동안 판의 시계는 가지 않는다");
			s.togglePause();
			assertFalse(s.paused());
			assertEquals(2, s.countdown(), "다시 가기 전 2·1");
			assertEquals(2000, s.countdownMs());
			now.set(ms(14_600));
			assertEquals(1, s.countdown());
			assertEquals(900, s.countdownMs());
			Thread.sleep(30);
			assertEquals(500, time(s), "카운트다운 동안에도 가지 않는다");
			now.set(ms(15_750));
			await(() -> time(s) == 750, "카운트다운 뒤 멈춘 자리부터 다시 간다");
			assertEquals(0, s.countdown());
			assertEquals(-1, s.goAgeMs(), "GO! 는 처음에만");
			s.togglePause(); // 15.75초에 멈춤 (판 0.75초)
			now.set(ms(16_000));
			s.togglePause(); // 풀면 18초까지 카운트다운
			s.togglePause(); // 카운트다운 중에 또 멈춰도 시계가 어긋나지 않는다
			assertTrue(s.paused());
			now.set(ms(17_000));
			s.togglePause(); // 풀면 19초까지 카운트다운
			assertEquals(2, s.countdown());
			now.set(ms(18_900));
			Thread.sleep(30);
			assertEquals(750, time(s), "아직 카운트다운 중");
			now.set(ms(19_100));
			await(() -> time(s) == 850, "카운트다운이 끝난 0.1초 뒤");
		} finally {
			s.stop();
		}
	}

	@Test
	void goIsHiddenWhilePausedAndShownOnlyAtTheStart() throws Exception {
		AtomicLong now = new AtomicLong();
		GameSession s = new GameSession(GameSession.Settings.solo(1000, GameModel.Scheduler.NONE), 3L, x -> {
		}, new AiCatalog.Choice[1], null, now::get);
		s.start();
		try {
			now.set(ms(1_000));
			s.togglePause(); // 시작 카운트다운 중에 멈춤
			assertEquals(-1, s.goAgeMs());
			now.set(ms(2_000));
			s.togglePause(); // 풀면 시작 카운트다운을 이어 간다 (남은 2초, 2·1 을 더하지 않는다)
			now.set(ms(4_300));
			await(() -> time(s) == 300, "판의 시계가 0.3초");
			assertEquals(300, s.goAgeMs(), "시작 카운트다운 중에 멈췄다 풀었으면 GO! 는 처음처럼 뜬다");
			s.togglePause(); // GO! 가 보이는 동안 멈춤
			assertTrue(s.paused());
			assertEquals(-1, s.goAgeMs(), "멈춘 동안에는 GO! 를 그리지 않는다 (다시 갈 때 남은 GO! 가 뜨지 않게)");
			assertFalse(s.showGo());
			now.set(ms(6_000));
			s.togglePause(); // 2·1 카운트다운 뒤 다시 간다
			assertEquals(2, s.countdown());
			assertEquals(-1, s.goAgeMs());
			now.set(ms(8_100));
			await(() -> time(s) == 400, "다시 가기 카운트다운이 끝난 0.1초 뒤");
			assertEquals(-1, s.goAgeMs(), "GO! 는 처음에만: 남은 시간만큼 다시 뜨지 않는다");
		} finally {
			s.stop();
		}
	}

	@Test
	void battlesAndPracticePauseWhenTheWindowLosesFocus() {
		GameSession battle = versus(CATALOG);
		assertTrue(battle.autoPause(), "대전은 두 사람의 시계가 함께 멈추므로 공정하다");
		battle.togglePause();
		assertFalse(battle.paused());
		GameSession practice = new GameSession(GameSession.Settings.solo(2000, GameModel.Scheduler.NONE), CATALOG, 2L,
				x -> {
				});
		assertTrue(practice.autoPause());
		practice.forfeit();
		assertFalse(practice.autoPause(), "끝난 판은 멈추지 않는다");
	}

	/** 시험용 사람 입력. */
	private enum TestKeys {
		HARD_DROP_EVERY_200MS;

		TimedPlayer.Input at(long t) {
			return t % 200 == 0 ? TimedPlayer.Input.HD : null;
		}
	}
}
