package kr.ac.jbnu.se.tetris.audio;

import static kr.ac.jbnu.se.tetris.audio.Synth.buffer;
import static kr.ac.jbnu.se.tetris.audio.Synth.note;
import static kr.ac.jbnu.se.tetris.audio.Synth.noise;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import kr.ac.jbnu.se.tetris.audio.Synth.Wave;

/**
 * 모든 효과음의 표본. 시작할 때 {@link #generate()} 가 한 번 만든다. 대부분은 코드로 합성하고, 합성으로는 잘 안 되는 소리
 * (부딪히는 소리, 모래가 사각거리는 소리, K.O. 의 부서짐, 메뉴 소리)만 녹음({@link Samples}, CC0)을 쓰거나 합성음에 겹친다.
 * 녹음을 읽지 못하면 그 소리는 합성음으로 대신한다. 같은 코드와 같은 녹음이면 언제나 같은 표본이 나온다.
 * <p>
 * 음은 다장조(C)를 중심으로 골라 여러 소리가 겹쳐도 어울리게 했다: 줄 지우기는 도·미·솔로 오르고, 콤보와 모래 연쇄는
 * 5음 음계로 한 단계씩 올라간다. 시작은 부드럽게(딸깍 없이), 자주 나는 소리(이동·회전)일수록 짧고 작다. 높은 음의 반짝이는
 * 배음은 8kHz(종소리는 12kHz)를 넘지 않게 해서 귀가 따갑지 않다.
 * <p>
 * <b>크기.</b> 모든 소리를 귀에 들리는 크기 한 가지 기준으로 맞춘다: 가장 시끄러운 0.1초의 K 가중 크기
 * ({@link Loudness#f100}, "F100")가 {@link #target(Sfx, int)} 이 되게 한다. 목표는 테트리스(4줄 지우기)를 0 으로 한 상대값
 * {@link #rel(Sfx, int)} 이다(효과음 표, docs 의 품질 설계 2.2). 그래서 소리의 생김새를 고쳐도 크기 순서는 그대로다. 맞춘 뒤
 * 가장 큰 표본이 {@link #PEAK_LIMIT} 를 넘으면 그만큼 줄이고 그 양을 적어 둔다({@link #guardLossDb}).
 */
final class SoundBank {

	/** 기준 크기: 테트리스 소리의 F100 (dB). 다른 소리의 목표는 이 값 + {@link #rel(Sfx, int)}. */
	static final double REFERENCE_F100 = -15.3;
	/** 크기를 맞춘 뒤 가장 큰 표본의 상한. 넘으면 줄인다(겹쳐도 여유가 있게). */
	static final float PEAK_LIMIT = 0.9f;
	/** 반음의 주파수 비. */
	static final double SEMITONE = StrictMath.pow(2, 1 / 12.0);

	/** 하드드롭 녹음 위에 겹치는 합성 쉭 소리의 상대 크기 (dB). */
	static final double WHOOSH_DB = -6;
	/** K.O. 합성음 위에 겹치는 부서지는 녹음의 상대 크기 (dB). */
	static final double CRUNCH_DB = -2;
	/** 모래 녹음 위에 겹치는 합성 알갱이 소리의 상대 크기 (dB). */
	static final double GRAINS_DB = -6;
	/** 방해 줄 경고의 쿵마다 겹치는 경보음(사각파 라, 440Hz)의 상대 크기 (dB). */
	static final double ALARM_DB = -9;
	/** B2B 4~7 연속에 겹치는 한 옥타브 위 화음의 상대 크기 (dB). */
	static final double B2B_OCTAVE_DB = -8;
	/** B2B 8 연속 이상에 겹치는 5~8kHz 반짝임의 상대 크기 (dB). */
	static final double B2B_SHIMMER_DB = -14;

	/** [소리][변형] → 모노 표본 ({@link Synth#RATE} Hz, -1 ~ 1). */
	private final float[][][] clips;
	/** [소리][변형] → 가장 큰 표본의 상한 때문에 줄인 양 (dB, 0 이면 줄이지 않음). */
	private final double[][] guardLoss;
	/** [소리][변형] → 녹음을 썼는가. */
	private final boolean[][] recorded;

	private SoundBank(float[][][] clips, double[][] guardLoss, boolean[][] recorded) {
		this.clips = clips;
		this.guardLoss = guardLoss;
		this.recorded = recorded;
	}

	/** 모든 효과음을 만든다. 녹음은 {@link Samples#load} 로 읽는다({@code -Dtetris.sfx=synth} 면 모두 합성). */
	static SoundBank generate() {
		return generate(Samples::load);
	}

	/**
	 * 녹음을 {@code loader}(이름 → 표본, 없으면 null)로 읽어 모든 효과음을 만든다. 시험은 녹음을 빼거나 바꿔 넣는다. loader 가
	 * null 을 돌려주면 말없이 합성음을 쓴다(까닭은 loader 가 알린다, {@link Samples#load}). 예외를 던지면 합성음을 쓰고 그
	 * 예외를 녹음 이름마다 한 번 표준 오류에 남긴다.
	 */
	static SoundBank generate(Function<String, float[]> loader) {
		return generate(loader, System.err::println);
	}

	/** {@link #generate(Function)} 와 같지만 loader 가 던진 예외의 알림을 {@code warn} 에 넘긴다 (시험이 알림을 받아 본다). */
	static SoundBank generate(Function<String, float[]> loader, Consumer<String> warn) {
		Recordings rec = new Recordings(loader, warn);
		Sfx[] all = Sfx.values();
		float[][][] c = new float[all.length][][];
		double[][] loss = new double[all.length][];
		boolean[][] used = new boolean[all.length][];
		for (Sfx e : all) {
			c[e.ordinal()] = new float[e.variants][];
			loss[e.ordinal()] = new double[e.variants];
			used[e.ordinal()] = new boolean[e.variants];
			for (int v = 0; v < e.variants; v++) {
				rec.used = false;
				float[] b = render(e, v, rec);
				loss[e.ordinal()][v] = normalize(b, target(e, v));
				c[e.ordinal()][v] = b;
				used[e.ordinal()][v] = rec.used;
			}
		}
		return new SoundBank(c, loss, used);
	}

