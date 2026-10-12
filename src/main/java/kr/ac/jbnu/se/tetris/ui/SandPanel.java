package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.swing.JPanel;
import javax.swing.Timer;

import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.CountdownSounds;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.SoundCues;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.LockEvent;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.sand.SandSnapshot;
import kr.ac.jbnu.se.tetris.sand.SandView;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 모래 모드 화면 (sand 패키지 설명, docs/GAME.md 7장). 알갱이 판은 80 × 176 화소 그림 한 장에 알갱이 하나를 화소 하나로 채우고
 * 정수 배로 늘려 그린다(가장 가까운 이웃이라 알갱이가 또렷한 정사각형이다). 알갱이 색은 그리기 번호({@link Grain#look})마다
 * 미리 만든 표({@link SandColors})에서 고르고, 색 묶음과 무늬는 설정(색약 색상, 블록 무늬, {@link ColorPalettes})을 따른다.
 * 떨어지는 블록은 내려앉을 모래와 같은 알갱이 무늬로 그린다.
 * <p>
 * 화면: 위에 점수와 최고 점수, 가운데 판(스폰 여백 2칸 + 보이는 판 20칸, 넘침 선, 고스트), 왼쪽에 홀드·콤보 배율과 남은 콤보
 * 시간·단계·목표, 오른쪽에 미리보기 5개와 통계({@link SandHud}). 효과({@link SandFx}, runs/quality/SPEC.md V14): 지워질
 * 덩어리의 한 번의 반짝임, 사라질 때의 조각, 점수 띄우기, 떨어지는 블록의 꼬리, 하드드롭 튕김, 게임 끝의 회색, 그리고 양쪽
 * 벽을 이었지만 아직 얇아 남은 띠(sand-0.3)의 옅은 밝힘과 벽 바깥의 작은 삼각형. 덮개
 * ({@link SandCards}): 시작 카운트다운과 GO, 일시정지(판을 가린다), 오류 카드, 결과 카드(최고 점수, 다시 하기 · 같은 판 다시 ·
 * 메뉴로). 판 도중의 움직임은 판의 시계로, 판이 끝난 뒤와 멈춘 동안은 판의 실제 시간 시계로 계산하므로 같은 판이면 언제나 같은
 * 그림이다. 다음 블록이 스폰 자리의 모래를 기다리는 동안에는 그 블록을 스폰 자리에 흐리게 보여 주고, 기다림이 0.6초를 넘으면
 * 스폰 여백 바로 아래에 "모래가 비켜나면 나와요" 를 보인다.
 * <p>
 * 다시 그리기 타이머(15ms)는 이 화면이 보이는 동안만 돈다. 판 도중(카운트다운과 GO 포함)에는 틱마다 다시 그리고, 멈춘 판은
 * 일시정지 카드가 다 들어올 때까지, 끝난 판은 회색과 결과 카드가 다 들어올 때까지만 그린다: 그 뒤의 그림은 같다
 * ({@link #needsFrame()}). 단추 고르기, 계속, 판 바꾸기는 그 자리에서 다시 그리거나 다음 틱에 다시 그리기 시작한다.
 * <p>
 * 화면 설정(색약 색상, 블록 무늬, 고스트, 효과 세기, 화면 흔들림, 번쩍임 줄이기, 심장 소리)은 이 화면의 저장 파일에서 읽는다
 * (게임 창에서는 판과 같은 파일이다). 저장 파일이 없으면 판을 시작할 때의 설정({@link SandSession#user}).
 * <p>
 * 키는 대전 화면과 같다: 판의 {@link HumanInput} 이 사용자 설정의 키 묶음으로 동작으로 바꾸고(자동 반복 무시), 블록 조작은
 * HumanInput 으로, 일시정지(P), 메뉴(Esc, 혼자 하는 판이라 바로), 끝난 판의 결과 카드는 여기서 처리한다. 카운트다운과
 * 일시정지 동안 누르고 있던 이동은 블록 조작을 다시 받기 시작하는 순간 넘긴다({@link HumanInput#resumeHeld()}). 판 도중
 * Enter 는 아무것도 하지 않는다. 결과 카드는 판이 끝나고 1초 뒤에 뜨고 1.15초부터 키를 받는다(대전 화면의 K.O. 와 같다):
 * 이동 키(←→)로 단추를 고르고 확인 키(Enter)로 정한다(처음에는 다시 하기). 다시 하기의 다른 키(R)는 언제나 다시 하기, Esc 는
 * 언제나 메뉴로, 마우스로 눌러도 된다(대전 화면의 결과 카드와 같은 규칙, {@link BattleResult#confirmKey}). 같은 판 다시도 결과
 * 카드에서 ←→ 로 고르고 Enter 로 정한다(따로 된 키는 없다). 전체 화면 키는 창({@link TetrisApp})이 듣는다. 소리(카운트다운, 일시정지·계속, 위험 심장 소리, 메뉴 소리)는 판의 {@link SandSession#speaker()} 로 낸다. 판을 떠날
 * 때는 판을 먼저 멈추고(창이 {@link SandSession#quit()}) 그다음 울리던 소리를 끝낸 뒤 메뉴 소리를 낸다. 그리기는 잠금 안에서
 * 복사본만 만들고 잠금 밖에서 한다.
 */
final class SandPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	private static final int G = SandRules.GRAIN;
	private static final int GW = SandRules.WIDTH;
	/** 그리는 알갱이 행 수: 스폰 여백 2칸 + 보이는 판 20칸 = 176. */
	private static final int DRAWN = SandRules.HEIGHT - SandRules.DRAW_TOP;
	private static final int DRAWN_CELLS = DRAWN / G;
	/** 스폰 여백(그리는 칸 중 위 2칸). 정착한 모래가 여기 닿으면 끝난다. */
	private static final int MARGIN_CELLS = (SandRules.VISIBLE_TOP - SandRules.DRAW_TOP) / G;
	/** 위 점수 띠, 아래 여백, 옆 칸 너비, 옆 칸과 판 사이 (칸). */
	private static final double HEADER = 2.95;
	private static final double FOOTER = 0.7;
	private static final double SIDE = 6.2;
	private static final double GAP = 0.7;
	/** 정착한 모래가 보이는 판의 위 이만큼(칸) 안에 들면 테두리가 빨갛게 숨 쉰다. */
	static final int DANGER_CELLS = 4;
	/** 단계가 오른 것을 알리는 시간 (ms). */
	private static final int LEVEL_UP_MS = 1600;
	/**
	 * 판이 판의 진행({@link SandSession#advance}) 밖에서 끝나 끝난 순간을 모를 때(도구가 판을 직접 돌림) 쓰는 "끝난 뒤 흐른
	 * 시간" (ms): 회색과 결과 카드가 다 들어온 모습.
	 */
	private static final long LONG_AGO = 10_000;
	/** 기다리는 블록(스폰 자리의 모래가 비켜나기를 기다림)의 진하기. */
	private static final float WAITING_ALPHA = 0.38f;
	/** 다음 블록이 이만큼(판의 ms) 넘게 모래를 기다리면 {@link #WAIT_HINT} 를 보인다. */
	static final int WAIT_HINT_MS = 600;
	/** 그 꼬리표가 들어오는 시간 (ms). */
	private static final int WAIT_HINT_IN_MS = 150;
	/** 오래 기다리는 블록의 안내. 스폰 자리의 모래가 비켜나야 나온다(그 자리에서 모래가 멈추면 판이 끝난다). */
	static final String WAIT_HINT = "모래가 비켜나면 나와요";
	/**
	 * 판이 끝난 뒤 그림이 더 바뀌지 않는 때 (판이 끝난 순간부터 ms): 회색(0.81초), 번쩍임과 테두리 물결(1.22초), 결과 카드(1초에
	 * 떠서 1.52초에 숫자와 통계 줄까지 다 들어온다)가 끝나고 넉넉히 더 기다린 때. 그 뒤로는 다시 그리지 않는다.
	 */
	static final int OVER_SETTLED_MS = SandFx.OVER_CARD_AT + SandCards.CARD_IN_MS + 1000;
	/** 멈춘 뒤 그림이 더 바뀌지 않는 때 (멈춘 순간부터 ms): 일시정지 카드가 다 들어온 뒤. */
	static final int PAUSE_SETTLED_MS = SandCards.PAUSE_IN_MS + 50;

	private final transient GamePanel.Actions actions;
	private final transient SaveData save;
	private transient SandSession session;
	private transient SandSnapshot snap;
	private transient List<SandSession.LockFx> locks = List.of();
	private transient List<SandSession.ClearFx> clears = List.of();
	/** 얇은 띠가 나타난 판 시각 ({@link SandSession#thinSince()}), 마지막으로 그린 것. */
	private long thinSince = SandSession.NO_THIN;
	private boolean softDrop;
	private boolean accepting; // 지난번에 본 "블록 조작을 받는 중" (카운트다운이 끝나는 순간을 알아챈다)
	/** 결과 카드에서 고른 단추 ({@link SandCards#RETRY} …). */
	private int focus = SandCards.RETRY;
	/** 결과 카드 단추의 화면 자리 (마우스용, 마지막으로 그린 것). 화면 스레드만 쓴다. */
	private final transient Rectangle[] buttons = new Rectangle[SandCards.BUTTONS.length];
	private final transient CountdownSounds countdownSounds = new CountdownSounds();
	/** 15ms 다시 그리기 타이머. 이 화면이 보이는 동안만 돈다({@link #showingChanged}). 창에 붙지 않은 패널(시험, 도구)은 켜지 않는다. */
	private final transient Timer timer = new Timer(15, e -> tick());

	// 알갱이 그림: 화소 하나 = 알갱이 하나. 화면 스레드만 쓴다.
	private final transient BufferedImage sandImage = new BufferedImage(GW, DRAWN, BufferedImage.TYPE_INT_ARGB);
	private final transient int[] pixels = ((DataBufferInt) sandImage.getRaster().getDataBuffer()).getData();
	private final short[] grains = new short[GW * SandRules.HEIGHT];
	private final short[] pieceGrains = new short[SandRules.PIECE_GRAINS];
	/** 얇은 띠의 알갱이 자리 (그릴 때마다 복사본에서 채운다). */
	private final int[] thinCells = new int[GW * SandRules.HEIGHT];
	/** 그리는 행마다 얇은 띠가 왼쪽·오른쪽 벽에 닿은 색 번호 (없으면 0). */
	private final byte[] wallLeft = new byte[DRAWN];
	private final byte[] wallRight = new byte[DRAWN];
	/** 열마다 얇은 띠의 흰색 섞기 (0~256, 그릴 때마다). */
	private final int[] thinMix = new int[GW];
	/** 얇은 띠의 벽 삼각형 (그릴 때마다 다시 쓴다). */
	private final transient Path2D.Double wallMark = new Path2D.Double();
	private transient SandColors colors;

	/** 화면 자리 (칸 c = 알갱이 g × 8 화소). 도우미({@link SandHud}, {@link SandFx}, {@link SandCards})도 쓴다. */
	record Geo(int g, int c, int boardX, int boardY, int boardW, int boardH, int leftX, int rightX, int sideW, int y0) {
	}

	/**
	 * @param actions 다시 하기·같은 판 다시·메뉴 (TetrisApp 이 정한다)
	 * @param save    화면 설정(색약 색상, 블록 무늬, 고스트, 효과)을 읽을 곳, 없으면 null(판을 시작할 때의 설정)
	 */
	SandPanel(GamePanel.Actions actions, SaveData save) {
		this.actions = actions;
		this.save = save;
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
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				SandSession s = session;
				if (s != null) {
					s.human.releaseAll(); // 저절로 멈추는 것은 창(TetrisApp)이 정한다
				}
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				hover(e.getX(), e.getY());
			}

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				click(e.getX(), e.getY());
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		// 다시 그리기 타이머는 이 화면이 보이는 동안만 돈다 (메뉴·대전·설정 화면에서는 멈춘다, 메뉴 화면과 같은 방식)
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				showingChanged(isShowing());
			}
		});
	}

	/** 이 화면이 보이게 됐거나 사라졌다: 보이는 동안만 다시 그리기 타이머를 돌린다. 화면 스레드. 시험은 직접 부른다. */
	void showingChanged(boolean showing) {
		if (!showing) {
			timer.stop();
		} else if (!timer.isRunning()) {
			timer.start();
		}
	}

	/** 다시 그리기 타이머가 도는가 (시험). */
	boolean ticking() {
		return timer.isRunning();
	}

	@Override
	public void removeNotify() {
		timer.stop(); // 창을 닫거나 다시 만든다(전체 화면 바꾸기): 다시 보이면 showingChanged 가 돌린다
		super.removeNotify();
	}

	/** 15ms 마다 (화면 스레드): 누르고 있던 이동을 넘기고, 그림이 바뀔 때만 다시 그린다. */
	private void tick() {
		if (isShowing()) { // 보이지 않는 패널(시험, 화면 확인 도구)은 부른 쪽이 직접 넘긴다
			handOff();
		}
		if (needsFrame()) {
			repaint();
		}
	}

	/**
	 * 다음 틱에 다시 그려야 하는가. 판 도중(시작 카운트다운과 GO, 일시정지를 푼 뒤의 카운트다운과 걷히는 카드 포함)은 언제나 참이다:
	 * 카운트다운 소리와 위험 심장 소리도 그릴 때 내므로 그때는 언제나 그린다. 멈춘 판은 판을 가린 일시정지 카드가 다 들어올 때까지
	 * ({@link #PAUSE_SETTLED_MS}), 끝난 판(오류로 멈춘 판 포함)은 회색과 결과 카드가 다 들어올 때까지({@link #OVER_SETTLED_MS})만
	 * 참이다: 그 뒤의 그림은 같다. 그 뒤에 바뀌는 것은 따로 다시 그린다: 단추 고르기(키, 마우스)와 판 바꾸기는 그 자리에서,
	 * 계속(P)은 다음 틱에 이 판단이 참이 되어서, 판이 끝난 뒤의 기록(새 기록)은 판이 끝났다는 알림(창이 다시 그린다)으로, 창 크기
	 * 바꾸기와 가려졌던 창은 Swing 이 그린다. 판이 없으면 거짓.
	 */
	boolean needsFrame() {
		SandSession s = session;
		if (s == null) {
			return false;
		}
		long clock = s.clockNanos();
		long pausedAt = s.pausedAtNanos();
		if (pausedAt != SandSession.NOT_OVER) { // 그리기와 같은 차례: 멈춘 판은 일시정지 카드가 판을 가린다
			return clock - pausedAt <= PAUSE_SETTLED_MS * 1_000_000L;
		}
		if (s.over()) {
			long at = s.overAtNanos();
			return at != SandSession.NOT_OVER && clock - at <= OVER_SETTLED_MS * 1_000_000L;
		}
		return true;
	}

	void setSession(SandSession s) {
		this.session = s;
		this.accepting = false;
		this.focus = SandCards.RETRY;
		java.util.Arrays.fill(buttons, null);
		countdownSounds.reset();
		repaint();
	}

	/**
	 * 블록 조작을 다시 받기 시작하는 순간(시작 카운트다운이 끝남, 일시정지를 푼 뒤의 카운트다운이 끝남)에 누르고 있던 이동을
	 * 넘긴다. 15ms 다시 그리기 타이머가 부른다(화면 스레드). 시험은 직접 부른다.
	 */
	void handOff() {
		SandSession s = session;
		boolean now = s != null && accepts(s);
		if (now && !accepting) {
			s.human.resumeHeld();
		}
		accepting = now;
	}

	/** 지금 블록 조작을 받는가: 멈춤도, 카운트다운도, 끝난 판도 아니다. */
	private static boolean accepts(SandSession s) {
		return !s.paused() && !s.over() && s.countdown() == 0;
	}

	// ------------------------------------------------------------------ 키와 마우스

	/** 키 하나 (화면 스레드). 시험은 직접 부른다. */
	void onKey(int code, boolean down) {
		SandSession s = session;
		if (s == null) {
			return;
		}
		KeyAction a = down ? s.human.keyPressed(code) : s.human.keyReleased(code);
		if (a == null) {
			return; // 자동 반복, 묶이지 않은 키, 같은 동작의 다른 키
		}
		if (s.over()) { // 끝났거나 오류로 멈춘 판
			if (down) {
				overKey(s, a, code);
			}
			return;
		}
		if (down && a == KeyAction.MENU) { // 혼자 하는 판이라 바로 메뉴로 (혼자 연습과 같다)
			menu(s);
			return;
		}
		if (down && a == KeyAction.PAUSE) {
			boolean was = s.paused();
			s.togglePause();
			if (s.paused() != was) {
				s.speaker().play(Cue.of(s.paused() ? Sfx.PAUSE : Sfx.RESUME));
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
	 * 끝난 판의 키 ({@code code} 는 누른 키, 대전 화면의 결과 카드와 같은 규칙). 오류 카드는 바로 다시 하기·메뉴로. 결과 카드는
	 * 키를 받기 시작한 뒤({@link SandFx#OVER_KEYS_AT}) 이동 키(←→)로 단추를 고르고, 확인 키(다시 하기에 묶인 Enter,
	 * {@link BattleResult#confirmKey})로 고른 단추를 정한다. 다시 하기의 다른 키(R)는 고른 단추와 상관없이 언제나 다시 하기, 메뉴
	 * 키(Esc)는 언제나 메뉴로. 같은 판 다시는 ←→ 로 고르고 Enter 로 정한다. 전체 화면 키는 창({@link TetrisApp})이 듣는다.
	 */
	private void overKey(SandSession s, KeyAction a, int code) {
		if (s.failure() != null) {
			if (a == KeyAction.RETRY) {
				restart(s, false);
			} else if (a == KeyAction.MENU) {
				menu(s);
			}
			return;
		}
		if (!resultAccepting(s)) {
			return; // 판이 끝나는 순간 연타한 키로 바로 넘어가지 않게
		}
		if (a == KeyAction.MOVE_LEFT) {
			moveFocus(s, -1);
		} else if (a == KeyAction.MOVE_RIGHT) {
			moveFocus(s, 1);
		} else if (a == KeyAction.RETRY) {
			press(s, code == BattleResult.confirmKey(s.human.keys()) ? focus : SandCards.RETRY);
		} else if (a == KeyAction.MENU) {
			menu(s);
		}
	}

	/**
	 * 결과 카드가 키(←→, Enter·R, Esc)와 마우스를 받는가: 판이 끝나고 {@link SandFx#OVER_KEYS_AT} ms 가 지났다(오류로 멈춘 판은
	 * 아니다). 시험용 (게임은 {@link #onKey} 와 마우스 처리가 같은 판단을 쓴다).
	 */
	boolean resultReady() {
		SandSession s = session;
		return s != null && resultAccepting(s);
	}

	/** 결과 카드가 키(와 마우스)를 받는가: 판이 끝나고 {@link SandFx#OVER_KEYS_AT} ms 가 지났다. */
	private static boolean resultAccepting(SandSession s) {
		return s.over() && s.failure() == null && overAge(s) >= SandFx.OVER_KEYS_AT;
	}

	/** 판이 끝난 뒤 흐른 실제 시간 (ms), 끝나지 않았으면 -1. */
	private static long overAge(SandSession s) {
		long at = s.overAtNanos();
		return at == SandSession.NOT_OVER ? -1 : Math.max(0, (s.clockNanos() - at) / 1_000_000);
	}

	private void moveFocus(SandSession s, int d) {
		int next = Math.max(0, Math.min(SandCards.BUTTONS.length - 1, focus + d));
		if (next == focus) {
			s.speaker().play(Cue.of(Sfx.DENY)); // 끝이라 움직이지 않는다
			return;
		}
		focus = next;
		s.speaker().play(Cue.of(Sfx.MENU_MOVE));
		repaint();
	}

	/** 결과 카드의 단추 {@code i} 를 누른다. */
	private void press(SandSession s, int i) {
		if (i == SandCards.MENU) {
			menu(s);
		} else {
			restart(s, i == SandCards.SAME_SEED);
		}
	}

	/**
	 * 다시 하기·같은 판 다시 (끝난 판에서만). 창이 앞 판을 멈춘({@link SandSession#quit()}) 뒤 앞 판의 소리(게임 끝, 새 기록)를
	 * 끝내고 고르는 소리를 낸다. 새 판의 카운트다운 소리는 그릴 때 나므로 끝내지 않는다.
	 */
	private void restart(SandSession s, boolean sameSeed) {
		actions.restart(sameSeed);
		s.speaker().stopAll();
		s.speaker().play(Cue.of(Sfx.MENU_SELECT));
	}

	/**
	 * 메뉴로. 창이 판을 멈춘 뒤({@link SandSession#quit()}: 진행 스레드가 끝나기를 기다린다, 그 뒤로 판은 소리를 내지 않는다)에
	 * 울리던 소리를 끝내므로, 판 도중에 떠나도 그 틈에 판이 낸 소리(고정·연쇄, 막 끝난 판의 게임 끝)와 창이 화면을 그림으로 뜨며
	 * 낸 심장 소리가 메뉴 위로 남지 않는다.
	 */
	private void menu(SandSession s) {
		actions.menu();
		s.speaker().stopAll();
		s.speaker().play(Cue.of(Sfx.MENU_BACK));
	}

	/** 마우스가 결과 카드의 단추 위로 오면 그 단추를 고른다. */
	void hover(int x, int y) {
		SandSession s = session;
		if (s == null || !resultAccepting(s)) {
			return;
		}
		int i = buttonAt(x, y);
		if (i >= 0 && i != focus) {
			focus = i;
			s.speaker().play(Cue.of(Sfx.MENU_MOVE));
			repaint();
		}
	}

	/** 결과 카드의 단추를 마우스로 눌렀다. */
	void click(int x, int y) {
		SandSession s = session;
		if (s == null || !resultAccepting(s)) {
			return;
		}
		int i = buttonAt(x, y);
		if (i >= 0) {
			focus = i;
			press(s, i);
		}
	}

	private int buttonAt(int x, int y) {
		for (int i = 0; i < buttons.length; i++) {
			if (buttons[i] != null && buttons[i].contains(x, y)) {
				return i;
			}
		}
		return -1;
	}

	/** 결과 카드에서 고른 단추 (시험). */
	int focus() {
		return focus;
	}

	/** 결과 카드 단추 {@code i} 의 화면 자리, 아직 그리지 않았으면 null (시험). */
	Rectangle button(int i) {
		return buttons[i];
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.antialias(g);
		Theme.backgroundCached(g, getWidth(), getHeight());
		SandSession s = session;
		if (s != null) {
			synchronized (s.lock) { // 잠금은 여기서만, 그리기는 복사본으로
				snap = s.snapshot(snap);
				locks = s.lockFx();
				clears = s.clearFx();
				thinSince = s.thinSince();
				softDrop = s.softDropping();
			}
			UserSettings u = save != null ? save.settings() : s.user;
			colors = SandColors.of(ColorPalettes.look(u));
			paintGame(g, s, snap, u);
		}
		g.dispose();
	}

	/** 지금 크기에서 알갱이 판(스폰 여백 포함)이 그려지는 자리. 알갱이 하나는 {@code width / 80} 화소다 (시험용). */
	Rectangle boardBounds() {
		Geo geo = geo();
		return new Rectangle(geo.boardX(), geo.boardY(), geo.boardW(), geo.boardH());
	}

	private Geo geo() {
		double wide = 2 * (SIDE + GAP) + SandRules.COLS + 1;
		double tall = HEADER + DRAWN_CELLS + FOOTER;
		int g = (int) Math.max(1, Math.floor(Math.min(getWidth() / (wide * G), getHeight() / (tall * G))));
		int c = g * G;
		int boardW = SandRules.COLS * c;
		int boardH = DRAWN_CELLS * c;
		int boardX = (getWidth() - boardW) / 2;
		int y0 = (int) Math.round((getHeight() - tall * c) / 2);
		int sideW = (int) Math.round(SIDE * c);
		int gap = (int) Math.round(GAP * c);
		return new Geo(g, c, boardX, y0 + (int) Math.round(HEADER * c), boardW, boardH, boardX - gap - sideW,
				boardX + boardW + gap, sideW, y0);
	}

	private void paintGame(Graphics2D g, SandSession session, SandSnapshot s, UserSettings u) {
		Geo geo = geo();
		int c = geo.c();
		int w = getWidth();
		int h = getHeight();
		double m = u.effectsScale();
		boolean calm = u.calmFlash();
		long now = s.time();
		long overAge = !s.over() ? -1 : session.overAtNanos() == SandSession.NOT_OVER ? LONG_AGO : overAge(session);
		boolean danger = !s.over() && s.settledTop() < SandRules.VISIBLE_TOP + DANGER_CELLS * G;
		// 얇은 띠 표시가 들어온 정도 (끝난 판은 회색이 되므로 보이지 않는다)
		double thin = s.over() || s.thinGrains() == 0 || thinSince == SandSession.NO_THIN ? 0
				: SandFx.thinIn(now - thinSince);

		SandHud.header(g, geo, s);
		int bump = u.shake() && !s.over() ? SandFx.bump(locks, now, c, m) : 0;
		g.translate(0, bump); // 판과 그 위의 것만 튕긴다(옆 카드와 점수는 그대로)
		paintBoard(g, geo, s, session.seed(), u, m, calm, danger, overAge, thin);
		g.translate(0, -bump);
		if (danger) {
			double fpx = Math.max(12, 0.42 * c);
			Theme.chip(g, "위험", geo.boardX() + (int) Math.round(0.3 * c), geo.boardY() + (int) Math.round(1.35 * c), fpx,
					Color.WHITE, Theme.withAlpha(Theme.DANGER, 215));
		}
		paintWaitHint(g, geo, waitedMs(s));
		if (!s.over()) {
			paintLevelUp(g, geo, s, session.startLevel());
		}
		SandHud.hold(g, geo, s, colors);
		SandHud.combo(g, geo, s, geo.boardY() + (int) Math.round(3.9 * c), m);
		SandHud.level(g, geo, s, session.startLevel(), geo.boardY() + (int) Math.round(8.6 * c));
		SandHud.goal(g, geo, colors, geo.boardY() + (int) Math.round(13.3 * c), thin);
		SandHud.next(g, geo, s.preview(), colors);
		SandHud.stats(g, geo, s);

		long left = session.countdownMs();
		if (left > 0) {
			SandCards.countdown(g, w, h, c, left);
		} else {
			long go = session.goAgeMs();
			if (go >= 0) {
				SandCards.go(g, w, h, c, go, m, calm);
			}
		}
		if (session.failure() != null) {
			SandCards.failure(g, w, h, c, session.human.keys(), session.failure());
		} else if (!paintPause(g, session, c)) {
			if (s.over() && overAge >= SandFx.OVER_CARD_AT) {
				SandCards.result(g, w, h, c, session, s, overAge - SandFx.OVER_CARD_AT, focus,
						overAge >= SandFx.OVER_KEYS_AT, buttons);
			}
		}
		sounds(session, s, u, danger);
	}

	/**
	 * 일시정지 카드 (SPEC V11): 멈추면 판을 바로 가리고(멈춰 놓고 다음 수를 궁리하는 이득이 없게, 최고 점수가 남는 판이다) 카드가
	 * 0.2초 동안 들어온다. 계속하면 가림과 카드가 0.15초에 걸쳐 걷힌다. 그렸으면 true.
	 */
	private boolean paintPause(Graphics2D g, SandSession session, int c) {
		long clock = session.clockNanos();
		long pausedAt = session.pausedAtNanos();
		double shown;
		double cover;
		if (pausedAt != SandSession.NOT_OVER) {
			shown = Motion.enter(Motion.clamp01((clock - pausedAt) / 1e6 / SandCards.PAUSE_IN_MS));
			cover = 1;
		} else {
			long resumed = session.resumedAtNanos();
			if (resumed == SandSession.NOT_OVER) {
				return false;
			}
			double u = (clock - resumed) / 1e6 / SandCards.PAUSE_OUT_MS;
			if (u >= 1 || u < 0) {
				return false;
			}
			shown = 1 - Motion.exit(u);
			cover = shown;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) cover));
		Theme.backgroundCached(g, getWidth(), getHeight());
		g.setComposite(old);
		SandCards.pause(g, getWidth(), getHeight(), c, session.human.keys(), shown);
		return true;
	}

	/** 카운트다운 소리와 위험 심장 소리 (그릴 때마다 불러도 소리 쪽이 박자를 맞춘다). */
	private void sounds(SandSession session, SandSnapshot s, UserSettings u, boolean danger) {
		SandSession.Speaker speaker = session.speaker();
		Cue cd = countdownSounds.update(session.countdown(), !session.paused() && !session.over());
		if (cd != null) {
			speaker.play(cd);
		}
		if (danger && u.heartbeat() && !session.paused() && !session.over() && session.countdown() == 0) {
			int fill = (SandRules.HEIGHT - s.settledTop() + G - 1) / G; // 쌓인 높이 (칸)
			speaker.play(SoundCues.danger(fill));
		}
	}

	// ------------------------------------------------------------------ 판

	/**
	 * 판과 그 위의 것 (튕김으로 함께 움직인다). {@code seed} 는 판의 시드(효과 난수), {@code thin} 은 얇은 띠 표시가 들어온 정도
	 * (0~1).
	 */
	private void paintBoard(Graphics2D g, Geo geo, SandSnapshot s, long seed, UserSettings u, double m, boolean calm,
			boolean danger, long overAge, double thin) {
		int c = geo.c();
		int bx = geo.boardX();
		int by = geo.boardY();
		int bw = geo.boardW();
		int bh = geo.boardH();
		long now = s.time();
		double pad = 0.18 * c;
		double arc = 0.6 * c;

		Theme.card(g, bx - pad, by - pad, bw + 2 * pad, bh + 2 * pad, arc, Theme.BOARD, null);
		if (danger) {
			Theme.glow(g, bx - pad, by - pad, bw + 2 * pad, bh + 2 * pad, arc, Theme.DANGER,
					SandFx.dangerGlow(now, m, calm));
		} else {
			Theme.glow(g, bx - pad, by - pad, bw + 2 * pad, bh + 2 * pad, arc, s.over() ? SandColors.KO_GREY : Theme.SAND,
					0.35);
		}
		int marginH = MARGIN_CELLS * c;
		g.setColor(Theme.withAlpha(Theme.BORDER, 60)); // 스폰 여백: 블록이 나오는 곳, 모래가 여기서 멈추면 끝
		g.fillRect(bx, by, bw, marginH);
		g.setColor(Theme.GRID);
		for (int col = 1; col < SandRules.COLS; col++) {
			g.drawLine(bx + col * c, by, bx + col * c, by + bh - 1);
		}
		for (int r = 1; r < DRAWN_CELLS; r++) {
			g.drawLine(bx, by + r * c, bx + bw - 1, by + r * c);
		}
		if (danger) { // 위쪽에서 숨 쉬듯 번지는 빨간 빛 (고전 화면과 같다)
			g.setPaint(new GradientPaint(0, by, Theme.withAlpha(Theme.DANGER, SandFx.dangerWash(now, m, calm)), 0, by + 6 * c,
					Theme.withAlpha(Theme.DANGER, 0)));
			g.fillRect(bx, by, bw, 6 * c);
		}

		PieceState piece = s.piece();
		if (!s.over()) { // 꼬리는 모래 그림 아래에: 알갱이를 가리지 않는다
			SandFx.dropTrail(g, geo, locks, colors, now, m);
			if (softDrop && piece != null && !s.grounded()) {
				SandFx.softTrail(g, geo, piece, Fx.lighter(colors.sand(s.current().color().code()), 0.25), m);
			}
		}
		fillPixels(s, now, m, calm, overAge, thin);
		Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(sandImage, bx, by, bw, bh, null);
		if (interpolation != null) {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		}
		if (thin > 0) {
			paintThinWalls(g, geo, pad, thin);
		}

		// 넘침 선: 정착한 모래가 이 선을 넘으면 끝난다
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(Math.max(1f, c / 16f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f,
				new float[] { c / 4f, c / 6f }, 0f));
		g.setColor(danger ? Theme.withAlpha(Theme.DANGER, 230) : Theme.withAlpha(Theme.LABEL, 150));
		g.draw(new Line2D.Double(bx, by + marginH, bx + bw, by + marginH));
		g.setStroke(old);

		if (piece != null) {
			if (u.ghost()) {
				paintGhost(g, geo, s.ghost(), piece, s.current().color());
			}
			paintOutline(g, geo, piece);
		} else if (s.waiting() && s.current() != null) {
			paintWaiting(g, geo, s.current().type(), s.current().color(), now);
		}
		if (!s.over()) {
			Shape clip = g.getClip();
			g.clipRect(bx - (int) pad, by - c, bw + 2 * (int) pad, bh + c + (int) pad);
			SandFx.pieces(g, geo, clears, colors, seed, now, m);
			g.setClip(clip);
			SandFx.scorePop(g, geo, clears, now);
		} else if (overAge >= 0) {
			double flash = SandFx.overFlash(overAge, m, calm);
			if (flash > 0) {
				g.setColor(Fx.alpha(Color.WHITE, flash));
				g.fillRect(bx, by, bw, bh);
			}
			SandFx.overRipple(g, bx - pad, by - pad, bw + 2 * pad, bh + 2 * pad, arc, c, overAge, m, calm);
		}
	}

	/**
	 * 알갱이 그림을 채운다: 판의 알갱이(행 DRAW_TOP 부터)와 떨어지는 블록의 알갱이. 빈칸은 투명. 얇은 띠는 옅게 밝히고 빛
	 * 물결을 흘리며({@code thin} 만큼 들어온, {@link SandFx#thinMix}), 지워질 덩어리는 지우기마다 한 번의 반짝임으로
	 * ({@link SandFx#pulseMix}), 판이 끝나면 아래에서 위로 회색으로.
	 */
	private void fillPixels(SandSnapshot s, long now, double m, boolean calm, long overAge, double thin) {
		s.copyGrains(grains);
		int off = SandRules.DRAW_TOP * GW;
		int[] base = colors.table(SandFx.restMix(m));
		for (int i = 0; i < pixels.length; i++) {
			short v = grains[off + i];
			pixels[i] = v == Grain.EMPTY ? 0 : base[Grain.look(v)];
		}
		Arrays.fill(wallLeft, (byte) 0);
		Arrays.fill(wallRight, (byte) 0);
		if (thin > 0) {
			int n = s.thinGrains();
			s.copyThin(thinCells);
			for (int x = 0; x < GW; x++) {
				thinMix[x] = (int) Math.round(256 * thin * SandFx.thinMix(x, now, m, calm));
			}
			for (int k = 0; k < n; k++) {
				int idx = thinCells[k];
				if (idx < off) {
					continue; // 그리지 않는 킥 여유
				}
				int gy = idx / GW;
				int gx = idx - gy * GW;
				pixels[idx - off] = SandColors.blend(pixels[idx - off], 0xFFFFFFFF, thinMix[gx]);
				if (gx == 0) {
					wallLeft[gy - SandRules.DRAW_TOP] = (byte) Grain.code(grains[idx]);
				} else if (gx == GW - 1) {
					wallRight[gy - SandRules.DRAW_TOP] = (byte) Grain.code(grains[idx]);
				}
			}
		}
		if (m > 0) {
			for (SandSession.ClearFx f : clears) {
				long age = now - f.at();
				if (age < 0 || age >= SandRules.FLASH_MS) {
					continue;
				}
				int[] table = colors.table(SandFx.pulseMix(age, m, calm));
				for (int k = 0; k < f.grains(); k++) {
					int idx = f.cell(k);
					short v = grains[idx];
					if (idx >= off && Grain.flashing(v)) {
						pixels[idx - off] = table[Grain.look(v)];
					}
				}
			}
		}
		PieceState p = s.piece();
		if (p != null) {
			s.pieceGrains(pieceGrains);
			int[][] cells = p.cells();
			for (int k = 0; k < cells.length; k++) {
				for (int oy = 0; oy < G; oy++) {
					int gy = cells[k][0] * G + oy - SandRules.DRAW_TOP;
					if (gy < 0 || gy >= DRAWN) {
						continue; // 그리지 않는 킥 여유(칸 0~1 행)
					}
					for (int ox = 0; ox < G; ox++) {
						pixels[gy * GW + cells[k][1] * G + ox] = base[Grain.look(pieceGrains[(k * G + oy) * G + ox])];
					}
				}
			}
		}
		if (overAge > SandFx.OVER_HOLD_MS) {
			for (int y = 0; y < DRAWN; y++) {
				int wgt = SandFx.greyAt(y, overAge);
				if (wgt <= 0) {
					continue;
				}
				for (int x = 0; x < GW; x++) {
					int i = y * GW + x;
					short v = grains[off + i];
					if (v != Grain.EMPTY) {
						int grey = colors.grey(Grain.look(v));
						pixels[i] = wgt >= 256 ? grey : SandColors.blend(pixels[i], grey, wgt);
					}
				}
			}
		}
	}

	/**
	 * 얇은 띠가 벽에 닿은 자리 ({@link #fillPixels} 가 행마다 적어 둔 색): 판 테두리 바로 바깥에 띠 색을 밝힌 작은 삼각형이 판
	 * 쪽을 가리켜 양쪽 벽을 이었다는 것을 보인다. 벽에 닿은 같은 색 행이 이어진 토막마다 하나, 그 가운데에. 진하기는 표시가 들어온
	 * 정도 {@code thin}(0~1)을 따른다.
	 */
	private void paintThinWalls(Graphics2D g, Geo geo, double pad, double thin) {
		int px = geo.g();
		double size = Math.max(5, 0.34 * geo.c());
		int alpha = (int) Math.round(235 * thin);
		for (int side = 0; side < 2; side++) {
			byte[] rows = side == 0 ? wallLeft : wallRight;
			double tip = side == 0 ? geo.boardX() - pad * 0.35 : geo.boardX() + geo.boardW() + pad * 0.35;
			double back = side == 0 ? tip - size : tip + size;
			int y = 0;
			while (y < DRAWN) {
				int code = rows[y];
				if (code == 0) {
					y++;
					continue;
				}
				int end = y;
				while (end < DRAWN && rows[end] == code) {
					end++;
				}
				double cy = geo.boardY() + (y + end) * px / 2.0;
				wallMark.reset();
				wallMark.moveTo(tip, cy);
				wallMark.lineTo(back, cy - size * 0.62);
				wallMark.lineTo(back, cy + size * 0.62);
				wallMark.closePath();
				g.setColor(Theme.withAlpha(Fx.lighter(colors.sand(code), 0.3), alpha));
				g.fill(wallMark);
				y = end;
			}
		}
	}

	/** 고스트: 지금 하드드롭하면 놓일 칸을 블록 색으로 옅게 채우고 테두리만 또렷하게. */
	private void paintGhost(Graphics2D g, Geo geo, PieceState ghost, PieceState piece, SandColor color) {
		if (ghost == null || ghost.y() == piece.y()) {
			return;
		}
		int c = geo.c();
		Color base = colors.sand(color.code());
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(Math.max(1.5f, c / 14f)));
		for (int[] cell : ghost.cells()) {
			int y = geo.boardY() + (cell[0] - SandRules.DRAW_TOP / G) * c;
			if (y < geo.boardY()) {
				continue;
			}
			int x = geo.boardX() + cell[1] * c;
			g.setColor(Theme.withAlpha(base, 40));
			g.fillRect(x + 2, y + 2, c - 4, c - 4);
			g.setColor(Theme.withAlpha(Theme.mix(base, Color.WHITE, 0.4), 200));
			g.drawRect(x + 2, y + 2, c - 5, c - 5);
		}
		g.setStroke(old);
	}

	/** 떨어지는 블록의 바깥 테두리: 같은 색 모래 위에서도 블록이 구분되게 옅은 흰 선을 두른다. */
	private static void paintOutline(Graphics2D g, Geo geo, PieceState piece) {
		outline(g, geo, piece.cells(), new Color(255, 255, 255, 120), Math.max(1f, geo.g() * 0.6f));
	}

	/**
	 * 다음 블록이 모래를 기다린 시간 (판의 ms), 기다리는 중이 아니면 -1. 기다림은 언제나 블록을 고정한 그 틱에 시작한다: 고정한
	 * 뒤 다음 블록의 스폰 자리가 막혔다(홀드는 막힌 자리로 꺼내지 않는다). 판의 처음부터 막혔으면 0ms 부터 잰다. 판의 상태만
	 * 쓰므로 같은 판이면 언제나 같은 값이다(화면이 틱마다 그리지 않아도 된다).
	 */
	static long waitedMs(SandView v) {
		if (!v.waiting()) {
			return -1;
		}
		LockEvent last = v.lastLock();
		return v.time() - (last != null ? last.time() : 0);
	}

	/**
	 * 오래 기다리는 블록의 안내: 다음 블록이 {@link #WAIT_HINT_MS} 넘게 모래를 기다리면 넘침 선 바로 아래 가운데에
	 * {@link #WAIT_HINT} 꼬리표를 0.15초에 걸쳐 보인다(단계 오름 띠와 같은 모양). 판 끝 무렵에는 기다림이 몇 초까지 가서 흐린
	 * 블록만으로는 멈춘 것처럼 보이기 때문이다. 정보라 효과를 꺼도 보인다.
	 */
	private static void paintWaitHint(Graphics2D g, Geo geo, long waited) {
		if (waited <= WAIT_HINT_MS) {
			return;
		}
		int c = geo.c();
		double alpha = Motion.clamp01((waited - WAIT_HINT_MS) / (double) WAIT_HINT_IN_MS);
		double fpx = Math.max(13, 0.56 * c);
		g.setFont(Theme.font(fpx, true));
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(WAIT_HINT) + 2 * (int) Math.round(fpx * 0.6); // Theme.chip 의 너비
		int h = (int) Math.round(fpx * 1.55);
		int top = geo.boardY() + MARGIN_CELLS * c + (int) Math.round(0.25 * c); // 넘침 선 바로 아래
		int baseline = top + fm.getAscent() + (h - fm.getHeight()) / 2; // Theme.chip 이 꼬리표 위를 정하는 식을 거꾸로
		Theme.chip(g, WAIT_HINT, (int) Math.round(geo.boardX() + (geo.boardW() - w) / 2.0), baseline, fpx,
				Theme.withAlpha(Theme.BG_TOP, (int) (255 * alpha)), Theme.withAlpha(Theme.SAND, (int) (235 * alpha)));
	}

	/**
	 * 다음 블록이 스폰 자리의 모래가 비켜나기를 기다리는 중: 그 블록을 스폰 자리에 흐리게(알갱이 무늬 38%) 보이고 점선
	 * 테두리를 두른다. 기다림은 대개 0.5초 안이지만 판 끝 무렵에는 몇 초까지 갈 수 있다(sand 패키지 설명 7장). 0.6초가 넘으면
	 * 안내 꼬리표도 보인다({@link #paintWaitHint}).
	 */
	private void paintWaiting(Graphics2D g, Geo geo, kr.ac.jbnu.se.tetris.core.PieceType type, SandColor color,
			long now) {
		int c = geo.c();
		int[][] cells = PieceState.spawn(type).cells();
		Composite oc = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, WAITING_ALPHA));
		Object interpolation = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		for (int[] cell : cells) {
			int y = geo.boardY() + (cell[0] - SandRules.DRAW_TOP / G) * c;
			if (y >= geo.boardY()) {
				g.drawImage(colors.tile(color), geo.boardX() + cell[1] * c, y, c, c, null);
			}
		}
		if (interpolation != null) {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
		}
		g.setComposite(oc);
		Stroke old = g.getStroke();
		float dash = Math.max(2f, c / 6f);
		g.setStroke(new BasicStroke(Math.max(1f, c / 14f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f,
				new float[] { dash, dash }, (now / 40) % (2 * (long) dash))); // 점선이 천천히 돈다: 멈춘 것이 아니라 기다리는 중
		outline(g, geo, cells, Theme.withAlpha(Theme.mix(colors.sand(color.code()), Color.WHITE, 0.5), 190), -1);
		g.setStroke(old);
	}

	/** 칸 묶음의 바깥 테두리. {@code width} 가 0 보다 크면 그 굵기의 실선, 아니면 지금 선. 스폰 여백 위는 그리지 않는다. */
	private static void outline(Graphics2D g, Geo geo, int[][] cells, Color color, float width) {
		int c = geo.c();
		Stroke old = g.getStroke();
		if (width > 0) {
			g.setStroke(new BasicStroke(width, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
		}
		g.setColor(color);
		int top = SandRules.DRAW_TOP / G;
		for (int[] cell : cells) {
			if (cell[0] < top) {
				continue;
			}
			double x = geo.boardX() + cell[1] * c;
			double y = geo.boardY() + (cell[0] - top) * c;
			if (!has(cells, cell[0] - 1, cell[1]) && cell[0] - 1 >= top) {
				g.draw(new Line2D.Double(x, y, x + c, y));
			}
			if (!has(cells, cell[0] + 1, cell[1])) {
				g.draw(new Line2D.Double(x, y + c, x + c, y + c));
			}
			if (!has(cells, cell[0], cell[1] - 1)) {
				g.draw(new Line2D.Double(x, y, x, y + c));
			}
			if (!has(cells, cell[0], cell[1] + 1)) {
				g.draw(new Line2D.Double(x + c, y, x + c, y + c));
			}
		}
		g.setStroke(old);
	}

	private static boolean has(int[][] cells, int r, int c) {
		for (int[] cell : cells) {
			if (cell[0] == r && cell[1] == c) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 단계가 오르면 판 위쪽(막 나온 블록 아래)에 "단계 N · 빨라져요" 띠를 잠깐 보인다: 0.22초 동안 0.9 → 1 로 들어오고
	 * 마지막 0.4초에 옅어진다(정보라 효과를 꺼도 남는다).
	 */
	private static void paintLevelUp(Graphics2D g, Geo geo, SandSnapshot s, int startLevel) {
		if (s.level() <= startLevel) {
			return;
		}
		long since = s.time() - (long) (s.level() - startLevel) * SandRules.LEVEL_MS;
		if (since < 0 || since >= LEVEL_UP_MS) {
			return;
		}
		int c = geo.c();
		double alpha = since > LEVEL_UP_MS - 400 ? (LEVEL_UP_MS - since) / 400.0 : Motion.clamp01(since / 80.0);
		double scale = 0.9 + 0.1 * Motion.easeOutBack(Motion.clamp01(since / 220.0));
		double y = geo.boardY() + (MARGIN_CELLS + 3.6) * c; // 막 나온 블록(칸 2~4행) 아래
		double fpx = Math.max(13, 0.56 * c);
		String text = "단계 " + s.level() + "  ·  빨라져요";
		g.setFont(Theme.font(fpx, true));
		int w = g.getFontMetrics().stringWidth(text) + (int) Math.round(fpx * 1.2);
		double cx = geo.boardX() + geo.boardW() / 2.0;
		AffineTransform old = SandFx.scaleAbout(g, cx, y - fpx * 0.4, scale);
		Theme.chip(g, text, (int) Math.round(cx - w / 2.0), (int) Math.round(y), fpx,
				Theme.withAlpha(Theme.BG_TOP, (int) (255 * alpha)), Theme.withAlpha(Theme.SAND, (int) (235 * alpha)));
		g.setTransform(old);
	}

	/**
	 * "같은 색 모래로 양쪽 벽을 두툼하게 이으면 사라져요" 그림 (메뉴의 모래 모드 설명과 모래 화면의 목표 카드가 함께 쓴다).
	 * 그림은 {@link SandHud#spanPicture} 가 그린다.
	 */
	static void paintSpanPicture(Graphics2D g, ColorPalettes.Look look, double x, double y, double w, double h) {
		SandHud.spanPicture(g, look, x, y, w, h);
	}

	// ------------------------------------------------------------------ 글자

	/** 천 단위 쉼표: 12,345. */
	static String number(long v) {
		return String.format(Locale.ROOT, "%,d", v);
	}

	/** 판의 시계: m:ss. */
	static String clock(long ms) {
		long sec = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
	}

	/** 판의 시계를 1/1000초까지: m:ss.mmm (통계와 결과 카드, 고정 폭 숫자로 그린다). */
	static String clockMs(long ms) {
		long sec = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d.%03d", sec / 60, sec % 60, ms % 1000);
	}
}
