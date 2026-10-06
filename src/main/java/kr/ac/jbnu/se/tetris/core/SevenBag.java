package kr.ac.jbnu.se.tetris.core;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 7-bag 블록 생성기 (RULES 5.3). 두 플레이어는 같은 게임 시드로 각자 하나씩 만든다.
 * 가방을 언제 채우든 수열은 같으므로 미리보기 개수와 무관하다.
 */
public final class SevenBag {

	private final SplitMix64 rng;
	private final ArrayDeque<PieceType> queue = new ArrayDeque<>();

	public SevenBag(long gameSeed) {
		this.rng = SplitMix64.stream(gameSeed, SplitMix64.STREAM_BAG);
	}

	private SevenBag(SevenBag other) {
		this.rng = other.rng.copy();
		this.queue.addAll(other.queue);
	}

	/** 같은 위치에서 이어지는 독립된 복사본. 앞으로 나올 블록 순서가 원본과 같다. */
	public SevenBag copy() {
		return new SevenBag(this);
	}

	public PieceType next() {
		ensure(1);
		return queue.poll();
	}

	/** 앞으로 나올 블록 i 번째(0 부터). 소비하지 않는다. */
	public PieceType peek(int i) {
		ensure(i + 1);
		Iterator<PieceType> it = queue.iterator();
		for (int k = 0; k < i; k++) {
			it.next();
		}
		return it.next();
	}

	private void ensure(int size) {
		while (queue.size() < size) {
			PieceType[] bag = PieceType.values();
			for (int i = bag.length - 1; i >= 1; i--) {
				int j = rng.nextInt(i + 1);
				PieceType tmp = bag[i];
				bag[i] = bag[j];
				bag[j] = tmp;
			}
			for (PieceType p : bag) {
				queue.add(p);
			}
		}
	}
}
