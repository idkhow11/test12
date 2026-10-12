package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.DoubleUnaryOperator;

import org.junit.jupiter.api.Test;

/** 움직임의 기초 (SPEC V3(b)): 이징의 양 끝과 모양, 베지어의 정확도, 용수철의 꼭대기와 되튐, 프레임과 무관한 감쇠. */
class MotionTest {

	private static final double EPS = 1e-9;

	@Test
	void easingsStartAtZeroEndAtOneAndClamp() {
		DoubleUnaryOperator[] all = { Motion::linear, Motion::easeOutQuad, Motion::easeOutCubic, Motion::easeOutQuart,
				Motion::easeOutQuint, Motion::easeInCubic, Motion::easeOutBack, Motion::enter, Motion::move,
				Motion::exit };
		for (DoubleUnaryOperator f : all) {
			assertEquals(0, f.applyAsDouble(0), EPS);
			assertEquals(1, f.applyAsDouble(1), EPS);
			assertEquals(0, f.applyAsDouble(-3), EPS, "범위 밖은 자른다");
			assertEquals(1, f.applyAsDouble(7), EPS, "범위 밖은 자른다");
			assertEquals(0, f.applyAsDouble(Double.NaN), EPS, "NaN 은 0");
		}
	}

	@Test
	void easingsFollowTheirFormulas() {
		for (double x = 0; x <= 1; x += 0.05) {
			double v = 1 - x;
			assertEquals(1 - v * v * v, Motion.easeOutCubic(x), EPS);
			assertEquals(1 - Math.pow(v, 4), Motion.easeOutQuart(x), EPS);
			assertEquals(1 - Math.pow(v, 5), Motion.easeOutQuint(x), EPS);
			assertEquals(x * x * x, Motion.easeInCubic(x), EPS);
			assertEquals(1 + 2.70158 * Math.pow(x - 1, 3) + 1.70158 * Math.pow(x - 1, 2), Motion.easeOutBack(x), EPS);
			assertEquals(Motion.easeOutQuint(x), Motion.enter(x), EPS, "들어올 때 = easeOutQuint");
		}
	}

	@Test
	void decelerationsRiseMonotonically() {
		DoubleUnaryOperator[] mono = { Motion::easeOutQuad, Motion::easeOutCubic, Motion::easeOutQuart,
				Motion::easeOutQuint, Motion::easeInCubic, Motion::move, Motion::exit };
		for (DoubleUnaryOperator f : mono) {
			double prev = -1;
			for (int i = 0; i <= 1000; i++) {
				double y = f.applyAsDouble(i / 1000.0);
				assertTrue(y >= prev - 1e-12, "단조 증가");
				prev = y;
			}
		}
		assertTrue(Motion.easeOutCubic(0.5) > 0.5, "감속은 앞쪽이 빠르다");
		assertTrue(Motion.easeInCubic(0.5) < 0.5, "가속은 뒤쪽이 빠르다");
	}

	@Test
	void easeOutBackOvershootsAboutTenPercent() {
		double max = 0;
		double at = 0;
		for (int i = 0; i <= 10000; i++) {
			double x = i / 10000.0;
			double y = Motion.easeOutBack(x);
			if (y > max) {
				max = y;
				at = x;
			}
		}
		assertEquals(1.1, max, 0.001, "꼭대기 약 1.1");
		assertEquals(0.58, at, 0.01);
	}

	@Test
	void bezierMatchesABisectionReference() {
		double[][] curves = { { 0.2, 0, 0, 1 }, { 0.3, 0, 1, 1 }, { 0.1, 0.7, 0.1, 1 }, { 0.42, 0, 0.58, 1 },
				{ 0, 0, 1, 1 }, { 0.9, 0.1, 0.1, 0.9 } };
		for (double[] c : curves) {
			for (int i = 0; i <= 200; i++) {
				double x = i / 200.0;
				assertEquals(reference(c, x), Motion.bezier(c[0], c[1], c[2], c[3], x), 1e-5,
						"bezier(" + c[0] + "," + c[1] + "," + c[2] + "," + c[3] + ") x=" + x);
			}
		}
		for (int i = 0; i <= 20; i++) {
			assertEquals(i / 20.0, Motion.bezier(0, 0, 1, 1, i / 20.0), 1e-6, "(0,0,1,1) 은 직선");
		}
		assertEquals(Motion.bezier(0.2, 0, 0, 1, 0.37), Motion.move(0.37), EPS);
		assertEquals(Motion.bezier(0.3, 0, 1, 1, 0.99), Motion.exit(0.99), EPS);
	}