	/**
	 * 소리마다, 변형마다 {@code maker}(소리, 변형 번호)가 만든 표본을 그대로 담는다(크기를 맞추지 않는다). 시험은 알아보기
	 * 쉬운 단순한 표본을 넣는다.
	 */
	static SoundBank build(BiFunction<Sfx, Integer, float[]> maker) {
		Sfx[] all = Sfx.values();
		float[][][] c = new float[all.length][][];
		double[][] loss = new double[all.length][];
		boolean[][] used = new boolean[all.length][];
		for (Sfx e : all) {
			c[e.ordinal()] = new float[e.variants][];
			loss[e.ordinal()] = new double[e.variants];
			used[e.ordinal()] = new boolean[e.variants];
			for (int v = 0; v < e.variants; v++) {
				c[e.ordinal()][v] = maker.apply(e, v);
			}
		}
		return new SoundBank(c, loss, used);
	}

	/** 소리 {@code e} 의 변형 {@code variant}. 범위를 벗어나면 가장 가까운 변형. 받은 배열은 고치지 않는다. */
	float[] clip(Sfx e, int variant) {
		float[][] v = clips[e.ordinal()];
		return v[Math.max(0, Math.min(v.length - 1, variant))];
	}

	int variants(Sfx e) {
		return clips[e.ordinal()].length;
	}

	/** 변형 {@code v} 를 가장 큰 표본의 상한 때문에 목표보다 줄인 양 (dB). 0 이면 목표 크기 그대로다. */
	double guardLossDb(Sfx e, int v) {
		double[] l = guardLoss[e.ordinal()];
		return l[Math.max(0, Math.min(l.length - 1, v))];
	}

	/** 변형 {@code v} 가 녹음을 썼는가 (아니면 모두 합성). */
	boolean recorded(Sfx e, int v) {
		boolean[] r = recorded[e.ordinal()];
		return r[Math.max(0, Math.min(r.length - 1, v))];
	}

	/** 모든 표본 수의 합. */
	long totalFrames() {
		long n = 0;
		for (float[][] vs : clips) {
			for (float[] c : vs) {
				n += c.length;
			}
		}
		return n;
	}

	/**
	 * 소리 하나의 생김새를 만든다: 설계(합성, 녹음, 둘을 겹침) → 아주 낮은 웅웅거림을 걸러 냄 → 끝의 들리지 않는 꼬리를 자름 →
	 * 앞뒤를 부드럽게. 크기는 {@link #normalize} 가 맞춘다.
	 */
	private static float[] render(Sfx e, int v, Recordings rec) {
		long seed = e.name().hashCode() * 0x9E3779B97F4A7C15L + v * 0xBF58476D1CE4E5B9L + 1;
		float[] b = design(e, v, seed, rec);
		Synth.highpass(b, 30);
		b = Synth.trim(b, -54, 8);
		double ms = b.length * 1000.0 / Synth.RATE;
		Synth.fade(b, 0.5, Math.min(150, ms * 0.2));
		return b;
	}

	/**
	 * 들리는 크기(F100)를 {@code targetDb} 로 맞춘다. 그 뒤 가장 큰 표본이 {@link #PEAK_LIMIT} 를 넘으면 상한까지 줄이고 줄인
	 * 양(dB)을 돌려준다. 조용한 버퍼는 그대로 둔다.
	 */
	static double normalize(float[] b, double targetDb) {
		double now = Loudness.f100(b);
		if (!(now > -120)) {
			return 0;
		}
		scale(b, (float) StrictMath.pow(10, (targetDb - now) / 20));
		float p = Synth.peak(b);
		if (p <= PEAK_LIMIT) {
			return 0;
		}
		float k = PEAK_LIMIT / p;
		scale(b, k);
		return -20 * StrictMath.log10(k);
	}

	private static void scale(float[] b, float k) {
		for (int i = 0; i < b.length; i++) {
			b[i] *= k;
		}
	}

	/** 소리 {@code e} 변형 {@code v} 의 목표 크기: 가장 시끄러운 0.1초의 K 가중 크기 (dB). */
	static double target(Sfx e, int v) {
		return REFERENCE_F100 + rel(e, v);
	}

	/**
	 * 테트리스 소리를 0 으로 한 상대 크기 (dB, 효과음 표). 자주 나는 소리는 작게(이동 -20), 큰 사건은 크게(퍼펙트 클리어 +2).
	 * 세기가 있는 소리는 세기에 따라 커진다: 줄 지우기 -5·-3.5·-2·0, T-spin 미니 -5·싱글 -3·더블 0·트리플 +1.5, 콤보는
	 * 한 단계에 0.2, 방해 줄 경고 -10 → -4, 상승 -11 → -6, 모래 연쇄 -3 → -0.2. 하드드롭은 거리 단계마다 -10·-8·-6.
	 */
	static double rel(Sfx e, int v) {
		int n = Math.max(0, v);
		switch (e) {
		case MENU_MOVE:
			return -14.5;
		case MENU_SELECT:
			return -6;
		case MENU_BACK:
			return -8;
		case DENY:
			return -9;
		case MOVE:
			return -20;
		case ROTATE:
		case LAND:
		case SAND_LAND:
			return -16;
		case HOLD:
			return -11;
		case LOCK:
			return -12;
		case HARD_DROP:
			return pick(new double[] { -10, -8, -6 }, n / Sfx.HARD_DROP.choices);
		case CLEAR:
			return pick(new double[] { -5, -3.5, -2, 0 }, n);
		case TSPIN:
			return pick(new double[] { -5, -3, 0, 1.5 }, n);
		case COMBO:
			return -4 + 0.2 * Math.min(n, 11);
		case B2B:
			return pick(new double[] { -5, -4.5, -4 }, n);
		case PERFECT_CLEAR:
			return 2;
		case GARBAGE:
			return -10 + 6.0 * Math.min(n, 7) / 7;
		case GARBAGE_RISE:
			return -11 + 5.0 * Math.min(n, 7) / 7;
		case GARBAGE_CANCEL:
		case PAUSE:
		case RESUME:
			return -8;
		case DANGER:
			return n == 0 ? -10 : -8.5;
		case LEVEL_UP:
			return -7;
		case COUNTDOWN:
			return -4;
		case GO:
			return -1;
		case KO:
		case NEW_RECORD:
			return 0.5;
		case WIN:
			return 0;
		case LOSE:
			return -2.5;
		case SAND_CLEAR:
			return -3;
		case SAND_CHAIN:
			return -3 + 0.35 * Math.min(n, 8);
		default:
			throw new IllegalArgumentException("크기를 정하지 않은 소리: " + e);
		}
	}

