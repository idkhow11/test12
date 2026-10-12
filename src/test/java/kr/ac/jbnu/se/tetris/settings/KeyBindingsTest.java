package kr.ac.jbnu.se.tetris.settings;

import static kr.ac.jbnu.se.tetris.settings.KeyAction.FULLSCREEN;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.HARD_DROP;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.HOLD;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.MENU;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.MOVE_LEFT;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.MOVE_RIGHT;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.PAUSE;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.RETRY;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.ROTATE_CCW;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.ROTATE_CW;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.SOFT_DROP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** 키 묶음 (docs/GAME.md 4장): 기본 키, 키로 동작 찾기, 맞바꾸기, 겹침 찾기, 되돌리기. */
class KeyBindingsTest {

	private static KeyBindings defaults() {
		return KeyBindings.defaults();
	}

	@Test
	void defaultsAreTheKeysOfTheGameBeforeSettings() {
		// GamePanel.key()·onKey() 와 TetrisApp 의 F11 (10-04 커밋 a8c8a4c 의 키)
		KeyBindings k = defaults();
		assertEquals(List.of(KeyEvent.VK_LEFT), k.keys(MOVE_LEFT));
		assertEquals(List.of(KeyEvent.VK_RIGHT), k.keys(MOVE_RIGHT));
		assertEquals(List.of(KeyEvent.VK_DOWN), k.keys(SOFT_DROP));
		assertEquals(List.of(KeyEvent.VK_SPACE), k.keys(HARD_DROP));
		assertEquals(List.of(KeyEvent.VK_UP, KeyEvent.VK_X), k.keys(ROTATE_CW));
		assertEquals(List.of(KeyEvent.VK_Z, KeyEvent.VK_CONTROL), k.keys(ROTATE_CCW));
		assertEquals(List.of(KeyEvent.VK_C, KeyEvent.VK_SHIFT), k.keys(HOLD));
		assertEquals(List.of(KeyEvent.VK_P), k.keys(PAUSE));
		assertEquals(List.of(KeyEvent.VK_ESCAPE), k.keys(MENU));
		assertEquals(List.of(KeyEvent.VK_ENTER, KeyEvent.VK_R), k.keys(RETRY));
		assertEquals(List.of(KeyEvent.VK_F11), k.keys(FULLSCREEN));
		assertTrue(k.isDefault());
		for (KeyAction a : KeyAction.values()) {
			assertTrue(k.isDefault(a), a.id());
		}
	}

	@Test
	void pieceControlsAreTheSevenHumanInputKeys() {
		Set<KeyAction> piece = new HashSet<>();
		for (KeyAction a : KeyAction.values()) {
			if (a.controlsPiece()) {
				piece.add(a);
			}
		}
		assertEquals(Set.of(MOVE_LEFT, MOVE_RIGHT, SOFT_DROP, HARD_DROP, ROTATE_CW, ROTATE_CCW, HOLD), piece);
		assertTrue(FULLSCREEN.global());
		assertFalse(MENU.global());
		assertEquals(HOLD, KeyAction.byId("hold"));
		assertNull(KeyAction.byId("jump"));
	}

	@Test
	void actionOfFindsWhoUsesAKey() {
		KeyBindings k = defaults();
		assertEquals(ROTATE_CW, k.actionOf(KeyEvent.VK_X));
		assertEquals(ROTATE_CCW, k.actionOf(KeyEvent.VK_CONTROL));
		assertEquals(HOLD, k.actionOf(KeyEvent.VK_SHIFT));
		assertEquals(RETRY, k.actionOf(KeyEvent.VK_R));
		assertEquals(FULLSCREEN, k.actionOf(KeyEvent.VK_F11));
		assertNull(k.actionOf(KeyEvent.VK_A));
	}

	@Test
	void describeJoinsTheKeyLabels() {
		assertEquals("↑ / X", defaults().describe(ROTATE_CW));
		assertEquals("Z / Ctrl", defaults().describe(ROTATE_CCW));
		assertEquals("Space", defaults().describe(HARD_DROP));
	}

	@Test
	void assigningAFreeKeyReplacesTheSlot() {
		KeyBindings k = defaults().assign(HOLD, 0, KeyEvent.VK_A);
		assertEquals(List.of(KeyEvent.VK_A, KeyEvent.VK_SHIFT), k.keys(HOLD));
		assertNull(k.actionOf(KeyEvent.VK_C), "내놓은 C 는 아무 동작에도 없다");
		assertFalse(k.isDefault());
		assertFalse(k.isDefault(HOLD));
		assertTrue(k.isDefault(HARD_DROP));
		assertTrue(defaults().isDefault(), "원래 객체는 바뀌지 않는다");
	}

