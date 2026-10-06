package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.Locale;

import javax.swing.JPanel;
import javax.swing.Timer;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;

/**
 * 대전 화면 (docs/GAME.md 7장): 보드, 고스트, 홀드, 미리보기 5개, 받을 가비지 막대, 콤보·B2B, 통계, 예측 승률 막대와 흐름,
 * 카운트다운, 결과. 그리기는 화면 스레드에서 {@link GameSession#lock} 을 잡고 한다. 움직임(큰 동작 표시, 위험 깜빡임)은 판의
 * 시계로 계산하므로 같은 판이면 언제나 같은 그림이 나온다. 키는 {@link HumanInput} 으로 넘긴다.
 */
final class GamePanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** 화면에서 할 일 (TetrisApp 이 정한다). */
	interface Actions {
		void restart();

		void menu();
	}

	private static final int VISIBLE_TOP = Grid.HIDDEN_ROWS;
	private static final int ROWS = Grid.HEIGHT - Grid.HIDDEN_ROWS;
	/** 플레이어 하나의 너비 (칸): 홀드 4.4 + 막대 자리 1.4 + 보드 10 + 0.6 + 미리보기 4.4. */
	private static final double PLAYER_W = 20.8;
	private static final double GAP_W = 3.4;
	/** 위: 시계·중력·승률 3.4 칸 + 이름 0.5 칸 + 숨김 영역 2.3 칸. 아래: 통계 3.2 칸. */
	private static final double TOP_H = 6.2;
	private static final double BOTTOM_H = 3.2;
	/** 이 높이(칸) 이상 쌓이면 보드 테두리가 빨갛게 깜빡인다. */
	private static final int DANGER_HEIGHT = 15;

	private final transient Actions actions;
	private transient GameSession session;
	private long escAt; // 대전에서 Esc 를 처음 누른 시각 (기권 확인). 0 이면 누르지 않음
	private transient GameSession shownFor; // 아래 두 값이 어느 판의 것인가
	private double shownP = Double.NaN; // 화면에 보이는 예측 승률 (실제 값을 부드럽게 따라간다)
	private long shownAt;

	/** 플레이어 한 명의 화면 자리 (칸 크기 c 기준). */
	private record Geo(int x0, int top, int c, int boardX, int boardW, int boardH, int meterX, int meterW, int nextX,
			int cardW) {
		static Geo of(int x0, int top, int c) {
			int boardX = x0 + (int) Math.round(5.8 * c);
			return new Geo(x0, top, c, boardX, Grid.WIDTH * c, ROWS * c, boardX - (int) Math.round(0.95 * c),
					(int) Math.round(0.55 * c), boardX + Grid.WIDTH * c + (int) Math.round(0.6 * c),
					(int) Math.round(4.4 * c));
		}
	}

	GamePanel(Actions actions) {
		this.actions = actions;
		setFocusable(true);
		setFocusTraversalKeysEnabled(false);
		enableInputMethods(false); // 한/영이 '한'이어도 Z·X·C·P 가 입력기에 먹히지 않게
		setPreferredSize(new Dimension(1280, 720));
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				onKey(e, true);
			}

			@Override
			public void keyReleased(KeyEvent e) {
				onKey(e, false);
			}
		});
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				if (session != null) {
					session.human.reset();
				}
			}
		});
		new Timer(15, e -> repaint()).start();
	}

	void setSession(GameSession s) {
		this.session = s;
		this.escAt = 0;
		repaint();
	}

	// ------------------------------------------------------------------ 키

	private void onKey(KeyEvent e, boolean down) {
		GameSession s = session;
		if (s == null) {
			return;
		}
		int code = e.getKeyCode();
		if (down && s.match.isOver()) {
			if (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_R) {
				actions.restart();
			} else if (code == KeyEvent.VK_ESCAPE) {
				actions.menu();
			}
			return;
		}
		if (down && code == KeyEvent.VK_ESCAPE) {
			// 혼자 연습은 바로 메뉴로, 대전은 2초 안에 한 번 더 누르면 기권하고 메뉴로 (GAME.md 4장)
			long now = System.currentTimeMillis();
			if (!s.settings.versusAi() || escPending(now)) {
				escAt = 0;
				actions.menu();
			} else {
				escAt = now;
			}
			return;
		}
		if (down && code == KeyEvent.VK_P) {
			s.togglePause();
			return;
		}
		if (down && s.countdown() > 0) {
			return; // 카운트다운 동안 누른 키는 버린다(시작하자마자 한꺼번에 실행되지 않게)
		}
		HumanInput.Key k = key(code);
		if (k == null) {
			return;
		}
		if (down) {
			s.human.press(k);
		} else {
			s.human.release(k);
		}
	}

	/** 2초 안에 Esc 를 한 번 눌러 둔 상태인가. (처음 값에서 빼면 넘쳐 음수가 되므로 0 을 따로 본다) */
	private boolean escPending(long now) {
		return escAt != 0 && now - escAt < 2000;
	}

	private static HumanInput.Key key(int code) {
		switch (code) {
		case KeyEvent.VK_LEFT:
			return HumanInput.Key.LEFT;
		case KeyEvent.VK_RIGHT:
			return HumanInput.Key.RIGHT;
		case KeyEvent.VK_DOWN:
			return HumanInput.Key.SOFT_DROP;
		case KeyEvent.VK_SPACE:
			return HumanInput.Key.HARD_DROP;
		case KeyEvent.VK_UP:
		case KeyEvent.VK_X:
			return HumanInput.Key.CW;
		case KeyEvent.VK_Z:
		case KeyEvent.VK_CONTROL:
			return HumanInput.Key.CCW;
		case KeyEvent.VK_C:
		case KeyEvent.VK_SHIFT:
			return HumanInput.Key.HOLD;
		default:
			return null;
		}
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.antialias(g);
		Theme.backgroundCached(g, getWidth(), getHeight());
		GameSession s = session;
		if (s != null) {
			synchronized (s.lock) {
				paintSession(g, s);
			}
		}
		g.dispose();
	}

	private void paintSession(Graphics2D g, GameSession s) {
		TimedMatch m = s.match;
		int players = m.size();
		double unitsWide = players * PLAYER_W + (players - 1) * GAP_W + 2;
		double unitsTall = TOP_H + ROWS + BOTTOM_H;
		int c = (int) Math.max(8, Math.min(getWidth() / unitsWide, getHeight() / unitsTall));
		int left = (getWidth() - (int) Math.round(unitsWide * c)) / 2 + c;
		int y0 = (getHeight() - (int) Math.round(unitsTall * c)) / 2;
		int top = y0 + (int) Math.round(TOP_H * c);
		long now = m.time();

		Geo[] geos = new Geo[players];
		for (int i = 0; i < players; i++) {
			geos[i] = Geo.of(left + (int) Math.round(i * (PLAYER_W + GAP_W) * c), top, c);
			paintPlayer(g, s, i, geos[i], now);
		}
		if (players == 2 && !m.isOver()) {
			paintAttackChips(g, s, geos, now);
		}
		paintHud(g, s, c, y0);
		if (s.aiWarning != null) {
			g.setFont(Theme.font(Math.max(11, c * 0.4), false));
			g.setColor(Theme.WARN);
			g.drawString(s.aiWarning, c, getHeight() - c / 3);
		}

		int countdown = s.countdown();
		if (countdown > 0) {
			paintCountdown(g, String.valueOf(countdown), Theme.TEXT, c, true);
		} else if (s.showGo()) {
			paintCountdown(g, "GO!", Theme.OK, c, false);
		}
		if (s.paused()) {
			paintMessage(g, "일시정지", "P  계속   ·   Esc  메뉴", c);
		} else if (m.isOver()) {
			paintResult(g, s, c);
		} else if (escPending(System.currentTimeMillis())) {
			paintToast(g, "Esc를 한 번 더 누르면 기권하고 메뉴로 갑니다", c);
		}
	}

	// ------------------------------------------------------------------ 플레이어 한 명

	private void paintPlayer(Graphics2D g, GameSession s, int i, Geo geo, long now) {
		TimedPlayer p = s.match.player(i);
		GameModel game = p.game();
		GameSession.Tally t = s.tallies[i];
		Color pc = i == 0 ? Theme.P1 : Theme.P2;
		int c = geo.c();
		int x0 = geo.x0();
		int top = geo.top();
		int boardX = geo.boardX();
		int boardW = geo.boardW();
		int boardH = geo.boardH();
		int cardW = geo.cardW();
		int nextX = geo.nextX();
		double pad = 0.18 * c;
		double arc = 0.6 * c;

		paintName(g, s, i, boardX - (int) pad, top - (int) Math.round(2.45 * c), c, pc);

		// 보드 테두리: 평소에는 플레이어 색, 위험하면 빨갛게 깜빡인다.
		// 위험: 쌓인 높이에 곧 올라올 방해 줄(한 번에 최대 8줄)을 더해 본다
		boolean danger = !game.isOver() && maxHeight(game) + Math.min(game.pendingLines(), 8) >= DANGER_HEIGHT;
		Theme.card(g, boardX - pad, top - pad, boardW + 2 * pad, boardH + 2 * pad, arc, Theme.BOARD, null);
		if (danger) {
			Theme.glow(g, boardX - pad, top - pad, boardW + 2 * pad, boardH + 2 * pad, arc, Theme.DANGER,
					0.6 + 0.4 * Math.sin(now / 120.0));
		} else {
			Theme.glow(g, boardX - pad, top - pad, boardW + 2 * pad, boardH + 2 * pad, arc, pc, 0.35);
		}
		g.setColor(Theme.GRID);
		for (int col = 1; col < Grid.WIDTH; col++) {
			g.drawLine(boardX + col * c, top, boardX + col * c, top + boardH - 1);
		}
		for (int r = 1; r < ROWS; r++) {
			g.drawLine(boardX, top + r * c, boardX + boardW - 1, top + r * c);
		}
		if (danger) { // 위쪽에서 숨 쉬듯 번지는 빨간 빛
			int a = (int) (80 * (0.55 + 0.45 * Math.sin(now / 160.0)));
			g.setPaint(new GradientPaint(0, top, Theme.withAlpha(Theme.DANGER, a), 0, top + 6 * c,
					Theme.withAlpha(Theme.DANGER, 0)));
			g.fillRect(boardX, top, boardW, 6 * c);
		}
		for (int r = VISIBLE_TOP; r < Grid.HEIGHT; r++) {
			for (int col = 0; col < Grid.WIDTH; col++) {
				int v = game.cell(r, col);
				if (v != Grid.EMPTY) {
					Theme.block(g, boardX + col * c, top + (r - VISIBLE_TOP) * c, c, v, 255, false);
				}
			}
		}
		if (!game.isOver()) {
			PieceState piece = p.piece();
			int code = piece.type().code();
			for (int[] cell : p.ghost().cells()) {
				if (cell[0] >= VISIBLE_TOP) {
					Theme.ghost(g, boardX + cell[1] * c, top + (cell[0] - VISIBLE_TOP) * c, c, code);
				}
			}
			for (int[] cell : piece.cells()) {
				// 숨김 영역의 아래 두 줄까지는 보드 위 여백에 그린다(막 나온 블록도 또렷하게).
				if (cell[0] >= VISIBLE_TOP - 2) {
					Theme.block(g, boardX + cell[1] * c, top + (cell[0] - VISIBLE_TOP) * c, c, code, 255, false);
				}
			}
		} else {
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.6f));
			g.setColor(Color.BLACK);
			g.fill(new RoundRectangle2D.Double(boardX - pad, top - pad, boardW + 2 * pad, boardH + 2 * pad, arc, arc));
			g.setComposite(old);
			g.setFont(Theme.font(1.4 * c, true));
			g.setColor(Theme.DANGER);
			Theme.centered(g, "K.O.", boardX + boardW / 2.0, top + boardH * 0.45);
		}

		paintMeter(g, game.pendingLines(), geo.meterX(), top, geo.meterW(), boardH, c, now);
		if (danger) { // 보드 왼쪽 바깥(받을 가비지 막대 위)에 둔다: 막 나온 블록과 겹치지 않게
			double fpx = Math.max(12, 0.42 * c);
			g.setFont(Theme.font(fpx, true));
			int w = g.getFontMetrics().stringWidth("위험") + (int) Math.round(fpx * 1.2);
			Theme.chip(g, "위험", boardX - (int) Math.round(0.3 * c) - w, top - (int) Math.round(0.55 * c), fpx,
					Color.WHITE, Theme.withAlpha(Theme.DANGER, 215));
		}
		paintHold(g, game, x0, top, cardW, c);
		paintNext(g, game.preview(), nextX, top, cardW, c);
		paintInfo(g, game, t, nextX, top + (int) Math.round(14.9 * c), cardW, (int) Math.round(5.1 * c), c);
		paintStats(g, game, t, boardX - (int) pad, top + boardH + (int) Math.round(0.45 * c), boardW + (int) (2 * pad),
				(int) Math.round(2.3 * c), c, s.match.time());
		if (!s.match.isOver()) {
			paintLog(g, t, geo, now, pc, s.match.size() == 2);
			paintPerfect(g, t, geo, now);
		}
	}

	/** 이름과 꼬리표: 나 / AI(난이도·페르소나) / 연습(방해 줄). */
	private static void paintName(Graphics2D g, GameSession s, int i, int x, int baseline, int c, Color pc) {
		boolean solo = s.match.size() == 1;
		String name = solo ? "연습" : i == 0 ? "나" : "AI";
		g.setFont(Theme.font(0.66 * c, true));
		g.setColor(Theme.TEXT);
		g.drawString(name, x, baseline);
		int cx = x + g.getFontMetrics().stringWidth(name) + (int) Math.round(0.35 * c);
		double fpx = 0.38 * c;
		Color chipBg = Theme.withAlpha(pc, 50);
		if (solo) {
			GameModel.Scheduler sc = s.settings.scheduler();
			Theme.chip(g, sc == GameModel.Scheduler.NONE ? "방해 줄 없음" : sc == GameModel.Scheduler.S_3 ? "방해 줄 조금씩" : "방해 줄 섞어서",
					cx, baseline - 2, fpx, pc, chipBg);
		} else if (i == 0) {
			Theme.chip(g, "P1", cx, baseline - 2, fpx, pc, chipBg);
		} else {
			cx += Theme.chip(g, s.settings.difficulty().name(), cx, baseline - 2, fpx, pc, chipBg) + (int) (0.2 * c);
			if (s.settings.difficulty().personas() && s.settings.persona().model() != null) {
				cx += Theme.chip(g, s.settings.persona().name(), cx, baseline - 2, fpx, Theme.TEXT_DIM,
						Theme.withAlpha(Theme.TEXT_DIM, 40)) + (int) (0.2 * c);
			}
			if (s.aiWarning != null) {
				cx += Theme.chip(g, "기준 AI", cx, baseline - 2, fpx, Theme.WARN, Theme.withAlpha(Theme.WARN, 45))
						+ (int) (0.2 * c);
			}
			double think = s.ai() == null ? -1 : s.ai().thinkProgress();
			if (think >= 0) { // AI 가 새 블록을 보고 생각하는 동안 차오르는 고리
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
	}

	/** 받을 가비지: 한 줄에 한 칸. 많을수록 노랑 → 주황 → 빨강, 8 줄 이상이면 깜빡인다. */
	private static void paintMeter(Graphics2D g, int pending, int x, int top, int w, int h, int c, long now) {
		Theme.card(g, x, top, w, h, w, Theme.BOARD, Theme.BORDER);
		if (pending <= 0) {
			return;
		}
		int n = Math.min(pending, ROWS);
		Color col = pending >= 8 ? Theme.DANGER : pending >= 4 ? Theme.mix(Theme.WARN, Theme.DANGER, 0.5) : Theme.WARN;
		int alpha = pending >= 8 ? (int) (185 + 70 * Math.sin(now / 90.0)) : 235;
		g.setColor(Theme.withAlpha(col, alpha));
		for (int k = 0; k < n; k++) {
			g.fill(new RoundRectangle2D.Double(x + 2, top + h - (k + 1) * c + 2, w - 4, c - 4, w / 2.0, w / 2.0));
		}
		g.setFont(Theme.font(Math.max(13, 0.48 * c), true));
		g.setColor(col);
		Theme.centered(g, String.valueOf(pending), x + w / 2.0, top + h - n * c - 0.25 * c);
	}

	private static void paintHold(Graphics2D g, GameModel game, int x, int top, int w, int c) {
		Theme.card(g, x, top, w, 3.4 * c, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		cardTitle(g, "HOLD", x, top, c);
		if (game.hold() != null) {
			mini(g, game.hold(), x, top + 0.95 * c, w, 2.2 * c, 0.78 * c, game.holdUsed());
		}
	}

	/** 미리보기: 바로 다음 블록은 크게, 나머지 넷은 작게. */
	private static void paintNext(Graphics2D g, List<PieceType> preview, int x, int top, int w, int c) {
		Theme.card(g, x, top, w, 14.4 * c, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		cardTitle(g, "NEXT", x, top, c);
		for (int k = 0; k < preview.size(); k++) {
			if (k == 0) {
				mini(g, preview.get(0), x, top + 0.95 * c, w, 2.6 * c, 0.86 * c, false);
			} else {
				mini(g, preview.get(k), x, top + 3.75 * c + (k - 1) * 2.6 * c, w, 2.4 * c, 0.62 * c, false);
			}
		}
	}

	/** 미리보기 아래: 지금 콤보, B2B, 테트리스와 최대 콤보. */
	private static void paintInfo(Graphics2D g, GameModel game, GameSession.Tally t, int x, int y, int w, int h, int c) {
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		int padX = (int) Math.round(0.4 * c);
		g.setFont(Theme.font(Math.max(12, 0.44 * c), true));
		g.setColor(Theme.TEXT_FAINT);
		g.drawString("콤보", x + padX, y + (int) Math.round(0.75 * c));
		boolean combo = game.combo() >= 1;
		g.setFont(Theme.font(1.05 * c, true));
		g.setColor(combo ? Theme.GOLD : Theme.TEXT_FAINT);
		g.drawString(combo ? String.valueOf(game.combo()) : "–", x + padX, y + (int) Math.round(1.95 * c));
		boolean b2b = game.b2b();
		int chipW = (int) Math.round(1.55 * c);
		Theme.card(g, x + w - padX - chipW, y + 0.95 * c, chipW, 0.72 * c, 0.72 * c,
				b2b ? Theme.withAlpha(Theme.WARN, 230) : Theme.withAlpha(Theme.BORDER, 255), null);
		g.setFont(Theme.font(Math.max(12, 0.44 * c), true));
		g.setColor(b2b ? Theme.BG_TOP : Theme.TEXT_FAINT);
		Theme.centered(g, "B2B", x + w - padX - chipW / 2.0, y + 1.47 * c);
		row(g, "테트리스", String.valueOf(t.tetrises), x + padX, x + w - padX, y + (int) Math.round(3.05 * c), c);
		row(g, "최대 콤보", String.valueOf(Math.max(0, t.maxCombo)), x + padX, x + w - padX, y + (int) Math.round(4.2 * c),
				c);
	}

	private static void row(Graphics2D g, String label, String value, int x, int right, int baseline, int c) {
		g.setFont(Theme.font(Math.max(12, 0.44 * c), false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString(label, x, baseline);
		g.setFont(Theme.font(0.48 * c, true));
		g.setColor(Theme.TEXT);
		Theme.right(g, value, right, baseline);
	}

	/** 보드 아래 통계: 초당 블록(PPS), 공격, 보냄, APP, T-spin. */
	private static void paintStats(Graphics2D g, GameModel game, GameSession.Tally t, int x, int y, int w, int h, int c,
			long timeMs) {
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		int pieces = game.pieces();
		String pps = String.format(Locale.ROOT, "%.2f", pieces / Math.max(1.0, timeMs / 1000.0));
		String[][] cols = { { "PPS", pps }, { "공격", String.valueOf(game.attackTotal()) },
				{ "보냄", String.valueOf(game.sentTotal()) },
				{ "APP", String.format(Locale.ROOT, "%.2f", pieces == 0 ? 0 : (double) game.attackTotal() / pieces) },
				{ "T-spin", String.valueOf(t.tspins) } };
		double colW = w / (double) cols.length;
		for (int k = 0; k < cols.length; k++) {
			double cx = x + colW * (k + 0.5);
			g.setFont(Theme.font(Math.max(12, 0.44 * c), true));
			g.setColor(Theme.TEXT_FAINT);
			Theme.centered(g, cols[k][0], cx, y + 0.78 * c);
			g.setFont(Theme.font(0.66 * c, true));
			g.setColor(Theme.TEXT);
			Theme.centered(g, cols[k][1], cx, y + 1.72 * c);
		}
	}

	/**
	 * 동작 기록 (홀드 아래 빈 칸): 방금 지운 줄의 이름(SINGLE~TETRIS, T-SPIN), B2B·콤보·퍼펙트 클리어 배지, "2줄 → 공격 3",
	 * 공격 내역(기본·콤보·B2B·PC), 상쇄. 2.6초 동안 보이고 끝의 0.6초에 옅어진다. 과제 3번(지운 줄만큼 공격)을 화면에서 보인다.
	 */
	private static void paintLog(Graphics2D g, GameSession.Tally t, Geo geo, long now, Color pc, boolean versus) {
		long age = now - t.clearAt;
		if (t.clearLines == 0 || age < 0 || age >= 2600) {
			return;
		}
		int c = geo.c();
		Composite old = g.getComposite();
		if (age > 2000) {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (2600 - age) / 600f));
		}
		double mid = geo.x0() + geo.cardW() / 2.0;
		double y = geo.top() + 4.7 * c;
		double small = Math.max(12, 0.44 * c);
		if (t.clearSpin != Spin.NONE) {
			g.setFont(Theme.font(small, true));
			g.setColor(Theme.mix(Theme.piece(PieceType.T.code()), Color.WHITE, 0.25));
			Theme.centered(g, t.clearSpin == Spin.MINI ? "T-SPIN MINI" : "T-SPIN", mid, y);
			y += 0.8 * c;
		}
		String[] names = { "", "SINGLE", "DOUBLE", "TRIPLE", "TETRIS" };
		double scale = age < 140 ? 1.25 - 0.25 * age / 140.0 : 1.0;
		g.setFont(Theme.font(Math.max(14, 0.74 * c * scale), true));
		g.setColor(t.clearLines == 4 ? Theme.piece(PieceType.I.code()) : Theme.TEXT);
		Theme.centered(g, names[t.clearLines], mid, y + 0.1 * c);
		y += 0.95 * c;
		if (t.clearParts[1] > 0) {
			y = badge(g, "B2B", mid, y, small, Theme.BG_TOP, Theme.GOLD, c);
		}
		if (t.clearCombo >= 1) {
			y = badge(g, t.clearCombo + " COMBO", mid, y, small, Theme.BG_TOP, Theme.WARN, c);
		}
		if (t.clearParts[3] > 0) {
			y = badge(g, "PERFECT CLEAR", mid, y, small, Theme.BG_TOP, Theme.OK, c);
		}
		y += 0.25 * c;
		g.setFont(Theme.font(Math.max(13, 0.5 * c), true));
		g.setColor(pc);
		Theme.centered(g, t.clearLines + "줄 → 공격 " + t.clearAttack, mid, y);
		y += 0.7 * c;
		int[] parts = t.clearParts;
		if (t.clearAttack != parts[0]) { // 보너스가 있을 때만 내역
			StringBuilder sb = new StringBuilder("기본 " + parts[0]);
			if (parts[2] > 0) {
				sb.append(" + 콤보 ").append(parts[2]);
			}
			if (parts[1] > 0) {
				sb.append(" + B2B ").append(parts[1]);
			}
			if (parts[3] > 0) {
				sb.append(" + PC ").append(parts[3]);
			}
			g.setFont(Theme.font(small * 0.92, false));
			g.setColor(Theme.TEXT_DIM);
			y = wrapCentered(g, sb.toString(), mid, y, geo.cardW() - 0.3 * c, 0.6 * c);
		}
		int cancelled = t.clearAttack - t.clearSent;
		if (cancelled > 0) {
			g.setFont(Theme.font(small * 0.92, true));
			g.setColor(Theme.WARN);
			String text = "상쇄 " + cancelled + (versus && t.clearSent > 0 ? " · 보냄 " + t.clearSent : "");
			wrapCentered(g, text, mid, y, geo.cardW() - 0.3 * c, 0.6 * c);
		}
		g.setComposite(old);
	}

	/** 가운데 맞춘 배지 하나를 그리고 다음 줄의 기준선을 돌려준다. */
	private static double badge(Graphics2D g, String text, double mid, double y, double fontPx, Color fg, Color bg, int c) {
		g.setFont(Theme.font(fontPx, true));
		int w = g.getFontMetrics().stringWidth(text) + (int) Math.round(fontPx * 1.2);
		Theme.chip(g, text, (int) Math.round(mid - w / 2.0), (int) Math.round(y), fontPx, fg, bg);
		return y + 0.8 * c;
	}

	/** 너비가 넘치면 " + " 에서 줄을 바꿔 가운데 맞춰 그린다. 다음 줄의 기준선을 돌려준다. */
	private static double wrapCentered(Graphics2D g, String text, double mid, double y, double maxW, double step) {
		FontMetrics fm = g.getFontMetrics();
		List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String part : text.split(" (?=[+·])")) {
			String next = line.length() == 0 ? part : line + " " + part;
			if (line.length() > 0 && fm.stringWidth(next) > maxW) {
				lines.add(line.toString());
				line = new StringBuilder(part);
			} else {
				line = new StringBuilder(next);
			}
		}
		lines.add(line.toString());
		for (String l : lines) {
			Theme.centered(g, l, mid, y);
			y += step;
		}
		return y;
	}

	/** 퍼펙트 클리어만은 보드 가운데에 금색 띠로 2초 보인다. */
	private static void paintPerfect(Graphics2D g, GameSession.Tally t, Geo geo, long now) {
		long age = now - t.clearAt;
		if (t.clearParts[3] <= 0 || age < 0 || age >= 2000) {
			return;
		}
		int c = geo.c();
		int alpha = age > 1500 ? (int) (255 * (2000 - age) / 500.0) : 255;
		double y = geo.top() + geo.boardH() * 0.4;
		g.setColor(Theme.withAlpha(Theme.BG_TOP, alpha * 200 / 255));
		g.fill(new Rectangle2D.Double(geo.boardX(), y - 0.9 * c, geo.boardW(), 1.4 * c));
		g.setFont(Theme.font(0.8 * c, true));
		g.setColor(Theme.withAlpha(Theme.GOLD, alpha));
		Theme.centered(g, "PERFECT CLEAR", geo.boardX() + geo.boardW() / 2.0, y + 0.1 * c);
	}

	/**
	 * 보낸 공격이 상대의 받을 가비지 막대로 날아가는 "+N" (0.45초), 도착하면 그 칸들이 0.2초 반짝인다. 일부만 보냈으면(상쇄)
	 * 보낸 만큼만 날아간다.
	 */
	private static void paintAttackChips(Graphics2D g, GameSession s, Geo[] geos, long now) {
		for (int i = 0; i < 2; i++) {
			GameSession.Tally t = s.tallies[i];
			long age = now - t.sentAt;
			if (t.sentLines <= 0 || age < 0 || age >= 650) {
				continue;
			}
			Geo from = geos[i];
			Geo to = geos[1 - i];
			int c = from.c();
			Color pc = i == 0 ? Theme.P1 : Theme.P2;
			double sx = i == 0 ? from.boardX() + from.boardW() + 0.4 * c : from.boardX() - 0.4 * c;
			double sy = from.top() + from.boardH() * 0.55;
			int pending = Math.min(ROWS, s.match.player(1 - i).game().pendingLines());
			double tx = to.meterX() + to.meterW() / 2.0;
			double ty = to.top() + to.boardH() - pending * c;
			if (age < 450) {
				double u = age / 450.0;
				for (int k = 2; k >= 0; k--) { // 꼬리 두 개와 본체
					double e = Math.max(0, 1 - Math.pow(1 - Math.max(0, u - k * 0.08), 3));
					double cx = (1 - e) * (1 - e) * sx + 2 * (1 - e) * e * (sx + tx) / 2 + e * e * tx;
					double cy = (1 - e) * (1 - e) * sy + 2 * (1 - e) * e * (Math.min(sy, ty) - 3 * c) + e * e * ty;
					int alpha = k == 0 ? 255 : 120 / k;
					String text = "+" + t.sentLines;
					g.setFont(Theme.font(Math.max(13, 0.52 * c), true));
					FontMetrics fm = g.getFontMetrics();
					double w = fm.stringWidth(text) + 0.6 * c;
					double h = fm.getHeight() + 0.1 * c;
					Theme.card(g, cx - w / 2, cy - h / 2, w, h, h, Theme.withAlpha(pc, alpha), null);
					if (k == 0) {
						g.setColor(Theme.BG_TOP);
						Theme.centered(g, text, cx, cy + fm.getAscent() / 2.6);
					}
				}
			} else { // 도착: 맨 위 칸들이 하얗게 반짝인다
				int alpha = (int) (200 * (1 - (age - 450) / 200.0));
				int n = Math.min(t.sentLines, pending);
				g.setColor(new Color(255, 255, 255, Math.max(0, alpha)));
				g.fill(new RoundRectangle2D.Double(to.meterX(), ty, to.meterW(), n * c, to.meterW(), to.meterW()));
			}
		}
	}

	private static void cardTitle(Graphics2D g, String title, int x, int top, int c) {
		g.setFont(Theme.font(Math.max(12, 0.44 * c), true));
		g.setColor(Theme.TEXT_FAINT);
		g.drawString(title, x + (int) Math.round(0.4 * c), top + (int) Math.round(0.72 * c));
	}

	/** 상자 가운데에 블록 하나를 그린다(홀드, 미리보기). */
	private static void mini(Graphics2D g, PieceType type, double boxX, double boxY, double boxW, double boxH,
			double size, boolean grey) {
		int[][] cells = PieceState.spawn(type).cells();
		int minR = Integer.MAX_VALUE;
		int maxR = Integer.MIN_VALUE;
		int minC = Integer.MAX_VALUE;
		int maxC = Integer.MIN_VALUE;
		for (int[] cell : cells) {
			minR = Math.min(minR, cell[0]);
			maxR = Math.max(maxR, cell[0]);
			minC = Math.min(minC, cell[1]);
			maxC = Math.max(maxC, cell[1]);
		}
		int s = (int) Math.round(size);
		double ox = boxX + (boxW - (maxC - minC + 1) * s) / 2.0;
		double oy = boxY + (boxH - (maxR - minR + 1) * s) / 2.0;
		for (int[] cell : cells) {
			Theme.block(g, (int) Math.round(ox + (cell[1] - minC) * s), (int) Math.round(oy + (cell[0] - minR) * s), s,
					type.code(), 255, grey);
		}
	}

	private static int maxHeight(GameModel game) {
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int col = 0; col < Grid.WIDTH; col++) {
				if (game.cell(r, col) != Grid.EMPTY) {
					return Grid.HEIGHT - r;
				}
			}
		}
		return 0;
	}

	// ------------------------------------------------------------------ 위 띠: 시계, 중력, 예측 승률

	private void paintHud(Graphics2D g, GameSession s, int c, int y0) {
		TimedMatch m = s.match;
		double cx = getWidth() / 2.0;
		long sec = m.time() / 1000;
		g.setFont(Theme.font(0.95 * c, true));
		g.setColor(Theme.TEXT);
		Theme.centered(g, String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60), cx, y0 + 0.9 * c);
		String level = m.gravityLevel() < 0 ? "" : String.format(Locale.ROOT, "속도 %d단계", m.gravityLevel() + 1);
		g.setFont(Theme.font(Math.max(12, 0.44 * c), false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, level, cx, y0 + 1.42 * c);

		double p = s.p1Win();
		if (m.size() < 2 || Double.isNaN(p)) {
			return;
		}
		// 블록이 놓일 때마다 값이 순간 이동하지 않게, 보이는 값이 약 0.2초에 걸쳐 따라간다.
		long nowNanos = System.nanoTime();
		if (shownFor != s || Double.isNaN(shownP) || m.isOver()) {
			shownFor = s;
			shownP = p;
		} else {
			shownP += (p - shownP) * Math.min(1, (nowNanos - shownAt) / 0.18e9);
		}
		shownAt = nowNanos;
		double q = shownP;
		double w = 13 * c;
		double h = 0.62 * c;
		double x = cx - w / 2;
		double y = y0 + 2.22 * c;
		Shape track = new RoundRectangle2D.Double(x, y, w, h, h, h);
		g.setColor(Theme.P2);
		g.fill(track);
		Shape old = g.getClip();
		g.clip(new Rectangle2D.Double(x, y, w * q, h));
		g.setColor(Theme.P1);
		g.fill(track);
		g.setClip(old);
		g.setColor(new Color(255, 255, 255, 210)); // 50% 눈금
		g.fill(new Rectangle2D.Double(cx - 1, y - 0.12 * c, 2, h + 0.24 * c));
		g.setFont(Theme.font(Math.max(16, 0.78 * c), true));
		g.setColor(Theme.P1);
		Theme.right(g, String.format(Locale.ROOT, "나 %.0f%%", 100 * q), x - 0.3 * c, y + h * 0.95);
		g.setColor(Theme.P2);
		g.drawString(String.format(Locale.ROOT, "%.0f%% AI", 100 * (1 - q)), (float) (x + w + 0.3 * c),
				(float) (y + h * 0.95));
		g.setFont(Theme.font(Math.max(12, 0.44 * c), false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, "딥러닝 예측 승률", cx, y - 0.24 * c);
		paintWinChart(g, s.winHistory, m.time(), x, y + h + 0.2 * c, w, 0.55 * c, false, c);
		long age = m.time() - s.swingAt;
		if (!m.isOver() && age >= 0 && age < 1600) { // 크게 움직였을 때: "▲ 12%p · AI 공격 3줄"
			boolean p1Gains = s.swingDelta > 0;
			String text = (p1Gains ? "나 ▲ " : "AI ▲ ") + Math.round(Math.abs(s.swingDelta) * 100) + "%p"
					+ (s.swingCause.isEmpty() ? "" : " · " + s.swingCause);
			int alpha = age > 1200 ? (int) (255 * (1600 - age) / 400.0) : 255;
			Color col = p1Gains ? Theme.P1 : Theme.P2;
			g.setFont(Theme.font(Math.max(12, 0.46 * c), true));
			FontMetrics fm = g.getFontMetrics();
			double cw = fm.stringWidth(text) + 0.8 * c;
			double ch = fm.getHeight() + 0.1 * c;
			double chipX = p1Gains ? x : x + w - cw;
			double chipY = y + h + 0.15 * c;
			Theme.card(g, chipX, chipY, cw, ch, ch, Theme.withAlpha(Theme.BG_TOP, alpha * 230 / 255),
					Theme.withAlpha(col, alpha));
			g.setColor(Theme.withAlpha(col, alpha));
			g.drawString(text, (float) (chipX + 0.4 * c), (float) (chipY + ch / 2 + fm.getAscent() / 2.8));
		}
	}

	/**
	 * 예측 승률의 흐름: 가운데 줄(50%)보다 위는 P1 색, 아래는 P2 색으로 채운다. 결과 화면에서는 크게, 위 띠에서는 작게 그린다.
	 */
	private static void paintWinChart(Graphics2D g, List<double[]> history, long end, double x, double y, double w,
			double h, boolean labels, int c) {
		if (history.size() < 2 || end <= 0) {
			return;
		}
		Path2D.Double line = new Path2D.Double();
		Path2D.Double area = new Path2D.Double();
		double mid = y + h / 2;
		area.moveTo(x, mid);
		boolean first = true;
		for (double[] pt : history) {
			double px = x + w * Math.min(1, pt[0] / end);
			double py = y + h * (1 - pt[1]);
			if (first) {
				line.moveTo(px, py);
				first = false;
			} else {
				line.lineTo(px, py);
			}
			area.lineTo(px, py);
		}
		double lastY = y + h * (1 - history.get(history.size() - 1)[1]);
		line.lineTo(x + w, lastY);
		area.lineTo(x + w, lastY);
		area.lineTo(x + w, mid);
		area.closePath();
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
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 4f }, 0f));
		g.draw(new Line2D.Double(x, mid, x + w, mid));
		g.setStroke(new BasicStroke(labels ? 2.2f : 1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.TEXT);
		g.draw(line);
		g.setStroke(new BasicStroke(1f));
		if (labels) {
			g.setFont(Theme.font(Math.max(12, 0.44 * c), false));
			g.setColor(Theme.TEXT_FAINT);
			g.drawString("나 100%", (float) (x + 0.15 * c), (float) (y + 0.45 * c));
			g.drawString("AI 100%", (float) (x + 0.15 * c), (float) (y + h - 0.15 * c));
			Theme.right(g, "50%", x + w - 0.15 * c, mid - 0.12 * c);
		}
	}

	// ------------------------------------------------------------------ 덮개: 카운트다운, 일시정지, 결과, 알림

	private void paintCountdown(Graphics2D g, String text, Color color, int c, boolean veil) {
		if (veil) {
			veil(g, 0.45f);
		}
		g.setFont(Theme.font(4.2 * c, true));
		FontMetrics fm = g.getFontMetrics();
		double cy = getHeight() / 2.0 + fm.getAscent() / 2.6;
		g.setColor(Theme.withAlpha(color, 60));
		Theme.centered(g, text, getWidth() / 2.0 + 0.12 * c, cy + 0.12 * c);
		g.setColor(color);
		Theme.centered(g, text, getWidth() / 2.0, cy);
		if (veil) {
			g.setFont(Theme.font(0.6 * c, true));
			g.setColor(Theme.TEXT_DIM);
			Theme.centered(g, "준비", getWidth() / 2.0, cy - fm.getAscent() * 0.95);
		}
	}

	private void paintMessage(Graphics2D g, String title, String hint, int c) {
		veil(g, 0.55f);
		double w = 11 * c;
		double h = 4.2 * c;
		double x = (getWidth() - w) / 2;
		double y = (getHeight() - h) / 2;
		Theme.card(g, x, y, w, h, 0.8 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, w, h, 0.8 * c, Theme.ACCENT, 0.5);
		g.setFont(Theme.font(1.2 * c, true));
		g.setColor(Theme.TEXT);
		Theme.centered(g, title, x + w / 2, y + 2.0 * c);
		g.setFont(Theme.font(0.48 * c, false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, hint, x + w / 2, y + 3.2 * c);
	}

	private void paintToast(Graphics2D g, String text, int c) {
		g.setFont(Theme.font(0.5 * c, true));
		FontMetrics fm = g.getFontMetrics();
		double w = fm.stringWidth(text) + 1.4 * c;
		double h = 1.1 * c;
		double x = (getWidth() - w) / 2;
		double y = getHeight() - h - 0.5 * c;
		Theme.card(g, x, y, w, h, h, Theme.withAlpha(Theme.DANGER, 225), null);
		g.setColor(Color.WHITE);
		Theme.centered(g, text, x + w / 2, y + h / 2 + fm.getAscent() / 2.8);
	}

	private void veil(Graphics2D g, float alpha) {
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		g.setColor(Color.BLACK);
		g.fillRect(0, 0, getWidth(), getHeight());
		g.setComposite(old);
	}

	/** 결과: 제목, 이유·시간·시드, 두 플레이어 통계 비교, 예측 승률 흐름, 다시 하기·메뉴. */
	private void paintResult(Graphics2D g, GameSession s, int c) {
		TimedMatch m = s.match;
		boolean vs = m.size() == 2;
		veil(g, 0.62f);
		double w = (vs ? 17 : 11) * c;
		double h = (vs ? 15.2 : 11) * c;
		double x = (getWidth() - w) / 2;
		double y = (getHeight() - h) / 2;
		String title;
		Color tc;
		switch (m.result()) {
		case P1:
			title = "승리!";
			tc = Theme.OK;
			break;
		case P2:
			title = "패배";
			tc = Theme.DANGER;
			break;
		case DRAW:
			title = "무승부";
			tc = Theme.TEXT_DIM;
			break;
		default:
			title = "게임 끝";
			tc = Theme.ACCENT;
			break;
		}
		Theme.card(g, x, y, w, h, 0.9 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, w, h, 0.9 * c, tc, 0.7);
		g.setFont(Theme.font(1.5 * c, true));
		g.setColor(tc);
		Theme.centered(g, title, x + w / 2, y + 1.95 * c);
		GameModel me = m.player(0).game();
		String why = me.isOver() ? topout(me.topout()) : vs ? "상대 " + topout(m.player(1).game().topout()) : "";
		long sec = m.time() / 1000;
		g.setFont(Theme.font(Math.max(13, 0.48 * c), false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, String.format(Locale.ROOT, "%s%s%d:%02d   ·   시드 %d", why, why.isEmpty() ? "" : "   ·   ",
				sec / 60, sec % 60, s.seed), x + w / 2, y + 2.75 * c);

		String[] labels = { "블록", "줄", "공격", "보냄", "APP", "T-spin", "테트리스", "최대 콤보" };
		double rowY = y + 3.85 * c;
		double step = 0.62 * c;
		if (vs) {
			g.setFont(Theme.font(Math.max(13, 0.48 * c), true));
			g.setColor(Theme.P1);
			Theme.centered(g, "나", x + w * 0.27, rowY);
			g.setColor(Theme.P2);
			Theme.centered(g, "AI", x + w * 0.73, rowY);
			rowY += step;
		}
		double[][] values = new double[m.size()][];
		for (int i = 0; i < m.size(); i++) {
			values[i] = stats(m.player(i).game(), s.tallies[i]);
		}
		for (int r = 0; r < labels.length; r++) {
			double by = rowY + r * step;
			if (r % 2 == 0) {
				g.setColor(new Color(255, 255, 255, 8));
				g.fill(new RoundRectangle2D.Double(x + 0.8 * c, by - 0.45 * c, w - 1.6 * c, step, 0.3 * c, 0.3 * c));
			}
			g.setFont(Theme.font(0.4 * c, false));
			g.setColor(Theme.TEXT_DIM);
			Theme.centered(g, labels[r], vs ? x + w / 2 : x + w * 0.35, by);
			if (vs) {
				for (int i = 0; i < 2; i++) {
					boolean better = values[i][r] > values[1 - i][r];
					g.setFont(Theme.font(0.46 * c, better));
					g.setColor(better ? (i == 0 ? Theme.P1 : Theme.P2) : Theme.TEXT);
					Theme.centered(g, format(r, values[i][r]), x + w * (i == 0 ? 0.27 : 0.73), by);
				}
			} else {
				g.setFont(Theme.font(0.46 * c, true));
				g.setColor(Theme.TEXT);
				Theme.centered(g, format(r, values[0][r]), x + w * 0.65, by);
			}
		}
		double after = rowY + labels.length * step;
		if (vs && s.winHistory.size() >= 2) {
			g.setFont(Theme.font(Math.max(12, 0.44 * c), true));
			g.setColor(Theme.TEXT_FAINT);
			g.drawString("예측 승률 흐름", (float) (x + 1.0 * c), (float) (after + 0.15 * c));
			paintWinChart(g, s.winHistory, m.time(), x + 1.0 * c, after + 0.4 * c, w - 2.0 * c, 2.6 * c, true, c);
		}
		double ky = y + h - 0.75 * c;
		g.setFont(Theme.font(Math.max(13, 0.48 * c), true));
		FontMetrics fm = g.getFontMetrics();
		String[][] keys = { { "Enter", "다시 하기" }, { "Esc", "메뉴" } };
		double total = 0;
		for (String[] k : keys) {
			total += fm.stringWidth(k[0]) + 0.6 * c + 0.25 * c + fm.stringWidth(k[1]) + 0.9 * c;
		}
		double kx = x + (w - total) / 2;
		for (String[] k : keys) {
			double kw = fm.stringWidth(k[0]) + 0.6 * c;
			Theme.card(g, kx, ky - 0.62 * c, kw, 0.85 * c, 0.3 * c, Theme.SURFACE_HI, Theme.BORDER);
			g.setColor(Theme.TEXT);
			g.drawString(k[0], (float) (kx + 0.3 * c), (float) ky);
			kx += kw + 0.25 * c;
			g.setColor(Theme.TEXT_DIM);
			g.drawString(k[1], (float) kx, (float) ky);
			kx += fm.stringWidth(k[1]) + 0.9 * c;
		}
	}

	private static double[] stats(GameModel g, GameSession.Tally t) {
		int pieces = g.pieces();
		return new double[] { pieces, g.linesTotal(), g.attackTotal(), g.sentTotal(),
				pieces == 0 ? 0 : (double) g.attackTotal() / pieces, t.tspins, t.tetrises, Math.max(0, t.maxCombo) };
	}

	private static String format(int row, double v) {
		return row == 4 ? String.format(Locale.ROOT, "%.2f", v) : String.valueOf((long) v);
	}

	private static String topout(GameModel.Topout t) {
		switch (t) {
		case BLOCK_OUT:
			return "블록 아웃";
		case LOCK_OUT:
			return "락 아웃";
		case GARBAGE_OUT:
			return "가비지 아웃";
		default:
			return "";
		}
	}
}
