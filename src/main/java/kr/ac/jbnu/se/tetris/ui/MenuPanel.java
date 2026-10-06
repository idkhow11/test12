package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JPanel;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.core.GameModel;

/**
 * 첫 화면 (docs/GAME.md 7장): 모드 카드 → 난이도·페르소나(또는 블록 속도·방해 줄) → 시작. 기본 Swing 부품 없이 직접
 * 그리고, 1280 × 720 가상 화면을 창 크기에 맞춰 늘려 그린다. 마우스(누르기)와 키보드(↑↓ 줄, ←→ 고르기, Enter 시작)로
 * 고른다.
 */
final class MenuPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final int W = 1280;
	private static final int H = 720;

	/** 혼자 연습의 블록 속도 = 중력 상한 (초당 칸 수, RULES 6.3 표). 메뉴에는 SOLO_CAP_NAMES 로 보인다. */
	private static final double[] SOLO_CAPS = { 1, 2, 3, 5, 8, 12 };
	private static final String[] SOLO_CAP_NAMES = { "아주 느리게", "느리게", "보통", "빠르게", "아주 빠르게", "최고 속도" };
	private static final GameModel.Scheduler[] SCHEDULERS = GameModel.Scheduler.values();

	private static final int ROW_MODE = 0;
	private static final int ROW_A = 1; // 사람 대 AI: 난이도, 혼자 연습: 블록 속도(중력 상한)
	private static final int ROW_B = 2; // 사람 대 AI: 페르소나, 혼자 연습: 방해 줄(가비지 스케줄러)
	private static final int ROW_START = 3;

	/** 누를 수 있는 곳 하나 (가상 화면 좌표). */
	private record Hit(Rectangle r, int row, int index) {
	}

	private final transient AiCatalog catalog;
	private final transient Consumer<GameSession.Settings> onStart;
	private final transient List<Hit> hits = new ArrayList<>();
	private transient AffineTransform toScreen = new AffineTransform();

	private boolean solo;
	private int difficulty;
	private int persona;
	private int soloCap = 2;
	private int scheduler;
	private int focus = ROW_START;
	private transient Hit hover;

	MenuPanel(AiCatalog catalog, Consumer<GameSession.Settings> onStart) {
		this.catalog = catalog;
		this.onStart = onStart;
		this.difficulty = Math.min(1, catalog.difficulties().size() - 1); // 처음 고른 단계는 2단계 '중급'
		setPreferredSize(new Dimension(W, H));
		setFocusable(true);
		enableInputMethods(false);
		setFocusTraversalKeysEnabled(false);
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				onKey(e.getKeyCode());
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Hit h = hitAt(e.getX(), e.getY());
				if (h != hover) {
					hover = h;
					setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
					repaint();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				Hit h = hitAt(e.getX(), e.getY());
				if (h != null) {
					select(h.row(), h.index());
					if (h.row() == ROW_START) {
						start();
					}
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
	}

	// ------------------------------------------------------------------ 고르기

	private void onKey(int code) {
		switch (code) {
		case KeyEvent.VK_UP:
			focus = Math.max(ROW_MODE, focus - 1);
			if (focus == ROW_B && !solo && !personasOpen()) {
				focus--; // 잠긴 페르소나 줄은 건너뛴다
			}
			break;
		case KeyEvent.VK_DOWN:
		case KeyEvent.VK_TAB:
			focus = Math.min(ROW_START, focus + 1);
			if (focus == ROW_B && !solo && !personasOpen()) {
				focus++;
			}
			break;
		case KeyEvent.VK_LEFT:
			step(-1);
			break;
		case KeyEvent.VK_RIGHT:
			step(1);
			break;
		case KeyEvent.VK_ENTER:
		case KeyEvent.VK_SPACE:
			start();
			return;
		default:
			return;
		}
		repaint();
	}

	/** 지금 줄에서 왼쪽(-1)이나 오른쪽(+1) 항목으로. */
	private void step(int d) {
		switch (focus) {
		case ROW_MODE:
			solo = d > 0;
			break;
		case ROW_A:
			if (solo) {
				soloCap = clamp(soloCap + d, SOLO_CAPS.length);
			} else {
				difficulty = clamp(difficulty + d, catalog.difficulties().size());
			}
			break;
		case ROW_B:
			if (solo) {
				scheduler = clamp(scheduler + d, SCHEDULERS.length);
			} else if (personasOpen()) {
				persona = clamp(persona + d, catalog.personas().size());
			}
			break;
		default:
			break;
		}
	}

	/** 지금 난이도에서 페르소나를 고를 수 있는가 (4·5단계만, GAME.md 6.2). */
	private boolean personasOpen() {
		return catalog.difficulties().get(difficulty).personas();
	}

	/** 페르소나를 고를 수 있는 단계 이름 ("최상급·불가능"). 모델 묶음의 {@code "personas": true} 를 따른다. */
	private String personaLevels() {
		return catalog.difficulties().stream().filter(AiCatalog.Difficulty::personas).map(AiCatalog.Difficulty::name)
				.collect(java.util.stream.Collectors.joining("·"));
	}

	/** 실제로 쓸 페르소나: 고를 수 없는 단계면 중립(첫 항목). */
	private int effectivePersona() {
		return personasOpen() ? persona : 0;
	}

	/** 줄 {@code row} 의 {@code index} 번째 항목을 고른다(마우스, 화면 확인 도구). */
	void select(int row, int index) {
		focus = row;
		switch (row) {
		case ROW_MODE:
			solo = index == 1;
			break;
		case ROW_A:
			if (solo) {
				soloCap = index;
			} else {
				difficulty = index;
			}
			break;
		case ROW_B:
			if (solo) {
				scheduler = index;
			} else if (personasOpen()) {
				persona = index;
			}
			break;
		default:
			break;
		}
		repaint();
	}

	private static int clamp(int v, int n) {
		return Math.max(0, Math.min(n - 1, v));
	}

	private void start() {
		AiCatalog.Difficulty d = catalog.difficulties().get(difficulty);
		AiCatalog.Persona p = catalog.personas().get(effectivePersona());
		int capG = (int) Math.round(SOLO_CAPS[soloCap] * 1000);
		onStart.accept(new GameSession.Settings(!solo, d, p, capG, SCHEDULERS[scheduler]));
	}

	private Hit hitAt(int x, int y) {
		try {
			Point2D v = toScreen.inverseTransform(new Point2D.Double(x, y), null);
			for (Hit h : hits) {
				if (h.r().contains(v)) {
					return h;
				}
			}
		} catch (java.awt.geom.NoninvertibleTransformException e) {
			// 창이 너무 작을 때
		}
		return null;
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.antialias(g);
		Theme.background(g, getWidth(), getHeight());
		double scale = Math.min(getWidth() / (double) W, getHeight() / (double) H);
		AffineTransform t = new AffineTransform();
		t.translate((getWidth() - W * scale) / 2, (getHeight() - H * scale) / 2);
		t.scale(scale, scale);
		toScreen = t;
		g.transform(t);
		hits.clear();
		paintDecor(g);
		paintTitle(g);
		Theme.card(g, 220, 176, 840, 466, 28, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER);
		paintModes(g, 248, 200);
		if (solo) {
			paintSoloOptions(g);
		} else {
			paintVersusOptions(g);
		}
		paintStart(g, W / 2 - 150, 570);
		paintKeys(g, 676);
		paintStatus(g);
		g.dispose();
	}

	/** 양옆에 옅게 깔린 큰 블록 무늬. */
	private void paintDecor(Graphics2D g) {
		int[][][] shapes = { { { 0, 1 }, { 1, 0 }, { 1, 1 }, { 1, 2 } }, { { 0, 0 }, { 1, 0 }, { 2, 0 }, { 3, 0 } },
				{ { 0, 0 }, { 0, 1 }, { 1, 1 }, { 1, 2 } }, { { 0, 0 }, { 0, 1 }, { 1, 0 }, { 1, 1 } },
				{ { 0, 2 }, { 1, 0 }, { 1, 1 }, { 1, 2 } } };
		int[][] places = { { 40, 70, 6 }, { 150, 380, 1 }, { 60, 540, 7 }, { 1110, 90, 2 }, { 1180, 330, 4 },
				{ 1060, 520, 3 } };
		for (int i = 0; i < places.length; i++) {
			int[][] s = shapes[i % shapes.length];
			for (int[] c : s) {
				Theme.block(g, places[i][0] + c[1] * 34, places[i][1] + c[0] * 34, 34, places[i][2], 40, false);
			}
		}
	}

	private void paintTitle(Graphics2D g) {
		g.setFont(Theme.font(60, true));
		FontMetrics fm = g.getFontMetrics();
		String a = "TETRIS ";
		String b = "BATTLE";
		int total = fm.stringWidth(a + b);
		int x = (W - total) / 2;
		int y = 96;
		g.setColor(Theme.TEXT);
		g.drawString(a, x, y);
		int bx = x + fm.stringWidth(a);
		g.setPaint(new GradientPaint(bx, 0, Theme.P1, bx + fm.stringWidth(b), 0, Theme.ACCENT_2));
		g.drawString(b, bx, y);
		g.setFont(Theme.font(18, false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, "딥러닝 AI와 겨루는 2인 테트리스", W / 2.0, 134);
	}

	private void paintModes(Graphics2D g, int x, int y) {
		label(g, "모드", x, y + 4, focus == ROW_MODE);
		String[][] modes = { { "사람 대 AI", "학습한 AI와 실시간으로 겨뤄요" }, { "혼자 연습", "AI 없이 내 속도에 맞춰 연습해요" } };
		for (int i = 0; i < 2; i++) {
			Rectangle r = new Rectangle(x + i * 396, y + 14, 384, 86);
			boolean sel = (i == 1) == solo;
			boolean hov = hover != null && hover.row() == ROW_MODE && hover.index() == i;
			Theme.card(g, r.x, r.y, r.width, r.height, 20, sel ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 200),
					hov ? Theme.TEXT_FAINT : Theme.BORDER);
			if (sel) {
				Theme.glow(g, r.x, r.y, r.width, r.height, 20, i == 0 ? Theme.P2 : Theme.P1, focus == ROW_MODE ? 1 : 0.6);
			}
			paintModeIcon(g, r.x + 22, r.y + 15, i == 0);
			g.setFont(Theme.font(22, true));
			g.setColor(sel ? Theme.TEXT : Theme.TEXT_DIM);
			g.drawString(modes[i][0], r.x + 104, r.y + 39);
			g.setFont(Theme.font(14, false));
			g.setColor(Theme.TEXT_DIM);
			g.drawString(modes[i][1], r.x + 104, r.y + 64);
			hits.add(new Hit(r, ROW_MODE, i));
		}
	}

	/** 모드 카드의 작은 그림: 보드 둘(대전) 또는 하나(연습). */
	private static void paintModeIcon(Graphics2D g, int x, int y, boolean versus) {
		int boards = versus ? 2 : 1;
		for (int b = 0; b < boards; b++) {
			int bx = x + b * 34 + (versus ? 0 : 17);
			Theme.card(g, bx, y, 28, 56, 6, Theme.BOARD, Theme.BORDER);
			int[] heights = b == 0 ? new int[] { 3, 2, 4, 1 } : new int[] { 1, 3, 2, 2 };
			for (int c = 0; c < 4; c++) {
				for (int r = 0; r < heights[c]; r++) {
					Theme.block(g, bx + 2 + c * 6, y + 48 - r * 6, 6, b == 0 ? 6 : 1, 255, false);
				}
			}
		}
	}

	private void paintVersusOptions(Graphics2D g) {
		List<AiCatalog.Difficulty> ds = catalog.difficulties();
		int x = 248;
		int y = 340;
		label(g, "난이도", x, y, focus == ROW_A);
		String[] names = new String[ds.size()];
		for (int i = 0; i < names.length; i++) {
			names[i] = ds.get(i).name();
		}
		pills(g, names, x, y + 14, 784, difficulty, ROW_A, Theme.ACCENT);
		AiCatalog.Difficulty d = ds.get(difficulty);
		g.setFont(Theme.font(15, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString(d.description().isEmpty() ? " " : d.description(), x, y + 86);
		g.setFont(Theme.font(12, true));
		g.setColor(Theme.TEXT_FAINT);
		Theme.right(g, "AI 학습량", x + 784, y + 86);
		strengthBar(g, x, y + 98, 784, difficulty, ds.size());

		int py = 474;
		boolean open = personasOpen();
		label(g, "페르소나", x, py, focus == ROW_B && open);
		List<AiCatalog.Persona> ps = catalog.personas();
		String[] pn = new String[ps.size()];
		for (int i = 0; i < pn.length; i++) {
			pn[i] = ps.get(i).name();
		}
		java.awt.Composite old = g.getComposite();
		if (!open) {
			g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.35f));
		}
		pills(g, pn, x, py + 14, Math.min(784, pn.length * 160), effectivePersona(), open ? ROW_B : -1, Theme.P2);
		g.setComposite(old);
		g.setFont(Theme.font(14, false));
		if (open) {
			AiCatalog.Persona p = ps.get(persona);
			g.setColor(Theme.TEXT_DIM);
			g.drawString(p.description().isEmpty() ? " " : p.description(), x, py + 78);
		} else if (ps.size() > 1 && !personaLevels().isEmpty()) {
			g.setColor(Theme.TEXT_FAINT);
			g.drawString("페르소나는 " + personaLevels() + "에서 고를 수 있어요", x, py + 78);
		}
	}

	private void paintSoloOptions(Graphics2D g) {
		int x = 248;
		int y = 340;
		label(g, "블록 속도", x, y, focus == ROW_A);
		pills(g, SOLO_CAP_NAMES, x, y + 14, 784, soloCap, ROW_A, Theme.P1);
		g.setFont(Theme.font(14, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("처음엔 천천히 떨어지다가 30초마다 빨라져요. 고른 속도까지만 빨라져요", x, y + 86);

		int py = 474;
		label(g, "방해 줄", x, py, focus == ROW_B);
		String[] names = { "없음", "조금씩", "섞어서" };
		pills(g, names, x, py + 14, 600, scheduler, ROW_B, Theme.P1);
		g.setFont(Theme.font(14, false));
		g.setColor(Theme.TEXT_DIM);
		String[] what = { "방해 줄 없이 편하게 쌓아요", "블록 10개를 놓을 때마다 아래에서 방해 줄이 3줄 올라와요",
				"블록 10개를 놓을 때마다 방해 줄이 2~4줄 올라와요" };
		g.drawString(what[scheduler], x, py + 78);
	}

	/** 알약 버튼 한 줄. */
	private void pills(Graphics2D g, String[] names, int x, int y, int width, int selected, int row, Color accent) {
		int gap = 10;
		int n = names.length;
		int w = (width - gap * (n - 1)) / n;
		for (int i = 0; i < n; i++) {
			Rectangle r = new Rectangle(x + i * (w + gap), y, w, 42);
			boolean sel = i == selected;
			boolean hov = row >= 0 && hover != null && hover.row() == row && hover.index() == i;
			if (sel) {
				g.setPaint(new GradientPaint(r.x, r.y, accent, r.x + r.width, r.y + r.height,
						Theme.mix(accent, Theme.ACCENT_2, 0.45)));
				g.fill(new java.awt.geom.RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 22, 22));
				if (focus == row) {
					Theme.glow(g, r.x, r.y, r.width, r.height, 22, accent, 0.8);
				}
			} else {
				Theme.card(g, r.x, r.y, r.width, r.height, 22, hov ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 210),
						hov ? Theme.TEXT_FAINT : Theme.BORDER);
			}
			g.setFont(Theme.font(16, sel));
			g.setColor(sel ? Color.WHITE : Theme.TEXT_DIM);
			Theme.centered(g, names[i], r.getCenterX(), r.y + 27);
			if (row >= 0) { // 잠긴 줄(-1)은 누를 수 없다
				hits.add(new Hit(r, row, i));
			}
		}
	}

	/** 난이도가 오를수록 차는 막대 (학습량과 속도). */
	private static void strengthBar(Graphics2D g, int x, int y, int width, int level, int levels) {
		int segs = levels;
		int gap = 6;
		int w = (width - gap * (segs - 1)) / segs;
		for (int i = 0; i < segs; i++) {
			Color c = i <= level ? Theme.mix(Theme.OK, Theme.DANGER, segs == 1 ? 0 : i / (double) (segs - 1))
					: Theme.withAlpha(Theme.BORDER, 255);
			Theme.card(g, x + i * (w + gap), y, w, 5, 5, c, null);
		}
	}

	private void paintStart(Graphics2D g, int x, int y) {
		Rectangle r = new Rectangle(x, y, 300, 54);
		boolean hov = hover != null && hover.row() == ROW_START;
		g.setPaint(new GradientPaint(r.x, r.y, solo ? Theme.P1 : Theme.ACCENT, r.x + r.width, r.y + r.height,
				Theme.ACCENT_2));
		g.fill(new java.awt.geom.RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 27, 27));
		if (focus == ROW_START || hov) {
			Theme.glow(g, r.x, r.y, r.width, r.height, 27, Theme.ACCENT_2, 1);
		}
		g.setFont(Theme.font(21, true));
		g.setColor(Color.WHITE);
		Theme.centered(g, solo ? "연습 시작" : "대전 시작", r.getCenterX() - 22, r.y + 35);
		g.setFont(Theme.font(12, true));
		int kx = (int) r.getCenterX() + 42;
		Theme.card(g, kx, r.y + 16, 56, 22, 8, new Color(10, 12, 30, 110), new Color(255, 255, 255, 90));
		g.setColor(Color.WHITE);
		Theme.centered(g, "Enter", kx + 28, r.y + 32);
		hits.add(new Hit(r, ROW_START, 0));
	}

	private static void paintKeys(Graphics2D g, int y) {
		String[][] keys = { { "← →", "이동" }, { "↓", "소프트드롭" }, { "Space", "하드드롭" }, { "↑ X", "회전" }, { "Z", "반대 회전" },
				{ "C", "홀드" }, { "P", "일시정지" }, { "Esc", "메뉴" } };
		g.setFont(Theme.font(13, true));
		FontMetrics fm = g.getFontMetrics();
		int total = 0;
		for (String[] k : keys) {
			total += fm.stringWidth(k[0]) + 16 + 6 + fm.stringWidth(k[1]) + 22;
		}
		int x = (W - total) / 2;
		for (String[] k : keys) {
			int kw = fm.stringWidth(k[0]) + 16;
			Theme.card(g, x, y - 17, kw, 24, 8, Theme.SURFACE, Theme.BORDER);
			g.setColor(Theme.TEXT);
			g.drawString(k[0], x + 8, y);
			x += kw + 6;
			g.setColor(Theme.TEXT_DIM);
			g.drawString(k[1], x, y);
			x += fm.stringWidth(k[1]) + 22;
		}
	}

	private void paintStatus(Graphics2D g) {
		if (catalog.problem() != null) {
			g.setFont(Theme.font(12, false));
			g.setColor(Theme.WARN);
			g.drawString("학습한 AI를 불러오지 못해 기본 AI로 둡니다", 24, H - 10);
		}
	}

	private static void label(Graphics2D g, String text, int x, int y, boolean focused) {
		g.setFont(Theme.font(15, true));
		g.setColor(focused ? Theme.TEXT : Theme.TEXT_DIM);
		g.drawString(text, x, y);
		if (focused) {
			g.setColor(Theme.ACCENT);
			g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(x - 12, y - 12, x - 12, y + 1);
		}
	}

}
