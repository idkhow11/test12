package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

/**
 * 효과의 기초 (runs/quality/SPEC.md 0장 규칙 3, V3(f)): 상태 없는 난수, 색마다 16단계 알파 표, 알파 합성 표, 미리 그려 두는 조각 그림.
 * <p>
 * 효과는 게임 상태를 바꾸지 않고, 대전 난수도 쓰지 않는다. 같은 판이면 같은 그림이 나오도록 효과의 "무작위"는
 * {@link #rand}(시드, 사건 시각, 칸 좌표, 번호)에서 계산한다. 프레임마다 시뮬레이션하지 않으므로 몇 번째 프레임에 그려도 같다.
 * <p>
 * 그릴 때 객체를 만들지 않는다(초당 60번 그리므로 쓰레기가 G1 멈춤을 부른다): 색은 {@link #alpha} 의 표에서, 합성은
 * {@link #composite} 의 표에서 꺼낸다. 표는 처음 쓸 때 한 번만 만든다. 여러 스레드(그리기 스레드, 시험)에서 불러도 된다.
 */
final class Fx {

	/** 알파 표의 단계 수: 0, 1/15, …, 1. */
	static final int ALPHA_STEPS = 16;

	private static final long GOLDEN = 0x9E3779B97F4A7C15L;
	private static final double UNIT = 0x1.0p-53;

	private static final AlphaComposite[] COMPOSITES = new AlphaComposite[256];
	private static final IntTable<Color[]> ALPHAS = new IntTable<>(256);
	private static final IntTable<Color> LIGHTER = new IntTable<>(256);
	private static final IntTable<BufferedImage> DOTS = new IntTable<>(128);