	@Test
	void takingAnotherActionsKeySwapsTheTwo() {
		KeyBindings before = defaults();
		assertEquals(HARD_DROP, before.actionOf(KeyEvent.VK_SPACE), "화면은 이것으로 '하드드롭과 바꿨어요'를 알린다");
		assertNull(before.reasonAgainst(HOLD, 0, KeyEvent.VK_SPACE));
		// 홀드의 C 자리에 Space(하드드롭의 하나뿐인 키) → 하드드롭은 홀드가 내놓은 C 를 받는다
		KeyBindings k = before.assign(HOLD, 0, KeyEvent.VK_SPACE);
		assertEquals(List.of(KeyEvent.VK_SPACE, KeyEvent.VK_SHIFT), k.keys(HOLD));
		assertEquals(List.of(KeyEvent.VK_C), k.keys(HARD_DROP));
		assertEquals(HOLD, k.actionOf(KeyEvent.VK_SPACE));
		assertEquals(HARD_DROP, k.actionOf(KeyEvent.VK_C));
	}

	@Test
	void addingAKeyTakesItFromAnActionThatHasASpare() {
		// 시계 회전은 ↑·X 둘이라 X 를 홀드에 더해도 ↑ 가 남는다
		KeyBindings k = defaults().assign(HOLD, 2, KeyEvent.VK_X);
		assertEquals(List.of(KeyEvent.VK_C, KeyEvent.VK_SHIFT, KeyEvent.VK_X), k.keys(HOLD));
		assertEquals(List.of(KeyEvent.VK_UP), k.keys(ROTATE_CW));
	}

