package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 설정 화면 "효과" 갈래의 미리 보기 (runs/quality/SPEC.md V15 의 작은 판). 6 × 5 판이 {@link #CYCLE_MS} 마다 다 찬 두 줄을
 * 지우고(번쩍임, 튀는 조각, 판이 살짝 눌림), 조금 뒤 공격을 받는다(받을 줄 막대, 화면 흔들림). 지운 두 줄은 끝에 다시 차오른다.
 * 지금 설정대로 움직인다.
 * <ul>
 * <li>효과 세기 m (끔 0 · 약하게 0.5 · 보통 1 · 강하게 1.5): 줄의 흰 번쩍임 진하기(0.55·m, 많아야 1), 튀는 조각 수(12·m),
 * 판이 눌리는 크기(3·m 화소), 흔들림 크기(4·m 화소).</li>
 * <li>번쩍임 줄이기: 번쩍임을 0.3 까지로.</li>
 * <li>화면 흔들림: 끄면 공격을 받아도 판이 흔들리지 않는다.</li>
 * </ul>
 * 효과 세기가 끔이어도 줄이 지워지는 것과 받을 줄 막대(정보)는 그대로 보인다. 그림은 시계({@code now}, ms)만으로 정해지므로 같은
 * 시각이면 언제나 같은 그림이다(화면 확인 도구가 시계를 멈춰 그린다). 조각의 "무작위"는 {@link Fx#rand} 다.
 */
final class SettingsDemo {

	/** 한 번 돌기 (ms). */
	static final int CYCLE_MS = 2400;
	/** 번쩍임의 길이 (ms). */
	private static final int FLASH_MS = 250;
	/** 줄이 사라지는 때 (ms): 번쩍임이 끝날 무렵. */
	private static final int CLEAR_AT = 220;
	private static final int PARTICLE_MS = 450;
	/** 공격을 받는 때와 받을 줄 막대가 사라지는 때 (ms). */
	static final int HIT_AT = 1000;
	private static final int HIT_UNTIL = 1600;
	private static final int SHAKE_MS = 320;
	/** 지운 줄이 다시 차는 동안 (ms). */
	private static final int REFILL_FROM = 1700;
	private static final int REFILL_TO = 2300;
	private static final long SEED = 0x5E771265L;

	private static final int COLS = 6;
	private static final int ROWS = 5;
	/**
	 * 판의 칸 값 (0 = 빈칸, 8 = 방해 줄). 셋째·넷째 줄이 다 찼다(지울 줄). 그 위는 비어 있어 지운 뒤 떠 있는 블록이 없고, 맨 아래는
	 * 구멍 난 방해 줄이라 지워지지 않는다.
	 */
	private static final int[][] STACK = { { 0, 0, 0, 0, 0, 0 }, { 0, 0, 0, 0, 0, 0 }, { 6, 6, 6, 3, 2, 2 },
			{ 1, 1, 1, 1, 4, 4 }, { 8, 8, 8, 0, 8, 8 } };
	/** 지우는 줄 (셋째·넷째). */
	private static final int FIRST_CLEAR = 2;
	private static final int LAST_CLEAR = 3;

	private SettingsDemo() {
	}

	/** 미리 보기 카드 {@code r} 를 그린다. */
	static void paint(Graphics2D g, Rectangle r, UserSettings s, long now) {
		ColorPalettes.Look look = ColorPalettes.look(s);
		Theme.card(g, r.x, r.y, r.width, r.height, 18, Theme.withAlpha(Theme.BOARD, 235), Theme.BORDER);
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		g.drawString("미리 보기", r.x + 18, r.y + 28);
		String tag = "효과 " + SettingsPanel.EFFECT_NAMES[s.effects()];
		g.setFont(Theme.font(11, true));
		int tw = g.getFontMetrics().stringWidth(tag) + (int) Math.round(11 * 1.2);
		Color tc = s.effects() == UserSettings.EFFECTS_OFF ? Theme.TEXT_DIM : Theme.P1;
		Theme.chip(g, tag, r.x + r.width - 16 - tw, r.y + 27, 11, tc, Theme.withAlpha(tc, 36));

		double m = s.effectsScale();
		int c = 28;
		int t = (int) Math.floorMod(now, (long) CYCLE_MS);
		long cycle = Math.floorDiv(now, (long) CYCLE_MS);
		// 판 전체: 지울 때 살짝 눌렸다 돌아오고(효과 세기), 공격을 받으면 흔들린다(화면 흔들림)
		double dy = Motion.spring(t) * 3 * m;
		double dx = 0;
		int hit = t - HIT_AT;
		if (s.shake() && m > 0 && hit >= 0 && hit < SHAKE_MS) {
			double fade = 1 - hit / (double) SHAKE_MS;
			dx = Fx.noise(SEED ^ cycle, hit / 1000.0 * 11) * 4 * m * fade * fade;
		}
		int wx = r.x + (r.width - COLS * c) / 2 + 6 + (int) Math.round(dx);
		int wy = r.y + 52 + (int) Math.round(dy);
		Theme.card(g, wx - 4, wy - 4, COLS * c + 8, ROWS * c + 8, 8, Theme.BOARD, Theme.BORDER);
		g.setColor(Theme.GRID);
		for (int col = 1; col < COLS; col++) {
			g.fillRect(wx + col * c, wy, 1, ROWS * c);
		}
		for (int row = 0; row < ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				int code = STACK[row][col];
				int alpha = row >= FIRST_CLEAR && row <= LAST_CLEAR ? clearedAlpha(t) : 255;
				if (code != 0 && alpha > 0) {
					look.block(g, wx + col * c, wy + row * c, c, code, alpha, false);
				}
			}
		}
		// 받을 줄 막대 (정보라서 효과 세기와 상관없이 보인다): 판 왼쪽 밖, 4 줄
		if (hit >= 0 && t < HIT_UNTIL) {
			int mx = wx - 14;
			Theme.card(g, mx, wy, 7, ROWS * c, 6, Theme.withAlpha(Theme.BOARD, 255), Theme.BORDER);
			g.setColor(Theme.DANGER);
			g.fill(new RoundRectangle2D.Double(mx + 1, wy + (ROWS - 4) * c + 1, 5, 4 * c - 2, 4, 4));
		}
		// 줄의 흰 번쩍임 (효과 세기 × 0.55, 번쩍임 줄이기면 0.3 까지)
		double flash = Math.min(1, 0.55 * m);
		if (s.calmFlash()) {
			flash = Math.min(flash, 0.3);
		}
		if (flash > 0 && t < FLASH_MS) {
			float a = (float) (flash * (1 - Motion.easeOutQuad(t / (double) FLASH_MS)));
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, a))));
			g.setColor(Color.WHITE);
			g.fillRect(wx, wy + FIRST_CLEAR * c, COLS * c, (LAST_CLEAR - FIRST_CLEAR + 1) * c);
			g.setComposite(old);
		}
		// 튀는 조각: 지운 줄을 따라 생겨 위로 튀었다가 떨어진다
		int n = (int) Math.round(12 * m);
		if (n > 0 && t < PARTICLE_MS) {
			double sec = t / 1000.0;
			double life = 1 - t / (double) PARTICLE_MS;
			Shape clip = g.getClip();
			g.clip(new Rectangle(r.x + 2, r.y + 40, r.width - 4, r.height - 42));
			for (int k = 0; k < n; k++) {
				double x0 = wx + Fx.rand(SEED, cycle, k, 0, 0) * COLS * c;
				int line = FIRST_CLEAR + k % (LAST_CLEAR - FIRST_CLEAR + 1);
				double y0 = wy + line * c + c / 2.0;
				double vx = Fx.rand(SEED, cycle, k, 0, 1, -2, 2) * c;
				double vy = -Fx.rand(SEED, cycle, k, 0, 2, 2, 4) * c;
				double px = x0 + vx * sec;
				double py = y0 + vy * sec + 0.5 * 20 * c * sec * sec;
				int col = Math.min(COLS - 1, Math.max(0, (int) ((x0 - wx) / c)));
				g.setColor(Fx.alpha(Fx.lighter(look.piece(STACK[line][col]), 0.2), life));
				double size = 3 + Fx.rand(SEED, cycle, k, 0, 3);
				g.fill(new Ellipse2D.Double(px - size / 2, py - size / 2, size, size));
			}
			g.setClip(clip);
		}
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.LABEL);
		Theme.centered(g, "줄을 지울 때 · 공격을 받을 때", r.getCenterX(), r.y + r.height - 20);
	}

	/** 지우는 줄의 진하기: 번쩍임 뒤 사라졌다가 다시 찬다. */
	private static int clearedAlpha(int t) {
		if (t < CLEAR_AT) {
			return 255;
		}
		if (t < REFILL_FROM) {
			return 0;
		}
		return (int) Math.round(255 * Motion.easeOutCubic((t - REFILL_FROM) / (double) (REFILL_TO - REFILL_FROM)));
	}
}
