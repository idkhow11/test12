package kr.ac.jbnu.se.tetris.audio;

import java.util.Arrays;

/**
 * 효과음 합성 부품. 소리 파일 없이 파형(사인·삼각·사각·톱니·잡음), 음 높이 미끄러짐, 떨림(비브라토), 포락선, 필터, 메아리,
 * 잔향으로 모노 표본(-1 ~ 1, {@link #RATE} Hz)을 만든다. 잡음도 시드로 정해지고 시각·스레드·바뀌는 전역 상태를 쓰지 않는다.
 * 지수·거듭제곱 같은 함수는 {@link StrictMath} 로 계산하고(Math 는 CPU 마다 마지막 자리가 다를 수 있다) 사인은 StrictMath 로
 * 만든 표를 보간하므로, 같은 코드면 어느 PC 에서나 비트까지 같은 표본이 나온다.
 * <p>
 * 음 하나는 {@link #note} 나 {@link #noise} 로 만들어 설정한 뒤 {@link Note#into} 로 버퍼에 더한다. 예:
 * {@code note(0, 120, midi(84)).wave(Wave.TRIANGLE).perc(2, 40).gain(0.8).into(buf)}.
 */
final class Synth {

	/** 표본률 (Hz). 출력 장치도 이 값으로 연다. */
	static final int RATE = 44_100;
	/** 이보다 높은 주파수는 그리지 않는다(나이퀴스트 근처에서 생기는 거슬리는 잡음을 막는다). */
	private static final double MAX_HZ = RATE * 0.45;

	/** 파형. 사각·톱니는 PolyBLEP 으로 계단을 다듬어 높은 음에서도 거슬리는 잡음(앨리어싱)이 적다. */
	enum Wave {
		SINE, TRIANGLE, SQUARE, SAW, NOISE
	}

	/** 필터 종류 (상태 변수 필터). 띠 통과는 가운데 주파수에서 크기 1 이다. */
	enum Filter {
		NONE, LOW, BAND, HIGH
	}

	private Synth() {
	}

	/** ms → 표본 수. */
	static int frames(double ms) {
		return (int) Math.round(ms * RATE / 1000.0);
	}

	/** ms 길이의 빈 버퍼. */
	static float[] buffer(double ms) {
		return new float[frames(ms)];
	}

	/** MIDI 음 번호 → Hz (69 = A4 = 440Hz, 60 = C4, 84 = C6). */
	static double midi(double note) {
		return 440.0 * StrictMath.pow(2, (note - 69) / 12.0);
	}

	/** {@code startMs} 에 시작해 {@code lenMs} 동안 울리는 음 하나 (기본은 사인, 2ms 에 올라오는 평평한 포락선). */
	static Note note(double startMs, double lenMs, double hz) {
		return new Note(startMs, lenMs, hz);
	}

	/** 잡음 하나. 보통 필터로 색을 입힌다. */
	static Note noise(double startMs, double lenMs, long seed) {
		return new Note(startMs, lenMs, 0).wave(Wave.NOISE).seed(seed);
	}

	/** 음 하나의 설정. 메서드는 모두 자기 자신을 돌려주므로 이어서 쓴다. */
	static final class Note {
		private final double start;
		private final double len;
		private final double f0;
		private double f1;
		private double glideMs;
		private Wave wave = Wave.SINE;
		private double[] partials = { 1 };
		private double attack = 2;
		private double tau; // 0 보다 크면 타악기형: 올라온 뒤 시간 상수 tau(ms)로 지수 감쇠
		private double decay;
		private double sustain = 1;
		private double release = 6;
		private double vibHz;
		private double vibCents;
		private double detune = 1;
		private double gain = 1;
		private Filter filter = Filter.NONE;
		private double cut0;
		private double cut1;
		private double q = 0.707;
		private long seed = 1;

		private Note(double start, double len, double hz) {
			this.start = start;
			this.len = len;
			this.f0 = hz;
			this.f1 = hz;
		}

		/** 끝 주파수. 처음 주파수에서 지수 곡선으로 미끄러진다. */
		Note to(double hz) {
			f1 = hz;
			return this;
		}

		/** 미끄러짐이 끝나는 시간 (기본: 음 전체). */
		Note glide(double ms) {
			glideMs = ms;
			return this;
		}

