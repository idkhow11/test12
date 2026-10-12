package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.Arrays;
import java.util.Locale;

import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 모래 판 하나(블록을 놓고 흘리고 지운 뒤 멈춘 판)의 특징과 점수. 점수는 특징의 가중합이고 클수록 좋다.
 * <p>
 * 고전 AI 의 기준 평가(높이, 구멍, 울퉁불퉁함, 지운 줄)를 모래에 맞게 바꿨다. 멈춘 모래에는 구멍이 생기지 않으므로(열마다 바닥부터
 * 꽉 찬다) 구멍 대신 <b>색이 조각난 정도</b>(같은 색 덩어리 수, 겉면의 색 바뀜)를 보고, 지운 줄 대신 <b>지운 알갱이</b>와
 * <b>같은 색이 좌우 벽을 향해 이어진 정도</b>를 본다. 여기에 블록을 놓는 데 드는 것(조작 수, 아래 모래가 무너지기를 기다릴 칸
 * 수, 스폰 자리에 가까움)을 더한다. 높이는 알갱이 단위다(칸 하나 = 8, 보이는 판 = 160).
 */
public final class SandEval {

	/** 이 블록으로(연쇄 포함) 지운 알갱이 수. */
	public static final int CLEARED = 0;
	/** 가장 높은 열의 높이. */
	public static final int MAX_HEIGHT = 1;
	/** 열 높이의 평균. */
	public static final int MEAN_HEIGHT = 2;
	/**
	 * 칸 열(알갱이 8 열)마다의 평균 높이가 이웃 칸 열과 다른 정도의 합. 멈춘 모래는 알갱이 단위로는 늘 약 63° 이하(이웃한 열의
	 * 차 2 이하)라 칸 단위로 본다.
	 */
	public static final int BUMPINESS = 3;
	/** 위험: 가장 높은 열이 {@link #DANGER_HEIGHT} 를 넘은 만큼의 제곱 ÷ 64. */
	public static final int DANGER = 4;
	/** 같은 색 덩어리(8방향) 수. 적을수록 색이 크게 모여 있다. */
	public static final int FRAGMENTS = 5;
	/**
	 * 같은 색이 좌우 벽을 향해 이어진 정도: 색마다 {@code p = max(가장 넓은 덩어리의 폭, 왼쪽 벽에 닿은 덩어리가 오른쪽으로 닿은 폭
	 * + 오른쪽 벽에 닿은 덩어리가 왼쪽으로 닿은 폭)} (79 까지) 을 구해 {@code p² ÷ 80} 을 더한다. 제곱이라 한 색을 길게 잇는 쪽을
	 * 좋아한다.
	 */
	public static final int PROGRESS = 6;
	/** 겉면(열마다 맨 위 알갱이)의 색이 이웃 열과 바뀌는 횟수. */
	public static final int SURFACE = 7;
	/** 목표 자리까지의 조작 수 (홀드 포함). */
	public static final int PATH = 8;
	/** 진 판인가 (정착한 모래가 보이는 판 위로): 1 또는 0. */
	public static final int LOSS = 9;
	/**
	 * 기다림: 목표에 닿는 순간(생각 지연 + 조작 수 × 입력 간격 뒤)의 판에 놓일 자리가 모래가 멈춘 판에 놓일 자리보다 몇 칸 높은가.
	 * 그 자리 아래 모래가 아직 무너지는 중이라는 뜻이고, 조작기는 그만큼 기다렸다가 떨어뜨린다. 그래서 기다리지 않아도 되는 자리를
	 * 조금 더 좋아한다.
	 */
	public static final int RISE = 10;
	/** 스폰에 가까움: 놓일 자리(멈춘 판)의 맨 위 칸이 칸 {@link #HIGH_ROW} 행보다 몇 칸 위인가 (0 이상). */
	public static final int HIGH = 11;
	public static final int FEATURES = 12;

	static final String[] NAMES = { "cleared", "maxHeight", "meanHeight", "bumpiness", "danger", "fragments", "progress",
			"surface", "path", "loss", "rise", "high" };

	/** {@link #HIGH} 가 0 보다 커지는 칸 행: 블록 맨 위 칸이 이보다 위(행 번호가 작음)면 부서진 모래가 스폰 자리 가까이 있다. */
	public static final int HIGH_ROW = 6;

	/** 위험 특징이 0 보다 커지는 높이 (알갱이, 칸 12 줄). 보이는 판은 160 이다. */
	public static final int DANGER_HEIGHT = 12 * SandRules.GRAIN;

	/** 특징마다의 가중치 (길이 {@link #FEATURES}). 불변. */
	public static final class Weights {

