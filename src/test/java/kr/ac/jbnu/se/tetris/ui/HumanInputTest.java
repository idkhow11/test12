package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 사람의 키 → 틱마다 조작 하나 (docs/GAME.md 4장): 누를 때 한 번, DAS 뒤 ARR 마다 반복. 키 묶음·DAS·ARR 은 사용자 설정에서,
 * 키 이벤트는 KeyTracker 가 동작으로 바꾼다(자동 반복 무시). 카운트다운 동안 누르고 있던 이동은 다시 받기 시작할 때 넘긴다.
 */
class HumanInputTest {

	/** 틱 from..to(포함)에서 조작이 나온 틱. */
	private static List<Long> inputTicks(HumanInput in, long from, long to, Input expected) {
		List<Long> out = new ArrayList<>();
		for (long t = from; t <= to; t++) {
			Input i = in.next(t);
			if (i != null) {
				assertEquals(expected, i, "틱 " + t);
				out.add(t);
			}
		}
		return out;
	}

	@Test
	void holdingLeftMovesOnceThenRepeatsAfterDas() {
		HumanInput in = new HumanInput();
		in.press(HumanInput.Key.LEFT);
		in.press(HumanInput.Key.LEFT); // 운영체제의 자동 반복은 무시된다
		List<Long> ticks = inputTicks(in, 0, 300, Input.L);
		assertEquals(List.of(0L, 170L, 220L, 270L), ticks, "누를 때 한 번, 170ms 뒤부터 50ms 마다");
		in.release(HumanInput.Key.LEFT);
		assertTrue(inputTicks(in, 301, 600, Input.L).isEmpty());
	}

	@Test
	void theLaterDirectionWinsWhileBothAreHeld() {
		HumanInput in = new HumanInput();
		in.press(HumanInput.Key.LEFT);
		assertEquals(Input.L, in.next(0));
		for (long t = 1; t < 100; t++) {
			assertNull(in.next(t));
		}
		in.press(HumanInput.Key.RIGHT);
		assertEquals(Input.R, in.next(100), "새로 누른 쪽으로 한 칸");
		assertEquals(List.of(270L, 320L), inputTicks(in, 101, 350, Input.R), "새 방향의 DAS 는 바뀐 틱부터");
		in.release(HumanInput.Key.RIGHT);
		assertEquals(List.of(521L), inputTicks(in, 351, 540, Input.L), "아직 누르고 있는 왼쪽이 다시 DAS 부터");
	}

	@Test
	void oneShotActionsAreNeverDroppedAndComeOnePerTick() {
		HumanInput in = new HumanInput();
		in.press(HumanInput.Key.CW);
		in.press(HumanInput.Key.HOLD);
		in.press(HumanInput.Key.HARD_DROP);
		assertEquals(Input.CW, in.next(0));
		assertEquals(Input.H, in.next(1));
		assertEquals(Input.HD, in.next(2));
		assertNull(in.next(3));
	}

	@Test
	void osKeyRepeatIsIgnoredForEveryKeyUntilReleased() {
		// Windows 는 키를 누르고 있으면 KEY_PRESSED 를 초당 약 30번 다시 보낸다. 하드드롭·회전·홀드도 한 번만 들어가야 한다.
		HumanInput in = new HumanInput();
		for (int i = 0; i < 5; i++) {
			in.press(HumanInput.Key.HARD_DROP);
			in.press(HumanInput.Key.CW);
		}
		assertEquals(Input.HD, in.next(0));
		assertEquals(Input.CW, in.next(1));
		assertNull(in.next(2), "반복 이벤트는 무시");
		in.release(HumanInput.Key.HARD_DROP);
		in.press(HumanInput.Key.HARD_DROP);
		assertEquals(Input.HD, in.next(3), "떼었다가 다시 누르면 다시 들어간다");
	}

	@Test
	void dasAndArrComeFromTheSettings() {
		HumanInput in = new HumanInput(UserSettings.defaults().withDasMs(100).withArrMs(0));
		assertEquals(100, in.dasMs());
		assertEquals(0, in.arrMs());
		in.press(HumanInput.Key.RIGHT);
		assertEquals(List.of(0L, 100L, 101L, 102L, 103L), inputTicks(in, 0, 103, Input.R), "ARR 0 이면 DAS 뒤 틱마다 한 칸");
		HumanInput defaults = new HumanInput(UserSettings.defaults());
		assertEquals(HumanInput.DAS_MS, defaults.dasMs(), "기본 설정은 지금 손맛 그대로");
		assertEquals(HumanInput.ARR_MS, defaults.arrMs());
		assertEquals(KeyBindings.defaults(), defaults.keys());
	}