		Note wave(Wave w) {
			wave = w;
			return this;
		}

		/** 사인일 때 배음 세기 (첫 값이 기본음). 표본률의 0.45 배를 넘는 배음은 뺀다. */
		Note partials(double... amps) {
			partials = amps.clone();
			return this;
		}

		/** 타악기형 포락선: {@code attackMs} 에 걸쳐 부드럽게 올라온 뒤 시간 상수 {@code tauMs} 로 줄어든다. */
		Note perc(double attackMs, double tauMs) {
			attack = attackMs;
			tau = tauMs;
			return this;
		}

		/** ADSR 포락선: 올라옴, 감쇠(sustain 까지), 유지 크기, 끝의 사라짐 (ms). */
		Note adsr(double a, double d, double s, double r) {
			attack = a;
			decay = d;
			sustain = s;
			release = r;
			tau = 0;
			return this;
		}

		/** 음 끝에서 0 으로 사라지는 시간 (기본 6ms). */
		Note release(double ms) {
			release = ms;
			return this;
		}

		Note vibrato(double hz, double cents) {
			vibHz = hz;
			vibCents = cents;
			return this;
		}

		/** 음 높이를 cents(반음의 1/100)만큼 옮긴다. 둘을 반대로 옮겨 겹치면 합창처럼 넓어진다. */
		Note detune(double cents) {
			detune = StrictMath.pow(2, cents / 1200.0);
			return this;
		}

		Note gain(double g) {
			gain = g;
			return this;
		}

		Note lowpass(double hz) {
			return filter(Filter.LOW, hz, hz, 0.707);
		}

		/** 차단 주파수가 음 동안 {@code fromHz} 에서 {@code toHz} 로 옮겨 가는 저역 통과. */
		Note lowpass(double fromHz, double toHz) {
			return filter(Filter.LOW, fromHz, toHz, 0.707);
		}

		Note highpass(double hz) {
			return filter(Filter.HIGH, hz, hz, 0.707);
		}

		/** 가운데 주파수가 음 동안 옮겨 가는 띠 통과. q 가 크면 좁고 울린다. */
		Note band(double fromHz, double toHz, double q) {
			return filter(Filter.BAND, fromHz, toHz, q);
		}

		Note filter(Filter f, double fromHz, double toHz, double quality) {
			filter = f;
			cut0 = fromHz;
			cut1 = toHz;
			q = quality;
			return this;
		}

		Note seed(long s) {
			seed = s;
			return this;
		}

		/** 버퍼에 더한다. 버퍼 밖으로 나가는 부분은 버린다. */
		void into(float[] buf) {
			int s0 = frames(start);
			int n = frames(len);
			int glideFrames = Math.max(1, frames(glideMs > 0 ? glideMs : len));
			double f = f0 * detune;
			double fStep = f1 == f0 ? 1 : StrictMath.pow(f1 / f0, 1.0 / glideFrames);
			double cut = cut0;
			double cutStep = cut1 == cut0 || cut0 <= 0 ? 1 : StrictMath.pow(cut1 / cut0, 1.0 / Math.max(1, n));
			Svf svf = filter == Filter.NONE ? null : new Svf(q);
			long rng = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
			double phase = 0;
			// 올라온 뒤의 지수 감쇠: 표본마다 exp 를 부르지 않고 같은 비율을 곱한다
			double fallStep = tau > 0 ? StrictMath.exp(-1000.0 / (RATE * tau))
					: decay > 0 ? StrictMath.exp(-4000.0 / (RATE * decay)) : 1;
			double fall = -1; // 올라온 뒤 줄어든 몫, 아직 올라오는 중이면 -1
			for (int i = 0; i < n; i++) {
				int idx = s0 + i;
				if (idx >= buf.length) {
					break;
				}
				double t = i * 1000.0 / RATE;
				if (t >= attack) {
					fall = fall < 0 ? firstFall(t) : fall * fallStep;
				}
				double hz = f;
				if (vibCents != 0) {
					hz *= StrictMath.pow(2, vibCents / 1200.0 * sin2pi(vibHz * i / RATE));
				}
				double dt = hz / RATE;
				double v;
				switch (wave) {
				case TRIANGLE:
					v = hz < MAX_HZ ? triangle(phase) : 0;
					break;
				case SQUARE:
					v = hz < MAX_HZ ? square(phase, dt) : 0;
					break;
				case SAW:
					v = hz < MAX_HZ ? saw(phase, dt) : 0;
					break;
				case NOISE:
					rng = next(rng);
					v = unit(rng) * 2 - 1;
					break;
				default:
					v = additive(phase, hz);
					break;
				}
				phase += dt;
				if (phase >= 1) {
					phase -= Math.floor(phase);
				}
				if (i < glideFrames) {
					f *= fStep;
				}
				if (svf != null) {
					if ((i & 15) == 0) {
						svf.tune(cut);
					}
					v = svf.process(v, filter);
					cut *= cutStep;
				}
				if (idx >= 0) {
					buf[idx] += (float) (v * envelope(t, fall) * gain);
				}
			}
		}

