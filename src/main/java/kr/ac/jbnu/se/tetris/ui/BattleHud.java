package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 대전 화면의 움직이지 않는 글자 (runs/quality/SPEC.md V2, V11): 위 띠(시계, 블록 속도 단계, 딥러닝 예측 승률 막대와 흐름, 크게
 * 움직였을 때의 알림)와 보드 위 이름표. 숫자는 고정 폭으로 그려 바뀌어도 옆으로 흔들리지 않는다. 승률 막대는 실제 값을
 * τ = 180ms 로 부드럽게 따라간다(프레임 간격과 무관, {@link Motion#damp}). 그 따라가는 값만 상태로 갖는다(대전 화면 하나에 하나).
 * 중력 단계는 메뉴의 "블록 속도"와 같은 말로 쓴다(AI 의 조작 빠르기인 "AI 속도"와 헷갈리지 않게).
 */
final class BattleHud {

	/** 승률 막대가 실제 값을 따라가는 시간 상수 (ms). */
	static final int WIN_BAR_TAU_MS = 180;
	/** 위 띠 안의 자리 (위 띠의 위에서 칸 단위): 블록 속도 줄의 기준선, 승률 막대의 위. 두 줄 글자가 붙지 않게 띄운다. */
	private static final double SPEED_BASELINE = 1.5;
	private static final double BAR_TOP = 2.36;
	/** 승률 흐름을 솎을 때 한 칸으로 묶는 장치 화소 수 (칸마다 가장 높은 점과 가장 낮은 점, 그래서 꼭짓점은 폭의 화소 수까지). */
	private static final int THIN_PX = 2;

	private GameSession shownFor; // 아래 값이 어느 판의 것인가
	private double shownP = Double.NaN; // 화면에 보이는 예측 승률
	private long shownAt; // 그 값의 효과 시각 (ms)

	/** 위 띠: 시계, 블록 속도 단계, 예측 승률 막대와 흐름, 크게 움직였을 때의 알림. */
	void paint(Graphics2D g, GameSession s, int width, int c, int y0, long now) {
		TimedMatch m = s.match;
		double cx = width / 2.0;
		long sec = m.time() / 1000;
		g.setColor(Theme.TEXT);
		g.setFont(Theme.numeric(1.15 * c));
		Theme.drawNumber(g, String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60), cx, y0 + 1.0 * c,
				Theme.Align.CENTER);
		if (m.gravityLevel() >= 0) {
			g.setColor(Theme.TEXT_DIM);
			Theme.Type.BODY.draw(g, "블록 속도 " + (m.gravityLevel() + 1) + "단계", 0.46 * c, cx,
					y0 + SPEED_BASELINE * c, Theme.Align.CENTER);
		}
		double p = s.p1Win();
		if (m.size() < 2 || Double.isNaN(p)) {
			return;
		}
		// 블록이 놓일 때마다 값이 순간 이동하지 않게 τ = 180ms 로 따라간다 (판이 끝난 뒤에도 세션 시계로 마저 간다)
		if (shownFor != s || Double.isNaN(shownP)) {
			shownFor = s;
			shownP = p;
		} else {
			shownP = Motion.damp(shownP, p, now - shownAt, WIN_BAR_TAU_MS);
		}
		shownAt = now;
		double q = shownP;
		double w = 13 * c;
		double h = 0.62 * c;
		double x = cx - w / 2;
		double y = y0 + BAR_TOP * c;
		winBar(g, x, y, w, h, q);
		g.setColor(new Color(255, 255, 255, 210)); // 50% 눈금
		g.fill(new Rectangle2D.Double(cx - 1, y - 0.12 * c, 2, h + 0.24 * c));
		// "나 58%" · "42% AI": % 는 큰 숫자, 이름은 같은 기준선의 작은 굵은 글자
		double base = y + h * 0.95;
		String left = Math.round(100 * q) + "%";
		String right = Math.round(100 * (1 - q)) + "%";
		g.setFont(Theme.display(1.1 * c));
		g.setColor(Theme.P1);
		float wl = Theme.drawNumber(g, left, x - 0.3 * c, base, Theme.Align.RIGHT);
		Theme.Type.TITLE.draw(g, s.name(0), 0.62 * c, x - 0.3 * c - wl - 0.18 * c, base, Theme.Align.RIGHT);
		g.setFont(Theme.display(1.1 * c));
		g.setColor(Theme.P2);
		float wr = Theme.drawNumber(g, right, x + w + 0.3 * c, base, Theme.Align.LEFT);
		Theme.Type.TITLE.draw(g, s.name(1), 0.62 * c, x + w + 0.3 * c + wr + 0.18 * c, base, Theme.Align.LEFT);
		g.setColor(Theme.LABEL);
		Theme.Type.BODY.draw(g, "딥러닝 예측 승률", 0.44 * c, cx, y - 0.24 * c, Theme.Align.CENTER);
		winChart(g, s, m.time(), x, y + h + 0.2 * c, w, 0.55 * c, false, c);
		long age = m.time() - s.swingAt;
		if (!s.over() && age >= 0 && age < 1600) { // 크게 움직였을 때: "AI ▲ 12%p · AI 공격 3줄"
			swing(g, s, age, x, y + h + 0.15 * c, w, c);
		}
	}

	/**
	 * 승률 막대: P2 색 알약 위에 왼쪽부터 {@code q} 만큼 P1 색 (같은 알약을 잘라서). 알약은 둥근 카드({@link Theme#card})라서
	 * 프레임마다 안티앨리어싱 도형을 칠하지 않고 조각 그림을 다시 쓴다(D3D 에서 싸다). 카드도 잘라내기를 따른다. 뒤의 g 색은 P1 이다.
	 */
	static void winBar(Graphics2D g, double x, double y, double w, double h, double q) {
		Theme.card(g, x, y, w, h, h, Theme.P2, null);
		Shape old = g.getClip();
		g.clip(new Rectangle2D.Double(x, y, w * q, h));
		Theme.card(g, x, y, w, h, h, Theme.P1, null);
		g.setClip(old);
	}

	private static void swing(Graphics2D g, GameSession s, long age, double x, double chipY, double w, int c) {
		boolean p1Gains = s.swingDelta > 0;
		String text = s.name(p1Gains ? 0 : 1) + " ▲ " + Math.round(Math.abs(s.swingDelta) * 100) + "%p"
				+ (s.swingCause.isEmpty() ? "" : " · " + s.swingCause);
		int alpha = age > 1200 ? (int) (255 * (1600 - age) / 400.0) : 255;
		Color col = p1Gains ? Theme.P1 : Theme.P2;
		g.setFont(Theme.font(Math.max(12, 0.46 * c), true));
		FontMetrics fm = g.getFontMetrics();
		double cw = fm.stringWidth(text) + 0.8 * c;
		double ch = fm.getHeight() + 0.1 * c;
		double chipX = p1Gains ? x : x + w - cw;
		Theme.card(g, chipX, chipY, cw, ch, ch, Theme.withAlpha(Theme.BG_TOP, alpha * 230 / 255),
				Theme.withAlpha(col, alpha));
		g.setColor(Theme.withAlpha(col, alpha));
		g.drawString(text, (float) (chipX + 0.4 * c), (float) (chipY + ch / 2 + fm.getAscent() / 2.8));
	}

	// ------------------------------------------------------------------ 이름표

	/**
	 * 보드 위 이름과 꼬리표: 나(꼬리표 없음) / AI(난이도, AI 속도를 바꿨으면 "중급 · AI 속도 80%", 페르소나, 기준 AI) / 연습(방해
	 * 줄). 꼬리표 글자는 0.42c(11 화소 이상). 이긴 보드는 이름 바로 뒤에 금색 "WIN". AI 가 생각하는 동안 차오르는 고리.
	 */
	static void name(Graphics2D g, GameSession s, int i, int x, int baseline, int c, Color pc, boolean won) {
		boolean solo = s.match.size() == 1;
		String name = s.name(i);
		g.setFont(Theme.font(0.66 * c, true));
		g.setColor(Theme.TEXT);
		g.drawString(name, x, baseline);
		int cx = x + g.getFontMetrics().stringWidth(name) + (int) Math.round(0.35 * c);
		double fpx = Math.max(11, 0.42 * c);
		int gap = (int) (0.2 * c);
		if (won) {
			cx += winChip(g, cx, baseline, c) + gap;
		}
		Color chipBg = Theme.withAlpha(pc, 50);
		GameSession.Seat seat = s.seat(i);
		if (solo) {
			GameModel.Scheduler sc = s.settings.scheduler();
			Theme.chip(g, sc == GameModel.Scheduler.NONE ? "방해 줄 없음" : sc == GameModel.Scheduler.S_3 ? "방해 줄 조금씩" : "방해 줄 섞어서",
					cx, baseline - 2, fpx, pc, chipBg);
			return;
		}
		if (seat == null) {
			return; // 사람 대 AI 의 사람: 이름 "나" 가 이미 말해 준다
		}
		String level = seat.difficulty().name();
		if (s.aiSpeed() != UserSettings.DEFAULT_AI_SPEED) { // AI 속도를 바꾼 판: "중급 · AI 속도 80%"
			level += " · AI 속도 " + s.aiSpeed() + "%";
		}
		cx += Theme.chip(g, level, cx, baseline - 2, fpx, pc, chipBg) + gap;
		// 페르소나 꼬리표: 학습한 페르소나일 때만
		boolean persona = seat.difficulty().personas() && seat.persona() != null;
		if (persona && seat.persona().model() != null) {
			cx += Theme.chip(g, seat.persona().name(), cx, baseline - 2, fpx, Theme.TEXT_DIM,
					Theme.withAlpha(Theme.TEXT_DIM, 40)) + gap;
		}
		if (s.notice(i)) { // 판 도중 모델 오류로 막 바뀌었다
			cx += Theme.chip(g, "기준 AI로 바뀜", cx, baseline - 2, fpx, Theme.BG_TOP, Theme.withAlpha(Theme.WARN, 230)) + gap;
		} else if (s.baseline(i)) {
			cx += Theme.chip(g, "기준 AI", cx, baseline - 2, fpx, Theme.WARN, Theme.withAlpha(Theme.WARN, 45)) + gap;
		}
		double think = s.think(i);
		if (think >= 0 && !s.over()) { // AI 가 새 블록을 보고 생각하는 동안 차오르는 고리
			double r = 0.27 * c;
			double ox = cx + r;
			double oy = baseline - 0.27 * c - r * 0.2;
			java.awt.Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(Math.max(2f, c / 11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(Theme.withAlpha(pc, 60));
			g.draw(new Ellipse2D.Double(ox - r, oy - r, 2 * r, 2 * r));
			g.setColor(pc);
			g.draw(new Arc2D.Double(ox - r, oy - r, 2 * r, 2 * r, 90, -360 * think, Arc2D.OPEN));
			g.setStroke(old);
		}
	}

	/** 이긴 보드의 "WIN" 꼬리표: Barlow Black 0.6c, 금색 바탕에 어두운 글자 (SPEC V9). 너비를 돌려준다. */
	private static int winChip(Graphics2D g, int x, int baseline, int c) {
		g.setFont(Theme.tracked(Theme.display(0.6 * c), 0.06));
		float tw = Theme.textWidth(g, "WIN");
		int padX = (int) Math.round(0.28 * c);
		int w = Math.round(tw) + 2 * padX;
		int h = (int) Math.round(0.72 * c);
		int y = baseline - (int) Math.round(0.6 * c);
		Theme.card(g, x, y, w, h, h, Theme.GOLD, null);
		g.setColor(Theme.BG_TOP);
		Theme.text(g, "WIN", x + w / 2.0, y + h / 2.0 + 0.21 * c, Theme.Align.CENTER);
		return w;
	}

	// ------------------------------------------------------------------ 예측 승률 흐름

	/**
	 * 예측 승률의 흐름: 가운데 줄(50%)보다 위는 P1 색, 아래는 P2 색으로 채운다. 결과 화면에서는 크게({@code labels}), 위 띠에서는
	 * 작게 그린다. 점이 그래프 폭의 장치 화소 수보다 많으면(긴 판, 블록마다 한 점) 솎는다({@link #chartPath}): 그린 모양은 같고,
	 * 매 프레임 만드는 꼭짓점이 판 길이와 상관없이 폭(장치 화소)을 넘지 않는다.
	 */
	static void winChart(Graphics2D g, GameSession s, long end, double x, double y, double w, double h, boolean labels,
			int c) {
		List<double[]> history = s.winHistory;
		if (history.size() < 2 || end <= 0) {
			return;
		}
		double mid = y + h / 2;
		AffineTransform t = g.getTransform();
		ChartPath path = chartPath(history, end, x, y, w, h, Math.hypot(t.getScaleX(), t.getShearY()));
		Path2D.Double line = path.line;
		Path2D.Double area = path.area;
		Shape old = g.getClip();
		g.clip(new Rectangle2D.Double(x, y, w, h / 2));
		g.setColor(Theme.withAlpha(Theme.P1, 70));
		g.fill(area);
		g.setClip(old);
		g.clip(new Rectangle2D.Double(x, mid, w, h / 2));
		g.setColor(Theme.withAlpha(Theme.P2, 70));
		g.fill(area);
		g.setClip(old);
		g.setColor(Theme.withAlpha(Theme.TEXT_FAINT, 160));
		java.awt.Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 4f }, 0f));
		g.draw(new Line2D.Double(x, mid, x + w, mid));
		g.setStroke(new BasicStroke(labels ? 2.2f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.TEXT);
		g.draw(line);
		g.setStroke(oldStroke);
		if (labels) { // 흐름 선이 글자를 지나가도 읽히게 카드 색 바탕을 깐다
			g.setFont(Theme.font(Math.max(12, 0.44 * c), false));
			chartLabel(g, s.name(0) + " 100%", x + 0.15 * c, y + 0.45 * c, false, c);
			chartLabel(g, s.name(1) + " 100%", x + 0.15 * c, y + h - 0.15 * c, false, c);
			chartLabel(g, "50%", x + w - 0.15 * c, mid - 0.12 * c, true, c);
		}
	}

	/**
	 * 흐름 그래프의 선과 채움 모양 (x 는 0 ~ {@code end} ms, y 는 위가 P1 100%). 점이 그래프 폭의 장치 화소 수({@code w × perPx})
	 * 보다 많으면 두 화소 열마다 가장 높은 점과 가장 낮은 점만 나온 차례대로 남긴다(선의 위아래 끝이 그대로라 그린 모양이 같다).
	 * 선은 마지막 값으로 오른쪽 끝까지, 채움은 가운데 줄(50%)까지 닫는다.
	 *
	 * @param perPx 사용자 좌표 1 이 장치 화소 몇 개인가 (그리는 변환의 배율)
	 */
	static ChartPath chartPath(List<double[]> history, long end, double x, double y, double w, double h, double perPx) {
		int n = history.size();
		double mid = y + h / 2;
		double scale = Math.max(1e-3, perPx) / THIN_PX; // 사용자 좌표 1 이 솎는 칸 몇 개인가
		int columns = Math.max(1, (int) Math.ceil(w * scale));
		boolean thin = n > 2 * columns;
		ChartPath path = new ChartPath(thin ? 2 * columns + 2 : n + 2, x, mid);
		for (int k = 0; k < n; k++) {
			double[] pt = history.get(k);
			double px = x + w * Math.min(1, pt[0] / end);
			double py = y + h * (1 - pt[1]);
			if (thin) {
				path.thinned((int) Math.min(columns - 1, Math.max(0, Math.floor((px - x) * scale))), k, px, py);
			} else {
				path.point(px, py);
			}
		}
		path.flush();
		double lastY = y + h * (1 - history.get(n - 1)[1]);
		path.line.lineTo(x + w, lastY);
		path.area.lineTo(x + w, lastY);
		path.area.lineTo(x + w, mid);
		path.area.closePath();
		return path;
	}

	/**
	 * 흐름 그래프의 꼭짓점을 선과 채움 모양에 함께 붙인다. 솎을 때는 칸마다 가장 높은 점과 가장 낮은 점을 모았다가 칸이 바뀔 때
	 * 나온 차례대로 붙인다({@link #thinned}, 마지막에 {@link #flush}).
	 */
	static final class ChartPath {
		final Path2D.Double line;
		final Path2D.Double area;
		/** 선에 붙인 데이터 점의 수 (시험용). */
		int points;
		private boolean started;
		private int column = Integer.MIN_VALUE;
		private double hiX;
		private double hiY;
		private double loX;
		private double loY;
		private int hiK;
		private int loK;

		ChartPath(int capacity, double x, double mid) {
			line = new Path2D.Double(Path2D.WIND_NON_ZERO, capacity);
			area = new Path2D.Double(Path2D.WIND_NON_ZERO, capacity + 3);
			area.moveTo(x, mid);
		}

		/** 점 하나를 그대로 붙인다. */
		void point(double px, double py) {
			points++;
			if (started) {
				line.lineTo(px, py);
			} else {
				line.moveTo(px, py);
				started = true;
			}
			area.lineTo(px, py);
		}

		/** 칸 {@code col} 의 {@code k} 번째 점: 칸이 바뀌면 앞 칸의 두 점을 붙이고 새 칸을 연다. */
		void thinned(int col, int k, double px, double py) {
			if (col != column) {
				flush();
				column = col;
				hiX = loX = px;
				hiY = loY = py;
				hiK = loK = k;
				return;
			}
			if (py < hiY) {
				hiX = px;
				hiY = py;
				hiK = k;
			}
			if (py > loY) {
				loX = px;
				loY = py;
				loK = k;
			}
		}

		/** 모아 둔 칸의 점(가장 높은 점과 가장 낮은 점, 나온 차례대로)을 붙인다. */
		void flush() {
			if (column == Integer.MIN_VALUE) {
				return;
			}
			if (hiK == loK) {
				point(hiX, hiY);
			} else if (hiK < loK) {
				point(hiX, hiY);
				point(loX, loY);
			} else {
				point(loX, loY);
				point(hiX, hiY);
			}
			column = Integer.MIN_VALUE;
		}
	}

	/** 흐름 그래프 안의 작은 글자: 카드 색 바탕 위에 라벨 색으로. {@code right} 면 x 가 오른쪽 끝이다. */
	private static void chartLabel(Graphics2D g, String text, double x, double baseline, boolean right, int c) {
		FontMetrics fm = g.getFontMetrics();
		double w = fm.stringWidth(text);
		double left = right ? x - w : x;
		double pad = 0.12 * c;
		g.setColor(Theme.withAlpha(Theme.SURFACE, 215));
		g.fill(new RoundRectangle2D.Double(left - pad, baseline - fm.getAscent() * 0.8 - pad / 2, w + 2 * pad,
				fm.getAscent() * 0.8 + fm.getDescent() * 0.5 + pad, pad * 2, pad * 2));
		g.setColor(Theme.LABEL);
		g.drawString(text, (float) left, (float) baseline);
	}
}
