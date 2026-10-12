package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 창과 작업 표시줄의 아이콘. 그림 파일 없이 코드로 그린다: 어두운 둥근 타일 위에 두 플레이어 색(P1 하늘색, P2 분홍)의 L 블록
 * 두 개가 대각선으로 마주 본다(2인 대전). 큰 아이콘에는 두 블록 사이에 보라색 빛줄기를 더한다.
 * <p>
 * 크기마다 따로 그린다({@link #SIZES}): 64 화소 이하는 칸 사이 틈과 입체 테를 빼고 블록을 한 덩어리로 화소에 맞춰
 * 또렷하게(위 테는 밝게, 아래 테는 어둡게), 128 화소 이상은 게임 블록과 같은 입체 모양에 빛줄기를 더한다. 붙이는 법:
 * {@code frame.setIconImages(AppIcon.images())}. 운영체제가 필요한 크기를 골라 쓴다(Windows 제목 표시줄 16, 작업 표시줄
 * 24·32·48, Alt+Tab 32~256). 창 없이(헤드리스)도 그릴 수 있다.
 */
final class AppIcon {

	/** 만들어 두는 크기 (화소). */
	static final int[] SIZES = { 16, 20, 24, 32, 40, 48, 64, 128, 256 };

	/** 4 × 4 칸에서 P1 블록의 칸 {열, 행}: 왼쪽 아래의 L. P2 는 이것을 180° 돌린 자리(오른쪽 위)다. */
	private static final int[][] P1_CELLS = { { 0, 2 }, { 0, 3 }, { 1, 3 }, { 2, 3 } };

	private static volatile List<Image> cached;

	private AppIcon() {
	}

	/** 모든 크기의 아이콘 (작은 것부터, 바꿀 수 없는 목록). 한 번 그린 것을 다시 준다. */
	static List<Image> images() {
		List<Image> c = cached;
		if (c == null) {
			List<Image> list = new ArrayList<>();
			for (int s : SIZES) {
				list.add(render(s));
			}
			c = Collections.unmodifiableList(list);
			cached = c;
		}
		return c;
	}

	/** 한 변이 {@code size} 화소인 아이콘을 새로 그린다 (투명한 바탕, ARGB). */
	static BufferedImage render(int size) {
		if (size < 8) {
			throw new IllegalArgumentException("아이콘은 8 화소 이상: " + size);
		}
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			paint(g, size);
		} finally {
			g.dispose();
		}
		return img;
	}

	private static void paint(Graphics2D g, int size) {
		boolean small = size <= 24;
		double inset = small ? 0 : size / 64.0;
		double arc = size * 0.44;
		RoundRectangle2D.Double tile = new RoundRectangle2D.Double(inset, inset, size - 2 * inset, size - 2 * inset, arc,
				arc);
		g.setPaint(new GradientPaint(0, 0, Theme.mix(Theme.SURFACE_HI, Theme.BORDER, 0.35), 0, size, Theme.BG_TOP));
		g.fill(tile);
		// 테두리: 어두운 작업 표시줄에서도 타일 모양이 보이게 바탕보다 밝게
		float edge = (float) Math.max(1.0, size / 40.0);
		g.setStroke(new BasicStroke(edge));
		g.setColor(Theme.withAlpha(Theme.mix(Theme.BORDER, Color.WHITE, 0.22), small ? 255 : 230));
		g.draw(new RoundRectangle2D.Double(inset + edge / 2, inset + edge / 2, size - 2 * inset - edge,
				size - 2 * inset - edge, arc - edge, arc - edge));

		int cell = (int) Math.round(size * 0.18);
		int grid = 4 * cell;
		int ox = (size - grid) / 2;
		int oy = (size - grid) / 2;
		if (size >= 128) {
			clash(g, ox, oy, grid, cell);
		}
		int[][] p2 = new int[P1_CELLS.length][];
		for (int i = 0; i < P1_CELLS.length; i++) {
			p2[i] = new int[] { 3 - P1_CELLS[i][0], 3 - P1_CELLS[i][1] };
		}
		boolean flat = size <= 64;
		piece(g, P1_CELLS, ox, oy, cell, Theme.P1, flat);
		piece(g, p2, ox, oy, cell, Theme.P2, flat);
	}

	/** 블록 하나 (칸 {열, 행} 목록). */
	private static void piece(Graphics2D g, int[][] cells, int ox, int oy, int cell, Color base, boolean flat) {
		for (int[] c : cells) {
			int x = ox + c[0] * cell;
			int y = oy + c[1] * cell;
			if (flat) {
				flatCell(g, x, y, cell, base, !has(cells, c[0], c[1] - 1), !has(cells, c[0], c[1] + 1));
			} else {
				cell(g, x, y, cell, base);
			}
		}
	}

	private static boolean has(int[][] cells, int col, int row) {
		for (int[] c : cells) {
			if (c[0] == col && c[1] == row) {
				return true;
			}
		}
		return false;
	}

	/** 작은 아이콘의 칸: 틈 없이 납작하게. 블록의 위쪽 끝은 한 줄 밝게, 6 화소 이상이면 아래쪽 끝은 한 줄 어둡게. */
	private static void flatCell(Graphics2D g, int x, int y, int s, Color base, boolean topEdge, boolean bottomEdge) {
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setColor(base);
		g.fillRect(x, y, s, s);
		int line = Math.max(1, s / 6);
		if (topEdge && s >= 3) {
			g.setColor(Theme.mix(base, Color.WHITE, 0.4));
			g.fillRect(x, y, s, line);
		}
		if (bottomEdge && s >= 6) {
			g.setColor(Theme.mix(base, Color.BLACK, 0.3));
			g.fillRect(x, y + s - line, s, line);
		}
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	/** 두 블록 사이 빈 대각선의 빛줄기 (큰 아이콘만): 두 플레이어가 맞부딪치는 자리. */
	private static void clash(Graphics2D g, int ox, int oy, int grid, int cell) {
		double a = ox + cell * 0.55;
		double b = ox + grid - cell * 0.55;
		double ya = oy + cell * 0.55;
		double yb = oy + grid - cell * 0.55;
		Line2D.Double line = new Line2D.Double(a, ya, b, yb);
		for (int i = 4; i >= 1; i--) {
			g.setStroke(new BasicStroke((float) (cell * 0.16 * i), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(Theme.withAlpha(Theme.ACCENT_2, 26 + (4 - i) * 14));
			g.draw(line);
		}
		g.setStroke(new BasicStroke((float) Math.max(1.0, cell * 0.09), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.withAlpha(Theme.mix(Theme.ACCENT_2, Color.WHITE, 0.55), 220));
		g.draw(line);
	}

	/** 큰 아이콘의 칸: 게임 블록처럼 틈과 입체 테 ({@code Theme.block} 과 같은 비율). */
	private static void cell(Graphics2D g, int x, int y, int s, Color base) {
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		int gap = Math.max(1, s / 12);
		int t = s - 2 * gap;
		int b = Math.max(2, t / 7);
		int x0 = x + gap;
		int y0 = y + gap;
		g.setColor(Theme.mix(base, Color.BLACK, 0.38));
		g.fillRect(x0, y0, t, t);
		g.setColor(Theme.mix(base, Color.WHITE, 0.38));
		g.fillRect(x0, y0, t - b, t - b);
		g.setColor(base);
		g.fillRect(x0 + b, y0 + b, t - 2 * b, t - 2 * b);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}
}
