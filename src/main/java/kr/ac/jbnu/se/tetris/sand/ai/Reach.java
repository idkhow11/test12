package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.Arrays;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;

/**
 * 칸 판에서 블록을 {@code L R CW CCW} 만으로(아래로 내리지 않고) 옮겨 닿을 수 있는 (회전, 박스 열) 마다 가장 짧은 경로. 모래
 * AI 는 목표를 (회전, 박스 열) 로 정하고 거기서 하드드롭한다. 멈춘 모래는 칸 단위로도 위가 트인 모양이라(열마다 바닥부터
 * 막힘) 그 열 위 어디서 떨어뜨려도 같은 자리에 놓인다.
 * <p>
 * 너비 우선 탐색이고 조작은 {@code L, R, CW, CCW} 순서로 시도한다. 그래서 같은 (회전, 열) 에 가는 경로가 여럿이면 조작 수가
 * 가장 적고, 그중 먼저 찾은 것(앞 조작이 사전 순으로 앞선 것)을 고른다. 같은 판, 같은 시작 상태면 언제나 같은 결과다.
 */
final class Reach {

	private static final Op[] OPS = { Op.L, Op.R, Op.CW, Op.CCW };
	/** 박스 열 x 는 -3~12, 박스 행 y 는 -4~27 이면 충분하다 (판 밖은 막힌 칸이라 그 밖으로 나가지 못한다). */
	private static final int X0 = 3;
	private static final int XS = 16;
	private static final int Y0 = 4;
	private static final int YS = 32;

	private final PieceState[] state = new PieceState[4 * XS];
	private final int[] dist = new int[4 * XS];
	private final Op[] first = new Op[4 * XS];

	private Reach() {
		Arrays.fill(dist, -1);
	}

	/**
	 * @param start 지금 블록 상태. {@code grid} 에 들어가 있어야 한다(아니면 아무 데도 못 간다)
	 */
	static Reach from(Grid grid, PieceState start) {
		Reach r = new Reach();
		if (!grid.fits(start.type(), start.rotation(), start.x(), start.y())) {
			return r;
		}
		int[] seen = new int[4 * XS * YS]; // 0 = 아직, 아니면 거리 + 1
		PieceState[] queue = new PieceState[4 * XS * YS];
		Op[] firstOf = new Op[4 * XS * YS];
		int head = 0;
		int tail = 0;
		queue[tail++] = start;
		seen[index(start)] = 1;
		while (head < tail) {
			PieceState s = queue[head++];
			int si = index(s);
			int d = seen[si] - 1;
			int key = s.rotation() * XS + s.x() + X0;
			if (r.dist[key] < 0) {
				r.dist[key] = d;
				r.state[key] = s;
				r.first[key] = firstOf[si];
			}
			for (Op op : OPS) {
				PieceState n = Movement.apply(grid, s, op);
				if (n == null) {
					continue;
				}
				int ni = index(n);
				if (seen[ni] != 0) {
					continue;
				}
				seen[ni] = d + 2;
				firstOf[ni] = d == 0 ? op : firstOf[si];
				queue[tail++] = n;
			}
		}
		return r;
	}

	private static int index(PieceState s) {
		return (s.rotation() * XS + s.x() + X0) * YS + s.y() + Y0;
	}

	private static int key(int rotation, int x) {
		int k = x + X0;
		return k < 0 || k >= XS ? -1 : rotation * XS + k;
	}

	/** (회전, 박스 열) 에 갈 수 있는가. */
	boolean reachable(int rotation, int x) {
		int k = key(rotation, x);
		return k >= 0 && dist[k] >= 0;
	}

	/** 그곳까지의 조작 수, 못 가면 -1. 이미 그곳이면 0. */
	int distance(int rotation, int x) {
		int k = key(rotation, x);
		return k < 0 ? -1 : dist[k];
	}

	/** 그곳에 처음 닿은 상태, 못 가면 null. */
	PieceState state(int rotation, int x) {
		int k = key(rotation, x);
		return k < 0 ? null : state[k];
	}

	/** 그곳까지 가는 첫 조작. 이미 그곳이거나 못 가면 null. */
	Op first(int rotation, int x) {
		int k = key(rotation, x);
		return k < 0 ? null : first[k];
	}

	/** 박스 열의 범위 (반복용): {@code minX() <= x <= maxX()}. */
	static int minX() {
		return -X0;
	}

	static int maxX() {
		return XS - X0 - 1;
	}
}
