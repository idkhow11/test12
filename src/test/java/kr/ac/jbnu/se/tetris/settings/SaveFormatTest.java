package kr.ac.jbnu.se.tetris.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** 저장 파일의 글 형식 {@code tetris-save-1}: 이스케이프, 버전, 손으로 쓴 파일 읽기. */
class SaveFormatTest {

	private static List<String> entries(String text) {
		return text.lines().filter(l -> !l.isBlank() && !l.startsWith("#")).toList();
	}

	@Test
	void escapedTextReadsBackThroughJavaProperties() throws IOException {
		String[] values = { "a=b", "x:y", "#hash", "!bang", "back\\slash", " lead", "trail ", "한글 값", "tab\there",
				"new\nline", "cr\rlf", "é", "emoji 😀", "", "  ", "\\" };
		for (String v : values) {
			Properties p = new Properties();
			p.load(new StringReader("k=" + SaveFormat.escape(v, false) + "\n"));
			assertEquals(v, p.getProperty("k"), "[" + v + "]");
			for (char c : SaveFormat.escape(v, false).toCharArray()) {
				assertTrue(c >= 0x20 && c < 0x7f, "ASCII 글자만 쓴다: [" + v + "]");
			}
		}
		Properties p = new Properties();
		p.load(new StringReader(SaveFormat.escape("a b=c", true) + "=v\n"));
		assertEquals("v", p.getProperty("a b=c"), "이름의 공백과 = 도 이스케이프한다");
	}

	@Test
	void defaultsWriteOnlyTheFormatLine() {
		String text = SaveFormat.write(UserSettings.defaults(), PlayRecords.empty());
		assertEquals(List.of("format=tetris-save-1"), entries(text));
		assertTrue(text.startsWith("# Tetris Battle"), "맨 위에 무엇인지 적는다");
		assertTrue(text.contains("das_ms=170"), "주석에 기본값을 적어 둔다");
		assertTrue(text.contains("patterns=false"), text);
		assertFalse(text.contains("\r"), "줄바꿈은 LF");
	}

	@Test
	void writingIsStableAndOrdered() {
		UserSettings s = UserSettings.defaults().withMuted(true).withDasMs(140)
				.withMenu(MenuMemory.EMPTY.with(MenuMemory.VERSUS_PERSONA, "attacker").with(MenuMemory.MODE, "versus"));
		PlayRecords r = PlayRecords.empty().withVersus(5, PlayRecords.Outcome.LOSS, 10).withVersus(2,
				PlayRecords.Outcome.WIN, 20);
		String a = SaveFormat.write(s, r);
		assertEquals(a, SaveFormat.write(s, r));
		assertEquals(List.of("format=tetris-save-1", "das_ms=140", "muted=true", "menu.mode=versus",
				"menu.versus.persona=attacker", "record.versus.2.wins=1", "record.versus.2.losses=0",
				"record.versus.2.draws=0", "record.versus.2.streak=1", "record.versus.2.best_streak=1",
				"record.versus.2.fastest_win_ms=20", "record.versus.5.wins=0", "record.versus.5.losses=1",
				"record.versus.5.draws=0", "record.versus.5.streak=0", "record.versus.5.best_streak=0",
				"record.versus.5.fastest_win_ms=0"), entries(a));
	}

	@Test
	void newSettingsAreWrittenOnlyWhenChanged() {
		UserSettings s = UserSettings.defaults().withAiSpeed(80).withEffects(UserSettings.EFFECTS_LOW).withShake(false)
				.withCalmFlash(true).withHeartbeat(false);
		String text = SaveFormat.write(s, PlayRecords.empty());
		assertEquals(List.of("format=tetris-save-1", "ai_speed=80", "effects=1", "shake=false", "calm_flash=true",
				"heartbeat=false"), entries(text));
		assertTrue(text.contains("ai_speed=100 (50~150, 10 단위)"), "주석에 기본값과 범위를 적어 둔다");
		assertEquals(s, SaveFormat.read(text).settings());
	}

