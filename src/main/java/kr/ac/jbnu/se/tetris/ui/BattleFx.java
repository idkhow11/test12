package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.Ellipse2D;
import java.util.List;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.Spin;

/**
 * 대전 화면의 보드 효과 (runs/quality/SPEC.md V4 줄 지우기, V5 고정 반짝임·하드드롭 꼬리·튀는 반동, V7(b)(c) 방해 줄이 오름·
 * 흔들림). 화면 효과용 사건({@link GameSession#clearFx}, {@link GameSession#lockFx} …)을 읽어 그 순간의 모습을 계산한다.
 * <ul>
 * <li><b>상태가 없다.</b> 프레임마다 시뮬레이션하지 않고 사건 시각과 지금 시각에서 바로 계산한다. "무작위"는
 * {@link Fx#rand}(판 시드·보드, 사건 시각, 칸, 번호)라서 같은 판이면 몇 번째 프레임에 그려도 같은 그림이다. 판의 상태, 시간,
 * 대전 난수는 건드리지 않는다.</li>
 * <li><b>시각</b>은 {@link #now}: 판이 진행 중이면 판의 시계, 끝난 뒤에는 끝난 순간의 판 시각에 세션 시계로 흐른 시간을 더한다
 * (끝나는 순간의 효과도 마저 움직이고, 화면 확인 도구는 시계를 정해 같은 그림을 얻는다).</li>
 * <li><b>세기</b>는 {@link BattleStyle} 의 효과 세기 m(AI 보드는 절반), 번쩍임 줄이기를 따른다. 보드 위 번쩍임은 250ms 안,
 * 알파 0.6 까지다(SPEC 0장 규칙 5).</li>
 * <li>그릴 때 객체를 거의 만들지 않는다: 색은 {@link Fx#alphas} 표, 합성은 {@link Fx#composite} 표에서 꺼낸다.</li>
 * </ul>
 */
final class BattleFx {

	private static final int VISIBLE_TOP = BattleGeo.VISIBLE_TOP;
	private static final int ROWS = BattleGeo.ROWS;

	/** 줄 지우기 조각: 지우기 하나에 이만큼까지. */
	static final int MAX_PER_CLEAR = 80;
	/** 줄 지우기 조각: 보드 하나에 동시에 이만큼까지. */
	static final int MAX_ALIVE = 200;
	/** 조각이 사는 가장 긴 시간 (퍼펙트 클리어의 금빛 조각, ms). */
	private static final int PARTICLE_MAX_MS = 800;
	/** 가장자리 불꽃 (ms). */
	static final int FLARE_MS = 250;
	/** 가장자리 불꽃의 너비 (화소). */
	private static final int FLARE_W = 3;
	/** 고정 반짝임: 보통 100ms, 하드드롭 120ms. */
	static final int LOCK_FLASH_MS = 100;
	static final int HARD_FLASH_MS = 120;
	/** 하드드롭 꼬리 (ms). */
	static final int TRAIL_MS = 110;
	/** 튀는 반동이 끝나는 때 (ms). */
	static final int BUMP_MS = 250;
	/** 큰 지우기의 보드 빛 (ms). */
	static final int GLOW_MS = 300;
	/** 퍼펙트 클리어 고리 (ms). */
	static final int RING_MS = 500;
	/** 새로 오른 방해 줄의 흰빛 (ms). */
	static final int RISE_TINT_MS = 150;
	/** 줄 지우기 번쩍임은 1초 안에 이만큼만 시작한다 (WCAG 2.3.1). */
	private static final int FLASHES_PER_SECOND = 3;
	/** 충격이 초당 줄어드는 양. */
	private static final double TRAUMA_DECAY = 1.5;

	private BattleFx() {
	}

	// ------------------------------------------------------------------ 시각과 씨앗