	private static double pick(double[] table, int i) {
		return table[Math.min(table.length - 1, i)];
	}

	// ------------------------------------------------------------------ 녹음

	/** 한 번 만들 때 쓰는 녹음 읽기 (같은 녹음을 여러 변형이 쓰면 한 번만 읽는다). 만드는 스레드 하나만 쓴다. */
	private static final class Recordings {
		private final Function<String, float[]> loader;
		/** loader 가 던진 예외를 알릴 곳. */
		private final Consumer<String> warn;
		private final Map<String, float[]> cache = new HashMap<>();
		/** 지금 만드는 소리가 녹음을 썼는가. */
		boolean used;

		Recordings(Function<String, float[]> loader, Consumer<String> warn) {
			this.loader = loader;
			this.warn = warn;
		}

		/** 녹음의 사본 (고쳐도 된다), 없으면 null. */
		float[] get(String name) {
			if (!cache.containsKey(name)) {
				float[] x;
				try {
					x = loader.apply(name);
				} catch (RuntimeException e) {
					Samples.warnOnce(name, String.valueOf(e), warn);
					x = null; // 합성음으로 대신한다
				}
				cache.put(name, x == null || x.length == 0 ? null : x);
			}
			float[] x = cache.get(name);
			if (x == null) {
				return null;
			}
			used = true;
			return x.clone();
		}

		/** 녹음이 있으면 그것을, 없으면 합성음을. */
		float[] or(String name, Supplier<float[]> synth) {
			float[] x = get(name);
			return x != null ? x : synth.get();
		}
	}

	/**
	 * {@code base} 에 {@code extra} 를 겹친 새 배열. extra 의 들리는 크기(F100)가 base 보다 {@code relDb} 만큼 되게 맞춘다
	 * (예: -6 이면 6dB 작게). 길이는 둘 중 긴 쪽.
	 */
	static float[] layer(float[] base, float[] extra, double relDb) {
		float[] out = Arrays.copyOf(base, Math.max(base.length, extra.length));
		double e = Loudness.f100(extra);
		if (!(e > -120)) {
			return out;
		}
		float g = (float) StrictMath.pow(10, (Loudness.f100(base) + relDb - e) / 20);
		for (int i = 0; i < extra.length; i++) {
			out[i] += extra[i] * g;
		}
		return out;
	}

	/** {@code ratio} 배 느리게 다시 읽은 사본(음이 낮아지고 길어진다). 선형 보간. */
	static float[] slower(float[] x, double ratio) {
		int n = (int) Math.ceil(x.length * ratio);
		float[] y = new float[n];
		for (int i = 0; i < n; i++) {
			double p = i / ratio;
			int k = (int) p;
			double f = p - k;
			double a = k < x.length ? x[k] : 0;
			double b = k + 1 < x.length ? x[k + 1] : 0;
			y[i] = (float) (a + (b - a) * f);
		}
		return y;
	}

	// ------------------------------------------------------------------ 소리마다의 설계

	private static float[] design(Sfx e, int v, long seed, Recordings rec) {
		switch (e) {
		case MENU_MOVE:
			return rec.or("ui_move", () -> menuMove(seed));
		case MENU_SELECT:
			return rec.or("ui_select", SoundBank::menuSelect);
		case MENU_BACK:
			return rec.or("ui_back", SoundBank::menuBack);
		case DENY:
			return rec.or("ui_deny", SoundBank::deny);
		case MOVE:
			return move(v, seed);
		case ROTATE:
			return rotate(v, seed);
		case HOLD:
			return hold(seed);
		case LAND:
			return rec.or("land", () -> land(seed));
		case HARD_DROP:
			return hardDrop(v / Sfx.HARD_DROP.choices, v % Sfx.HARD_DROP.choices, seed, rec);
		case LOCK:
			return rec.or("lock_" + (v + 1), () -> lock(v, seed));
		case CLEAR:
			return v >= 3 ? tetris(seed) : clear(v + 1, seed);
		case TSPIN:
			return v == 0 ? tspinMini(seed) : tspin(v, seed);
		case COMBO:
			return combo(v + 1);
		case B2B:
			return b2b(v, seed);
		case PERFECT_CLEAR:
			return perfect(seed);
		case GARBAGE:
			return garbage(v + 1, seed);
		case GARBAGE_RISE:
			return garbageRise(v + 1, seed);
		case GARBAGE_CANCEL:
			return garbageCancel(seed);
		case DANGER:
			return danger(v, seed);
		case LEVEL_UP:
			return levelUp();
		case COUNTDOWN:
			return countdown();
		case GO:
			return go();
		case PAUSE:
			return pause(false);
		case RESUME:
			return pause(true);
		case KO:
			return ko(seed, rec);
		case WIN:
			return win();
		case LOSE:
			return lose();
		case NEW_RECORD:
			return newRecord(seed);
		case SAND_LAND:
			return sandLand(v, seed, rec);
		case SAND_CLEAR:
			return sandClear(seed);
		case SAND_CHAIN:
			return sandChain(v + 2, seed);
		default:
			throw new IllegalArgumentException("만들지 않은 소리: " + e);
		}
	}

	// ------------------------------------------------------------------ 자주 쓰는 조각

	private static double m(double midi) {
		return Synth.midi(midi);
	}

	/** 반짝이는 배음이 귀를 찌르지 않게 두는 상한 (Hz). 이보다 높아질 배음은 낮은 배음으로 바꾸거나 뺀다. */
	static final double SPARKLE_MAX_HZ = 8000;

