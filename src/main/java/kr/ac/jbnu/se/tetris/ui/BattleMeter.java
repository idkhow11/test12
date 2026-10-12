package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.TexturePaint;
import java.awt.image.BufferedImage;
import java.util.List;

import kr.ac.jbnu.se.tetris.audio.SoundCues;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.GarbageQueue;

/**
 * 받을 방해 줄 막대 (runs/quality/SPEC.md V7(a)). 보드 왼쪽의 좁은 막대에 받은 공격마다 한 조각씩(사이 2 화소) 아래부터 쌓는다.
 * <ul>
 * <li>색: 다음에 줄을 지우지 못하고 블록을 놓으면 올라올 아래쪽 8줄(RULES 10.3)은 빨강(#FF3B30), 그 위에서 기다리는 줄은 주황
 * 반투명.</li>
 * <li>새 조각: 333ms 동안 √(시간) 으로 자라며 처음 200ms 는 하얗게 빛난다. 사람 대 AI 에서는 상대가 보낸 "+N" 이 막대에 닿는
 * 0.45초 뒤에 자라기 시작한다(방해 줄 경고 소리와 같은 때).</li>
 * <li>상쇄: 줄어든 만큼이 200ms 동안 아래에서 줄어들며 사라진다.</li>
 * <li>줄 수: 0.55c 숫자. 8줄 이상이면 0.8c 큰 숫자가 2 Hz(번쩍임 줄이기면 1 Hz)로 숨 쉬고, 빨간 조각에 45° 흰 빗금(색이 아닌
 * 단서)을 긋는다.</li>
 * </ul>
 * 이 그림은 움직이지 않는다(흔들림, 반동과 무관). 막대가 차오르는 것은 정보라서 효과를 꺼도 남는다.
 */
final class BattleMeter {

	/** 다음 고정에 올라올 수 있는 줄 (RULES 10.3). */
	private static final int RISE = GarbageQueue.MAX_RISE;
	/** 새 조각이 자라는 시간 (ms). */
	static final int GROW_MS = 333;
	/** 새 조각이 하얗게 빛나는 시간 (ms). */
	static final int ARRIVE_FLASH_MS = 200;
	/** 상쇄된 조각이 줄어드는 시간 (ms). */
	static final int CANCEL_MS = 200;
	/** 조각 사이 (화소). */
	private static final int GAP = 2;
	/** 기다리는 줄의 주황 (알파 0.45). */
	private static final Color WAITING = Fx.alpha(Theme.WARN, 0.45);
	/** 4~7줄일 때 줄 수의 색 (주황과 빨강의 가운데). */
	private static final Color AMBER_RED = Theme.mix(Theme.WARN, Theme.DANGER, 0.5);
	/** 45° 흰 빗금 (3 화소 굵기, 6 화소마다, 알파 0.35). 한 번만 만들어 다시 쓴다. */
	private static final TexturePaint STRIPES = stripes();

	private BattleMeter() {
	}

	private static TexturePaint stripes() {
		BufferedImage tile = new BufferedImage(6, 6, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 6; y++) {
			for (int x = 0; x < 6; x++) {
				if ((x + y) % 6 < 3) {
					tile.setRGB(x, y, 0x59FFFFFF);
				}
			}
		}
		return new TexturePaint(tile, new Rectangle(0, 0, 6, 6));
	}