	/** 매개변수를 이분법으로 끝까지 찾은 값. */
	private static double reference(double[] c, double x) {
		double lo = 0;
		double hi = 1;
		for (int i = 0; i < 100; i++) {
			double u = (lo + hi) / 2;
			if (cubic(c[0], c[2], u) < x) {
				lo = u;
			} else {
				hi = u;
			}
		}
		return cubic(c[1], c[3], (lo + hi) / 2);
	}

	private static double cubic(double p1, double p2, double u) {
		double v = 1 - u;
		return 3 * v * v * u * p1 + 3 * v * u * u * p2 + u * u * u;
	}

	@Test
	void springPeaksAt21msAndReboundsOnce() {
		assertEquals(0, Motion.spring(0), EPS);
		assertEquals(0, Motion.spring(-5), EPS, "시작 전은 0");
		double max = 0;
		int at = 0;
		double min = 0;
		int minAt = 0;
		for (int ms = 0; ms <= 400; ms++) {
			double y = Motion.spring(ms);
			if (y > max) {
				max = y;
				at = ms;
			}
			if (y < min) {
				min = y;
				minAt = ms;
			}
			if (ms >= 250) {
				assertTrue(Math.abs(y) < 0.02, "250 ms 뒤에는 거의 멈춘다: " + ms + " ms " + y);
			}
		}
		assertEquals(1.0, max, 0.01, "꼭대기는 1");
		assertEquals(21, at, 1, "꼭대기 21 ms");
		assertEquals(-0.126, min, 0.01, "한 번 되튀어 약 13%");
		assertEquals(87, minAt, 2, "되튐 87 ms");
	}

	@Test
	void dampIsIndependentOfFrameRate() {
		double tau = 180;
		double one = Motion.damp(0.2, 0.9, 32, tau);
		double two = Motion.damp(Motion.damp(0.2, 0.9, 16, tau), 0.9, 16, tau);
		double four = 0.2;
		for (int i = 0; i < 4; i++) {
			four = Motion.damp(four, 0.9, 8, tau);
		}
		assertEquals(one, two, 1e-12, "16 ms 두 번 = 32 ms 한 번");
		assertEquals(one, four, 1e-12, "8 ms 네 번 = 32 ms 한 번");
		assertEquals(0.2 + 0.7 * (1 - Math.exp(-32 / tau)), one, 1e-12);
		assertEquals(0.9, Motion.damp(0.2, 0.9, 16, 0), EPS, "τ = 0 이면 바로 목표");
		assertEquals(0.2, Motion.damp(0.2, 0.9, 0, tau), EPS, "시간이 안 흐르면 그대로");
		assertEquals(0.9, Motion.damp(0.2, 0.9, 1e6, tau), EPS, "오래 지나면 목표");
	}

	@Test
	void progressPulseAndTokens() {
		assertEquals(0, Motion.progress(90, 100, 250), EPS, "시작 전");
		assertEquals(0.5, Motion.progress(225, 100, 250), EPS);
		assertEquals(1, Motion.progress(900, 100, 250), EPS, "끝난 뒤");
		assertEquals(1, Motion.progress(100, 100, 0), EPS, "길이 0 이면 바로 끝");
		assertEquals(0, Motion.progress(99, 100, 0), EPS);
		assertEquals(0, Motion.pulse(0, 2), EPS, "맥박은 0 에서 시작");
		assertEquals(1, Motion.pulse(250, 2), EPS, "2 Hz 면 250 ms 에 꼭대기");
		assertEquals(Motion.pulse(130, 2), Motion.pulse(130, 12), EPS, "2 Hz 보다 빠른 깜빡임은 2 Hz 로 자른다");
		assertEquals(3.5, Motion.lerp(3, 4, 0.5), EPS);
		assertEquals(4.2, Motion.lerp(3, 4, 1.2), EPS, "lerp 는 넘친 곡선을 그대로 쓴다");
		assertEquals(100, Motion.FAST);
		assertEquals(150, Motion.SHORT);
		assertEquals(250, Motion.MED);
		assertEquals(400, Motion.LONG);
	}
}