	/** {@code hz} 의 배음 중 {@code limitHz} 를 넘지 않는 가장 높은 차수 (1 ~ {@code max}). */
	static int harmonicBelow(double hz, int max, double limitHz) {
		return (int) Math.max(1, Math.min(max, Math.floor(limitHz / hz)));
	}

	/** 맑은 종소리: 기본음 + 정수가 아닌 배음(2.76 배, 5.4 배)을 짧게. 12kHz 를 넘는 배음은 뺀다(높은 종에서 귀가 따갑지 않게). */
	private static void bell(float[] b, double t, double hz, double tau, double gain) {
		note(t, tau * 6, hz).partials(1, 0, 0.06).perc(1.5, tau).gain(gain).into(b);
		note(t, tau * 2, hz * 2.76).perc(1, tau * 0.3).gain(gain * 0.22).into(b);
		if (hz * 5.4 < 12_000) {
			note(t, tau, hz * 5.4).perc(0.8, tau * 0.12).gain(gain * 0.07).into(b);
		}
	}

	/** 쿵: 높은 음에서 낮은 음으로 빠르게 미끄러지는 사인 + 배음 (낮은 소리가 잘 안 나는 스피커에서도 들리게). */
	private static void thump(float[] b, double t, double fromHz, double toHz, double tau, double gain) {
		note(t, tau * 5, fromHz).to(toHz).glide(tau * 1.5).partials(1, 0.45, 0.18).perc(0.8, tau).gain(gain).into(b);
	}

	/**
	 * 배음을 더 세게 넣은 쿵. 노트북 스피커는 150Hz 아래를 거의 내지 못하는데, 배음(2~5 배)이 있으면 귀가 낮은 음을 채워
	 * 듣는다. 놓치면 안 되는 경고(방해 줄, 위험 심장 소리)에 쓴다. 헤드폰에서는 조금 단단하게 들릴 뿐 크기는 비슷하다.
	 */
	private static void thumpBright(float[] b, double t, double fromHz, double toHz, double tau, double gain) {
		note(t, tau * 5, fromHz).to(toHz).glide(tau * 1.5).partials(1, 0.9, 0.6, 0.35, 0.18).perc(0.8, tau).gain(gain)
				.into(b);
	}

	/** 딸깍: 아주 짧은 잡음. 소리의 시작을 또렷하게 한다. */
	private static void click(float[] b, double t, double hz, double gain, long seed) {
		noise(t, 14, seed).band(hz, hz, 0.9).perc(0.3, 2.5).release(4).gain(gain).into(b);
	}

	// ------------------------------------------------------------------ 메뉴 (녹음이 없을 때의 합성음)

	private static float[] menuMove(long seed) {
		float[] b = buffer(70);
		note(0, 60, m(88)).to(m(86)).partials(1, 0.22, 0.08).perc(1.5, 14).into(b);
		click(b, 0, 3200, 0.25, seed);
		return b;
	}

	private static float[] menuSelect() {
		float[] b = buffer(480);
		note(0, 120, m(79)).wave(Wave.TRIANGLE).perc(2, 40).gain(0.8).into(b);
		note(60, 300, m(84)).wave(Wave.TRIANGLE).perc(2, 75).into(b);
		note(60, 200, m(96)).perc(2, 40).gain(0.12).into(b);
		Synth.reverb(b, 0.12, 0.7, 0.4);
		return b;
	}

	private static float[] menuBack() {
		float[] b = buffer(420);
		note(0, 110, m(84)).wave(Wave.TRIANGLE).perc(2, 35).gain(0.8).into(b);
		note(55, 260, m(79)).wave(Wave.TRIANGLE).perc(2, 65).gain(0.9).into(b);
		Synth.reverb(b, 0.08, 0.6, 0.5);
		return b;
	}

	private static float[] deny() {
		float[] b = buffer(240);
		note(0, 85, m(55)).to(m(54)).wave(Wave.SQUARE).lowpass(1100).perc(2, 35).gain(0.7).into(b);
		note(100, 110, m(54)).to(m(53)).wave(Wave.SQUARE).lowpass(900).perc(2, 40).gain(0.6).into(b);
		return b;
	}

	// ------------------------------------------------------------------ 블록 조작

	/** 좌우 이동: 아주 짧은 톡. 변형 셋은 높이가 조금씩 다르다. */
	private static float[] move(int v, long seed) {
		double p = new double[] { 1.0, 1.06, 0.95 }[v % 3];
		float[] b = buffer(45);
		note(0, 40, 1500 * p).to(1100 * p).partials(1, 0.3).perc(0.6, 6).into(b);
		note(0, 40, 420 * p).perc(0.8, 8).gain(0.35).into(b);
		click(b, 0, 4500 * p, 0.35, seed);
		return b;
	}

	/** 회전: 짧은 쉭 + 위로 휘는 음. */
	private static float[] rotate(int v, long seed) {
		double p = v == 0 ? 1.0 : 1.05;
		float[] b = buffer(80);
		noise(0, 60, seed).band(1800 * p, 4200 * p, 2).adsr(4, 0, 1, 34).gain(0.4).into(b);
		note(0, 70, 760 * p).to(1150 * p).wave(Wave.TRIANGLE).perc(2, 16).gain(0.8).into(b);
		return b;
	}

	/** 홀드: 바람 소리 + 내려가는 두 음(바꿔 넣는 느낌). */
	private static float[] hold(long seed) {
		float[] b = buffer(190);
		noise(0, 130, seed).band(3200, 1200, 1.5).adsr(15, 0, 1, 70).gain(0.35).into(b);
		note(0, 90, m(81)).partials(1, 0.2).perc(2, 22).gain(0.7).into(b);
		note(55, 130, m(76)).partials(1, 0.2).perc(2, 32).gain(0.8).into(b);
		return b;
	}

	/** 바닥에 닿음 (녹음이 없을 때): 짧은 쿵 + 딸깍. */
	private static float[] land(long seed) {
		float[] b = buffer(90);
		thump(b, 0, 180, 115, 16, 1.0);
		click(b, 0, 2400, 0.3, seed);
		return b;
	}

