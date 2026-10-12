package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 모래 화면을 창 없이 그려 본다: 카운트다운, 판 도중(세 창 크기와 아주 작은 창), 색약 색상과 무늬, 일시정지(판을 가린다), 끝
 * 카드와 새 기록, 오류 카드가 예외 없이 그려진다. 알갱이가 판 자리에 정수 배로 그려지는 것, 지우기의 반짝임이 깜빡임이 아니라
 * 한 번의 맥박인 것(번쩍임 줄이기와 효과 끔 포함), 사라질 때의 조각, 고스트 설정, 기다리는 블록, 판이 끝나면 아래부터 회색이 되는
 * 것, 결과 카드의 시간과 단추 셋, 키(P, Esc, Enter, 멈춘 동안 버리는 조작)와 소리, 얇은 띠(sand-0.3)의 옅은 밝힘과 벽
 * 삼각형을 본다. 배치는 사람이 그림으로 확인한다({@code SandScreens}).
 */
class SandPanelTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	static final class Counts implements GamePanel.Actions {
		final AtomicInteger restarts = new AtomicInteger();
		final AtomicInteger menus = new AtomicInteger();
		final AtomicInteger sameSeed = new AtomicInteger();

		@Override
		public void restart(boolean same) {
			(same ? sameSeed : restarts).incrementAndGet();
		}

