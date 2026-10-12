package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.util.List;

import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.Spin;

/**
 * 동작 글자 (runs/quality/SPEC.md V6): 홀드 카드 아래, 보드 옆 빈 칸에 오른쪽을 맞춰(보드를 가리지 않는다) 방금 지운 줄을
 * 보인다. 위에서부터 "T-SPIN" 머리말, 큰 낱말(SINGLE~TETRIS), 퍼펙트 클리어, "B2B ×N", "N COMBO", "2줄 → 공격 6", 공격 내역
 * ("기본 2 + 콤보 1 …"), "상쇄 N · 보냄 M". 과제 3번(지운 줄만큼 공격)을 화면에서 보인다.
 * <ul>
 * <li>움직임: 큰 낱말은 오른쪽 끝·기준선을 붙잡고 1.35배에서 120ms 에 1배로(easeOutBack), 60ms 에 나타난다. 머리말은 10
 * 화소 오른쪽에서 150ms 에 미끄러져 들어온다. B2B 는 +60ms, 콤보는 +120ms 에 나타나고 콤보 수는 1.3배에서 1배로. 끝의 300ms
 * 에 옅어진다. 효과를 끄면 옅어지기만 한다.</li>
 * <li>보이는 시간: 1~3줄 1.2초, 4줄·T-spin·B2B·콤보·퍼펙트 클리어 2초, 공격 내역이나 상쇄를 보이면 2.6초.</li>
 * </ul>
 */
final class BattleLog {

	private static final String[] WORDS = { "", "SINGLE", "DOUBLE", "TRIPLE", "TETRIS" };
	/** 머리말 "T-SPIN" 의 보라 (BOARD 위 6.5:1). 색약 색상에서는 그 묶음의 T 색을 밝게 쓴다. */
	private static final Color SPIN = new Color(0xC28BFF);
	/** 끝에 옅어지는 시간 (ms). */
	static final int FADE_MS = 300;

	private BattleLog() {
	}

	/** 이 지우기를 보이는 시간 (ms). */
	static long holdMs(GameSession.Tally t) {
		int[] parts = t.clearParts;
		boolean breakdown = t.clearAttack != parts[0];
		if (breakdown || t.clearAttack - t.clearSent > 0) {
			return 2600;
		}
		boolean big = t.clearLines == 4 || t.clearSpin != Spin.NONE || parts[1] > 0 || t.clearCombo >= 1 || parts[3] > 0;
		return big ? 2000 : 1200;
	}

	static void paint(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st, Color pc) {
		GameSession.Tally t = s.tallies[i];
		long age = now - t.clearAt;
		long hold = holdMs(t);
		if (t.clearLines <= 0 || age < 0 || age >= hold) {
			return;
		}
		int c = geo.c();
		boolean motion = st.m(i) > 0;
		double fade = age > hold - FADE_MS ? (hold - age) / (double) FADE_MS : 1;
		double xr = geo.x0() + geo.cardW(); // 홀드 카드의 오른쪽 끝
		double y = geo.top() + 4.7 * c;
		Composite base = g.getComposite();
		int b2b = b2bStreak(s, i, t);
		int[] parts = t.clearParts;

		// 머리말 "T-SPIN" / "T-SPIN MINI"
		if (t.clearSpin != Spin.NONE) {
			double dx = motion ? 10 * (1 - Motion.easeOutCubic(age / 150.0)) : 0;
			alpha(g, base, fade * Motion.clamp01(age / 90.0));
			g.setColor(spinColor(st.look()));
			g.setFont(Theme.tracked(Theme.numeric(0.62 * c), 0.12));
			Theme.text(g, t.clearSpin == Spin.MINI ? "T-SPIN MINI" : "T-SPIN", xr + dx, y, Theme.Align.RIGHT);
		}
		y += 1.3 * c;
		// 큰 낱말
		double k = motion ? 1.35 - 0.35 * Motion.easeOutBack(Motion.clamp01(age / 120.0)) : 1;
		alpha(g, base, fade * Motion.clamp01(age / 60.0));
		g.setColor(t.clearLines == 4 ? st.look().piece(PieceType.I.code()) : Color.WHITE);
		scaled(g, Theme.tracked(Theme.display(1.35 * c), 0.04), WORDS[Math.min(4, t.clearLines)], xr, y, k);
		y += 0.9 * c;
		// 퍼펙트 클리어 (두 줄)
		if (parts[3] > 0) {
			g.setColor(Theme.GOLD);
			g.setFont(Theme.display(1.0 * c));
			y += 0.15 * c;
			Theme.text(g, "PERFECT", xr, y, Theme.Align.RIGHT);
			y += 0.9 * c;
			Theme.text(g, "CLEAR", xr, y, Theme.Align.RIGHT);
			y += 0.9 * c;
		}
		// B2B ×N (+60ms)
		if (b2b > 0 || parts[1] > 0) {
			alpha(g, base, fade * Motion.clamp01((age - 60) / 60.0));
			g.setColor(Theme.GOLD);
			g.setFont(Theme.numeric(0.62 * c));
			Theme.drawNumber(g, b2b >= 2 ? "B2B ×" + b2b : "B2B", xr, y, Theme.Align.RIGHT);
			y += 1.3 * c;
		}
		// N COMBO (+120ms)
		if (t.clearCombo >= 1) {
			long ca = age - 120;
			double a = fade * Motion.clamp01(ca / 60.0);
			if (t.clearCombo >= 10) { // 큰 콤보는 숫자가 숨 쉰다 (2 Hz 까지)
				a *= 0.75 + 0.25 * Motion.pulse(Math.max(0, ca), st.maxHz());
			}
			alpha(g, base, a);
			g.setColor(Color.WHITE);
			g.setFont(Theme.numeric(0.75 * c));
			float w = Theme.text(g, "COMBO", xr, y, Theme.Align.RIGHT);
			double nk = motion ? 1.3 - 0.3 * Motion.easeOutBack(Motion.clamp01(ca / 120.0)) : 1;
			g.setColor(Theme.WARN);
			scaled(g, Theme.display((1.1 + 0.05 * Math.min(t.clearCombo, 10)) * c), String.valueOf(t.clearCombo),
					xr - w - 0.18 * c, y, nk);
			y += 0.9 * c;
		}
		// "2줄 → 공격 6", 공격 내역, 상쇄 (+120ms)
		alpha(g, base, fade * Motion.clamp01((age - 120) / 120.0));
		g.setColor(pc);
		g.setFont(Theme.font(0.58 * c, true));
		Theme.text(g, t.clearLines + "줄 → 공격 " + t.clearAttack, xr, y, Theme.Align.RIGHT);
		y += 0.7 * c;
		double maxW = geo.cardW() - 0.2 * c;
		if (t.clearAttack != parts[0]) { // 보너스가 있을 때만 내역
			StringBuilder sb = new StringBuilder("기본 ").append(parts[0]);
			if (parts[2] > 0) {
				sb.append(" + 콤보 ").append(parts[2]);
			}
			if (parts[1] > 0) {
				sb.append(" + B2B ").append(parts[1]);
			}
			if (parts[3] > 0) {
				sb.append(" + PC ").append(parts[3]);
			}
			g.setColor(Theme.LABEL);
			g.setFont(Theme.font(0.46 * c, false));
			y = wrapRight(g, sb.toString(), xr, y, maxW, 0.62 * c);
		}
		int cancelled = t.clearAttack - t.clearSent;
		if (cancelled > 0) {
			g.setColor(Theme.WARN);
			g.setFont(Theme.font(0.46 * c, true));
			wrapRight(g, "상쇄 " + cancelled + (st.versus() && t.clearSent > 0 ? " · 보냄 " + t.clearSent : ""), xr, y,
					maxW, 0.62 * c);
		}
		g.setComposite(base);
	}

