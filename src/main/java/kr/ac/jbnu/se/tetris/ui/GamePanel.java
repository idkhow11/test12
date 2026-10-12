package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Locale;

import javax.swing.JPanel;
import javax.swing.Timer;

import kr.ac.jbnu.se.tetris.audio.CountdownSounds;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.SoundCues;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyNames;

/**
 * 대전 화면 (docs/GAME.md 7장, runs/quality/SPEC.md V2·V4~V12): 사람 대 AI 와 혼자 연습. 보드, 고스트, 홀드, 미리보기 5개, 받을
 * 방해 줄 막대, 콤보·B2B, 통계, 예측 승률 막대와 흐름, 동작 글자, 카운트다운과 GO!, K.O., 결과 카드, 일시정지. 판 도중 AI 가 기준
 * AI 로 바뀌면 이름 옆 꼬리표와 아래 알림으로 알리고, 판이 오류로 멈추면 오류 카드를 띄운다. 멈춘 동안에는 보드를 가린다(사람 대
 * AI, 혼자 연습 모두: 기록이 남는 판이라 멈춰 놓고 더미를 살펴보는 이득이 없게, 모래 화면도 같다). 창이 포커스를 잃으면(다른 창,
 * 최소화) 창({@link TetrisApp})이 판을 멈춘다({@link GameSession#autoPause()}).
 * <p>
 * <b>그리기.</b> 화면 스레드에서 {@link GameSession#lock} 을 잡고 한다(진행 스레드는 판을 바꾸는 순간에만 잠그므로 오래 기다리지
 * 않는다). 보드 묶음은 {@link BattleBoard}, 효과는 {@link BattleFx}, 막대는 {@link BattleMeter}, 동작 글자는 {@link BattleLog}, 위
 * 띠와 이름표는 {@link BattleHud}, 덮개는 {@link BattleOverlay}, 결과 카드는 {@link BattleResult} 가 그린다. 효과는 판의 상태를
 * 읽기만 하고 판의 시계(끝난 뒤에는 세션 시계)와 상태 없는 난수로 계산하므로, 같은 판이면 언제나 같은 그림이 나온다. 효과 세기,
 * 화면 흔들림, 번쩍임 줄이기, 고스트, 색약 색상, 블록 무늬는 판을 시작할 때의 사용자 설정을 따른다({@link BattleStyle}).
 * <p>
 * <b>다시 그리기.</b> 15ms 타이머는 화면에 보이는 동안만 돈다(메뉴·설정·모래 화면에 있을 때, 창에 붙지 않은 시험·도구의 패널은
 * 돌지 않는다). 그림이 바뀔 때만 다시 그린다({@link #needsFrame()}): 끝난 판은 결과 카드가 뜨고 {@link #SETTLE_MS} 뒤, 멈춘 판은
 * 일시정지 카드가 다 나타난 뒤, 오류 카드는 바로 그림이 멈춘다. 판이 끝나면 위험 표시(빨간 빛, 막대의 큰 숫자, 리셋 테두리)도
 * 숨 쉬기를 멈춘다.
 * <p>
 * <b>키.</b> 판의 {@link HumanInput} 이 사용자 설정의 키 묶음으로 동작으로 바꾼다(자동 반복 무시). 블록 조작은 HumanInput 으로,
 * 일시정지·메뉴·결과 카드의 고르기는 여기서 처리한다. 카운트다운과 일시정지 동안 누른 조작은 버리고, 그동안 누르고 있던 이동은 블록
 * 조작을 다시 받기 시작하는 순간 넘긴다({@link HumanInput#resumeHeld()}). 결과 카드는 이동 키로 고르고 확인 키(Enter)로 정한다.
 * 다시 하기의 다른 키(R)는 언제나 "다시 하기", 메뉴 키(Esc)는 언제나 "메뉴로"다({@link BattleResult#confirmKey}). 마우스로 눌러도
 * 된다.
 * <p>
 * <b>소리</b> ({@link BattleSounds}): 일시정지·계속, 카운트다운, 사람 보드가 위험할 때의 심장 소리(설정이 켜져 있을 때), 기권
 * 확인, 결과 카드의 고르기와 떠나기. 떠날 때는 창이 판을 먼저 멈춘 뒤 앞 판의 소리를 끝낸다. 판의 진행 소리는
 * {@link GameSession} 이 낸다.
 */
final class GamePanel extends JPanel {

	private static final long serialVersionUID = 1L;

	/** 화면에서 할 일 (TetrisApp 이 정한다). 모래 화면도 같이 쓴다. */
	interface Actions {
		/**
		 * 한 판 더. {@code sameSeed} 면 같은 시드·같은 설정으로("같은 판 다시"), 아니면 같은 설정에 새 시드로("다시 하기").
		 */
		void restart(boolean sameSeed);

		/** 메뉴로. 진행 중인 판은 창이 기권으로 끝낸다({@link GameSession#forfeit()}). */
		void menu();
	}