		@Override
		public void menu() {
			menus.incrementAndGet();
		}
	}

	private static long ms(long v) {
		return v * 1_000_000L;
	}

	private static BufferedImage paint(SandPanel panel, int w, int h) {
		panel.setSize(w, h);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		panel.printAll(g);
		g.dispose();
		return img;
	}

	private static int luma(int rgb) {
		return (299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000;
	}

	private static int saturation(int rgb) {
		int r = (rgb >> 16) & 255;
		int g = (rgb >> 8) & 255;
		int b = rgb & 255;
		return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
	}

	/** 그림의 한 영역에서 가장 밝은 화소의 밝기(0~255). */
	private static int brightest(BufferedImage img, int x0, int y0, int x1, int y1) {
		int max = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				max = Math.max(max, luma(img.getRGB(x, y)));
			}
		}
		return max;
	}

	/** 한 가로줄의 평균 밝기. */
	private static double meanLuma(BufferedImage img, int x0, int x1, int y) {
		long sum = 0;
		for (int x = x0; x < x1; x++) {
			sum += luma(img.getRGB(x, y));
		}
		return sum / (double) (x1 - x0);
	}

	/** 한 영역에서 밝기가 {@code min} 을 넘는 화소 수. */
	private static int brightCount(BufferedImage img, int x0, int y0, int x1, int y1, int min) {
		int n = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				n += luma(img.getRGB(x, y)) > min ? 1 : 0;
			}
		}
		return n;
	}

	private static SaveData settings(UnaryOperator<UserSettings> f) {
		SaveData save = SaveData.inMemory();
		save.updateSettings(f);
		return save;
	}

	/** 하드드롭을 0.3초마다 해서 {@code ms} 만큼 진행한다. */
	private static void play(SandSession s, long ms) {
		for (long t = 0; t < ms && !s.over(); t++) {
			s.advance(s.game().piece() != null && s.game().time() % 300 == 0 ? Input.HD : null, false);
		}
	}

	private static void press(SandPanel panel, int key) {
		panel.onKey(key, true);
		panel.onKey(key, false);
	}

	@Test
	void paintsEveryStateAtSeveralWindowSizes() throws Exception {
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		paint(panel, 1280, 720); // 판 없이

		AtomicLong now = new AtomicLong();
		SandSession counting = new SandSession(new SandGame(1L, 1, 0), SaveData.inMemory(), x -> {
		}, now::get);
		counting.start();
		try {
			panel.setSession(counting);
			assertEquals(3, counting.countdown());
			for (long t : new long[] { 0, 150, 500, 900, 1_500, 2_800 }) { // 3·2·1 의 들어옴, 메아리, 사라짐
				now.set(ms(t));
				paint(panel, 1280, 720);
			}
		} finally {
			counting.stop();
		}

		SandSession s = new SandSession(new SandGame(2L, 3, 1_000), SaveData.inMemory(), x -> {
		}, () -> 0L);
		panel.setSession(s);
		play(s, 40_000);
		for (int[] wh : new int[][] { { 1280, 720 }, { 1920, 1080 }, { 960, 540 }, { 320, 200 } }) {
			paint(panel, wh[0], wh[1]);
		}
		SandPanel cb = new SandPanel(new Counts(), settings(u -> u.withColorBlind(true).withPatterns(true)));
		cb.setSession(s);
		paint(cb, 1280, 720);

		AtomicLong clock = new AtomicLong();
		SandSession over = new SandSession(SandSessionTest.nearlyFull(3L, 0), SaveData.inMemory(), x -> {
		}, clock::get);
		panel.setSession(over);
		play(over, 60_000);
		assertTrue(over.over());
		over.finishIfOver();
		for (long t : new long[] { 0, 300, 730, 1_100, 1_500 }) { // 멈춤, 회색, 번쩍임, 결과 카드가 들어옴, 다 들어옴
			clock.set(ms(t));
			paint(panel, 1280, 720);
			paint(panel, 960, 540);
		}
		panel.setSession(new SandSession(SandSessionTest.withRedBand(4L, 10), SaveData.inMemory(), x -> {
		}, () -> 0L));
		paint(panel, 1920, 1080); // 새 기록이 위에 보인다

		SandSession broken = new SandSession(new SandGame(5L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		broken.fail(new IllegalStateException("시험용"));
		panel.setSession(broken);
		paint(panel, 1280, 720); // 오류 카드
	}

	@Test
	void grainsAreDrawnOnTheBoardAndPausingHidesThem() {
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		SandSession s = new SandSession(new SandGame(6L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		panel.setSession(s);
		play(s, 25_000);
		assertFalse(s.over());
		// 1280 × 720: 알갱이 3 화소, 칸 24 화소, 판 240 × 528 (스폰 여백 2칸 + 보이는 판 20칸)
		BufferedImage img = paint(panel, 1280, 720);
		Rectangle board = panel.boardBounds();
		assertEquals(240, board.width);
		assertEquals(528, board.height);
		int px = board.width / 80;
		int sand = 0;
		int square = 0;
		for (int gy = 176 - 32; gy < 176; gy++) { // 아래 네 칸 높이의 알갱이
			for (int gx = 0; gx < 80; gx++) {
				int x = board.x + gx * px;
				int y = board.y + gy * px;
				if (luma(img.getRGB(x, y)) <= 40) {
					continue;
				}
				sand++;
				boolean same = true;
				for (int k = 0; k < px * px; k++) {
					same &= img.getRGB(x + k % px, y + k / px) == img.getRGB(x, y);
				}
				square += same ? 1 : 0;
			}
		}
		assertTrue(sand > 80 * 32 / 2, "판 아래쪽에 모래가 보인다: " + sand);
		assertTrue(square >= sand * 0.9, "알갱이 하나는 3 × 3 화소의 같은 색: " + square + " / " + sand);
		int x0 = board.x + 5;
		int x1 = board.x + board.width - 5;
		int y0 = board.y + board.height - 170;
		int y1 = board.y + board.height - 6;
		int playing = brightest(img, x0, y0, x1, y1);
		s.togglePause();
		int paused = brightest(paint(panel, 1280, 720), x0, y0, x1, y1);
		assertTrue(playing > 100, "모래가 보인다: " + playing);
		assertTrue(paused < 40, "멈춘 동안은 판을 가린다: " + paused);
	}

	@Test
	void keysFollowTheClassicScreens() {
		Counts actions = new Counts();
		SandPanel panel = new SandPanel(actions, SaveData.inMemory());
		SandSession s = new SandSession(new SandGame(7L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		SandSessionTest.Heard heard = new SandSessionTest.Heard();
		s.speaker(heard);
		panel.setSession(s);
		panel.onKey(KeyEvent.VK_SPACE, true);
		assertEquals(Input.HD, s.human.next(0), "Space 는 하드드롭");
		panel.onKey(KeyEvent.VK_SPACE, false);

		panel.onKey(KeyEvent.VK_P, true);
		assertTrue(s.paused(), "P 는 일시정지");
		assertEquals(List.of(Cue.of(Sfx.PAUSE)), heard.cues);
		panel.onKey(KeyEvent.VK_P, true);
		assertTrue(s.paused(), "누르고 있는 P 의 자동 반복은 무시한다");
		panel.onKey(KeyEvent.VK_P, false);
		panel.onKey(KeyEvent.VK_X, true);
		assertNull(s.human.next(1), "멈춘 동안 누른 키는 버린다");
		panel.onKey(KeyEvent.VK_X, false);
		panel.onKey(KeyEvent.VK_P, true);
		assertFalse(s.paused());
		assertEquals(List.of(Cue.of(Sfx.PAUSE), Cue.of(Sfx.RESUME)), heard.cues, "계속하면 계속 소리");
		panel.onKey(KeyEvent.VK_P, false);

		press(panel, KeyEvent.VK_ENTER);
		assertEquals(0, actions.restarts.get(), "판 도중 Enter 는 아무것도 하지 않는다");
		panel.onKey(KeyEvent.VK_ESCAPE, true);
		assertEquals(1, actions.menus.get(), "Esc 는 바로 메뉴 (혼자 하는 판)");
		assertEquals(Cue.of(Sfx.MENU_BACK), heard.cues.get(heard.cues.size() - 1));
		assertEquals(1, heard.stops.get(), "판을 떠날 때 울리던 소리를 끝낸다");
		panel.onKey(KeyEvent.VK_ESCAPE, false);

		AtomicLong now = new AtomicLong();
		SandSession over = new SandSession(SandSessionTest.nearlyFull(8L, 0), SaveData.inMemory(), x -> {
		}, now::get);
		panel.setSession(over);
		play(over, 60_000);
		now.set(over.overAtNanos() + ms(SandFx.OVER_KEYS_AT));
		press(panel, KeyEvent.VK_ENTER);
		press(panel, KeyEvent.VK_R);
		assertEquals(2, actions.restarts.get(), "끝난 판에서 Enter·R 은 다시 하기 (새 시드)");
		assertEquals(0, actions.sameSeed.get());
		panel.onKey(KeyEvent.VK_ESCAPE, true);
		assertEquals(2, actions.menus.get(), "끝난 판에서 Esc 는 메뉴");
	}

	@Test
	void aMoveHeldThroughTheCountdownStartsAtGoAndCustomKeysWork() {
		SaveData save = SaveData.inMemory();
		save.updateSettings(u -> u.withDasMs(120).withKeys(
				u.keys().assign(kr.ac.jbnu.se.tetris.settings.KeyAction.HARD_DROP, 0, KeyEvent.VK_ENTER)));
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(21L, 1, 0), save, x -> {
		}, now::get);
		SandPanel panel = new SandPanel(new Counts(), save);
		panel.setSession(s);
		s.start(); // 시계가 0 에 서 있다: 카운트다운이 끝나기 전에는 판이 가지 않는다
		try {
			assertEquals(3, s.countdown());
			panel.onKey(KeyEvent.VK_RIGHT, true); // 카운트다운 중에 → 를 누르고 있다
			panel.handOff();
			assertNull(s.human.next(0), "카운트다운 동안 누른 조작은 버린다");
			now.set(SandSession.COUNTDOWN_MS * 1_000_000L); // 카운트다운이 막 끝났다 (판은 아직 0ms)
			panel.handOff();
			assertEquals(Input.R, s.human.next(0), "GO 와 함께 한 칸");
			assertEquals(Input.R, s.human.next(120), "설정의 DAS 120ms");
			panel.onKey(KeyEvent.VK_RIGHT, false);
			panel.onKey(KeyEvent.VK_ENTER, true); // Enter 를 하드드롭에 묶었다
			assertEquals(Input.HD, s.human.next(121));
		} finally {
			s.stop();
		}
	}

	@Test
	void theResultCardWaitsForTheEndSequenceAndOffersThreeButtons() {
		Counts actions = new Counts();
		SandPanel panel = new SandPanel(actions, SaveData.inMemory());
		AtomicLong now = new AtomicLong(ms(10_000));
		SandSession s = new SandSession(SandSessionTest.nearlyFull(9L, 0), SaveData.inMemory(), x -> {
		}, now::get);
		SandSessionTest.Heard heard = new SandSessionTest.Heard();
		s.speaker(heard);
		panel.setSession(s);
		play(s, 60_000);
		assertTrue(s.over());
		long overAt = s.overAtNanos();
		assertEquals(ms(10_000), overAt, "판이 끝난 순간의 시계");

		now.set(overAt + ms(SandFx.OVER_CARD_AT - 100)); // 아직 회색이 번지는 중: 카드도 단추도 없다
		paint(panel, 1280, 720);
		assertNull(panel.button(0));
		press(panel, KeyEvent.VK_ENTER);
		press(panel, KeyEvent.VK_ESCAPE);
		assertEquals(0, actions.restarts.get() + actions.menus.get(), "판이 끝나는 순간 연타한 키로 넘어가지 않는다");

		now.set(overAt + ms(SandFx.OVER_CARD_AT + 50)); // 카드가 막 떴지만 키는 아직
		paint(panel, 1280, 720);
		assertNotNull(panel.button(0), "결과 카드의 단추");
		press(panel, KeyEvent.VK_ENTER);
		assertEquals(0, actions.restarts.get());
		assertFalse(panel.resultReady(), "결과 카드는 아직 키와 마우스를 받지 않는다");

		now.set(overAt + ms(SandFx.OVER_KEYS_AT + 300));
		paint(panel, 1280, 720);
		assertTrue(panel.resultReady());
		assertEquals(SandCards.RETRY, panel.focus(), "처음에는 다시 하기");
		press(panel, KeyEvent.VK_LEFT);
		assertEquals(SandCards.RETRY, panel.focus(), "왼쪽 끝");
		press(panel, KeyEvent.VK_RIGHT);
		assertEquals(SandCards.SAME_SEED, panel.focus());
		assertEquals(Cue.of(Sfx.MENU_MOVE), heard.cues.get(heard.cues.size() - 1));
		press(panel, KeyEvent.VK_ENTER);
		assertEquals(1, actions.sameSeed.get(), "같은 판 다시 (같은 시드)");
		assertEquals(Cue.of(Sfx.MENU_SELECT), heard.cues.get(heard.cues.size() - 1));
		press(panel, KeyEvent.VK_RIGHT);
		press(panel, KeyEvent.VK_RIGHT);
		assertEquals(SandCards.MENU, panel.focus(), "오른쪽 끝에서 멈춘다");
		press(panel, KeyEvent.VK_R);
		assertEquals(1, actions.restarts.get(), "R 은 고른 단추와 상관없이 언제나 다시 하기 (대전 결과 카드와 같다)");
		assertEquals(0, actions.menus.get());
		press(panel, KeyEvent.VK_ENTER);
		assertEquals(1, actions.menus.get(), "확인 키(Enter)는 고른 단추(메뉴로)");
		press(panel, KeyEvent.VK_ESCAPE);
		assertEquals(2, actions.menus.get(), "Esc 는 언제나 메뉴로");

		// 마우스: 단추 위로 오면 고르고, 누르면 그 단추
		paint(panel, 1280, 720);
		Rectangle retry = panel.button(SandCards.RETRY);
		Rectangle same = panel.button(SandCards.SAME_SEED);
		assertTrue(retry.x + retry.width <= same.x, "단추가 가로로 놓였다");
		panel.hover((int) retry.getCenterX(), (int) retry.getCenterY());
		assertEquals(SandCards.RETRY, panel.focus());
		panel.click((int) same.getCenterX(), (int) same.getCenterY());
		assertEquals(2, actions.sameSeed.get());
		panel.click(5, 5);
		assertEquals(2, actions.sameSeed.get(), "단추 밖은 아무것도 하지 않는다");
		assertEquals(1, actions.restarts.get());
		assertEquals(5, heard.stops.get(), "판을 떠날 때마다 앞 판의 소리를 끝낸다");
	}

	@Test
	void theResultCardUsesTheBattleKeyHintsAndWidensToFitThem() {
		// 결과 카드 아래 키 안내는 대전과 같다: "← → 고르기 · Enter 확인 · R 다시 하기 · Esc 메뉴로"
		SaveData save = SaveData.inMemory();
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(SandSessionTest.nearlyFull(10L, 0), save, x -> {
		}, now::get);
		SandPanel panel = new SandPanel(new Counts(), save);
		panel.setSession(s);
		play(s, 60_000);
		assertTrue(s.over());
		s.finishIfOver();
		now.set(s.overAtNanos() + ms(SandPanel.OVER_SETTLED_MS));
		String[][] hints = BattleResult.keyHints(s.human.keys());
		assertEquals(List.of("고르기", "확인", "다시 하기", "메뉴로"), List.of(hints[0][1], hints[1][1], hints[2][1], hints[3][1]));
		for (int[] wh : new int[][] { { 1280, 720 }, { 1920, 1080 } }) {
			BufferedImage img = paint(panel, wh[0], wh[1]);
			int c = panel.boardBounds().width / SandRules.COLS;
			Graphics2D g = img.createGraphics();
			double keys = BattleResult.keysWidth(g, hints, c);
			g.dispose();
			Rectangle left = panel.button(SandCards.RETRY);
			Rectangle right = panel.button(SandCards.MENU);
			int span = right.x + right.width - left.x; // 단추 셋은 카드 안쪽 너비(카드 - 1.6c)를 채운다
			assertTrue(span + 2 >= keys, wh[0] + ": 키 안내(" + keys + ")가 카드 안에 들어가게 넓힌다: 단추 폭 " + span);
		}
	}

	@Test
	void theWordsMatchTheBattleScreensAndTheRules() {
		assertEquals("새 기록", SandHud.NEW_BEST, "대전 결과 카드의 꼬리표와 같은 말 (느낌표 없음)");
		// 목표 카드의 끝나는 조건은 도움말·메뉴와 같은 말이고, 결과 카드의 끝난 이유도 그 말을 줄인 것이다
		assertTrue(List.of(SandHud.GOAL_LINES).contains("점선 위까지 쌓이면 끝나요"));
		assertTrue(List.of(HelpPanel.SAND_LINES).contains("점선 위까지 쌓이면 끝나요"));
		assertEquals("점선 위까지 쌓임", SandCards.OVER_REASON);
	}

	@Test
	void theGoalCardShowsTheEndConditionWhenItFitsAndDropsItInATinyWindow() {
		SandSession s = new SandSession(new SandGame(11L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		panel.setSession(s);
		// 목표 카드의 글자(판 왼쪽 옆 칸 아래쪽)를 끝나는 조건까지 그렸는지: 세 문장을 다 그린 그림과 앞 두 문장만 그린 그림을 비교한다
		for (int[] wh : new int[][] { { 1280, 720 }, { 1920, 1080 } }) {
			BufferedImage img = paint(panel, wh[0], wh[1]);
			Rectangle b = panel.boardBounds();
			int c = b.width / SandRules.COLS;
			int x0 = b.x - (int) Math.round(0.7 * c) - (int) Math.round(6.2 * c);
			int x1 = x0 + (int) Math.round(6.2 * c);
			int y0 = b.y + b.height - (int) Math.round(2.2 * c); // 카드 아래 2.2칸: 셋째 문장이 놓이는 곳
			int y1 = b.y + b.height - (int) Math.round(0.25 * c);
			assertTrue(brightCount(img, x0 + 4, y0, x1 - 4, y1, 90) > 20,
					wh[0] + ": 카드 아래쪽에 끝나는 조건 문장이 보인다");
		}
		paint(panel, 320, 200); // 아주 작은 창: 넘치는 문장은 빼고 그린다 (예외 없음)
	}

	@Test
	void aClearPulsesOnceInsteadOfBlinking() {
		double[] normal = bandBrightness(UserSettings.EFFECTS_NORMAL, false, 0, 40, 80, 120, 160, 199);
		for (int i = 1; i < normal.length; i++) {
			assertTrue(normal[i] < normal[i - 1], "한 번 밝아졌다 가라앉는다(깜빡이지 않는다): " + java.util.Arrays.toString(normal));
		}
		double[] calm = bandBrightness(UserSettings.EFFECTS_NORMAL, true, 0, 80);
		assertTrue(calm[0] < normal[0] - 30, "번쩍임 줄이기: 처음 밝기를 누른다 " + calm[0] + " < " + normal[0]);
		assertTrue(Math.abs(calm[0] - normal[2]) < 25, "번쩍임 줄이기의 상한(섞기 0.5)은 보통 세기의 80ms 즈음: " + calm[0]);
		double[] off = bandBrightness(UserSettings.EFFECTS_OFF, false, 0, 100, 199);
		assertEquals(off[0], off[1], 0.01, "효과를 끄면 움직이지 않는다");
		assertEquals(off[0], off[2], 0.01);
		double[] plain = bandBrightness(UserSettings.EFFECTS_OFF, false, 250);
		assertTrue(off[0] > plain[0] + 20, "효과를 꺼도 사라질 덩어리는 밝게 보인다: " + off[0] + " > " + plain[0]);
	}

	/**
	 * 맨 아래 빨간 띠(시작하자마자 지워짐)의 밝기를 판 시각 {@code ages}(ms) 마다 잰다. 250ms 처럼 띠가 사라진 뒤면 빈 판의
	 * 밝기.
	 */
	private static double[] bandBrightness(int effects, boolean calm, long... ages) {
		SandPanel panel = new SandPanel(new Counts(), settings(u -> u.withEffects(effects).withCalmFlash(calm)));
		SandSession s = new SandSession(SandSessionTest.withRedBand(51L, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		panel.setSession(s);
		double[] out = new double[ages.length];
		for (int i = 0; i < ages.length; i++) {
			while (s.game().time() < ages[i]) {
				s.advance(null, false);
			}
			BufferedImage img = paint(panel, 1280, 720);
			Rectangle b = panel.boardBounds();
			// 판의 왼쪽 4분의 1 (가운데의 "+점수" 를 피한다)
			out[i] = meanLuma(img, b.x + 6, b.x + b.width / 4, b.y + b.height - 12);
		}
		return out;
	}

	@Test
	void theClusterBurstsIntoPiecesWhenItVanishesUnlessEffectsAreOff() {
		int normal = piecesAbove(UserSettings.EFFECTS_NORMAL);
		int strong = piecesAbove(UserSettings.EFFECTS_HIGH);
		int off = piecesAbove(UserSettings.EFFECTS_OFF);
		assertTrue(normal > 50, "사라질 때 조각이 튄다: " + normal);
		assertTrue(strong > normal, "강하게는 더 많이: " + strong + " > " + normal);
		assertEquals(0, off, "효과를 끄면 조각이 없다");
	}

	/** 띠가 사라지고 0.1초 뒤, 띠 바로 위(조각이 튀는 곳)의 밝은 화소 수. */
	private static int piecesAbove(int effects) {
		// 고스트는 끈다(블록이 바닥에 놓일 자리가 조각이 튀는 곳과 겹친다)
		SandPanel panel = new SandPanel(new Counts(), settings(u -> u.withEffects(effects).withGhost(false)));
		SandSession s = new SandSession(SandSessionTest.withRedBand(52L, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		panel.setSession(s);
		while (s.game().time() < SandRules.FLASH_MS + 100) {
			s.advance(null, false);
		}
		BufferedImage img = paint(panel, 1280, 720);
		Rectangle b = panel.boardBounds();
		int c = b.width / SandRules.COLS;
		int y0 = b.y + b.height - 4 * c;
		int y1 = b.y + b.height - c / 2;
		// 판의 왼쪽과 오른쪽 4분의 1 (가운데 무게중심의 "+점수" 는 효과를 꺼도 남는 정보라 뺀다)
		return brightCount(img, b.x, y0, b.x + b.width / 4, y1, 70)
				+ brightCount(img, b.x + b.width * 3 / 4, y0, b.x + b.width, y1, 70);
	}

	@Test
	void theGhostFollowsTheSetting() {
		assertTrue(ghostPixels(true) > 40, "고스트가 바닥에 보인다");
		assertEquals(0, ghostPixels(false), "고스트를 끄면 없다");
	}

	private static int ghostPixels(boolean ghost) {
		SandPanel panel = new SandPanel(new Counts(), settings(u -> u.withGhost(ghost)));
		SandSession s = new SandSession(new SandGame(53L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		panel.setSession(s);
		s.advance(null, false);
		BufferedImage img = paint(panel, 1280, 720);
		Rectangle b = panel.boardBounds();
		int c = b.width / SandRules.COLS;
		return brightCount(img, b.x + 2, b.y + b.height - 3 * c, b.x + b.width - 2, b.y + b.height - 2, 60);
	}

	@Test
	void aPieceWaitingForTheSandIsShownFaintlyAtTheSpawn() {
		// 첫 블록의 스폰 칸 하나에 흐르는 모래 한 칸: 블록이 나오지 못하고 기다린다
		SandGame probe = new SandGame(54L, 1, 0);
		int[][] cells = PieceState.spawn(probe.current().type()).cells();
		SandField f = SandField.standard(54L);
		int r0 = cells[0][0];
		int c0 = cells[0][1];
		for (int y = r0 * SandRules.GRAIN; y < (r0 + 1) * SandRules.GRAIN; y++) {
			for (int x = c0 * SandRules.GRAIN; x < (c0 + 1) * SandRules.GRAIN; x++) {
				f.set(y, x, Grain.of(SandColor.GREEN, 1, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
		SandSession s = new SandSession(SandGame.fromField(54L, 1, 0, f), SaveData.inMemory(), x -> {
		}, () -> 0L);
		assertTrue(s.game().waiting(), "스폰 자리를 모래가 막았다");
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		panel.setSession(s);
		BufferedImage img = paint(panel, 1280, 720);
		Rectangle b = panel.boardBounds();
		int c = b.width / SandRules.COLS;
		int top = SandRules.DRAW_TOP / SandRules.GRAIN;
		int[] other = cells[cells.length - 1]; // 모래가 없는 다른 칸
		int x = b.x + other[1] * c + c / 2;
		int y = b.y + (other[0] - top) * c + c / 2;
		int empty = luma(img.getRGB(b.x + c / 2, b.y + c / 2)); // 스폰 여백의 빈 칸
		int faint = luma(img.getRGB(x, y));
		assertTrue(faint > empty + 12, "기다리는 블록이 흐리게 보인다: " + faint + " > " + empty);
		assertTrue(faint < 140, "흐리게: " + faint);
	}

	@Test
	void theBoardTurnsGreyFromTheBottomWhenTheGameEnds() {
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(SandSessionTest.nearlyFull(55L, 0), SaveData.inMemory(), x -> {
		}, now::get);
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		panel.setSession(s);
		play(s, 60_000);
		assertTrue(s.over());
		long at = s.overAtNanos();
		Rectangle b = panel.boardBounds();
		now.set(at + ms(60));
		BufferedImage held = paint(panel, 1280, 720);
		now.set(at + ms(SandFx.OVER_HOLD_MS + 250));
		BufferedImage half = paint(panel, 1280, 720);
		now.set(at + ms(SandFx.OVER_HOLD_MS + SandFx.OVER_GREY_MS + 100));
		BufferedImage grey = paint(panel, 1280, 720);
		b = panel.boardBounds();
		int bottom = b.y + b.height - 10;
		int middle = b.y + b.height / 3;
		int x = b.x + b.width / 2 + 3;
		assertTrue(saturation(held.getRGB(x, bottom)) > 80, "끝난 순간에는 그대로: " + saturation(held.getRGB(x, bottom)));
		assertTrue(saturation(half.getRGB(x, bottom)) < 50, "아래부터 회색");
		assertTrue(saturation(half.getRGB(x, middle)) > 80, "위는 아직");
		assertTrue(saturation(grey.getRGB(x, middle)) < 50, "다 회색");
	}

	@Test
	void countdownPauseAndDangerSounds() {
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(56L, 1, 0), SaveData.inMemory(), x -> {
		}, now::get);
		SandSessionTest.Heard heard = new SandSessionTest.Heard();
		s.speaker(heard);
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		panel.setSession(s);
		s.start();
		try {
			for (long t : new long[] { 0, 400, 1_000, 2_000, 2_500 }) {
				now.set(ms(t));
				paint(panel, 640, 360);
			}
			now.set(ms(SandSession.COUNTDOWN_MS + 20));
			long until = System.nanoTime() + 10_000_000_000L;
			while (s.time() < 20 && System.nanoTime() < until) {
				Thread.onSpinWait();
			}
			paint(panel, 640, 360);
		} finally {
			s.stop();
		}
		assertEquals(List.of(Cue.of(Sfx.COUNTDOWN, 3), Cue.of(Sfx.COUNTDOWN, 2), Cue.of(Sfx.COUNTDOWN, 1), Cue.of(Sfx.GO)),
				heard.cues, "3·2·1 그리고 GO");

		SandSessionTest.Heard beat = new SandSessionTest.Heard();
		SandSession high = new SandSession(SandSessionTest.nearlyFull(57L, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		high.speaker(beat);
		SandPanel on = new SandPanel(new Counts(), SaveData.inMemory());
		on.setSession(high);
		paint(on, 640, 360);
		assertEquals(Cue.of(Sfx.DANGER, 2), beat.first(Sfx.DANGER), "보이는 판 위 두 칸 안: 빠른 심장 소리");
		SandSessionTest.Heard quiet = new SandSessionTest.Heard();
		high.speaker(quiet);
		SandPanel off = new SandPanel(new Counts(), settings(u -> u.withHeartbeat(false)));
		off.setSession(high);
		paint(off, 640, 360);
		assertEquals(0, quiet.count(Sfx.DANGER), "심장 소리를 끄면 없다");
		high.togglePause();
		high.speaker(beat);
		int before = beat.count(Sfx.DANGER);
		paint(on, 640, 360);
		assertEquals(before, beat.count(Sfx.DANGER), "멈춘 동안은 없다");
	}

	@Test
	void theEffectMathFollowsTheSpec() {
		assertEquals(1.0, SandFx.pulseMix(0, 1, false), 1e-9, "반짝임은 흰색에서 시작해");
		assertEquals(SandFx.PULSE_FLOOR, SandFx.pulseMix(SandRules.FLASH_MS, 1, false), 1e-9, "0.15 로 가라앉는다");
		assertEquals(SandFx.CALM_MAX, SandFx.pulseMix(0, 1, true), 1e-9, "번쩍임 줄이기는 0.5 까지");
		assertEquals(0.575, SandFx.pulseMix(0, 0.5, false), 1e-9, "약하게는 맥박의 절반");
		assertEquals(SandFx.STEADY_MIX, SandFx.pulseMix(50, 0, false), 1e-9, "효과를 끄면 움직이지 않는 밝음");

		int[][] cells = { { 20, 4 }, { 21, 4 }, { 22, 4 }, { 23, 4 } };
		List<SandSession.LockFx> hard = List.of(new SandSession.LockFx(1_000, cells, 1, true, 15));
		assertEquals(4, SandFx.bump(hard, 1_021, 24, 1), "15칸 하드드롭: (0.08 + 0.09)·24 ≈ 4px 가 21ms 에 가장 크다");
		assertEquals(0, SandFx.bump(hard, 1_021, 24, 0), "효과를 끄면 튀지 않는다");
		assertEquals(0, SandFx.bump(hard, 1_000 + SandFx.BUMP_MS, 24, 1), "0.25초면 끝난다");
		assertEquals(0, SandFx.bump(List.of(new SandSession.LockFx(1_000, cells, 1, false, 0)), 1_021, 24, 1),
				"락 딜레이로 고정하면 튀지 않는다");

		int last = SandRules.HEIGHT - SandRules.DRAW_TOP - 1;
		assertEquals(0, SandFx.greyAt(last, SandFx.OVER_HOLD_MS), "멈춘 동안은 그대로");
		assertEquals(256, SandFx.greyAt(last, SandFx.OVER_HOLD_MS + SandFx.OVER_ROW_FADE_MS), "아래부터 회색");
		assertEquals(0, SandFx.greyAt(0, SandFx.OVER_HOLD_MS + SandFx.OVER_GREY_MS / 2), "위는 아직");
		assertEquals(256, SandFx.greyAt(0, SandFx.OVER_HOLD_MS + SandFx.OVER_GREY_MS + SandFx.OVER_ROW_FADE_MS));

		assertEquals(0.6, SandFx.dangerGlow(123, 0, false), 1e-9, "효과를 끄면 위험 빛은 숨 쉬지 않는다");
		assertEquals(0.5, SandFx.overFlash(SandFx.OVER_FLASH_AT, 1, false), 1e-9);
		assertEquals(0, SandFx.overFlash(SandFx.OVER_FLASH_AT, 1, true), 1e-9, "번쩍임 줄이기면 흰 번쩍임이 없다");
	}

	/** 얇은 띠 표시의 수: 들어오는 시간, 효과 세기·번쩍임 줄이기, 반짝임보다 늘 옅다. */
	@Test
	void theThinBandMathIsGentle() {
		assertEquals(0, SandFx.thinIn(-1), 1e-9, "띠가 없다");
		assertEquals(0, SandFx.thinIn(SandFx.THIN_DELAY_MS), 1e-9, "잠깐 이어졌다 끊기는 띠는 보이지 않는다");
		assertEquals(0.5, SandFx.thinIn(SandFx.THIN_DELAY_MS + SandFx.THIN_IN_MS / 2), 1e-9);
		assertEquals(1, SandFx.thinIn(SandFx.THIN_DELAY_MS + SandFx.THIN_IN_MS), 1e-9);
		double lo = 1;
		double hi = 0;
		for (int x = 0; x < SandRules.WIDTH; x++) {
			for (long t = 0; t < SandFx.THIN_WAVE_MS; t += 70) {
				double v = SandFx.thinMix(x, t, 1, false);
				lo = Math.min(lo, v);
				hi = Math.max(hi, v);
				assertEquals(SandFx.THIN_STEADY, SandFx.thinMix(x, t, 1, true), 1e-9, "번쩍임 줄이기면 움직이지 않는다");
				assertEquals(SandFx.THIN_STEADY, SandFx.thinMix(x, t, 0, false), 1e-9, "효과를 끄면 움직이지 않는다");
			}
		}
		assertEquals(SandFx.THIN_MIX, lo, 0.01, "물결의 바닥");
		assertEquals(SandFx.THIN_MIX + SandFx.THIN_WAVE, hi, 0.01, "물결의 꼭대기");
		assertTrue(hi < SandFx.CALM_MAX + 0.05 && hi < SandFx.pulseMix(0, 1, false) / 1.5,
				"지워질 덩어리의 반짝임(흰색 1.0, 번쩍임 줄이기 0.5)보다 옅다: " + hi);
		assertTrue(SandHud.GOAL_LINES[0].contains(SandHud.GOAL_MARK), SandHud.GOAL_LINES[0]);
	}

	/**
	 * 얇은 띠 (sand-0.3): 맨 아래 세 행의 파랑(240알)은 양쪽 벽을 이었지만 지우기 문턱(640알)보다 작아 남는다. 0.15초가 지나면
	 * 0.25초에 걸쳐 옅게 밝아지고 판 바깥 양쪽에 띠 색 삼각형이 생기고, 목표 카드의 "두툼하게" 가 모래색이 된다. 효과를 꺼도 보이고,
	 * 효과를 끄거나 번쩍임 줄이기면 움직이지 않는다. 보통 세기에서는 빛 물결이 왼쪽에서 오른쪽으로 흐른다.
	 */
	@Test
	void aThinBandIsLitGentlyWithMarksAtBothWalls() {
		double[][] normal = thinBand(UserSettings.EFFECTS_NORMAL, false, 100, 700, 1050);
		assertEquals(0, normal[0][1], 0.01, "0.15초 전에는 표시가 없다 (삼각형 화소 " + normal[0][1] + ")");
		assertEquals(0, normal[0][3], 0.01, "0.15초 전에는 목표 카드 글자도 그대로");
		assertTrue(normal[1][0] > normal[0][0] + 10, "들어온 뒤 띠가 밝다: " + normal[0][0] + " → " + normal[1][0]);
		assertTrue(normal[1][1] > 20 && normal[1][2] > 20, "왼쪽·오른쪽 벽 바깥에 삼각형: " + normal[1][1] + ", " + normal[1][2]);
		assertTrue(normal[1][3] > 20, "목표 카드의 '" + SandHud.GOAL_MARK + "' 가 모래색: 화소 " + normal[1][3]);
		assertTrue(Math.abs(normal[1][0] - normal[2][0]) > 3, "빛 물결이 흐른다: " + normal[1][0] + " / " + normal[2][0]);
		double[][] off = thinBand(UserSettings.EFFECTS_OFF, false, 100, 700, 1050);
		assertTrue(off[1][0] > off[0][0] + 10, "효과를 꺼도 보인다: " + off[0][0] + " → " + off[1][0]);
		assertEquals(off[1][0], off[2][0], 0.01, "효과를 끄면 움직이지 않는다");
		assertTrue(off[1][1] > 20 && off[1][3] > 20, "효과를 꺼도 벽 삼각형과 목표 카드 글자");
		double[][] calm = thinBand(UserSettings.EFFECTS_NORMAL, true, 100, 700, 1050);
		assertTrue(calm[1][0] > calm[0][0] + 10);
		assertEquals(calm[1][0], calm[2][0], 0.01, "번쩍임 줄이기면 움직이지 않는다");
	}

	/**
	 * 맨 아래 세 행이 파랑인 판을 판 시각 {@code ages}(ms) 마다 그려 {띠 왼쪽 4분의 1 의 평균 밝기, 왼쪽 벽 바깥 삼각형 자리의 파란
	 * 화소 수, 오른쪽 벽 바깥의 파란 화소 수, 목표 카드 글자 자리(그림 아래)의 모래색 화소 수} 를 잰다.
	 */
	private static double[][] thinBand(int effects, boolean calm, long... ages) {
		SandField f = SandField.standard(62L);
		for (int y = SandRules.HEIGHT - 3; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				f.set(y, x, Grain.of(SandColor.BLUE, (x + y) & 3, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
		SandPanel panel = new SandPanel(new Counts(),
				settings(u -> u.withEffects(effects).withCalmFlash(calm).withGhost(false)));
		SandSession s = new SandSession(SandGame.fromField(62L, 1, 0, f), SaveData.inMemory(), x -> {
		}, () -> 0L);
		panel.setSession(s);
		assertEquals(0, s.game().clears(), "240알은 지워지지 않는다");
		assertEquals(3 * SandRules.WIDTH, s.game().thinGrains());
		double[][] out = new double[ages.length][];
		for (int i = 0; i < ages.length; i++) {
			while (s.game().time() < ages[i]) {
				s.advance(null, false);
			}
			BufferedImage img = paint(panel, 1280, 720);
			Rectangle b = panel.boardBounds();
			int px = b.width / SandRules.WIDTH;
			int c = b.width / SandRules.COLS;
			int y0 = b.y + b.height - 3 * px;
			double sum = 0;
			int n = 0;
			for (int y = y0; y < b.y + b.height; y++) {
				for (int x = b.x; x < b.x + b.width / 4; x++) {
					sum += luma(img.getRGB(x, y));
					n++;
				}
			}
			int textTop = b.y + (int) Math.round((13.3 + 1.15 + 2.4 + 0.3) * c); // 목표 카드의 그림 아래 (SandHud.goal)
			out[i] = new double[] { sum / n, bluish(img, b.x - c, y0 - c, b.x, b.y + b.height + 2),
					bluish(img, b.x + b.width + 1, y0 - c, b.x + b.width + c, b.y + b.height + 2),
					sandy(img, 0, textTop, b.x - c, b.y + b.height) };
		}
		return out;
	}

	/** 한 영역에서 모래색(주황, {@link Theme#SAND})이 뚜렷한 화소 수: 빨강이 밝고 파랑보다 70 넘게 크다(회색 글자는 아니다). */
	private static int sandy(BufferedImage img, int x0, int y0, int x1, int y1) {
		int n = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int rgb = img.getRGB(x, y);
				int r = (rgb >> 16) & 255;
				int bl = rgb & 255;
				n += r > 170 && r - bl > 70 ? 1 : 0;
			}
		}
		return n;
	}

	/** 한 영역에서 파랑이 뚜렷한(파랑이 빨강·초록보다 50 넘게 큰) 화소 수. */
	private static int bluish(BufferedImage img, int x0, int y0, int x1, int y1) {
		int n = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int rgb = img.getRGB(x, y);
				int r = (rgb >> 16) & 255;
				int g = (rgb >> 8) & 255;
				int bl = rgb & 255;
				n += bl - Math.max(r, g) > 50 ? 1 : 0;
			}
		}
		return n;
	}

	@Test
	void aHardDropBumpsTheBoardOnlyWithScreenShakeOn() {
		SandSession s = new SandSession(new SandGame(58L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		SandPanel shake = new SandPanel(new Counts(), SaveData.inMemory());
		SandPanel still = new SandPanel(new Counts(), settings(u -> u.withShake(false)));
		shake.setSession(s);
		still.setSession(s);
		s.advance(null, false);
		s.advance(Input.HD, false);
		while (s.game().time() < s.lockFx().get(0).at() + 21) {
			s.advance(null, false);
		}
		assertFalse(same(paint(shake, 1280, 720), paint(still, 1280, 720)), "흔들림을 켜면 판이 튄다");
		while (s.game().time() < s.lockFx().get(0).at() + SandFx.BUMP_MS + 50) {
			s.advance(null, false);
		}
		assertTrue(same(paint(shake, 1280, 720), paint(still, 1280, 720)), "튕김이 끝나면 같은 그림");
	}

	@Test
	void theTimerRunsOnlyWhileShownAndRepaintsOnlyWhileThePictureCanChange() {
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		assertFalse(panel.ticking(), "창에 붙지 않은 패널(시험, 화면 확인 도구)은 타이머를 켜지 않아 판을 붙잡지 않는다");
		panel.showingChanged(true);
		assertTrue(panel.ticking(), "화면이 보이면 돈다");
		panel.showingChanged(false);
		assertFalse(panel.ticking(), "다른 화면으로 가면 멈춘다");
		assertFalse(panel.needsFrame(), "판이 없으면 그리지 않는다");

		AtomicLong now = new AtomicLong(ms(10_000));
		SandSession s = new SandSession(SandSessionTest.nearlyFull(61L, 0), SaveData.inMemory(), x -> {
		}, now::get);
		panel.setSession(s);
		assertTrue(panel.needsFrame(), "판 도중은 언제나 그린다");
		s.togglePause();
		assertTrue(panel.needsFrame(), "일시정지 카드가 들어오는 동안");
		now.addAndGet(ms(SandPanel.PAUSE_SETTLED_MS));
		assertTrue(panel.needsFrame());
		// 처음 한 장은 카드 조각 그림을 만드느라 모서리 화소 몇 개가 다를 수 있다 (게임은 다 들어온 뒤에도 몇 장 더 그린다)
		paint(panel, 1280, 720);
		BufferedImage paused = paint(panel, 1280, 720);
		now.addAndGet(ms(1));
		assertFalse(panel.needsFrame(), "다 들어온 일시정지 카드는 다시 그리지 않는다");
		now.addAndGet(ms(5_000));
		assertTrue(same(paused, paint(panel, 1280, 720)), "멈춘 판은 그 뒤로 같은 그림");
		s.togglePause();
		assertTrue(panel.needsFrame(), "계속하면 카드가 걷히고 카운트다운한다");

		play(s, 60_000);
		assertTrue(s.over());
		long at = s.overAtNanos();
		now.set(at + ms(SandFx.OVER_CARD_AT + 100));
		assertTrue(panel.needsFrame(), "결과 카드가 들어오는 동안");
		now.set(at + ms(SandPanel.OVER_SETTLED_MS));
		assertTrue(panel.needsFrame());
		now.set(at + ms(SandPanel.OVER_SETTLED_MS + 1));
		assertFalse(panel.needsFrame(), "다 들어온 결과 카드는 다시 그리지 않는다");
		paint(panel, 1280, 720);
		BufferedImage result = paint(panel, 1280, 720);
		now.set(at + ms(SandPanel.OVER_SETTLED_MS + 30_000));
		assertTrue(same(result, paint(panel, 1280, 720)), "끝난 판은 그 뒤로 같은 그림");
		now.set(at + ms(SandPanel.OVER_SETTLED_MS - 500));
		assertTrue(same(result, paint(panel, 1280, 720)), "회색과 결과 카드는 그리기를 멈추기 0.5초도 더 전에 다 들어왔다");

		SandSession broken = new SandSession(new SandGame(62L, 1, 0), SaveData.inMemory(), x -> {
		}, now::get);
		broken.fail(new IllegalStateException("시험용"));
		panel.setSession(broken);
		assertTrue(panel.needsFrame());
		now.addAndGet(ms(SandPanel.OVER_SETTLED_MS + 1));
		assertFalse(panel.needsFrame(), "오류 카드도 그대로다");
	}

	@Test
	void leavingStopsTheGameBeforeSilencingItAndThenPlaysTheMenuSound() {
		List<String> heard = new CopyOnWriteArrayList<>();
		SandSession.Speaker speaker = new SandSession.Speaker() {
			@Override
			public void play(Cue cue) {
				heard.add(cue.sfx().name());
			}

			@Override
			public void stopAll() {
				heard.add("stopAll");
			}
		};
		AtomicLong now = new AtomicLong();
		SandSession[] current = new SandSession[1];
		SandPanel panel = new SandPanel(new GamePanel.Actions() { // 창(TetrisApp)처럼 판을 멈춘다
			@Override
			public void restart(boolean sameSeed) {
				heard.add(sameSeed ? "same" : "restart");
				current[0].quit();
			}

			@Override
			public void menu() {
				heard.add("menu");
				current[0].quit();
			}
		}, SaveData.inMemory());

		current[0] = new SandSession(new SandGame(63L, 1, 0), SaveData.inMemory(), x -> {
		}, now::get);
		current[0].speaker(speaker);
		panel.setSession(current[0]);
		panel.onKey(KeyEvent.VK_ESCAPE, true); // 판 도중 Esc
		panel.onKey(KeyEvent.VK_ESCAPE, false);
		assertEquals(List.of("menu", "stopAll", "MENU_BACK"), heard,
				"판을 먼저 멈추고 울리던 소리를 끝낸 뒤 메뉴 소리 (그 틈에 판이 낸 소리가 메뉴 위로 남지 않는다)");

		current[0] = new SandSession(SandSessionTest.nearlyFull(64L, 0), SaveData.inMemory(), x -> {
		}, now::get);
		current[0].speaker(speaker);
		panel.setSession(current[0]);
		play(current[0], 60_000);
		current[0].finishIfOver();
		now.set(current[0].overAtNanos() + ms(SandFx.OVER_KEYS_AT));
		heard.clear();
		press(panel, KeyEvent.VK_ENTER);
		assertEquals(List.of("restart", "stopAll", "MENU_SELECT"), heard, "다시 하기도 앞 판을 멈춘 뒤 그 소리를 끝낸다");
	}

	@Test
	void aLongWaitForTheSandShowsAHintBelowTheSpawn() {
		SandSession s = new SandSession(new SandGame(65L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		SandPanel panel = new SandPanel(new Counts(), SaveData.inMemory());
		panel.setSession(s);
		SandGame g = s.game();
		int seen = -1;
		long spawnedAt = 0;
		long waitStart = -1;
		BufferedImage brief = null;
		BufferedImage longer = null;
		while (longer == null && !s.over() && g.time() < 120_000) { // 블록이 나오고 150ms 뒤 그 자리에 하드드롭만 한다
			Input in = null;
			if (g.piece() != null) {
				if (g.spawns() != seen) {
					seen = g.spawns();
					spawnedAt = g.time();
				} else if (g.time() - spawnedAt >= 150) {
					in = Input.HD;
				}
			}
			boolean before = g.waiting();
			s.advance(in, false);
			if (g.waiting() && !before) {
				waitStart = g.time();
			}
			long waited = g.waiting() ? g.time() - waitStart : -1;
			assertEquals(waited, SandPanel.waitedMs(g), "기다림은 고정한 그 틱부터 잰다");
			if (waited == 300) {
				brief = paint(panel, 1280, 720);
			} else if (waited == SandPanel.WAIT_HINT_MS + 160 && brief != null) {
				longer = paint(panel, 1280, 720);
			}
		}
		assertNotNull(longer, "하드드롭만 하면 곧 다음 블록이 0.76초 넘게 모래를 기다린다");
		Rectangle b = panel.boardBounds();
		int c = b.width / SandRules.COLS;
		int top = b.y + 2 * c + Math.round(0.25f * c); // 넘침 선 바로 아래
		int x0 = b.x + b.width / 2 - 2 * c;
		int x1 = b.x + b.width / 2 + 2 * c;
		int hint = chipPixels(longer, x0, top, x1, top + c);
		int none = chipPixels(brief, x0, top, x1, top + c);
		assertTrue(hint > c * c, "0.6초를 넘게 기다리면 넘침 선 아래에 모래 주황 꼬리표: " + hint);
		assertTrue(none < hint / 10, "잠깐 기다리는 동안은 없다: " + none);
	}

	/** 한 영역에서 기다림 꼬리표의 바탕(모래 주황 {@link Theme#SAND}, 알파 235)과 거의 같은 색의 화소 수. */
	private static int chipPixels(BufferedImage img, int x0, int y0, int x1, int y1) {
		int n = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int rgb = img.getRGB(x, y);
				int r = (rgb >> 16) & 255;
				int g = (rgb >> 8) & 255;
				int b = rgb & 255;
				n += Math.abs(r - Theme.SAND.getRed()) <= 24 && Math.abs(g - Theme.SAND.getGreen()) <= 18
						&& Math.abs(b - Theme.SAND.getBlue()) <= 24 ? 1 : 0;
			}
		}
		return n;
	}

	private static boolean same(BufferedImage a, BufferedImage b) {
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				if (a.getRGB(x, y) != b.getRGB(x, y)) {
					return false;
				}
			}
		}
		return true;
	}
}
