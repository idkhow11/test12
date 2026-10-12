package kr.ac.jbnu.se.tetris.settings;

import static kr.ac.jbnu.se.tetris.settings.PlayRecords.Outcome.DRAW;
import static kr.ac.jbnu.se.tetris.settings.PlayRecords.Outcome.LOSS;
import static kr.ac.jbnu.se.tetris.settings.PlayRecords.Outcome.WIN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 저장 파일: 기본값, 되읽기, 망가진 파일, 새 버전 파일, 원자적 쓰기, 두 창이 함께 쓰기. */
class SaveDataTest {

	/** 혼자 연습 "보통(초당 3칸)·섞어서". */
	private static final String SOLO = PlayRecords.soloSetup(3000, "S-mix");

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve(SaveData.FILE_NAME);
	}

	private void write(String text) throws IOException {
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	private List<String> entries() throws IOException {
		return Files.readAllLines(file(), StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank() && !l.startsWith("#"))
				.toList();
	}

	@Test
	void missingFileGivesDefaultsAndCreatesNothing() {
		Path cfg = dir.resolve("cfg");
		SaveData d = SaveData.open(cfg);
		assertEquals(UserSettings.defaults(), d.settings());
		assertEquals(PlayRecords.empty(), d.records());
		assertNull(d.problem());
		assertFalse(d.readOnly());
		assertEquals(cfg.resolve("settings.properties"), d.file());
		assertFalse(Files.exists(cfg), "열기만 해서는 폴더를 만들지 않는다");
		d.updateSettings(s -> s);
		assertFalse(Files.exists(cfg), "바뀐 것이 없으면 쓰지 않는다");
		d.updateSettings(s -> s.withGhost(false));
		assertTrue(Files.isRegularFile(cfg.resolve("settings.properties")), "처음 저장할 때 폴더를 만든다");
	}

	@Test
	void everythingReadsBackTheSame() {
		SaveData d = SaveData.open(dir);
		KeyBindings keys = KeyBindings.defaults().assign(KeyAction.HOLD, 0, KeyEvent.VK_A)
				.assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_NUMPAD0).assign(KeyAction.ROTATE_CCW, 2, KeyEvent.VK_QUOTE);
		UserSettings s = d.updateSettings(x -> x.withKeys(keys).withDasMs(120).withArrMs(0).withGhost(false)
				.withColorBlind(true).withPatterns(true).withVolume(40).withMuted(true).withFullscreenOnStart(true)
				.withAiSpeed(130).withEffects(UserSettings.EFFECTS_HIGH).withShake(false).withCalmFlash(true)
				.withHeartbeat(false)
				.withMenu(x.menu().with(MenuMemory.MODE, MenuMemory.MODE_SOLO).with(MenuMemory.VERSUS_LEVEL, 4)
						.with(MenuMemory.VERSUS_PERSONA, "tspin").with(MenuMemory.SOLO_SPEED, 2.5)
						.with(MenuMemory.SAND_LEVEL, "한글 값 = 되나?")));
		PlayRecords r = d.updateRecords(x -> x.withVersus(4, WIN, 61_000).withVersus(4, LOSS, 30_000)
				.withVersus(1, DRAW, 100_000).withSolo(SOLO, 42, 185_000).withSolo("all", 1, 2).withSand(12_340, 4));
		assertEquals(s, d.settings());
		assertEquals(r, d.records());

		SaveData again = SaveData.open(dir);
		assertEquals(s, again.settings());
		assertEquals(r, again.records());
		assertNull(again.problem());
		assertTrue(again.warnings().isEmpty(), again.warnings().toString());
	}

	@Test
	void defaultSettingsAreNotWrittenButKeysAreWrittenAsAWhole() throws IOException {
		SaveData d = SaveData.open(dir);
		d.updateRecords(r -> r.withSolo(SOLO, 3, 1_000));
		assertEquals(List.of("format=tetris-save-1", "record.solo.3000_S-mix.games=1", "record.solo.3000_S-mix.best_lines=3",
				"record.solo.3000_S-mix.best_time_ms=1000", "record.solo.3000_S-mix.total_lines=3"), entries());
		d.updateSettings(s -> s.withDasMs(150).withKeys(s.keys().assign(KeyAction.HOLD, 0, KeyEvent.VK_A)));
		List<String> e = entries();
		assertTrue(e.contains("das_ms=150"), e.toString());
		assertFalse(e.stream().anyMatch(l -> l.startsWith("arr_ms")), "기본값과 같은 ARR 은 적지 않는다");
		assertEquals(KeyAction.values().length, e.stream().filter(l -> l.startsWith("key.")).count(), "키는 모두 적는다");
		assertTrue(e.contains("key.hold=A,SHIFT"), e.toString());
		assertTrue(e.contains("key.rotate_cw=UP,X"), e.toString());
	}

	@Test
	void theFileHoldsOnlyKnownNamesAndNoPersonalData() throws IOException {
		SaveData d = SaveData.open(dir);
		d.updateSettings(s -> s.withVolume(10).withKeys(s.keys().assign(KeyAction.PAUSE, 0, KeyEvent.VK_F1))
				.withMenu(s.menu().with(MenuMemory.MODE, MenuMemory.MODE_VERSUS).with(MenuMemory.VERSUS_LEVEL, 5)));
		d.updateRecords(r -> r.withVersus(5, WIN, 70_000).withSolo(SOLO, 10, 20_000).withSand(300, 2));
		Pattern known = Pattern.compile("(format|das_ms|arr_ms|ghost|color_blind|patterns|volume|muted|fullscreen_on_start"
				+ "|key\\.[a-z_]+|menu\\.[a-z0-9._-]+"
				+ "|record\\.(versus\\.[0-9]+\\.[a-z_]+|solo\\.[A-Za-z0-9_-]+\\.[a-z_]+|sand\\.[a-z_]+))"
				+ "=[A-Za-z0-9_.,\\-]+");
		for (String l : entries()) {
			assertTrue(known.matcher(l).matches(), l);
		}
		String text = Files.readString(file(), StandardCharsets.UTF_8);
		assertFalse(text.contains(System.getProperty("user.home")), "홈 폴더 경로를 적지 않는다");
		assertFalse(text.contains(dir.toString()), "설정 폴더 경로를 적지 않는다");
	}

	@Test
	void temporaryFilesLeftByACrashAreRemovedWhenOpening() throws IOException {
		// 이름을 바꿔 끼우기 전에 꺼지면 임시 파일이 남는다. 오래된 것만 지운다(다른 창이 막 쓰는 중인 것은 둔다)
		Path old = dir.resolve(SaveData.FILE_NAME + ".123.tmp");
		Path fresh = dir.resolve(SaveData.FILE_NAME + ".456.tmp");
		Path other = dir.resolve("notes.tmp");
		for (Path p : List.of(old, fresh, other)) {
			Files.writeString(p, "x", StandardCharsets.UTF_8);
		}
		long longAgo = System.currentTimeMillis() - SaveData.STALE_TMP_MS - 60_000;
		Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.fromMillis(longAgo));
		Files.setLastModifiedTime(other, java.nio.file.attribute.FileTime.fromMillis(longAgo));
		SaveData.open(dir);
		assertFalse(Files.exists(old));
		assertTrue(Files.exists(fresh));
		assertTrue(Files.exists(other), "이 게임의 임시 파일이 아니다");
	}

	@Test
	void writesLeaveNoTemporaryFiles() throws IOException {
		SaveData d = SaveData.open(dir);
		for (int i = 0; i < 20; i++) {
			int v = i;
			d.updateSettings(s -> s.withVolume(v * 5));
			d.updateRecords(r -> r.withSolo(SOLO, v, v * 1_000L));
		}
		try (Stream<Path> files = Files.list(dir)) {
			assertEquals(List.of(file()), files.toList());
		}
		assertEquals(95, SaveData.open(dir).settings().volume());
		assertEquals(20, SaveData.open(dir).records().solo(SOLO).games());
	}

	@Test
	void corruptFileFallsBackToDefaultsAndIsKeptAside() throws IOException {
		// properties 로도 읽을 수 없는 파일: 잘못된 유니코드 이스케이프 (역슬래시 u 다음에 16진수가 아님)
		String broken = "format=tetris-save-1\nghost=false\nvolume=\\" + "uZZZZ\n";
		write(broken);
		SaveData d = SaveData.open(dir);
		assertEquals(UserSettings.defaults(), d.settings());
		assertNotNull(d.problem());
		assertFalse(d.readOnly(), "다음 저장 때 새로 쓴다");
		Path aside = dir.resolve(SaveData.FILE_NAME + SaveData.BROKEN_SUFFIX);
		assertEquals(broken, Files.readString(aside, StandardCharsets.UTF_8), "옛 파일을 옆에 남긴다");
		d.updateSettings(s -> s.withGhost(false));
		SaveData again = SaveData.open(dir);
		assertFalse(again.settings().ghost());
		assertNull(again.problem());
	}

	@Test
	void binaryJunkAndForeignFilesAreNotOurs() throws IOException {
		Files.write(file(), new byte[] { 0, (byte) 0xFF, (byte) 0xFE, 'x', '=', '1', '\n', (byte) 0x80 });
		SaveData junk = SaveData.open(dir);
		assertEquals(UserSettings.defaults(), junk.settings());
		assertNotNull(junk.problem());
		assertTrue(Files.exists(dir.resolve(SaveData.FILE_NAME + SaveData.BROKEN_SUFFIX)));

		write("volume=10\nghost=false\n"); // format 줄이 없다: 이 게임이 쓴 파일이 아니다
		SaveData foreign = SaveData.open(dir);
		assertEquals(UserSettings.defaults(), foreign.settings());
		assertNotNull(foreign.problem());
	}

	@Test
	void emptyFileIsLikeNoFile() throws IOException {
		write("  \n\n");
		SaveData d = SaveData.open(dir);
		assertEquals(UserSettings.defaults(), d.settings());
		assertNull(d.problem());
		assertFalse(Files.exists(dir.resolve(SaveData.FILE_NAME + SaveData.BROKEN_SUFFIX)));
	}

	@Test
	void badValuesFallBackOneByOne() throws IOException {
		write(String.join("\n", "# 사람이 고친 파일", "format=tetris-save-1",
				"das_ms=abc", // 수가 아님 → 170
				"arr_ms=999", // 범위 밖 → 150
				"ghost=maybe", // true/false 가 아님 → true
				"color_blind=TRUE", // 대소문자는 가리지 않는다
				"volume=-20", // 범위 밖 → 0
				"muted=  false  ",
				"key.hold=a, windows ,NOPE,A", // 쓸 수 없는 키·모르는 키는 버리고 겹친 A 는 하나로 → [A]
				"key.jump=SPACE", // 모르는 동작
				"menu.mode=solo",
				"menu.Bad=x", // 쓸 수 없는 이름
				"record.solo.3000_S-mix.games=3",
				"record.solo.3000_S-mix.best_lines=12x", // 수가 아님
				"record.versus.0.wins=5", // 난이도 0 은 없다
				"record.versus.3.wins=2",
				"something=else", ""));
		SaveData d = SaveData.open(dir);
		UserSettings s = d.settings();
		assertEquals(170, s.dasMs());
		assertEquals(150, s.arrMs());
		assertTrue(s.ghost());
		assertTrue(s.colorBlind());
		assertEquals(0, s.volume());
		assertFalse(s.muted());
		assertEquals(List.of(KeyEvent.VK_A), s.keys().keys(KeyAction.HOLD));
		assertEquals(KeyBindings.defaults().keys(KeyAction.HARD_DROP), s.keys().keys(KeyAction.HARD_DROP));
		assertEquals(MenuMemory.EMPTY.with(MenuMemory.MODE, MenuMemory.MODE_SOLO), s.menu());
		assertEquals(new PlayRecords.Solo(3, 0, 0, 0), d.records().solo(SOLO));
		assertEquals(2, d.records().versus(3).wins());
		assertEquals(1, d.records().versus().size());
		assertNull(d.problem(), "파일은 이 게임의 것이므로 문제로 보이지 않는다");
		assertFalse(d.readOnly());
		// 버린 항목: das_ms, arr_ms(맞춤), ghost, volume(맞춤), windows, NOPE, key.jump, menu.Bad, best_lines, versus.0, something
		assertEquals(11, d.warnings().size(), d.warnings().toString());
	}

	@Test
	void overlappingKeysInTheFileResetOnlyTheKeys() throws IOException {
		write("format=tetris-save-1\nkey.hold=SPACE\nkey.hard_drop=SPACE\nghost=false\n");
		SaveData d = SaveData.open(dir);
		assertEquals(KeyBindings.defaults(), d.settings().keys());
		assertFalse(d.settings().ghost(), "다른 설정은 그대로");
	}

	@Test
	void actionsMissingFromTheFileGetTheirFreeDefaultKeys() throws IOException {
		write("format=tetris-save-1\nkey.hold=C\n");
		KeyBindings k = SaveData.open(dir).settings().keys();
		assertEquals(List.of(KeyEvent.VK_C), k.keys(KeyAction.HOLD));
		assertNull(k.actionOf(KeyEvent.VK_SHIFT));
		assertEquals(KeyBindings.defaults().keys(KeyAction.ROTATE_CW), k.keys(KeyAction.ROTATE_CW));

		// 적지 않은 하드드롭의 기본 키 Space 를 홀드가 쓰면 남는 키(F1)를 준다. 홀드의 Space 는 그대로
		write("format=tetris-save-1\nkey.hold=SPACE\n");
		KeyBindings spare = SaveData.open(dir).settings().keys();
		assertEquals(List.of(KeyEvent.VK_SPACE), spare.keys(KeyAction.HOLD));
		assertEquals(List.of(KeyEvent.VK_F1), spare.keys(KeyAction.HARD_DROP));
		assertEquals(KeyBindings.defaults().keys(KeyAction.RETRY), spare.keys(KeyAction.RETRY));
	}

	@Test
	void aFileFromANewerGameIsReadButNeverOverwritten() throws IOException {
		String newer = "format=tetris-save-99\nghost=false\nfuture.thing=1\n";
		write(newer);
		SaveData d = SaveData.open(dir);
		assertFalse(d.settings().ghost(), "아는 항목은 읽는다");
		assertTrue(d.readOnly());
		assertNotNull(d.problem());
		assertEquals(10, d.updateSettings(s -> s.withVolume(10)).volume(), "이번 실행 동안은 바뀐다");
		d.updateRecords(r -> r.withSolo(SOLO, 1, 1));
		assertFalse(d.save());
		assertEquals(newer, Files.readString(file(), StandardCharsets.UTF_8), "파일은 그대로");
	}

	@Test
	void tooLargeFilesAreLeftAlone() throws IOException {
		byte[] big = new byte[(int) SaveData.MAX_BYTES + 1];
		java.util.Arrays.fill(big, (byte) '#');
		Files.write(file(), big);
		SaveData d = SaveData.open(dir);
		assertEquals(UserSettings.defaults(), d.settings());
		assertTrue(d.readOnly());
		assertNotNull(d.problem());
		d.updateSettings(s -> s.withGhost(false));
		assertEquals(big.length, Files.size(file()));
	}

	@Test
	void notepadByteOrderMarkIsIgnored() throws IOException {
		write("﻿format=tetris-save-1\nvolume=30\n");
		SaveData d = SaveData.open(dir);
		assertEquals(30, d.settings().volume());
		assertNull(d.problem());
		assertTrue(d.warnings().isEmpty(), d.warnings().toString());
	}

	@Test
	void twoWindowsDoNotLoseEachOthersChanges() {
		SaveData a = SaveData.open(dir);
		SaveData b = SaveData.open(dir);
		a.updateRecords(r -> r.withSolo(SOLO, 10, 60_000));
		b.updateRecords(r -> r.withSolo(SOLO, 20, 90_000)); // b 는 a 가 쓴 파일을 다시 읽고 그 위에 더한다
		PlayRecords.Solo solo = SaveData.open(dir).records().solo(SOLO);
		assertEquals(new PlayRecords.Solo(2, 20, 90_000, 30), solo);
		a.updateSettings(s -> s.withGhost(false));
		b.updateSettings(s -> s.withVolume(30));
		UserSettings s = SaveData.open(dir).settings();
		assertFalse(s.ghost());
		assertEquals(30, s.volume());
		assertEquals(solo, a.records().solo(SOLO), "a 도 다시 읽어 b 의 기록을 안다");
	}

	@Test
	void updatesFromSeveralThreadsAreAllKept() throws Exception {
		// 화면 스레드(설정)와 게임 진행 스레드(판이 끝난 기록)가 함께 불러도 하나도 빠지지 않는다
		SaveData d = SaveData.open(dir);
		int threads = 4;
		int each = 25;
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
		try {
			List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
			for (int t = 0; t < threads; t++) {
				int id = t;
				done.add(pool.submit(() -> {
					for (int i = 0; i < each; i++) {
						int volume = id * 10 + i % 10;
						d.updateRecords(r -> r.withSolo(SOLO, 1, 1_000));
						d.updateSettings(s -> s.withVolume(volume));
					}
				}));
			}
			for (java.util.concurrent.Future<?> f : done) {
				f.get(60, java.util.concurrent.TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}
		assertEquals(threads * each, d.records().solo(SOLO).games());
		assertEquals(threads * each, SaveData.open(dir).records().solo(SOLO).games(), "파일에도 모두 있다");
		assertEquals(d.settings(), SaveData.open(dir).settings());
	}

	@Test
	void aFolderInPlaceOfTheFileIsLeftAlone() throws IOException {
		Files.createDirectories(file());
		SaveData d = SaveData.open(dir);
		assertEquals(UserSettings.defaults(), d.settings());
		assertTrue(d.readOnly(), "읽지 못한 것은 덮어쓰지 않는다");
		assertNotNull(d.problem());
		assertFalse(d.updateSettings(s -> s.withGhost(false)).ghost(), "이번 실행 동안은 바뀐다");
		assertTrue(Files.isDirectory(file()));
	}

	@Test
	void aShortLockWhenOpeningIsWaitedOut() throws IOException {
		// 백신·클라우드 동기화가 파일을 잠깐 잡고 있다: 조금 기다렸다 다시 읽는다
		SaveData.open(dir).updateSettings(s -> s.withGhost(false).withDasMs(120));
		AtomicInteger locked = new AtomicInteger(2);
		SaveData d = SaveData.open(dir, p -> {
			if (locked.getAndDecrement() > 0) {
				throw new AccessDeniedException(p.toString());
			}
			return SaveData.readFile(p);
		});
		assertFalse(d.settings().ghost(), "기다렸다가 읽었다");
		assertEquals(120, d.settings().dasMs());
		assertFalse(d.readOnly());
		assertNull(d.problem());
		assertTrue(d.persistent());
	}

	@Test
	void aFileUnreadableWhenOpeningIsReadOnTheNextChangeAndNothingIsLost() throws IOException {
		SaveData first = SaveData.open(dir);
		first.updateSettings(s -> s.withGhost(false).withDasMs(120));
		first.updateRecords(r -> r.withSolo(SOLO, 10, 60_000));
		AtomicBoolean locked = new AtomicBoolean(true);
		AtomicInteger reads = new AtomicInteger();
		SaveData d = SaveData.open(dir, p -> {
			reads.incrementAndGet();
			if (locked.get()) {
				throw new FileSystemException(p.toString(), null, "다른 프로세스가 사용 중 (시험)");
			}
			return SaveData.readFile(p);
		});
		assertEquals(4, reads.get(), "열 때 세 번 더 읽어 본다");
		assertEquals(UserSettings.defaults(), d.settings(), "읽지 못했으면 기본값으로 시작한다");
		assertTrue(d.readOnly());
		assertFalse(d.persistent());
		assertNotNull(d.problem());

		String before = Files.readString(file(), StandardCharsets.UTF_8);
		assertEquals(30, d.updateSettings(s -> s.withVolume(30)).volume(), "이번 실행 동안은 바뀐다");
		d.updateRecords(r -> r.withSolo(SOLO, 5, 2_000));
		assertEquals(before, Files.readString(file(), StandardCharsets.UTF_8), "읽지 못한 파일은 덮어쓰지 않는다");

		locked.set(false); // 잠김이 풀렸다
		UserSettings s = d.updateSettings(x -> x.withMuted(true));
		assertFalse(s.ghost(), "파일의 설정 위에");
		assertEquals(120, s.dasMs());
		assertEquals(30, s.volume(), "읽지 못한 동안 바꾼 것을 다시 한다");
		assertTrue(s.muted(), "그리고 지금 바꾼 것");
		assertEquals(new PlayRecords.Solo(2, 10, 60_000, 15), d.records().solo(SOLO), "파일의 기록 + 그동안 한 판");
		assertFalse(d.readOnly());
		assertTrue(d.persistent());
		assertNull(d.problem());
		SaveData again = SaveData.open(dir);
		assertEquals(s, again.settings(), "파일에도 모두 있다");
		assertEquals(d.records(), again.records());
	}

	@Test
	void aNewerFileFoundAfterTheLockIsStillNotOverwritten() throws IOException {
		String newer = "format=tetris-save-99\nghost=false\n";
		write(newer);
		AtomicBoolean locked = new AtomicBoolean(true);
		SaveData d = SaveData.open(dir, p -> {
			if (locked.get()) {
				throw new AccessDeniedException(p.toString());
			}
			return SaveData.readFile(p);
		});
		d.updateSettings(s -> s.withVolume(30));
		locked.set(false);
		UserSettings s = d.updateSettings(x -> x.withDasMs(100));
		assertFalse(s.ghost(), "읽은 값 위에");
		assertEquals(30, s.volume());
		assertEquals(100, s.dasMs());
		assertTrue(d.readOnly(), "새 버전의 파일은 그래도 덮어쓰지 않는다");
		assertEquals(newer, Files.readString(file(), StandardCharsets.UTF_8));
	}

	@Test
	void aLockThatNeverEndsStopsRememberingAfterAWhile() throws IOException {
		SaveData.open(dir).updateSettings(s -> s.withGhost(false));
		AtomicBoolean locked = new AtomicBoolean(true);
		SaveData d = SaveData.open(dir, p -> {
			if (locked.get()) {
				throw new AccessDeniedException(p.toString());
			}
			return SaveData.readFile(p);
		});
		for (int i = 0; i <= SaveData.MAX_PENDING; i++) {
			int v = i % 100;
			d.updateSettings(s -> s.withVolume(v));
		}
		locked.set(false);
		d.updateSettings(s -> s.withArrMs(10));
		assertTrue(d.readOnly(), "너무 오래 고쳤으면 다시 읽지 않는다(이번 실행은 저장하지 않음)");
		assertTrue(d.settings().ghost(), "이번 실행의 값을 그대로 쓴다");
		assertEquals(10, d.settings().arrMs());
		assertFalse(SaveData.open(dir).settings().ghost(), "파일은 그대로");
	}

	@Test
	void deletingTheFileStartsOver() throws IOException {
		SaveData d = SaveData.open(dir);
		d.updateRecords(r -> r.withSolo(SOLO, 10, 60_000).withSolo(SOLO, 5, 1_000));
		d.updateSettings(s -> s.withVolume(20));
		Files.delete(file());
		d.updateRecords(r -> r.withSolo(SOLO, 1, 500));
		assertEquals(1, d.records().solo(SOLO).games(), "지워서 처음으로 돌리려 한 것으로 본다");
		assertEquals(UserSettings.DEFAULT_VOLUME, d.settings().volume());
	}

	@Test
	void inMemoryNeverTouchesTheDisk() {
		SaveData m = SaveData.inMemory();
		assertNull(m.file());
		assertFalse(m.updateSettings(s -> s.withGhost(false)).ghost());
		assertEquals(1, m.updateRecords(r -> r.withSolo(SOLO, 1, 1)).solo(SOLO).games());
		assertTrue(m.save());
		assertNull(m.problem());
		assertNull(SaveData.open(null).file());
	}

	@Test
	void updateGivesBackTheStoredValue() {
		SaveData d = SaveData.open(dir);
		UserSettings before = d.settings();
		assertSame(before, d.updateSettings(s -> s), "바뀐 것이 없으면 그대로");
		UserSettings after = d.updateSettings(s -> s.withArrMs(20));
		assertSame(after, d.settings());
	}

	@Test
	void aFailedSaveIsReportedButTheGameGoesOn() throws IOException {
		Path notADir = dir.resolve("plain-file");
		Files.writeString(notADir, "x", StandardCharsets.UTF_8);
		SaveData d = SaveData.open(notADir); // 폴더 자리에 파일이 있다: 읽기나 쓰기가 실패한다
		UserSettings s = d.updateSettings(x -> x.withGhost(false));
		assertFalse(s.ghost(), "이번 실행 동안은 바뀐다");
		assertFalse(d.save());
		assertNotNull(d.problem());
		assertEquals("x", Files.readString(notADir, StandardCharsets.UTF_8));
	}

	@Test
	void warningsAfterOpeningReachTheReportOnceEach() throws IOException {
		// 열 때의 경고 (망가진 값) 는 알리기 시작할 때 바로
		write("format=tetris-save-1\ndas_ms=zzz\n");
		SaveData broken = SaveData.open(dir);
		List<String> heard = new ArrayList<>();
		broken.reportWarnings(heard::add);
		assertFalse(heard.isEmpty(), "열 때의 경고: " + broken.warnings());
		assertEquals(broken.warnings().stream().distinct().toList(), heard);

		// 연 뒤의 저장 실패: 생길 때마다, 같은 까닭은 한 번만 (화면에는 한 줄, 표준 오류에는 까닭)
		Path notADir = dir.resolve("plain-file");
		Files.writeString(notADir, "x", StandardCharsets.UTF_8);
		SaveData d = SaveData.open(notADir);
		List<String> later = new ArrayList<>();
		d.reportWarnings(later::add);
		int atOpen = later.size();
		d.updateSettings(s -> s.withGhost(false));
		assertEquals(atOpen + 1, later.size(), later.toString());
		assertTrue(later.get(later.size() - 1).startsWith("저장 실패"), later.toString());
		d.updateSettings(s -> s.withDasMs(120));
		assertEquals(atOpen + 1, later.size(), "같은 까닭은 다시 알리지 않는다");
		assertTrue(d.warnings().size() > later.size(), "경고 목록에는 모두 남는다");
	}

	@Test
	void theSameStateAlwaysWritesTheSameBytes() throws IOException {
		SaveData d = SaveData.open(dir);
		d.updateRecords(r -> r.withVersus(2, WIN, 1_000).withSand(10, 1));
		byte[] first = Files.readAllBytes(file());
		assertTrue(d.save());
		assertArrayEquals(first, Files.readAllBytes(file()), "시각 같은 바뀌는 값을 적지 않는다");
	}
}
