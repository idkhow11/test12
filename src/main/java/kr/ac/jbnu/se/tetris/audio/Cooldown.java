package kr.ac.jbnu.se.tetris.audio;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 소리마다의 쿨다운: 마지막으로 받아들인 때부터 {@link Sfx#cooldownMs(int)} 가 지나야 다시 받아들인다. 키를 빠르게 누르거나
 * 같은 사건이 한꺼번에 여러 번 와도 소리가 겹겹이 쌓이지 않게 한다. 여러 스레드(게임 진행, 화면)가 동시에 불러도 된다.
 */
final class Cooldown {

	/** 처음 값: 어떤 시각과 비교해도 쿨다운이 지난 것으로 보이고, 빼도 넘치지 않는다. */
	private static final long NEVER = Long.MIN_VALUE / 4;

	private final AtomicLongArray last = new AtomicLongArray(Sfx.values().length);

	Cooldown() {
		for (int i = 0; i < last.length(); i++) {
			last.set(i, NEVER);
		}
	}

	/** 소리 {@code e} 를 {@code nowMs} 에 울려도 되면 그 시각을 적고 true. 쿨다운 안이면 false. */
	boolean tryAcquire(Sfx e, int intensity, long nowMs) {
		int i = e.ordinal();
		long gap = e.cooldownMs(intensity);
		while (true) {
			long prev = last.get(i);
			if (nowMs - prev < gap) {
				return false;
			}
			if (last.compareAndSet(i, prev, nowMs)) {
				return true;
			}
		}
	}
}
