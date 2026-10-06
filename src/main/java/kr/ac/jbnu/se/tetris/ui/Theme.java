package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 화면 공통 모양 (docs/GAME.md 7장): 색, 글꼴, 블록 그리기, 둥근 카드와 빛 번짐. 메뉴와 대전 화면이 같은 모양을 쓴다.
 * 색과 글꼴 객체는 미리 만들어 두고 다시 쓴다(초당 60번 그리므로 그릴 때마다 만들지 않는다).
 */
final class Theme {

	// 바탕과 면
	static final Color BG_TOP = new Color(0x0E1122);
	static final Color BG_BOTTOM = new Color(0x191D35);
	static final Color SURFACE = new Color(0x1A1F35);
	static final Color SURFACE_HI = new Color(0x242A47);
	static final Color BORDER = new Color(0x2E3558);
	static final Color BOARD = new Color(0x090C18);
	static final Color GRID = new Color(0x161B30);

	// 글자
	static final Color TEXT = new Color(0xE9ECF5);
	static final Color TEXT_DIM = new Color(0xA3ABC6);
	static final Color TEXT_FAINT = new Color(0x6B7394);

	// 강조
	static final Color ACCENT = new Color(0x5B8CFF);
	static final Color ACCENT_2 = new Color(0x9B6BFF);
	static final Color P1 = new Color(0x4CC9F0);
	static final Color P2 = new Color(0xFF6B8B);
	static final Color DANGER = new Color(0xFF3B30); // 빨강은 위험에만 (AI 색 P2 와 구분되게)
	static final Color WARN = new Color(0xFFB547);
	static final Color OK = new Color(0x4ADE80);
	static final Color GOLD = new Color(0xFFD166);

	/** 블록 색. 인덱스는 칸 값(PieceType.code(): 1 = I … 7 = Z, 8 = 가비지). */
	private static final Color[] PIECE = { null, new Color(0x31C7EF), new Color(0x4F6CF7), new Color(0xFF9F43),
			new Color(0xFFD43B), new Color(0x51CF66), new Color(0xB266FF), new Color(0xFF5C6C), new Color(0x5A6178) };
	private static final Color[] LIGHT = new Color[PIECE.length];
	private static final Color[] DARK = new Color[PIECE.length];
	private static final Color GREY = new Color(0x4A5068);
	private static final Color GREY_LIGHT = mix(GREY, Color.WHITE, 0.25);
	private static final Color GREY_DARK = mix(GREY, Color.BLACK, 0.35);

	static {
		for (int i = 1; i < PIECE.length; i++) {
			LIGHT[i] = mix(PIECE[i], Color.WHITE, 0.38);
			DARK[i] = mix(PIECE[i], Color.BLACK, 0.38);
		}
	}

	private static final String FAMILY = pickFamily();
	private static final Map<Integer, Font> FONTS = new HashMap<>();

	private Theme() {
	}

	/** 한글이 잘 보이는 글꼴: 맑은 고딕이 있으면 그것, 없으면 Dialog. */
	private static String pickFamily() {
		try {
			String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
			for (String want : new String[] { "Malgun Gothic", "맑은 고딕", "Apple SD Gothic Neo", "NanumGothic" }) {
				if (Arrays.asList(names).contains(want)) {
					return want;
				}
			}
		} catch (RuntimeException e) {
			// 글꼴 목록을 못 읽는 환경이면 기본 글꼴
		}
		return Font.DIALOG;
	}

	/** 크기(px)와 굵기로 글꼴을 돌려준다. 같은 조합은 한 번만 만든다. */
	static Font font(double size, boolean bold) {
		int px = (int) Math.max(9, Math.round(size));
		int key = px * 2 + (bold ? 1 : 0);
		synchronized (FONTS) {
			return FONTS.computeIfAbsent(key, k -> new Font(FAMILY, bold ? Font.BOLD : Font.PLAIN, px));
		}
	}

	static Color piece(int code) {
		return PIECE[code];
	}

