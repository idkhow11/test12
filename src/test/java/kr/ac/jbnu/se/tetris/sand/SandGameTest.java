package kr.ac.jbnu.se.tetris.sand;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.SevenBag;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 모래 모드 한 판 (package-info 2, 3, 5, 6, 7장): 블록 조작, 틱 순서, 락 딜레이와 흐르는 받침, 부서짐, 고스트, 연쇄와
 * 점수, 콤보, 지우기 문턱과 얇은 띠(sand-0.3), 단계, 패배, 결정론. 판은 80 × 192 이지만 알갱이를 칸 단위나 줄 단위로만 두어
 * 기대값을 손으로 셀 수 있게 했다. 지우려면 양쪽 벽을 이은 덩어리가 640알(칸 한 줄) 이상이어야 하므로 지우는 띠는 칸 한 줄
 * 두께로 둔다.
 * 중력은 대부분 0 으로 두어 블록이 조작한 대로만 움직인다. 모래 스텝은 1 단계에서 10ms 마다(10, 20, 30 … 번째 틱)다.
 */
class SandGameTest {

	private static final int G = SandRules.GRAIN;

	private static SandField emptyField() {
		return new SandField(SandRules.WIDTH, SandRules.HEIGHT, new SplitMix64(0));
	}

	/** 칸 (row, col) 의 알갱이 64개를 색 c 로 채운다. */
	private static void fillCell(SandField f, int row, int col, SandColor c) {
		for (int oy = 0; oy < G; oy++) {
			for (int ox = 0; ox < G; ox++) {
				f.set(row * G + oy, col * G + ox, Grain.of(c, 0, oy, ox));
			}
		}
	}

	/** 첫 블록이 type 인 시드. */
	private static long seedStartingWith(PieceType type) {
		for (long s = 0;; s++) {
			if (new SevenBag(s).peek(0) == type) {
				return s;
			}
		}
	}

	private static void advanceTo(SandGame g, long time) {
		while (g.time() < time && !g.over()) {
			g.advance(null);
		}
	}

	/** 알갱이 행 y 를 왼쪽 벽부터 오른쪽 벽까지 빨강으로 채운다 (80알, 혼자서는 지우기 문턱에 못 미치는 얇은 띠). */
	private static void band(SandGame g, int y) {
		for (int x = 0; x < SandRules.WIDTH; x++) {
			g.field().set(y, x, Grain.of(SandColor.RED, 0, y % G, x % G));
		}
	}

	/**
	 * 알갱이 행 y ~ y + 7 을 빨강으로 채운다: 칸 한 줄 두께, 640알 = 지우기 문턱. 떠 있으면 다음 모래 스텝에 한 행 내려가며
	 * 지워진다.
	 */
	private static void slab(SandGame g, int y) {
		for (int k = 0; k < G; k++) {
			band(g, y + k);
		}
	}

	private static short red() {
		return Grain.of(SandColor.RED, 0, 0, 0);
	}

	private static short[] grains(SandView v) {
		short[] out = new short[SandRules.WIDTH * SandRules.HEIGHT];
		v.copyGrains(out);
		return out;
	}

	// ------------------------------------------------------------------ 블록

	@Test
	void piecesSpawnAndMoveOnTheCellGridLikeTheClassicGame() {
		SandGame g = new SandGame(seedStartingWith(PieceType.T), 1, 0, null, 0);
		PieceState p = g.piece();
		assertEquals(PieceType.T, p.type());
		assertEquals(PieceState.SPAWN_X, p.x());
		assertEquals(PieceState.SPAWN_Y, p.y());
		g.advance(Input.L);
		assertEquals(PieceState.SPAWN_X - 1, g.piece().x());
		g.advance(Input.CW);
		assertEquals(1, g.piece().rotation());
		assertEquals(23 - 2, g.ghost().y(), "T(R) 박스의 맨 아래 칸(박스 2 행)이 바닥 23 행에 닿는 자리");
		assertEquals(1, g.spawns());
	}

	@Test
	void aCellWithEvenOneGrainBlocksThePiece() {
		SandField f = emptyField();
		// 칸 (3, 2) 의 오른쪽 아래 구석에 반짝이는(움직이지 않는) 알갱이 하나
		f.set(3 * G + G - 1, 2 * G + G - 1, Grain.flash(Grain.of(SandColor.RED, 0, 0, 0)));
		SandGame g = new SandGame(seedStartingWith(PieceType.T), 1, 0, f, 0);
		g.advance(Input.L); // T 의 (3, 3) 칸이 (3, 2) 로 가야 한다
		assertEquals(PieceState.SPAWN_X, g.piece().x(), "막혀서 실패한 조작");
	}

	@Test
	void aHardDroppedPieceCrumblesIntoTwoHundredFiftySixGrainsOfItsColour() {
		SandGame g = new SandGame(seedStartingWith(PieceType.I), 1, 0, null, 0);
		SandPiece first = g.current();
		SandPiece second = g.preview().get(0);
		PieceState ghost = g.ghost();
		short[] expected = new short[SandRules.PIECE_GRAINS];
		g.pieceGrains(expected);
		g.advance(Input.HD); // 첫 모래 스텝은 10 번째 틱이라 이 틱에는 알갱이가 그대로다
		assertEquals(1, g.pieces());
		assertEquals(SandRules.PIECE_GRAINS, g.field().count());
		int[][] cells = ghost.cells();
		for (int k = 0; k < cells.length; k++) {
			for (int oy = 0; oy < G; oy++) {
				for (int ox = 0; ox < G; ox++) {
					short v = g.grain(cells[k][0] * G + oy, cells[k][1] * G + ox);
					assertEquals(first.color(), Grain.color(v));
					assertEquals(oy, Grain.originRow(v));
					assertEquals(ox, Grain.originCol(v));
					assertEquals(expected[(k * G + oy) * G + ox], v, "떨어지던 블록의 무늬 그대로 내려앉는다");
				}
			}
		}
		assertEquals(second, g.current(), "다음 블록이 바로 나온다");
		assertNotNull(g.piece());
		LockEvent lock = g.lastLock();
		assertEquals(1, lock.time());
		assertEquals(ghost, lock.piece());
		assertEquals(first.color(), lock.color());
		assertTrue(lock.hardDrop());
	}

