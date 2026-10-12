package kr.ac.jbnu.se.tetris.audio;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * 효과음을 들어 보는 도구 (시험이 아니다). 모든 소리를 WAV 파일(44.1kHz, 16 비트, 모노)로 쓰고, 소리마다 한 줄씩 길이, 가장
 * 큰 표본, 녹음을 썼는지("녹음": 녹음만, 또는 녹음에 합성음을 겹침), 들리는 크기(F100: 가장 시끄러운 0.1초,
 * {@link Loudness})를 테트리스 기준의 상대값으로 목표({@link SoundBank#rel})와 나란히, 그리고 가장 시끄러운 0.4초와 노트북
 * 스피커 흉내로 들리는 크기를 찍는다. 같은 표를 그 폴더의 {@code levels.tsv} 에도 쓴다. 소리를 고칠 때 이 숫자로 균형을
 * 본다. 실행({@code gradlew build} 뒤, 저장소 폴더에서):
 *
 * <pre>
 * java -Dstdout.encoding=UTF-8 -cp "build/classes/java/main;build/resources/main;build/classes/java/test" kr.ac.jbnu.se.tetris.audio.SoundPreview [폴더] [synth] [play]
 * </pre>
 *
 * macOS·Linux 에서는 {@code ;} 대신 {@code :}. 기본 폴더는 {@code build/sfx-preview}. {@code synth} 를 주면 녹음 없이 모두
 * 합성한 소리를 만든다(예전 소리와 견줄 때). {@code play} 를 주면 기본 소리 장치로 하나씩 차례로 들려준다. 파일만 쓸 때는
 * 소리 장치를 쓰지 않는다.
 */
public final class SoundPreview {

	/** levels.tsv 의 한 줄. */
	private static final String ROW = "%s\t%s\t%.0f\t%.3f\t%.2f\t%.2f\t%+.2f\t%.2f\t%.1f\t%.1f";
	/** 화면에 찍는 한 줄 (ROW 의 칸을 그대로). */
	private static final String LINE = "  %-26s %-6s %5s ms  최대 %s  F100 %6s (목표 %6s, 차 %s, 상한으로 줄임 %s)"
			+ "  0.4초 %s (노트북 %s)%n";

	private SoundPreview() {
	}

	public static void main(String[] args) throws Exception {
		List<String> a = Arrays.asList(args);
		boolean play = a.contains("play");
		boolean synth = a.contains("synth");
		String folder = a.stream().filter(s -> !s.equals("play") && !s.equals("synth")).findFirst()
				.orElse("build/sfx-preview");
		File dir = new File(folder);
		dir.mkdirs();
		long t0 = System.nanoTime();
		SoundBank bank = synth ? SoundBank.generate(name -> null) : SoundBank.generate();
		System.out.printf(Locale.ROOT, "효과음 %d 종, 변형 %d 개, 모두 %.1f 초를 %.0fms 에 만듦%s → %s%n", Sfx.values().length,
				count(), bank.totalFrames() / (double) Synth.RATE, (System.nanoTime() - t0) / 1e6,
				synth ? " (녹음 없이 모두 합성)" : "", dir.getAbsolutePath());
		String head = "file\tsource\tms\tpeak\tF100_rel\ttarget_rel\tdiff_dB\tguard_dB\tM400\tlaptop_M400";
		System.out.println("  " + head.replace('\t', ' '));
		try (PrintWriter tsv = new PrintWriter(new File(dir, "levels.tsv"), StandardCharsets.UTF_8)) {
			tsv.println(head);
			double worst = 0;
			for (Sfx e : Sfx.values()) {
				for (int v = 0; v < bank.variants(e); v++) {
					float[] c = bank.clip(e, v);
					String name = String.format(Locale.ROOT, "%02d-%s-%d.wav", e.ordinal(),
							e.name().toLowerCase(Locale.ROOT), v);
					write(new File(dir, name), c);
					double rel = Loudness.f100(c) - SoundBank.REFERENCE_F100;
					double target = SoundBank.rel(e, v);
					worst = Math.max(worst, Math.abs(rel - target));
					String row = String.format(Locale.ROOT, ROW, name, bank.recorded(e, v) ? "녹음" : "합성",
							c.length * 1000.0 / Synth.RATE, Synth.peak(c), rel, target, rel - target,
							bank.guardLossDb(e, v), Loudness.max(c, 400), Loudness.max(Loudness.smallSpeaker(c), 400));
					tsv.println(row);
					System.out.printf(Locale.ROOT, LINE, (Object[]) row.split("\t"));
				}
			}
			System.out.printf(Locale.ROOT, "목표와의 가장 큰 차이: %.2f dB%n", worst);
		}
		if (play) {
			playAll();
		}
	}

	private static int count() {
		int n = 0;
		for (Sfx e : Sfx.values()) {
			n += e.variants;
		}
		return n;
	}

	/** 모든 소리를 기본 장치로 차례로 들려준다. 세기가 있는 소리는 가장 작은 것과 가장 큰 것을. */
	private static void playAll() throws InterruptedException {
		Sound s = Sound.start();
		long until = System.nanoTime() + 5_000_000_000L;
		while (s.state() == Sound.State.STARTING && System.nanoTime() < until) {
			Thread.sleep(10);
		}
		if (s.state() != Sound.State.READY) {
			System.out.println("소리 장치를 쓸 수 없음: " + s.reason());
			return;
		}
		for (Sfx e : Sfx.values()) {
			int[] intensities = e.tiers == 1 ? new int[] { e.base } : new int[] { e.base, e.base + e.tiers - 1 };
			for (int intensity : intensities) {
				System.out.println("  ▶ " + e + (e.tiers > 1 ? " " + intensity : ""));
				s.play(e, intensity);
				Thread.sleep(e.priority >= 3 ? 2300 : 900);
			}
		}
		Thread.sleep(500);
		s.close();
	}

	private static void write(File f, float[] c) throws IOException {
		byte[] b = new byte[c.length * 2];
		for (int i = 0; i < c.length; i++) {
			int s = Math.max(-32767, Math.min(32767, Math.round(c[i] * 32767)));
			b[2 * i] = (byte) s;
			b[2 * i + 1] = (byte) (s >> 8);
		}
		AudioFormat mono = new AudioFormat(Synth.RATE, 16, 1, true, false);
		try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(b), mono, c.length)) {
			AudioSystem.write(in, AudioFileFormat.Type.WAVE, f);
		}
	}
}