	/** 판이 끝난 뒤 흐른 시간 (ms, 세션 시계), 아직 끝나지 않았으면 -1. lock 을 잡고 부른다. */
	static long overMs(GameSession s) {
		long at = s.overAtNanos();
		return at == GameSession.NOT_OVER ? -1 : Math.max(0, (s.clockNanos() - at) / 1_000_000);
	}

	/** 효과의 시각 (ms): 진행 중이면 판의 시계, 끝난 뒤에는 끝난 순간의 판 시각 + 그 뒤 흐른 시간. lock 을 잡고 부른다. */
	static long now(GameSession s) {
		long over = overMs(s);
		return over < 0 ? s.match.time() : s.match.time() + over;
	}

	/** 보드 i 의 효과 난수 씨앗 (판 시드와 보드 번호, 대전 난수와 따로). */
	static long seed(GameSession s, int i) {
		return Fx.mix(s.seed * 31 + i + 1);
	}

	/** 큰 지우기인가: 4줄, T-spin 더블·트리플 (퍼펙트 클리어는 따로 본다). */
	static boolean big(GameSession.ClearFx e) {
		return e.lines() == 4 || e.spin() == Spin.FULL && e.lines() >= 2;
	}

	/** 큰 지우기의 색: 퍼펙트 클리어는 금색, T-spin 은 T 블록 색, 4줄은 I 블록 색 (색 묶음을 따른다). */
	static Color bigColor(GameSession.ClearFx e, ColorPalettes.Look look) {
		if (e.perfectClear()) {
			return Theme.GOLD;
		}
		return look.piece(e.spin() != Spin.NONE ? PieceType.T.code() : PieceType.I.code());
	}

	// ------------------------------------------------------------------ 보드의 움직임: 흔들림, 튀는 반동, 오름

	/**
	 * 보드 i 의 충격 0~1 (SPEC V7(c)): 최근 사건마다 a − 1.5·(지난 초)를 더해 자른다. 큰 지우기 0.55, 퍼펙트 클리어 0.8, 방해 줄
	 * n ≥ 2 줄이 오름 min(0.25 + 0.05n, 0.65), 이 보드의 K.O. 1.0. 1~3줄 지우기, 이동, 회전, 홀드는 흔들지 않는다.
	 *
	 * @param koAt 이 보드가 진 순간의 효과 시각, 지지 않았으면 {@link Long#MIN_VALUE}
	 */
	static double trauma(GameSession s, int i, long now, long koAt) {
		double sum = 0;
		List<GameSession.ClearFx> clears = s.clearFx(i);
		for (int k = 0; k < clears.size(); k++) {
			GameSession.ClearFx e = clears.get(k);
			sum += decay(e.perfectClear() ? 0.8 : big(e) ? 0.55 : 0, now - e.at());
		}
		GameSession.Tally t = s.tallies[i];
		if (t.risenLines >= 2) {
			sum += decay(Math.min(0.25 + 0.05 * t.risenLines, 0.65), now - t.risenAt);
		}
		if (koAt != Long.MIN_VALUE) {
			sum += decay(1.0, now - koAt);
		}
		return Motion.clamp01(sum);
	}

	private static double decay(double a, long ageMs) {
		return a <= 0 || ageMs < 0 ? 0 : Math.max(0, a - TRAUMA_DECAY * ageMs / 1000.0);
	}

	/** 흔들림의 가로 위치 (화소): (c/3)·m·충격²·잡음, 잡음은 11 Hz 로 부드럽게 바뀐다. 축과 보드마다 씨앗이 다르다. */
	static double shakeX(GameSession s, int i, long now, double trauma, int c, double m) {
		return c / 3.0 * m * trauma * trauma * Fx.noise(seed(s, i) ^ 0x5DEECE66DL, now * 0.011);
	}

	/** 흔들림의 세로 위치 (화소). */
	static double shakeY(GameSession s, int i, long now, double trauma, int c, double m) {
		return c / 3.0 * m * trauma * trauma * Fx.noise(seed(s, i) ^ 0x2545F4914F6CDD1DL, now * 0.011);
	}