	/**
	 * 보드 i 의 막대를 그리고, 보이는 조각의 맨 위 y 를 돌려준다 (날아오는 "+N" 이 닿을 곳).
	 */
	static double paint(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, BattleStyle st) {
		int x = geo.meterX();
		int top = geo.top();
		int w = geo.meterW();
		int h = geo.boardH();
		int c = geo.c();
		Theme.card(g, x, top, w, h, w, Theme.BOARD, Theme.BORDER);
		GameModel game = s.match.player(i).game();
		List<GarbageQueue.Chunk> chunks = game.pending();
		double bottom = top + h;
		double y = bottom;
		// 상쇄: 바닥에서 줄어드는 조각 (그 위의 조각들이 내려앉는다)
		List<GameSession.CancelFx> cancels = s.cancelFx(i);
		if (!cancels.isEmpty()) {
			GameSession.CancelFx e = cancels.get(cancels.size() - 1);
			long age = now - e.at();
			if (age >= 0 && age < CANCEL_MS) {
				double u = age / (double) CANCEL_MS;
				double hc = Math.min(h, e.lines() * c) * (1 - Motion.easeOutCubic(u));
				if (hc >= 1) {
					g.setColor(Fx.alpha(Color.WHITE, 0.55 * (1 - u)));
					segment(g, x, y - hc, w, hc);
					y -= hc + GAP;
				}
			}
		}
		int total = game.pendingLines();
		int danger = Math.min(RISE, total); // 아래쪽 이만큼이 다음 고정에 오른다
		List<GameSession.ReceiveFx> received = s.receiveFx(i);
		long flight = st.versus() ? SoundCues.ATTACK_FLIGHT_MS : 0;
		int n = chunks.size();
		int shown = 0; // 막대에 보이는 줄 (날아오는 중인 조각은 빼고)
		for (int k = 0; k < n; k++) {
			if (age(received, n - 1 - k, now, flight) >= 0) {
				shown += chunks.get(k).lines();
			}
		}
		boolean striped = shown >= RISE; // 빗금과 큰 숫자는 보이는 줄 수로 (둘이 늘 같이 나온다)
		double fm = st.flash(i);
		int below = 0; // 아래에서부터 센 줄 (빨강·주황을 가른다)
		for (int k = 0; k < n && y > top; k++) {
			GarbageQueue.Chunk ch = chunks.get(k);
			int lines = ch.lines();
			double grow = 1;
			double flash = 0;
			long age = age(received, n - 1 - k, now, flight);
			if (age < 0) { // 아직 날아오는 중
				below += lines;
				continue;
			}
			if (age < Long.MAX_VALUE) {
				if (age < GROW_MS) {
					grow = Math.sqrt(age / (double) GROW_MS);
				}
				if (age < ARRIVE_FLASH_MS) { // 도착을 알리는 정보라서 효과를 꺼도 옅게 남긴다
					flash = (fm > 0 && !st.calm() ? 0.6 : 0.3) * (1 - age / (double) ARRIVE_FLASH_MS);
				}
			}
			double segH = Math.min(y - top, lines * c * grow);
			int red = Math.max(0, Math.min(lines, danger - below));
			double redH = segH * red / lines;
			Paint old = g.getPaint();
			if (redH > 0) {
				g.setColor(Theme.DANGER);
				segment(g, x, y - redH, w, redH);
				if (striped) {
					g.setPaint(STRIPES);
					segment(g, x, y - redH, w, redH);
					g.setPaint(old);
				}
			}
			if (segH - redH > 0) {
				g.setColor(WAITING);
				segment(g, x, y - segH, w, segH - redH);
			}
			if (flash > 0) {
				g.setColor(Fx.alpha(Color.WHITE, flash));
				segment(g, x, y - segH, w, segH);
			}
			y -= segH + GAP;
			below += lines;
		}
		double visibleTop = Math.min(bottom, y + GAP);
		if (shown > 0) {
			count(g, shown, x + w / 2.0, visibleTop, c, now, st, !s.over());
		}
		return visibleTop;
	}

	/**
	 * 위에서 {@code fromTop} 번째 조각이 막대에 닿은 뒤 흐른 시간 (ms): 날아오는 중이면 음수, 받은 사건을 모르면(오래전에 받음)
	 * {@link Long#MAX_VALUE}. 위의 조각일수록 최근에 받은 것이다(상쇄와 상승은 아래에서부터 쓴다).
	 */
	private static long age(List<GameSession.ReceiveFx> received, int fromTop, long now, long flight) {
		if (fromTop >= received.size()) {
			return Long.MAX_VALUE;
		}
		return now - received.get(received.size() - 1 - fromTop).at() - flight;
	}

	/**
	 * 막대 안의 둥근 조각 하나 (양옆 2 화소 안쪽), 지금 칠로. 단색이면 둥근 카드({@link Theme#card})로 그려 조각 그림을 다시 쓴다
	 * (긴 조각도 사각형 몇 번이라 D3D 에서 싸다). 무늬(빗금)는 예전처럼 바로 칠한다.
	 */
	static void segment(Graphics2D g, int x, double y, int w, double h) {
		int iy = (int) Math.round(y);
		int ih = (int) Math.round(y + h) - iy;
		if (ih > 0) {
			int arc = Math.min(w - 4, ih);
			if (g.getPaint() instanceof Color fill) {
				Theme.card(g, x + 2, iy, w - 4, ih, arc, fill, null);
			} else {
				g.fillRoundRect(x + 2, iy, w - 4, ih, arc, arc);
			}
		}
	}

	/** 줄 수: 8줄 아래는 0.55c 숫자, 8줄 이상은 0.8c 큰 숫자가 숨 쉰다 (알파 0.65~1, 판이 끝나면({@code live} 가 아니면) 멈춘다). */
	private static void count(Graphics2D g, int lines, double cx, double topY, int c, long now, BattleStyle st,
			boolean live) {
		String text = String.valueOf(lines);
		if (lines >= RISE) {
			Composite old = BattleFx.alpha(g, live ? 0.65 + 0.35 * Motion.pulse(now, st.maxHz()) : 1);
			g.setColor(Theme.DANGER);
			g.setFont(Theme.display(0.8 * c));
			Theme.drawNumber(g, text, cx, topY - 0.22 * c, Theme.Align.CENTER);
			g.setComposite(old);
			return;
		}
		g.setColor(lines >= 4 ? AMBER_RED : Theme.WARN);
		g.setFont(Theme.numeric(0.55 * c));
		Theme.drawNumber(g, text, cx, topY - 0.22 * c, Theme.Align.CENTER);
	}
}
