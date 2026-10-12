package kr.ac.jbnu.se.tetris.ui;

import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.settings.ConfigDir;
import kr.ac.jbnu.se.tetris.settings.FullscreenKey;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 게임 창 (docs/GAME.md). 메뉴, 대전 화면(사람 대 AI, 혼자 연습), 모래 모드 화면, 설정, 도움말을 오간다. 모델 묶음은
 * {@code models/}(또는 {@code -Dtetris.models=<폴더>})에서 읽고, 설정과 플레이 기록은 사용자 설정 폴더의 저장 파일
 * ({@link SaveData})에 둔다. 판은 시작할 때의 설정(키, DAS·ARR, AI 속도 …)으로 두고, 끝나거나 떠나면 기록에 더한다(판이 스스로
 * 남기고 소리도 낸다. 창은 다시 그리기만 한다). 실행: {@code gradlew run}.
 * <ul>
 * <li><b>메뉴 ↔ 판:</b> 120ms 동안 어두워졌다가 250ms 동안 밝아진다({@link AppFade}, SPEC V11). 메뉴에서 판으로 갈 때는 살아
 * 있는 메뉴가 어두워지고, 판을 떠날 때는 판을 바로 멈추고(기권·기록) 멈춘 그림이 어두워진다. 설정·도움말은 메뉴 오른쪽 위 단추로
 * 바로 오간다(열 때 MENU_SELECT, 돌아올 때 MENU_BACK). 메뉴로 돌아올 때 눌려 있던 키를 메뉴에 알린다: 결과 카드·설정에서 Enter 로
 * "메뉴로" 를 고르고 누르고 있어도 그 자동 반복으로 판이 시작되지 않는다.</li>
 * <li><b>기권 알림:</b> 끝나지 않은 사람 대 AI 판을 떠나면(기권, 결과 카드는 없다) 메뉴가 2초 동안 알린다: 전적에 패배로 남았으면
 * "기권 · 패배로 기록했어요", 남지 않았으면(카운트다운 중에 나갔거나 기준 AI 판) "기권했어요" ({@link #forfeitNotice}).</li>
 * <li><b>한 판 더:</b> 결과 화면의 "다시 하기" 는 같은 설정에 새 시드, "같은 판 다시" 는 같은 시드·같은 설정이다
 * ({@link GamePanel.Actions#restart(boolean)}, 결과 카드에서 고른다). 바로 바꾼다(어두워지지 않는다).</li>
 * <li><b>전체 화면 키:</b> 창 어디서나({@link KeyboardFocusManager} 의 KeyEventDispatcher) 사용자 설정의 전체 화면 키를
 * 듣는다. 누르고 있어도 한 번만 바꾼다({@link FullscreenKey}). 설정 화면이 그 키를 스스로 쓰는 동안(새 키를 기다림,
 * Delete·Backspace)은 건너뛴다.</li>
 * <li><b>창을 벗어나면 멈춤:</b> 창이 포커스를 잃거나 최소화되면 판(사람 대 AI, 혼자 연습, 모래 모드)을 멈춘다
 * ({@link GameSession#autoPause()}, {@link SandSession#autoPause()}). 메뉴는 떨어지는 블록과 숨 쉬는 빛을 멈춘다
 * ({@link MenuPanel#windowActive}). 전체 화면을 바꿀 때는 창을 닫았다 다시 열므로 잠깐 포커스를 잃지만, 이것은 멈추지 않는다(다시
 * 활성화될 때까지 무시).</li>
 * <li><b>시작:</b> 사람 대 AI 판의 기록(승률 예측망의 사람 판 자료, {@link GameSession#recordsDir()})은 게임 실행만 켠다
 * ({@link #recordHumanGames}: 속성이 없으면 {@code runs/human/data/games}, {@code -Dtetris.records=off} 나 다른 폴더를 주면
 * 그대로). Windows 에서는 타이머 간격을 1ms 로 둔다(깨지 않고 잠자는 스레드 하나, {@link #keepFineTimer()}). 기본 간격 15.6ms
 * 로는 메뉴·설정의 33ms 타이머가 47ms(21fps)로, 15ms 타이머가 가끔 31ms 로 돈다. Gradle 밖에서(IDE 에서 바로) 켰으면 ONNX
 * Runtime 의 DLL 을 사용자 폴더에 한 번 풀어 두고 다시 쓴다({@link #shareOrtNatives()}). 글꼴 넷은 배경 스레드가 먼저 읽는다.
 * 설정 파일의 경고는 켤 때의 것과 그 뒤의 것(저장 실패 까닭 등)을 모두 표준 오류에 한 줄씩 남긴다.</li>
 * <li><b>소리:</b> 켤 때 저장된 크기·음소거로 엔진을 시작하고({@link Sound#start()}), 창을 닫을 때 부드럽게 닫는다. 설정
 * 화면에서 바꾼 크기·음소거는 설정 화면이 바로 엔진에 넘긴다.</li>
 * </ul>
 */
public final class TetrisApp {

	private static final String MENU = "menu";
	private static final String GAME = "game";
	private static final String SAND = "sand";
	private static final String SETTINGS = "settings";
	private static final String HELP = "help";

	/** 사람 대 AI 판의 기록을 두는 곳 ({@code -Dtetris.records} 를 주지 않고 게임을 켰을 때, {@link #recordHumanGames}). */
	static final String HUMAN_RECORDS = "runs/human/data/games";
	private static final String RECORDS_PROPERTY = "tetris.records";
	/** 끝나지 않은 사람 대 AI 판을 떠났고 전적에 패배로 남았을 때 메뉴의 알림. */
	static final String FORFEIT_RECORDED = "기권 · 패배로 기록했어요";
	/** 끝나지 않은 사람 대 AI 판을 떠났지만 전적에 남지 않았을 때(카운트다운 중, 기준 AI 판) 메뉴의 알림. */
	static final String FORFEIT_NOT_RECORDED = "기권했어요";
	/** ONNX Runtime 의 Windows x64 DLL (jar 안의 {@code /ai/onnxruntime/native/win-x64/}). */
	static final List<String> ORT_DLLS = List.of("onnxruntime.dll", "onnxruntime4j_jni.dll",
			"onnxruntime_providers_shared.dll");
	private static final String ORT_RESOURCES = "/ai/onnxruntime/native/win-x64/";
	private static final String ORT_PATH_PROPERTY = "onnxruntime.native.path";

	private final JFrame frame = new JFrame("Tetris Battle");
	private final CardLayout cards = new CardLayout();
	private final JPanel root = new JPanel(cards);
	private final AppFade fade = new AppFade();
	private final FullscreenKey fullscreenKey = new FullscreenKey();
	/** 지금 눌려 있는 키 (화면을 바꿀 때 새 화면에 알린다: 앞 화면에서 누른 키의 자동 반복을 새 눌림으로 받지 않게). */
	private final Set<Integer> held = new HashSet<>();
	private final AiCatalog catalog;
	private final SaveData save;
	private final GamePanel game;
	private final SandPanel sand;
	private final MenuPanel menu;
	private final SettingsPanel settings;
	private final HelpPanel help;
	/** 지금 보이는 카드 이름. */
	private String shown = MENU;
	private boolean fullscreen;
	/** 전체 화면을 바꾸느라 창을 다시 만드는 중: 그 사이의 비활성화는 사람이 자리를 비운 것이 아니다. 다시 활성화되면 끝난다. */
	private boolean refitting;
	private Rectangle windowedBounds;
	private GameSession session;
	private GameSession.Settings last;
	private SandSession sandSession;
	private int lastSandLevel = 1;
	private long lastSandSeed;
	/** 판을 떠나 어두워지는 동안 기다리는 메뉴 알림(기권): 메뉴가 보일 때 띄운다({@link #showMenuNow()}). */
	private String pendingNotice;

	private TetrisApp(AiCatalog catalog, SaveData save) {
		this.catalog = catalog;
		this.save = save;
		this.game = new GamePanel(new GamePanel.Actions() {
			@Override
			public void restart(boolean sameSeed) {
				restartGame(sameSeed);
			}

			@Override
			public void menu() {
				leaveToMenu();
			}
		});
		this.sand = new SandPanel(new GamePanel.Actions() {
			@Override
			public void restart(boolean sameSeed) {
				restartSand(sameSeed);
			}

			@Override
			public void menu() {
				leaveToMenu();
			}
		}, save);
		menu = new MenuPanel(catalog, save, new MenuPanel.Starter() {
			@Override
			public void start(GameSession.Settings s) {
				fade.run(root, false, () -> startGame(s)); // 메뉴는 어두워지는 동안 바뀌지 않는다: 그림을 뜨지 않는다
			}

			@Override
			public void startSand(int level) {
				fade.run(root, false, () -> TetrisApp.this.startSand(level, System.currentTimeMillis()));
			}
		});
		settings = new SettingsPanel(save, this::backToMenu, us -> menu.repaint(), Sound::get, catalog.difficulties());
		help = new HelpPanel(save::settings, this::backToMenu);
		root.add(menu, MENU);
		root.add(game, GAME);
		root.add(sand, SAND);
		root.add(settings, SETTINGS);
		root.add(help, HELP);
		menu.addLink("도움말", this::showHelp); // 먼저 더한 것이 왼쪽
		menu.addLink("설정", this::showSettings);
		frame.setContentPane(root);
		frame.setGlassPane(fade);
		frame.setIconImages(AppIcon.images());
		KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::dispatchKey);
		// 닫기 버튼을 눌렀을 때만 끝낸다. 전체 화면을 바꿀 때 부르는 dispose() 는 windowClosed 를 일으키므로 거기서
		// 끝내면 전체 화면 키에 게임이 꺼진다.
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				stopSessions();
				catalog.close();
				Sound.get().close(); // 울리던 소리를 줄여 없앤 뒤 장치를 닫는다 (퍽 소리 없이)
				frame.dispose();
				System.exit(0);
			}

			@Override
			public void windowActivated(WindowEvent e) {
				refitting = false;
				menu.windowActive(true);
			}

			@Override
			public void windowDeactivated(WindowEvent e) {
				if (!refitting) {
					fullscreenKey.reset(); // 다른 창에서 뗀 키는 받지 못한다
					held.clear();
					menu.windowActive(false); // 뒤에 있는 동안 메뉴의 움직임을 멈춘다
					autoPause(); // 다른 창으로 감, 최소화
				}
			}

			@Override
			public void windowIconified(WindowEvent e) {
				autoPause();
			}
		});
		frame.pack();
		frame.setLocationRelativeTo(null);
		// 창이 화면(작업 영역)보다 크면 처음부터 최대화한다(1366×768 노트북, 150% 배율 등).
		Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		if (frame.getWidth() > work.width || frame.getHeight() > work.height) {
			frame.setExtendedState(Frame.MAXIMIZED_BOTH);
		}
		if (save.settings().startsFullscreen(System.getProperty(UserSettings.FULLSCREEN_PROPERTY))) {
			SwingUtilities.invokeLater(this::toggleFullscreen);
		}
	}

	// ------------------------------------------------------------------ 창 어디서나 듣는 키

	/**
	 * 모든 키 이벤트가 화면(패널)보다 먼저 지나는 곳. 화면을 바꾸느라 어두워지는 동안의 눌림은 버린다. 전체 화면 키는 여기서
	 * 처리하고 소비한다. 나머지는 화면으로 보낸다.
	 */
	private boolean dispatchKey(KeyEvent e) {
		int id = e.getID();
		int code = e.getKeyCode();
		if (id == KeyEvent.KEY_RELEASED) {
			held.remove(code);
			fullscreenKey.release(code);
			return false; // 뗌은 언제나 화면에 보낸다 (판의 키 추적기가 눌린 키를 놓게)
		}
		if (id == KeyEvent.KEY_PRESSED) {
			held.add(code);
		}
		if (fade.blocking()) {
			return true; // 어두워지는 동안: 시작을 두 번 하거나 사라지는 메뉴를 바꾸지 않게
		}
		if (id != KeyEvent.KEY_PRESSED || (settings.isShowing() && settings.takesKey(code))) {
			return false;
		}
		if (save.settings().keys().actionOf(code) != KeyAction.FULLSCREEN) {
			return false;
		}
		if (fullscreenKey.press(code, e.getWhen())) { // 누르고 있어도 한 번 (창을 다시 연 뒤의 자동 반복도)
			toggleFullscreen();
		}
		return true;
	}

	private void toggleFullscreen() {
		refitting = true; // 창을 닫았다 다시 여는 동안의 비활성화로 판을 멈추지 않는다
		frame.dispose(); // 테두리 설정은 창이 보이지 않을 때만 바꿀 수 있다
		if (fullscreen) {
			frame.setUndecorated(false);
			frame.setExtendedState(Frame.NORMAL);
			if (windowedBounds != null) {
				frame.setBounds(windowedBounds);
			}
		} else {
			windowedBounds = frame.getBounds();
			frame.setUndecorated(true);
			frame.setExtendedState(Frame.MAXIMIZED_BOTH);
		}
		fullscreen = !fullscreen;
		frame.setVisible(true);
		SwingUtilities.invokeLater(() -> shownComponent().requestFocusInWindow());
	}

	// ------------------------------------------------------------------ 화면 바꾸기

	private void showCard(String name) {
		shown = name;
		cards.show(root, name);
	}

	/** 지금 보이는 화면. */
	private Component shownComponent() {
		switch (shown) {
		case GAME:
			return game;
		case SAND:
			return sand;
		case SETTINGS:
			return settings;
		case HELP:
			return help;
		default:
			return menu;
		}
	}

	private void showSettings() {
		Sound.get().play(Sfx.MENU_SELECT);
		settings.open();
		settings.heldKeys(held); // 메뉴에서 연 Enter 를 누르고 있어도 그 자동 반복으로 갈래 안에 들어가지 않게
		showCard(SETTINGS);
		SwingUtilities.invokeLater(settings::requestFocusInWindow);
	}

	private void showHelp() {
		Sound.get().play(Sfx.MENU_SELECT);
		help.open();
		showCard(HELP);
		SwingUtilities.invokeLater(help::requestFocusInWindow);
	}

	/** 설정·도움말에서 메뉴로 (바로, 나가는 소리는 여기 한 곳에서). */
	private void backToMenu() {
		Sound.get().play(Sfx.MENU_BACK);
		showMenuNow();
	}

	/**
	 * 판에서 메뉴로 (판 화면의 메뉴 키, 기권). 판은 지금 바로 멈추고(기권·기록, 그동안 블록이 더 떨어지지 않게) 멈춘 그림이
	 * 어두워진 뒤 메뉴가 밝아진다.
	 */
	private void leaveToMenu() {
		fade.run(root, this::showMenuNow); // 지금 화면을 그림으로 뜬 뒤에
		String notice = stopSessions();
		if (notice != null) {
			pendingNotice = notice; // 메뉴가 보일 때(다 어두워진 뒤) 띄운다
		}
	}

	private void showMenuNow() {
		// 보통은 이미 멈췄다(leaveToMenu). 밝아지는 중에 떠나 AppFade 가 바로 불렀으면 여기서 기권한다
		String notice = stopSessions();
		if (notice != null) {
			pendingNotice = notice;
		}
		showCard(MENU);
		if (pendingNotice != null) {
			menu.notice(pendingNotice);
			pendingNotice = null;
		}
		menu.heldKeys(held); // 결과 카드·설정에서 Enter 로 돌아와 누르고 있어도 그 자동 반복으로 판을 시작하지 않게
		menu.repaint(); // 기록(모래 최고 점수)이 바뀌었을 수 있다
		SwingUtilities.invokeLater(menu::requestFocusInWindow); // 클릭하지 않아도 ↑↓·Enter 가 먹히게
	}

	/** 창을 벗어났다: 판을 멈춘다. */
	private void autoPause() {
		if (session != null) {
			session.autoPause();
		}
		if (sandSession != null) {
			sandSession.autoPause();
		}
	}

	// ------------------------------------------------------------------ 판

	/** 사람 대 AI 또는 혼자 연습. 설정에 정한 시드가 없으면 새 시드로. 사용자 설정과 기록은 {@link #save} 에서. */
	private void startGame(GameSession.Settings s) {
		stopSessions();
		pendingNotice = null; // 메뉴를 거치지 않은 판 바꾸기(다시 하기)의 기권은 알리지 않는다
		last = s;
		session = new GameSession(s, catalog, System.currentTimeMillis(), save, played -> game.repaint());
		game.setSession(session);
		showCard(GAME);
		session.start();
		game.requestFocusInWindow();
		pauseIfAway();
	}

	/** 결과 화면의 다시 하기 ({@code sameSeed} 면 같은 판 다시). 바로 바꾼다. */
	private void restartGame(boolean sameSeed) {
		if (last == null) {
			return;
		}
		GameSession played = session;
		startGame(sameSeed && played != null ? last.withSeed(played.seed) : last.withNewSeed());
	}

	/** 모래 모드: 시작 단계와 시드 ("같은 판 다시" 는 지난 판의 시드). */
	private void startSand(int level, long seed) {
		stopSessions();
		pendingNotice = null;
		lastSandLevel = level;
		lastSandSeed = seed;
		sandSession = new SandSession(seed, level, save, s -> sand.repaint());
		sand.setSession(sandSession);
		showCard(SAND);
		sandSession.start();
		sand.requestFocusInWindow();
		pauseIfAway();
	}

	private void restartSand(boolean sameSeed) {
		startSand(lastSandLevel, sameSeed ? lastSandSeed : System.currentTimeMillis());
	}

	/** 판을 시작하는 사이(화면이 어두워지는 0.12초)에 창을 떠났으면 바로 멈춘다(그때는 멈출 판이 없었다). */
	private void pauseIfAway() {
		if (frame.isShowing() && !frame.isActive() && !refitting) {
			autoPause();
		}
	}

	/**
	 * 진행 중인 판을 모두 멈춘다. 끝나지 않은 판은 떠나는 것이므로 기권으로 끝내고, 카운트다운이 끝난 판이면 기록에 더한다
	 * ({@link GameSession#forfeit()}: 사람 대 AI 는 패배, 혼자 연습은 그때까지의 기록. 모래 모드는 {@link SandSession#quit()}).
	 *
	 * @return 끝나지 않은 사람 대 AI 판을 기권으로 끝냈으면 메뉴에 띄울 알림({@link #forfeit}), 아니면 null. 메뉴로 갈 때만 쓴다
	 */
	private String stopSessions() {
		String notice = null;
		if (session != null) {
			notice = forfeit(session, save);
			session = null;
			game.setSession(null);
		}
		if (sandSession != null) {
			sandSession.quit();
			sandSession = null;
			sand.setSession(null);
		}
		return notice;
	}

	/**
	 * 판 하나를 기권으로 멈추고({@link GameSession#forfeit()}), 그것이 끝나지 않은 사람 대 AI 판이었으면 메뉴에 띄울 알림을 돌려준다
	 * ({@link #forfeitNotice}). 이미 끝난 판(결과 카드), 그 순간 스스로 끝난 판, 혼자 연습이면 null. 화면 스레드에서 부른다.
	 */
	static String forfeit(GameSession s, SaveData save) {
		boolean versus = s.settings.versusAi() && !s.over();
		int level = versus ? s.settings.difficulty().id() : 0;
		PlayRecords.Versus before = versus ? save.records().versus(level) : null;
		s.forfeit();
		return versus && s.forfeited() ? forfeitNotice(before, save.records().versus(level)) : null;
	}

	/**
	 * 끝나지 않은 사람 대 AI 판을 떠났을 때 메뉴의 알림: 그 난이도의 전적에 패배가 하나 늘었으면 {@value #FORFEIT_RECORDED},
	 * 아니면(카운트다운 중에 나갔거나 기준 AI 판이라 남기지 않음, {@link GameSession#forfeit()}) {@value #FORFEIT_NOT_RECORDED}.
	 *
	 * @param before 판을 멈추기 전 그 난이도의 전적
	 * @param after  기권한 뒤 그 난이도의 전적
	 */
	static String forfeitNotice(PlayRecords.Versus before, PlayRecords.Versus after) {
		return after.losses() > before.losses() ? FORFEIT_RECORDED : FORFEIT_NOT_RECORDED;
	}

	public static void main(String[] args) {
		recordHumanGames(); // 판을 만들기 전에: 사람 대 AI 판의 기록은 게임 실행만 켠다(GameSession.recordsDir 의 기본은 끔)
		keepFineTimer();
		shareOrtNatives(); // 모델을 불러오기 전에: ONNX Runtime 은 DLL 을 처음 불러올 때 이 경로를 읽는다
		preloadFonts();
		Path models = Path.of(System.getProperty("tetris.models", "models"));
		// 판 하나를 두는 게임이므로 모델 하나가 연산 스레드 여럿을 쓴다(점수는 스레드 수와 상관없이 같다, AiCatalog)
		AiCatalog catalog = AiCatalog.load(models, AiCatalog.GAME_THREADS);
		SaveData save = SaveData.open();
		// 켤 때의 경고와 그 뒤의 경고(판이 끝날 때 기록 저장 실패의 까닭 등)를 한 줄씩 (같은 글은 한 번)
		save.reportWarnings(w -> System.err.println("설정 파일: " + w));
		// 소리: 저장된 크기·음소거를 먼저 넘기고 시작한다(장치 열기와 소리 만들기는 믹서 스레드가 한다, 바로 돌아온다)
		UserSettings us = save.settings();
		Sound.get().setVolume(us.volume() / 100.0);
		Sound.get().setMuted(us.muted());
		Sound.start();
		SwingUtilities.invokeLater(() -> {
			TetrisApp app = new TetrisApp(catalog, save);
			app.frame.setVisible(true);
			app.menu.requestFocusInWindow();
			// 모델을 배경에서 미리 불러 둔다: 첫 판을 시작할 때 모델을 불러오느라 기다리지 않게, 그리고 쓸 수 없는 모델을
			// 메뉴에 미리 알리게(그동안 메뉴는 "AI 불러오는 중")
			Thread preload = new Thread(() -> {
				try {
					catalog.preload();
				} finally { // 미리 불러오기가 뜻밖의 오류로 끝나도 메뉴가 "불러오는 중" 에 머물지 않게
					SwingUtilities.invokeLater(app.menu::modelsChecked);
				}
			}, "model-preload");
			preload.setDaemon(true);
			preload.start();
		});
	}

	/**
	 * 사람 대 AI 판의 기록(승률 예측망의 사람 판 자료, {@link GameSession#recordsDir()})을 켠다: {@code tetris.records} 가 없을 때만
	 * {@value #HUMAN_RECORDS}(작업 폴더 기준, git 에 올리지 않는 폴더). 기록의 기본은 끔이라 IDE 에서 바로 돌린 시험·도구는 봇 판을
	 * 사람 판 자료에 섞지 않고, 게임 실행만 남긴다. {@code -Dtetris.records=off} 나 다른 폴더를 주면 그대로 둔다. 폴더는 첫 기록을
	 * 남길 때 만든다(새로 받은 저장소에는 {@code runs/} 가 없다, {@link GameSession#saveRecord()}).
	 */
	static void recordHumanGames() {
		recordHumanGames(System.getProperties());
	}

	/** {@link #recordHumanGames()} 를 {@code props} 에 한다 (시험은 진짜 시스템 속성 대신 따로 만든 것을 넘긴다). */
	static void recordHumanGames(Properties props) {
		if (props.getProperty(RECORDS_PROPERTY) == null) {
			props.setProperty(RECORDS_PROPERTY, HUMAN_RECORDS);
		}
	}

	/**
	 * Windows 의 타이머 간격을 게임이 켜져 있는 동안 1ms 로 둔다. HotSpot 은 10 의 배수가 아닌 시간만큼 잠자는 스레드가 있는 동안
	 * 1ms 간격을 켜 두므로, 깨지 않고 잠자는 스레드 하나를 둔다(판 도중에는 진행 스레드의 sleep(1) 이 이미 그렇게 한다. 판이 끝나면
	 * 진행 스레드도 끝나므로 결과 카드·메뉴·설정의 움직임은 이 스레드에 기댄다). 기본 간격 15.6ms 로는 Swing 타이머가 그 배수로만
	 * 깨어 메뉴·설정의 33ms 타이머가 47ms(21fps)로 돌고 15ms 타이머가 가끔 31ms 로 튄다. Windows 가 아니면 아무것도 하지 않는다.
	 */
	private static void keepFineTimer() {
		keepFineTimer(System.getProperty("os.name", ""));
	}

	/**
	 * {@link #keepFineTimer()} 의 본체: {@code osName} 이 Windows 면 깨지 않고 잠자는 데몬 스레드({@code timer-resolution})를 띄워
	 * 돌려준다(끼어들면 끝난다). 아니면 아무것도 하지 않고 null.
	 */
	static Thread keepFineTimer(String osName) {
		if (!osName.startsWith("Windows")) {
			return null;
		}
		Thread t = new Thread(() -> {
			while (true) {
				try {
					Thread.sleep(Integer.MAX_VALUE); // 10 의 배수가 아니다 (약 24.8일마다 다시 잔다)
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "timer-resolution");
		t.setDaemon(true);
		t.start();
		return t;
	}

	/**
	 * Gradle 밖에서(IDE 에서 바로) 켰을 때만: ONNX Runtime 의 Windows DLL 셋을 {@code %LOCALAPPDATA%\TetrisBattle\onnxruntime-<버전>}
	 * 에 한 번 풀어 두고 {@code onnxruntime.native.path} 로 넘긴다({@link #shareOrtNatives(Path, Function, Properties)}). 넘기지
	 * 않으면 ONNX Runtime 은 JVM 을 띄울 때마다 DLL 을 {@code %TEMP%\onnxruntime-java<수>} 에 푸는데, Windows 는 쓰는 중인 DLL 을
	 * 지우지 못해 한 번에 16MB 씩 남는다. Gradle 의 run·test·도구는 이미 {@code build/onnxruntime-native} 를 넘기므로 그때는 아무것도
	 * 하지 않는다. Windows x64 가 아니거나 버전·폴더를 모르거나 풀지 못하면 아무것도 하지 않는다(ONNX Runtime 이 예전처럼 임시
	 * 폴더에 푼다). 폴더 이름의 버전은 클래스 경로에 있는 jar 의 것이라({@link #ortVersion()}) jar 를 올리면 새 폴더에 다시 푼다.
	 */
	private static void shareOrtNatives() {
		String arch = System.getProperty("os.arch", "");
		if (System.getProperty(ORT_PATH_PROPERTY) != null || !System.getProperty("os.name", "").startsWith("Windows")
				|| !(arch.equals("amd64") || arch.equals("x86_64"))) {
			return;
		}
		String local = System.getenv("LOCALAPPDATA");
		String version = ortVersion();
		if (local == null || local.isBlank() || version == null) {
			return;
		}
		try {
			Path dir = Path.of(local, ConfigDir.APP_NAME, "onnxruntime-" + version);
			if (!shareOrtNatives(dir, TetrisApp.class::getResource, System.getProperties())) {
				System.err.println("ONNX Runtime DLL 을 " + dir + " 에 두지 못해 임시 폴더에 풀어 씀");
			}
		} catch (RuntimeException e) { // 쓸 수 없는 폴더 이름 등: 예전처럼 둔다
			System.err.println("ONNX Runtime DLL 을 사용자 폴더에 두지 못함: " + e);
		}
	}

	/**
	 * {@code dir} 에 ONNX Runtime 의 Windows DLL 셋({@link #ORT_DLLS})을 맞춰 두고 {@code props} 의 {@code onnxruntime.native.path}
	 * 를 그 폴더로 한다. 없거나 크기가 다른 것만 클래스 경로({@code resource}, jar 안의 {@code /ai/onnxruntime/native/win-x64/})에서
	 * 같은 폴더의 임시 파일로 복사한 뒤 이름을 바꿔 끼운다(반쯤 쓴 DLL 이 그 이름으로 남지 않는다). 크기가 같으면 건드리지 않는다:
	 * 다른 게임 창이 그 DLL 을 쓰는 중일 수 있다. 셋 중 하나라도 클래스 경로에 없거나, 읽기·쓰기에 실패하면 경로를 두지 않는다.
	 *
	 * @param resource 클래스 경로의 이름(예: {@code /ai/onnxruntime/native/win-x64/onnxruntime.dll}) → 그 자원, 없으면 null
	 * @return 셋이 모두 준비돼 경로를 두었으면 true
	 */
	static boolean shareOrtNatives(Path dir, Function<String, URL> resource, Properties props) {
		try {
			URL[] urls = new URL[ORT_DLLS.size()];
			for (int i = 0; i < urls.length; i++) {
				urls[i] = resource.apply(ORT_RESOURCES + ORT_DLLS.get(i));
				if (urls[i] == null) {
					return false;
				}
			}
			Files.createDirectories(dir);
			for (int i = 0; i < urls.length; i++) {
				Path target = dir.resolve(ORT_DLLS.get(i));
				URLConnection c = urls[i].openConnection();
				long size = c.getContentLengthLong();
				try (InputStream in = c.getInputStream()) { // 크기만 볼 때도 열린 파일을 닫는다
					if (size >= 0 && Files.isRegularFile(target) && Files.size(target) == size) {
						continue;
					}
					Path tmp = Files.createTempFile(dir, ORT_DLLS.get(i), ".tmp");
					try {
						Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
						Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					} finally {
						Files.deleteIfExists(tmp);
					}
				}
			}
			props.setProperty(ORT_PATH_PROPERTY, dir.toString());
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * 클래스 경로에 있는 ONNX Runtime jar 의 버전 (jar MANIFEST 의 Implementation-Version), 모르면 null. 클래스를 초기화하지 않으므로
	 * DLL 을 불러오지 않는다.
	 */
	static String ortVersion() {
		try {
			Package p = Class.forName("ai.onnxruntime.OrtEnvironment", false, TetrisApp.class.getClassLoader()).getPackage();
			String v = p == null ? null : p.getImplementationVersion();
			return v != null && v.matches("[0-9A-Za-z][0-9A-Za-z._-]{0,39}") ? v : null;
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}

	/** 함께 배포한 글꼴 넷을 배경 스레드에서 미리 읽는다: 첫 메뉴를 그리는 화면 스레드가 글꼴 파일을 읽느라(약 55ms) 멈추지 않게. */
	private static void preloadFonts() {
		Thread t = new Thread(() -> Theme.font(14, false), "font-preload");
		t.setDaemon(true);
		t.start();
	}
}
