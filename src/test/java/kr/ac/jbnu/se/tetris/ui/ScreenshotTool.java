package kr.ac.jbnu.se.tetris.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.JComponent;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 화면을 창 없이 그림 파일로 그린다(레이아웃 확인용). P1 도 AI 가 두게 해 판을 진행하며 메뉴, 대전 화면(두 창 크기),
 * 큰 동작 표시, 위험한 순간, 결과, 혼자 연습, 일시정지를 {@code build/screens/} 에 PNG 로 남긴다. 실행:
 * {@code gradlew screenshot}.
 */
public final class ScreenshotTool {

	private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 }, { 960, 540 } };

	private ScreenshotTool() {
	}

	public static void main(String[] args) {
		int code = 1;
		try {
			run(args);
			code = 0;
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			// 패널의 다시 그리기 타이머가 AWT 스레드를 살려 두므로, 실패했을 때도 직접 끝내야 gradlew screenshot 이 멈추지 않는다
			System.exit(code);
		}
	}

	private static void run(String[] args) throws Exception {
		System.setProperty("java.awt.headless", "true");
		File out = new File(args.length > 0 ? args[0] : "build/screens");
		out.mkdirs();
		try (AiCatalog catalog = AiCatalog.load(Path.of("models"))) {
			MenuPanel menu = new MenuPanel(catalog, s -> {
			});
			for (int[] wh : SIZES) {
				save(menu, wh[0], wh[1], new File(out, "menu-" + wh[0] + ".png"));
			}
			if (catalog.personas().size() > 3) {
				menu.select(1, 3); // 4단계 최상급 (페르소나는 4·5단계에서만 고른다)
				menu.select(2, 3); // 페르소나 줄의 넷째 항목
				save(menu, 1280, 720, new File(out, "menu-persona-1280.png"));
			}
			menu.select(0, 1); // 혼자 연습
			save(menu, 1280, 720, new File(out, "menu-solo-1280.png"));

			AiCatalog.Difficulty d = catalog.difficulties().get(2);
			AiCatalog.Persona p = catalog.personas().get(0);
			GameSession s = new GameSession(new GameSession.Settings(true, d, p, 0, GameModel.Scheduler.NONE), catalog,
					20261004L, x -> {
					});
			TimedMatch m = s.match;
			AiController p1 = new AiController(m.player(0), () -> Opponent.of(m.player(1).game()),
					new Brain(new BaselineScorer(), 0, null, Chooser.rng(20261004L, 1)), 250, 10);
			GamePanel panel = new GamePanel(new GamePanel.Actions() {
				@Override
				public void restart() {
				}

				@Override
				public void menu() {
				}
			});
			panel.setSession(s);
			run(s, p1, 30_000);
			for (int[] wh : SIZES) {
				save(panel, wh[0], wh[1], new File(out, "battle-" + wh[0] + ".png"));
			}
			// 큰 동작(콤보, T-spin, 테트리스)이 화면에 뜬 순간과, P1 이 위험한 순간(높은 더미 + 받을 가비지)을 하나씩
			boolean action = false;
			boolean danger = false;
			while (!m.isOver() && (!action || !danger)) {
				s.advance(p1.poll());
				for (int i = 0; i < 2 && !action; i++) {
					GameSession.Tally t = s.tallies[i];
					if (m.time() - t.lastActionAt == 400) {
						save(panel, 1280, 720, new File(out, "action-1280.png"));
						action = true;
					}
				}
				GameModel g = m.player(0).game();
				if (!danger && g.pendingLines() >= 3 && maxHeight(g) >= 10) {
					save(panel, 1280, 720, new File(out, "danger-1280.png"));
					danger = true;
				}
			}
			run(s, p1, 20 * 60_000);
			save(panel, 1280, 720, new File(out, "result-1280.png"));
			System.out.println("결과 " + m.result() + ", " + m.time() / 1000 + "초, 큰 동작 " + action + ", 위험 " + danger
					+ " → " + out);
			s.stop();

			GameSession solo = new GameSession(new GameSession.Settings(false, d, p, 3000, GameModel.Scheduler.S_MIX),
					catalog, 7L, x -> {
					});
			AiController me = new AiController(solo.match.player(0), () -> Opponent.NONE,
					new Brain(new BaselineScorer(), 0, null, Chooser.rng(7L, 1)), 200, 12);
			panel.setSession(solo);
			for (int t = 0; t < 40_000 && !solo.match.isOver(); t++) {
				solo.advance(me.poll());
			}
			save(panel, 1280, 720, new File(out, "solo-1280.png"));
			solo.togglePause();
			save(panel, 1280, 720, new File(out, "pause-1280.png"));
		}
	}

	private static int maxHeight(GameModel g) {
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				if (g.cell(r, c) != Grid.EMPTY) {
					return Grid.HEIGHT - r;
				}
			}
		}
		return 0;
	}

	private static void run(GameSession s, AiController p1, long untilMs) {
		while (!s.match.isOver() && s.match.time() < untilMs) {
			s.advance(p1.poll());
		}
	}

	private static void save(JComponent c, int w, int h, File file) throws Exception {
		c.setSize(w, h);
		c.doLayout();
		layoutAll(c);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		c.printAll(g);
		g.dispose();
		ImageIO.write(img, "png", file);
	}

	private static void layoutAll(java.awt.Container c) {
		c.doLayout();
		for (java.awt.Component child : c.getComponents()) {
			if (child instanceof java.awt.Container) {
				layoutAll((java.awt.Container) child);
			}
		}
	}
}
