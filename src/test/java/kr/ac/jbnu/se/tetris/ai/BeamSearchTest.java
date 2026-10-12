package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/** 다음 블록까지 보는 2수 탐색 (TRAINING.md 6.2). 기대값은 모두 손으로 셀 수 있는 작은 점수 모델로 정했다. */
class BeamSearchTest {

	private static final List<PieceType> I_THEN_O = List.of(PieceType.I, PieceType.O, PieceType.O, PieceType.O,
			PieceType.O);
	private static final List<PieceType> ALL_O = List.of(PieceType.O, PieceType.O, PieceType.O, PieceType.O,
			PieceType.O);

	/** 점수 = 이 배치가 지운 줄 수. */
	private static final PlacementScorer LINES = scorer(a -> a.lines());

	/** 점수 = 0 열의 찬 칸 수. */
	private static final PlacementScorer COLUMN0 = scorer(a -> {
		int n = 0;
		for (int r = 0; r < Grid.HEIGHT; r++) {
			n += a.cell(r, 0) != Grid.EMPTY ? 1 : 0;
		}
		return n;
	});

	private interface Score {
		double of(Afterstate a);
	}

	private static PlacementScorer scorer(Score f) {
		return new PlacementScorer() {
			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				double[] out = new double[candidates.size()];
				for (int i = 0; i < out.length; i++) {
					out[i] = f.of(candidates.get(i));
				}
				return out;
			}

			@Override
			public String name() {
				return "test";
			}
		};
	}

	private static Afterstate after(Grid g, List<PieceType> queue, int lines, int attack, int pending) {
		return new Afterstate(g, queue, null, -1, false, lines, attack, pending);
	}

	/** 22, 23 행이 0 열만 빼고 찬 보드(깊이 2 의 우물)에 O 하나를 (row, col) 을 왼쪽 위로 놓은 것. */
	private static Grid wellWithO(int row, int col) {
		Grid g = new Grid();
		for (int r = 22; r < 24; r++) {
			for (int c = 1; c < Grid.WIDTH; c++) {
				g.set(r, c, Grid.GARBAGE);
			}
		}
		for (int r = row; r < row + 2; r++) {
			for (int c = col; c < col + 2; c++) {
				g.set(r, c, PieceType.O.code());
			}
		}
		return g;
	}

	@Test
	void seesTheClearThatTheNextPieceMakes() {
		// 두 후보 모두 줄을 지우지 않아 1수 점수는 0 으로 같다. A 는 O 가 우물(0 열)을 덮었고 B 는 열어 두었다.
		// 다음 블록 I 를 세워 우물에 넣으면 B 에서만 22, 23 행이 차서 2 줄을 지운다. 홀드로 꺼낼 O 로는 못 지운다.
		Afterstate covered = after(wellWithO(20, 0), I_THEN_O, 0, 0, 0);
		Afterstate open = after(wellWithO(20, 4), I_THEN_O, 0, 0, 0);
		BeamSearch.Result r = new BeamSearch(2).search(List.of(covered, open), new double[] { 0, 0 }, 0, Opponent.NONE,
				LINES);
		assertEquals(2, r.size());
		assertEquals(0, r.candidate(0));
		assertEquals(1, r.candidate(1));
		assertArrayEquals(new double[] { 0, 2 }, r.values());
		assertEquals(1, Chooser.choose(r.values(), 0, null), "1수로는 비기지만 2수로는 B");
	}

	@Test
	void beamKeepsTheBestFirstMovesInCandidateOrder() {
		Afterstate a = after(new Grid(), ALL_O, 0, 0, 0);
		// 1수 점수 0.5, 0.9, 0.1, 0.9 에서 beam 2: 0.9 인 둘(1, 3)을 위치 순으로. 같은 점수면 앞선 후보가 먼저 들어간다.
		BeamSearch.Result r = new BeamSearch(2).search(List.of(a, a, a, a), new double[] { 0.5, 0.9, 0.1, 0.9 }, 0,
				Opponent.NONE, LINES);
		assertEquals(2, r.size());
		assertEquals(1, r.candidate(0));
		assertEquals(3, r.candidate(1));
		BeamSearch.Result one = new BeamSearch(1).search(List.of(a, a, a, a), new double[] { 0.9, 0.5, 0.9, 0.1 }, 0,
				Opponent.NONE, LINES);
		assertEquals(1, one.size());
		assertEquals(0, one.candidate(0));
		BeamSearch.Result wide = new BeamSearch(10).search(List.of(a, a), new double[] { 0, 1 }, 0, Opponent.NONE, LINES);
		assertEquals(2, wide.size(), "beam 이 후보보다 많으면 모두 펼친다");
	}

	@Test
	void unknownGarbageHoleIsAveragedOverAllColumns() {
		// 빈 보드, 줄을 안 지웠고 상쇄 뒤 대기 가비지 2 줄 → 2 줄이 올라온다. 구멍 열 h 는 모르므로 10 열을 평균한다.
		// 점수(0 열의 찬 칸)의 최댓값: h ≠ 0 이면 가비지 2 칸 + 그 위 0~1 열에 놓은 O 2 칸 = 4.
		// h = 0 이면 0 열은 가비지의 구멍이라 O 의 2 칸뿐이다. 평균 = (9 × 4 + 2) / 10 = 3.8.
		Afterstate a = after(new Grid(), ALL_O, 0, 0, 2);
		BeamSearch.Result r = new BeamSearch(1).search(List.of(a), new double[] { 0 }, 2, Opponent.NONE, COLUMN0);
		assertEquals(3.8, r.values()[0], 1e-12);
		// 줄을 지운 배치면 가비지가 올라오지 않는다(RULES 7장 8단계): 0 열 최댓값은 O 의 2 칸.
		Afterstate cleared = after(new Grid(), ALL_O, 1, 1, 2);
		assertEquals(2, new BeamSearch(1).search(List.of(cleared), new double[] { 0 }, 3, Opponent.NONE, COLUMN0)
				.values()[0], 1e-12);
	}

	@Test
	void secondPieceKeepsTheRemainingGarbageAndSeesOneHiddenPreviewSlotEmpty() {
		// 대기 10 줄 중 8 줄만 올라오고(RULES 10.3) 둘째 수의 애프터스테이트에는 2 줄이 남는다.
		// 첫 수 뒤 대기열 IOOOO 에서 I 를 놓으면 알고 있는 대기열은 OOOO 4 개다(5 번째 칸은 아직 안 보인다).
		Afterstate a = after(new Grid(), I_THEN_O, 0, 0, 10);
		List<Afterstate> next = BeamSearch.nextStates(a, 8, 3);
		// 홀드 없이 I 를 가비지 위에 눕힌 배치들 중 하나 (줄을 지우지 않음).
		Afterstate flat = next.stream().filter(s -> s.lines() == 0 && s.hold() == null).findFirst().orElseThrow();
		assertEquals(2, flat.pending());
		assertEquals(List.of(PieceType.O, PieceType.O, PieceType.O, PieceType.O), flat.queue());
		assertEquals(Grid.GARBAGE, flat.cell(23, 0));
		assertEquals(Grid.EMPTY, flat.cell(23, 3), "구멍 열");
		assertEquals(Grid.GARBAGE, flat.cell(16, 0), "8 줄이 올라왔다");
		// I 를 세워 3 열의 구멍(깊이 8)에 넣으면 20~23 행을 지운다.
		assertTrue(next.stream().anyMatch(s -> s.lines() == 4));
	}

	@Test
	void firstMoveThatCannotBeFollowedIsALoss() {
		// 가비지 아웃: 0 행에 칸이 있는데 가비지가 올라온다.
		Grid top = new Grid();
		top.set(0, 9, PieceType.I.code());
		top.set(23, 9, PieceType.I.code());
		Afterstate garbageOut = after(top, ALL_O, 0, 0, 1);
		// 블록 아웃: 다음 블록의 스폰 자리(2~3 행의 3~6 열)가 막혀 있다.
		Grid spawn = new Grid();
		for (int c = 3; c <= 6; c++) {
			spawn.set(2, c, PieceType.I.code());
			spawn.set(3, c, PieceType.I.code());
		}
		Afterstate blockOut = after(spawn, ALL_O, 0, 0, 0);
		Afterstate fine = after(new Grid(), ALL_O, 0, 0, 0);
		BeamSearch.Result r = new BeamSearch(3).search(List.of(garbageOut, blockOut, fine), new double[] { 3, 2, 1 }, 0,
				Opponent.NONE, LINES);
		assertArrayEquals(new double[] { BeamSearch.LOSS, BeamSearch.LOSS, 0 }, r.values());
		assertEquals(2, Chooser.choose(r.values(), 0, null), "1수 점수가 가장 낮아도 지지 않는 후보");
		// 지는 후보를 빼면 남는 것은 위치 2 하나. 모두 지는 후보면 그대로 둔다(규칙대로 지게 둔다).
		BeamSearch.Result alive = r.withoutLosses();
		assertEquals(1, alive.size());
		assertEquals(2, alive.candidate(0));
		BeamSearch.Result doomed = new BeamSearch(2).search(List.of(garbageOut, blockOut), new double[] { 3, 2 }, 0,
				Opponent.NONE, LINES);
		assertEquals(2, doomed.withoutLosses().size());
	}

	@Test
	void firstMoveSendsItsAttackToTheOpponent() {
		// 결정 시점 대기 1 줄, 첫 수 공격 4 → 1 줄 상쇄, 3 줄 송신. 둘째 수는 상대 대기 2 + 3 = 5 로 점수를 매긴다.
		// 공격 1 이면 모두 상쇄되어 송신 0, 상대 대기는 2 그대로.
		List<Integer> seen = new ArrayList<>();
		PlacementScorer recorder = new PlacementScorer() {
			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				seen.add(opponent.pending());
				return new double[candidates.size()];
			}

			@Override
			public String name() {
				return "recorder";
			}
		};
		Opponent opp = new Opponent(new Grid(), 2, -1, false, null);
		Afterstate strong = after(new Grid(), ALL_O, 4, 4, 0);
		Afterstate weak = after(new Grid(), ALL_O, 1, 1, 0);
		new BeamSearch(2).search(List.of(strong, weak), new double[] { 1, 1 }, 1, opp, recorder);
		assertEquals(List.of(2, 5), seen, "송신량별로 한 번씩, 송신량 순서로 부른다");
	}

	@Test
	void rejectsBadArguments() {
		assertThrows(IllegalArgumentException.class, () -> new BeamSearch(0));
		Afterstate a = after(new Grid(), ALL_O, 0, 0, 0);
		assertThrows(IllegalArgumentException.class,
				() -> new BeamSearch(1).search(List.of(a), new double[] { 0, 1 }, 0, Opponent.NONE, LINES));
	}
}
