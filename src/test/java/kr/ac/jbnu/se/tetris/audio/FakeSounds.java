package kr.ac.jbnu.se.tetris.audio;

import java.util.concurrent.locks.LockSupport;

import javax.sound.sampled.LineUnavailableException;

/**
 * 다른 패키지의 시험과 화면 확인 도구(ui.ScreensPreview)가 쓰는 엔진. 진짜 소리 장치는 열지 않는다.
 * <ul>
 * <li>{@link #ready()}: 소리를 내는 엔진(READY)이지만 섞은 소리는 버린다. 설정 화면의 "효과음이 켜져 있어요" 모습.</li>
 * <li>{@link #noDevice()}: 장치를 열지 못해 꺼진 엔진. "소리 장치를 찾지 못했어요" 모습.</li>
 * </ul>
 * 다 쓰면 {@code close()} 한다.
 */
public final class FakeSounds {

	private FakeSounds() {
	}

	/** 장치 대신 버리는 출력. 진짜 장치처럼 쓴 길이만큼(4 배 빠르게) 기다려 믹서가 바쁘게 돌지 않게 한다. */
	private static final class Discard implements AudioOut {
		@Override
		public int write(byte[] data, int off, int len) {
			LockSupport.parkNanos(len / 4 * 1_000_000_000L / Synth.RATE / 4);
			return len;
		}

		@Override
		public void pause() {
		}

		@Override
		public void resume() {
		}

		@Override
		public void close() {
		}
	}

	/** 소리를 내는 것처럼 보이는 엔진 (READY). 소리는 짧은 무음이라 만드는 데 시간이 들지 않는다. */
	public static Sound ready() {
		Sound s = Sound.open(Discard::new, () -> SoundBank.build((e, v) -> new float[441]), FakeSounds::now,
				Sound.IDLE_MS);
		await(s, Sound.State.READY);
		return s;
	}

	/** 소리 장치를 열지 못해 꺼진 엔진 (OFF, {@link Sound.OffCause#NO_DEVICE}). */
	public static Sound noDevice() {
		Sound s = Sound.open(() -> {
			throw new LineUnavailableException("소리 장치 없음 (흉내)");
		}, FakeSounds::now, Sound.IDLE_MS);
		await(s, Sound.State.OFF);
		return s;
	}

	private static long now() {
		return System.nanoTime() / 1_000_000;
	}

	private static void await(Sound s, Sound.State want) {
		long end = System.nanoTime() + 10_000_000_000L;
		while (s.state() != want) {
			if (System.nanoTime() > end) {
				throw new IllegalStateException("엔진이 " + want + " 가 되지 않음: " + s.state() + " " + s.reason());
			}
			LockSupport.parkNanos(2_000_000L);
		}
	}
}
