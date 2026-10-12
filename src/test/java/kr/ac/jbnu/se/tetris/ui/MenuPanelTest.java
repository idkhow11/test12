package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.settings.MenuMemory;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 메뉴 (GAME.md 7장): 모드 셋(사람 대 AI, 혼자 연습, 모래 모드)을 키보드로 오가며 모드마다 그 설정만 지나가고, Enter 가
 * 그 모드의 판을 시작한다. 사람 대 AI 의 처음 난이도는 중급, 페르소나는 4·5단계에서만, AI 속도는 사용자 설정의 값이고 메뉴에서
 * 바꾸면 바로 저장한다. 고른 것은 메뉴 기억에 남아 다음에 그대로 열린다. 소리, 모델 상태, 설정·도움말 단추, 움직이는 장식,
 * 누르고 있는 Enter 의 자동 반복, Shift+Tab, 창이 뒤에 있을 때 멈춤, 그려 둔 움직이지 않는 부분, 고른 난이도의 전적 한 줄,
 * 기권 뒤의 짧은 알림. 모델 묶음은 {@code models/manifest.json} 만 읽는다(모델 파일은 불러오지 않는다).
 */
class MenuPanelTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	private static final AiCatalog CATALOG = AiCatalog.load(Path.of("models"));

	/** 메뉴가 시작한 판을 모은다. */
	private static final class Started implements MenuPanel.Starter {
		final List<GameSession.Settings> games = new ArrayList<>();
		final List<Integer> sand = new ArrayList<>();

		@Override
		public void start(GameSession.Settings settings) {
			games.add(settings);
		}

		@Override
		public void startSand(int level) {
			sand.add(level);
		}

		GameSession.Settings last() {
			return games.get(games.size() - 1);
		}
	}

	private static void keys(MenuPanel menu, int... codes) {
		for (int code : codes) {
			menu.press(code);
		}
	}

	/** 창이 보내는 것처럼 키 이벤트를 듣는 곳에 보낸다 (자동 반복 = 뗌 없이 다시 누름). */
	private static void key(MenuPanel menu, int id, int code, int modifiers) {
		KeyEvent e = new KeyEvent(menu, id, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
		for (KeyListener l : menu.getKeyListeners()) {
			if (id == KeyEvent.KEY_PRESSED) {
				l.keyPressed(e);
			} else {
				l.keyReleased(e);
			}
		}
	}

	private static void key(MenuPanel menu, int id, int code) {
		key(menu, id, code, 0);
	}

	private static BufferedImage paint(MenuPanel menu, int w, int h) {
		menu.setSize(w, h);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		menu.printAll(g);
		g.dispose();
		return img;
	}

	/** 가상 화면(1280 × 720) 좌표를 누른다. 먼저 그려서 누를 자리를 만든다. */
	private static void click(MenuPanel menu, int x, int y) {
		paint(menu, 1280, 720);
		MouseEvent e = new MouseEvent(menu, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1);
		for (MouseListener l : menu.getMouseListeners()) {
			l.mousePressed(e);
		}
	}

	@Test
	void enterRightAwayStartsHumanVersusIntermediateNeutral() {
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), started);
		assertEquals(MenuPanel.Mode.VERSUS, menu.mode());
		assertEquals(MenuPanel.ROW_START, menu.focus(), "처음 초점은 시작 단추");
		keys(menu, KeyEvent.VK_ENTER);
		GameSession.Settings s = started.last();
		assertEquals(GameSession.Mode.VERSUS, s.mode());
		assertEquals("중급", s.difficulty().name(), "처음 단계는 중급 (GAME.md 6.1)");
		assertEquals(AiCatalog.NEUTRAL, s.persona().id());
		assertEquals(100, s.aiSpeed(), "AI 속도는 처음엔 100%");
		assertNull(s.seed(), "판마다 새 시드");
	}

	@Test
	void versusStartsWithTheSavedAiSpeed() {
		Started started = new Started();
		SaveData save = SaveData.inMemory();
		save.updateSettings(u -> u.withAiSpeed(130));
		MenuPanel menu = new MenuPanel(CATALOG, save, started);
		keys(menu, KeyEvent.VK_ENTER);
		assertEquals(130, started.last().aiSpeed());
		assertEquals(started.last().difficulty().atSpeed(130), started.last().aiDifficulty());
		assertEquals(List.of(MenuPanel.Mode.VERSUS, MenuPanel.Mode.SOLO, MenuPanel.Mode.SAND),
				List.of(MenuPanel.Mode.values()), "모드는 셋");
	}

	@Test
	void theAiSpeedControlStepsByTenAndSavesAtOnce() {
		Started started = new Started();
		SaveData save = SaveData.inMemory();
		MenuPanel menu = new MenuPanel(CATALOG, save, started);
		List<Sfx> sounds = new ArrayList<>();
		menu.sfx = sounds::add;
		assertEquals(List.of(MenuPanel.ROW_MODE, MenuPanel.ROW_LEVEL, MenuPanel.ROW_AI_SPEED, MenuPanel.ROW_START),
				menu.rows(), "난이도 다음 줄이 AI 속도 (중급이라 페르소나 줄은 없다)");
		keys(menu, KeyEvent.VK_UP);
		assertEquals(MenuPanel.ROW_AI_SPEED, menu.focus(), "시작 단추 바로 위");
		keys(menu, KeyEvent.VK_LEFT, KeyEvent.VK_LEFT);
		assertEquals(80, save.settings().aiSpeed(), "← 두 번: 10 씩, 바로 저장");
		assertEquals(80, menu.aiSpeed());
		keys(menu, KeyEvent.VK_LEFT, KeyEvent.VK_LEFT, KeyEvent.VK_LEFT, KeyEvent.VK_LEFT);
		assertEquals(UserSettings.MIN_AI_SPEED, save.settings().aiSpeed(), "50% 에서 멈춘다");
		assertEquals(Sfx.DENY, sounds.get(sounds.size() - 1), "끝에서는 DENY");
		for (int i = 0; i < 15; i++) {
			keys(menu, KeyEvent.VK_RIGHT);
		}
		assertEquals(UserSettings.MAX_AI_SPEED, save.settings().aiSpeed(), "150% 에서 멈춘다");
		keys(menu, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER);
		assertEquals(150, started.last().aiSpeed(), "시작한 판의 AI 속도");

		// 마우스: ‹ › 단추 (가상 화면 x 150~410, y 452~494 의 양 끝), 끝을 넘는 단추는 DENY
		click(menu, 150 + 260 - 20, 452 + 21); // › (이미 150%)
		assertEquals(150, save.settings().aiSpeed());
		assertEquals(Sfx.DENY, sounds.get(sounds.size() - 1));
		click(menu, 150 + 20, 452 + 21); // ‹
		assertEquals(140, save.settings().aiSpeed());
		assertEquals(Sfx.MENU_MOVE, sounds.get(sounds.size() - 1));
		assertEquals(MenuPanel.ROW_AI_SPEED, menu.focus());
		// 설정 화면이 바꾼 값이 메뉴에 바로 보인다 (같은 값을 쓴다)
		save.updateSettings(u -> u.withAiSpeed(70));
		assertEquals(70, menu.aiSpeed());
	}

	@Test
	void personasOpenOnlyAtTheTopTwoLevels() {
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), started);
		assertFalse(menu.rows().contains(MenuPanel.ROW_PERSONA), "중급에서는 페르소나 줄을 건너뛴다");
		keys(menu, KeyEvent.VK_UP, KeyEvent.VK_UP, KeyEvent.VK_RIGHT, KeyEvent.VK_RIGHT); // 난이도 → 최상급
		assertTrue(menu.rows().contains(MenuPanel.ROW_PERSONA), "최상급이면 페르소나 줄이 열린다");
		keys(menu, KeyEvent.VK_DOWN, KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER); // 페르소나 → 둘째(공격형)
		assertEquals("최상급", started.last().difficulty().name());
		assertEquals("attacker", started.last().persona().id());
		keys(menu, KeyEvent.VK_UP, KeyEvent.VK_UP, KeyEvent.VK_LEFT, KeyEvent.VK_ENTER); // 상급으로 내리면 고른 페르소나는 쓰지 않는다
		assertEquals("상급", started.last().difficulty().name());
		assertEquals(AiCatalog.NEUTRAL, started.last().persona().id());
	}

	@Test
	void practiceAndSandShowOnlyTheirOwnOptions() {
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), started);
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
		assertEquals(List.of(MenuPanel.ROW_MODE, MenuPanel.ROW_SPEED, MenuPanel.ROW_GARBAGE, MenuPanel.ROW_START),
				menu.rows());
		keys(menu, KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT, KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER);
		GameSession.Settings s = started.last();
		assertEquals(GameSession.Mode.SOLO, s.mode());
		assertEquals(5000, s.gravityCapG(), "블록 속도 '빠르게' = 초당 5칸");
		assertEquals(GameModel.Scheduler.S_3, s.scheduler());

		keys(menu, KeyEvent.VK_UP, KeyEvent.VK_UP, KeyEvent.VK_RIGHT); // 모래 모드
		assertEquals(MenuPanel.Mode.SAND, menu.mode());
		assertEquals(List.of(MenuPanel.ROW_MODE, MenuPanel.ROW_SAND_LEVEL, MenuPanel.ROW_START), menu.rows());
		keys(menu, KeyEvent.VK_RIGHT); // 끝이라 그대로
		assertEquals(MenuPanel.Mode.SAND, menu.mode());
		keys(menu, KeyEvent.VK_ENTER);
		assertEquals(List.of(1), started.sand, "처음 단계는 1");
		keys(menu, KeyEvent.VK_DOWN, KeyEvent.VK_RIGHT, KeyEvent.VK_RIGHT, KeyEvent.VK_SPACE);
		assertEquals(List.of(1, 3), started.sand);
		assertEquals(1, started.games.size(), "모래 모드는 대전 판을 시작하지 않는다");
	}

	@Test
	void whatWasChosenIsRememberedForTheNextLaunch() {
		SaveData save = SaveData.inMemory();
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, save, started);
		// 사람 대 AI: 불가능 · 수비형 (셋째 페르소나)
		menu.select(MenuPanel.ROW_LEVEL, 4);
		menu.select(MenuPanel.ROW_PERSONA, 2);
		menu.select(MenuPanel.ROW_START, 0);
		keys(menu, KeyEvent.VK_ENTER);
		// 혼자 연습: 아주 빠르게, 섞어서
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
		menu.select(MenuPanel.ROW_SPEED, 4);
		menu.select(MenuPanel.ROW_GARBAGE, 2);
		keys(menu, KeyEvent.VK_ENTER);
		// 모래 모드: 7단계 (마지막으로 시작한 모드)
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SAND.ordinal());
		menu.select(MenuPanel.ROW_SAND_LEVEL, 6);
		keys(menu, KeyEvent.VK_ENTER);
		assertEquals(List.of(7), started.sand);

		MenuMemory m = save.settings().menu();
		assertEquals(MenuMemory.MODE_SAND, m.get(MenuMemory.MODE));
		assertEquals("5", m.get(MenuMemory.VERSUS_LEVEL), "난이도는 순서가 아니라 id");
		assertEquals(CATALOG.personas().get(2).id(), m.get(MenuMemory.VERSUS_PERSONA));
		assertEquals("8", m.get(MenuMemory.SOLO_SPEED), "초당 칸 수");
		assertEquals(GameModel.Scheduler.values()[2].json(), m.get(MenuMemory.SOLO_GARBAGE));
		assertEquals("7", m.get(MenuMemory.SAND_LEVEL));

		// 다시 켠 게임의 메뉴: 모래 모드 7단계가 골라져 있고, 다른 모드의 설정도 그대로
		Started next = new Started();
		MenuPanel again = new MenuPanel(CATALOG, save, next);
		assertEquals(MenuPanel.Mode.SAND, again.mode());
		assertEquals(MenuPanel.ROW_START, again.focus(), "Enter 한 번이면 지난번 판");
		keys(again, KeyEvent.VK_ENTER);
		assertEquals(List.of(7), next.sand);
		again.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
		keys(again, KeyEvent.VK_ENTER);
		assertEquals(8000, next.last().gravityCapG());
		assertEquals(GameModel.Scheduler.values()[2], next.last().scheduler());
		again.select(MenuPanel.ROW_MODE, MenuPanel.Mode.VERSUS.ordinal());
		keys(again, KeyEvent.VK_ENTER);
		assertEquals("불가능", next.last().difficulty().name());
		assertEquals(CATALOG.personas().get(2).id(), next.last().persona().id());

		// 페르소나가 잠긴 단계로 시작해도 마지막으로 고른 페르소나는 기억에 남는다
		again.select(MenuPanel.ROW_LEVEL, 0);
		keys(again, KeyEvent.VK_ENTER);
		assertEquals(AiCatalog.NEUTRAL, next.last().persona().id(), "초급은 중립");
		assertEquals(CATALOG.personas().get(2).id(), save.settings().menu().get(MenuMemory.VERSUS_PERSONA));
		assertEquals("1", save.settings().menu().get(MenuMemory.VERSUS_LEVEL));
	}

	@Test
	void anOldOrForeignMemoryFallsBackToTheDefaults() {
		SaveData save = SaveData.inMemory();
		save.updateSettings(u -> u.withMenu(MenuMemory.EMPTY.with(MenuMemory.MODE, "spectate-or-unknown")
				.with(MenuMemory.VERSUS_LEVEL, 99).with(MenuMemory.VERSUS_PERSONA, "gone").with(MenuMemory.SOLO_SPEED, 4.5)));
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, save, started);
		assertEquals(MenuPanel.Mode.VERSUS, menu.mode(), "모르는 모드면 사람 대 AI");
		keys(menu, KeyEvent.VK_ENTER);
		assertEquals("중급", started.last().difficulty().name(), "목록에 없는 난이도면 처음 값");
		assertEquals(AiCatalog.NEUTRAL, started.last().persona().id());
	}

	@Test
	void menuSoundsFollowTheAudioTable() {
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), started);
		List<Sfx> sounds = new ArrayList<>();
		menu.sfx = sounds::add;
		keys(menu, KeyEvent.VK_DOWN); // 시작 단추가 맨 아래: 조용히
		assertEquals(List.of(), sounds);
		keys(menu, KeyEvent.VK_UP); // 줄이 바뀜
		assertEquals(List.of(Sfx.MENU_MOVE), sounds);
		keys(menu, KeyEvent.VK_UP, KeyEvent.VK_LEFT, KeyEvent.VK_LEFT); // 난이도: 초급까지, 그다음은 끝
		assertEquals(List.of(Sfx.MENU_MOVE, Sfx.MENU_MOVE, Sfx.MENU_MOVE, Sfx.DENY), sounds);
		sounds.clear();
		keys(menu, KeyEvent.VK_DOWN, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT); // 시작 단추의 ←→ 는 조용히
		assertEquals(List.of(Sfx.MENU_MOVE, Sfx.MENU_MOVE), sounds);
		sounds.clear();
		keys(menu, KeyEvent.VK_ENTER);
		assertEquals(List.of(Sfx.MENU_SELECT), sounds, "시작");
		// 잠긴 페르소나(초급)를 누르면 DENY, 고를 수 있는 단계에서 누르면 MENU_MOVE
		sounds.clear();
		click(menu, 450 + 200, 452 + 21);
		assertEquals(List.of(Sfx.DENY), sounds);
		menu.select(MenuPanel.ROW_LEVEL, 3);
		sounds.clear();
		click(menu, 450 + 200, 452 + 21);
		assertEquals(List.of(Sfx.MENU_MOVE), sounds);
		assertEquals(MenuPanel.ROW_PERSONA, menu.focus());
	}

	@Test
	void linksGetTheirOwnRowAboveTheModes() {
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), started);
		assertFalse(menu.rows().contains(MenuPanel.ROW_LINK), "단추가 없으면 줄도 없다");
		AtomicInteger settings = new AtomicInteger();
		AtomicInteger help = new AtomicInteger();
		menu.addLink("도움말", help::incrementAndGet);
		menu.addLink("설정", settings::incrementAndGet);
		assertEquals(MenuPanel.ROW_LINK, menu.rows().get(0));
		keys(menu, KeyEvent.VK_UP, KeyEvent.VK_UP, KeyEvent.VK_UP, KeyEvent.VK_UP, KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER);
		assertEquals(MenuPanel.ROW_LINK, menu.focus());
		assertEquals(0, help.get());
		assertEquals(1, settings.get(), "둘째 단추(설정)를 연다");
		assertTrue(started.games.isEmpty(), "단추 줄의 Enter 는 판을 시작하지 않는다");
	}

	@Test
	void modelStatusIsLoadingThenReadyOrFallback(@TempDir Path dir) throws IOException {
		// 모델 파일이 없는 불가능(불러 보지만 없으므로 기준 AI 로 대신)과 기준 AI 만 쓰는 중급 (처음 고른 것은 둘째)
		Files.writeString(dir.resolve("manifest.json"), String.join("\n", "{\"format\": \"models-1\", \"difficulties\": [",
				"{\"id\": 5, \"name\": \"불가능\", \"model\": \"missing.onnx\", \"think_ms\": 225, \"inputs_per_sec\": 25,"
						+ " \"gravity_cap\": 12, \"personas\": true},",
				"{\"id\": 2, \"name\": \"중급\", \"model\": \"baseline\", \"think_ms\": 1150, \"inputs_per_sec\": 6, \"gravity_cap\": 3}],",
				"\"personas\": [{\"id\": \"neutral\", \"name\": \"중립\", \"model\": null}]}"),
				StandardCharsets.UTF_8);
		try (AiCatalog catalog = AiCatalog.load(dir)) {
			MenuPanel menu = new MenuPanel(catalog, SaveData.inMemory(), new Started());
			assertEquals(MenuPanel.ModelStatus.LOADING, menu.modelStatus(), "미리 불러오기가 끝나기 전");
			paint(menu, 1280, 720);
			menu.modelsChecked();
			assertEquals(MenuPanel.ModelStatus.READY, menu.modelStatus(), "중급은 기준 AI 라 바로 쓸 수 있다");
			menu.select(MenuPanel.ROW_LEVEL, 0);
			assertEquals(MenuPanel.ModelStatus.FALLBACK, menu.modelStatus(), "고른 AI 의 모델이 없다");
			paint(menu, 1280, 720);
		}
		AiCatalog none = AiCatalog.load(dir.resolve("no-models-here"));
		assertEquals(MenuPanel.ModelStatus.FALLBACK, new MenuPanel(none, null, new Started()).modelStatus(),
				"모델 묶음이 없으면 처음부터 기준 AI");
	}

	@Test
	void fallingBlocksAreStatelessAndStayInTheMargins() {
		// 같은 시각이면 같은 그림, 효과를 끄면 그리지 않는다
		SaveData save = SaveData.inMemory();
		MenuPanel menu = new MenuPanel(CATALOG, save, new Started());
		menu.clock = () -> 600_000L;
		BufferedImage a = paint(menu, 1280, 720);
		BufferedImage b = paint(menu, 1280, 720);
		assertEquals(0, differences(a, b), "멈춘 시계면 같은 그림");
		save.updateSettings(u -> u.withEffects(UserSettings.EFFECTS_OFF));
		BufferedImage off = paint(menu, 1280, 720);
		assertTrue(differences(a, off) > 200, "효과를 끄면 떨어지는 블록이 없다");
		for (long i = 0; i < 400; i++) {
			int cell = MenuDecor.cell(i);
			assertTrue(cell >= MenuDecor.MIN_CELL && cell <= MenuDecor.MAX_CELL);
			assertTrue(MenuDecor.speed(MenuDecor.MAX_CELL) > MenuDecor.speed(MenuDecor.MIN_CELL), "큰 블록이 빠르다");
			assertEquals(-4.0 * cell, MenuDecor.top(i, i * MenuDecor.SPAWN_MS), 1e-9, "나올 때는 화면 위");
		}
	}

	@Test
	void paintsEveryModeWithoutModelsOrSaveFile() {
		AiCatalog none = AiCatalog.load(Path.of("no-models-here")); // 기준 AI 만, 페르소나는 중립 하나
		MenuPanel bare = new MenuPanel(none, null, new Started());
		MenuPanel full = new MenuPanel(CATALOG, SaveData.inMemory(), new Started());
		full.addLink("설정", () -> {
		});
		for (MenuPanel menu : List.of(bare, full)) {
			for (MenuPanel.Mode m : MenuPanel.Mode.values()) {
				menu.select(MenuPanel.ROW_MODE, m.ordinal());
				paint(menu, 1280, 720);
				paint(menu, 800, 450);
			}
		}
		bare.modelsChecked();
		paint(bare, 1280, 720);
		assertNull(none.personas().get(0).model(), "모델이 없어도 중립 페르소나로 그린다");
	}

	@Test
	void anEnterStillHeldFromTheScreenBeforeDoesNotStartAGame() {
		Started started = new Started();
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), started);
		// 결과 카드에서 Enter 로 "메뉴로" 를 고르고 계속 누르고 있다: 창이 화면을 바꾸며 눌린 키를 넘긴다
		menu.heldKeys(List.of(KeyEvent.VK_ENTER));
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER); // 운영체제의 자동 반복
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER);
		assertTrue(started.games.isEmpty(), "누르고 있는 Enter 의 반복으로 판을 시작하지 않는다");
		key(menu, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER);
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER);
		assertEquals(1, started.games.size(), "떼었다 다시 누르면 시작한다");
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER);
		assertEquals(1, started.games.size(), "메뉴에서 누른 Enter 도 누르고 있으면 한 번");
		key(menu, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER);
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_UP);
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_UP);
		assertEquals(MenuPanel.ROW_LEVEL, menu.focus(), "↑↓ 는 누르고 있으면 계속 옮겨 간다");
		// 초점을 잃으면(다른 화면·창) 눌린 키 기록을 지운다: 그 사이에 뗀 키를 받지 못했어도 다음 눌림은 먹힌다
		key(menu, KeyEvent.KEY_RELEASED, KeyEvent.VK_UP);
		menu.heldKeys(List.of(KeyEvent.VK_SPACE));
		for (FocusListener l : menu.getFocusListeners()) {
			l.focusLost(new FocusEvent(menu, FocusEvent.FOCUS_LOST));
		}
		menu.select(MenuPanel.ROW_START, 0);
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE);
		assertEquals(2, started.games.size());
	}

	@Test
	void shiftTabGoesUpLikeInTheSettings() {
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), new Started());
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
		assertEquals(MenuPanel.ROW_AI_SPEED, menu.focus(), "Shift+Tab 은 위 줄로");
		key(menu, KeyEvent.KEY_RELEASED, KeyEvent.VK_TAB);
		key(menu, KeyEvent.KEY_PRESSED, KeyEvent.VK_TAB);
		assertEquals(MenuPanel.ROW_START, menu.focus(), "Tab 은 아래 줄로");
	}

	@Test
	void aBackgroundWindowFreezesTheFallingBlocks() {
		long[] now = { 600_000L };
		MenuPanel menu = new MenuPanel(CATALOG, SaveData.inMemory(), new Started());
		menu.clock = () -> now[0];
		BufferedImage before = paint(menu, 1280, 720);
		menu.windowActive(false);
		now[0] += 5_000;
		assertEquals(0, differences(before, paint(menu, 1280, 720)), "창이 뒤에 있는 동안 블록과 빛이 멈춘다");
		menu.windowActive(true);
		assertEquals(0, differences(before, paint(menu, 1280, 720)), "다시 앞에 오면 멈춘 자리에서 이어진다");
		now[0] += 1_000;
		assertTrue(differences(before, paint(menu, 1280, 720)) > 0, "그리고 다시 떨어진다");
	}

	@Test
	void theRememberedStillPartLooksLikeAFreshPaint() {
		long[] now = { 600_000L };
		SaveData save = SaveData.inMemory();
		MenuPanel menu = new MenuPanel(CATALOG, save, new Started());
		menu.clock = () -> now[0];
		menu.addLink("설정", () -> {
		});
		paint(menu, 1280, 720); // 움직이지 않는 부분을 그려 둔다
		for (int step = 0; step < 4; step++) {
			switch (step) {
			case 1 -> menu.select(MenuPanel.ROW_LEVEL, 3); // 고른 것과 초점
			case 2 -> save.updateSettings(u -> u.withColorBlind(true)); // 모드 카드의 블록 색
			case 3 -> {
				menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SAND.ordinal());
				save.updateRecords(r -> r.withSand(12_345, 4)); // 최고 점수 카드
			}
			default -> {
				// 그대로
			}
			}
			paint(menu, 1280, 720); // 바뀐 것을 그려 둔다 (꺾쇠는 이때부터 옮겨 간다)
			now[0] += 1_000; // 꺾쇠가 다 옮겨 간 뒤
			BufferedImage remembered = paint(menu, 1280, 720);
			MenuPanel fresh = new MenuPanel(CATALOG, save, new Started());
			fresh.clock = () -> now[0];
			fresh.addLink("설정", () -> {
			});
			fresh.select(MenuPanel.ROW_MODE, menu.mode().ordinal());
			if (step >= 1) {
				fresh.select(MenuPanel.ROW_LEVEL, 3);
			}
			fresh.select(menu.focus(), step == 3 ? MenuPanel.Mode.SAND.ordinal() : step >= 1 ? 3 : 0);
			assertEquals(0, differences(remembered, paint(fresh, 1280, 720)), "그려 둔 그림을 붙여도 같다 (" + step + ")");
		}
		// 그려 둔 그림 위에서도 누를 자리가 그대로다
		click(menu, 150 + 30, 314 + 21); // 시작 단계 1
		click(menu, 150 + 30, 314 + 21);
		assertEquals(MenuPanel.ROW_SAND_LEVEL, menu.focus());
	}

	@Test
	void theVersusCardShowsTheRecordOfTheChosenLevel() {
		assertNull(MenuPanel.recordLine(PlayRecords.Versus.NONE), "한 판도 없으면 그리지 않는다");
		assertEquals("전적 0승 2패", MenuPanel.recordLine(new PlayRecords.Versus(0, 2, 0, 0, 0, 0)), "이긴 적이 없으면 연승은 뺀다");
		assertEquals("전적 3승 2패 · 최고 3연승", MenuPanel.recordLine(new PlayRecords.Versus(3, 2, 0, 1, 3, 41_000)));
		assertEquals("전적 3승 2패 · 1무 · 최고 2연승", MenuPanel.recordLine(new PlayRecords.Versus(3, 2, 1, 0, 2, 41_000)));

		long[] now = { 600_000L };
		SaveData save = SaveData.inMemory();
		save.updateSettings(u -> u.withEffects(UserSettings.EFFECTS_OFF)); // 떨어지는 블록 없이 견준다
		MenuPanel menu = new MenuPanel(CATALOG, save, new Started());
		menu.clock = () -> now[0];
		int level = CATALOG.difficulties().get(1).id(); // 처음 고른 중급
		assertNull(menu.versusRecordLine());
		BufferedImage empty = paint(menu, 1280, 720); // 움직이지 않는 부분을 그려 둔다 (전적 없음)
		save.updateRecords(r -> r.withVersus(level, PlayRecords.Outcome.WIN, 60_000).withVersus(level,
				PlayRecords.Outcome.LOSS, 30_000));
		assertEquals("전적 1승 1패 · 최고 1연승", menu.versusRecordLine());
		BufferedImage shown = paint(menu, 1280, 720);
		int changed = changedOnlyIn(empty, shown, new Rectangle(140, 570, 340, 40));
		assertTrue(changed > 50, "기록이 바뀌면 그려 둔 부분을 다시 그리고, 시작 단추 왼쪽에만 보인다 (" + changed + ")");
		MenuPanel fresh = new MenuPanel(CATALOG, save, new Started());
		fresh.clock = () -> now[0];
		assertEquals(0, differences(shown, paint(fresh, 1280, 720)), "그려 둔 그림도 새로 그린 것과 같다");

		menu.select(MenuPanel.ROW_LEVEL, 0); // 초급: 한 판도 없다
		assertNull(menu.versusRecordLine(), "고른 난이도의 전적만");
		menu.select(MenuPanel.ROW_LEVEL, 1);
		menu.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
		assertEquals("전적 1승 1패 · 최고 1연승", menu.versusRecordLine());
		SaveData noRecords = SaveData.inMemory();
		noRecords.updateSettings(u -> u.withEffects(UserSettings.EFFECTS_OFF));
		MenuPanel bare = new MenuPanel(CATALOG, noRecords, new Started());
		bare.clock = () -> now[0];
		bare.select(MenuPanel.ROW_MODE, MenuPanel.Mode.SOLO.ordinal());
		assertEquals(0, differences(paint(bare, 1280, 720), paint(menu, 1280, 720)), "혼자 연습 카드에는 그리지 않는다");
	}

	@Test
	void aNoticeShowsBesideTheStartButtonForTwoSecondsThenGoes() {
		long[] now = { 600_000L };
		SaveData save = SaveData.inMemory();
		save.updateSettings(u -> u.withEffects(UserSettings.EFFECTS_OFF));
		MenuPanel menu = new MenuPanel(CATALOG, save, new Started());
		menu.clock = () -> now[0];
		BufferedImage before = paint(menu, 1280, 720);
		assertNull(menu.noticeText());
		menu.notice(TetrisApp.FORFEIT_RECORDED);
		assertEquals(TetrisApp.FORFEIT_RECORDED, menu.noticeText());
		Rectangle right = new Rectangle(800, 565, 335, 50); // 시작 단추(x 490~790) 오른쪽, 내용의 오른쪽 끝(1130)까지
		BufferedImage shown = paint(menu, 1280, 720);
		int full = changedOnlyIn(before, shown, right);
		assertTrue(full > 300, "시작 단추 오른쪽에만 알약이 보인다 (" + full + ")");
		now[0] += MenuPanel.NOTICE_MS - MenuPanel.NOTICE_FADE_MS / 2; // 끝에 옅어지는 중
		assertEquals(TetrisApp.FORFEIT_RECORDED, menu.noticeText());
		BufferedImage fading = paint(menu, 1280, 720);
		assertTrue(changedOnlyIn(before, fading, right) > 0, "아직 보인다");
		assertTrue(changedOnlyIn(shown, fading, right) > 0, "옅어진다");
		now[0] += MenuPanel.NOTICE_FADE_MS;
		assertNull(menu.noticeText(), MenuPanel.NOTICE_MS + "ms 뒤에는 없다");
		assertEquals(0, differences(before, paint(menu, 1280, 720)), "사라진 뒤에는 알림 전과 같은 그림");
		menu.notice(TetrisApp.FORFEIT_NOT_RECORDED);
		assertEquals(TetrisApp.FORFEIT_NOT_RECORDED, menu.noticeText(), "새 알림");
	}

	/** 두 그림에서 다른 화소의 수. 사각형 {@code r} 밖에 다른 화소가 있으면 실패한다. */
	private static int changedOnlyIn(BufferedImage a, BufferedImage b, Rectangle r) {
		int n = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					assertTrue(r.contains(x, y), "바뀐 화소 (" + x + ", " + y + ") 가 " + r + " 밖에 있다");
					n++;
				}
			}
		}
		return n;
	}

	private static int differences(BufferedImage a, BufferedImage b) {
		int n = 0;
		for (int y = 0; y < a.getHeight(); y += 2) {
			for (int x = 0; x < a.getWidth(); x += 2) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					n++;
				}
			}
		}
		return n;
	}
}
