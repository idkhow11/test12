package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.audio.Sfx.Bus;

/** 효과음 종류의 설정 (Sfx): 세기 → 묶음과 변형, 묶음(버스), 덕킹, 쿨다운. 그리고 요청 값 Cue. */
class SfxTest {

	@Test
	void intensityPicksTheTierAndClamps() {
		assertEquals(0, Sfx.CLEAR.tier(1), "1줄");
		assertEquals(3, Sfx.CLEAR.tier(4), "4줄 = 테트리스");
		assertEquals(3, Sfx.CLEAR.tier(9), "범위를 넘으면 가장 큰 묶음");
		assertEquals(0, Sfx.CLEAR.tier(0), "범위보다 작으면 가장 작은 묶음");
		assertEquals(0, Sfx.SAND_CHAIN.tier(2), "연쇄는 2 부터");
		assertEquals(8, Sfx.SAND_CHAIN.tier(10));
		assertEquals(11, Sfx.COMBO.tier(Integer.MAX_VALUE), "넘침 없이");
		assertEquals(0, Sfx.COMBO.tier(Integer.MIN_VALUE), "넘침 없이");
		assertEquals(0, Sfx.KO.tier(5), "변형이 하나면 언제나 0");
		assertEquals(0, Sfx.TSPIN.tier(0), "T-spin 미니");
		assertEquals(1, Sfx.TSPIN.tier(1), "T-spin 싱글");
		assertEquals(3, Sfx.TSPIN.tier(3), "T-spin 트리플");
	}

	@Test
	void hardDropHasThreeDistanceTiersOfTwoRecordings() {
		assertEquals(3, Sfx.HARD_DROP.tiers);
		assertEquals(2, Sfx.HARD_DROP.choices);
		assertEquals(1, Sfx.HARD_DROP.tier(1), "세기 없이 부르면(1) 가운데 단계");
		assertEquals(2, Sfx.HARD_DROP.tier(2));
		assertEquals(0, Sfx.HARD_DROP.tier(-1));
		assertEquals(5, Sfx.HARD_DROP.variant(2, 1), "단계 2 의 두 번째 녹음");
	}

	@Test
	void b2bStreaksMapToThreeTiers() {
		int[] want = { 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2 };
		for (int streak = 1; streak <= 10; streak++) {
			assertEquals(want[streak], Sfx.B2B.tier(streak), "B2B ×" + streak);
		}
		assertEquals(2, Sfx.B2B.tier(50));
	}

	@Test
	void everySoundHasABusAndOnlyRewardsAndAlertsAreDucked() {
		for (Sfx e : Sfx.values()) {
			assertTrue(e.bus() != null, e.name());
		}
		assertEquals(Bus.HANDLING, Sfx.HARD_DROP.bus());
		assertEquals(Bus.HANDLING, Sfx.SAND_LAND.bus());
		assertEquals(Bus.REWARD, Sfx.CLEAR.bus());
		assertEquals(Bus.REWARD, Sfx.SAND_CHAIN.bus());
		assertEquals(Bus.ALERT, Sfx.GARBAGE_CANCEL.bus());
		assertEquals(Bus.ALERT, Sfx.DANGER.bus());
		assertEquals(Bus.UI, Sfx.MENU_SELECT.bus());
		assertEquals(Bus.UI, Sfx.PAUSE.bus());
		assertEquals(Bus.MATCH, Sfx.KO.bus());
		for (Bus b : Bus.values()) {
			assertEquals(b == Bus.REWARD || b == Bus.ALERT, b.duckable(), b + ": 조작·메뉴·판 흐름은 줄이지 않는다");
		}
	}