	@Test
	void anActionIsNeverLeftWithoutAKey() {
		KeyBindings k = defaults();
		// 일시정지의 하나뿐인 P 를 하드드롭에 '더하면' 일시정지에 줄 키가 없다 → 바꾸지 않는다
		String why = k.reasonAgainst(HARD_DROP, 1, KeyEvent.VK_P);
		assertNotNull(why);
		assertTrue(why.contains(PAUSE.label()), why);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> k.assign(HARD_DROP, 1, KeyEvent.VK_P));
		assertEquals(why, e.getMessage());
		// 자리 0 을 '바꾸면' Space 를 내주므로 된다
		KeyBindings swapped = k.assign(HARD_DROP, 0, KeyEvent.VK_P);
		assertEquals(List.of(KeyEvent.VK_P), swapped.keys(HARD_DROP));
		assertEquals(List.of(KeyEvent.VK_SPACE), swapped.keys(PAUSE));
	}

	@Test
	void choosingOwnKeyReordersOrDoesNothing() {
		assertEquals(List.of(KeyEvent.VK_X, KeyEvent.VK_UP), defaults().assign(ROTATE_CW, 0, KeyEvent.VK_X).keys(ROTATE_CW));
		assertSame(defaults(), defaults().assign(ROTATE_CW, 1, KeyEvent.VK_X), "같은 자리에 같은 키");
		assertSame(defaults(), defaults().assign(ROTATE_CW, 2, KeyEvent.VK_UP), "이미 있는 키를 더함");
	}

	@Test
	void atMostThreeKeysPerAction() {
		KeyBindings k = defaults().assign(HOLD, 2, KeyEvent.VK_A);
		assertEquals(KeyBindings.MAX_KEYS, k.keys(HOLD).size());
		assertNotNull(k.reasonAgainst(HOLD, 3, KeyEvent.VK_B), "넷째 키는 더할 수 없다");
		assertNull(k.reasonAgainst(HOLD, 2, KeyEvent.VK_B), "바꾸는 것은 된다");
		assertThrows(IndexOutOfBoundsException.class, () -> k.reasonAgainst(HOLD, 4, KeyEvent.VK_B));
		assertThrows(IndexOutOfBoundsException.class, () -> k.reasonAgainst(HOLD, -1, KeyEvent.VK_B));
	}

	@Test
	void reservedKeysCannotBeBound() {
		KeyBindings k = defaults();
		int[] reserved = { KeyEvent.VK_WINDOWS, KeyEvent.VK_KANA, KeyEvent.VK_KANJI, KeyEvent.VK_ALT,
				KeyEvent.VK_PRINTSCREEN, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_UNDEFINED, -5 };
		for (int code : reserved) {
			assertNotNull(k.reasonAgainst(HOLD, 0, code), KeyNames.token(code));
			assertThrows(IllegalArgumentException.class, () -> k.assign(HOLD, 0, code));
		}
	}

	@Test
	void fullscreenCannotUseMenuKeysOrModifiers() {
		KeyBindings k = defaults();
		assertEquals("Enter 키는 전체 화면에 쓸 수 없어요", k.reasonAgainst(FULLSCREEN, 0, KeyEvent.VK_ENTER));
		assertNotNull(k.reasonAgainst(FULLSCREEN, 1, KeyEvent.VK_TAB));
		assertEquals("Shift 키는 전체 화면에 쓸 수 없어요", k.reasonAgainst(FULLSCREEN, 0, KeyEvent.VK_SHIFT),
				"Shift 를 누를 때마다 창이 바뀌면 안 된다");
		assertNotNull(k.reasonAgainst(FULLSCREEN, 1, KeyEvent.VK_CONTROL));
		assertNull(k.reasonAgainst(FULLSCREEN, 0, KeyEvent.VK_F10));
		// 하드드롭이 F11 을 가져가면 전체 화면이 받을 키가 Space(메뉴 키)뿐이라 바꾸지 않는다
		assertEquals("F11 키는 전체 화면의 하나뿐인 키예요", k.reasonAgainst(HARD_DROP, 0, KeyEvent.VK_F11));
		// 홀드의 Shift 자리에 F11 을 주어도 전체 화면이 Shift 를 받을 수 없어 바꾸지 않는다
		assertNotNull(k.reasonAgainst(HOLD, 1, KeyEvent.VK_F11));
		// 홀드의 C 자리면 전체 화면은 C 를 받는다
		KeyBindings s = k.assign(HOLD, 0, KeyEvent.VK_F11);
		assertEquals(List.of(KeyEvent.VK_C), s.keys(FULLSCREEN));
		assertEquals(List.of(KeyEvent.VK_F11, KeyEvent.VK_SHIFT), s.keys(HOLD));
		// 전체 화면에 키가 둘이면 하나를 가져가도 된다: 받을 수 없는 Shift 는 넘기지 않고 빼기만 한다
		KeyBindings two = k.assign(FULLSCREEN, 1, KeyEvent.VK_F12);
		KeyBindings taken = two.assign(HOLD, 1, KeyEvent.VK_F12);
		assertEquals(List.of(KeyEvent.VK_F11), taken.keys(FULLSCREEN));
		assertEquals(List.of(KeyEvent.VK_C, KeyEvent.VK_F12), taken.keys(HOLD));
		assertNull(taken.actionOf(KeyEvent.VK_SHIFT));
	}

	@Test
	void refusalsAreShortKoreanSentences() {
		KeyBindings k = defaults();
		assertEquals("한/영 키는 쓸 수 없어요", k.reasonAgainst(HOLD, 0, KeyEvent.VK_KANA));
		assertEquals("Win 키는 쓸 수 없어요", k.reasonAgainst(HOLD, 0, KeyEvent.VK_WINDOWS));
		assertEquals("그 키는 쓸 수 없어요", k.reasonAgainst(HOLD, 0, KeyNames.NONE));
		assertEquals("P 키는 일시정지의 하나뿐인 키예요", k.reasonAgainst(HARD_DROP, 1, KeyEvent.VK_P));
		assertEquals("키는 동작마다 3개까지예요",
				k.assign(HOLD, 2, KeyEvent.VK_A).reasonAgainst(HOLD, 3, KeyEvent.VK_B));
		Map<KeyAction, List<Integer>> map = new EnumMap<>(k.asMap());
		map.put(HOLD, List.of(99999));
		map.put(PAUSE, List.of(99999));
		assertEquals("키(99999)가 홀드·일시정지에 함께 있어요", KeyBindings.problem(map), "표에 없는 키도 조사가 맞는다");
	}

	@Test
	void assignNoticeTellsWhatHappenedToTheOtherAction() {
		KeyBindings k = defaults();
		// 홀드의 C 자리에 Space → 하드드롭이 C 를 받는다
		assertEquals("하드드롭 키는 이제 C", k.assignNotice(HOLD, 0, KeyEvent.VK_SPACE));
		// 시계 회전의 X 를 홀드에 더함 → 시계 회전은 ↑ 만 남는다
		assertEquals("시계 회전 키는 이제 ↑", k.assignNotice(HOLD, 2, KeyEvent.VK_X));
		// 다른 동작이 바뀌지 않으면 null: 빈 키, 자기 키 순서 바꾸기, 거절되는 바꾸기, 범위 밖 자리
		assertNull(k.assignNotice(HOLD, 0, KeyEvent.VK_A));
		assertNull(k.assignNotice(ROTATE_CW, 0, KeyEvent.VK_X));
		assertNull(k.assignNotice(HARD_DROP, 1, KeyEvent.VK_P));
		assertNull(k.assignNotice(HOLD, 7, KeyEvent.VK_SPACE));
		assertTrue(k.isDefault(), "알림만 만들고 묶음은 바꾸지 않는다");
	}

	@Test
	void shortDescriptionShowsTheFirstKeys() {
		KeyBindings k = defaults();
		assertEquals("↑", k.describe(ROTATE_CW, 1));
		assertEquals("↑ / X", k.describe(ROTATE_CW, 2));
		assertEquals("↑ / X", k.describe(ROTATE_CW, 9));
		assertEquals("", k.describe(ROTATE_CW, 0));
		assertEquals("Enter", k.describe(RETRY, 1), "결과 카드의 '다시' 키");
	}

	@Test
	void removeKeepsAtLeastOneKey() {
		KeyBindings k = defaults().remove(HOLD, 1);
		assertEquals(List.of(KeyEvent.VK_C), k.keys(HOLD));
		assertNull(k.actionOf(KeyEvent.VK_SHIFT));
		assertThrows(IllegalArgumentException.class, () -> k.remove(HOLD, 0));
		assertThrows(IndexOutOfBoundsException.class, () -> k.remove(HOLD, 1));
	}

	@Test
	void resetBringsBackTheDefaultKeys() {
		// 홀드가 Space 를 가져가 하드드롭이 C 가 된 상태에서 하드드롭만 되돌리면: C 자리에 Space, 홀드는 Space 자리에 C
		KeyBindings changed = defaults().assign(HOLD, 0, KeyEvent.VK_SPACE);
		assertEquals(defaults(), changed.reset(HARD_DROP));
		// 더 단 키는 빠진다
		KeyBindings extra = defaults().assign(HOLD, 2, KeyEvent.VK_A);
		assertEquals(defaults(), extra.reset(HOLD));
		// 기본 키를 하나뿐인 키로 쓰는 동작이 있으면 그 키는 건너뛴다: 일시정지 ← X(시계 회전이 X 를 내줌, ↑ 는 남음)
		KeyBindings taken = defaults().assign(PAUSE, 0, KeyEvent.VK_X); // 일시정지 [X], 시계 회전 [↑, P]
		assertEquals(List.of(KeyEvent.VK_UP, KeyEvent.VK_P), taken.keys(ROTATE_CW));
		KeyBindings r = taken.reset(ROTATE_CW);
		// 자리 0 의 ↑ 는 그대로, 자리 1 의 P 를 X 로 바꾸면 일시정지는 P 를 돌려받는다
		assertEquals(defaults(), r);
	}

	@Test
	void conflictsAreFoundInHandMadeMaps() {
		Map<KeyAction, List<Integer>> map = new EnumMap<>(defaults().asMap());
		map.put(HOLD, List.of(KeyEvent.VK_SPACE)); // 하드드롭과 겹침
		map.put(PAUSE, List.of(KeyEvent.VK_X)); // 시계 회전과 겹침
		List<KeyBindings.Conflict> c = KeyBindings.conflicts(map);
		// 동작 순서로 훑는다: 하드드롭(Space), 시계 회전(↑, X) 를 먼저 보고, 홀드에서 Space, 일시정지에서 X 가 겹친다
		assertEquals(List.of(new KeyBindings.Conflict(KeyEvent.VK_SPACE, HARD_DROP, HOLD),
				new KeyBindings.Conflict(KeyEvent.VK_X, ROTATE_CW, PAUSE)), c);
		assertEquals("Space 키가 하드드롭·홀드에 함께 있어요", c.get(0).message());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KeyBindings.of(map));
		assertEquals(c.get(0).message(), e.getMessage());
		assertTrue(KeyBindings.conflicts(defaults().asMap()).isEmpty());
	}

	@Test
	void ofRejectsMissingRepeatedTooManyAndUnusableKeys() {
		Map<KeyAction, List<Integer>> missing = new EnumMap<>(defaults().asMap());
		missing.remove(HOLD);
		assertEquals("홀드에 키가 없어요", KeyBindings.problem(missing));
		Map<KeyAction, List<Integer>> empty = new EnumMap<>(defaults().asMap());
		empty.put(HOLD, List.of());
		assertEquals("홀드에 키가 없어요", KeyBindings.problem(empty));
		Map<KeyAction, List<Integer>> twice = new EnumMap<>(defaults().asMap());
		twice.put(HOLD, List.of(KeyEvent.VK_C, KeyEvent.VK_C));
		assertEquals("홀드에 C 키가 두 번 있어요", KeyBindings.problem(twice));
		Map<KeyAction, List<Integer>> four = new EnumMap<>(defaults().asMap());
		four.put(HOLD, List.of(KeyEvent.VK_C, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D));
		assertNotNull(KeyBindings.problem(four));
		Map<KeyAction, List<Integer>> windows = new EnumMap<>(defaults().asMap());
		windows.put(HOLD, List.of(KeyEvent.VK_WINDOWS));
		assertNotNull(KeyBindings.problem(windows));
		Map<KeyAction, List<Integer>> tab = new EnumMap<>(defaults().asMap());
		tab.put(FULLSCREEN, List.of(KeyEvent.VK_TAB));
		assertEquals("Tab 키는 전체 화면에 쓸 수 없어요", KeyBindings.problem(tab));
		assertNull(KeyBindings.problem(defaults().asMap()));
		assertEquals(defaults(), KeyBindings.of(defaults().asMap()));
	}

	@Test
	void bindingsAreValues() {
		KeyBindings a = defaults().assign(HOLD, 0, KeyEvent.VK_A);
		KeyBindings b = defaults().assign(HOLD, 0, KeyEvent.VK_A);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertThrows(UnsupportedOperationException.class, () -> a.keys(HOLD).add(KeyEvent.VK_B));
		assertThrows(UnsupportedOperationException.class, () -> a.asMap().put(HOLD, List.of(KeyEvent.VK_B)));
		assertTrue(a.toString().contains("hold=A,SHIFT"), a.toString());
	}

	@Test
	void randomEditsAlwaysKeepTheRules() {
		// 설정 화면에서 아무렇게나 눌러도(바꾸기, 더하기, 빼기, 되돌리기) 규칙이 깨지지 않는다
		int[] pool = { KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_C, KeyEvent.VK_X, KeyEvent.VK_Z,
				KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB, KeyEvent.VK_UP, KeyEvent.VK_LEFT,
				KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_F11, KeyEvent.VK_F10, KeyEvent.VK_P, KeyEvent.VK_R,
				KeyEvent.VK_WINDOWS, KeyEvent.VK_KANA, KeyEvent.VK_NUMPAD0 };
		Random rnd = new Random(20261004);
		KeyBindings k = defaults();
		int refused = 0;
		for (int step = 0; step < 5000; step++) {
			KeyAction a = KeyAction.values()[rnd.nextInt(KeyAction.values().length)];
			int op = rnd.nextInt(10);
			if (op < 7) {
				int slot = rnd.nextInt(k.keys(a).size() + 1);
				int code = pool[rnd.nextInt(pool.length)];
				String why = k.reasonAgainst(a, slot, code);
				if (why == null) {
					k = k.assign(a, slot, code);
				} else {
					refused++;
					KeyBindings before = k;
					assertThrows(IllegalArgumentException.class, () -> before.assign(a, slot, code));
				}
			} else if (op < 9) {
				if (k.keys(a).size() > 1) {
					k = k.remove(a, rnd.nextInt(k.keys(a).size()));
				}
			} else {
				k = k.reset(a);
			}
			assertNull(KeyBindings.problem(k.asMap()), "걸음 " + step + ": " + k);
			for (KeyAction b : KeyAction.values()) {
				for (int code : k.keys(b)) {
					assertEquals(b, k.actionOf(code));
				}
			}
		}
		assertTrue(refused > 0, "거절되는 경우도 시험했다");
		for (KeyAction a : KeyAction.values()) {
			k = k.reset(a);
		}
		assertNull(KeyBindings.problem(k.asMap()));
	}
}
