package kr.ac.jbnu.se.tetris.sand.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandPiece;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/** 두뇌 {@link SandBrain}: 결정론, 갈 수 있는 자리만 고르기, 지우기와 홀드, 지는 자리는 맨 뒤, 온도. */
class SandBrainTest {

	private static final int G = SandRules.GRAIN;
	private static final List<SandPiece> PREVIEW = List.of(new SandPiece(PieceType.T, SandColor.GREEN),
			new SandPiece(PieceType.S, SandColor.BLUE), new SandPiece(PieceType.Z, SandColor.YELLOW),
			new SandPiece(PieceType.J, SandColor.GREEN), new SandPiece(PieceType.L, SandColor.BLUE));

	private static SandField emptyField() {
		return new SandField(SandRules.WIDTH, SandRules.HEIGHT, new SplitMix64(0));
	}

	private static void fillCell(SandField f, int row, int col, SandColor c) {
		for (int oy = 0; oy < G; oy++) {
			for (int ox = 0; ox < G; ox++) {
				f.set(row * G + oy, col * G + ox, Grain.of(c, 0, oy, ox));
			}
		}
	}

	private static Situation situation(SandField f, SandPiece current, SandPiece hold) {
		short[] grains = new short[SandRules.WIDTH * SandRules.HEIGHT];
		f.copyTo(grains);
		return new Situation(grains, PieceState.spawn(current.type()), current, hold, false, PREVIEW, 1, 1, 0);
	}

	/** 바닥 칸 줄: 0~6 칸 빨강, 7~9 칸 파랑. 빨강 블록을 오른쪽 위에 얹으면 빨강이 좌우 벽을 잇는다. */
	private static SandField redShelf() {
		SandField f = emptyField();
		for (int c = 0; c < SandRules.COLS; c++) {
			fillCell(f, 23, c, c < 7 ? SandColor.RED : SandColor.BLUE);
		}
		return f;
	}

	/** 게임 도중 판들 (상급 AI 가 둔 판, 블록이 막 나온 틱). */
	private static List<Situation> gameSituations(long seed, int count) {
		SandGame g = new SandGame(seed, 3, 0);
		SandAiController ai = new SandAiController(g, SandAiLevel.of(3), seed, 1);
		List<Situation> out = new java.util.ArrayList<>();
		int seen = 0;
		while (!g.over() && out.size() < count) {
			g.advance(ai.poll());
			if (g.pieces() != seen && g.piece() != null) {
				seen = g.pieces();
				if (seen % 3 == 0) {
					out.add(Situation.of(g));
				}
			}
		}
		return out;
	}

	@Test
	void theSameSituationAndNoiseAlwaysGiveTheSamePlan() {
		for (Situation s : gameSituations(41, 6)) {
			for (int level = 1; level <= 5; level++) {
				SandPlan a = new SandBrain(SandAiLevel.of(level)).plan(s, 77);
				SandPlan b = new SandBrain(SandAiLevel.of(level)).plan(s, 77);
				assertEquals(a.ranked(), b.ranked(), "단계 " + level);
				assertEquals(a.chosen(), b.chosen());
				assertEquals(a.candidates(), b.candidates());
			}
		}
	}

	/**
	 * 모든 후보는 지금 칸 판에서 시작 상태(홀드면 꺼낼 블록의 스폰 자리)부터 {@code L R CW CCW} 로 갈 수 있는 (회전, 박스 열) 이고,
	 * 그 조작 수가 가장 짧은 경로와 같다. 예상한 놓일 자리는 같은 회전·열의 판 안 자리이고, 같은 자리는 한 번만 나온다. 이 시험은
	 * 두뇌의 탐색과 따로, core {@link Movement} 로 직접 너비 우선 탐색을 한다.
	 */
	@Test
	void everyTargetIsReachableFromItsStartWithTheShortestPath() {
		int checked = 0;
		for (Situation s : gameSituations(43, 8)) {
			SandPlan plan = new SandBrain(SandAiLevel.of(5)).plan(s, 5);
			assertTrue(plan.candidates() > 0);
			Grid live = s.cellGrid();
			Set<String> keys = new HashSet<>();
			for (SandPlan.Target t : plan.ranked()) {
				PieceState start = t.hold() ? PieceState.spawn(t.piece().type()) : s.piece();
				assertEquals(t.hold() ? s.holdOut() : s.current(), t.piece());
				int d = shortest(live, start, t.rotation(), t.x());
				assertEquals(d + (t.hold() ? 1 : 0), t.path(), "가장 짧은 경로: " + t);
				PieceState l = t.landing();
				assertEquals(t.piece().type(), l.type());
				assertEquals(t.rotation(), l.rotation());
				assertEquals(t.x(), l.x());
				for (int[] c : l.cells()) {
					assertTrue(Grid.inside(c[0], c[1]), "판 안: " + l);
				}
				assertTrue(keys.add((t.hold() ? "h" : "") + java.util.Arrays.deepToString(l.cells())), "같은 자리는 한 번만");
				checked++;
			}
		}
		assertTrue(checked > 100, "확인한 후보 수: " + checked);
	}

