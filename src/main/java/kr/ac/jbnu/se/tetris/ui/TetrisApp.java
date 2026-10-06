package kr.ac.jbnu.se.tetris.ui;

import java.awt.CardLayout;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;

/**
 * 게임 창 (docs/GAME.md). 메뉴와 대전 화면을 오간다. 모델 묶음은 {@code models/}(또는 {@code -Dtetris.models=<폴더>})에서
 * 읽는다. 실행: {@code gradlew run}.
 */
public final class TetrisApp {

	private final JFrame frame = new JFrame("Tetris Battle");
	private final CardLayout cards = new CardLayout();
	private final JPanel root = new JPanel(cards);
	private final AiCatalog catalog;
	private final GamePanel game;
	private final MenuPanel menu;
	private boolean fullscreen;
	private Rectangle windowedBounds;
	private GameSession session;
	private GameSession.Settings last;

	private TetrisApp(AiCatalog catalog) {
		this.catalog = catalog;
		this.game = new GamePanel(new GamePanel.Actions() {
			@Override
			public void restart() {
				start(last);
			}

			@Override
			public void menu() {
				showMenu();
			}
		});
		menu = new MenuPanel(catalog, this::start);
		root.add(menu, "menu");
		root.add(game, "game");
		frame.setContentPane(root);
		// F11: 테두리 없는 전체 화면 켜고 끄기 (발표용)
		root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), "fullscreen");
		root.getActionMap().put("fullscreen", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				toggleFullscreen();
			}
		});
		// 닫기 버튼을 눌렀을 때만 끝낸다. 전체 화면을 바꿀 때 부르는 dispose() 는 windowClosed 를 일으키므로 거기서
		// 끝내면 F11 에 게임이 꺼진다.
		frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		frame.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override
			public void windowClosing(java.awt.event.WindowEvent e) {
				stopSession();
				catalog.close();
				frame.dispose();
				System.exit(0);
			}
		});
		frame.pack();
		frame.setLocationRelativeTo(null);
		// 창이 화면(작업 영역)보다 크면 처음부터 최대화한다(1366×768 노트북, 150% 배율 등).
		Rectangle work = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
		if (frame.getWidth() > work.width || frame.getHeight() > work.height) {
			frame.setExtendedState(Frame.MAXIMIZED_BOTH);
		}
		if (Boolean.getBoolean("tetris.fullscreen")) {
			SwingUtilities.invokeLater(this::toggleFullscreen);
		}
	}

	private void toggleFullscreen() {
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
		SwingUtilities.invokeLater(() -> (session != null ? (JComponent) game : menu).requestFocusInWindow());
	}

	private void start(GameSession.Settings settings) {
		stopSession();
		last = settings;
		session = new GameSession(settings, catalog, System.currentTimeMillis(), s -> game.repaint());
		game.setSession(session);
		cards.show(root, "game");
		session.start();
		game.requestFocusInWindow();
	}

	private void showMenu() {
		stopSession();
		game.setSession(null);
		cards.show(root, "menu");
		SwingUtilities.invokeLater(menu::requestFocusInWindow); // 클릭하지 않아도 ↑↓·Enter 가 먹히게
	}

	private void stopSession() {
		if (session != null) {
			session.stop();
			session = null;
		}
	}

	public static void main(String[] args) {
		Path models = Path.of(System.getProperty("tetris.models", "models"));
		AiCatalog catalog = AiCatalog.load(models);
		SwingUtilities.invokeLater(() -> {
			TetrisApp app = new TetrisApp(catalog);
			app.frame.setVisible(true);
			app.menu.requestFocusInWindow();
		});
	}
}
