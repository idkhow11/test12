package kr.ac.jbnu.se.tetris.settings;

import static kr.ac.jbnu.se.tetris.settings.KeyAction.HOLD;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.MENU;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.MOVE_LEFT;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.MOVE_RIGHT;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.ROTATE_CW;
import static kr.ac.jbnu.se.tetris.settings.KeyAction.SOFT_DROP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.List;

import org.junit.jupiter.api.Test;

/** 키 이벤트 → 동작의 막 눌림·막 뗌: 자동 반복 무시, 한 동작의 여러 키, 묶이지 않은 키, GO 에 넘길 이동. */
class KeyTrackerTest {

	@Test
	void osAutoRepeatIsIgnored() {
		// Windows 는 누르고 있으면 KEY_PRESSED 만 계속 보낸다. 동작은 처음 한 번만 눌린다
		KeyTracker t = new KeyTracker(KeyBindings.defaults());
		assertEquals(MENU, t.press(KeyEvent.VK_ESCAPE));
		for (int i = 0; i < 10; i++) {
			assertNull(t.press(KeyEvent.VK_ESCAPE), "Esc 를 누르고 있어도 기권 확인이 넘어가지 않는다");
		}
		assertTrue(t.held(MENU));
		assertEquals(MENU, t.release(KeyEvent.VK_ESCAPE));
		assertFalse(t.held(MENU));
		assertEquals(MENU, t.press(KeyEvent.VK_ESCAPE), "떼고 다시 누르면 다시 눌린다");
	}

	@Test
	void anActionWithTwoKeysIsHeldUntilTheLastKeyIsReleased() {
		KeyTracker t = new KeyTracker(KeyBindings.defaults());
		assertEquals(ROTATE_CW, t.press(KeyEvent.VK_UP));
		assertNull(t.press(KeyEvent.VK_X), "↑ 를 누른 채 X: 회전은 한 번");
		assertNull(t.release(KeyEvent.VK_UP), "X 를 아직 누르고 있다");
		assertTrue(t.held(ROTATE_CW));
		assertNull(t.press(KeyEvent.VK_X), "X 의 자동 반복이 회전을 다시 일으키지 않는다");
		assertEquals(ROTATE_CW, t.release(KeyEvent.VK_X));
	}

	@Test
	void holdingOneLeftKeyKeepsTheRepeatWhenAnotherLeftKeyIsTapped() {
		// 왼쪽 이동에 ← 와 A 를 묶은 사람: ← 를 누르고 있는 동안 A 를 눌렀다 떼도 왼쪽은 계속 눌려 있다(DAS 가 끊기지 않음)
		KeyBindings k = KeyBindings.defaults().assign(MOVE_LEFT, 1, KeyEvent.VK_A);
		KeyTracker t = new KeyTracker(k);
		assertEquals(MOVE_LEFT, t.press(KeyEvent.VK_LEFT));
		assertNull(t.press(KeyEvent.VK_A));
		assertNull(t.release(KeyEvent.VK_A));
		assertTrue(t.held(MOVE_LEFT));
		assertEquals(MOVE_LEFT, t.release(KeyEvent.VK_LEFT));
	}

	@Test
	void unboundAndUnknownKeysAreIgnored() {
		KeyTracker t = new KeyTracker(KeyBindings.defaults());
		assertNull(t.press(KeyEvent.VK_A), "묶이지 않은 키");
		assertNull(t.release(KeyEvent.VK_A));
		assertNull(t.release(KeyEvent.VK_C), "누른 적 없는 키(메뉴에서 누르고 대전 화면에서 뗌)");
		assertFalse(t.held(HOLD));
	}

	@Test
	void clearForgetsEverythingWithoutReleaseEvents() {
		KeyTracker t = new KeyTracker(KeyBindings.defaults());
		t.press(KeyEvent.VK_C);
		t.press(KeyEvent.VK_LEFT);
		t.clear(); // 창이 포커스를 잃음
		assertFalse(t.held(HOLD));
		assertFalse(t.held(MOVE_LEFT));
		assertNull(t.release(KeyEvent.VK_C), "지운 뒤의 뗌은 알리지 않는다");
		assertEquals(HOLD, t.press(KeyEvent.VK_C), "다시 누르면 눌린다");
	}

