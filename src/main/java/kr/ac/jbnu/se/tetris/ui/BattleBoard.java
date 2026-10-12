package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.BasicStroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.RoundRectangle2D;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;

/**
 * 보드 묶음 하나를 그린다 (runs/quality/SPEC.md 1.4(d)). 이 묶음은 흔들림·튀는 반동으로 함께 움직이고(부르는 쪽이 옮겨 둔다),
 * 차례는 카드와 빛 → 격자 → 위험 빛 → 하드드롭 꼬리 → 더미(방해 줄이 오르는 움직임) → 지운 줄 번쩍임 → 고정 반짝임 → 고스트 →
 * 블록(고정 지연 색) → 조각 → K.O. 이다. 막대, 카드, 동작 글자, 위 띠는 움직이지 않으므로 {@link GamePanel} 이 따로 그린다.
 * <p>
 * <b>K.O.</b> (SPEC V9, 진 보드, 끝난 뒤의 세션 시계): 0~120ms 그대로 → 120~720ms 맨 아래 줄부터 30ms 마다 회색으로(줄마다
 * 90ms) → 720ms 흰 번쩍임(120ms)과 테두리 물결(500ms, 둘 다 번쩍임 줄이기면 없음), 60% 어둡게, 기울어진 "K.O." 도장(2.2배에서
 * 220ms 에 1배로). <b>이긴 보드</b>는 금빛이 1초 동안 두 번 맥박친다.
 */
final class BattleBoard {

	private static final int VISIBLE_TOP = BattleGeo.VISIBLE_TOP;

	/** K.O.: 끝난 순간을 이만큼 그대로 둔다 (ms). */
	static final int KO_HOLD_MS = 120;
	/** K.O.: 줄마다 회색이 시작하는 간격 (ms). */
	static final int KO_ROW_MS = 30;
	/** K.O.: 줄 하나가 회색으로 바뀌는 시간 (ms). */
	static final int KO_FADE_MS = 90;
	/** K.O.: 도장이 찍히는 때 (ms). */
	static final int KO_STAMP_MS = 720;
	/** 이긴 보드의 금빛 맥박이 끝나는 때 (ms). */
	static final int WIN_GLOW_MS = 1000;
	/** 바닥에 닿은 블록의 리셋이 이만큼 남으면 테두리가 깜빡인다 (SPEC V12: 15 중 13 부터). */
	static final int RESETS_WARN = TimedPlayer.MAX_RESETS - 2;

	private BattleBoard() {
	}

	/**
	 * @param koMs   이 보드가 진 뒤 흐른 시간 (ms), 지지 않았으면 -1
	 * @param winMs  이 보드가 이긴 뒤 흐른 시간 (ms), 이기지 않았으면 -1
	 * @param danger 위험 (빨간 테두리)
	 */
	static void paint(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, long koMs, long winMs,
			BattleStyle st, boolean danger) {
		TimedPlayer p = s.match.player(i);
		GameModel game = p.game();
		int c = geo.c();
		double pad = geo.pad();
		double arc = geo.arc();
		double bx = geo.boardX() - pad;
		double by = geo.top() - pad;
		double bw = geo.boardW() + 2 * pad;
		double bh = geo.boardH() + 2 * pad;

		// 1. 카드와 빛: 평소에는 플레이어 색, 위험하면 빨갛게 숨 쉰다
		Theme.card(g, bx, by, bw, bh, arc, Theme.BOARD, null);
		if (danger) {
			Theme.glow(g, bx, by, bw, bh, arc, Theme.DANGER, 0.6 + 0.4 * wave(now, st.calm() ? 0.9 : 1.3));
		} else {
			Theme.glow(g, bx, by, bw, bh, arc, i == 0 ? Theme.P1 : Theme.P2, 0.35);
		}
		BattleFx.bigGlow(g, s, i, now, st, bx, by, bw, bh, arc);
		if (winMs >= 0) {
			Theme.glow(g, bx, by, bw, bh, arc, Theme.GOLD, winGlow(winMs, st.m(), st.maxHz()));
		}
		// 2. 격자
		g.setColor(Theme.GRID);
		int boardX = geo.boardX();
		int top = geo.top();
		for (int col = 1; col < Grid.WIDTH; col++) {
			g.drawLine(boardX + col * c, top, boardX + col * c, top + geo.boardH() - 1);
		}
		for (int r = 1; r < BattleGeo.ROWS; r++) {
			g.drawLine(boardX, top + r * c, boardX + geo.boardW() - 1, top + r * c);
		}
		// 3. 위에서 번지는 빨간 빛
		if (danger) {
			int a = (int) (80 * (0.55 + 0.45 * wave(now, st.calm() ? 0.7 : 1.0)));
			g.setPaint(new GradientPaint(0, top, Theme.withAlpha(Theme.DANGER, a), 0, top + 6 * c,
					Theme.withAlpha(Theme.DANGER, 0)));
			g.fillRect(boardX, top, geo.boardW(), 6 * c);
		}
		// 4~9. 방해 줄이 오르는 동안은 더미·고스트·블록을 함께 아래에서 올리고 보드 밖은 자른다
		double dy = BattleFx.rise(s.tallies[i], now, c, st.m(i));
		Shape clip = null;
		if (dy > 0) {
			clip = g.getClip();
			g.clipRect(boardX, top - 2 * c, geo.boardW(), geo.boardH() + 2 * c);
		}
		BattleFx.trail(g, s, i, geo, now, st, dy);
		stack(g, game, geo, st.look(), dy, koMs);
		BattleFx.riseTint(g, s, i, geo, now, st, dy);
		BattleFx.clearFlash(g, s, i, geo, now, st);
		BattleFx.lockFlash(g, s, i, geo, now, st, dy);
		if (!game.isOver()) {
			piece(g, p, geo, now, st, dy, !s.over());
		}
		if (clip != null) {
			g.setClip(clip);
		}
		// 10. 조각, 퍼펙트 클리어 고리
		BattleFx.particles(g, s, i, geo, now, st);
		BattleFx.pcRing(g, s, i, geo, now, st);
		if (koMs >= 0) {
			ko(g, geo, koMs, st, i);
		}
	}

