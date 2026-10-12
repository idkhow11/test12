package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

import javax.swing.JPanel;

import kr.ac.jbnu.se.tetris.core.Attack;
import kr.ac.jbnu.se.tetris.core.GarbageQueue;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.KeyNames;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 도움말 화면 한 장. 메뉴와 같은 모양(1280 × 720 가상 화면, 둥근 카드, 키 모양)을 직접 그린다.
 * <ul>
 * <li><b>조작:</b> 지금 묶인 키 ({@code settings} 가 주는 설정의 키, 바꾸면 다음에 열 때 바로 보인다). 아래에 AI 속도·페르소나는
 * 메뉴에서 고르고 "기준 AI" 가 무엇인지 한 줄({@link #CONTROLS_NOTE}).</li>
 * <li><b>공격:</b> 지운 줄 수와 T-spin 에 따른 공격, B2B·퍼펙트 클리어·콤보 보너스. 숫자는 규칙 엔진({@link Attack})에서
 * 읽으므로 규칙(docs/RULES.md 9장)이 바뀌어도 따라간다.</li>
 * <li><b>방해 줄과 상쇄</b>, <b>예측 승률</b>(무엇을 뜻하는지), <b>모래 모드</b> 규칙과 끝나는 조건({@link #SAND_LINES}, 메뉴·판
 * 옆 목표 카드와 같은 말).</li>
 * <li>제목 아래 한 줄({@link #SUBTITLE}): 사람 대 AI 는 Esc 를 두 번 누르면 기권(패배)이고, 같은 판 다시는 같은 블록 순서다.</li>
 * <li>맨 아래 출처 한 줄({@link #CREDITS}, runs/quality/SPEC.md V15): 함께 배포하는 효과음(Kenney, CC0)과 글꼴(Pretendard,
 * Barlow Condensed, OFL). 자세한 것은 저장소의 CREDITS.md.</li>
 * </ul>
 * 그림의 블록과 모래는 설정의 색약 색상·블록 무늬대로 그린다({@link ColorPalettes#look(UserSettings)}): 같은 색이 이어지면
 * 사라지는 모래 규칙은 색을 구분하기 어려운 사람에게 특히 그 색으로 보여야 한다.
 * <p>
 * 키보드는 Esc 하나로 메뉴로 돌아간다(누르고 있어도 한 번). Enter·Space 는 쓰지 않는다: 메뉴에서 Enter 로 이 화면을 열고
 * 조금 오래 누르면 운영체제의 자동 반복이 이 화면에 와서 곧바로 닫히고, 메뉴에서는 판이 시작되기 때문이다. 마우스는 왼쪽 위
 * "메뉴로" 버튼. 나가는 소리는 창(TetrisApp)이 낸다.
 */
final class HelpPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final int W = 1280;
	private static final int H = 720;
	private static final Rectangle BACK = new Rectangle(40, 40, 150, 44);

	// ---- 카드 자리: 아래 줄 세 카드의 칸에 위 줄 두 카드의 가장자리를 맞춘다
	private static final int LEFT = 56;
	private static final int CONTENT_W = 1168;
	private static final int GAP = 16;
	/** 아래 줄 카드 하나의 너비. 위 줄의 조작 카드도 같은 너비다. */
	private static final int CELL_W = (CONTENT_W - 2 * GAP) / 3;
	private static final int TOP_Y = 148;
	private static final int TOP_H = 352;
	private static final int BOTTOM_Y = 516;
	private static final int BOTTOM_H = 132;

	/** 조작 카드에 보이는 동작 (블록 조작 다음에 화면 조작). */
	private static final KeyAction[] PIECE_ACTIONS = { KeyAction.MOVE_LEFT, KeyAction.MOVE_RIGHT, KeyAction.SOFT_DROP,
			KeyAction.HARD_DROP, KeyAction.ROTATE_CW, KeyAction.ROTATE_CCW, KeyAction.HOLD };
	private static final KeyAction[] SCREEN_ACTIONS = { KeyAction.PAUSE, KeyAction.MENU, KeyAction.RETRY,
			KeyAction.FULLSCREEN };

	/** 제목 아래 한 줄: 조작 표의 "메뉴·기권" 이 한 번에 기권하는지 알 수 없어서, 기권 규칙과 "같은 판 다시" 를 짧게. */
	static final String SUBTITLE = "사람 대 AI는 Esc를 두 번 누르면 기권(패배)이에요 · 같은 판 다시는 같은 블록 순서로 한 판 더";

	/** 조작 카드 맨 아래 한 줄: 도움말에 없던 AI 속도·페르소나와 메뉴 상태 "기준 AI로 대신해요" 의 기준 AI. */
	static final String CONTROLS_NOTE = "AI 속도·페르소나는 메뉴에서 · 기준 AI = 학습 없이 규칙으로 두는 AI";

	/** 모래 모드 카드의 줄: 부서짐, 지우기(sand-0.3, 판 옆 목표 카드와 같은 말), 끝나는 조건(판의 점선). */
	static final String[] SAND_LINES = { "블록이 모래로 부서져요", "같은 색 모래로 양쪽 벽을", "두툼하게 이으면 사라져요",
			"점선 위까지 쌓이면 끝나요" };

	/** 맨 아래 출처 한 줄 (SPEC V15, 자세한 것은 저장소의 CREDITS.md). */
	static final String CREDITS = "효과음 일부: Kenney (CC0) · 글꼴: Pretendard, Barlow Condensed (OFL) · 자세한 출처: CREDITS.md";

	/**
	 * 공격표의 한 줄.
	 *
	 * @param name   "보통", "T-spin", "T-spin mini"
	 * @param attack 1~4줄을 지웠을 때의 기본 공격 (그 종류로 지울 수 없는 줄 수는 -1)
	 */
	record AttackRow(String name, int[] attack) {
	}

	/**
	 * 콤보 보너스 한 묶음: 화면의 "N COMBO" 가 {@code from}~{@code to} 일 때 {@code bonus}. {@code to} 가 -1 이면 그 위로
	 * 모두.
	 */
	record ComboGroup(int from, int to, int bonus) {

		/** 화면 글자: "1·2", "7~9", "10~". */
		String range() {
			if (to < 0) {
				return from + "~";
			}
			if (to == from) {
				return String.valueOf(from);
			}
			return to == from + 1 ? from + "·" + to : from + "~" + to;
		}
	}

	private final transient Supplier<UserSettings> settings;
	private final transient Runnable onBack;
	private final transient Set<Integer> down = new HashSet<>();
	private boolean hoverBack;

	/**
	 * @param settings 지금 설정 (보통 {@code data::settings}): 키 묶음과 색약 색상·블록 무늬
	 * @param onBack   메뉴로 돌아가기
	 */
	HelpPanel(Supplier<UserSettings> settings, Runnable onBack) {
		this.settings = Objects.requireNonNull(settings, "settings");
		this.onBack = Objects.requireNonNull(onBack, "onBack");
		setPreferredSize(new Dimension(W, H));
		setFocusable(true);
		enableInputMethods(false);
		setFocusTraversalKeysEnabled(false);
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				int code = e.getKeyCode();
				boolean repeat = !down.add(code);
				if (!repeat && code == KeyEvent.VK_ESCAPE) {
					e.consume();
					onBack.run();
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
				down.remove(e.getKeyCode());
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				boolean h = overBack(e.getX(), e.getY());
				if (h != hoverBack) {
					hoverBack = h;
					setCursor(Cursor.getPredefinedCursor(h ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
					repaint();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				if (overBack(e.getX(), e.getY())) {
					onBack.run();
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				down.clear();
			}
		});
	}

	/** 화면을 보일 때마다 부른다 (눌린 키 기록과 마우스 강조를 지운다). */
	void open() {
		down.clear();
		hoverBack = false;
		repaint();
	}

	private boolean overBack(int x, int y) {
		try {
			Point2D v = transform().inverseTransform(new Point2D.Double(x, y), null);
			return BACK.contains(v);
		} catch (NoninvertibleTransformException e) {
			return false;
		}
	}

	private AffineTransform transform() {
		double scale = Math.min(getWidth() / (double) W, getHeight() / (double) H);
		if (!(scale > 0)) {
			scale = 1;
		}
		AffineTransform t = new AffineTransform();
		t.translate((getWidth() - W * scale) / 2, (getHeight() - H * scale) / 2);
		t.scale(scale, scale);
		return t;
	}

	// ------------------------------------------------------------------ 내용 (규칙 엔진에서 읽는다)

	/** 공격표: 보통 1~4줄, T-spin 1~3줄, T-spin mini 1~2줄의 기본 공격 ({@link Attack#breakdown} 의 기본 몫). */
	static List<AttackRow> attackRows() {
		return List.of(row("보통", Spin.NONE, 4), row("T-spin", Spin.FULL, 3), row("T-spin mini", Spin.MINI, 2));
	}

	private static AttackRow row(String name, Spin spin, int maxLines) {
		int[] a = { -1, -1, -1, -1 };
		for (int lines = 1; lines <= maxLines; lines++) {
			a[lines - 1] = Attack.breakdown(-1, false, lines, spin, false)[0];
		}
		return new AttackRow(name, a);
	}

	/** B2B 보너스 (테트리스·T-spin 을 이어 갈 때). */
	static int b2bBonus() {
		return Attack.breakdown(-1, true, 4, Spin.NONE, false)[1];
	}

	/** 퍼펙트 클리어 보너스. */
	static int perfectClearBonus() {
		return Attack.breakdown(-1, false, 1, Spin.NONE, true)[3];
	}

	/** 콤보 보너스를 같은 값끼리 묶은 것: 1·2 +1, 3·4 +2, … 마지막 묶음은 그 위로 모두. */
	static List<ComboGroup> comboGroups() {
		int maxShown = 40; // 이만큼 보면 보너스가 더 오르지 않는다(표의 끝)
		int[] bonus = new int[maxShown + 1];
		for (int k = 1; k <= maxShown; k++) { // 화면의 "k COMBO" = 놓기 전 콤보 k - 1
			bonus[k] = Attack.breakdown(k - 1, false, 1, Spin.NONE, false)[2];
		}
		List<ComboGroup> out = new ArrayList<>();
		int start = 1;
		for (int k = 2; k <= maxShown + 1; k++) {
			if (k > maxShown || bonus[k] != bonus[start]) {
				out.add(new ComboGroup(start, k > maxShown ? -1 : k - 1, bonus[start]));
				start = k;
			}
		}
		return out;
	}

	/** 조작 카드의 줄 {동작 이름, 키 글자 ("↑ / X")}. 블록 조작 일곱 가지 다음에 화면 조작 넷. */
	List<String[]> controlRows() {
		KeyBindings k = settings.get().keys();
		List<String[]> out = new ArrayList<>();
		for (KeyAction a : PIECE_ACTIONS) {
			out.add(new String[] { a.label(), k.describe(a) });
		}
		for (KeyAction a : SCREEN_ACTIONS) {
			out.add(new String[] { a.label(), k.describe(a) });
		}
		return out;
	}

	/** 그림을 그리는 방법: 설정의 색약 색상·블록 무늬. */
	ColorPalettes.Look look() {
		return ColorPalettes.look(settings.get());
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.antialias(g);
		Theme.backgroundCached(g, getWidth(), getHeight());
		g.transform(transform());
		UserSettings s = settings.get();
		ColorPalettes.Look look = ColorPalettes.look(s);
		SettingsPanel.paintHeader(g, "도움말", SUBTITLE);
		SettingsPanel.paintBack(g, BACK, false, hoverBack);
		paintControls(g, s.keys(), LEFT, TOP_Y, CELL_W, TOP_H);
		int attackX = LEFT + CELL_W + GAP;
		paintAttack(g, look, attackX, TOP_Y, LEFT + CONTENT_W - attackX, TOP_H);
		paintGarbage(g, look, LEFT, BOTTOM_Y, CELL_W, BOTTOM_H);
		paintWinRate(g, LEFT + CELL_W + GAP, BOTTOM_Y, CELL_W, BOTTOM_H);
		int sandX = LEFT + 2 * (CELL_W + GAP);
		paintSand(g, look, sandX, BOTTOM_Y, LEFT + CONTENT_W - sandX, BOTTOM_H);
		SettingsPanel.paintKeyHints(g, new String[][] { { "Esc", "메뉴로" } }, 680);
		// 출처 한 줄: 함께 배포하는 효과음(CC0)과 글꼴(OFL)
		g.setColor(Theme.LABEL);
		Theme.Type.BODY.draw(g, CREDITS, 12, W / 2.0, 708, Theme.Align.CENTER);
		g.dispose();
	}

	/** 카드와 제목 (오른쪽에 작은 설명). */
	private static void card(Graphics2D g, int x, int y, int w, int h, String title, String note) {
		Theme.card(g, x, y, w, h, 24, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER);
		g.setFont(Theme.font(19, true));
		g.setColor(Theme.TEXT);
		g.drawString(title, x + 24, y + 36);
		if (note != null) {
			g.setFont(Theme.font(12, false));
			g.setColor(Theme.LABEL);
			Theme.right(g, note, x + w - 24, y + 35);
		}
	}

	/**
	 * 조작 카드: 동작 이름 바로 옆의 고정 열에 키 모양을 왼쪽부터 놓는다(줄을 따라 읽기 쉽게). 키 이름이 길어 넘치면 글자를
	 * 줄여 카드 안에 맞춘다. 맨 아래에 {@link #CONTROLS_NOTE} 한 줄.
	 */
	private static void paintControls(Graphics2D g, KeyBindings k, int x, int y, int w, int h) {
		card(g, x, y, w, h, "조작", "설정에서 바꿀 수 있어요");
		g.setFont(Theme.font(14, false));
		FontMetrics labels = g.getFontMetrics();
		int labelW = 0;
		for (KeyAction a : KeyAction.values()) {
			labelW = Math.max(labelW, labels.stringWidth(a.label()));
		}
		int keyX = x + 24 + labelW + 22;
		int keyRight = x + w - 24;
		int ry = y + 68;
		int step = 24;
		for (int i = 0; i < PIECE_ACTIONS.length + SCREEN_ACTIONS.length; i++) {
			if (i == PIECE_ACTIONS.length) { // 블록 조작과 화면 조작 사이
				g.setColor(Theme.withAlpha(Theme.BORDER, 255));
				g.fill(new Rectangle2D.Double(x + 24, ry - 15, w - 48, 1));
				ry += 7;
			}
			KeyAction a = i < PIECE_ACTIONS.length ? PIECE_ACTIONS[i] : SCREEN_ACTIONS[i - PIECE_ACTIONS.length];
			g.setFont(Theme.font(14, false));
			g.setColor(Theme.TEXT_DIM);
			g.drawString(a.label(), x + 24, ry);
			paintKeyCaps(g, k.keys(a), keyX, ry, keyRight - keyX);
			ry += step;
		}
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.LABEL);
		g.drawString(CONTROLS_NOTE, x + 24, y + 340);
	}

	/** 키 모양을 {@code x} 부터 왼쪽 맞춤으로 늘어놓는다. {@code maxW} 를 넘으면 글자를 줄인다(9pt 까지). */
	private static void paintKeyCaps(Graphics2D g, List<Integer> codes, int x, int baseline, int maxW) {
		int size = 12;
		FontMetrics fm;
		while (true) {
			g.setFont(Theme.font(size, true));
			fm = g.getFontMetrics();
			int total = -6;
			for (int code : codes) {
				total += capWidth(fm, KeyNames.label(code)) + 6;
			}
			if (total <= maxW || size <= 9) {
				break;
			}
			size--;
		}
		int kx = x;
		for (int code : codes) {
			String t = KeyNames.label(code);
			int kw = capWidth(fm, t);
			Theme.card(g, kx, baseline - 16, kw, 22, 8, Theme.SURFACE_HI, Theme.BORDER);
			g.setColor(Theme.TEXT);
			Theme.centered(g, t, kx + kw / 2.0, baseline);
			kx += kw + 6;
		}
	}

	private static int capWidth(FontMetrics fm, String text) {
		return Math.max(26, fm.stringWidth(text) + 16);
	}

	private static void paintAttack(Graphics2D g, ColorPalettes.Look look, int x, int y, int w, int h) {
		card(g, x, y, w, h, "공격", "지운 줄만큼 상대에게 보내요");
		List<AttackRow> rows = attackRows();
		int labelX = x + 24;
		int colW = 125;
		int col0 = x + 260;
		int hy = y + 70;
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		String[] heads = { "1줄", "2줄", "3줄", "4줄" };
		for (int i = 0; i < 4; i++) {
			Theme.centered(g, heads[i], col0 + i * colW, hy);
		}
		g.setFont(Theme.font(11, false));
		Theme.centered(g, "테트리스", col0 + 3 * colW, hy + 14);
		Color tColor = Theme.mix(look.piece(PieceType.T.code()), Color.WHITE, 0.25);
		Color[] rowColor = { Theme.TEXT, tColor, Theme.mix(tColor, Theme.TEXT_DIM, 0.45) };
		int ry = hy + 30;
		for (int r = 0; r < rows.size(); r++) {
			AttackRow row = rows.get(r);
			if (r % 2 == 0) {
				g.setColor(new Color(255, 255, 255, 8));
				g.fill(new RoundRectangle2D.Double(x + 16, ry, w - 32, 38, 12, 12));
			}
			g.setFont(Theme.font(15, true));
			g.setColor(r == 0 ? Theme.TEXT : rowColor[r]);
			g.drawString(row.name(), labelX, ry + 25);
			for (int i = 0; i < 4; i++) {
				int v = row.attack()[i];
				int cx = col0 + i * colW;
				if (v < 0) {
					g.setFont(Theme.font(15, false));
					g.setColor(Theme.withAlpha(Theme.TEXT_FAINT, 140));
					Theme.centered(g, "–", cx, ry + 25);
					continue;
				}
				g.setFont(Theme.font(20, true));
				g.setColor(rowColor[r]);
				Theme.centered(g, String.valueOf(v), cx, ry + 27);
			}
			ry += 40;
		}

		// 보너스
		int by = ry + 16;
		g.setColor(Theme.withAlpha(Theme.BORDER, 255));
		g.fill(new Rectangle2D.Double(x + 24, by - 8, w - 48, 1));
		int bx = labelX;
		bx += bonusChip(g, "B2B +" + b2bBonus(), bx, by + 22, Theme.GOLD);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("테트리스·T-spin을 이어 가면", bx + 10, by + 22);
		int px = x + w / 2 + 40;
		px += bonusChip(g, "퍼펙트 클리어 +" + perfectClearBonus(), px, by + 22, Theme.OK);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("보드를 다 비우면", px + 10, by + 22);

		int cy = by + 60;
		int cx = labelX;
		cx += bonusChip(g, "콤보", cx, cy, Theme.WARN);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("연달아 지우면", cx + 10, cy);
		cx += 10 + g.getFontMetrics().stringWidth("연달아 지우면") + 16;
		for (ComboGroup c : comboGroups()) {
			g.setFont(Theme.font(13, true));
			FontMetrics fm = g.getFontMetrics();
			String a = c.range();
			String b = "+" + c.bonus();
			int cw = fm.stringWidth(a) + fm.stringWidth(b) + 30;
			Theme.card(g, cx, cy - 17, cw, 24, 24, Theme.withAlpha(Theme.WARN, 26), Theme.withAlpha(Theme.WARN, 90));
			g.setColor(Theme.TEXT);
			g.drawString(a, cx + 11, cy);
			g.setColor(Theme.WARN);
			g.drawString(b, cx + 11 + fm.stringWidth(a) + 8, cy);
			cx += cw + 6;
		}
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.LABEL);
		g.drawString("두 번 연달아 지우면 1 COMBO예요", labelX, cy + 30);
	}

	/** 보너스 꼬리표 (색 바탕 위 어두운 글자). 너비를 돌려준다. */
	private static int bonusChip(Graphics2D g, String text, int x, int baseline, Color c) {
		return Theme.chip(g, text, x, baseline - 1, 13, Theme.BG_TOP, Theme.withAlpha(c, 230));
	}

	/** 작은 카드: 왼쪽에 그림, 오른쪽에 제목과 짧은 줄. */
	private static void infoCard(Graphics2D g, int x, int y, int w, int h, String title, String[] lines) {
		Theme.card(g, x, y, w, h, 24, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER);
		int tx = x + 108;
		g.setFont(Theme.font(17, true));
		g.setColor(Theme.TEXT);
		g.drawString(title, tx, y + 32);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		int ly = y + 57;
		for (String l : lines) {
			g.drawString(l, tx, ly);
			ly += 20;
		}
	}

	private static void paintGarbage(Graphics2D g, ColorPalettes.Look look, int x, int y, int w, int h) {
		infoCard(g, x, y, w, h, "방해 줄과 상쇄", new String[] { "받은 공격은 왼쪽 막대에 쌓여요", "줄을 지우면 막대부터 줄여요(상쇄)",
				"남은 만큼만 상대에게 가요", "못 지우고 놓으면 " + GarbageQueue.MAX_RISE + "줄까지 올라와요" });
		// 그림: 보드와 받을 가비지 막대 (노랑 3칸)
		int bx = x + 40;
		int by = y + 26;
		int c = 10;
		Theme.card(g, bx, by, 6, 8 * c, 6, Theme.BOARD, Theme.BORDER);
		g.setColor(Theme.withAlpha(Theme.WARN, 235));
		for (int k = 0; k < 3; k++) {
			g.fill(new RoundRectangle2D.Double(bx + 1, by + 8 * c - (k + 1) * c + 1, 4, c - 2, 3, 3));
		}
		Theme.card(g, bx + 10, by, 4 * c + 4, 8 * c, 6, Theme.BOARD, Theme.BORDER);
		for (int r = 0; r < 2; r++) {
			for (int col = 0; col < 4; col++) {
				if (col != (r == 0 ? 1 : 2)) {
					look.block(g, bx + 12 + col * c, by + 8 * c - (r + 1) * c - 2, c, ColorPalettes.GARBAGE, 255, false);
				}
			}
		}
		look.block(g, bx + 12 + c, by + 8 * c - 3 * c - 2, c, PieceType.L.code(), 255, false);
		look.block(g, bx + 12 + 2 * c, by + 8 * c - 3 * c - 2, c, PieceType.L.code(), 255, false);
	}

	/** 예측 승률: 게임 위 띠의 막대처럼 왼쪽 끝에 "나 63%", 오른쪽 끝에 "37% AI". */
	private static void paintWinRate(Graphics2D g, int x, int y, int w, int h) {
		infoCard(g, x, y, w, h, "예측 승률", new String[] { "딥러닝 모델이 두 보드를 보고 잰", "지금 이길 확률이에요",
				"블록이 놓일 때마다 다시 재요", "예측이라 빗나갈 수도 있어요" });
		int bx = x + 20;
		int by = y + 60;
		int bw = 76;
		int bh = 10;
		double p = 0.63;
		g.setColor(Theme.P2);
		g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, bh, bh));
		java.awt.Shape old = g.getClip();
		g.clip(new Rectangle2D.Double(bx, by, bw * p, bh));
		g.setColor(Theme.P1);
		g.fill(new RoundRectangle2D.Double(bx, by, bw, bh, bh, bh));
		g.setClip(old);
		g.setColor(new Color(255, 255, 255, 210)); // 50% 눈금
		g.fill(new Rectangle2D.Double(bx + bw / 2.0 - 1, by - 3, 2, bh + 6));
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.P1);
		g.drawString("나 " + Math.round(100 * p) + "%", bx, by - 9);
		g.setColor(Theme.P2);
		Theme.right(g, Math.round(100 * (1 - p)) + "% AI", bx + bw, by + bh + 21);
	}

	private static void paintSand(Graphics2D g, ColorPalettes.Look look, int x, int y, int w, int h) {
		infoCard(g, x, y, w, h, "모래 모드", SAND_LINES);
		// 그림: 쌓인 모래 가운데 왼쪽 벽에서 오른쪽 벽까지 이어진 두툼한 노란 띠 (그림 행 4개, 곧 사라진다)
		int bx = x + 22;
		int by = y + 28;
		int gs = 4;
		int cols = 18;
		int rows = 19;
		Theme.card(g, bx - 3, by - 3, cols * gs + 6, rows * gs + 6, 6, Theme.BOARD, Theme.BORDER);
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				int surface = 6 + (int) Math.round(1.6 * Math.sin(c * 0.45 + 0.6));
				if (r < surface) {
					continue;
				}
				int wave = (int) Math.round(1.2 * Math.sin(c * 0.6));
				SandColor col;
				if (r >= 10 + wave && r <= 13 + wave) {
					col = SandColor.YELLOW;
				} else if (r < 10 + wave) {
					col = c < 9 ? SandColor.RED : SandColor.BLUE;
				} else {
					col = c < 7 ? SandColor.GREEN : c < 13 ? SandColor.BLUE : SandColor.RED;
				}
				boolean sparkle = col == SandColor.YELLOW && ColorPalettes.texture(r + 40, c) == 0; // 사라지기 전 반짝임
				int cr = Math.floorMod(r, 8);
				int cc = Math.floorMod(c, 8);
				g.setColor(look.grain(col.code(), ColorPalettes.texture(r, c), sparkle, col.pattern(cr, cc)));
				g.fillRect(bx + c * gs, by + r * gs, gs, gs);
			}
		}
		// 넘침 선 (판의 점선과 같은 모양): 모래가 이 위까지 쌓이면 끝난다
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 3f }, 0f));
		g.setColor(Theme.withAlpha(Theme.LABEL, 190));
		g.draw(new Line2D.Double(bx, by + 2.5 * gs, bx + cols * gs, by + 2.5 * gs));
		g.setStroke(old);
	}
}
