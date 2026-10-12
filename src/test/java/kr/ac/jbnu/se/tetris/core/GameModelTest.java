package kr.ac.jbnu.se.tetris.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;

/** 한 판 진행 (RULES 4, 7, 13장). 정확한 보드 결과는 fixtures/game 이 확인하고, 여기서는 규칙의 흐름을 본다. */
class GameModelTest {

	/** 가장 낮은 곳에 놓이는(가장 아래 칸의 행이 큰) 홀드 없는 배치. 빈 보드에서 오래 버티는 단순한 선택. */
	private static Placement lowest(GameModel g) {
		Placement best = null;
		int bestRow = -1;
		for (Placement p : g.placements().values()) {
			if (p.hold()) {
				continue;
			}
			int top = Integer.MAX_VALUE;
			for (int[] c : p.state().cells()) {
				top = Math.min(top, c[0]);
			}
			if (top > bestRow) {
				bestRow = top;
				best = p;
			}
		}
		return best;
	}

	@Test
	void twoPlayersWithSameSeedGetSamePieces() {
		GameModel a = new GameModel(7, 1, GameModel.Scheduler.NONE);
		GameModel b = new GameModel(7, 2, GameModel.Scheduler.NONE);
		assertEquals(a.current(), b.current());
		assertEquals(a.preview(), b.preview());
	}

	@Test
	void holdPlacementsAppearOnlyWhenTheyChangeSomething() {
		GameModel g = new GameModel(0, 1, GameModel.Scheduler.NONE); // 현재 I, 미리보기 S …
		Map<String, Placement> first = g.placements();
		assertTrue(first.keySet().stream().anyMatch(k -> k.startsWith("h1|")), "홀드가 비었으면 홀드 배치가 있어야 함");
		assertTrue(first.values().stream().filter(Placement::hold).allMatch(p -> p.state().type() == PieceType.S));
	}

	@Test
	void schedulerSendsEveryTenthPiece() {
		GameModel g = new GameModel(0, 1, GameModel.Scheduler.S_3);
		for (int i = 1; i <= 10; i++) {
			assertEquals(0, g.pendingLines(), i + "번째 전에는 대기 가비지가 없어야 함");
			GameModel.LockResult r = g.apply(lowest(g));
			assertEquals(GameModel.Topout.NONE, r.topout());
			if (r.lines() > 0) {
				return; // 줄을 지우면 이 시험의 전제(대기 가비지 없음)가 흐려지므로 끝낸다
			}
		}
		// 10번째 고정 뒤에 3줄. 구멍 열은 P1 가비지 스트림의 첫 값(시드 0 → 0, rng/basic.json).
		assertEquals(1, g.pending().size());
		assertEquals(3, g.pending().get(0).lines());
		assertEquals(0, g.pending().get(0).hole());
	}

	@Test
	void copyIsIndependentAndDeterministic() {
		GameModel g = new GameModel(12345, 1, GameModel.Scheduler.S_MIX);
		for (int i = 0; i < 5; i++) {
			g.apply(lowest(g));
		}
		GameModel c = g.copy();
		List<String> fromOriginal = new ArrayList<>();
		List<String> fromCopy = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			Placement p = lowest(g);
			fromOriginal.add(p.key() + " " + g.preview() + " " + g.pending().size());
			g.apply(p);
			Placement q = lowest(c);
			fromCopy.add(q.key() + " " + c.preview() + " " + c.pending().size());
			c.apply(q);
		}
		assertEquals(fromOriginal, fromCopy, "같은 선택이면 복사본도 같은 블록·가비지를 받아야 함");

