package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.JComponent;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.audio.FakeSounds;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.MenuMemory;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 메뉴·설정·도움말 화면, 색 묶음, 앱 아이콘을 창 없이 그림 파일로 그린다 (배치 확인용, 실제 화면은 찍지 않는다).
 * {@code build/screens-prep/} 에 1280 × 720 (몇 장은 1920 × 1080 도) 으로 남긴다:
 * <ul>
 * <li>{@code menu-<모드>-1280.png}: 모드마다 (사람 대 AI 시작 단추, 난이도·페르소나, AI 속도 80%, 혼자 연습, 모래 모드),
 * {@code menu-loading-1280.png}·{@code menu-problem-1280.png}·{@code menu-nomodels-1280.png}: 모델 상태(불러오는 중, 고른 AI 의
 * 모델이 없음, 모델 묶음이 없음), {@code menu-effects-off-1280.png}: 효과 끔(떨어지는 블록 없음), {@code menu-1920.png},
 * {@code menu-forfeit-1280.png}: 사람 대 AI 판을 기권하고 돌아온 메뉴(시작 단추 왼쪽에 고른 난이도의 전적, 오른쪽에 알림).</li>
 * <li>{@code settings-<갈래>-<너비>.png}: 조작·감도·화면·효과·소리·AI 속도. 조작은 키를 기다리는 모습, 맞바꾼 알림, 거절 알림,
 * 마우스를 올린 키(×)도 그린다. 효과는 미리 보기의 순간 셋(줄 지우기, 공격 받기, 끔)과 번쩍임 줄이기, 소리는 음소거·심장 소리
 * 끔·장치 없음(파일 없이 실행한 머리말과 함께), AI 속도는 80%.</li>
 * <li>{@code help-<너비>.png}: 도움말 (기본 키, 바꾼 키, 색약 색상 + 블록 무늬). 맨 아래 출처 줄.</li>
 * <li>{@code palettes.png}: 색 묶음 넷(기본·색약 × 무늬)을 보통 눈과 적색약·녹색약·청색약 흉내로.</li>
 * <li>{@code icons.png}, {@code icon-256.png}: 모든 크기의 아이콘(어두운·밝은 바탕, 작은 것은 확대).</li>
 * </ul>
 * 실행: {@code gradlew screensPreview} (build/screens-prep), 또는 ({@code gradlew testClasses} 뒤) {@code java
 * "-Djava.awt.headless=true" "-Dtetris.sound=off" "-Dtetris.config=off" "-Dtetris.records=off" -cp
 * "build/classes/java/main;build/classes/java/test;build/resources/main;<jackson·onnxruntime·junit-platform-launcher jar>"
 * kr.ac.jbnu.se.tetris.ui.ScreensPreview [폴더]} (Windows 는 경로 구분이 {@code ;}, PowerShell 에서는 {@code -D} 를 따옴표로
 * 감싼다). 메뉴 그림에는 jackson 이 필요하다(없으면 건너뛴다). 시계를 멈춰 그리므로 언제나 같은 그림이 나온다. 시작할 때
 * {@code TestSandbox.apply()} 를 불러 IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않는다(준 값은 덮지
 * 않는다, 그 클래스 때문에 {@code java} 로 돌릴 때는 junit-platform-launcher jar 가 필요하다). 사용자 설정 폴더는 읽지도 쓰지도
 * 않는다: 설정은 임시 폴더의 저장 파일(끝나면 지운다)과 {@link SaveData#inMemory()} 에 둔다. 소리는 장치를 열지 않는 흉내
 * 엔진({@link FakeSounds})이다.
 */
public final class ScreensPreview {

	private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 } };
	private static final long T0 = 1_000_000L;

	private ScreensPreview() {
	}

	public static void main(String[] args) {
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않게
		int code = 1;
		try {
			run(new File(args.length > 0 ? args[0] : "build/screens-prep"));
			code = 0;
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			System.exit(code); // 다시 그리기 타이머 등이 AWT 스레드를 살려 두어도 끝나게
		}
	}

	private static void run(File out) throws Exception {
		System.setProperty("java.awt.headless", "true");
		out.mkdirs();
		Path cfg = Files.createTempDirectory("tetris-screens-");
		Sound[] engine = { FakeSounds.ready() };
		try {
			drawSettings(out, cfg, engine);
			drawHelp(out);
			try {
				drawMenus(out, cfg);
			} catch (Throwable t) { // 클래스 경로에 jackson 이 없으면
				System.out.println("메뉴 그림은 건너뜀: " + t);
			}
			ImageIO.write(palettes(), "png", new File(out, "palettes.png"));
			ImageIO.write(icons(), "png", new File(out, "icons.png"));
			ImageIO.write(AppIcon.render(256), "png", new File(out, "icon-256.png"));
		} finally {
			engine[0].close();
			try (Stream<Path> files = Files.walk(cfg)) {
				for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(p);
				}
			}
		}
		System.out.println("그림 → " + out.getAbsolutePath());
	}

	// ------------------------------------------------------------------ 설정

	private static void drawSettings(File out, Path cfg, Sound[] engine) throws Exception {
		long[] now = { T0 };
		// 임시 폴더의 저장 파일: 사람이 보는 그대로 "바꾸면 바로 저장돼요"
		SaveData data = SaveData.open(cfg);
		List<AiCatalog.Difficulty> levels = levels();
		SettingsPanel sp = new SettingsPanel(data, () -> {
		}, s -> {
		}, () -> engine[0], levels);
		sp.clock = () -> now[0];
		for (SettingsPanel.Section s : SettingsPanel.Section.values()) {
			sp.open();
			sp.show(s);
			String first = switch (s) {
			case CONTROLS -> "key:hard_drop:0";
			case FEEL -> "das";
			case DISPLAY -> "colorBlind";
			case EFFECTS -> "effects";
			case SOUND -> "volume";
			case AI -> "aiSpeed";
			};
			sp.focus(first);
			for (int[] wh : SIZES) {
				save(sp, wh[0], wh[1], new File(out, "settings-" + s.name().toLowerCase() + "-" + wh[0] + ".png"));
			}
		}
		// 조작: 키를 기다림 → C (홀드의 키) 를 누르면 맞바꾸고 알린다 → Alt 는 거절
		sp.open();
		sp.show(SettingsPanel.Section.CONTROLS);
		sp.focus("key:hard_drop:0");
		press(sp, KeyEvent.VK_ENTER);
		release(sp, KeyEvent.VK_ENTER);
		now[0] += 180 * 3; // 숨 쉬는 빛의 밝은 때
		save(sp, 1280, 720, new File(out, "settings-capture-1280.png"));
		press(sp, KeyEvent.VK_C);
		release(sp, KeyEvent.VK_C);
		now[0] += 250;
		save(sp, 1280, 720, new File(out, "settings-swap-1280.png"));
		sp.focus("key:rotate_ccw:2");
		press(sp, KeyEvent.VK_ENTER);
		release(sp, KeyEvent.VK_ENTER);
		press(sp, KeyEvent.VK_ALT);
		release(sp, KeyEvent.VK_ALT);
		now[0] += 100;
		save(sp, 1280, 720, new File(out, "settings-reject-1280.png"));
		press(sp, KeyEvent.VK_ESCAPE);
		release(sp, KeyEvent.VK_ESCAPE);
		// 마우스를 올린 키: × 가 보인다
		sp.setSize(1280, 720);
		move(sp, 248 + 116 + 74 + 30, 280 + 6 * 44 + 15); // 홀드의 둘째 키(Shift)
		now[0] += 5000;
		save(sp, 1280, 720, new File(out, "settings-hover-1280.png"));
		move(sp, 5, 5);
		data.updateSettings(s -> s.withKeys(s.keys().reset(KeyAction.HARD_DROP).reset(KeyAction.HOLD)));

		// 감도: ARR 0
		sp.open();
		sp.show(SettingsPanel.Section.FEEL);
		data.updateSettings(s -> s.withArrMs(0).withDasMs(120));
		sp.focus("arr");
		save(sp, 1280, 720, new File(out, "settings-feel-arr0-1280.png"));
		data.updateSettings(s -> s.withArrMs(50).withDasMs(170));

		// 화면: 색약 색상 + 블록 무늬 + 고스트 끔
		sp.open();
		sp.show(SettingsPanel.Section.DISPLAY);
		data.updateSettings(s -> s.withColorBlind(true).withPatterns(true));
		sp.focus("patterns");
		for (int[] wh : SIZES) {
			save(sp, wh[0], wh[1], new File(out, "settings-display-cb-" + wh[0] + ".png"));
		}
		data.updateSettings(s -> s.withColorBlind(false).withPatterns(true).withGhost(false));
		save(sp, 1280, 720, new File(out, "settings-display-patterns-1280.png"));
		data.updateSettings(s -> s.withPatterns(false).withGhost(true));

		// 효과: 미리 보기의 순간 (줄 지우기 40ms, 공격 받기 1080ms), 강하게 + 흔들림, 번쩍임 줄이기, 끔
		sp.open();
		sp.show(SettingsPanel.Section.EFFECTS);
		sp.focus("effects");
		long cycleStart = (T0 / SettingsDemo.CYCLE_MS + 10) * SettingsDemo.CYCLE_MS;
		now[0] = cycleStart + 40;
		data.updateSettings(s -> s.withEffects(UserSettings.EFFECTS_HIGH));
		save(sp, 1280, 720, new File(out, "settings-effects-clear-1280.png"));
		now[0] = cycleStart + SettingsDemo.HIT_AT + 80;
		save(sp, 1280, 720, new File(out, "settings-effects-hit-1280.png"));
		sp.focus("calmFlash");
		data.updateSettings(s -> s.withCalmFlash(true).withShake(false));
		now[0] = cycleStart + 40;
		save(sp, 1280, 720, new File(out, "settings-effects-calm-1280.png"));
		sp.focus("effects");
		data.updateSettings(s -> s.withEffects(UserSettings.EFFECTS_OFF));
		save(sp, 1280, 720, new File(out, "settings-effects-off-1280.png"));
		data.updateSettings(s -> s.withEffects(UserSettings.DEFAULT_EFFECTS).withCalmFlash(false).withShake(true));
		now[0] = T0;

		// AI 속도 80%: 난이도마다 블록 하나에 드는 시간이 늘고 기본값(100%)이 아래에 보인다
		sp.open();
		sp.show(SettingsPanel.Section.AI);
		data.updateSettings(s -> s.withAiSpeed(80));
		sp.focus("aiSpeed");
		for (int[] wh : SIZES) {
			save(sp, wh[0], wh[1], new File(out, "settings-ai-80-" + wh[0] + ".png"));
		}
		data.updateSettings(s -> s.withAiSpeed(UserSettings.DEFAULT_AI_SPEED));

		// 소리: 음소거, 심장 소리 끔
		data.updateSettings(s -> s.withDefaultsKeepingMenu().withMuted(true).withHeartbeat(false));
		sp.open();
		sp.show(SettingsPanel.Section.SOUND);
		sp.focus("heartbeat");
		save(sp, 1280, 720, new File(out, "settings-sound-muted-1280.png"));
		data.updateSettings(s -> s.withMuted(false).withHeartbeat(true));

		// 소리 장치가 없는 PC, 파일 없이 실행(-Dtetris.config=off): 상태 카드와 머리말이 그것을 알린다
		Sound ready = engine[0];
		engine[0] = FakeSounds.noDevice();
		ready.close();
		SettingsPanel offline = new SettingsPanel(SaveData.inMemory(), () -> {
		}, s -> {
		}, () -> engine[0], levels);
		offline.clock = () -> now[0];
		offline.open();
		offline.show(SettingsPanel.Section.SOUND);
		offline.focus("test");
		press(offline, KeyEvent.VK_ENTER);
		release(offline, KeyEvent.VK_ENTER);
		save(offline, 1280, 720, new File(out, "settings-sound-nodevice-1280.png"));
	}

	/** 모델 묶음의 난이도 (jackson 이 없으면 빈 목록: AI 속도 표 없이 그린다). */
	private static List<AiCatalog.Difficulty> levels() {
		try {
			return AiCatalog.load(Path.of("models")).difficulties();
		} catch (Throwable t) {
			System.out.println("AI 속도 표는 건너뜀: " + t);
			return List.of();
		}
	}

	// ------------------------------------------------------------------ 도움말

	private static void drawHelp(File out) throws Exception {
		SaveData data = SaveData.inMemory();
		HelpPanel help = new HelpPanel(data::settings, () -> {
		});
		for (int[] wh : SIZES) {
			save(help, wh[0], wh[1], new File(out, "help-" + wh[0] + ".png"));
		}
		data.updateSettings(s -> s.withColorBlind(true).withPatterns(true));
		save(help, 1280, 720, new File(out, "help-cb-1280.png"));
		data.updateSettings(s -> s.withColorBlind(false).withPatterns(false)
				.withKeys(s.keys().assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_W)
						.assign(KeyAction.MOVE_LEFT, 1, KeyEvent.VK_A).assign(KeyAction.MOVE_RIGHT, 1, KeyEvent.VK_D)));
		save(help, 1280, 720, new File(out, "help-custom-1280.png"));
	}

	// ------------------------------------------------------------------ 메뉴

	private static void drawMenus(File out, Path cfg) throws Exception {
		long[] now = { 600_000L };
		try (AiCatalog catalog = AiCatalog.load(Path.of("models"))) {
			SaveData data = SaveData.inMemory();
			data.updateRecords(r -> r.withSand(146_314, 7).withSand(98_000, 5));
			MenuPanel menu = links(new MenuPanel(catalog, data, starter()));
			menu.clock = () -> now[0];
			menu.modelsChecked(); // 모델 파일은 불러오지 않는 묶음 그대로: 고른 AI 의 상태만 묻는다
			shot(menu, now, 1280, 720, new File(out, "menu-versus-1280.png"));
			shot(menu, now, 1920, 1080, new File(out, "menu-1920.png"));
			// 난이도 줄에 초점, 최상급이라 페르소나가 열림 (공격형)
			menu.select(MenuPanel.ROW_LEVEL, 3);
			menu.select(MenuPanel.ROW_PERSONA, 1);
			menu.select(MenuPanel.ROW_LEVEL, 3);
			shot(menu, now, 1280, 720, new File(out, "menu-versus-level-1280.png"));
			// AI 속도 80%: ‹ 80% › 와 블록 하나에 드는 시간
			menu.select(MenuPanel.ROW_LEVEL, 1);
			menu.select(MenuPanel.ROW_AI_SPEED, 3);
			shot(menu, now, 1280, 720, new File(out, "menu-aispeed-1280.png"));
			menu.select(MenuPanel.ROW_AI_SPEED, 5);
			// 혼자 연습 (모드 카드에 초점), 모래 모드 (최고 점수)
			menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
			shot(menu, now, 1280, 720, new File(out, "menu-solo-1280.png"));
			menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SAND.ordinal());
			menu.select(MenuPanel.ROW_SAND_LEVEL, 2);
			shot(menu, now, 1280, 720, new File(out, "menu-sand-1280.png"));
			shot(menu, now, 1920, 1080, new File(out, "menu-sand-1920.png"));
			// 설정·도움말 단추 줄에 초점
			menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.VERSUS.ordinal());
			menu.select(MenuPanel.ROW_LINK, 1);
			shot(menu, now, 1280, 720, new File(out, "menu-links-1280.png"));
			// 효과 끔: 떨어지는 블록이 없다
			data.updateSettings(s -> s.withEffects(UserSettings.EFFECTS_OFF));
			menu.select(MenuPanel.ROW_START, 0);
			shot(menu, now, 1280, 720, new File(out, "menu-effects-off-1280.png"));
			data.updateSettings(s -> s.withEffects(UserSettings.DEFAULT_EFFECTS));
			// 끝나지 않은 사람 대 AI 판을 떠난 뒤 (TetrisApp): 시작 단추 왼쪽에 고른 난이도(중급)의 전적, 오른쪽에 2초 알림
			int level = catalog.difficulties().get(1).id();
			data.updateRecords(r -> r.withVersus(level, PlayRecords.Outcome.WIN, 95_000)
					.withVersus(level, PlayRecords.Outcome.WIN, 81_000).withVersus(level, PlayRecords.Outcome.WIN, 70_000)
					.withVersus(level, PlayRecords.Outcome.LOSS, 60_000).withVersus(level, PlayRecords.Outcome.LOSS, 40_000));
			menu.notice(TetrisApp.FORFEIT_RECORDED);
			shot(menu, now, 1280, 720, new File(out, "menu-forfeit-1280.png"));

			// 모델을 불러오는 중 (게임을 막 켰을 때)
			MenuPanel loading = links(new MenuPanel(catalog, SaveData.inMemory(), starter()));
			loading.clock = () -> now[0];
			shot(loading, now, 1280, 720, new File(out, "menu-loading-1280.png"));
		}
		// 고른 AI 의 모델 파일이 없다: 기준 AI 로 대신 (메뉴 기억으로 그 단계를 고른 채로 연다)
		Path broken = cfg.resolve("broken-models");
		Files.createDirectories(broken);
		Files.writeString(broken.resolve("manifest.json"), String.join("\n", "{\"format\": \"models-1\", \"difficulties\": [",
				"{\"id\": 1, \"name\": \"초급\", \"model\": \"baseline\", \"think_ms\": 2200, \"inputs_per_sec\": 5, \"gravity_cap\": 2},",
				"{\"id\": 2, \"name\": \"중급\", \"model\": \"missing.onnx\", \"think_ms\": 1150, \"inputs_per_sec\": 6,"
						+ " \"gravity_cap\": 3, \"description\": \"기본기를 익히는 단계\"}],",
				"\"personas\": [{\"id\": \"neutral\", \"name\": \"중립\", \"model\": null}]}"),
				StandardCharsets.UTF_8);
		try (AiCatalog catalog = AiCatalog.load(broken)) {
			SaveData data = SaveData.inMemory();
			data.updateSettings(s -> s.withMenu(MenuMemory.EMPTY.with(MenuMemory.VERSUS_LEVEL, 2)));
			MenuPanel menu = links(new MenuPanel(catalog, data, starter()));
			menu.clock = () -> now[0];
			menu.modelsChecked();
			shot(menu, now, 1280, 720, new File(out, "menu-problem-1280.png"));
		}
		// 모델 묶음이 없다: 처음부터 기준 AI
		try (AiCatalog none = AiCatalog.load(cfg.resolve("no-models-here"))) {
			MenuPanel menu = links(new MenuPanel(none, SaveData.inMemory(), starter()));
			menu.clock = () -> now[0];
			menu.modelsChecked();
			shot(menu, now, 1280, 720, new File(out, "menu-nomodels-1280.png"));
		}
	}

	private static MenuPanel.Starter starter() {
		return new MenuPanel.Starter() {
			@Override
			public void start(GameSession.Settings settings) {
				// 미리보기는 판을 시작하지 않는다
			}

			@Override
			public void startSand(int level) {
				// 미리보기는 판을 시작하지 않는다
			}
		};
	}

	/** 게임처럼 오른쪽 위에 도움말·설정 단추를 붙인다. */
	private static MenuPanel links(MenuPanel menu) {
		menu.addLink("도움말", () -> {
		});
		menu.addLink("설정", () -> {
		});
		return menu;
	}

	/** 메뉴 한 장: 한 번 그려 초점 꺾쇠가 옮겨 갈 자리를 정한 뒤, 0.3초 뒤(꺾쇠가 다 옮겨 간 때)의 그림을 남긴다. */
	private static void shot(MenuPanel menu, long[] now, int w, int h, File file) throws Exception {
		save(menu, w, h, null);
		now[0] += 300;
		save(menu, w, h, file);
	}

	// ------------------------------------------------------------------ 색 묶음 판

	/** 행: 기본 / 기본 + 무늬 / 색약 / 색약 + 무늬. 열: 보통 눈, 적색약, 녹색약, 청색약 흉내. */
	private static BufferedImage palettes() {
		int cellW = 400;
		int cellH = 190;
		int left = 150;
		int top = 50;
		ColorPalettes.Look[] looks = { ColorPalettes.look(false, false), ColorPalettes.look(false, true),
				ColorPalettes.look(true, false), ColorPalettes.look(true, true) };
		BufferedImage sheet = new BufferedImage(left + 4 * cellW, top + looks.length * cellH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		Theme.antialias(g);
		Theme.background(g, sheet.getWidth(), sheet.getHeight());
		String[] cols = { "보통 눈", "적색약 흉내", "녹색약 흉내", "청색약 흉내" };
		g.setFont(Theme.font(16, true));
		for (int c = 0; c < cols.length; c++) {
			g.setColor(Theme.TEXT);
			Theme.centered(g, cols[c], left + c * cellW + cellW / 2.0, 32);
		}
		for (int r = 0; r < looks.length; r++) {
			BufferedImage swatch = swatch(looks[r], cellW - 16, cellH - 16);
			g.setFont(Theme.font(15, true));
			g.setColor(Theme.TEXT_DIM);
			g.drawString(looks[r].toString(), 14, top + r * cellH + cellH / 2);
			for (int c = 0; c < 4; c++) {
				BufferedImage img = c == 0 ? swatch : ColorPalettesTest.Cvd.simulate(swatch,
						ColorPalettesTest.Cvd.Kind.values()[c - 1]);
				g.drawImage(img, left + c * cellW + 8, top + r * cellH + 8, null);
			}
		}
		g.dispose();
		return sheet;
	}

	/** 블록 일곱 가지와 방해 줄, 고스트, 모래 네 색. */
	private static BufferedImage swatch(ColorPalettes.Look look, int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Theme.BOARD);
		g.fillRect(0, 0, w, h);
		int c = 24;
		PieceType[][] rows = { { PieceType.I, PieceType.J, PieceType.L, PieceType.O },
				{ PieceType.S, PieceType.T, PieceType.Z } };
		int y = 10;
		for (PieceType[] row : rows) {
			int x = 10;
			for (PieceType p : row) {
				SettingsPanel.piece(g, look, p, x, y, c);
				x += (p == PieceType.I ? 4 : p == PieceType.O ? 2 : 3) * c + 14;
			}
			if (row.length == 3) { // 방해 줄과 고스트
				for (int k = 0; k < 3; k++) {
					look.ghost(g, x + k * c, y, c, PieceType.T.code());
					look.block(g, x + k * c, y + c, c, ColorPalettes.GARBAGE, 255, false);
				}
			}
			y += 2 * c + 10;
		}
		for (int i = 0; i < SandColor.values().length; i++) { // 모래 블록 두 칸씩
			SandColor col = SandColor.values()[i];
			look.sandCell(g, 10 + i * 92, y + 4, 32, col.code(), 255, col::pattern);
			look.sandCell(g, 10 + i * 92 + 32, y + 4, 32, col.code(), 255, col::pattern);
		}
		g.dispose();
		return img;
	}

	// ------------------------------------------------------------------ 아이콘 판

	/** 위: 어두운 바탕, 아래: 밝은 바탕. 줄마다 실제 크기 전부, 그 옆에 작은 넷을 6배로 확대해 화소를 본다. */
	private static BufferedImage icons() {
		List<Image> list = AppIcon.images();
		int w = 1480;
		int half = 330;
		BufferedImage sheet = new BufferedImage(w, 2 * half, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = sheet.createGraphics();
		g.setColor(new Color(0x202020));
		g.fillRect(0, 0, w, half);
		g.setColor(new Color(0xF3F3F3));
		g.fillRect(0, half, w, half);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		for (int k = 0; k < 2; k++) {
			int top = k * half + 20;
			int x = 20;
			for (Image im : list) { // 실제 크기 (아래쪽 끝을 맞춘다)
				int s = im.getWidth(null);
				g.drawImage(im, x, top + 256 - s, null);
				x += s + 14;
			}
			int zx = x + 20;
			for (int i = 0; i < 4; i++) { // 16, 20, 24, 32 를 6배로
				Image im = list.get(i);
				int s = im.getWidth(null) * 6;
				g.drawImage(im, zx, top + 256 - s, s, s, null);
				zx += s + 16;
			}
		}
		g.dispose();
		return sheet;
	}

	// ------------------------------------------------------------------ 도우미

	private static void press(JComponent c, int code) {
		KeyEvent e = new KeyEvent(c, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
		for (KeyListener l : c.getKeyListeners()) {
			l.keyPressed(e);
		}
	}

	private static void release(JComponent c, int code) {
		KeyEvent e = new KeyEvent(c, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
		for (KeyListener l : c.getKeyListeners()) {
			l.keyReleased(e);
		}
	}

	private static void move(JComponent c, int x, int y) {
		MouseEvent e = new MouseEvent(c, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false);
		for (MouseMotionListener l : c.getMouseMotionListeners()) {
			l.mouseMoved(e);
		}
	}

	/** {@code c} 를 {@code w × h} 로 그린다. {@code file} 이 null 이면 그리기만 한다. */
	private static void save(JComponent c, int w, int h, File file) throws Exception {
		c.setSize(w, h);
		c.doLayout();
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		c.printAll(g);
		g.dispose();
		if (file != null) {
			ImageIO.write(img, "png", file);
		}
	}
}