		/** 올라온 뒤 첫 표본({@code t} ms)에서 줄어든 몫. 그 뒤로는 표본마다 같은 비율을 곱한다. */
		private double firstFall(double t) {
			if (tau > 0) {
				return StrictMath.exp(-(t - attack) / tau);
			}
			return decay > 0 ? StrictMath.exp(-4.0 * (t - attack) / decay) : 0;
		}

		/**
		 * 음이 시작한 뒤 {@code t} ms 의 크기 (0 ~ 1). {@code fall} 은 올라온 뒤 줄어든 몫({@link #firstFall} 에서 시작해
		 * 표본마다 같은 비율로 줄어든다). 끝의 release 동안 0 으로 내려가므로 딸깍 소리가 나지 않는다.
		 */
		private double envelope(double t, double fall) {
			double a;
			if (t < attack) {
				a = smooth(t / attack);
			} else if (tau > 0) {
				a = fall;
			} else if (decay > 0) {
				a = sustain + (1 - sustain) * fall;
			} else {
				a = sustain;
			}
			double left = len - t;
			if (left < release) {
				a *= smooth(Math.max(0, left / release));
			}
			return a;
		}

		/** 사인 + 배음. 너무 높은 배음은 뺀다. */
		private double additive(double phase, double hz) {
			double v = 0;
			for (int k = 0; k < partials.length; k++) {
				if (hz * (k + 1) >= MAX_HZ) {
					break;
				}
				if (partials[k] != 0) {
					v += partials[k] * sin2pi((k + 1) * phase);
				}
			}
			return v;
		}
	}

	// ------------------------------------------------------------------ 사인 표

	/** 사인 표의 한 주기 칸 수. */
	private static final int SINE_SIZE = 4096;
	/** 한 주기의 사인 값 (끝에 한 칸 더: 보간할 때 다음 칸). StrictMath 로 한 번 만든다. */
	private static final double[] SINE = new double[SINE_SIZE + 1];

	static {
		for (int i = 0; i <= SINE_SIZE; i++) {
			SINE[i] = StrictMath.sin(2 * Math.PI * i / SINE_SIZE);
		}
	}

	/**
	 * sin(2π × {@code cycles}). 표를 선형 보간한다: 오차는 3 × 10⁻⁷ (-130dB) 아래라 들리지 않고, 매번 사인을 계산하는 것보다
	 * 빠르며, 더하기·곱하기만 쓰므로 어느 PC 에서나 같은 값이다.
	 */
	static double sin2pi(double cycles) {
		double x = (cycles - Math.floor(cycles)) * SINE_SIZE;
		int i = Math.min((int) x, SINE_SIZE - 1);
		double frac = x - i;
		return SINE[i] + (SINE[i + 1] - SINE[i]) * frac;
	}

	// ------------------------------------------------------------------ 파형

	/** 0 에서 시작해 올라가는 삼각파. */
	private static double triangle(double phase) {
		double p = phase + 0.25;
		p -= Math.floor(p);
		return 1 - 4 * Math.abs(p - 0.5);
	}

	private static double square(double phase, double dt) {
		double v = phase < 0.5 ? 1 : -1;
		v += polyBlep(phase, dt);
		double p = phase + 0.5;
		p -= Math.floor(p);
		return v - polyBlep(p, dt);
	}

