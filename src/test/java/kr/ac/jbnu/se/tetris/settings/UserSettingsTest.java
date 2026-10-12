package kr.ac.jbnu.se.tetris.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/** 사용자 설정과 메뉴 기억: 기본값은 설정 화면이 생기기 전의 게임과 같다. */
class UserSettingsTest {

	@Test
	void defaultsMatchTheGameBeforeSettings() {
		UserSettings d = UserSettings.defaults();
		assertEquals(170, d.dasMs(), "HumanInput.DAS_MS (docs/GAME.md 4장)");
		assertEquals(50, d.arrMs(), "HumanInput.ARR_MS");
		assertTrue(d.ghost(), "고스트는 켜져 있었다");
		assertFalse(d.colorBlind(), "색은 Theme 의 지금 색");
		assertFalse(d.patterns(), "무늬 없이 그렸다");
		assertEquals(70, d.volume());
		assertFalse(d.muted());
		assertFalse(d.fullscreenOnStart(), "-Dtetris.fullscreen=true 를 주지 않으면 창으로 시작했다");
		assertEquals(100, d.aiSpeed(), "AI 속도 100% = 난이도 그대로");
		assertEquals(UserSettings.EFFECTS_NORMAL, d.effects(), "효과 세기 보통");
		assertEquals(1.0, d.effectsScale());
		assertTrue(d.shake(), "화면 흔들림 켬");
		assertFalse(d.calmFlash(), "번쩍임 줄이기 끔");
		assertTrue(d.heartbeat(), "심장 소리 켬");
		assertEquals(KeyBindings.defaults(), d.keys());
		assertTrue(d.menu().isEmpty(), "메뉴는 처음엔 자기 기본값(2단계 중급 등)을 쓴다");
		assertSame(d, UserSettings.defaults());
	}

	@Test
	void numbersAreClampedToTheirRanges() {
		UserSettings d = UserSettings.defaults();
		assertEquals(UserSettings.MIN_DAS_MS, d.withDasMs(10).dasMs());
		assertEquals(UserSettings.MAX_DAS_MS, d.withDasMs(1000).dasMs());
		assertEquals(175, d.withDasMs(175).dasMs(), "칸에 맞추지 않는다(사람이 파일에 적은 값)");
		assertEquals(0, d.withArrMs(-5).arrMs(), "ARR 0 = 틱마다 한 칸");
		assertEquals(UserSettings.MAX_ARR_MS, d.withArrMs(999).arrMs());
		assertEquals(0, d.withVolume(-1).volume());
		assertEquals(100, d.withVolume(101).volume());
		assertEquals(UserSettings.MIN_AI_SPEED, d.withAiSpeed(10).aiSpeed());
		assertEquals(UserSettings.MAX_AI_SPEED, d.withAiSpeed(500).aiSpeed());
		assertEquals(120, d.withAiSpeed(120).aiSpeed());
		assertEquals(90, d.withAiSpeed(94).aiSpeed(), "AI 속도는 10% 단위로 맞춘다");
		assertEquals(100, d.withAiSpeed(95).aiSpeed(), "가운데면 위로");
		assertEquals(UserSettings.EFFECTS_OFF, d.withEffects(-3).effects());
		assertEquals(UserSettings.EFFECTS_HIGH, d.withEffects(9).effects());
		// 기본값이 범위와 칸 안에 있다
		assertEquals(0, (UserSettings.DEFAULT_DAS_MS - UserSettings.MIN_DAS_MS) % UserSettings.DAS_STEP_MS);
		assertEquals(0, (UserSettings.DEFAULT_ARR_MS - UserSettings.MIN_ARR_MS) % UserSettings.ARR_STEP_MS);
		assertEquals(0, UserSettings.DEFAULT_VOLUME % UserSettings.VOLUME_STEP);
		assertEquals(0, (UserSettings.DEFAULT_AI_SPEED - UserSettings.MIN_AI_SPEED) % UserSettings.AI_SPEED_STEP);
		assertEquals(0, (UserSettings.MAX_AI_SPEED - UserSettings.MIN_AI_SPEED) % UserSettings.AI_SPEED_STEP);
	}

