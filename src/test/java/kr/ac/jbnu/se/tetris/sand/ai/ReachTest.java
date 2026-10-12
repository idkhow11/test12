package kr.ac.jbnu.se.tetris.sand.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;

/** 목표 (회전, 박스 열) 까지의 가장 짧은 경로 ({@link Reach}). */
class ReachTest {

	@Test
	void onAnEmptyBoardEveryRotationAndColumnIsReachableWithTheFewestMoves() {
		Reach r = Reach.from(new Grid(), PieceState.spawn(PieceType.T));
		assertEquals(0, r.distance(0, 3));
		assertNull(r.first(0, 3), "이미 그곳");
		assertEquals(3, r.distance(0, 0));
		assertEquals(Op.L, r.first(0, 0));
		assertEquals(4, r.distance(0, 7));
		assertEquals(Op.R, r.first(0, 7));
		assertEquals(1, r.distance(1, 3));
		assertEquals(Op.CW, r.first(1, 3));
		assertEquals(1, r.distance(3, 3));
		assertEquals(Op.CCW, r.first(3, 3));
		assertEquals(2, r.distance(2, 3));
		assertFalse(r.reachable(0, 8), "판 밖으로 나간다");
		int n = 0;
		for (int rot = 0; rot < 4; rot++) {
			for (int x = Reach.minX(); x <= Reach.maxX(); x++) {
				n += r.reachable(rot, x) ? 1 : 0;
			}
		}
		assertEquals(8 + 9 + 8 + 9, n, "T 의 회전마다 판 안의 모든 열");
	}

	@Test
	void sandInTheWayBlocksSidewaysMoves() {
		Grid g = new Grid();
		for (int r = 0; r < Grid.HEIGHT; r++) {
			g.set(r, 6, Grid.GARBAGE); // 6 열이 꼭대기까지 막혔다
		}
		Reach r = Reach.from(g, PieceState.spawn(PieceType.T));
		assertTrue(r.reachable(0, 3), "3~5 열");
		assertFalse(r.reachable(0, 4), "4~6 열은 막혔다");
		assertFalse(r.reachable(0, 7), "막힌 열 너머");
		assertTrue(r.reachable(0, 0));
		Reach none = Reach.from(g, new PieceState(PieceType.T, 0, 5, 2, false, -1));
		assertFalse(none.reachable(0, 5), "시작 상태가 막혀 있으면 아무 데도 못 간다");
	}

	/** 게임 판마다, 모든 블록과 갈 수 있는 모든 목표에서: 첫 조작을 따라가기를 되풀이하면 정확히 {@code distance} 번 만에 닿는다. */
	@Test
	void followingTheFirstMoveReachesEveryTargetInExactlyDistanceMoves() {
		int checked = 0;
		for (long seed = 1; seed <= 2; seed++) {
			Grid g = PileTest.randomBoard(seed, 6_000).cellGrid(new Grid());
			for (PieceType type : PieceType.values()) {
				PieceState start = PieceState.spawn(type);
				if (!g.fits(type, 0, start.x(), start.y())) {
					continue;
				}
				Reach r = Reach.from(g, start);
				for (int rot = 0; rot < 4; rot++) {
					for (int x = Reach.minX(); x <= Reach.maxX(); x++) {
						if (!r.reachable(rot, x)) {
							continue;
						}
						PieceState s = start;
						for (int d = r.distance(rot, x); d > 0; d--) {
							Reach now = Reach.from(g, s);
							assertEquals(d, now.distance(rot, x), "남은 거리는 하나씩 준다");
							Op op = now.first(rot, x);
							assertNotNull(op);
							s = Movement.apply(g, s, op);
							assertNotNull(s, "첫 조작은 언제나 성공한다");
						}
						assertEquals(rot, s.rotation());
						assertEquals(x, s.x());
						checked++;
					}
				}
			}
		}
		assertTrue(checked > 300, "확인한 목표 수: " + checked);
	}
}