	private static double saw(double phase, double dt) {
		return 2 * phase - 1 - polyBlep(phase, dt);
	}

	/** 계단 근처를 2 차 다항식으로 다듬는다 (PolyBLEP). */
	private static double polyBlep(double t, double dt) {
		if (dt <= 0) {
			return 0;
		}
		if (t < dt) {
			double x = t / dt;
			return x + x - x * x - 1;
		}
		if (t > 1 - dt) {
			double x = (t - 1) / dt;
			return x * x + x + x + 1;
		}
		return 0;
	}

	/** 0 → 1 을 처음과 끝의 기울기가 0 이 되게 잇는다. */
	static double smooth(double x) {
		double c = Math.max(0, Math.min(1, x));
		return c * c * (3 - 2 * c);
	}

	// ------------------------------------------------------------------ 난수 (잡음, 알갱이)

	/** xorshift64*. 시드가 같으면 같은 열. */
	static long next(long s) {
		long x = s == 0 ? 0x2545F4914F6CDD1DL : s;
		x ^= x >>> 12;
		x ^= x << 25;
		x ^= x >>> 27;
		return x * 0x2545F4914F6CDD1DL;
	}

	/** [0, 1) 의 실수. */
	static double unit(long s) {
		return (s >>> 11) * 0x1.0p-53;
	}

	// ------------------------------------------------------------------ 필터

	/** 상태 변수 필터 (Simper 의 TPT 구조): 차단 주파수를 움직여도 안정하다. */
	private static final class Svf {
		private final double k;
		private double g;
		private double a1;
		private double a2;
		private double a3;
		private double ic1;
		private double ic2;

		Svf(double q) {
			k = 1 / Math.max(0.1, q);
			tune(1000);
		}

		void tune(double hz) {
			g = StrictMath.tan(Math.PI * Math.max(10, Math.min(hz, MAX_HZ)) / RATE);
			a1 = 1 / (1 + g * (g + k));
			a2 = g * a1;
			a3 = g * a2;
		}

		double process(double v0, Filter type) {
			double v3 = v0 - ic2;
			double v1 = a1 * ic1 + a2 * v3;
			double v2 = ic2 + a2 * ic1 + a3 * v3;
			ic1 = 2 * v1 - ic1;
			ic2 = 2 * v2 - ic2;
			switch (type) {
			case LOW:
				return v2;
			case BAND:
				return k * v1;
			default:
				return v0 - k * v1 - v2;
			}
		}
	}

	/** 아주 낮은 웅웅거림(직류 성분 포함)을 걸러 낸다. 한 극 고역 통과, 제자리에서. */
	static void highpass(float[] buf, double hz) {
		double rc = 1 / (2 * Math.PI * hz);
		double a = rc / (rc + 1.0 / RATE);
		double prevIn = 0;
		double prevOut = 0;
		for (int i = 0; i < buf.length; i++) {
			double x = buf[i];
			double y = a * (prevOut + x - prevIn);
			prevIn = x;
			prevOut = y;
			buf[i] = (float) y;
		}
	}

	// ------------------------------------------------------------------ 시간 효과

	/** 메아리: {@code delayMs} 마다 {@code feedback} 배로 줄어드는 되풀이를 버퍼 끝까지 더한다. */
	static void echo(float[] buf, double delayMs, double feedback) {
		int d = Math.max(1, frames(delayMs));
		for (int i = d; i < buf.length; i++) {
			buf[i] += (float) (buf[i - d] * feedback);
		}
	}

	private static final int[] COMBS = { 1116, 1188, 1277, 1356, 1422, 1491 };
	private static final int[] ALLPASSES = { 556, 441, 341, 225 };

