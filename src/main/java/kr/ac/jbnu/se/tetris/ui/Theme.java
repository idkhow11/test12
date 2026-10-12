package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.font.TextAttribute;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 화면 공통 모양 (docs/GAME.md 7장, runs/quality/SPEC.md V1·V2): 색, 글꼴과 글자 크기표, 블록 그리기, 둥근 카드와 빛 번짐. 메뉴와
 * 대전 화면이 같은 모양을 쓴다. 색과 글꼴 객체는 미리 만들어 두고 다시 쓴다(초당 60번 그리므로 그릴 때마다 만들지 않는다).
 * <p>
 * <b>글꼴</b>: 함께 배포하는 네 글꼴(src/main/resources/fonts/, SIL OFL 1.1, CREDITS.md)을 클래스패스에서 읽는다. 한글과 보통 글자는
 * Pretendard, 숫자와 큰 동작 글자는 Barlow Condensed 다. 글꼴을 못 읽으면 표준 오류에 까닭을 한 줄 쓰고 예전 시스템 글꼴(맑은 고딕
 * …)로 그린다. {@code -Dtetris.fonts=system} 이면 비교용으로 일부러 시스템 글꼴을 쓴다.
 * <p>
 * <b>글자 역할</b> ({@link Type}): DISPLAY(동작 글자, 카운트다운, K.O., 메뉴 제목), TITLE(한글 제목), BODY(설명), LABEL(작은 라벨),
 * NUMERIC(숫자 값, 고정 폭 숫자). 크기는 px 로 주거나 대전 화면에서는 칸 c 의 배수로 준다.
 *
 * <pre>
 * g.setColor(Theme.LABEL);
 * Theme.Type.LABEL.draw(g, "PPS", 0.5 * c, cx, y + 0.72 * c, Theme.Align.CENTER);
 * g.setColor(Theme.TEXT);
 * Theme.Type.NUMERIC.draw(g, "1.07", 1.0 * c, cx, y + 1.92 * c, Theme.Align.CENTER); // 숫자 폭이 고정이라 흔들리지 않는다
 * </pre>
 */
final class Theme {

	// 바탕과 면
	static final Color BG_TOP = new Color(0x0E1122);
	static final Color BG_BOTTOM = new Color(0x191D35);
	static final Color SURFACE = new Color(0x1A1F35);
	static final Color SURFACE_HI = new Color(0x242A47);
	/** 카드 테두리. SURFACE 위 1.83:1 (V1, 예전 #2E3558 은 1.37:1 이라 거의 안 보였다). */
	static final Color BORDER = new Color(0x3E4774);
	static final Color BOARD = new Color(0x090C18);
	/** 보드 격자선: BOARD 위에 흰색 10%, 1.26:1 (V1, 예전 #161B30 은 1.14:1). */
	static final Color GRID = new Color(0x22242F);

	// 글자
	static final Color TEXT = new Color(0xE9ECF5);
	/** 보조 글자 (SURFACE 위 7.12:1). SURFACE_HI(고른 카드, 키 모양) 위의 작은 글자는 {@link #LABEL} 대신 이 색 (6.14:1). */
	static final Color TEXT_DIM = new Color(0xA3ABC6);
	/**
	 * 작은 라벨 (V1): HOLD/NEXT, PPS·공격·보냄·APP·T-SPIN, 콤보, 결과 라벨, 그래프 라벨, 설정 설명. SURFACE 위 5.18:1, BOARD 위
	 * 6.21:1 로 작은 글자 기준(WCAG 1.4.3, 4.5:1)을 넘는다. SURFACE_HI 위에서는 4.47:1 이라 모자라므로 {@link #TEXT_DIM} 을 쓴다.
	 */
	static final Color LABEL = new Color(0x8790B5);
	/** 꺼졌거나 잠긴 항목과 "–" 자리 표시에만 (3.49:1 로 작은 글자 기준에 못 미친다). 라벨에는 {@link #LABEL}. */
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
	/** 모래 모드의 강조색 (따뜻한 모래빛). 메뉴의 모래 모드 카드와 모래 화면의 테두리·막대. */
	static final Color SAND = new Color(0xF0A64B);

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