	private static final int ROWS = BattleGeo.ROWS;
	/** 플레이어 하나의 너비 (칸): 홀드 4.4 + 막대 자리 1.4 + 보드 10 + 0.6 + 미리보기 4.4. */
	private static final double PLAYER_W = 20.8;
	private static final double GAP_W = 3.4;
	/** 위: 시계·중력·승률 3.4 칸 + 이름 0.5 칸 + 숨김 영역 2.3 칸. 아래: 통계 3.2 칸. */
	private static final double TOP_H = 6.2;
	private static final double BOTTOM_H = 3.2;
	/** 이 높이(칸) 이상 쌓이면 보드 테두리가 빨갛게 숨 쉰다. */
	private static final int DANGER_HEIGHT = 15;
	/** 대전에서 Esc 를 한 번 누른 뒤 이 안에 한 번 더 누르면 기권 (ns). */
	private static final long ESC_WINDOW_NS = 2_000_000_000L;
	/** 일시정지 카드가 나타나는 시간과 사라지는 시간 (ms, SPEC V11). */
	static final int PAUSE_IN_MS = 200;
	static final int PAUSE_OUT_MS = 150;
	/**
	 * 끝난 판의 그림이 멈추는 때: 결과 카드가 뜬 뒤 이만큼 (ms). 그때까지 K.O.(1.2초), 이긴 보드의 금빛(1초), 결과 카드의 숫자
	 * (0.8초), 새 기록 꼬리표(0.44초), 승률 막대 따라가기(τ 180ms)가 모두 끝난다.
	 */
	static final int SETTLE_MS = 1500;
	/** 멈춘 판의 그림이 멈추는 때: 일시정지 카드가 다 나타나고 조금 뒤 (ms). */
	private static final int PAUSE_SETTLE_MS = PAUSE_IN_MS + 50;

	private final transient Actions actions;
	private final transient BattleSounds sounds;
	private final transient CountdownSounds countdownSounds = new CountdownSounds();
	private final transient BattleHud hud = new BattleHud();
	private transient GameSession session;
	private transient BattleStyle style;
	private boolean escArmed; // 대전에서 Esc 를 한 번 눌렀다 (기권 확인)
	private long escAtNanos; // 그때의 세션 시계
	private boolean accepting; // 지난번에 본 "블록 조작을 받는 중" (카운트다운이 끝나는 순간을 알아챈다)
	private boolean pausedSeen; // 지난번에 본 일시정지 상태와 그것이 바뀐 세션 시계 (카드의 움직임)
	private long pauseFlipNanos;
	private int choice = BattleResult.RETRY; // 결과 카드에서 고른 단추
	private final Rectangle2D.Double[] choiceHits = new Rectangle2D.Double[BattleResult.CHOICES.length];
	private boolean choicesShown; // 결과 카드의 단추가 지금 화면에 있다 (마우스)
	private final double[] meterTop = new double[2]; // 보드마다 받을 줄 막대의 보이는 맨 위 (날아오는 "+N" 이 닿을 곳)
	/** 다시 그리기 타이머: 화면에 보이는 동안만 돈다. */
	private final transient Timer timer = new Timer(15, e -> tick());
	// 지난번에 그린 그림의 판과 그때의 상태 (그림이 바뀌었는지 정한다, needsFrame)
	private transient GameSession paintedFor;
	private boolean paintedSettled;
	private boolean paintedPaused;
	private boolean paintedOver;
	private boolean paintedFailed;
	private boolean paintedBest;

	GamePanel(Actions actions) {
		this(actions, BattleSounds.engine());
	}

	/** 소리를 바꿔 넣는다 (시험은 요청을 적어 두는 것을 넘긴다). */
	GamePanel(Actions actions, BattleSounds sounds) {
		this.actions = actions;
		this.sounds = sounds;
		setFocusable(true);
		setFocusTraversalKeysEnabled(false);
		enableInputMethods(false); // 한/영이 '한'이어도 Z·X·C·P 가 입력기에 먹히지 않게
		setPreferredSize(new Dimension(1280, 720));
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				onKey(e.getKeyCode(), true);
			}

			@Override
			public void keyReleased(KeyEvent e) {
				onKey(e.getKeyCode(), false);
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				hover(e.getX(), e.getY());
			}

