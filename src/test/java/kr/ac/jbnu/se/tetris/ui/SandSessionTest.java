package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.sand.SandSnapshot;
import kr.ac.jbnu.se.tetris.sand.ai.SandAiController;
import kr.ac.jbnu.se.tetris.sand.ai.SandAiLevel;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;

/**
 * 모래 모드 한 판의 진행 (SandSession): 카운트다운 뒤 실제 시간을 따라잡는 시계, 일시정지와 다시 가기 카운트다운, 창을 벗어나면
 * 멈춤, 스레드로 진행해도 같은 판, 플레이 기록(끝난 판과 카운트다운 뒤 그만둔 판만 한 번), 오류 카드, 소리(블록·지우기·단계·게임
 * 끝), 효과 사건(부서짐·지우기), 그리고 모래 AI 가 실제 진행 스레드로 두는 판(멈춤 없음, 기록은 시험 폴더에만).
 */
class SandSessionTest {

	private static long ms(long v) {
		return v * 1_000_000L;
	}

	/** 조건이 될 때까지 기다린다(진행 스레드를 쓰는 시험). */
	private static void await(BooleanSupplier done, String what) throws InterruptedException {
		long until = System.nanoTime() + 20_000_000_000L;
		while (!done.getAsBoolean()) {
			if (System.nanoTime() > until) {
				throw new AssertionError("기다리다 시간이 다 됨: " + what);
			}
			Thread.sleep(2);
		}
	}

	/** 들은 소리를 적어 둔다. */
	static final class Heard implements SandSession.Speaker {
		final List<Cue> cues = new CopyOnWriteArrayList<>();
		final AtomicInteger stops = new AtomicInteger();

		@Override
		public void play(Cue cue) {
			cues.add(cue);
		}

		@Override
		public void stopAll() {
			stops.incrementAndGet();
		}

		int count(Sfx sfx) {
			int n = 0;
			for (Cue c : cues) {
				n += c.sfx() == sfx ? 1 : 0;
			}
			return n;
		}

		Cue first(Sfx sfx) {
			for (Cue c : cues) {
				if (c.sfx() == sfx) {
					return c;
				}
			}
			return null;
		}
	}

	/**
	 * 거의 찬 판: 알갱이 40행부터 바닥까지 폭 8 의 세로 줄무늬(빨강·파랑·초록·노랑을 되풀이)로 가득하다. 같은 색 줄무늬가 서로
	 * 떨어져 있어 아무것도 지워지지 않고, 블록 두세 개면 정착한 모래가 보이는 판 위로 넘쳐 끝난다.
	 */
	static SandGame nearlyFull(long seed, long best) {
		return SandGame.fromField(seed, 1, best, stripes(seed, 40, SandRules.HEIGHT));
	}