	/** 이 지우기의 B2B 이어진 횟수 (마지막 줄 지우기 사건이 이 지우기면 그 값). */
	private static int b2bStreak(GameSession s, int i, GameSession.Tally t) {
		List<GameSession.ClearFx> clears = s.clearFx(i);
		if (clears.isEmpty()) {
			return 0;
		}
		GameSession.ClearFx last = clears.get(clears.size() - 1);
		return last.at() == t.clearAt ? last.b2bStreak() : 0;
	}

	/** "T-SPIN" 의 색: 기본 색은 #C28BFF, 색약 색상은 그 묶음의 T 색을 밝게. */
	private static Color spinColor(ColorPalettes.Look look) {
		Color t = look.piece(PieceType.T.code());
		return t.equals(Theme.piece(PieceType.T.code())) ? SPIN : Fx.lighter(t, 0.3);
	}

	private static void alpha(Graphics2D g, Composite base, double a) {
		g.setComposite(base);
		BattleFx.alpha(g, Motion.clamp01(a));
	}

	/** 오른쪽 끝과 기준선 (xr, y) 를 붙잡고 k 배로 그린다. */
	private static void scaled(Graphics2D g, Font f, String s, double xr, double y, double k) {
		g.setFont(f);
		if (Math.abs(k - 1) < 1e-3) {
			Theme.text(g, s, xr, y, Theme.Align.RIGHT);
			return;
		}
		AffineTransform saved = g.getTransform();
		g.translate(xr, y);
		g.scale(k, k);
		Theme.text(g, s, 0, 0, Theme.Align.RIGHT);
		g.setTransform(saved);
	}

	/** 너비가 넘치면 " + " 와 " · " 앞에서 줄을 바꿔 오른쪽을 맞춰 그린다. 다음 줄의 기준선을 돌려준다. */
	private static double wrapRight(Graphics2D g, String text, double xr, double y, double maxW, double step) {
		StringBuilder line = new StringBuilder();
		for (String part : text.split(" (?=[+·])")) {
			String next = line.length() == 0 ? part : line + " " + part;
			if (line.length() > 0 && Theme.textWidth(g, next) > maxW) {
				Theme.text(g, line.toString(), xr, y, Theme.Align.RIGHT);
				y += step;
				line = new StringBuilder(part);
			} else {
				line = new StringBuilder(next);
			}
		}
		Theme.text(g, line.toString(), xr, y, Theme.Align.RIGHT);
		return y + step;
	}

	/** 퍼펙트 클리어만은 보드 가운데에 금색 띠로 2초 보인다 (끝의 0.5초에 옅어진다). */
	static void perfect(Graphics2D g, GameSession.Tally t, BattleGeo geo, long now) {
		long age = now - t.clearAt;
		if (t.clearParts[3] <= 0 || age < 0 || age >= 2000) {
			return;
		}
		int c = geo.c();
		double a = age > 1500 ? (2000 - age) / 500.0 : 1;
		double y = geo.top() + geo.boardH() * 0.4;
		Composite old = BattleFx.alpha(g, a);
		g.setColor(Fx.alpha(Theme.BG_TOP, 0.8));
		g.fillRect(geo.boardX(), (int) Math.round(y - 0.9 * c), geo.boardW(), (int) Math.round(1.4 * c));
		g.setColor(Theme.GOLD);
		g.setFont(Theme.tracked(Theme.display(0.9 * c), 0.04));
		Theme.text(g, "PERFECT CLEAR", geo.boardX() + geo.boardW() / 2.0, y + 0.1 * c, Theme.Align.CENTER);
		g.setComposite(old);
	}
}
