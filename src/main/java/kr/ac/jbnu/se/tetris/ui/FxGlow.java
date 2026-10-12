package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * 빛 번짐({@link Theme#glow})을 미리 그려 둔 조각 그림으로 그린다 (runs/quality/SPEC.md V3(f)).
 * <p>
 * 예전에는 부를 때마다 안티앨리어싱 선 다섯 겹(넓고 옅은 선 넷과 가운데의 또렷한 선)을 그렸다. 이제 같은 다섯 겹을 작은 그림에 한 번만
 * 그려 두고 아홉 조각으로 나눠 붙인다.
 * <ul>
 * <li>모서리 넷은 그림에서 그대로 옮긴다. 곧은 가장자리 넷은 어디를 잘라도 같은 모양이므로, 그림의 가운데 한 줄의 색을 기억해 두었다가
 * 화소 한 줄씩 채운다(늘여 붙이기보다 훨씬 싸다). 안쪽은 비어 있으므로 건드리지 않는다. 그래서 그림 크기는 모서리의 둥글기에만 달려
 * 있고 보드가 커도 작다.</li>
 * <li>장치 화소에서 그린다: 지금 변환(창 배율, 화면 배율)을 읽어 실제 화소 위치와 크기를 계산하고 변환 없이 붙인다. 확대해도 흐려지지
 * 않는다. 위치의 소수 부분은 1/4 화소까지 그림에 담는다.</li>
 * <li>세기는 1 당 15칸으로 나눠 그림을 만든다(0~1 이 16단계). 그 사이의 세기는 바로 위 단계의 그림을 알파로 조금 옅게 붙인다. 세기는
 * 2 까지 받는다.</li>
 * <li>부르는 쪽이 알파 합성(예: 카드가 나타나며 옅게)을 걸어 두면 빛 전체를 한 덩어리로 옅게 한다. 예전에는 다섯 겹을 따로 옅게 해서
 * 겹친 가운데 선이 조금 더 밝았다(알파 0.5 에서 0~255 기준 최대 19 차이).</li>
 * <li>회전·기울임·가로세로 배율이 다른 변환, SRC_OVER 가 아닌 합성, 아주 큰 그림은 예전처럼 선으로 긋는다({@link #strokes}).</li>
 * <li>최근에 쓴 {@value #CAPACITY}장까지 기억한다(화소 합계도 제한). 넘치면 가장 오래 안 쓴 것부터 버린다.</li>
 * </ul>
 */
final class FxGlow {

	/** 세기 1 을 나누는 칸 수 (0, 1/15, …, 1 의 16단계). */
	static final int STEPS = 15;
	/** 받는 가장 큰 세기. */
	static final double MAX_STRENGTH = 2;
	/** 기억하는 그림 수. */
	static final int CAPACITY = 96;
	/** 기억하는 그림의 화소 합계 (4바이트씩 약 8 MB). */
	private static final long PIXEL_BUDGET = 2_000_000;
	/** 그림 한 변의 최대 길이(화소). 넘으면 선으로 긋는다. */
	private static final int MAX_SIDE = 1024;
	/** 이보다 먼 좌표(화소)는 선으로 긋는다 (정수 넘침 방지). */
	private static final double MAX_COORD = 1 << 20;
	/** 가장 넓은 선의 굵기(사용자 화소): 4 × 2.4. */
	private static final double WIDEST = 9.6;
	private static final AffineTransform IDENTITY = new AffineTransform();

	private static final Entry[] CACHE = new Entry[CAPACITY];
	private static int count;
	private static long pixels;
	private static long tick;
	private static long made;

	private FxGlow() {
	}

	/** {@link Theme#glow} 와 같다: 둥근 사각형(x, y, w, h, 모서리 지름 r) 바깥으로 번지는 빛. {@code strength} 는 0~1 (2 까지). */
	static void draw(Graphics2D g, double x, double y, double w, double h, double r, Color c, double strength) {
		if (!(strength > 0) || c == null) {
			return; // 예전 코드도 세기 0 이하면 투명한 선만 그었다
		}
		AffineTransform t = g.getTransform();
		Composite comp = g.getComposite();
		double s = t.getScaleX();
		int type = t.getType();
		if (!(w > 0 && h > 0) || (type & ~(AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE)) != 0
				|| !(s > 0) || !(comp instanceof AlphaComposite ac) || ac.getRule() != AlphaComposite.SRC_OVER) {
			strokes(g, x, y, w, h, r, c, strength);
			return;
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
				&& Math.abs(bottom) < MAX_COORD && arcW >= 0 && arcH >= 0)) {
			strokes(g, x, y, w, h, r, c, strength);
			return;
		}
		// 그림의 모양은 아래의 반올림한 값(열쇠)만으로 정한다: 열쇠가 같으면 같은 그림을 다시 써도 된다
		int scale = (int) Math.round(s * 256);
		int qArcW = (int) Math.round(arcW * 4);
		int qArcH = (int) Math.round(arcH * 4);
		double sq = scale / 256.0;
		int halfUp = (int) Math.ceil(WIDEST * sq / 2);
		int band = halfUp + 2; // 선의 절반 + 안티앨리어싱 1 + 여유 1
		long qL = Math.round(left * 4);
		long qR = Math.round(right * 4);
		long qT = Math.round(top * 4);
		long qB = Math.round(bottom * 4);
		int iL = (int) Math.floorDiv(qL, 4);
		int iR = (int) Math.floorDiv(qR, 4);
		int iT = (int) Math.floorDiv(qT, 4);
		int iB = (int) Math.floorDiv(qB, 4);
		int fracs = Math.floorMod(qL, 4) | Math.floorMod(qR, 4) << 2 | Math.floorMod(qT, 4) << 4
				| Math.floorMod(qB, 4) << 6;
		// 모서리 조각: 둥근 부분과 선의 번짐을 다 담고, 그 너머(곧은 가장자리)는 어디를 잘라도 같은 모양
		int cw = band + Math.max((qArcW + 7) / 8, halfUp) + 3;
		int ch = band + Math.max((qArcH + 7) / 8, halfUp) + 3;
		int dw = iR - iL + 2 * band + 1;
		int dh = iB - iT + 2 * band + 1;
		boolean stretchX = dw >= 2 * cw + 1;
		boolean stretchY = dh >= 2 * ch + 1;
		int sw = stretchX ? 2 * cw + 1 : dw;
		int sh = stretchY ? 2 * ch + 1 : dh;
		if (sw > MAX_SIDE || sh > MAX_SIDE) {
			strokes(g, x, y, w, h, r, c, strength);
			return;
		}
		double st = Math.min(strength, MAX_STRENGTH);
		int bucket = Math.max(1, (int) Math.ceil(st * STEPS - 1e-9));
		double extra = st * STEPS / bucket; // 위 단계 그림을 이만큼 옅게 (단계에 딱 맞으면 1)
		int rgb = c.getRGB() & 0xFFFFFF;
		int flags = (stretchX ? 1 : 0) | (stretchY ? 2 : 0);
		Entry e = find(rgb, bucket, sw, sh, flags, fracs, qArcW, qArcH, scale);
		if (e == null) {
			BufferedImage img = render(g, sw, sh, band, fracs, qArcW / 4.0, qArcH / 4.0, sq, rgb, bucket / (double) STEPS);
			e = keep(new Entry(rgb, bucket, sw, sh, flags, fracs, qArcW, qArcH, scale, img, stretchX ? cw : -1,
					stretchY ? ch : -1));
		}
		AlphaComposite alpha = extra >= 1 ? ac : Fx.times(ac, extra);
		Color oldColor = g.getColor();
		g.setTransform(IDENTITY);
		if (alpha != comp) {
			g.setComposite(alpha);
		}
		try {
			pieces(g, e, iL - band, iT - band, dw, dh, cw, ch);
		} finally {
			g.setTransform(t);
			if (alpha != comp) {
				g.setComposite(comp);
			}
			g.setColor(oldColor);
		}
	}

	/** 조각을 붙인다: 모서리는 그림에서, 곧은 가장자리는 기억한 한 줄의 색으로 한 줄씩, 안쪽은 건너뜀. */
	private static void pieces(Graphics2D g, Entry e, int dx, int dy, int dw, int dh, int cw, int ch) {
		boolean stretchX = e.midCol != null;
		boolean stretchY = e.midRow != null;
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
				if (midX && midY) {
					continue; // 안쪽: 비어 있다
				}
				int sx0 = start(i, stretchX, cw);
				int sx1 = end(i, stretchX, cw, e.sw);
				int dx0 = dx + startDst(i, stretchX, cw, dw);
				int dx1 = dx + endDst(i, stretchX, cw, dw);
				if (dx1 <= dx0 || dy1 <= dy0) {
					continue;
				}
				if (midX) { // 위·아래 가장자리 (또는 낮은 알약의 가운데): 가로줄 채우기
					for (int row = sy0; row < sy1; row++) {
						Color col = e.midCol[row];
						if (col != null) {
							g.setColor(col);
							g.fillRect(dx0, dy0 + row - sy0, dx1 - dx0, 1);
						}
					}
				} else if (midY) { // 왼쪽·오른쪽 가장자리: 세로줄 채우기
					for (int c = sx0; c < sx1; c++) {
						Color col = e.midRow[c];
						if (col != null) {
							g.setColor(col);
							g.fillRect(dx0 + c - sx0, dy0, 1, dy1 - dy0);
						}
					}
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

	/** 다섯 겹을 장치 화소로 그린 그림. 사각형의 왼쪽 위는 (band + 소수, band + 소수). */
	private static BufferedImage render(Graphics2D g, int sw, int sh, int band, int fracs, double arcW, double arcH,
			double s, int rgb, double strength) {
		double ox = band + (fracs & 3) / 4.0;
		double rx = sw - band - 1 + (fracs >> 2 & 3) / 4.0;
		double oy = band + (fracs >> 4 & 3) / 4.0;
		double by = sh - band - 1 + (fracs >> 6 & 3) / 4.0;
		BufferedImage img = Fx.sprite(g, sw, sh);
		Graphics2D sg = img.createGraphics();
		sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		layers(sg, new RoundRectangle2D.Double(ox, oy, rx - ox, by - oy, arcW, arcH), (float) s, rgb, strength);
		sg.dispose();
		return img;
	}

	/** 예전 Theme.glow 와 같은 다섯 겹. 선 굵기는 사용자 화소의 굵기에 {@code scale} 을 곱한 것. */
	private static void layers(Graphics2D g, Shape shape, float scale, int rgb, double strength) {
		for (int i = 4; i >= 1; i--) {
			g.setStroke(new BasicStroke(i * 2.4f * scale));
			g.setColor(argb(rgb, (int) (strength * (70 - i * 14))));
			g.draw(shape);
		}
		g.setStroke(new BasicStroke(1.6f * scale));
		g.setColor(argb(rgb, (int) (strength * 220)));
		g.draw(shape);
	}

	private static Color argb(int rgb, int alpha) {
		return new Color(rgb | Math.max(0, Math.min(255, alpha)) << 24, true);
	}

	/**
	 * 예전의 그리기 방법 그대로: 선 다섯 겹을 지금 변환과 합성으로 바로 긋는다. 조각 그림으로 그릴 수 없을 때와, 비교·측정용.
	 */
	static void strokes(Graphics2D g, double x, double y, double w, double h, double r, Color c, double strength) {
		Stroke old = g.getStroke();
		for (int i = 4; i >= 1; i--) {
			g.setStroke(new BasicStroke(i * 2.4f));
			g.setColor(Theme.withAlpha(c, (int) (strength * (70 - i * 14))));
			g.draw(new RoundRectangle2D.Double(x, y, w, h, r, r));
		}
		g.setStroke(new BasicStroke(1.6f));
		g.setColor(Theme.withAlpha(c, (int) (strength * 220)));
		g.draw(new RoundRectangle2D.Double(x, y, w, h, r, r));
		g.setStroke(old);
	}

	// ------------------------------------------------------------------ 기억 (가장 오래 안 쓴 것부터 버림)

	/** 그림 한 장과 그 가운데 한 줄의 색 (곧은 가장자리를 채울 색, 투명한 칸은 null). */
	private static final class Entry {
		final int rgb;
		final int bucket;
		final int sw;
		final int sh;
		final int flags;
		final int fracs;
		final int arcW;
		final int arcH;
		final int scale;
		final BufferedImage img;
		/** 가운데 세로줄(열 cw)의 색, 위에서 아래로. 가로로 나누지 않으면 null. */
		final Color[] midCol;
		/** 가운데 가로줄(행 ch)의 색, 왼쪽에서 오른쪽으로. 세로로 나누지 않으면 null. */
		final Color[] midRow;
		long used;

		Entry(int rgb, int bucket, int sw, int sh, int flags, int fracs, int arcW, int arcH, int scale,
				BufferedImage img, int cw, int ch) {
			this.rgb = rgb;
			this.bucket = bucket;
			this.sw = sw;
			this.sh = sh;
			this.flags = flags;
			this.fracs = fracs;
			this.arcW = arcW;
			this.arcH = arcH;
			this.scale = scale;
			this.img = img;
			this.midCol = cw < 0 ? null : line(img, cw, 0, 0, 1, sh);
			this.midRow = ch < 0 ? null : line(img, 0, ch, 1, 0, sw);
		}

		private static Color[] line(BufferedImage img, int x, int y, int stepX, int stepY, int n) {
			Color[] out = new Color[n];
			for (int i = 0; i < n; i++) {
				int argb = img.getRGB(x + i * stepX, y + i * stepY);
				out[i] = (argb >>> 24) == 0 ? null : new Color(argb, true);
			}
			return out;
		}

		boolean is(int rgb, int bucket, int sw, int sh, int flags, int fracs, int arcW, int arcH, int scale) {
			return this.rgb == rgb && this.bucket == bucket && this.sw == sw && this.sh == sh && this.flags == flags
					&& this.fracs == fracs && this.arcW == arcW && this.arcH == arcH && this.scale == scale;
		}
	}

	private static synchronized Entry find(int rgb, int bucket, int sw, int sh, int flags, int fracs, int arcW,
			int arcH, int scale) {
		for (int i = 0; i < count; i++) {
			Entry e = CACHE[i];
			if (e.is(rgb, bucket, sw, sh, flags, fracs, arcW, arcH, scale)) {
				e.used = ++tick;
				return e;
			}
		}
		return null;
	}

	/** 새 그림을 넣는다. 그 사이 다른 스레드가 같은 그림을 넣었으면 그것을 준다. */
	private static synchronized Entry keep(Entry e) {
		Entry other = find(e.rgb, e.bucket, e.sw, e.sh, e.flags, e.fracs, e.arcW, e.arcH, e.scale);
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
		made++;
		return e;
	}

	/** 지금까지 만든 그림 수 (시험용: 같은 빛을 다시 그리면 늘지 않아야 한다). */
	static synchronized long spritesMade() {
		return made;
	}

	/** 기억하는 그림 수 (시험용). */
	static synchronized int cached() {
		return count;
	}

	/** 기억을 비운다 (시험용). */
	static synchronized void clear() {
		java.util.Arrays.fill(CACHE, null);
		count = 0;
		pixels = 0;
	}
}
