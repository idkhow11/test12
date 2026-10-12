package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;

/**
 * 대전 화면 위에 덮는 것 (runs/quality/SPEC.md V8, V11): 카운트다운 3·2·1 과 GO!, 일시정지 카드, 아래쪽 알림(기권 확인, AI 가
 * 기준 AI 로 바뀜), 오류 카드, 화면 전환의 검은 덮개. 모두 화면 크기와 칸 크기 c 로 자리를 정하고, 움직임은 넘겨받은 시간으로
 * 계산한다(상태 없음).
 */
final class BattleOverlay {

	/** 카운트다운 숫자의 색 (SPEC V8): 3 하늘, 2 살구, 1 분홍. */
	private static final Color THREE = new Color(0xB3CCFA);
	private static final Color TWO = new Color(0xFAD9BF);
	private static final Color ONE = new Color(0xFFB3B3);
	/** Barlow 숫자의 세로 가운데: 기준선에서 글자 크기의 이만큼 위 (대문자·숫자 높이의 절반). */
	private static final double MID = 0.35;
	/** 알림이 나타나는 시간 (ms). */
	static final int TOAST_IN_MS = 200;

	private BattleOverlay() {
	}

	/** 화면 전체를 검게 덮는다 (알파 a). */
	static void veil(Graphics2D g, int w, int h, double a) {
		BattleFx.fill(g, Color.BLACK, a, 0, 0, w, h);
	}

	// ------------------------------------------------------------------ 카운트다운과 GO!

	/**
	 * 카운트다운 (SPEC V8): 판이 보이게 25% 만 어둡게 하고, 숫자마다 1.6배에서 220ms 에 1배로 튀어나와(easeOutBack) 1.8배로
	 * 퍼지는 옅은 메아리를 남기고, 700~1000ms 에 0.6배로 줄며 사라진다. 뒤에 흰 원판(반지름 3c), 위에 "준비". 정보라서 효과를
	 * 꺼도 그대로 움직인다.
	 *
	 * @param msLeft 남은 시간 ({@link GameSession#countdownMs()})
	 * @param held   AI 모델을 불러오느라 1 에서 멈춰 있다 (숫자를 그대로 두고 "AI 불러오는 중")
	 */
	static void countdown(Graphics2D g, int w, int h, int c, long msLeft, boolean held) {
		veil(g, w, h, 0.25);
		int digit = (int) Math.max(1, (msLeft + 999) / 1000);
		long tau = digit * 1000L - msLeft; // 이 숫자가 뜬 뒤 흐른 시간 0~999
		if (held) {
			tau = Math.min(tau, 699);
		}
		Color col = digit >= 3 ? THREE : digit == 2 ? TWO : ONE;
		String text = String.valueOf(digit);
		double cx = w / 2.0;
		double cy = h / 2.0;
		disc(g, cx, cy, 3 * c, 0.18 * (1 - tau / 1000.0));
		Font f = Theme.display(4.2 * c);
		if (tau < 500) { // 메아리
			double u = tau / 500.0;
			scaledText(g, f, text, cx, cy, 1 + 0.8 * Motion.easeOutQuad(u), 0.5 * (1 - u), col);
		}
		double k;
		double a;
		if (tau < 220) {
			k = 1.6 - 0.6 * Motion.easeOutBack(tau / 220.0);
			a = Motion.clamp01(tau / 80.0);
		} else if (tau < 700) {
			k = 1;
			a = 1;
		} else {
			double u = (tau - 700) / 300.0;
			k = 1 - 0.4 * Motion.easeInCubic(u);
			a = 1 - Motion.easeInCubic(u);
		}
		scaledText(g, f, text, cx, cy, k, a, col);
		Composite old = BattleFx.alpha(g, 0.7);
		g.setColor(Theme.TEXT);
		Theme.Type.TITLE.draw(g, "준비", 0.6 * c, cx, cy - 2.7 * c, Theme.Align.CENTER);
		g.setComposite(old);
		if (held) {
			g.setColor(Theme.TEXT_DIM);
			Theme.Type.BODY.draw(g, "AI 불러오는 중", 0.5 * c, cx, cy + 2.6 * c, Theme.Align.CENTER);
		}
	}

