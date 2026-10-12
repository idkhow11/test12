package kr.ac.jbnu.se.tetris.core;

/**
 * 고정 처리 중 보드에 관한 부분 (RULES 7장 1~5단계).
 * 공격 계산(6단계), 상쇄·송신(7단계), 가비지 상승(8단계), 스폰(9단계)은 게임 상태를 가진 쪽이 이어서 한다.
 */
public final class Locking {

	/** 지운 줄이 없을 때 쓰는 빈 배열 (밖으로는 언제나 복사본을 준다). */
	private static final int[] NO_ROWS = new int[0];
	private static final int[][] NO_CODES = new int[0][];

	/** 고정 결과. */
	public static final class Outcome {
		private final PieceType type;
		private final Spin spin;
		private final boolean lockOut;
		private final int lines;
		private final boolean perfectClear;
		private final int[][] cells;
		private final int[] clearedRows;
		private final int[][] clearedCodes;

		Outcome(PieceType type, Spin spin, boolean lockOut, int lines, boolean perfectClear, int[][] cells,
				int[] clearedRows, int[][] clearedCodes) {
			this.type = type;
			this.spin = spin;
			this.lockOut = lockOut;
			this.lines = lines;
			this.perfectClear = perfectClear;
			this.cells = cells;
			this.clearedRows = clearedRows;
			this.clearedCodes = clearedCodes;
		}

		/** 고정한 블록의 종류. */
		public PieceType type() {
			return type;
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
			return copy(cells);
		}

		/** 고정한 칸 4개 {행, 열}의 복사본 (줄을 지우기 전의 자리). {@link #cells()} 와 같다. */
		public int[][] placedCells() {
			return copy(cells);
		}

		/** 4단계에서 지운 줄의 행 번호 (지우기 전의 보드 행, 오름차순). 지운 줄이 없으면 빈 배열. 길이는 {@link #lines()}. */
		public int[] clearedRows() {
			return clearedRows.clone();
		}

		/** 지운 줄마다 지우기 직전의 칸 값 {@code [줄][열]} (열 10개, {@link #clearedRows()} 와 같은 차례). 화면 효과용. */
		public int[][] clearedCodes() {
			return copy(clearedCodes);
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
			return new Outcome(s.type(), spin, true, 0, false, cells, NO_ROWS, NO_CODES);
		}
		// 지울 줄과 그 칸 값을 지우기 전에 적어 둔다(화면 효과용). 규칙에는 쓰지 않고, 줄이 없으면 아무것도 만들지 않는다.
		int[] rows = fullRows(grid);
		int[][] codes = NO_CODES;
		if (rows.length > 0) {
			codes = new int[rows.length][Grid.WIDTH];
			for (int i = 0; i < rows.length; i++) {
				for (int c = 0; c < Grid.WIDTH; c++) {
					codes[i][c] = grid.get(rows[i], c);
				}
			}
		}
		int lines = grid.clearFullLines();
		boolean perfectClear = lines > 0 && grid.isEmpty();
		return new Outcome(s.type(), spin, false, lines, perfectClear, cells, rows, codes);
	}

	/** RULES 11: 블록 아웃. 스폰 위치에 들어갈 수 없으면 true. */
	public static boolean isBlockOut(Grid grid, PieceType type) {
		PieceState s = PieceState.spawn(type);
		return !grid.fits(type, s.rotation(), s.x(), s.y());
	}

	/** 가득 찬 줄의 행 번호 (오름차순). {@link Grid#clearFullLines()} 가 지울 줄과 같다. 없으면 공유하는 빈 배열. */
	private static int[] fullRows(Grid grid) {
		int n = 0;
		int mask = 0; // 행 24개라 int 하나에 들어간다
		for (int r = 0; r < Grid.HEIGHT; r++) {
			if (full(grid, r)) {
				mask |= 1 << r;
				n++;
			}
		}
		if (n == 0) {
			return NO_ROWS;
		}
		int[] rows = new int[n];
		for (int r = 0, i = 0; r < Grid.HEIGHT; r++) {
			if ((mask & 1 << r) != 0) {
				rows[i++] = r;
			}
		}
		return rows;
	}

	private static boolean full(Grid grid, int row) {
		for (int c = 0; c < Grid.WIDTH; c++) {
			if (grid.get(row, c) == Grid.EMPTY) {
				return false;
			}
		}
		return true;
	}

	private static int[][] copy(int[][] a) {
		int[][] out = new int[a.length][];
		for (int i = 0; i < a.length; i++) {
			out[i] = a[i].clone();
		}
		return out;
	}
}
