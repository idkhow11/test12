package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 게임 사건 → 소리 (SoundCues): 고정과 하드드롭 거리 단계, 줄 지우기 겹침과 순서, T-spin 미니, B2B 연속, 상쇄, 방해 줄,
 * 카운트다운, 위험, 모래 모드, 판의 끝.
 */
class SoundCuesTest {

	/** 값으로 받는 본체: 하드드롭 거리(-1 = 하드드롭 아님). */
	private static List<Cue> lock(int dropRows, int lines, Spin spin, boolean pc, int combo, int b2b, int risen) {
		return SoundCues.lock(dropRows, lines, spin, pc, combo, b2b, risen);
	}

	@Test
	void hardDropReplacesTheLockSoundAndGrowsWithTheDistance() {
		assertEquals(List.of(Cue.of(Sfx.LOCK)), lock(-1, 0, Spin.NONE, false, -1, 0, 0));
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 0)), lock(0, 0, Spin.NONE, false, -1, 0, 0), "이미 닿아 있던 블록");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 0)), lock(2, 0, Spin.NONE, false, -1, 0, 0), "2칸 이하");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 1)), lock(3, 0, Spin.NONE, false, -1, 0, 0), "3~9칸");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 1)), lock(9, 0, Spin.NONE, false, -1, 0, 0));
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 2)), lock(10, 0, Spin.NONE, false, -1, 0, 0), "10칸 이상");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 2)), lock(19, 0, Spin.NONE, false, -1, 0, 0));
		assertEquals(0, SoundCues.dropTier(-5));
	}

	@Test
	void aTetrisWithComboAndB2bLayersInOrder() {
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 1), Cue.of(Sfx.CLEAR, 4), Cue.of(Sfx.COMBO, 3),
				Cue.of(Sfx.B2B, 2).withDelay(SoundCues.B2B_GAP_MS)), lock(5, 4, Spin.NONE, false, 3, 2, 0));
		assertEquals(List.of(Cue.of(Sfx.LOCK), Cue.of(Sfx.CLEAR, 1)), lock(-1, 1, Spin.NONE, false, 0, 0, 0),
				"콤보 0 (첫 줄 지우기)은 콤보 소리가 없다");
		assertEquals(Cue.of(Sfx.B2B, 9).withDelay(SoundCues.B2B_GAP_MS), lock(-1, 4, Spin.NONE, false, -1, 9, 0).get(2),
				"B2B 의 세기는 연속 수 (×9)");
	}

	@Test
	void tSpinsUseTheSpinSoundAndMinisTheirOwn() {
		assertEquals(Cue.of(Sfx.TSPIN, 2), lock(-1, 2, Spin.FULL, false, -1, 0, 0).get(1), "T-spin 더블");
		assertEquals(Cue.of(Sfx.TSPIN, 0), lock(-1, 1, Spin.MINI, false, -1, 0, 0).get(1), "T-spin 미니는 세기 0");
		assertEquals(Cue.of(Sfx.TSPIN, 0), lock(-1, 2, Spin.MINI, false, -1, 0, 0).get(1), "미니 더블도 미니 소리");
		assertEquals(List.of(Cue.of(Sfx.LOCK)), lock(-1, 0, Spin.FULL, false, -1, 0, 0),
				"줄을 못 지운 T-spin 은 화면에도 나오지 않으므로 소리도 없다");
	}

	@Test
	void perfectClearComesLastAndRisenGarbageRumbles() {
		List<Cue> pc = lock(12, 4, Spin.NONE, true, -1, 0, 0);
		assertEquals(Cue.of(Sfx.PERFECT_CLEAR).withDelay(SoundCues.PERFECT_GAP_MS), pc.get(pc.size() - 1));
		assertEquals(List.of(Cue.of(Sfx.LOCK), Cue.of(Sfx.GARBAGE_RISE, 3)), lock(-1, 0, Spin.NONE, false, -1, 0, 3));
	}

	@Test
	void lockResultOverloadsReadTheResult() {
		GameModel g = new GameModel(1, 1, GameModel.Scheduler.NONE);
		GameModel.LockResult r = g.apply(g.placements().values().iterator().next()); // 첫 블록: 줄 없음
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 2)), SoundCues.lock(r, 14, g.combo(), 0));
		assertEquals(List.of(Cue.of(Sfx.LOCK)), SoundCues.lock(r, -1, g.combo(), 0));
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 1)), SoundCues.lock(r, true, g.combo(), false),
				"예전 모양: 거리를 모르는 하드드롭은 가운데 단계");
		assertEquals(List.of(Cue.of(Sfx.LOCK)), SoundCues.lock(r, false, g.combo(), false));
		assertEquals(0, SoundCues.incoming(0, 0, r));
	}

	@Test
	void theOldBooleanB2bMeansAStreakOfOne() {
		List<Cue> v = SoundCues.lock(-1, 4, Spin.NONE, false, -1, 1, 0);
		assertEquals(Cue.of(Sfx.B2B, 1).withDelay(SoundCues.B2B_GAP_MS), v.get(2));
	}

	@Test
	void cancellingGarbageClinksJustAfterTheClear() {
		assertEquals(Cue.of(Sfx.GARBAGE_CANCEL, 3).withDelay(SoundCues.CANCEL_GAP_MS), SoundCues.cancel(3));
		assertEquals(Sfx.GARBAGE_CANCEL.tier(3), Sfx.GARBAGE_CANCEL.tier(1), "줄 수와 상관없이 같은 소리");
	}

	@Test
	void incomingCountsOnlyNewGarbage() {
		assertEquals(3, SoundCues.incoming(0, 3, 0, 0), "상대 공격 3줄 도착");
		assertEquals(0, SoundCues.incoming(3, 1, 2, 0), "내가 2줄 상쇄");
		assertEquals(0, SoundCues.incoming(3, 0, 0, 3), "3줄이 보드로 올라옴");
		assertEquals(1, SoundCues.incoming(5, 4, 2, 0), "2줄 상쇄한 틱에 1줄이 더 옴");
		assertEquals(2, SoundCues.incoming(4, 2, 0, 4), "4줄이 올라온 틱에 2줄이 더 옴 (혼자 연습의 스케줄러)");
	}

	@Test
	void garbageWarningWaitsForTheFlyingChip() {
		assertEquals(Cue.of(Sfx.GARBAGE, 4).withDelay(SoundCues.ATTACK_FLIGHT_MS), SoundCues.garbage(4, true));
		assertEquals(Cue.of(Sfx.GARBAGE, 2), SoundCues.garbage(2, false));
	}

	@Test
	void countdownBeepsThenGo() {
		assertEquals(Sfx.COUNTDOWN, SoundCues.countdown(3).sfx());
		assertEquals(Sfx.COUNTDOWN, SoundCues.countdown(1).sfx());
		assertEquals(Sfx.GO, SoundCues.countdown(0).sfx());
	}

	@Test
	void dangerBeatsFasterWhenTheBoardIsFuller() {
		assertEquals(Cue.of(Sfx.DANGER, 1), SoundCues.danger(15), "화면의 위험 표시(15줄)부터 보통 박자");
		assertEquals(Cue.of(Sfx.DANGER, 1), SoundCues.danger(SoundCues.DANGER_FAST_FILL - 1));
		assertEquals(Cue.of(Sfx.DANGER, 2), SoundCues.danger(SoundCues.DANGER_FAST_FILL), "더 차면 빠른 박자");
		assertEquals(Sfx.DANGER.cooldownMs(2), 600);
	}

	@Test
	void sandModeCrumblesAndChains() {
		assertEquals(List.of(Cue.of(Sfx.SAND_LAND)), SoundCues.sandLock(false), "고정 소리 대신 부서지는 소리");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 1), Cue.of(Sfx.SAND_LAND)), SoundCues.sandLock(true),
				"거리를 모르는 하드드롭은 가운데 단계");
		assertEquals(List.of(Cue.of(Sfx.SAND_LAND)), SoundCues.sandLock(-1));
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 2), Cue.of(Sfx.SAND_LAND)), SoundCues.sandLock(16), "멀리서 하드드롭");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 0), Cue.of(Sfx.SAND_LAND)), SoundCues.sandLock(1));
		assertEquals(Cue.of(Sfx.SAND_CLEAR), SoundCues.sandClear(1), "배율 1: 첫 지우기");
		assertEquals(Cue.of(Sfx.SAND_CHAIN, 2), SoundCues.sandClear(2), "콤보 시간 안의 다음 지우기");
		assertEquals(Sfx.SAND_CHAIN.tiers - 1, Sfx.SAND_CHAIN.tier(SoundCues.sandClear(10).intensity()),
				"×10 은 가장 높은 연쇄 소리");
	}

	@Test
	void theEndPlaysKoThenTheRightJingle() {
		Cue win = Cue.of(Sfx.WIN).withDelay(SoundCues.RESULT_GAP_MS);
		Cue lose = Cue.of(Sfx.LOSE).withDelay(SoundCues.RESULT_GAP_MS);
		assertEquals(List.of(Cue.of(Sfx.KO), win), SoundCues.result(TimedMatch.Result.P1, false));
		assertEquals(List.of(Cue.of(Sfx.KO), lose), SoundCues.result(TimedMatch.Result.P2, false));
		assertEquals(List.of(Cue.of(Sfx.KO)), SoundCues.result(TimedMatch.Result.DRAW, false));
		assertEquals(List.of(Cue.of(Sfx.KO), Cue.of(Sfx.NEW_RECORD).withDelay(SoundCues.RESULT_GAP_MS)),
				SoundCues.result(TimedMatch.Result.OVER, true), "혼자 연습·모래 모드의 최고 기록");
		assertEquals(Cue.of(Sfx.NEW_RECORD).withDelay(SoundCues.RESULT_GAP_MS + SoundCues.RECORD_AFTER_WIN_MS),
				SoundCues.result(TimedMatch.Result.P1, true).get(2), "승리 음악이 끝나 갈 때");
		for (Cue c : SoundCues.result(TimedMatch.Result.P1, true)) {
			assertEquals(0f, c.pan(), "판의 끝 소리는 가운데에서");
		}
	}
}
