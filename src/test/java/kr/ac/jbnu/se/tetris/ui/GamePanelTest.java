package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 대전 화면을 창 없이 그려 본다 (GAME.md 7장, runs/quality/SPEC.md V2·V4~V12).
 * <ul>
 * <li>사람 대 AI 와 혼자 연습의 모든 상태(카운트다운, 판 도중의 효과, K.O., 결과 카드, 일시정지, 기권 확인, 오류)가 설정마다
 * 예외 없이 그려진다.</li>
 * <li>같은 판이면 같은 그림이다(효과는 상태 없는 난수와 판의 시계로만 계산한다).</li>
 * <li>설정을 따른다: 효과를 끄면 줄 지우기 순간이 달라지지만 효과가 끝난 뒤의 그림은 같다(효과는 판을 바꾸지 않는다), AI
 * 보드는 흔들리지 않고 사람 보드는 흔들린다, 번쩍임 줄이기는 K.O. 의 흰 번쩍임을 뺀다, 고스트·색약 색상은 그림을 바꾼다.</li>
 * <li>결과 카드: K.O. 를 보이는 동안은 키도 마우스도 받지 않고, 이동 키로 고르고 확인 키(Enter)로 정한다(같은 판 다시 포함).
 * 다시 하기의 다른 키(R)는 언제나 다시 하기다. 마우스로도 고른다. 끝난 이유는 쉬운 말이고, 새 기록이면 무슨 기록인지, 전적에
 * 남지 않는 판이면 그렇다고 알린다. 소리: 일시정지·계속, 카운트다운, 기권 확인, 위험할 때의 심장 소리(설정을 따른다). 떠날 때는
 * 판을 먼저 멈춘 뒤 앞 판의 소리를 끈다.</li>
 * <li>멈추면 보드를 가리는 것(사람 대 AI, 혼자 연습 모두), 키가 판의 키 묶음을 따르는 것, 카운트다운 동안 누르고 있던 이동이 GO
 * 와 함께 넘어가는 것.</li>
 * <li>다시 그리기: 창에 붙지 않은 패널은 타이머를 켜지 않고, 끝난 판·멈춘 판·오류 카드는 움직임이 끝나면 다시 그리지 않는다(그
 * 그림은 정말 그대로다). 긴 판의 승률 흐름은 꼭짓점을 솎아도 같은 모양이다.</li>
 * </ul>
 * 배치는 사람이 그림으로 확인한다({@code gradlew screenshot}).
 */
class GamePanelTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	private static BufferedImage paint(GamePanel panel) {
		return paint(panel, 1280, 720);
	}

	private static BufferedImage paint(GamePanel panel, int w, int h) {
		panel.setSize(w, h);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		panel.printAll(g);
		g.dispose();
		return img;
	}

	private static int[] pixels(BufferedImage img) {
		return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
	}

	/** 보드 카드 둘레(테두리 바깥 4 화소까지)의 화소. */
	private static int[] boardPixels(BufferedImage img, BattleGeo geo) {
		int pad = (int) Math.ceil(geo.pad()) + 4;
		int x = geo.boardX() - pad;
		int y = geo.top() - pad;
		return img.getRGB(x, y, geo.boardW() + 2 * pad, geo.boardH() + 2 * pad, null, 0, geo.boardW() + 2 * pad);
	}

	/** 패널이 부른 일을 적는다. */
	private static final class Calls implements GamePanel.Actions {
		final List<String> calls = new ArrayList<>();

		@Override
		public void restart(boolean sameSeed) {
			calls.add(sameSeed ? "same" : "restart");
		}

		@Override
		public void menu() {
			calls.add("menu");
		}
	}

	/** 패널이 부른 일과 낸 소리를 한 줄로 적는다 (둘의 차례를 본다, "stop" 은 stopAll). */
	private static final class Log implements GamePanel.Actions, BattleSounds {
		final List<String> events = new ArrayList<>();

		@Override
		public void restart(boolean sameSeed) {
			events.add(sameSeed ? "same" : "restart");
		}

		@Override
		public void menu() {
			events.add("menu");
		}

		@Override
		public void play(Cue cue) {
			if (cue != null) {
				events.add(cue.sfx().name());
			}
		}

		@Override
		public void stopAll() {
			events.add("stop");
		}
	}

	/** 화면이 낸 소리를 적는다 ("stop" 은 stopAll). */
	private static final class Heard implements BattleSounds {
		final List<String> heard = new ArrayList<>();

		@Override
		public void play(Cue cue) {
			if (cue != null) {
				heard.add(cue.sfx().name());
			}
		}

		@Override
		public void stopAll() {
			heard.add("stop");
		}
	}

	/** 모델 없는 카탈로그 (기준 AI, 승률 막대 없음): 시험이 모델 파일과 상관없이 빠르다. */
	private static AiCatalog noModels() {
		return AiCatalog.load(Path.of("no-models-here"));
	}

	private static SaveData save(UnaryOperator<UserSettings> change) {
		SaveData s = SaveData.inMemory();
		s.updateSettings(change);
		return s;
	}

	/** 사람 자리를 대신 두는 기준 AI. */
	private static AiController pilot(GameSession s, long seed) {
		return new AiController(s.match.player(0),
				() -> s.match.size() == 2 ? Opponent.of(s.match.player(1).game()) : Opponent.NONE,
				new Brain(new BaselineScorer(), 0, null, Chooser.rng(seed, 1)), 200, 10);
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

	// ------------------------------------------------------------------ 모든 상태를 그린다

	@Test
	void paintsVersusFromStartToResultAndTheOverlays() {
		AiCatalog catalog = noModels();
		GameSession s = new GameSession(GameSession.Settings.versus(catalog.difficulties().get(3), catalog.personas().get(0),
				130), catalog, 4L, x -> {
				});
		GamePanel panel = new GamePanel(new Calls());
		panel.setSession(s);
		BufferedImage start = paint(panel);
		AiController me = pilot(s, 4L);
		while (s.match.time() < 7_000) {
			s.advance(me.poll());
		}
		BufferedImage mid = paint(panel);
		assertNotEquals(start.getRGB(640, 360), 0, "그림이 그려졌다");
		s.togglePause();
		paint(panel);
		s.togglePause();
		while (!s.over() && s.match.time() < 600_000) {
			s.advance(null); // 사람이 손을 놓으면 언젠가 진다
		}
		assertTrue(s.over());
		paint(panel); // K.O. 또는 결과 카드
		s.fail(new IllegalStateException("시험용"));
		paint(panel); // 오류 카드
		assertTrue(mid.getWidth() == 1280);

		GameSession forfeited = new GameSession(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 5L, x -> {
				});
		panel.setSession(forfeited);
		AiController p = pilot(forfeited, 5L);
		while (forfeited.match.time() < 3_000) {
			forfeited.advance(p.poll());
		}
		forfeited.forfeit();
		paint(panel); // 기권 결과 카드는 바로 뜬다
		assertTrue(forfeited.forfeited());
		assertNotNull(panel.choiceBounds(BattleResult.SAME), "기권한 판의 결과 카드와 단추");
	}

	@Test
	void paintsEveryMomentWithEverySettingWithoutErrors() {
		AiCatalog catalog = noModels();
		List<UnaryOperator<UserSettings>> settings = List.of(u -> u, u -> u.withEffects(UserSettings.EFFECTS_HIGH),
				u -> u.withEffects(UserSettings.EFFECTS_OFF), u -> u.withColorBlind(true).withPatterns(true).withGhost(false),
				u -> u.withCalmFlash(true).withShake(false).withEffects(UserSettings.EFFECTS_LOW));
		int frames = 0;
		for (UnaryOperator<UserSettings> change : settings) {
			for (GameSession.Settings st : List.of(GameSession.Settings.versus(catalog.difficulties().get(2),
					catalog.personas().get(0)), GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX))) {
				BattleScreens.Rig r = new BattleScreens.Rig(st, catalog, 31L, save(change), null);
				while (!r.s.over() && r.s.match.time() < 20_000) { // 여러 효과의 여러 순간
					r.step();
					if (r.s.match.time() % 97 == 0) {
						paint(r.panel, 640, 360);
						frames++;
					}
				}
				paint(r.panel, 1920, 1080);
				r.hand = () -> null; // 손을 놓으면 언젠가 진다: K.O. 와 결과 카드
				r.runToEnd(20 * 60_000);
				assertTrue(r.s.over());
				for (long ms = 0; ms <= 2_600; ms += 130) {
					r.afterOver(ms);
					paint(r.panel, 640, 360);
					frames++;
				}
				paint(r.panel, 1920, 1080);
			}
		}
		assertTrue(frames > 500, "그린 프레임 " + frames);
	}

	@Test
	void countdownGoPauseAndToastAreDrawnFromTheClock() throws Exception {
		AiCatalog catalog = noModels();
		BattleScreens.Rig r = new BattleScreens.Rig(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 8L, SaveData.inMemory(), null);
		Heard heard = new Heard();
		GamePanel panel = new GamePanel(new Calls(), heard);
		panel.setSession(r.s);
		r.clock.set(0);
		r.s.start();
		try {
			BufferedImage[] counts = new BufferedImage[3];
			for (int k = 0; k < 3; k++) {
				r.clock.set((150 + 1000L * k) * 1_000_000L);
				counts[k] = paint(panel, 960, 540);
			}
			assertNotEquals(pixels(counts[0]), pixels(counts[1]), "3 과 2 는 다르게 그린다");
			r.clock.set(3_100 * 1_000_000L);
			long until = System.nanoTime() + 10_000_000_000L;
			while (r.s.time() < 100 && System.nanoTime() < until) {
				Thread.sleep(2);
			}
			paint(panel, 960, 540); // GO!
			assertEquals(List.of("COUNTDOWN", "COUNTDOWN", "COUNTDOWN", "GO"), heard.heard, "3·2·1 은 삑, 0 이 되면 GO");
		} finally {
			r.s.stop();
		}
		// 멈춤: 카드가 200ms 동안 나타난다 (처음 그림과 다 나타난 그림이 다르다)
		heard.heard.clear();
		panel.onKey(KeyEvent.VK_P, true);
		panel.onKey(KeyEvent.VK_P, false);
		assertTrue(r.s.paused());
		BufferedImage just = paint(panel, 960, 540);
		r.later(300);
		BufferedImage shown = paint(panel, 960, 540);
		assertNotEquals(pixels(just), pixels(shown), "일시정지 카드가 나타난다");
		panel.onKey(KeyEvent.VK_P, true);
		panel.onKey(KeyEvent.VK_P, false);
		assertEquals(List.of("PAUSE", "RESUME"), heard.heard);
		// 기권 확인: 알림이 아래에서 올라온다
		heard.heard.clear();
		r.later(3_000);
		panel.onKey(KeyEvent.VK_ESCAPE, true);
		panel.onKey(KeyEvent.VK_ESCAPE, false);
		assertEquals(List.of("DENY"), heard.heard, "첫 Esc 는 기권 확인");
		BufferedImage toast0 = paint(panel, 960, 540);
		r.later(250);
		BufferedImage toast = paint(panel, 960, 540);
		assertNotEquals(pixels(toast0), pixels(toast), "기권 확인 알림이 올라온다");
	}

	// ------------------------------------------------------------------ 같은 판이면 같은 그림

	@Test
	void theSameMatchGivesIdenticalFrames() {
		AiCatalog catalog = noModels();
		GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(2), catalog.personas().get(0));
		UnaryOperator<UserSettings> strong = u -> u.withEffects(UserSettings.EFFECTS_HIGH);
		BattleScreens.Rig a = new BattleScreens.Rig(st, catalog, 77L, save(strong), null);
		BattleScreens.Rig b = new BattleScreens.Rig(st, catalog, 77L, save(strong), null);
		int compared = 0;
		boolean effects = false;
		while (!a.s.over() && a.s.match.time() < 40_000) {
			a.step();
			b.step();
			GameSession.ClearFx last = lastClear(a.s, 0);
			boolean fx = last != null && a.s.match.time() - last.at() == 40; // 줄 지우기 효과가 한창인 순간
			if (fx || a.s.match.time() % 997 == 0) {
				assertArrayEquals(pixels(paint(a.panel, 960, 540)), pixels(paint(b.panel, 960, 540)),
						"판 시각 " + a.s.match.time() + "ms 의 그림");
				compared++;
				effects |= fx;
			}
		}
		assertTrue(effects, "효과가 보이는 순간도 비교했다");
		a.hand = () -> null;
		b.hand = () -> null;
		a.runToEnd(20 * 60_000);
		b.runToEnd(20 * 60_000);
		for (long ms : new long[] { 100, 400, 760, 1_400, 2_600 }) { // K.O. 와 결과 카드
			a.afterOver(ms);
			b.afterOver(ms);
			assertArrayEquals(pixels(paint(a.panel, 960, 540)), pixels(paint(b.panel, 960, 540)), "끝난 뒤 " + ms + "ms");
			compared++;
		}
		BufferedImage once = paint(a.panel, 960, 540);
		assertArrayEquals(pixels(once), pixels(paint(a.panel, 960, 540)), "같은 순간을 두 번 그려도 같다");
		assertTrue(compared > 40, "비교한 그림 " + compared);
	}

	private static GameSession.ClearFx lastClear(GameSession s, int i) {
		List<GameSession.ClearFx> c = s.clearFx(i);
		return c.isEmpty() ? null : c.get(c.size() - 1);
	}

	// ------------------------------------------------------------------ 설정을 따른다

	/** 효과가 살아 있지 않은 순간: 지우기 0.7초, 고정 0.3초, 방해 줄 오름·받음·상쇄 0.4초가 지났다. */
	private static boolean quiet(GameSession s) {
		long now = s.match.time();
		for (int i = 0; i < s.match.size(); i++) {
			GameSession.ClearFx c = lastClear(s, i);
			List<GameSession.LockFx> l = s.lockFx(i);
			List<GameSession.ReceiveFx> rc = s.receiveFx(i);
			List<GameSession.CancelFx> cn = s.cancelFx(i);
			if (c != null && now - c.at() < 700 || !l.isEmpty() && now - l.get(l.size() - 1).at() < 300
					|| now - s.tallies[i].risenAt < 400 || !rc.isEmpty() && now - rc.get(rc.size() - 1).at() < 400
					|| !cn.isEmpty() && now - cn.get(cn.size() - 1).at() < 400) {
				return false;
			}
		}
		return true;
	}

	@Test
	void effectsChangeOnlyTheMomentsAfterAnEvent() {
		AiCatalog catalog = noModels();
		GameSession.Settings st = GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX);
		BattleScreens.Rig high = new BattleScreens.Rig(st, catalog, 5L, save(u -> u.withEffects(UserSettings.EFFECTS_HIGH)),
				null);
		BattleScreens.Rig off = new BattleScreens.Rig(st, catalog, 5L, save(u -> u.withEffects(UserSettings.EFFECTS_OFF)),
				null);
		int differ = 0;
		int same = 0;
		while (!high.s.over() && high.s.match.time() < 90_000 && (differ < 3 || same < 5)) {
			high.step();
			off.step();
			GameSession.ClearFx c = lastClear(high.s, 0);
			if (c != null && high.s.match.time() - c.at() == 40) {
				assertFalse(java.util.Arrays.equals(pixels(paint(high.panel, 960, 540)), pixels(paint(off.panel, 960, 540))),
						"줄을 지운 뒤 40ms: 효과가 보인다");
				differ++;
			} else if (high.s.match.time() % 50 == 0 && quiet(high.s)) {
				assertArrayEquals(pixels(paint(high.panel, 960, 540)), pixels(paint(off.panel, 960, 540)),
						"효과가 끝난 뒤의 그림은 같다 (판 시각 " + high.s.match.time() + "ms)");
				same++;
			}
		}
		assertTrue(differ >= 3 && same >= 5, "다른 순간 " + differ + ", 같은 순간 " + same);
	}

	@Test
	void onlyTheHumanBoardShakesAndCalmFlashDropsTheKoFlash() {
		AiCatalog catalog = noModels();
		GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(2), catalog.personas().get(0));
		UnaryOperator<UserSettings> base = u -> u.withEffects(UserSettings.EFFECTS_HIGH);
		BattleScreens.Rig shake = new BattleScreens.Rig(st, catalog, 41L, save(u -> base.apply(u).withShake(true)), null);
		BattleScreens.Rig still = new BattleScreens.Rig(st, catalog, 41L, save(u -> base.apply(u).withShake(false)), null);
		BattleScreens.Rig calm = new BattleScreens.Rig(st, catalog, 41L,
				save(u -> base.apply(u).withShake(false).withCalmFlash(true)), null);
		for (BattleScreens.Rig r : List.of(shake, still, calm)) {
			r.hand = () -> null; // 사람은 손을 놓고 진다
		}
		while (!shake.s.over()) {
			shake.step();
			still.step();
			calm.step();
			if (shake.s.match.time() % 251 == 0) { // AI 보드는 언제나 같다 (흔들지도 튀지도 않는다)
				BufferedImage a = paint(shake.panel, 960, 540);
				BufferedImage b = paint(still.panel, 960, 540);
				assertArrayEquals(boardPixels(a, shake.panel.geoOf(1)), boardPixels(b, still.panel.geoOf(1)),
						"AI 보드, 판 시각 " + shake.s.match.time());
			}
		}
		calm.runToEnd(1);
		still.runToEnd(1);
		shake.runToEnd(1);
		assertEquals(kr.ac.jbnu.se.tetris.core.TimedMatch.Result.P2, shake.s.result());
		shake.afterOver(60);
		still.afterOver(60);
		BattleGeo me = shake.panel.geoOf(0);
		assertFalse(java.util.Arrays.equals(boardPixels(paint(shake.panel, 960, 540), me),
				boardPixels(paint(still.panel, 960, 540), me)), "K.O. 충격으로 사람 보드가 흔들린다");
		still.afterOver(740);
		calm.afterOver(740);
		double flash = brightness(paint(still.panel, 960, 540), me);
		double quiet = brightness(paint(calm.panel, 960, 540), me);
		assertTrue(flash > quiet + 10, "K.O. 의 흰 번쩍임: " + flash + " / 번쩍임 줄이기 " + quiet);
	}

	/** 보드 안 화소의 평균 밝기 (0~255). */
	private static double brightness(BufferedImage img, BattleGeo geo) {
		long sum = 0;
		int n = 0;
		for (int y = geo.top(); y < geo.top() + geo.boardH(); y += 2) {
			for (int x = geo.boardX(); x < geo.boardX() + geo.boardW(); x += 2) {
				int rgb = img.getRGB(x, y);
				sum += (299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000;
				n++;
			}
		}
		return sum / (double) n;
	}

	@Test
	void ghostColourBlindAndPatternsChangeTheBoard() {
		AiCatalog catalog = noModels();
		GameSession.Settings st = GameSession.Settings.solo(2000, GameModel.Scheduler.NONE);
		BattleScreens.Rig plain = new BattleScreens.Rig(st, catalog, 12L, SaveData.inMemory(), null);
		BattleScreens.Rig noGhost = new BattleScreens.Rig(st, catalog, 12L, save(u -> u.withGhost(false)), null);
		BattleScreens.Rig cb = new BattleScreens.Rig(st, catalog, 12L, save(u -> u.withColorBlind(true).withPatterns(true)),
				null);
		for (BattleScreens.Rig r : List.of(plain, noGhost, cb)) {
			r.runTo(15_000);
		}
		BufferedImage first = paint(plain.panel); // 크기를 정한 뒤에 자리를 묻는다
		BattleGeo geo = plain.panel.geoOf(0);
		int[] a = boardPixels(first, geo);
		assertFalse(java.util.Arrays.equals(a, boardPixels(paint(noGhost.panel), geo)), "고스트를 끄면 그리지 않는다");
		assertFalse(java.util.Arrays.equals(a, boardPixels(paint(cb.panel), geo)), "색약 색상·무늬");
	}

	// ------------------------------------------------------------------ 하드드롭 꼬리, 막대의 둥근 조각

	@Test
	void hardDropTrailsEndWhereTheBlockLandedEvenWhenItsCellsWereCleared() {
		int c = 24;
		BattleGeo geo = BattleGeo.of(0, c, c);
		int b = Grid.HEIGHT - 1; // 맨 아래 행
		int x = geo.colX(4) + c / 2; // 꼬리가 지나는 열의 가운데
		// 세로 I 로 테트리스: 칸이 모두 지워져 최종 자리(cells)는 비었고, 내려앉은 자리(landed)는 4칸 그대로다
		int[][] landed = { { b - 3, 4 }, { b - 2, 4 }, { b - 1, 4 }, { b, 4 } };
		BufferedImage tetris = trail(geo, new GameSession.LockFx(1_000, new int[0][], PieceType.I.code(), true, 15, landed));
		assertTrue(alpha(tetris, x, geo.rowY(b - 3) - c / 2) > 0, "칸이 모두 지워진 하드드롭에도 꼬리가 있다");
		assertTrue(alpha(tetris, x, geo.rowY(b - 3) - 10 * c) > 0, "떨어지기 전 자리 쪽으로 이어진다");
		assertEquals(0, alpha(tetris, x, geo.rowY(b - 3) + c / 2), "꼬리는 내려앉은 블록의 위에서 끝난다");
		assertEquals(0, alpha(tetris, geo.colX(3) + c / 2, geo.rowY(b - 3) - c / 2), "블록이 없는 열에는 없다");
		// 아래 두 줄만 지웠다: 남은 두 칸은 두 줄 내려왔어도 꼬리는 블록이 실제로 내려앉은 곳(지운 줄의 번쩍임 위)에서 끝난다
		int[][] rest = { { b - 1, 4 }, { b, 4 } };
		BufferedImage two = trail(geo, new GameSession.LockFx(1_000, rest, PieceType.I.code(), true, 15, landed));
		assertTrue(alpha(two, x, geo.rowY(b - 3) - c / 2) > 0);
		assertEquals(0, alpha(two, x, geo.rowY(b - 2) + c / 2), "남은 칸의 최종 자리까지 늘어나지 않는다");
	}

	/** 하드드롭 꼬리 하나를 진행 0.2 (110ms 중 22ms) 에서 투명한 그림에 그린다. */
	private static BufferedImage trail(BattleGeo geo, GameSession.LockFx e) {
		BufferedImage img = new BufferedImage(geo.boardX() + geo.boardW() + geo.c(), geo.top() + geo.boardH() + geo.c(),
				BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		BattleFx.drawTrail(g, e, geo, 0.2, ColorPalettes.standard(), 1, 0);
		g.dispose();
		return img;
	}

	private static int alpha(BufferedImage img, int x, int y) {
		return img.getRGB(x, y) >>> 24;
	}

	@Test
	void winBarAndMeterSegmentsLookAsBeforeWhenDrawnFromCardSprites() {
		ThemeCard.clear();
		double x = 40.5;
		double y = 20.3;
		double w = 312;
		double h = 14.88; // 0.62c (c = 24)
		Paint[] paints = { Theme.DANGER, Fx.alpha(Theme.WARN, 0.45), Fx.alpha(Color.WHITE, 0.3),
				new GradientPaint(0, 0, Theme.DANGER, 0, 280, Theme.WARN) }; // 마지막은 무늬처럼 단색이 아닌 칠
		double[][] segments = { { 60, 7 }, { 70.4, 13.2 }, { 90.6, 70.3 }, { 170, 108 }, { 280, 1.4 } };
		for (double s : new double[] { 1, 1.25, 1.5 }) {
			AffineTransform t = AffineTransform.getScaleInstance(s, s);
			for (double q : new double[] { 0, 0.37, 0.5, 0.81, 1 }) {
				// 예전 승률 막대: 안티앨리어싱 알약을 칠하고, P1 만큼 잘라 한 번 더
				BufferedImage before = canvas(t, g -> {
					Shape track = new RoundRectangle2D.Double(x, y, w, h, h, h);
					g.setColor(Theme.P2);
					g.fill(track);
					g.clip(new Rectangle2D.Double(x, y, w * q, h));
					g.setColor(Theme.P1);
					g.fill(track);
				});
				int d = maxDiff(before, canvas(t, g -> BattleHud.winBar(g, x, y, w, h, q)));
				assertTrue(d <= 4, "승률 막대 배율 " + s + ", " + q + ": 가장 큰 차이 " + d);
			}
			for (Paint paint : paints) {
				// 예전 받을 줄 막대 조각: fillRoundRect (막대 너비 13, 양옆 2 화소 안쪽)
				BufferedImage before = canvas(t, g -> {
					g.setPaint(paint);
					for (double[] sg : segments) {
						int iy = (int) Math.round(sg[0]);
						int ih = (int) Math.round(sg[0] + sg[1]) - iy;
						int arc = Math.min(13 - 4, ih);
						g.fillRoundRect(20 + 2, iy, 13 - 4, ih, arc, arc);
					}
				});
				int d = maxDiff(before, canvas(t, g -> {
					g.setPaint(paint);
					for (double[] sg : segments) {
						BattleMeter.segment(g, 20, sg[0], 13, sg[1]);
					}
				}));
				assertTrue(d <= 4, "막대 조각 배율 " + s + ", " + paint + ": 가장 큰 차이 " + d);
			}
		}
		assertTrue(ThemeCard.spritesMade() > 0, "알약과 단색 조각은 둥근 카드의 조각 그림으로 그렸다");
	}

	/** 배경을 칠한 600×450 그림에 게임과 같은 안티앨리어싱 설정과 변환 {@code t} 로 그린다. */
	private static BufferedImage canvas(AffineTransform t, Consumer<Graphics2D> paint) {
		BufferedImage img = new BufferedImage(600, 450, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.background(g, 600, 450);
		Theme.antialias(g);
		g.transform(t);
		paint.accept(g);
		g.dispose();
		return img;
	}

	/** 두 그림의 가장 큰 채널 차이 (0~255). */
	private static int maxDiff(BufferedImage a, BufferedImage b) {
		int max = 0;
		for (int yy = 0; yy < a.getHeight(); yy++) {
			for (int xx = 0; xx < a.getWidth(); xx++) {
				int p = a.getRGB(xx, yy);
				int q = b.getRGB(xx, yy);
				for (int sh = 0; sh <= 16; sh += 8) {
					max = Math.max(max, Math.abs((p >> sh & 0xFF) - (q >> sh & 0xFF)));
				}
			}
		}
		return max;
	}

	// ------------------------------------------------------------------ 결과 카드, 키, 마우스, 소리

	@Test
	void resultCardWaitsForTheKoThenChoosesWithMoveKeysEnterAndTheMouse() {
		AiCatalog catalog = noModels();
		BattleScreens.Rig r = new BattleScreens.Rig(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 19L, SaveData.inMemory(), null);
		r.hand = () -> null;
		r.runToEnd(20 * 60_000);
		assertTrue(r.s.over());
		Calls calls = new Calls();
		Heard heard = new Heard();
		GamePanel panel = new GamePanel(calls, heard);
		panel.setSession(r.s);
		r.afterOver(500); // K.O. 를 보이는 중: 결과 카드도 키도 아직
		paint(panel, 1280, 720);
		assertNull(panel.choiceBounds(BattleResult.RETRY), "결과 카드는 1초 뒤에");
		panel.onKey(KeyEvent.VK_ENTER, true);
		panel.onKey(KeyEvent.VK_ENTER, false);
		assertEquals(List.of(), calls.calls, "판 끝에 누르던 키로 넘어가지 않는다");

		r.afterOver(1_200);
		paint(panel, 1280, 720);
		assertEquals(BattleResult.RETRY, panel.choice(), "처음에는 다시 하기");
		panel.onKey(KeyEvent.VK_RIGHT, true);
		panel.onKey(KeyEvent.VK_RIGHT, false);
		assertEquals(BattleResult.SAME, panel.choice());
		panel.onKey(KeyEvent.VK_RIGHT, true);
		panel.onKey(KeyEvent.VK_RIGHT, false);
		panel.onKey(KeyEvent.VK_RIGHT, true); // 끝: 움직이지 않는다
		panel.onKey(KeyEvent.VK_RIGHT, false);
		assertEquals(BattleResult.MENU, panel.choice());
		panel.onKey(KeyEvent.VK_LEFT, true);
		panel.onKey(KeyEvent.VK_LEFT, false);
		assertEquals(List.of("MENU_MOVE", "MENU_MOVE", "DENY", "MENU_MOVE"), heard.heard);
		heard.heard.clear();
		panel.onKey(KeyEvent.VK_ENTER, true); // 고른 단추: 같은 판 다시
		panel.onKey(KeyEvent.VK_ENTER, false);
		assertEquals(List.of("same"), calls.calls);
		assertEquals(List.of("stop", "MENU_SELECT"), heard.heard, "앞 판의 소리를 끄고 떠난다");

		panel.setSession(r.s); // 고르기가 다시 다시 하기로
		paint(panel, 1280, 720);
		Rectangle2D menu = panel.choiceBounds(BattleResult.MENU);
		assertNotNull(menu);
		panel.hover((int) menu.getCenterX(), (int) menu.getCenterY());
		assertEquals(BattleResult.MENU, panel.choice(), "마우스를 올리면 고른다");
		panel.click((int) menu.getCenterX(), (int) menu.getCenterY());
		panel.onKey(KeyEvent.VK_ESCAPE, true); // 메뉴 키는 언제나 메뉴로
		assertEquals(List.of("same", "menu", "menu"), calls.calls);
	}

	@Test
	void heartbeatFollowsTheSetting() {
		AiCatalog catalog = noModels();
		GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(3), catalog.personas().get(0));
		for (boolean on : new boolean[] { true, false }) {
			BattleScreens.Rig r = new BattleScreens.Rig(st, catalog, 23L, save(u -> u.withHeartbeat(on)), null);
			r.hand = () -> null;
			Heard heard = new Heard();
			GamePanel panel = new GamePanel(new Calls(), heard);
			panel.setSession(r.s);
			boolean danger = false;
			while (!r.s.over() && !danger) {
				r.step();
				GameModel g = r.s.match.player(0).game();
				danger = maxHeight(g) + Math.min(g.pendingLines(), 8) >= 15;
			}
			assertTrue(danger, "사람이 손을 놓으면 위험해진다");
			paint(panel, 640, 360);
			assertEquals(on, heard.heard.contains("DANGER"), "심장 소리 설정 " + on + ": " + heard.heard);
		}
	}

	// ------------------------------------------------------------------ 일시정지와 키 (예전부터)

	/** 그림의 한 영역에서 가장 밝은 화소의 밝기(0~255). */
	private static int brightest(BufferedImage img, int x0, int y0, int x1, int y1) {
		int max = 0;
		for (int y = y0; y < y1; y++) {
			for (int x = x0; x < x1; x++) {
				int rgb = img.getRGB(x, y);
				int luma = (299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000;
				max = Math.max(max, luma);
			}
		}
		return max;
	}

	@Test
	void pausingHidesTheBoardsInEveryMode() {
		AiCatalog catalog = noModels();
		GamePanel panel = new GamePanel(new Calls());
		// 사람 자리는 기준 AI 가 대신 둔다(보드에 블록이 쌓이게)
		GameSession v = new GameSession(GameSession.Settings.versus(catalog.difficulties().get(2), catalog.personas().get(0)),
				catalog, 12L, x -> {
				});
		AiController me = pilot(v, 12L);
		while (v.match.time() < 25_000 && !v.over()) {
			v.advance(me.poll());
		}
		panel.setSession(v);
		// 1280×720 에서 P1 보드의 아래쪽 (일시정지 카드와 겹치지 않는 곳)
		int playing = brightest(paint(panel), 245, 420, 475, 630);
		v.togglePause();
		int paused = brightest(paint(panel), 245, 420, 475, 630);
		assertTrue(playing > 120, "보드에 블록이 보인다: " + playing);
		assertTrue(paused < 40, "멈춘 대전은 보드를 가린다: " + paused);

		// 혼자 연습도 기록이 남는 판이라 같은 규칙이다 (모래 모드도 가린다)
		GameSession solo = new GameSession(GameSession.Settings.solo(2000, GameModel.Scheduler.S_MIX), catalog, 12L,
				x -> {
				});
		AiController bot = pilot(solo, 12L);
		while (solo.match.time() < 25_000 && !solo.over()) {
			solo.advance(bot.poll());
		}
		panel.setSession(solo);
		// 1280×720 에서 가운데 보드의 아래쪽 (일시정지 카드 아래)
		int practicing = brightest(paint(panel), 535, 430, 765, 630);
		solo.togglePause();
		int hidden = brightest(paint(panel), 535, 430, 765, 630);
		assertTrue(practicing > 120, "보드에 블록이 보인다: " + practicing);
		assertTrue(hidden < 40, "멈춘 혼자 연습도 보드를 가린다: " + hidden);
	}

	// ------------------------------------------------------------------ 떠날 때의 차례, 결과 카드의 키

	@Test
	void leavingStopsTheMatchBeforeSilencingIt() {
		AiCatalog catalog = noModels();
		// 사람 대 AI 판 도중: Esc 두 번 → 창이 판을 멈춘 뒤(메뉴로) 앞 판의 소리를 끈다
		BattleScreens.Rig v = new BattleScreens.Rig(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 8L, SaveData.inMemory(), null);
		v.runTo(5_000);
		Log log = new Log();
		GamePanel panel = new GamePanel(log, log);
		panel.setSession(v.s);
		for (int k = 0; k < 2; k++) {
			panel.onKey(KeyEvent.VK_ESCAPE, true);
			panel.onKey(KeyEvent.VK_ESCAPE, false);
		}
		assertEquals(List.of("DENY", "menu", "stop", "MENU_BACK"), log.events,
				"메뉴로 간 뒤(판이 멈춘 뒤)에 끈다: 그 사이 판이 낸 소리까지 지워진다");

		// 혼자 연습: Esc 한 번
		BattleScreens.Rig solo = new BattleScreens.Rig(GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX), catalog,
				8L, SaveData.inMemory(), null);
		solo.runTo(5_000);
		log.events.clear();
		panel.setSession(solo.s);
		panel.onKey(KeyEvent.VK_ESCAPE, true);
		assertEquals(List.of("menu", "stop", "MENU_BACK"), log.events);

		// 결과 카드에서 고른 단추도 같은 차례
		solo.hand = () -> null;
		solo.runToEnd(20 * 60_000);
		solo.afterOver(1_200);
		panel.setSession(solo.s);
		log.events.clear();
		panel.onKey(KeyEvent.VK_ESCAPE, false);
		panel.onKey(KeyEvent.VK_ENTER, true);
		assertEquals(List.of("restart", "stop", "MENU_SELECT"), log.events);
	}

	@Test
	void retryKeyAlwaysRetriesEnterConfirmsAndTheMouseWaitsForTheCard() {
		AiCatalog catalog = noModels();
		BattleScreens.Rig r = new BattleScreens.Rig(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 19L, SaveData.inMemory(), null);
		r.hand = () -> null;
		r.runToEnd(20 * 60_000);
		Log log = new Log();
		GamePanel panel = new GamePanel(log, log);
		panel.setSession(r.s);
		// 결과 카드는 1초에 뜨지만 키와 마우스는 1.15초부터: 그 사이 마우스가 지나가도 고르지 않는다
		r.afterOver(1_050);
		paint(panel, 1280, 720);
		Rectangle2D menu = panel.choiceBounds(BattleResult.MENU);
		assertNotNull(menu, "결과 카드가 떴다");
		panel.hover((int) menu.getCenterX(), (int) menu.getCenterY());
		assertEquals(BattleResult.RETRY, panel.choice(), "K.O. 뒤 키를 받기 전에는 마우스로 고르지 않는다");
		assertEquals(List.of(), log.events);

		r.afterOver(1_200);
		paint(panel, 1280, 720);
		panel.hover((int) menu.getCenterX(), (int) menu.getCenterY());
		assertEquals(BattleResult.MENU, panel.choice(), "키를 받기 시작하면 마우스로 고른다");
		panel.onKey(KeyEvent.VK_R, true); // R 은 고른 단추와 상관없이 다시 하기
		panel.onKey(KeyEvent.VK_R, false);
		assertEquals(List.of("MENU_MOVE", "restart", "stop", "MENU_SELECT"), log.events);

		panel.setSession(r.s);
		paint(panel, 1280, 720);
		log.events.clear();
		panel.onKey(KeyEvent.VK_RIGHT, true);
		panel.onKey(KeyEvent.VK_RIGHT, false);
		panel.onKey(KeyEvent.VK_ENTER, true); // Enter 는 고른 단추를 정한다: 같은 판 다시
		assertEquals(List.of("MENU_MOVE", "same", "stop", "MENU_SELECT"), log.events);

		// 확인 키는 다시 하기에 묶인 Enter, 없으면 다시 하기의 첫 키. 안내도 지금 키 묶음을 따른다
		KeyBindings keys = KeyBindings.defaults();
		assertEquals(KeyEvent.VK_ENTER, BattleResult.confirmKey(keys));
		assertArrayEquals(new String[][] { { "← →", "고르기" }, { "Enter", "확인" }, { "R", "다시 하기" }, { "Esc", "메뉴로" } },
				BattleResult.keyHints(keys));
		KeyBindings swapped = keys.assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_ENTER); // Enter 를 하드드롭에 주면 맞바꾼다
		assertFalse(swapped.keys(KeyAction.RETRY).contains(KeyEvent.VK_ENTER));
		assertEquals(swapped.keys(KeyAction.RETRY).get(0), BattleResult.confirmKey(swapped));
	}

	// ------------------------------------------------------------------ 다시 그리기

	@Test
	void stillFramesStopRepaintingAndADetachedPanelDoesNotTick() {
		AiCatalog catalog = noModels();
		GamePanel idle = new GamePanel(new Calls());
		assertFalse(idle.ticking(), "창에 붙지 않은 패널은 타이머를 켜지 않는다(판을 붙잡지 않는다)");
		assertFalse(idle.needsFrame(), "판이 없으면 다시 그리지 않는다");

		// 끝난 판: K.O. 와 결과 카드가 움직이는 동안은 그리고, 결과 카드가 뜨고 SETTLE_MS 뒤에는 멈춘다
		for (GameSession.Settings st : List.of(GameSession.Settings.versus(catalog.difficulties().get(2),
				catalog.personas().get(0)), GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX))) {
			BattleScreens.Rig r = new BattleScreens.Rig(st, catalog, 29L, SaveData.inMemory(), null);
			r.hand = () -> null;
			r.runToEnd(20 * 60_000);
			assertTrue(r.s.over());
			assertFalse(r.panel.ticking());
			assertTrue(r.panel.needsFrame(), "아직 그리지 않은 판");
			r.afterOver(500);
			paint(r.panel, 640, 360);
			assertTrue(r.panel.needsFrame(), "K.O. 가 움직이는 중");
			long settle = BattleResult.cardAt(r.s) + GamePanel.SETTLE_MS;
			r.afterOver(settle);
			paint(r.panel, 960, 540); // 처음 그리는 카드는 바로 그리고 두 번째부터 조각 그림을 쓰므로(ThemeCard) 한 번 그려 둔다
			int[] settled = pixels(paint(r.panel, 960, 540));
			assertFalse(r.panel.needsFrame(), "다 멈춘 결과 카드는 다시 그리지 않는다");
			r.afterOver(settle + 4_000);
			assertFalse(r.panel.needsFrame(), "시간이 지나도 그대로");
			assertArrayEquals(settled, pixels(paint(r.panel, 960, 540)), "멈춘 그림은 정말 그대로다 (" + st.mode() + ")");
		}

		// 멈춘 판: 창이 저절로 멈춰도 알아채고, 일시정지 카드가 다 나타나면 멈추고, 풀면 다시 그린다
		BattleScreens.Rig p = new BattleScreens.Rig(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 33L, SaveData.inMemory(), null);
		p.runTo(8_000);
		paint(p.panel, 960, 540);
		assertTrue(p.panel.needsFrame(), "진행 중인 판");
		assertTrue(p.s.autoPause());
		assertTrue(p.panel.needsFrame(), "그린 뒤로 판이 멈췄다");
		paint(p.panel, 960, 540);
		assertTrue(p.panel.needsFrame(), "일시정지 카드가 나타나는 중");
		p.later(300);
		paint(p.panel, 960, 540);
		int[] paused = pixels(paint(p.panel, 960, 540));
		assertFalse(p.panel.needsFrame(), "다 나타난 일시정지 카드");
		p.later(5_000);
		assertArrayEquals(paused, pixels(paint(p.panel, 960, 540)), "멈춘 동안 그림은 그대로다");
		p.key(KeyEvent.VK_P);
		assertTrue(p.panel.needsFrame(), "풀면 다시 그린다");

		// 오류 카드는 한 번 그리면 멈춘다
		p.s.fail(new IllegalStateException("시험용"));
		assertTrue(p.panel.needsFrame(), "그린 뒤로 오류가 났다");
		paint(p.panel, 960, 540);
		assertFalse(p.panel.needsFrame());
	}

	@Test
	void longWinChartsKeepTheirShapeWithFewerPoints() {
		List<double[]> history = new ArrayList<>();
		java.util.Random rnd = new java.util.Random(7);
		double p = 0.5;
		for (int i = 0; i < 4_000; i++) { // 블록 4천 개: 아주 긴 판
			p = Math.max(0.02, Math.min(0.98, p + rnd.nextGaussian() * 0.04));
			history.add(new double[] { i * 300.0, p });
		}
		long end = 4_000 * 300L;
		BattleHud.ChartPath full = BattleHud.chartPath(history, end, 10, 10, 300, 40, 100); // 화소가 넉넉하면 솎지 않는다
		BattleHud.ChartPath thin = BattleHud.chartPath(history, end, 10, 10, 300, 40, 1);
		assertEquals(4_000, full.points);
		assertTrue(thin.points <= 300, "두 화소마다 두 점까지 (폭의 화소 수까지): " + thin.points);
		// 솎아도 칸(두 화소 열)마다 가장 높은 점과 가장 낮은 점은 그 자리 그대로 남는다 (선의 위아래 끝이 같다)
		double[] hi = new double[150];
		double[] lo = new double[150];
		java.util.Arrays.fill(hi, Double.MAX_VALUE);
		java.util.Arrays.fill(lo, -Double.MAX_VALUE);
		for (double[] pt : history) {
			double px = 10 + 300 * Math.min(1, pt[0] / end);
			double py = 10 + 40 * (1 - pt[1]);
			int cell = (int) Math.min(149, Math.floor((px - 10) / 2));
			hi[cell] = Math.min(hi[cell], py);
			lo[cell] = Math.max(lo[cell], py);
		}
		List<Double> kept = new ArrayList<>();
		double[] seg = new double[6];
		for (java.awt.geom.PathIterator it = thin.line.getPathIterator(null); !it.isDone(); it.next()) {
			it.currentSegment(seg);
			kept.add(seg[1]);
		}
		for (int cell = 0; cell < 150; cell++) {
			if (hi[cell] != Double.MAX_VALUE) {
				assertTrue(kept.contains(hi[cell]) && kept.contains(lo[cell]), "칸 " + cell + " 의 위아래 끝");
			}
		}
		// 그린 그림도 거의 같다 (아주 빽빽한 곳의 선 굵기만 조금 다르다)
		BufferedImage a = chart(full);
		BufferedImage b = chart(thin);
		int differ = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				int pa = a.getRGB(x, y);
				int pb = b.getRGB(x, y);
				int d = Math.max(Math.abs((pa >> 16 & 255) - (pb >> 16 & 255)),
						Math.max(Math.abs((pa >> 8 & 255) - (pb >> 8 & 255)), Math.abs((pa & 255) - (pb & 255))));
				if (d > 48) {
					differ++;
				}
			}
		}
		assertTrue(differ < a.getWidth() * a.getHeight() / 20, "크게 다른 화소 " + differ);
	}

	/** 흐름 그래프 모양을 결과 화면처럼 그린다 (채움과 2.2 화소 선). */
	private static BufferedImage chart(BattleHud.ChartPath path) {
		BufferedImage img = new BufferedImage(320, 60, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Theme.withAlpha(Theme.P1, 70));
		g.fill(path.area);
		g.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
		g.setColor(Theme.TEXT);
		g.draw(path.line);
		g.dispose();
		return img;
	}

	// ------------------------------------------------------------------ 결과 카드의 글

	@Test
	void resultCardSaysWhyInPlainWordsAndWhichRecordWasBroken() {
		AiCatalog catalog = noModels();
		// 사람이 손을 놓아 진다: 영어 용어 없이 쉬운 말로. 모델을 불러오지 못해 기준 AI 가 대신 둔 판은 전적에 남지 않는다
		AiCatalog.Choice stand = new AiCatalog.Choice(new Brain(new BaselineScorer(), 0, null, Chooser.rng(19L, 2)), "기준 AI",
				"모델 파일이 없음");
		BattleScreens.Rig lost = new BattleScreens.Rig(GameSession.Settings.versus(catalog.difficulties().get(1),
				catalog.personas().get(0)), catalog, 19L, SaveData.inMemory(), stand);
		lost.hand = () -> null;
		lost.runToEnd(20 * 60_000);
		String why = BattleResult.why(lost.s);
		assertTrue(why.equals("꼭대기까지 쌓임") || why.equals("방해 줄에 밀려 넘침"), why);
		assertTrue(BattleResult.unrecorded(lost.s), "기준 AI 판은 기록 안 함");
		assertEquals("", BattleResult.recordDetail(lost.s));

		// 학습한 AI 자리(기준 AI 가 아닌 두뇌)를 이긴 판: 이름을 붙인 이유와, 기록보다 빠른 승리
		AiCatalog.Difficulty easy = catalog.difficulties().get(0);
		SaveData save = SaveData.inMemory();
		save.updateRecords(rec -> rec.withVersus(easy.id(), PlayRecords.Outcome.WIN, 30 * 60_000L));
		AiCatalog.Choice model = new AiCatalog.Choice(new Brain(new BaselineScorer(), 0, null, Chooser.rng(20261011L, 2)),
				"시험", null);
		BattleScreens.Rig win = new BattleScreens.Rig(GameSession.Settings.versus(easy, catalog.personas().get(0)), catalog,
				20261011L, save, model);
		win.runToEnd(20 * 60_000);
		assertEquals(kr.ac.jbnu.se.tetris.core.TimedMatch.Result.P1, win.s.result(), "사람 자리의 손이 초급 AI 를 이긴다");
		assertFalse(BattleResult.unrecorded(win.s));
		assertTrue(BattleResult.why(win.s).startsWith("AI가 "), BattleResult.why(win.s));
		assertTrue(win.s.newBest(), "기록보다 빠른 승리");
		long sec = win.s.match.time() / 1000;
		assertEquals(String.format("최단 승리 %d:%02d", sec / 60, sec % 60), BattleResult.recordDetail(win.s));
		win.afterOver(2_600);
		paint(win.panel, 1280, 720); // 두 줄 꼬리표
		paint(win.panel, 960, 540);

		// 혼자 연습: 기록보다 오래 버티면 버틴 시간, 버틴 시간은 못 넘고 줄만 넘으면 지운 줄
		GameSession.Settings st = GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX);
		String setup = PlayRecords.soloSetup(st.gravityCapG(), st.scheduler().json());
		for (long bestTime : new long[] { 20_000, 60 * 60_000L }) {
			SaveData s = SaveData.inMemory();
			s.updateRecords(rec -> rec.withSolo(setup, 3, bestTime));
			BattleScreens.Rig solo = new BattleScreens.Rig(st, catalog, 7L, s, null);
			solo.runTo(30_000);
			long[] next = { 0 };
			solo.hand = () -> { // 이제부터는 나오자마자 떨어뜨려 일부러 진다
				if (solo.s.match.time() < next[0]) {
					return null;
				}
				next[0] = solo.s.match.time() + 150;
				return Input.HD;
			};
			solo.runToEnd(10 * 60_000);
			assertTrue(solo.s.newBest(), "기록을 넘었다");
			int lines = solo.s.match.player(0).game().linesTotal();
			long t = solo.s.match.time() / 1000;
			assertEquals(bestTime == 20_000 ? String.format("최장 시간 %d:%02d", t / 60, t % 60) : lines + "줄",
					BattleResult.recordDetail(solo.s));
			solo.afterOver(2_600);
			paint(solo.panel, 1280, 720);
		}
	}

	@Test
	void keysFollowTheBindingsAndMovesHeldThroughTheCountdownStartAtGo() {
		AiCatalog catalog = noModels();
		SaveData save = SaveData.inMemory();
		save.updateSettings(u -> u.withKeys(u.keys().assign(KeyAction.HARD_DROP, 0, KeyEvent.VK_A)));
		AtomicLong now = new AtomicLong();
		GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(0), catalog.personas().get(0));
		GameSession s = new GameSession(st, 9L, save, x -> {
		}, new AiCatalog.Choice[] { null, catalog.choose(catalog.difficulties().get(0), catalog.personas().get(0), 9L, 2) },
				null, now::get);
		Calls calls = new Calls();
		GamePanel panel = new GamePanel(calls);
		panel.setSession(s);
		s.start(); // 시계가 0 에 서 있다: 카운트다운 3초가 끝나기 전에는 판이 가지 않는다
		try {
			assertEquals(3, s.countdown());
			panel.onKey(KeyEvent.VK_LEFT, true); // 카운트다운 중에 ← 를 누르고 있다
			panel.onKey(KeyEvent.VK_UP, true);
			panel.onKey(KeyEvent.VK_UP, false);
			panel.handOff();
			assertNull(s.human.next(0), "카운트다운 동안 누른 조작은 버린다");
			now.set(GameSession.COUNTDOWN_MS * 1_000_000L); // 카운트다운이 막 끝났다 (판은 아직 0ms)
			assertEquals(0, s.countdown());
			panel.handOff(); // 블록 조작을 다시 받기 시작하는 순간: 누르고 있던 ← 를 넘긴다
			assertEquals(Input.L, s.human.next(0), "GO 와 함께 한 칸");
			assertNull(s.human.next(1), "회전은 넘기지 않는다");
			assertEquals(Input.L, s.human.next(170), "DAS 가 시작됐다");
			panel.onKey(KeyEvent.VK_LEFT, false);
			panel.onKey(KeyEvent.VK_A, true); // 바꾼 하드드롭 키
			assertEquals(Input.HD, s.human.next(171));
			panel.onKey(KeyEvent.VK_A, false);
			panel.onKey(KeyEvent.VK_SPACE, true); // 원래 키는 이제 아무것도 아니다
			assertNull(s.human.next(172));
			panel.onKey(KeyEvent.VK_SPACE, false);

			panel.onKey(KeyEvent.VK_P, true);
			assertTrue(s.paused(), "P 는 일시정지");
			panel.onKey(KeyEvent.VK_P, true); // 누르고 있는 P 의 자동 반복은 무시한다
			assertTrue(s.paused(), "자동 반복으로 풀리지 않는다");
			panel.onKey(KeyEvent.VK_P, false);
			panel.onKey(KeyEvent.VK_P, true);
			assertFalse(s.paused());
			panel.onKey(KeyEvent.VK_P, false);

			panel.onKey(KeyEvent.VK_ESCAPE, true); // 대전: 한 번은 기권 확인
			panel.onKey(KeyEvent.VK_ESCAPE, true); // 누르고 있는 Esc 의 자동 반복은 두 번째 눌림이 아니다
			assertEquals(List.of(), calls.calls);
			panel.onKey(KeyEvent.VK_ESCAPE, false);
			panel.onKey(KeyEvent.VK_ESCAPE, true); // 2초 안에 한 번 더: 메뉴로 (창이 기권으로 끝낸다)
			assertEquals(List.of("menu"), calls.calls);
			panel.onKey(KeyEvent.VK_ESCAPE, false);

			s.forfeit();
			panel.onKey(KeyEvent.VK_ENTER, true); // 끝난 판: 다시 하기 (새 시드)
			panel.onKey(KeyEvent.VK_R, true); // 같은 동작의 다른 키를 함께 누르면 한 번
			panel.onKey(KeyEvent.VK_ENTER, false);
			panel.onKey(KeyEvent.VK_R, false);
			panel.onKey(KeyEvent.VK_R, true);
			assertEquals(List.of("menu", "restart", "restart"), calls.calls);
		} finally {
			s.stop();
		}
	}
}