	/**
	 * GO! (SPEC V8, 700ms): 0.6배에서 150ms 에 1.15배, 250ms 에 1배. 고리가 반지름 1.5c 에서 4c 로 퍼지며 사라진다(400ms,
	 * 번쩍임 줄이기나 효과 끔이면 없음). 끝의 200ms 에 옅어진다. 덮개 없음.
	 */
	static void go(Graphics2D g, int w, int h, int c, long age, BattleStyle st) {
		double cx = w / 2.0;
		double cy = h / 2.0;
		if (!st.calm() && st.m() > 0 && age < 400) {
			double u = age / 400.0;
			double r = c * (1.5 + 2.5 * Motion.easeOutCubic(u));
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke((float) (c * (0.15 - 0.13 * u))));
			g.setColor(Fx.alpha(Theme.OK, 0.6 * (1 - u) * Math.min(1, st.m())));
			g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
			g.setStroke(old);
		}
		double k = age < 150 ? 0.6 + 0.55 * Motion.easeOutCubic(age / 150.0)
				: age < 250 ? 1.15 - 0.15 * Motion.easeOutCubic((age - 150) / 100.0) : 1;
		double a = age > 500 ? Motion.clamp01((700 - age) / 200.0) : 1;
		scaledText(g, Theme.display(3.0 * c), "GO!", cx, cy, k, a, Theme.OK);
	}

	/** 뒤의 흰 원판: 가운데 알파 a 에서 반지름 r 끝으로 투명하게. */
	private static void disc(Graphics2D g, double cx, double cy, double r, double a) {
		if (!(a > 0)) {
			return;
		}
		int d = (int) Math.round(2 * r);
		BufferedImage dot = Fx.dot(g, Color.WHITE, Math.min(Fx.MAX_DOT, d));
		Composite old = BattleFx.alpha(g, a);
		g.drawImage(dot, (int) Math.round(cx - r), (int) Math.round(cy - r), d, d, null);
		g.setComposite(old);
	}

	/** 가운데 (cx, cy) 를 붙잡고 k 배로, 알파 a 로 그린다 (Barlow 숫자의 세로 가운데에 맞춘다). */
	private static void scaledText(Graphics2D g, Font f, String s, double cx, double cy, double k, double a, Color col) {
		if (!(a > 0) || !(k > 0)) {
			return;
		}
		AffineTransform saved = g.getTransform();
		Composite old = BattleFx.alpha(g, a);
		g.translate(cx, cy);
		g.scale(k, k);
		g.setFont(f);
		g.setColor(col);
		Theme.text(g, s, 0, MID * f.getSize2D(), Theme.Align.CENTER);
		g.setTransform(saved);
		g.setComposite(old);
	}

	// ------------------------------------------------------------------ 일시정지, 알림

	/**
	 * 일시정지 카드 (SPEC V11): 들어올 때 200ms 동안 나타나며 0.96배에서 1배로, 나갈 때 150ms 동안 사라진다. 멈춘 동안 화면이
	 * 보드를 가리므로 덮개는 옅다(30%). 사람 대 AI 는 떠나면 기권이므로 "기권하고 메뉴로" 라고 쓴다.
	 *
	 * @param a     카드의 알파 (0~1)
	 * @param scale 카드의 크기 배수
	 */
	static void pause(Graphics2D g, int w, int h, int c, KeyBindings keys, boolean versus, double a, double scale) {
		veil(g, w, h, 0.3 * a);
		double cw = (versus ? 12.5 : 11) * c;
		double ch = 4.2 * c;
		double x = (w - cw) / 2;
		double y = (h - ch) / 2;
		AffineTransform saved = g.getTransform();
		Composite old = BattleFx.alpha(g, a);
		if (scale != 1) {
			g.translate(w / 2.0, h / 2.0);
			g.scale(scale, scale);
			g.translate(-w / 2.0, -h / 2.0);
		}
		Theme.card(g, x, y, cw, ch, 0.8 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, cw, ch, 0.8 * c, Theme.ACCENT, 0.5);
		g.setColor(Theme.TEXT);
		Theme.Type.TITLE.draw(g, "일시정지", 1.2 * c, w / 2.0, y + 2.0 * c, Theme.Align.CENTER);
		Theme.keys(g, new String[][] { { keys.describe(KeyAction.PAUSE, 1), "계속" },
				{ keys.describe(KeyAction.MENU, 1), versus ? "기권하고 메뉴로" : "메뉴로" } }, w / 2.0, y + 3.25 * c, c);
		g.setTransform(saved);
		g.setComposite(old);
	}

	/**
	 * 화면 아래 가운데의 둥근 알림 한 줄. 처음 200ms 동안 0.5c 아래에서 올라오며 나타나고, {@code lifeMs} 가 있으면 끝의 200ms 에
	 * 사라진다.
	 *
	 * @param age    알림이 뜬 뒤 흐른 시간 (ms). 모르면 {@link Motion#LONG} 이상을 넘긴다
	 * @param lifeMs 알림의 길이 (ms), 정해지지 않았으면 0
	 */
	static void toast(Graphics2D g, int w, int h, int c, String text, Color bg, Color fg, long age, long lifeMs) {
		double in = Motion.enter(Motion.clamp01(age / (double) TOAST_IN_MS));
		double out = lifeMs > 0 && age > lifeMs - TOAST_IN_MS ? Motion.clamp01((lifeMs - age) / (double) TOAST_IN_MS)
				: 1;
		double a = in * out;
		if (!(a > 0)) {
			return;
		}
		g.setFont(Theme.font(0.5 * c, true));
		FontMetrics fm = g.getFontMetrics();
		double tw = fm.stringWidth(text) + 1.4 * c;
		double th = 1.1 * c;
		double x = (w - tw) / 2;
		double y = h - th - 0.5 * c + 0.5 * c * (1 - in);
		Composite old = BattleFx.alpha(g, a);
		Theme.card(g, x, y, tw, th, th, bg, null);
		g.setColor(fg);
		Theme.centered(g, text, x + tw / 2, y + th / 2 + fm.getAscent() / 2.8);
		g.setComposite(old);
	}

	/** 오류로 멈춘 판: 짧은 이유와 다시 하기·메뉴로. 자세한 오류는 콘솔에 남는다. */
	static void failure(Graphics2D g, int w, int h, int c, KeyBindings keys, String reason) {
		veil(g, w, h, 0.62);
		double cw = 12 * c;
		double ch = 5.4 * c;
		double x = (w - cw) / 2;
		double y = (h - ch) / 2;
		Theme.card(g, x, y, cw, ch, 0.8 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, cw, ch, 0.8 * c, Theme.WARN, 0.6);
		g.setColor(Theme.WARN);
		Theme.Type.TITLE.draw(g, "문제가 생겼어요", 1.1 * c, w / 2.0, y + 1.75 * c, Theme.Align.CENTER);
		g.setColor(Theme.TEXT_DIM);
		Theme.Type.BODY.draw(g, reason, Math.max(13, 0.48 * c), w / 2.0, y + 2.75 * c, Theme.Align.CENTER);
		Theme.keys(g, new String[][] { { keys.describe(KeyAction.RETRY, 1), "다시 하기" },
				{ keys.describe(KeyAction.MENU, 1), "메뉴로" } }, w / 2.0, y + ch - 0.9 * c, c);
	}
}