	/**
	 * 작은 방 잔향 (Freeverb 구조를 줄인 것): 빗살 필터 6 개를 나란히, 전역 통과 4 개를 차례로. 원래 소리에 {@code wet} 만큼
	 * 더한다. 버퍼에 꼬리가 들어갈 자리를 남겨 둬야 한다.
	 *
	 * @param room 빗살 필터의 되먹임 (0.6 ~ 0.85: 클수록 길게 울린다)
	 * @param damp 높은 소리가 빨리 줄어드는 정도 (0 ~ 1)
	 */
	static void reverb(float[] buf, double wet, double room, double damp) {
		float[] tail = new float[buf.length];
		for (int size : COMBS) {
			float[] line = new float[size];
			int p = 0;
			double store = 0;
			for (int i = 0; i < buf.length; i++) {
				double y = line[p];
				store = y * (1 - damp) + store * damp;
				line[p] = (float) (buf[i] * 0.02 + store * room);
				tail[i] += (float) y;
				if (++p == size) {
					p = 0;
				}
			}
		}
		for (int size : ALLPASSES) {
			float[] line = new float[size];
			int p = 0;
			for (int i = 0; i < buf.length; i++) {
				double in = tail[i];
				double y = line[p];
				line[p] = (float) (in + y * 0.5);
				tail[i] = (float) (y - in);
				if (++p == size) {
					p = 0;
				}
			}
		}
		for (int i = 0; i < buf.length; i++) {
			buf[i] += (float) (tail[i] * wet * 3);
		}
	}

	/**
	 * 알갱이 소리: 짧은 잡음 알갱이를 앞쪽에 많이, 뒤로 갈수록 드물고 작게 흩뿌린다(모래가 부서지는 소리). 알갱이마다
	 * 높이(띠 통과 가운데)와 길이가 다르다.
	 */
	static void grains(float[] buf, double startMs, double lenMs, int count, double minMs, double maxMs, double lowHz,
			double highHz, double gain, long seed) {
		long r = seed;
		for (int k = 0; k < count; k++) {
			r = next(r);
			double u = unit(r);
			r = next(r);
			double dur = minMs + (maxMs - minMs) * unit(r);
			r = next(r);
			double hz = lowHz * StrictMath.pow(highHz / lowHz, unit(r));
			r = next(r);
			double t = startMs + lenMs * u * u; // 앞쪽에 몰린다
			double amp = (0.35 + 0.65 * unit(r)) * (1 - 0.75 * u * u);
			noise(t, dur, r).band(hz, hz, 1.1).perc(dur * 0.15, dur * 0.35).release(dur * 0.4).gain(gain * amp)
					.into(buf);
		}
	}

	// ------------------------------------------------------------------ 마무리

	/** 처음 {@code inMs} 와 끝 {@code outMs} 를 부드럽게 0 으로 (잘린 곳에서 딸깍 소리가 나지 않게). */
	static void fade(float[] buf, double inMs, double outMs) {
		int in = Math.min(buf.length, Math.max(1, frames(inMs)));
		int out = Math.min(buf.length, Math.max(1, frames(outMs)));
		for (int i = 0; i < in; i++) {
			buf[i] *= (float) smooth(i / (double) in);
		}
		for (int i = 0; i < out; i++) {
			buf[buf.length - 1 - i] *= (float) smooth(i / (double) out);
		}
	}

	/**
	 * 끝의 거의 들리지 않는 부분(가장 큰 표본보다 {@code db} 데시벨 아래)을 잘라 낸 사본. 잔향 꼬리가 길게 남아 기억 장소와
	 * 섞는 일만 늘리지 않게 한다. 잘린 끝은 {@link #fade} 로 부드럽게 한다.
	 */
	static float[] trim(float[] buf, double db, double padMs) {
		float threshold = (float) (peak(buf) * StrictMath.pow(10, db / 20));
		int last = buf.length - 1;
		while (last > 0 && Math.abs(buf[last]) <= threshold) {
			last--;
		}
		int len = Math.min(buf.length, last + 1 + frames(padMs));
		return len == buf.length ? buf : Arrays.copyOf(buf, Math.max(1, len));
	}

	/** 가장 큰 표본의 절댓값. */
	static float peak(float[] buf) {
		float p = 0;
		for (float x : buf) {
			p = Math.max(p, Math.abs(x));
		}
		return p;
	}

	/** 가장 큰 표본이 {@code level} 이 되게 키우거나 줄인다. */
	static void normalize(float[] buf, double level) {
		float p = peak(buf);
		if (p <= 0) {
			return;
		}
		float k = (float) (level / p);
		for (int i = 0; i < buf.length; i++) {
			buf[i] *= k;
		}
	}
}
