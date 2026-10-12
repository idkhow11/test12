package kr.ac.jbnu.se.tetris.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;

import org.junit.jupiter.api.Test;

/** 전체 화면 키: 누르고 있으면 한 번, 뗌을 잃어도 다음 눌림은 먹힌다, 다른 창으로 가면 처음부터. */
class FullscreenKeyTest {

	private static final int F11 = KeyEvent.VK_F11;

	@Test
	void holdingTheKeyTogglesOnceEvenAcrossTheWindowRebuild() {
		FullscreenKey k = new FullscreenKey();
		assertTrue(k.press(F11, 1_000), "처음 눌림");
		// 창을 다시 여는 동안의 비활성화에는 reset() 을 부르지 않는다. 그 뒤로 오는 자동 반복: 첫 반복은 0.5초 뒤, 그다음 33ms 마다
		long t = 1_500;
		for (int i = 0; i < 60; i++, t += 33) {
			assertFalse(k.press(F11, t), "자동 반복은 바꾸지 않는다 (" + t + "ms)");
		}
		k.release(F11);
		assertTrue(k.press(F11, t + 80), "떼고 다시 누르면 바로 바꾼다");
	}

	@Test
	void aSlowFirstRepeatIsStillARepeat() {
		// Windows 의 반복 시작 지연을 가장 길게(약 1초) 해 둔 PC
		FullscreenKey k = new FullscreenKey();
		assertTrue(k.press(F11, 0));
		assertFalse(k.press(F11, 1_000));
		assertFalse(k.press(F11, 1_030));
	}

	@Test
	void aLostReleaseDoesNotEatTheNextPress() {
		// 창을 다시 만드는 사이에 뗌을 받지 못했다
		FullscreenKey k = new FullscreenKey();
		assertTrue(k.press(F11, 10_000));
		assertTrue(k.press(F11, 10_000 + FullscreenKey.REPEAT_GAP_MS), "한참 뒤의 눌림은 새 눌림이다");
		// 아주 빨리 다시 눌러 한 번 걸러졌어도, 그 눌림의 뗌은 받으므로 다음 눌림부터는 바로 먹힌다
		assertTrue(k.press(F11, 20_000));
		assertFalse(k.press(F11, 20_300));
		k.release(F11);
		assertTrue(k.press(F11, 20_600));
	}

	@Test
	void leavingTheWindowStartsOver() {
		FullscreenKey k = new FullscreenKey();
		assertTrue(k.press(F11, 0));
		k.reset(); // 다른 창으로 갔다 (뗌은 그 창이 받았다)
		assertTrue(k.press(F11, 200), "돌아와서 누르면 바로 바꾼다");
	}

	@Test
	void anotherKeyIsANewPress() {
		// 전체 화면 키를 둘 묶은 사람(F11 과 F12): 서로의 자동 반복이 아니다
		FullscreenKey k = new FullscreenKey();
		assertTrue(k.press(F11, 0));
		assertTrue(k.press(KeyEvent.VK_F12, 100));
		k.release(F11); // 이미 다른 키가 눌렸으므로 아무것도 바꾸지 않는다
		assertFalse(k.press(KeyEvent.VK_F12, 600), "F12 의 자동 반복");
	}
}
