package kr.ac.jbnu.se.tetris.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/** 가상 시간 모드 (RULES 12.3, docs/GAME.md 3.3). */
class TimedMatchTest {

	@Test
	void gravityLevelRisesEveryThirtySecondsUpToTheCap() {
		TimedMatch m = new TimedMatch(1, 1, GameModel.Scheduler.NONE, 1500); // 상한 초당 1.5칸 → 1 단계
		assertEquals(0, m.gravityLevel());
		assertEquals(1000, m.player(0).gravity());
		for (int i = 1; i < TimedMatch.LEVEL_MS; i++) {
			m.tick((Input) null);
		}
		assertEquals(0, m.gravityLevel(), "29.999초");
		m.tick((Input) null);
		assertEquals(1, m.gravityLevel(), "30초");
		assertEquals(1500, m.player(0).gravity());
		for (int i = 0; i < TimedMatch.LEVEL_MS; i++) {
			m.tick((Input) null);
		}
		assertFalse(m.isOver(), "중력만으로 1분 안에는 지지 않는다");
		assertEquals(1, m.gravityLevel(), "60초에도 상한에 머문다");
		assertEquals(1500, m.player(0).gravity());
		assertEquals(0, TimedMatch.maxLevel(500), "가장 낮은 단계보다 작으면 0 단계");
		assertEquals(9, TimedMatch.maxLevel(1_000_000));
		assertEquals(3, TimedMatch.maxLevel(3000));
		assertEquals(3, TimedMatch.maxLevel(3999));
		assertEquals(-1, new TimedMatch(1, 1, GameModel.Scheduler.NONE, 0).gravityLevel(), "중력 없음");
	}

	@Test
	void sendsArriveAtTheEndOfTheTick() {
		// 시드 0 의 첫 블록은 I(가로, 3~6열). P1 은 맨 아래 줄의 3~6열만 비어 있어 하드드롭하면 한 줄을 지워 1 줄 보낸다.
		Grid almost = new Grid();
		for (int c = 0; c < Grid.WIDTH; c++) {
			if (c < 3 || c > 6) {
				almost.set(Grid.HEIGHT - 1, c, Grid.GARBAGE);
			}
		}
		almost.set(Grid.HEIGHT - 2, 0, Grid.GARBAGE); // 퍼펙트 클리어(+10)가 되지 않게 한 칸 남긴다
		TimedMatch m = new TimedMatch(0, new GameModel(0, 1, GameModel.Scheduler.NONE, almost),
				new GameModel(0, 2, GameModel.Scheduler.NONE));
		m.tick(Input.HD, Input.HD);
		assertEquals(1, m.lastLock(0).sent());
		assertEquals(0, m.lastLock(1).garbageRisen(), "같은 틱의 P2 고정은 아직 받지 않은 상태로 했다");
		assertEquals(1, m.player(1).game().pendingLines(), "틱이 끝날 때 붙었다");
		m.tick(null, Input.HD);
		assertEquals(1, m.lastLock(1).garbageRisen(), "다음 고정에서 올라온다");
	}

	@Test
	void bothToppingOutInTheSameTickIsADraw() {
		// 둘 다 하드드롭만 하면 같은 블록을 같은 자리에 쌓아 같은 틱에 블록 아웃으로 진다.
		TimedMatch m = new TimedMatch(3, 2, GameModel.Scheduler.NONE, 0);
		while (!m.isOver()) {
			m.tick(Input.HD, Input.HD);
		}
		assertEquals(TimedMatch.Result.DRAW, m.result());
		assertEquals(m.player(0).game().pieces(), m.player(1).game().pieces());
	}

	@Test
	void onlyOneSideToppingOutGivesTheWinToTheOther() {
		TimedMatch m = new TimedMatch(3, 2, GameModel.Scheduler.NONE, 0);
		while (!m.isOver()) {
			m.tick(Input.HD, null); // P2 는 아무것도 하지 않는다(중력 0 이라 블록이 떠 있다)
		}
		assertEquals(TimedMatch.Result.P2, m.result());
	}

	@Test
	void sameSeedAndInputsGiveTheSameGame() {
		Input[] script = { Input.L, null, Input.CW, Input.R, Input.D, Input.HD, Input.H, Input.CCW, null, Input.HD };
		TimedMatch a = new TimedMatch(42, 2, GameModel.Scheduler.NONE, 12000);
		TimedMatch b = new TimedMatch(42, 2, GameModel.Scheduler.NONE, 12000);
		for (int t = 0; t < 20_000; t++) {
			Input in = script[t % script.length];
			Input other = script[(t * 7 + 3) % script.length];
			a.tick(in, other);
			b.tick(in, other);
		}
		assertEquals(a.result(), b.result());
		for (int i = 0; i < 2; i++) {
			assertTrue(a.player(i).game().grid().sameOccupancy(b.player(i).game().grid()));
			assertEquals(a.player(i).game().sentTotal(), b.player(i).game().sentTotal());
		}
	}

	@Test
	void argumentsAreChecked() {
		assertThrows(IllegalArgumentException.class, () -> new TimedMatch(1, 3, GameModel.Scheduler.NONE, 0));
		assertThrows(IllegalArgumentException.class, () -> new TimedMatch(1, 2, GameModel.Scheduler.S_3, 0));
		TimedMatch m = new TimedMatch(1, 2, GameModel.Scheduler.NONE, 0);
		assertThrows(IllegalArgumentException.class, () -> m.tick(Input.L));
	}
}
