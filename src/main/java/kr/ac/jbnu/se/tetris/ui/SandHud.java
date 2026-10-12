package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandPiece;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.sand.SandSnapshot;

/**
 * 모래 화면의 판 둘레 글자와 옆 카드: 위의 점수와 최고 점수, 왼쪽의 홀드·콤보 배율·단계·목표, 오른쪽의 미리보기와 통계. 글자는
 * SPEC V2 의 역할을 따른다: 작은 라벨은 {@link Theme.Type#LABEL}(색 {@link Theme#LABEL}), 숫자 값은
 * {@link Theme.Type#NUMERIC} 의 고정 폭 숫자(바뀌어도 옆으로 흔들리지 않는다), 큰 수(점수, 배율, 단계)는
 * {@link Theme.Type#DISPLAY}. 카드는 판이 튕겨도 움직이지 않는다(SPEC 0장 규칙 4).
 */
final class SandHud {

	private static final int G = SandRules.GRAIN;
	/** 최고 점수를 넘었다는 꼬리표와 결과 카드 제목. 대전 결과 카드의 "새 기록" 꼬리표와 같은 말이다. */
	static final String NEW_BEST = "새 기록";
	/**
	 * 목표 카드의 짧은 설명 (차례대로, 작은 창에서 넘치는 문장은 뒤에서부터 뺀다): 지우기, 콤보 배율, 끝나는 조건. 지우기와 끝나는
	 * 조건은 메뉴({@code MenuPanel.SAND_RULES})·도움말({@code HelpPanel.SAND_LINES})과 같은 말이다. 지우기의 "두툼하게"
	 * ({@link #GOAL_MARK})는 규칙 sand-0.3 의 지우기 문턱(알갱이 {@link SandRules#MIN_CLEAR_GRAINS}개, 칸 한 줄 분량)이다.
	 */
	static final String[] GOAL_LINES = { "같은 색 모래로 양쪽 벽을 두툼하게 이으면 사라져요", "연달아 지우면 배율이 올라요",
			"점선 위까지 쌓이면 끝나요" };
	/** 판에 얇은 띠(양쪽 벽을 이었지만 아직 얇아 남은 덩어리)가 있으면 목표 카드에서 모래색으로 밝히는 낱말. */
	static final String GOAL_MARK = "두툼하게";

	private SandHud() {
	}

	// ------------------------------------------------------------------ 위: 점수

	/** 점수(가운데 큰 숫자)와 그 아래 최고 점수, 넘으면 "새 기록" ({@link #NEW_BEST}). */
	static void header(Graphics2D g, SandPanel.Geo geo, SandSnapshot s) {
		int c = geo.c();
		double cx = geo.boardX() + geo.boardW() / 2.0;
		g.setColor(Theme.TEXT);
		displayNumber(g, SandPanel.number(s.score()), 1.45 * c, cx, geo.y0() + 1.45 * c, Theme.Align.CENTER);
		double fpx = Math.max(12, 0.5 * c);
		double baseline = geo.y0() + 2.35 * c;
		if (s.newBest()) {
			g.setFont(Theme.font(fpx, true));
			int w = g.getFontMetrics().stringWidth(NEW_BEST) + (int) Math.round(fpx * 1.2);
			Theme.chip(g, NEW_BEST, (int) Math.round(cx - w / 2.0), (int) Math.round(baseline), fpx, Theme.BG_TOP,
					Theme.GOLD);
		} else {
			labelled(g, "최고", SandPanel.number(s.best()), fpx, cx, baseline);
		}
	}

	/**
	 * 큰 숫자 (점수, 배율, 단계): DISPLAY 글꼴(Barlow Condensed Black)의 고정 폭 숫자라 값이 바뀌어도 글자가 옆으로 흔들리지
	 * 않는다. 색은 g 의 색. 그린 너비를 돌려준다.
	 */
	static float displayNumber(Graphics2D g, String s, double px, double x, double baseline, Theme.Align align) {
		g.setFont(Theme.fit(Theme.display(px), s));
		return Theme.drawNumber(g, s, x, baseline, align);
	}

