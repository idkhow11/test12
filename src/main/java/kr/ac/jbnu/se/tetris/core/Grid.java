package kr.ac.jbnu.se.tetris.core;

/**
 * 10 × 24 보드 (RULES 1, 2장). <b>0 행이 맨 위, 23 행이 바닥</b>이고, 0~3 행은 화면에 그리지 않는 숨김 영역이다.
 * <p>
 * (분기점 1 이전의 옛 {@code Board} 는 y = 0 이 바닥이었다. 10-04 에 지웠다.)
 * 보드 밖은 모두 막힌 칸으로 본다. 이동 가능 여부와 T-spin 코너 검사가 같은 규칙을 쓴다.
 */
public final class Grid {

	public static final int WIDTH = 10;
	public static final int HEIGHT = 24;
	public static final int HIDDEN_ROWS = 4;

	public static final int EMPTY = 0;
	public static final int GARBAGE = 8;

	private final int[][] cells = new int[HEIGHT][WIDTH];

	public int get(int row, int col) {
		return cells[row][col];
	}

	public void set(int row, int col, int value) {
		cells[row][col] = value;
	}

	public static boolean inside(int row, int col) {
		return row >= 0 && row < HEIGHT && col >= 0 && col < WIDTH;
	}

	public boolean isBlocked(int row, int col) {
		return !inside(row, col) || cells[row][col] != EMPTY;
	}

	/** 회전 박스 왼쪽 위가 (x, y) 일 때 블록이 들어가는가. */
	public boolean fits(PieceType type, int rotation, int x, int y) {
		for (int[] c : type.cells(rotation)) {
			if (isBlocked(y + c[0], x + c[1])) {
				return false;
			}
		}
		return true;
	}

	/** 가득 찬 줄을 모두 지우고 위쪽을 내린다. 지운 줄 수를 돌려준다. */
	public int clearFullLines() {
		int cleared = 0;
		int write = HEIGHT - 1;
		for (int read = HEIGHT - 1; read >= 0; read--) {
			if (isFull(read)) {
				cleared++;
				continue;
			}
			if (write != read) {
				cells[write] = cells[read].clone();
			}
			write--;
		}
		for (; write >= 0; write--) {
			cells[write] = new int[WIDTH];
		}
		return cleared;
	}

	private boolean isFull(int row) {
		for (int v : cells[row]) {
			if (v == EMPTY) {
				return false;
			}
		}
		return true;
	}

	public boolean isEmpty() {
		for (int[] row : cells) {
			for (int v : row) {
				if (v != EMPTY) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * 바닥에 가비지 한 줄을 끼워 넣고 기존 칸을 한 행씩 올린다 (RULES 10.3).
	 *
	 * @return 0 행에 있던 칸이 보드 밖으로 밀려났으면 true (가비지 아웃, RULES 11). 이때 보드는 바꾸지 않는다.
	 */
	public boolean insertGarbageRow(int hole) {
		for (int v : cells[0]) {
			if (v != EMPTY) {
				return true;
			}
		}
		for (int r = 0; r < HEIGHT - 1; r++) {
			cells[r] = cells[r + 1];
		}
		int[] row = new int[WIDTH];
		for (int c = 0; c < WIDTH; c++) {
			row[c] = c == hole ? EMPTY : GARBAGE;
		}
		cells[HEIGHT - 1] = row;
		return false;
	}

	/** 칸의 종류는 무시하고 채워짐/비어 있음만 비교한다. */
	public boolean sameOccupancy(Grid other) {
		for (int r = 0; r < HEIGHT; r++) {
			for (int c = 0; c < WIDTH; c++) {
				if ((cells[r][c] == EMPTY) != (other.cells[r][c] == EMPTY)) {
					return false;
				}
			}
		}
		return true;
	}

	public Grid copy() {
		Grid g = new Grid();
		for (int r = 0; r < HEIGHT; r++) {
			g.cells[r] = cells[r].clone();
		}
		return g;
	}

	/** 픽스처 문자열 형식(RULES 2장). 디버깅과 실패 메시지용. */
	public String toText() {
		StringBuilder sb = new StringBuilder();
		for (int[] row : cells) {
			for (int v : row) {
				sb.append(v == EMPTY ? '.' : v == GARBAGE ? 'G' : v <= 7 ? PieceType.values()[v - 1].letter() : '#');
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
