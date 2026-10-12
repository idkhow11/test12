package kr.ac.jbnu.se.tetris.core;

/**
 * 블록 종류와 회전 상태별 칸 배치 (RULES 3장).
 * <p>
 * 선언 순서가 곧 7-bag 의 기준 순서이고(RULES 5.3), {@link #code()} 는 JSON 보드의 칸 값이다(RULES 2장).
 * (옛 {@code Tetrominoes} 와는 순서가 달랐다. 10-04 에 지웠다.)
 */
public enum PieceType {
	I(4, new int[][] { { 1, 0 }, { 1, 1 }, { 1, 2 }, { 1, 3 } }),
	J(3, new int[][] { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 1, 2 } }),
	L(3, new int[][] { { 0, 2 }, { 1, 0 }, { 1, 1 }, { 1, 2 } }),
	O(4, new int[][] { { 0, 1 }, { 0, 2 }, { 1, 1 }, { 1, 2 } }),
	S(3, new int[][] { { 0, 1 }, { 0, 2 }, { 1, 0 }, { 1, 1 } }),
	T(3, new int[][] { { 0, 1 }, { 1, 0 }, { 1, 1 }, { 1, 2 } }),
	Z(3, new int[][] { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 1, 2 } });

	public static final int ROTATIONS = 4;

	private final int boxSize;

	/** cells[rotation][i] = {박스 안 행, 박스 안 열} */
	private final int[][][] cells;

	PieceType(int boxSize, int[][] spawnCells) {
		this.boxSize = boxSize;
		this.cells = new int[ROTATIONS][][];
		cells[0] = spawnCells;
		// O 는 모든 상태의 칸이 같다(RULES 3.3, 회전 조작 자체는 Movement 에서 실패 처리).
		// 나머지는 박스 안에서 시계 방향 회전 (r, c) → (c, n-1-r).
		// 열거형 생성자 안에서도 name() 은 이미 정해져 있다.
		boolean fixed = name().equals("O");
		for (int r = 1; r < ROTATIONS; r++) {
			cells[r] = new int[spawnCells.length][];
			for (int i = 0; i < spawnCells.length; i++) {
				int[] prev = cells[r - 1][i];
				cells[r][i] = fixed ? prev.clone() : new int[] { prev[1], boxSize - 1 - prev[0] };
			}
		}
	}

	public int boxSize() {
		return boxSize;
	}

	/**
	 * 박스 안 칸 좌표. 모든 게임이 공유하는 배열이라 core 패키지 안에서만 읽는다(복사 없이 빠르게 쓰기 위해).
	 * 밖에서는 {@link PieceState#cells()} 로 보드 위 칸을 얻는다.
	 */
	int[][] cells(int rotation) {
		return cells[rotation];
	}

	/** JSON 보드의 칸 값. 1~7. */
	public int code() {
		return ordinal() + 1;
	}

	public char letter() {
		return name().charAt(0);
	}

	public static PieceType fromLetter(char c) {
		return valueOf(String.valueOf(c));
	}
}