	/** "라벨 값" 한 줄을 cx 가운데에: 라벨은 작은 라벨 색, 값은 고정 폭 숫자. */
	private static void labelled(Graphics2D g, String label, String value, double px, double cx, double baseline) {
		float lw = Theme.Type.LABEL.width(g, label, px);
		float gap = (float) (px * 0.45);
		float vw = Theme.Type.NUMERIC.width(g, value, px * 1.15);
		double x = cx - (lw + gap + vw) / 2.0;
		g.setColor(Theme.LABEL);
		Theme.Type.LABEL.draw(g, label, px, x, baseline, Theme.Align.LEFT);
		g.setColor(Theme.TEXT_DIM);
		Theme.Type.NUMERIC.draw(g, value, px * 1.15, x + lw + gap, baseline, Theme.Align.LEFT);
	}

	// ------------------------------------------------------------------ 왼쪽: 홀드, 콤보, 단계, 목표

	static void hold(Graphics2D g, SandPanel.Geo geo, SandSnapshot s, SandColors colors) {
		int c = geo.c();
		int x = geo.leftX();
		int y = geo.boardY();
		Theme.card(g, x, y, geo.sideW(), 3.4 * c, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		title(g, "HOLD", x, y, c);
		if (s.hold() != null) {
			mini(g, colors, s.hold(), x, y + 0.95 * c, geo.sideW(), 2.2 * c, cellSize(geo, 0.75), s.holdUsed());
		}
	}

	/** 콤보 배율: 큰 "×N" 과 남은 콤보 시간 막대 (다 줄면 한 단계 내려간다). */
	static void combo(Graphics2D g, SandPanel.Geo geo, SandSnapshot s, int y, double m) {
		int c = geo.c();
		int x = geo.leftX();
		int w = geo.sideW();
		int h = (int) Math.round(4.2 * c);
		boolean on = s.multiplier() > 1;
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		if (on) {
			Theme.glow(g, x, y, w, h, 0.5 * c, Theme.GOLD, 0.2 + 0.15 * Math.min(1, m));
		}
		title(g, "콤보 배율", x, y, c);
		int padX = (int) Math.round(0.4 * c);
		g.setColor(on ? Theme.GOLD : Theme.TEXT_FAINT);
		displayNumber(g, "×" + s.multiplier(), 1.6 * c, x + padX, y + 2.65 * c, Theme.Align.LEFT);
		bar(g, x + padX, y + 3.2 * c, w - 2 * padX, c, s.comboLeft() / (double) SandRules.COMBO_MS, Theme.GOLD);
	}

	/** 단계: 큰 숫자, 지금 중력(초당 칸), 다음 단계까지의 막대. */
	static void level(Graphics2D g, SandPanel.Geo geo, SandSnapshot s, int startLevel, int y) {
		int c = geo.c();
		int x = geo.leftX();
		int w = geo.sideW();
		int h = (int) Math.round(4.2 * c);
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		title(g, "단계", x, y, c);
		int padX = (int) Math.round(0.4 * c);
		g.setColor(Theme.TEXT);
		displayNumber(g, String.valueOf(s.level()), 1.6 * c, x + padX, y + 2.65 * c, Theme.Align.LEFT);
		double speed = SandRules.gravity(s.level()) / 1000.0;
		g.setColor(Theme.LABEL);
		Theme.Type.LABEL.draw(g,
				String.format(Locale.ROOT, speed == Math.rint(speed) ? "초당 %.0f칸" : "초당 %.1f칸", speed),
				Math.max(11, 0.46 * c), x + w - padX, y + 2.45 * c, Theme.Align.RIGHT);
		double done;
		if (s.level() >= SandRules.MAX_LEVEL) {
			done = 1;
		} else {
			long levelStart = (long) (s.level() - startLevel) * SandRules.LEVEL_MS;
			done = Motion.clamp01((s.time() - levelStart) / (double) SandRules.LEVEL_MS);
		}
		bar(g, x + padX, y + 3.2 * c, w - 2 * padX, c, done, Theme.SAND);
	}

	/** 아래의 얇은 막대: 바탕과 채운 비율 {@code part}(0~1). */
	private static void bar(Graphics2D g, double x, double y, double w, int c, double part, Color fill) {
		double h = Math.max(4, 0.26 * c);
		Theme.card(g, x, y, w, h, h, Theme.BORDER, null);
		if (part > 0) {
			Theme.card(g, x, y, Math.max(h, w * Math.min(1, part)), h, h, fill, null);
		}
	}

	/**
	 * 목표 카드: 처음 하는 사람도 무엇을 해야 하는지 알게, 같은 색 띠가 양쪽 벽을 잇는 작은 그림과 짧은 설명({@link #GOAL_LINES}:
	 * 지우기, 콤보 배율, 끝나는 조건). 단계 카드 아래부터 판 아래 끝까지 채운다. 작은 창에서 카드 안에 다 들어가지 않는 문장은
	 * (그 뒤의 문장과 함께) 뺀다. 판에 얇은 띠(양쪽 벽을 이었지만 아직 얇아 남은 덩어리)가 있으면 지우기 문장의
	 * {@link #GOAL_MARK} 가 {@code thin}(0~1, 판의 얇은 띠 표시가 들어온 정도, {@link SandFx#thinIn})만큼 모래색이 되어 왜 안
	 * 지워졌는지 알려 준다.
	 */
	static void goal(Graphics2D g, SandPanel.Geo geo, SandColors colors, int y, double thin) {
		int c = geo.c();
		int x = geo.leftX();
		int w = geo.sideW();
		int h = geo.boardY() + geo.boardH() - y;
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.withAlpha(Theme.SURFACE, 170), Theme.BORDER);
		title(g, "목표", x, y, c);
		int padX = (int) Math.round(0.4 * c);
		double iw = w - 2 * padX;
		double ih = 2.4 * c;
		double ix = x + padX;
		double iy = y + 1.15 * c;
		spanPicture(g, colors.look, ix, iy, iw, ih);
		g.setFont(Theme.Type.BODY.px(Math.max(12, 0.46 * c)));
		double step = Math.max(0.64 * c, g.getFontMetrics().getHeight() * 0.95);
		g.setColor(Theme.TEXT_DIM);
		double ty = wrap(g, GOAL_LINES[0], ix, iy + ih + 0.9 * c, iw, step, thin > 0 ? GOAL_MARK : null,
				Theme.mix(Theme.TEXT_DIM, Theme.SAND, Motion.clamp01(thin)));
		double bottom = y + h - 0.2 * c; // 마지막 줄의 기준선이 이보다 위여야 한다
		g.setColor(Theme.LABEL);
		for (int i = 1; i < GOAL_LINES.length; i++) {
			double top = ty + 0.25 * c;
			if (top + (lines(g.getFontMetrics(), GOAL_LINES[i], iw).size() - 1) * step >= bottom) {
				break; // 작은 창에서 넘치면 이 문장과 그 뒤는 뺀다
			}
			ty = wrap(g, GOAL_LINES[i], ix, top, iw, step);
		}
	}

