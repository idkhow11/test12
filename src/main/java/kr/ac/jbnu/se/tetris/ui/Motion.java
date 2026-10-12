package kr.ac.jbnu.se.tetris.ui;

/**
 * 움직임의 기초 (runs/quality/SPEC.md V3(b)): 이징 곡선, 용수철, 프레임과 무관한 감쇠, 시간 토큰.
 * <p>
 * 모든 함수는 상태가 없고 객체를 만들지 않는다(초당 60번 그려도 쓰레기가 생기지 않는다). 시간은 ms 다. 진행도 {@code x} 는 0~1 이고,
 * 범위 밖의 값은 0 또는 1 로 자른다. 효과는 게임 상태를 읽기만 하고, 시간은 대전 중에는 대전 시계({@code m.time()}), 끝난 뒤에는
 * 세션 시계를 쓴다(SPEC 0장 규칙 2).
 * <p>
 * 쓰는 법: 들어올 때 {@link #enter}, 움직일 때 {@link #move}, 나갈 때 {@link #exit}. 길이는 {@link #FAST}·{@link #SHORT}·
 * {@link #MED}·{@link #LONG} 중에서 고른다(피드백 100~150 ms, 카드 200~300 ms, 400 ms 를 넘기지 않는다).
 *
 * <pre>
 * double u = Motion.progress(now, shownAt, Motion.MED); // 0 → 1
 * double a = Motion.enter(u); // 빠르게 시작해 부드럽게 멈춘다
 * </pre>
 */
final class Motion {

	/** 짧은 피드백 (눌림, 반짝임). */
	static final int FAST = 100;
	/** 작은 움직임 (초점 이동, 글자 등장). */
	static final int SHORT = 150;
	/** 카드·오버레이가 들고 나는 시간. */
	static final int MED = 250;
	/** 가장 긴 움직임. 이보다 길게 하지 않는다. */
	static final int LONG = 400;

	/** {@link #spring} 의 감쇠 (1/s): ζ = 0.55, ω = 2π·9 에서 ζω. */
	private static final double SPRING_DECAY = 31.1;
	/** {@link #spring} 의 진동 (rad/s): ω·√(1 − ζ²). */
	private static final double SPRING_FREQ = 47.2;
	/** {@link #spring} 의 첫 꼭대기 값. 이것으로 나눠 꼭대기를 1 로 맞춘다. */
	private static final double SPRING_PEAK = 0.4355;
	private static final double BACK_C1 = 1.70158;
	private static final double BACK_C3 = BACK_C1 + 1;
	private static final int NEWTON_STEPS = 4;

	private Motion() {
	}

	/** 0~1 로 자른다. NaN 은 0. */
	static double clamp01(double x) {
		return x > 0 ? (x < 1 ? x : 1) : 0;
	}

	/** {@code start} 부터 {@code durationMs} 동안의 진행도(0~1). 시작 전은 0, 끝난 뒤는 1. 길이가 0 이하면 시작하자마자 1. */
	static double progress(long now, long start, long durationMs) {
		if (durationMs <= 0) {
			return now >= start ? 1 : 0;
		}
		return clamp01((double) (now - start) / durationMs);
	}

	/** a 에서 b 로 t 만큼 (t 를 자르지 않는다: 튀어나가는 곡선을 그대로 쓴다). */
	static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	// ------------------------------------------------------------------ 이징 (x: 0~1 → 0~1)

	static double linear(double x) {
		return clamp01(x);
	}

	/** 1 − (1 − x)²: 가장 부드러운 감속 (가장자리 불꽃, 고정 반짝임). */
	static double easeOutQuad(double x) {
		double v = 1 - clamp01(x);
		return 1 - v * v;
	}

	/** 1 − (1 − x)³: 일반적인 감속. */
	static double easeOutCubic(double x) {
		double v = 1 - clamp01(x);
		return 1 - v * v * v;
	}

	/** 1 − (1 − x)⁴. */
	static double easeOutQuart(double x) {
		double v = 1 - clamp01(x);
		double v2 = v * v;
		return 1 - v2 * v2;
	}

	/** 1 − (1 − x)⁵: 강하게 감속 (들어올 때). Material 3 의 강조 감속 (0.1, 0.7, 0.1, 1) 과 거의 같다. */
	static double easeOutQuint(double x) {
		double v = 1 - clamp01(x);
		double v2 = v * v;
		return 1 - v2 * v2 * v;
	}

	/** x³: 가속 (사라질 때, 줄어들 때). */
	static double easeInCubic(double x) {
		double v = clamp01(x);
		return v * v * v;
	}