	@Test
	void aFileWrittenBeforeTheNewSettingsLoadsWithTheirDefaults() {
		// 10-06 형식 그대로의 파일 (AI 속도·효과·흔들림·번쩍임·심장 소리가 생기기 전, 모래 규칙 세대를 적기 전)
		String text = String.join("\n", "# Tetris Battle 설정과 기록.", "format=tetris-save-1", "das_ms=150", "ghost=false",
				"volume=40", "menu.mode=solo", "menu.versus.level=4", "record.sand.games=1", "record.sand.best_score=500",
				"record.sand.best_multiplier=2", "");
		SaveFormat.Parsed p = SaveFormat.read(text);
		assertEquals(1, p.warnings().size(), "옛 규칙의 모래 기록만 버린다: " + p.warnings());
		UserSettings u = p.settings();
		assertEquals(150, u.dasMs());
		assertFalse(u.ghost());
		assertEquals(40, u.volume());
		assertEquals(UserSettings.DEFAULT_AI_SPEED, u.aiSpeed());
		assertEquals(UserSettings.DEFAULT_EFFECTS, u.effects());
		assertTrue(u.shake());
		assertFalse(u.calmFlash());
		assertTrue(u.heartbeat());
		assertEquals("solo", u.menu().get(MenuMemory.MODE));
		assertEquals(PlayRecords.Sand.NONE, p.records().sand(), "sand-0.2 까지의 모래 기록은 sand-0.3 에서 새로 시작한다");
	}

	/**
	 * 모래 기록에는 규칙 세대를 함께 적는다({@code record.sand.rules}). 세대가 다르거나 적혀 있지 않은(sand-0.2 까지의) 모래 기록은
	 * 점수의 뜻이 달라 버리고 새로 시작한다. 다른 기록과 설정은 그대로 읽는다.
	 */
	@Test
	void sandRecordsOfAnotherRuleGenerationStartFresh() {
		PlayRecords r = PlayRecords.empty().withSand(4_480, 2).withVersus(3, PlayRecords.Outcome.WIN, 60_000);
		String text = SaveFormat.write(UserSettings.defaults(), r);
		assertTrue(entries(text).contains("record.sand.rules=" + PlayRecords.SAND_RULES), text);
		assertEquals(r, SaveFormat.read(text).records(), "지금 세대의 기록은 그대로 읽는다");
		assertFalse(SaveFormat.write(UserSettings.defaults(), PlayRecords.empty()).contains("record.sand"),
				"모래 기록이 없으면 세대도 적지 않는다");

		SaveFormat.Parsed old = SaveFormat.read(String.join("\n", "format=tetris-save-1", "das_ms=150",
				"record.versus.3.wins=1", "record.sand.games=40", "record.sand.best_score=672887",
				"record.sand.best_multiplier=10", "record.sand.rules=2", ""));
		assertEquals(PlayRecords.Sand.NONE, old.records().sand(), "다른 세대(2)의 모래 기록은 버린다");
		assertEquals(1, old.records().versus(3).wins(), "다른 기록은 그대로");
		assertEquals(150, old.settings().dasMs());
		assertEquals(1, old.warnings().size(), old.warnings().toString());
		assertTrue(old.warnings().get(0).contains("모래 규칙"), old.warnings().toString());
		// 버린 뒤 처음 끝낸 판이 새 세대의 첫 기록이다
		PlayRecords fresh = old.records().withSand(1_000, 1);
		assertEquals(new PlayRecords.Sand(1, 1_000, 1), fresh.sand());
		assertTrue(SaveFormat.write(old.settings(), fresh).contains("record.sand.rules=" + PlayRecords.SAND_RULES));
	}

	@Test
	void oldSpectateMenuEntriesAreDroppedAndEverythingElseLoads() {
		// AI 관전이 있던 때(10-06)의 파일: 관전 모드를 마지막으로 골랐고 관전 설정이 남아 있다
		String text = String.join("\n", "format=tetris-save-1", "arr_ms=30", "menu.mode=spectate",
				"menu.spectate.left.level=4", "menu.spectate.left.persona=attacker", "menu.spectate.right.level=5",
				"menu.spectate.right.persona=defender", "menu.versus.level=3", "menu.sand.level=2",
				"record.versus.3.wins=2", "record.versus.3.losses=1", "record.versus.3.draws=0", "record.versus.3.streak=1",
				"record.versus.3.best_streak=2", "record.versus.3.fastest_win_ms=90000", "");
		SaveFormat.Parsed p = SaveFormat.read(text);
		assertTrue(p.recognized());
		assertEquals(30, p.settings().arrMs());
		MenuMemory m = p.settings().menu();
		assertEquals(List.of("sand.level", "versus.level"), List.copyOf(m.values().keySet()),
				"관전 모드와 관전 설정만 버리고 나머지 메뉴 기억은 남는다");
		assertEquals(new PlayRecords.Versus(2, 1, 0, 1, 2, 90_000), p.records().versus(3));
		assertEquals(5, p.warnings().size(), p.warnings().toString());
		assertTrue(p.warnings().stream().allMatch(w -> w.startsWith("없어진 모드의 메뉴 기억이라 버림")), p.warnings().toString());
		String again = SaveFormat.write(p.settings(), p.records());
		assertFalse(again.contains("spectate"), "다시 저장하면 관전 항목이 빠진다");
		assertTrue(SaveFormat.read(again).warnings().isEmpty());
		// 모르는 항목(새 버전 게임의 설정)도 그 항목만 버리고 읽는다
		SaveFormat.Parsed future = SaveFormat.read("format=tetris-save-1\nsome_new_setting=7\ndas_ms=160\n");
		assertEquals(160, future.settings().dasMs());
		assertEquals(List.of("모르는 항목이라 버림: some_new_setting"), future.warnings());
	}