			@Override
			public void mousePressed(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1) {
					click(e.getX(), e.getY());
				}
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				GameSession s = session;
				if (s != null) {
					s.human.releaseAll(); // 저절로 멈추는 것은 창(TetrisApp)이 정한다: F11 전환으로 창을 다시 만들 때는 멈추지 않게
				}
			}
		});
		// 보이는 동안만 돈다: 메뉴·설정·모래 화면에 있는 동안은 쉬고, 창에 붙지 않은 패널(시험, 화면 확인 도구)은 켜지 않는다
		// (그런 패널은 부른 쪽이 직접 handOff 하고 그린다). 그래서 숨은 패널이 판을 붙잡거나 화면 스레드를 깨우지 않는다.
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				if (isShowing()) {
					timer.start();
				} else {
					timer.stop();
				}
			}
		});
	}

	/** 15ms 마다 (보이는 동안): 누르고 있던 이동을 넘기고, 그림이 바뀔 때만 다시 그린다. */
	private void tick() {
		handOff();
		if (needsFrame()) {
			repaint();
		}
	}

	/** 다시 그리기 타이머가 도는가 (화면에 보이는 동안). 시험용. */
	boolean ticking() {
		return timer.isRunning();
	}

	/**
	 * 다시 그려야 하는가: 판이 진행 중이거나(카운트다운, 알림 포함) 움직임이 남았거나, 지난번에 그린 뒤 판의 상태(멈춤, 끝,
	 * 오류, 새 기록)가 바뀌었다. 끝난 판은 결과 카드가 뜨고 {@link #SETTLE_MS} 뒤, 멈춘 판은 일시정지 카드가 다 나타난 뒤, 오류
	 * 카드는 바로 그림이 멈춘다. 결과 카드의 고르기(키, 마우스)와 판 바꾸기는 그 자리에서 다시 그리고, 창 크기가 바뀌면 Swing 이
	 * 다시 그린다. 판의 소리(카운트다운, 심장 소리)는 그리기에서 나지만 그때는 언제나 진행 중이라 그림이 멈추지 않는다.
	 */
	boolean needsFrame() {
		GameSession s = session;
		return s != null && (s != paintedFor || !paintedSettled || s.paused() != paintedPaused || s.over() != paintedOver
				|| (s.failure() != null) != paintedFailed || s.newBest() != paintedBest);
	}

	void setSession(GameSession s) {
		this.session = s;
		this.style = s == null ? null : BattleStyle.of(s);
		this.escArmed = false;
		this.accepting = false;
		this.choice = BattleResult.RETRY;
		this.choicesShown = false;
		this.paintedFor = null;
		countdownSounds.reset();
		if (s != null) {
			pausedSeen = s.paused();
			pauseFlipNanos = s.clockNanos() - 10_000_000_000L; // 이미 멈춘 판이면 카드를 다 보인다
		}
		repaint();
	}

	/**
	 * 블록 조작을 다시 받기 시작하는 순간(시작 카운트다운이 끝남, 일시정지를 푼 뒤의 카운트다운이 끝남)에 누르고 있던 이동을
	 * 넘긴다. 15ms 다시 그리기 타이머가 부른다(화면 스레드). 시험은 직접 부른다.
	 */
	void handOff() {
		GameSession s = session;
		boolean now = s != null && accepts(s);
		if (now && !accepting) {
			s.human.resumeHeld();
		}
		accepting = now;
	}

	/** 지금 블록 조작을 받는가: 멈춤도, 카운트다운도, 끝난 판도 아니다. */
	private static boolean accepts(GameSession s) {
		return !s.paused() && !s.over() && s.countdown() == 0;
	}

	// ------------------------------------------------------------------ 키와 마우스

	/** 키 하나 (화면 스레드). 시험은 직접 부른다. */
	void onKey(int code, boolean down) {
		GameSession s = session;
		if (s == null) {
			return;
		}
		KeyAction a = down ? s.human.keyPressed(code) : s.human.keyReleased(code);
		if (a == null) {
			return; // 자동 반복, 묶이지 않은 키, 같은 동작의 다른 키
		}
		if (s.over()) { // 끝났거나 오류로 멈춘 판
			if (down) {
				resultKey(s, a, code);
			}
			return;
		}
		if (down && a == KeyAction.MENU) {
			// 사람 대 AI 는 2초 안에 한 번 더 누르면 기권하고 메뉴로. 혼자 연습과 멈춘 판은 바로 메뉴로 (GAME.md 4장)
			if (!s.settings.versusAi() || s.paused() || escPending(s)) {
				escArmed = false;
				// 창이 판을 먼저 멈추고(기권, 진행 스레드가 끝나기를 기다린다) 그 뒤에 소리를 끈다: 그 사이에 판이 낸 소리(늦춘 방해
				// 줄 경고, 화면을 그림으로 뜨며 낸 심장 소리)까지 메뉴에서 울리지 않게
				actions.menu();
				sounds.leave(Sfx.MENU_BACK);
			} else {
				escArmed = true;
				escAtNanos = s.clockNanos();
				sounds.play(Sfx.DENY);
			}
			return;
		}
		if (down && a == KeyAction.PAUSE) {
			boolean was = s.paused();
			s.togglePause();
			if (s.paused() != was) {
				sounds.play(s.paused() ? Sfx.PAUSE : Sfx.RESUME);
				pausedSeen = s.paused();
				pauseFlipNanos = s.clockNanos();
			}
			return;
		}
		HumanInput.Key k = HumanInput.keyOf(a);
		if (k == null) {
			return; // 다시 하기·전체 화면은 판 도중에 쓰지 않는다
		}
		if (!down) {
			s.human.release(k);
		} else if (accepts(s)) {
			s.human.press(k); // 멈춘 동안과 카운트다운 동안 누른 키는 버린다(누르고 있으면 다시 받을 때 넘긴다, handOff)
		}
	}

	/**
	 * 끝난 판의 키 ({@code code} 는 누른 키). 오류 카드는 다시 하기·메뉴로. 결과 카드는 이동 키로 고르고, 확인 키(다시 하기에 묶인
	 * Enter)로 고른 단추를 정한다. 다시 하기의 다른 키(R)는 고른 단추와 상관없이 "다시 하기", 메뉴 키는 "메뉴로".
	 */
	private void resultKey(GameSession s, KeyAction a, int code) {
		if (s.failure() != null) {
			if (a == KeyAction.RETRY) {
				choose(BattleResult.RETRY);
			} else if (a == KeyAction.MENU) {
				choose(BattleResult.MENU);
			}
			return;
		}
		if (!resultAccepts(s)) {
			return; // K.O. 를 보이는 동안: 판 끝에 누르던 키로 넘어가지 않게
		}
		switch (a) {
		case MOVE_LEFT -> moveChoice(-1);
		case MOVE_RIGHT -> moveChoice(1);
		case RETRY -> choose(code == BattleResult.confirmKey(s.human.keys()) ? choice : BattleResult.RETRY);
		case MENU -> choose(BattleResult.MENU);
		default -> {
			// 다른 키는 결과 카드에서 쓰지 않는다
		}
		}
	}

	/** 결과 카드가 키와 마우스를 받는가 (진 판은 끝난 뒤 1.15초부터, 기권한 판은 바로, 오류 카드는 아니다). */
	boolean resultReady() {
		GameSession s = session;
		return s != null && s.over() && s.failure() == null && resultAccepts(s);
	}

	/** 결과 카드가 키를 받는가 (진 판은 끝난 뒤 1.15초부터, 기권한 판은 바로). */
	private static boolean resultAccepts(GameSession s) {
		synchronized (s.lock) {
			long over = BattleFx.overMs(s);
			return over >= 0 && over >= BattleResult.keysAt(s);
		}
	}

	private void moveChoice(int d) {
		int next = Math.max(0, Math.min(BattleResult.CHOICES.length - 1, choice + d));
		if (next == choice) {
			sounds.play(Sfx.DENY); // 끝이라 움직이지 않는다
			return;
		}
		choice = next;
		sounds.play(Sfx.MENU_MOVE);
		repaint();
	}

	/**
	 * 결과 카드의 단추 하나를 정한다: 다시 하기, 같은 판 다시, 메뉴로. 떠난 뒤(창이 앞 판을 멈추고 새 판이나 메뉴로 바꾼 뒤)에 앞
	 * 판의 소리를 끝내고 고른 소리를 낸다. 새 판의 카운트다운 소리는 그 뒤 첫 그림에서 나므로 끊기지 않는다.
	 */
	private void choose(int k) {
		switch (k) {
		case BattleResult.RETRY -> {
			actions.restart(false);
			sounds.leave(Sfx.MENU_SELECT);
		}
		case BattleResult.SAME -> {
			actions.restart(true);
			sounds.leave(Sfx.MENU_SELECT);
		}
		default -> {
			actions.menu();
			sounds.leave(Sfx.MENU_BACK);
		}
		}
	}

	/** 마우스가 결과 카드의 단추 위로: 그 단추를 고른다 (결과 카드가 키를 받기 시작한 뒤, K.O. 를 보이는 동안은 아니다). */
	void hover(int x, int y) {
		int k = choiceAt(x, y);
		if (k >= 0 && k != choice && resultReady()) {
			choice = k;
			sounds.play(Sfx.MENU_MOVE);
			repaint();
		}
	}

	/** 마우스로 누름: 결과 카드의 단추면 그것을 정한다 (키를 받기 시작한 뒤). 시험은 직접 부른다. */
	void click(int x, int y) {
		int k = choiceAt(x, y);
		if (k < 0 || !resultReady()) {
			return;
		}
		choice = k;
		choose(k);
	}

	/** (x, y) 에 있는 결과 카드의 단추, 없으면 -1. */
	private int choiceAt(double x, double y) {
		if (!choicesShown) {
			return -1;
		}
		for (int k = 0; k < choiceHits.length; k++) {
			if (choiceHits[k] != null && choiceHits[k].contains(x, y)) {
				return k;
			}
		}
		return -1;
	}

	/** 결과 카드에서 고른 단추 ({@link BattleResult#RETRY}, {@link BattleResult#SAME}, {@link BattleResult#MENU}). */
	int choice() {
		return choice;
	}

	/** 결과 카드 단추 {@code k} 의 자리 (마지막으로 그린 결과 카드, 패널 좌표). 결과 카드가 없으면 null. 시험용. */
	Rectangle2D choiceBounds(int k) {
		return choicesShown && choiceHits[k] != null ? (Rectangle2D) choiceHits[k].clone() : null;
	}

	/** 2초 안에 Esc 를 한 번 눌러 둔 상태인가 (세션 시계). */
	private boolean escPending(GameSession s) {
		return escArmed && s.clockNanos() - escAtNanos < ESC_WINDOW_NS;
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
				// 그리기 전의 상태를 적어 둔다: 그리는 사이에 바뀌면 다음 틱이 다르다고 보고 다시 그린다
				long clock = s.clockNanos();
				boolean paused = s.paused();
				boolean over = s.over();
				boolean failed = s.failure() != null;
				boolean best = s.newBest();
				paintSession(g, s);
				paintedFor = s;
				paintedPaused = paused;
				paintedOver = over;
				paintedFailed = failed;
				paintedBest = best;
				paintedSettled = settled(s, clock, paused, over, failed);
			}
		}
		g.dispose();
	}

	/**
	 * 방금 그린 그림이 더 바뀌지 않는가 ({@code clock} 은 그리기 전의 세션 시계): 오류 카드, 결과 카드가 뜨고
	 * {@link #SETTLE_MS} 가 지난 끝난 판, 일시정지 카드가 다 나타난 멈춘 판.
	 */
	private boolean settled(GameSession s, long clock, boolean paused, boolean over, boolean failed) {
		if (failed) {
			return true;
		}
		if (over) {
			long at = s.overAtNanos();
			return at != GameSession.NOT_OVER && (clock - at) / 1_000_000 >= BattleResult.cardAt(s) + SETTLE_MS;
		}
		return paused && (clock - pauseFlipNanos) / 1_000_000 >= PAUSE_SETTLE_MS;
	}

	/** 화면 크기에서 정한 자리: 칸 크기, 첫 플레이어의 왼쪽, 위 띠의 위, 보드의 위. */
	private record Layout(int c, int left, int y0, int top) {
	}

	private Layout layout(int players) {
		double unitsWide = players * PLAYER_W + (players - 1) * GAP_W + 2;
		double unitsTall = TOP_H + ROWS + BOTTOM_H;
		int c = (int) Math.max(8, Math.min(getWidth() / unitsWide, getHeight() / unitsTall));
		int left = (getWidth() - (int) Math.round(unitsWide * c)) / 2 + c;
		int y0 = (getHeight() - (int) Math.round(unitsTall * c)) / 2;
		return new Layout(c, left, y0, y0 + (int) Math.round(TOP_H * c));
	}

	private BattleGeo geo(Layout l, int i) {
		return BattleGeo.of(l.left() + (int) Math.round(i * (PLAYER_W + GAP_W) * l.c()), l.top(), l.c());
	}

	/** 지금 크기에서 플레이어 i 의 자리 (판이 없으면 null). 시험이 보드의 화소를 볼 때 쓴다. */
	BattleGeo geoOf(int i) {
		GameSession s = session;
		return s == null ? null : geo(layout(s.match.size()), i);
	}

	private void paintSession(Graphics2D g, GameSession s) {
		BattleStyle st = style != null ? style : BattleStyle.of(s);
		TimedMatch m = s.match;
		int players = m.size();
		Layout l = layout(players);
		int c = l.c();
		int y0 = l.y0();
		long now = BattleFx.now(s);
		long overMs = BattleFx.overMs(s);
		int countdown = s.countdown();
		sounds.play(countdownSounds.update(countdown, !s.paused() && !s.over()));
		if (s.paused() != pausedSeen) { // 창이 저절로 멈춘 것도 알아챈다
			pausedSeen = s.paused();
			pauseFlipNanos = s.clockNanos();
		}

		BattleGeo[] geos = new BattleGeo[players];
		for (int i = 0; i < players; i++) {
			geos[i] = geo(l, i);
			paintPlayer(g, s, i, geos[i], now, overMs, st, countdown);
		}
		if (players == 2 && !s.over()) {
			paintAttackChips(g, s, geos, now);
		}
		hud.paint(g, s, getWidth(), c, y0, now);
		paintOverlays(g, s, c, overMs, st);
	}

	/** 카운트다운·GO!, 오류 카드, 결과 카드, 일시정지 카드, 아래쪽 알림. */
	private void paintOverlays(Graphics2D g, GameSession s, int c, long overMs, BattleStyle st) {
		int w = getWidth();
		int h = getHeight();
		long left = s.countdownMs();
		if (left > 0) {
			BattleOverlay.countdown(g, w, h, c, left, left <= 1 && s.aiLoading());
		} else {
			long go = s.goAgeMs();
			if (go >= 0) {
				BattleOverlay.go(g, w, h, c, go, st);
			}
		}
		choicesShown = false;
		if (s.failure() != null) {
			BattleOverlay.failure(g, w, h, c, s.human.keys(), s.failure());
			return;
		}
		if (s.over()) {
			long ms = overMs - BattleResult.cardAt(s);
			if (overMs >= 0 && ms >= 0) {
				BattleResult.paint(g, s, w, h, c, ms, choice, choiceHits, st);
				choicesShown = true;
			}
			return;
		}
		long since = (s.clockNanos() - pauseFlipNanos) / 1_000_000;
		if (s.paused()) {
			// 멈춘 동안에는 보드를 가린다: 사람 대 AI 도 혼자 연습도 기록이 남는 판이라 멈춰 놓고 더미를 살펴보는 이득이 없게
			// (GameSession.togglePause, 모래 화면도 같다)
			Theme.backgroundCached(g, w, h);
			double in = Motion.enter(Motion.clamp01(since / (double) PAUSE_IN_MS));
			BattleOverlay.pause(g, w, h, c, s.human.keys(), s.settings.versusAi(), in,
					st.m() > 0 ? 0.96 + 0.04 * in : 1);
			return;
		}
		if (since >= 0 && since < PAUSE_OUT_MS) { // 막 풀었다: 카드가 사라진다 (다시 가기 카운트다운과 겹친다)
			BattleOverlay.pause(g, w, h, c, s.human.keys(), s.settings.versusAi(),
					1 - Motion.exit(since / (double) PAUSE_OUT_MS), 1);
			return;
		}
		if (escPending(s)) {
			String esc = KeyNames.phrase(s.human.keys().keys(KeyAction.MENU).get(0));
			BattleOverlay.toast(g, w, h, c, esc + "를 한 번 더 누르면 기권하고 메뉴로 가요", Theme.withAlpha(Theme.DANGER, 225),
					Color.WHITE, (s.clockNanos() - escAtNanos) / 1_000_000, ESC_WINDOW_NS / 1_000_000);
			return;
		}
		paintAiNotice(g, s, c);
	}

	/**
	 * AI 모델 알림: 판 도중 기준 AI 로 바뀌면 아래쪽 알림({@link GameSession#NOTICE_MS} 동안), 그 밖에 기준 AI 가 두는 판이면
	 * 왼쪽 아래에 짧은 한 줄 (모델을 불러오지 못함, 판 도중 바뀜). 자세한 이유는 콘솔에 남는다.
	 */
	private void paintAiNotice(Graphics2D g, GameSession s, int c) {
		for (int i = 0; i < s.match.size(); i++) {
			if (s.notice(i)) {
				BattleOverlay.toast(g, getWidth(), getHeight(), c, s.aiStatus().reason(), Theme.withAlpha(Theme.WARN, 235),
						Theme.BG_TOP, Motion.LONG, 0);
				return;
			}
		}
		GameSession.AiStatus ai = s.aiStatus();
		if (ai.fallback()) {
			g.setColor(Theme.WARN);
			Theme.Type.BODY.draw(g, ai.reason(), Math.max(11, c * 0.42), c, getHeight() - c / 3.0, Theme.Align.LEFT);
		}
	}

	// ------------------------------------------------------------------ 플레이어 한 명

	private void paintPlayer(Graphics2D g, GameSession s, int i, BattleGeo geo, long now, long overMs, BattleStyle st,
			int countdown) {
		TimedPlayer p = s.match.player(i);
		GameModel game = p.game();
		GameSession.Tally t = s.tallies[i];
		Color pc = i == 0 ? Theme.P1 : Theme.P2;
		int c = geo.c();
		int pad = (int) geo.pad();
		long winMs = winner(s) == i ? Math.max(0, overMs) : -1;
		BattleHud.name(g, s, i, geo.boardX() - pad, geo.top() - (int) Math.round(2.45 * c), c, pc, winMs >= 0);

		// 위험: 쌓인 높이에 곧 올라올 방해 줄(한 번에 최대 8줄)을 더해 본다. 판이 끝나면 위험도 끝난다(빨간 빛이 숨 쉬지 않는다)
		int fill = maxHeight(game) + Math.min(game.pendingLines(), 8);
		boolean danger = !s.over() && !game.isOver() && fill >= DANGER_HEIGHT;
		if (danger && s.seat(i) == null && !s.paused() && countdown == 0 && s.user.heartbeat()) {
			sounds.play(SoundCues.danger(fill)); // 사람의 보드만. 쿨다운이 심장 박자를 맞춘다
		}
		long koMs = game.isOver() ? (overMs >= 0 ? overMs : Long.MAX_VALUE / 4) : -1;

		// 보드 묶음: 흔들림과 튀는 반동으로 함께 움직인다 (HUD 는 움직이지 않는다)
		int dx = 0;
		int dy = 0;
		if (st.shakes(i)) {
			double tr = BattleFx.trauma(s, i, now, koMs >= 0 ? now - koMs : Long.MIN_VALUE);
			if (tr > 0) {
				dx = (int) Math.round(BattleFx.shakeX(s, i, now, tr, c, st.m()));
				dy = (int) Math.round(BattleFx.shakeY(s, i, now, tr, c, st.m()));
			}
			dy += BattleFx.bump(s, i, now, c, st.m());
		}
		g.translate(dx, dy);
		BattleBoard.paint(g, s, i, geo, now, koMs, winMs, st, danger);
		g.translate(-dx, -dy);

		BattleFx.edgeFlares(g, s, i, geo, now, st);
		meterTop[i] = BattleMeter.paint(g, s, i, geo, now, st);
		if (danger) { // 보드 왼쪽 바깥(받을 줄 막대 위)에 둔다: 막 나온 블록과 겹치지 않게
			double fpx = Math.max(12, 0.42 * c);
			g.setFont(Theme.font(fpx, true));
			int cw = g.getFontMetrics().stringWidth("위험") + (int) Math.round(fpx * 1.2);
			Theme.chip(g, "위험", geo.boardX() - (int) Math.round(0.3 * c) - cw, geo.top() - (int) Math.round(0.55 * c),
					fpx, Color.WHITE, Theme.withAlpha(Theme.DANGER, 215));
		}
		ColorPalettes.Look look = st.look();
		paintHold(g, game, geo, look);
		paintNext(g, game.preview(), geo, look);
		paintInfo(g, game, t, geo.nextX(), geo.top() + (int) Math.round(14.9 * c), geo.cardW(),
				(int) Math.round(5.1 * c), c);
		paintStats(g, game, t, geo.boardX() - pad, geo.top() + geo.boardH() + (int) Math.round(0.45 * c),
				geo.boardW() + 2 * pad, (int) Math.round(2.3 * c), c, s.match.time());
		if (!s.over()) {
			BattleLog.paint(g, s, i, geo, now, st, pc);
			BattleLog.perfect(g, t, geo, now);
		}
	}

	/** 이긴 보드 (사람 대 AI 에서 승부가 났을 때), 아니면 -1. 무승부와 오류에는 없다. */
	private static int winner(GameSession s) {
		if (s.match.size() < 2 || s.failure() != null) {
			return -1;
		}
		return switch (s.result()) {
		case P1 -> 0;
		case P2 -> 1;
		default -> -1;
		};
	}

	private static void paintHold(Graphics2D g, GameModel game, BattleGeo geo, ColorPalettes.Look look) {
		int c = geo.c();
		int x = geo.x0();
		int top = geo.top();
		Theme.card(g, x, top, geo.cardW(), 3.4 * c, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		cardTitle(g, "HOLD", x, top, c);
		if (game.hold() != null) {
			mini(g, look, game.hold(), x, top + 0.95 * c, geo.cardW(), 2.2 * c, 0.78 * c, game.holdUsed());
		}
	}

	/** 미리보기: 바로 다음 블록은 크게, 나머지 넷은 작게. */
	private static void paintNext(Graphics2D g, List<PieceType> preview, BattleGeo geo, ColorPalettes.Look look) {
		int c = geo.c();
		int x = geo.nextX();
		int top = geo.top();
		int w = geo.cardW();
		Theme.card(g, x, top, w, 14.4 * c, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		cardTitle(g, "NEXT", x, top, c);
		for (int k = 0; k < preview.size(); k++) {
			if (k == 0) {
				mini(g, look, preview.get(0), x, top + 0.95 * c, w, 2.6 * c, 0.86 * c, false);
			} else {
				mini(g, look, preview.get(k), x, top + 3.75 * c + (k - 1) * 2.6 * c, w, 2.4 * c, 0.62 * c, false);
			}
		}
	}

	/** 미리보기 아래: 지금 콤보, B2B, 테트리스와 최대 콤보. */
	private static void paintInfo(Graphics2D g, GameModel game, GameSession.Tally t, int x, int y, int w, int h, int c) {
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		double padX = 0.4 * c;
		g.setColor(Theme.LABEL);
		Theme.Type.LABEL.draw(g, "콤보", 0.5 * c, x + padX, y + 0.78 * c, Theme.Align.LEFT);
		if (game.combo() >= 1) {
			g.setColor(Theme.GOLD);
			g.setFont(Theme.display(1.3 * c));
			Theme.drawNumber(g, String.valueOf(game.combo()), x + padX, y + 2.1 * c, Theme.Align.LEFT);
		} else { // 자리 표시 "–" 는 숫자보다 가늘게
			g.setColor(Theme.TEXT_FAINT);
			g.setFont(Theme.numeric(0.9 * c));
			Theme.text(g, "–", x + padX, y + 1.95 * c, Theme.Align.LEFT);
		}
		boolean b2b = game.b2b();
		double chipW = 1.55 * c;
		double chipX = x + w - padX - chipW;
		Theme.card(g, chipX, y + 1.0 * c, chipW, 0.72 * c, 0.72 * c, b2b ? Theme.withAlpha(Theme.WARN, 230) : Theme.BORDER,
				null);
		g.setColor(b2b ? Theme.BG_TOP : Theme.TEXT_DIM);
		Theme.Type.LABEL.draw(g, "B2B", 0.46 * c, chipX + chipW / 2, y + 1.53 * c, Theme.Align.CENTER);
		row(g, "테트리스", String.valueOf(t.tetrises), x + padX, x + w - padX, y + 3.1 * c, c);
		row(g, "최대 콤보", String.valueOf(Math.max(0, t.maxCombo)), x + padX, x + w - padX, y + 4.25 * c, c);
	}

	private static void row(Graphics2D g, String label, String value, double x, double right, double baseline, int c) {
		g.setColor(Theme.LABEL);
		Theme.Type.LABEL.draw(g, label, 0.5 * c, x, baseline, Theme.Align.LEFT);
		g.setColor(Theme.TEXT);
		Theme.Type.NUMERIC.draw(g, value, 0.62 * c, right, baseline, Theme.Align.RIGHT);
	}

	/** 보드 아래 통계: 초당 블록(PPS), 공격, 보냄, APP, T-spin. 라벨 0.5c, 값 1.0c 고정 폭 숫자 (SPEC V2). */
	private static void paintStats(Graphics2D g, GameModel game, GameSession.Tally t, int x, int y, int w, int h, int c,
			long timeMs) {
		Theme.card(g, x, y, w, h, 0.5 * c, Theme.SURFACE, Theme.BORDER);
		int pieces = game.pieces();
		String pps = String.format(Locale.ROOT, "%.2f", pieces / Math.max(1.0, timeMs / 1000.0));
		String app = String.format(Locale.ROOT, "%.2f", pieces == 0 ? 0 : (double) game.attackTotal() / pieces);
		String[][] cols = { { "PPS", pps }, { "공격", String.valueOf(game.attackTotal()) },
				{ "보냄", String.valueOf(game.sentTotal()) }, { "APP", app }, { "T-SPIN", String.valueOf(t.tspins) } };
		double colW = w / (double) cols.length;
		for (int k = 0; k < cols.length; k++) {
			double cx = x + colW * (k + 0.5);
			g.setColor(Theme.LABEL);
			Theme.Type.LABEL.draw(g, cols[k][0], 0.5 * c, cx, y + 0.72 * c, Theme.Align.CENTER);
			g.setColor(Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, cols[k][1], 1.0 * c, cx, y + 1.92 * c, Theme.Align.CENTER);
		}
	}

	/**
	 * 보낸 공격이 상대의 받을 줄 막대로 날아가는 "+N" (0.45초). 일부만 보냈으면(상쇄) 보낸 만큼만 날아간다. 닿으면 막대의 새
	 * 조각이 자라며 빛난다({@link BattleMeter}).
	 */
	private void paintAttackChips(Graphics2D g, GameSession s, BattleGeo[] geos, long now) {
		for (int i = 0; i < 2; i++) {
			GameSession.Tally t = s.tallies[i];
			long age = now - t.sentAt;
			if (t.sentLines <= 0 || age < 0 || age >= SoundCues.ATTACK_FLIGHT_MS) {
				continue;
			}
			BattleGeo from = geos[i];
			BattleGeo to = geos[1 - i];
			int c = from.c();
			Color pc = i == 0 ? Theme.P1 : Theme.P2;
			double sx = i == 0 ? from.boardX() + from.boardW() + 0.4 * c : from.boardX() - 0.4 * c;
			double sy = from.top() + from.boardH() * 0.55;
			double tx = to.meterX() + to.meterW() / 2.0;
			double ty = meterTop[1 - i];
			double u = age / (double) SoundCues.ATTACK_FLIGHT_MS;
			String text = "+" + t.sentLines;
			g.setFont(Theme.display(0.6 * c));
			FontMetrics fm = g.getFontMetrics();
			double w = Theme.textWidth(g, text) + 0.6 * c;
			double h = fm.getAscent() * 0.75 + 0.42 * c;
			for (int k = 2; k >= 0; k--) { // 꼬리 둘과 본체
				double e = Motion.easeOutCubic(Math.max(0, u - k * 0.08));
				double cx = (1 - e) * (1 - e) * sx + 2 * (1 - e) * e * (sx + tx) / 2 + e * e * tx;
				double cy = (1 - e) * (1 - e) * sy + 2 * (1 - e) * e * (Math.min(sy, ty) - 3 * c) + e * e * ty;
				Theme.card(g, cx - w / 2, cy - h / 2, w, h, h, k == 0 ? pc : Fx.alpha(pc, 0.47 / k), null);
				if (k == 0) {
					g.setColor(Theme.BG_TOP);
					Theme.text(g, text, cx, cy + 0.21 * c, Theme.Align.CENTER);
				}
			}
		}
	}

	private static void cardTitle(Graphics2D g, String title, int x, int top, int c) {
		g.setColor(Theme.LABEL);
		g.setFont(Theme.tracked(Theme.numeric(0.5 * c), 0.12));
		Theme.text(g, title, x + 0.4 * c, top + 0.72 * c, Theme.Align.LEFT);
	}

	/** 상자 가운데에 블록 하나를 그린다(홀드, 미리보기). */
	private static void mini(Graphics2D g, ColorPalettes.Look look, PieceType type, double boxX, double boxY,
			double boxW, double boxH, double size, boolean grey) {
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
			look.block(g, (int) Math.round(ox + (cell[1] - minC) * s), (int) Math.round(oy + (cell[0] - minR) * s), s,
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
}