	@Test
	void lockDelayIsFiveHundredGroundedTicksAsInTheClassicGame() {
		SandGame g = new SandGame(1, 1, 0, null, 0);
		while (!g.grounded()) {
			g.advance(Input.D);
		}
		assertEquals(SandRules.LOCK_DELAY_MS - 1, g.lockTimer(), "닿게 만든 틱이 첫 번째 틱");
		for (int i = 2; i < SandRules.LOCK_DELAY_MS; i++) {
			g.advance(null);
			assertEquals(0, g.pieces(), i + " 번째 틱");
		}
		g.advance(null);
		assertEquals(1, g.pieces(), "500 번째 틱에 고정");
		assertFalse(g.lastLock().hardDrop());
		assertEquals(g.time(), g.lastLock().time());
	}

	/**
	 * 틱 순서(입력 → 중력 → 락 딜레이 → 모래): 바닥 칸 줄의 빨강 띠는 시작하자마자 찾아 200 번째 틱에 사라진다. 블록은 그 띠
	 * 위에서 리셋을 14번 해 두고, 200 번째 틱에 15번째 리셋을 한다. 15번째 리셋은 시간을 주지 않으므로 그 틱의 락 딜레이에서
	 * 띠 위에 고정되고, 같은 틱의 모래 단계에서 띠가 사라진 뒤 부서진 알갱이가 한 줄 내려간다.
	 */
	@Test
	void theFifteenthResetLocksAtOnceBeforeTheSandPhaseOfTheSameTick() {
		SandField f = emptyField();
		for (int c = 0; c < SandRules.COLS; c++) {
			fillCell(f, 23, c, SandColor.RED);
		}
		SandGame g = new SandGame(seedStartingWith(PieceType.I), 1, 0, f, 0);
		assertEquals(1, g.clears());
		for (int i = 0; i < 19; i++) {
			g.advance(Input.D); // 3 행 → 22 행 (19번째 틱에 띠에 닿는다)
		}
		assertTrue(g.grounded());
		for (int i = 0; i < 14; i++) {
			g.advance(i % 2 == 0 ? Input.L : Input.R); // 20~33 번째 틱: 바닥에서 리셋 14회, 제자리로 돌아온다
		}
		assertEquals(14, g.resets());
		advanceTo(g, SandRules.FLASH_MS - 1);
		assertEquals(0, g.pieces(), "마지막 리셋부터 500ms 가 아직 지나지 않았다");
		g.advance(Input.L);
		assertEquals(SandRules.FLASH_MS, g.time());
		assertEquals(1, g.pieces(), "15번째 리셋을 한 틱에 바로 고정");
		LockEvent lock = g.lastLock();
		assertEquals(SandRules.FLASH_MS, lock.time());
		assertFalse(lock.hardDrop());
		assertEquals(new PieceState(PieceType.I, 0, 2, 21, false, -1), lock.piece(), "띠 바로 위 22 행, 2~5 열");
		assertEquals(SandRules.PIECE_GRAINS, g.field().count(), "띠 640알은 같은 틱에 사라졌다");
		assertEquals(Grain.EMPTY, g.grain(22 * G, 2 * G), "부서진 알갱이는 같은 틱의 모래 스텝으로 한 줄 내려갔다");
		assertNotEquals(Grain.EMPTY, g.grain(23 * G, 2 * G));
	}

	@Test
	void softDropIsTwentyCellsPerSecond() {
		SandGame g = new SandGame(1, 1, 0, null, 0); // 중력 0
		g.setSoftDrop(true);
		advanceTo(g, 49);
		assertEquals(PieceState.SPAWN_Y, g.piece().y());
		g.advance(null);
		assertEquals(PieceState.SPAWN_Y + 1, g.piece().y(), "max(0, 20000): 50 번째 틱에 한 칸");
		advanceTo(g, 100);
		assertEquals(PieceState.SPAWN_Y + 2, g.piece().y());
		g.setSoftDrop(false);
		advanceTo(g, 1000);
		assertEquals(PieceState.SPAWN_Y + 2, g.piece().y(), "떼면 다시 중력 0");
	}

	@Test
	void aPieceWhoseSupportIsClearedDoesNotLockInTheAir() {
		SandField f = emptyField();
		for (int c = 0; c < SandRules.COLS; c++) {
			fillCell(f, 23, c, SandColor.RED); // 바닥 줄이 왼쪽 벽부터 오른쪽 벽까지 빨강
		}
		SandGame g = new SandGame(seedStartingWith(PieceType.I), 1, 0, f, 0); // 중력 0
		assertEquals(1, g.clears(), "시작하자마자 찾아 200ms 반짝인다");
		while (!g.grounded()) {
			g.advance(Input.D); // 반짝이는 띠 위(바닥 22 행)에 닿는다
		}
		PieceState resting = g.piece();
		assertEquals(22, resting.cells()[0][0]);
		while (g.time() < SandRules.FLASH_MS) {
			g.advance(null);
			assertEquals(0, g.pieces(), "띠가 남아 있는 동안은 500ms 가 차지 않는다");
		}
		assertFalse(g.grounded(), "띠가 사라지면 받침이 없다");
		for (int i = 0; i < 3000; i++) {
			g.advance(null);
		}
		assertEquals(0, g.pieces(), "공중에서는 고정되지 않는다");
		assertEquals(resting, g.piece(), "중력 0 이라 그 자리에 떠 있다");

		g.advance(Input.D); // 바닥에 닿게 만든 틱이 첫 번째 틱
		assertTrue(g.grounded());
		for (int i = 2; i < SandRules.LOCK_DELAY_MS; i++) {
			g.advance(null);
		}
		assertEquals(0, g.pieces());
		g.advance(null);
		assertEquals(1, g.pieces(), "다시 닿은 뒤 500ms 에 고정");
	}

