package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** 카운트다운 소리 (CountdownSounds): 숫자가 바뀔 때 한 번, 0 이 되면 GO, 멈춤·판 끝으로 숫자가 사라질 때는 조용. */
class CountdownSoundsTest {

	private static final Cue BEEP_3 = SoundCues.countdown(3);
	private static final Cue BEEP_2 = SoundCues.countdown(2);
	private static final Cue BEEP_1 = SoundCues.countdown(1);
	private static final Cue GO = SoundCues.countdown(0);

	@Test
	void startCountsDownThenGo() {
		CountdownSounds c = new CountdownSounds();
		assertEquals(BEEP_3, c.update(3, true));
		assertNull(c.update(3, true), "같은 숫자를 다시 그려도 한 번만");
		assertEquals(BEEP_2, c.update(2, true));
		assertEquals(BEEP_1, c.update(1, true));
		assertEquals(GO, c.update(0, true));
		assertNull(c.update(0, true), "진행 중에는 조용");
	}

	@Test
	void pausingIsNotGoAndResumingCountsAgain() {
		CountdownSounds c = new CountdownSounds();
		c.update(3, true);
		c.update(2, true);
		assertNull(c.update(0, false), "카운트다운 중에 멈추면 숫자가 사라지지만 GO 가 아니다");
		assertEquals(BEEP_2, c.update(2, true), "다시 가면 남은 숫자부터");
		assertEquals(BEEP_1, c.update(1, true));
		assertEquals(GO, c.update(0, true));
		assertNull(c.update(0, false), "판 도중 멈춤");
		assertEquals(BEEP_2, c.update(2, true), "일시정지를 풀면 2·1 뒤에 GO");
		assertEquals(BEEP_1, c.update(1, true));
		assertEquals(GO, c.update(0, true));
	}

	@Test
	void aNewSessionStartsFresh() {
		CountdownSounds c = new CountdownSounds();
		assertNull(c.update(0, true), "카운트다운 없이 시작한 판(스레드 없이 진행하는 도구)은 GO 도 없다");
		c.update(3, true);
		c.reset();
		assertEquals(BEEP_3, c.update(3, true), "판이 바뀌면 같은 숫자라도 다시");
	}
}
