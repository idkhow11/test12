package kr.ac.jbnu.se.tetris.sand.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandPiece;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 빠른 시뮬레이션 판 {@link Pile}: 부서짐, 흘러내림(절벽은 밑에서부터), 지우기와 연쇄, 지우기 문턱(sand-0.3), 칸 판, 그리고
 * 엔진의 모래 스텝({@link SandField#settle})과 같은 결과를 내는지. 엔진 쪽 판은 게임과 같은 지우기 문턱으로 만든다.
 */
class PileTest {

	private static final int G = SandRules.GRAIN;

	/** 게임과 같은 크기·지우기 문턱의 빈 엔진 판 (물리 난수 {@code SplitMix64(0)}). */
	private static SandField emptyField() {
		return SandField.standard(new SplitMix64(0));
	}

	private static void fillCell(SandField f, int row, int col, SandColor c) {
		for (int oy = 0; oy < G; oy++) {
			for (int ox = 0; ox < G; ox++) {
				f.set(row * G + oy, col * G + ox, Grain.of(c, 0, oy, ox));
			}
		}
	}

	/** 세로 I 블록이 칸 열 {@code col} 의 칸 행 {@code top}~{@code top + 3} 에 있는 상태. */
	private static PieceState verticalI(int top, int col) {
		return new PieceState(PieceType.I, 1, col - 2, top, false, -1);
	}

	/** 멈춘 판: 이웃한 열의 높이 차가 2 이하(규칙 sand-0.2, 약 63°). */
	private static void assertSettled(Pile p) {
		for (int x = 0; x + 1 < Pile.W; x++) {
			int col = x;
			assertTrue(Math.abs(p.height[x] - p.height[x + 1]) <= 2,
					() -> "이웃한 열의 높이 차는 2 이하: 열 " + col + System.lineSeparator() + p.toText());
		}
	}

	/** 바닥에 세운 I 블록이 같은 색의 가파른 더미로 무너지고, 열 높이는 엔진의 모래 스텝과 같다. */
	@Test
	void aDroppedPieceCrumblesIntoASteepPileOfItsColourLikeTheEngine() {
		Pile p = new Pile();
		p.drop(verticalI(20, 4).cells(), SandColor.RED.code());
		assertEquals(0, p.settle());
		assertSettled(p);
		assertEquals(SandRules.PIECE_GRAINS, p.count());
		for (int x = 0; x < Pile.W; x++) {
			for (int l = 0; l < p.height[x]; l++) {
				assertEquals(SandColor.RED.code(), p.colorAt(x, l));
			}
		}
		assertTrue(p.maxHeight() < 4 * G, "탑은 무너졌다: " + p.maxHeight());
		assertEquals(0, p.height[0], "왼쪽 벽까지 퍼지지는 않는다 (256알 더미의 밑변은 22열)");
		SandField f = emptyField();
		f.place(verticalI(20, 4), SandColor.RED);
		f.settle(SandBrain.SETTLE_STEPS);
		Pile engine = Pile.of(f);
		for (int x = 0; x < Pile.W; x++) {
			assertEquals(engine.height[x], p.height[x], "열 " + x + " 의 높이");
		}
	}

	/**
	 * 빨강 세로 I 위에 파랑 세로 I 가 서 있는 탑(64알 높이)이 무너진 모양. 엔진처럼 절벽의 아래(미끄러질 수 있는 가장 낮은
	 * 알갱이)부터 빠지므로 무너지는 동안 탑이 통째로 가라앉고, 처음에 빠져나간 빨강이 바닥에서 넓게 깔린다(바닥 줄 가운데의 빨강
	 * 토막: 엔진 16열, 이 판 18열). 맨 위 알갱이부터 미끄러뜨리면 빨강이 처음 8열에 갇혀 그대로 남는다(8열). 열 높이는 엔진과
	 * 똑같다. 두 색이 함께 무너지는 이 경우는 알갱이 하나하나의 순서가 엔진과 조금 달라 색이 같은 알갱이는 512알 중 468알이다
	 * (10-07, sand-0.2. AI 가 실제로 고르는 상황인 한 색 블록 하나를 멈춘 판에 놓는 경우는 134만 알 모두 같았다).
	 */
	@Test
	void aTowerCollapsesFromItsFootLikeTheEngine() {
		Pile p = new Pile();
		p.drop(verticalI(20, 4).cells(), SandColor.RED.code());
		p.drop(verticalI(16, 4).cells(), SandColor.BLUE.code());
		assertEquals(0, p.settle());
		assertSettled(p);
		SandField f = emptyField();
		f.place(verticalI(20, 4), SandColor.RED);
		f.place(verticalI(16, 4), SandColor.BLUE);
		f.settle(SandBrain.SETTLE_STEPS);
		Pile engine = Pile.of(f);
		int same = 0;
		for (int x = 0; x < Pile.W; x++) {
			assertEquals(engine.height[x], p.height[x], "열 " + x + " 의 높이");
			for (int l = 0; l < p.height[x]; l++) {
				same += p.colorAt(x, l) == engine.colorAt(x, l) ? 1 : 0;
			}
		}
		assertTrue(same >= 0.85 * 2 * SandRules.PIECE_GRAINS, "색이 같은 알갱이: " + same);
		int mine = redRunAtTheFloor(p);
		int theirs = redRunAtTheFloor(engine);
		assertTrue(theirs >= 12 && mine >= 12, "바닥 줄 가운데의 빨강 토막이 처음 8열보다 넓다: 엔진 " + theirs + ", 이 판 " + mine);
		int peak = 0;
		for (int x = 0; x < Pile.W; x++) {
			if (p.height[x] > p.height[peak]) {
				peak = x;
			}
		}
		assertEquals(SandColor.RED.code(), p.colorAt(peak, p.height[peak] - 1), "꼭대기에는 가운데로 가라앉은 빨강이 남는다");
	}

	/** 바닥 줄(l = 0)에서 탑이 서 있던 가운데 열(36)을 품은 빨강 토막의 폭. */
	private static int redRunAtTheFloor(Pile p) {
		int red = SandColor.RED.code();
		int center = 4 * G + G / 2;
		if (p.colorAt(center, 0) != red) {
			return 0;
		}
		int lo = center;
		int hi = center;
		while (lo > 0 && p.colorAt(lo - 1, 0) == red) {
			lo--;
		}
		while (hi < Pile.W - 1 && p.colorAt(hi + 1, 0) == red) {
			hi++;
		}
		return hi - lo + 1;
	}

	/**
	 * SandGameTest 의 연쇄 판: 23 행은 0~8 칸 파랑과 9 칸 초록, 22 행은 빨강 띠, 21 행 9 칸에 파랑. 빨강 띠(640알)가 지워지면
	 * 파랑 칸이 내려앉아 파랑이 좌우를 잇는다(576 + 64 = 640알). 초록 한 칸만 남는다. 엔진과 같다.
	 */
	@Test
	void aClearLetsTheSandAboveFallIntoAChain() {
		SandField f = emptyField();
		for (int c = 0; c < SandRules.COLS; c++) {
			fillCell(f, 23, c, c < 9 ? SandColor.BLUE : SandColor.GREEN);
			fillCell(f, 22, c, SandColor.RED);
		}
		fillCell(f, 21, 9, SandColor.BLUE);
		Pile p = Pile.of(f);
		assertEquals(1280, p.settle());
		assertEquals(64, p.count());
		assertEquals(1280, f.copy().settle(SandBrain.SETTLE_STEPS), "엔진도 같다");
		for (int x = 0; x < Pile.W; x++) {
			for (int l = 0; l < p.height[x]; l++) {
				assertEquals(SandColor.GREEN.code(), p.colorAt(x, l));
			}
		}
	}

	/**
	 * 지우기 문턱: 블록 하나 분량(256알)의 파랑 띠가 양쪽 벽을 이어도 엔진과 이 판 모두 지우지 않는다. 같은 띠를 칸 한 줄
	 * 두께(640알)로 채우면 둘 다 지운다. 문턱은 엔진 판에서 읽으므로 문턱 0 인 엔진 판에서 옮기면 얇은 띠도 함께 지운다.
	 */
	@Test
	void thinBandsStayAndTheThresholdComesFromTheEngineField() {
		SandField f = emptyField();
		for (int y = SandRules.HEIGHT - 4; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < Pile.W; x++) {
				if (y > SandRules.HEIGHT - 4 || x < 16) {
					f.set(y, x, Grain.of(SandColor.BLUE, 0, 0, 0));
				}
			}
		}
		assertEquals(SandRules.PIECE_GRAINS, f.count());
		Pile p = Pile.of(f);
		assertEquals(SandRules.MIN_CLEAR_GRAINS, p.minClear);
		assertEquals(0, p.settle(), "256알 띠는 남는다");
		assertEquals(0, f.copy().settle(SandBrain.SETTLE_STEPS), "엔진도 같다");
		Pile c = new Pile();
		c.minClear = 0;
		c.copyFrom(p);
		assertEquals(SandRules.MIN_CLEAR_GRAINS, c.minClear, "복사하면 문턱도 옮긴다");

		SandField old = new SandField(SandRules.WIDTH, SandRules.HEIGHT, new SplitMix64(0));
		SandField thick = emptyField();
		for (int y = SandRules.HEIGHT - G; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < Pile.W; x++) {
				thick.set(y, x, Grain.of(SandColor.BLUE, 0, 0, 0));
				if (y >= SandRules.HEIGHT - 4 && (y > SandRules.HEIGHT - 4 || x < 16)) {
					old.set(y, x, Grain.of(SandColor.BLUE, 0, 0, 0));
				}
			}
		}
		assertEquals(SandRules.MIN_CLEAR_GRAINS, Pile.of(thick).settle(), "칸 한 줄(640알)이면 지운다");
		assertEquals(SandRules.MIN_CLEAR_GRAINS, thick.copy().settle(SandBrain.SETTLE_STEPS));
		Pile fromOld = Pile.of(old);
		assertEquals(0, fromOld.minClear, "문턱 0 인 엔진 판의 문턱을 읽는다");
		assertEquals(SandRules.PIECE_GRAINS, fromOld.settle());
		assertEquals(SandRules.PIECE_GRAINS, old.copy().settle(SandBrain.SETTLE_STEPS));
	}

	@Test
	void flashingGrainsAreLeftOutAndGapsInAColumnFall() {
		SandField f = emptyField();
		fillCell(f, 23, 0, SandColor.YELLOW);
		f.markSpans(); // 왼쪽 벽에만 닿아 반짝이지 않는다
		f.set(100, 3, Grain.of(SandColor.RED, 0, 0, 0)); // 떠 있는 알갱이 하나
		Pile p = Pile.of(f);
		assertEquals(G + 1, p.height[3], "떠 있는 알갱이는 떨어져 쌓인다");
		assertEquals(SandColor.RED.code(), p.colorAt(3, G));
		SandField span = emptyField();
		for (int c = 0; c < SandRules.COLS; c++) {
			fillCell(span, 23, c, SandColor.GREEN);
		}
		assertEquals(SandRules.WIDTH * G, span.markSpans().length);
		assertEquals(0, Pile.of(span).count(), "반짝이는(지워질) 알갱이는 옮기지 않는다");
	}

	@Test
	void theCellGridFollowsTheEngineRuleAndCopiesAreIndependent() {
		SandField f = randomBoard(5, 8_000);
		f.settle(SandBrain.SETTLE_STEPS);
		Pile p = Pile.of(f);
		Grid mine = p.cellGrid(new Grid());
		Grid engine = f.cellGrid(new Grid());
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				assertEquals(engine.get(r, c), mine.get(r, c), r + "," + c);
			}
		}
		Pile c = p.copy();
		c.drop(verticalI(0, 9).cells(), SandColor.BLUE.code());
		c.settle();
		assertEquals(Pile.of(f).count(), p.count(), "복사본을 바꿔도 원본은 그대로");
	}

	/**
	 * 엔진의 모래 스텝({@link SandField#settle})과 같은 결과를 내는가. 판마다 블록을 놓고 엔진과 이 판으로 각각 멈출 때까지
	 * 흘린다. 두 가지 판에서 견준다.
	 * <ul>
	 * <li>무작위 손이 2~10초 둔 판(엔진으로 멈춤)에 무작위 블록·색을 무작위 자리에. 규칙 sand-0.2 에서는 블록 하나가 판을 덮지
	 * 못해 지우기는 거의 없으므로 주로 열 높이를 견준다.</li>
	 * <li>상급 AI 가 둔 판(블록이 막 나온 틱, 다섯 블록마다)에 지금 블록(모양과 색)을 칸 판에서 놓을 수 있는 모든 자리에. AI 가 같은
	 * 색을 모아 두므로 지우기가 자주 난다(AI 가 실제로 고르는 상황).</li>
	 * </ul>
	 * 10-07(sand-0.2) AI 판에서 820번 중 엔진이 지운 것 100번, 지우기 판단이 모두 같고 둘 다 지울 때 지운 양도 모두 같았다. 지우기가
	 * 없을 때 열 높이는 평균 0.01 알갱이쯤 다르다. 연쇄는 어느 알갱이가 먼저 미끄러지는지에 따라 커질 수 있어 지운 양은 가끔 다를 수
	 * 있다.
	 */
	@Test
	void agreesWithTheEngineOnClearsAndHeights() {
		Agreement a = new Agreement();
		SplitMix64 r = new SplitMix64(3);
		for (long seed = 1; seed <= 3; seed++) {
			for (long t = 2_000; t <= 10_000; t += 2_000) {
				SandField f = randomBoard(seed, t);
				f.settle(SandBrain.SETTLE_STEPS);
				Grid grid = f.cellGrid(new Grid());
				for (int k = 0; k < 20; k++) {
					PieceType type = PieceType.values()[r.nextInt(PieceType.values().length)];
					int rot = r.nextInt(4);
					int x = r.nextInt(10) - 1;
					if (grid.fits(type, rot, x, 0)) {
						a.compare(f, Movement.hardDrop(grid, new PieceState(type, rot, x, 0, false, -1)),
								SandColor.values()[r.nextInt(SandRules.COLORS)]);
					}
				}
			}
			SandGame g = new SandGame(seed, 1, 0);
			SandAiController ai = new SandAiController(g, SandAiLevel.of(3), seed, 1);
			int seen = 0;
			int boards = 0;
			while (!g.over() && boards < 8) {
				g.advance(ai.poll());
				if (g.pieces() != seen && g.piece() != null) {
					seen = g.pieces();
					if (seen % 5 != 0) {
						continue;
					}
					boards++;
					SandField f = g.copyField();
					f.settle(SandBrain.SETTLE_STEPS);
					Grid grid = f.cellGrid(new Grid());
					SandPiece piece = g.current();
					for (int rot = 0; rot < 4; rot++) {
						for (int x = -2; x < SandRules.COLS; x++) {
							if (grid.fits(piece.type(), rot, x, 0)) {
								a.compare(f, Movement.hardDrop(grid, new PieceState(piece.type(), rot, x, 0, false, -1)),
										piece.color());
							}
						}
					}
				}
			}
		}
		assertTrue(a.n >= 1_000, "견준 수: " + a.n);
		assertTrue(a.clears >= 30, "지우기가 있는 경우도 견줬다: " + a.clears);
		assertTrue(a.same >= a.n * 0.97, "지우기 판단이 같은 비율: " + a.same + " / " + a.n);
		double meanDiff = a.diff / (double) (a.quiet * Pile.W);
		assertTrue(meanDiff < 0.1, "지우기가 없을 때 열 높이 차이 평균: " + meanDiff);
	}

	/** 엔진과 이 판을 견준 결과를 모은다. */
	private static final class Agreement {
		int n;
		int same;
		int clears;
		int quiet;
		long diff;

		/** 멈춘 엔진 판 {@code f} 에 블록을 놓고 엔진과 이 판으로 각각 흘려 견준다 ({@code f} 는 그대로). */
		void compare(SandField f, PieceState landing, SandColor color) {
			SandField e = f.copy();
			e.place(landing, color);
			int byEngine = e.settle(SandBrain.SETTLE_STEPS);
			Pile p = Pile.of(f);
			p.drop(landing.cells(), color.code());
			int byPile = p.settle();
			n++;
			same += (byEngine > 0) == (byPile > 0) ? 1 : 0;
			clears += byEngine > 0 ? 1 : 0;
			if (byEngine == 0 && byPile == 0) {
				quiet++;
				Pile pe = Pile.of(e);
				for (int c = 0; c < Pile.W; c++) {
					diff += Math.abs(pe.height[c] - p.height[c]);
				}
			}
		}
	}

	/** 시드로 정해지는 무작위 손(300ms 마다 회전·열을 골라 하드드롭)으로 {@code t} ms 둔 판. 지면 그때의 판. */
	static SandField randomBoard(long seed, long t) {
		SandGame g = new SandGame(seed, 1, 0);
		SplitMix64 r = new SplitMix64(seed * 31 + 7);
		int wait = 0;
		int rot = 0;
		int tx = 0;
		while (!g.over() && g.time() < t) {
			Input in = null;
			PieceState p = g.piece();
			if (p != null && --wait <= 0) {
				if (wait == 0) {
					rot = r.nextInt(4);
					tx = r.nextInt(10) - 1;
				}
				if (p.rotation() != rot && wait > -3) {
					in = Input.CW;
				} else if (p.x() < tx && wait > -12) {
					in = Input.R;
				} else if (p.x() > tx && wait > -12) {
					in = Input.L;
				} else {
					in = Input.HD;
					wait = 300;
				}
			}
			g.advance(in);
		}
		return g.copyField();
	}
}