	@Test
	void aPieceRidingOnFallingSandLocksOnlyAfterTheSandStops() {
		SandField f = emptyField();
		// 1 알갱이 두께의 층을 공중(알갱이 40 행, 칸 5 행)에 둔다. 칸마다 색을 번갈아 좌우로 이어지지 않게 한다.
		for (int x = 0; x < SandRules.WIDTH; x++) {
			f.set(40, x, Grain.of((x / G) % 2 == 0 ? SandColor.RED : SandColor.BLUE, 0, 0, x % G));
		}
		// 소프트드롭(초당 20칸, 50ms 에 한 칸)은 층(10ms 에 한 알갱이 = 80ms 에 한 칸)보다 빨라서 블록이 층을 따라잡아
		// 얹히고, 그 뒤로는 층과 함께 내려간다(층을 뚫고 지나가지 않는다).
		SandGame g = new SandGame(seedStartingWith(PieceType.O), 1, 0, f, 0);
		g.setSoftDrop(true);
		long layerStopped = -1;
		long lastLanding = -1;
		int liftOffs = 0;
		boolean wasGrounded = false;
		while (g.pieces() == 0 && g.time() < 10_000) {
			g.advance(null);
			if (g.pieces() == 0) {
				if (wasGrounded && !g.grounded()) {
					liftOffs++; // 받침(층)이 아래 칸으로 흘러가 버렸다: 타이머가 멈추고 다시 떨어진다
				}
				if (!wasGrounded && g.grounded()) {
					lastLanding = g.time();
				}
				wasGrounded = g.grounded();
			}
			if (layerStopped < 0 && g.surfaceRow() == SandRules.HEIGHT - 1) {
				layerStopped = g.time();
			}
		}
		assertEquals(1, g.pieces());
		assertTrue(liftOffs >= 10, "층에 얹혔다 떨어지기를 되풀이했다: " + liftOffs);
		assertEquals((SandRules.HEIGHT - 1 - 40) * 10, layerStopped, "층은 10ms 마다 한 알갱이씩 바닥까지");
		assertTrue(lastLanding >= (23 * G - 40) * 10, "층이 맨 아래 칸 줄(23 행)에 들어간 뒤에야 마지막으로 닿았다: " + lastLanding);
		assertEquals(lastLanding + SandRules.LOCK_DELAY_MS - 1, g.time(), "마지막으로 닿은 틱부터 500 번째 틱에 고정");
		// O 는 층이 있는 맨 아래 칸 줄 바로 위(21~22 행)에서 부서졌다. 이 틱에는 모래 스텝이 없어 그대로다.
		assertNotEquals(Grain.EMPTY, g.grain(22 * G + G - 1, 4 * G));
		assertEquals(Grain.EMPTY, g.grain(20 * G + G - 1, 4 * G));
	}

	/**
	 * 고스트는 지금 하드드롭하면 놓일 자리다. 칸 (12, 4~5) 에 떠 있는 알갱이 한 줄(100 행)이 10ms 마다 한 줄씩 내려가
	 * 40 번째 틱에 104 행(칸 13 행)으로 넘어가면 고스트도 한 칸 내려간다.
	 */
	@Test
	void theGhostIsWhereAHardDropLandsNowAndFollowsFallingSand() {
		SandField f = emptyField();
		for (int x = 4 * G; x < 6 * G; x++) {
			f.set(100, x, Grain.of(SandColor.BLUE, 0, 4, x % G));
		}
		SandGame g = new SandGame(seedStartingWith(PieceType.O), 1, 0, f, 0);
		assertEquals(10, g.ghost().y(), "O(4~5 열)가 칸 12 행 바로 위, 10~11 행에 놓인다");
		advanceTo(g, 39);
		assertNotEquals(Grain.EMPTY, g.grain(103, 4 * G));
		assertEquals(10, g.ghost().y());
		g.advance(null);
		assertNotEquals(Grain.EMPTY, g.grain(104, 4 * G));
		assertEquals(11, g.ghost().y());
		PieceState ghost = g.ghost();
		g.advance(Input.HD);
		assertEquals(ghost, g.lastLock().piece(), "하드드롭은 그 틱 처음의 고스트 자리에 놓인다");
	}

	// ------------------------------------------------------------------ 홀드, 미리보기, 색

	@Test
	void holdKeepsTheColourAndWorksOncePerPiece() {
		SandGame g = new SandGame(3, 1, 0, null, 0);
		SandPiece first = g.current();
		SandPiece next = g.preview().get(0);
		g.advance(Input.H);
		assertEquals(first, g.hold());
		assertEquals(next, g.current());
		assertTrue(g.holdUsed());
		g.advance(Input.H);
		assertEquals(first, g.hold(), "블록 하나에 한 번");
		g.advance(Input.HD);
		assertFalse(g.holdUsed());
		SandPiece placed = g.current();
		List<SandPiece> queued = g.preview();
		g.advance(Input.H);
		assertEquals(first, g.current(), "꺼낸 블록은 모양과 색이 그대로");
		assertEquals(placed, g.hold());
		assertEquals(queued, g.preview(), "맞바꾸기는 대기열을 진행하지 않는다");
	}

	@Test
	void holdFailsWhenTheOutgoingPieceCannotSpawn() {
		SandGame g = new SandGame(3, 1, 0, null, 0);
		for (int i = 0; i < 8; i++) {
			g.advance(Input.D); // 지금 블록을 스폰 자리에서 치운다
		}
		SandPiece out = g.preview().get(0);
		int[] cell = PieceState.spawn(out.type()).cells()[0];
		g.field().set(cell[0] * G, cell[1] * G, Grain.flash(Grain.of(SandColor.RED, 0, 0, 0))); // 움직이지 않는 알갱이
		g.advance(Input.H);
		assertNull(g.hold(), "꺼낼 자리가 막혀 실패");
		assertFalse(g.holdUsed());
		g.field().set(cell[0] * G, cell[1] * G, Grain.EMPTY);
		g.advance(Input.H);
		assertEquals(out, g.current());
	}