	/** 0~1 사이를 오가는 사인 맥박 (hz). */
	private static double wave(long ms, double hz) {
		return Math.sin(2 * Math.PI * hz * ms / 1000.0);
	}

	/**
	 * 이긴 보드의 금빛 세기 (SPEC V9): 0.4 에서 0.6 으로 오르며 1초 동안 두 번(번쩍임 줄이기면 한 번) 1.0 까지 맥박친다. 효과를
	 * 끄면 맥박 없이 오른다.
	 */
	static double winGlow(long ms, double m, double maxHz) {
		double base = 0.4 + 0.2 * Motion.clamp01(ms / (double) WIN_GLOW_MS);
		if (ms >= WIN_GLOW_MS || m <= 0) {
			return base;
		}
		double pulses = Math.min(2, maxHz); // 1초 안의 맥박 수 (2 Hz 를 넘지 않는다)
		double sn = Math.sin(Math.PI * pulses * ms / WIN_GLOW_MS);
		return base + Math.min(1, m) * (1.0 - base) * sn * sn;
	}

	/** 쌓인 더미. 진 보드는 K.O. 순서대로 아래 줄부터 회색으로 바뀐다. */
	private static void stack(Graphics2D g, GameModel game, BattleGeo geo, ColorPalettes.Look look, double dy, long koMs) {
		int c = geo.c();
		int oy = (int) Math.round(dy);
		int bottom = geo.top() + geo.boardH();
		for (int r = VISIBLE_TOP; r < Grid.HEIGHT; r++) {
			int y = geo.rowY(r) + oy;
			if (y >= bottom) {
				break;
			}
			int grey = koMs < 0 ? 0 : koGrey(r, koMs);
			for (int col = 0; col < Grid.WIDTH; col++) {
				int v = game.cell(r, col);
				if (v == Grid.EMPTY) {
					continue;
				}
				int x = geo.colX(col);
				if (grey < 255) {
					look.block(g, x, y, c, v, 255, false);
				}
				if (grey > 0) {
					look.block(g, x, y, c, Grid.GARBAGE, grey, false);
				}
			}
		}
	}

	/** K.O. 의 회색 알파 (0~255): 맨 아래 줄은 120ms 부터, 한 줄 위로 갈수록 30ms 씩 늦게, 90ms 동안. */
	static int koGrey(int row, long koMs) {
		int fromBottom = Grid.HEIGHT - 1 - row;
		double u = (koMs - KO_HOLD_MS - KO_ROW_MS * (double) fromBottom) / KO_FADE_MS;
		return (int) Math.round(255 * Motion.clamp01(u));
	}

