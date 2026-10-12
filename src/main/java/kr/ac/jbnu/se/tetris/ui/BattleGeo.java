package kr.ac.jbnu.se.tetris.ui;

import kr.ac.jbnu.se.tetris.core.Grid;

/**
 * 대전 화면에서 플레이어 한 명의 자리 (칸 크기 c 기준, docs/GAME.md 7장). 왼쪽부터 홀드 카드 4.4 칸, 받을 줄 막대 자리 1.4 칸,
 * 보드 10 칸, 0.6 칸, 미리보기 카드 4.4 칸이다. 대전 화면({@link GamePanel})과 그 그리기 도우미(Battle*)가 같은 값을 쓴다.
 *
 * @param x0     플레이어 자리의 왼쪽 (홀드 카드의 왼쪽)
 * @param top    보드의 맨 위 (화면에 보이는 첫 줄의 위)
 * @param c      칸 크기 (화소)
 * @param boardX 보드 왼쪽
 * @param boardW 보드 너비 (10 칸)
 * @param boardH 보드 높이 (20 칸)
 * @param meterX 받을 줄 막대의 왼쪽
 * @param meterW 받을 줄 막대의 너비
 * @param nextX  미리보기 카드의 왼쪽
 * @param cardW  홀드·미리보기 카드의 너비
 */
record BattleGeo(int x0, int top, int c, int boardX, int boardW, int boardH, int meterX, int meterW, int nextX,
		int cardW) {

	/** 화면에 보이는 첫 보드 행 (그 위는 숨김 행). */
	static final int VISIBLE_TOP = Grid.HIDDEN_ROWS;
	/** 화면에 보이는 행 수. */
	static final int ROWS = Grid.HEIGHT - Grid.HIDDEN_ROWS;

	static BattleGeo of(int x0, int top, int c) {
		int boardX = x0 + (int) Math.round(5.8 * c);
		return new BattleGeo(x0, top, c, boardX, Grid.WIDTH * c, ROWS * c, boardX - (int) Math.round(0.95 * c),
				(int) Math.round(0.55 * c), boardX + Grid.WIDTH * c + (int) Math.round(0.6 * c),
				(int) Math.round(4.4 * c));
	}

	/** 보드와 보드 카드 테두리 사이의 여백. */
	double pad() {
		return 0.18 * c;
	}

	/** 보드 카드의 모서리 지름. */
	double arc() {
		return 0.6 * c;
	}

	/** 보드 행 {@code row}(0 = 맨 위 숨김 행)의 화면 y (칸의 위). */
	int rowY(int row) {
		return top + (row - VISIBLE_TOP) * c;
	}

	/** 열 {@code col} 의 화면 x (칸의 왼쪽). */
	int colX(int col) {
		return boardX + col * c;
	}
}