	static {
		for (int i = 0; i < 255; i++) {
			COMPOSITES[i] = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, i / 255f);
		}
		COMPOSITES[255] = AlphaComposite.SrcOver;
	}

	private Fx() {
	}

	// ------------------------------------------------------------------ 상태 없는 난수

	/**
	 * 효과용 난수 [0, 1): 다섯 값을 SplitMix64 마무리 함수로 섞는다. 같은 입력이면 언제나 같은 값이다. 예: 줄 지우기의 조각 k 의 속도는
	 * {@code rand(seed, clearAt, row, col, k)}. {@code seed} 는 판·보드마다 다르게(예: 판 시드 ^ 보드 번호) 준다.
	 */
	static double rand(long seed, long eventTime, int a, int b, int k) {
		return (hash(seed, eventTime, a, b, k) >>> 11) * UNIT;
	}

	/** {@link #rand} 를 lo~hi 로 늘인 값. */
	static double rand(long seed, long eventTime, int a, int b, int k, double lo, double hi) {
		return lo + (hi - lo) * rand(seed, eventTime, a, b, k);
	}

	/** {@link #rand} 의 64비트 해시. */
	static long hash(long seed, long eventTime, int a, int b, int k) {
		long h = mix(seed + GOLDEN);
		h = mix(h + eventTime + GOLDEN);
		h = mix(h + (((long) a << 32) ^ (b & 0xFFFFFFFFL)) + GOLDEN);
		return mix(h + k + GOLDEN);
	}

	/** SplitMix64 의 마무리 함수 (Steele, Lea, Flood 2014). */
	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	/**
	 * 1차원 값 잡음 [−1, 1]: 정수 x 마다 {@link #rand} 로 정한 값을 smoothstep 으로 잇는다. 이어져 있으므로 흔들림에 쓴다(SPEC V7(c):
	 * {@code noise(seed, t초 × 11)} 이면 11 Hz). 축과 보드마다 시드를 다르게 준다.
	 */
	static double noise(long seed, double x) {
		double fl = Math.floor(x);
		long i = (long) fl;
		double f = x - fl;
		double s = f * f * (3 - 2 * f);
		double a = rand(seed, i, 0, 0, 0) * 2 - 1;
		double b = rand(seed, i + 1, 0, 0, 0) * 2 - 1;
		return a + (b - a) * s;
	}

	// ------------------------------------------------------------------ 색과 합성 (객체를 만들지 않는다)

	/**
	 * 색 c 의 16단계 알파 표: [i] 의 알파는 i × 17 (0, 17, …, 255). c 의 원래 알파는 무시한다. 같은 RGB 면 같은 배열을 준다(고치지 말 것).
	 */
	static Color[] alphas(Color c) {
		int rgb = c.getRGB() & 0xFFFFFF;
		synchronized (ALPHAS) {
			Color[] t = ALPHAS.get(rgb);
			if (t == null) {
				t = new Color[ALPHA_STEPS];
				for (int i = 0; i < ALPHA_STEPS; i++) {
					t[i] = new Color(rgb | (i * 255 / (ALPHA_STEPS - 1)) << 24, true);
				}
				ALPHAS.put(rgb, t);
			}
			return t;
		}
	}

	/** 색 c 를 알파 a (0~1, 16단계로 반올림) 로. 프레임마다 불러도 새 객체를 만들지 않는다. */
	static Color alpha(Color c, double a) {
		return alphas(c)[(int) Math.round(Motion.clamp01(a) * (ALPHA_STEPS - 1))];
	}

	/** 흰색 쪽으로 t (0~1, 1/20 단계) 만큼 밝힌 색 (예: 줄 지우기 조각은 칸 색을 20% 밝게). 같은 값이면 같은 객체. */
	static Color lighter(Color c, double t) {
		int step = (int) Math.round(Motion.clamp01(t) * 20);
		int rgb = c.getRGB() & 0xFFFFFF;
		int key = rgb | step << 24;
		synchronized (LIGHTER) {
			Color out = LIGHTER.get(key);
			if (out == null) {
				out = Theme.mix(new Color(rgb), Color.WHITE, step / 20.0);
				LIGHTER.put(key, out);
			}
			return out;
		}
	}

	/** 알파 a (0~1, 1/255 단계) 의 SRC_OVER 합성. 만들어 둔 것을 준다. */
	static AlphaComposite composite(double a) {
		return COMPOSITES[(int) Math.round(Motion.clamp01(a) * 255)];
	}

	/**
	 * 지금 합성에 알파 a 를 더 곱한 합성. 지금 합성이 SRC_OVER 알파 합성(기본값)이 아니면 {@code null} (곱할 수 없음). 한 묶음의
	 * 조각 그림을 그리기 전에 한 번 바꾸고, 다 그린 뒤 원래 합성으로 돌려놓는다.
	 */
	static AlphaComposite times(Composite current, double a) {
		if (current instanceof AlphaComposite ac && ac.getRule() == AlphaComposite.SRC_OVER) {
			return composite(ac.getAlpha() * a);
		}
		return null;
	}

	// ------------------------------------------------------------------ 조각 그림 (스프라이트)

	/** 반투명 조각 그림을 만든다: 그리는 곳과 맞는 형식({@code createCompatibleImage(…, TRANSLUCENT)}), 안 되면 ARGB. */
	static BufferedImage sprite(Graphics2D g, int w, int h) {
		GraphicsConfiguration gc = g == null ? null : g.getDeviceConfiguration();
		if (gc != null) {
			try {
				BufferedImage img = gc.createCompatibleImage(w, h, Transparency.TRANSLUCENT);
				if (img != null) {
					return img;
				}
			} catch (RuntimeException e) {
				// 장치가 반투명 그림을 못 만들면 아래의 보통 그림
			}
		}
		return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
	}

	/** {@link #dot} 의 가장 큰 지름(화소). */
	static final int MAX_DOT = 255;

	/**
	 * 지름 d 화소의 부드러운 점(가운데 불투명 → 가장자리 투명, 색 c). 입자나 카운트다운 숫자 뒤의 둥근 빛에 쓴다. 색·지름마다 한 번만
	 * 만든다. d 는 2~{@value #MAX_DOT} 로 자른다(그보다 크면 늘여 그리거나 RadialGradientPaint 로 직접).
	 */
	static BufferedImage dot(Graphics2D g, Color c, int d) {
		int size = Math.max(2, Math.min(MAX_DOT, d));
		int key = (c.getRGB() & 0xFFFFFF) | size << 24;
		BufferedImage img;
		synchronized (DOTS) {
			img = DOTS.get(key);
		}
		if (img != null) {
			return img;
		}
		img = sprite(g, size, size);
		Graphics2D sg = img.createGraphics();
		sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		float r = size / 2f;
		Color solid = new Color(c.getRGB() & 0xFFFFFF);
		sg.setPaint(new RadialGradientPaint(r, r, r, new float[] { 0f, 1f }, new Color[] { solid, alphas(solid)[0] }));
		sg.fillOval(0, 0, size, size);
		sg.dispose();
		synchronized (DOTS) {
			BufferedImage other = DOTS.get(key);
			if (other != null) {
				return other;
			}
			DOTS.put(key, img);
		}
		return img;
	}

	/**
	 * 조각 그림을 알파 a 로 (x, y) 에 그리고 합성을 돌려놓는다. 많이 그릴 때는 {@link #times} 로 합성을 한 번만 바꾸고
	 * {@code g.drawImage} 를 여러 번 부르는 편이 빠르다. 지금 합성이 SRC_OVER 가 아니면 a 없이 그린다.
	 */
	static void draw(Graphics2D g, Image img, int x, int y, double a) {
		if (!(a > 0)) {
			return;
		}
		Composite old = g.getComposite();
		AlphaComposite c = times(old, a);
		if (c != null && c != old) {
			g.setComposite(c);
			g.drawImage(img, x, y, null);
			g.setComposite(old);
		} else {
			g.drawImage(img, x, y, null);
		}
	}

	// ------------------------------------------------------------------ 상자 없는 int 표

	/** int 키로 찾는 작은 표(열린 주소법). 찾을 때 객체를 만들지 않는다. 가득 차면 비우고 다시 채운다(캐시라서 괜찮다). 잠금은 부르는 쪽. */
	static final class IntTable<V> {
		private final int[] keys;
		private final Object[] vals;
		private int size;

		IntTable(int capacity) {
			int n = Integer.highestOneBit(Math.max(8, capacity - 1)) << 1;
			keys = new int[n];
			vals = new Object[n];
		}

		@SuppressWarnings("unchecked")
		V get(int key) {
			int mask = keys.length - 1;
			for (int i = slot(key) & mask;; i = (i + 1) & mask) {
				Object v = vals[i];
				if (v == null) {
					return null;
				}
				if (keys[i] == key) {
					return (V) v;
				}
			}
		}

		void put(int key, V value) {
			if ((size + 1) * 2 > keys.length) {
				clear();
			}
			int mask = keys.length - 1;
			for (int i = slot(key) & mask;; i = (i + 1) & mask) {
				if (vals[i] == null) {
					keys[i] = key;
					vals[i] = value;
					size++;
					return;
				}
				if (keys[i] == key) {
					vals[i] = value;
					return;
				}
			}
		}

		int size() {
			return size;
		}

		void clear() {
			java.util.Arrays.fill(vals, null);
			size = 0;
		}

		private static int slot(int key) {
			int h = key * 0x9E3779B9;
			return h ^ (h >>> 16);
		}
	}
}
