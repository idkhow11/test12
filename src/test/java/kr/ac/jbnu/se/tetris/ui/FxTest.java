package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 효과의 기초 (SPEC 0장 규칙 3, V3(f)): 상태 없는 난수, 16단계 알파 표, 합성 표, 조각 그림, 그릴 때 객체를 만들지 않음. */
class FxTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	@Test
	void randIsAPureFunctionInZeroToOne() {
		for (int k = 0; k < 1000; k++) {
			double a = Fx.rand(42, 1234, 3, 7, k);
			assertEquals(a, Fx.rand(42, 1234, 3, 7, k), "같은 입력이면 같은 값");
			assertTrue(a >= 0 && a < 1, "0 이상 1 미만: " + a);
		}
		double base = Fx.rand(1, 2, 3, 4, 5);
		assertNotEquals(base, Fx.rand(9, 2, 3, 4, 5), "시드");
		assertNotEquals(base, Fx.rand(1, 9, 3, 4, 5), "사건 시각");
		assertNotEquals(base, Fx.rand(1, 2, 9, 4, 5), "a");
		assertNotEquals(base, Fx.rand(1, 2, 3, 9, 5), "b");
		assertNotEquals(base, Fx.rand(1, 2, 3, 4, 9), "k");
		assertNotEquals(Fx.rand(1, 2, 3, 4, 5), Fx.rand(1, 2, 4, 3, 5), "a 와 b 를 바꾸면 다른 값");
		double r = Fx.rand(7, 8, 9, 10, 11, 3, 9);
		assertTrue(r >= 3 && r < 9, "범위를 늘인 값");
	}

	@Test
	void randIsRoughlyUniform() {
		int n = 200_000;
		int[] bins = new int[10];
		double sum = 0;
		for (int i = 0; i < n; i++) {
			double v = Fx.rand(2026, i / 50, i % 10, i % 7, i);
			bins[(int) (v * 10)]++;
			sum += v;
		}
		assertEquals(0.5, sum / n, 0.005, "평균");
		for (int b : bins) {
			assertEquals(n / 10.0, b, n / 10.0 * 0.03, "칸마다 고르게");
		}
	}

	@Test
	void noiseIsSmoothAndBounded() {
		double prev = Fx.noise(5, 0);
		for (int i = 1; i <= 5000; i++) {
			double x = i * 0.01;
			double v = Fx.noise(5, x);
			assertTrue(v >= -1 && v <= 1, "−1~1");
			assertTrue(Math.abs(v - prev) < 0.04, "이어져 있다: " + x);
			assertEquals(v, Fx.noise(5, x), "같은 입력이면 같은 값");
			prev = v;
		}
		assertEquals(Fx.rand(5, 3, 0, 0, 0) * 2 - 1, Fx.noise(5, 3), 1e-12, "정수 자리에서는 그 자리의 값");
		assertNotEquals(Fx.noise(5, 2.5), Fx.noise(6, 2.5), "시드마다 다르다");
	}

	@Test
	void alphaTablesHaveSixteenStepsAndAreShared() {
		Color[] t = Fx.alphas(Theme.P1);
		assertEquals(Fx.ALPHA_STEPS, t.length);
		for (int i = 0; i < t.length; i++) {
			assertEquals(i * 17, t[i].getAlpha());
			assertEquals(Theme.P1.getRGB() & 0xFFFFFF, t[i].getRGB() & 0xFFFFFF);
		}
		assertSame(t, Fx.alphas(new Color(Theme.P1.getRGB())), "같은 RGB 면 같은 표");
		assertSame(t, Fx.alphas(new Color(Theme.P1.getRed(), Theme.P1.getGreen(), Theme.P1.getBlue(), 30)),
				"원래 알파는 무시");
		assertSame(t[8], Fx.alpha(Theme.P1, 0.5), "0.5 → 8단계 (반올림)");
		assertSame(t[0], Fx.alpha(Theme.P1, -1));
		assertSame(t[15], Fx.alpha(Theme.P1, 3));
	}

	@Test
	void compositesComeFromATable() {
		assertSame(AlphaComposite.SrcOver, Fx.composite(1));
		assertSame(Fx.composite(0.5), Fx.composite(0.5));
		assertEquals(128 / 255f, Fx.composite(0.5).getAlpha(), 1e-6);
		assertEquals(AlphaComposite.SRC_OVER, Fx.composite(0.3).getRule());
		assertEquals(0f, Fx.composite(-2).getAlpha());
		assertEquals(Fx.composite(0.25), Fx.times(Fx.composite(0.5), 0.5), "알파끼리 곱한다");
		assertSame(Fx.composite(0.6), Fx.times(AlphaComposite.SrcOver, 0.6));
		assertNull(Fx.times(AlphaComposite.Xor, 0.5), "SRC_OVER 가 아니면 곱할 수 없다");
	}

	@Test
	void lighterMatchesThemeMixAndIsCached() {
		Color c = Fx.lighter(Theme.P2, 0.2);
		assertEquals(Theme.mix(Theme.P2, Color.WHITE, 0.2), c);
		assertSame(c, Fx.lighter(new Color(Theme.P2.getRGB()), 0.2));
		assertEquals(Theme.P2.getRGB(), Fx.lighter(Theme.P2, 0).getRGB());
		assertEquals(Color.WHITE.getRGB(), Fx.lighter(Theme.P2, 1).getRGB());
	}

	@Test
	void dotSpriteIsSoftRoundAndCached() {
		BufferedImage dst = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = dst.createGraphics();
		BufferedImage dot = Fx.dot(g, Theme.GOLD, 16);
		assertSame(dot, Fx.dot(g, Theme.GOLD, 16), "같은 색·크기는 한 번만 만든다");
		assertEquals(16, dot.getWidth());
		assertTrue((dot.getRGB(8, 8) >>> 24) > 200, "가운데는 거의 불투명");
		assertEquals(0, dot.getRGB(0, 0) >>> 24, "모서리는 투명");
		assertTrue((dot.getRGB(8, 8) >>> 24) > (dot.getRGB(12, 8) >>> 24), "바깥으로 갈수록 옅다");
		BufferedImage big = Fx.dot(g, Theme.GOLD, 216);
		assertEquals(216, big.getWidth(), "카운트다운 뒤의 빛(지름 6c)처럼 큰 점");
		assertEquals(Fx.MAX_DOT, Fx.dot(g, Theme.GOLD, 4000).getWidth(), "너무 크면 자른다");
		assertTrue(big != Fx.dot(g, Theme.P1, 216), "색이 다르면 다른 그림");
		Fx.draw(g, dot, 10, 10, 0.5);
		assertEquals(AlphaComposite.SrcOver, g.getComposite(), "합성을 돌려놓는다");
		g.dispose();
		assertTrue((dst.getRGB(18, 18) & 0xFF0000) != 0, "그려졌다");
	}

	@Test
	void intTableFindsAndOverflowsByClearing() {
		Fx.IntTable<String> t = new Fx.IntTable<>(8);
		t.put(0, "zero");
		t.put(-5, "minus");
		t.put(0xFFFFFF, "white");
		assertEquals("zero", t.get(0));
		assertEquals("minus", t.get(-5));
		assertEquals("white", t.get(0xFFFFFF));
		assertNull(t.get(1));
		t.put(0, "again");
		assertEquals("again", t.get(0));
		assertEquals(3, t.size());
		for (int i = 100; i < 200; i++) {
			t.put(i, "v" + i);
			assertEquals("v" + i, t.get(i), "넣은 것은 바로 찾는다");
		}
		assertTrue(t.size() <= 8, "가득 차면 비우고 다시 채운다");
	}

	@Test
	void lookupsDoNotAllocate() {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		Assumptions.assumeTrue(mx instanceof com.sun.management.ThreadMXBean sun && sun.isThreadAllocatedMemorySupported(),
				"이 JVM 은 할당량을 잴 수 없다");
		com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) mx;
		int n = 20_000;
		double sink = 0;
		for (int round = 0; round < 3; round++) { // 데우기 (JIT)
			sink += lookups(n);
		}
		long before = sun.getCurrentThreadAllocatedBytes();
		sink += lookups(n);
		long bytes = sun.getCurrentThreadAllocatedBytes() - before;
		assertTrue(bytes < 4096, "표에서 꺼낼 때 객체를 만들지 않는다: " + n + "번에 " + bytes + " 바이트 (" + sink + ")");
	}

	private static double lookups(int n) {
		double sink = 0;
		for (int i = 0; i < n; i++) {
			double a = (i % 100) / 100.0;
			sink += Fx.alpha(Theme.P1, a).getAlpha();
			sink += Fx.composite(a).getAlpha();
			sink += Fx.rand(1, i, 2, 3, 4);
			sink += Fx.noise(9, i * 0.01);
			sink += Fx.lighter(Theme.WARN, 0.2).getRed();
			sink += Motion.bezier(0.2, 0, 0, 1, a) + Motion.spring(i % 300) + Motion.easeOutBack(a);
		}
		return sink;
	}
}
