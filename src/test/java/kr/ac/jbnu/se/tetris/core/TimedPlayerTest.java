package kr.ac.jbnu.se.tetris.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/** 실제 시간 규칙 (RULES 6.3, docs/GAME.md 3장)과 GameModel 의 실시간 확장. */
class TimedPlayerTest {

	/** 맨 아래 행부터 위로가 아니라, 보드의 아래쪽 행들을 위에서 아래 순서로 준다("#" 은 찬 칸). */
	private static Grid bottom(String... rows) {
		Grid g = new Grid();
		int top = Grid.HEIGHT - rows.length;
		for (int r = 0; r < rows.length; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				if (rows[r].charAt(c) == '#') {
					g.set(top + r, c, Grid.GARBAGE);
				}
			}
		}
		return g;
	}

	/** 첫 블록이 type 인 시드. */
	private static long seedStartingWith(PieceType type) {
		for (long s = 0;; s++) {
			if (new GameModel(s, 1, GameModel.Scheduler.NONE).current() == type) {
				return s;
			}
		}
	}

	/** 바닥에 닿을 때까지 D 를 넣는다. 닿게 만든 틱의 고정 결과(언제나 null)를 확인하고 그 틱 수를 돌려준다. */
	private static int dropUntilGrounded(TimedPlayer p) {
		int n = 0;
		while (!p.grounded()) {
			assertNull(p.tick(Input.D));
			n++;
		}
		return n;
	}

	@Test
	void gravityMovesOneCellPerThousandTicksAtOneCellPerSecond() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 1000);
		int y = p.piece().y();
		for (int i = 1; i < 1000; i++) {
			p.tick(null);
		}
		assertEquals(y, p.piece().y(), "999 틱까지는 그대로");
		p.tick(null);
		assertEquals(y + 1, p.piece().y(), "1000 번째 틱에 한 칸");
	}

	@Test
	void softDropRaisesGravityToTwentyCellsPerSecond() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 1000);
		p.setSoftDrop(true);
		int y = p.piece().y();
		for (int i = 0; i < 50; i++) {
			p.tick(null);
		}
		assertEquals(y + 1, p.piece().y(), "g = 20000 이면 50 틱에 한 칸");
	}

	@Test
	void lockDelayExpiresOnTheFiveHundredthGroundedTick() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0);
		dropUntilGrounded(p); // 닿게 만든 틱이 첫 번째 틱
		assertEquals(TimedPlayer.LOCK_DELAY_MS - 1, p.lockTimer());
		for (int i = 2; i < TimedPlayer.LOCK_DELAY_MS; i++) {
			assertNull(p.tick(null), i + " 번째 틱");
		}
		GameModel.LockResult r = p.tick(null);
		assertNotNull(r, "500 번째 틱에 고정");
		assertEquals(1, p.game().pieces());
		assertFalse(p.grounded(), "다음 블록은 스폰 위치에 떠 있다");
	}

	@Test
	void successfulGroundedMovesResetTheTimerAndTheFifteenthLocksAtOnce() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0); // 첫 블록 I
		dropUntilGrounded(p);
		for (int k = 1; k < TimedPlayer.MAX_RESETS; k++) {
			for (int i = 0; i < 100; i++) {
				p.tick(null); // 타이머를 조금 쓴다
			}
			assertNull(p.tick(k % 2 == 1 ? Input.L : Input.R));
			assertEquals(k, p.resets());
			assertEquals(TimedPlayer.LOCK_DELAY_MS - 1, p.lockTimer(), "되돌린 뒤 이번 틱에 1 줄었다");
		}
		assertNotNull(p.tick(Input.L), "15 번째 리셋은 시간을 주지 않고 그 틱에 고정");
		assertEquals(1, p.game().pieces());
	}

	@Test
	void failedMovesChangeNothing() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0);
		dropUntilGrounded(p);
		while (p.tick(Input.L) == null && p.piece().x() > 0) {
			// 왼쪽 벽까지 (중간에 고정되지 않을 만큼 짧다)
		}
		int resets = p.resets();
		p.tick(null);
		int timer = p.lockTimer();
		assertNull(p.tick(Input.L), "벽에 막힌 L");
		assertEquals(resets, p.resets());
		assertEquals(timer - 1, p.lockTimer(), "타이머는 평소처럼 1 줄기만 한다");
	}

	@Test
	void reachingANewLowestRowClearsTheResetCountAndLandingAgainRestartsTheTimer() {
		// 첫 블록 I(가로, 3~6열). 23행 0~5열이 차 있어 22행에 걸린 뒤, 오른쪽으로 밀면 6~9열 위에서 떨어진다.
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE, bottom("######....")), 0);
		dropUntilGrounded(p);
		p.tick(Input.R);
		p.tick(Input.R);
		assertEquals(2, p.resets());
		assertTrue(p.grounded());
		p.tick(Input.R); // 6~9열: 받칠 칸이 없다
		assertFalse(p.grounded());
		assertEquals(3, p.resets(), "바닥에서 한 이동이라 리셋으로 센다");
		for (int i = 0; i < 1000; i++) {
			assertNull(p.tick(null), "떨어져 있는 동안 타이머는 멈춘다(중력 0)");
		}
		p.tick(Input.D);
		assertTrue(p.grounded());
		assertEquals(0, p.resets(), "더 낮은 행에 닿았다");
		assertEquals(TimedPlayer.LOCK_DELAY_MS - 1, p.lockTimer(), "다시 닿으면 500ms 부터");
	}

	@Test
	void holdOncePerPieceThenAgainAfterLocking() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0); // 현재 I, 미리보기 S …
		int spawns = p.spawns();
		p.tick(Input.H);
		assertEquals(PieceType.I, p.game().hold());
		assertEquals(PieceType.S, p.piece().type());
		assertEquals(PieceState.spawn(PieceType.S), p.piece(), "꺼낸 블록은 스폰 상태");
		assertEquals(spawns + 1, p.spawns());
		assertTrue(p.game().placements().keySet().stream().noneMatch(k -> k.startsWith("h1|")),
				"홀드를 쓴 블록에는 홀드 배치가 없다");
		p.tick(Input.H);
		assertEquals(PieceType.S, p.piece().type(), "두 번째 홀드는 무시");
		assertNotNull(p.tick(Input.HD));
		PieceType next = p.piece().type();
		p.tick(Input.H);
		assertEquals(next, p.game().hold(), "고정한 뒤에는 다시 홀드할 수 있다");
		assertEquals(PieceType.I, p.piece().type(), "홀드 칸의 I 를 꺼낸다");
	}

	@Test
	void hardDropLocksAtOnceAndSkipsGravity() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 12000);
		GameModel.LockResult r = p.tick(Input.HD);
		assertNotNull(r);
		assertEquals(1, p.game().pieces());
		assertEquals(PieceState.spawn(p.game().current()), p.piece(), "새 블록은 아직 중력을 받지 않았다");
	}

	@Test
	void rotatingIntoATSpinSlotThenHardDroppingScoresAFullTSpin() {
		// fixtures/tspin/tsd.json 의 판과 조작. 회전으로 끼운 뒤 거리 0 하드드롭이라 마지막 조작이 회전으로 남는다.
		long seed = seedStartingWith(PieceType.T);
		TimedPlayer p = new TimedPlayer(new GameModel(seed, 1, GameModel.Scheduler.NONE,
				bottom("####......", "###...####", "####.#####", ".#########")), 0);
		p.tick(Input.CW);
		for (int i = 0; i < 18; i++) {
			assertNull(p.tick(Input.D));
		}
		p.tick(Input.CW);
		GameModel.LockResult r = p.tick(Input.HD);
		assertEquals(Spin.FULL, r.spin());
		assertEquals(2, r.lines());
	}

	@Test
	void gravityStepAfterARotationClearsTheSpin() {
		// 같은 자리라도 회전 뒤 한 칸이라도 내려가면(중력 또는 D) 마지막 조작이 이동이 된다(RULES 6.1).
		long seed = seedStartingWith(PieceType.T);
		TimedPlayer p = new TimedPlayer(new GameModel(seed, 1, GameModel.Scheduler.NONE,
				bottom("####......", "###...####", "####.#####", ".#########")), 0);
		p.tick(Input.CW);
		for (int i = 0; i < 17; i++) {
			p.tick(Input.D);
		}
		p.tick(Input.CW); // 한 칸 위에서 회전
		p.tick(Input.D); // 바닥까지 한 칸이면 같은 칸이지만…
		GameModel.LockResult r = p.tick(Input.HD);
		assertSame(Spin.NONE, r.spin());
	}

	@Test
	void lockingInPlaceMatchesApplyingTheSamePlacement() {
		GameModel a = new GameModel(99, 1, GameModel.Scheduler.S_MIX);
		GameModel b = a.copy();
		for (int i = 0; i < 12; i++) {
			Map<String, Placement> all = a.placements();
			Placement p = all.values().stream().filter(x -> !x.hold()).reduce((x, y) -> y).orElseThrow();
			GameModel.LockResult ra = a.apply(p);
			GameModel.LockResult rb = b.lock(p.state());
			assertEquals(ra.lines(), rb.lines());
			assertEquals(ra.sent(), rb.sent());
			assertEquals(ra.topout(), rb.topout());
			assertTrue(a.grid().sameOccupancy(b.grid()));
			assertEquals(a.current(), b.current());
			if (a.isOver()) {
				break;
			}
		}
	}

	@Test
	void holdPlacementUsesTheSameRuleAsHoldNow() {
		GameModel a = new GameModel(5, 1, GameModel.Scheduler.NONE);
		GameModel b = a.copy();
		Placement held = a.placements().values().stream().filter(Placement::hold).findFirst().orElseThrow();
		a.apply(held);
		assertTrue(b.holdNow());
		b.lock(held.state());
		assertEquals(a.hold(), b.hold());
		assertEquals(a.current(), b.current());
		assertEquals(a.preview(), b.preview());
		assertTrue(a.grid().sameOccupancy(b.grid()));
	}

	@Test
	void midPieceSearchStartsFromTheCurrentState() {
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0);
		for (int i = 0; i < 4; i++) {
			p.tick(Input.L);
		}
		Map<String, Placement> from = p.game().placementsFrom(p.piece());
		Placement any = from.values().stream().filter(x -> !x.hold()).findFirst().orElseThrow();
		// 경로를 지금 위치에서 그대로 재생하면 그 배치에 닿는다.
		PieceState s = p.piece();
		for (Op op : any.path()) {
			s = Movement.apply(p.game().grid(), s, op);
			assertNotNull(s);
		}
		assertEquals(any.state(), s);
	}
}
