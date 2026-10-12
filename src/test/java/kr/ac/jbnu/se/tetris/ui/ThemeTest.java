package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 화면 공통 모양 (SPEC V1·V2): 함께 배포한 글꼴을 클래스패스에서 읽고 한글은 Pretendard 로 그린다, 글자 역할, 고정 폭 숫자, 자간, 대비
 * 토큰, 예전 도우미가 그대로 동작한다.
 */
class ThemeTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	/** {@code -Dtetris.fonts=system} 으로 돌리면 글꼴 시험은 건너뛴다. */
	private static void assumeBundled() {
		Assumptions.assumeFalse("system".equalsIgnoreCase(System.getProperty(Theme.FONTS_PROPERTY, "")),
				"시스템 글꼴로 돌리는 중");
	}

	// ------------------------------------------------------------------ 글꼴

	@Test
	void bundledFontsAreLoadedFromTheClasspath() {
		assumeBundled();
		assertTrue(Theme.bundledFonts(), "src/main/resources/fonts/ 의 글꼴을 읽었다");
		assertEquals("Pretendard Regular", Theme.font(14, false).getFontName());
		assertEquals("Pretendard Bold", Theme.font(14, true).getFontName());
		assertEquals("Barlow Condensed Bold", Theme.numeric(14).getFontName());
		assertEquals("Barlow Condensed Black", Theme.display(14).getFontName());
		for (Font f : new Font[] { Theme.font(20, false), Theme.font(20, true), Theme.numeric(20), Theme.display(20) }) {
			assertEquals(Font.PLAIN, f.getStyle(), f.getFontName() + ": 굵은 파일을 그대로 쓴다 (한 번 더 굵게 하지 않음)");
		}
	}

	@Test
	void fontKeepsItsOldContract() {
		assertSame(Theme.font(14, true), Theme.font(13.6, true), "반올림한 크기가 같으면 같은 객체");
		assertEquals(14, Theme.font(13.6, true).getSize());
		assertEquals(Theme.MIN_PX, Theme.font(3, false).getSize(), "9 px 보다 작게 하지 않는다");
		assertEquals(Theme.MIN_PX, Theme.font(Double.NaN, false).getSize());
		assertEquals(Theme.MAX_PX, Theme.font(1e12, false).getSize());
		assertEquals(700, Theme.display(700).getSize(), "큰 글자도 기억한다");
		assertSame(Theme.display(700), Theme.display(700.2));
		assertNotEquals(Theme.font(20, false), Theme.font(20, true));
	}

	@Test
	void pretendardDrawsEveryHangulSyllableAndUiSymbol() {
		assumeBundled();
		Font regular = Theme.font(14, false);
		Font bold = Theme.font(14, true);
		for (char ch = '가'; ch <= '힣'; ch++) {
			assertTrue(regular.canDisplay(ch) && bold.canDisplay(ch), "한글 " + ch);
		}
		String symbols = "·×–…€¡←↑→↓▲%:.+";
		assertEquals(-1, regular.canDisplayUpTo(symbols));
		assertEquals(-1, bold.canDisplayUpTo(symbols));
		assertEquals(-1, Theme.numeric(14).canDisplayUpTo("0123456789%:.+-×·–"), "Barlow: 숫자와 기호");
		assertFalse(Theme.numeric(14).canDisplay('가'), "Barlow 에는 한글이 없다");
		assertFalse(Theme.display(14).canDisplay('←'), "Barlow 에는 화살표가 없다");
	}

	@Test
	void glyphRuleFallsBackToPretendardBold() {
		assumeBundled();
		Font d = Theme.display(30);
		assertSame(d, Theme.fit(d, "K.O. 58% ×2"), "Barlow 로 그릴 수 있으면 그대로");
		assertSame(Theme.font(30, true), Theme.fit(d, "승리!"), "한글이 섞이면 같은 크기의 Pretendard Bold");
		assertSame(Theme.font(30, true), Theme.fit(d, "← 이동"));
		assertSame(d, Theme.fit(d, null));
		Font label = Theme.Type.LABEL.px(12);
		assertTrue(label.hasLayoutAttributes(), "라벨은 자간 0.08em");
		assertSame(label, Theme.Type.LABEL.fit(12, "PPS"));
		Font korean = Theme.Type.LABEL.fit(12, "공격");
		assertSame(Theme.font(12, true), korean, "한글 라벨은 Pretendard Bold, 자간 없음");
		assertFalse(korean.hasLayoutAttributes());
	}

	@Test
	void koreanIsDrawnWithPretendard() throws Exception {
		assumeBundled();
		String s = "사람 대 AI · 혼자 연습 · 모래 모드 · 다시 하기";
		Font direct;
		try (InputStream in = new BufferedInputStream(Theme.class.getResourceAsStream("/fonts/Pretendard-Bold.otf"))) {
			direct = Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.PLAIN, 24f);
		}
		BufferedImage theme = text(Theme.font(24, true), s);
		assertEquals(0, differing(theme, text(direct, s)), "Pretendard Bold 파일로 그린 것과 화소까지 같다");
		assertTrue(differing(theme, text(new Font(Font.DIALOG, Font.BOLD, 24), s)) > 100, "시스템 글꼴과는 다르다");
	}

	@Test
	void missingFontsFallBackWithOneWarning() {
		List<String> warnings = new ArrayList<>();
		assertNull(Theme.loadFaces("/no-such-fonts/", warnings::add));
		assertEquals(1, warnings.size(), "까닭을 한 번만 알린다");
		assertTrue(warnings.get(0).contains("/no-such-fonts/Pretendard-Regular.otf"), warnings.get(0));
		Consumer<String> fail = w -> {
			throw new AssertionError(w);
		};
		Font[] faces = Theme.loadFaces(Theme.FONT_DIR, fail);
		assertNotNull(faces, "배포한 글꼴은 경고 없이 읽힌다");
		assertEquals(Theme.Face.values().length, faces.length);
		assertEquals("Barlow Condensed Black", faces[Theme.Face.DISPLAY.ordinal()].getFontName());
	}

	// ------------------------------------------------------------------ 글자 역할

	@Test
	void typeRolesUseTheirFacesAndSizes() {
		assumeBundled();
		assertEquals("Barlow Condensed Black", Theme.Type.DISPLAY.px(40).getFontName());
		assertEquals("Pretendard Bold", Theme.Type.TITLE.px(40).getFontName());
		assertEquals("Pretendard Regular", Theme.Type.BODY.px(40).getFontName());
		assertEquals("Barlow Condensed Bold", Theme.Type.LABEL.px(40).getFontName());
		assertEquals("Barlow Condensed Bold", Theme.Type.NUMERIC.px(40).getFontName());
		assertEquals(0.08, Theme.Type.LABEL.tracking(), 1e-9);
		assertSame(Theme.Type.NUMERIC.px(12), Theme.Type.NUMERIC.c(24, 0.5), "칸 단위: 0.5c (c = 24) = 12 px");
		assertSame(Theme.Type.LABEL.px(18), Theme.Type.LABEL.c(36, 0.5));
		assertEquals(Theme.LABEL, Theme.Type.LABEL.color());
		assertEquals(Theme.TEXT_DIM, Theme.Type.BODY.color());
		assertEquals(Theme.TEXT, Theme.Type.DISPLAY.color());
		Graphics2D g = graphics(400, 100);
		float w = Theme.Type.NUMERIC.draw(g, "12:05", 30, 380, 60, Theme.Align.RIGHT);
		assertEquals(w, Theme.Type.NUMERIC.width(g, "12:05", 30), 1e-3, "width = draw 가 그린 너비");
		assertEquals(Theme.Type.NUMERIC.px(30), g.getFont(), "draw 는 고른 글꼴을 g 에 남긴다");
		g.dispose();
	}

	@Test
	void numbersUseTabularDigits() {
		Graphics2D g = graphics(600, 200);
		g.setFont(Theme.numeric(40));
		FontMetrics fm = g.getFontMetrics();
		int digit = 0;
		int narrow = Integer.MAX_VALUE;
		for (char ch = '0'; ch <= '9'; ch++) {
			digit = Math.max(digit, fm.charWidth(ch));
			narrow = Math.min(narrow, fm.charWidth(ch));
		}
		assertTrue(narrow < digit, "이 글꼴은 숫자마다 너비가 다르다");
		assertEquals(4 * digit, Theme.numberWidth(g, "1111"), 1e-3);
		assertEquals(Theme.numberWidth(g, "8888"), Theme.numberWidth(g, "1111"), 1e-3, "숫자가 바뀌어도 너비가 같다");
		assertEquals(Theme.numberWidth(g, "8:88"), Theme.numberWidth(g, "1:11"), 1e-3);
		assertTrue(Theme.textWidth(g, "1:11") < Theme.textWidth(g, "8:88"), "보통 그리기는 너비가 바뀐다");
		g.dispose();
		// 오른쪽 정렬한 "1" 과 "8" 은 같은 숫자 칸 가운데에 놓인다
		double c1 = centreX(draw(Theme.numeric(40), "1", true));
		double c8 = centreX(draw(Theme.numeric(40), "8", true));
		assertEquals(c8, c1, 1.5, "숫자 칸의 가운데");
		assertEquals(500 - digit / 2.0, c8, 2.0, "오른쪽 끝(500)에서 칸 반만큼");
	}

	@Test
	void textAlignsLeftCentreAndRight() {
		Font f = Theme.font(24, true);
		int[] left = bounds(drawAligned(f, "사람 대 AI", 300, Theme.Align.LEFT));
		int[] centre = bounds(drawAligned(f, "사람 대 AI", 300, Theme.Align.CENTER));
		int[] right = bounds(drawAligned(f, "사람 대 AI", 300, Theme.Align.RIGHT));
		assertEquals(300, left[0], 3, "왼쪽 정렬: 300 에서 시작");
		assertEquals(300, right[1], 3, "오른쪽 정렬: 300 에서 끝");
		assertEquals(300, (centre[0] + centre[1]) / 2.0, 3, "가운데 정렬");
	}

	@Test
	void trackingGoesBetweenLettersOnly() {
		Font base = Theme.numeric(30);
		Font t = Theme.tracked(base, 0.12);
		assertSame(t, Theme.tracked(base, 0.12), "같은 자간은 같은 객체");
		assertSame(t, Theme.tracked(base, 0.1204), "0.01em 단위로 반올림");
		assertSame(base, Theme.tracked(base, 0), "자간 0 은 그대로");
		Graphics2D g = graphics(400, 100);
		g.setFont(base);
		float plain = Theme.textWidth(g, "HOLD");
		g.setFont(t);
		float tracked = Theme.textWidth(g, "HOLD");
		g.dispose();
		assertEquals(plain + 3 * 0.12f * 30, tracked, 0.01, "글자 사이 세 곳에만 자간");
		int[] right = bounds(drawAligned(t, "HOLD", 300, Theme.Align.RIGHT));
		assertEquals(300, right[1], 3, "오른쪽 정렬이 마지막 글자 끝에 맞는다 (끝의 자간 없음)");
		// drawString(TextLayout)으로 그린 것과 글자 위치가 같다
		int[] mine = bounds(drawAligned(t, "HOLD", 40, Theme.Align.LEFT));
		BufferedImage img = new BufferedImage(400, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = img.createGraphics();
		Theme.antialias(g2);
		g2.setColor(Color.WHITE);
		g2.setFont(t);
		g2.drawString("HOLD", 40, 60);
		g2.dispose();
		int[] layout = bounds(img);
		assertEquals(layout[0], mine[0], 1);
		assertEquals(layout[1], mine[1], 1);
	}

	// ------------------------------------------------------------------ 색 토큰 (V1)

	@Test
	void contrastTokensMeetTheSpec() {
		assertEquals(0x8790B5, Theme.LABEL.getRGB() & 0xFFFFFF);
		assertEquals(5.18, ThemeSpecimen.contrast(Theme.LABEL, Theme.SURFACE), 0.01, "LABEL / SURFACE");
		assertEquals(6.21, ThemeSpecimen.contrast(Theme.LABEL, Theme.BOARD), 0.01, "LABEL / BOARD");
		assertTrue(ThemeSpecimen.contrast(Theme.LABEL, Theme.SURFACE_HI) < 4.5, "SURFACE_HI 위에서는 모자라므로 TEXT_DIM");
		assertTrue(ThemeSpecimen.contrast(Theme.TEXT_DIM, Theme.SURFACE_HI) >= 4.5);
		for (Color bg : new Color[] { Theme.BG_TOP, Theme.SURFACE, Theme.BOARD }) {
			assertTrue(ThemeSpecimen.contrast(Theme.LABEL, bg) >= 4.5, "작은 글자 기준 4.5:1");
			assertTrue(ThemeSpecimen.contrast(Theme.TEXT_DIM, bg) >= 4.5);
			assertTrue(ThemeSpecimen.contrast(Theme.TEXT, bg) >= 7);
		}
		assertEquals(3.49, ThemeSpecimen.contrast(Theme.TEXT_FAINT, Theme.SURFACE), 0.01, "TEXT_FAINT 는 그대로 (꺼진 항목에만)");
		assertEquals(Theme.mix(Theme.BOARD, Color.WHITE, 0.10).getRGB(), Theme.GRID.getRGB(), "GRID = BOARD 위 흰색 10%");
		assertEquals(1.26, ThemeSpecimen.contrast(Theme.GRID, Theme.BOARD), 0.01);
		assertEquals(0x3E4774, Theme.BORDER.getRGB() & 0xFFFFFF);
		assertEquals(1.83, ThemeSpecimen.contrast(Theme.BORDER, Theme.SURFACE), 0.01);
	}

	// ------------------------------------------------------------------ 예전 도우미

	@Test
	void oldHelpersStillDrawTheSame() {
		Font f = Theme.font(20, true);
		String s = "다시 하기 · 같은 판 다시";
		BufferedImage mine = new BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB);
		BufferedImage old = new BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB);
		Graphics2D a = mine.createGraphics();
		Graphics2D b = old.createGraphics();
		for (Graphics2D g : new Graphics2D[] { a, b }) {
			Theme.antialias(g);
			g.setFont(f);
			g.setColor(Color.WHITE);
		}
		Theme.centered(a, s, 200.5, 40);
		Theme.right(a, s, 390, 70);
		FontMetrics fm = b.getFontMetrics(); // 예전 계산 그대로
		b.drawString(s, (float) (200.5 - fm.stringWidth(s) / 2.0), 40f);
		b.drawString(s, (float) (390 - fm.stringWidth(s)), 70f);
		a.dispose();
		b.dispose();
		assertEquals(0, differing(mine, old), "centered·right 는 예전과 화소까지 같다");

		Graphics2D g = graphics(600, 200);
		assertTrue(Theme.chip(g, "최상급", 10, 50, 14, Theme.TEXT, Theme.SURFACE_HI) > 30);
		Theme.keys(g, new String[][] { { "Enter", "다시 하기" }, { "Esc", "메뉴로" } }, 300, 120, 24);
		Theme.glow(g, 20, 140, 200, 40, 40, Theme.ACCENT, 0.8);
		Theme.card(g, 10, 10, 50, 50, 10, Theme.SURFACE, Theme.BORDER);
		Theme.block(g, 100, 100, 24, 3, 255, false);
		Theme.ghost(g, 130, 100, 24, 3);
		g.dispose();
	}

	@Test
	void keysWidthIsTheWidthOfTheDrawnKeyRow() {
		// 결과 카드가 키 안내에 맞춰 너비를 정할 때 쓴다: 그린 줄의 왼쪽 끝(첫 키 상자)부터 오른쪽 끝(마지막 설명)까지
		String[][] keys = { { "R", "다시 하기" }, { "S", "같은 판 다시" }, { "Esc", "메뉴로" } };
		for (int c : new int[] { 18, 24, 36 }) {
			BufferedImage img = new BufferedImage(1600, 200, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			Theme.antialias(g);
			Font before = g.getFont();
			double w = Theme.keysWidth(g, keys, c);
			assertSame(before, g.getFont(), "g 의 글꼴은 바꾸지 않는다");
			Theme.keys(g, keys, 800, 100, c);
			g.dispose();
			int left = img.getWidth();
			int right = -1;
			for (int y = 0; y < img.getHeight(); y++) {
				for (int x = 0; x < img.getWidth(); x++) {
					if ((img.getRGB(x, y) & 0xFFFFFF) != 0) {
						left = Math.min(left, x);
						right = Math.max(right, x);
					}
				}
			}
			assertEquals(800 - w / 2, left, 1.5, "c = " + c + ": 첫 키 상자의 왼쪽 끝");
			assertTrue(right <= 800 + w / 2 + 1.5 && right >= 800 + w / 2 - 0.5 * c,
					"c = " + c + ": 마지막 설명의 끝 " + right + ", 너비의 끝 " + (800 + w / 2));
		}
	}

	@Test
	void specimenSheetsRenderHeadless() {
		BufferedImage type = ThemeSpecimen.typeScale();
		BufferedImage tokens = ThemeSpecimen.tokens();
		assertEquals(1600, type.getWidth());
		assertEquals(1600, tokens.getWidth());
	}

	// ------------------------------------------------------------------ 도우미

	private static Graphics2D graphics(int w, int h) {
		Graphics2D g = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).createGraphics();
		Theme.antialias(g);
		return g;
	}

	private static BufferedImage text(Font f, String s) {
		BufferedImage img = new BufferedImage(900, 60, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Color.WHITE);
		g.setFont(f);
		g.drawString(s, 10, 40);
		g.dispose();
		return img;
	}

	/** 숫자를 오른쪽 끝 500 에 맞춰 그린다. */
	private static BufferedImage draw(Font f, String s, boolean tabular) {
		BufferedImage img = new BufferedImage(600, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Color.WHITE);
		g.setFont(f);
		if (tabular) {
			Theme.drawNumber(g, s, 500, 70, Theme.Align.RIGHT);
		} else {
			Theme.text(g, s, 500, 70, Theme.Align.RIGHT);
		}
		g.dispose();
		return img;
	}

	private static BufferedImage drawAligned(Font f, String s, double x, Theme.Align align) {
		BufferedImage img = new BufferedImage(600, 100, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Color.WHITE);
		g.setFont(f);
		Theme.text(g, s, x, 60, align);
		g.dispose();
		return img;
	}

	/** 밝은 화소의 {가장 왼쪽 x, 가장 오른쪽 x + 1}. */
	private static int[] bounds(BufferedImage img) {
		int lo = Integer.MAX_VALUE;
		int hi = -1;
		for (int y = 0; y < img.getHeight(); y++) {
			for (int x = 0; x < img.getWidth(); x++) {
				if ((img.getRGB(x, y) & 0xFF) > 60) {
					lo = Math.min(lo, x);
					hi = Math.max(hi, x + 1);
				}
			}
		}
		assertTrue(hi > 0, "무언가 그렸다");
		return new int[] { lo, hi };
	}

	private static double centreX(BufferedImage img) {
		int[] b = bounds(img);
		return (b[0] + b[1]) / 2.0;
	}

	private static int differing(BufferedImage a, BufferedImage b) {
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
}
