package kr.ac.jbnu.se.tetris.core;

/**
 * 게임에서 쓰는 모든 난수의 출처 (RULES 5.1, 5.2).
 * <p>
 * Java 와 Python 이 비트 단위로 같은 수열을 만들어야 하므로 {@code java.util.Random} 을 쓰지 않는다.
 * 모든 연산은 부호 없는 64비트이며, Java 의 {@code long} 오버플로와 {@code >>>} 가 그 의미와 같다.
 */
public final class SplitMix64 {

	/** 스트림 번호 (RULES 5.2). 게임 시드 하나에서 용도별로 독립된 수열을 만든다. */
	public static final int STREAM_BAG = 1;
	public static final int STREAM_GARBAGE_P1 = 2;
	public static final int STREAM_GARBAGE_P2 = 3;
	public static final int STREAM_SCHEDULER = 4;

	private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

	private long state;

	public SplitMix64(long state) {
		this.state = state;
	}

	/**
	 * 게임 시드에서 k 번째 스트림을 만든다. 루트 생성기의 k 번째 출력을 초기 state 로 쓴다.
	 * <p>
	 * {@code seed ^ k * 상수} 처럼 만들면 seed = 0 일 때 스트림끼리 같은 수열을 한 칸씩 밀어 놓은 꼴이 된다.
	 */
	public static SplitMix64 stream(long gameSeed, int k) {
		SplitMix64 root = new SplitMix64(gameSeed);
		long s = 0;
		for (int i = 0; i < k; i++) {
			s = root.next();
		}
		return new SplitMix64(s);
	}

	/** 같은 위치에서 이어지는 독립된 복사본. 이후 두 생성기는 같은 수열을 따로 낸다. */
	public SplitMix64 copy() {
		return new SplitMix64(state);
	}

	public long next() {
		state += GOLDEN_GAMMA;
		long z = state;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/** 0 이상 n 미만. 호출 한 번에 {@link #next()} 를 정확히 한 번 소비한다(거부 샘플링 없음). */
	public int nextInt(int n) {
		return (int) Long.remainderUnsigned(next(), n);
	}
}
