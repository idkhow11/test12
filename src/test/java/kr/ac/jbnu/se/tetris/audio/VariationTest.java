package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * 작은 변화 (Variation): 섞어 쓰는 변형은 바로 앞의 것을 피하고(둘이면 번갈아) 고루 쓰이며, 시드가 같으면 같은 열이다. 조작·
 * 메뉴 소리 몇 가지만 음 높이와 크기를 정한 범위 안에서 흔들고, 음 높이가 정보인 소리는 언제나 그대로다. 누르고 있어 반복된
 * 이동은 3dB 작다.
 */
class VariationTest {

	/** 음 높이나 크기를 흔드는 소리 (효과음 표). */
	private static final Set<Sfx> JITTERED = EnumSet.of(Sfx.MENU_MOVE, Sfx.MOVE, Sfx.ROTATE, Sfx.LAND, Sfx.LOCK,
			Sfx.HARD_DROP, Sfx.GARBAGE_CANCEL, Sfx.SAND_LAND);

	@Test
	void shuffledVariantsNeverRepeatImmediatelyAndAllGetUsed() {
		Variation var = new Variation(42);
		for (Sfx e : Sfx.values()) {
			for (int tier = 0; tier < e.tiers; tier++) {
				int[] used = new int[e.choices];
				int prev = -1;
				for (int i = 0; i < 300; i++) {
					int c = var.choose(e, tier);
					assertTrue(c >= 0 && c < e.choices, e + ": 범위 안");
					if (e.choices > 1) {
						assertNotEquals(prev, c, e + " 묶음 " + tier + ": 바로 앞과 같은 변형을 다시 쓰지 않는다");
					}
					used[c]++;
					prev = c;
				}
				for (int k = 0; k < e.choices; k++) {
					assertTrue(used[k] >= 300 / e.choices / 2, e + " 변형 " + k + " 도 쓰인다 (" + used[k] + ")");
				}
			}
		}
	}

	@Test
	void twoVariantsAlternateAndTiersRememberSeparately() {
		Variation var = new Variation(7);
		int a = var.choose(Sfx.HARD_DROP, 0);
		int b = var.choose(Sfx.HARD_DROP, 2);
		for (int i = 0; i < 10; i++) {
			assertEquals(1 - a, var.choose(Sfx.HARD_DROP, 0), "단계 0 의 녹음 둘은 번갈아");
			a = 1 - a;
			assertEquals(1 - b, var.choose(Sfx.HARD_DROP, 2), "단계 2 는 따로 번갈아");
			b = 1 - b;
		}
	}

	@Test
	void theSameSeedGivesTheSameSequence() {
		Variation x = new Variation(123);
		Variation y = new Variation(123);
		for (int i = 0; i < 200; i++) {
			assertEquals(x.choose(Sfx.MOVE, 0), y.choose(Sfx.MOVE, 0));
			assertEquals(x.rate(Sfx.MOVE), y.rate(Sfx.MOVE));
			assertEquals(x.gain(Sfx.SAND_LAND, 1), y.gain(Sfx.SAND_LAND, 1));
		}
	}

	@Test
	void jitterStaysInsideTheTableRanges() {
		Variation var = new Variation(99);
		for (Sfx e : JITTERED) {
			double lowest = 1;
			double highest = 1;
			for (int i = 0; i < 2000; i++) {
				double cents = 1200 * Math.log(var.rate(e)) / Math.log(2);
				assertTrue(Math.abs(cents) <= e.jitterCents + 1e-9,
						e + ": ±" + e.jitterCents + " cents 안 (" + cents + ")");
				lowest = Math.min(lowest, cents);
				highest = Math.max(highest, cents);
				double db = 20 * Math.log10(var.gain(e, e.base));
				assertTrue(Math.abs(db) <= e.jitterDb + 1e-6, e + ": ±" + e.jitterDb + " dB 안 (" + db + ")");
			}
			assertTrue(lowest < -e.jitterCents / 2 && highest > e.jitterCents / 2, e + ": 범위를 고루 쓴다");
		}
		assertEquals(40, Sfx.MOVE.jitterCents);
		assertEquals(1, Sfx.MOVE.jitterDb);
		assertEquals(40, Sfx.SAND_LAND.jitterCents);
		assertEquals(1.5, Sfx.SAND_LAND.jitterDb);
		assertEquals(25, Sfx.HARD_DROP.jitterCents);
	}

	@Test
	void soundsWhosePitchIsInformationAreNeverJittered() {
		Variation var = new Variation(5);
		for (Sfx e : Sfx.values()) {
			if (JITTERED.contains(e)) {
				continue;
			}
			for (int i = 0; i < 50; i++) {
				assertEquals(1.0, var.rate(e), e + ": 음 높이 그대로");
				assertEquals(1f, var.gain(e, e.base), e + ": 크기 그대로");
			}
		}
		for (Sfx e : new Sfx[] { Sfx.CLEAR, Sfx.TSPIN, Sfx.COMBO, Sfx.SAND_CHAIN, Sfx.COUNTDOWN, Sfx.GO, Sfx.WIN,
				Sfx.LOSE, Sfx.NEW_RECORD, Sfx.B2B, Sfx.PERFECT_CLEAR, Sfx.KO }) {
			assertEquals(0, e.jitterCents, e + ": 음 높이가 정보인 소리");
			assertEquals(0, e.jitterDb, e.name());
		}
	}

	@Test
	void repeatedMovesAreThreeDecibelsQuieter() {
		Variation var = new Variation(1);
		double sum = 0;
		for (int i = 0; i < 4000; i++) {
			sum += 20 * Math.log10(var.gain(Sfx.MOVE, 2)) - 20 * Math.log10(var.gain(Sfx.MOVE, 1));
		}
		assertEquals(-3, sum / 4000, 0.1, "누르고 있어 반복된 이동(세기 2)은 평균 3dB 작다");
	}
}