	/**
	 * 하드드롭의 튀는 반동 (화소, 아래가 +, SPEC V5): p·spring(t), p = (0.08 + 0.09k)·c·m, k = (내려온 줄 − 2)/13 을 0~1 로 자른
	 * 값. 21ms 에 가장 내려가고 87ms 에 조금 되튀고 250ms 면 끝난다. c = 24 에서 m = 1 이면 2~4 화소.
	 */
	static int bump(GameSession s, int i, long now, int c, double m) {
		List<GameSession.LockFx> locks = s.lockFx(i);
		for (int k = locks.size() - 1; k >= 0; k--) {
			GameSession.LockFx e = locks.get(k);
			long age = now - e.at();
			if (age >= BUMP_MS) {
				return 0;
			}
			if (age >= 0 && e.hardDrop()) {
				double kk = Motion.clamp01((e.dropRows() - 2) / 13.0);
				return (int) Math.round((0.08 + 0.09 * kk) * c * m * Motion.spring(age));
			}
		}
		return 0;
	}

	/** 방해 줄 n 줄이 오르는 움직임의 길이 (ms): min(80 + 60n, 300). */
	static int riseMs(int lines) {
		return Math.min(80 + 60 * lines, 300);
	}

	/**
	 * 방해 줄이 오르는 움직임 (화소, 아래가 +, SPEC V7(b)): 더미, 고스트, 블록을 n·c·(1 − easeOutCubic(t/T)) 만큼 아래에 그려
	 * 올라오게 한다. 모두 같이 움직이므로 고스트는 언제나 진짜 놓일 자리를 가리킨다. 효과를 끄면 0.
	 */
	static double rise(GameSession.Tally t, long now, int c, double m) {
		if (m <= 0 || t.risenLines <= 0) {
			return 0;
		}
		long age = now - t.risenAt;
		int dur = riseMs(t.risenLines);
		if (age < 0 || age >= dur) {
			return 0;
		}
		return t.risenLines * c * (1 - Motion.easeOutCubic(age / (double) dur));
	}

	// ------------------------------------------------------------------ 보드 안 (보드와 함께 움직인다)

	/** 새로 오른 방해 줄을 잠깐 하얗게: 알파 0.4 → 0, 150ms. {@code dy} 는 오르는 움직임. */
	static void riseTint(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st, double dy) {
		GameSession.Tally t = s.tallies[i];
		double fm = st.flash(i);
		long age = now - t.risenAt;
		if (fm <= 0 || t.risenLines <= 0 || age < 0 || age >= RISE_TINT_MS) {
			return;
		}
		double a = Math.min(st.flashCap(), 0.4 * fm) * (1 - age / (double) RISE_TINT_MS);
		int n = Math.min(t.risenLines, ROWS);
		int c = geo.c();
		fill(g, Color.WHITE, a, geo.boardX(), geo.top() + (ROWS - n) * c + Math.round(dy), geo.boardW(), n * c);
	}

