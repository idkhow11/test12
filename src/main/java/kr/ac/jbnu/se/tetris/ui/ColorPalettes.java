package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;

import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 블록과 모래의 색 묶음, 블록 무늬 (설정 화면의 "색약 색상" 과 "블록 무늬", {@link UserSettings#colorBlind()},
 * {@link UserSettings#patterns()}).
 * <p>
 * 그리는 쪽은 판을 그릴 때 {@link #look(UserSettings)} 로 {@link Look} 하나를 고르고, {@code Theme.block}·
 * {@code Theme.ghost}·{@code Theme.piece} 대신 그 객체의 {@link Look#block}·{@link Look#ghost}·{@link Look#piece} 를
 * 부른다(인자가 같다). 모래는 알갱이마다 {@link Look#grain}, 홀드·미리보기의 모래 블록은 {@link Look#sandCell}.
 * <ul>
 * <li><b>기본 색</b>({@link Palette#NORMAL}): 지금 게임의 색({@code Theme.piece}) 그대로다. 무늬를 끄면 {@code Theme} 가
 * 그대로 그린다(설정을 바꾸지 않은 사람에게는 화소 하나 바뀌지 않는다).</li>
 * <li><b>색약 색상</b>({@link Palette#COLOR_BLIND}): Okabe-Ito 의 일곱 색(Okabe &amp; Ito, "Color Universal Design",
 * 2008). 블록 모양과 같은 계열의 색을 골랐다(I 하늘색, J 파랑, L 주황, O 노랑, S 청록, T 적자색, Z 주홍). 적색약·녹색약·
 * 청색약을 흉내 낸 색(Machado 2009)에서도 블록 일곱 가지와 방해 줄 가운데 가장 가까운 두 색의 차이가 CIEDE2000 10.9
 * 이상이다(기본 색은 적색약에서 J·T 가 3.2, 녹색약에서 S·Z 가 5.4). 모래 네 색도 같은 묶음에서 고른다.</li>
 * <li><b>블록 무늬</b>: 블록 종류마다 이름 글자 모양의 무늬를 블록 면에 새긴다(O T S Z J L 은 글자 모양, I 는 일자 블록의
 * 一). 면보다 어둡거나 밝은 같은 계열 색이라 색 없이도 블록을 구분하면서 화면이 어지럽지 않다. 방해 줄과
 * {@value #MIN_MARK_SIZE} 화소보다 작은 블록에는 그리지 않는다. 모래는 색마다 정해진 알갱이 무늬
 * ({@code sand.SandColor#pattern}, {@code Grain.pattern})를 또렷한 색으로 그린다.</li>
 * </ul>
 * 색과 무늬 모양은 미리 만들어 두고 다시 쓴다(초당 60번 그린다). 여러 스레드가 함께 써도 된다.
 */
final class ColorPalettes {

	/** 칸 값: 1 = I, 2 = J, 3 = L, 4 = O, 5 = S, 6 = T, 7 = Z ({@code PieceType.code()}), 8 = 방해 줄. */
	static final int GARBAGE = 8;
	/** 모래 색 번호: 1 = 빨강, 2 = 파랑, 3 = 초록, 4 = 노랑 ({@code sand.SandColor.code()}). */
	static final int SAND_COLORS = 4;
	/** 알갱이 명암의 수 ({@code sand.Grain.shade}, 0~3). */
	static final int SHADES = 4;

	/** 무늬를 그리는 가장 작은 블록 (화소). 이보다 작으면 무늬가 뭉개지므로 그리지 않는다. */
	static final int MIN_MARK_SIZE = 12;

	/** 쓴 홀드의 회색 블록 (Theme 와 같은 값). */
	private static final Color GREY = new Color(0x4A5068);
	private static final Color GREY_LIGHT = Theme.mix(GREY, Color.WHITE, 0.25);
	private static final Color GREY_DARK = Theme.mix(GREY, Color.BLACK, 0.35);
	private static final Color GREY_MARK = markColor(GREY);

	/** 무늬의 불투명도 (0~255): 면 위에 새긴 것처럼 살짝 비친다. */
	private static final int MARK_ALPHA = 215;

	private ColorPalettes() {
	}

	// ------------------------------------------------------------------ 색 묶음

	/** 색 묶음 하나: 칸 값 1~8 의 블록 색과 모래 색 1~4. 값이 바뀌지 않는다. */
	static final class Palette {

		/** 지금 게임의 색 (Theme). 모래는 블록 색에서 Z·J·S·O 를 쓴다. */
		static final Palette NORMAL;
		/** 색약이 있어도 구분되는 색 (Okabe-Ito). */
		static final Palette COLOR_BLIND;

		static {
			Color[] normal = new Color[GARBAGE + 1];
			for (int code = 1; code <= GARBAGE; code++) {
				normal[code] = Theme.piece(code);
			}
			NORMAL = new Palette("기본 색", normal,
					new Color[] { null, normal[7], normal[2], normal[5], normal[4] });
			// Okabe-Ito: 하늘색 56B4E9, 파랑 0072B2, 주황 E69F00, 노랑 F0E442, 초록(청록) 009E73, 보라(적자색) CC79A7, 주홍 D55E00
			Color[] oi = { null, new Color(0x56B4E9), new Color(0x0072B2), new Color(0xE69F00), new Color(0xF0E442),
					new Color(0x009E73), new Color(0xCC79A7), new Color(0xD55E00), normal[GARBAGE] };
			// 모래의 파랑은 어두운 판에서 잘 보이는 하늘색을 쓴다(색약 흉내에서도 네 색의 차이는 거의 같다)
			COLOR_BLIND = new Palette("색약 색상", oi, new Color[] { null, oi[7], oi[1], oi[5], oi[4] });
		}

		private final String label;
		private final Color[] piece;
		private final Color[] light = new Color[GARBAGE + 1];
		private final Color[] dark = new Color[GARBAGE + 1];
		private final Color[] mark = new Color[GARBAGE + 1];
		private final Color[] sand;

		private Palette(String label, Color[] piece, Color[] sand) {
			this.label = label;
			this.piece = piece;
			this.sand = sand;
			for (int code = 1; code <= GARBAGE; code++) {
				light[code] = Theme.mix(piece[code], Color.WHITE, 0.38);
				dark[code] = Theme.mix(piece[code], Color.BLACK, 0.38);
				mark[code] = markColor(piece[code]);
			}
		}

		/** 화면에 보이는 이름 ("기본 색", "색약 색상"). */
		String label() {
			return label;
		}

		/** 칸 값 1~8 의 바탕색. */
		Color piece(int code) {
			return piece[checkCode(code)];
		}

		/** 블록 위·왼쪽 테의 밝은 색. */
		Color light(int code) {
			return light[checkCode(code)];
		}

		/** 블록 아래·오른쪽 테의 어두운 색. */
		Color dark(int code) {
			return dark[checkCode(code)];
		}

		/** 블록 무늬의 색 (바탕보다 대비가 큰 쪽으로 어둡거나 밝은 같은 계열 색). */
		Color mark(int code) {
			return mark[checkCode(code)];
		}

		/** 모래 색 번호 1~4 의 바탕색. */
		Color sand(int colorCode) {
			if (colorCode < 1 || colorCode > SAND_COLORS) {
				throw new IllegalArgumentException("모래 색 번호는 1~" + SAND_COLORS + ": " + colorCode);
			}
			return sand[colorCode];
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static int checkCode(int code) {
		if (code < 1 || code > GARBAGE) {
			throw new IllegalArgumentException("칸 값은 1~" + GARBAGE + ": " + code);
		}
		return code;
	}

	/** 색 묶음: {@code colorBlind} 면 색약 색상. */
	static Palette palette(boolean colorBlind) {
		return colorBlind ? Palette.COLOR_BLIND : Palette.NORMAL;
	}

	// ------------------------------------------------------------------ 그리는 방법

	private static final Look[] LOOKS = { new Look(Palette.NORMAL, false), new Look(Palette.NORMAL, true),
			new Look(Palette.COLOR_BLIND, false), new Look(Palette.COLOR_BLIND, true) };

	/** 설정에 맞는 그리는 방법 (색약 색상, 블록 무늬). */
	static Look look(UserSettings s) {
		return look(s.colorBlind(), s.patterns());
	}

	static Look look(boolean colorBlind, boolean patterns) {
		return LOOKS[(colorBlind ? 2 : 0) + (patterns ? 1 : 0)];
	}

	/** 기본 색, 무늬 없음 (설정을 바꾸기 전의 게임, Theme 와 같다). */
	static Look standard() {
		return LOOKS[0];
	}

	/**
	 * 판을 그리는 방법 하나: 색 묶음과 무늬. 네 가지를 미리 만들어 두므로 판을 그릴 때마다 {@link #look} 으로 골라도 된다.
	 * 메서드는 {@code Theme} 의 같은 이름 메서드와 인자가 같다.
	 */
	static final class Look {

		private final Palette palette;
		private final boolean patterns;
		/** 알갱이 색: [((색 × 명암 + 명암) × 2 + 반짝임) × 2 + 무늬]. */
		private final Color[] grains = new Color[(SAND_COLORS + 1) * SHADES * 4];

		private Look(Palette palette, boolean patterns) {
			this.palette = palette;
			this.patterns = patterns;
			for (int code = 1; code <= SAND_COLORS; code++) {
				Color base = palette.sand(code);
				for (int shade = 0; shade < SHADES; shade++) {
					for (int f = 0; f < 2; f++) {
						for (int p = 0; p < 2; p++) {
							boolean flash = f == 1;
							boolean mark = p == 1;
							grains[grainIndex(code, shade, flash, mark)] = grainColor(base, shade, flash, mark && patterns);
						}
					}
				}
			}
		}

		Palette palette() {
			return palette;
		}

		/** 블록 무늬를 그리는가. */
		boolean patterns() {
			return patterns;
		}

		/** 칸 값 1~8 의 색 (글자나 효과에 블록 색을 쓸 때. 예: "T-SPIN" 글자, "TETRIS" 글자). */
		Color piece(int code) {
			return palette.piece(code);
		}

		/** 모래 색 번호 1~4 의 바탕색 (미리보기·홀드의 모래 블록, 색 이름 옆 견본). */
		Color sand(int colorCode) {
			return palette.sand(colorCode);
		}

		/**
		 * 블록 한 칸 ({@code Theme.block} 과 같은 입체 모양). {@code code} 는 칸 값 1~8, {@code alpha} 0~255,
		 * {@code grey} 면 회색(쓴 홀드). 무늬를 켰고 칸이 {@value ColorPalettes#MIN_MARK_SIZE} 화소 이상이면 무늬를 새긴다.
		 */
		void block(Graphics2D g, int x, int y, int size, int code, int alpha, boolean grey) {
			if (palette == Palette.NORMAL) {
				Theme.block(g, x, y, size, code, alpha, grey);
			} else {
				Color base = grey ? GREY : palette.piece(code);
				Color light = grey ? GREY_LIGHT : palette.light(code);
				Color dark = grey ? GREY_DARK : palette.dark(code);
				bevel(g, x, y, size, base, light, dark, alpha);
			}
			if (patterns) {
				mark(g, x, y, size, code, grey ? GREY_MARK : palette.mark(code), alpha);
			}
		}

		/** 고스트(하드드롭하면 놓일 자리): 옅게 채우고 테두리만 또렷하게 ({@code Theme.ghost} 와 같은 모양). */
		void ghost(Graphics2D g, int x, int y, int size, int code) {
			if (palette == Palette.NORMAL) {
				Theme.ghost(g, x, y, size, code);
				return;
			}
			Color c = palette.piece(code);
			g.setColor(Theme.withAlpha(c, 38));
			g.fillRect(x + 2, y + 2, size - 4, size - 4);
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(Math.max(1.5f, size / 14f)));
			g.setColor(Theme.withAlpha(Theme.mix(c, Color.WHITE, 0.4), 210));
			g.drawRect(x + 2, y + 2, size - 5, size - 5);
			g.setStroke(old);
		}

		/**
		 * 모래 알갱이 하나의 색. 인자는 {@code sand.Grain} 으로 읽은 값이다: 색 번호 1~4({@code Grain.code}), 명암
		 * 0~3({@code Grain.shade}), 반짝임({@code Grain.flashing}), 무늬 표시({@code Grain.pattern}). 무늬 표시는 블록 무늬를 켰을
		 * 때만 색을 바꾼다. 빈칸(색 번호 0)이면 null.
		 */
		Color grain(int colorCode, int shade, boolean flashing, boolean pattern) {
			if (colorCode == 0) {
				return null;
			}
			if (colorCode < 1 || colorCode > SAND_COLORS) {
				throw new IllegalArgumentException("모래 색 번호는 1~" + SAND_COLORS + ": " + colorCode);
			}
			return grains[grainIndex(colorCode, shade & 3, flashing, pattern)];
		}

		/**
		 * 모래 블록 한 칸 (홀드·미리보기처럼 블록을 칸으로 그릴 때): 칸을 알갱이 8 × 8 의 결로 칠한다. 알갱이 명암은 자리마다
		 * 정해진 값(언제나 같은 결)이고, {@code pattern} 은 칸 안 알갱이 (행, 열) 0~7 의 무늬 표시다(보통
		 * {@code SandColor.of(colorCode)::pattern}, 없으면 null). 무늬 표시는 블록 무늬를 켰을 때만 보인다.
		 */
		void sandCell(Graphics2D g, int x, int y, int size, int colorCode, int alpha, BiPredicate<Integer, Integer> pattern) {
			for (int r = 0; r < 8; r++) {
				int y0 = y + r * size / 8;
				int y1 = y + (r + 1) * size / 8;
				for (int c = 0; c < 8; c++) {
					int x0 = x + c * size / 8;
					int x1 = x + (c + 1) * size / 8;
					Color col = grain(colorCode, texture(r, c), false, pattern != null && pattern.test(r, c));
					g.setColor(alpha >= 255 ? col : Theme.withAlpha(col, alpha));
					g.fillRect(x0, y0, x1 - x0, y1 - y0);
				}
			}
		}

		@Override
		public String toString() {
			return palette.label() + (patterns ? " + 무늬" : "");
		}
	}

	/** 모래 칸 그림의 알갱이 명암 0~3: 자리마다 정해진 값 (모래처럼 고르게 섞인다). */
	static int texture(int row, int col) {
		int h = col * 0x45D9F3B ^ row * 0x119DE1F3 ^ 0x2C1B3C6D;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		return h & 3;
	}

	private static int grainIndex(int code, int shade, boolean flash, boolean pattern) {
		return ((code * SHADES + shade) * 2 + (flash ? 1 : 0)) * 2 + (pattern ? 1 : 0);
	}

	/** 알갱이 색: 명암은 아주 조금씩, 무늬 알갱이는 무늬 색 쪽으로, 반짝이면 거의 흰색으로. */
	private static Color grainColor(Color base, int shade, boolean flash, boolean pattern) {
		Color c;
		switch (shade) {
		case 0:
			c = Theme.mix(base, Color.BLACK, 0.12);
			break;
		case 1:
			c = Theme.mix(base, Color.BLACK, 0.04);
			break;
		case 2:
			c = Theme.mix(base, Color.WHITE, 0.05);
			break;
		default:
			c = Theme.mix(base, Color.WHITE, 0.12);
			break;
		}
		if (pattern) {
			c = Theme.mix(c, markColor(base), 0.8);
		}
		if (flash) {
			c = Theme.mix(c, Color.WHITE, 0.72);
		}
		return c;
	}

	// ------------------------------------------------------------------ 블록 모양과 무늬

	/** {@code Theme.block} 과 같은 입체 모양을 주어진 색으로 그린다. */
	private static void bevel(Graphics2D g, int x, int y, int size, Color base, Color light, Color dark, int alpha) {
		int gap = size >= 14 ? 1 : 0;
		int s = size - 2 * gap;
		int b = Math.max(2, s / 7);
		int x0 = x + gap;
		int y0 = y + gap;
		boolean opaque = alpha >= 255;
		g.setColor(opaque ? dark : Theme.withAlpha(dark, alpha));
		g.fillRect(x0, y0, s, s);
		g.setColor(opaque ? light : Theme.withAlpha(light, alpha));
		g.fillRect(x0, y0, s - b, s - b);
		g.setColor(opaque ? base : Theme.withAlpha(base, alpha));
		g.fillRect(x0 + b, y0 + b, s - 2 * b, s - 2 * b);
	}

	/** 블록 면(테 안쪽)에 칸 값 {@code code} 의 무늬를 새긴다. 방해 줄과 작은 칸에는 그리지 않는다. */
	private static void mark(Graphics2D g, int x, int y, int size, int code, Color color, int alpha) {
		if (size < MIN_MARK_SIZE || code < 1 || code >= GARBAGE || alpha <= 0) {
			return;
		}
		int gap = size >= 14 ? 1 : 0;
		int s = size - 2 * gap;
		int b = Math.max(2, s / 7);
		int face = s - 2 * b;
		Shape shape = markShape(code, face);
		if (shape == null) {
			return;
		}
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(Theme.withAlpha(color, MARK_ALPHA * Math.min(255, alpha) / 255));
		AffineTransform old = g.getTransform();
		g.translate(x + gap + b, y + gap + b);
		g.fill(shape);
		g.setTransform(old);
		if (aa != null) {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
	}

	/** 무늬 모양 (면 크기마다 한 번 만든다): 칸 값과 면 한 변 → 면의 왼쪽 위 기준 채울 모양. */
	private static final Map<Integer, Shape> MARKS = new ConcurrentHashMap<>();

	/**
	 * 칸 값 {@code code} 의 무늬 모양. 면의 왼쪽 위가 (0, 0) 이고 한 변이 {@code face} 화소인 정사각형 안에 들어간다. 방해
	 * 줄이거나 면이 너무 작으면 null.
	 */
	static Shape markShape(int code, int face) {
		if (code < 1 || code >= GARBAGE || face < 6) {
			return null;
		}
		int f = Math.min(face, 1023);
		return MARKS.computeIfAbsent(code * 1024 + f, k -> buildMark(code, f));
	}

	/**
	 * 이름 글자 모양의 무늬: I 가로줄(일자 블록의 一), J ┘, L └, O 고리, S 각진 S, T ┬, Z 각진 Z. 모두 같은 굵기의 둥근
	 * 선이고 면의 가운데 64% 안에 들어간다.
	 */
	private static Shape buildMark(int code, int face) {
		double box = face * 0.64;
		double o = (face - box) / 2;
		Path2D.Double p = new Path2D.Double();
		switch (code) {
		case 1: // I: 일자(一) 블록의 가로줄. 세로줄로 그리면 작은 칸에서 T(┬)와 헷갈린다
			p.moveTo(0.08, 0.5);
			p.lineTo(0.92, 0.5);
			break;
		case 2: // J
			p.moveTo(0.72, 0.08);
			p.lineTo(0.72, 0.88);
			p.lineTo(0.22, 0.88);
			break;
		case 3: // L
			p.moveTo(0.28, 0.08);
			p.lineTo(0.28, 0.88);
			p.lineTo(0.78, 0.88);
			break;
		case 4: // O
			p.append(new Ellipse2D.Double(0.12, 0.12, 0.76, 0.76), false);
			break;
		case 5: // S
			p.moveTo(0.82, 0.12);
			p.lineTo(0.2, 0.12);
			p.lineTo(0.2, 0.5);
			p.lineTo(0.8, 0.5);
			p.lineTo(0.8, 0.88);
			p.lineTo(0.18, 0.88);
			break;
		case 6: // T
			p.moveTo(0.1, 0.12);
			p.lineTo(0.9, 0.12);
			p.moveTo(0.5, 0.12);
			p.lineTo(0.5, 0.92);
			break;
		default: // 7: Z
			p.moveTo(0.16, 0.12);
			p.lineTo(0.84, 0.12);
			p.lineTo(0.16, 0.88);
			p.lineTo(0.84, 0.88);
			break;
		}
		AffineTransform t = new AffineTransform();
		t.translate(o, o);
		t.scale(box, box);
		Shape scaled = t.createTransformedShape(p);
		float w = (float) Math.max(1.5, box * 0.15);
		return new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(scaled);
	}

	// ------------------------------------------------------------------ 대비

	/** 무늬 색: 바탕을 어둡게 한 색과 밝게 한 색 중 바탕과 대비가 큰 쪽. */
	static Color markColor(Color base) {
		Color darker = Theme.mix(base, Color.BLACK, 0.5);
		Color lighter = Theme.mix(base, Color.WHITE, 0.62);
		return contrast(darker, base) >= contrast(lighter, base) ? darker : lighter;
	}

	/** 상대 휘도 (sRGB, WCAG 2). */
	static double luminance(Color c) {
		return 0.2126 * linear(c.getRed()) + 0.7152 * linear(c.getGreen()) + 0.0722 * linear(c.getBlue());
	}

	/** 두 색의 대비 비율 (WCAG 2, 1~21). */
	static double contrast(Color a, Color b) {
		double la = luminance(a);
		double lb = luminance(b);
		return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
	}

	private static double linear(int v) {
		double c = v / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}
}