	private Theme() {
	}

	// ------------------------------------------------------------------ 글꼴 (SPEC V2, 3장)

	/** 글꼴이 들어 있는 클래스패스 폴더. */
	static final String FONT_DIR = "/fonts/";
	/** 시스템 속성 이름: 값이 {@code system} 이면 함께 배포한 글꼴 대신 예전 시스템 글꼴로 그린다(비교용). */
	static final String FONTS_PROPERTY = "tetris.fonts";
	/** 가장 작은 글자 크기(px). 더 작게 달라고 해도 이 크기. */
	static final int MIN_PX = 9;
	/** 가장 큰 글자 크기(px). */
	static final int MAX_PX = 2000;
	/** 크기별 글꼴을 배열에 기억하는 한계(px). 더 크면 표에 기억한다. */
	private static final int ARRAY_PX = 512;

	/** 함께 배포하는 글꼴 네 가지 (원본 그대로, SIL OFL 1.1). */
	enum Face {
		/** Pretendard Regular: 본문, 설명. */
		REGULAR("Pretendard-Regular.otf", Font.PLAIN),
		/** Pretendard Bold: 제목, 한글 라벨, 굵은 글자 전부. */
		BOLD("Pretendard-Bold.otf", Font.BOLD),
		/** Barlow Condensed Bold: 숫자 값, 시계, 라틴 라벨. 한글·화살표·▲ 는 없다. */
		NUMERIC("BarlowCondensed-Bold.ttf", Font.BOLD),
		/** Barlow Condensed Black: 동작 글자, 카운트다운, K.O., 메뉴 제목. 한글·화살표·▲ 는 없다. */
		DISPLAY("BarlowCondensed-Black.ttf", Font.BOLD);

		/** fonts/ 안의 파일 이름. */
		final String file;
		/** 시스템 글꼴로 대신 그릴 때의 굵기. */
		final int systemStyle;

		Face(String file, int systemStyle) {
			this.file = file;
			this.systemStyle = systemStyle;
		}
	}

	/** 처음 글꼴을 쓸 때 한 번 읽는다 (색만 쓰는 도구는 글꼴을 읽지 않는다). */
	private static final class Faces {
		static final Font[] BASE = loadDefault();
	}

	/** 시스템 글꼴 이름도 필요할 때만 찾는다 (글꼴 목록을 읽는 데 시간이 걸린다). */
	private static final class SystemFamily {
		static final String NAME = pickFamily();
	}

	private static final Font[][] BY_PX = new Font[Face.values().length][ARRAY_PX + 1];
	private static final Map<Integer, Font> BIG = new HashMap<>();

	private static Font[] loadDefault() {
		if ("system".equalsIgnoreCase(System.getProperty(FONTS_PROPERTY, "").trim())) {
			return null;
		}
		return loadFaces(FONT_DIR, why -> System.err.println("Theme: " + why));
	}

	/**
	 * 클래스패스 폴더 {@code dir} 에서 네 글꼴을 읽는다({@code Font.createFont}, 등록하지 않음: 사용자 PC 에 깔린 같은 이름의 글꼴이
	 * 끼어들지 않는다). 하나라도 못 읽으면 {@code warn} 에 까닭을 한 번 알리고 {@code null} (모두 시스템 글꼴로).
	 */
	static Font[] loadFaces(String dir, Consumer<String> warn) {
		Face[] faces = Face.values();
		Font[] out = new Font[faces.length];
		for (Face f : faces) {
			String path = dir + f.file;
			try (InputStream raw = Theme.class.getResourceAsStream(path)) {
				if (raw == null) {
					throw new IOException("파일 없음");
				}
				// jar 안의 스트림도 읽히도록 버퍼를 씌운다
				out[f.ordinal()] = Font.createFont(Font.TRUETYPE_FONT, new BufferedInputStream(raw));
			} catch (IOException | FontFormatException | RuntimeException e) {
				warn.accept("글꼴을 읽지 못해 시스템 글꼴(" + SystemFamily.NAME + ")로 그립니다: " + path + " (" + e.getMessage()
						+ ")");
				return null;
			}
		}
		return out;
	}

