package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;

/**
 * 게임 창(TetrisApp)이 켤 때와 판을 떠날 때 하는 일 가운데 창 없이 볼 수 있는 것: 사람 판 기록은 게임 실행만 켜고(준 값은 그대로),
 * 끝나지 않은 사람 대 AI 판을 떠나면 전적에 남았는지에 따라 메뉴의 알림을 고르고, Windows 의 타이머 간격을 지키는 스레드는 데몬이며,
 * ONNX Runtime 의 DLL 은 한 번 풀어 둔 폴더를 다시 쓴다. 진짜 시스템 속성, 사용자 폴더, runs/human 은 건드리지 않는다.
 */
class TetrisAppTest {

	private static final String ORT_PATH = "onnxruntime.native.path";

	@Test
	void onlyTheGameTurnsOnHumanRecordsAndAGivenValueStays() {
		Properties none = new Properties(); // 진짜 시스템 속성 대신
		TetrisApp.recordHumanGames(none);
		assertEquals("runs/human/data/games", none.getProperty("tetris.records"), "속성이 없으면 사람 판 기록을 켠다");
		assertEquals(Path.of("runs", "human", "data", "games"), Path.of(TetrisApp.HUMAN_RECORDS));
		// 판은 그 값을 작업 폴더 기준의 기록 폴더로 읽는다 (GameSession.recordsDir 와 같은 풀이)
		Path games = GameSession.recordsDir(none.getProperty("tetris.records"));
		assertEquals(Path.of(TetrisApp.HUMAN_RECORDS), games, "게임 실행은 사람 대 AI 판을 남긴다");
		assertFalse(games.isAbsolute(), "작업 폴더 기준 (gradlew run 은 저장소 폴더)");
		for (String given : new String[] { "off", "OFF", "build/my-records", "" }) {
			Properties p = new Properties();
			p.setProperty("tetris.records", given);
			TetrisApp.recordHumanGames(p);
			assertEquals(given, p.getProperty("tetris.records"), "준 값('" + given + "')은 그대로 (빈 값은 끔)");
		}
		assertNull(GameSession.recordsDir("off"), "-Dtetris.records=off 는 그대로 끈다");
		assertNull(GameSession.recordsDir(""));
		assertEquals(Path.of("build/my-records"), GameSession.recordsDir("build/my-records"));
	}

	@Test
	void theRealPropertyGivenAsOffStaysOff() {
		// 진짜 시스템 속성으로 recordHumanGames() 를 거친다. 값은 off 로만 둔다: 이 JVM 의 다른 시험이 사람 판 폴더(runs/human)에
		// 쓰는 일이 잠깐이라도 없게, 속성을 지운 채로 부르지 않는다(지우면 게임처럼 켜진다).
		String old = System.getProperty("tetris.records");
		try {
			System.setProperty("tetris.records", "off");
			TetrisApp.recordHumanGames();
			assertEquals("off", System.getProperty("tetris.records"));
			assertNull(GameSession.recordsDir(), "-Dtetris.records=off 로 켠 게임은 남기지 않는다");
		} finally {
			if (old == null) {
				System.clearProperty("tetris.records");
			} else {
				System.setProperty("tetris.records", old);
			}
		}
	}

	// ------------------------------------------------------------------ 기권 알림

	/** 기준 AI 두뇌로 AI 자리의 선택을 만든다 (모델 묶음 없이, 기준 AI 로 "대신" 두는 것은 아니므로 전적에 남는다). */
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

	private static GameSession session(GameSession.Settings st, long seed, SaveData save) {
		return new GameSession(st, seed, save, x -> {
		}, choices(st, seed), null, System::nanoTime);
	}

	@Test
	void leavingAnUnfinishedVersusGameTellsTheMenuWhetherTheLossWasRecorded() {
		AiCatalog catalog = AiCatalog.load(Path.of("no-models-here"));
		AiCatalog.Difficulty level = catalog.difficulties().get(4); // 빠른 AI: 가만히 있는 사람이 금방 진다
		GameSession.Settings st = GameSession.Settings.versus(level, catalog.personas().get(0));
		SaveData save = SaveData.inMemory();

		GameSession playing = session(st, 7L, save);
		while (playing.match.time() < 2_000) {
			playing.advance(null); // 카운트다운이 끝나고 2초 둔 판
		}
		assertEquals(TetrisApp.FORFEIT_RECORDED, TetrisApp.forfeit(playing, save));
		assertTrue(playing.forfeited());
		assertEquals(1, save.records().versus(level.id()).losses(), "기권은 패배로 남는다");

		GameSession counting = session(st, 8L, save); // 카운트다운 중에 나감: 남기지 않는다
		assertEquals(TetrisApp.FORFEIT_NOT_RECORDED, TetrisApp.forfeit(counting, save));
		assertEquals(1, save.records().versus(level.id()).losses());

		GameSession finished = session(st, 9L, save); // 결과 카드에서 "메뉴로": 기권이 아니다
		while (!finished.over()) {
			finished.advance(null);
		}
		finished.finishIfOver();
		long losses = save.records().versus(level.id()).losses();
		assertNull(TetrisApp.forfeit(finished, save));
		assertEquals(losses, save.records().versus(level.id()).losses(), "끝난 판을 떠나도 더 남기지 않는다");

		SaveData soloSave = SaveData.inMemory();
		GameSession solo = session(GameSession.Settings.solo(2000, GameModel.Scheduler.NONE), 10L, soloSave);
		while (solo.match.time() < 2_000) {
			solo.advance(null);
		}
		assertNull(TetrisApp.forfeit(solo, soloSave), "혼자 연습은 알리지 않는다 (그때까지의 기록은 남는다)");
		assertEquals(PlayRecords.Versus.NONE, soloSave.records().versus(level.id()));
	}

