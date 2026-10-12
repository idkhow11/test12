package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

import kr.ac.jbnu.se.tetris.sand.SandSnapshot;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;

/**
 * 모래 화면의 덮개: 카운트다운과 GO(SPEC V8), 일시정지 카드(V11), 오류 카드, 결과 카드(V10 의 움직임). 대전 화면과 같은 시간과
 * 글자 역할을 쓴다. 판이 끝난 뒤와 멈춘 동안의 움직임은 판의 실제 시간 시계({@link SandSession#clockNanos()})로 잰다.
 * <p>
 * 결과 카드에는 단추 셋이 있다: <b>다시 하기</b>(새 시드), <b>같은 판 다시</b>(같은 시드), <b>메뉴로</b>. 대전 화면의 결과
 * 카드와 같이 이동 키(←→)로 고르고 확인 키(Enter)로 정한다. 처음에는 다시 하기가 골라져 있어 Enter 는 예전처럼 다시 하기다.
 * 다시 하기의 다른 키(R)는 언제나 다시 하기, Esc 는 언제나 메뉴로. 같은 판 다시도 ←→ 로 고르고 Enter 로 정한다(따로 된 키는
 * 없다). 마우스로 눌러도 된다. 아래 키 안내는 대전 결과 카드와 같다({@link BattleResult#keyHints}). 전체 화면 키는 창이 듣는다.
 */
final class SandCards {

	/** 결과 카드의 단추: 다시 하기, 같은 판 다시, 메뉴로. */
	static final String[] BUTTONS = { "다시 하기", "같은 판 다시", "메뉴로" };
	static final int RETRY = 0;
	static final int SAME_SEED = 1;
	static final int MENU = 2;

	/** 카운트다운 숫자 색 (SPEC V8): 3, 2, 1. */
	private static final Color[] DIGIT = { null, new Color(0xFFB3B3), new Color(0xFAD9BF), new Color(0xB3CCFA) };
	private static final Color GO = new Color(0x4ADE80);
	/** 일시정지 카드가 들어오고 나가는 시간 (ms). */
	static final int PAUSE_IN_MS = 200;
	static final int PAUSE_OUT_MS = 150;
	/** 결과 카드: 들어오는 시간, 숫자가 올라가는 시간 (ms). */
	static final int CARD_IN_MS = 260;
	static final int COUNT_MS = 400;
	/**
	 * 결과 카드의 끝난 이유. 모래 판은 정착한 모래가 판의 점선(넘침 선) 위까지 쌓일 때만 끝난다(오류로 멈춘 판은 오류 카드다).
	 * 대전 결과 카드의 "꼭대기까지 쌓임", 메뉴·도움말·목표 카드의 "점선 위까지 쌓이면 끝나요" 와 같은 말이다.
	 */
	static final String OVER_REASON = "점선 위까지 쌓임";

	private SandCards() {
	}

	// ------------------------------------------------------------------ 카운트다운

