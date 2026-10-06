package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.SplitMix64;

/** 배치 고르기 (TRAINING.md 6장): 온도 0 의 동점 처리, 표준편차로 나눈 softmax, 재현성. */
class ChooserTest {

	@Test
	void zeroTemperaturePicksBestAndFirstOnTies() {
		assertEquals(2, Chooser.choose(new double[] { 1, 3, 5, 4 }, 0, new SplitMix64(0)));
		assertEquals(1, Chooser.choose(new double[] { 1, 5, 5, 4 }, 0, new SplitMix64(0)), "동점이면 앞선 후보");
	}

	@Test
	void zeroTemperatureDoesNotConsumeRandomness() {
		SplitMix64 rng = new SplitMix64(42);
		Chooser.choose(new double[] { 1, 2 }, 0, rng);
		assertEquals(new SplitMix64(42).next(), rng.next());
	}

	@Test
	void sameSeedSameChoices() {
		double[] scores = { 0.1, -0.4, 0.3, 0.2, -1.0 };
		SplitMix64 a = Chooser.rng(123, 1);
		SplitMix64 b = Chooser.rng(123, 1);
		for (int i = 0; i < 50; i++) {
			assertEquals(Chooser.choose(scores, 0.5, a), Chooser.choose(scores, 0.5, b));
		}
	}

	@Test
	void softmaxUsesScoresDividedByStandardDeviation() {
		// 점수 {1, 0}: 표준편차 0.5. τ = 1 이면 둘째 후보의 가중치는 exp((0 - 1) / 0.5) = e^-2,
		// 확률은 e^-2 / (1 + e^-2) ≈ 0.1192. 점수를 10 배 해도 표준편차로 나누므로 같은 확률이다.
		for (double scale : new double[] { 1, 10 }) {
			SplitMix64 rng = new SplitMix64(7);
			int second = 0;
			int trials = 40_000;
			for (int i = 0; i < trials; i++) {
				second += Chooser.choose(new double[] { scale, 0 }, 1.0, rng);
			}
			double p = Math.exp(-2) / (1 + Math.exp(-2));
			assertEquals(p, (double) second / trials, 0.006, "점수 배율 " + scale);
		}
	}

	@Test
	void equalScoresAreUniform() {
		SplitMix64 rng = new SplitMix64(9);
		int[] counts = new int[4];
		int trials = 40_000;
		for (int i = 0; i < trials; i++) {
			counts[Chooser.choose(new double[] { 3, 3, 3, 3 }, 0.3, rng)]++;
		}
		for (int c : counts) {
			assertEquals(0.25, (double) c / trials, 0.01);
		}
	}

	@Test
	void rankCountsHigherScores() {
		double[] scores = { 0.5, 2.0, 1.0, 2.0 };
		assertEquals(0, Chooser.rank(scores, 1));
		assertEquals(2, Chooser.rank(scores, 2));
		assertEquals(3, Chooser.rank(scores, 0));
	}

	@Test
	void rejectsEmptyAndNaN() {
		assertThrows(IllegalArgumentException.class, () -> Chooser.choose(new double[0], 0, new SplitMix64(0)));
		assertThrows(IllegalStateException.class,
				() -> Chooser.choose(new double[] { 1, Double.NaN }, 0.3, new SplitMix64(0)));
	}

	@Test
	void playerStreamsDifferAndAvoidRuleStreams() {
		assertEquals(101, Chooser.STREAM_P1);
		assertEquals(102, Chooser.STREAM_P2);
		assertThrows(IllegalArgumentException.class, () -> Chooser.rng(0, 3));
		long a = Chooser.rng(5, 1).next();
		long b = Chooser.rng(5, 2).next();
		assertEquals(false, a == b);
	}
}