	/**
	 * "같은 색 모래로 양쪽 벽을 두툼하게 이으면 사라져요" 그림: 작은 판에 섞인 모래가 쌓였고, 그 위의 두툼한 파란 띠(그림 행 3~5개)가
	 * 왼쪽 벽과 오른쪽 벽을 잇는다(띠의 윗면은 막 사라지려는 듯 밝게). 알갱이는 6 화소 안팎(가로 32 개 이상). 메뉴의 모래 모드
	 * 설명과 모래 화면의 목표 카드가 함께 쓴다({@link SandPanel#paintSpanPicture}).
	 */
	static void spanPicture(Graphics2D g, ColorPalettes.Look look, double x, double y, double w, double h) {
		int cols = Math.max(32, (int) Math.round(w / 6.0));
		double s = w / cols;
		int rows = Math.max(5, (int) Math.floor(h / s));
		double bottom = y + h;
		g.setColor(Theme.BOARD);
		g.fill(new RoundRectangle2D.Double(x, y, w, h, s * 2, s * 2));
		int[] ground = { 1, 4, 3 }; // 띠 아래 모래: 빨강, 노랑, 초록
		Color band = look.sand(SandColor.BLUE.code());
		Color edge = Theme.mix(band, Color.WHITE, 0.45); // 띠의 윗면: 막 사라지려는 듯 밝게
		for (int i = 0; i < cols; i++) {
			int base = 1 + (int) Math.round(0.8 + 0.8 * Math.sin(i / 3.0));
			int top = Math.min(rows, base + 4 + (int) Math.round(0.9 * Math.sin(i / 5.0 + 1)));
			for (int r = 0; r < top; r++) {
				Color c = r < base ? look.sand(ground[(i / 4 + r) % ground.length]) : r == top - 1 ? edge : band;
				int noise = (i * 7 + r * 13) % 5;
				g.setColor(noise == 0 ? Theme.mix(c, Color.WHITE, 0.1) : noise == 1 ? Theme.mix(c, Color.BLACK, 0.12) : c);
				g.fill(new Rectangle2D.Double(x + i * s, bottom - (r + 1) * s, s + 0.4, s + 0.4));
			}
		}
		g.setColor(Theme.TEXT_DIM); // 양쪽 벽
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke((float) Math.max(1.5, s * 0.7), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Line2D.Double(x, y + s, x, bottom));
		g.draw(new Line2D.Double(x + w, y + s, x + w, bottom));
		g.setStroke(old);
	}

