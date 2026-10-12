package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import javax.sound.sampled.LineUnavailableException;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 효과음 엔진 (Sound): 소리 장치가 없으면 모든 호출이 조용히 아무 일도 하지 않고(소리도 만들지 않는다), 가짜 장치로는 소리가
 * 실제로 쓰이며, 음소거·볼륨 0·쿨다운·쉬기·모두 멈춤·닫기가 동작한다. 장치 버퍼는 25ms 이고, 처음 30초에 끊김이 잦으면 40ms
 * 로 늘린다. 판이 진행 중이면(MatchSounds·PieceSounds 가 알린다) 조용해도 장치를 쉬게 하지 않는다. 진짜 소리 장치는 열지
 * 않는다(빌드 중에 소리가 나지 않게, 장치 없는 CI 에서도 통과하게).
 */
class SoundTest {

	/** 진짜 장치 대신 받은 PCM 을 모은다. 진짜 장치처럼 쓴 길이만큼 기다리되 4 배 빠르게. */
	private static final class FakeOut implements AudioOut {
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		final AtomicInteger pauses = new AtomicInteger();
		final AtomicInteger resumes = new AtomicInteger();
		final AtomicInteger drains = new AtomicInteger();
		volatile boolean closed;
		/** 닫을 때 그 전에 남은 소리를 내보냈는가. */
		volatile boolean drainedBeforeClose;
		/** 이어폰을 뽑은 것처럼 쓰기에서 오류를 낸다. */
		volatile boolean unplugged;
		/** 장치 버퍼 크기 (바이트, -1 이면 모름). */
		volatile int bufferBytes = -1;
		/** true 면 쓰기 전마다 버퍼가 비어 있다고 알린다 (소리가 끊긴 PC 흉내). */
		volatile boolean starving;
		/** false 면 진짜 장치처럼 기다리지 않고 바로 받는다 (긴 시간을 빨리 흘려보낼 때). */
		volatile boolean paced = true;
		final AtomicLong writes = new AtomicLong();

		@Override
		public int write(byte[] data, int off, int len) {
			if (unplugged) {
				throw new IllegalStateException("장치가 사라짐 (시험)");
			}
			writes.incrementAndGet();
			if (!paced) {
				return len; // 모아 두지 않고 바로 받는다
			}
			synchronized (bytes) {
				bytes.write(data, off, len);
			}
			LockSupport.parkNanos(len / 4 * 1_000_000_000L / Synth.RATE / 4);
			return len;
		}

		@Override
		public int available() {
			return starving ? bufferBytes : 0;
		}

		@Override
		public int bufferBytes() {
			return bufferBytes;
		}

		@Override
		public void pause() {
			pauses.incrementAndGet();
		}

		@Override
		public void resume() {
			resumes.incrementAndGet();
		}

		@Override
		public void drain() {
			drains.incrementAndGet();
		}

		@Override
		public void close() {
			drainedBeforeClose = drains.get() > 0;
			closed = true;
		}

		/** 마지막 {@code frames} 프레임의 왼쪽 채널 표본. */
		short[] lastLeft(int frames) {
			byte[] b;
			synchronized (bytes) {
				b = bytes.toByteArray();
			}
			short[] out = new short[frames];
			int start = b.length - frames * 4;
			for (int i = 0; i < frames; i++) {
				int at = start + 4 * i;
				out[i] = (short) ((b[at] & 0xFF) | (b[at + 1] << 8));
			}
			return out;
		}

		/** 지금까지 받은 가장 큰 표본의 절댓값. */
		int loudest() {
			byte[] b;
			synchronized (bytes) {
				b = bytes.toByteArray();
			}
			int max = 0;
			for (int i = 0; i + 1 < b.length; i += 2) {
				max = Math.max(max, Math.abs((short) ((b[i] & 0xFF) | (b[i + 1] << 8))));
			}
			return max;
		}

		void forget() {
			synchronized (bytes) {
				bytes.reset();
			}
		}
	}