	@Test
	void shapesFollowTheClassicBagAndColoursComeFromABagOfTwelve() {
		long seed = 11;
		PieceQueue q = new PieceQueue(seed);
		SevenBag bag = new SevenBag(seed);
		Map<SandColor, Integer> count = new EnumMap<>(SandColor.class);
		for (int i = 0; i < 48; i++) {
			SandPiece p = q.next();
			assertEquals(bag.next(), p.type(), "모양 순서는 같은 시드의 고전 게임과 같다");
			count.merge(p.color(), 1, Integer::sum);
			if ((i + 1) % 12 == 0) {
				for (SandColor c : SandColor.values()) {
					assertEquals((i + 1) / 12 * SandRules.COLOR_COPIES, count.getOrDefault(c, 0), c + " 는 12개마다 3번");
				}
			}
		}
		SandGame g = new SandGame(seed, 1, 0);
		PieceQueue same = new PieceQueue(seed);
		assertEquals(same.next(), g.current());
		List<SandPiece> expect = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			expect.add(same.peek(i));
		}
		assertEquals(expect, g.preview(), "미리보기 5개에 색까지 보인다");
	}

	// ------------------------------------------------------------------ 지우기, 연쇄, 점수

	/**
	 * 23 행: 0~8 칸 파랑, 9 칸 초록. 22 행: 빨강 띠(좌우를 잇는다). 21 행: 9 칸에만 파랑 덩어리.
	 * 시작하자마자 빨강 띠(640알)를 찾고, 200ms 뒤 띠가 사라지면 파랑 덩어리가 8 알갱이 떨어져 초록 위에 앉는다. 그 순간
	 * 덩어리의 왼쪽 아래 알갱이가 23 행 8 칸 파랑과 대각선으로 이어져 파랑(576 + 64 = 640알)이 좌우를 잇는다(연쇄).
	 */
	private static SandGame chainGame(long best) {
		SandField f = emptyField();
		for (int c = 0; c < SandRules.COLS; c++) {
			fillCell(f, 23, c, c < 9 ? SandColor.BLUE : SandColor.GREEN);
			fillCell(f, 22, c, SandColor.RED);
		}
		fillCell(f, 21, 9, SandColor.BLUE);
		return new SandGame(1, 1, best, f, 0);
	}

	@Test
	void aClearLetsSandFallIntoAChainThatScoresDouble() {
		SandGame g = chainGame(0);
		assertEquals(1, g.clears());
		assertEquals(640, g.score(), "빨강 640알 × 1");
		assertEquals(1, g.multiplier());
		ClearEvent first = g.lastClear();
		assertTrue(first.has(SandColor.RED));
		assertEquals(22 * G, first.topRow());
		assertEquals(23 * G - 1, first.bottomRow());

		while (g.clears() < 2 && g.time() < 2000) {
			g.advance(null);
		}
		ClearEvent chain = g.lastClear();
		// 띠는 200 번째 틱에 사라지고, 같은 틱의 모래 스텝(10ms 마다)부터 8 스텝 = 270 번째 틱에 덩어리가 앉는다
		assertEquals(SandRules.FLASH_MS + 7 * 10, chain.time());
		assertEquals(640, chain.grains());
		assertEquals(2, chain.multiplier(), "콤보 시간(1.5초) 안의 지우기");
		assertEquals(640 + 640 * 2, g.score());
		assertEquals(2, g.maxMultiplier());
		assertEquals(1280, g.grainsCleared());

		advanceTo(g, chain.time() + SandRules.FLASH_MS);
		assertEquals(64, g.field().count(), "초록 한 칸만 남는다");
		advanceTo(g, chain.time() + SandRules.COMBO_MS - 2);
		assertEquals(2, g.multiplier());
		g.advance(null); // 지운 틱부터 1500 번째 틱에 콤보 시간이 다 된다
		assertEquals(1, g.multiplier(), "콤보 시간이 다 되면 한 단계 내려간다");
		assertEquals(0, g.comboLeft(), "×1 이 되면 콤보 시간이 멈춘다");
	}

	/**
	 * 블록 하나로 생긴 얇은 띠는 양쪽 벽에 닿아도 지워지지 않는다 (규칙 sand-0.3). 아무것도 누르지 않는 판(시드 990001)에서는
	 * 블록이 모두 스폰 자리에 떨어져 가운데 더미가 자라다가, 114.4초에 블록 하나(256알)가 더미의 비탈을 얇게 덮으며 양쪽 벽을
	 * 잇는다. 지난 규칙(문턱 0, sand-0.2)으로는 그 순간 256알을 지웠고, 지금 규칙으로는 그 알갱이가 그대로 남아 얇은 띠로 적힌다.
	 * 그때까지 두 판은 똑같다.
	 */
	@Test
	void aThinBandOfOnePieceThatJoinsBothWallsIsNotCleared() {
		long seed = 990_001;
		SandGame old = new SandGame(seed, 1, 0, null, -1, 0);
		while (old.clears() == 0 && old.time() < 200_000) {
			old.advance(null);
		}
		ClearEvent first = old.lastClear();
		assertNotNull(first);
		assertEquals(SandRules.PIECE_GRAINS, first.grains(), "지난 규칙: 블록 하나 분량을 지웠다");

		SandGame g = new SandGame(seed, 1, 0);
		advanceTo(g, first.time());
		assertFalse(g.over());
		assertEquals(0, g.clears(), "지금 규칙: 지우지 않는다");
		assertEquals(SandRules.PIECE_GRAINS, g.thinGrains(), "양쪽 벽을 이은 256알이 얇은 띠로 남는다");
		int[] thin = new int[g.thinGrains()];
		g.copyThin(thin);
		Arrays.sort(thin);
		short[] before = grains(old);
		int[] flashed = new int[first.grains()];
		int n = 0;
		for (int i = 0; i < before.length; i++) {
			if (Grain.flashing(before[i])) {
				flashed[n++] = i;
			}
		}
		assertArrayEquals(flashed, thin, "지난 규칙이 지운 바로 그 알갱이들");
		boolean left = false;
		boolean right = false;
		for (int i : thin) {
			short v = g.grain(i / SandRules.WIDTH, i % SandRules.WIDTH);
			assertFalse(Grain.flashing(v));
			assertTrue(first.has(Grain.color(v)), "한 색");
			left |= i % SandRules.WIDTH == 0;
			right |= i % SandRules.WIDTH == SandRules.WIDTH - 1;
		}
		assertTrue(left && right, "왼쪽 벽과 오른쪽 벽에 닿아 있다");
		advanceTo(g, first.time() + SandRules.FLASH_MS + 10);
		assertEquals(0, g.clears(), "반짝임 시간이 지나도 그대로 남는다");
	}

	@Test
	void bestIsTheSavedRecordUntilTheScoreBeatsIt() {
		SandGame g = chainGame(1000);
		assertEquals(1000, g.best());
		assertFalse(g.newBest());
		while (g.clears() < 2 && g.time() < 2000) {
			g.advance(null);
		}
		assertEquals(1920, g.best());
		assertTrue(g.newBest());
	}

	/**
	 * 바닥의 빨강 일곱 행(185~191 행, 560알)과 그 위 184 행(40·41 열만 빈 78알)이 한 덩어리로 양쪽 벽을 이었지만 638알이라
	 * 지우지 않고 얇은 띠로 남는다. 그 위 181 행 40·41 열에 빨강 두 알이 떠 있다. 10 번째 틱에 182 행, 20 번째 틱에 183 행으로
	 * 내려오는 순간 184 행의 39 열·42 열과 대각선으로 이어져 640알이 된다. 고정 없이 모래 스텝만으로 문턱에 닿은 덩어리도 그
	 * 스텝에서 찾는다.
	 */
	@Test
	void aSpanCompletedByFlowingSandIsFoundAtThatStep() {
		SandField f = emptyField();
		int floor = SandRules.HEIGHT - 1;
		int top = floor - 7;
		for (int y = top; y <= floor; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				if (y != top || x != 40 && x != 41) {
					f.set(y, x, red());
				}
			}
		}
		f.set(top - 3, 40, red());
		f.set(top - 3, 41, red());
		SandGame g = new SandGame(1, 1, 0, f, 0);
		assertEquals(0, g.clears(), "638알: 양쪽 벽을 이었지만 문턱보다 작다");
		assertEquals(SandRules.MIN_CLEAR_GRAINS - 2, g.thinGrains(), "얇은 띠로 남는다");
		advanceTo(g, 19);
		assertEquals(0, g.clears(), "182 행에서는 아직 닿지 않는다");
		g.advance(null);
		assertEquals(1, g.clears());
		assertEquals(0, g.thinGrains(), "지운 덩어리는 얇은 띠가 아니다");
		ClearEvent c = g.lastClear();
		assertEquals(20, c.time());
		assertEquals(SandRules.MIN_CLEAR_GRAINS, c.grains());
		assertEquals(top - 1, c.topRow());
		assertEquals(floor, c.bottomRow());
		assertEquals(SandRules.MIN_CLEAR_GRAINS, g.score());
	}

	/**
	 * 떠 있는 빨강 칸 한 줄(640알)을 10ms 마다 하나씩 넣으면 넣은 뒤 첫 모래 스텝에서 지워진다. 1.5초 안의 지우기마다 배율이 한
	 * 단계 올라 ×10 에서 멈추고, 지우지 못하면 1.5초마다 한 단계씩 내려가며 그 사이의 지우기는 지금 배율에서 한 단계 올린다.
	 */
	@Test
	void theMultiplierRisesWithinTheComboTimeCapsAtTenAndStepsDownOncePerComboTime() {
		SandGame g = new SandGame(1, 1, 0, null, 0); // 빈 판, 중력 0
		int[] expected = { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 10, 10 };
		int slab = SandRules.MIN_CLEAR_GRAINS;
		for (int k = 0; k < expected.length; k++) {
			slab(g, 40 + 10 * k); // 앞의 줄(한 행 내려가 반짝이는 중)과 겹치지 않는 자리
			advanceTo(g, 10L * (k + 1));
			assertEquals(k + 1, g.clears(), "틱 " + g.time());
			assertEquals(expected[k], g.multiplier());
			assertEquals(expected[k], g.lastClear().multiplier());
			assertEquals((long) slab * expected[k], g.lastClear().points());
		}
		assertEquals(slab * (55 + 10 + 10), g.score());
		assertEquals(10, g.maxMultiplier());
		// 마지막 지우기(120 번째 틱)부터 1500 번째 틱(1619)에 한 단계, 그 뒤 1500ms 마다 한 단계
		advanceTo(g, 120 + SandRules.COMBO_MS - 2);
		assertEquals(10, g.multiplier());
		g.advance(null);
		assertEquals(9, g.multiplier());
		assertEquals(SandRules.COMBO_MS, g.comboLeft(), "아직 ×2 이상이면 콤보 시간을 다시 준다");
		advanceTo(g, 120 + SandRules.COMBO_MS - 1 + SandRules.COMBO_MS);
		assertEquals(8, g.multiplier());
		slab(g, 40);
		g.advance(null); // 3120 번째 틱의 모래 스텝
		assertEquals(13, g.clears());
		assertEquals(9, g.multiplier(), "내려가던 배율에서 다시 한 단계 오른다");
		long one = g.time() + SandRules.COMBO_MS - 1 + 7L * SandRules.COMBO_MS; // ×9 → ×1 은 8 단계
		advanceTo(g, one - 1);
		assertEquals(2, g.multiplier());
		g.advance(null);
		assertEquals(1, g.multiplier());
		assertEquals(0, g.comboLeft(), "×1 이 되면 콤보 시간이 멈춘다");
		assertEquals(10, g.maxMultiplier());
	}

	@Test
	void theFirstGameIsNeverANewBest() {
		SandGame g = chainGame(0);
		while (g.clears() < 2 && g.time() < 2000) {
			g.advance(null);
		}
		assertEquals(1920, g.score());
		assertEquals(1920, g.best());
		assertFalse(g.newBest(), "저장된 기록이 없으면(0) 새 기록으로 치지 않는다");
	}

	// ------------------------------------------------------------------ 단계

	@Test
	void levelRisesEveryThirtySecondsUpToTen() {
		SandGame g = new SandGame(1, 1, 0, null, 0); // 중력 0: 블록이 떠 있어 판이 끝나지 않는다
		assertEquals(1, g.level());
		assertEquals(SandRules.sandStepMs(1), g.sandStepMs());
		advanceTo(g, SandRules.LEVEL_MS - 1);
		assertEquals(1, g.level());
		g.advance(null);
		assertEquals(2, g.level());
		assertEquals(SandRules.sandStepMs(2), g.sandStepMs());
		advanceTo(g, 9L * SandRules.LEVEL_MS);
		assertEquals(10, g.level());
		advanceTo(g, 13L * SandRules.LEVEL_MS);
		assertEquals(10, g.level());

		assertEquals(2000, new SandGame(1, 3, 0).gravity(), "3 단계는 초당 2칸 (RULES 6.3 표)");
		assertEquals(1, new SandGame(1, 0, 0).level(), "단계는 1~10 으로 자른다");
		assertEquals(12_000, new SandGame(1, 99, 0).gravity());
	}

	@Test
	void theSandStepPeriodFollowsTheLevel() {
		for (int level : new int[] { 1, 10 }) {
			SandField f = emptyField();
			f.set(40, 0, red());
			SandGame g = new SandGame(1, level, 0, f, 0);
			int period = SandRules.sandStepMs(level);
			advanceTo(g, 10L * period - 1);
			assertNotEquals(Grain.EMPTY, g.grain(49, 0), level + " 단계: 스텝 9번");
			g.advance(null);
			assertNotEquals(Grain.EMPTY, g.grain(50, 0), level + " 단계: " + period + "ms 마다 한 알갱이씩");
		}
		assertEquals(10, SandRules.sandStepMs(1));
		assertEquals(6, SandRules.sandStepMs(10));
	}

	// ------------------------------------------------------------------ 패배와 기다림

	@Test
	void sandSettlingAboveTheVisibleFieldEndsTheGame() {
		SandField f = emptyField();
		for (int r = 4; r < SandRules.ROWS; r++) {
			for (int c = 0; c < SandRules.COLS; c++) {
				fillCell(f, r, c, SandColor.values()[c % 4]); // 세로 띠: 같은 색 띠끼리는 닿지 않아 지워지지 않는다
			}
		}
		SandGame g = new SandGame(1, 1, 0, f, 0);
		assertFalse(g.over());
		assertEquals(0, g.clears());
		assertEquals(SandRules.VISIBLE_TOP, g.settledTop(), "보이는 판 맨 위까지는 괜찮다");
		assertTrue(g.grounded(), "보이는 판이 꽉 차 스폰하자마자 닿아 있다");
		g.advance(Input.HD); // 스폰 여백에서 부서진 모래는 꽉 찬 판 위에 정착한다
		assertTrue(g.over());
		assertTrue(g.settledTop() < SandRules.VISIBLE_TOP);
		assertNull(g.piece());
		long t = g.time();
		g.advance(Input.HD);
		assertEquals(t, g.time(), "끝난 판은 진행하지 않는다");
	}

	/**
	 * 지는 판이 된다 (규칙 sand-0.2, 사용자 결정): 블록이 나오고 150ms 뒤 그 자리에서 하드드롭만 하는 손은 1 단계에서 1분 남짓에
	 * 진다. 모래가 63° 까지 서므로 가운데 더미가 스폰 자리까지 자란다. sand-0.1(45°)에서는 이 손이 10분을 버텼다: 블록 하나가 더미
	 * 겉면을 얇게 덮으며 양쪽 벽을 이어 거의 블록마다 지워졌다. 10-07 측정(SandDropBench, 시드 990001~990008): 57~91초, 평균
	 * 63초.
	 */
	@Test
	void aPlayerWhoOnlyHardDropsLosesInAboutAMinute() {
		for (long seed = 990_001; seed <= 990_003; seed++) {
			SandGame g = new SandGame(seed, 1, 0);
			int seen = -1;
			long spawnedAt = 0;
			while (!g.over() && g.time() < 300_000) {
				Input in = null;
				if (g.piece() != null) {
					if (g.spawns() != seen) {
						seen = g.spawns();
						spawnedAt = g.time();
					}
					if (g.time() + 1 - spawnedAt >= 150) {
						in = Input.HD;
					}
				}
				g.advance(in);
			}
			assertTrue(g.over(), "시드 " + seed + ": 5분 안에 진다");
			assertTrue(g.time() >= 30_000 && g.time() <= 120_000, "시드 " + seed + ": 30초~2분 사이에 진다: " + g.time() + "ms");
			assertTrue(g.settledTop() < SandRules.VISIBLE_TOP, "정착한 모래가 보이는 판 위로 넘쳐서");
		}
	}

	/**
	 * 아무것도 누르지 않는 손도 진다 (규칙 sand-0.3). 블록이 모두 스폰 자리에 떨어져 가운데 더미가 스폰 여백까지 자란다. 블록
	 * 하나가 더미의 비탈을 얇게 덮어 양쪽 벽을 이어도 지워지지 않기 때문이다. sand-0.2 에서는 그 얇은 띠가 지워져 이 손이 10분을
	 * 버텼고 점수도 사람처럼 두는 손보다 높았다(10-07, 시드 24개 모두 600초, 점수 중앙 158,993). 10-11 측정: 시드 990001~3 에서
	 * 162·196·164초.
	 */
	@Test
	void aPlayerWhoPressesNothingLosesWithinFiveMinutes() {
		for (long seed = 990_001; seed <= 990_003; seed++) {
			SandGame g = new SandGame(seed, 1, 0);
			advanceTo(g, 300_000);
			assertTrue(g.over(), "시드 " + seed + ": 5분 안에 진다 (" + g.time() + "ms)");
			assertTrue(g.settledTop() < SandRules.VISIBLE_TOP, "정착한 모래가 보이는 판 위로 넘쳐서");
		}
	}

	/**
	 * {@link SandGame#sandStill()}: 막 부서진 블록, 흐르는 알갱이, 반짝이는 덩어리가 있으면 false 이고, 모래 스텝에서 아무것도
	 * 움직이지 않으면 true 다. 모래 AI 가 이것을 보고 떨어뜨릴 때를 고른다.
	 */
	@Test
	void sandStillTellsWhenTheSandHasStopped() {
		SandGame g = new SandGame(seedStartingWith(PieceType.O), 1, 0, null, 0); // 빈 판, 중력 0
		assertTrue(g.sandStill(), "빈 판");
		g.advance(Input.HD); // O 가 바닥에 16 × 16 알갱이로 부서진다
		assertFalse(g.sandStill(), "막 부서진 블록");
		short[] last = grains(g);
		long lastChange = g.time();
		while (!g.sandStill()) {
			g.advance(null);
			short[] now = grains(g);
			if (!Arrays.equals(now, last)) {
				last = now;
				lastChange = g.time();
			}
			assertTrue(g.time() < 5_000, "모래는 언젠가 멈춘다");
		}
		long stopped = g.time();
		assertTrue(stopped > lastChange && stopped - lastChange <= g.sandStepMs(),
				"마지막으로 움직인 뒤 첫 모래 스텝에서 멈췄다고 본다: " + lastChange + " → " + stopped);
		advanceTo(g, stopped + 100);
		assertTrue(g.sandStill());
		assertArrayEquals(last, grains(g), "멈췄다고 본 뒤로 알갱이가 그대로");

		slab(g, 40); // 떠 있는 빨강 칸 한 줄(640알): 다음 모래 스텝에 내려가며 좌우를 잇는다
		advanceTo(g, g.time() + g.sandStepMs());
		assertEquals(1, g.clears());
		long cleared = g.lastClear().time();
		advanceTo(g, cleared + SandRules.FLASH_MS - 1);
		assertFalse(g.sandStill(), "반짝이는 덩어리가 있는 동안은 멈춘 것이 아니다");
		while (!g.sandStill()) {
			g.advance(null);
		}
		assertTrue(g.time() <= cleared + SandRules.FLASH_MS + g.sandStepMs(), "덩어리가 사라진 뒤 첫 모래 스텝에서 다시 멈춘다");
		assertEquals(SandRules.PIECE_GRAINS, g.field().count());
	}

	@Test
	void theNextPieceWaitsWhileFallingSandStillCoversItsSpawnCells() {
		SandField f = emptyField();
		// 칸 (4, 4) 맨 위에 알갱이 하나: 모든 블록의 스폰 모양이 (3, 4) 칸을 써서 스폰하자마자 닿는다
		f.set(4 * G, 4 * G, Grain.of(SandColor.RED, 0, 0, 0));
		SandGame g = new SandGame(1, 1, 0, f, 0);
		assertTrue(g.grounded());
		g.advance(Input.HD); // 스폰 여백(칸 2~3 행)에서 고정
		assertEquals(1, g.pieces());
		assertFalse(g.over(), "아래가 비어 흐를 알갱이라 넘친 것이 아니다");
		assertTrue(g.surfaceRow() < SandRules.VISIBLE_TOP);
		assertEquals(SandRules.HEIGHT, g.settledTop(), "흐르는 알갱이뿐이라 정착한 것이 없다");
		assertNull(g.piece(), "다음 블록은 모래가 비켜날 때까지 기다린다");
		assertTrue(g.waiting());
		assertNull(g.ghost());
		int guard = 0;
		while (g.piece() == null) {
			g.advance(Input.HD); // 기다리는 동안의 조작은 버린다
			assertTrue(++guard < 5000);
		}
		assertFalse(g.waiting());
		assertFalse(g.over());
		assertEquals(1, g.pieces(), "기다리는 동안의 하드드롭은 버려졌다");
		assertEquals(PieceState.SPAWN_Y, g.piece().y());
		assertEquals(2, g.spawns());
	}

	// ------------------------------------------------------------------ 결정론과 보존

	/** 시드로 정해지는 단순한 손: 250ms 마다 회전·열을 고르고, 그쪽으로 옮긴 뒤 하드드롭한다. 소프트드롭도 가끔. */
	private static final class Bot {
		private final SplitMix64 rng;
		private int rotations;
		private int targetX;
		private int wait;
		private int tries;

		Bot(long seed) {
			this.rng = new SplitMix64(seed);
		}

		Input next(SandGame g) {
			PieceState p = g.piece();
			if (p == null) {
				return null;
			}
			g.setSoftDrop(rng.nextInt(50) == 0);
			if (wait > 0) {
				wait--;
				return null;
			}
			if (tries == 0) {
				rotations = rng.nextInt(4);
				targetX = rng.nextInt(10) - 1;
			}
			if (++tries > 40) {
				return finish(); // 막혀서 못 가면 그 자리에 놓는다
			}
			if (rotations > 0) {
				rotations--;
				return rng.nextInt(2) == 0 ? Input.CW : Input.CCW;
			}
			if (p.x() < targetX) {
				return Input.R;
			}
			if (p.x() > targetX) {
				return Input.L;
			}
			if (rng.nextInt(8) == 0) {
				return Input.H;
			}
			return finish();
		}

		private Input finish() {
			tries = 0;
			wait = 250;
			return Input.HD;
		}
	}

	private static SandGame play(long seed, long botSeed, int ticks) {
		SandGame g = new SandGame(seed, 4, 0);
		Bot bot = new Bot(botSeed);
		for (int t = 0; t < ticks && !g.over(); t++) {
			g.advance(bot.next(g));
		}
		return g;
	}

	@Test
	void sameSeedAndInputsGiveTheSameGame() {
		SandGame a = play(77, 5, 60_000);
		SandGame b = play(77, 5, 60_000);
		assertArrayEquals(grains(a), grains(b));
		assertEquals(a.time(), b.time());
		assertEquals(a.score(), b.score());
		assertEquals(a.pieces(), b.pieces());
		assertEquals(a.spawns(), b.spawns());
		assertEquals(a.piece(), b.piece());
		assertEquals(a.hold(), b.hold());
		assertEquals(a.over(), b.over());
		assertTrue(a.pieces() > 20, "판이 충분히 진행됐다: " + a.pieces());
		SandGame c = play(78, 5, 60_000);
		assertFalse(Arrays.equals(grains(a), grains(c)), "다른 시드면 다른 판");
	}

	@Test
	void grainsAreOnlyAddedByLocksAndRemovedByClearsAndNeverEnterThePiece() {
		SandGame g = new SandGame(5, 4, 0);
		Bot bot = new Bot(6); // 이 손은 넘치기 전에 네 번 지운다 (sand-0.2 에서는 무작위 손의 지우기가 드물다)
		int maxFlashing = 0;
		while (!g.over() && g.time() < 120_000) {
			g.advance(bot.next(g));
			int removed = g.grainsCleared() - g.field().flashing();
			assertEquals(g.pieces() * SandRules.PIECE_GRAINS - removed, g.field().count(), "틱 " + g.time());
			maxFlashing = Math.max(maxFlashing, g.field().flashing());
			if (g.piece() != null) {
				for (int[] c : g.piece().cells()) {
					assertFalse(g.field().cellOccupied(c[0], c[1]), "움직이는 블록 칸에는 알갱이가 없다: 틱 " + g.time());
				}
			}
		}
		assertTrue(g.over(), "손이 어설퍼 언젠가 넘친다");
		assertTrue(g.clears() > 0 && maxFlashing > 0, "지우기가 한 번은 일어났다: " + g.clears());
		int counted = 0;
		for (short v : grains(g)) {
			if (v != Grain.EMPTY) {
				counted++;
			}
		}
		assertEquals(g.field().count(), counted);
	}

	@Test
	void aSnapshotCopiesWhatThePanelDraws() {
		SandGame g = play(21, 3, 3000);
		SandSnapshot s = g.snapshot(null);
		assertArrayEquals(grains(g), grains(s));
		assertEquals(g.piece(), s.piece());
		assertEquals(g.ghost(), s.ghost());
		assertEquals(g.current(), s.current());
		assertEquals(g.preview(), s.preview());
		assertEquals(g.score(), s.score());
		assertEquals(g.time(), s.time());
		assertEquals(g.level(), s.level());
		assertEquals(g.surfaceRow(), s.surfaceRow());
		short[] live = new short[SandRules.PIECE_GRAINS];
		short[] copy = new short[SandRules.PIECE_GRAINS];
		g.pieceGrains(live);
		s.pieceGrains(copy);
		assertArrayEquals(live, copy);
		long t = s.time();
		g.advance(null);
		assertEquals(t, s.time(), "복사본은 게임이 진행돼도 그대로");
		assertTrue(s == g.snapshot(s), "배열을 다시 쓴다");
		assertEquals(g.time(), s.time());
	}

	@Test
	void readingTheGameNeverChangesIt() {
		SandGame quiet = play(31, 4, 20_000);
		SandGame watched = new SandGame(31, 4, 0);
		Bot bot = new Bot(4);
		SandSnapshot s = null;
		short[] pg = new short[SandRules.PIECE_GRAINS];
		for (int t = 0; t < 20_000 && !watched.over(); t++) {
			watched.advance(bot.next(watched));
			s = watched.snapshot(s);
			watched.ghost();
			watched.preview();
			watched.cellGrid();
			watched.copyField();
			watched.pieceGrains(pg);
		}
		assertArrayEquals(grains(quiet), grains(watched));
		assertEquals(quiet.time(), watched.time());
		assertEquals(quiet.score(), watched.score());
		assertEquals(quiet.pieces(), watched.pieces());
		assertEquals(quiet.piece(), watched.piece());
		assertTrue(quiet.pieces() > 10, "판이 충분히 진행됐다: " + quiet.pieces());
	}

	@Test
	void copiesOfTheFieldAndTheCellGridAreIndependent() {
		SandGame g = play(21, 3, 3000);
		SandField copy = g.copyField();
		short[] copied = new short[SandRules.WIDTH * SandRules.HEIGHT];
		copy.copyTo(copied);
		assertArrayEquals(grains(g), copied);
		Grid cells = g.cellGrid();
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				assertEquals(g.field().cellOccupied(r, c), cells.get(r, c) != Grid.EMPTY, r + "," + c);
			}
		}
		copy.set(0, 0, red());
		cells.set(0, 0, Grid.GARBAGE);
		assertEquals(Grain.EMPTY, g.grain(0, 0));
		assertEquals(Grid.EMPTY, g.cellGrid().get(0, 0));
	}

	@Test
	void fromFieldStartsFromTheGivenGrains() {
		SandField f = emptyField();
		fillCell(f, 23, 0, SandColor.YELLOW);
		SandGame g = SandGame.fromField(9, 2, 0, f);
		assertEquals(64, g.field().count());
		assertEquals(2, g.startLevel());
		assertEquals(2, g.level());
		assertEquals(SandRules.gravity(2), g.gravity());
		f.set(0, 0, red());
		assertEquals(Grain.EMPTY, g.grain(0, 0), "알갱이만 옮겨 왔다");
		assertEquals(new SandGame(9, 2, 0).current(), g.current(), "블록 순서는 시드로 정한다");
	}
}