	@Test
	void effectsStrengthScalesTheEffects() {
		UserSettings d = UserSettings.defaults();
		double[] want = { 0, 0.5, 1.0, 1.5 }; // 끔·약하게·보통·강하게
		for (int e = UserSettings.EFFECTS_OFF; e <= UserSettings.EFFECTS_HIGH; e++) {
			assertEquals(want[e], d.withEffects(e).effectsScale(), "효과 세기 " + e);
		}
	}

	@Test
	void newWithersChangeOnlyTheirOwnField() {
		UserSettings d = UserSettings.defaults();
		UserSettings s = d.withAiSpeed(70).withEffects(UserSettings.EFFECTS_OFF).withShake(false).withCalmFlash(true)
				.withHeartbeat(false);
		assertEquals(d.keys(), s.keys());
		assertEquals(d.dasMs(), s.dasMs());
		assertEquals(d.ghost(), s.ghost());
		assertEquals(d.volume(), s.volume());
		assertEquals(70, s.aiSpeed());
		assertEquals(0.0, s.effectsScale());
		assertFalse(s.shake());
		assertTrue(s.calmFlash());
		assertFalse(s.heartbeat());
		assertEquals(70, s.withDefaultControls().aiSpeed(), "조작만 처음으로는 AI 속도를 남긴다");
		assertEquals(d, s.withDefaultsKeepingMenu(), "모두 처음으로");
	}

	@Test
	void withersChangeOnlyOneThing() {
		UserSettings d = UserSettings.defaults();
		UserSettings s = d.withGhost(false);
		assertFalse(s.ghost());
		assertEquals(d.withGhost(false), s);
		assertEquals(d, s.withGhost(true));
		assertNotEquals(d, d.withColorBlind(true));
		assertNotEquals(d, d.withPatterns(true));
		assertNotEquals(d, d.withFullscreenOnStart(true));
		assertNotEquals(d, d.withMuted(true));
		assertNotEquals(d, d.withMenu(MenuMemory.EMPTY.with(MenuMemory.MODE, MenuMemory.MODE_SAND)));
		KeyBindings keys = KeyBindings.defaults().assign(KeyAction.HOLD, 0, KeyEvent.VK_A);
		UserSettings k = d.withKeys(keys).withDasMs(120).withArrMs(10).withVolume(30).withPatterns(true);
		assertEquals(keys, k.keys());
		assertEquals(120, k.dasMs());
		assertEquals(10, k.arrMs());
		assertEquals(30, k.volume());
		assertTrue(k.patterns());
		assertFalse(k.colorBlind(), "무늬와 색약 색상은 따로 켠다");
		assertTrue(k.ghost());
	}

	@Test
	void defaultControlsKeepTheOtherSettings() {
		UserSettings s = UserSettings.defaults().withKeys(KeyBindings.defaults().assign(KeyAction.HOLD, 0, KeyEvent.VK_A))
				.withDasMs(100).withArrMs(0).withGhost(false).withVolume(20).withColorBlind(true).withPatterns(true);
		UserSettings r = s.withDefaultControls();
		assertEquals(KeyBindings.defaults(), r.keys());
		assertEquals(170, r.dasMs());
		assertEquals(50, r.arrMs());
		assertFalse(r.ghost());
		assertEquals(20, r.volume());
		assertTrue(r.colorBlind());
		assertTrue(r.patterns());
	}

	@Test
	void resettingEverythingKeepsOnlyTheMenuMemory() {
		MenuMemory menu = MenuMemory.EMPTY.with(MenuMemory.MODE, MenuMemory.MODE_SOLO);
		UserSettings s = UserSettings.defaults().withDasMs(100).withMuted(true).withPatterns(true).withMenu(menu);
		assertEquals(UserSettings.defaults().withMenu(menu), s.withDefaultsKeepingMenu());
	}

