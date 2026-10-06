package kr.ac.jbnu.se.tetris.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 한 플레이어가 받을 가비지 대기열 (RULES 10장). 앞이 먼저 받은 덩어리다. */
public final class GarbageQueue {

	/** 한 번의 고정에 올라오는 최대 줄 수. */
	public static final int MAX_RISE = 8;

	/** 같은 구멍 열을 가진 가비지 줄 묶음. 불변. */
	public static final class Chunk {
		private final int lines;
		private final int hole;

		public Chunk(int lines, int hole) {
			this.lines = lines;
			this.hole = hole;
		}

		public int lines() {
			return lines;
		}

		public int hole() {
			return hole;
		}
	}

	private final List<Chunk> chunks = new ArrayList<>();
	private final SplitMix64 holeRng;
	private int prevHole = -1;

	/** @param holeRng 받는 플레이어의 가비지 스트림 (RULES 5.2) */
	public GarbageQueue(SplitMix64 holeRng) {
		this.holeRng = holeRng;
	}

	/** 대기열, 구멍 난수, 직전 구멍 열까지 그대로 옮긴 독립된 복사본. */
	public GarbageQueue copy() {
		GarbageQueue q = new GarbageQueue(holeRng.copy());
		q.chunks.addAll(chunks);
		q.prevHole = prevHole;
		return q;
	}

	/** 구멍 열을 이미 아는 덩어리를 붙인다. 픽스처와 상태 복원용. */
	public void addChunk(int lines, int hole) {
		chunks.add(new Chunk(lines, hole));
	}

	/** RULES 10.2: 공격 하나를 받는다. 송신량 0 은 붙이지 않고 난수도 쓰지 않는다. */
	public void receive(int lines) {
		if (lines <= 0) {
			return;
		}
		int hole = prevHole < 0 ? holeRng.nextInt(Grid.WIDTH)
				: (prevHole + 1 + holeRng.nextInt(Grid.WIDTH - 1)) % Grid.WIDTH;
		prevHole = hole;
		chunks.add(new Chunk(lines, hole));
	}

	/** RULES 10.1: 앞에서부터 상쇄하고, 남은 공격(상대에게 보낼 양)을 돌려준다. */
	public int cancel(int attack) {
		int left = attack;
		while (left > 0 && !chunks.isEmpty()) {
			Chunk first = chunks.get(0);
			int used = Math.min(left, first.lines());
			left -= used;
			if (used == first.lines()) {
				chunks.remove(0);
			} else {
				chunks.set(0, new Chunk(first.lines() - used, first.hole()));
			}
		}
		return left;
	}

	/**
	 * RULES 10.3: 앞에서부터 최대 {@link #MAX_RISE} 줄을 바닥에 끼워 넣는다.
	 * 앞 덩어리부터 한 줄씩 넣으므로 나중에 넣은 줄이 더 아래에 온다.
	 *
	 * @return 가비지 아웃이면 true
	 */
	public boolean rise(Grid grid) {
		int budget = MAX_RISE;
		while (budget > 0 && !chunks.isEmpty()) {
			Chunk first = chunks.get(0);
			int n = Math.min(budget, first.lines());
			for (int i = 0; i < n; i++) {
				if (grid.insertGarbageRow(first.hole())) {
					return true;
				}
			}
			budget -= n;
			if (n == first.lines()) {
				chunks.remove(0);
			} else {
				chunks.set(0, new Chunk(first.lines() - n, first.hole()));
			}
		}
		return false;
	}

	public List<Chunk> chunks() {
		return Collections.unmodifiableList(chunks);
	}

	public int totalLines() {
		int sum = 0;
		for (Chunk c : chunks) {
			sum += c.lines();
		}
		return sum;
	}
}
