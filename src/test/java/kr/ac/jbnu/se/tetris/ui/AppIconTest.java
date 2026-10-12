package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 앱 아이콘: 모든 크기, 투명한 모서리, 두 플레이어 색, 같은 그림, 창 없이 그리기. */
class AppIconTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	@Test
	void everySizeIsDrawnWithTransparentCorners() {
		List<Image> images = AppIcon.images();
		assertEquals(AppIcon.SIZES.length, images.size());
		assertSame(images, AppIcon.images(), "한 번 그린 것을 다시 준다");
		for (int i = 0; i < images.size(); i++) {
			BufferedImage img = (BufferedImage) images.get(i);
			int s = AppIcon.SIZES[i];
			assertEquals(s, img.getWidth());
			assertEquals(s, img.getHeight());
			assertTrue(img.getColorModel().hasAlpha());
			assertEquals(0, img.getRGB(0, 0) >>> 24, s + ": 둥근 모서리 밖은 투명");
			assertEquals(0, img.getRGB(s - 1, s - 1) >>> 24, s + ": 둥근 모서리 밖은 투명");
			assertEquals(255, img.getRGB(s / 2, s / 2) >>> 24, s + ": 가운데는 불투명");
		}
		assertThrows(UnsupportedOperationException.class, () -> images.add(null));
	}

	@Test
	void bothPlayerColoursShowAtEverySize() {
		for (int s : AppIcon.SIZES) {
			BufferedImage img = AppIcon.render(s);
			int p1 = count(img, Theme.P1);
			int p2 = count(img, Theme.P2);
			int area = s * s;
			assertTrue(p1 > area / 25, s + ": P1 색 화소 " + p1);
			assertTrue(p2 > area / 25, s + ": P2 색 화소 " + p2);
			assertTrue(Math.abs(p1 - p2) <= Math.max(2, (p1 + p2) / 6), s + ": 두 블록은 같은 크기 " + p1 + " / " + p2);
			// P1 은 왼쪽 아래 구석 칸, P2 는 오른쪽 위 구석 칸 (4 × 4 칸, 칸 = 크기의 18%)
			int cell = (int) Math.round(s * 0.18);
			int o = (s - 4 * cell) / 2;
			int lo = o + cell / 2;
			int hi = o + 3 * cell + cell / 2;
			assertTrue(near(new Color(img.getRGB(lo, hi)), Theme.P1, 60), s + ": 왼쪽 아래는 P1");
			assertTrue(near(new Color(img.getRGB(hi, lo)), Theme.P2, 60), s + ": 오른쪽 위는 P2");
		}
	}

	@Test
	void sameSizeGivesTheSamePicture() {
		BufferedImage a = AppIcon.render(48);
		BufferedImage b = AppIcon.render(48);
		for (int y = 0; y < 48; y++) {
			for (int x = 0; x < 48; x++) {
				assertEquals(a.getRGB(x, y), b.getRGB(x, y));
			}
		}
		assertThrows(IllegalArgumentException.class, () -> AppIcon.render(4));
	}

	private static int count(BufferedImage img, Color c) {
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				int argb = img.getRGB(x, y);
				if ((argb >>> 24) == 255 && near(new Color(argb), c, 60)) {
					n++;
				}
			}
		}
		return n;
	}

	private static boolean near(Color a, Color b, int tol) {
		return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
				+ Math.abs(a.getBlue() - b.getBlue()) <= tol;
	}
}