	@Test
	void anAiSpeedOffTheStepIsMovedToTheNearestStep() {
		SaveFormat.Parsed p = SaveFormat.read("format=tetris-save-1\nai_speed=87\neffects=9\n");
		assertEquals(90, p.settings().aiSpeed());
		assertEquals(UserSettings.EFFECTS_HIGH, p.settings().effects());
		assertEquals(2, p.warnings().size(), p.warnings().toString());
		assertEquals(UserSettings.MAX_AI_SPEED, SaveFormat.read("format=tetris-save-1\nai_speed=900\n").settings().aiSpeed());
	}

	@Test
	void versionComesFromTheFormatLine() {
		assertEquals(1, SaveFormat.version("tetris-save-1"));
		assertEquals(12, SaveFormat.version(" tetris-save-12 "));
		assertEquals(0, SaveFormat.version("tetris-save-"));
		assertEquals(0, SaveFormat.version("tetris-save-x"));
		assertEquals(0, SaveFormat.version("tetris-save--1"));
		assertEquals(0, SaveFormat.version("other-1"));
		assertEquals(0, SaveFormat.version(null));
		assertEquals(0, SaveFormat.version("tetris-save-9999999999"));
	}

	@Test
	void readsAFileWrittenByHand() {
		// 사람이 메모장으로 쓴 파일: 공백, 소문자 키 이름, ":" 구분, 이어지는 줄(역슬래시)도 properties 규칙대로 읽는다
		String text = String.join("\n", "! 느낌표 주석도 된다", "format = tetris-save-1", "das_ms : 130",
				"key.hard_drop = enter", "key.retry = r, \\", "    f5", "record.sand.games=2",
				"record.sand.best_score=9000", "record.sand.best_multiplier=6",
				"record.sand.rules = " + PlayRecords.SAND_RULES, "");
		SaveFormat.Parsed p = SaveFormat.read(text);
		assertEquals(1, p.version());
		assertTrue(p.recognized());
		assertEquals(130, p.settings().dasMs());
		// 하드드롭 ← Enter. 다시 하기는 R, F5 (Enter 를 하드드롭에 주었으므로 겹치지 않는다)
		assertEquals(List.of(KeyEvent.VK_ENTER), p.settings().keys().keys(KeyAction.HARD_DROP));
		assertEquals(List.of(KeyEvent.VK_R, KeyEvent.VK_F5), p.settings().keys().keys(KeyAction.RETRY));
		assertEquals(new PlayRecords.Sand(2, 9000, 6), p.records().sand());
		assertTrue(p.warnings().isEmpty(), p.warnings().toString());
	}

	@Test
	void unreadableTextGivesDefaults() {
		SaveFormat.Parsed p = SaveFormat.read("format=tetris-save-1\nx=\\" + "u12\n");
		assertFalse(p.recognized());
		assertEquals(UserSettings.defaults(), p.settings());
		assertEquals(PlayRecords.empty(), p.records());
		assertEquals(1, p.warnings().size());
	}