	/**
	 * 하드드롭: 나무를 내리치는 녹음(변형 둘을 번갈아) + 아래로 내리꽂는 합성 쉭. 거리 단계 2(10칸 이상)는 녹음을 반음
	 * 낮춘다. 녹음이 없으면 예전 합성음(쉭 + 묵직한 쿵).
	 */
	private static float[] hardDrop(int tier, int which, long seed, Recordings rec) {
		float[] hit = rec.get("hard_drop_" + (which + 1));
		if (hit == null) {
			return hardDropSynth(tier, which, seed);
		}
		if (tier >= 2) {
			hit = slower(hit, SEMITONE);
		}
		return layer(hit, whoosh(which, seed), WHOOSH_DB);
	}

	/** 하드드롭의 쉭 + 딸깍 (쿵은 빼고). */
	private static float[] whoosh(int which, long seed) {
		double p = which == 0 ? 1.0 : 0.94;
		float[] b = buffer(110);
		noise(0, 70, seed).band(5200 * p, 900 * p, 1.1).perc(0.5, 18).gain(0.45).into(b);
		click(b, 0, 3500, 0.55, seed + 1);
		return b;
	}

	/** 합성 하드드롭: 아래로 내리꽂는 쉭 + 묵직한 쿵. 단계 2 는 반음 낮다. */
	private static float[] hardDropSynth(int tier, int which, long seed) {
		double p = (which == 0 ? 1.0 : 0.94) / (tier >= 2 ? SEMITONE : 1);
		float[] b = buffer(240);
		noise(0, 70, seed).band(5200 * p, 900 * p, 1.1).perc(0.5, 18).gain(0.45).into(b);
		thump(b, 0, 170 * p, 55, 42, 1.0);
		click(b, 0, 3500, 0.55, seed + 1);
		return b;
	}

	/** 고정 (녹음이 없을 때): 짧은 쿵 + 딸깍. */
	private static float[] lock(int v, long seed) {
		double p = v == 0 ? 1.0 : 1.05;
		float[] b = buffer(110);
		thump(b, 0, 250 * p, 160 * p, 18, 0.9);
		click(b, 0, 2600 * p, 0.4, seed);
		return b;
	}

	// ------------------------------------------------------------------ 줄 지우기

	/** 1~3줄: 지운 줄 수만큼 도·미·솔로 오르는 맑은 음 + 줄이 쓸려 나가는 반짝임. */
	private static float[] clear(int lines, long seed) {
		double[] notes = { 84, 88, 91 };
		float[] b = buffer(900);
		for (int k = 0; k < lines; k++) {
			double t = k * 55;
			note(t, 520, m(notes[k])).partials(1, 0.3, 0.1, 0.04).perc(2, 90 + 20 * lines).gain(0.8).into(b);
			note(t, 120, m(notes[k]) * 2.76).perc(1, 18).gain(0.1).into(b);
		}
		noise(0, 170 + 45 * lines, seed).band(2500, 8500, 1.4).adsr(10, 0, 1, 110).gain(0.16 + 0.04 * lines).into(b);
		note(0, 200, m(48)).partials(1, 0.5).perc(3, 45).gain(0.3).into(b);
		Synth.reverb(b, 0.16, 0.72, 0.35);
		return b;
	}

	/** 테트리스: 빠르게 솟는 아르페지오 + 넓은 화음 + 낮은 울림 + 반짝이는 꼬리. 다른 줄 지우기와 확실히 다르다. */
	private static float[] tetris(long seed) {
		float[] b = buffer(1450);
		double[] run = { 72, 76, 79, 84, 88, 91 };
		for (int k = 0; k < run.length; k++) {
			double t = k * 32;
			note(t, 260, m(run[k])).wave(Wave.SAW).lowpass(3600).perc(2, 70).gain(0.32).into(b);
			note(t, 300, m(run[k])).partials(1, 0.25).perc(2, 90).gain(0.4).into(b);
		}
		for (double n : new double[] { 72, 76, 79, 84 }) {
			for (double d : new double[] { -7, 7 }) {
				note(190, 800, m(n)).detune(d).wave(Wave.SAW).lowpass(2600, 1300).adsr(14, 320, 0.5, 330).gain(0.12)
						.into(b);
			}
		}
		thump(b, 0, 120, 52, 120, 0.7);
		noise(150, 650, seed).band(7000, 7000, 0.8).adsr(40, 0, 1, 450).gain(0.1).into(b);
		Synth.reverb(b, 0.22, 0.78, 0.3);
		return b;
	}

	/** T-spin: 빙글 도는 떨림 + 레·파#·라 (다장조 위의 밝고 신비한 색). 줄 수만큼 음이 쌓인다. */
	private static float[] tspin(int lines, long seed) {
		float[] b = buffer(1100);
		note(0, 200, m(79)).to(m(86)).vibrato(22, 70).perc(5, 70).gain(0.55).into(b);
		noise(0, 170, seed).band(1500, 5200, 3).adsr(10, 0, 1, 100).gain(0.22).into(b);
		double[] hits = { 86, 90, 93 };
		for (int k = 0; k < lines; k++) {
			double t = 120 + k * 50;
			note(t, 520, m(hits[k])).wave(Wave.SQUARE).lowpass(3200).perc(2, 110).gain(0.26).into(b);
			note(t, 600, m(hits[k])).partials(1, 0.3).perc(2, 140).gain(0.5).into(b);
		}
		note(120, 300, m(50)).partials(1, 0.4).perc(3, 60).gain(0.3).into(b);
		Synth.reverb(b, 0.18, 0.74, 0.35);
		return b;
	}

	/** T-spin 미니: 짧은 떨림 + 레 한 번. 싱글보다 가볍고 짧다(낮은 울림 없음). */
	private static float[] tspinMini(long seed) {
		float[] b = buffer(760);
		note(0, 150, m(79)).to(m(86)).vibrato(22, 60).perc(4, 50).gain(0.5).into(b);
		noise(0, 120, seed).band(1800, 5200, 3).adsr(8, 0, 1, 70).gain(0.18).into(b);
		note(90, 380, m(86)).partials(1, 0.3).perc(2, 85).gain(0.5).into(b);
		note(90, 280, m(86)).wave(Wave.SQUARE).lowpass(2600).perc(2, 55).gain(0.16).into(b);
		Synth.reverb(b, 0.14, 0.7, 0.4);
		return b;
	}