	/** {@code L R CW CCW} 너비 우선 탐색으로 (회전, x) 까지의 가장 짧은 조작 수. 못 가면 실패. */
	private static int shortest(Grid g, PieceState start, int rotation, int x) {
		ArrayDeque<PieceState> q = new ArrayDeque<>();
		ArrayDeque<Integer> dist = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		q.add(start);
		dist.add(0);
		seen.add(start.rotation() + "," + start.x() + "," + start.y());
		while (!q.isEmpty()) {
			PieceState s = q.poll();
			int d = dist.poll();
			if (s.rotation() == rotation && s.x() == x) {
				return d;
			}
			for (Op op : new Op[] { Op.L, Op.R, Op.CW, Op.CCW }) {
				PieceState n = Movement.apply(g, s, op);
				if (n != null && seen.add(n.rotation() + "," + n.x() + "," + n.y())) {
					q.add(n);
					dist.add(d + 1);
				}
			}
		}
		throw new AssertionError("갈 수 없는 목표: 회전 " + rotation + ", x " + x);
	}

	@Test
	void itCompletesASpanWhenItCan() {
		Situation s = situation(redShelf(), new SandPiece(PieceType.I, SandColor.RED), null);
		SandPlan plan = new SandBrain(SandAiLevel.of(3)).plan(s, 1);
		SandPlan.Target t = plan.target();
		assertNotNull(t);
		assertFalse(t.hold());
		assertTrue(t.cleared() > 0, "빨강을 오른쪽에 얹어 좌우 벽을 잇는다: " + t);
		int right = 0;
		for (int[] c : t.landing().cells()) {
			right = Math.max(right, c[1]);
		}
		assertTrue(right >= 5, "오른쪽 파랑 위: " + t.landing());
	}

	@Test
	void itHoldsWhenTheHeldPieceCompletesASpanAndOnlyLevelsWithHoldDo() {
		Situation s = situation(redShelf(), new SandPiece(PieceType.O, SandColor.YELLOW),
				new SandPiece(PieceType.I, SandColor.RED));
		SandPlan.Target t = new SandBrain(SandAiLevel.of(3)).plan(s, 1).target();
		assertTrue(t.hold(), "홀드한 빨강 I 로 지운다: " + t);
		assertEquals(new SandPiece(PieceType.I, SandColor.RED), t.piece());
		assertTrue(t.cleared() > 0);
		SandPlan noHold = new SandBrain(SandAiLevel.of(2)).plan(s, 1);
		assertTrue(noHold.ranked().stream().noneMatch(SandPlan.Target::hold), "중급은 홀드를 쓰지 않는다");
		Situation used = new Situation(gridOf(redShelf()), PieceState.spawn(PieceType.O),
				new SandPiece(PieceType.O, SandColor.YELLOW), new SandPiece(PieceType.I, SandColor.RED), true, PREVIEW, 1,
				1, 0);
		assertTrue(new SandBrain(SandAiLevel.of(3)).plan(used, 1).ranked().stream().noneMatch(SandPlan.Target::hold),
				"이번 블록에서 홀드를 이미 썼다");
	}

	private static short[] gridOf(SandField f) {
		short[] grains = new short[SandRules.WIDTH * SandRules.HEIGHT];
		f.copyTo(grains);
		return grains;
	}

	/**
	 * 칸 5~23 행이 칸 열마다 다른 색 세로 띠로 꽉 찬 판(보이는 판의 맨 위 한 줄만 빔). 세로로 세운 I 는 무너져도 보이는 판 위로
	 * 넘치고, 눕힌 I 는 넓게 퍼져 버틴다. 지는 자리는 언제나 지지 않는 자리 뒤에 온다.
	 */
	@Test
	void placementsThatLoseRankBehindEveryPlacementThatDoesNot() {
		SandField f = emptyField();
		for (int r = 5; r < SandRules.ROWS; r++) {
			for (int c = 0; c < SandRules.COLS; c++) {
				fillCell(f, r, c, SandColor.values()[c % SandRules.COLORS]);
			}
		}
		Situation s = situation(f, new SandPiece(PieceType.I, SandColor.RED), null);
		SandPlan plan = new SandBrain(SandAiLevel.of(3)).plan(s, 3);
		List<SandPlan.Target> ranked = plan.ranked();
		assertTrue(ranked.stream().anyMatch(SandPlan.Target::loses), "지는 자리가 있다");
		assertTrue(ranked.stream().anyMatch(t -> !t.loses()), "지지 않는 자리도 있다");
		assertFalse(plan.target().loses());
		boolean seenLoss = false;
		for (SandPlan.Target t : ranked) {
			if (t.loses()) {
				seenLoss = true;
			} else {
				assertFalse(seenLoss, "지는 자리 뒤에 지지 않는 자리가 왔다");
			}
		}
	}

	@Test
	void aPositiveTemperatureSometimesTakesALowerRankedPlacement() {
		List<Situation> ss = gameSituations(47, 12);
		int lower = 0;
		for (Situation s : ss) {
			for (long noise = 0; noise < 4; noise++) {
				assertEquals(0, new SandBrain(SandAiLevel.of(3)).plan(s, noise).chosen(), "온도 0 이면 언제나 1등");
				lower += new SandBrain(SandAiLevel.of(1)).plan(s, noise).chosen() > 0 ? 1 : 0;
			}
		}
		assertTrue(lower > 0, "초급(온도 0.3)은 가끔 1등이 아닌 자리를 고른다");
	}

	@Test
	void theRandomBrainPicksAmongTheSameReachableCandidates() {
		Situation s = gameSituations(53, 2).get(1);
		SandPlan scored = new SandBrain(SandAiLevel.of(3)).plan(s, 9);
		SandPlan random = SandBrain.random(SandAiLevel.of(3)).plan(s, 9);
		assertEquals(scored.candidates(), random.candidates());
		Set<PieceState> a = new HashSet<>();
		Set<PieceState> b = new HashSet<>();
		scored.ranked().forEach(t -> a.add(t.landing()));
		random.ranked().forEach(t -> b.add(t.landing()));
		assertEquals(a, b);
	}
}