	@Test
	void heroSoundsDuckByTheTableAmounts() {
		assertEquals(5, Sfx.CLEAR.duckDb(4), "테트리스");
		assertEquals(0, Sfx.CLEAR.duckDb(3), "세 줄은 줄이지 않는다");
		assertEquals(5, Sfx.TSPIN.duckDb(2), "T-spin 더블");
		assertEquals(5, Sfx.TSPIN.duckDb(3), "T-spin 트리플");
		assertEquals(0, Sfx.TSPIN.duckDb(1));
		assertEquals(0, Sfx.TSPIN.duckDb(0), "미니");
		assertEquals(6, Sfx.PERFECT_CLEAR.duckDb(1));
		assertEquals(6, Sfx.KO.duckDb(1));
		assertEquals(5, Sfx.WIN.duckDb(1));
		assertEquals(5, Sfx.LOSE.duckDb(1));
		assertEquals(5, Sfx.NEW_RECORD.duckDb(1));
		assertEquals(4, Sfx.SAND_CHAIN.duckDb(5), "모래 연쇄 5 부터");
		assertEquals(0, Sfx.SAND_CHAIN.duckDb(4));
		assertEquals(0, Sfx.MOVE.duckDb(1));
		assertEquals(600, Sfx.PERFECT_CLEAR.duckReleaseMs(), "퍼펙트 클리어는 더 천천히 풀린다");
		assertEquals(400, Sfx.CLEAR.duckReleaseMs());
	}

	@Test
	void dangerBeatsFasterWhenWorse() {
		assertEquals(850, Sfx.DANGER.cooldownMs(1));
		assertEquals(600, Sfx.DANGER.cooldownMs(2));
		assertEquals(Sfx.MOVE.cooldownMs, Sfx.MOVE.cooldownMs(5), "다른 소리는 세기와 상관없다");
	}

	@Test
	void cooldownsFollowTheTable() {
		assertEquals(25, Sfx.MENU_MOVE.cooldownMs);
		assertEquals(150, Sfx.DENY.cooldownMs);
		assertEquals(30, Sfx.HARD_DROP.cooldownMs);
		assertEquals(120, Sfx.GARBAGE_CANCEL.cooldownMs);
		assertEquals(300, Sfx.PERFECT_CLEAR.cooldownMs);
		assertEquals(50, Sfx.SAND_LAND.cooldownMs);
	}

	@Test
	void everySoundHasSaneSettings() {
		for (Sfx e : Sfx.values()) {
			assertTrue(e.cooldownMs >= 0 && e.cooldownMs <= 2000, e + " 쿨다운");
			assertTrue(e.priority >= 0 && e.priority <= 3, e + " 중요도");
			assertTrue(e.maxVoices >= 1 && e.maxVoices < Voices.MAX_VOICES, e + " 같은 소리 동시 수");
			assertTrue(e.tiers >= 1 && e.choices >= 1, e + " 변형 수");
			assertEquals(e.tiers * e.choices, e.variants, e.name());
			assertTrue(e.jitterCents >= 0 && e.jitterCents <= 50 && e.jitterDb >= 0 && e.jitterDb <= 2, e + " 흔들기");
		}
		assertTrue(Sfx.MOVE.priority < Sfx.CLEAR.priority && Sfx.CLEAR.priority < Sfx.KO.priority,
				"자주 나는 소리가 먼저 양보하고, 판의 끝 소리는 끝까지 남는다");
	}

	@Test
	void cueClampsPanAndDelay() {
		Cue c = new Cue(Sfx.CLEAR, 2, 5f, -30);
		assertEquals(1f, c.pan());
		assertEquals(0, c.delayMs());
		assertEquals(0f, new Cue(Sfx.CLEAR, 2, Float.NaN, 0).pan());
		assertEquals(-1f, Cue.of(Sfx.MOVE).withPan(-3).pan());
		Cue later = Cue.of(Sfx.GARBAGE, 4).withPan(0.5).withDelay(450);
		assertEquals(new Cue(Sfx.GARBAGE, 4, 0.5f, 450), later);
		assertThrows(NullPointerException.class, () -> Cue.of(null));
	}
}
