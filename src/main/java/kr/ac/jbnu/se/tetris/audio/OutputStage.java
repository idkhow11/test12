package kr.ac.jbnu.se.tetris.audio;

/**
 * 섞은 소리를 장치로 보내기 직전의 마지막 단계: 전체 볼륨을 곱하고, 너무 커지면 부드럽게 눌러(리미터) 16 비트 PCM 으로
 * 바꾼다. 소리가 많이 겹쳐도 찢어지는 소리(클리핑)가 나지 않는다. 볼륨이 바뀌면 한 블록에 걸쳐 천천히 옮겨 가므로
 * 지지직거리지 않는다. 믹서 스레드 하나만 쓴다.
 */
final class OutputStage {

	/** 이보다 커지면 리미터가 줄인다. */
	static final float THRESHOLD = 0.8f;
	/** 마지막 안전장치(부드러운 포화)가 시작되는 크기. 리미터 덕분에 보통은 닿지 않는다. */
	private static final float KNEE = 0.9f;
	/** 리미터가 다시 풀리는 시간 상수 (초). */
	private static final double RELEASE_S = 0.12;

	private final float release = (float) Math.exp(-1.0 / (RELEASE_S * Synth.RATE));
	private float gain;
	private float env;

	/**
	 * {@code frames} 프레임의 스테레오 {@code mix} 를 볼륨 {@code target} 으로 PCM(16 비트, 작은 끝, 스테레오)으로 바꿔
	 * {@code out} 에 쓴다. 볼륨은 이 블록 동안 지금 값에서 {@code target} 으로 곧게 옮겨 간다.
	 */
	void process(float[] mix, int frames, float target, byte[] out) {
		if (!(env >= 0 && env < 1e6f)) { // NaN 이 섞여도 다음 블록부터는 다시 정상으로
			env = 0;
		}
		float g0 = gain;
		float step = (target - g0) / frames;
		for (int i = 0; i < frames; i++) {
			float g = g0 + step * (i + 1);
			float l = mix[2 * i] * g;
			float r = mix[2 * i + 1] * g;
			float peak = Math.max(Math.abs(l), Math.abs(r));
			env = peak > env ? peak : peak + (env - peak) * release; // 바로 누르고 천천히 푼다
			float k = env > THRESHOLD ? THRESHOLD / env : 1f;
			put(out, 4 * i, saturate(l * k));
			put(out, 4 * i + 2, saturate(r * k));
		}
		gain = target;
	}

	/**
	 * 볼륨을 옮겨 가지 않고 바로 {@code target} 으로 맞추고 리미터를 푼다. 조용하던 중(섞을 소리가 없던 때)에만 부른다:
	 * 그때는 끊김이 생기지 않고, 첫 소리의 시작(딸깍 같은 짧은 소리)이 흐려지지 않는다.
	 */
	void snap(float target) {
		gain = target;
		env = 0;
	}

	/** KNEE 까지는 그대로, 그 위는 1 에 다가가기만 하고 넘지 않는다. 기울기가 끊기지 않게 이어 붙였다. */
	static float saturate(float x) {
		float a = Math.abs(x);
		if (!(a > KNEE)) {
			return Float.isNaN(x) ? 0f : x;
		}
		float y = KNEE + (1 - KNEE) * (float) Math.tanh((a - KNEE) / (1 - KNEE));
		return Math.copySign(y, x);
	}

	private static void put(byte[] out, int at, float v) {
		int s = Math.round(v * 32767f);
		s = Math.max(-32767, Math.min(32767, s));
		out[at] = (byte) s;
		out[at + 1] = (byte) (s >> 8);
	}
}
