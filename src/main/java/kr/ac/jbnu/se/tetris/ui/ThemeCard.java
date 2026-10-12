package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * 둥근 카드({@link Theme#card})를 미리 그려 둔 조각 그림으로 그린다. 빛 번짐({@link FxGlow})과 같은 방법이다.
 * <p>
 * 까닭: Java2D 는 안티앨리어싱 도형을 32 × 32 화소 타일로 나눠 타일마다 그리기 명령을 하나씩 낸다(속이 꽉 찬 안쪽 타일도). D3D
 * 에서는 화면을 덮는 큰 카드 하나가 수백 번의 명령이라, 메뉴와 대전 화면 한 장을 그리는 시간의 대부분이 카드였다(1920 × 1080 에서
 * 카드 한 번에 약 0.3ms).
 * <ul>
 * <li>모서리 넷만 예전 방법(면을 채우고 1.2 화소 테두리, 안티앨리어싱)으로 작은 그림에 한 번 그려 둔다. 곧은 가장자리는 어디를 잘라도
 * 같은 모양이므로 그림의 가운데 한 줄의 색을 기억해 두었다가, 같은 색이 이어지는 만큼씩 사각형으로 채운다. 안쪽은 면 색 사각형
 * 하나다(안티앨리어싱 없음). 그래서 그림 크기는 모서리의 둥글기에만 달려 있고 카드가 커도 작다.</li>
 * <li>장치 화소에서 그린다: 지금 변환(창 배율, 화면 배율)으로 실제 화소 위치를 계산하고 변환 없이 붙인다. 그림은 예전 그리기와 똑같은
 * 도형·선·변환을 정수 화소만큼만 옮겨 그린 것이라 화소 안의 위치(소수 부분)가 화면과 같다. Java2D 는 둥근 도형을 화소마다 세로 8줄로
 * 나눠 칠하므로 위치를 조금만 반올림해도 가장자리 한 줄이 통째로 1/8 만큼 달라지기 때문이다. 그래서 그림은 예전 그리기와 같다(합성의
 * 반올림만큼, 0~255 에서 몇 단계).</li>
 * <li>처음 그리는 카드도 그림을 만들어 붙인다: 한 카드는 언제나 같은 방법으로 그려지므로 멈춘 화면은 프레임마다 화소까지 같다
 * (바로 그린 것과 그림을 붙인 것은 합성의 반올림만큼 다를 수 있다). 움직이는 동안 위치·배율·색이 프레임마다 바뀌는 카드(나타나며
 * 커지는 카드, 깜빡이는 줄)는 그때마다 작은 그림을 새로 만든다(바로 그리는 것과 비슷한 비용).</li>
 * <li>회전·기울임·가로세로 배율이 다른 변환, SRC_OVER 가 아닌 합성, 안티앨리어싱을 켜지 않았거나 STROKE_PURE 가 아닌 그리기, 아주 큰
 * 모서리는 예전처럼 바로 그린다({@link Theme#cardDirect}).</li>
 * <li>부르는 쪽이 알파 합성(카드가 나타나며 옅게)을 걸어 두면 카드 전체를 한 덩어리로 옅게 한다. 예전에는 면과 테두리를 따로 옅게
 * 해서, 테두리가 면에 겹친 화소가 조금 달랐다.</li>
 * <li>최근에 쓴 {@value #CAPACITY}장까지 기억한다(화소 합계도 제한). 넘치면 가장 오래 안 쓴 것부터 버린다.</li>
 * </ul>
 * 붙인 뒤 g 의 색은 예전 그리기처럼 테두리 색(없으면 면 색)이다. 변환·합성·선·잘라내기는 그대로 둔다.
 */
final class ThemeCard {

	/** 기억하는 그림 수. */
	static final int CAPACITY = 160;
	/** 기억하는 그림의 화소 합계 (4바이트씩 약 6 MB). */
	private static final long PIXEL_BUDGET = 1_500_000;
	/** 그림 한 변의 최대 길이(화소). 넘으면 바로 그린다. */
	private static final int MAX_SIDE = 1024;
	/** 이보다 먼 좌표(화소)는 바로 그린다 (정수 넘침 방지). */
	private static final double MAX_COORD = 1 << 20;
	/** 테두리 굵기 (사용자 화소, {@link Theme#cardDirect} 와 같다). */
	static final float BORDER = 1.2f;
	private static final AffineTransform IDENTITY = new AffineTransform();

	/** 조각 그림을 쓰는가 (시험·비교 도구가 끄고 예전 그림과 견준다). */
	static volatile boolean enabled = true;

	private static final Entry[] CACHE = new Entry[CAPACITY];
	private static int count;
	private static long pixels;
	private static long tick;
	/** 이 스레드가 만든 그림 수 (시험용: 다른 스레드가 그리는 것과 섞이지 않게 스레드마다 센다). */
	private static final ThreadLocal<long[]> MADE = ThreadLocal.withInitial(() -> new long[1]);

	private ThemeCard() {
	}

	/**
	 * {@link Theme#card} 를 조각 그림으로 그린다. 그렸으면 true, 이 카드는 조각 그림으로 그리지 않으면(위 조건) 아무것도 하지 않고
	 * false: 부르는 쪽이 바로 그린다.
	 */
	static boolean draw(Graphics2D g, double x, double y, double w, double h, double r, Color fill, Color border) {
		if (!enabled || fill == null || !(w > 0 && h > 0)) {
			return false;
		}
		if (g.getRenderingHint(RenderingHints.KEY_ANTIALIASING) != RenderingHints.VALUE_ANTIALIAS_ON
				|| g.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL) != RenderingHints.VALUE_STROKE_PURE) {
			return false; // 안티앨리어싱 없는 카드는 바로 그려도 싸다
		}
		Composite comp = g.getComposite();
		AffineTransform t = g.getTransform();
		double s = t.getScaleX();
		if ((t.getType() & ~(AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE)) != 0 || !(s > 0)
				|| !(comp instanceof AlphaComposite ac) || ac.getRule() != AlphaComposite.SRC_OVER) {
			return false;
		}
		// 장치 화소의 사각형과 모서리
		double tx = t.getTranslateX();
		double ty = t.getTranslateY();
		double left = x * s + tx;
		double right = (x + w) * s + tx;
		double top = y * s + ty;
		double bottom = (y + h) * s + ty;
		double arcW = Math.min(Math.abs(r), w) * s; // RoundRectangle2D 처럼 크기를 넘는 둥글기는 자른다
		double arcH = Math.min(Math.abs(r), h) * s;
		if (!(Math.abs(left) < MAX_COORD && Math.abs(right) < MAX_COORD && Math.abs(top) < MAX_COORD
				&& Math.abs(bottom) < MAX_COORD && arcW < MAX_COORD && arcH < MAX_COORD && s < MAX_COORD)) {
			return false; // NaN 도 여기서 걸린다
		}
		int iL = (int) Math.floor(left);
		int iR = (int) Math.floor(right);
		int iT = (int) Math.floor(top);
		int iB = (int) Math.floor(bottom);
		double fL = left - iL;
		double fR = right - iR;
		double fT = top - iT;
		double fB = bottom - iB;
		int halfUp = (int) Math.ceil(BORDER * s / 2);
		int band = halfUp + 2; // 테두리의 절반 + 안티앨리어싱 1 + 여유 1
		// 모서리 조각: 둥근 부분과 테두리의 번짐을 다 담고, 그 너머(곧은 가장자리)는 어디를 잘라도 같은 모양
		int cw = band + Math.max((int) Math.ceil(arcW / 2), halfUp) + 3;
		int ch = band + Math.max((int) Math.ceil(arcH / 2), halfUp) + 3;
		int dw = iR - iL + 2 * band + 1;
		int dh = iB - iT + 2 * band + 1;
		boolean stretchX = dw >= 2 * cw + 1;
		boolean stretchY = dh >= 2 * ch + 1;
		int sw = stretchX ? 2 * cw + 1 : dw;
		int sh = stretchY ? 2 * ch + 1 : dh;
		if (sw > MAX_SIDE || sh > MAX_SIDE) {
			return false;
		}
		// 열쇠: 이 값들이 같으면 그림이 같다
		int fillArgb = fill.getRGB();
		int borderArgb = border == null ? 0 : border.getRGB();
		int flags = (stretchX ? 1 : 0) | (stretchY ? 2 : 0) | (border != null ? 4 : 0);
		long[] geo = { Double.doubleToLongBits(fL), Double.doubleToLongBits(fR), Double.doubleToLongBits(fT),
				Double.doubleToLongBits(fB), Double.doubleToLongBits(arcW), Double.doubleToLongBits(arcH),
				Double.doubleToLongBits(s) };
		Entry e = find(fillArgb, borderArgb, sw, sh, flags, geo);
		if (e == null) {
			BufferedImage img = render(g, t, iL - band, iT - band, dw, dh, stretchX ? cw : -1, stretchY ? ch : -1, sw, sh,
					new RoundRectangle2D.Double(x, y, w, h, r, r), fill, border);
			e = keep(new Entry(fillArgb, borderArgb, sw, sh, flags, geo, img, stretchX ? cw : -1, stretchY ? ch : -1,
					probe(g, fill)));
		}
		g.setTransform(IDENTITY);
		try {
			pieces(g, e, iL - band, iT - band, dw, dh, cw, ch, fill);
		} finally {
			g.setTransform(t);
			g.setColor(border != null ? border : fill); // 예전 그리기가 남기던 색
		}
		return true;
	}

	/** 조각을 붙인다: 모서리는 그림에서, 곧은 가장자리는 기억한 한 줄의 색으로 같은 색이 이어지는 만큼씩, 안쪽은 면 색으로 한 번에. */
	private static void pieces(Graphics2D g, Entry e, int dx, int dy, int dw, int dh, int cw, int ch, Color fill) {
		boolean stretchX = e.col != null;
		boolean stretchY = e.row != null;
		int nx = stretchX ? 3 : 1;
		int ny = stretchY ? 3 : 1;
		for (int j = 0; j < ny; j++) {
			int sy0 = start(j, stretchY, ch);
			int sy1 = end(j, stretchY, ch, e.sh);
			int dy0 = dy + startDst(j, stretchY, ch, dh);
			int dy1 = dy + endDst(j, stretchY, ch, dh);
			for (int i = 0; i < nx; i++) {
				boolean midX = stretchX && i == 1;
				boolean midY = stretchY && j == 1;
				int sx0 = start(i, stretchX, cw);
				int sx1 = end(i, stretchX, cw, e.sw);
				int dx0 = dx + startDst(i, stretchX, cw, dw);
				int dx1 = dx + endDst(i, stretchX, cw, dw);
				if (dx1 <= dx0 || dy1 <= dy0) {
					continue;
				}
				if (midX && midY) { // 안쪽: 면 색
					g.setColor(fill);
					g.fillRect(dx0, dy0, dx1 - dx0, dy1 - dy0);
				} else if (midX) { // 위·아래 가장자리 (또는 낮은 카드의 가운데): 가로로 긴 사각형
					e.col.fill(g, sy0, sy1, dy0, dx0, dx1 - dx0, false);
				} else if (midY) { // 왼쪽·오른쪽 가장자리: 세로로 긴 사각형
					e.row.fill(g, sx0, sx1, dx0, dy0, dy1 - dy0, true);
				} else {
					g.drawImage(e.img, dx0, dy0, dx1, dy1, sx0, sy0, sx1, sy1, null);
				}
			}
		}
	}

	/** 그림 안에서 조각 p 가 시작하는 곳: 0 = 앞 모서리, 1 = 가운데 한 줄, 2 = 뒤 모서리. 나누지 않으면 조각은 하나(전체). */
	private static int start(int p, boolean split, int corner) {
		return !split || p == 0 ? 0 : p == 1 ? corner : corner + 1;
	}

	private static int end(int p, boolean split, int corner, int size) {
		return !split ? size : p == 0 ? corner : p == 1 ? corner + 1 : size;
	}

	/** 화면에서 조각 p 가 시작하는 곳 (붙이는 자리의 왼쪽 위 기준). */
	private static int startDst(int p, boolean split, int corner, int dst) {
		return !split || p == 0 ? 0 : p == 1 ? corner : dst - corner;
	}

	private static int endDst(int p, boolean split, int corner, int dst) {
		return !split ? dst : p == 0 ? corner : p == 1 ? dst - corner : dst;
	}

	/**
	 * 예전 카드를 장치 화소로 그린 그림. 예전 그리기와 똑같이 사용자 좌표의 둥근 사각형과 1.2 화소 선을 지금 변환으로 그리되, 변환을
	 * 정수 화소만큼 옮겨 그림에 담는다(그래서 화소 안의 위치와 반올림이 화면과 같다). 늘여 붙이는 방향이면 앞쪽 모서리와 가운데 한 줄은
	 * 앞 끝에 맞춰, 뒤쪽 모서리는 뒤 끝에 맞춰 따로 옮겨 그린다.
	 *
	 * @param ox 그림의 왼쪽 위 화소가 화면에서 놓일 곳 (장치 화소)
	 * @param cw 가로로 나누면 모서리 너비, 아니면 -1 (ch 도 같다)
	 */
	private static BufferedImage render(Graphics2D g, AffineTransform t, int ox, int oy, int dw, int dh, int cw, int ch,
			int sw, int sh, RoundRectangle2D shape, Color fill, Color border) {
		BufferedImage img = Fx.sprite(g, sw, sh);
		Graphics2D sg = img.createGraphics();
		sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		BasicStroke stroke = new BasicStroke(BORDER);
		int[] xs = cw < 0 ? new int[] { 0, sw } : new int[] { 0, cw + 1, sw };
		int[] ys = ch < 0 ? new int[] { 0, sh } : new int[] { 0, ch + 1, sh };
		for (int j = 0; j + 1 < ys.length; j++) {
			for (int i = 0; i + 1 < xs.length; i++) {
				int shiftX = -ox - (i == 1 ? dw - sw : 0); // 뒤쪽 모서리는 화면의 뒤 끝에 맞춘다
				int shiftY = -oy - (j == 1 ? dh - sh : 0);
				AffineTransform at = AffineTransform.getTranslateInstance(shiftX, shiftY);
				at.concatenate(t);
				sg.setTransform(IDENTITY);
				sg.setClip(xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j]);
				sg.setTransform(at);
				sg.setColor(fill);
				sg.fill(shape);
				if (border != null) {
					sg.setStroke(stroke);
					sg.setColor(border);
					sg.draw(shape);
				}
			}
		}
		sg.dispose();
		return img;
	}

	/** 면 색 한 화소를 그림과 같은 형식에 칠하고 읽은 값: 가장자리 줄에서 이 값이면 면 색 그대로 칠한다(반올림 차이 없이). */
	private static int probe(Graphics2D g, Color fill) {
		BufferedImage one = Fx.sprite(g, 1, 1);
		Graphics2D pg = one.createGraphics();
		pg.setColor(fill);
		pg.fillRect(0, 0, 1, 1);
		pg.dispose();
		return one.getRGB(0, 0);
	}

	// ------------------------------------------------------------------ 가장자리 한 줄

	/** 그림의 가운데 한 줄: 같은 색이 이어지는 토막들 (투명한 칸은 뺀다). */
	private static final class Line {
		final int[] start;
		final int[] length;
		final Color[] color;

		Line(BufferedImage img, int x, int y, int stepX, int stepY, int n, int fillPixel, Color fill) {
			int[] s = new int[n];
			int[] l = new int[n];
			Color[] c = new Color[n];
			int runs = 0;
			int i = 0;
			while (i < n) {
				int argb = img.getRGB(x + i * stepX, y + i * stepY);
				int j = i + 1;
				while (j < n && img.getRGB(x + j * stepX, y + j * stepY) == argb) {
					j++;
				}
				if ((argb >>> 24) != 0) {
					s[runs] = i;
					l[runs] = j - i;
					c[runs] = argb == fillPixel ? fill : new Color(argb, true);
					runs++;
				}
				i = j;
			}
			start = Arrays.copyOf(s, runs);
			length = Arrays.copyOf(l, runs);
			color = Arrays.copyOf(c, runs);
		}

		/**
		 * 그림의 칸 [{@code from}, {@code to}) 를 화면에 칠한다. 칸 {@code from} 이 화면의 {@code at} 이고, 칸마다 길이 {@code span}
		 * 의 줄을 칠한다({@code across} 면 칸이 x 방향이고 줄은 세로, 아니면 칸이 y 방향이고 줄은 가로).
		 */
		void fill(Graphics2D g, int from, int to, int at, int other, int span, boolean across) {
			for (int k = 0; k < start.length; k++) {
				int a = Math.max(from, start[k]);
				int b = Math.min(to, start[k] + length[k]);
				if (b <= a) {
					continue;
				}
				g.setColor(color[k]);
				if (across) {
					g.fillRect(at + a - from, other, b - a, span);
				} else {
					g.fillRect(other, at + a - from, span, b - a);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 기억 (가장 오래 안 쓴 것부터 버림)

	/** 그림 한 장과 그 가운데 한 줄들 (곧은 가장자리를 채울 색). */
	private static final class Entry {
		final int fill;
		final int border;
		final int sw;
		final int sh;
		final int flags;
		/** 화소 안의 위치 넷(왼·오른·위·아래), 모서리 지름 둘, 배율 (double 의 비트). */
		final long[] geo;
		final BufferedImage img;
		/** 가운데 세로줄(열 cw)의 토막, 위에서 아래로. 가로로 나누지 않으면 null. */
		final Line col;
		/** 가운데 가로줄(행 ch)의 토막, 왼쪽에서 오른쪽으로. 세로로 나누지 않으면 null. */
		final Line row;
		long used;

		Entry(int fill, int border, int sw, int sh, int flags, long[] geo, BufferedImage img, int cw, int ch,
				int fillPixel) {
			this.fill = fill;
			this.border = border;
			this.sw = sw;
			this.sh = sh;
			this.flags = flags;
			this.geo = geo;
			this.img = img;
			Color fillColor = new Color(fill, true);
			this.col = cw < 0 ? null : new Line(img, cw, 0, 0, 1, sh, fillPixel, fillColor);
			this.row = ch < 0 ? null : new Line(img, 0, ch, 1, 0, sw, fillPixel, fillColor);
		}

		boolean is(int fill, int border, int sw, int sh, int flags, long[] geo) {
			return this.fill == fill && this.border == border && this.sw == sw && this.sh == sh && this.flags == flags
					&& Arrays.equals(this.geo, geo);
		}
	}

	private static synchronized Entry find(int fill, int border, int sw, int sh, int flags, long[] geo) {
		for (int i = 0; i < count; i++) {
			Entry e = CACHE[i];
			if (e.is(fill, border, sw, sh, flags, geo)) {
				e.used = ++tick;
				return e;
			}
		}
		return null;
	}

	/** 새 그림을 넣는다. 그 사이 다른 스레드가 같은 그림을 넣었으면 그것을 준다. */
	private static synchronized Entry keep(Entry e) {
		Entry other = find(e.fill, e.border, e.sw, e.sh, e.flags, e.geo);
		if (other != null) {
			return other;
		}
		long size = (long) e.sw * e.sh;
		while (count > 0 && (count >= CAPACITY || pixels + size > PIXEL_BUDGET)) {
			int oldest = 0;
			for (int i = 1; i < count; i++) {
				if (CACHE[i].used < CACHE[oldest].used) {
					oldest = i;
				}
			}
			pixels -= (long) CACHE[oldest].sw * CACHE[oldest].sh;
			CACHE[oldest] = CACHE[--count];
			CACHE[count] = null;
		}
		e.used = ++tick;
		CACHE[count++] = e;
		pixels += size;
		MADE.get()[0]++;
		return e;
	}

	/** 이 스레드가 지금까지 만든 그림 수 (시험용: 같은 카드를 다시 그리면 늘지 않아야 한다). */
	static long spritesMade() {
		return MADE.get()[0];
	}

	/** 기억하는 그림 수 (시험용). */
	static synchronized int cached() {
		return count;
	}

	/** 기억을 비우고 이 스레드가 만든 수를 0 으로 (시험용). */
	static synchronized void clear() {
		Arrays.fill(CACHE, null);
		count = 0;
		pixels = 0;
		MADE.get()[0] = 0;
	}
}
