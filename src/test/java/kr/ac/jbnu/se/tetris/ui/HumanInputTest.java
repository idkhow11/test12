package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/** 사람의 키 → 틱마다 조작 하나 (docs/GAME.md 4장): 누를 때 한 번, DAS 뒤 ARR 마다 반복. */
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
