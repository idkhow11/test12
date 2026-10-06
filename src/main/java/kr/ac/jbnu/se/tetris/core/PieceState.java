package kr.ac.jbnu.se.tetris.core;

import java.util.Arrays;
import java.util.Comparator;

/**
 * 움직이는 블록 하나의 상태. 불변이며, 조작은 {@link Movement} 가 새 상태를 만들어 돌려준다.
 * <p>
 * 배치 탐색에서 {@code HashMap} 키로 쓰므로 {@link #equals} / {@link #hashCode} 가 모든 필드를 본다.
 * (분기점 1 당시 IntelliJ 언어 레벨이 1.8 이라 일반 클래스로 썼다. 지금은 Java 21 이므로 record 로 바꿔도 된다.)
 */
public final class PieceState {

	public static final int SPAWN_X = 3;
	/** 보이는 영역(4 행부터) 바로 위. 위쪽 0~1 행은 스폰 직후 위로 킥될 여유로 남긴다. */
	public static final int SPAWN_Y = 2;

	private final PieceType type;
	private final int rotation;
	private final int x;
	private final int y;
	private final boolean lastRotation;
	private final int kick;

	/**
	 * @param x            회전 박스 왼쪽 위 칸의 열
	 * @param y            회전 박스 왼쪽 위 칸의 행
	 * @param lastRotation 마지막으로 성공한 조작이 회전이었는가 (RULES 8장 조건 A)
	 * @param kick         그 회전의 킥 번호(0부터). 마지막 조작이 회전이 아니면 -1
	 */
	public PieceState(PieceType type, int rotation, int x, int y, boolean lastRotation, int kick) {
		this.type = type;
		this.rotation = rotation;
		this.x = x;
		this.y = y;
		this.lastRotation = lastRotation;
		this.kick = kick;
	}

	/** RULES 4장: 회전 상태 0, 박스 (3, 2). */
	public static PieceState spawn(PieceType type) {
		return new PieceState(type, 0, SPAWN_X, SPAWN_Y, false, -1);
	}

	public PieceType type() {
		return type;
	}

	public int rotation() {
		return rotation;
	}

	public int x() {
		return x;
	}

	public int y() {
		return y;
	}

	public boolean lastRotation() {
		return lastRotation;
	}

	public int kick() {
		return kick;
	}

	/** 보드 위의 칸 4개 {행, 열}. 행 오름차순, 같은 행이면 열 오름차순으로 정렬돼 있다. */
	public int[][] cells() {
		int[][] local = type.cells(rotation);
		int[][] out = new int[local.length][];
		for (int i = 0; i < local.length; i++) {
			out[i] = new int[] { y + local[i][0], x + local[i][1] };
		}
		Arrays.sort(out, Comparator.<int[]>comparingInt(c -> c[0]).thenComparingInt(c -> c[1]));
		return out;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof PieceState)) {
			return false;
		}
		PieceState s = (PieceState) o;
		return type == s.type && rotation == s.rotation && x == s.x && y == s.y && lastRotation == s.lastRotation
				&& kick == s.kick;
	}

	@Override
	public int hashCode() {
		int h = type.ordinal();
		h = h * 31 + rotation;
		h = h * 31 + x;
		h = h * 31 + y;
		h = h * 31 + (lastRotation ? 1 : 0);
		return h * 31 + kick;
	}

	@Override
	public String toString() {
		return type + " r" + rotation + " (" + x + "," + y + ")" + (lastRotation ? " 회전 킥" + kick : "");
	}
}
