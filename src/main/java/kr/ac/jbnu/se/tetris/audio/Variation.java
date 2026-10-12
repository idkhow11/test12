package kr.ac.jbnu.se.tetris.audio;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * 같은 소리가 기계처럼 똑같이 반복되지 않게 하는 작은 변화. 믹서 스레드 하나만 쓴다(동기화하지 않는다).
 * <ul>
 * <li>한 묶음에 비슷한 변형이 여럿이면({@link Sfx#choices}) 그중 하나를 고르되 바로 앞에 쓴 것은 피한다(둘이면 번갈아).</li>
 * <li>조작·메뉴 소리 몇 가지는 음 높이({@link Sfx#jitterCents})와 크기({@link Sfx#jitterDb})를 매번 조금씩 바꾼다. 음
 * 높이가 정보인 소리(줄 지우기, T-spin, 콤보, 모래 연쇄, 카운트다운, GO, 음악)는 바꾸지 않는다: 그런 소리는 흔들기 값이
 * 0 이다.</li>
 * </ul>
 * 이 변화는 소리에만 쓰고 게임 진행과는 상관없다(판의 난수를 쓰지 않는다). 시험은 시드를 정해 같은 열을 얻는다.
 */
final class Variation {

	private final SplittableRandom rng;
	/** [소리][묶음] → 마지막으로 고른 변형 (-1 이면 아직 없음). */
	private final int[][] last;

	Variation(long seed) {
		rng = new SplittableRandom(seed);
		Sfx[] all = Sfx.values();
		last = new int[all.length][];
		for (Sfx e : all) {
			last[e.ordinal()] = new int[e.tiers];
			Arrays.fill(last[e.ordinal()], -1);
		}
	}

	/** 소리 {@code e} 의 묶음 {@code tier} 에서 쓸 변형(0 ~ choices-1). 바로 앞에 고른 것과 다르다(변형이 둘 이상이면). */
	int choose(Sfx e, int tier) {
		int n = e.choices;
		if (n <= 1) {
			return 0;
		}
		int[] mem = last[e.ordinal()];
		int t = Math.max(0, Math.min(mem.length - 1, tier));
		int prev = mem[t];
		int pick;
		if (prev < 0) {
			pick = rng.nextInt(n);
		} else {
			pick = rng.nextInt(n - 1);
			if (pick >= prev) {
				pick++;
			}
		}
		mem[t] = pick;
		return pick;
	}

	/** 이번에 읽을 빠르기 = 2^(cents/1200). 흔들지 않는 소리는 정확히 1. */
	double rate(Sfx e) {
		double c = e.jitterCents;
		if (c <= 0) {
			return 1.0;
		}
		return Math.pow(2, (rng.nextDouble() * 2 - 1) * c / 1200);
	}

	/** 이번 크기 배율: 세기에 따른 조정({@link Sfx#gainDb}) + 흔들기. 흔들지도 줄이지도 않는 소리는 정확히 1. */
	float gain(Sfx e, int intensity) {
		double db = e.gainDb(intensity);
		if (e.jitterDb > 0) {
			db += (rng.nextDouble() * 2 - 1) * e.jitterDb;
		}
		return db == 0 ? 1f : (float) Math.pow(10, db / 20);
	}
}
