package kr.ac.jbnu.se.tetris.core;

/** T-spin 판정 (RULES 8장). 블록을 보드에 기록하기 <b>전</b>에 부른다. */
public final class TSpinDetector {

	/** 회전 상태별 앞쪽 코너 두 칸의 박스 좌표 {행, 열}. T 가 가리키는 쪽이다. */
	private static final int[][][] FRONT = {
			{ { 0, 0 }, { 0, 2 } },  // 0: 위
			{ { 0, 2 }, { 2, 2 } },  // R: 오른쪽
			{ { 2, 0 }, { 2, 2 } },  // 2: 아래
			{ { 0, 0 }, { 2, 0 } } }; // L: 왼쪽

	private static final int[][] CORNERS = { { 0, 0 }, { 0, 2 }, { 2, 0 }, { 2, 2 } };

	/** 조건 A 가 성립해도 코너 판정을 뒤집는 킥 번호 (다섯 번째 시도, 1×2 이동). */
	private static final int PROMOTING_KICK = 4;

	private TSpinDetector() {
	}

	public static Spin detect(Grid grid, PieceState s) {
		if (s.type() != PieceType.T || !s.lastRotation()) {
			return Spin.NONE;
		}
		int blocked = 0;
		for (int[] c : CORNERS) {
			if (grid.isBlocked(s.y() + c[0], s.x() + c[1])) {
				blocked++;
			}
		}
		if (blocked < 3) {
			return Spin.NONE;
		}
		int frontBlocked = 0;
		for (int[] c : FRONT[s.rotation()]) {
			if (grid.isBlocked(s.y() + c[0], s.x() + c[1])) {
				frontBlocked++;
			}
		}
		if (frontBlocked == 2 || s.kick() == PROMOTING_KICK) {
			return Spin.FULL;
		}
		return Spin.MINI;
	}
}