	/**
	 * 지운 줄의 번쩍임 (SPEC V4): 지우기 전 자리에 흰 띠가 가운데에서 넓어지며 얇아진다. 길이 120/140/160/180ms (1~4줄), T-spin
	 * +20ms, 퍼펙트 클리어 220ms. 알파 0.45 (4줄·T-spin·퍼펙트 클리어 0.55)·(1 − u)², 번쩍임 줄이기면 0.3 까지. 1초에 3번까지만
	 * 시작한다(그 뒤의 지우기는 번쩍이지 않는다).
	 */
	static void clearFlash(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st) {
		double fm = st.flash(i);
		List<GameSession.ClearFx> clears = s.clearFx(i);
		if (fm <= 0 || clears.isEmpty()) {
			return;
		}
		int shown = governor(clears);
		Composite old = g.getComposite();
		int c = geo.c();
		for (int k = 0; k < clears.size(); k++) {
			GameSession.ClearFx e = clears.get(k);
			long age = now - e.at();
			int dur = e.perfectClear() ? 220 : 100 + 20 * e.lines() + (e.spin() != Spin.NONE ? 20 : 0);
			if ((shown & 1 << k) == 0 || age < 0 || age >= dur) {
				continue;
			}
			double u = age / (double) dur;
			boolean strong = e.lines() == 4 || e.spin() != Spin.NONE || e.perfectClear();
			double a = Math.min(st.flashCap(), (strong ? 0.55 : 0.45) * fm) * (1 - u) * (1 - u);
			AlphaComposite ac = Fx.times(old, a);
			if (ac == null) {
				continue;
			}
			g.setComposite(ac);
			g.setColor(e.perfectClear() || strong ? Fx.lighter(bigColor(e, st.look()), 0.75) : Color.WHITE);
			double w = geo.boardW() * (0.5 + 0.5 * Motion.easeOutCubic(Math.min(1, u / 0.3)));
			double h = c * (1 - Motion.easeInCubic(u));
			int ix = (int) Math.round(geo.boardX() + (geo.boardW() - w) / 2);
			int iw = (int) Math.round(w);
			int ih = (int) Math.round(h);
			for (int row : e.rows()) {
				if (row >= VISIBLE_TOP && ih > 0) {
					g.fillRect(ix, geo.rowY(row) + (c - ih) / 2, iw, ih);
				}
			}
		}
		g.setComposite(old);
	}

	/** 번쩍임을 시작하는 지우기의 비트 (오래된 것부터 보고, 앞선 1초 안에 이미 3번 번쩍였으면 건너뛴다). */
	static int governor(List<GameSession.ClearFx> clears) {
		int shown = 0;
		for (int k = 0; k < clears.size(); k++) {
			long at = clears.get(k).at();
			int recent = 0;
			for (int j = 0; j < k; j++) {
				if ((shown & 1 << j) != 0 && at - clears.get(j).at() < 1000) {
					recent++;
				}
			}
			if (recent < FLASHES_PER_SECOND) {
				shown |= 1 << k;
			}
		}
		return shown;
	}

	/**
	 * 고정 반짝임 (SPEC V5): 놓인 칸(최종 자리)을 하얗게 덮었다가 걷는다. 보통 알파 0.55 → 0 (100ms), 하드드롭 0.70 → 0 (120ms),
	 * easeOutQuad. 번쩍임 줄이기면 절반. {@code dy} 는 방해 줄이 오르는 움직임.
	 */
	static void lockFlash(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st, double dy) {
		double fm = st.flash(i);
		if (fm <= 0) {
			return;
		}
		List<GameSession.LockFx> locks = s.lockFx(i);
		Composite old = g.getComposite();
		int c = geo.c();
		int oy = (int) Math.round(dy);
		for (int k = locks.size() - 1; k >= 0; k--) {
			GameSession.LockFx e = locks.get(k);
			long age = now - e.at();
			if (age >= HARD_FLASH_MS) {
				break;
			}
			int dur = e.hardDrop() ? HARD_FLASH_MS : LOCK_FLASH_MS;
			if (age < 0 || age >= dur) {
				continue;
			}
			double a0 = Math.min(1, (e.hardDrop() ? 0.70 : 0.55) * fm * (st.calm() ? 0.5 : 1));
			AlphaComposite ac = Fx.times(old, a0 * (1 - Motion.easeOutQuad(age / (double) dur)));
			if (ac == null) {
				continue;
			}
			g.setComposite(ac);
			g.setColor(Color.WHITE);
			for (int[] cell : e.cells()) {
				if (cell[0] >= VISIBLE_TOP) {
					g.fillRect(geo.colX(cell[1]) + 1, geo.rowY(cell[0]) + oy + 1, c - 2, c - 2);
				}
			}
		}
		g.setComposite(old);
	}

