package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** 소리마다의 쿨다운: 빠르게 여러 번 불러도 쿨다운에 한 번만 받아들인다. 여러 스레드가 함께 불러도 마찬가지. */
class CooldownTest {

	@Test
	void secondPlayInsideTheCooldownIsDropped() {
		Cooldown c = new Cooldown();
		assertTrue(c.tryAcquire(Sfx.MOVE, 1, 1000));
		assertFalse(c.tryAcquire(Sfx.MOVE, 1, 1010));
		assertFalse(c.tryAcquire(Sfx.MOVE, 1, 1019), "MOVE 의 쿨다운은 20ms");
		assertTrue(c.tryAcquire(Sfx.MOVE, 1, 1020));
		assertFalse(c.tryAcquire(Sfx.MOVE, 1, 1021), "받아들인 때부터 다시 잰다");
	}

	@Test
	void soundsHaveSeparateCooldowns() {
		Cooldown c = new Cooldown();
		assertTrue(c.tryAcquire(Sfx.MOVE, 1, 0));
		assertTrue(c.tryAcquire(Sfx.ROTATE, 1, 0), "다른 소리는 막지 않는다");
		assertTrue(c.tryAcquire(Sfx.CLEAR, 4, 0));
		assertFalse(c.tryAcquire(Sfx.CLEAR, 1, 10), "세기가 달라도 같은 소리면 쿨다운 안");
		assertTrue(c.tryAcquire(Sfx.GARBAGE_CANCEL, 2, 10), "새 소리도 따로");
		assertFalse(c.tryAcquire(Sfx.GARBAGE_CANCEL, 2, 129), "상쇄의 쿨다운은 120ms");
		assertTrue(c.tryAcquire(Sfx.GARBAGE_CANCEL, 2, 130));
	}

	@Test
	void firstPlayIsAcceptedWhateverTheClock() {
		// System.nanoTime 으로 만든 시각은 음수일 수도 있다
		assertTrue(new Cooldown().tryAcquire(Sfx.KO, 1, Long.MIN_VALUE / 8));
		assertTrue(new Cooldown().tryAcquire(Sfx.KO, 1, -5));
		assertTrue(new Cooldown().tryAcquire(Sfx.KO, 1, Long.MAX_VALUE / 2));
	}

	@Test
	void holdingDangerKeepsAHeartbeatPace() {
		// 위험한 동안 화면이 15ms 마다 불러도 약 0.85초마다 한 번만 울린다
		Cooldown c = new Cooldown();
		List<Long> beats = new ArrayList<>();
		for (long t = 0; t < 3000; t += 15) {
			if (c.tryAcquire(Sfx.DANGER, 1, t)) {
				beats.add(t);
			}
		}
		assertEquals(List.of(0L, 855L, 1710L, 2565L), beats);
		assertTrue(c.tryAcquire(Sfx.DANGER, 2, 2565 + 600), "더 위험하면 0.6초 만에 다음 박자");
	}

	@Test
	void concurrentCallersGetExactlyOne() throws Exception {
		Cooldown c = new Cooldown();
		AtomicInteger accepted = new AtomicInteger();
		int threads = 8;
		CyclicBarrier ready = new CyclicBarrier(threads);
		List<Thread> all = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Thread t = new Thread(() -> {
				try {
					ready.await();
				} catch (Exception e) {
					return;
				}
				for (int k = 0; k < 1000; k++) {
					if (c.tryAcquire(Sfx.HARD_DROP, 1, 5000)) {
						accepted.incrementAndGet();
					}
				}
			});
			all.add(t);
			t.start();
		}
		for (Thread t : all) {
			t.join(5000);
		}
		assertEquals(1, accepted.get(), "같은 순간 8000 번 불러도 한 번");
	}
}
