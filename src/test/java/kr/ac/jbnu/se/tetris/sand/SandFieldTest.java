package kr.ac.jbnu.se.tetris.sand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 모래 물리(sand-0.2 부터), 지우기와 지우기 문턱(sand-0.3), 정착 검사, 블록과의 경계 (package-info 2, 4, 5, 7장). 기대값은 모두
 * 작은 판에서 손으로 계산했다. 글자 판과 크기만 주고 만든 판은 지우기 문턱이 0 이라 작은 띠도 지운다. 문턱은 게임 판
 * ({@link SandField#standard})에서 따로 본다.
 * <p>
 * 물리 난수는 {@code SplitMix64(0)} 이다. RULES 5.1 의 검증값대로 처음 출력은 0x...CDAF(홀수), 다음은 0x...65F4(짝수)라서
 * 양쪽 대각선이 모두 빌 때 처음에는 오른쪽({@code nextInt(2) = 1}), 다음에는 왼쪽(0)으로 간다.
 */
class SandFieldTest {

	private static SandField field(String... rows) {
		return SandField.parse(new SplitMix64(0), rows);
	}

	private static String text(String... rows) {
		return String.join("\n", rows) + "\n";
	}

	private static short red() {
		return Grain.of(SandColor.RED, 0, 0, 0);
	}

	// ------------------------------------------------------------------ 떨어지기와 대각선

	@Test
	void aGrainFallsOneRowPerStepUntilTheFloor() {
		SandField f = field(".R.", "...", "...");
		assertEquals(1, f.step(null));
		assertEquals(text("...", ".R.", "..."), f.toText());
		assertEquals(1, f.step(null));
		assertEquals(text("...", "...", ".R."), f.toText());
		assertEquals(0, f.step(null), "맨 아래 행은 움직이지 않는다");
	}

	/**
	 * 세 알갱이 기둥: 두 스텝 동안 함께 떨어져 바닥에 닿는다. 그 뒤 B(바닥 바로 위)는 대각선 칸의 아래가 바닥이라 못 가고, 맨 위
	 * R 이 오른쪽 아래 두 칸이 비어 미끄러진 다음 떨어진다. 열 높이 2, 1, 0.
	 */
	@Test
	void aColumnCollapsesFromTheTopOnlyWhereTheDropIsMoreThanTwoGrains() {
		SandField f = field("R..", "B..", "G..", "...", "...");
		assertEquals(3, f.step(null), "아래 행부터 훑으므로 붙어 있는 세 알갱이가 한 스텝에 함께 내려간다");
		assertEquals(text("...", "R..", "B..", "G..", "..."), f.toText());
		assertEquals(3, f.step(null));
		assertEquals(text("...", "...", "R..", "B..", "G.."), f.toText());
		// 스텝 2: 3 행 B 는 아래(G)가 막혔고 오른쪽 아래 칸의 아래가 바닥이라 못 간다. 2 행 R 은 오른쪽 옆, 오른쪽 아래, 그 아래가
		// 모두 비어 미끄러진다.
		assertEquals(1, f.step(null));
		assertEquals(text("...", "...", "...", "BR.", "G.."), f.toText());
		assertEquals(1, f.step(null), "미끄러진 R 은 다음 스텝에 떨어진다");
		assertEquals(text("...", "...", "...", "B..", "GR."), f.toText());
		assertEquals(0, f.step(null), "열 높이 2, 1, 0 에서 멈춘다");
	}

	@Test
	void aCliffCollapsesOneGrainAtATimeFromItsFootAndStandsAtTwoGrainsPerColumn() {
		SandField f = field("R...", "R...", "R...", "R...");
		// 스텝 0: 2 행 R 은 오른쪽 아래 칸의 아래가 바닥이라 못 간다. 1 행 R 이 오른쪽 아래로 미끄러지고 0 행 R 은 비워진 자리로
		// 떨어진다
		assertEquals(2, f.step(null));
		assertEquals(text("....", "R...", "RR..", "R..."), f.toText());
		// 스텝 1: 미끄러진 R 이 바닥으로 떨어진다. 1 행 R 은 오른쪽 아래 칸이 비었지만 그 아래가 막혔다
		assertEquals(1, f.step(null));
		assertEquals(text("....", "R...", "R...", "RR.."), f.toText());
		assertEquals(0, f.step(null), "열 높이 3, 1, 0, 0: 이웃한 열의 차가 2 이하라 멈춘다 (sand-0.1 이면 2, 1, 1, 0)");
	}

	@Test
	void aDiagonalMoveNeedsTheSideTheDiagonalAndTheCellBelowItFree() {
		// 가운데 R 은 바로 아래(반짝이는 b, 움직이지 않음)가 막혔고 오른쪽 옆도 막혔다. 왼쪽 옆 (1,0), 왼쪽 아래 (2,0), 그 아래
		// (3,0) 이 모두 비어 왼쪽 아래로 간다.
		SandField f = field("...", ".Rb", ".b.", "...");
		assertEquals(1, f.step(null));
		assertEquals(text("...", "..b", "Rb.", "..."), f.toText());

		assertEquals(0, field("...", ".Rb", ".b.", "R..").step(null),
				"대각선 칸의 바로 아래가 차 있으면 못 간다: 두 알갱이 낭떠러지에서는 선다");
		assertEquals(0, field("...", ".Rb", "bb.", "...").step(null), "대각선 칸이 차 있으면 못 간다");
		assertEquals(0, field("...", "bRb", ".b.", "...").step(null),
				"옆 칸이 막히면 대각선 아래가 비어도 못 간다 (모서리 사이로 빠져나가지 않음)");
		assertEquals(0, field("...", ".Rb", ".b.").step(null), "대각선 칸의 아래가 바닥(판 밖)이면 못 간다");
	}

	@Test
	void whenBothDiagonalsAreFreeThePhysicsRandomPicksTheSide() {
		// 0 행을 왼쪽부터 훑는다(스텝 0 + 행 0 이 짝수). B 는 첫 난수(홀수) → 오른쪽. Y 는 왼쪽 아래가 아직 비어 있어 둘째
		// 난수(짝수) → 왼쪽. 1 행 R 들은 대각선 칸의 아래가 바닥이라 움직이지 않는다.
		SandField f = field(".B...Y.", ".R...R.", ".R...R.");
		assertEquals(2, f.step(null));
		assertEquals(text(".......", ".RB.YR.", ".R...R."), f.toText());
	}

	@Test
	void theScanDirectionAlternatesSoNeitherSideAlwaysWins() {
		// 가운데 아래 칸 하나를 왼쪽 B 와 오른쪽 Y 가 함께 노리면 먼저 훑는 쪽이 차지한다.
		SandField even = field("B.Y", "R.R", "R.R"); // 스텝 0, 행 0: 짝수 → 왼쪽부터 → B
		even.step(null);
		assertEquals(text("..Y", "RBR", "R.R"), even.toText());

		SandField odd = field("...", "B.Y", "R.R", "R.R"); // 스텝 0, 행 1: 홀수 → 오른쪽부터 → Y
		odd.step(null);
		assertEquals(text("...", "B..", "RYR", "R.R"), odd.toText());

		SandField later = field("...", "...", "...");
		later.step(null); // 빈 판에서 스텝 하나를 써서 다음 스텝 번호를 1 로
		for (String cell : new String[] { "0,0,B", "0,2,Y", "1,0,R", "1,2,R", "2,0,R", "2,2,R" }) {
			String[] p = cell.split(",");
			later.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
					Grain.of(SandColor.fromLetter(p[2].charAt(0)), 0, 0, 0));
		}
		later.step(null); // 스텝 1, 행 0: 홀수 → 오른쪽부터 → Y
		assertEquals(text("B..", "RYR", "R.R"), later.toText());
	}

	@Test
	void sandCannotEnterTheMovingPieceCells() {
		SandField f = new SandField(16, 16, new SplitMix64(0));
		f.set(7, 10, red()); // 칸 (0, 1) 의 맨 아래 행
		boolean[] piece = new boolean[2 * 2];
		piece[1 * 2 + 1] = true; // 칸 (1, 1) = 알갱이 8~15 행, 8~15 열
		assertEquals(0, f.step(piece), "아래와 양쪽 아래가 모두 블록 칸이라 블록 위에 얹힌다");
		assertEquals(1, f.step(null), "블록이 비키면 다시 떨어진다");
		assertEquals(red(), f.get(8, 10));
	}

	@Test
	void randomFieldsKeepEveryGrainAndComeToRestWithSlopesOfAtMostTwoGrains() {
		SplitMix64 r = new SplitMix64(42);
		int w = 40;
		int h = 48;
		SandField f = new SandField(w, h, new SplitMix64(7));
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (r.nextInt(10) < 3) {
					f.set(y, x, Grain.of(SandColor.values()[r.nextInt(4)], r.nextInt(4), y % 8, x % 8));
				}
			}
		}
		int n = f.count();
		int steps = 0;
		while (f.step(null) > 0) {
			assertEquals(n, f.count(), "스텝 " + steps);
			assertTrue(++steps < 5_000, "모래는 언젠가 멈춘다");
		}
		short[] all = new short[w * h];
		f.copyTo(all);
		int counted = 0;
		int[] height = new int[w];
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				if (all[y * w + x] != Grain.EMPTY) {
					counted++;
					height[x]++;
				} else {
					for (int above = 0; above < y; above++) {
						assertEquals(Grain.EMPTY, all[above * w + x], "멈춘 판에는 빈칸 위에 떠 있는 알갱이가 없다");
					}
				}
			}
		}
		assertEquals(n, counted);
		int steepest = 0;
		for (int x = 0; x + 1 < w; x++) {
			int d = Math.abs(height[x] - height[x + 1]);
			assertTrue(d <= 2, "이웃한 열의 높이 차는 2 이하(약 63°): 열 " + x);
			steepest = Math.max(steepest, d);
		}
		assertEquals(2, steepest, "45° 보다 가파르게 선다 (sand-0.1 은 차 1 이하)");
	}

	/**
	 * 평평한 바닥에 세운 I 블록(256알)은 이웃한 열의 차가 2 인 가파른 더미로 무너진다. 밑변은 22열, 꼭대기는 21알(2.6칸)
	 * 높이다(sand-0.1 에서는 밑변 32열, 높이 15알). 블록 하나가 판의 너비 80열을 덮지 못하므로 지우기에는 같은 색 블록 여럿이
	 * 맞아떨어져야 한다.
	 */
	@Test
	void aPieceOnAFlatFloorStandsAsASteepPile() {
		SandField f = SandField.standard(1);
		f.place(new PieceState(PieceType.I, 1, 2, 20, false, -1), SandColor.RED); // 세로 I: 칸 열 4, 칸 행 20~23
		int steps = 0;
		while (f.step(null) > 0) {
			assertTrue(++steps < 2_000, "모래는 언젠가 멈춘다");
		}
		assertEquals(SandRules.PIECE_GRAINS, f.count());
		int[] height = new int[SandRules.WIDTH];
		for (int x = 0; x < SandRules.WIDTH; x++) {
			for (int y = 0; y < SandRules.HEIGHT; y++) {
				height[x] += f.get(y, x) != Grain.EMPTY ? 1 : 0;
			}
		}
		int width = 0;
		int top = 0;
		for (int x = 0; x < SandRules.WIDTH; x++) {
			width += height[x] > 0 ? 1 : 0;
			top = Math.max(top, height[x]);
			if (x > 0) {
				assertTrue(Math.abs(height[x] - height[x - 1]) <= 2, "열 " + x);
			}
		}
		assertTrue(width >= 20 && width <= 26, "밑변 " + width + "열");
		assertTrue(top >= 20 && top <= 25, "꼭대기 " + top + "알");
	}

	/** 건너뛰기 없이 모든 행을 훑는 기준 구현 (package-info 4장을 그대로 옮김, 규칙 sand-0.2). */
	private static int referenceStep(short[] g, int w, int h, long s, SplitMix64 rng, boolean[] obstacles) {
		int moved = 0;
		for (int y = h - 2; y >= 0; y--) {
			boolean leftFirst = ((s + y) & 1) == 0;
			for (int k = 0; k < w; k++) {
				int x = leftFirst ? k : w - 1 - k;
				short v = g[y * w + x];
				if (v == Grain.EMPTY || Grain.flashing(v)) {
					continue;
				}
				int to = -1;
				if (free(g, w, y + 1, x, obstacles)) {
					to = (y + 1) * w + x;
				} else {
					// 옆 칸, 대각선 칸, 그 아래 칸이 모두 비어야 하고, 그 아래 칸이 바닥(판 밖)이면 못 간다
					boolean left = x > 0 && y + 2 < h && free(g, w, y, x - 1, obstacles)
							&& free(g, w, y + 1, x - 1, obstacles) && free(g, w, y + 2, x - 1, obstacles);
					boolean right = x < w - 1 && y + 2 < h && free(g, w, y, x + 1, obstacles)
							&& free(g, w, y + 1, x + 1, obstacles) && free(g, w, y + 2, x + 1, obstacles);
					if (left && right) {
						left = rng.nextInt(2) == 0;
						right = !left;
					}
					to = left ? (y + 1) * w + x - 1 : right ? (y + 1) * w + x + 1 : -1;
				}
				if (to >= 0) {
					g[to] = v;
					g[y * w + x] = Grain.EMPTY;
					moved++;
				}
			}
		}
		return moved;
	}

	private static boolean free(short[] g, int w, int y, int x, boolean[] obstacles) {
		int cellCols = (w + SandRules.GRAIN - 1) / SandRules.GRAIN;
		return g[y * w + x] == Grain.EMPTY && !obstacles[(y / SandRules.GRAIN) * cellCols + x / SandRules.GRAIN];
	}

	@Test
	void skippingQuietRowsGivesExactlyTheStepsOfAFullScan() {
		int w = 24; // 칸 3 × 5
		int h = 40;
		for (long seed = 1; seed <= 8; seed++) {
			SplitMix64 r = new SplitMix64(seed * 7919);
			SandField f = new SandField(w, h, new SplitMix64(seed));
			short[] ref = new short[w * h];
			SplitMix64 refRng = new SplitMix64(seed);
			long refSteps = 0;
			boolean[] obstacles = new boolean[3 * 5];
			short[] got = new short[w * h];
			int steps = 0;
			for (int it = 0; it < 4000; it++) {
				int op = r.nextInt(100);
				if (op < 5) { // 위쪽에 알갱이 덩어리를 떨어뜨린다
					int y0 = r.nextInt(h / 2);
					int x0 = r.nextInt(w - 4);
					SandColor c = SandColor.values()[r.nextInt(SandRules.COLORS)];
					for (int i = 0; i < 12; i++) {
						int y = y0 + i / 4;
						int x = x0 + i % 4;
						if (f.get(y, x) == Grain.EMPTY) {
							short v = Grain.of(c, r.nextInt(4), y % 8, x % 8);
							f.set(y, x, v);
							ref[y * w + x] = v;
						}
					}
				} else if (op < 7) { // 알갱이 하나를 뺀다
					int i = r.nextInt(w * h);
					f.set(i / w, i % w, Grain.EMPTY);
					ref[i] = Grain.EMPTY;
				} else if (op < 10) { // 움직이는 블록이 옮겨 간다
					java.util.Arrays.fill(obstacles, false);
					for (int k = r.nextInt(3); k > 0; k--) {
						obstacles[r.nextInt(obstacles.length)] = true;
					}
				} else if (op < 13) { // 지우기 검사, 반쯤은 바로 지운다
					int[] marked = f.markSpans();
					for (int i : marked) {
						ref[i] = f.at(i);
					}
					if (r.nextInt(2) == 0) {
						f.remove(marked);
						for (int i : marked) {
							ref[i] = Grain.EMPTY;
						}
					}
				} else if (op < 14) {
					f.removeFlashing();
					for (int i = 0; i < ref.length; i++) {
						if (Grain.flashing(ref[i])) {
							ref[i] = Grain.EMPTY;
						}
					}
				} else if (op < 15) { // 다른 판으로 옮겨 이어 간다 (copyFrom 뒤 첫 스텝은 모든 행을 훑어야 한다)
					SandField other = new SandField(w, h, new SplitMix64(0));
					for (int i = 0; i < w * h; i++) {
						other.set(i / w, i % w, red()); // 꽉 찬 판: 스텝 한 번에 모든 행이 "움직일 것 없음" 으로 적힌다
					}
					other.step(null);
					other.copyFrom(f);
					f = other;
				} else {
					int a = f.step(obstacles);
					int b = referenceStep(ref, w, h, refSteps++, refRng, obstacles);
					steps++;
					f.copyTo(got);
					assertEquals(b, a, "seed " + seed + " it " + it);
					assertTrue(java.util.Arrays.equals(ref, got), "seed " + seed + " it " + it);
				}
			}
			assertTrue(steps > 3000);
		}
	}

	@Test
	void aCopyIsIndependentAndContinuesIdentically() {
		SandField f = field(".B...Y.", ".R...R.", ".R...R.");
		SandField c = f.copy();
		assertEquals(f.toText(), c.toText());
		assertEquals(2, c.step(null));
		assertEquals(text(".B...Y.", ".R...R.", ".R...R."), f.toText(), "복사본을 움직여도 원본은 그대로");
		f.step(null);
		assertEquals(c.toText(), f.toText(), "같은 난수 상태에서 이어지므로 같은 결과");
	}

	@Test
	void copyFromReusesAFieldAndContinuesLikeTheOriginal() {
		SandField f = field(".B...Y.", ".R...R.", ".R...R.");
		SandField reused = SandField.parse(new SplitMix64(99), "RRRRRRR", "BBBBBBB", "GGGGGGG");
		reused.step(null); // 스텝 수와 난수가 원본과 다른 판
		long version = reused.cellVersion();
		reused.copyFrom(f);
		assertEquals(f.toText(), reused.toText());
		assertEquals(f.count(), reused.count());
		assertTrue(reused.cellVersion() > version, "칸 판을 캐시한 쪽이 다시 만들도록 번호가 오른다");
		reused.step(null);
		assertEquals(text(".......", ".RB.YR.", ".R...R."), reused.toText(), "스텝 번호와 난수 상태까지 옮겼다");
		assertEquals(text(".B...Y.", ".R...R.", ".R...R."), f.toText(), "원본은 그대로");
	}

	// ------------------------------------------------------------------ 칸 (블록과의 경계)

	@Test
	void cellOccupancyFollowsEveryGrainAndTheVersionMovesOnlyWhenACellEmptiesOrFills() {
		SandField f = new SandField(16, 16, new SplitMix64(0)); // 칸 2 × 2
		assertEquals(0, f.cellVersion());
		f.set(1, 1, red());
		assertTrue(f.cellOccupied(0, 0));
		assertEquals(1, f.cellVersion(), "빈 칸이 차면 오른다");
		f.set(0, 0, red());
		assertEquals(1, f.cellVersion(), "이미 찬 칸에 하나 더 놓으면 그대로");
		f.set(0, 0, Grain.EMPTY);
		assertEquals(1, f.cellVersion(), "아직 하나가 남았다");
		for (int i = 0; i < 6; i++) {
			f.step(null); // (1,1) → (7,1): 같은 칸 안
		}
		assertEquals(red(), f.get(7, 1));
		assertEquals(1, f.cellVersion());
		f.step(null); // (7,1) → (8,1): 칸 (0,0) 이 비고 칸 (1,0) 이 찬다
		assertEquals(red(), f.get(8, 1));
		assertEquals(3, f.cellVersion());
		assertFalse(f.cellOccupied(0, 0));
		assertTrue(f.cellOccupied(1, 0));
	}

	@Test
	void theCellGridBlocksEveryCellWithEvenOneGrain() {
		SandField f = SandField.standard(1);
		f.set(100, 5, red()); // 칸 (12, 0)
		f.set(191, 79, red()); // 칸 (23, 9)
		Grid grid = f.cellGrid(new Grid());
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				boolean blocked = r == 12 && c == 0 || r == 23 && c == 9;
				assertEquals(blocked ? Grid.GARBAGE : Grid.EMPTY, grid.get(r, c), r + "," + c);
			}
		}
	}

	@Test
	void placeBreaksAPieceIntoItsFourCellsAndRefusesOccupiedOnes() {
		SandField f = SandField.standard(1);
		PieceState o = PieceState.spawn(PieceType.O); // 칸 (2,4) (2,5) (3,4) (3,5) = 알갱이 16~31 행, 32~47 열
		f.place(o, SandColor.BLUE);
		assertEquals(SandRules.PIECE_GRAINS, f.count());
		for (int y = 16; y < 32; y++) {
			for (int x = 32; x < 48; x++) {
				short v = f.get(y, x);
				assertEquals(SandColor.BLUE, Grain.color(v));
				assertEquals(y % SandRules.GRAIN, Grain.originRow(v));
				assertEquals(x % SandRules.GRAIN, Grain.originCol(v));
			}
		}
		assertThrows(IllegalStateException.class, () -> f.place(o, SandColor.RED));
		assertEquals(SandRules.PIECE_GRAINS, f.count(), "거절하면 판은 그대로");
		PieceState lower = new PieceState(PieceType.O, 0, 3, 10, false, -1); // 칸 (10,4) ~ (11,5)
		f.set(11 * SandRules.GRAIN + 7, 5 * SandRules.GRAIN + 7, red()); // 칸 (11, 5) 구석에 하나
		assertThrows(IllegalStateException.class, () -> f.place(lower, SandColor.RED), "알갱이 하나라도 있으면 막힌 칸");
		assertEquals(SandRules.PIECE_GRAINS + 1, f.count());
	}

	// ------------------------------------------------------------------ 지우기

	@Test
	void aSameColourBandFromWallToWallIsMarkedAndThenRemoved() {
		SandField f = field("....", "RRRR", "BB.B");
		int[] marked = f.markSpans();
		assertEquals(4, marked.length);
		assertEquals(text("....", "rrrr", "BB.B"), f.toText());
		assertEquals(4, f.flashing());
		assertEquals(0, f.step(null), "반짝이는 알갱이는 움직이지 않고 받침이 된다");
		f.remove(marked);
		assertEquals(text("....", "....", "BB.B"), f.toText());
		assertEquals(3, f.count());
		assertEquals(0, f.flashing());
	}

	@Test
	void diagonalContactConnects() {
		assertEquals(4, field("R...", ".R..", "..R.", "...R").markSpans().length);
		// 두 색이 대각선에서 엇갈려도 각자 이어진다(8방향): R 은 (0,1)-(1,2), B 는 (1,1)-(0,2)
		assertEquals(8, field("RRBB", "BBRR").markSpans().length);
	}

	@Test
	void aSpanRemovesTheWholeComponentIncludingBranches() {
		SandField f = field(".R..", ".R..", "RRRR", "B..B");
		assertEquals(6, f.markSpans().length, "벽에 닿지 않은 가지(1 열 위쪽 둘)도 함께");
		assertEquals(text(".r..", ".r..", "rrrr", "B..B"), f.toText());
	}

	@Test
	void aSpanCanWindUpAndDownThroughCorners() {
		SandField f = field("RR..R", ".R.R.", "..R..");
		assertEquals(6, f.markSpans().length, "(0,0)-(0,1)-(1,1)-(2,2)-(1,3)-(0,4)");
		assertEquals(text("rr..r", ".r.r.", "..r.."), f.toText());
	}

	@Test
	void aGapAnotherColourOrOneWallOnlyIsNotASpan() {
		assertEquals(0, field("RR.R", "....").markSpans().length, "틈");
		assertEquals(0, field("RRBR", "....").markSpans().length, "다른 색이 끼면 끊긴다");
		assertEquals(0, field("..RR", "....").markSpans().length, "오른쪽 벽에만 닿음");
		assertEquals(0, field("RrRR", "....").markSpans().length, "이미 반짝이는 알갱이로는 잇지 않는다");
	}

	@Test
	void everySpanningComponentIsMarkedInOneCheck() {
		SandField f = field("RRRR", "....", "BBBB", "....", "RRRR");
		assertEquals(12, f.markSpans().length);
		assertEquals(0, f.markSpans().length, "다시 검사하면 남은 것이 없다");
	}

	@Test
	void removingABandLetsSandFallAndCompleteAnotherSpan() {
		SandField f = field("...B", "RRRR", "BBB.", "GYGY");
		f.remove(f.markSpans());
		assertEquals(text("...B", "....", "BBB.", "GYGY"), f.toText());
		assertEquals(1, f.step(null));
		assertEquals(text("....", "...B", "BBB.", "GYGY"), f.toText());
		assertEquals(4, f.markSpans().length, "떨어진 B 가 (2,2) 와 대각선으로 이어져 왼쪽 벽부터 오른쪽 벽까지 닿는다");
	}

	/**
	 * 게임 판의 맨 아래 세 행(240알)과 그 위 행 왼쪽 16알을 파랑으로 채운다: 블록 하나 분량(256알)이 양쪽 벽을 잇는 얇은 띠.
	 *
	 * @return 띠 알갱이의 자리 (오름차순)
	 */
	private static int[] onePieceBand(SandField f) {
		int w = SandRules.WIDTH;
		int h = SandRules.HEIGHT;
		int[] cells = new int[SandRules.PIECE_GRAINS];
		int n = 0;
		for (int y = h - 4; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (y > h - 4 || x < 16) {
					f.set(y, x, Grain.of(SandColor.BLUE, 0, 0, 0));
					cells[n++] = y * w + x;
				}
			}
		}
		assertEquals(cells.length, n);
		return cells;
	}

	/**
	 * 지우기 문턱 (규칙 sand-0.3): 게임 판은 양쪽 벽을 이은 덩어리라도 알갱이가 640(칸 한 줄)보다 적으면 지우지 않고 얇은 띠로
	 * 적어 둔다. 블록 하나 분량(256알)의 띠는 그대로 남는다. 크기만 주고 만든 판(문턱 0)에서는 같은 띠를 지운다(sand-0.2 와 같다).
	 */
	@Test
	void theGameFieldKeepsAOnePieceBandAndListsItAsThin() {
		SandField f = SandField.standard(1);
		assertEquals(SandRules.MIN_CLEAR_GRAINS, f.minClear());
		int[] band = onePieceBand(f);
		assertEquals(0, f.markSpans().length, "256알은 640알보다 적어 지우지 않는다");
		assertEquals(0, f.flashing());
		assertEquals(SandRules.PIECE_GRAINS, f.count());
		assertEquals(SandRules.PIECE_GRAINS, f.thinCount());
		int[] thin = new int[f.thinCount()];
		f.copyThin(thin);
		Arrays.sort(thin);
		assertArrayEquals(band, thin, "얇은 띠로 적힌 알갱이가 그 띠다");
		for (int y = SandRules.HEIGHT - 3; y < SandRules.HEIGHT; y++) {
			f.set(y, 40, Grain.of(SandColor.RED, 0, 0, 0)); // 40 열에서 띠를 끊는다
		}
		assertEquals(0, f.markSpans().length);
		assertEquals(0, f.thinCount(), "다시 검사하면 지금 판의 얇은 띠만 남는다");

		SandField old = new SandField(SandRules.WIDTH, SandRules.HEIGHT, new SplitMix64(1));
		assertEquals(0, old.minClear());
		onePieceBand(old);
		assertEquals(SandRules.PIECE_GRAINS, old.markSpans().length, "문턱 0 이면 크기와 상관없이 지운다");
		assertEquals(0, old.thinCount());
	}

	/** 문턱 경계: 양쪽 벽을 이은 639알은 남고, 한 알을 더해 640알이 되면 지운다. */
	@Test
	void sixHundredFortyGrainsClearAndOneLessStays() {
		SandField f = SandField.standard(1);
		int w = SandRules.WIDTH;
		int h = SandRules.HEIGHT;
		for (int y = h - SandRules.GRAIN; y < h; y++) {
			for (int x = 0; x < w; x++) {
				if (y != h - SandRules.GRAIN || x != w / 2) {
					f.set(y, x, Grain.of(SandColor.GREEN, 0, 0, 0));
				}
			}
		}
		assertEquals(SandRules.MIN_CLEAR_GRAINS - 1, f.count());
		assertEquals(0, f.markSpans().length);
		assertEquals(SandRules.MIN_CLEAR_GRAINS - 1, f.thinCount());
		f.set(h - SandRules.GRAIN, w / 2, Grain.of(SandColor.GREEN, 0, 0, 0));
		assertEquals(SandRules.MIN_CLEAR_GRAINS, f.markSpans().length, "칸 한 줄(640알)이면 지운다");
		assertEquals(0, f.thinCount(), "지울 덩어리는 얇은 띠가 아니다");
		assertEquals(SandRules.MIN_CLEAR_GRAINS, f.flashing());
	}

	/** 복사본은 지우기 문턱과 마지막으로 찾은 얇은 띠까지 옮겨 받는다(AI 가 게임과 같은 규칙으로 시뮬레이션한다). */
	@Test
	void copiesCarryTheThresholdAndTheThinBands() {
		SandField f = SandField.standard(1);
		onePieceBand(f);
		f.markSpans();
		SandField c = f.copy();
		assertEquals(SandRules.MIN_CLEAR_GRAINS, c.minClear());
		assertEquals(SandRules.PIECE_GRAINS, c.thinCount());
		SandField reused = new SandField(SandRules.WIDTH, SandRules.HEIGHT, new SplitMix64(5));
		reused.copyFrom(f);
		assertEquals(SandRules.MIN_CLEAR_GRAINS, reused.minClear());
		assertEquals(SandRules.PIECE_GRAINS, reused.thinCount());
		assertEquals(0, reused.markSpans().length, "옮겨 받은 문턱으로 검사한다");
		SandField blank = SandField.standard(new SplitMix64(0));
		assertEquals(0, blank.count());
		assertEquals(SandRules.MIN_CLEAR_GRAINS, blank.minClear(), "AI 가 쓰는 빈 게임 판도 같은 문턱");
		assertThrows(IllegalArgumentException.class, () -> new SandField(4, 4, new SplitMix64(0), -1));
	}

	// ------------------------------------------------------------------ 정착

	@Test
	void settledMeansTheCellBelowAndOnEachSideTheDiagonalOrTheCellBelowItAreFloorWallOrSettled() {
		SandField full = field("....", "....", "RBRB", "BRBR");
		assertTrue(full.hasSettledAbove(3), "2 행은 꽉 찬 판 위라 정착");
		assertFalse(full.hasSettledAbove(2), "0~1 행에는 알갱이가 없다");

		assertFalse(field(".R..", "....", "RBRB", "BRBR").hasSettledAbove(2), "떠 있는 알갱이는 정착이 아니다");
		assertTrue(field("....", ".R..", "RBRB", "BRBR").hasSettledAbove(2), "아래와 양쪽 아래가 정착한 알갱이");
		assertTrue(field("....", "R...", "RBRB", "BRBR").hasSettledAbove(2), "벽 쪽은 벽이 막는다");

		SandField two = field("....", ".R..", ".R..", "RBRB");
		assertTrue(two.hasSettledAbove(2),
				"두 단 기둥은 선다: 1 행 R 은 양쪽 아래 칸이 비었지만 그 아래가 정착한 알갱이라 미끄러질 수 없다");
		assertEquals(0, two.copy().step(null), "실제로 움직이지 않는다");

		SandField three = field("....", ".R..", ".R..", ".R..", "RBRB");
		assertFalse(three.hasSettledAbove(2), "세 단 기둥의 맨 위는 왼쪽 아래 두 칸이 비어 언젠가 미끄러진다");
		assertTrue(three.hasSettledAbove(3), "그 아래 R 은 대각선 칸의 아래가 꽉 찬 4 행이라 정착");
		assertEquals(1, three.copy().step(null), "실제로 맨 위 R 이 미끄러진다");

		assertTrue(field("....", ".R..", ".R..").hasSettledAbove(2), "바닥 바로 위의 두 단도 선다 (대각선 칸의 아래가 바닥)");
		assertFalse(field("....", ".R..", "rrrr", "BRBR").hasSettledAbove(2), "반짝이는(곧 사라질) 덩어리 위는 정착이 아니다");
	}

	@Test
	void theSettledTopIgnoresFallingGrainsAndGrainsOnFlashingOnes() {
		assertEquals(5, field("....", "....", "....", "....", "....").settledTop(), "빈 판은 높이");
		assertEquals(3, field("....", ".R..", "....", "RBRB", "BRBR").settledTop(), "떠 있는 1 행 R 은 빼고");
		assertEquals(2, field("....", "....", ".R..", "RBRB", "BRBR").settledTop());
		assertEquals(1, field("....", ".R..", ".R..", "RBRB", "BRBR").settledTop(), "두 단 기둥은 둘 다 정착");
		assertEquals(1, field(".R..", ".R..", ".R..", "RBRB", "BRBR").settledTop(),
				"세 단 기둥의 맨 위 0 행 R 은 왼쪽 아래 두 칸이 비어 미끄러진다");
		assertEquals(4, field("....", "....", ".R..", "rrrr", "BRBR").settledTop(), "반짝이는 줄과 그 위는 정착이 아니다");
	}

	/** 정착의 기준 구현 (package-info 7장을 그대로 옮김): 알갱이마다, 아래 행부터. 바닥(판 밖)은 정착한 것으로 본다. */
	private static boolean[] referenceSettled(SandField f) {
		int w = f.width();
		int h = f.height();
		boolean[] s = new boolean[w * h];
		for (int y = h - 1; y >= 0; y--) {
			for (int x = 0; x < w; x++) {
				short v = f.get(y, x);
				if (v == Grain.EMPTY || Grain.flashing(v)) {
					continue;
				}
				boolean below = solid(s, w, h, y + 1, x);
				boolean left = x == 0 || solid(s, w, h, y + 1, x - 1) || solid(s, w, h, y + 2, x - 1);
				boolean right = x == w - 1 || solid(s, w, h, y + 1, x + 1) || solid(s, w, h, y + 2, x + 1);
				s[y * w + x] = below && left && right;
			}
		}
		return s;
	}

	private static boolean solid(boolean[] settled, int w, int h, int y, int x) {
		return y >= h || settled[y * w + x];
	}

	/**
	 * 정착은 "다시는 움직이지 않음" 이고 모래가 멈추면 빠짐없이 맞다: 무작위 판을 멈출 때까지 흘리는 동안 정착한 알갱이는 하나도
	 * 움직이지 않고(알갱이마다 다른 값이라 다른 알갱이가 들어와도 들킨다), 정착한 높이는 기준 구현과 같으며, 멈춘 판에서는 모든
	 * 알갱이가 정착이다.
	 */
	@Test
	void settledGrainsNeverMoveAndARestingFieldIsSettledEverywhere() {
		SplitMix64 r = new SplitMix64(17);
		for (int trial = 0; trial < 20; trial++) {
			int w = 24;
			int h = 32;
			SandField f = new SandField(w, h, new SplitMix64(trial));
			int id = 0;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					if (r.nextInt(10) < 4) {
						f.set(y, x, Grain.of(SandColor.values()[id & 3], (id >> 2) & 3, (id >> 4) & 7, (id >> 7) & 7));
						id++;
					}
				}
			}
			assertTrue(id < 1024, "알갱이마다 다른 값");
			int steps = 0;
			boolean[] settled;
			while (true) {
				settled = referenceSettled(f);
				int top = h;
				for (int i = 0; i < settled.length; i++) {
					if (settled[i]) {
						top = Math.min(top, i / w);
					}
				}
				assertEquals(top, f.settledTop(), "정착한 높이: 판 " + trial + " 스텝 " + steps);
				short[] before = new short[w * h];
				f.copyTo(before);
				int moved = f.step(null);
				short[] after = new short[w * h];
				f.copyTo(after);
				for (int i = 0; i < settled.length; i++) {
					if (settled[i]) {
						assertEquals(before[i], after[i], "정착한 알갱이가 움직였다: 판 " + trial + " 스텝 " + steps);
					}
				}
				if (moved == 0) {
					break;
				}
				assertTrue(++steps < 5_000);
			}
			for (int i = 0; i < settled.length; i++) {
				assertEquals(grainAt(f, i) != Grain.EMPTY, settled[i], "멈춘 판의 알갱이는 모두 정착: 판 " + trial);
			}
			assertEquals(f.topRow(), f.settledTop());
		}
	}

	private static short grainAt(SandField f, int i) {
		return f.get(i / f.width(), i % f.width());
	}

	// ------------------------------------------------------------------ 시뮬레이션 (AI)

	@Test
	void removeFlashingTakesOnlyTheFlashingGrains() {
		SandField f = field("rB", "Rb");
		assertEquals(2, f.removeFlashing());
		assertEquals(text(".B", "R."), f.toText());
		assertEquals(0, f.flashing());
		assertEquals(2, f.count());
	}

	@Test
	void settleRunsToRestAndClearsEverySpanAtOnce() {
		// 처음 검사에서 R 줄(4), 멈춘 뒤 B 가 (2,3) 에 앉아 B 줄(4)
		SandField f = field("...B", "RRRR", "BBB.", "GYGY");
		assertEquals(8, f.settle(100));
		assertEquals(text("....", "....", "....", "GYGY"), f.toText());
		assertEquals(0, f.flashing());

		SandField flashing = field("RR", "bb");
		assertEquals(2, flashing.settle(100), "반짝이던 줄은 세지 않고 먼저 지운다");
		assertEquals(0, flashing.count());

		SandField capped = field("R..", "...", "...", "...");
		assertEquals(0, capped.settle(2));
		assertEquals(text("...", "...", "R..", "..."), capped.toText(), "스텝 상한에서 멈춘다");
	}
}