	private static void waitFor(BooleanSupplier condition, String what) {
		long end = System.nanoTime() + 10_000_000_000L;
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > end) {
				fail("기다렸지만 오지 않음: " + what);
			}
			LockSupport.parkNanos(2_000_000L);
		}
	}

	private static void sleepMs(long ms) {
		LockSupport.parkNanos(ms * 1_000_000L);
	}

	/** 믹서가 블록을 {@code chunks} 개 더 쓸 때까지 기다린다 (스레드가 밀려도 시간 대신 진행으로 잰다). */
	private static void waitChunks(Sound s, int chunks) {
		long from = s.framesWritten();
		waitFor(() -> s.framesWritten() >= from + (long) chunks * Sound.CHUNK, chunks + " 블록 더 씀");
	}

	private static Sound readyEngine(FakeOut out, int idleMs) {
		Sound s = Sound.open(() -> out, () -> System.nanoTime() / 1_000_000, idleMs);
		waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
		return s;
	}

	/** 알아보기 쉬운 표본: 소리마다 일정한 크기가 길게 이어진다(가짜 장치가 4 배 빨라도 시험 동안 끝나지 않게). */
	private static SoundBank flatBank() {
		return SoundBank.build((e, v) -> {
			float level;
			int seconds;
			switch (e) {
			case PERFECT_CLEAR:
				level = 0.5f;
				seconds = 20;
				break;
			case MENU_SELECT:
				level = 0.1f;
				seconds = 10;
				break;
			case GARBAGE:
				level = 0.3f;
				seconds = v == 0 ? 5 : 0; // 시험은 1줄(변형 0)만 쓴다
				break;
			default:
				level = 0.05f;
				seconds = 0;
				break;
			}
			float[] c = new float[Math.max(441, seconds * Synth.RATE)];
			Arrays.fill(c, level);
			return c;
		});
	}

	/** 기본 볼륨에서 크기 {@code level} 인 표본이 장치에 쓰이는 값. */
	private static int pcmAtDefaultVolume(double level) {
		return (int) Math.round(level * Sound.DEFAULT_VOLUME * Sound.DEFAULT_VOLUME * 32767);
	}

	@Test
	void beforeStartTheSharedEngineIsSilent() {
		Sound s = Sound.get();
		assertEquals(Sound.State.OFF, s.state(), "start() 를 부르기 전에는 장치를 열지 않는다");
		assertEquals(Sound.OffCause.UNUSED, s.offCause());
		assertFalse(s.isAvailable());
		assertDoesNotThrow(() -> {
			s.play(Sfx.TSPIN, 3);
			s.play(Cue.of(Sfx.KO));
			s.play(SoundCues.result(TimedMatch.Result.P1, true));
			s.stopAll();
		});
	}

	@Test
	void aMissingDeviceTurnsEverySoundIntoANoOpWithoutMakingSounds() {
		AtomicInteger made = new AtomicInteger();
		Sound s = Sound.open(() -> {
			throw new LineUnavailableException("소리 장치 없음 (시험)");
		}, () -> {
			made.incrementAndGet();
			return SoundBank.generate();
		}, () -> 0L, Sound.IDLE_MS);
		waitFor(() -> s.state() == Sound.State.OFF, "장치가 없으면 OFF");
		assertTrue(s.reason().contains("LineUnavailableException"), s.reason());
		assertEquals(Sound.OffCause.NO_DEVICE, s.offCause());
		assertEquals(0, made.get(), "장치가 없으면 소리를 만들지 않는다(시간과 메모리를 쓰지 않는다)");
		assertFalse(s.isAvailable());
		assertDoesNotThrow(() -> {
			for (Sfx e : Sfx.values()) {
				s.play(e);
				s.play(e, 3, -0.5);
			}
			s.play((List<Cue>) null);
			s.play((Cue) null);
			s.play((Sfx) null);
			s.stopAll();
			s.setVolume(0.3);
			s.setMuted(true);
			s.close();
			s.close();
		});
		assertEquals(0, s.accepted());
		assertEquals(Sound.State.CLOSED, s.state());
		assertEquals(Sound.OffCause.UNUSED, s.offCause(), "닫은 엔진");
	}

	@Test
	void brokenNativeAudioIsAlsoSilent() {
		// 헤드리스 서버에서는 소리 라이브러리 자체가 없어 링크 오류가 날 수도 있다
		Sound s = Sound.open(() -> {
			throw new UnsatisfiedLinkError("libjsound 없음 (시험)");
		}, () -> 0L, Sound.IDLE_MS);
		waitFor(() -> s.state() == Sound.State.OFF, "링크 오류도 OFF");
		assertEquals(Sound.OffCause.NO_DEVICE, s.offCause());
		assertDoesNotThrow(() -> s.play(Sfx.CLEAR, 4));
		s.close();
	}

	@Test
	void aDeviceThatDisappearsTurnsSoundOffAndSaysWhy() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, Sound.IDLE_MS);
		try {
			assertEquals(Sound.OffCause.NONE, s.offCause());
			out.unplugged = true; // 이어폰을 뽑음
			s.play(Sfx.CLEAR, 4);
			waitFor(() -> s.state() == Sound.State.OFF, "쓰다가 오류가 나면 OFF");
			assertEquals(Sound.OffCause.DEVICE_LOST, s.offCause());
			assertTrue(s.reason().contains("IllegalStateException"), s.reason());
			waitFor(() -> out.closed, "장치를 닫는다");
			assertDoesNotThrow(() -> s.play(Sfx.KO), "게임은 소리 없이 계속한다");
		} finally {
			s.close();
		}
	}

	@Test
	void theDeviceStartsOnlyAfterTheSoundsAreMade() {
		FakeOut out = new FakeOut();
		AtomicInteger resumesWhileMaking = new AtomicInteger(-1);
		Sound s = Sound.open(() -> out, () -> {
			resumesWhileMaking.set(out.resumes.get());
			return SoundBank.generate();
		}, () -> System.nanoTime() / 1_000_000, Sound.IDLE_MS);
		try {
			waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
			assertEquals(0, resumesWhileMaking.get(), "소리를 만드는 동안 장치는 멈춰 있다(빈 소리를 흘리지 않는다)");
			assertEquals(1, out.resumes.get(), "다 만든 뒤에 한 번 움직인다");
		} finally {
			s.close();
		}
	}

	@Test
	void aBrokenSoundBankTurnsSoundOffAndClosesTheDevice() {
		FakeOut out = new FakeOut();
		Sound s = Sound.open(() -> out, () -> {
			throw new IllegalStateException("만들다 실패 (시험)");
		}, () -> 0L, Sound.IDLE_MS);
		waitFor(() -> s.state() == Sound.State.OFF, "소리를 못 만들면 OFF");
		assertTrue(s.reason().contains("IllegalStateException"), s.reason());
		assertEquals(Sound.OffCause.BROKEN, s.offCause());
		waitFor(() -> out.closed, "연 장치는 닫는다");
		assertDoesNotThrow(() -> s.play(Sfx.MOVE));
	}

	@Test
	void soundOffPropertyGivesASilentEngineAndKeepsSettings() {
		Sound before = Sound.get();
		String old = System.getProperty("tetris.sound");
		System.setProperty("tetris.sound", "off");
		try {
			Sound placeholder = Sound.silent();
			placeholder.setVolume(0.25);
			placeholder.setMuted(true);
			Sound.install(placeholder); // 설정을 읽어 start() 전에 볼륨·음소거를 정해 둔 경우
			assertTrue(Sound.offReason().contains("tetris.sound"));
			Sound s = Sound.start();
			assertEquals(Sound.State.OFF, s.state());
			assertEquals(Sound.OffCause.OPTION, s.offCause(), "설정 화면은 '장치 없음' 이 아니라 '옵션으로 끔' 을 보인다");
			assertTrue(s.reason().contains("tetris.sound"), s.reason());
			assertEquals(0.25, s.volume(), 1e-9, "start() 전에 정한 볼륨을 이어받는다");
			assertTrue(s.muted());
			assertEquals(s, Sound.get());
			assertDoesNotThrow(() -> s.play(Sfx.WIN));
		} finally {
			if (old == null) {
				System.clearProperty("tetris.sound");
			} else {
				System.setProperty("tetris.sound", old);
			}
			Sound.install(before);
		}
	}

	@Test
	void playedSoundsReachTheDeviceAndCloseReleasesIt() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, Sound.IDLE_MS);
		try {
			assertTrue(s.isAvailable());
			s.play(Sfx.CLEAR, 4);
			waitFor(() -> out.loudest() > 1000, "테트리스 소리가 장치에 쓰인다");
			assertTrue(out.loudest() < 32767 * 0.6, "기본 볼륨에서는 크지 않다: " + out.loudest());
		} finally {
			s.close();
		}
		assertEquals(Sound.State.CLOSED, s.state());
		waitFor(() -> out.closed, "닫으면 믹서 스레드가 장치를 닫는다");
		long frames = s.framesWritten();
		sleepMs(50);
		assertEquals(frames, s.framesWritten(), "닫은 뒤에는 쓰지 않는다");
		assertDoesNotThrow(() -> s.play(Sfx.KO), "닫은 엔진에 불러도 괜찮다");
	}

	@Test
	void closeFadesToSilenceAndDrainsBeforeClosingTheDevice() {
		// 울리는 중에 닫아도 출력이 뚝 끊기지 않는다(딸깍·퍽 소리 없음): 0 까지 줄이고, 남은 소리를 내보낸 뒤 닫는다
		FakeOut out = new FakeOut();
		Sound s = Sound.open(() -> out, SoundTest::flatBank, () -> System.nanoTime() / 1_000_000, 60_000);
		waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
		s.play(Sfx.PERFECT_CLEAR); // 20초 동안 일정한 크기
		int loud = pcmAtDefaultVolume(0.5);
		waitFor(() -> out.loudest() >= loud - 2, "긴 소리가 난다");
		waitChunks(s, 2);
		s.close();
		assertTrue(out.closed, "close() 는 장치를 닫을 때까지 기다린다");
		assertTrue(out.drainedBeforeClose, "남은 소리를 다 내보낸 뒤에 닫는다");
		short[] tail = out.lastLeft((Sound.CLOSE_FADE_CHUNKS + 1) * Sound.CHUNK); // 울리던 블록 하나와 줄이는 블록들
		assertEquals(loud, tail[0], 2, "줄이기 전에는 원래 크기");
		int biggestStep = 0;
		for (int i = 1; i < tail.length; i++) {
			biggestStep = Math.max(biggestStep, Math.abs(tail[i] - tail[i - 1]));
		}
		assertTrue(biggestStep < loud / 40, "한 표본 사이에 크게 뛰지 않는다: " + biggestStep + " (그냥 끊으면 " + loud + ")");
		for (int i = tail.length - Sound.CHUNK; i < tail.length; i++) {
			assertEquals(0, tail[i], "마지막 블록은 0: 장치를 닫는 순간 출력이 이미 0 이다");
		}
	}

	@Test
	void closingARestingEngineNeedsNoFade() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, 50);
		s.play(Sfx.MENU_MOVE);
		waitFor(() -> out.pauses.get() >= 1, "조용해져 장치를 쉬게 한다");
		long frames = s.framesWritten();
		s.close();
		assertTrue(out.closed);
		assertEquals(frames, s.framesWritten(), "쉬던 장치는 비어 있어 더 쓰지 않고 닫는다");
		assertEquals(0, out.drains.get());
	}

	@Test
	void muteFadesOutAndDropsNewRequests() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, 60_000); // 시험 동안 잠들지 않게(블록 수로 기다리므로)
		try {
			s.play(Sfx.PERFECT_CLEAR); // 약 2초짜리
			waitFor(() -> out.loudest() > 1000, "소리가 난다");
			s.setMuted(true);
			waitChunks(s, 3); // 음소거를 본 블록에서 줄어들기 시작해 다음 블록에는 끝난다
			out.forget();
			waitChunks(s, 4);
			assertEquals(0, out.loudest(), "음소거하면 울리던 소리도 곧 사라진다");
			long before = s.accepted();
			s.play(Sfx.KO);
			assertEquals(before, s.accepted(), "음소거 중의 요청은 받지 않는다");
			s.setMuted(false);
			s.play(Sfx.KO);
			assertEquals(before + 1, s.accepted());
			waitFor(() -> out.loudest() > 1000, "음소거를 풀면 다시 소리가 난다");
		} finally {
			s.close();
		}
	}

	@Test
	void zeroVolumeIsLikeMute() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, Sound.IDLE_MS);
		try {
			s.setVolume(0);
			s.play(Sfx.KO);
			assertEquals(0, s.accepted(), "볼륨 0 이면 요청을 받지 않는다(장치를 깨우지도 않는다)");
			s.setVolume(0.5);
			s.play(Sfx.KO);
			assertEquals(1, s.accepted());
		} finally {
			s.close();
		}
	}

	@Test
	void rapidRepeatsAreLimitedByTheCooldown() {
		AtomicLong now = new AtomicLong(1_000);
		FakeOut out = new FakeOut();
		Sound s = Sound.open(() -> out, now::get, Sound.IDLE_MS);
		try {
			waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
			for (int i = 0; i < 100; i++) {
				s.play(Sfx.MOVE); // 같은 순간에 100 번
			}
			assertEquals(1, s.accepted(), "쿨다운 안의 반복은 버린다");
			now.addAndGet(Sfx.MOVE.cooldownMs);
			s.play(Sfx.MOVE);
			s.play(Sfx.ROTATE);
			assertEquals(3, s.accepted(), "쿨다운이 지나면 다시, 다른 소리는 따로");
			s.play(Sfx.CLEAR, 4, -0.5);
			s.play(Sfx.CLEAR, 4, 0.5);
			assertEquals(4, s.accepted(), "좌우 위치가 달라도 같은 소리는 쿨다운을 함께 쓴다");
		} finally {
			s.close();
		}
	}

	@Test
	void aQuietEngineRestsTheDeviceAndWakesUpForTheNextSound() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, 100);
		try {
			s.play(Sfx.MENU_MOVE);
			waitFor(() -> out.pauses.get() >= 1, "조용해지면 장치를 쉬게 한다");
			long frames = s.framesWritten();
			int resumes = out.resumes.get();
			sleepMs(80);
			assertEquals(frames, s.framesWritten(), "쉬는 동안에는 쓰지 않는다(잔다)");
			out.forget();
			s.play(Sfx.MENU_SELECT);
			waitFor(() -> out.resumes.get() > resumes && out.loudest() > 500, "다음 소리에 깨어나 소리를 낸다");
		} finally {
			s.close();
		}
	}

	@Test
	void stopAllWhileRestingDoesNotWakeTheDevice() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, 100);
		try {
			s.play(Sfx.MENU_MOVE);
			waitFor(() -> out.pauses.get() >= 1, "쉰다");
			long frames = s.framesWritten();
			int resumes = out.resumes.get();
			s.stopAll();
			sleepMs(60);
			assertEquals(resumes, out.resumes.get(), "멈출 것이 없으면 장치를 깨우지 않는다");
			assertEquals(frames, s.framesWritten());
		} finally {
			s.close();
		}
	}

	@Test
	void delayedCuesWaitBeforeSounding() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, Sound.IDLE_MS);
		try {
			sleepMs(30);
			out.forget();
			s.play(Cue.of(Sfx.GARBAGE, 8).withDelay(400));
			sleepMs(150);
			assertEquals(0, out.loudest(), "0.4초가 지나기 전에는 조용하다");
			waitFor(() -> out.loudest() > 1000, "0.4초 뒤에 울린다");
		} finally {
			s.close();
		}
	}

	@Test
	void stopAllEndsLongAndPendingSoundsButNotTheNextOnes() {
		FakeOut out = new FakeOut();
		Sound s = Sound.open(() -> out, SoundTest::flatBank, () -> System.nanoTime() / 1_000_000, Sound.IDLE_MS);
		try {
			waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
			s.play(Sfx.PERFECT_CLEAR); // 판 끝의 긴 음악 대신
			int loud = pcmAtDefaultVolume(0.5);
			waitFor(() -> out.loudest() >= loud - 2, "긴 소리가 난다");
			s.play(Cue.of(Sfx.GARBAGE).withDelay(150)); // 아직 울리지 않은 늦춘 소리
			s.stopAll(); // 판을 떠난다
			s.play(Sfx.MENU_SELECT); // 그 뒤의 메뉴 소리
			waitChunks(s, 3);
			out.forget();
			sleepMs(400); // 늦춘 소리가 울렸을 때를 넘긴다
			waitChunks(s, 2);
			int quiet = pcmAtDefaultVolume(0.1);
			assertEquals(quiet, out.loudest(), 2, "남은 것은 멈춘 뒤에 부른 메뉴 소리뿐이다 (긴 소리 " + loud + ", 늦춘 소리 "
					+ pcmAtDefaultVolume(0.3) + ")");
		} finally {
			s.close();
		}
	}

	@Test
	void theDeviceBufferIsShortWithASaferFallback() {
		assertEquals(Synth.RATE / 40, Sound.LINE_BUFFER_FRAMES, "25ms");
		assertEquals(Synth.RATE / 25, Sound.SAFE_BUFFER_FRAMES, "끊김이 잦으면 40ms");
		assertEquals(3, Sound.MAX_UNDERRUNS);
		assertEquals(30L * Synth.RATE, Sound.UNDERRUN_WINDOW_FRAMES, "처음 30초 동안 센다");
	}

	@Test
	void frequentUnderrunsSwitchToTheSaferBufferOnce() {
		FakeOut first = new FakeOut();
		first.bufferBytes = Sound.CHUNK * 4; // 한 블록이면 다 찬다
		FakeOut safer = new FakeOut();
		AtomicInteger saferOpens = new AtomicInteger();
		Sound s = Sound.open(() -> first, () -> {
			saferOpens.incrementAndGet();
			return safer;
		}, SoundTest::flatBank, () -> System.nanoTime() / 1_000_000, 60_000, 1L);
		try {
			waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
			s.play(Sfx.PERFECT_CLEAR); // 계속 쓰게 긴 소리
			waitChunks(s, 3);
			assertEquals(0, s.underruns(), "버퍼가 차 있으면 끊김이 아니다");
			assertFalse(s.safeBuffer());
			first.starving = true; // 이제 쓰기 전마다 버퍼가 비어 있다
			waitFor(() -> safer.writes.get() > 2, "끊김이 3 번을 넘으면 버퍼를 늘린 새 장치로 계속 쓴다");
			assertTrue(s.safeBuffer());
			assertEquals(Sound.MAX_UNDERRUNS + 1, s.underruns(), "4 번째 끊김에서");
			assertEquals(1, saferOpens.get(), "40ms 장치를 한 번 연다");
			assertTrue(first.closed && first.drainedBeforeClose, "앞의 장치는 남은 소리를 내보낸 뒤 닫는다");
			assertEquals(Sound.State.READY, s.state());
			waitChunks(s, 3);
			assertEquals(1, saferOpens.get(), "그 실행 동안 한 번만");
		} finally {
			s.close();
		}
		assertTrue(safer.closed, "닫으면 새 장치를 닫는다");
	}

	@Test
	void underrunsAfterTheFirstThirtySecondsAreNotCounted() {
		FakeOut out = new FakeOut();
		out.bufferBytes = Sound.CHUNK * 4;
		out.paced = false; // 기다리지 않고 받아 30초를 빨리 흘려보낸다
		AtomicInteger saferOpens = new AtomicInteger();
		Sound s = Sound.open(() -> out, () -> {
			saferOpens.incrementAndGet();
			return new FakeOut();
		}, SoundTest::flatBank, () -> System.nanoTime() / 1_000_000, 60_000, 1L);
		try {
			waitFor(() -> s.state() == Sound.State.READY, "엔진 준비");
			s.play(Sfx.PERFECT_CLEAR); // 20초짜리, 그 뒤로는 쉬기 전까지(1분) 무음을 쓴다
			waitFor(() -> s.framesWritten() > Sound.UNDERRUN_WINDOW_FRAMES + Sound.CHUNK * 8, "30초를 넘게 썼다");
			out.starving = true;
			long from = s.framesWritten();
			waitFor(() -> s.framesWritten() > from + Sound.CHUNK * 20, "더 쓴다");
			assertEquals(0, s.underruns(), "처음 30초가 지난 뒤의 끊김은 세지 않는다");
			assertFalse(s.safeBuffer());
			assertEquals(0, saferOpens.get());
		} finally {
			s.close();
		}
	}

	@Test
	void anActiveMatchKeepsTheDeviceAwakeAndWakesItUp() {
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, 50);
		try {
			s.play(Sfx.MENU_MOVE);
			waitFor(() -> out.pauses.get() >= 1, "판이 없으면 조용해진 뒤 쉰다");
			int resumes = out.resumes.get();
			s.markActive(); // 판이 시작됨
			waitFor(() -> out.resumes.get() > resumes, "판이 진행 중이면 쉬던 장치를 바로 깨운다");
			s.markActive(); // 판 동안에는 몇십 ms 마다 다시 알린다
			assertTrue(s.activeNow());
			long frames = s.framesWritten();
			int pauses = out.pauses.get();
			sleepMs(200);
			assertTrue(s.framesWritten() > frames + Sound.CHUNK * 4, "조용해도 무음을 계속 쓴다(쉬지 않는다)");
			assertEquals(pauses, out.pauses.get(), "판 동안은 쉬지 않는다");
			waitFor(() -> out.pauses.get() > pauses, "알림이 끊기면(멈춤, 판 끝) 다시 쉰다");
			assertFalse(s.activeNow());
		} finally {
			s.close();
		}
	}

	@Test
	void matchAndPieceSoundsMarkTheMatchActiveAutomatically() {
		Sound before = Sound.get();
		FakeOut out = new FakeOut();
		Sound s = readyEngine(out, Sound.IDLE_MS);
		Sound.install(s);
		try {
			assertFalse(s.activeNow(), "판이 없으면 진행 중이 아니다");
			TimedMatch m = new TimedMatch(3, 2, GameModel.Scheduler.NONE, 0);
			MatchSounds sounds = new MatchSounds();
			sounds.before(m);
			m.tick(null, null);
			sounds.after(m, null);
			assertTrue(s.activeNow(), "MatchSounds 가 틱마다 알린다 (화면 코드는 부를 것이 없다)");
		} finally {
			Sound.install(before);
			s.close();
		}
		assertDoesNotThrow(() -> Sound.get().markActive(), "소리 없는 엔진에 알려도 아무 일도 없다");
	}

	@Test
	void volumeIsClamped() {
		Sound s = Sound.silent();
		assertEquals(Sound.DEFAULT_VOLUME, s.volume());
		s.setVolume(2);
		assertEquals(1.0, s.volume());
		s.setVolume(-1);
		assertEquals(0.0, s.volume());
		s.setVolume(Double.NaN);
		assertEquals(Sound.DEFAULT_VOLUME, s.volume());
	}
}