	/**
	 * 하드드롭 꼬리 (SPEC V5, 내려온 줄 ≥ 2): 블록의 열마다 떨어지기 전 자리에서 내려앉은 자리({@link GameSession.LockFx#landed},
	 * 줄을 지우기 전의 자리)까지 세로 띠. 그래서 칸이 모두 지워진 하드드롭(세로 I 의 테트리스 등)에도 꼬리가 있고, 아래 줄이 지워져도
	 * 꼬리는 블록이 실제로 내려앉은 곳(줄 지우기 번쩍임과 같은 자리)에서 끝난다. 위는 투명, 아래는 알파 0.35(1 − u)², 너비
	 * (c − 4)(1 − 0.5u), 블록 색을 흰색과 반씩. 110ms. 더미 아래에 그리므로 칸을 가리지 않는다.
	 */
	static void trail(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st, double dy) {
		double fm = st.flash(i);
		if (fm <= 0) {
			return;
		}
		List<GameSession.LockFx> locks = s.lockFx(i);
		for (int k = locks.size() - 1; k >= 0; k--) {
			GameSession.LockFx e = locks.get(k);
			long age = now - e.at();
			if (age >= TRAIL_MS) {
				return;
			}
			if (age < 0 || !e.hardDrop() || e.dropRows() < 2) {
				continue;
			}
			drawTrail(g, e, geo, age / (double) TRAIL_MS, st.look(), fm, dy);
			return;
		}
	}

	/** 고정 {@code e} 의 꼬리를 진행 {@code u}(0~1)로 그린다 (시험이 바로 부른다). */
	static void drawTrail(Graphics2D g, GameSession.LockFx e, BattleGeo geo, double u, ColorPalettes.Look look, double fm,
			double dy) {
		int[][] cells = e.landed();
		int c = geo.c();
		double w = (c - 4) * (1 - 0.5 * u);
		Color col = Fx.lighter(look.piece(e.code()), 0.5);
		Color bottomColor = Fx.alpha(col, 0.35 * (1 - u) * (1 - u) * fm);
		if (bottomColor.getAlpha() == 0) {
			return;
		}
		Color topColor = Fx.alphas(col)[0];
		int iw = (int) Math.round(w);
		Paint old = g.getPaint();
		for (int a = 0; a < cells.length; a++) {
			if (!topOfColumn(cells, a)) {
				continue;
			}
			int ix = (int) Math.round(geo.colX(cells[a][1]) + (c - w) / 2);
			int bottom = (int) Math.round(geo.rowY(cells[a][0]) + dy);
			int from = bottom - e.dropRows() * c;
			int y0 = Math.max(geo.top(), from);
			if (bottom <= y0) {
				continue;
			}
			// 위 투명 → 아래 알파: 떨어지기 전 자리에서 놓인 자리까지 (보드 위로 넘친 부분은 자른다)
			g.setPaint(new GradientPaint(0, from, topColor, 0, bottom, bottomColor));
			g.fillRect(ix, y0, iw, bottom - y0);
		}
		g.setPaint(old);
	}

