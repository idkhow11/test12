package kr.ac.jbnu.se.tetris.audio;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * 녹음한 효과음(CC0, {@code src/main/resources/sfx/*.wav}, 출처는 그 폴더의 README.txt 와 CREDITS.md)을 읽는다. 합성으로는
 * 잘 안 되는 소리에만 쓴다: 부딪히는 소리(닿음, 고정, 하드드롭), 모래가 사각거리는 소리, K.O. 의 부서지는 소리, 메뉴 소리.
 * <p>
 * 파일은 16 비트 모노 44.1kHz WAV 이고, 크기는 파일에 굽지 않았다: {@link SoundBank} 가 다른 소리와 같은 기준(F100)으로
 * 맞춘다. 읽지 못하면(파일이 없음, 형식이 다름, 소리 라이브러리 문제) {@code null} 을 돌려주고, 그 소리는 예전처럼 코드로
 * 합성한다. 게임이 쓰는 {@link #load} 는 그때 녹음 이름마다 한 번 표준 오류에 까닭을 남긴다(jar 에 녹음이 빠져도 알 수 있게).
 * 실행 옵션 {@code -Dtetris.sfx=synth} 를 주면 녹음을 하나도 쓰지 않는다(예전 소리와 견주어 들어 볼 때). 일부러 끈 것이라
 * 남기지 않는다.
 */
final class Samples {

	/** 함께 배포하는 녹음 13 개의 이름 (확장자 없이). */
	static final List<String> NAMES = List.of("ui_move", "ui_select", "ui_back", "ui_deny", "land", "lock_1", "lock_2",
			"hard_drop_1", "hard_drop_2", "ko_crunch", "sand_land_1", "sand_land_2", "sand_land_3");

	/** 클래스패스 안의 폴더. */
	static final String FOLDER = "/sfx/";

	/** 이보다 긴 파일은 잘못 넣은 것으로 보고 읽지 않는다 (효과음은 모두 1초 안쪽). */
	static final int MAX_SECONDS = 5;

	/** 읽어서 바꿀 형식: 엔진과 같은 44.1kHz, 16 비트, 모노, 부호 있음, 작은 끝. */
	private static final AudioFormat MONO = new AudioFormat(Synth.RATE, 16, 1, true, false);

	/** 읽지 못했다고 이미 알린 녹음 이름. 이름마다 한 번만 알린다. 여러 스레드가 함께 쓴다(소리는 믹서 스레드가 만든다). */
	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private Samples() {
	}

	/** 녹음을 쓰는가. {@code -Dtetris.sfx=synth} 면 false (모든 소리를 합성). */
	static boolean enabled() {
		return !"synth".equalsIgnoreCase(System.getProperty("tetris.sfx", "").trim());
	}

	/**
	 * 녹음 {@code name} 을 -1 ~ 1 의 모노 표본으로 읽는다. 녹음을 쓰지 않게 했거나({@link #enabled()}) 읽지 못하면 null.
	 * 읽지 못했으면 그 까닭을 표준 오류에 남긴다({@link #warnOnce}, 이름마다 한 번). 예외를 던지지 않는다.
	 */
	static float[] load(String name) {
		return load(name, System.err::println);
	}

	/** {@link #load} 와 같지만 알림을 {@code warn} 에 넘긴다 (시험이 알림을 받아 본다). */
	static float[] load(String name, Consumer<String> warn) {
		return enabled() ? read(name, why -> warnOnce(name, why, warn)) : null; // 일부러 끈 것은 알리지 않는다
	}

	/** {@link #load} 와 같지만 실행 옵션과 상관없이 읽고, 읽지 못해도 알리지 않는다 (시험, 들어 보기 도구). 읽지 못하면 null. */
	static float[] read(String name) {
		return read(name, why -> {
		});
	}

	/**
	 * 녹음 {@code name} 을 읽지 못해 합성음으로 대신한다고 {@code warn} 에 한 줄 알린다. 이미 알린 이름이면 아무것도 하지
	 * 않는다. {@code why} 는 까닭: 예외(클래스와 메시지)나 "리소스 없음" 같은 말.
	 */
	static void warnOnce(String name, String why, Consumer<String> warn) {
		if (WARNED.add(String.valueOf(name))) {
			warn.accept("효과음 녹음을 읽지 못해 합성음으로 대신함: " + name + " (" + why + ")");
		}
	}

	/** 녹음을 읽는다. 읽지 못하면 그 까닭을 {@code failed} 에 한 번 넘기고 null. 예외를 던지지 않는다. */
	private static float[] read(String name, Consumer<String> failed) {
		if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) {
			failed.accept("이름이 잘못됨");
			return null;
		}
		InputStream raw = Samples.class.getResourceAsStream(FOLDER + name + ".wav");
		if (raw == null) {
			failed.accept("리소스 없음");
			return null;
		}
		return decode(raw, failed);
	}

	/**
	 * WAV 스트림 {@code raw} 를 -1 ~ 1 의 모노 표본으로 읽고 닫는다. 읽지 못하면 그 까닭(예외 클래스와 메시지, "빈 파일",
	 * "5초보다 김")을 {@code failed} 에 한 번 넘기고 null. 예외를 던지지 않는다.
	 */
	static float[] decode(InputStream raw, Consumer<String> failed) {
		String why;
		// jar 안의 스트림은 mark/reset 을 못 해서 AudioSystem 이 형식을 알아보지 못한다: 버퍼를 끼운다
		try (InputStream in = new BufferedInputStream(raw);
				AudioInputStream src = AudioSystem.getAudioInputStream(in)) {
			AudioInputStream pcm = matches(src.getFormat()) ? src : AudioSystem.getAudioInputStream(MONO, src);
			try {
				if (pcm.getFrameLength() > (long) MAX_SECONDS * Synth.RATE) {
					why = MAX_SECONDS + "초보다 김";
				} else {
					byte[] bytes = pcm.readAllBytes();
					float[] x = toFloat(bytes);
					if (x != null) {
						return x;
					}
					why = bytes.length < 2 ? "빈 파일" : MAX_SECONDS + "초보다 김";
				}
			} finally {
				if (pcm != src) {
					pcm.close();
				}
			}
		} catch (Exception | LinkageError e) {
			why = String.valueOf(e); // 예외 클래스: 메시지
		}
		failed.accept(why); // 합성한 소리로 대신한다
		return null;
	}

	/** 이미 엔진의 형식인가 (바꿀 필요가 없다). */
	private static boolean matches(AudioFormat f) {
		return AudioFormat.Encoding.PCM_SIGNED.equals(f.getEncoding()) && f.getSampleRate() == Synth.RATE
				&& f.getSampleSizeInBits() == 16 && f.getChannels() == 1 && !f.isBigEndian();
	}

	/** 16 비트 작은 끝 PCM → -1 ~ 1. 빈 파일이면 null. */
	private static float[] toFloat(byte[] b) {
		int n = b.length / 2;
		if (n == 0 || n > MAX_SECONDS * Synth.RATE) {
			return null;
		}
		float[] out = new float[n];
		for (int i = 0; i < n; i++) {
			out[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8)) / 32768f;
		}
		return out;
	}
}
