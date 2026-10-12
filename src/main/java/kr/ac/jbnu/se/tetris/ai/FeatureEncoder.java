package kr.ac.jbnu.se.tetris.ai;

import java.util.List;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 애프터스테이트와 상대 상태를 모델 입력 텐서로 바꾼다 (docs/MODEL_IO.md 3장, 형식 버전 2).
 * <p>
 * Python 의 {@code tetris_ai.model_io} 와 비트 단위로 같은 값을 만들어야 한다. 그래서 정수를 float 로 바꾸는 것
 * 말고는 계산하지 않는다. 정규화는 모델 안에서 한다.
 */
public final class FeatureEncoder {

	public static final String MODEL_IO_VERSION = "2";

	public static final int BOARD_SIZE = Grid.HEIGHT * Grid.WIDTH;
	public static final int FEATURES = 48;
	public static final int OPP_FEATURES = 10;

	private static final int PIECES = PieceType.values().length;
	static final int QUEUE_OFFSET = 0;
	static final int HOLD_OFFSET = Afterstate.MAX_QUEUE * PIECES; // 35
	static final int COMBO = HOLD_OFFSET + PIECES; // 42
	static final int B2B = COMBO + 1;
	static final int LINES = B2B + 1;
	static final int ATTACK = LINES + 1;
	static final int PENDING = ATTACK + 1;
	static final int GARBAGE_RISES = PENDING + 1; // 47

	// opp_features 의 위치 (MODEL_IO.md 3.4)
	static final int OPP_PENDING = 0;
	static final int OPP_COMBO = 1;
	static final int OPP_B2B = 2;
	static final int OPP_HOLD = 3;

	static {
		if (GARBAGE_RISES + 1 != FEATURES || OPP_HOLD + PIECES != OPP_FEATURES) {
			throw new AssertionError("features 배치가 MODEL_IO.md 와 다름");
		}
	}

	private FeatureEncoder() {
	}

	/** board 텐서 한 개를 {@code dst[offset ..]} 에 쓴다: 행 우선, 0 행이 맨 위, 채워진 칸 1. */
	public static void encodeBoard(Afterstate a, float[] dst, int offset) {
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				dst[offset + r * Grid.WIDTH + c] = a.cell(r, c) == Grid.EMPTY ? 0f : 1f;
			}
		}
	}

	/** features 텐서 한 개를 {@code dst[offset ..]} 에 쓴다. */
	public static void encodeFeatures(Afterstate a, float[] dst, int offset) {
		for (int i = 0; i < FEATURES; i++) {
			dst[offset + i] = 0f;
		}
		List<PieceType> queue = a.queue();
		for (int k = 0; k < queue.size(); k++) {
			dst[offset + QUEUE_OFFSET + k * PIECES + queue.get(k).ordinal()] = 1f;
		}
		if (a.hold() != null) {
			dst[offset + HOLD_OFFSET + a.hold().ordinal()] = 1f;
		}
		dst[offset + COMBO] = a.combo() + 1;
		dst[offset + B2B] = a.b2b() ? 1f : 0f;
		dst[offset + LINES] = a.lines();
		dst[offset + ATTACK] = a.attack();
		dst[offset + PENDING] = a.pending();
		dst[offset + GARBAGE_RISES] = a.garbageRises() ? 1f : 0f;
	}

	/** opp_board 텐서 한 개를 {@code dst[offset ..]} 에 쓴다. board 와 같은 방법이다. */
	public static void encodeOpponentBoard(Opponent o, float[] dst, int offset) {
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				dst[offset + r * Grid.WIDTH + c] = o.cell(r, c) == Grid.EMPTY ? 0f : 1f;
			}
		}
	}

	/** opp_features 텐서 한 개를 {@code dst[offset ..]} 에 쓴다. */
	public static void encodeOpponentFeatures(Opponent o, float[] dst, int offset) {
		for (int i = 0; i < OPP_FEATURES; i++) {
			dst[offset + i] = 0f;
		}
		dst[offset + OPP_PENDING] = o.pending();
		dst[offset + OPP_COMBO] = o.combo() + 1;
		dst[offset + OPP_B2B] = o.b2b() ? 1f : 0f;
		if (o.hold() != null) {
			dst[offset + OPP_HOLD + o.hold().ordinal()] = 1f;
		}
	}

	/** 후보 N 개의 board 텐서 [N, 24, 10] 를 평평하게 편 배열. */
	public static float[] boards(List<Afterstate> states) {
		float[] out = new float[states.size() * BOARD_SIZE];
		for (int n = 0; n < states.size(); n++) {
			encodeBoard(states.get(n), out, n * BOARD_SIZE);
		}
		return out;
	}

	/** 후보 N 개의 features 텐서 [N, 48] 를 평평하게 편 배열. */
	public static float[] features(List<Afterstate> states) {
		float[] out = new float[states.size() * FEATURES];
		for (int n = 0; n < states.size(); n++) {
			encodeFeatures(states.get(n), out, n * FEATURES);
		}
		return out;
	}

	/** 같은 상대 상태를 N 번 반복한 opp_board 텐서 [N, 24, 10] (MODEL_IO.md 1장). */
	public static float[] opponentBoards(Opponent o, int n) {
		float[] one = new float[BOARD_SIZE];
		encodeOpponentBoard(o, one, 0);
		return repeat(one, n);
	}

	/** 같은 상대 상태를 N 번 반복한 opp_features 텐서 [N, 10]. */
	public static float[] opponentFeatures(Opponent o, int n) {
		float[] one = new float[OPP_FEATURES];
		encodeOpponentFeatures(o, one, 0);
		return repeat(one, n);
	}

	private static float[] repeat(float[] one, int n) {
		float[] out = new float[one.length * n];
		for (int i = 0; i < n; i++) {
			System.arraycopy(one, 0, out, i * one.length, one.length);
		}
		return out;
	}
}