		GameModel before = g.copy();
		c.apply(lowest(c));
		assertEquals(before.pieces(), g.pieces(), "복사본을 진행해도 원본은 그대로");
		assertTrue(before.grid().sameOccupancy(g.grid()));
	}

	/** 9열만 빈 4줄 위에 시드 0 의 첫 블록 I 를 세우면 테트리스 + 퍼펙트 클리어로 공격 14 (fixtures/game 과 같은 판). */
	private static GameModel tetrisReady() {
		Grid board = new Grid();
		for (int r = 20; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH - 1; c++) {
				board.set(r, c, Grid.GARBAGE);
			}
		}
		return new GameModel(0, 1, GameModel.Scheduler.NONE, board);
	}

	private static final String I_IN_WELL = "h0|none|20,9;21,9;22,9;23,9";

	@Test
	void attackCancelsPendingThenSendsTheRest() {
		GameModel g = tetrisReady();
		g.receive(3);
		GameModel.LockResult r = g.applyKey(I_IN_WELL);
		assertEquals(4, r.lines());
		assertTrue(r.perfectClear());
		assertEquals(14, r.attack());
		assertEquals(11, r.sent(), "대기 3줄을 먼저 상쇄하고 남은 11줄을 보낸다");
		assertEquals(0, g.pendingLines());
		assertEquals(0, r.garbageRisen(), "줄을 지운 고정에서는 가비지가 올라오지 않는다");
		assertEquals(11, g.sentTotal());
		assertEquals(14, g.attackTotal());
	}

	@Test
	void attackSmallerThanPendingSendsNothing() {
		GameModel g = tetrisReady();
		g.receive(20);
		GameModel.LockResult r = g.applyKey(I_IN_WELL);
		assertEquals(0, r.sent());
		assertEquals(6, g.pendingLines(), "대기 20줄 - 공격 14줄");
		assertEquals(1, g.pending().size(), "일부만 깎인 덩어리는 구멍 열을 유지한 채 남는다");
	}

	@Test
	void applyRejectsWrongPieceAndFinishedGame() {
		GameModel g = new GameModel(0, 1, GameModel.Scheduler.NONE); // 현재 I
		GameModel other = new GameModel(1, 1, GameModel.Scheduler.NONE);
		Placement foreign = other.placements().values().stream().filter(p -> p.state().type() != PieceType.I)
				.findFirst().orElseThrow();
		assertThrows(IllegalArgumentException.class, () -> g.apply(foreign));
		assertThrows(IllegalArgumentException.class, () -> g.applyKey("h0|none|0,0;0,1;0,2;0,3"));
		assertFalse(g.isOver());
	}

	// ------------------------------------------------------------------ 화면 효과용 자리 정보 (규칙에는 쓰지 않는다)

	@Test
	void lockResultTellsWhereThePieceWentAndWhichRowsWereCleared() {
		GameModel g = tetrisReady();
		GameModel.LockResult r = g.applyKey(I_IN_WELL);
		assertEquals(PieceType.I, r.type());
		assertArrayEquals(new int[][] { { 20, 9 }, { 21, 9 }, { 22, 9 }, { 23, 9 } }, r.placedCells(),
				"놓은 칸은 줄을 지우기 전의 자리");
		assertArrayEquals(new int[] { 20, 21, 22, 23 }, r.clearedRows(), "지운 줄은 지우기 전의 행, 오름차순");
		int[][] codes = r.clearedCodes();
		assertEquals(4, codes.length);
		for (int[] row : codes) {
			assertEquals(10, row.length);
			for (int c = 0; c < 9; c++) {
				assertEquals(Grid.GARBAGE, row[c]);
			}
			assertEquals(PieceType.I.code(), row[9], "지우기 직전의 칸 값: 막 놓은 I 도 들어 있다");
		}
		r.clearedRows()[0] = 99; // 돌려준 배열을 바꿔도 결과는 그대로다
		r.placedCells()[0][0] = 99;
		assertEquals(20, r.clearedRows()[0]);
		assertEquals(20, r.placedCells()[0][0]);
	}

	@Test
	void aLockWithoutClearsHasNoClearedRows() {
		GameModel g = new GameModel(0, 1, GameModel.Scheduler.NONE);
		GameModel.LockResult r = g.apply(lowest(g));
		assertEquals(0, r.lines());
		assertEquals(0, r.clearedRows().length);
		assertEquals(0, r.clearedCodes().length);
		assertEquals(4, r.placedCells().length);
		for (int[] cell : r.placedCells()) {
			assertEquals(PieceType.I.code(), g.cell(cell[0], cell[1]), "줄을 지우지 않았으면 놓은 칸이 그대로 보드에 있다");
		}
	}

	@Test
	void clearedRowsAlwaysMatchTheLineCount() {
		// 방해 줄이 섞인 긴 판: 지운 줄 목록은 언제나 지운 줄 수만큼이고, 같은 선택이면 두 판의 결과가 같다
		GameModel a = new GameModel(4242, 1, GameModel.Scheduler.S_MIX);
		GameModel b = new GameModel(4242, 1, GameModel.Scheduler.S_MIX);
		int clears = 0;
		for (int i = 0; i < 60 && !a.isOver(); i++) {
			Placement p = lowest(a);
			GameModel.LockResult ra = a.apply(p);
			GameModel.LockResult rb = b.applyKey(p.key());
			assertEquals(ra.lines(), rb.lines());
			assertEquals(ra.attack(), rb.attack());
			assertEquals(ra.garbageRisen(), rb.garbageRisen());
			assertEquals(ra.lines(), ra.clearedRows().length, "지운 줄 수와 지운 줄 목록이 같다");
			clears += ra.lines() > 0 ? 1 : 0;
			assertTrue(a.grid().sameOccupancy(b.grid()));
		}
		assertTrue(clears > 0, "줄을 지우는 고정이 한 번은 있었다");
	}
}
