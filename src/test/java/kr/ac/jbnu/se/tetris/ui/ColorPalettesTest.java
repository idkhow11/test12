package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 색 묶음과 블록 무늬 (설정의 색약 색상·블록 무늬): 기본 색은 지금 게임과 화소까지 같고, 색약 색상은 색약을 흉내 낸 색에서도
 * 서로 떨어져 있고, 무늬는 블록마다 다르고 블록 면 안에 들어간다.
 */
class ColorPalettesTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	// ------------------------------------------------------------------ 기본 색 = 지금 게임

	@Test
	void normalPaletteIsTheGameColours() {
		for (int code = 1; code <= ColorPalettes.GARBAGE; code++) {
			assertEquals(Theme.piece(code), ColorPalettes.Palette.NORMAL.piece(code), "칸 값 " + code);
		}
		assertSame(ColorPalettes.standard(), ColorPalettes.look(UserSettings.defaults()), "처음 설정은 기본 색, 무늬 없음");
		assertSame(ColorPalettes.Palette.NORMAL, ColorPalettes.standard().palette());
		assertFalse(ColorPalettes.standard().patterns());
	}

	@Test
	void standardLookDrawsExactlyLikeTheme() {
		ColorPalettes.Look look = ColorPalettes.standard();
		for (int size : new int[] { 6, 13, 14, 24, 37 }) {
			for (int code = 1; code <= ColorPalettes.GARBAGE; code++) {
				for (int alpha : new int[] { 255, 120 }) {
					for (boolean grey : new boolean[] { false, true }) {
						int c = code;
						int a = alpha;
						boolean gr = grey;
						BufferedImage want = draw(size, g -> Theme.block(g, 3, 3, size, c, a, gr));
						BufferedImage got = draw(size, g -> look.block(g, 3, 3, size, c, a, gr));
						assertEquals(0, differing(want, got), "block " + size + " " + code + " " + alpha + " " + grey);
					}
				}
				int c = code;
				assertEquals(0, differing(draw(size, g -> Theme.ghost(g, 3, 3, size, c)),
						draw(size, g -> look.ghost(g, 3, 3, size, c))), "ghost " + size + " " + code);
			}
		}
	}

	@Test
	void colorBlindBlocksHaveTheShapeOfThemeBlocks() {
		ColorPalettes.Look cb = ColorPalettes.look(true, false);
		for (int size : new int[] { 8, 14, 24, 36 }) {
			for (int code = 1; code <= ColorPalettes.GARBAGE; code++) {
				int c = code;
				BufferedImage theme = draw(size, g -> Theme.block(g, 3, 3, size, c, 255, false));
				BufferedImage mine = draw(size, g -> cb.block(g, 3, 3, size, c, 255, false));
				assertEquals(0, footprintDiff(theme, mine), "같은 자리를 칠한다: " + size + " " + code);
			}
			// 쓴 홀드의 회색은 색 묶음과 상관없이 같다
			BufferedImage grey = draw(size, g -> Theme.block(g, 3, 3, size, 6, 255, true));
			assertEquals(0, differing(grey, draw(size, g -> cb.block(g, 3, 3, size, 6, 255, true))), "회색 " + size);
		}
	}

	// ------------------------------------------------------------------ 색약 흉내

	@Test
	void colorBlindPaletteStaysApartUnderSimulatedColourBlindness() {
		for (Cvd.Kind k : Cvd.Kind.values()) {
			double cb = minDistance(ColorPalettes.Palette.COLOR_BLIND, k);
			assertTrue(cb >= 10, "색약 색상, " + k + ": 가장 가까운 두 색의 차이 " + cb);
		}
		// 기본 색은 적색약·녹색약에서 두 블록이 거의 같은 색이 된다 (이 설정이 있는 까닭)
		assertTrue(minDistance(ColorPalettes.Palette.NORMAL, Cvd.Kind.PROTAN) < 6);
		assertTrue(minDistance(ColorPalettes.Palette.NORMAL, Cvd.Kind.DEUTAN) < 6);
		// 보통 눈에도 서로 잘 떨어져 있다
		assertTrue(minDistance(ColorPalettes.Palette.COLOR_BLIND, null) >= 15);
	}

	@Test
	void colorBlindSandColoursStayApart() {
		for (Cvd.Kind k : new Cvd.Kind[] { null, Cvd.Kind.PROTAN, Cvd.Kind.DEUTAN, Cvd.Kind.TRITAN }) {
			List<Color> sand = new ArrayList<>();
			for (int c = 1; c <= ColorPalettes.SAND_COLORS; c++) {
				sand.add(ColorPalettes.Palette.COLOR_BLIND.sand(c));
			}
			double d = minDistance(sand, k);
			assertTrue(d >= 12, "모래 네 색, " + k + ": " + d);
		}
		// 기본 모래 색은 녹색약에서 빨강과 초록이 가깝다
		List<Color> normal = new ArrayList<>();
		for (int c = 1; c <= ColorPalettes.SAND_COLORS; c++) {
			normal.add(ColorPalettes.Palette.NORMAL.sand(c));
		}
		assertTrue(minDistance(normal, Cvd.Kind.DEUTAN) < 8);
	}

	@Test
	void sandColourNumbersFollowSandColor() {
		// 모래 색 번호 = SandColor.code(): 1 빨강, 2 파랑, 3 초록, 4 노랑. 기본 색은 같은 계열의 블록 색이다
		assertEquals(1, SandColor.RED.code());
		assertEquals(4, SandColor.YELLOW.code());
		ColorPalettes.Palette n = ColorPalettes.Palette.NORMAL;
		assertEquals(Theme.piece(7), n.sand(SandColor.RED.code()));
		assertEquals(Theme.piece(2), n.sand(SandColor.BLUE.code()));
		assertEquals(Theme.piece(5), n.sand(SandColor.GREEN.code()));
		assertEquals(Theme.piece(4), n.sand(SandColor.YELLOW.code()));
		assertThrows(IllegalArgumentException.class, () -> n.sand(0));
		assertThrows(IllegalArgumentException.class, () -> n.sand(5));
		assertThrows(IllegalArgumentException.class, () -> n.piece(0));
		assertThrows(IllegalArgumentException.class, () -> n.piece(9));
	}

	// ------------------------------------------------------------------ 무늬

	@Test
	void marksAreDistinctAndStayInsideTheFace() {
		for (int face : new int[] { 8, 12, 18, 30 }) {
			List<boolean[]> masks = new ArrayList<>();
			for (int code = 1; code < ColorPalettes.GARBAGE; code++) {
				Shape s = ColorPalettes.markShape(code, face);
				assertNotNull(s, "무늬 " + code);
				Rectangle2D b = s.getBounds2D();
				assertTrue(b.getMinX() >= 0 && b.getMinY() >= 0 && b.getMaxX() <= face && b.getMaxY() <= face,
						"면 안에 들어간다: " + code + " " + face + " " + b);
				masks.add(mask(s, face));
			}
			for (int i = 0; i < masks.size(); i++) {
				for (int j = i + 1; j < masks.size(); j++) {
					int diff = 0;
					for (int p = 0; p < masks.get(i).length; p++) {
						if (masks.get(i)[p] != masks.get(j)[p]) {
							diff++;
						}
					}
					assertTrue(diff >= Math.max(4, face * face / 14), "무늬 " + (i + 1) + "·" + (j + 1) + " 가 다르다 (면 " + face
							+ ", 다른 화소 " + diff + ")");
				}
			}
		}
		assertNull(ColorPalettes.markShape(ColorPalettes.GARBAGE, 20), "방해 줄에는 무늬가 없다");
		assertNull(ColorPalettes.markShape(3, 4), "너무 작으면 없다");
	}

	@Test
	void patternsAreDrawnOnlyWhenOnAndOnLargeEnoughBlocks() {
		for (boolean cbOn : new boolean[] { false, true }) {
			ColorPalettes.Look plain = ColorPalettes.look(cbOn, false);
			ColorPalettes.Look marked = ColorPalettes.look(cbOn, true);
			assertTrue(marked.patterns());
			for (int code = 1; code < ColorPalettes.GARBAGE; code++) {
				int c = code;
				BufferedImage a = draw(24, g -> plain.block(g, 3, 3, 24, c, 255, false));
				BufferedImage b = draw(24, g -> marked.block(g, 3, 3, 24, c, 255, false));
				assertTrue(differing(a, b) > 8, "무늬가 보인다: " + code);
				assertEquals(0, footprintDiff(a, b), "무늬는 블록 밖으로 나가지 않는다: " + code);
				int small = ColorPalettes.MIN_MARK_SIZE - 1;
				assertEquals(0, differing(draw(small, g -> plain.block(g, 3, 3, small, c, 255, false)),
						draw(small, g -> marked.block(g, 3, 3, small, c, 255, false))), "작은 블록에는 무늬가 없다");
			}
			int garbage = ColorPalettes.GARBAGE;
			assertEquals(0, differing(draw(24, g -> plain.block(g, 3, 3, 24, garbage, 255, false)),
					draw(24, g -> marked.block(g, 3, 3, 24, garbage, 255, false))), "방해 줄에는 무늬가 없다");
		}
	}

	@Test
	void marksHaveEnoughContrastOnEveryColour() {
		for (ColorPalettes.Palette p : new ColorPalettes.Palette[] { ColorPalettes.Palette.NORMAL,
				ColorPalettes.Palette.COLOR_BLIND }) {
			for (int code = 1; code <= ColorPalettes.GARBAGE; code++) {
				double c = ColorPalettes.contrast(p.mark(code), p.piece(code));
				assertTrue(c >= 2.2, p + " " + code + ": 무늬 대비 " + c);
			}
		}
	}

	@Test
	void grainColours() {
		for (boolean cbOn : new boolean[] { false, true }) {
			ColorPalettes.Look plain = ColorPalettes.look(cbOn, false);
			ColorPalettes.Look marked = ColorPalettes.look(cbOn, true);
			assertNull(plain.grain(0, 2, false, false), "빈칸");
			for (int code = 1; code <= ColorPalettes.SAND_COLORS; code++) {
				Color base = plain.sand(code);
				for (int shade = 0; shade < ColorPalettes.SHADES; shade++) {
					Color c = plain.grain(code, shade, false, false);
					double d = Cvd.deltaE2000(Cvd.lab(c), Cvd.lab(base));
					assertTrue(d < 10, "명암은 같은 색으로 보일 만큼 작다: " + code + " " + shade + " (" + d + ")");
					assertEquals(c, plain.grain(code, shade, false, true), "무늬를 끄면 무늬 표시는 색을 바꾸지 않는다");
					assertNotEquals(c, marked.grain(code, shade, false, true), "무늬를 켜면 무늬 알갱이가 다르다");
					assertEquals(c, marked.grain(code, shade, false, false));
					Color flash = plain.grain(code, shade, true, false);
					assertTrue(ColorPalettes.luminance(flash) > ColorPalettes.luminance(c), "반짝이면 밝다");
				}
				assertNotEquals(plain.grain(code, 0, false, false), plain.grain(code, 3, false, false));
			}
			assertThrows(IllegalArgumentException.class, () -> plain.grain(5, 0, false, false));
		}
		assertEquals(ColorPalettes.Palette.COLOR_BLIND, ColorPalettes.look(UserSettings.defaults().withColorBlind(true))
				.palette());
		assertTrue(ColorPalettes.look(UserSettings.defaults().withPatterns(true)).patterns());
	}

	@Test
	void sandCellsHaveGrainTextureAndPatternsOnlyWhenOn() {
		for (boolean cbOn : new boolean[] { false, true }) {
			ColorPalettes.Look plain = ColorPalettes.look(cbOn, false);
			ColorPalettes.Look marked = ColorPalettes.look(cbOn, true);
			for (SandColor col : SandColor.values()) {
				BufferedImage a = draw(32, g -> plain.sandCell(g, 3, 3, 32, col.code(), 255, col::pattern));
				BufferedImage b = draw(32, g -> marked.sandCell(g, 3, 3, 32, col.code(), 255, col::pattern));
				BufferedImage none = draw(32, g -> marked.sandCell(g, 3, 3, 32, col.code(), 255, null));
				int bg = Theme.BOARD.getRGB();
				for (int y = 0; y < a.getHeight(); y++) {
					for (int x = 0; x < a.getWidth(); x++) {
						boolean inside = x >= 3 && x < 35 && y >= 3 && y < 35;
						assertEquals(inside, a.getRGB(x, y) != bg, "칸을 빈틈없이, 칸 안에만: " + x + "," + y);
					}
				}
				assertEquals(0, differing(a, none), "무늬 표시가 없으면 무늬를 켜도 같다");
				boolean hasPattern = col != SandColor.RED; // 빨강은 민무늬
				assertEquals(hasPattern, differing(a, b) > 0, col + ": 무늬를 켜면 무늬 알갱이가 보인다");
				assertTrue(colorsIn(a) >= 2, "알갱이 결이 있다");
			}
		}
	}

	// ------------------------------------------------------------------ 도우미

	private static int colorsIn(BufferedImage img) {
		java.util.Set<Integer> set = new java.util.HashSet<>();
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				set.add(img.getRGB(x, y));
			}
		}
		return set.size() - 1;
	}

	/** 판에 그리듯 어두운 바탕 위에 그린다 (블록 크기 + 6 화소 정사각형). */
	static BufferedImage draw(int size, java.util.function.Consumer<Graphics2D> paint) {
		BufferedImage img = new BufferedImage(size + 6, size + 6, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Theme.BOARD);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		paint.accept(g);
		g.dispose();
		return img;
	}

	static int differing(BufferedImage a, BufferedImage b) {
		int n = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					n++;
				}
			}
		}
		return n;
	}

	/** 바탕과 다른 화소(칠한 자리)가 서로 다른 수. */
	private static int footprintDiff(BufferedImage a, BufferedImage b) {
		int bg = Theme.BOARD.getRGB();
		int n = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if ((a.getRGB(x, y) != bg) != (b.getRGB(x, y) != bg)) {
					n++;
				}
			}
		}
		return n;
	}

	private static boolean[] mask(Shape s, int face) {
		BufferedImage img = new BufferedImage(face, face, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.WHITE);
		g.fill(s);
		g.dispose();
		boolean[] m = new boolean[face * face];
		for (int y = 0; y < face; y++) {
			for (int x = 0; x < face; x++) {
				m[y * face + x] = (img.getRGB(x, y) & 0xFF) > 127;
			}
		}
		return m;
	}

	/** 칸 값 1~8 의 색 가운데 가장 가까운 두 색의 CIEDE2000 차이 ({@code k} 가 null 이면 보통 눈). */
	private static double minDistance(ColorPalettes.Palette p, Cvd.Kind k) {
		List<Color> colors = new ArrayList<>();
		for (int code = 1; code <= ColorPalettes.GARBAGE; code++) {
			colors.add(p.piece(code));
		}
		return minDistance(colors, k);
	}

	static double minDistance(List<Color> colors, Cvd.Kind k) {
		double min = Double.MAX_VALUE;
		for (int i = 0; i < colors.size(); i++) {
			for (int j = i + 1; j < colors.size(); j++) {
				Color a = k == null ? colors.get(i) : Cvd.simulate(colors.get(i), k);
				Color b = k == null ? colors.get(j) : Cvd.simulate(colors.get(j), k);
				min = Math.min(min, Cvd.deltaE2000(Cvd.lab(a), Cvd.lab(b)));
			}
		}
		return min;
	}

	/**
	 * 색약 흉내 (Machado, Oliveira &amp; Fernandes 2009, 정도 1.0 = 이색형 색각)와 색 차이(CIEDE2000). 시험과 화면 확인
	 * 도구({@link ScreensPreview})가 쓴다.
	 */
	static final class Cvd {

		/** 적색약(1형), 녹색약(2형), 청색약(3형). */
		enum Kind {
			PROTAN("적색약", new double[][] { { 0.152286, 1.052583, -0.204868 }, { 0.114503, 0.786281, 0.099216 },
					{ -0.003882, -0.048116, 1.051998 } }),
			DEUTAN("녹색약", new double[][] { { 0.367322, 0.860646, -0.227968 }, { 0.280085, 0.672501, 0.047413 },
					{ -0.011820, 0.042940, 0.968881 } }),
			TRITAN("청색약", new double[][] { { 1.255528, -0.076749, -0.178779 }, { -0.078411, 0.930809, 0.147602 },
					{ 0.004733, 0.691367, 0.303900 } });

			final String label;
			final double[][] m;

			Kind(String label, double[][] m) {
				this.label = label;
				this.m = m;
			}
		}

		private Cvd() {
		}

		/** 색약인 사람에게 보이는 색 (선형 RGB 에서 행렬을 곱한다). */
		static Color simulate(Color c, Kind k) {
			double[] lin = { lin(c.getRed()), lin(c.getGreen()), lin(c.getBlue()) };
			int[] out = new int[3];
			for (int i = 0; i < 3; i++) {
				double v = k.m[i][0] * lin[0] + k.m[i][1] * lin[1] + k.m[i][2] * lin[2];
				out[i] = enc(Math.max(0, Math.min(1, v)));
			}
			return new Color(out[0], out[1], out[2], c.getAlpha());
		}

		/** 그림 전체를 흉내 낸 색으로 바꾼다. */
		static BufferedImage simulate(BufferedImage src, Kind k) {
			BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < src.getHeight(); y++) {
				for (int x = 0; x < src.getWidth(); x++) {
					out.setRGB(x, y, simulate(new Color(src.getRGB(x, y)), k).getRGB());
				}
			}
			return out;
		}

		private static double lin(int v) {
			double c = v / 255.0;
			return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
		}

		private static int enc(double v) {
			double c = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
			return (int) Math.round(Math.max(0, Math.min(1, c)) * 255);
		}

		/** CIE L*a*b* (D65). */
		static double[] lab(Color c) {
			double r = lin(c.getRed());
			double g = lin(c.getGreen());
			double b = lin(c.getBlue());
			double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
			double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
			double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
			double fx = f(x);
			double fy = f(y);
			double fz = f(z);
			return new double[] { 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz) };
		}

		private static double f(double t) {
			return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
		}

		/** CIEDE2000 색 차이 (Sharma, Wu &amp; Dalal 2005). */
		static double deltaE2000(double[] l1, double[] l2) {
			double c1 = Math.hypot(l1[1], l1[2]);
			double c2 = Math.hypot(l2[1], l2[2]);
			double cb = (c1 + c2) / 2;
			double cb7 = Math.pow(cb, 7);
			double gfac = 0.5 * (1 - Math.sqrt(cb7 / (cb7 + Math.pow(25, 7))));
			double a1 = (1 + gfac) * l1[1];
			double a2 = (1 + gfac) * l2[1];
			double c1p = Math.hypot(a1, l1[2]);
			double c2p = Math.hypot(a2, l2[2]);
			double h1 = hue(a1, l1[2]);
			double h2 = hue(a2, l2[2]);
			double dl = l2[0] - l1[0];
			double dc = c2p - c1p;
			double dh;
			if (c1p * c2p == 0) {
				dh = 0;
			} else {
				dh = h2 - h1;
				if (dh > 180) {
					dh -= 360;
				} else if (dh < -180) {
					dh += 360;
				}
			}
			double dH = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dh / 2));
			double lbp = (l1[0] + l2[0]) / 2;
			double cbp = (c1p + c2p) / 2;
			double hbp;
			if (c1p * c2p == 0) {
				hbp = h1 + h2;
			} else if (Math.abs(h1 - h2) <= 180) {
				hbp = (h1 + h2) / 2;
			} else if (h1 + h2 < 360) {
				hbp = (h1 + h2 + 360) / 2;
			} else {
				hbp = (h1 + h2 - 360) / 2;
			}
			double t = 1 - 0.17 * Math.cos(Math.toRadians(hbp - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hbp))
					+ 0.32 * Math.cos(Math.toRadians(3 * hbp + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hbp - 63));
			double dth = 30 * Math.exp(-Math.pow((hbp - 275) / 25, 2));
			double cbp7 = Math.pow(cbp, 7);
			double rc = 2 * Math.sqrt(cbp7 / (cbp7 + Math.pow(25, 7)));
			double sl = 1 + 0.015 * Math.pow(lbp - 50, 2) / Math.sqrt(20 + Math.pow(lbp - 50, 2));
			double sc = 1 + 0.045 * cbp;
			double sh = 1 + 0.015 * cbp * t;
			double rt = -Math.sin(Math.toRadians(2 * dth)) * rc;
			return Math.sqrt(Math.pow(dl / sl, 2) + Math.pow(dc / sc, 2) + Math.pow(dH / sh, 2)
					+ rt * (dc / sc) * (dH / sh));
		}

		private static double hue(double a, double b) {
			double h = Math.toDegrees(Math.atan2(b, a));
			return h < 0 ? h + 360 : h;
		}
	}
}