	/** 콤보 음 높이의 MIDI 번호: 1 콤보 = 솔(G5) 에서 5음 음계로 한 단계씩, 10 콤보 = 미(E7). 그 위는 미에 머문다. */
	private static final int[] COMBO_STEPS = { 79, 81, 84, 86, 88, 91, 93, 96, 98, 100, 100, 100 };

	/** 콤보 음 높이 (Hz). 10 콤보까지 오르고, 11·12 콤보는 같은 높이(E7)에 아래 음을 쌓는다(라(A7)까지 오르면 날카롭다). */
	static double comboHz(int combo) {
		return Synth.midi(COMBO_STEPS[Math.max(0, Math.min(COMBO_STEPS.length - 1, combo - 1))]);
	}

	/**
	 * 콤보: 짧고 맑은 음 + 메아리. 5 콤보부터는 아래 5도로 받쳐 더 크게 들린다. 11 콤보는 한 옥타브 아래 음을, 12 콤보는
	 * 거기에 5도 음(시)을 더 쌓아 높이 대신 두께로 커진다.
	 */
	private static float[] combo(int n) {
		double f = comboHz(n);
		float[] b = buffer(560);
		note(0, 240, f).partials(1, 0.18, 0.06).perc(1.5, 65).into(b);
		note(0, 60, f * harmonicBelow(f, 4, SPARKLE_MAX_HZ)).perc(0.6, 9).gain(0.2).into(b); // 시작의 반짝임
		if (n >= 5) {
			note(20, 220, f * 2 / 3).partials(1, 0.2).perc(2, 70).gain(0.35).into(b);
		}
		if (n >= 11) {
			note(0, 300, f / 2).partials(1, 0.25).perc(2, 85).gain(0.5).into(b); // 한 옥타브 아래 (E6)
		}
		if (n >= 12) {
			note(10, 280, f * 3 / 4).partials(1, 0.2).perc(2, 80).gain(0.4).into(b); // 그 위 5도 (B6)
		}
		Synth.echo(b, 85, 0.3);
		return b;
	}

	/**
	 * B2B: 솔·레·솔의 힘 있는 화음 한 번 (줄 지우기 소리 뒤에 겹쳐도 어울린다). 단계 1(4~7 연속)은 한 옥타브 위 화음을,
	 * 단계 2(8 연속 이상)는 거기에 5~8kHz 의 짧은 반짝임(0.3초)을 더 겹친다.
	 */
	private static float[] b2b(int tier, long seed) {
		float[] b = buffer(800);
		for (double n : new double[] { 79, 86, 91 }) {
			for (double d : new double[] { -6, 6 }) {
				note(0, 340, m(n)).detune(d).wave(Wave.SAW).lowpass(3400, 1500).adsr(4, 130, 0.35, 120).gain(0.22)
						.into(b);
			}
		}
		note(0, 110, m(79)).to(m(91)).perc(2, 35).gain(0.3).into(b);
		click(b, 0, 5000, 0.3, seed);
		Synth.reverb(b, 0.16, 0.7, 0.4);
		if (tier >= 1) {
			float[] up = buffer(800);
			for (double n : new double[] { 91, 98, 103 }) {
				note(0, 340, m(n)).wave(Wave.TRIANGLE).adsr(4, 130, 0.35, 120).gain(0.3).into(up);
			}
			Synth.reverb(up, 0.16, 0.7, 0.4);
			b = layer(b, up, B2B_OCTAVE_DB);
		}
		if (tier >= 2) {
			float[] shimmer = buffer(800);
			Synth.grains(shimmer, 0, 300, 70, 1, 4, 5000, 8000, 1.0, seed + 9);
			b = layer(b, shimmer, B2B_SHIMMER_DB);
		}
		return b;
	}

	/** 퍼펙트 클리어: 천천히 피어나는 넓은 화음(Cmaj9) + 반짝이는 종소리 + 위로 쓸리는 바람. */
	private static float[] perfect(long seed) {
		float[] b = buffer(2300);
		for (double n : new double[] { 72, 76, 79, 83, 86 }) {
			note(0, 1700, m(n)).detune(-5).adsr(60, 650, 0.45, 900).gain(0.16).into(b);
			note(0, 1700, m(n)).detune(5).wave(Wave.TRIANGLE).adsr(60, 650, 0.45, 900).gain(0.13).into(b);
		}
		double[] sparkle = { 96, 91, 100, 96, 103, 100, 98 };
		for (int k = 0; k < sparkle.length; k++) {
			bell(b, k * 70, m(sparkle[k]), 90, 0.22);
		}
		noise(0, 650, seed).band(800, 8000, 1.5).adsr(320, 0, 1, 260).gain(0.12).into(b);
		note(0, 1400, m(48)).adsr(40, 420, 0.4, 700).gain(0.32).into(b);
		Synth.reverb(b, 0.3, 0.82, 0.3);
		return b;
	}

	// ------------------------------------------------------------------ 대전

	/**
	 * 받을 방해 줄 경고: 낮은 쿵(배음을 세게 넣어 노트북에서도 들린다). 1~2줄은 한 번, 3~5줄은 두 번, 6줄 이상은 세 번.
	 * 쿵마다 짧은 경보음(사각파 라 440Hz, 70ms, 1.6kHz 아래만)을 9dB 작게 겹쳐 작은 스피커에서도 놓치지 않는다. 4줄부터는
	 * 불길한 낮은 울림이 깔린다.
	 */
	private static float[] garbage(int lines, long seed) {
		double s = (Math.min(lines, 8) - 1) / 7.0;
		int hits = lines <= 2 ? 1 : lines <= 5 ? 2 : 3;
		double ms = (hits - 1) * 115 + 300 + 5 * (45 + 25 * s);
		float[] b = buffer(ms);
		float[] alarm = buffer(ms);
		for (int k = 0; k < hits; k++) {
			double t = k * 115;
			double g = 1.0 - 0.15 * k;
			thumpBright(b, t, 140 - 8 * k, 62, 45 + 25 * s, g);
			click(b, t, 1800, 0.3, seed + k);
			note(t, 70, 440).wave(Wave.SQUARE).lowpass(1600).adsr(2, 0, 1, 18).gain(g).into(alarm);
		}
		b = layer(b, alarm, ALARM_DB);
		if (lines >= 4) {
			double len = 320 + 140 * s;
			note(0, len, m(45)).wave(Wave.SQUARE).lowpass(650).adsr(25, 0, 1, 160).gain(0.16 + 0.1 * s).into(b);
			note(0, len, m(51)).wave(Wave.SQUARE).lowpass(650).adsr(25, 0, 1, 160).gain(0.1 + 0.06 * s).into(b);
		}
		return b;
	}