	/** 너비가 넘치면 빈칸에서 줄을 바꿔({@link #lines}) 왼쪽 맞춰 그린다. 다음 줄의 기준선을 돌려준다. */
	static double wrap(Graphics2D g, String text, double x, double y, double maxW, double step) {
		return wrap(g, text, x, y, maxW, step, null, null);
	}

	/**
	 * {@link #wrap(Graphics2D, String, double, double, double, double)} 과 같고, 낱말 {@code mark}(없으면 null)만
	 * {@code markColor} 로 그린다.
	 */
	static double wrap(Graphics2D g, String text, double x, double y, double maxW, double step, String mark,
			Color markColor) {
		FontMetrics fm = g.getFontMetrics();
		double baseline = y;
		for (String line : lines(fm, text, maxW)) {
			int at = mark == null ? -1 : line.indexOf(mark);
			if (at < 0) {
				g.drawString(line, (float) x, (float) baseline);
			} else { // 앞 · 낱말 · 뒤를 나눠 그린다
				Color base = g.getColor();
				String head = line.substring(0, at);
				float mx = (float) x + fm.stringWidth(head);
				g.drawString(head, (float) x, (float) baseline);
				g.setColor(markColor);
				g.drawString(mark, mx, (float) baseline);
				g.setColor(base);
				g.drawString(line.substring(at + mark.length()), mx + fm.stringWidth(mark), (float) baseline);
			}
			baseline += step;
		}
		return baseline;
	}