	@Test
	void theFullscreenPropertyWinsForThisRunOnly() {
		UserSettings off = UserSettings.defaults();
		UserSettings on = off.withFullscreenOnStart(true);
		assertFalse(off.startsFullscreen(null), "지금처럼 창으로 시작");
		assertTrue(on.startsFullscreen(null));
		assertTrue(off.startsFullscreen("true"), "-Dtetris.fullscreen=true");
		assertTrue(off.startsFullscreen(" TRUE "));
		assertFalse(on.startsFullscreen("false"), "저장한 켬을 이번만 끈다");
		assertTrue(on.startsFullscreen(""), "빈 값은 준 것으로 치지 않는다");
		assertEquals("tetris.fullscreen", UserSettings.FULLSCREEN_PROPERTY);
	}

	@Test
	void soundLevelFollowsVolumeAndMute() {
		UserSettings d = UserSettings.defaults();
		assertEquals(0.7, d.soundLevel(), 1e-12);
		assertEquals(0, d.withMuted(true).soundLevel());
		assertEquals(70, d.withMuted(true).volume(), "음소거해도 크기는 남는다");
		assertEquals(0, d.withVolume(0).soundLevel());
		assertEquals(1, d.withVolume(100).soundLevel(), 1e-12);
	}

	@Test
	void nullsAreRejected() {
		assertThrows(NullPointerException.class, () -> UserSettings.defaults().withKeys(null));
		assertThrows(NullPointerException.class, () -> UserSettings.defaults().withMenu(null));
	}

	// ------------------------------------------------------------------ 메뉴 기억

	@Test
	void emptyMenuMemoryLeavesTheMenuDefaults() {
		MenuMemory m = MenuMemory.EMPTY;
		assertNull(m.get(MenuMemory.MODE));
		assertEquals(MenuMemory.MODE_VERSUS, m.get(MenuMemory.MODE, MenuMemory.MODE_VERSUS));
		assertEquals(2, m.getInt(MenuMemory.VERSUS_LEVEL, 2));
		assertEquals(3.0, m.getDouble(MenuMemory.SOLO_SPEED, 3.0));
	}

	@Test
	void menuMemoryKeepsTypedValues() {
		MenuMemory m = MenuMemory.EMPTY.with(MenuMemory.MODE, MenuMemory.MODE_SOLO).with(MenuMemory.VERSUS_LEVEL, 4)
				.with(MenuMemory.VERSUS_PERSONA, "tspin").with(MenuMemory.SOLO_SPEED, 2.5)
				.with(MenuMemory.SOLO_GARBAGE, "S-mix");
		assertEquals("solo", m.get(MenuMemory.MODE));
		assertEquals(4, m.getInt(MenuMemory.VERSUS_LEVEL, 2));
		assertEquals("tspin", m.get(MenuMemory.VERSUS_PERSONA));
		assertEquals(2.5, m.getDouble(MenuMemory.SOLO_SPEED, 3));
		assertEquals("2.5", m.get(MenuMemory.SOLO_SPEED));
		assertEquals("3", MenuMemory.EMPTY.with(MenuMemory.SOLO_SPEED, 3.0).get(MenuMemory.SOLO_SPEED));
		assertEquals("S-mix", m.get(MenuMemory.SOLO_GARBAGE));
		// 정수가 아닌 값은 기본값으로 읽는다
		assertEquals(2, MenuMemory.EMPTY.with(MenuMemory.VERSUS_LEVEL, "abc").getInt(MenuMemory.VERSUS_LEVEL, 2));
		assertEquals(3.0, MenuMemory.EMPTY.with(MenuMemory.SOLO_SPEED, "NaN").getDouble(MenuMemory.SOLO_SPEED, 3.0));
		// 이름 순서로 담긴다
		assertEquals(List.of("mode", "solo.garbage", "solo.speed", "versus.level", "versus.persona"),
				List.copyOf(m.values().keySet()));
	}

