package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 도움말: 공격표가 규칙(docs/RULES.md 9장)과 같고, 조작은 지금 키를 보이고, 그림은 설정의 색약 색상으로 그리고, Esc·마우스로
 * 메뉴에 돌아간다.
 */
class HelpPanelTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	@Test
	void attackTableIsTheRuleBook() {
		// RULES 9.2: 싱글/더블/트리플/테트리스 1/2/3/4, T-spin 싱글/더블/트리플 2/4/6, mini 싱글/더블 1/2
		List<HelpPanel.AttackRow> rows = HelpPanel.attackRows();
		assertEquals(3, rows.size());
		assertEquals("보통", rows.get(0).name());
		assertArrayEquals(new int[] { 1, 2, 3, 4 }, rows.get(0).attack());
		assertEquals("T-spin", rows.get(1).name());
		assertArrayEquals(new int[] { 2, 4, 6, -1 }, rows.get(1).attack());
		assertEquals("T-spin mini", rows.get(2).name());
		assertArrayEquals(new int[] { 1, 2, -1, -1 }, rows.get(2).attack());
		// RULES 9.3: B2B +1, 퍼펙트 클리어 +10, COMBO = [0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5] (화면의 "N COMBO" = 그 뒤의 combo)
		assertEquals(1, HelpPanel.b2bBonus());
		assertEquals(10, HelpPanel.perfectClearBonus());
		List<HelpPanel.ComboGroup> combos = HelpPanel.comboGroups();
		assertEquals(List.of(new HelpPanel.ComboGroup(1, 2, 1), new HelpPanel.ComboGroup(3, 4, 2),
				new HelpPanel.ComboGroup(5, 6, 3), new HelpPanel.ComboGroup(7, 9, 4), new HelpPanel.ComboGroup(10, -1, 5)),
				combos);
		assertEquals(List.of("1·2", "3·4", "5·6", "7~9", "10~"), combos.stream().map(HelpPanel.ComboGroup::range).toList());
		assertEquals("4", new HelpPanel.ComboGroup(4, 4, 2).range());
	}

	@Test
	void controlsShowTheKeysOfNow() {
		SaveData data = SaveData.inMemory();
		HelpPanel help = new HelpPanel(data::settings, () -> {
		});
		List<String[]> rows = help.controlRows();
		assertEquals(KeyAction.values().length, rows.size(), "모든 동작");
		assertArrayEquals(new String[] { "하드드롭", "Space" }, rows.get(3));
		assertArrayEquals(new String[] { "시계 회전", "↑ / X" }, rows.get(4));
		assertArrayEquals(new String[] { "전체 화면", "F11" }, rows.get(rows.size() - 1));
		data.updateSettings(s -> s.withKeys(s.keys().assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_W)));
		assertArrayEquals(new String[] { "하드드롭", "W" }, help.controlRows().get(3), "바꾼 키가 바로 보인다");
		paint(help, 1280, 720);
	}

	@Test
	void theFooterNamesTheBundledSoundsAndFonts() {
		// SPEC V15: 함께 배포하는 효과음(CC0)과 글꼴(OFL)의 출처 한 줄, 자세한 것은 CREDITS.md
		assertTrue(HelpPanel.CREDITS.contains("Kenney (CC0)"), HelpPanel.CREDITS);
		assertTrue(HelpPanel.CREDITS.contains("Pretendard, Barlow Condensed (OFL)"), HelpPanel.CREDITS);
		assertTrue(HelpPanel.CREDITS.endsWith("CREDITS.md"), HelpPanel.CREDITS);
		BufferedImage img = paint(new HelpPanel(UserSettings::defaults, () -> {
		}), 1280, 720);
		int lit = 0;
		for (int y = 698; y < 713; y++) {
			for (int x = 300; x < 980; x++) {
				int rgb = img.getRGB(x, y);
				if (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF) > 3 * 90) {
					lit++;
				}
			}
		}
		assertTrue(lit > 150, "맨 아래 가운데에 출처 글자가 있다: " + lit);
	}

	@Test
	void theHelpTellsHowGamesEndAndHowToForfeit() {
		// 모래 모드의 끝나는 조건은 메뉴·판 옆 목표 카드와 같은 말 (판의 점선)
		assertTrue(List.of(HelpPanel.SAND_LINES).contains("점선 위까지 쌓이면 끝나요"));
		assertTrue(List.of(MenuPanel.SAND_RULES).contains("모래가 점선 위까지 쌓이면 끝나요"));
		assertTrue(MenuPanel.SAND_RULES[2].contains("콤보 배율"), "판 옆 카드의 이름과 같다: " + MenuPanel.SAND_RULES[2]);
		// 지우기 규칙(sand-0.3: 양쪽 벽을 이은 덩어리가 640알 이상)은 판 옆 목표 카드와 같은 말이다. 얇은 띠가 있으면 목표 카드가
		// 밝히는 낱말("두툼하게")도 들어 있다
		assertEquals(SandHud.GOAL_LINES[0], MenuPanel.SAND_RULES[1]);
		assertEquals(SandHud.GOAL_LINES[0], HelpPanel.SAND_LINES[1] + " " + HelpPanel.SAND_LINES[2], "도움말은 두 줄로 나눠 쓴다");
		assertTrue(SandHud.GOAL_LINES[0].contains(SandHud.GOAL_MARK), SandHud.GOAL_LINES[0]);
		// 기권은 두 번, 같은 판 다시는 같은 블록 순서, AI 속도·페르소나·기준 AI
		assertTrue(HelpPanel.SUBTITLE.contains("Esc를 두 번") && HelpPanel.SUBTITLE.contains("기권(패배)"), HelpPanel.SUBTITLE);
		assertTrue(HelpPanel.SUBTITLE.contains("같은 판 다시") && HelpPanel.SUBTITLE.contains("같은 블록 순서"));
		for (String word : new String[] { "AI 속도", "페르소나", "기준 AI" }) {
			assertTrue(HelpPanel.CONTROLS_NOTE.contains(word), word);
		}
		// 조작 카드의 마지막 줄 아래(가상 화면 y 470~500)에 그 한 줄이 그려진다
		BufferedImage img = paint(new HelpPanel(UserSettings::defaults, () -> {
		}), 1280, 720);
		int lit = 0;
		for (int y = 476; y < 492; y++) {
			for (int x = 80; x < 400; x++) {
				int rgb = img.getRGB(x, y);
				if (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF) > 3 * 90) {
					lit++;
				}
			}
		}
		assertTrue(lit > 100, "조작 카드 아래 줄: " + lit);
	}

	@Test
	void picturesUseTheColourBlindPaletteWhenItIsOn() {
		// 같은 색이 이어지면 사라지는 모래 그림은 색약 색상을 켠 사람에게 그 색으로 보여야 한다
		SaveData data = SaveData.inMemory();
		HelpPanel help = new HelpPanel(data::settings, () -> {
		});
		assertSame(ColorPalettes.standard(), help.look());
		BufferedImage normal = paint(help, 1280, 720);
		data.updateSettings(s -> s.withColorBlind(true).withPatterns(true));
		assertSame(ColorPalettes.look(true, true), help.look(), "설정을 바꾸면 다음 그리기부터");
		BufferedImage colourBlind = paint(help, 1280, 720);
		// 모래 카드의 그림 칸 (가상 화면 = 1280 × 720 에서는 화소와 같다)
		int changed = 0;
		for (int y = 544; y < 544 + 76; y++) {
			for (int x = 866; x < 866 + 72; x++) {
				if (normal.getRGB(x, y) != colourBlind.getRGB(x, y)) {
					changed++;
				}
			}
		}
		assertTrue(changed > 72 * 76 / 3, "모래 그림이 색약 색상으로 바뀐다: " + changed);
		// 조작 카드의 글자와 키는 그대로다
		for (int y = 160; y < 480; y += 3) {
			for (int x = 70; x < 420; x += 3) {
				assertEquals(normal.getRGB(x, y), colourBlind.getRGB(x, y), "조작 카드 (" + x + ", " + y + ")");
			}
		}
	}

	@Test
	void escAndTheBackButtonGoBack() {
		int[] backs = { 0 };
		HelpPanel help = new HelpPanel(UserSettings::defaults, () -> backs[0]++);
		help.setSize(1280, 720);
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE);
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE); // 자동 반복
		assertEquals(1, backs[0]);
		key(help, KeyEvent.KEY_RELEASED, KeyEvent.VK_ESCAPE);
		// 메뉴에서 이 화면을 연 Enter 를 누르고 있으면 자동 반복이 여기로 온다: 닫히지 않는다
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER);
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_SPACE);
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_DOWN);
		assertEquals(1, backs[0]);
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE);
		assertEquals(2, backs[0]);
		help.open(); // 다시 열면 눌린 키 기록을 지운다 (뗌을 못 받았어도)
		key(help, KeyEvent.KEY_PRESSED, KeyEvent.VK_ESCAPE);
		assertEquals(3, backs[0]);
		click(help, 100, 62); // 왼쪽 위 메뉴 버튼
		assertEquals(4, backs[0]);
		click(help, 640, 400);
		assertEquals(4, backs[0]);
	}

	@Test
	void paintsAtEverySizeWithLongKeyNames() {
		KeyBindings keys = KeyBindings.defaults().assign(KeyAction.HOLD, 2, KeyEvent.VK_PAGE_DOWN)
				.assign(KeyAction.ROTATE_CW, 2, KeyEvent.VK_SCROLL_LOCK).assign(KeyAction.PAUSE, 1, KeyEvent.VK_BACK_SPACE)
				.assign(KeyAction.RETRY, 2, KeyEvent.VK_INSERT);
		UserSettings settings = UserSettings.defaults().withKeys(keys);
		HelpPanel help = new HelpPanel(() -> settings, () -> {
		});
		for (int[] wh : new int[][] { { 1280, 720 }, { 1920, 1080 }, { 960, 540 }, { 1000, 1000 } }) {
			BufferedImage img = paint(help, wh[0], wh[1]);
			int lit = 0;
			for (int y = 0; y < img.getHeight(); y += 5) {
				for (int x = 0; x < img.getWidth(); x += 5) {
					if ((img.getRGB(x, y) & 0xFF) > 200) {
						lit++;
					}
				}
			}
			assertTrue(lit > 50, "글자와 그림이 그려졌다: " + wh[0] + "×" + wh[1]);
		}
	}

	private static BufferedImage paint(HelpPanel help, int w, int h) {
		help.setSize(w, h);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		help.printAll(g);
		g.dispose();
		return img;
	}

	private static void key(HelpPanel help, int id, int code) {
		KeyEvent e = new KeyEvent(help, id, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
		for (KeyListener l : help.getKeyListeners()) {
			if (id == KeyEvent.KEY_PRESSED) {
				l.keyPressed(e);
			} else {
				l.keyReleased(e);
			}
		}
	}

	private static void click(HelpPanel help, int x, int y) {
		MouseEvent e = new MouseEvent(help, MouseEvent.MOUSE_PRESSED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1);
		for (MouseListener l : help.getMouseListeners()) {
			l.mousePressed(e);
		}
	}
}