	/**
	 * 카운트다운 숫자 (SPEC V8, 대전 화면과 같다): 판이 보이게 25% 만 어둡게 하고, 숫자마다 1.6배에서 220ms 에 1배로 튀어나와
	 * (easeOutBack) 1.8배로 퍼지는 옅은 메아리를 남기고, 700~1000ms 에 0.6배로 줄며 사라진다. 뒤에 흰 원판(반지름 3c), 위에
	 * "준비". 정보라서 효과를 꺼도 그대로 움직인다.
	 *
	 * @param left 남은 시간 (ms, 0 보다 크다, {@link SandSession#countdownMs()})
	 */
	static void countdown(Graphics2D g, int w, int h, int c, long left) {
		veil(g, w, h, 0.25f);
		int digit = (int) Math.max(1, Math.min(3, (left + 999) / 1000));
		long tau = Math.max(0, Math.min(999, digit * 1000L - left)); // 이 숫자가 뜬 뒤 (ms)
		double cx = w / 2.0;
		double cy = h / 2.0;
		Color color = DIGIT[digit];
		disc(g, cx, cy, 3 * c, 0.18 * (1 - tau / 1000.0));
		String text = String.valueOf(digit);
		double px = 4.2 * c;
		if (tau < 500) { // 메아리
			double u = tau / 500.0;
			drawScaled(g, text, px, cx, cy, 1 + 0.8 * Motion.easeOutQuad(u), 0.5 * (1 - u), color);
		}
		double scale;
		double alpha;
		if (tau < 220) {
			scale = 1.6 - 0.6 * Motion.easeOutBack(tau / 220.0);
			alpha = Motion.clamp01(tau / 80.0);
		} else if (tau >= 700) {
			double u = Motion.easeInCubic((tau - 700) / 300.0);
			scale = 1 - 0.4 * u;
			alpha = 1 - u;
		} else {
			scale = 1;
			alpha = 1;
		}
		drawScaled(g, text, px, cx, cy, scale, alpha, color);
		g.setColor(Fx.alpha(Theme.TEXT, 0.7));
		Theme.Type.TITLE.draw(g, "준비", 0.6 * c, cx, cy - 2.7 * c, Theme.Align.CENTER);
	}

	/** 숫자 뒤의 흰 원판: 가운데 알파 a 에서 반지름 r 끝으로 투명하게. */
	private static void disc(Graphics2D g, double cx, double cy, double r, double a) {
		if (!(a > 0)) {
			return;
		}
		int d = (int) Math.round(2 * r);
		BufferedImage dot = Fx.dot(g, Color.WHITE, Math.min(Fx.MAX_DOT, d));
		Composite old = g.getComposite();
		AlphaComposite ac = Fx.times(old, a);
		if (ac != null) {
			g.setComposite(ac);
		}
		g.drawImage(dot, (int) Math.round(cx - r), (int) Math.round(cy - r), d, d, null);
		g.setComposite(old);
	}

