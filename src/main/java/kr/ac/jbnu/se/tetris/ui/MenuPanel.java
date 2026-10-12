package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.Transparency;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import javax.swing.JPanel;
import javax.swing.Timer;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.MenuMemory;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 첫 화면 (docs/GAME.md 7장): 모드 카드 셋(사람 대 AI, 혼자 연습, 모래 모드) → 그 모드의 설정만 → 시작. 기본 Swing 부품
 * 없이 직접 그리고, 1280 × 720 가상 화면을 창 크기에 맞춰 늘려 그린다. 마우스(누르기)와 키보드(↑↓·Tab 줄, Shift+Tab 위 줄,
 * ←→ 고르기, Enter·Space 시작)로 고른다. 누르고 있는 Enter·Space 의 자동 반복은 시작으로 받지 않는다: 결과 카드·설정에서 Enter 로
 * "메뉴로" 를 고르고 계속 누르고 있어도 메뉴가 그 반복으로 판을 시작하지 않는다({@link #heldKeys}).
 * <ul>
 * <li>사람 대 AI: 난이도(처음은 2단계 중급), AI 속도(50~150%, 바꾸면 바로 사용자 설정에 저장, 설정 화면의 AI 속도와 같은
 * 값), 페르소나(4·5단계에서만, GAME.md 6.2). 오른쪽 위에 AI 모델 상태(불러오는 중, 준비됨, 기준 AI 로 대신). 시작 단추 왼쪽에
 * 고른 난이도의 전적 한 줄("전적 3승 2패 · 최고 3연승", 한 판도 없으면 없음, {@link #recordLine}).</li>
 * <li>혼자 연습: 블록 속도(중력 상한)와 방해 줄.</li>
 * <li>모래 모드: 시작 단계와 최고 점수.</li>
 * </ul>
 * 고른 것(모드, 난이도, 페르소나, 혼자 연습·모래 모드의 설정)은 시작할 때 저장 파일의 메뉴 기억({@link MenuMemory})에 남기고,
 * 다음에 게임을 켜면 그대로 고른 채로 연다. 오른쪽 위에는 설정·도움말 같은 다른 화면으로 가는 단추를 둘 자리가 있다
 * ({@link #addLink}). 창이 짧은 알림을 띄울 수 있다: 시작 단추 오른쪽에 {@value #NOTICE_MS}ms 동안 보이고 끝의
 * {@value #NOTICE_FADE_MS}ms 에 옅어진다(기권 뒤 "기권 · 패배로 기록했어요", {@link #notice}).
 * <p>
 * 움직임 (runs/quality/SPEC.md V13): 양옆 여백에 옅은 블록이 천천히 떨어지고({@link MenuDecor}, 효과 세기가 끔이면 없음),
 * 키보드 초점이 있는 것에 네 귀퉁이 꺾쇠가 150ms 동안 옮겨 가며, 시작 단추는 누르면 0.1초 작아지고 초점이 있으면 빛이 천천히
 * 숨 쉰다. 다시 그리기 타이머(33ms)는 메뉴가 보이고 움직일 것이 있을 때만 돈다. 창이 비활성이면 떨어지는 블록과 숨 쉬는 빛이
 * 그 자리에 멈춘다({@link #windowActive}). 그림은 시계({@link #clock})로만 정해지므로 화면 확인 도구가 시계를 멈춰 그린다.
 * <p>
 * 그리기 비용: 움직이지 않는 부분(바탕, 제목, 큰 카드, 모드 카드, 모드마다 설정)은 고른 것·초점·마우스·설정·기록이 바뀔 때만 그림
 * 한 장에 다시 그려 두고({@link #paintLayer}), 프레임마다 그 그림 위에 떨어지는 블록, 단추, 모델 상태, 키 안내, 시작 단추, 꺾쇠만
 * 그린다.
 * <p>
 * 소리 (audio 패키지 설명의 표): ↑↓·Tab 으로 줄이 바뀌면 MENU_MOVE(끝에서는 조용히), ←→·마우스로 값이 바뀌면 MENU_MOVE, 끝이라
 * 안 바뀌거나 잠긴 페르소나를 누르면 DENY, 시작하면 MENU_SELECT. 설정·도움말을 열고 닫는 소리는 창(TetrisApp)이 낸다.
 */
final class MenuPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final int W = 1280;
	private static final int H = 720;

	/** 알림({@link #notice})이 보이는 시간 (ms). */
	static final int NOTICE_MS = 2000;
	/** 알림이 끝에 옅어지는 시간 (ms, {@link #NOTICE_MS} 안). */
	static final int NOTICE_FADE_MS = 400;

	/** 메뉴에서 판을 시작한다 (TetrisApp 이 정한다). */
	interface Starter {
		/** 사람 대 AI, 혼자 연습. */
		void start(GameSession.Settings settings);

		/** 모래 모드: 시작 단계 1~10. */
		void startSand(int level);
	}

	/** 모드. 메뉴 위 카드의 순서다. 색은 SPEC V13 의 모드별 색조 (카드 바탕, 고른 카드의 제목, 설명). */
	enum Mode {
		VERSUS("사람 대 AI", "학습한 AI와 겨뤄요", "대전 시작", Theme.P2, 0x3A1E2B, 0xFFC2D2, 0xD48AA0, MenuMemory.MODE_VERSUS),
		SOLO("혼자 연습", "내 속도로 연습해요", "연습 시작", Theme.P1, 0x16303A, 0xA6ECFF, 0x6FB8CF, MenuMemory.MODE_SOLO),
		SAND("모래 모드", "블록이 모래가 돼요", "게임 시작", Theme.SAND, 0x3A2F16, 0xFFE2A6, 0xCFAE6F, MenuMemory.MODE_SAND);

		final String title;
		final String blurb;
		final String start;
		final Color accent;
		final Color tint;
		final Color titleColor;
		final Color blurbColor;
		/** 메뉴 기억에 쓰는 이름 ({@link MenuMemory#MODE}). */
		final String memory;

		Mode(String title, String blurb, String start, Color accent, int tint, int titleColor, int blurbColor,
				String memory) {
			this.title = title;
			this.blurb = blurb;
			this.start = start;
			this.accent = accent;
			this.tint = new Color(tint);
			this.titleColor = new Color(titleColor);
			this.blurbColor = new Color(blurbColor);
			this.memory = memory;
		}
	}

	/** AI 모델 상태 (사람 대 AI 카드 오른쪽 위의 짧은 한 줄). 자세한 까닭은 화면 아래 경고 줄에 있다. */
	enum ModelStatus {
		/** 게임을 켠 뒤 모델을 배경에서 미리 불러오는 중. */
		LOADING("AI 불러오는 중"),
		/** 고른 AI 의 모델을 쓸 수 있다. */
		READY("AI 준비됐어요"),
		/** 모델 묶음이나 고른 AI 의 모델을 쓸 수 없어 기준 AI 로 둔다. */
		FALLBACK("기준 AI로 대신해요");

		final String text;

		ModelStatus(String text) {
			this.text = text;
		}
	}

	private static final Mode[] MODES = Mode.values();

	/** 혼자 연습의 블록 속도 = 중력 상한 (초당 칸 수, RULES 6.3 표). 메뉴에는 SOLO_CAP_NAMES 로 보인다. */
	private static final double[] SOLO_CAPS = { 1, 2, 3, 5, 8, 12 };
	private static final String[] SOLO_CAP_NAMES = { "아주 느리게", "느리게", "보통", "빠르게", "아주 빠르게", "최고 속도" };
	private static final GameModel.Scheduler[] SCHEDULERS = GameModel.Scheduler.values();
	private static final String[] SCHEDULER_NAMES = { "없음", "조금씩", "섞어서" };

	// 줄 (키보드 초점). 모드마다 쓰는 줄만 지나간다({@link #rows()}). 번호는 순서가 아니라 이름이다.
	static final int ROW_LINK = 0;
	static final int ROW_MODE = 1;
	/** 사람 대 AI: 난이도. */
	static final int ROW_LEVEL = 2;
	/** 사람 대 AI: 페르소나 (4·5단계에서만 열린다). */
	static final int ROW_PERSONA = 3;
	/** 혼자 연습: 블록 속도. */
	static final int ROW_SPEED = 4;
	/** 혼자 연습: 방해 줄. */
	static final int ROW_GARBAGE = 5;
	/** 모래 모드: 시작 단계. */
	static final int ROW_SAND_LEVEL = 6;
	static final int ROW_START = 7;
	/** 사람 대 AI: AI 속도 (난이도와 페르소나 사이). */
	static final int ROW_AI_SPEED = 8;

	// 큰 카드와 그 안의 자리 (가상 화면 좌표)
	private static final int CARD_X = 120;
	private static final int CARD_Y = 148;
	private static final int CARD_W = 1040;
	private static final int CARD_H = 484;
	private static final int CONTENT_X = 150;
	private static final int CONTENT_W = 980;
	private static final int MODE_Y = 170;
	private static final int MODE_H = 92;
	/** 첫 설정 줄의 이름 기준선. 둘째 줄은 {@link #ROW2} 아래. */
	private static final int OPTION_Y = 300;
	private static final int ROW2 = 138;
	private static final int START_Y = 562;
	/** 사람 대 AI 둘째 줄: 왼쪽 AI 속도, 오른쪽 페르소나. */
	private static final int SPEED_W = 260;
	private static final int PERSONA_X = 450;
	private static final int PERSONA_W = CONTENT_X + CONTENT_W - PERSONA_X;

	/** 누를 수 있는 곳 하나 (가상 화면 좌표). */
	private record Hit(Rectangle r, int row, int index) {
	}

	/** 다른 화면으로 가는 단추 (설정, 도움말). */
	private record Link(String label, Runnable action) {
	}

	private final transient AiCatalog catalog;
	private final transient SaveData save;
	private final transient Starter starter;
	private final transient List<Link> links = new ArrayList<>();
	private final transient List<Hit> hits = new ArrayList<>();
	private transient AffineTransform toScreen = new AffineTransform();
	/** 소리 (시험은 목록에 모은다). */
	transient Consumer<Sfx> sfx = s -> Sound.get().play(s);
	/** 시계 (ms). 화면 확인 도구가 멈춘 시계를 넣는다. */
	transient LongSupplier clock = () -> System.nanoTime() / 1_000_000L;
	private final transient Timer anim;

	private Mode mode = Mode.VERSUS;
	private int difficulty;
	private int persona;
	private int soloCap = 2;
	private int scheduler;
	private int sandLevel; // 0 = 1단계
	/** 저장 파일이 없을 때(미리보기 도구)의 AI 속도. 있으면 저장 파일의 값을 쓴다. */
	private int aiSpeedLocal = UserSettings.DEFAULT_AI_SPEED;
	private int link;
	private int focus = ROW_START;
	private transient Hit hover;
	private volatile boolean modelsChecked;
	/** 초점 꺾쇠: 지난 자리 → 새 자리 (가상 화면), 옮기기 시작한 때. 그릴 때 정한다. */
	private transient Rectangle2D bracketFrom;
	private transient Rectangle2D bracketTo;
	private long bracketAt = Long.MIN_VALUE / 2;
	/** 이번 그리기에서 초점이 있는 것의 자리. */
	private transient Rectangle focusTarget;
	/** 시작 단추를 누른 때 (눌림 피드백). */
	private long pressedAt = Long.MIN_VALUE / 2;
	/** 지금 알림의 글 ({@link #notice}), 없으면 null. */
	private transient String noticeText;
	/** 알림을 띄운 때. */
	private long noticeAt = Long.MIN_VALUE / 2;
	/** 지금 눌려 있는 키: 누르고 있는 Enter·Space 의 자동 반복을 거른다. */
	private final transient Set<Integer> down = new HashSet<>();
	/** 창이 활성인가 (아니면 떨어지는 블록과 숨 쉬는 빛을 멈춘다). */
	private boolean windowActive = true;
	/** 장식 시계에서 뺄 시간 (ms): 창이 비활성이던 동안. 다시 활성이 되면 장식이 멈춘 자리에서 이어진다. */
	private long decorPaused;
	/** 창이 비활성이 된 때 (장식 시계가 멈춘 때). */
	private long inactiveSince;
	/** 움직이지 않는 부분을 그려 둔 그림 (장치 화소), 그 열쇠, 그 안의 누를 자리와 초점. */
	private transient BufferedImage layer;
	private transient Object layerKey;
	private transient List<Hit> layerHits = List.of();
	private transient Rectangle layerFocus;
	/** 시작 단추의 바탕(그라데이션 알약)을 장치 화소로 그려 둔 그림과 그 열쇠. */
	private transient BufferedImage startSprite;
	private transient Object startKey;

	/**
	 * 사람 대 AI·혼자 연습만 시작하는 메뉴(모래 모드의 시작은 무시하고, 저장 파일 없이 그린다). 다른 화면의 미리보기 도구가
	 * 비교용 메뉴를 그릴 때 쓴다.
	 */
	MenuPanel(AiCatalog catalog, Consumer<GameSession.Settings> onStart) {
		this(catalog, null, new Starter() {
			@Override
			public void start(GameSession.Settings settings) {
				onStart.accept(settings);
			}

			@Override
			public void startSand(int level) {
				// 미리보기 도구는 모래 모드를 시작하지 않는다
			}
		});
	}

	/**
	 * @param save 메뉴 기억, AI 속도, 키, 색 설정, 효과 세기, 모래 모드의 최고 점수, 설정 파일 문제를 읽고 쓸 곳, 없으면 null
	 */
	MenuPanel(AiCatalog catalog, SaveData save, Starter starter) {
		this.catalog = catalog;
		this.save = save;
		this.starter = starter;
		int levels = catalog.difficulties().size();
		this.difficulty = Math.min(1, levels - 1); // 처음 고른 단계는 2단계 '중급'
		recall();
		setPreferredSize(new Dimension(W, H));
		setFocusable(true);
		enableInputMethods(false);
		setFocusTraversalKeysEnabled(false);
		anim = new Timer(33, e -> {
			repaint();
			if (!isShowing() || !moving()) {
				((Timer) e.getSource()).stop();
			}
		});
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				int code = e.getKeyCode();
				boolean repeat = !down.add(code);
				if (repeat && (code == KeyEvent.VK_ENTER || code == KeyEvent.VK_SPACE)) {
					return; // 누르고 있는 Enter·Space 의 자동 반복 (앞 화면에서 누른 것 포함): 판을 다시 시작하지 않는다
				}
				press(code, e.isShiftDown());
			}

			@Override
			public void keyReleased(KeyEvent e) {
				down.remove(e.getKeyCode());
			}
		});
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				down.clear(); // 다른 화면·창에서 뗀 키는 받지 못한다
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Hit h = hitAt(e.getX(), e.getY());
				if (!Objects.equals(h, hover)) { // 그릴 때마다 새로 만드는 자리이므로 값으로 비교한다
					hover = h;
					setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
					repaint();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				click(hitAt(e.getX(), e.getY()));
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		// 메뉴가 보이게 되면 움직임 타이머를 다시 돌린다 (판 화면에 있는 동안에는 돌지 않는다)
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				if (isShowing()) {
					poke();
				} else {
					anim.stop();
					layer = null; // 판 화면에 있는 동안 그림을 들고 있지 않는다 (돌아오면 한 번 다시 그린다)
					startSprite = null;
				}
			}
		});
	}

	/**
	 * 오른쪽 위에 다른 화면으로 가는 단추를 더한다(설정, 도움말). 먼저 더한 것이 왼쪽이다. 키보드로는 맨 위 줄에서 ←→ 로 고르고
	 * Enter 로 연다. 화면 스레드에서 부른다.
	 */
	void addLink(String label, Runnable action) {
		links.add(new Link(label, action));
		repaint();
	}

	/**
	 * 모든 모델을 미리 불러 본 뒤(TetrisApp 의 배경 스레드) 부른다. 그 뒤로는 고른 AI 의 모델을 쓸 수 있는지 알린다. 그 전에는
	 * 모델을 불러오느라 화면이 멈추지 않게 묻지 않는다("AI 불러오는 중").
	 */
	void modelsChecked() {
		modelsChecked = true;
		poke();
		repaint();
	}

	/**
	 * 메뉴를 다시 보일 때 이미 눌려 있던 키 (TetrisApp 이 화면을 바꾸며 넘긴다). 결과 카드나 설정에서 Enter 로 "메뉴로" 를 고르고 계속
	 * 누르고 있으면 운영체제의 자동 반복이 메뉴에 오는데, 그것을 시작으로 받지 않는다(떼었다 다시 누르면 받는다).
	 */
	void heldKeys(Collection<Integer> codes) {
		down.addAll(codes);
	}

	/**
	 * 창이 활성인지 알린다 (TetrisApp). 비활성이면 떨어지는 블록과 시작 단추의 숨 쉬는 빛이 그 자리에 멈추고 다시 그리기 타이머도
	 * 멈춘다(옮겨 가던 꺾쇠와 눌림은 끝까지 간다). 다시 활성이 되면 멈춘 자리에서 이어서 움직인다.
	 */
	void windowActive(boolean active) {
		if (active == windowActive) {
			return;
		}
		long now = clock.getAsLong();
		if (active) {
			decorPaused += now - inactiveSince;
		} else {
			inactiveSince = now;
		}
		windowActive = active;
		poke();
	}

	/**
	 * 짧은 알림을 띄운다 (TetrisApp: 끝나지 않은 사람 대 AI 판을 떠난 뒤 "기권 · 패배로 기록했어요"). 시작 단추 오른쪽에
	 * {@value #NOTICE_MS}ms 동안 보이고 끝의 {@value #NOTICE_FADE_MS}ms 에 옅어진다. 새 알림은 앞 알림을 바꾼다. 화면 스레드에서
	 * 부른다.
	 */
	void notice(String text) {
		noticeText = text;
		noticeAt = clock.getAsLong();
		poke();
		repaint();
	}

	/** 지금 보이는 알림의 글, 없으면 null (시험). */
	String noticeText() {
		return noticeShowing(clock.getAsLong()) ? noticeText : null;
	}

	private boolean noticeShowing(long now) {
		return noticeText != null && now - noticeAt >= 0 && now - noticeAt < NOTICE_MS;
	}

	/** 장식(떨어지는 블록, 숨 쉬는 빛)의 시계: 창이 비활성인 동안은 멈춘다. */
	private long decorNow(long now) {
		return (windowActive ? now : inactiveSince) - decorPaused;
	}

	/** 사람 대 AI 카드의 모델 상태. */
	ModelStatus modelStatus() {
		if (catalog.problem() != null) {
			return ModelStatus.FALLBACK;
		}
		if (!modelsChecked) {
			return ModelStatus.LOADING;
		}
		return chosenModelBroken() ? ModelStatus.FALLBACK : ModelStatus.READY;
	}

	// ------------------------------------------------------------------ 메뉴 기억

	/** 저장 파일의 메뉴 기억으로 지난번에 고른 것을 고른다. 없거나 목록에 없으면(모델 묶음이 바뀜) 지금의 기본값. */
	private void recall() {
		if (save == null) {
			return;
		}
		MenuMemory m = save.settings().menu();
		List<AiCatalog.Difficulty> ds = catalog.difficulties();
		difficulty = m.indexOf(MenuMemory.VERSUS_LEVEL, ds.stream().map(AiCatalog.Difficulty::id).toList(), difficulty);
		persona = m.indexOf(MenuMemory.VERSUS_PERSONA, catalog.personas().stream().map(AiCatalog.Persona::id).toList(), 0);
		List<Double> caps = new ArrayList<>();
		for (double c : SOLO_CAPS) {
			caps.add(c);
		}
		soloCap = m.indexOf(MenuMemory.SOLO_SPEED, caps, soloCap);
		List<String> jsons = new ArrayList<>();
		for (GameModel.Scheduler s : SCHEDULERS) {
			jsons.add(s.json());
		}
		scheduler = m.indexOf(MenuMemory.SOLO_GARBAGE, jsons, 0);
		List<Integer> sandLevels = new ArrayList<>();
		for (int i = 1; i <= SandRules.MAX_LEVEL; i++) {
			sandLevels.add(i);
		}
		sandLevel = m.indexOf(MenuMemory.SAND_LEVEL, sandLevels, 0);
		for (Mode md : MODES) {
			if (md.memory.equals(m.get(MenuMemory.MODE))) {
				mode = md;
			}
		}
	}

	/** 시작할 때 고른 것을 메뉴 기억에 남긴다. 페르소나가 잠긴 단계로 시작해도 마지막으로 고른 페르소나를 그대로 둔다. */
	private void remember() {
		if (save == null) {
			return;
		}
		AiCatalog.Difficulty d = catalog.difficulties().get(difficulty);
		AiCatalog.Persona p = catalog.personas().get(persona);
		try {
			save.updateSettings(s -> s.withMenu(s.menu().with(MenuMemory.MODE, mode.memory)
					.with(MenuMemory.VERSUS_LEVEL, d.id()).with(MenuMemory.VERSUS_PERSONA, p.id())
					.with(MenuMemory.SOLO_SPEED, SOLO_CAPS[soloCap]).with(MenuMemory.SOLO_GARBAGE, SCHEDULERS[scheduler].json())
					.with(MenuMemory.SAND_LEVEL, sandLevel + 1)));
		} catch (IllegalArgumentException e) { // 모델 묶음의 id 가 메뉴 기억에 쓸 수 없는 글자일 때: 기억만 하지 않는다
			System.err.println("메뉴 기억을 남기지 못함: " + e.getMessage());
		}
	}

	// ------------------------------------------------------------------ 고르기

	/** 키 하나 (화면 스레드). 시험은 직접 부른다. */
	void press(int code) {
		press(code, false);
	}

	/** 키 하나. Shift+Tab 은 위 줄로 (설정 화면과 같다). */
	void press(int code, boolean shift) {
		switch (code) {
		case KeyEvent.VK_UP:
			move(-1);
			break;
		case KeyEvent.VK_DOWN:
			move(1);
			break;
		case KeyEvent.VK_TAB:
			move(shift ? -1 : 1);
			break;
		case KeyEvent.VK_LEFT:
			step(-1);
			break;
		case KeyEvent.VK_RIGHT:
			step(1);
			break;
		case KeyEvent.VK_ENTER:
		case KeyEvent.VK_SPACE:
			if (focus == ROW_LINK && !links.isEmpty()) {
				links.get(link).action().run(); // 여는 소리는 창이 낸다
			} else {
				start();
			}
			return;
		default:
			return;
		}
		poke();
		repaint();
	}

	/** 마우스로 누른 곳. */
	private void click(Hit h) {
		if (h == null) {
			return;
		}
		if (h.row() == ROW_START) {
			focus = ROW_START;
			start();
			return;
		}
		if (h.row() == ROW_LINK) {
			select(ROW_LINK, h.index());
			links.get(link).action().run();
			return;
		}
		int before = value(h.row());
		if (h.index() < 0 || h.index() >= count(h.row()) || !select(h.row(), h.index())) {
			sfx.accept(Sfx.DENY); // 끝을 넘음, 잠긴 페르소나
			return;
		}
		if (value(h.row()) != before) {
			sfx.accept(Sfx.MENU_MOVE);
		}
		poke();
	}

	/** 지금 모드에서 키보드 초점이 지나가는 줄 (잠긴 페르소나 줄은 건너뛴다). */
	List<Integer> rows() {
		List<Integer> r = new ArrayList<>();
		if (!links.isEmpty()) {
			r.add(ROW_LINK);
		}
		r.add(ROW_MODE);
		switch (mode) {
		case VERSUS:
			r.add(ROW_LEVEL);
			r.add(ROW_AI_SPEED);
			if (open(difficulty)) {
				r.add(ROW_PERSONA);
			}
			break;
		case SOLO:
			r.add(ROW_SPEED);
			r.add(ROW_GARBAGE);
			break;
		default:
			r.add(ROW_SAND_LEVEL);
			break;
		}
		r.add(ROW_START);
		return r;
	}

	/** ↑(-1) ↓(+1): 위아래 줄로. 끝이면 조용히 그대로. */
	private void move(int d) {
		List<Integer> rows = rows();
		int i = rows.indexOf(focus);
		int before = focus;
		focus = i < 0 ? ROW_MODE : rows.get(Math.max(0, Math.min(rows.size() - 1, i + d)));
		if (focus != before) {
			sfx.accept(Sfx.MENU_MOVE);
		}
	}

	/** ←(-1) →(+1): 지금 줄에서 옆 항목으로. 끝이면 그대로 두고 DENY. 시작 단추에서는 아무것도 하지 않는다. */
	private void step(int d) {
		if (focus == ROW_START) {
			return;
		}
		int v = value(focus) + d;
		if (v >= 0 && v < count(focus) && select(focus, v)) {
			sfx.accept(Sfx.MENU_MOVE);
		} else {
			sfx.accept(Sfx.DENY);
		}
	}

	/** 줄 {@code row} 에서 지금 고른 항목. */
	private int value(int row) {
		switch (row) {
		case ROW_LINK:
			return link;
		case ROW_MODE:
			return mode.ordinal();
		case ROW_LEVEL:
			return difficulty;
		case ROW_PERSONA:
			return persona;
		case ROW_SPEED:
			return soloCap;
		case ROW_GARBAGE:
			return scheduler;
		case ROW_SAND_LEVEL:
			return sandLevel;
		case ROW_AI_SPEED:
			return (aiSpeed() - UserSettings.MIN_AI_SPEED) / UserSettings.AI_SPEED_STEP;
		default:
			return 0;
		}
	}

	/** 줄 {@code row} 의 항목 수. */
	private int count(int row) {
		switch (row) {
		case ROW_LINK:
			return links.size();
		case ROW_MODE:
			return MODES.length;
		case ROW_LEVEL:
			return catalog.difficulties().size();
		case ROW_PERSONA:
			return catalog.personas().size();
		case ROW_SPEED:
			return SOLO_CAPS.length;
		case ROW_GARBAGE:
			return SCHEDULERS.length;
		case ROW_SAND_LEVEL:
			return SandRules.MAX_LEVEL;
		case ROW_AI_SPEED:
			return (UserSettings.MAX_AI_SPEED - UserSettings.MIN_AI_SPEED) / UserSettings.AI_SPEED_STEP + 1;
		default:
			return 1;
		}
	}

	/**
	 * 줄 {@code row} 의 {@code index} 번째 항목을 고르고 그 줄로 초점을 옮긴다(마우스, 화면 확인 도구, 시험). 잠긴 페르소나 줄은
	 * 고르지 않는다. AI 속도는 바로 사용자 설정에 저장한다(설정 화면과 같은 값).
	 *
	 * @return 골랐으면 true, 잠긴 페르소나 줄이면 false
	 */
	boolean select(int row, int index) {
		switch (row) {
		case ROW_LINK:
			link = clamp(index, links.size());
			break;
		case ROW_MODE:
			mode = MODES[clamp(index, MODES.length)];
			break;
		case ROW_LEVEL:
			difficulty = clamp(index, count(row));
			break;
		case ROW_PERSONA:
			if (!open(difficulty)) {
				return false;
			}
			persona = clamp(index, count(row));
			break;
		case ROW_SPEED:
			soloCap = clamp(index, count(row));
			break;
		case ROW_GARBAGE:
			scheduler = clamp(index, count(row));
			break;
		case ROW_SAND_LEVEL:
			sandLevel = clamp(index, count(row));
			break;
		case ROW_AI_SPEED:
			setAiSpeed(UserSettings.MIN_AI_SPEED + clamp(index, count(row)) * UserSettings.AI_SPEED_STEP);
			break;
		default:
			break;
		}
		focus = row;
		repaint();
		return true;
	}

	/** 지금 모드. */
	Mode mode() {
		return mode;
	}

	/** 키보드 초점이 있는 줄. */
	int focus() {
		return focus;
	}

	/** 사람 대 AI 의 AI 속도 (%): 저장 파일의 값 (설정 화면과 같다). 저장 파일이 없으면 이 메뉴만의 값. */
	int aiSpeed() {
		return save == null ? aiSpeedLocal : save.settings().aiSpeed();
	}

	private void setAiSpeed(int percent) {
		if (save == null) {
			aiSpeedLocal = percent;
		} else if (save.settings().aiSpeed() != percent) {
			save.updateSettings(s -> s.withAiSpeed(percent));
		}
	}

	/** 그 난이도(목록 번호)에서 페르소나를 고를 수 있는가 (4·5단계만, GAME.md 6.2). */
	private boolean open(int level) {
		return catalog.difficulties().get(level).personas();
	}

	/** 실제로 쓸 페르소나: 고를 수 없는 단계면 중립(첫 항목). */
	private AiCatalog.Persona effectivePersona(int level, int personaIndex) {
		return catalog.personas().get(open(level) ? personaIndex : 0);
	}

	/** 페르소나를 고를 수 있는 단계 이름 ("최상급·불가능"). 모델 묶음의 {@code "personas": true} 를 따른다. */
	private String personaLevels() {
		return catalog.difficulties().stream().filter(AiCatalog.Difficulty::personas).map(AiCatalog.Difficulty::name)
				.collect(Collectors.joining("·"));
	}

	private static int clamp(int v, int n) {
		return Math.max(0, Math.min(n - 1, v));
	}

	private void start() {
		sfx.accept(Sfx.MENU_SELECT);
		pressedAt = clock.getAsLong();
		poke();
		remember();
		List<AiCatalog.Difficulty> ds = catalog.difficulties();
		switch (mode) {
		case VERSUS:
			starter.start(GameSession.Settings.versus(ds.get(difficulty), effectivePersona(difficulty, persona), aiSpeed()));
			break;
		case SOLO:
			starter.start(GameSession.Settings.solo((int) Math.round(SOLO_CAPS[soloCap] * 1000), SCHEDULERS[scheduler]));
			break;
		default:
			starter.startSand(sandLevel + 1);
			break;
		}
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

	// ------------------------------------------------------------------ 움직임

	/** 효과 세기 (끔 0 … 강하게 1.5). 저장 파일이 없으면 보통. */
	private double effects() {
		return save == null ? 1 : save.settings().effectsScale();
	}

	/** 지금 움직이는 것이 있는가: 떨어지는 블록(효과를 켜고 창이 활성일 때), 옮겨 가는 꺾쇠, 눌린 시작 단추, 알림(옅어지고 사라진다). */
	private boolean moving() {
		long now = clock.getAsLong();
		return effects() > 0 && windowActive || now - bracketAt < Motion.SHORT || now - pressedAt < Motion.FAST
				|| noticeShowing(now);
	}

	/** 움직일 것이 있으면 다시 그리기 타이머를 돌린다. 메뉴가 보일 때만. */
	private void poke() {
		if (isShowing() && moving() && !anim.isRunning()) {
			anim.start();
		}
	}

	@Override
	public void removeNotify() {
		anim.stop();
		super.removeNotify();
	}

	// ------------------------------------------------------------------ 그리기

	/** 큰 카드와 그 테두리의 1 화소 바깥 (떨어지는 블록은 이 안에만, 가상 화면 좌표). */
	private static final Area OUTSIDE_CARD;

	static {
		Area outside = new Area(new Rectangle(-W, -H, 3 * W, 3 * H));
		outside.subtract(new Area(new RoundRectangle2D.Double(CARD_X - 1, CARD_Y - 1, CARD_W + 2, CARD_H + 2, 30, 30)));
		OUTSIDE_CARD = outside;
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.antialias(g);
		double scale = Math.min(getWidth() / (double) W, getHeight() / (double) H);
		AffineTransform t = new AffineTransform();
		t.translate((getWidth() - W * scale) / 2, (getHeight() - H * scale) / 2);
		t.scale(scale, scale);
		toScreen = t;
		hits.clear();
		focusTarget = null;
		long now = clock.getAsLong();
		long decor = decorNow(now);
		paintLayer(g, t); // 바탕, 제목, 큰 카드, 모드 카드, 모드마다 설정: 바뀔 때만 다시 그린 그림
		g.transform(t);
		if (effects() > 0) { // 떨어지는 블록은 큰 카드 밖에만 (카드가 반투명이라 비쳐 보이지 않게)
			Shape clip = g.getClip();
			g.clip(OUTSIDE_CARD);
			MenuDecor.paintFalling(g, look(), decor);
			g.setClip(clip);
		}
		paintLinks(g);
		if (mode == Mode.VERSUS) {
			paintModelStatus(g, CONTENT_X + CONTENT_W, OPTION_Y, decor);
		}
		paintStart(g, now, decor);
		paintNotice(g, now);
		paintKeys(g, 676);
		paintStatus(g);
		paintBrackets(g, now);
		g.dispose();
	}

	/**
	 * 움직이지 않는 부분의 그림을 다시 그려야 하는지 가리는 값들 (그림 크기·화소 안의 위치, 고른 것, 초점, 마우스, 설정, 기록: 고른
	 * 난이도의 전적과 모래 모드).
	 */
	private record LayerKey(int w, int h, double scale, double fx, double fy, int panelW, int panelH, Mode mode,
			int difficulty, int persona, int soloCap, int scheduler, int sandLevel, int aiSpeed, int focus, Hit hover,
			UserSettings settings, PlayRecords.Versus versus, PlayRecords.Sand sand) {
	}

	/**
	 * 움직이지 않는 부분(바탕, 제목, 큰 카드, 모드 카드, 지금 모드의 설정)을 그린다. 바뀐 것이 없으면 지난번에 장치 화소로 그려 둔 그림을
	 * 붙이고, 그 안의 누를 자리와 초점도 그때 것을 쓴다. 그림은 같은 그리기를 정수 화소만큼만 옮겨 그린 것이라 바로 그린 것과 같다.
	 * 떨어지는 블록은 이 부분(큰 카드와 제목)에 겹치지 않는다(MenuDecor 는 양옆 여백 x -56~236, 1044~1336 에만 그린다). 회전 같은
	 * 이상한 변환이면 바로 그린다.
	 */
	private void paintLayer(Graphics2D g, AffineTransform view) {
		AffineTransform dev = g.getTransform();
		double s = dev.getScaleX();
		if ((dev.getType() & ~(AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE)) != 0 || !(s > 0)
				|| getWidth() <= 0 || getHeight() <= 0) {
			paintStatic(g, view);
			return;
		}
		int ox = (int) Math.floor(dev.getTranslateX());
		int oy = (int) Math.floor(dev.getTranslateY());
		double fx = dev.getTranslateX() - ox;
		double fy = dev.getTranslateY() - oy;
		int lw = (int) Math.ceil(getWidth() * s + fx);
		int lh = (int) Math.ceil(getHeight() * s + fy);
		LayerKey key = new LayerKey(lw, lh, s, fx, fy, getWidth(), getHeight(), mode, difficulty, persona, soloCap,
				scheduler, sandLevel, aiSpeed(), focus, hover, save == null ? null : save.settings(), versusRecord(),
				save == null ? null : save.records().sand());
		if (layer == null || !key.equals(layerKey)) {
			if (layer == null || layer.getWidth() != lw || layer.getHeight() != lh) {
				layer = newLayer(lw, lh);
			}
			Graphics2D lg = layer.createGraphics();
			lg.setRenderingHints(g.getRenderingHints());
			lg.translate(fx, fy);
			lg.scale(s, s);
			paintStatic(lg, view);
			lg.dispose();
			layerHits = List.copyOf(hits);
			layerFocus = focusTarget;
			layerKey = key;
		} else {
			hits.addAll(layerHits);
			focusTarget = layerFocus;
		}
		g.setTransform(new AffineTransform());
		g.drawImage(layer, ox, oy, null);
		g.setTransform(dev);
	}

	/** 그려 둘 그림 한 장: 화면과 같은 형식(가속되는 그림), 창에 붙지 않았으면(시험) 보통 그림. */
	private BufferedImage newLayer(int w, int h) {
		GraphicsConfiguration gc = getGraphicsConfiguration();
		if (gc != null) {
			try {
				return gc.createCompatibleImage(w, h, Transparency.OPAQUE);
			} catch (RuntimeException e) {
				// 아래의 보통 그림
			}
		}
		return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
	}

	/** 움직이지 않는 부분을 그대로 그린다 (그려 둘 그림에, 또는 바로). {@code view} 는 가상 화면 → 패널 좌표. */
	private void paintStatic(Graphics2D g, AffineTransform view) {
		Theme.backgroundCached(g, getWidth(), getHeight());
		AffineTransform base = g.getTransform();
		g.transform(view);
		paintTitle(g);
		Theme.card(g, CARD_X, CARD_Y, CARD_W, CARD_H, 28, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER);
		paintModes(g);
		switch (mode) {
		case VERSUS:
			paintVersusOptions(g);
			break;
		case SOLO:
			paintSoloOptions(g);
			break;
		default:
			paintSandOptions(g);
			break;
		}
		g.setTransform(base);
	}

	/** 제목: Barlow Condensed Black 72, 자간 0.04em, BATTLE 은 P1 → 보라 그라데이션 (SPEC V2·V13). */
	private static void paintTitle(Graphics2D g) {
		Font f = Theme.tracked(Theme.display(72), 0.04);
		g.setFont(f);
		String a = "TETRIS ";
		String b = "BATTLE";
		float total = Theme.textWidth(g, a + b);
		float aw = Theme.textWidth(g, a) + (float) (0.04 * 72); // 글자 사이 자간 한 칸
		double x = (W - total) / 2.0;
		int y = 98;
		g.setColor(Theme.TEXT);
		Theme.text(g, a, x, y, Theme.Align.LEFT);
		double bx = x + aw;
		g.setPaint(new GradientPaint((float) bx, 0, Theme.P1, (float) (bx + Theme.textWidth(g, b)), 0, Theme.ACCENT_2));
		Theme.text(g, b, bx, y, Theme.Align.LEFT);
		g.setFont(Theme.font(17, false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, "딥러닝 AI와 겨루는 테트리스", W / 2.0, 128);
	}

	/** 오른쪽 위의 다른 화면 단추 (설정, 도움말). 없으면 그리지 않는다. */
	private void paintLinks(Graphics2D g) {
		if (links.isEmpty()) {
			return;
		}
		g.setFont(Theme.font(14, true));
		FontMetrics fm = g.getFontMetrics();
		int x = CARD_X + CARD_W;
		for (int i = links.size() - 1; i >= 0; i--) {
			String label = links.get(i).label();
			int w = fm.stringWidth(label) + 32;
			x -= w;
			Rectangle r = new Rectangle(x, 100, w, 34);
			boolean sel = focus == ROW_LINK && link == i;
			boolean hov = hover != null && hover.row() == ROW_LINK && hover.index() == i;
			Theme.card(g, r.x, r.y, r.width, r.height, 34, hov || sel ? Theme.SURFACE_HI : Theme.withAlpha(Theme.SURFACE, 200),
					hov ? Theme.TEXT_FAINT : Theme.BORDER);
			if (sel) {
				Theme.glow(g, r.x, r.y, r.width, r.height, 34, Theme.ACCENT, 0.8);
				focusTarget = r;
			}
			g.setFont(Theme.font(14, true));
			g.setColor(sel || hov ? Theme.TEXT : Theme.TEXT_DIM);
			Theme.centered(g, label, r.getCenterX(), r.y + 22);
			hits.add(new Hit(r, ROW_LINK, i));
			x -= 10;
		}
	}

	// ------------------------------------------------------------------ 모드 카드

	/**
	 * 모드 카드 셋 (SPEC V13): 고르지 않은 카드는 모드 색조를 바탕과 반씩 섞어 옅게(α 200), 고른 카드는 색조 바탕에 2 화소 강조
	 * 테두리, 빛(초점이 있으면 1.0, 없으면 0.6), 왼쪽 끝 4 화소 막대.
	 */
	private void paintModes(Graphics2D g) {
		int gap = 12;
		int w = (CARD_W - 48 - gap * (MODES.length - 1)) / MODES.length;
		for (Mode m : MODES) {
			int i = m.ordinal();
			Rectangle r = new Rectangle(CARD_X + 24 + i * (w + gap), MODE_Y, w, MODE_H);
			boolean sel = m == mode;
			boolean hov = hover != null && hover.row() == ROW_MODE && hover.index() == i;
			if (sel) {
				Theme.card(g, r.x, r.y, r.width, r.height, 20, m.tint, null);
				Theme.glow(g, r.x, r.y, r.width, r.height, 20, m.accent, focus == ROW_MODE ? 1 : 0.6);
				Stroke old = g.getStroke();
				g.setStroke(new BasicStroke(2f));
				g.setColor(m.accent);
				g.draw(new RoundRectangle2D.Double(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 19, 19));
				g.setStroke(old);
				g.fill(new RoundRectangle2D.Double(r.x + 6, r.y + 16, 4, r.height - 32, 4, 4));
				if (focus == ROW_MODE) {
					focusTarget = r;
				}
			} else {
				Theme.card(g, r.x, r.y, r.width, r.height, 20, Theme.withAlpha(Theme.mix(m.tint, Theme.BG_TOP, 0.5), 200),
						hov ? Theme.withAlpha(m.accent, 150) : Theme.BORDER);
			}
			paintModeIcon(g, r.x + 18, r.y + 18, m);
			g.setColor(sel ? m.titleColor : hov ? Theme.TEXT : Theme.TEXT_DIM);
			Theme.Type.TITLE.draw(g, m.title, 19, r.x + 88, r.y + 41, Theme.Align.LEFT);
			g.setColor(sel ? m.blurbColor : Theme.LABEL);
			Theme.Type.BODY.draw(g, m.blurb, 14, r.x + 88, r.y + 66, Theme.Align.LEFT);
			hits.add(new Hit(r, ROW_MODE, i));
		}
	}

	/** 모드 카드의 작은 그림 (가로 약 62, 세로 56). */
	private void paintModeIcon(Graphics2D g, int x, int y, Mode m) {
		switch (m) {
		case VERSUS:
			miniBoard(g, x, y, new int[] { 3, 2, 4, 1 }, 6);
			miniBoard(g, x + 34, y, new int[] { 1, 3, 2, 2 }, 1);
			break;
		case SOLO:
			miniBoard(g, x + 17, y, new int[] { 2, 4, 3, 1 }, 6);
			break;
		default:
			paintSandIcon(g, x + 17, y);
			break;
		}
	}

	/** 작은 보드 하나: 열마다 높이만큼 블록. */
	private void miniBoard(Graphics2D g, int x, int y, int[] heights, int code) {
		Theme.card(g, x, y, 28, 56, 6, Theme.BOARD, Theme.BORDER);
		ColorPalettes.Look look = look();
		for (int c = 0; c < heights.length; c++) {
			for (int r = 0; r < heights[c]; r++) {
				look.block(g, x + 2 + c * 6, y + 48 - r * 6, 6, code, 255, false);
			}
		}
	}

	/** 모래 모드 그림: 작은 판에 네 색 모래가 비탈지게 쌓였다. */
	private void paintSandIcon(Graphics2D g, int x, int y) {
		Theme.card(g, x, y, 28, 56, 6, Theme.BOARD, Theme.BORDER);
		ColorPalettes.Look look = look();
		SandColor[] colors = SandColor.values();
		Color[][] shades = new Color[SandRules.COLORS][]; // 색 띠마다 {보통, 밝게, 어둡게}: 알갱이마다 만들지 않는다
		for (int b = 0; b < shades.length; b++) {
			Color c = look.sand(colors[b].code());
			shades[b] = new Color[] { c, Theme.mix(c, Color.WHITE, 0.12), Theme.mix(c, Color.BLACK, 0.12) };
		}
		int[] heights = { 9, 10, 12, 13, 14, 13, 12, 11, 10, 11, 12, 13 }; // 2 화소 알갱이 열마다 높이
		for (int col = 0; col < heights.length; col++) {
			for (int r = 0; r < heights[col]; r++) {
				int band = (r + (col / 4)) / 4 % SandRules.COLORS; // 비스듬한 색 띠
				int noise = (col * 7 + r * 13) % 5;
				g.setColor(shades[band][noise == 0 ? 1 : noise == 1 ? 2 : 0]);
				g.fillRect(x + 2 + col * 2, y + 52 - (r + 1) * 2, 2, 2);
			}
		}
	}

	/** 설정(색약 색상, 블록 무늬)에 맞는 색. */
	private ColorPalettes.Look look() {
		return save == null ? ColorPalettes.standard() : ColorPalettes.look(save.settings());
	}

	/** 지금 키 묶음 (저장 파일이 없으면 처음 키). */
	private KeyBindings keyBindings() {
		return save == null ? KeyBindings.defaults() : save.settings().keys();
	}

	// ------------------------------------------------------------------ 모드마다 설정

	/** 사람 대 AI: 첫 줄 난이도(오른쪽 위의 모델 상태는 프레임마다 따로 그린다), 둘째 줄 왼쪽 AI 속도, 오른쪽 페르소나. */
	private void paintVersusOptions(Graphics2D g) {
		List<AiCatalog.Difficulty> ds = catalog.difficulties();
		int x = CONTENT_X;
		int y = OPTION_Y;
		label(g, "난이도", x, y, focus == ROW_LEVEL);
		pills(g, names(ds), x, y + 14, CONTENT_W, 42, difficulty, ROW_LEVEL, Theme.ACCENT, 16);
		AiCatalog.Difficulty d = ds.get(difficulty);
		g.setFont(Theme.font(15, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString(d.description().isEmpty() ? " " : d.description(), x, y + 82);
		g.setFont(Theme.font(12, true));
		g.setColor(Theme.LABEL);
		Theme.right(g, "AI 실력", x + CONTENT_W, y + 82);
		strengthBar(g, x, y + 94, CONTENT_W, difficulty, ds.size());
		paintAiSpeed(g, x, y + ROW2, d);
		paintPersonaRow(g, PERSONA_X, y + ROW2, PERSONA_W, difficulty, persona, Theme.P2, 42, 16);
		String record = versusRecordLine();
		if (record != null) { // 시작 단추 왼쪽 빈 곳 (x 150~470), 단추 글자와 같은 높이쯤
			g.setFont(Theme.font(13, false));
			g.setColor(Theme.LABEL);
			g.drawString(record, x, START_Y + 32);
		}
	}

	/** 고른 난이도의 전적 (저장 파일이 없으면 null). */
	private PlayRecords.Versus versusRecord() {
		return save == null ? null : save.records().versus(catalog.difficulties().get(difficulty).id());
	}

	/** 사람 대 AI 카드의 시작 단추 왼쪽에 보일 고른 난이도의 전적 한 줄({@link #recordLine}), 없으면 null. */
	String versusRecordLine() {
		PlayRecords.Versus v = versusRecord();
		return v == null ? null : recordLine(v);
	}

	/**
	 * 전적 한 줄: "전적 3승 2패 · 최고 3연승". 무승부가 있으면 패 다음에 "· 1무", 이긴 적이 없으면 연승은 뺀다. 한 판도 없으면 null
	 * (그리지 않는다). 기권은 패로 센다({@link PlayRecords.Outcome#LOSS}).
	 */
	static String recordLine(PlayRecords.Versus v) {
		if (v.games() == 0) {
			return null;
		}
		StringBuilder s = new StringBuilder("전적 ").append(v.wins()).append("승 ").append(v.losses()).append('패');
		if (v.draws() > 0) {
			s.append(" · ").append(v.draws()).append('무');
		}
		if (v.bestStreak() > 0) {
			s.append(" · 최고 ").append(v.bestStreak()).append("연승");
		}
		return s.toString();
	}

	/** 오른쪽 위의 모델 상태 한 줄: 점(불러오는 중은 숨 쉬듯, 장식 시계 {@code now})과 짧은 글. */
	private void paintModelStatus(Graphics2D g, int right, int baseline, long now) {
		ModelStatus st = modelStatus();
		g.setFont(Theme.font(13, true));
		int tw = g.getFontMetrics().stringWidth(st.text);
		Color dot = st == ModelStatus.READY ? Theme.OK : Theme.WARN;
		double a = 1;
		if (st == ModelStatus.LOADING) {
			a = 0.45 + 0.55 * (1 - Motion.pulse(now, 1) * Math.min(1, effects()));
		}
		g.setColor(Theme.withAlpha(dot, (int) Math.round(255 * a)));
		g.fill(new Ellipse2D.Double(right - tw - 16, baseline - 9, 8, 8));
		g.setColor(st == ModelStatus.FALLBACK ? Theme.WARN : Theme.LABEL);
		g.drawString(st.text, right - tw, baseline);
	}

	/**
	 * AI 속도: ‹ 100% › (50~150%, 10 단위). 바꾸면 바로 사용자 설정에 저장한다. 아래에 고른 난이도의 AI 가 블록 하나를 두는 데
	 * 드는 시간(어림)을 보인다.
	 */
	private void paintAiSpeed(Graphics2D g, int x, int y, AiCatalog.Difficulty d) {
		boolean foc = focus == ROW_AI_SPEED;
		label(g, "AI 속도", x, y, foc);
		Rectangle r = new Rectangle(x, y + 14, SPEED_W, 42);
		int speed = aiSpeed();
		int idx = value(ROW_AI_SPEED);
		int n = count(ROW_AI_SPEED);
		Theme.card(g, r.x, r.y, r.width, r.height, r.height, foc ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 210),
				foc ? Theme.withAlpha(Theme.ACCENT, 200) : Theme.BORDER);
		if (foc) {
			Theme.glow(g, r.x, r.y, r.width, r.height, r.height, Theme.ACCENT, 0.8);
			focusTarget = r;
		}
		Rectangle left = new Rectangle(r.x + 4, r.y + 4, 34, 34);
		Rectangle right = new Rectangle(r.x + r.width - 38, r.y + 4, 34, 34);
		arrowButton(g, left, -1, idx > 0, isHover(ROW_AI_SPEED, idx - 1));
		arrowButton(g, right, 1, idx < n - 1, isHover(ROW_AI_SPEED, idx + 1));
		hits.add(new Hit(left, ROW_AI_SPEED, idx - 1));
		hits.add(new Hit(right, ROW_AI_SPEED, idx + 1));
		hits.add(new Hit(r, ROW_AI_SPEED, idx));
		g.setColor(Theme.TEXT);
		Theme.Type.NUMERIC.draw(g, speed + "%", 26, r.getCenterX(), r.y + 31, Theme.Align.CENTER);
		String now = SettingsPanel.seconds(SettingsPanel.pieceMs(d, speed));
		String base = SettingsPanel.seconds(SettingsPanel.pieceMs(d, UserSettings.DEFAULT_AI_SPEED));
		String hint = speed == UserSettings.DEFAULT_AI_SPEED ? "블록마다 약 " + now + "초 (기본)"
				: "블록마다 약 " + now + "초 (기본 " + base + "초)";
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.LABEL);
		g.drawString(hint, x, y + 14 + 42 + 22);
	}

	private boolean isHover(int row, int index) {
		return hover != null && hover.row() == row && hover.index() == index;
	}

	/** AI 속도의 ‹ › 단추. 끝이면 옅게. */
	private static void arrowButton(Graphics2D g, Rectangle r, int dir, boolean enabled, boolean hovered) {
		if (enabled && hovered) {
			g.setColor(Theme.withAlpha(Theme.ACCENT, 70));
			g.fill(new Ellipse2D.Double(r.x, r.y, r.width, r.height));
		}
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(enabled ? (hovered ? Theme.TEXT : Theme.TEXT_DIM) : Theme.withAlpha(Theme.TEXT_FAINT, 150));
		double cx = r.getCenterX();
		double cy = r.getCenterY();
		Path2D.Double p = new Path2D.Double();
		p.moveTo(cx - 3 * dir, cy - 7);
		p.lineTo(cx + 4 * dir, cy);
		p.lineTo(cx - 3 * dir, cy + 7);
		g.draw(p);
		g.setStroke(old);
	}

	/**
	 * 페르소나 줄: 이름, 알약들, 아래 설명. 고를 수 없는 단계면 옅게 잠그고 어느 단계에서 고를 수 있는지 알린다(잠긴 알약을 누르면
	 * DENY).
	 */
	private void paintPersonaRow(Graphics2D g, int x, int y, int width, int level, int chosen, Color accent, int pillH,
			int fontPx) {
		int row = ROW_PERSONA;
		boolean open = open(level);
		label(g, "페르소나", x, y, focus == row && open);
		List<AiCatalog.Persona> ps = catalog.personas();
		String[] pn = new String[ps.size()];
		for (int i = 0; i < pn.length; i++) {
			pn[i] = ps.get(i).name();
		}
		Composite old = g.getComposite();
		if (!open) {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
		}
		int pw = Math.min(width, pn.length * 170);
		pills(g, pn, x, y + 14, pw, pillH, open ? chosen : 0, row, accent, fontPx);
		g.setComposite(old);
		g.setFont(Theme.font(14, false));
		int ty = y + 14 + pillH + 22;
		if (open) {
			AiCatalog.Persona p = ps.get(chosen);
			g.setColor(Theme.TEXT_DIM);
			g.drawString(p.description().isEmpty() ? " " : p.description(), x, ty);
		} else if (ps.size() > 1 && !personaLevels().isEmpty()) {
			g.setColor(Theme.LABEL);
			g.drawString("페르소나는 " + personaLevels() + "에서 고를 수 있어요", x, ty);
		}
	}

	private void paintSoloOptions(Graphics2D g) {
		int x = CONTENT_X;
		int y = OPTION_Y;
		label(g, "블록 속도", x, y, focus == ROW_SPEED);
		pills(g, SOLO_CAP_NAMES, x, y + 14, CONTENT_W, 42, soloCap, ROW_SPEED, Theme.P1, 16);
		g.setFont(Theme.font(14, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("처음엔 천천히 떨어지다가 30초마다 빨라져요. 고른 속도까지만 빨라져요", x, y + 82);

		int py = y + ROW2;
		label(g, "방해 줄", x, py, focus == ROW_GARBAGE);
		pills(g, SCHEDULER_NAMES, x, py + 14, 600, 42, scheduler, ROW_GARBAGE, Theme.P1, 16);
		g.setFont(Theme.font(14, false));
		g.setColor(Theme.TEXT_DIM);
		String[] what = { "방해 줄 없이 편하게 쌓아요", "블록 10개를 놓을 때마다 아래에서 방해 줄이 3줄 올라와요",
				"블록 10개를 놓을 때마다 방해 줄이 2~4줄 올라와요" };
		g.drawString(what[scheduler], x, py + 78);
	}

	/**
	 * 모래 모드의 짧은 규칙 셋: 끝나는 조건(판의 점선, 도움말·판 옆 목표 카드와 같은 말), 지우기(sand-0.3: 양쪽 벽을 이은 덩어리가
	 * 칸 한 줄 분량 640알 이상이어야 사라진다, 판 옆 목표 카드와 같은 말), 콤보 배율(판 옆 카드의 이름).
	 */
	static final String[] SAND_RULES = { "모래가 점선 위까지 쌓이면 끝나요", "같은 색 모래로 양쪽 벽을 두툼하게 이으면 사라져요",
			"연달아 지우면 콤보 배율이 ×" + SandRules.MAX_MULTIPLIER + "까지 올라요" };

	/** 모래 모드: 시작 단계, 최고 점수, 짧은 규칙 셋. */
	private void paintSandOptions(Graphics2D g) {
		int x = CONTENT_X;
		int y = OPTION_Y;
		label(g, "시작 단계", x, y, focus == ROW_SAND_LEVEL);
		String[] levels = new String[SandRules.MAX_LEVEL];
		for (int i = 0; i < levels.length; i++) {
			levels[i] = String.valueOf(i + 1);
		}
		int pillsW = 600;
		pills(g, levels, x, y + 14, pillsW, 42, sandLevel, ROW_SAND_LEVEL, Theme.SAND, 16);
		g.setFont(Theme.font(14, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("30초마다 한 단계씩 빨라져요", x, y + 82);

		// 최고 점수
		int bx = x + pillsW + 40;
		int bw = CONTENT_X + CONTENT_W - bx;
		int by = y - 20;
		Theme.card(g, bx, by, bw, 112, 18, Theme.withAlpha(Theme.BG_TOP, 150), Theme.BORDER);
		PlayRecords.Sand rec = save == null ? PlayRecords.Sand.NONE : save.records().sand();
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		g.drawString("최고 점수", bx + 20, by + 28);
		if (rec.bestScore() > 0) {
			g.setColor(Theme.GOLD);
			Theme.Type.NUMERIC.draw(g, String.format(Locale.ROOT, "%,d", rec.bestScore()), 40, bx + 20, by + 72,
					Theme.Align.LEFT);
		} else {
			g.setFont(Theme.font(34, true));
			g.setColor(Theme.TEXT_FAINT);
			g.drawString("–", bx + 20, by + 70);
		}
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString(rec.games() > 0 ? rec.games() + "판 · 최대 배율 ×" + rec.bestMultiplier() : "아직 기록이 없어요", bx + 20,
				by + 96);

		// 규칙 셋: 작은 그림 + 한 줄
		int ry = y + ROW2;
		label(g, "이렇게 해요", x, ry, false);
		String[] rules = SAND_RULES;
		ColorPalettes.Look look = look();
		for (int i = 0; i < rules.length; i++) {
			int ly = ry + 34 + i * 30;
			SandColor c = SandColor.values()[i % SandRules.COLORS];
			Theme.card(g, x, ly - 14, 18, 18, 6, look.sand(c.code()), null);
			g.setFont(Theme.font(12, true));
			g.setColor(Theme.BG_TOP);
			Theme.centered(g, String.valueOf(i + 1), x + 9, ly);
			g.setFont(Theme.font(15, false));
			g.setColor(Theme.TEXT_DIM);
			g.drawString(rules[i], x + 30, ly);
		}
		// 둘째 규칙의 그림: 두툼한 같은 색 띠가 양쪽 벽을 잇는다
		SandPanel.paintSpanPicture(g, look, bx + 20, ry + 2, bw - 40, 92);
	}

	private static String[] names(List<AiCatalog.Difficulty> ds) {
		String[] names = new String[ds.size()];
		for (int i = 0; i < names.length; i++) {
			names[i] = ds.get(i).name();
		}
		return names;
	}

	/** 알약 단추 한 줄. 잠긴 줄(페르소나)도 누를 자리는 남긴다: 누르면 DENY. */
	private void pills(Graphics2D g, String[] names, int x, int y, int width, int h, int selected, int row, Color accent,
			int fontPx) {
		int gap = width >= 600 ? 10 : 8;
		int n = names.length;
		int w = (width - gap * (n - 1)) / n;
		boolean locked = row == ROW_PERSONA && !open(difficulty);
		for (int i = 0; i < n; i++) {
			Rectangle r = new Rectangle(x + i * (w + gap), y, w, h);
			boolean sel = i == selected;
			boolean hov = !locked && hover != null && hover.row() == row && hover.index() == i;
			if (sel) {
				g.setPaint(new GradientPaint(r.x, r.y, accent, r.x + r.width, r.y + r.height, accentEnd(accent)));
				g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, h / 2.0 + 1, h / 2.0 + 1));
				if (focus == row && !locked) {
					Theme.glow(g, r.x, r.y, r.width, r.height, h / 2.0 + 1, accent, 0.8);
					focusTarget = r;
				}
			} else {
				Theme.card(g, r.x, r.y, r.width, r.height, h / 2.0 + 1,
						hov ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 210), hov ? Theme.TEXT_FAINT : Theme.BORDER);
			}
			// 글자가 알약보다 넓으면 줄인다
			int px = fontPx;
			g.setFont(Theme.font(px, sel));
			while (px > 11 && g.getFontMetrics().stringWidth(names[i]) > w - 12) {
				g.setFont(Theme.font(--px, sel));
			}
			g.setColor(sel ? Color.WHITE : hov ? Theme.TEXT : Theme.TEXT_DIM);
			FontMetrics fm = g.getFontMetrics();
			Theme.centered(g, names[i], r.getCenterX(), r.y + (h + fm.getAscent() - fm.getDescent()) / 2.0);
			hits.add(new Hit(r, row, i));
		}
	}

	/** 고른 알약의 그라데이션 끝 색: 대개 보라 쪽으로, 모래는 진한 주황으로. */
	private static Color accentEnd(Color accent) {
		return accent == Theme.SAND ? SAND_DEEP : Theme.mix(accent, Theme.ACCENT_2, 0.45);
	}

	/** 모래 모드의 진한 주황 (시작 단추와 고른 알약의 그라데이션 끝). */
	private static final Color SAND_DEEP = new Color(0xD0623A);

	/** 난이도가 오를수록 차는 막대 (AI 실력: 학습한 모델, 내다보기, 속도를 합친 것). */
	private static void strengthBar(Graphics2D g, int x, int y, int width, int level, int levels) {
		int gap = width >= 300 ? 6 : 4;
		int w = (width - gap * (levels - 1)) / levels;
		for (int i = 0; i < levels; i++) {
			Color c = i <= level ? Theme.mix(Theme.OK, Theme.DANGER, levels == 1 ? 0 : i / (double) (levels - 1))
					: Theme.withAlpha(Theme.BORDER, 255);
			Theme.card(g, x + i * (w + gap), y, w, 5, 5, c, null);
		}
	}

	/**
	 * 시작 단추: 누르면 0.1초 동안 0.97 배, 초점이 있으면 빛이 0.5 Hz 로 숨 쉰다(0.5 ↔ 0.8, 효과 세기만큼, 장식 시계 {@code decor}).
	 * 바탕의 그라데이션 알약은 프레임마다 그리므로 그려 둔 그림을 붙인다({@link #fillStartCached}).
	 */
	private void paintStart(Graphics2D g, long now, long decor) {
		Rectangle r = new Rectangle(W / 2 - 150, START_Y, 300, 54);
		boolean hov = hover != null && hover.row() == ROW_START;
		boolean foc = focus == ROW_START;
		AffineTransform at = g.getTransform();
		boolean pressed = now - pressedAt < Motion.FAST;
		if (pressed) {
			g.translate(r.getCenterX(), r.getCenterY());
			g.scale(0.97, 0.97);
			g.translate(-r.getCenterX(), -r.getCenterY());
		}
		Color from = mode == Mode.VERSUS ? Theme.ACCENT : mode == Mode.SAND ? new Color(0xE58A2C) : mode.accent;
		Color to = mode == Mode.SAND ? SAND_DEEP : Theme.ACCENT_2;
		RoundRectangle2D.Double pill = new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, 27, 27);
		GradientPaint paint = new GradientPaint(r.x, r.y, from, r.x + r.width, r.y + r.height, to);
		if (pressed || !fillStartCached(g, pill, paint)) { // 눌려 작아지는 0.1초는 바로 그린다
			g.setPaint(paint);
			g.fill(pill);
		}
		if (foc || hov) {
			double m = Math.min(1.5, effects());
			double strength = foc ? 0.65 + 0.15 * m * (2 * Motion.pulse(decor, 0.5) - 1) : 0.8;
			Theme.glow(g, r.x, r.y, r.width, r.height, 27, mode == Mode.SAND ? Theme.SAND : Theme.ACCENT_2, strength);
		}
		g.setFont(Theme.font(21, true));
		g.setColor(Color.WHITE);
		Theme.centered(g, mode.start, r.getCenterX() - 22, r.y + 35);
		g.setFont(Theme.font(12, true));
		int kx = (int) r.getCenterX() + 42;
		Theme.card(g, kx, r.y + 16, 56, 22, 8, new Color(10, 12, 30, 110), new Color(255, 255, 255, 90));
		g.setColor(Color.WHITE);
		Theme.centered(g, "Enter", kx + 28, r.y + 32);
		g.setTransform(at);
		hits.add(new Hit(r, ROW_START, 0));
		if (foc) {
			focusTarget = r;
		}
	}

	/**
	 * 시작 단추 오른쪽의 알림({@link #notice}): 설정 화면의 알림과 같은 모양(옅은 색 알약, 점, 글)으로 오른쪽 끝을 내용의 오른쪽 끝에
	 * 맞추고, 끝의 {@value #NOTICE_FADE_MS}ms 에 옅어진다.
	 */
	private void paintNotice(Graphics2D g, long now) {
		if (!noticeShowing(now)) {
			return;
		}
		long left = NOTICE_MS - (now - noticeAt);
		float alpha = left < NOTICE_FADE_MS ? left / (float) NOTICE_FADE_MS : 1f;
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		g.setFont(Theme.font(14, true));
		int w = g.getFontMetrics().stringWidth(noticeText) + 48;
		int h = 30;
		int x = CONTENT_X + CONTENT_W - w;
		int y = START_Y + (54 - h) / 2; // 시작 단추(높이 54)와 가운데를 맞춘다
		Color c = Theme.P1;
		Theme.card(g, x, y, w, h, h, Theme.withAlpha(c, 34), Theme.withAlpha(c, 150));
		g.setColor(c);
		g.fill(new Ellipse2D.Double(x + 14, y + 11, 8, 8));
		g.setColor(Theme.TEXT);
		g.drawString(noticeText, x + 30, y + 20);
		g.setComposite(old);
	}

	/** 시작 단추 바탕의 그림을 다시 그려야 하는지 가리는 값들 (모드의 색, 화소 안의 위치, 배율, 그림 크기). */
	private record StartKey(Mode mode, double fx, double fy, double scale, int w, int h) {
	}

	/**
	 * 시작 단추의 바탕을 장치 화소로 그려 둔 그림으로 붙인다(프레임마다 그리는 큰 안티앨리어싱 도형을 줄인다). 그림은 같은 도형·칠·변환을
	 * 정수 화소만큼만 옮겨 그린 것이라 바로 그린 것과 같다(합성의 반올림만큼). 모드·창 크기가 바뀌면 다시 그린다. 회전 같은 변환이나 다른
	 * 합성이면 false (부르는 쪽이 바로 그린다).
	 */
	private boolean fillStartCached(Graphics2D g, RoundRectangle2D shape, GradientPaint paint) {
		AffineTransform t = g.getTransform();
		double s = t.getScaleX();
		if ((t.getType() & ~(AffineTransform.TYPE_TRANSLATION | AffineTransform.TYPE_UNIFORM_SCALE)) != 0 || !(s > 0)
				|| !(g.getComposite() instanceof AlphaComposite ac) || ac.getRule() != AlphaComposite.SRC_OVER) {
			return false;
		}
		double left = shape.getX() * s + t.getTranslateX();
		double top = shape.getY() * s + t.getTranslateY();
		int ox = (int) Math.floor(left) - 1;
		int oy = (int) Math.floor(top) - 1;
		int w = (int) Math.ceil((shape.getX() + shape.getWidth()) * s + t.getTranslateX()) + 1 - ox;
		int h = (int) Math.ceil((shape.getY() + shape.getHeight()) * s + t.getTranslateY()) + 1 - oy;
		if (w <= 0 || h <= 0 || w > 4096 || h > 4096) {
			return false;
		}
		StartKey key = new StartKey(mode, left - Math.floor(left), top - Math.floor(top), s, w, h);
		if (startSprite == null || !key.equals(startKey)) {
			BufferedImage img = Fx.sprite(g, w, h);
			Graphics2D sg = img.createGraphics();
			sg.setRenderingHints(g.getRenderingHints());
			AffineTransform shifted = AffineTransform.getTranslateInstance(-ox, -oy);
			shifted.concatenate(t);
			sg.setTransform(shifted);
			sg.setPaint(paint);
			sg.fill(shape);
			sg.dispose();
			startSprite = img;
			startKey = key;
		}
		g.setTransform(new AffineTransform());
		g.drawImage(startSprite, ox, oy, null);
		g.setTransform(t);
		return true;
	}

	/** 아래 키 안내의 머리말: 메뉴의 키가 아니라 판에서 쓰는 키라는 것. */
	static final String KEYS_HEAD = "게임 키";

	/**
	 * 아래 키 안내: 판에서 쓰는 지금 키 (설정 화면·도움말과 같은 이름, {@link KeyAction#label()}). 맨 앞의 머리말("게임 키")이 메뉴의
	 * 키가 아님을 알린다(메뉴에서 Esc 는 아무것도 하지 않는다).
	 */
	private void paintKeys(Graphics2D g, int y) {
		KeyBindings k = keyBindings();
		String[][] keys = { { k.describe(KeyAction.MOVE_LEFT, 1) + " " + k.describe(KeyAction.MOVE_RIGHT, 1), "이동" },
				{ k.describe(KeyAction.SOFT_DROP, 1), KeyAction.SOFT_DROP.label() },
				{ k.describe(KeyAction.HARD_DROP, 1), KeyAction.HARD_DROP.label() },
				{ k.describe(KeyAction.ROTATE_CW, 2), KeyAction.ROTATE_CW.label() },
				{ k.describe(KeyAction.ROTATE_CCW, 1), KeyAction.ROTATE_CCW.label() },
				{ k.describe(KeyAction.HOLD, 1), KeyAction.HOLD.label() },
				{ k.describe(KeyAction.PAUSE, 1), KeyAction.PAUSE.label() },
				{ k.describe(KeyAction.MENU, 1), KeyAction.MENU.label() } };
		// 바꾼 키 이름이 길면 화면 폭에 맞게 글자를 줄인다
		int px = 13;
		int total;
		while (true) {
			g.setFont(Theme.font(px, true));
			FontMetrics fm = g.getFontMetrics();
			total = fm.stringWidth(KEYS_HEAD) + 16 - 22;
			for (String[] kk : keys) {
				total += fm.stringWidth(kk[0]) + 16 + 6 + fm.stringWidth(kk[1]) + 22;
			}
			if (total <= W - 48 || px <= 10) {
				break;
			}
			px--;
		}
		FontMetrics fm = g.getFontMetrics();
		int x = (W - total) / 2;
		g.setColor(Theme.LABEL);
		g.drawString(KEYS_HEAD, x, y);
		x += fm.stringWidth(KEYS_HEAD) + 16;
		for (String[] kk : keys) {
			int kw = fm.stringWidth(kk[0]) + 16;
			Theme.card(g, x, y - 17, kw, 24, 8, Theme.SURFACE, Theme.BORDER);
			g.setColor(Theme.TEXT);
			g.drawString(kk[0], x + 8, y);
			x += kw + 6;
			g.setColor(Theme.TEXT_DIM);
			g.drawString(kk[1], x, y);
			x += fm.stringWidth(kk[1]) + 22;
		}
	}

	/**
	 * 아래 경고 줄: 모델 묶음을 읽지 못함, 고른 AI 의 모델을 쓸 수 없음(기준 AI 로 대신), 승률 모델을 쓸 수 없음, 설정 파일
	 * 문제. 모델 확인은 미리 불러 보기가 끝난 뒤에만 한다({@link #modelsChecked()}).
	 */
	private void paintStatus(Graphics2D g) {
		List<String> notes = new ArrayList<>();
		if (catalog.problem() != null) {
			notes.add("학습한 AI를 불러오지 못해 기준 AI로 둬요");
		} else if (modelsChecked && mode == Mode.VERSUS) {
			if (chosenModelBroken()) {
				notes.add("고른 AI의 모델을 쓸 수 없어 기준 AI로 둬요");
			}
			if (catalog.winRateProblem() != null) {
				notes.add("승률 예측 모델을 쓸 수 없어 승률 막대를 숨겨요");
			}
		}
		if (save != null && save.problem() != null) {
			notes.add(save.problem());
		}
		if (notes.isEmpty()) {
			return;
		}
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.WARN);
		g.drawString(String.join("   ·   ", notes), 24, H - 12);
	}

	/** 지금 고른 AI 의 모델을 쓸 수 없어 기준 AI 로 대신하게 되는가. 미리 불러 본 뒤에만 묻는다. */
	private boolean chosenModelBroken() {
		List<AiCatalog.Difficulty> ds = catalog.difficulties();
		return catalog.modelProblem(ds.get(difficulty), effectivePersona(difficulty, persona)) != null;
	}

	/**
	 * 초점 꺾쇠 (SPEC V13): 이번 그리기에서 초점이 있는 것({@link #focusTarget})의 네 귀퉁이. 자리가 바뀌면 150ms 동안
	 * bezier(0.2, 0, 0, 1) 로 옮겨 간다(효과 세기가 끔이면 바로).
	 */
	private void paintBrackets(Graphics2D g, long now) {
		Rectangle target = focusTarget;
		if (target == null) {
			return;
		}
		if (bracketTo == null || !bracketTo.equals(target)) {
			bracketFrom = bracketTo == null || effects() <= 0 ? target : bracketNow(now);
			bracketTo = new Rectangle(target);
			bracketAt = now;
			poke();
		}
		MenuDecor.brackets(g, bracketNow(now), Theme.ACCENT);
	}

	/** 지금 꺾쇠의 자리. */
	private Rectangle2D bracketNow(long now) {
		if (bracketFrom == null) {
			return bracketTo;
		}
		double e = Motion.move(Motion.progress(now, bracketAt, Motion.SHORT));
		Rectangle2D a = bracketFrom;
		Rectangle2D b = bracketTo;
		return new Rectangle2D.Double(Motion.lerp(a.getX(), b.getX(), e), Motion.lerp(a.getY(), b.getY(), e),
				Motion.lerp(a.getWidth(), b.getWidth(), e), Motion.lerp(a.getHeight(), b.getHeight(), e));
	}

	private static void label(Graphics2D g, String text, int x, int y, boolean focused) {
		g.setFont(Theme.font(15, true));
		g.setColor(focused ? Theme.TEXT : Theme.TEXT_DIM);
		g.drawString(text, x, y);
		if (focused) {
			g.setColor(Theme.ACCENT);
			g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(x - 12, y - 12, x - 12, y + 1);
			g.setStroke(new BasicStroke(1f));
		}
	}
}