	/** 너비 {@code maxW} 가 넘치면 빈칸에서 줄을 바꾼 줄들 (한 낱말이 더 길면 그 줄만 넘친다). */
	static List<String> lines(FontMetrics fm, String text, double maxW) {
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.length() == 0 ? word : line + " " + word;
			if (line.length() > 0 && fm.stringWidth(next) > maxW) {
				out.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		out.add(line.toString());
		return out;
	}

	// ------------------------------------------------------------------ 오른쪽: 미리보기, 통계

	static void next(Graphics2D g, SandPanel.Geo geo, List<SandPiece> preview, SandColors colors) {
		int c = geo.c();
		int x = geo.rightX();
		int y = geo.boardY();
		int w = geo.sideW();
		Theme.card(g, x, y, w, 13.0 * c, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		title(g, "NEXT", x, y, c);
		for (int k = 0; k < preview.size(); k++) {
			if (k == 0) {
				mini(g, colors, preview.get(0), x, y + 0.95 * c, w, 2.7 * c, cellSize(geo, 0.75), false);
			} else {
				mini(g, colors, preview.get(k), x, y + 3.75 * c + (k - 1) * 2.25 * c, w, 2.1 * c, cellSize(geo, 0.55),
						false);
			}
		}
	}

	/** 통계: 시간(1/1000초까지), 지운 횟수, 지운 알갱이, 블록, 최대 배율. 값은 고정 폭 숫자라 바뀌어도 흔들리지 않는다. */
	static void stats(Graphics2D g, SandPanel.Geo geo, SandSnapshot s) {
		int c = geo.c();
		int x = geo.rightX();
		int w = geo.sideW();
		int y = geo.boardY() + (int) Math.round(13.5 * c);
		int h = geo.boardY() + geo.boardH() - y;
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		int padX = (int) Math.round(0.4 * c);
		// {이름, 좁을 때의 짧은 이름, 값}
		String[][] rows = { { "시간", "시간", SandPanel.clockMs(s.time()) }, { "지운 횟수", "지움", SandPanel.number(s.clears()) },
				{ "지운 알갱이", "알갱이", SandPanel.number(s.grainsCleared()) }, { "블록", "블록", SandPanel.number(s.pieces()) },
				{ "최대 배율", "배율", "×" + s.maxMultiplier() } };
		double step = (h - 0.7 * c) / rows.length;
		double labelPx = Math.max(11, 0.46 * c);
		double valuePx = 0.82 * c;
		for (int i = 0; i < rows.length; i++) {
			double baseline = y + 0.35 * c + (i + 0.72) * step;
			float vw = Theme.Type.NUMERIC.width(g, rows[i][2], valuePx);
			g.setColor(Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, rows[i][2], valuePx, x + w - padX, baseline, Theme.Align.RIGHT);
			String label = Theme.Type.LABEL.width(g, rows[i][0], labelPx) + vw + 0.3 * c > w - 2 * padX ? rows[i][1]
					: rows[i][0];
			g.setColor(Theme.LABEL);
			Theme.Type.LABEL.draw(g, label, labelPx, x + padX, baseline, Theme.Align.LEFT);
		}
	}

	/** 카드 제목 (HOLD, NEXT, 콤보 배율 …): 작은 라벨, 라틴은 자간을 넓힌 숫자 글꼴. */
	private static void title(Graphics2D g, String title, int x, int top, int c) {
		double px = Math.max(11, 0.5 * c);
		g.setColor(Theme.LABEL);
		if (Theme.numeric(px).canDisplayUpTo(title) == -1) {
			g.setFont(Theme.tracked(Theme.numeric(px), 0.12));
			Theme.text(g, title, x + 0.4 * c, top + 0.78 * c, Theme.Align.LEFT);
		} else {
			Theme.Type.LABEL.draw(g, title, px, x + 0.4 * c, top + 0.78 * c, Theme.Align.LEFT);
		}
	}

	/** 미리보기·홀드 블록의 칸 크기: 알갱이가 정수 화소가 되게 8 의 배수. */
	private static int cellSize(SandPanel.Geo geo, double ratio) {
		return G * Math.max(1, (int) Math.round(geo.g() * ratio));
	}

	/** 상자 가운데에 모래 블록 하나를 그린다(홀드, 미리보기). 칸마다 알갱이 무늬 한 장. {@code grey} 면 흐리게(쓴 홀드). */
	static void mini(Graphics2D g, SandColors colors, SandPiece piece, double boxX, double boxY, double boxW,
			double boxH, int s, boolean grey) {
		int[][] cells = PieceState.spawn(piece.type()).cells();
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
		int ox = (int) Math.round(boxX + (boxW - (maxC - minC + 1) * s) / 2.0);
		int oy = (int) Math.round(boxY + (boxH - (maxR - minR + 1) * s) / 2.0);
		Composite old = g.getComposite();
		if (grey) {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f));
		}
		Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		for (int[] cell : cells) {
			g.drawImage(colors.tile(piece.color()), ox + (cell[1] - minC) * s, oy + (cell[0] - minR) * s, s, s, null);
		}
		if (interpolation != null) {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		}
		g.setComposite(old);
	}
}