	private static SandField stripes(long seed, int from, int to) {
		SandField f = SandField.standard(seed);
		for (int y = from; y < to; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				SandColor c = SandColor.values()[(x / SandRules.GRAIN) % SandRules.COLORS];
				f.set(y, x, Grain.of(c, (x * 7 + y * 3) & 3, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
		return f;
	}

	/** 판이 끝날 때까지 하드드롭만 한다(블록이 없으면 기다린다). */
	private static void dropUntilOver(SandSession s) {
		for (int t = 0; t < 120_000 && !s.over(); t++) {
			s.advance(s.game().piece() != null && t % 50 == 0 ? Input.HD : null, false);
		}
		assertTrue(s.over(), "판이 끝났다");
	}

	@Test
	void theClockWaitsForTheCountdownThenFollowsRealTime() throws Exception {
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(5L, 1, 0), SaveData.inMemory(), x -> {
		}, now::get);
		s.start();
		try {
			assertEquals(3, s.countdown());
			assertEquals(3_000, s.countdownMs());
			now.set(ms(1_200));
			assertEquals(2, s.countdown());
			assertEquals(1_800, s.countdownMs(), "숫자의 움직임에 쓰는 남은 시간");
			now.set(ms(2_990));
			Thread.sleep(30);
			assertEquals(0, s.time(), "카운트다운 동안 판은 가지 않는다");
			assertEquals(-1, s.goAgeMs());
			now.set(ms(3_450));
			await(() -> s.time() == 450, "카운트다운 뒤 흐른 만큼 따라잡는다");
			assertEquals(0, s.countdown());
			assertEquals(0, s.countdownMs());
			assertTrue(s.showGo(), "막 시작하면 GO!");
			assertEquals(450, s.goAgeMs());
			now.set(ms(5_000));
			await(() -> s.time() == 2_000, "2초");
			assertFalse(s.showGo());
		} finally {
			s.stop();
		}
	}

	@Test
	void pauseFreezesTheClockAndResumeCountsDownFirst() throws Exception {
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(6L, 1, 0), SaveData.inMemory(), x -> {
		}, now::get);
		s.start();
		try {
			assertEquals(SandSession.NOT_OVER, s.pausedAtNanos());
			now.set(ms(3_500));
			await(() -> s.time() == 500, "판의 시계가 0.5초");
			s.togglePause();
			assertTrue(s.paused());
			assertEquals(ms(3_500), s.pausedAtNanos(), "일시정지 카드가 들어오는 움직임의 시작");
			assertEquals(0, s.countdown(), "멈춘 동안은 카운트다운 숫자가 없다");
			now.set(ms(20_000));
			Thread.sleep(30);
			assertEquals(500, s.time(), "멈춘 동안 판의 시계는 가지 않는다");
			s.togglePause();
			assertEquals(SandSession.NOT_OVER, s.pausedAtNanos());
			assertEquals(ms(20_000), s.resumedAtNanos(), "카드가 걷히는 움직임의 시작");
			assertEquals(2, s.countdown(), "다시 가기 전 2·1");
			assertEquals(2_000, s.countdownMs());
			now.set(ms(21_900));
			Thread.sleep(30);
			assertEquals(500, s.time(), "카운트다운 동안에도 가지 않는다");
			now.set(ms(22_100));
			await(() -> s.time() == 600, "카운트다운이 끝난 0.1초 뒤");
		} finally {
			s.stop();
		}
	}

	@Test
	void leavingTheWindowPausesOnceAndNeverAFinishedGame() {
		SandSession s = new SandSession(new SandGame(7L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		assertTrue(s.autoPause(), "창을 벗어나면 멈춘다");
		assertFalse(s.autoPause(), "이미 멈췄다");
		assertTrue(s.paused());
		s.togglePause();
		assertFalse(s.paused(), "돌아와서 P 로 다시 간다");

		SandSession done = new SandSession(nearlyFull(8L, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		dropUntilOver(done);
		assertFalse(done.autoPause(), "끝난 판은 멈추지 않는다");
		done.togglePause();
		assertFalse(done.paused(), "끝난 판은 P 로도 멈추지 않는다");
	}

	@Test
	void theThreadedLoopPlaysExactlyTheGameOfDirectStepping() throws Exception {
		// 사람 입력 없이(블록이 중력(8단계, 초당 8칸)으로 내려와 고정되고 모래가 흐른다) 스레드로 6초 진행한 판 = 직접 6,000 틱 진행한 판
		AtomicLong now = new AtomicLong();
		SandSession threaded = new SandSession(new SandGame(11L, 8, 0), SaveData.inMemory(), x -> {
		}, now::get);
		threaded.start();
		try {
			for (int k = 1; k <= 8; k++) { // 0.75초씩 몰아서 흘러도 그만큼 따라잡을 뿐 판은 같다
				long target = 750L * k;
				now.set(ms(3_000 + target));
				await(() -> threaded.time() == target, "판의 시계 " + target + "ms");
			}
		} finally {
			threaded.stop();
		}
		SandSession direct = new SandSession(new SandGame(11L, 8, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		for (int t = 0; t < 6_000; t++) {
			direct.advance(null, false);
		}
		SandSnapshot a = threaded.snapshot(null);
		SandSnapshot b = direct.snapshot(null);
		short[] ga = new short[SandRules.WIDTH * SandRules.HEIGHT];
		short[] gb = new short[ga.length];
		a.copyGrains(ga);
		b.copyGrains(gb);
		assertArrayEquals(gb, ga, "같은 알갱이");
		assertEquals(b.pieces(), a.pieces());
		assertEquals(b.score(), a.score());
		assertTrue(a.pieces() > 0, "블록이 고정됐다");
	}

	@Test
	void aFinishedGameIsRecordedOnceWithItsScoreAndBestMultiplier() throws Exception {
		SaveData save = SaveData.inMemory();
		AtomicInteger overs = new AtomicInteger();
		AtomicLong now = new AtomicLong();
		Heard heard = new Heard();
		SandSession s = new SandSession(nearlyFull(9L, 0), save, x -> overs.incrementAndGet(), now::get);
		s.speaker(heard);
		s.start();
		try {
			assertFalse(s.threadDone(), "진행 스레드가 돈다");
			now.set(ms(3_001)); // 카운트다운이 끝났다. 사람 입력이 없어도 블록이 내려와 쌓인다
			for (long t = 3_001; !s.over() && t < 200_000; t += 500) {
				now.set(ms(t));
				Thread.sleep(1);
			}
			await(s::over, "넘쳐서 끝남");
			await(() -> overs.get() == 1, "끝났다고 한 번 알림");
			// 결과 카드를 띄워 둔 동안(아직 떠나지 않음) 진행 스레드가 1ms 마다 깨어나지 않는다
			await(s::threadDone, "끝 처리를 한 번 한 뒤 진행 스레드가 스스로 끝난다");
		} finally {
			s.quit(); // 메뉴로: 이미 더했으므로 다시 더하지 않는다 (이미 끝난 스레드여도 된다)
		}
		PlayRecords.Sand rec = save.records().sand();
		assertEquals(1, rec.games(), "한 판");
		assertEquals(s.game().score(), rec.bestScore());
		assertEquals(s.game().maxMultiplier(), rec.bestMultiplier());
		assertTrue(s.recorded());
		assertEquals(1, overs.get());
		assertEquals(1, heard.count(Sfx.KO), "게임 끝 소리 한 번");
		assertEquals(0, heard.count(Sfx.NEW_RECORD), "첫 판은 새 기록이 아니다");
		assertTrue(s.overAtNanos() != SandSession.NOT_OVER, "끝난 순간의 시계");
	}

	@Test
	void quittingAfterTheCountdownCountsButQuittingDuringItDoesNot() {
		SaveData save = SaveData.inMemory();
		SandSession early = new SandSession(new SandGame(12L, 1, 0), save, x -> {
		}, () -> 0L);
		early.quit();
		assertEquals(0, save.records().sand().games(), "시작 전에 그만둔 판은 남기지 않는다");

		SandSession played = new SandSession(new SandGame(12L, 1, 0), save, x -> {
		}, () -> 0L);
		for (int t = 0; t < 2_000; t++) {
			played.advance(null, false);
		}
		played.quit();
		played.quit();
		assertEquals(1, save.records().sand().games(), "카운트다운 뒤 그만둔 판은 한 번 남긴다");
		assertFalse(played.newBest(), "첫 판은 새 기록이 아니다");
		assertEquals(save.records(), played.records());

		SandSession band = new SandSession(withRedBand(12L, save.records().sand().bestScore()), save, x -> {
		}, () -> 0L);
		for (int t = 0; t < 10; t++) {
			band.advance(null, false);
		}
		assertFalse(band.newBest(), "기록에 더하기 전에는 아니다");
		band.quit();
		assertTrue(band.newBest(), "저장된 최고 점수(한 판, 0점)보다 높다");
		assertEquals(2, band.records().sand().games());
		assertEquals(save.records(), band.records());
	}

	/** 맨 아래 한 칸 높이(알갱이 8행)가 모두 빨강인 판: 시작하자마자 양쪽 벽을 이어 640 알갱이를 지운다(점수 640). */
	static SandGame withRedBand(long seed, long best) {
		SandField f = SandField.standard(seed);
		redBand(f);
		return SandGame.fromField(seed, 1, best, f);
	}

	private static void redBand(SandField f) {
		for (int y = SandRules.HEIGHT - SandRules.GRAIN; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				f.set(y, x, Grain.of(SandColor.RED, (x + y) & 3, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
	}

	@Test
	void theSavedBestIsReadAtTheStartAndBeatingItIsANewBest() {
		SaveData save = SaveData.inMemory();
		SandSession plain = new SandSession(new SandGame(13L, 1, 50), save, x -> {
		}, () -> 0L);
		SandSnapshot first = plain.snapshot(null);
		assertEquals(50, first.best(), "저장된 최고 점수");
		assertFalse(first.newBest());

		SandSnapshot beat = new SandSession(withRedBand(13L, 50), save, x -> {
		}, () -> 0L).snapshot(null);
		assertEquals(640, beat.score());
		assertEquals(640, beat.best());
		assertTrue(beat.newBest(), "저장된 기록보다 높다");
		SandSnapshot firstGame = new SandSession(withRedBand(13L, 0), save, x -> {
		}, () -> 0L).snapshot(null);
		assertFalse(firstGame.newBest(), "첫 판은 새 기록으로 치지 않는다");
	}

	@Test
	void anUnexpectedErrorStopsTheGameWithAFailureCardAndNoRecord() {
		SaveData save = SaveData.inMemory();
		AtomicInteger overs = new AtomicInteger();
		Heard heard = new Heard();
		AtomicLong now = new AtomicLong(ms(7_000));
		SandSession s = new SandSession(new SandGame(14L, 1, 0), save, x -> overs.incrementAndGet(), now::get);
		s.speaker(heard);
		for (int t = 0; t < 1_000; t++) {
			s.advance(null, false);
		}
		s.fail(new IllegalStateException("시험용"));
		assertTrue(s.over());
		assertNotNull(s.failure());
		assertEquals(1, overs.get());
		assertEquals(ms(7_000), s.overAtNanos());
		s.quit();
		assertEquals(0, save.records().sand().games(), "오류로 멈춘 판은 남기지 않는다");
		assertEquals(0, heard.count(Sfx.KO), "오류로 멈춘 판은 게임 끝 소리가 없다");
	}

	// ------------------------------------------------------------------ 소리

	@Test
	void pieceMovesHardDropsAndLocksSoundLikeTheAudioTable() {
		Heard heard = new Heard();
		SandSession s = new SandSession(new SandGame(31L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		s.speaker(heard);
		s.advance(null, false);
		s.advance(Input.L, false);
		assertEquals(1, heard.count(Sfx.MOVE), "성공한 이동");
		PieceState p = s.game().piece();
		int rows = s.game().ghost().y() - p.y();
		assertTrue(rows >= 10, "빈 판에서는 멀리 떨어진다: " + rows);
		s.advance(Input.HD, false);
		assertEquals(Cue.of(Sfx.HARD_DROP, 2), heard.first(Sfx.HARD_DROP), "10칸 넘게 떨어진 하드드롭은 가장 큰 단계");
		assertEquals(1, heard.count(Sfx.SAND_LAND), "블록이 모래로 부서짐");
		assertEquals(0, heard.count(Sfx.LOCK), "모래 모드는 고전 고정 소리 대신 부서지는 소리");

		// 하드드롭이 아니면 부서지는 소리만 (락 딜레이로 고정)
		Heard quiet = new Heard();
		SandSession lazy = new SandSession(new SandGame(32L, 10, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		lazy.speaker(quiet);
		for (int t = 0; t < 4_000 && lazy.game().pieces() == 0; t++) {
			lazy.advance(null, false);
		}
		assertEquals(1, lazy.game().pieces());
		assertEquals(1, quiet.count(Sfx.SAND_LAND));
		assertEquals(0, quiet.count(Sfx.HARD_DROP));
		assertEquals(1, quiet.count(Sfx.LAND), "바닥에 처음 닿은 소리");
	}

	@Test
	void aClearSoundsOnceAndALevelUpSoundsAtThirtySeconds() {
		// 빨간 띠(알갱이 9행 × 78열 = 702알, 지우기 문턱 640 이상)가 왼쪽 벽에 두 줄 모자라게 놓였다: 띠의 왼쪽 끝이 빈 열로
		// 무너져 내리면 곧 양쪽 벽을 잇는다
		SandField f = SandField.standard(33L);
		for (int y = SandRules.HEIGHT - SandRules.GRAIN - 1; y < SandRules.HEIGHT; y++) {
			for (int x = 2; x < SandRules.WIDTH; x++) {
				f.set(y, x, Grain.of(SandColor.RED, (x + y) & 3, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
		SandSession s = new SandSession(SandGame.fromField(33L, 1, 0, f), SaveData.inMemory(), x -> {
		}, () -> 0L);
		Heard heard = new Heard();
		s.speaker(heard);
		assertEquals(0, s.game().clears(), "처음에는 벽에 닿지 않았다");
		for (int t = 0; t < 2_000 && s.game().clears() == 0; t++) {
			s.advance(null, false);
		}
		assertEquals(1, s.game().clears(), "무너진 모래가 벽에 닿아 지운다");
		assertEquals(List.of(Cue.of(Sfx.SAND_CLEAR)), heard.cues, "지우기 소리 한 번 (배율 1)");

		SandSession slow = new SandSession(new SandGame(34L, 1, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		Heard levels = new Heard();
		slow.speaker(levels);
		for (int t = 0; t < SandRules.LEVEL_MS + 5; t++) {
			slow.advance(null, false);
		}
		assertEquals(2, slow.game().level());
		assertEquals(1, levels.count(Sfx.LEVEL_UP), "단계가 오른 순간 한 번");
	}

	@Test
	void theEndSoundsAKnockOutAndANewRecordWhenTheSavedBestIsBeaten() {
		SaveData save = SaveData.inMemory();
		save.updateRecords(r -> r.withSand(100, 2)); // 한 판, 최고 100점
		SandField f = stripes(35L, 40, SandRules.HEIGHT - SandRules.GRAIN);
		redBand(f); // 시작하자마자 640점, 그 뒤 줄무늬가 내려와 곧 넘친다
		SandSession s = new SandSession(SandGame.fromField(35L, 1, 100, f), save, x -> {
		}, () -> 0L);
		Heard heard = new Heard();
		s.speaker(heard);
		dropUntilOver(s);
		assertEquals(0, heard.count(Sfx.KO), "판이 끝났다고 알리기 전에는 아니다");
		s.finishIfOver();
		s.finishIfOver();
		assertTrue(s.newBest());
		assertEquals(1, heard.count(Sfx.KO), "한 번만");
		Cue record = heard.first(Sfx.NEW_RECORD);
		assertNotNull(record, "최고 기록을 깨면 그 소리");
		assertTrue(record.delayMs() > 0, "게임 끝 소리 뒤에");
		assertEquals(2, save.records().sand().games());
	}

	@Test
	void aNewRecordIsJudgedAgainstWhatAnotherWindowSavedMeanwhile(@TempDir Path dir) {
		// 같은 게임을 둘 켰다: 이 창은 판을 시작할 때 최고 100점을 읽었고, 판 도중 다른 창이 같은 파일에 5,000점을 남겼다
		SaveData save = SaveData.open(dir);
		save.updateRecords(r -> r.withSand(100, 2));
		SaveData other = SaveData.open(dir);
		SandField f = stripes(37L, 40, SandRules.HEIGHT - SandRules.GRAIN);
		redBand(f); // 시작하자마자 640점, 그 뒤 곧 넘친다
		SandSession s = new SandSession(SandGame.fromField(37L, 1, save.records().sand().bestScore(), f), save, x -> {
		}, () -> 0L);
		Heard heard = new Heard();
		s.speaker(heard);
		other.updateRecords(r -> r.withSand(5_000, 3));
		dropUntilOver(s);
		assertTrue(s.game().score() > 100 && s.game().score() < 5_000, "판을 시작할 때의 최고보다만 높다: " + s.game().score());
		s.finishIfOver();
		assertTrue(s.recorded());
		assertFalse(s.newBest(), "다른 창이 그사이 남긴 5,000점이 더하기 바로 전의 최고다");
		assertEquals(0, heard.count(Sfx.NEW_RECORD), "새 기록 소리도 없다");
		assertEquals(1, heard.count(Sfx.KO));
		assertEquals(new PlayRecords.Sand(3, 5_000, Math.max(3, s.game().maxMultiplier())), s.records().sand(),
				"다시 읽은 파일 위에 더했다");
		assertEquals(5_000, s.previousBest());
		assertEquals(5_000, s.bestScore(s.game().score()), "결과 카드의 최고는 더한 뒤의 기록");

		// 다른 창이 남긴 점수가 이 판보다 낮으면 새 기록이고, 이전 최고는 그 점수다(판을 시작할 때의 100점이 아니다)
		SaveData mine = SaveData.open(dir.resolve("b"));
		mine.updateRecords(r -> r.withSand(100, 2));
		SaveData theirs = SaveData.open(dir.resolve("b"));
		SandSession beat = new SandSession(withRedBand(38L, mine.records().sand().bestScore()), mine, x -> {
		}, () -> 0L);
		theirs.updateRecords(r -> r.withSand(300, 2));
		for (int t = 0; t < 10; t++) {
			beat.advance(null, false);
		}
		assertEquals(100, beat.previousBest(), "기록에 더하기 전에는 판을 시작할 때의 최고");
		beat.quit();
		assertTrue(beat.newBest(), "640점 > 다른 창의 300점");
		assertEquals(300, beat.previousBest(), "새 기록을 판단한 기준");
		assertEquals(640, beat.records().sand().bestScore());
		assertEquals(640, beat.bestScore(0));
	}

	@Test
	void aGameThatWasLeftMakesNoMoreSoundsButIsStillRecorded() {
		SaveData save = SaveData.inMemory();
		Heard heard = new Heard();
		SandSession s = new SandSession(nearlyFull(36L, 0), save, x -> {
		}, () -> 0L);
		s.speaker(heard);
		s.stop(); // 판을 떠났다: 진행 스레드가 멈추기 전의 마지막 틱도, 막 끝난 판의 끝 처리도 소리 없이 지나간다
		dropUntilOver(s);
		s.finishIfOver();
		assertEquals(List.of(), heard.cues, "떠난 판은 부서짐·게임 끝 소리를 내지 않는다(메뉴 위로 남지 않게)");
		assertTrue(s.recorded(), "기록은 그대로 남긴다");
		assertEquals(1, save.records().sand().games());
	}

	// ------------------------------------------------------------------ 효과 사건

	@Test
	void locksAndClearsAreRecordedAsEffectEvents() {
		SandSession s = new SandSession(withRedBand(41L, 0), SaveData.inMemory(), x -> {
		}, () -> 0L);
		List<SandSession.ClearFx> clears = s.clearFx();
		assertEquals(1, clears.size(), "처음 판의 띠는 시작하자마자 지워진다");
		SandSession.ClearFx band = clears.get(0);
		assertEquals(0, band.at());
		assertEquals(640, band.grains());
		assertEquals(640, band.points());
		assertEquals(1, band.multiplier());
		assertEquals(40.0, band.cx(), 0.01, "가로 무게중심은 판 가운데");
		assertEquals(SandRules.HEIGHT - SandRules.GRAIN / 2.0, band.cy(), 0.01);
		assertEquals(60, band.pieceCount(), "효과 세기 강하게(×1.5)까지 쓸 조각: 640 × 1.5 / 16");
		assertEquals(40, SandFx.pieceCount(band, 1), "보통 세기: 지운 알갱이 16개마다 하나");
		assertEquals(0, SandFx.pieceCount(band, 0), "효과를 끄면 없다");
		Set<Integer> seen = new HashSet<>();
		for (int k = 0; k < band.pieceCount(); k++) {
			int p = band.piece(k);
			assertEquals(SandColor.RED.code(), p >>> 16, "조각의 색은 덩어리의 색");
			assertTrue((p & 0xFFFF) / SandRules.WIDTH >= SandRules.HEIGHT - SandRules.GRAIN, "조각은 덩어리 안에서 튄다");
			assertTrue(seen.add(p & 0xFFFF), "같은 알갱이를 두 번 고르지 않는다");
		}

		s.advance(null, false);
		PieceState p = s.game().piece();
		int rows = s.game().ghost().y() - p.y();
		s.advance(Input.HD, false);
		List<SandSession.LockFx> locks = s.lockFx();
		assertEquals(1, locks.size());
		SandSession.LockFx lock = locks.get(0);
		assertTrue(lock.hardDrop());
		assertEquals(rows, lock.dropRows(), "하드드롭으로 내려온 칸 수");
		assertEquals(2, lock.at());
		assertEquals(s.game().lastLock().color().code(), lock.colorCode());
		assertEquals(4, lock.size());
		assertArrayEquals(s.game().lastLock().piece().cells(), lock.cells());
		assertFalse(s.softDropping());
	}

	@Test
	void aSecondClearWhileTheFirstStillFlashesGetsOnlyItsOwnGrains() {
		// 아래의 빨간 띠는 시작하자마자 반짝이고(200ms), 그 위에 얹힌 파란 띠(알갱이 9행 × 79열 = 711알, 왼쪽 벽에 한 줄
		// 모자람)의 왼쪽 끝이 빈 열로 무너져 벽에 닿으면 따로 지워진다
		SandField f = SandField.standard(42L);
		redBand(f);
		for (int y = SandRules.HEIGHT - 2 * SandRules.GRAIN - 1; y < SandRules.HEIGHT - SandRules.GRAIN; y++) {
			for (int x = 1; x < SandRules.WIDTH; x++) {
				f.set(y, x, Grain.of(SandColor.BLUE, (x + y) & 3, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
		SandSession s = new SandSession(SandGame.fromField(42L, 1, 0, f), SaveData.inMemory(), x -> {
		}, () -> 0L);
		for (int t = 0; t < SandRules.FLASH_MS && s.game().clears() < 2; t++) {
			s.advance(null, false);
		}
		assertEquals(2, s.game().clears(), "빨강이 사라지기 전에 파랑을 지웠다");
		List<SandSession.ClearFx> clears = s.clearFx();
		assertEquals(2, clears.size());
		SandSession.ClearFx red = clears.get(0);
		SandSession.ClearFx blue = clears.get(1);
		assertEquals(640, red.grains());
		Set<Integer> redCells = new HashSet<>();
		for (int k = 0; k < red.grains(); k++) {
			redCells.add(red.cell(k));
		}
		assertTrue(blue.grains() > 0);
		for (int k = 0; k < blue.grains(); k++) {
			int i = blue.cell(k);
			assertFalse(redCells.contains(i), "앞 지우기의 알갱이는 빠진다");
			assertEquals(SandColor.BLUE, Grain.color(s.game().grain(i / SandRules.WIDTH, i % SandRules.WIDTH)));
		}
		assertTrue(blue.at() > 0 && blue.at() < SandRules.FLASH_MS);
	}

	/**
	 * 얇은 띠(양쪽 벽을 이었지만 640알이 안 되는 덩어리)가 판에 나타난 판 시각을 적고, 띠가 모두 사라지면 지운다(화면이 표시를
	 * 천천히 들인다). 처음 판: 맨 아래 세 행 파랑(240알)과 그 위에 떠 있는 파랑 다섯 행(400알). 둘 다 양쪽 벽을 잇지만 얇다.
	 * 떠 있는 줄이 내려와 아래 띠에 닿으면 640알이 되어 지워진다.
	 */
	@Test
	void theSessionNotesWhenAThinBandAppearsAndForgetsItWhenTheBandGoes() {
		SandField f = SandField.standard(61L);
		for (int y = SandRules.HEIGHT - 40; y < SandRules.HEIGHT; y++) {
			if (y >= SandRules.HEIGHT - 35 && y < SandRules.HEIGHT - 3) {
				continue;
			}
			for (int x = 0; x < SandRules.WIDTH; x++) {
				f.set(y, x, Grain.of(SandColor.BLUE, (x + y) & 3, y % SandRules.GRAIN, x % SandRules.GRAIN));
			}
		}
		SandSession s = new SandSession(SandGame.fromField(61L, 1, 0, f), SaveData.inMemory(), x -> {
		}, () -> 0L);
		assertEquals(0, s.game().clears());
		assertEquals(SandRules.MIN_CLEAR_GRAINS, s.game().thinGrains(), "얇은 띠 둘 (240 + 400알)");
		assertEquals(0, s.thinSince(), "처음 판부터 있던 띠는 0ms 부터");
		for (int t = 0; t < 1_000 && s.game().clears() == 0; t++) {
			s.advance(null, false);
			if (s.game().clears() == 0) {
				assertEquals(0, s.thinSince(), "띠가 남아 있는 동안은 그대로");
			}
		}
		assertEquals(1, s.game().clears(), "떠 있던 줄이 내려와 닿으면 640알이 되어 지운다");
		assertEquals(SandRules.MIN_CLEAR_GRAINS, s.game().lastClear().grains());
		assertEquals(0, s.game().thinGrains());
		assertEquals(SandSession.NO_THIN, s.thinSince(), "띠가 사라지면 지운다");
	}

	@Test
	void theGameOverClockIsTheSessionClockAtTheEnd() {
		AtomicLong now = new AtomicLong(ms(1_000));
		SandSession s = new SandSession(nearlyFull(43L, 0), SaveData.inMemory(), x -> {
		}, now::get);
		assertEquals(SandSession.NOT_OVER, s.overAtNanos());
		now.set(ms(9_876));
		dropUntilOver(s);
		assertEquals(ms(9_876), s.overAtNanos());
		assertEquals(ms(9_876), s.clockNanos());
	}

	// ------------------------------------------------------------------ 실제 진행 스레드로 두는 모래 AI

	/**
	 * 모래 AI(상급)가 실제 진행 스레드로 30초(판의 시계)를 둔다. 시계는 8배 빠르게 가므로 약 4초 걸린다. 그동안 화면을 계속
	 * 그리고(잠금을 함께 쓴다) 판의 시계가 실제 시간을 놓치지 않는지(멈춤 없음) 본다. 기록은 시험 폴더의 저장 파일에만 남는다.
	 */
	@Test
	void theSandAiPlaysThroughTheRealLoopWithoutStallsAndRecordsOnlyInTheTestFolder(@TempDir Path dir)
			throws Exception {
		SaveData save = SaveData.open(dir);
		long t0 = System.nanoTime();
		LongSupplier fast = () -> t0 + (System.nanoTime() - t0) * 8;
		long seed = 990_001L;
		AtomicInteger overs = new AtomicInteger();
		SandSession s = new SandSession(new SandGame(seed, 1, save.records().sand().bestScore()), save,
				x -> overs.incrementAndGet(), fast);
		s.speaker(new Heard());
		SandAiController ai = new SandAiController(s.game(), SandAiLevel.of(3), seed, 1);
		s.drive(new SandSession.Hand() {
			@Override
			public Input next(long tick) {
				return ai.poll();
			}

			@Override
			public boolean softDrop() {
				return false;
			}
		});
		SandPanel panel = new SandPanel(new SandPanelTest.Counts(), save);
		panel.setSession(s);
		panel.setSize(1280, 720);
		AtomicReference<Throwable> paintError = new AtomicReference<>();
		AtomicInteger frames = new AtomicInteger();
		Thread painter = new Thread(() -> {
			BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
			while (!Thread.currentThread().isInterrupted()) {
				try {
					Graphics2D g = img.createGraphics();
					panel.printAll(g);
					g.dispose();
					frames.incrementAndGet();
					Thread.sleep(16);
				} catch (InterruptedException e) {
					return;
				} catch (Throwable e) {
					paintError.compareAndSet(null, e);
					return;
				}
			}
		}, "sand-painter");
		long worstLag = 0;
		s.start();
		painter.start();
		try {
			long until = System.nanoTime() + 30_000_000_000L;
			while (s.time() < 30_000 && !s.over() && System.nanoTime() < until) {
				Thread.sleep(20);
				long expected = (fast.getAsLong() - t0) / 1_000_000 - SandSession.COUNTDOWN_MS;
				if (expected > 500) {
					worstLag = Math.max(worstLag, expected - s.time());
				}
			}
		} finally {
			painter.interrupt();
			painter.join(5_000);
			s.quit();
		}
		assertNull(paintError.get(), "그리다 예외가 나지 않는다");
		assertNull(s.failure(), "진행 중 오류 없음");
		assertNull(ai.problem(), "AI 가 고르다 예외를 내지 않았다");
		assertTrue(s.time() >= 30_000 || s.over(), "30초를 두었다(또는 졌다): " + s.time());
		assertTrue(frames.get() > 20, "화면을 계속 그렸다: " + frames.get());
		// 멈춤(교착)이면 끝없이 뒤처진다. 다른 빌드가 CPU 를 함께 쓰는 때의 잠깐 밀림(실제 0.5초 = 판 4초)까지는 봐준다
		assertTrue(worstLag < 4_000, "판의 시계가 실제 시간을 놓치지 않는다(8배 빠른 시계에서 판 시각으로): " + worstLag + "ms");
		assertTrue(s.game().pieces() > 10, "블록을 두었다: " + s.game().pieces());
		assertEquals(1, save.records().sand().games(), "기록은 한 번");
		assertTrue(Files.exists(dir.resolve(SaveData.FILE_NAME)), "기록은 시험 폴더의 저장 파일에");
	}
}
