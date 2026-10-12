package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 글자 크기표와 색 견본을 창 없이 PNG 로 그린다 (runs/quality/SPEC.md V1·V2). 글꼴·크기·대비를 눈으로 확인하는 도구다.
 * <ul>
 * <li>{@code type-scale.png}: 글자 역할 다섯 가지(DISPLAY·TITLE·BODY·LABEL·NUMERIC)를 대전 화면 크기(칸 c = 24, 36)와 메뉴 크기(px)로,
 * 고정 폭 숫자와 글리프 규칙(한글은 Pretendard 로)도 함께.</li>
 * <li>{@code tokens.png}: 색 토큰 견본, 글자색 × 바탕색의 대비(WCAG), 예전과 새 격자선·테두리.</li>
 * </ul>
 * 실행: {@code gradlew lookSpecimen} (build/look-foundation), 또는 시험 클래스까지 빌드한 뒤 {@code java "-Djava.awt.headless=true"
 * "-Dtetris.sound=off" "-Dtetris.config=off" "-Dtetris.records=off" -cp
 * "build/classes/java/main;build/classes/java/test;build/resources/main;<junit-platform-launcher jar>"
 * kr.ac.jbnu.se.tetris.ui.ThemeSpecimen [출력 폴더]} (기본 build/look-foundation, Windows 는 경로 구분이 {@code ;}, PowerShell 에서는
 * {@code -D} 를 따옴표로 감싼다). 시작할 때 {@code TestSandbox.apply()} 를 불러 IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람
 * 판 기록을 건드리지 않는다(준 값은 덮지 않는다). 그 클래스가 JUnit 의 LauncherSessionListener 를 구현하므로 {@code java} 로 돌릴
 * 때는 junit-platform-launcher jar 가 클래스 경로에 있어야 한다(Gradle 작업과 IDE 는 시험 클래스 경로라 이미 있다).
 */
public final class ThemeSpecimen {

	/** 예전 값 (V1 전): 견본에서 비교로만 쓴다. */
	static final Color OLD_GRID = new Color(0x161B30);
	static final Color OLD_BORDER = new Color(0x2E3558);

	public static void main(String[] args) throws IOException {
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않게
		File out = new File(args.length > 0 ? args[0] : "build/look-foundation");
		if (!out.isDirectory() && !out.mkdirs()) {
			throw new IOException("폴더를 만들 수 없음: " + out);
		}
		ImageIO.write(typeScale(), "png", new File(out, "type-scale.png"));
		ImageIO.write(tokens(), "png", new File(out, "tokens.png"));
		System.out.println("그림: " + new File(out, "type-scale.png").getPath() + ", " + new File(out, "tokens.png").getPath()
				+ (Theme.bundledFonts() ? "" : " (시스템 글꼴)"));
	}

	// ------------------------------------------------------------------ 글자 크기표

