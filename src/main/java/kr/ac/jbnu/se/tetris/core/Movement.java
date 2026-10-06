package kr.ac.jbnu.se.tetris.core;

/**
 * 조작 하나를 적용한다 (RULES 6.1, 6.2). 시간(중력, 락 딜레이)은 다루지 않는다.
 * <p>
 * 실패한 조작은 아무것도 바꾸지 않으므로 {@code null} 을 돌려주고, 호출한 쪽은 원래 상태를 그대로 쓴다.
 */
public final class Movement {

	private Movement() {
	}

	/** 성공하면 새 상태, 실패하면 null. */
	public static PieceState apply(Grid grid, PieceState s, Op op) {
		switch (op) {
		case L:
			return shift(grid, s, -1, 0);
		case R:
			return shift(grid, s, 1, 0);
		case D:
			return shift(grid, s, 0, 1);
		case CW:
			return rotate(grid, s, true);
		case CCW:
			return rotate(grid, s, false);
		default:
			throw new IllegalArgumentException(op.name());
		}
	}

	private static PieceState shift(Grid grid, PieceState s, int dx, int dy) {
		int nx = s.x() + dx;
		int ny = s.y() + dy;
		if (!grid.fits(s.type(), s.rotation(), nx, ny)) {
			return null;
		}
		return new PieceState(s.type(), s.rotation(), nx, ny, false, -1);
	}

	private static PieceState rotate(Grid grid, PieceState s, boolean clockwise) {
		if (s.type() == PieceType.O) {
			// RULES 3.3: O 의 회전은 언제나 실패한 조작이다. 회전 상태는 0 에 머문다.
			return null;
		}
		int to = (s.rotation() + (clockwise ? 1 : 3)) % PieceType.ROTATIONS;
		int[][] tests = Kicks.tests(s.type(), s.rotation(), clockwise);
		for (int i = 0; i < tests.length; i++) {
			int nx = s.x() + tests[i][0];
			int ny = s.y() + tests[i][1];
			if (grid.fits(s.type(), to, nx, ny)) {
				return new PieceState(s.type(), to, nx, ny, true, i);
			}
		}
		return null;
	}

	/** 더 내려갈 수 없을 때까지 내린다. 이동 거리가 0 이면 "마지막 조작"을 바꾸지 않는다(RULES 6.1). */
	public static PieceState hardDrop(Grid grid, PieceState s) {
		int ny = s.y();
		while (grid.fits(s.type(), s.rotation(), s.x(), ny + 1)) {
			ny++;
		}
		if (ny == s.y()) {
			return s;
		}
		return new PieceState(s.type(), s.rotation(), s.x(), ny, false, -1);
	}

	public static boolean isGrounded(Grid grid, PieceState s) {
		return !grid.fits(s.type(), s.rotation(), s.x(), s.y() + 1);
	}
}