	@Test
	void writtenTextReadsBackTheSame() {
		KeyBindings keys = KeyBindings.defaults().assign(KeyAction.MENU, 0, KeyEvent.VK_BACK_SPACE)
				.assign(KeyAction.FULLSCREEN, 0, KeyEvent.VK_F12).assign(KeyAction.MOVE_LEFT, 1, KeyEvent.VK_NUMPAD4);
		UserSettings s = UserSettings.defaults().withKeys(keys).withArrMs(15).withColorBlind(true).withPatterns(true)
				.withMenu(MenuMemory.EMPTY.with(MenuMemory.SAND_LEVEL, 3).with(MenuMemory.VERSUS_PERSONA, "a:b#c!d"));
		PlayRecords r = PlayRecords.empty().withSolo(PlayRecords.soloSetup(8000, "S-3"), 7, 7_000)
				.withSolo(PlayRecords.soloSetup(1000, "none"), 70, 70_000).withSand(70, 7)
				.withVersus(7, PlayRecords.Outcome.DRAW, 7);
		SaveFormat.Parsed p = SaveFormat.read(SaveFormat.write(s, r));
		assertEquals(s, p.settings());
		assertEquals(r, p.records());
		assertTrue(p.warnings().isEmpty(), p.warnings().toString());
	}

	@Test
	void soloRecordsAreWrittenPerSetup() {
		PlayRecords r = PlayRecords.empty().withSolo(PlayRecords.soloSetup(3000, "S-mix"), 42, 185_000);
		assertEquals(List.of("format=tetris-save-1", "record.solo.3000_S-mix.games=1", "record.solo.3000_S-mix.best_lines=42",
				"record.solo.3000_S-mix.best_time_ms=185000", "record.solo.3000_S-mix.total_lines=42"),
				entries(SaveFormat.write(UserSettings.defaults(), r)));
		// 설정 이름으로 쓸 수 없는 것과 모르는 칸은 버린다
		SaveFormat.Parsed p = SaveFormat.read(String.join("\n", "format=tetris-save-1", "record.solo.a b.games=1",
				"record.solo.x.wins=1", "record.solo.games=1", "record.solo.ok.games=2", ""));
		assertEquals(new PlayRecords.Solo(2, 0, 0, 0), p.records().solo("ok"));
		assertEquals(1, p.records().solo().size());
		assertEquals(3, p.warnings().size(), p.warnings().toString());
	}

	@Test
	void anActionMissingFromAnOldFileGetsAFreeKeyAndTheUsersKeysStay() {
		// 옛 버전이 쓴 파일이라 다시 하기(Enter·R)가 없고, 사용자가 Enter 를 하드드롭에, R 을 홀드에 묶었다.
		// 다시 하기의 기본 키가 모두 쓰이고 있으므로 남는 키(F1)를 주고, 사용자의 키는 그대로 둔다.
		StringBuilder text = new StringBuilder("format=tetris-save-1\n");
		KeyBindings mine = KeyBindings.defaults().assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_ENTER)
				.assign(KeyAction.HOLD, 0, KeyEvent.VK_R);
		for (KeyAction a : KeyAction.values()) {
			if (a != KeyAction.RETRY) {
				text.append("key.").append(a.id()).append('=');
				for (int i = 0; i < mine.keys(a).size(); i++) {
					text.append(i == 0 ? "" : ",").append(KeyNames.token(mine.keys(a).get(i)));
				}
				text.append('\n');
			}
		}
		SaveFormat.Parsed p = SaveFormat.read(text.toString());
		KeyBindings k = p.settings().keys();
		assertEquals(List.of(KeyEvent.VK_ENTER), k.keys(KeyAction.HARD_DROP), "사용자의 키");
		assertEquals(List.of(KeyEvent.VK_R, KeyEvent.VK_SHIFT), k.keys(KeyAction.HOLD));
		assertEquals(List.of(KeyEvent.VK_F1), k.keys(KeyAction.RETRY), "남는 키 하나");
		assertEquals(1, p.warnings().size(), p.warnings().toString());
		// 기본 키가 하나라도 비어 있으면 그것을 준다
		SaveFormat.Parsed q = SaveFormat.read("format=tetris-save-1\nkey.hard_drop=ENTER\n");
		assertEquals(List.of(KeyEvent.VK_R), q.settings().keys().keys(KeyAction.RETRY));
		assertEquals(List.of(KeyEvent.VK_ENTER), q.settings().keys().keys(KeyAction.HARD_DROP));
		assertTrue(q.warnings().isEmpty(), q.warnings().toString());
	}

	@Test
	void fullscreenNeverGetsAModifierFromAHandEditedFile() {
		SaveFormat.Parsed p = SaveFormat.read("format=tetris-save-1\nkey.fullscreen=SHIFT,F10\n");
		assertEquals(List.of(KeyEvent.VK_F10), p.settings().keys().keys(KeyAction.FULLSCREEN));
		assertEquals(1, p.warnings().size(), p.warnings().toString());
	}
}
