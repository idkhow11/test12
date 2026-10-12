package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 빛 번짐의 조각 그림 (SPEC V3(f)): 예전의 선 다섯 겹과 같은 그림, 다시 쓰기, 그리는 쪽 상태 보존, 그릴 수 없는 변환에서는 예전 방법.
 */
class FxGlowTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	/** 그릴 경우: 사각형, 모서리, 배율. */
	private record Shape(double x, double y, double w, double h, double r, double scale) {
	}

	@Test
	void sameAsStrokesAtQuarterPixelsAndExactSteps() {
		Shape[] shapes = { new Shape(20.25, 30.5, 248.75, 488.25, 14.5, 1), // 보드 (c = 24)
				new Shape(20, 30, 372.75, 732.5, 21.5, 1), // 보드 (c = 36)
				new Shape(16, 18, 280, 54, 54, 1), // 알약
				new Shape(16, 18, 54, 54, 54, 1), // 동그라미
				new Shape(16, 18, 120, 33, 10, 1), // 키
				new Shape(12, 14, 340, 300, 34, 1.5), // 메뉴 카드 (확대)
				new Shape(12.4, 14.8, 200, 120, 20, 1.25), // 화면 배율 125%
				new Shape(10, 10, 40, 30, 0, 2) }; // 각진 사각형 (둥글기 0)
		for (Shape s : shapes) {
			for (int step : new int[] { 1, 5, 9, 12, 15 }) {
				double strength = step / 15.0;
				int[] d = compare(s, Theme.ACCENT, strength, null);
				assertTrue(d[0] <= 3, s + " 세기 " + step + "/15: 가장 큰 차이 " + d[0]);
			}
		}
	}

	@Test
	void closeToStrokesAnywhere() {
		SplittableRandom rnd = new SplittableRandom(20261007);
		Color[] colours = { Theme.P1, Theme.P2, Theme.DANGER, Theme.GOLD, Theme.ACCENT_2, Theme.SAND };
		for (int i = 0; i < 60; i++) {
			double scale = new double[] { 1, 1, 1.25, 1.5, 0.75 }[rnd.nextInt(5)];
			double w = 20 + rnd.nextDouble() * 400;
			double h = 20 + rnd.nextDouble() * 400;
			double r = rnd.nextDouble() * Math.min(80, Math.min(w, h) * 1.2);
			Shape s = new Shape(10 + rnd.nextDouble() * 5, 10 + rnd.nextDouble() * 5, w, h, r, scale);
			double strength = 0.05 + rnd.nextDouble() * 1.0;
			int[] d = compare(s, colours[i % colours.length], strength, null);
			// 위치를 1/4 화소로, 세기를 16단계로 맞춰 생기는 차이: 선 가장자리에서 안티앨리어싱 한두 단계 (2026-10-07 측정: 가장 큰
			// 차이 28, 빛이 닿은 화소의 평균 차이 4.3 이하, 0~255 기준)
			assertTrue(d[0] <= 40, s + " 세기 " + strength + ": 가장 큰 차이 " + d[0]);
			assertTrue(d[3] <= 600, s + " 세기 " + strength + ": 평균 차이 " + d[3] / 100.0);
		}
	}

	@Test
	void callerAlphaFadesTheWholeGlow() {
		// 부르는 쪽 알파는 빛 전체를 한 덩어리로 옅게 한다(겹이 겹치는 곳이 더 밝아지지 않는다). 예전에는 다섯 겹을 따로 옅게 했다.
		double x = 20.25;
		double y = 20.5;
		BufferedImage layer = new BufferedImage(260, 200, BufferedImage.TYPE_INT_ARGB);
		Graphics2D gl = layer.createGraphics();
		Theme.antialias(gl);
		FxGlow.strokes(gl, x, y, 200, 140, 24, Theme.WARN, 0.8);
		gl.dispose();
		BufferedImage want = board(260, 200);
		Graphics2D gw = want.createGraphics();
		gw.setComposite(Fx.composite(0.5));
		gw.drawImage(layer, 0, 0, null);
		gw.dispose();
		BufferedImage got = board(260, 200);
		Graphics2D gg = got.createGraphics();
		Theme.antialias(gg);
		gg.setComposite(Fx.composite(0.5));
		FxGlow.draw(gg, x, y, 200, 140, 24, Theme.WARN, 0.8);
		gg.dispose();
		assertTrue(maxDiff(want, got, 0, 0, 260, 200) <= 4, "알파 0.5 로 빛 전체를 옅게: " + maxDiff(want, got, 0, 0, 260, 200));
	}

	@Test
	void rotatedOrShearedFallsBackToStrokes() {
		BufferedImage a = board(200, 200);
		BufferedImage b = board(200, 200);
		Graphics2D ga = a.createGraphics();
		Graphics2D gb = b.createGraphics();
		for (Graphics2D g : new Graphics2D[] { ga, gb }) {
			Theme.antialias(g);
			g.rotate(0.3, 100, 100);
		}
		FxGlow.draw(ga, 50, 60, 100, 70, 20, Theme.P1, 0.7);
		FxGlow.strokes(gb, 50, 60, 100, 70, 20, Theme.P1, 0.7);
		ga.dispose();
		gb.dispose();
		assertEquals(0, maxDiff(a, b, 0, 0, 200, 200), "회전하면 예전처럼 선으로 긋는다");
	}

	@Test
	void spritesAreReusedAndBoardHeightDoesNotMatter() {
		FxGlow.clear();
		BufferedImage img = board(800, 900);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		long before = FxGlow.spritesMade();
		for (int i = 0; i < 50; i++) {
			Theme.glow(g, 100.25, 50.5, 248.5, 488.75, 14.4, Theme.P1, 0.35);
		}
		assertEquals(before + 1, FxGlow.spritesMade(), "같은 빛은 그림 한 장");
		Theme.glow(g, 100.25, 50.5, 248.5, 700.75, 14.4, Theme.P1, 0.35);
		Theme.glow(g, 400.25, 80.5, 300.5, 520.75, 14.4, Theme.P1, 0.35);
		assertEquals(before + 1, FxGlow.spritesMade(), "보드 크기와 위치(소수 부분이 같으면)는 그림을 바꾸지 않는다");
		long pulse = FxGlow.spritesMade();
		for (int t = 0; t < 600; t += 15) {
			Theme.glow(g, 100.25, 50.5, 248.5, 488.75, 14.4, Theme.DANGER, 0.6 + 0.4 * Math.sin(t / 120.0));
		}
		assertTrue(FxGlow.spritesMade() - pulse <= FxGlow.STEPS, "깜빡이는 세기도 16단계 안에서: "
				+ (FxGlow.spritesMade() - pulse));
		g.dispose();
	}

	@Test
	void cacheStaysBounded() {
		BufferedImage img = board(400, 400);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		for (int i = 0; i < 3 * FxGlow.CAPACITY; i++) {
			Theme.glow(g, 20, 20, 60 + i % 40, 60, 10 + i, new Color(i * 2654435 & 0xFFFFFF), 0.5);
		}
		assertTrue(FxGlow.cached() <= FxGlow.CAPACITY, "기억은 " + FxGlow.CAPACITY + "장까지: " + FxGlow.cached());
		g.dispose();
	}

	@Test
	void callerStateIsRestoredAndZeroStrengthDrawsNothing() {
		BufferedImage img = board(300, 300);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.translate(7, 9);
		g.scale(1.25, 1.25);
		Composite comp = Fx.composite(0.7);
		g.setComposite(comp);
		Stroke stroke = new BasicStroke(3f);
		g.setStroke(stroke);
		g.setColor(Color.MAGENTA);
		AffineTransform tx = g.getTransform();
		Theme.glow(g, 20, 20, 150, 120, 18, Theme.GOLD, 0.37);
		assertEquals(tx, g.getTransform(), "변환");
		assertSame(comp, g.getComposite(), "합성");
		assertSame(stroke, g.getStroke(), "선");
		assertEquals(Color.MAGENTA, g.getColor(), "색");
		g.dispose();

		BufferedImage clean = board(100, 100);
		Graphics2D g2 = clean.createGraphics();
		Theme.glow(g2, 10, 10, 60, 60, 10, Theme.P1, 0);
		Theme.glow(g2, 10, 10, 60, 60, 10, Theme.P1, -1);
		Theme.glow(g2, 10, 10, 60, 60, 10, Theme.P1, Double.NaN);
		g2.dispose();
		assertEquals(0, maxDiff(clean, board(100, 100), 0, 0, 100, 100), "세기 0 이하는 아무것도 그리지 않는다");
	}

	@Test
	void glowAllocatesOnlyTheTransformCopy() {
		Assumptions.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean sun
				&& sun.isThreadAllocatedMemorySupported(), "이 JVM 은 할당량을 잴 수 없다");
		com.sun.management.ThreadMXBean sun = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		BufferedImage img = board(640, 640);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		int n = 2000;
		for (int round = 0; round < 3; round++) {
			for (int i = 0; i < n; i++) {
				Theme.glow(g, 100.68, 40.68, 248.64, 488.64, 14.4, Theme.P1, 0.35);
			}
		}
		long before = sun.getCurrentThreadAllocatedBytes();
		for (int i = 0; i < n; i++) {
			Theme.glow(g, 100.68, 40.68, 248.64, 488.64, 14.4, Theme.P1, 0.35);
		}
		double perCall = (sun.getCurrentThreadAllocatedBytes() - before) / (double) n;
		g.dispose();
		// 예전 선 다섯 겹은 한 번에 약 1 KB. 이제는 g.getTransform() 의 복사본 하나 정도
		assertTrue(perCall <= 160, "한 번에 " + perCall + " 바이트");
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 조각 그림과 선 다섯 겹을 같은 자리에 그려 비교: {가장 큰 채널 차이, 차이가 4 를 넘는 화소 수, 빛이 닿은 화소 수, 빛이 닿은 화소의 평균
	 * 차이 × 100}.
	 */
	private static int[] compare(Shape s, Color c, double strength, Composite comp) {
		int w = (int) Math.ceil((s.x + s.w) * s.scale) + 30;
		int h = (int) Math.ceil((s.y + s.h) * s.scale) + 30;
		BufferedImage a = board(w, h);
		BufferedImage b = board(w, h);
		Graphics2D ga = a.createGraphics();
		Graphics2D gb = b.createGraphics();
		for (Graphics2D g : new Graphics2D[] { ga, gb }) {
			Theme.antialias(g);
			g.scale(s.scale, s.scale);
			if (comp != null) {
				g.setComposite(comp);
			}
		}
		FxGlow.draw(ga, s.x, s.y, s.w, s.h, s.r, c, strength);
		FxGlow.strokes(gb, s.x, s.y, s.w, s.h, s.r, c, strength);
		ga.dispose();
		gb.dispose();
		int bg = Theme.BOARD.getRGB();
		int max = 0;
		int over = 0;
		int lit = 0;
		long sum = 0;
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				int d = channelDiff(p, q);
				if (p != bg || q != bg) {
					lit++;
					sum += d;
				}
				max = Math.max(max, d);
				if (d > 4) {
					over++;
				}
			}
		}
		assertTrue(lit > 0, "빛이 그려졌다");
		return new int[] { max, over, lit, (int) (sum * 100 / lit) };
	}

	private static BufferedImage board(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Theme.BOARD);
		g.fillRect(0, 0, w, h);
		g.dispose();
		return img;
	}

	private static int maxDiff(BufferedImage a, BufferedImage b, int x0, int y0, int w, int h) {
		int max = 0;
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				max = Math.max(max, channelDiff(a.getRGB(x, y), b.getRGB(x, y)));
			}
		}
		return max;
	}

	private static int channelDiff(int p, int q) {
		int d = 0;
		for (int sh = 0; sh <= 16; sh += 8) {
			d = Math.max(d, Math.abs((p >> sh & 0xFF) - (q >> sh & 0xFF)));
		}
		return d;
	}
}
