package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;

/** 애프터스테이트의 대기열·홀드·공격·상쇄 (MODEL_IO.md 2장). */
class AfterstateBuilderTest {

	private static final List<PieceType> PREVIEW = List.of(PieceType.I, PieceType.O, PieceType.S, PieceType.Z,
			PieceType.L);

	/** 빈 보드에서 O 를 스폰 자리 바로 아래 바닥에 놓는 배치. */
	private static Placement oOnFloor(boolean hold) {
		Map<String, Placement> all = PlacementFinder.find(new Grid(), PieceState.spawn(PieceType.O), hold);
		return all.get((hold ? "h1" : "h0") + "|none|22,4;22,5;23,4;23,5");
	}

	@Test
	void withoutHoldQueueIsWholePreview() {
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.O, PieceType.T, PREVIEW, -1, false, 0);
		Afterstate a = AfterstateBuilder.build(new Grid(), oOnFloor(false), ctx);
		assertEquals(PREVIEW, a.queue());
		assertEquals(PieceType.T, a.hold());
		assertEquals(4, countFilled(a));
	}

	@Test
	void holdWithFilledHoldKeepsQueueAndSwapsHold() {
		// 현재 T, 홀드 O: 홀드를 써서 O 를 놓으면 T 가 홀드로 가고 가방은 진행하지 않는다.
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.T, PieceType.O, PREVIEW, -1, false, 0);
		Afterstate a = AfterstateBuilder.build(new Grid(), oOnFloor(true), ctx);
		assertEquals(PREVIEW, a.queue());
		assertEquals(PieceType.T, a.hold());
	}

	@Test
	void holdWithEmptyHoldConsumesFirstPreview() {
		// 현재 T, 홀드 없음, p1 = O: O 를 놓고 T 가 홀드로. 알고 있는 대기열은 p2..p5 (4개).
		List<PieceType> preview = List.of(PieceType.O, PieceType.I, PieceType.S, PieceType.Z, PieceType.L);
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.T, null, preview, -1, false, 0);
		Afterstate a = AfterstateBuilder.build(new Grid(), oOnFloor(true), ctx);
		assertEquals(preview.subList(1, 5), a.queue());
		assertEquals(PieceType.T, a.hold());
	}

	@Test
	void longerPreviewNeverLeaksUnrevealedPieces() {
		// 엔진의 가방 대기열을 그대로 넘겨도, 화면에 보이지 않는 6번째 이후 블록이 입력에 섞이면 안 된다.
		List<PieceType> bagQueue = List.of(PieceType.O, PieceType.I, PieceType.S, PieceType.Z, PieceType.L,
				PieceType.J, PieceType.T);
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.T, null, bagQueue, -1, false, 0);
		Afterstate a = AfterstateBuilder.build(new Grid(), oOnFloor(true), ctx);
		assertEquals(bagQueue.subList(1, 5), a.queue(), "홀드가 비어 p1 을 놓았으면 p2..p5 만");
		Afterstate b = AfterstateBuilder.build(new Grid(), oOnFloor(false),
				new AfterstateBuilder.Context(PieceType.O, null, bagQueue, -1, false, 0));
		assertEquals(bagQueue.subList(0, 5), b.queue(), "홀드를 안 쓰면 p1..p5 만");
	}

	@Test
	void noLinesWithPendingMeansGarbageRises() {
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.O, null, PREVIEW, 3, true, 4);
		Afterstate a = AfterstateBuilder.build(new Grid(), oOnFloor(false), ctx);
		assertEquals(0, a.lines());
		assertEquals(0, a.attack());
		assertEquals(4, a.pending());
		assertEquals(-1, a.combo(), "줄을 못 지우면 콤보가 끊긴다");
		assertTrue(a.b2b(), "줄 없는 배치는 B2B 를 끊지 않는다");
		assertTrue(a.garbageRises());
	}

	@Test
	void attackCancelsPending() {
		// 23행이 4~5열만 비어 있다. O 를 넣으면 한 줄(싱글)을 지운다.
		// 규칙 0.3: 싱글 1 + 콤보 2 → 3 의 보너스 COMBO[3] 2 = 공격 3.
		Grid g = new Grid();
		for (int c = 0; c < Grid.WIDTH; c++) {
			if (c != 4 && c != 5) {
				g.set(23, c, 8);
			}
		}
		Placement p = PlacementFinder.find(g, PieceState.spawn(PieceType.O), false)
				.get("h0|none|22,4;22,5;23,4;23,5");
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.O, null, PREVIEW, 2, true, 5);
		Afterstate a = AfterstateBuilder.build(g, p, ctx);
		assertEquals(1, a.lines());
		assertEquals(3, a.attack());
		assertEquals(3, a.combo());
		assertFalse(a.b2b(), "일반 싱글은 B2B 를 끊는다");
		assertEquals(2, a.pending(), "대기 5줄 - 공격 3줄");
		assertFalse(a.garbageRises());
		assertEquals(2, countFilled(a), "지운 줄 위에 O 의 윗줄 두 칸만 남는다");
	}

	@Test
	void lockOutGivesNull() {
		// 숨김 영역에 그대로 고정되는 배치는 지는 수라서 후보에서 빠진다.
		Grid g = new Grid();
		for (int r = 4; r < Grid.HEIGHT; r++) {
			g.set(r, 4, 8);
			g.set(r, 5, 8);
		}
		Placement p = PlacementFinder.find(g, PieceState.spawn(PieceType.O), false)
				.get("h0|none|2,4;2,5;3,4;3,5");
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(PieceType.O, null, PREVIEW, -1, false, 0);
		assertNull(AfterstateBuilder.build(g, p, ctx));
	}

	private static int countFilled(Afterstate a) {
		int n = 0;
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				n += a.cell(r, c) == Grid.EMPTY ? 0 : 1;
			}
		}
		return n;
	}
}
