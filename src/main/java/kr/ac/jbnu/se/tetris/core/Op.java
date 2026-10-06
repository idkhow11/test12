package kr.ac.jbnu.se.tetris.core;

/**
 * 배치 탐색에 쓰는 조작 (RULES 6.1, 14.1). 하드드롭과 홀드는 탐색 간선이 아니라서 여기 없다.
 * <p>
 * 선언 순서가 경로 동점 규칙의 사전식 순서 {@code L < R < CW < CCW < D} 이다(RULES 14.2).
 */
public enum Op {
	L, R, CW, CCW, D;

	public boolean isRotation() {
		return this == CW || this == CCW;
	}
}