	@Test
	void keyEventsBecomeActionsOfTheBindings() {
		KeyBindings keys = KeyBindings.defaults().assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_A);
		HumanInput in = new HumanInput(UserSettings.defaults().withKeys(keys));
		assertEquals(KeyAction.HARD_DROP, in.keyPressed(KeyEvent.VK_A));
		assertNull(in.keyPressed(KeyEvent.VK_A), "운영체제의 자동 반복은 동작이 아니다");
		assertEquals(KeyAction.HARD_DROP, in.keyReleased(KeyEvent.VK_A));
		assertNull(in.keyPressed(KeyEvent.VK_SPACE), "묶이지 않은 키");
		assertEquals(KeyAction.ROTATE_CW, in.keyPressed(KeyEvent.VK_UP));
		assertNull(in.keyPressed(KeyEvent.VK_X), "같은 동작의 다른 키는 다시 누른 것이 아니다");
		assertNull(in.keyReleased(KeyEvent.VK_UP), "아직 X 를 누르고 있다");
		assertEquals(KeyAction.ROTATE_CW, in.keyReleased(KeyEvent.VK_X));
		assertEquals(KeyAction.PAUSE, in.keyPressed(KeyEvent.VK_P));
		for (KeyAction a : KeyAction.values()) {
			assertEquals(a.controlsPiece(), HumanInput.keyOf(a) != null, a + " 는 블록 조작" + (a.controlsPiece() ? "" : "이 아니다"));
		}
	}

	@Test
	void movesHeldThroughTheCountdownAreHandedOverWhenControlResumes() {
		HumanInput in = new HumanInput(UserSettings.defaults());
		// 카운트다운 동안: 화면은 동작을 받기만 하고 조작으로 넘기지 않는다
		in.keyPressed(KeyEvent.VK_LEFT);
		in.keyPressed(KeyEvent.VK_DOWN);
		in.keyPressed(KeyEvent.VK_SPACE);
		in.keyReleased(KeyEvent.VK_SPACE);
		in.keyPressed(KeyEvent.VK_RIGHT); // 나중에 누른 쪽
		assertNull(in.next(0));
		in.resumeHeld(); // GO
		assertTrue(in.softDrop(), "누르고 있던 소프트드롭");
		assertEquals(Input.R, in.next(1), "좌우는 나중에 누른 쪽만 한 칸");
		assertNull(in.next(2), "하드드롭은 넘기지 않는다");
		assertEquals(Input.R, in.next(171), "DAS 가 시작됐다");
		in.reset(); // 일시정지: 조작은 지우지만 누르고 있는 키는 기억한다
		assertFalse(in.softDrop());
		in.resumeHeld(); // 일시정지 뒤 카운트다운이 끝남
		assertTrue(in.softDrop());
		assertEquals(Input.R, in.next(200), "한 칸");
		assertTrue(inputTicks(in, 201, 369, Input.R).isEmpty(), "멈추기 전의 반복은 이어지지 않는다");
		assertEquals(Input.R, in.next(370), "DAS 부터 다시");
		in.releaseAll(); // 창이 포커스를 잃음: 키 추적도 비운다
		in.resumeHeld();
		assertFalse(in.softDrop());
		assertTrue(inputTicks(in, 201, 600, Input.R).isEmpty());
		assertEquals(KeyAction.MOVE_RIGHT, in.keyPressed(KeyEvent.VK_RIGHT), "뗌을 잃었어도 다시 누르면 눌림이다");
	}

	@Test
	void softDropIsAHeldStateAndResetReleasesEverything() {
		HumanInput in = new HumanInput();
		in.press(HumanInput.Key.SOFT_DROP);
		in.press(HumanInput.Key.RIGHT);
		assertTrue(in.softDrop());
		in.reset();
		assertFalse(in.softDrop());
		assertTrue(inputTicks(in, 0, 400, Input.R).isEmpty(), "대기열과 누른 상태가 모두 지워졌다");
	}
}
