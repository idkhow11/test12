package kr.ac.jbnu.se.tetris.ai;

import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 점수가 매겨진 후보 중 하나를 고른다 (docs/TRAINING.md 6장).
 * <p>
 * 온도 τ 가 0 이면 점수가 가장 높은 후보, 같으면 앞선 후보를 고른다. 후보를 정규화 키의 사전 순으로 넘기면 문서의 동점 처리와
 * 같다. τ > 0 이면 점수를 후보 점수의 표준편차로 나눈 뒤 softmax(점수 / τ) 확률로 뽑는다. 표준편차로 나누므로 모델마다 점수
 * 크기가 달라도 같은 τ 면 탐험 정도가 비슷하다. 학습용 대전과 게임의 난이도(온도)가 같은 방법을 쓴다.
 */
public final class Chooser {

	/** 뽑기 난수의 스트림 번호. 규칙의 스트림(RULES 5.2 의 1~4)과 겹치지 않는다. */
	public static final int STREAM_P1 = 101;
	public static final int STREAM_P2 = 102;

	private Chooser() {
	}

	/** 판 시드와 자리(1 또는 2)로 정해지는 뽑기 난수. 같은 시드, 같은 모델, 같은 τ 면 같은 판이 나온다. */
	public static SplitMix64 rng(long seed, int player) {
		if (player != 1 && player != 2) {
			throw new IllegalArgumentException("player 는 1 또는 2: " + player);
		}
		return SplitMix64.stream(seed, player == 1 ? STREAM_P1 : STREAM_P2);
	}

	/**
	 * @param scores      후보 점수
	 * @param temperature 온도 τ. 0 이하면 탐험하지 않는다
	 * @param rng         뽑기 난수. τ > 0 일 때만 한 번 소비한다
	 * @return 고른 후보의 위치
	 * @throws IllegalArgumentException 후보가 없을 때
	 * @throws IllegalStateException    점수에 NaN 이 있을 때(모델이 망가졌다는 뜻이라 조용히 넘어가지 않는다)
	 */
	public static int choose(double[] scores, double temperature, SplitMix64 rng) {
		int n = scores.length;
		if (n == 0) {
			throw new IllegalArgumentException("후보가 없음");
		}
		int best = 0;
		for (int i = 0; i < n; i++) {
			if (Double.isNaN(scores[i])) {
				throw new IllegalStateException("점수에 NaN 이 있음 (후보 " + i + ")");
			}
			if (scores[i] > scores[best]) {
				best = i;
			}
		}
		if (temperature <= 0 || n == 1) {
			return best;
		}
		double mean = 0;
		for (double s : scores) {
			mean += s;
		}
		mean /= n;
		double variance = 0;
		for (double s : scores) {
			variance += (s - mean) * (s - mean);
		}
		double std = Math.sqrt(variance / n);
		double[] weights = new double[n];
		double sum = 0;
		for (int i = 0; i < n; i++) {
			// 가장 높은 점수를 빼서 exp 가 넘치지 않게 한다. 표준편차가 0 이면 모든 후보가 같은 확률이다.
			weights[i] = std > 0 ? Math.exp((scores[i] - scores[best]) / (std * temperature)) : 1.0;
			sum += weights[i];
		}
		double u = nextDouble(rng) * sum;
		for (int i = 0; i < n; i++) {
			u -= weights[i];
			if (u < 0) {
				return i;
			}
		}
		return n - 1; // 반올림 오차로 끝까지 온 경우
	}

	/** 고른 후보보다 점수가 높은 후보의 수. 0 이면 1등이다. 기록의 {@code rank} (TRAINING.md 7.3). */
	public static int rank(double[] scores, int chosen) {
		int higher = 0;
		for (double s : scores) {
			if (s > scores[chosen]) {
				higher++;
			}
		}
		return higher;
	}

	/** [0, 1) 균등 분포. 53 비트를 쓴다. */
	static double nextDouble(SplitMix64 rng) {
		return (rng.next() >>> 11) * 0x1.0p-53;
	}
}