	/** 함께 배포한 글꼴로 그리는가 (못 읽었거나 {@code -Dtetris.fonts=system} 이면 false). */
	static boolean bundledFonts() {
		return Faces.BASE != null;
	}

	/** 한글이 잘 보이는 시스템 글꼴: 맑은 고딕이 있으면 그것, 없으면 Dialog. 함께 배포한 글꼴을 못 읽었을 때만 쓴다. */
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

	/** 얼굴 {@code face} 의 {@code size} px 글꼴 (반올림, 9~2000). 같은 조합은 한 번만 만든다. */
	static Font face(Face face, double size) {
		int px = (int) Math.max(MIN_PX, Math.min(MAX_PX, Math.round(size)));
		synchronized (BY_PX) {
			if (px <= ARRAY_PX) {
				Font f = BY_PX[face.ordinal()][px];
				if (f == null) {
					f = make(face, px);
					BY_PX[face.ordinal()][px] = f;
				}
				return f;
			}
			return BIG.computeIfAbsent(px * 4 + face.ordinal(), k -> make(face, px));
		}
	}

	private static Font make(Face face, int px) {
		Font[] base = Faces.BASE;
		if (base != null) {
			// Bold 는 Bold 파일 자체라서 PLAIN 으로 키운다 (BOLD 로 하면 한 번 더 굵게 그린다)
			return base[face.ordinal()].deriveFont(Font.PLAIN, (float) px);
		}
		return new Font(SystemFamily.NAME, face.systemStyle, px);
	}

	/** 크기(px)와 굵기로 글꼴을 돌려준다: Pretendard Regular / Bold. 같은 조합은 한 번만 만든다. 크기는 반올림, 9 px 이상. */
	static Font font(double size, boolean bold) {
		return face(bold ? Face.BOLD : Face.REGULAR, size);
	}

	/** 큰 글자 글꼴(Barlow Condensed Black). 한글이 섞일 수 있으면 {@link #fit} 을 거친다. */
	static Font display(double px) {
		return face(Face.DISPLAY, px);
	}

	/** 숫자 글꼴(Barlow Condensed Bold). 숫자가 바뀌는 값은 {@link #drawNumber} 로 그린다. */
	static Font numeric(double px) {
		return face(Face.NUMERIC, px);
	}

	/**
	 * 글리프 규칙 (SPEC V2): Barlow 는 라틴·숫자·{@code % : . + × · –} 만 있고 한글·화살표·▲ 가 없다. 만든 글꼴은 다른 글꼴로 대신
	 * 그려 주지 않고 네모를 그리므로, {@code f} 가 {@code s} 를 다 그릴 수 없으면 같은 크기의 Pretendard Bold 를 준다.
	 */
	static Font fit(Font f, String s) {
		if (s == null || f.canDisplayUpTo(s) == -1) {
			return f;
		}
		return face(Face.BOLD, f.getSize2D());
	}

	// ------------------------------------------------------------------ 자간

	private static final double TRACK_MIN = -0.2;
	private static final double TRACK_MAX = 0.5;
	private static final int TRACK_SLOTS = (int) Math.round((TRACK_MAX - TRACK_MIN) * 100) + 1;
	/** 바탕 글꼴 → 자간(0.01 em 단위)별 글꼴. */
	private static final Map<Font, Font[]> TRACKED = new HashMap<>();
	/** 자간을 넣은 글꼴 → 바탕 글꼴과 자간. */
	private static final Map<Font, Tracking> TRACKING_OF = new HashMap<>();