	/** 방해 줄이 올라옴: 낮게 우르릉 + 줄마다 밀려 올라가는 톡. */
	private static float[] garbageRise(int lines, long seed) {
		double len = 150 + 32 * lines;
		float[] b = buffer(len + 60);
		noise(0, len, seed).lowpass(260, 900).adsr(15, 0, 1, 90).gain(0.55).into(b);
		note(0, len, 70).to(110).wave(Wave.SQUARE).lowpass(380).adsr(10, 0, 1, 90).gain(0.22).into(b);
		for (int k = 0; k < lines; k++) {
			click(b, 10 + k * (len - 70) / lines, 1500 + 120 * k, 0.28, seed + 7 + k);
		}
		return b;
	}

	/**
	 * 상쇄: 받을 방해 줄을 막아 냄. 미(E6)에서 파(F6)로 20ms 에 살짝 오르는 맑은 쨍 + 종 같은 배음(2.76 배). 약 0.14초에
	 * 거의 사라진다.
	 */
	private static float[] garbageCancel(long seed) {
		float[] b = buffer(230);
		note(0, 210, m(88)).to(m(89)).glide(20).partials(1, 0.25, 0.08).perc(1, 38).into(b);
		note(0, 160, m(88) * 2.76).to(m(89) * 2.76).glide(20).perc(0.8, 18).gain(0.3).into(b);
		click(b, 0, 5200, 0.2, seed);
		return b;
	}

	/** 심장 소리 (쿵-쿵, 배음을 세게 넣어 노트북에서도 들린다). 변형 1 은 더 위험할 때: 간격이 좁고 조금 높다. */
	private static float[] danger(int v, long seed) {
		double gap = v == 0 ? 175 : 145;
		double p = v == 0 ? 1.0 : 1.12;
		float[] b = buffer(gap + 380);
		thumpBright(b, 0, 100 * p, 62 * p, 42, 1.0);
		thumpBright(b, gap, 90 * p, 56 * p, 38, 0.72);
		noise(0, 24, seed).lowpass(900).perc(1, 6).gain(0.18).into(b);
		noise(gap, 24, seed + 1).lowpass(900).perc(1, 6).gain(0.13).into(b);
		return b;
	}

	/** 속도 단계가 오름: 솔·도·미 종소리. */
	private static float[] levelUp() {
		float[] b = buffer(900);
		double[] n = { 79, 84, 88 };
		for (int k = 0; k < n.length; k++) {
			bell(b, k * 70, m(n[k]), 150, 0.6);
		}
		Synth.reverb(b, 0.2, 0.72, 0.35);
		return b;
	}

	// ------------------------------------------------------------------ 판 흐름

	private static float[] countdown() {
		float[] b = buffer(280);
		note(0, 200, m(81)).partials(1, 0.18, 0.06).adsr(4, 50, 0.55, 60).into(b);
		return b;
	}

	/** GO: 카운트다운 삑보다 한 옥타브 높은 밝은 화음. */
	private static float[] go() {
		float[] b = buffer(900);
		note(0, 460, m(93)).partials(1, 0.15).adsr(4, 90, 0.55, 220).gain(0.75).into(b);
		note(0, 460, m(88)).wave(Wave.SQUARE).lowpass(4200).adsr(4, 90, 0.5, 220).gain(0.18).into(b);
		note(0, 460, m(81)).partials(1, 0.2).adsr(4, 90, 0.5, 220).gain(0.35).into(b);
		Synth.reverb(b, 0.15, 0.7, 0.4);
		return b;
	}

	/** 일시정지는 내려가는 두 음, 계속은 오르는 두 음. */
	private static float[] pause(boolean resume) {
		float[] b = buffer(420);
		double first = resume ? m(84) : m(88);
		double second = resume ? m(88) : m(84);
		note(0, 140, first).wave(Wave.TRIANGLE).perc(3, 45).gain(0.8).into(b);
		note(80, 240, second).wave(Wave.TRIANGLE).perc(3, 70).into(b);
		if (resume) {
			note(80, 160, m(91)).perc(2, 40).gain(0.12).into(b);
		}
		Synth.reverb(b, 0.1, 0.65, 0.45);
		return b;
	}

	/** K.O.: 힘이 빠지며 내려가는 소리 + 큰 쿵 + 부서지는 잡음, 그 위에 부서지는 녹음을 2dB 작게 겹친다. */
	private static float[] ko(long seed, Recordings rec) {
		float[] b = buffer(1800);
		note(0, 700, 560).to(70).wave(Wave.SAW).lowpass(2400, 450).adsr(5, 0, 1, 260).gain(0.32).into(b);
		note(0, 700, 560).to(70).detune(14).wave(Wave.SQUARE).lowpass(2000, 400).adsr(5, 0, 1, 260).gain(0.2).into(b);
		thump(b, 0, 130, 42, 230, 0.9);
		noise(0, 1200, seed).lowpass(6500, 1300).perc(2, 260).gain(0.38).into(b);
		Synth.reverb(b, 0.24, 0.8, 0.3);
		float[] crunch = rec.get("ko_crunch");
		return crunch == null ? b : layer(b, crunch, CRUNCH_DB);
	}

