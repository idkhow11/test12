package kr.ac.jbnu.se.tetris.sand;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Iterator;

import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 색 가방: 4색 × 3개 = 12개를 섞어 차례로 낸다 {@code [초안]}. 한 색이 오래 나오지 않는 일을 막는다(원작 Sandtrix 도 같은
 * 12개 가방). 섞는 법은 7-bag(RULES 5.3)과 같은 피셔–예이츠이고, 난수는 게임 시드의 스트림 {@link SandRules#STREAM_COLORS}.
 * 기준 순서는 빨강·파랑·초록·노랑을 세 번 되풀이한 것이다.
 */
final class ColorBag {

	private final SplitMix64 rng;
	private final ArrayDeque<SandColor> queue = new ArrayDeque<>();

	ColorBag(long seed) {
		this.rng = SplitMix64.stream(seed, SandRules.STREAM_COLORS);
	}

	SandColor next() {
		ensure(1);
		return queue.poll();
	}

	/** 앞으로 나올 색 i 번째(0 부터). 소비하지 않는다. */
	SandColor peek(int i) {
		ensure(i + 1);
		Iterator<SandColor> it = queue.iterator();
		for (int k = 0; k < i; k++) {
			it.next();
		}
		return it.next();
	}

	private void ensure(int size) {
		SandColor[] all = SandColor.values();
		while (queue.size() < size) {
			SandColor[] bag = new SandColor[SandRules.COLORS * SandRules.COLOR_COPIES];
			for (int i = 0; i < bag.length; i++) {
				bag[i] = all[i % SandRules.COLORS];
			}
			for (int i = bag.length - 1; i >= 1; i--) {
				int j = rng.nextInt(i + 1);
				SandColor tmp = bag[i];
				bag[i] = bag[j];
				bag[j] = tmp;
			}
			Collections.addAll(queue, bag);
		}
	}
}