	/** {@code cells[a]} 가 그 열에서 가장 위 칸인가 (같은 열의 더 위 칸이나, 같은 자리의 앞 칸이 없다). */
	private static boolean topOfColumn(int[][] cells, int a) {
		for (int b = 0; b < cells.length; b++) {
			if (b != a && cells[b][1] == cells[a][1] && (cells[b][0] < cells[a][0] || cells[b][0] == cells[a][0] && b < a)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 줄 지우기 조각 (SPEC V4): 지운 칸마다 round(2m)개, 칸 색을 20% 밝게. 가로로 바깥쪽(왼쪽 반은 왼쪽으로) 3~9c/s 에 끌림,
	 * 세로로 2~7c/s 위로 튀었다 40c/s² 로 떨어진다. 수명 0.35~0.6초, 알파 (1 − t/L)², 크기 0.22~0.32c 에서 60% 로. 지우기 하나에
	 * 80개, 보드에 200개까지. 퍼펙트 클리어는 보드 가운데에서 금빛 조각 40·m 개가 더 튄다. 홀드·미리보기 카드와 막대를 덮지 않게
	 * 보드 둘레 안에서만 그린다.
	 */
	static void particles(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st) {
		double m = st.m(i);
		int per = (int) Math.round(2 * m);
		List<GameSession.ClearFx> clears = s.clearFx(i);
		if (per <= 0 || clears.isEmpty()) {
			return;
		}
		long seed = seed(s, i);
		int c = geo.c();
		Box box = new Box(geo.boardX() - 0.35 * c, geo.boardX() + geo.boardW() + 0.5 * c, geo.top() - c,
				geo.top() + geo.boardH());
		int alive = 0;
		for (int k = clears.size() - 1; k >= 0 && alive < MAX_ALIVE; k--) {
			GameSession.ClearFx e = clears.get(k);
			long age = now - e.at();
			if (age >= PARTICLE_MAX_MS) {
				break;
			}
			if (age < 0) {
				continue;
			}
			double t = age / 1000.0;
			alive += cellBurst(g, e, seed, geo, t, per, st.look(), box, MAX_ALIVE - alive);
			if (e.perfectClear()) {
				alive += goldBurst(g, e, seed, geo, t, m, box, MAX_ALIVE - alive);
			}
		}
	}

	/** 조각을 그려도 되는 사각형. */
	private record Box(double minX, double maxX, double minY, double maxY) {
	}

	private static int cellBurst(Graphics2D g, GameSession.ClearFx e, long seed, BattleGeo geo, double t, int per,
			ColorPalettes.Look look, Box box, int budget) {
		int[] rows = e.rows();
		int[][] codes = e.codes();
		int n = Math.min(per, MAX_PER_CLEAR / Math.max(1, rows.length * Grid.WIDTH));
		int c = geo.c();
		double drift = (1 - Math.exp(-2 * t)) / 2;
		double fall = 20.0 * c * t * t;
		int drawn = 0;
		for (int r = 0; r < rows.length; r++) {
			int row = rows[r];
			if (row < VISIBLE_TOP) {
				continue;
			}
			double y0 = geo.rowY(row) + c / 2.0;
			for (int col = 0; col < Grid.WIDTH; col++) {
				int code = codes[r][col];
				if (code == Grid.EMPTY) {
					continue;
				}
				Color[] tab = Fx.alphas(Fx.lighter(look.piece(code), 0.2));
				double x0 = geo.colX(col) + c / 2.0;
				double dir = col < Grid.WIDTH / 2 ? -1 : 1;
				for (int j = 0; j < n; j++) {
					if (drawn >= budget) {
						return drawn;
					}
					int key = j * 8;
					double life = Fx.rand(seed, e.at(), row, col, key, 0.35, 0.60);
					if (t >= life) {
						continue;
					}
					double vx = dir * Fx.rand(seed, e.at(), row, col, key + 1, 3, 9) * c;
					double vy = -Fx.rand(seed, e.at(), row, col, key + 2, 2, 7) * c;
					double u = t / life;
					double size = Fx.rand(seed, e.at(), row, col, key + 3, 0.22, 0.32) * c * (1 - 0.4 * u);
					square(g, tab, (1 - u) * (1 - u), x0 + vx * drift, y0 + vy * t + fall, size, box);
					drawn++;
				}
			}
		}
		return drawn;
	}

	/** 퍼펙트 클리어: 보드 가운데에서 금빛 조각 40·m 개가 사방으로 (수명 0.5~0.8초). */
	private static int goldBurst(Graphics2D g, GameSession.ClearFx e, long seed, BattleGeo geo, double t, double m, Box box,
			int budget) {
		int n = Math.min(budget, (int) Math.round(40 * m));
		Color[] tab = Fx.alphas(Fx.lighter(Theme.GOLD, 0.2));
		int c = geo.c();
		double cx = geo.boardX() + geo.boardW() / 2.0;
		double cy = geo.top() + geo.boardH() / 2.0;
		double drift = (1 - Math.exp(-3 * t)) / 3;
		int drawn = 0;
		for (int j = 0; j < n; j++) {
			double life = Fx.rand(seed, e.at(), -1, j, 0, 0.5, 0.8);
			if (t >= life) {
				continue;
			}
			double ang = Fx.rand(seed, e.at(), -1, j, 1) * 2 * Math.PI;
			double speed = Fx.rand(seed, e.at(), -1, j, 2, 6, 16) * c;
			double u = t / life;
			double size = Fx.rand(seed, e.at(), -1, j, 3, 0.2, 0.34) * c * (1 - 0.4 * u);
			square(g, tab, (1 - u) * (1 - u), cx + Math.cos(ang) * speed * drift,
					cy + Math.sin(ang) * speed * drift + 10.0 * c * t * t, size, box);
			drawn++;
		}
		return drawn;
	}

	/** 가운데 (cx, cy), 한 변 size 의 조각 하나를 상자 안만 채운다. 알파는 16단계 표에서. */
	private static void square(Graphics2D g, Color[] tab, double a, double cx, double cy, double size, Box box) {
		int idx = (int) Math.round(Motion.clamp01(a) * (Fx.ALPHA_STEPS - 1));
		if (idx <= 0) {
			return;
		}
		double h = size / 2;
		int x0 = (int) Math.round(Math.max(box.minX(), cx - h));
		int x1 = (int) Math.round(Math.min(box.maxX(), cx + h));
		int y0 = (int) Math.round(Math.max(box.minY(), cy - h));
		int y1 = (int) Math.round(Math.min(box.maxY(), cy + h));
		if (x1 > x0 && y1 > y0) {
			g.setColor(tab[idx]);
			g.fillRect(x0, y0, x1 - x0, y1 - y0);
		}
	}

	/**
	 * 큰 지우기의 보드 빛 (SPEC V4): 4줄은 I 색, T-spin 더블·트리플은 T 색, 퍼펙트 클리어는 금색으로 세기 0.5·m 을 더했다가
	 * 300ms 동안 걷는다. AI 보드도 m 그대로 둔다(AI 의 테트리스를 알아채게, V3(e)).
	 */
	static void bigGlow(Graphics2D g, GameSession s, int i, long now, BattleStyle st, double x, double y, double w,
			double h, double r) {
		if (st.m() <= 0) {
			return;
		}
		List<GameSession.ClearFx> clears = s.clearFx(i);
		for (int k = clears.size() - 1; k >= 0; k--) {
			GameSession.ClearFx e = clears.get(k);
			long age = now - e.at();
			if (age >= GLOW_MS) {
				return;
			}
			if (age >= 0 && (big(e) || e.perfectClear())) {
				Theme.glow(g, x, y, w, h, r, bigColor(e, st.look()),
						0.5 * st.m() * (1 - Motion.easeOutCubic(age / (double) GLOW_MS)));
				return;
			}
		}
	}

	/** 퍼펙트 클리어 고리 (SPEC V4): 보드 가운데에서 반대각선의 1.2배까지 퍼지며 굵기 6 → 1, 알파 0.8 → 0 (500ms). 번쩍임 줄이기면 없다. */
	static void pcRing(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st) {
		double fm = st.flash(i);
		if (st.calm() || fm <= 0) {
			return;
		}
		List<GameSession.ClearFx> clears = s.clearFx(i);
		for (int k = clears.size() - 1; k >= 0; k--) {
			GameSession.ClearFx e = clears.get(k);
			long age = now - e.at();
			if (age >= RING_MS) {
				return;
			}
			if (age < 0 || !e.perfectClear()) {
				continue;
			}
			double u = age / (double) RING_MS;
			double cx = geo.boardX() + geo.boardW() / 2.0;
			double cy = geo.top() + geo.boardH() / 2.0;
			double r = 0.6 * Math.hypot(geo.boardW(), geo.boardH()) * Motion.easeOutCubic(u);
			Shape clip = g.getClip();
			Stroke stroke = g.getStroke();
			g.clipRect(geo.boardX(), geo.top(), geo.boardW(), geo.boardH());
			g.setStroke(new BasicStroke((float) (6 - 5 * u)));
			g.setColor(Fx.alpha(Theme.GOLD, 0.8 * (1 - u) * fm));
			g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
			g.setStroke(stroke);
			g.setClip(clip);
			return;
		}
	}

	// ------------------------------------------------------------------ 보드 밖 (움직이지 않는다)

	/**
	 * 가장자리 불꽃 (SPEC V4): 지운 줄마다 보드 양쪽 벽 바로 바깥에 3 화소 × 1칸 막대. 색은 그 줄의 끝 칸, 알파 0.9 → 0 (250ms,
	 * easeOutQuad). 아무것도 가리지 않으므로 번쩍임 줄이기에도 남는다.
	 */
	static void edgeFlares(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st) {
		double fm = st.flash(i);
		List<GameSession.ClearFx> clears = s.clearFx(i);
		if (fm <= 0 || clears.isEmpty()) {
			return;
		}
		int c = geo.c();
		int pad = (int) Math.round(geo.pad());
		int leftX = geo.boardX() - pad - 1 - FLARE_W;
		int rightX = geo.boardX() + geo.boardW() + pad + 1;
		for (int k = 0; k < clears.size(); k++) {
			GameSession.ClearFx e = clears.get(k);
			long age = now - e.at();
			if (age < 0 || age >= FLARE_MS) {
				continue;
			}
			double a = 0.9 * fm * (1 - Motion.easeOutQuad(age / (double) FLARE_MS));
			int[] rows = e.rows();
			int[][] codes = e.codes();
			for (int r = 0; r < rows.length; r++) {
				if (rows[r] < VISIBLE_TOP) {
					continue;
				}
				int y = geo.rowY(rows[r]);
				g.setColor(Fx.alpha(st.look().piece(endCode(codes[r], 0)), a));
				g.fillRect(leftX, y, FLARE_W, c);
				g.setColor(Fx.alpha(st.look().piece(endCode(codes[r], Grid.WIDTH - 1)), a));
				g.fillRect(rightX, y, FLARE_W, c);
			}
		}
	}

	private static int endCode(int[] row, int col) {
		int v = row[col];
		return v >= 1 && v <= Grid.GARBAGE ? v : Grid.GARBAGE;
	}

	// ------------------------------------------------------------------ 도구

	/** 색 {@code c} 를 알파 {@code a} 로 채운다 (지금 합성에 곱하고 돌려놓는다). */
	static void fill(Graphics2D g, Color c, double a, double x, double y, double w, double h) {
		if (!(a > 0) || w <= 0 || h <= 0) {
			return;
		}
		Composite old = g.getComposite();
		AlphaComposite ac = Fx.times(old, a);
		if (ac == null) {
			return;
		}
		g.setComposite(ac);
		g.setColor(c);
		int ix = (int) Math.round(x);
		int iy = (int) Math.round(y);
		g.fillRect(ix, iy, (int) Math.round(x + w) - ix, (int) Math.round(y + h) - iy);
		g.setComposite(old);
	}

	/** 지금 합성에 알파 {@code a} 를 곱해 걸고, 돌려놓을 합성을 돌려준다. 곱할 수 없는 합성이면 그대로 둔다. */
	static Composite alpha(Graphics2D g, double a) {
		Composite old = g.getComposite();
		AlphaComposite ac = Fx.times(old, a);
		if (ac != null) {
			g.setComposite(ac);
		}
		return old;
	}
}
