package kr.ac.jbnu.se.tetris.audio;

/**
 * 들리는 크기 재기. 귀에 들리는 크기를 흉내 내는 ITU-R BS.1770 의 K 가중(높은 소리를 조금 올리고 아주 낮은 소리를 뺀다)을
 * 거친 뒤 가장 시끄러운 구간의 평균 제곱을 LUFS 비슷한 dB 로 잰다. 표본의 가장 큰 값만 보면 낮은 쿵 소리와 맑은 음의 크기를
 * 비교할 수 없어서 쓴다.
 * <p>
 * 효과음 크기는 가장 시끄러운 0.1초({@link #f100}, "F100")로 맞춘다: {@link SoundBank} 가 모든 소리를 이 값으로 정한
 * 크기에 맞춘다. 노트북 스피커 흉내({@link #smallSpeaker})는 250Hz 아래를 깎는다. 계산에 {@link StrictMath} 를 써서 어느
 * PC 에서나 같은 값이 나온다(소리 표본이 비트까지 같게).
 */
final class Loudness {

	/** 효과음 크기를 재는 구간 (ms). */
	static final double F100_MS = 100;

	private Loudness() {
	}

	/** 가장 시끄러운 0.1초의 크기 (dB). 소리 크기를 맞추는 기준이다. */
	static double f100(float[] clip) {
		return max(clip, F100_MS);
	}

	/** 가장 시끄러운 {@code windowMs} 구간의 크기 (dB). 클립이 구간보다 짧으면 뒤를 조용한 것으로 친다. */
	static double max(float[] clip, double windowMs) {
		double[] k = kWeighted(clip);
		int w = Math.max(1, (int) (windowMs * Synth.RATE / 1000));
		double sum = 0;
		double best = 0;
		for (int i = 0; i < k.length; i++) {
			sum += k[i] * k[i];
			if (i >= w) {
				sum -= k[i - w] * k[i - w];
			}
			best = Math.max(best, sum);
		}
		return -0.691 + 10 * StrictMath.log10(best / w + 1e-20);
	}

	/** 작은 스피커 흉내: 250Hz 2 차 고역 통과. */
	static float[] smallSpeaker(float[] clip) {
		double w0 = 2 * Math.PI * 250 / Synth.RATE;
		double alpha = StrictMath.sin(w0) / (2 * StrictMath.sqrt(0.5));
		double cs = StrictMath.cos(w0);
		double a0 = 1 + alpha;
		double[] y = biquad(clip, (1 + cs) / 2 / a0, -(1 + cs) / a0, (1 + cs) / 2 / a0, -2 * cs / a0, (1 - alpha) / a0);
		float[] out = new float[y.length];
		for (int i = 0; i < y.length; i++) {
			out[i] = (float) y[i];
		}
		return out;
	}

	/** BS.1770 K 가중: 1.7kHz 위를 약 4dB 올리는 선반 필터 + 38Hz 고역 통과. 계수는 표본률에 맞춰 계산한다. */
	private static double[] kWeighted(float[] clip) {
		double fs = Synth.RATE;
		double k = StrictMath.tan(Math.PI * 1681.974450955533 / fs);
		double q = 0.7071752369554196;
		double vh = StrictMath.pow(10, 3.999843853973347 / 20);
		double vb = StrictMath.pow(vh, 0.4996667741545416);
		double a0 = 1 + k / q + k * k;
		double[] shelf = biquad(clip, (vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
				2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0);
		k = StrictMath.tan(Math.PI * 38.13547087602444 / fs);
		q = 0.5003270373238773;
		double d = 1 + k / q + k * k;
		float[] tmp = new float[shelf.length];
		for (int i = 0; i < tmp.length; i++) {
			tmp[i] = (float) shelf[i];
		}
		return biquad(tmp, 1, -2, 1, 2 * (k * k - 1) / d, (1 - k / q + k * k) / d);
	}

	private static double[] biquad(float[] x, double b0, double b1, double b2, double a1, double a2) {
		double[] y = new double[x.length];
		double x1 = 0;
		double x2 = 0;
		double y1 = 0;
		double y2 = 0;
		for (int i = 0; i < x.length; i++) {
			double v = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
			x2 = x1;
			x1 = x[i];
			y2 = y1;
			y1 = v;
			y[i] = v;
		}
		return y;
	}
}