	/**
	 * 고스트와 움직이는 블록. 바닥에 닿아 있으면 남은 고정 지연만큼 어두워지고(0.65 + 0.35·남은 시간/500 을 곱한 색), 리셋이 13 번을
	 * 넘으면 1 화소 흰 테두리가 2 Hz 로 깜빡인다 (SPEC V12, 언제나 켜짐). 판이 끝나면({@code live} 가 아니면) 깜빡이지 않는다.
	 */
	private static void piece(Graphics2D g, TimedPlayer p, BattleGeo geo, long now, BattleStyle st, double dy,
			boolean live) {
		PieceState piece = p.piece();
		int code = piece.type().code();
		int c = geo.c();
		int oy = (int) Math.round(dy);
		ColorPalettes.Look look = st.look();
		if (st.ghost()) {
			for (int[] cell : p.ghost().cells()) {
				if (cell[0] >= VISIBLE_TOP) {
					look.ghost(g, geo.colX(cell[1]), geo.rowY(cell[0]) + oy, c, code);
				}
			}
		}
		boolean grounded = p.grounded();
		double shade = grounded ? 0.35 * (1 - Motion.clamp01(p.lockTimer() / (double) TimedPlayer.LOCK_DELAY_MS)) : 0;
		double ring = live && grounded && p.resets() >= RESETS_WARN ? 0.3 + 0.4 * Motion.pulse(now, st.maxHz()) : 0;
		int gap = c >= 14 ? 1 : 0;
		for (int[] cell : piece.cells()) {
			// 숨김 영역의 아래 두 줄까지는 보드 위 여백에 그린다(막 나온 블록도 또렷하게)
			if (cell[0] < VISIBLE_TOP - 2) {
				continue;
			}
			int x = geo.colX(cell[1]);
			int y = geo.rowY(cell[0]) + oy;
			look.block(g, x, y, c, code, 255, false);
			if (shade > 0) {
				BattleFx.fill(g, Color.BLACK, shade, x + gap, y + gap, c - 2 * gap, c - 2 * gap);
			}
			if (ring > 0) {
				g.setColor(Fx.alpha(Color.WHITE, ring));
				g.drawRect(x + gap, y + gap, c - 2 * gap - 1, c - 2 * gap - 1);
			}
		}
	}

	/** K.O. 의 뒷부분 (720ms 부터): 흰 번쩍임과 테두리 물결, 어둡게, 도장. */
	private static void ko(Graphics2D g, BattleGeo geo, long koMs, BattleStyle st, int i) {
		if (koMs < KO_STAMP_MS) {
			return;
		}
		long t = koMs - KO_STAMP_MS;
		int c = geo.c();
		double pad = geo.pad();
		double arc = geo.arc();
		double bx = geo.boardX() - pad;
		double by = geo.top() - pad;
		double bw = geo.boardW() + 2 * pad;
		double bh = geo.boardH() + 2 * pad;
		double fm = st.flash(i);
		if (!st.calm() && fm > 0) {
			if (t < 120) {
				BattleFx.fill(g, Color.WHITE, Math.min(st.flashCap(), 0.5 * fm) * (1 - t / 120.0), geo.boardX(), geo.top(),
						geo.boardW(), geo.boardH());
			}
			if (t < 500) { // 테두리 물결: 0.2c 굵기의 선이 0 → 0.6c 바깥으로
				double u = t / 500.0;
				double grow = 0.6 * c * Motion.easeOutCubic(u);
				Stroke old = g.getStroke();
				g.setStroke(new BasicStroke((float) (0.2 * c)));
				g.setColor(Fx.alpha(Color.WHITE, (1 - u) * fm));
				g.draw(new RoundRectangle2D.Double(bx - grow, by - grow, bw + 2 * grow, bh + 2 * grow, arc + grow,
						arc + grow));
				g.setStroke(old);
			}
		}
		Composite old = BattleFx.alpha(g, 0.6 * Motion.clamp01(t / 150.0));
		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, arc, arc));
		g.setComposite(old);
		stamp(g, geo, t, st.m(i) > 0);
	}

	/** "K.O." 도장 (SPEC V9): Barlow Black 2.4c, 6° 돌리고 기울여, 2.2배에서 220ms 에 1배로 (easeOutBack), 검은 그림자 3 화소. */
	private static void stamp(Graphics2D g, BattleGeo geo, long t, boolean motion) {
		int c = geo.c();
		double px = 2.4 * c;
		double k = motion ? 2.2 - 1.2 * Motion.easeOutBack(Motion.clamp01(t / 220.0)) : 1;
		double a = Motion.clamp01(t / 60.0);
		double cx = geo.boardX() + geo.boardW() / 2.0;
		double cy = geo.top() + geo.boardH() * 0.45;
		Font f = Theme.display(px);
		AffineTransform saved = g.getTransform();
		for (int pass = 0; pass < 2; pass++) {
			boolean shadow = pass == 0;
			g.setTransform(saved);
			g.translate(cx + (shadow ? 3 : 0), cy + (shadow ? 3 : 0));
			g.rotate(Math.toRadians(-6));
			g.shear(-0.18, 0);
			g.scale(k, k);
			g.setFont(f);
			Composite old = BattleFx.alpha(g, shadow ? 0.6 * a : a);
			g.setColor(shadow ? Color.BLACK : Theme.DANGER);
			Theme.text(g, "K.O.", 0, 0.35 * px, Theme.Align.CENTER);
			g.setComposite(old);
		}
		g.setTransform(saved);
	}
}