	@Test
	void movesHeldThroughTheCountdownAreHandedOverAtGo() {
		// 카운트다운 동안 누른 키는 버린다. 자동 반복이 다시 눌러 주지 않으므로 GO 에 heldMoves() 로 넘긴다
		KeyTracker t = new KeyTracker(KeyBindings.defaults());
		assertEquals(List.of(), t.heldMoves(), "아무것도 누르지 않음");
		t.press(KeyEvent.VK_LEFT);
		t.press(KeyEvent.VK_LEFT); // 자동 반복
		assertEquals(List.of(MOVE_LEFT), t.heldMoves());
		t.press(KeyEvent.VK_DOWN);
		assertEquals(List.of(SOFT_DROP, MOVE_LEFT), t.heldMoves(), "소프트드롭 먼저, 그다음 방향");
		t.press(KeyEvent.VK_RIGHT);
		assertEquals(List.of(SOFT_DROP, MOVE_RIGHT), t.heldMoves(), "좌우를 다 누르면 나중에 누른 쪽 하나만");
		t.release(KeyEvent.VK_RIGHT);
		assertEquals(List.of(SOFT_DROP, MOVE_LEFT), t.heldMoves(), "나중 것을 떼면 아직 누르고 있는 쪽");
		t.release(KeyEvent.VK_LEFT);
		t.press(KeyEvent.VK_RIGHT);
		t.press(KeyEvent.VK_LEFT);
		assertEquals(List.of(SOFT_DROP, MOVE_LEFT), t.heldMoves());
		t.release(KeyEvent.VK_DOWN);
		assertEquals(List.of(MOVE_LEFT), t.heldMoves());
	}

	@Test
	void hardDropRotationAndHoldAreNeverHandedOver() {
		// GO 직후 뜻하지 않은 하드드롭이 나지 않게: 누르고 있어도 넘기지 않는다
		KeyTracker t = new KeyTracker(KeyBindings.defaults());
		t.press(KeyEvent.VK_SPACE);
		t.press(KeyEvent.VK_UP);
		t.press(KeyEvent.VK_Z);
		t.press(KeyEvent.VK_C);
		t.press(KeyEvent.VK_P);
		assertEquals(List.of(), t.heldMoves());
		t.press(KeyEvent.VK_RIGHT);
		assertEquals(List.of(MOVE_RIGHT), t.heldMoves());
		t.clear(); // 창이 포커스를 잃음
		assertEquals(List.of(), t.heldMoves());
	}

	@Test
	void aSecondKeyOfTheSameDirectionKeepsItsOrder() {
		// 왼쪽에 ← 와 A: ← 를 누른 뒤 → 를 누르고, A 를 더 눌러도 왼쪽이 "나중" 이 되지는 않는다(이미 눌린 동작)
		KeyTracker t = new KeyTracker(KeyBindings.defaults().assign(MOVE_LEFT, 1, KeyEvent.VK_A));
		t.press(KeyEvent.VK_LEFT);
		t.press(KeyEvent.VK_RIGHT);
		t.press(KeyEvent.VK_A);
		assertEquals(List.of(MOVE_RIGHT), t.heldMoves());
		t.release(KeyEvent.VK_LEFT);
		t.release(KeyEvent.VK_RIGHT);
		assertEquals(List.of(MOVE_LEFT), t.heldMoves(), "A 를 아직 누르고 있다");
	}

	@Test
	void usesTheBindingsItWasMadeWith() {
		KeyBindings k = KeyBindings.defaults().assign(HOLD, 0, KeyEvent.VK_SPACE);
		KeyTracker t = new KeyTracker(k);
		assertSame(k, t.keys());
		assertEquals(HOLD, t.press(KeyEvent.VK_SPACE));
		assertEquals(KeyAction.HARD_DROP, t.press(KeyEvent.VK_C));
		assertThrows(NullPointerException.class, () -> new KeyTracker(null));
	}
}