	private record Tracking(Font base, float em) {
	}

	/**
	 * 자간을 넣은 글꼴 ({@code TextAttribute.TRACKING}, em 단위, 0.01 단계로 반올림, −0.2~0.5). 같은 값이면 같은 객체.
	 * {@link #text}·{@link #drawNumber} 는 자간을 글자 사이에만 넣고 정렬한다(drawString 은 끝 글자 뒤에도 넣는다).
	 */
	static Font tracked(Font font, double em) {
		int slot = (int) Math.round((Math.max(TRACK_MIN, Math.min(TRACK_MAX, em)) - TRACK_MIN) * 100);
		float q = (float) (slot / 100.0 + TRACK_MIN);
		if (Math.abs(q) < 1e-6) {
			return font;
		}
		synchronized (TRACKED) {
			Font[] row = TRACKED.computeIfAbsent(font, k -> new Font[TRACK_SLOTS]);
			Font f = row[slot];
			if (f == null) {
				f = font.deriveFont(Map.of(TextAttribute.TRACKING, q));
				row[slot] = f;
				TRACKING_OF.put(f, new Tracking(font, q));
			}
			return f;
		}
	}

	/** 글꼴의 자간(px). 자간이 없으면 0. */
	private static float trackingPx(Font f) {
		if (!f.hasLayoutAttributes()) {
			return 0;
		}
		return tracking(f).em() * f.getSize2D();
	}

	private static Tracking tracking(Font f) {
		synchronized (TRACKED) {
			Tracking t = TRACKING_OF.get(f);
			if (t != null) {
				return t;
			}
			Object v = f.getAttributes().get(TextAttribute.TRACKING); // 여기서 만들지 않은 글꼴 (드물다)
			float em = v instanceof Number n ? n.floatValue() : 0;
			t = new Tracking(em == 0 ? f : f.deriveFont(Map.of(TextAttribute.TRACKING, 0f)), em);
			TRACKING_OF.put(f, t);
			return t;
		}
	}

	// ------------------------------------------------------------------ 글자 역할과 그리기

	/** 글자 정렬: x 가 글자의 왼쪽 끝, 가운데, 오른쪽 끝. */
	enum Align {
		LEFT, CENTER, RIGHT
	}

	/**
	 * 글자 역할 (SPEC V2). 역할마다 글꼴과 기본 자간이 정해져 있고 크기는 px({@link #px}) 또는 칸 c 의 배수({@link #c})로 준다.
	 * {@link #draw} 는 글리프 규칙({@link Theme#fit})과 정렬을 한 번에 처리한다. 색은 부르는 쪽이 정한다({@link #color} 는 권장 색).
	 * <p>
	 * 대전 화면 크기표 (V2, 칸 c 의 배수): 통계 라벨 LABEL 0.5c, 통계 값 NUMERIC 1.0c, HOLD/NEXT 제목 NUMERIC 0.5c + 자간 0.12em,
	 * 콤보 수 DISPLAY 1.3c, 시계 NUMERIC 1.15c, 승률 % DISPLAY 1.1c(나·AI 꼬리표 TITLE 0.62c), 방해 줄 수 NUMERIC 0.55c (8줄 이상 DISPLAY
	 * 0.8c), 공격 칩 DISPLAY 0.6c, K.O. DISPLAY 2.4c, 카운트다운 DISPLAY 4.2c, GO! DISPLAY 3.0c, 결과 제목 TITLE 2.2c, 결과 값
	 * NUMERIC 0.75c. 메뉴(px): 제목 DISPLAY 72 + 자간 0.04em, 모드 이름 TITLE 19, 설명 BODY 14.
	 */
	enum Type {
		/** 큰 글자: 동작 글자(TETRIS, DOUBLE), 카운트다운, GO!, K.O., 메뉴 제목. Barlow Condensed Black. */
		DISPLAY(Face.DISPLAY, 0),
		/** 제목: 한글 제목, 이름, 결과 제목, 메뉴 카드 제목, 버튼. Pretendard Bold. */
		TITLE(Face.BOLD, 0),
		/** 본문: 설명, 안내, 공격 내역. Pretendard Regular. */
		BODY(Face.REGULAR, 0),
		/** 작은 라벨(HOLD, NEXT, PPS…): 라틴은 Barlow Condensed Bold + 자간 0.08em, 한글이 섞이면 Pretendard Bold. 색 LABEL. */
		LABEL(Face.NUMERIC, 0.08),
		/** 숫자 값(통계, 시계, 점수): Barlow Condensed Bold. {@link #draw} 는 고정 폭 숫자로 그린다. */
		NUMERIC(Face.NUMERIC, 0);