	@Test
	void theNoticeSaysRecordedOnlyWhenALossWasAdded() {
		PlayRecords.Versus before = new PlayRecords.Versus(3, 2, 0, 1, 2, 41_000);
		assertEquals(TetrisApp.FORFEIT_RECORDED,
				TetrisApp.forfeitNotice(before, before.plus(PlayRecords.Outcome.LOSS, 9_000)));
		assertEquals(TetrisApp.FORFEIT_NOT_RECORDED, TetrisApp.forfeitNotice(before, before), "기준 AI 판, 카운트다운 중");
		assertEquals("기권 · 패배로 기록했어요", TetrisApp.FORFEIT_RECORDED);
		assertEquals("기권했어요", TetrisApp.FORFEIT_NOT_RECORDED);
	}

	// ------------------------------------------------------------------ 켤 때

	@Test
	void theTimerResolutionThreadIsADaemonThatSleepsUntilInterrupted() throws InterruptedException {
		assertNull(TetrisApp.keepFineTimer("Linux"), "Windows 가 아니면 아무것도 하지 않는다");
		assertNull(TetrisApp.keepFineTimer("Mac OS X"));
		Thread t = TetrisApp.keepFineTimer("Windows 11");
		assertNotNull(t);
		try {
			assertEquals("timer-resolution", t.getName());
			assertTrue(t.isDaemon(), "창을 닫으면 JVM 이 끝나야 한다");
			assertTrue(t.isAlive());
		} finally {
			t.interrupt();
			t.join(5_000);
		}
		assertFalse(t.isAlive(), "끼어들면 끝난다");
	}

	@Test
	void theOnnxRuntimeDllsAreUnpackedOnceAndReused(@TempDir Path tmp) throws Exception {
		Path jar = Files.createDirectories(tmp.resolve("jar")); // 진짜 jar 대신 작은 가짜 DLL 셋
		for (String name : TetrisApp.ORT_DLLS) {
			Files.writeString(jar.resolve(name), "fake " + name, StandardCharsets.UTF_8);
		}
		Function<String, URL> resource = path -> {
			assertTrue(path.startsWith("/ai/onnxruntime/native/win-x64/"), path);
			Path p = jar.resolve(path.substring(path.lastIndexOf('/') + 1));
			try {
				return Files.exists(p) ? p.toUri().toURL() : null;
			} catch (MalformedURLException e) {
				throw new IllegalStateException(e);
			}
		};
		Path dir = tmp.resolve("LocalAppData").resolve("TetrisBattle").resolve("onnxruntime-x");
		Properties props = new Properties();
		assertTrue(TetrisApp.shareOrtNatives(dir, resource, props));
		assertEquals(dir.toString(), props.getProperty(ORT_PATH), "ONNX Runtime 이 이 폴더에서 DLL 을 불러온다");
		for (String name : TetrisApp.ORT_DLLS) {
			assertArrayEquals(Files.readAllBytes(jar.resolve(name)), Files.readAllBytes(dir.resolve(name)), name);
		}

		// 크기가 같으면 다시 쓰지 않는다 (다른 게임 창이 쓰는 중인 DLL 을 건드리지 않게)
		Path first = dir.resolve(TetrisApp.ORT_DLLS.get(0));
		byte[] sameSize = Files.readAllBytes(first);
		sameSize[0] ^= 1;
		Files.write(first, sameSize);
		assertTrue(TetrisApp.shareOrtNatives(dir, resource, new Properties()));
		assertArrayEquals(sameSize, Files.readAllBytes(first), "크기가 같으면 그대로");
		// 크기가 다르면(다른 버전, 망가진 파일) 다시 쓴다
		Files.write(first, new byte[1]);
		assertTrue(TetrisApp.shareOrtNatives(dir, resource, new Properties()));
		assertArrayEquals(Files.readAllBytes(jar.resolve(TetrisApp.ORT_DLLS.get(0))), Files.readAllBytes(first));
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(TetrisApp.ORT_DLLS.size(), files.count(), "임시 파일이 남지 않는다");
		}

		// 하나라도 없거나 폴더를 만들 수 없으면 경로를 두지 않는다 (ONNX Runtime 이 예전처럼 푼다)
		Properties untouched = new Properties();
		Path elsewhere = tmp.resolve("elsewhere");
		assertFalse(TetrisApp.shareOrtNatives(elsewhere, p -> p.endsWith("/onnxruntime4j_jni.dll") ? null : resource.apply(p),
				untouched));
		assertFalse(Files.exists(elsewhere), "없는 DLL 이 있으면 아무것도 만들지 않는다");
		Path aFile = Files.writeString(tmp.resolve("a-file"), "x", StandardCharsets.UTF_8);
		assertFalse(TetrisApp.shareOrtNatives(aFile.resolve("sub"), resource, untouched));
		assertNull(untouched.getProperty(ORT_PATH));
	}

	@Test
	void theFolderNameFollowsTheOnnxRuntimeJarOnTheClassPath() {
		String v = TetrisApp.ortVersion();
		assertNotNull(v, "jar 의 MANIFEST 에 버전이 있다");
		assertTrue(v.matches("\\d+\\.\\d+\\.\\d+.*"), v);
		assertNotNull(TetrisApp.class.getResource("/ai/onnxruntime/native/win-x64/onnxruntime.dll"),
				"이 jar 에 Windows DLL 이 들어 있다 (모든 PC 에서 같은 jar)");
	}
}