	/**
	 * "GO!" (SPEC V8, 0.7초, 덮개 없음): 0.6 → 1.15 → 1 로 튀어나오고 마지막 0.2초에 옅어진다. 퍼지는 고리는 번쩍임 줄이기나
	 * 효과 끔이면 없다.
	 *
	 * @param age "GO!" 가 뜬 뒤 (ms)
	 */
	static void go(Graphics2D g, int w, int h, int c, long age, double m, boolean calm) {
		double cx = w / 2.0;
		double cy = h / 2.0;
		double scale = age < 150 ? 0.6 + 0.55 * Motion.easeOutCubic(age / 150.0)
				: age < 250 ? 1.15 - 0.15 * Motion.easeOutCubic((age - 150) / 100.0) : 1;
		double alpha = age > SandSession.GO_MS - 200 ? Motion.clamp01((SandSession.GO_MS - age) / 200.0) : 1;
		if (!calm && m > 0 && age < 400) {
			double u = age / 400.0;
			double r = (1.5 + 2.5 * Motion.easeOutCubic(u)) * c;
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke((float) ((0.15 - 0.13 * u) * c)));
			g.setColor(Fx.alpha(GO, 0.6 * (1 - u) * Math.min(1, m)));
			g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
			g.setStroke(old);
		}
		drawScaled(g, "GO!", 3.0 * c, cx, cy, scale, alpha, GO);
	}

	/** DISPLAY 글자의 세로 가운데를 (cx, cy) 에 두고 {@code scale} 배로, 알파와 함께 그린다. */
	private static void drawScaled(Graphics2D g, String text, double px, double cx, double cy, double scale,
			double alpha, Color color) {
		if (!(alpha > 0) || !(scale > 0)) {
			return;
		}
		AffineTransform old = SandFx.scaleAbout(g, cx, cy, scale);
		g.setColor(Fx.alpha(color, alpha));
		Theme.Type.DISPLAY.draw(g, text, px, cx, cy + 0.35 * px, Theme.Align.CENTER);
		g.setTransform(old);
	}

	// ------------------------------------------------------------------ 일시정지, 오류

	/**
	 * 일시정지 카드 (SPEC V11): 0.2초 동안 커지며(0.96 → 1) 나타나고, 계속하면 0.15초에 사라진다. 판은 부르는 쪽이 가린다.
	 *
	 * @param shown 0~1: 나타난 정도 (들어올 때 오르고 나갈 때 내린다)
	 */
	static void pause(Graphics2D g, int w, int h, int c, KeyBindings keys, double shown) {
		if (shown <= 0) {
			return;
		}
		double cw = 11 * c;
		double ch = 4.2 * c;
		double x = (w - cw) / 2;
		double y = (h - ch) / 2;
		AffineTransform old = SandFx.scaleAbout(g, w / 2.0, h / 2.0, 0.96 + 0.04 * shown);
		Composite oc = g.getComposite();
		AlphaComposite a = Fx.times(oc, shown);
		if (a != null) {
			g.setComposite(a);
		}
		Theme.card(g, x, y, cw, ch, 0.8 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, cw, ch, 0.8 * c, Theme.SAND, 0.5);
		g.setColor(Theme.TEXT);
		Theme.Type.TITLE.draw(g, "일시정지", 1.2 * c, x + cw / 2, y + 2.0 * c, Theme.Align.CENTER);
		Theme.keys(g, new String[][] { { keys.describe(KeyAction.PAUSE, 1), "계속" },
				{ keys.describe(KeyAction.MENU, 1), "메뉴로" } }, x + cw / 2, y + 3.25 * c, c);
		g.setComposite(oc);
		g.setTransform(old);
	}

	/** 오류 카드: 짧은 이유와 다시 하기·메뉴로. */
	static void failure(Graphics2D g, int w, int h, int c, KeyBindings keys, String reason) {
		veil(g, w, h, 0.62f);
		double cw = 12 * c;
		double ch = 5.4 * c;
		double x = (w - cw) / 2;
		double y = (h - ch) / 2;
		Theme.card(g, x, y, cw, ch, 0.8 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, cw, ch, 0.8 * c, Theme.WARN, 0.6);
		g.setColor(Theme.WARN);
		Theme.Type.TITLE.draw(g, "문제가 생겼어요", 1.1 * c, x + cw / 2, y + 1.75 * c, Theme.Align.CENTER);
		g.setColor(Theme.TEXT_DIM);
		Theme.Type.BODY.draw(g, reason, Math.max(13, 0.48 * c), x + cw / 2, y + 2.75 * c, Theme.Align.CENTER);
		Theme.keys(g, new String[][] { { keys.describe(KeyAction.RETRY, 1), "다시 하기" },
				{ keys.describe(KeyAction.MENU, 1), "메뉴로" } }, x + cw / 2, y + ch - 0.9 * c, c);
	}

	// ------------------------------------------------------------------ 결과

	/**
	 * 결과 카드 (SPEC V10 의 움직임): 0.26초 동안 커지며(0.94 → 1) 나타나고, 제목이 1.25 → 1 로 자리 잡고, 점수가 0.4초 동안
	 * 올라가고, 통계 줄이 차례로(0.12초 + 줄마다 0.04초) 떠오른다. 점수 아래 한 줄은 끝난 이유 · 최고(새 기록이면 이전 최고) ·
	 * 시간이다. 아래에 단추 셋(다시 하기, 같은 판 다시, 메뉴로)과 키 안내(대전 결과 카드와 같은 안내,
	 * {@link BattleResult#keyHints}). 카드 너비는 14c 이고, 키 안내나 이유 줄이 들어가지 않으면 그만큼 넓힌다.
	 *
	 * @param age     카드가 뜬 뒤 (ms)
	 * @param focus   고른 단추 ({@link #RETRY}, {@link #SAME_SEED}, {@link #MENU})
	 * @param buttons 단추 자리를 여기에 적는다 (마우스용, 길이 3)
	 */
	static void result(Graphics2D g, int w, int h, int c, SandSession session, SandSnapshot s, long age, int focus,
			boolean accepting, Rectangle[] buttons) {
		veil(g, w, h, (float) (0.62 * Motion.clamp01(age / (double) CARD_IN_MS)));
		// 새 기록: 기록에 더했으면 플레이 기록의 판단(더하기 바로 전의 저장 파일과 비교, 첫 판은 아님), 아직이면 판의 판단(같은 규칙)
		boolean best = session.recorded() ? session.newBest() : s.newBest();
		String record = best ? "이전 최고 " + SandPanel.number(session.previousBest())
				: "최고 " + SandPanel.number(session.bestScore(s.best()));
		String meta = OVER_REASON + "  ·  " + record + "  ·  " + SandPanel.clockMs(s.time());
		double metaPx = Math.max(12, 0.5 * c);
		String[][] hints = BattleResult.keyHints(session.human.keys());
		double cw = Math.max(14 * c, Math.max(BattleResult.keysWidth(g, hints, c), Theme.Type.BODY.width(g, meta, metaPx))
				+ 1.6 * c); // 키 안내와 이유 줄이 카드 안에 들어가게 (대전 결과 카드와 같은 방법)
		double ch = 15.6 * c;
		double x = (w - cw) / 2;
		double y = (h - ch) / 2;
		double enter = Motion.enter(Motion.clamp01(age / (double) CARD_IN_MS));
		AffineTransform old = SandFx.scaleAbout(g, w / 2.0, h / 2.0, 0.94 + 0.06 * enter);
		Composite oc = g.getComposite();
		AlphaComposite fade = Fx.times(oc, enter);
		if (fade != null) {
			g.setComposite(fade);
		}
		Color tc = best ? Theme.GOLD : Theme.SAND;
		Theme.card(g, x, y, cw, ch, 0.9 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, cw, ch, 0.9 * c, tc, 0.7);

		// 제목: 1.25 → 1 (easeOutBack)
		double titleScale = 1.25 - 0.25 * Motion.easeOutBack(Motion.clamp01(age / 240.0));
		double tx = x + cw / 2;
		double ty = y + 1.95 * c;
		AffineTransform t0 = SandFx.scaleAbout(g, tx, ty - 0.5 * c, titleScale);
		g.setColor(tc);
		Theme.Type.TITLE.draw(g, best ? SandHud.NEW_BEST : "게임 끝", 1.45 * c, tx, ty, Theme.Align.CENTER);
		g.setTransform(t0);

		// 점수: 0.4초 동안 올라간다
		double count = Motion.easeOutCubic(Motion.clamp01((age - 120) / (double) COUNT_MS));
		g.setColor(best ? Theme.GOLD : Theme.TEXT);
		SandHud.displayNumber(g, SandPanel.number(Math.round(s.score() * count)), 2.5 * c, tx, y + 4.65 * c,
				Theme.Align.CENTER);
		g.setColor(Theme.LABEL);
		Theme.Type.BODY.draw(g, meta, metaPx, tx, y + 5.6 * c, Theme.Align.CENTER);

		// 통계 줄: 차례로 떠오른다
		String[][] rows = { { "단계", session.startLevel() == s.level() ? String.valueOf(s.level())
				: session.startLevel() + " → " + s.level() }, { "지운 횟수", SandPanel.number(s.clears()) },
				{ "지운 알갱이", SandPanel.number(s.grainsCleared()) }, { "최대 배율", "×" + s.maxMultiplier() },
				{ "블록", SandPanel.number(s.pieces()) } };
		double rowY = y + 6.75 * c;
		double step = 0.9 * c;
		for (int r = 0; r < rows.length; r++) {
			double u = Motion.easeOutCubic(Motion.clamp01((age - 120 - 40 * r) / 200.0));
			if (u <= 0) {
				continue;
			}
			double by = rowY + r * step + 0.5 * c * (1 - u);
			Composite rc = g.getComposite();
			AlphaComposite ra = Fx.times(rc, u);
			if (ra != null) {
				g.setComposite(ra);
			}
			if (r % 2 == 0) {
				g.setColor(new Color(255, 255, 255, 8));
				g.fill(new RoundRectangle2D.Double(x + 0.9 * c, by - 0.6 * c, cw - 1.8 * c, step, 0.3 * c, 0.3 * c));
			}
			g.setColor(Theme.LABEL);
			Theme.Type.LABEL.draw(g, rows[r][0], Math.max(11, 0.5 * c), x + 1.4 * c, by, Theme.Align.LEFT);
			g.setColor(Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, rows[r][1], 0.75 * c, x + cw - 1.4 * c, by, Theme.Align.RIGHT);
			g.setComposite(rc);
		}
		g.setColor(Theme.LABEL);
		Theme.Type.BODY.draw(g, "시드 " + session.seed(), Math.max(11, 0.4 * c), tx, rowY + rows.length * step + 0.05 * c,
				Theme.Align.CENTER);

		// 단추 셋과 키 안내 ("← → 고르기 · Enter 확인 · R 다시 하기 · Esc 메뉴로", 지금 키 묶음의 키 이름)
		buttons(g, x, y + ch - 3.35 * c, cw, c, focus, accepting, g.getTransform(), buttons);
		Theme.keys(g, hints, tx, y + ch - 0.85 * c, c);
		g.setComposite(oc);
		g.setTransform(old);
	}

	/**
	 * 단추 셋을 가로로 그린다. 고른 단추는 모래 주황 테두리와 빛. 키를 아직 받지 않으면(결과 카드가 막 뜸) 옅게. 마우스용으로 화면
	 * 좌표의 자리를 {@code out} 에 적는다(카드가 커지는 중이면 그 크기 그대로).
	 */
	private static void buttons(Graphics2D g, double x, double top, double cw, int c, int focus, boolean accepting,
			AffineTransform at, Rectangle[] out) {
		double px = Math.max(12, 0.52 * c);
		double gap = 0.3 * c;
		double padX = 0.7 * c;
		double bh = 1.35 * c;
		double[] widths = new double[BUTTONS.length];
		double total = gap * (BUTTONS.length - 1);
		for (int i = 0; i < BUTTONS.length; i++) {
			widths[i] = Theme.Type.TITLE.width(g, BUTTONS[i], px) + 2 * padX;
			total += widths[i];
		}
		double inner = cw - 1.6 * c;
		if (total < inner) { // 남는 너비는 단추마다 고르게 나눈다
			double extra = (inner - total) / BUTTONS.length;
			for (int i = 0; i < widths.length; i++) {
				widths[i] += extra;
			}
			total = inner;
		}
		double bx = x + (cw - total) / 2;
		for (int i = 0; i < BUTTONS.length; i++) {
			boolean on = i == focus;
			Theme.card(g, bx, top, widths[i], bh, 0.45 * c, on ? Theme.SURFACE_HI : Theme.withAlpha(Theme.SURFACE_HI, 120),
					on ? Theme.SAND : Theme.BORDER);
			if (on && accepting) {
				Theme.glow(g, bx, top, widths[i], bh, 0.45 * c, Theme.SAND, 0.55);
			}
			g.setColor(on ? Theme.TEXT : Theme.TEXT_DIM);
			Theme.Type.TITLE.draw(g, BUTTONS[i], px, bx + widths[i] / 2, top + bh / 2 + 0.36 * px, Theme.Align.CENTER);
			if (out != null) {
				out[i] = at.createTransformedShape(new java.awt.geom.Rectangle2D.Double(bx, top, widths[i], bh)).getBounds();
			}
			bx += widths[i] + gap;
		}
	}

	// ------------------------------------------------------------------ 바탕

	/** 화면 전체를 검게 {@code alpha} 만큼 덮는다. */
	static void veil(Graphics2D g, int w, int h, float alpha) {
		if (alpha <= 0) {
			return;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.min(1f, alpha)));
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, w, h);
		g.setComposite(old);
	}
}