		private final Face face;
		private final double tracking;

		Type(Face face, double tracking) {
			this.face = face;
			this.tracking = tracking;
		}

		/** 이 역할의 글꼴 얼굴. */
		Face face() {
			return face;
		}

		/** 기본 자간(em). */
		double tracking() {
			return tracking;
		}

		/** 이 역할의 {@code px} 크기 글꼴 (기본 자간 포함). 한글이 섞일 수 있으면 {@link #fit} 을 쓴다. */
		Font px(double px) {
			Font f = Theme.face(face, px);
			return tracking == 0 ? f : tracked(f, tracking);
		}

		/** 칸 크기 {@code c} 의 {@code k} 배 크기 (대전 화면). */
		Font c(int c, double k) {
			return px(k * c);
		}

		/** {@code s} 를 그릴 글꼴: 이 역할의 글꼴이 다 그릴 수 없으면 같은 크기의 Pretendard Bold (자간 없음). */
		Font fit(double px, String s) {
			return Theme.fit(px(px), s);
		}

		/** 권장 글자 색: LABEL 은 {@link Theme#LABEL}, BODY 는 {@link Theme#TEXT_DIM}, 나머지는 {@link Theme#TEXT}. */
		Color color() {
			return this == LABEL ? Theme.LABEL : this == BODY ? TEXT_DIM : TEXT;
		}

		/**
		 * {@code s} 를 이 역할로 그린다: 글꼴을 고르고({@link #fit}, g 에 남는다) 정렬해 그린다. NUMERIC 은 고정 폭 숫자. 색은 g 의 색.
		 * 그린 너비를 돌려준다.
		 */
		float draw(Graphics2D g, String s, double px, double x, double baseline, Align align) {
			g.setFont(fit(px, s));
			return run(g, s, x, baseline, align, this == NUMERIC, true);
		}

		/** {@link #draw} 가 그릴 너비 (그리지 않는다). g 의 글꼴은 바꾸지 않는다. */
		float width(Graphics2D g, String s, double px) {
			Font old = g.getFont();
			g.setFont(fit(px, s));
			float w = run(g, s, 0, 0, Align.LEFT, this == NUMERIC, false);
			g.setFont(old);
			return w;
		}
	}

	/**
	 * 지금 글꼴로 {@code s} 를 정렬해 그린다. 자간이 있는 글꼴은 글자 사이에만 자간을 넣어 정렬이 글자 끝에 맞는다. 그린 너비를 돌려준다.
	 */
	static float text(Graphics2D g, String s, double x, double baseline, Align align) {
		return run(g, s, x, baseline, align, false, true);
	}

	/** {@link #text} 가 그릴 너비. */
	static float textWidth(Graphics2D g, String s) {
		return run(g, s, 0, 0, Align.LEFT, false, false);
	}