	/** 승리: 솔·도·미·솔로 오르는 팡파르 뒤 다장조 화음과 종소리. */
	private static float[] win() {
		float[] b = buffer(2300);
		double[] run = { 67, 72, 76, 79 };
		for (int k = 0; k < run.length; k++) {
			double t = k * 110;
			note(t, 170, m(run[k])).wave(Wave.SAW).lowpass(2800).adsr(5, 60, 0.6, 60).gain(0.5).into(b);
			note(t, 170, m(run[k])).wave(Wave.TRIANGLE).adsr(5, 60, 0.6, 60).gain(0.65).into(b);
		}
		for (double n : new double[] { 72, 76, 79, 84 }) {
			note(440, 1050, m(n)).wave(Wave.SAW).lowpass(3000, 1700).adsr(15, 420, 0.6, 470).vibrato(5.5, 12)
					.gain(0.15).into(b);
			note(440, 1050, m(n)).wave(Wave.TRIANGLE).adsr(15, 420, 0.6, 470).gain(0.14).into(b);
		}
		note(440, 1050, m(60)).wave(Wave.TRIANGLE).adsr(15, 420, 0.6, 470).gain(0.32).into(b);
		bell(b, 440, m(96), 130, 0.2);
		bell(b, 500, m(100), 130, 0.18);
		bell(b, 560, m(103), 130, 0.16);
		Synth.reverb(b, 0.22, 0.78, 0.35);
		return b;
	}

	/** 패배: 솔·미♭·도로 천천히 내려가 다단조 화음이 살짝 처진다. 무겁지 않게 부드러운 삼각파로. */
	private static float[] lose() {
		float[] b = buffer(2300);
		double[] run = { 67, 63, 60 };
		for (int k = 0; k < run.length; k++) {
			double t = k * 260;
			note(t, 320, m(run[k])).wave(Wave.TRIANGLE).adsr(10, 120, 0.5, 120).gain(0.6).into(b);
			note(t, 320, m(run[k] + 12)).adsr(10, 120, 0.5, 120).gain(0.12).into(b);
		}
		for (double n : new double[] { 60, 63, 67 }) {
			note(800, 1000, m(n)).to(m(n - 0.35)).wave(Wave.TRIANGLE).adsr(30, 400, 0.5, 420).vibrato(4, 14)
					.gain(0.24).into(b);
		}
		note(800, 1000, m(48)).adsr(30, 400, 0.5, 420).gain(0.3).into(b);
		Synth.reverb(b, 0.2, 0.75, 0.4);
		return b;
	}

	/** 최고 기록: 빠르게 오르는 종소리 아르페지오 + 반짝이는 꼬리. */
	private static float[] newRecord(long seed) {
		float[] b = buffer(1500);
		double[] run = { 84, 88, 91, 96, 100, 103 };
		for (int k = 0; k < run.length; k++) {
			bell(b, k * 45, m(run[k]), 130, 0.4);
		}
		for (double n : new double[] { 84, 88, 91 }) {
			note(270, 820, m(n)).wave(Wave.TRIANGLE).adsr(10, 300, 0.4, 400).gain(0.18).into(b);
		}
		noise(0, 750, seed).highpass(7000).adsr(40, 0, 1, 520).gain(0.08).into(b);
		Synth.reverb(b, 0.25, 0.8, 0.3);
		return b;
	}

	// ------------------------------------------------------------------ 모래 모드

	/**
	 * 모래가 내려앉음: 눈 밟는 듯 사각거리는 녹음(셋을 섞어 쓴다) + 합성한 알갱이 반짝임을 6dB 작게. 녹음이 없으면 예전
	 * 합성음(알갱이 + 부드러운 쿵).
	 */
	private static float[] sandLand(int v, long seed, Recordings rec) {
		float[] crunch = rec.get("sand_land_" + (v + 1));
		if (crunch != null) {
			return layer(crunch, sandGrains(seed), GRAINS_DB);
		}
		float[] b = sandGrains(seed);
		thump(b, 0, 140 * (1 + 0.04 * v), 90, 22, 1.0);
		return b;
	}

	/** 부서지는 알갱이: 짧은 잡음 알갱이를 앞쪽에 많이. */
	private static float[] sandGrains(long seed) {
		float[] b = buffer(280);
		Synth.grains(b, 0, 190, 170, 0.8, 3, 1300, 4500, 1.2, seed);
		return b;
	}

	/** 모래가 사라짐: 위로 쓸려 올라가는 쉬익 + 반짝이는 알갱이 + 미·시 종소리. */
	private static float[] sandClear(long seed) {
		float[] b = buffer(1100);
		noise(0, 440, seed).band(600, 6200, 1.2).adsr(60, 0, 1, 260).gain(0.45).into(b);
		Synth.grains(b, 40, 420, 120, 1, 4, 4000, 9000, 0.35, seed + 1);
		bell(b, 120, m(88), 150, 0.35);
		bell(b, 120, m(95), 150, 0.3);
		Synth.reverb(b, 0.2, 0.74, 0.35);
		return b;
	}

	/** 모래 연쇄 음 높이: 연쇄 2 = 도(C6) 에서 5음 음계로 한 단계씩, 연쇄 10 = 솔(G7). */
	static double chainHz(int chain) {
		int[] steps = { 84, 86, 88, 91, 93, 96, 98, 100, 103 };
		return Synth.midi(steps[Math.max(0, Math.min(steps.length - 1, chain - 2))]);
	}

	/** 모래 연쇄: 유리 같은 종소리 + 반짝이는 알갱이 + 메아리. 연쇄 4 부터는 위 5도가 더해진다. */
	private static float[] sandChain(int chain, long seed) {
		double f = chainHz(chain);
		float[] b = buffer(800);
		note(0, 700, f).partials(1, 0, 0.1).perc(1.5, 140).gain(0.8).into(b);
		note(0, 220, f * 2).perc(1, 55).gain(0.2).into(b);
		note(0, 120, f * harmonicBelow(f, 3, SPARKLE_MAX_HZ)).perc(0.8, 25).gain(0.1).into(b);
		if (chain >= 4) {
			note(40, 500, f * 1.5).perc(1.5, 110).gain(0.35).into(b);
		}
		Synth.grains(b, 0, 220, 40, 1, 3.5, 4500, 9500, 0.25, seed);
		Synth.echo(b, 90, 0.25);
		return b;
	}
}
