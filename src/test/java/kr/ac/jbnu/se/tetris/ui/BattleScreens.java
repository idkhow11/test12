package kr.ac.jbnu.se.tetris.ui;

import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import kr.ac.jbnu.se.tetris.ai.Afterstate;
import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.ai.PlacementScorer;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 대전 화면(사람 대 AI, 혼자 연습)의 상태를 모두 창 없이 PNG 로 그린다 ({@link ScreenshotTool} 이 부른다, build/screens). 판마다
 * 시계를 직접 움직이고(세션 시계 = 카운트다운 3초 + 판 시각, 판이 끝난 뒤에는 끝난 순간 + 정한 시간) P1 은 기준 AI 손이 두므로 몇
 * 번을 돌려도 같은 그림이다. 크기는 1280×720 과 1920×1080 (판 도중 모습은 960×540 도).
 * <ul>
 * <li>사람 대 AI (상급): 판 도중(battle), 2줄 이상 지운 뒤 40ms(fx-clear-40ms), 10줄 넘게 하드드롭한 뒤 30ms
 * (fx-harddrop-30ms), 방해 줄 2줄 이상이 오른 뒤 60ms(fx-rise-60ms), 큰 동작(action), 위험(danger), 받을 줄 8줄 이상
 * (meter-8), K.O. 400·800ms, 결과
 * 카드가 숫자를 세는 중(result-1400ms)과 다 센 뒤(result-2600ms).</li>
 * <li>이긴 판(초급, 더 느린 승리가 기록에 있음): 이긴 보드의 금빛과 WIN(win-500ms), 새 기록 결과 카드(result-newbest).</li>
 * <li>카운트다운 3·2·1 과 GO!(countdown-3-150ms, countdown-2-500ms, countdown-1-850ms, go-100ms), 멈춘 대전(pause-versus),
 * 일시정지를 막 풀었을 때(resume-80ms), 기권 확인(esc-toast).</li>
 * <li>AI 모델 알림: 판 도중 기준 AI 로 바뀜(fallback-notice), 처음부터 기준 AI(fallback-load)와 그 판의 결과 카드("기록 안 함",
 * result-unrecorded). AI 속도 80%(speed), 기권한 판(forfeit: 게임은 기권하면 결과 카드 없이 메뉴로 가므로 화면 확인용).</li>
 * <li>설정: 색약 색상 + 블록 무늬(colorblind-clear), 효과 끔(effects-off-clear), 번쩍임 줄이기의 K.O.(calm-ko-760ms) — 앞의
 * 2줄 지우기와 같은 판·같은 순간.</li>
 * <li>혼자 연습: 판 도중(solo), 멈춤(solo-pause), 새 기록 결과(solo-result).</li>
 * </ul>
 */
final class BattleScreens {