	static BufferedImage typeScale() {
		BufferedImage img = new BufferedImage(1600, 1640, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		Theme.background(g, img.getWidth(), img.getHeight());
		heading(g, "글자 역할 (Theme.Type): 대전 화면, 칸 c = 24 (1280 × 720)", 30, 40);
		roles(g, 24, 30, 60, 740);
		heading(g, "칸 c = 36 (1920 × 1080)", 760, 40);
		roles(g, 36, 760, 60, 1570);

		heading(g, "통계 카드 (라벨 0.5c LABEL, 값 1.0c NUMERIC, 카드 높이 2.3c)", 30, 900);
		stats(g, 24, 30, 920);
		stats(g, 36, 430, 920);

		heading(g, "고정 폭 숫자 (오른쪽 정렬)", 30, 1080);
		tabular(g, 30, 1100);

		heading(g, "글리프 규칙: Barlow 에 없는 글자는 같은 크기의 Pretendard Bold", 760, 1080);
		glyphRule(g, 760, 1100);

		heading(g, "메뉴 (1280 × 720 가상 화면, px)", 760, 1355);
		menu(g, 760, 1360);

		footer(g, 30, img.getHeight() - 20);
		g.dispose();
		return img;
	}

	private static void heading(Graphics2D g, String s, int x, int y) {
		g.setColor(Theme.WARN);
		g.setFont(Theme.font(15, true));
		g.drawString(s, x, y);
	}

	/** 역할마다 한 줄: 역할 이름, 크기, 실제 글꼴 이름, 보기 글자. 보기 글자는 {@code sampleX} 부터 (왼쪽 정렬). */
	private static void roles(Graphics2D g, int c, int x, int y, int maxX) {
		Object[][] rows = { { Theme.Type.DISPLAY, 1.35, "DOUBLE  TETRIS", Theme.TEXT },
				{ Theme.Type.DISPLAY, 2.4, "K.O.", Theme.DANGER }, { Theme.Type.DISPLAY, 4.2, "3", new Color(0xB3CCFA) },
				{ Theme.Type.DISPLAY, 1.3, "3 COMBO", Theme.GOLD }, { Theme.Type.TITLE, 2.2, "승리! 패배", Theme.OK },
				{ Theme.Type.TITLE, 0.62, "나 · AI · 사람 대 AI", Theme.P1 },
				{ Theme.Type.BODY, 0.46, "기본 2 + 콤보 2 + B2B 2 · 속도 2단계", Theme.LABEL },
				{ Theme.Type.LABEL, 0.5, "PPS  APP  T-SPIN  HOLD  NEXT", Theme.LABEL },
				{ Theme.Type.LABEL, 0.5, "공격  보냄  콤보", Theme.LABEL },
				{ Theme.Type.NUMERIC, 1.0, "1.07  17  16  0.53  0", Theme.TEXT },
				{ Theme.Type.NUMERIC, 1.15, "0:37  12:05", Theme.TEXT } };
		int yy = y;
		int sampleX = x + 220;
		for (Object[] r : rows) {
			Theme.Type t = (Theme.Type) r[0];
			double k = (Double) r[1];
			String s = (String) r[2];
			double px = k * c;
			Font f = t.fit(px, s);
			java.awt.FontMetrics fm = g.getFontMetrics(f);
			int rowH = Math.max(40, fm.getAscent() + fm.getDescent() + 6);
			g.setColor(Theme.TEXT_DIM);
			g.setFont(Theme.font(12, true));
			g.drawString(t + " " + fmt(k) + "c = " + Math.round(px) + "px", x, yy + 16);
			g.setColor(Theme.LABEL);
			g.setFont(Theme.font(11, false));
			g.drawString(f.getFontName() + (f.hasLayoutAttributes() ? " · 자간 " + t.tracking() + "em" : ""), x, yy + 31);
			g.setColor((Color) r[3]);
			t.draw(g, s, px, sampleX, yy + fm.getAscent() + 2, Theme.Align.LEFT);
			g.setColor(Theme.withAlpha(Theme.BORDER, 120));
			g.drawLine(x, yy + rowH, maxX, yy + rowH);
			yy += rowH + 4;
		}
	}

	/** 통계 카드: 2.3c 높이, 다섯 칸. 라벨 기준선 y + 0.72c, 값 기준선 y + 1.92c (V2). */
	private static void stats(Graphics2D g, int c, int x, int y) {
		String[][] cols = { { "PPS", "1.07" }, { "공격", "17" }, { "보냄", "16" }, { "APP", "0.53" }, { "T-SPIN", "0" } };
		double colW = 1.9 * c;
		double w = colW * cols.length + 0.4 * c;
		Theme.card(g, x, y, w, 2.3 * c, 0.4 * c, Theme.SURFACE, Theme.BORDER);
		for (int i = 0; i < cols.length; i++) {
			double cx = x + 0.2 * c + colW * (i + 0.5);
			g.setColor(Theme.LABEL);
			Theme.Type.LABEL.draw(g, cols[i][0], 0.5 * c, cx, y + 0.72 * c, Theme.Align.CENTER);
			g.setColor(Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, cols[i][1], 1.0 * c, cx, y + 1.92 * c, Theme.Align.CENTER);
		}
		g.setColor(Theme.LABEL);
		g.setFont(Theme.font(11, false));
		g.drawString("c = " + c, x, (int) (y + 2.3 * c + 16));
	}

	/** 바뀌는 시계: 고정 폭(drawNumber)은 자리마다 세로로 맞고, 보통 그리기(drawString)는 흔들린다. */
	private static void tabular(Graphics2D g, int x, int y) {
		String[] values = { "0:07", "0:11", "1:48", "10:00", "0.53", "11.11", "88.88" };
		Font f = Theme.numeric(1.15 * 36);
		int rx1 = x + 200;
		int rx2 = x + 480;
		int lineH = 44;
		g.setColor(Theme.LABEL);
		g.setFont(Theme.font(13, true));
		g.drawString("drawNumber (고정 폭)", x + 40, y + 14);
		g.drawString("drawString (보통)", x + 340, y + 14);
		g.setFont(f);
		int digit = 0;
		for (char ch = '0'; ch <= '9'; ch++) {
			digit = Math.max(digit, g.getFontMetrics().charWidth(ch));
		}
		// 숫자 칸 경계 (고정 폭이면 위아래 숫자가 이 선 사이에 맞는다)
		g.setStroke(new BasicStroke(1f));
		g.setColor(Theme.withAlpha(Theme.ACCENT, 110));
		for (int k = 0; k <= 2; k++) {
			g.drawLine(rx1 - k * digit, y + 24, rx1 - k * digit, y + 30 + lineH * values.length);
		}
		for (int i = 0; i < values.length; i++) {
			int base = y + 62 + i * lineH;
			g.setFont(f);
			g.setColor(Theme.TEXT);
			Theme.drawNumber(g, values[i], rx1, base, Theme.Align.RIGHT);
			g.setColor(Theme.TEXT_DIM);
			Theme.right(g, values[i], rx2, base);
		}
		g.setColor(Theme.LABEL);
		g.setFont(Theme.font(12, false));
		g.drawString("숫자 칸 너비 " + digit + "px: " + f.getFontName() + " " + f.getSize() + "px 의 '0'~'9' 중 가장 넓은 숫자", x,
				y + 50 + lineH * values.length);
	}

	private static void glyphRule(Graphics2D g, int x, int y) {
		Object[][] rows = { { Theme.Type.DISPLAY, "승리!" }, { Theme.Type.DISPLAY, "K.O. ×2 – 58%" },
				{ Theme.Type.LABEL, "← ↑ → ↓ ▲" }, { Theme.Type.LABEL, "HOLD" }, { Theme.Type.NUMERIC, "+N ×3 · 1–2" } };
		int yy = y;
		for (Object[] r : rows) {
			Theme.Type t = (Theme.Type) r[0];
			String s = (String) r[1];
			Font f = t.fit(30, s);
			g.setColor(Theme.TEXT);
			t.draw(g, s, 30, x, yy + 32, Theme.Align.LEFT);
			g.setColor(Theme.LABEL);
			g.setFont(Theme.font(12, false));
			g.drawString(t + " → " + f.getFontName(), x + 330, yy + 26);
			yy += 44;
		}
	}

	private static void menu(Graphics2D g, int x, int y) {
		Font title = Theme.tracked(Theme.display(72), 0.04);
		g.setFont(title);
		g.setColor(Theme.TEXT);
		float w = Theme.text(g, "TETRIS ", x, y + 64, Theme.Align.LEFT);
		float bw = Theme.textWidth(g, "BATTLE");
		g.setPaint(new GradientPaint(x + w, 0, Theme.P1, x + w + bw, 0, Theme.ACCENT_2));
		Theme.text(g, "BATTLE", x + w, y + 64, Theme.Align.LEFT);
		Theme.card(g, x, y + 92, 420, 70, 16, new Color(0x3A1E2B), null);
		g.setColor(new Color(0xFFC2D2));
		Theme.Type.TITLE.draw(g, "사람 대 AI", 19, x + 22, y + 92 + 30, Theme.Align.LEFT);
		g.setColor(new Color(0xD48AA0));
		Theme.Type.BODY.draw(g, "학습한 AI와 실시간으로 겨뤄요", 14, x + 22, y + 92 + 54, Theme.Align.LEFT);
		g.setColor(Theme.LABEL);
		Theme.Type.BODY.draw(g, "설정 설명 13px: 줄 지우기·충격 효과의 세기예요", 13, x, y + 190, Theme.Align.LEFT);
		g.setColor(Theme.TEXT_DIM);
		Theme.Type.LABEL.draw(g, "LEVEL 3 · 페르소나", 12, x, y + 212, Theme.Align.LEFT);
	}

	private static void footer(Graphics2D g, int x, int y) {
		g.setColor(Theme.LABEL);
		g.setFont(Theme.font(12, false));
		g.drawString("글꼴: " + (Theme.bundledFonts() ? "함께 배포한 글꼴 (Pretendard 1.3.9, Barlow Condensed 1.408, SIL OFL 1.1)"
				: "시스템 글꼴 (함께 배포한 글꼴을 읽지 못함)") + " · " + Theme.font(12, false).getFontName() + " / "
				+ Theme.font(12, true).getFontName() + " / " + Theme.numeric(12).getFontName() + " / "
				+ Theme.display(12).getFontName(), x, y);
	}

	// ------------------------------------------------------------------ 색 견본

	static BufferedImage tokens() {
		BufferedImage img = new BufferedImage(1600, 980, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		Theme.background(g, img.getWidth(), img.getHeight());
		heading(g, "색 토큰 (Theme)", 30, 40);
		Object[][] sw = { { "BG_TOP", Theme.BG_TOP }, { "BG_BOTTOM", Theme.BG_BOTTOM }, { "SURFACE", Theme.SURFACE },
				{ "SURFACE_HI", Theme.SURFACE_HI }, { "BOARD", Theme.BOARD }, { "BORDER", Theme.BORDER },
				{ "GRID", Theme.GRID }, { "TEXT", Theme.TEXT }, { "TEXT_DIM", Theme.TEXT_DIM }, { "LABEL", Theme.LABEL },
				{ "TEXT_FAINT", Theme.TEXT_FAINT }, { "ACCENT", Theme.ACCENT }, { "ACCENT_2", Theme.ACCENT_2 },
				{ "P1", Theme.P1 }, { "P2", Theme.P2 }, { "DANGER", Theme.DANGER }, { "WARN", Theme.WARN },
				{ "OK", Theme.OK }, { "GOLD", Theme.GOLD }, { "SAND", Theme.SAND } };
		for (int i = 0; i < sw.length; i++) {
			int x = 30 + (i % 10) * 154;
			int y = 60 + (i / 10) * 110;
			Color c = (Color) sw[i][1];
			Theme.card(g, x, y, 140, 60, 10, c, Theme.withAlpha(Color.WHITE, 40));
			g.setColor(Theme.TEXT);
			g.setFont(Theme.font(13, true));
			g.drawString((String) sw[i][0], x, y + 78);
			g.setColor(Theme.LABEL);
			g.setFont(Theme.numeric(13));
			g.drawString(hex(c), x, y + 94);
		}

		heading(g, "글자 대비 (WCAG 2.1, 작은 글자는 4.5:1 이상)", 30, 300);
		Object[][] fgs = { { "TEXT", Theme.TEXT }, { "TEXT_DIM", Theme.TEXT_DIM }, { "LABEL", Theme.LABEL },
				{ "TEXT_FAINT", Theme.TEXT_FAINT } };
		Object[][] bgs = { { "BG_TOP", Theme.BG_TOP }, { "SURFACE", Theme.SURFACE }, { "SURFACE_HI", Theme.SURFACE_HI },
				{ "BOARD", Theme.BOARD } };
		for (int j = 0; j < bgs.length; j++) {
			int x = 180 + j * 340;
			g.setColor(Theme.TEXT_DIM);
			g.setFont(Theme.font(13, true));
			g.drawString("바탕 " + bgs[j][0], x, 330);
		}
		for (int i = 0; i < fgs.length; i++) {
			int y = 345 + i * 70;
			g.setColor(Theme.TEXT_DIM);
			g.setFont(Theme.font(13, true));
			g.drawString((String) fgs[i][0], 30, y + 36);
			for (int j = 0; j < bgs.length; j++) {
				int x = 180 + j * 340;
				Color fg = (Color) fgs[i][1];
				Color bg = (Color) bgs[j][1];
				Theme.card(g, x, y, 320, 58, 10, bg, null);
				g.setColor(fg);
				Theme.Type.LABEL.draw(g, "PPS 공격 1.07", 12, x + 14, y + 24, Theme.Align.LEFT);
				g.setFont(Theme.font(15, false));
				g.drawString("설명 글자 15px", x + 14, y + 47);
				double ratio = contrast(fg, bg);
				g.setColor(ratio >= 4.5 ? Theme.OK : Theme.WARN);
				Theme.Type.NUMERIC.draw(g, String.format(Locale.ROOT, "%.2f:1", ratio), 22, x + 306, y + 36, Theme.Align.RIGHT);
			}
		}

		heading(g, "격자선: 예전 #161B30 (1.14:1) / 새 GRID #22242F (1.26:1)", 30, 660);
		grid(g, OLD_GRID, 30, 680);
		grid(g, Theme.GRID, 330, 680);
		heading(g, "카드 테두리: 예전 #2E3558 (1.37:1) / 새 BORDER #3E4774 (1.83:1)", 700, 660);
		Theme.card(g, 700, 690, 260, 120, 14, Theme.SURFACE, OLD_BORDER);
		Theme.card(g, 1000, 690, 260, 120, 14, Theme.SURFACE, Theme.BORDER);
		g.setColor(Theme.LABEL);
		Theme.Type.LABEL.draw(g, "HOLD", 0.5 * 24, 720, 720, Theme.Align.LEFT);
		Theme.Type.LABEL.draw(g, "HOLD", 0.5 * 24, 1020, 720, Theme.Align.LEFT);
		g.setColor(Theme.TEXT_FAINT);
		g.setFont(Theme.font(12, false));
		g.drawString("예전", 720, 800);
		g.drawString("새것", 1020, 800);

		g.setColor(Theme.LABEL);
		g.setFont(Theme.font(12, false));
		g.drawString(String.format(Locale.ROOT,
				"LABEL: SURFACE %.2f · BOARD %.2f · SURFACE_HI %.2f (SURFACE_HI 위에는 TEXT_DIM %.2f) · BORDER/SURFACE %.2f · GRID/BOARD %.2f",
				contrast(Theme.LABEL, Theme.SURFACE), contrast(Theme.LABEL, Theme.BOARD),
				contrast(Theme.LABEL, Theme.SURFACE_HI), contrast(Theme.TEXT_DIM, Theme.SURFACE_HI),
				contrast(Theme.BORDER, Theme.SURFACE), contrast(Theme.GRID, Theme.BOARD)), 30, 950);
		g.dispose();
		return img;
	}

	/** 보드 격자 한 조각 (10 × 7 칸, c = 24). */
	private static void grid(Graphics2D g, Color line, int x, int y) {
		int c = 24;
		g.setColor(Theme.BOARD);
		g.fillRect(x, y, 10 * c, 7 * c);
		g.setColor(line);
		for (int col = 1; col < 10; col++) {
			g.drawLine(x + col * c, y, x + col * c, y + 7 * c - 1);
		}
		for (int r = 1; r < 7; r++) {
			g.drawLine(x, y + r * c, x + 10 * c - 1, y + r * c);
		}
		for (int col = 0; col < 4; col++) {
			Theme.block(g, x + (3 + col) * c, y + 6 * c, c, 1 + col, 255, false);
		}
		Theme.block(g, x + 4 * c, y + 5 * c, c, 6, 255, false);
	}

	/** WCAG 2.1 대비 (1~21). */
	static double contrast(Color a, Color b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	/** WCAG 2.1 상대 휘도. */
	static double luminance(Color c) {
		return 0.2126 * channel(c.getRed()) + 0.7152 * channel(c.getGreen()) + 0.0722 * channel(c.getBlue());
	}

	private static double channel(int v) {
		double s = v / 255.0;
		return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
	}

	private static String hex(Color c) {
		return String.format(Locale.ROOT, "#%06X", c.getRGB() & 0xFFFFFF);
	}

	private static String fmt(double k) {
		return k == Math.rint(k) ? String.valueOf((int) k) : String.valueOf(k);
	}

	private ThemeSpecimen() {
	}
}