	/**
	 * 고정 폭 숫자 (SPEC V2): 숫자는 그 글꼴의 '0'~'9' 중 가장 넓은 너비의 칸 가운데에, 다른 글자는 제 너비로 그린다. Java2D 는
	 * OpenType {@code tnum} 을 켤 수 없어서 이렇게 한다. 그래서 시계·점수가 바뀌어도 글자가 옆으로 흔들리지 않는다. 지금 글꼴(g 의
	 * 글꼴)을 쓰고, 그린 너비를 돌려준다. 숫자 너비는 글꼴마다 FontMetrics 가 기억하는 값에서 바로 읽는다.
	 */
	static float drawNumber(Graphics2D g, String s, double x, double baseline, Align align) {
		return run(g, s, x, baseline, align, true, true);
	}

	/** {@link #drawNumber} 가 그릴 너비. */
	static float numberWidth(Graphics2D g, String s) {
		return run(g, s, 0, 0, Align.LEFT, true, false);
	}

	/** ASCII 한 글자 문자열 (글자마다 그릴 때 새 문자열을 만들지 않는다). */
	private static final String[] ASCII = new String[128];

	static {
		for (int i = 32; i < 127; i++) {
			ASCII[i] = String.valueOf((char) i);
		}
	}

	private static String glyph(char ch) {
		return ch < 128 && ASCII[ch] != null ? ASCII[ch] : String.valueOf(ch);
	}

	/** 그리기와 재기의 공통 부분. {@code paint} 가 false 면 재기만 한다. */
	private static float run(Graphics2D g, String s, double x, double baseline, Align align, boolean tabular,
			boolean paint) {
		int n = s == null ? 0 : s.length();
		if (n == 0) {
			return 0;
		}
		Font f = g.getFont();
		float track = trackingPx(f);
		Font base = track == 0 ? f : tracking(f).base();
		FontMetrics fm = g.getFontMetrics(base);
		if ((!tabular && track == 0) || hasSurrogate(s)) {
			int w = fm.stringWidth(s);
			if (paint) {
				g.drawString(s, (float) aligned(x, w, align), (float) baseline);
			}
			return w;
		}
		int digit = 0;
		if (tabular) {
			for (char ch = '0'; ch <= '9'; ch++) {
				digit = Math.max(digit, fm.charWidth(ch));
			}
		}
		float w = track * (n - 1);
		for (int i = 0; i < n; i++) {
			char ch = s.charAt(i);
			w += tabular && ch >= '0' && ch <= '9' ? digit : fm.charWidth(ch);
		}
		if (!paint) {
			return w;
		}
		float px = (float) aligned(x, w, align);
		float py = (float) baseline;
		if (base != f) {
			g.setFont(base);
		}
		for (int i = 0; i < n; i++) {
			char ch = s.charAt(i);
			int cw = fm.charWidth(ch);
			if (tabular && ch >= '0' && ch <= '9') {
				g.drawString(glyph(ch), px + (digit - cw) / 2, py);
				px += digit;
			} else {
				if (ch != ' ') {
					g.drawString(glyph(ch), px, py);
				}
				px += cw;
			}
			px += track;
		}
		if (base != f) {
			g.setFont(f);
		}
		return w;
	}

	private static boolean hasSurrogate(String s) {
		for (int i = 0; i < s.length(); i++) {
			if (Character.isSurrogate(s.charAt(i))) {
				return true;
			}
		}
		return false;
	}

	private static double aligned(double x, double w, Align align) {
		return align == Align.CENTER ? x - w / 2.0 : align == Align.RIGHT ? x - w : x;
	}

	// ------------------------------------------------------------------ 색

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

	// ------------------------------------------------------------------ 바탕, 블록, 카드

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

	/**
	 * 둥근 카드: 면 색으로 채우고 얇은 테두리(1.2 화소). 같은 카드를 거듭 그리면 미리 그려 둔 조각 그림을 붙이므로 프레임마다 불러도
	 * 싸다({@link ThemeCard}). 조각 그림으로 그릴 수 없으면 {@link #cardDirect} 로 바로 그린다.
	 */
	static void card(Graphics2D g, double x, double y, double w, double h, double r, Color fill, Color border) {
		if (!ThemeCard.draw(g, x, y, w, h, r, fill, border)) {
			cardDirect(g, x, y, w, h, r, fill, border);
		}
	}