	/** 1 + 2.70158(x − 1)³ + 1.70158(x − 1)²: 살짝 넘쳤다가(최대 약 1.1) 돌아온다 (글자가 튀어나올 때). */
	static double easeOutBack(double x) {
		double v = clamp01(x) - 1;
		return 1 + BACK_C3 * v * v * v + BACK_C1 * v * v;
	}

	/** 들어올 때 (카드, 글자, 화면): {@link #easeOutQuint}. */
	static double enter(double x) {
		return easeOutQuint(x);
	}

	/** 자리를 옮길 때 (초점 테두리, 막대): 3차 베지어 (0.2, 0, 0, 1). */
	static double move(double x) {
		return bezier(0.2, 0, 0, 1, x);
	}

	/** 나갈 때 (어두워짐, 닫힘): 3차 베지어 (0.3, 0, 1, 1). */
	static double exit(double x) {
		return bezier(0.3, 0, 1, 1, x);
	}

	/**
	 * CSS {@code cubic-bezier(x1, y1, x2, y2)} 와 같은 곡선. x 에 맞는 매개변수를 뉴턴법 4번으로 찾고, 기울기가 0 에 가까워 뉴턴법이
	 * 맞지 않으면 이분법으로 마무리한다. x1, x2 는 0~1 이어야 한다(그래야 x 가 한 값으로 정해진다).
	 */
	static double bezier(double x1, double y1, double x2, double y2, double x) {
		double t = clamp01(x);
		if (t == 0 || t == 1) {
			return t;
		}
		double u = t;
		for (int i = 0; i < NEWTON_STEPS; i++) {
			double err = cubic(x1, x2, u) - t;
			double d = cubicSlope(x1, x2, u);
			if (Math.abs(d) < 1e-6) {
				break;
			}
			u = clamp01(u - err / d);
		}
		if (Math.abs(cubic(x1, x2, u) - t) > 1e-6) { // 뉴턴법이 멈춘 곳 (끝 기울기 0): 이분법
			double lo = 0;
			double hi = 1;
			u = t;
			for (int i = 0; i < 40; i++) {
				double v = cubic(x1, x2, u);
				if (Math.abs(v - t) < 1e-9) {
					break;
				}
				if (v < t) {
					lo = u;
				} else {
					hi = u;
				}
				u = (lo + hi) / 2;
			}
		}
		return cubic(y1, y2, u);
	}

	/** 끝점이 0 과 1 인 3차 베지어의 한 좌표: 3(1−u)²u·p1 + 3(1−u)u²·p2 + u³. */
	private static double cubic(double p1, double p2, double u) {
		double v = 1 - u;
		return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
	}

	private static double cubicSlope(double p1, double p2, double u) {
		double v = 1 - u;
		return 3 * v * v * p1 + 6 * v * u * (p2 - p1) + 3 * u * u * (1 - p2);
	}

	// ------------------------------------------------------------------ 용수철, 감쇠, 맥박

	/**
	 * 감쇠 진동 (하드드롭의 튀는 반동, SPEC V5): e^(−31.1t)·sin(47.2t) / 0.4355, t 는 초. ζ = 0.55, 9 Hz 라서 21 ms 에 1(꼭대기),
	 * 87 ms 에 약 −0.13 으로 한 번 되튀고 250 ms 면 거의 0 이다. 시작 전(음수)은 0. 반환값에 진폭(화소)을 곱해 쓴다.
	 */
	static double spring(double ms) {
		if (ms <= 0) {
			return 0;
		}
		double t = ms / 1000.0;
		return Math.exp(-SPRING_DECAY * t) * Math.sin(SPRING_FREQ * t) / SPRING_PEAK;
	}

	/**
	 * 프레임 간격과 무관한 따라가기: cur + (target − cur)(1 − e^(−dt/τ)). 같은 시간이면 한 번에 가든 여러 번에 나눠 가든 같은 곳에
	 * 닿는다 (Swing 타이머는 화면 주사와 맞지 않으므로 프레임마다 일정 비율로 다가가면 안 된다). τ 가 0 이하면 바로 target.
	 */
	static double damp(double cur, double target, double dtMs, double tauMs) {
		if (tauMs <= 0 || dtMs >= tauMs * 50) {
			return target;
		}
		if (dtMs <= 0) {
			return cur;
		}
		return cur + (target - cur) * (1 - Math.exp(-dtMs / tauMs));
	}

	/**
	 * 0 과 1 사이를 부드럽게 오가는 맥박: 0.5 − 0.5·cos(2π·hz·t). t = 0 에서 0. 깜빡임은 2 Hz 를 넘기지 않는다(SPEC 6장 2): 더 큰 값은
	 * 2 Hz 로 자른다.
	 */
	static double pulse(long ms, double hz) {
		double f = Math.min(Math.max(hz, 0), 2);
		return 0.5 - 0.5 * Math.cos(2 * Math.PI * f * ms / 1000.0);
	}
}
