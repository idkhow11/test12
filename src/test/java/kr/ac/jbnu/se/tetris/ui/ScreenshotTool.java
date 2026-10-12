package kr.ac.jbnu.se.tetris.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.JComponent;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.settings.SaveData;

/**
 * 화면을 창 없이 그림 파일로 그린다(레이아웃 확인용, 실제 화면은 찍지 않는다). 실행: {@code gradlew screenshot}. 시계를 정해
 * 그리므로 언제 돌려도 같은 그림이다.
 * <ul>
 * <li>메뉴 ({@code build/screens/menu-<모드>-<너비>.png}): 게임처럼 오른쪽 위에 도움말·설정 단추를 붙이고(앞이 도움말), 모델
 * 상태를 확인한 뒤(사람 대 AI 시작 단추가 "AI 불러오는 중"에 머물지 않게) 모드마다(versus, solo, sand), 페르소나를 고른 사람 대
 * AI(persona), 도움말·설정 단추 줄에 초점(links). 초점 꺾쇠가 다 옮겨 간 뒤에 찍는다.</li>
 * <li>대전 화면 ({@code build/screens/}, 사람 대 AI, 혼자 연습): {@link BattleScreens} 가 시계를 정해 판 도중, 줄 지우기·하드드롭·
 * 방해 줄 효과, 큰 동작, 위험, 카운트다운과 GO!, 일시정지, 기권 확인, K.O., 결과 카드(새 기록), AI 모델 알림, AI 속도, 기권,
 * 색약 색상·무늬, 효과 끔, 번쩍임 줄이기를 1280×720 과 1920×1080 으로 그린다.</li>
 * <li>모래 모드 ({@code build/screens-sand/sand-<장면>-<너비>.png}): {@link SandScreens} 가 모래 AI 손과 정한 시계로 카운트다운,
 * 판 도중, 지우기, 단계 오름, 설정 바꾼 모습, 떨어지는 블록의 꼬리, 일시정지, 위험, 기다림, 끝, 결과 카드를 그린다. 예전 도구가
 * {@code build/screens/} 에 남긴 모래 그림({@code sand-*.png})은 지운다(옛 판이라 지금 화면과 다르다).</li>
 * </ul>
 * 저장 파일은 메모리에만 둔다(사용자 폴더를 읽거나 쓰지 않는다). IDE 에서 바로 돌려도 그렇게 되도록 시작할 때
 * {@code TestSandbox.apply()} 를 부른다(창 없음, 소리·설정 폴더·사람 판 기록 끔).
 */
public final class ScreenshotTool {

	private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 }, { 960, 540 } };

	private ScreenshotTool() {
	}

	public static void main(String[] args) {
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않게
		int code = 1;
		try {
			run(args);
			code = 0;
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			// 그리기가 띄운 AWT 스레드가 남을 수 있으므로, 실패했을 때도 직접 끝내야 gradlew screenshot 이 멈추지 않는다
			System.exit(code);
		}
	}

	private static void run(String[] args) throws Exception {
		File out = new File(args.length > 0 ? args[0] : "build/screens").getAbsoluteFile();
		out.mkdirs();
		SaveData save = SaveData.inMemory();
		save.updateRecords(r -> r.withSand(64_120, 5).withSand(98_350, 7)); // 메뉴의 최고 점수 칸이 보이게 두 판
		try (AiCatalog catalog = AiCatalog.load(Path.of("models"))) {
			menus(catalog, save, out);
			BattleScreens.render(catalog, out);
		}
		removeOldSandShots(out);
		SandScreens.run(new File(out.getParentFile(), "screens-sand"));
	}

	// ------------------------------------------------------------------ 메뉴

	private static void menus(AiCatalog catalog, SaveData save, File out) throws Exception {
		long[] now = { 600_000L };
		MenuPanel menu = new MenuPanel(catalog, save, new MenuPanel.Starter() {
			@Override
			public void start(GameSession.Settings settings) {
				// 화면 확인은 판을 시작하지 않는다
			}

			@Override
			public void startSand(int level) {
				// 화면 확인은 판을 시작하지 않는다
			}
		});
		menu.clock = () -> now[0];
		menu.addLink("도움말", () -> { // 게임(TetrisApp)과 같은 차례: 먼저 더한 것이 왼쪽
		});
		menu.addLink("설정", () -> {
		});
		menu.modelsChecked(); // 모델 파일은 불러오지 않는 묶음 그대로: 고른 AI 의 상태만 묻는다
		for (int[] wh : SIZES) {
			shot(menu, now, wh[0], wh[1], new File(out, "menu-versus-" + wh[0] + ".png"));
		}
		if (catalog.personas().size() > 3) {
			menu.select(MenuPanel.ROW_LEVEL, 3); // 4단계 최상급 (페르소나는 4·5단계에서만 고른다)
			menu.select(MenuPanel.ROW_PERSONA, 3); // 페르소나 줄의 넷째 항목
			shot(menu, now, 1280, 720, new File(out, "menu-persona-1280.png"));
			menu.select(MenuPanel.ROW_LEVEL, 1);
		}
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
		shot(menu, now, 1280, 720, new File(out, "menu-solo-1280.png"));
		shot(menu, now, 1920, 1080, new File(out, "menu-solo-1920.png"));
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SAND.ordinal());
		shot(menu, now, 1280, 720, new File(out, "menu-sand-1280.png"));
		shot(menu, now, 1920, 1080, new File(out, "menu-sand-1920.png"));
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.VERSUS.ordinal());
		menu.select(MenuPanel.ROW_LINK, 0);
		shot(menu, now, 1280, 720, new File(out, "menu-links-1280.png")); // 도움말·설정 단추 줄에 초점
	}

	/** 메뉴 한 장: 한 번 그려 초점 꺾쇠가 옮겨 갈 자리를 정한 뒤, 0.3초 뒤(꺾쇠가 다 옮겨 간 때)의 그림을 남긴다. */
	private static void shot(MenuPanel menu, long[] now, int w, int h, File file) throws Exception {
		save(menu, w, h, null);
		now[0] += 300;
		save(menu, w, h, file);
	}

	// ------------------------------------------------------------------ 모래 모드

	/** 예전 도구가 이 폴더에 남긴 모래 그림 (이름이 정확히 같은 것만 지운다). */
	private static final String[] OLD_SAND_SHOTS = { "sand-1280.png", "sand-1920.png", "sand-960.png",
			"sand-clear-1280.png", "sand-levelup-1280.png", "sand-colorblind-1280.png", "sand-danger-1280.png",
			"sand-pause-1280.png", "sand-over-1280.png", "sand-over-1920.png", "sand-newbest-1280.png" };

	/** 예전 도구가 이 폴더에 남긴 모래 그림을 지운다 (모래 그림은 이제 screens-sand 에 있다). */
	private static void removeOldSandShots(File out) {
		for (String name : OLD_SAND_SHOTS) {
			File f = new File(out, name);
			if (f.isFile() && !f.delete()) {
				System.out.println("옛 모래 그림을 지우지 못함: " + f);
			}
		}
	}

	// ------------------------------------------------------------------ 도구

	/** 화면 하나를 w×h 로 그려 PNG 로 남긴다 (BattleScreens 도 쓴다). {@code file} 이 null 이면 그리기만 한다. */
	static void save(JComponent c, int w, int h, File file) throws Exception {
		c.setSize(w, h);
		c.doLayout();
		layoutAll(c);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		c.printAll(g);
		g.dispose();
		if (file != null) {
			ImageIO.write(img, "png", file);
		}
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