		/**
		 * 벤치마크(SandAiBench, 단계 2·3·5, 12판 × 240초)로 조금 맞춘 기본값. 조작 수 가중치를 -1 에서 -3 으로 키우니 중급의 분당
		 * 점수가 3.9만 → 8.4만, 가장 높이 쌓인 높이가 19.5칸 → 7.4칸이 됐고 상급·불가능은 그대로였다(10-06). 다른 특징(이어진 정도,
		 * 겉면, 기다림, 평균 높이)을 2~3배로 바꿔 본 것은 뚜렷이 낫지 않았다.
		 */
		public static final Weights DEFAULT = new Weights(
				new double[] { 1.0, -0.5, -4.0, -0.5, -20, -2, 3, -2, -3, -1e6, -10, -40 });

		private final double[] w;

		public Weights(double[] w) {
			if (w.length != FEATURES) {
				throw new IllegalArgumentException("가중치는 " + FEATURES + "개: " + w.length);
			}
			this.w = w.clone();
		}

		public double get(int feature) {
			return w[feature];
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder("{");
			for (int i = 0; i < FEATURES; i++) {
				sb.append(i == 0 ? "" : ", ").append(NAMES[i]).append(' ').append(String.format(Locale.ROOT, "%g", w[i]));
			}
			return sb.append('}').toString();
		}
	}

	private SandEval() {
	}

	/** 특징의 가중합. */
	public static double score(double[] f, Weights w) {
		double s = 0;
		for (int i = 0; i < FEATURES; i++) {
			if (f[i] != 0) {
				s += w.w[i] * f[i];
			}
		}
		return s;
	}

	/**
	 * 판 {@code p} (흘리고 지운 뒤, {@link Pile#settle} 다음)의 특징을 {@code out} 에 쓴다.
	 *
	 * @param cleared 이 블록으로 지운 알갱이 수
	 * @param path    목표 자리까지의 조작 수
	 * @param rise    {@link #RISE} (칸)
	 * @param topRow  놓일 자리의 맨 위 칸 행 ({@link #HIGH})
	 */
	static void features(Pile p, int cleared, int path, int rise, int topRow, double[] out) {
		Arrays.fill(out, 0);
		int[] h = p.height;
		int max = 0;
		long sum = 0;
		for (int x = 0; x < Pile.W; x++) {
			max = Math.max(max, h[x]);
			sum += h[x];
		}
		int g = SandRules.GRAIN;
		long bump = 0;
		long prev = 0;
		for (int c = 0; c < Pile.W / g; c++) {
			long s = 0;
			for (int x = c * g; x < c * g + g; x++) {
				s += h[x];
			}
			if (c > 0) {
				bump += Math.abs(s - prev);
			}
			prev = s;
		}
		int surface = 0;
		for (int x = 1; x < Pile.W; x++) {
			if (top(p, x) != top(p, x - 1)) {
				surface++;
			}
		}
		int over = Math.max(0, max - DANGER_HEIGHT);
		Progress pr = new Progress();
		p.components(pr);

		out[CLEARED] = cleared;
		out[MAX_HEIGHT] = max;
		out[MEAN_HEIGHT] = sum / (double) Pile.W;
		out[BUMPINESS] = bump / (double) g;
		out[DANGER] = over * over / 64.0;
		out[FRAGMENTS] = pr.fragments;
		out[PROGRESS] = pr.value();
		out[SURFACE] = surface;
		out[PATH] = path;
		out[LOSS] = p.lost() ? 1 : 0;
		out[RISE] = Math.max(0, rise);
		out[HIGH] = Math.max(0, HIGH_ROW - topRow);
	}

	/** 열 x 의 맨 위 알갱이 색 번호, 빈 열이면 0. */
	private static int top(Pile p, int x) {
		return p.colorAt(x, p.height[x] - 1);
	}

	/** 덩어리를 훑으며 색마다 이어진 정도를 모은다. */
	private static final class Progress implements Pile.ComponentVisitor {
		final int[] wide = new int[SandRules.COLORS + 1];
		final int[] left = new int[SandRules.COLORS + 1];
		final int[] right = new int[SandRules.COLORS + 1];
		int fragments;

		@Override
		public void visit(int code, int minX, int maxX, int grains) {
			fragments++;
			wide[code] = Math.max(wide[code], maxX - minX + 1);
			if (minX == 0) {
				left[code] = Math.max(left[code], maxX + 1);
			}
			if (maxX == Pile.W - 1) {
				right[code] = Math.max(right[code], Pile.W - minX);
			}
		}

		double value() {
			double v = 0;
			for (int k = 1; k <= SandRules.COLORS; k++) {
				int p = Math.min(Pile.W - 1, Math.max(wide[k], left[k] + right[k]));
				v += p * (double) p / Pile.W;
			}
			return v;
		}
	}
}