	static Color withAlpha(Color c, int alpha) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, alpha)));
	}

	static Color mix(Color a, Color b, double t) {
		return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
				(int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				(int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	static void antialias(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
	}

	private static BufferedImage bgImage;

	/** 화면 바탕을 그린다. 같은 크기면 한 번 그려 둔 그림을 다시 쓴다(대전 화면은 초당 60번 그린다). */
	static void backgroundCached(Graphics2D g, int w, int h) {
		if (w <= 0 || h <= 0) {
			return;
		}
		BufferedImage img;
		synchronized (Theme.class) {
			if (bgImage == null || bgImage.getWidth() != w || bgImage.getHeight() != h) {
				bgImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
				Graphics2D bg = bgImage.createGraphics();
				background(bg, w, h);
				bg.dispose();
			}
			img = bgImage;
		}
		g.drawImage(img, 0, 0, null);
	}

	/** 화면 바탕: 위아래 그라데이션과 옅은 격자 점. */
	static void background(Graphics2D g, int w, int h) {
		g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, h, BG_BOTTOM));
		g.fillRect(0, 0, w, h);
		g.setColor(new Color(255, 255, 255, 9));
		int step = Math.max(24, Math.min(w, h) / 24);
		for (int y = step / 2; y < h; y += step) {
			for (int x = step / 2; x < w; x += step) {
				g.fillRect(x, y, 2, 2);
			}
		}
	}

	/**
	 * 블록 한 칸: 바탕색에 위·왼쪽은 밝게, 아래·오른쪽은 어둡게 테를 둘러 입체감을 준다. {@code grey} 면 회색(쓴 홀드 등).
	 */
	static void block(Graphics2D g, int x, int y, int size, int code, int alpha, boolean grey) {
		Color base = grey ? GREY : PIECE[code];
		Color light = grey ? GREY_LIGHT : LIGHT[code];
		Color dark = grey ? GREY_DARK : DARK[code];
		int gap = size >= 14 ? 1 : 0;
		int s = size - 2 * gap;
		int b = Math.max(2, s / 7);
		int x0 = x + gap;
		int y0 = y + gap;
		if (alpha >= 255) {
			g.setColor(dark);
			g.fillRect(x0, y0, s, s);
			g.setColor(light);
			g.fillRect(x0, y0, s - b, s - b);
			g.setColor(base);
			g.fillRect(x0 + b, y0 + b, s - 2 * b, s - 2 * b);
		} else {
			g.setColor(withAlpha(dark, alpha));
			g.fillRect(x0, y0, s, s);
			g.setColor(withAlpha(light, alpha));
			g.fillRect(x0, y0, s - b, s - b);
			g.setColor(withAlpha(base, alpha));
			g.fillRect(x0 + b, y0 + b, s - 2 * b, s - 2 * b);
		}
	}

	/** 고스트(하드드롭하면 놓일 자리): 옅게 채우고 테두리만 또렷하게. */
	static void ghost(Graphics2D g, int x, int y, int size, int code) {
		Color c = PIECE[code];
		g.setColor(withAlpha(c, 38));
		g.fillRect(x + 2, y + 2, size - 4, size - 4);
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(Math.max(1.5f, size / 14f)));
		g.setColor(withAlpha(mix(c, Color.WHITE, 0.4), 210)); // 어두운 보드 위에서도 또렷하게
		g.drawRect(x + 2, y + 2, size - 5, size - 5);
		g.setStroke(old);
	}

	/** 둥근 카드: 면 색으로 채우고 얇은 테두리. */
	static void card(Graphics2D g, double x, double y, double w, double h, double r, Color fill, Color border) {
		RoundRectangle2D.Double shape = new RoundRectangle2D.Double(x, y, w, h, r, r);
		g.setColor(fill);
		g.fill(shape);
		if (border != null) {
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(1.2f));
			g.setColor(border);
			g.draw(shape);
			g.setStroke(old);
		}
	}

	/** 빛 번짐: 둥근 사각형 바깥으로 점점 옅어지는 테두리 몇 겹. {@code strength} 는 0~1. */
	static void glow(Graphics2D g, double x, double y, double w, double h, double r, Color c, double strength) {
		Stroke old = g.getStroke();
		for (int i = 4; i >= 1; i--) {
			g.setStroke(new BasicStroke(i * 2.4f));
			g.setColor(withAlpha(c, (int) (strength * (70 - i * 14))));
			g.draw(new RoundRectangle2D.Double(x, y, w, h, r, r));
		}
		g.setStroke(new BasicStroke(1.6f));
		g.setColor(withAlpha(c, (int) (strength * 220)));
		g.draw(new RoundRectangle2D.Double(x, y, w, h, r, r));
		g.setStroke(old);
	}

	/** 작은 알약 모양 꼬리표(난이도, 페르소나 등). 너비를 돌려준다. */
	static int chip(Graphics2D g, String text, int x, int baselineY, double fontPx, Color fg, Color bg) {
		g.setFont(font(fontPx, true));
		FontMetrics fm = g.getFontMetrics();
		int padX = (int) Math.round(fontPx * 0.6);
		int w = fm.stringWidth(text) + 2 * padX;
		int h = (int) Math.round(fontPx * 1.55);
		int y = baselineY - fm.getAscent() - (h - fm.getHeight()) / 2;
		card(g, x, y, w, h, h, bg, null);
		g.setColor(fg);
		g.drawString(text, x + padX, baselineY);
		return w;
	}

	static void centered(Graphics2D g, String s, double cx, double baseline) {
		FontMetrics fm = g.getFontMetrics();
		g.drawString(s, (float) (cx - fm.stringWidth(s) / 2.0), (float) baseline);
	}

	static void right(Graphics2D g, String s, double rx, double baseline) {
		FontMetrics fm = g.getFontMetrics();
		g.drawString(s, (float) (rx - fm.stringWidth(s)), (float) baseline);
	}
}
