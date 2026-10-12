package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import org.junit.jupiter.api.Test;

/**
 * 함께 배포하는 녹음 (Samples): 13 개 모두 읽히고, 1초 안쪽이며, 소리가 2ms 안에 시작하고, 엔진의 형식(44.1kHz 모노)으로
 * 바뀐다. 없는 이름이나 이상한 이름은 null 이고(엔진은 합성음으로 대신한다), {@code -Dtetris.sfx=synth} 면 읽지 않는다.
 * 게임이 읽다 실패하면 녹음 이름마다 한 번 까닭을 알리고, 일부러 끈 것은 알리지 않는다.
 */
class SamplesTest {

	/** 읽지 못했다는 알림의 앞부분. */
	private static final String HEAD = "효과음 녹음을 읽지 못해 합성음으로 대신함: ";

	/** {@code tetris.sfx} 를 {@code value}(null 이면 지움)로 두고 {@code body} 를 돌린 뒤 되돌린다. */
	private static void withSfxOption(String value, Runnable body) {
		String old = System.getProperty("tetris.sfx");
		try {
			setSfxOption(value);
			body.run();
		} finally {
			setSfxOption(old);
		}
	}

	private static void setSfxOption(String value) {
		if (value == null) {
			System.clearProperty("tetris.sfx");
		} else {
			System.setProperty("tetris.sfx", value);
		}
	}

	/** 엔진 형식(44.1kHz, 16 비트, 모노)의 조용한 WAV, {@code frames} 표본. */
	private static InputStream wav(int frames) throws IOException {
		AudioFormat f = new AudioFormat(Synth.RATE, 16, 1, true, false);
		AudioInputStream pcm = new AudioInputStream(new ByteArrayInputStream(new byte[frames * 2]), f, frames);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AudioSystem.write(pcm, AudioFileFormat.Type.WAVE, out);
		return new ByteArrayInputStream(out.toByteArray());
	}

	@Test
	void allThirteenRecordingsLoadShortWithAnEarlyOnset() {
		assertEquals(13, Samples.NAMES.size());
		for (String name : Samples.NAMES) {
			float[] x = Samples.read(name);
			assertNotNull(x, name + ": 읽힌다");
			double ms = x.length * 1000.0 / Synth.RATE;
			assertTrue(ms > 30 && ms <= 1000, name + ": 1초 안쪽 (" + ms + "ms)");
			float peak = Synth.peak(x);
			assertEquals(Math.pow(10, -1 / 20.0), peak, 0.01, name + ": 파일은 가장 큰 표본 -1dBFS 로 준비했다");
			int onset = 0;
			while (Math.abs(x[onset]) <= peak * 0.01) { // -40dB
				onset++;
			}
			assertTrue(onset * 1000.0 / Synth.RATE <= 2, name + ": 소리가 2ms 안에 시작한다 (" + onset + " 표본)");
			for (float v : x) {
				assertTrue(v >= -1 && v <= 1, name + ": -1 ~ 1");
			}
		}
	}

	@Test
	void theRecordingsDifferFromEachOther() {
		float[] a = Samples.read("lock_1");
		float[] b = Samples.read("lock_2");
		assertNotNull(a);
		assertNotNull(b);
		assertFalse(a.length == b.length && Arrays.equals(a, b), "고정 소리 두 녹음은 서로 다르다");
	}

	@Test
	void missingOrOddNamesReturnNull() {
		assertNull(Samples.read("no_such_sound"), "없는 파일");
		assertNull(Samples.read(""), "빈 이름");
		assertNull(Samples.read(null));
		assertNull(Samples.read("../sfx/land"), "폴더 밖을 가리키는 이름");
		assertNull(Samples.read("README"), "WAV 가 아닌 파일(README.txt)은 찾지 않는다");
	}

	@Test
	void theSynthOptionTurnsRecordingsOff() {
		String old = System.getProperty("tetris.sfx");
		try {
			System.clearProperty("tetris.sfx");
			assertTrue(Samples.enabled());
			assertNotNull(Samples.load("land"));
			System.setProperty("tetris.sfx", " Synth ");
			assertFalse(Samples.enabled(), "-Dtetris.sfx=synth 면 녹음을 쓰지 않는다 (대소문자·빈칸 무시)");
			assertNull(Samples.load("land"), "그때는 load 가 null: 엔진은 합성음을 쓴다");
			assertNotNull(Samples.read("land"), "read 는 옵션과 상관없이 읽는다 (시험, 들어 보기 도구)");
		} finally {
			if (old == null) {
				System.clearProperty("tetris.sfx");
			} else {
				System.setProperty("tetris.sfx", old);
			}
		}
	}

	@Test
	void anUnreadableRecordingIsReportedOncePerName() {
		// 이름은 이 시험에만 쓴다: 알린 이름은 JVM 전체에서 한 번이라 다른 시험과 겹치면 안 된다
		List<String> said = new ArrayList<>();
		withSfxOption(null, () -> {
			assertNull(Samples.load("missing_report_test", said::add));
			assertNull(Samples.load("missing_report_test", said::add), "같은 이름은 다시 알리지 않는다");
			assertNull(Samples.load("../odd_report_test", said::add));
			assertNull(Samples.load(null, said::add), "이름이 없어도 예외 없이 알린다");
		});
		assertEquals(List.of(HEAD + "missing_report_test (리소스 없음)", HEAD + "../odd_report_test (이름이 잘못됨)",
				HEAD + "null (이름이 잘못됨)"), said);
	}

	@Test
	void readableRecordingsAndTheSynthOptionAreNotReported() {
		List<String> said = new ArrayList<>();
		withSfxOption(null, () -> {
			for (String name : Samples.NAMES) {
				assertNotNull(Samples.load(name, said::add), name);
			}
		});
		assertEquals(List.of(), said, "읽은 녹음은 알리지 않는다");
		withSfxOption("synth", () -> assertNull(Samples.load("off_report_test", said::add)));
		assertEquals(List.of(), said, "-Dtetris.sfx=synth 로 일부러 끈 것은 알리지 않는다");
		withSfxOption(null, () -> assertNull(Samples.load("off_report_test", said::add)));
		assertEquals(List.of(HEAD + "off_report_test (리소스 없음)"), said, "끈 동안은 알린 것으로 치지 않는다");
		assertNull(Samples.read("off_report_test"));
		assertNull(Samples.read("quiet_report_test"));
		assertEquals(1, said.size(), "read 는 알리지 않는다 (시험, 들어 보기 도구)");
	}

	@Test
	void decodingFailuresNameTheirReason() throws IOException {
		List<String> why = new ArrayList<>();
		assertNull(Samples.decode(new ByteArrayInputStream("WAV 가 아님".getBytes(StandardCharsets.UTF_8)), why::add));
		assertNull(Samples.decode(wav(0), why::add));
		assertNull(Samples.decode(wav((Samples.MAX_SECONDS + 1) * Synth.RATE), why::add));
		assertEquals(3, why.size(), "실패마다 까닭 하나: " + why);
		assertTrue(why.get(0).startsWith(UnsupportedAudioFileException.class.getName()),
				"형식을 모르면 예외 클래스와 메시지: " + why.get(0));
		assertEquals("빈 파일", why.get(1));
		assertEquals(Samples.MAX_SECONDS + "초보다 김", why.get(2));
		float[] tenth = Samples.decode(wav(Synth.RATE / 10), why::add);
		assertNotNull(tenth, "엔진 형식의 0.1초 WAV 는 읽는다");
		assertEquals(Synth.RATE / 10, tenth.length);
		assertEquals(3, why.size(), "읽으면 까닭을 넘기지 않는다");
	}
}