	/** {@link #card} 의 예전 그리기 그대로: 안티앨리어싱 도형을 지금 변환과 합성으로 바로 그린다 (조각 그림을 못 쓸 때와 비교용). */
	static void cardDirect(Graphics2D g, double x, double y, double w, double h, double r, Color fill, Color border) {
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

	/**
	 * 빛 번짐: 둥근 사각형 바깥으로 점점 옅어지는 테두리 몇 겹. {@code strength} 는 0~1 (2 까지). 미리 그려 둔 조각 그림을 붙이므로
	 * 프레임마다 불러도 싸다({@link FxGlow}, SPEC V3(f)). 세기는 16단계로 그림을 나누고 그 사이는 알파로 맞춘다.
	 */
	static void glow(Graphics2D g, double x, double y, double w, double h, double r, Color c, double strength) {
		FxGlow.draw(g, x, y, w, h, r, c, strength);
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

	/**
	 * 키 안내 한 줄: 키 모양 상자와 설명을 {@code cx} 가운데에 맞춰 그린다(결과·일시정지 카드 아래). 대전 화면의 안내와 같은
	 * 모양이다. {@code keys} 는 {키, 설명} 짝들이고 {@code c} 는 칸 크기(화소)다.
	 */
	static void keys(Graphics2D g, String[][] keys, double cx, double baseline, int c) {
		g.setFont(keysFont(c));
		FontMetrics fm = g.getFontMetrics();
		double kx = cx - keysWidth(fm, keys, c) / 2;
		for (String[] k : keys) {
			double kw = fm.stringWidth(k[0]) + 0.6 * c;
			card(g, kx, baseline - 0.62 * c, kw, 0.85 * c, 0.3 * c, SURFACE_HI, BORDER);
			g.setColor(TEXT);
			g.drawString(k[0], (float) (kx + 0.3 * c), (float) baseline);
			kx += kw + 0.25 * c;
			g.setColor(TEXT_DIM);
			g.drawString(k[1], (float) kx, (float) baseline);
			kx += fm.stringWidth(k[1]) + 0.9 * c;
		}
	}

	/**
	 * {@link #keys} 가 그릴 키 안내 한 줄의 너비 (같은 글꼴, 같은 간격): 첫 키 상자의 왼쪽 끝부터 마지막 설명의 끝까지. 카드 너비를
	 * 키 안내에 맞출 때 쓴다(결과 카드). {@code g} 의 글꼴은 바꾸지 않는다.
	 */
	static double keysWidth(Graphics2D g, String[][] keys, int c) {
		return keysWidth(g.getFontMetrics(keysFont(c)), keys, c);
	}

	private static double keysWidth(FontMetrics fm, String[][] keys, int c) {
		double total = 0;
		for (String[] k : keys) {
			total += fm.stringWidth(k[0]) + 0.6 * c + 0.25 * c + fm.stringWidth(k[1]) + 0.9 * c;
		}
		return total - 0.9 * c;
	}

	/** 키 안내의 글꼴: 칸 크기의 0.48 배(13 화소 이상), 굵게. */
	private static Font keysFont(int c) {
		return font(Math.max(13, 0.48 * c), true);
	}

	/** 지금 글꼴로 {@code cx} 가운데에 그린다 ({@link #text} 의 CENTER). */
	static void centered(Graphics2D g, String s, double cx, double baseline) {
		text(g, s, cx, baseline, Align.CENTER);
	}

	/** 지금 글꼴로 오른쪽 끝을 {@code rx} 에 맞춰 그린다 ({@link #text} 의 RIGHT). */
	static void right(Graphics2D g, String s, double rx, double baseline) {
		text(g, s, rx, baseline, Align.RIGHT);
	}
}