	@Test
	void emptyValueForgetsAChoice() {
		MenuMemory m = MenuMemory.EMPTY.with(MenuMemory.MODE, MenuMemory.MODE_SAND);
		assertNull(m.with(MenuMemory.MODE, "").get(MenuMemory.MODE));
		assertTrue(m.with(MenuMemory.MODE, (String) null).isEmpty());
		assertSame(m, m.with(MenuMemory.MODE, MenuMemory.MODE_SAND), "같은 값이면 같은 객체");
	}

	@Test
	void indexOfFindsTheLastChoiceInTodaysList() {
		MenuMemory m = MenuMemory.EMPTY.with(MenuMemory.VERSUS_LEVEL, 4).with(MenuMemory.VERSUS_PERSONA, "tspin")
				.with(MenuMemory.SOLO_SPEED, 5.0).with(MenuMemory.SOLO_GARBAGE, "S-mix");
		// 난이도 id 목록(모델 묶음 순서)에서 id 4 는 네 번째
		assertEquals(3, m.indexOf(MenuMemory.VERSUS_LEVEL, List.of(1, 2, 3, 4, 5), 1));
		assertEquals(2, m.indexOf(MenuMemory.VERSUS_PERSONA, List.of("neutral", "attacker", "tspin"), 0));
		// 속도는 수로 비교한다: 저장한 "5" 와 메뉴의 5.0
		assertEquals(3, m.indexOf(MenuMemory.SOLO_SPEED, List.of(1.0, 2.0, 3.0, 5.0, 8.0, 12.0), 2));
		assertEquals(2, m.indexOf(MenuMemory.SOLO_GARBAGE, List.of("none", "S-3", "S-mix"), 0));
		// 없어진 항목(모델 묶음이 바뀜)이나 고른 적 없는 이름이면 메뉴의 기본값
		assertEquals(1, m.indexOf(MenuMemory.VERSUS_LEVEL, List.of(1, 2, 3), 1));
		assertEquals(0, m.indexOf(MenuMemory.VERSUS_PERSONA, List.of("neutral"), 0));
		assertEquals(1, m.indexOf(MenuMemory.SAND_LEVEL, List.of(1, 2, 3), 1));
		assertEquals(1, MenuMemory.EMPTY.with(MenuMemory.VERSUS_LEVEL, "abc").indexOf(MenuMemory.VERSUS_LEVEL,
				List.of(1, 2, 3), 1), "수가 아닌 값");
		assertEquals(1, m.indexOf(MenuMemory.VERSUS_LEVEL, java.util.Arrays.asList(null, 4), 0), "null 항목은 건너뛴다");
	}

	@Test
	void menuMemoryRejectsBadNamesAndValues() {
		MenuMemory m = MenuMemory.EMPTY;
		for (String key : new String[] { "Mode", "a..b", "", ".a", "a b", "모드", "x".repeat(41) }) {
			assertThrows(IllegalArgumentException.class, () -> m.with(key, "v"), key);
		}
		for (String value : new String[] { "a\nb", " lead", "trail ", "x".repeat(65), "tab\there" }) {
			assertThrows(IllegalArgumentException.class, () -> m.with(MenuMemory.MODE, value), value);
		}
		assertThrows(IllegalArgumentException.class, () -> m.with(MenuMemory.SOLO_SPEED, Double.NaN));
		assertEquals("한글 값도 된다", m.with(MenuMemory.VERSUS_PERSONA, "한글 값도 된다").get(MenuMemory.VERSUS_PERSONA));
		assertThrows(UnsupportedOperationException.class, () -> m.values().put("mode", "x"));
	}

	@Test
	void menuMemoryHasALimit() {
		TreeMap<String, String> many = new TreeMap<>();
		for (int i = 0; i < MenuMemory.MAX_ENTRIES; i++) {
			many.put("k" + i, "v");
		}
		MenuMemory full = new MenuMemory(many);
		assertEquals(MenuMemory.MAX_ENTRIES, full.values().size());
		assertThrows(IllegalArgumentException.class, () -> full.with("one-more", "v"));
		assertEquals("w", full.with("k0", "w").get("k0"), "있는 이름은 고칠 수 있다");
	}
}
