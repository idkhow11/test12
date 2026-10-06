package kr.ac.jbnu.se.tetris.core;

/**
 * 고정 처리 중 보드에 관한 부분 (RULES 7장 1~5단계).
 * 공격 계산(6단계), 상쇄·송신(7단계), 가비지 상승(8단계), 스폰(9단계)은 게임 상태를 가진 쪽이 이어서 한다.
 */
public final class Locking {

	/** 고정 결과. */
	public static final class Outcome {
		private final Spin spin;
		private final boolean lockOut;
		private final int lines;
		private final boolean perfectClear;
		private final int[][] cells;

		Outcome(Spin spin, boolean lockOut, int lines, boolean perfectClear, int[][] cells) {
			this.spin = spin;
			this.lockOut = lockOut;
			this.lines = lines;
			this.perfectClear = perfectClear;
			this.cells = cells;
		}

		/** 1단계 T-spin 판정. */
		public Spin spin() {
			return spin;
		}

		/** 3단계 락 아웃. true 이면 4, 5단계는 하지 않았다. */
		public boolean lockOut() {
			return lockOut;
		}

		/** 4단계에서 지운 줄 수. */
		public int lines() {
			return lines;
		}

		/** 5단계 퍼펙트 클리어. */
		public boolean perfectClear() {
			return perfectClear;
		}

		/** 고정한 칸 4개 {행, 열}의 복사본. */
		public int[][] cells() {
			int[][] copy = new int[cells.length][];
			for (int i = 0; i < cells.length; i++) {
				copy[i] = cells[i].clone();
			}
			return copy;
		}
	}

	private Locking() {
	}

	/** 보드를 직접 바꾼다. */
	public static Outcome lock(Grid grid, PieceState s) {
		Spin spin = TSpinDetector.detect(grid, s);
		int[][] cells = s.cells();
		boolean allHidden = true;
		for (int[] c : cells) {
			grid.set(c[0], c[1], s.type().code());
			if (c[0] >= Grid.HIDDEN_ROWS) {
				allHidden = false;
			}
		}
		if (allHidden) {
			return new Outcome(spin, true, 0, false, cells);
		}
		int lines = grid.clearFullLines();
		boolean perfectClear = lines > 0 && grid.isEmpty();
		return new Outcome(spin, false, lines, perfectClear, cells);
	}

	/** RULES 11: 블록 아웃. 스폰 위치에 들어갈 수 없으면 true. */
	public static boolean isBlockOut(Grid grid, PieceType type) {
		PieceState s = PieceState.spawn(type);
		return !grid.fits(type, s.rotation(), s.x(), s.y());
	}
}