	private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 } };
	private static final long VERSUS_SEED = 20261004L;
	/** 찍은 그림 이름 (결과 보고용). */
	static final List<String> SHOTS = new ArrayList<>();

	private BattleScreens() {
	}

	static void render(AiCatalog catalog, File out) throws Exception {
		versusMain(catalog, out);
		meter(catalog, out);
		settingsVariants(catalog, out);
		versusWin(catalog, out);
		countdown(catalog, out);
		pauseAndToast(catalog, out);
		fallbacks(catalog, out);
		speedAndForfeit(catalog, out);
		solo(catalog, out);
		System.out.println("대전 화면 " + SHOTS.size() + "장");
	}

	// ------------------------------------------------------------------ 판 하나

	/** 화면 확인용 판 하나: 시계를 직접 움직이고 P1 은 손이 둔다. */
	static final class Rig {
		final AtomicLong clock = new AtomicLong();
		final GameSession s;
		final GamePanel panel = new GamePanel(new GamePanel.Actions() {
			@Override
			public void restart(boolean sameSeed) {
			}

			@Override
			public void menu() {
			}
		}, silent());
		Supplier<Input> hand;

		Rig(GameSession.Settings st, AiCatalog catalog, long seed, SaveData save, AiCatalog.Choice ai) {
			AiCatalog.Choice[] choices = new AiCatalog.Choice[st.players()];
			if (st.versusAi()) {
				AiCatalog.Persona p = st.persona() != null ? st.persona() : catalog.personas().get(0);
				choices[1] = ai != null ? ai : catalog.choose(st.difficulty(), p, seed, 2);
			}
			s = new GameSession(st, seed, save, x -> {
			}, choices, st.versusAi() ? catalog.winRate() : null, clock::get);
			AiController h = new AiController(s.match.player(0),
					() -> st.versusAi() ? Opponent.of(s.match.player(1).game()) : Opponent.NONE,
					new Brain(new BaselineScorer(), 0, null, Chooser.rng(seed, 1)), 250, 10);
			hand = h::poll;
			sync();
			panel.setSession(s);
		}

		/** 1ms 진행하고 세션 시계를 판 시각에 맞춘다 (카운트다운 3초 뒤부터 판이 간다). */
		void step() {
			s.advance(hand.get());
			sync();
		}

		void sync() {
			clock.set((GameSession.COUNTDOWN_MS + s.match.time()) * 1_000_000L);
		}

		/** 판 시각 {@code ms} 까지 (끝나면 멈춘다). */
		void runTo(long ms) {
			while (!s.over() && s.match.time() < ms) {
				step();
			}
		}

		/** 끝날 때까지 (많아야 {@code limitMs}). 끝났으면 기록과 결과를 마무리한다. */
		void runToEnd(long limitMs) {
			runTo(limitMs);
			s.finishIfOver();
		}

		/** 세션 시계를 판이 끝난 뒤 {@code ms} 로. */
		void afterOver(long ms) {
			synchronized (s.lock) {
				clock.set(s.overAtNanos() + ms * 1_000_000L);
			}
		}

		/** 세션 시계만 {@code ms} 앞으로 (판은 그대로). */
		void later(int ms) {
			clock.addAndGet(ms * 1_000_000L);
		}

		void key(int code) {
			panel.onKey(code, true);
			panel.onKey(code, false);
		}

		void shoot(File out, String name) throws Exception {
			for (int[] wh : SIZES) {
				shoot(out, name, wh[0], wh[1]);
			}
		}

		void shoot(File out, String name, int w, int h) throws Exception {
			String file = name + "-" + w + ".png";
			ScreenshotTool.save(panel, w, h, new File(out, file));
			SHOTS.add(file);
		}
	}

	/** 소리를 내지 않는 대전 화면 소리. */
	static BattleSounds silent() {
		return new BattleSounds() {
			@Override
			public void play(kr.ac.jbnu.se.tetris.audio.Cue cue) {
			}

			@Override
			public void stopAll() {
			}
		};
	}

	/** 판을 진행하며 사건이 정한 판 시각에 찍는다. {@code target} 은 아직이면 -1. */
	record Shot(String name, ToLongFunction<GameSession> target) {
	}

	/** 판을 진행하며 찍는다. 모두 찍었거나, 판이 끝났거나, {@code limitMs} 면 멈춘다. 못 찍은 것은 알린다. */
	static void play(Rig r, long limitMs, File out, Shot... shots) throws Exception {
		long[] at = new long[shots.length];
		java.util.Arrays.fill(at, -1);
		boolean[] done = new boolean[shots.length];
		int left = shots.length;
		while (left > 0 && !r.s.over() && r.s.match.time() < limitMs) {
			r.step();
			for (int k = 0; k < shots.length; k++) {
				if (done[k]) {
					continue;
				}
				if (at[k] < 0) {
					at[k] = shots[k].target().applyAsLong(r.s);
				}
				if (at[k] >= 0 && r.s.match.time() >= at[k]) {
					r.shoot(out, shots[k].name());
					done[k] = true;
					left--;
				}
			}
		}
		for (int k = 0; k < shots.length; k++) {
			if (!done[k]) {
				System.out.println("찍지 못함: " + shots[k].name());
			}
		}
	}

	// ------------------------------------------------------------------ 사건 방아쇠

	/** P1 이 {@code from} 뒤에 2줄 이상 지운 순간 + {@code delay}. */
	static ToLongFunction<GameSession> clear(long from, int delay) {
		return s -> {
			List<GameSession.ClearFx> c = s.clearFx(0);
			GameSession.ClearFx e = c.isEmpty() ? null : c.get(c.size() - 1);
			return e != null && e.lines() >= 2 && e.at() >= from && e.at() == s.match.time() ? e.at() + delay : -1;
		};
	}

	/** P1 이 {@code rows} 줄 넘게 하드드롭한 순간 + {@code delay}. */
	static ToLongFunction<GameSession> hardDrop(int rows, int delay) {
		return s -> {
			List<GameSession.LockFx> l = s.lockFx(0);
			GameSession.LockFx e = l.isEmpty() ? null : l.get(l.size() - 1);
			return e != null && e.hardDrop() && e.dropRows() > rows && e.at() == s.match.time() ? e.at() + delay : -1;
		};
	}

	/** P1 에 방해 줄이 2줄 이상 오른 순간 + {@code delay}. */
	static ToLongFunction<GameSession> rise(int delay) {
		return s -> {
			GameSession.Tally t = s.tallies[0];
			return t.risenLines >= 2 && t.risenAt == s.match.time() ? t.risenAt + delay : -1;
		};
	}

	/** 큰 동작(콤보, T-spin, 테트리스, B2B)이 화면에 뜬 뒤 {@code delay}. */
	static ToLongFunction<GameSession> action(int delay) {
		return s -> {
			for (GameSession.Tally t : s.tallies) {
				if (t.lastActionAt == s.match.time() && s.match.time() > 0) {
					return t.lastActionAt + delay;
				}
			}
			return -1;
		};
	}

	/** P1 이 위험한 순간 (높은 더미 + 받을 줄). */
	static ToLongFunction<GameSession> danger() {
		return s -> {
			GameModel g = s.match.player(0).game();
			return g.pendingLines() >= 3 && maxHeight(g) >= 11 ? s.match.time() : -1;
		};
	}

	/** P1 의 받을 줄이 {@code lines} 줄 이상이 된 순간 + {@code delay} (막대의 조각이 다 자란 뒤). */
	static ToLongFunction<GameSession> pending(int lines, int delay) {
		return s -> s.match.player(0).game().pendingLines() >= lines ? s.match.time() + delay : -1;
	}

	static ToLongFunction<GameSession> at(long ms) {
		return s -> ms;
	}

	// ------------------------------------------------------------------ 장면들

	/** 사람 대 AI (상급) 한 판: 판 도중, 효과 순간들, K.O., 결과. */
	private static void versusMain(AiCatalog catalog, File out) throws Exception {
		Rig r = new Rig(GameSession.Settings.versus(level(catalog, 2), catalog.personas().get(0)), catalog, VERSUS_SEED,
				SaveData.inMemory(), null);
		play(r, 20 * 60_000, out, new Shot("fx-clear-40ms", clear(5_000, 40)), new Shot("fx-harddrop-30ms", hardDrop(10, 30)),
				new Shot("fx-rise-60ms", rise(60)), new Shot("action", action(400)), new Shot("battle", at(30_000)));
		r.shoot(out, "battle", 960, 540);
		play(r, 20 * 60_000, out, new Shot("danger", danger()));
		r.runToEnd(20 * 60_000);
		System.out.println("대전 결과 " + r.s.result() + ", " + r.s.match.time() / 1000 + "초");
		r.afterOver(400);
		r.shoot(out, "ko-400ms");
		r.afterOver(800);
		r.shoot(out, "ko-800ms");
		r.afterOver(1400);
		r.shoot(out, "result-1400ms");
		r.afterOver(2600);
		r.shoot(out, "result-2600ms");
	}

	/** 받을 줄이 8줄을 넘은 막대 (불가능 AI 앞에서 사람이 손을 놓는다): 빨간 조각의 빗금과 큰 숫자. */
	private static void meter(AiCatalog catalog, File out) throws Exception {
		Rig r = new Rig(GameSession.Settings.versus(level(catalog, 4), catalog.personas().get(0)), catalog, 20261014L,
				SaveData.inMemory(), null);
		r.hand = () -> null;
		play(r, 10 * 60_000, out, new Shot("meter-8", pending(8, 800)));
	}

	/** 같은 판을 설정만 바꿔: 색약 색상 + 무늬, 효과 끔 (2줄 지우기 뒤 40ms), 번쩍임 줄이기의 K.O. */
	private static void settingsVariants(AiCatalog catalog, File out) throws Exception {
		GameSession.Settings st = GameSession.Settings.versus(level(catalog, 2), catalog.personas().get(0));
		Rig cb = new Rig(st, catalog, VERSUS_SEED, save(u -> u.withColorBlind(true).withPatterns(true)), null);
		play(cb, 20 * 60_000, out, new Shot("colorblind-clear", clear(5_000, 40)));
		Rig off = new Rig(st, catalog, VERSUS_SEED, save(u -> u.withEffects(UserSettings.EFFECTS_OFF)), null);
		play(off, 20 * 60_000, out, new Shot("effects-off-clear", clear(5_000, 40)));
		Rig calm = new Rig(st, catalog, VERSUS_SEED, save(u -> u.withCalmFlash(true)), null);
		calm.runToEnd(20 * 60_000);
		calm.afterOver(760);
		calm.shoot(out, "calm-ko-760ms");
	}

	/** 이긴 판 (초급): 이긴 보드의 금빛과 WIN, 새 기록 결과 카드 (기록에 더 느린 승리가 있다). */
	private static void versusWin(AiCatalog catalog, File out) throws Exception {
		AiCatalog.Difficulty d = level(catalog, 0);
		SaveData save = SaveData.inMemory();
		save.updateRecords(rec -> rec.withVersus(d.id(), PlayRecords.Outcome.WIN, 30 * 60_000L));
		Rig r = new Rig(GameSession.Settings.versus(d, catalog.personas().get(0)), catalog, 20261011L, save, null);
		r.runToEnd(20 * 60_000);
		System.out.println("이긴 판? " + r.s.result() + ", " + r.s.match.time() / 1000 + "초, 새 기록 " + r.s.newBest());
		r.afterOver(500);
		r.shoot(out, "win-500ms");
		r.afterOver(2600);
		r.shoot(out, "result-newbest");
	}

	/** 카운트다운 3·2·1 과 GO! (진행 스레드를 돌리고 시계를 정해 둔다). */
	private static void countdown(AiCatalog catalog, File out) throws Exception {
		Rig r = new Rig(GameSession.Settings.versus(level(catalog, 2), catalog.personas().get(0)), catalog, 20261012L,
				SaveData.inMemory(), null);
		r.clock.set(0);
		r.s.start(); // 시계가 0 에 서 있다
		try {
			r.clock.set(150 * 1_000_000L);
			r.shoot(out, "countdown-3-150ms");
			r.clock.set(1_500 * 1_000_000L);
			r.shoot(out, "countdown-2-500ms");
			r.clock.set(2_850 * 1_000_000L);
			r.shoot(out, "countdown-1-850ms");
			r.clock.set(3_100 * 1_000_000L);
			long until = System.nanoTime() + 10_000_000_000L;
			while (r.s.time() < 100 && System.nanoTime() < until) { // 진행 스레드가 판을 100ms 까지 돌린다
				Thread.sleep(2);
			}
			r.shoot(out, "go-100ms");
		} finally {
			r.s.stop();
		}
	}

	/** 멈춘 대전(보드를 가림), 막 풀었을 때, 기권 확인 알림. */
	private static void pauseAndToast(AiCatalog catalog, File out) throws Exception {
		Rig r = new Rig(GameSession.Settings.versus(level(catalog, 1), catalog.personas().get(0)), catalog, 20261009L,
				SaveData.inMemory(), null);
		r.runTo(20_000);
		r.key(KeyEvent.VK_P);
		r.later(300);
		r.shoot(out, "pause-versus");
		r.key(KeyEvent.VK_P);
		r.later(80);
		r.shoot(out, "resume-80ms");
		r.later(3000);
		r.runTo(24_000);
		r.panel.onKey(KeyEvent.VK_ESCAPE, true);
		r.panel.onKey(KeyEvent.VK_ESCAPE, false);
		r.later(250);
		r.shoot(out, "esc-toast");
	}

	/** AI 모델 알림: 판 도중 기준 AI 로 바뀜, 처음부터 기준 AI. */
	private static void fallbacks(AiCatalog catalog, File out) throws Exception {
		AiCatalog.Difficulty d = level(catalog, 3);
		List<AiCatalog.Persona> ps = catalog.personas();
		GameSession.Settings st = GameSession.Settings.versus(d, ps.get(Math.min(1, ps.size() - 1)));
		AiCatalog.Choice broken = new AiCatalog.Choice(new Brain(new BreaksAfter(25), 0, null, Chooser.rng(20261008L, 2)),
				"시험", null);
		Rig r = new Rig(st, catalog, 20261008L, SaveData.inMemory(), broken);
		long[] noticed = { -1 };
		play(r, 10 * 60_000, out, new Shot("fallback-notice", s -> {
			if (noticed[0] < 0 && s.notice(1)) {
				noticed[0] = s.match.time();
			}
			return noticed[0] < 0 ? -1 : noticed[0] + 400;
		}));
		// 모델 묶음의 설명서만 있고 모델 파일이 없는 폴더: 고른 AI 의 모델을 불러오지 못해 기준 AI 로 둔다
		Path missing = Path.of("build", "tmp", "screens-models-missing");
		java.nio.file.Files.createDirectories(missing);
		java.nio.file.Files.copy(Path.of("models", "manifest.json"), missing.resolve("manifest.json"),
				java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		try (AiCatalog none = AiCatalog.load(missing)) {
			Rig load = new Rig(GameSession.Settings.versus(level(none, 1), none.personas().get(0)), none, 20261013L,
					SaveData.inMemory(), null);
			load.runTo(15_000);
			load.shoot(out, "fallback-load");
			load.hand = () -> null; // 손을 놓아 진다: 기준 AI 가 둔 판은 전적에 남지 않으므로 결과 카드에 "기록 안 함"
			load.runToEnd(20 * 60_000);
			load.afterOver(2600);
			load.shoot(out, "result-unrecorded");
		}
	}

	/** AI 속도 80% 판의 이름표, 그리고 기권한 판의 결과. */
	private static void speedAndForfeit(AiCatalog catalog, File out) throws Exception {
		Rig r = new Rig(GameSession.Settings.versus(level(catalog, 1), catalog.personas().get(0), 80), catalog, 20261010L,
				SaveData.inMemory(), null);
		r.runTo(15_000);
		r.shoot(out, "speed");
		r.s.forfeit();
		r.later(400);
		r.shoot(out, "forfeit");
	}

	/** 혼자 연습: 판 도중, 멈춤, 새 기록 결과 (기록에 더 적은 줄이 있다). */
	private static void solo(AiCatalog catalog, File out) throws Exception {
		GameSession.Settings st = GameSession.Settings.solo(3000, GameModel.Scheduler.S_MIX);
		SaveData save = SaveData.inMemory();
		String setup = PlayRecords.soloSetup(st.gravityCapG(), st.scheduler().json());
		save.updateRecords(rec -> rec.withSolo(setup, 3, 20_000));
		Rig r = new Rig(st, catalog, 7L, save, null);
		r.runTo(40_000);
		r.shoot(out, "solo");
		r.key(KeyEvent.VK_P);
		r.later(300);
		r.shoot(out, "solo-pause");
		r.key(KeyEvent.VK_P);
		r.later(3000);
		long[] next = { 0 };
		r.hand = () -> { // 이제부터는 나오자마자 떨어뜨려 일부러 진다
			if (r.s.match.time() < next[0]) {
				return null;
			}
			next[0] = r.s.match.time() + 150;
			return Input.HD;
		};
		r.runToEnd(10 * 60_000);
		System.out.println("혼자 연습 끝 " + r.s.match.time() / 1000 + "초, 새 기록 " + r.s.newBest());
		r.afterOver(2600);
		r.shoot(out, "solo-result");
	}

	// ------------------------------------------------------------------ 도구

	private static AiCatalog.Difficulty level(AiCatalog catalog, int index) {
		List<AiCatalog.Difficulty> ds = catalog.difficulties();
		return ds.get(Math.min(index, ds.size() - 1));
	}

	/** 기본 설정에서 {@code change} 만 바꾼 메모리 저장 파일. */
	private static SaveData save(java.util.function.UnaryOperator<UserSettings> change) {
		SaveData save = SaveData.inMemory();
		save.updateSettings(change);
		return save;
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

	/** {@code calls} 번까지는 기준 AI 처럼 점수를 매기다가 그 뒤로는 모델 실행 오류를 낸다 (판 도중 모델 오류 흉내). */
	static final class BreaksAfter implements PlacementScorer {
		private final PlacementScorer inner = new BaselineScorer();
		private final int calls;
		private int seen;

		BreaksAfter(int calls) {
			this.calls = calls;
		}

		@Override
		public double[] score(List<Afterstate> candidates, Opponent opponent) {
			if (seen++ >= calls) {
				throw new IllegalStateException("모델 실행 실패: 화면 확인용");
			}
			return inner.score(candidates, opponent);
		}

		@Override
		public String name() {
			return "breaks-after-" + calls;
		}
	}
}
