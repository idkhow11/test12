package kr.ac.jbnu.se.tetris.ui;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.ai.SandAiController;
import kr.ac.jbnu.se.tetris.sand.ai.SandAiLevel;
import kr.ac.jbnu.se.tetris.settings.SaveData;

/**
 * 모래 판을 실제 진행 스레드와 실제 시간 시계로 몇 분 동안 둔다(창 없이). 손은 모래 AI 다. 화면을 그리는 스레드가 초당 약
 * 60번 그리고(판과 같은 잠금을 쓴다), 판이 끝나면 결과 카드를 1.5초 그린 뒤 새 판을 시작한다(게임의 "다시 하기" 와 같다).
 * 판의 시계가 실제 시간에 뒤처진 정도(멈춤), 진행 오류, AI 의 고르기 오류, 그리기 예외, 그리기 시간과 CPU, 소리 요청 수를 센다.
 * 기록은 임시 폴더의 저장 파일에만 남기고, 판마다 한 번씩 더해졌는지 본 뒤 그 폴더를 지운다(사용자 설정 폴더와 runs/human 은
 * 건드리지 않는다). 소리는 내지 않고 요청만 센다. IDE 에서 바로 돌려도 그렇게 되도록 시작할 때 {@code TestSandbox.apply()} 를
 * 부른다.
 * <p>
 * <b>그리는 곳.</b> 기본은 소프트웨어 그리기(1280 × 720 BufferedImage, {@code java.awt.headless=true})다. 그런데 소프트웨어는
 * 비용 순위를 뒤집는다(화면 전체 덮개는 소프트웨어에서 비싸고 D3D 에서는 거의 0 이며 둥근 카드는 반대다, {@code BattleFxBench}
 * 설명). 그래서 셋째 인자 {@code d3d} 를 주면 Windows 의 실제 화면 파이프라인으로 잰다: 기본 화면 장치의 VolatileImage 에
 * 그리고(창을 띄우지 않고 화면을 찍지도 않는다) 그릴 때마다 {@link Toolkit#sync()} 로 그리기 큐를 비운다. 어느 쪽이든 그리는
 * 스레드와 AWT-Windows 스레드(D3D 큐를 실행한다, 소프트웨어 그리기에서는 거의 0 이다)의 프레임당 CPU 와 프로세스 CPU(판 진행·AI
 * 포함)를 보고한다. d3d 는 창 있는 실행이어야 하므로 {@code java.awt.headless} 만 실행에 준 값으로 되돌린다(소리·설정 폴더·사람 판
 * 기록은 끈 채로 둔다). 실행에 {@code -Djava.awt.headless=true} 를 주었으면 알림을 찍고 소프트웨어로 잰다: Gradle 작업은 창 없는
 * 실행이라 그대로 d3d 를 주면 그렇게 된다.
 * <p>
 * 실행: {@code gradlew sandSoak --args="180 3"}, 또는 시험 클래스까지 빌드한 뒤 시험 실행 경로(Gradle 의 test runtimeClasspath:
 * 빌드한 클래스·자원과 의존 jar, Windows 는 경로 구분이 {@code ;})로 돌린다. 시작할 때 부르는 {@code TestSandbox} 가 JUnit 의
 * {@code LauncherSessionListener} 를 구현하므로 경로에 JUnit jar 가 없으면 {@code NoClassDefFoundError} 로 멈춘다(빌드한 클래스
 * 폴더만으로는 안 된다):
 *
 * <pre>
 * java -Djava.awt.headless=true -Dtetris.sound=off -Dtetris.config=off -Dtetris.records=off -Dstdout.encoding=UTF-8 -cp &lt;시험 실행 경로&gt; kr.ac.jbnu.se.tetris.ui.SandSoak [초 180] [AI 단계 3] [sw|d3d]
 * </pre>
 *
 * D3D 로 재려면 이 줄에서 {@code -Djava.awt.headless=true} 를 빼고 셋째 인자로 {@code d3d} 를 준다. AI 단계 0 은 AI 대신 블록이
 * 나오고 150ms 뒤 그 자리에 하드드롭만 하는 손이다(1분 남짓에 진다): 게임 끝, 결과 카드, 새 판을 여러 번 지나게 할 때 쓴다.
 */
public final class SandSoak {

	/** 그리기 시간과 CPU 에서 빼는 처음 장 수 (글꼴을 읽고 조각 그림을 만든다). */
	private static final int WARM_FRAMES = 30;
	/** 그리는 크기 (게임 창의 처음 크기). */
	private static final int PAINT_W = 1280;
	private static final int PAINT_H = 720;

	private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();
	private static final com.sun.management.OperatingSystemMXBean OS = (com.sun.management.OperatingSystemMXBean) ManagementFactory
			.getOperatingSystemMXBean();

	private SandSoak() {
	}

	public static void main(String[] args) throws Exception {
		String headless = System.getProperty("java.awt.headless"); // 실행에 준 값 (d3d 는 이것을 따른다)
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창 없이, 소리·사용자 설정 폴더·사람 판 기록 끔
		int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 180;
		int level = args.length > 1 ? Integer.parseInt(args[1]) : 3;
		boolean d3d = args.length > 2 && args[2].equalsIgnoreCase("d3d");
		if (d3d) {
			// D3D 는 창 있는 실행이어야 하므로 headless 만 실행에 준 값으로 되돌린다(창은 띄우지 않는다, BattleFxBench 와 같다).
			// AWT 는 이 값을 처음 쓸 때 한 번 읽으므로 아직 아무것도 그리지 않은 지금 되돌리면 된다.
			if (headless == null) {
				System.clearProperty("java.awt.headless");
			} else {
				System.setProperty("java.awt.headless", headless);
			}
			if (GraphicsEnvironment.isHeadless()) {
				System.out.println("d3d 모드는 창 있는 실행이어야 한다(java.awt.headless=true 를 빼고 실행). 소프트웨어로 잰다.");
				d3d = false;
			}
		}
		int code = 1;
		Path dir = Files.createTempDirectory("sand-soak");
		try {
			code = run(dir, seconds, level, new Target(d3d, PAINT_W, PAINT_H)) ? 0 : 1;
		} catch (Throwable t) {
			t.printStackTrace(); // 아래 System.exit 가 예외를 삼키지 않게 먼저 찍는다
		} finally {
			try (Stream<Path> files = Files.walk(dir)) {
				files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
			}
			System.exit(code);
		}
	}

	/** 들은 소리를 종류마다 센다. */
	private static final class Tally implements SandSession.Speaker {
		final Map<Sfx, AtomicInteger> counts = new EnumMap<>(Sfx.class);

		Tally() {
			for (Sfx s : Sfx.values()) {
				counts.put(s, new AtomicInteger());
			}
		}

		@Override
		public void play(Cue cue) {
			counts.get(cue.sfx()).incrementAndGet();
		}

		@Override
		public void stopAll() {
		}
	}

	// ------------------------------------------------------------------ 그리는 곳과 CPU (BattleFxBench 와 같다)

	/** 그리는 곳: 소프트웨어(BufferedImage) 또는 D3D(화면 장치의 VolatileImage, 창 없음). */
	private static final class Target {
		final boolean d3d;
		private final int w;
		private final int h;
		private final BufferedImage img;
		private final GraphicsConfiguration gc;
		private VolatileImage vi;

		Target(boolean d3d, int w, int h) {
			this.d3d = d3d;
			this.w = w;
			this.h = h;
			if (d3d) {
				gc = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration();
				vi = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
				img = null;
			} else {
				gc = null;
				img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			}
		}

		String describe() {
			return d3d ? String.format(Locale.ROOT, "VolatileImage %d×%d (%s, 가속 %s)", w, h, gc.getClass().getSimpleName(),
					vi.getCapabilities().isAccelerated()) : String.format(Locale.ROOT, "BufferedImage %d×%d (소프트웨어)", w, h);
		}

		/** 패널을 한 번 그린다 (D3D 면 그리기 큐를 비울 때까지). 그리는 스레드만 부른다. */
		void paint(Component p) {
			Graphics2D g;
			if (d3d) {
				if (vi.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {
					vi = gc.createCompatibleVolatileImage(w, h, Transparency.OPAQUE);
				}
				g = vi.createGraphics();
			} else {
				g = img.createGraphics();
			}
			p.printAll(g);
			g.dispose();
			if (d3d) {
				Toolkit.getDefaultToolkit().sync();
			}
		}
	}

	/**
	 * 데우기가 끝난 때(그림 {@link #WARM_FRAMES} 장째)의 시각과 CPU 시간 (나노초). 그때부터 끝까지를 잰다.
	 *
	 * @param awtId AWT-Windows 스레드, 없으면 -1
	 */
	private record CpuMark(long nanos, long painterCpu, long awtId, long awtCpu, long processCpu) {
	}

	/** D3D 그리기 큐를 실행하는 스레드 (AWT-Windows), 없으면 -1. 첫 그리기가 툴킷을 띄운 뒤에 찾는다. */
	private static long awtThread() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if ("AWT-Windows".equals(t.getName())) {
				return t.threadId();
			}
		}
		return -1;
	}

	/**
	 * 데우기가 끝난 뒤부터 지금까지의 CPU: 그리는 스레드와 AWT-Windows 스레드는 프레임당 ms, 프로세스(판 진행·AI·그리기 모두)는
	 * 초당 ms. 그리는 스레드가 살아 있을 때 부른다(끝난 스레드는 CPU 시간을 알 수 없어 "-").
	 */
	private static String cpuReport(CpuMark m, long painterId, int frames) {
		long draw = MX.getThreadCpuTime(painterId);
		long awt = m.awtId() < 0 ? -1 : MX.getThreadCpuTime(m.awtId());
		double sec = Math.max(1e-3, (System.nanoTime() - m.nanos()) / 1e9);
		double proc = (OS.getProcessCpuTime() - m.processCpu()) / 1e6 / sec;
		return String.format(Locale.ROOT,
				"CPU: 그리는 스레드 프레임당 %s ms, AWT-Windows 프레임당 %s ms, 프로세스 초당 %.0f ms (한 코어의 %.0f%%, 판 진행·AI 포함)",
				draw < 0 || m.painterCpu() < 0 ? "-" : String.format(Locale.ROOT, "%.2f", (draw - m.painterCpu()) / 1e6 / frames),
				awt < 0 || m.awtCpu() < 0 ? "-" : String.format(Locale.ROOT, "%.2f", (awt - m.awtCpu()) / 1e6 / frames), proc,
				proc / 10);
	}

	// ------------------------------------------------------------------ 두기

	private static boolean run(Path dir, int seconds, int level, Target target) throws Exception {
		System.out.println("그리는 곳: " + target.describe());
		SaveData save = SaveData.open(dir);
		SandPanel panel = new SandPanel(new GamePanel.Actions() {
			@Override
			public void restart(boolean sameSeed) {
			}

			@Override
			public void menu() {
			}
		}, save);
		AtomicReference<SandSession> shown = new AtomicReference<>();
		AtomicReference<Throwable> paintError = new AtomicReference<>();
		AtomicReference<CpuMark> mark = new AtomicReference<>();
		AtomicInteger frames = new AtomicInteger();
		AtomicLong paintNanos = new AtomicLong();
		AtomicLong worstPaint = new AtomicLong();
		Thread painter = new Thread(() -> {
			panel.setSize(PAINT_W, PAINT_H);
			long me = Thread.currentThread().threadId();
			SandSession current = null;
			while (!Thread.currentThread().isInterrupted()) {
				try {
					SandSession s = shown.get();
					if (s != current) {
						current = s;
						panel.setSession(s);
					}
					long t0 = System.nanoTime();
					target.paint(panel);
					long took = System.nanoTime() - t0;
					int f = frames.incrementAndGet();
					if (f > WARM_FRAMES) { // 처음 몇 장은 글꼴·조각 그림을 만드느라 느리다
						paintNanos.addAndGet(took);
						worstPaint.accumulateAndGet(took, Math::max);
					} else if (f == WARM_FRAMES) { // 여기부터 CPU 를 잰다 (툴킷이 떴으므로 AWT-Windows 스레드도 찾는다)
						long awt = awtThread();
						mark.set(new CpuMark(System.nanoTime(), MX.getThreadCpuTime(me), awt,
								awt < 0 ? 0 : MX.getThreadCpuTime(awt), OS.getProcessCpuTime()));
					}
					Thread.sleep(16);
				} catch (InterruptedException e) {
					return;
				} catch (Throwable e) {
					paintError.compareAndSet(null, e);
					return;
				}
			}
		}, "sand-soak-painter");
		painter.setDaemon(true);
		painter.start();

		Tally tally = new Tally();
		long end = System.nanoTime() + seconds * 1_000_000_000L;
		int games = 0;
		int played = 0; // 카운트다운이 끝난 판 (기록에 더해지는 판)
		long gameMs = 0;
		long pieces = 0;
		long worstLag = 0;
		long lagSum = 0;
		long lagSamples = 0;
		int failures = 0;
		int aiProblems = 0;
		int lost = 0;
		long seed = 990_101L;
		while (System.nanoTime() < end) {
			SandSession s = new SandSession(new SandGame(seed + games, 1, save.records().sand().bestScore()), save, x -> {
			}, System::nanoTime);
			s.speaker(tally);
			SandAiController ai = level > 0 ? new SandAiController(s.game(), SandAiLevel.of(level), seed + games, 1)
					: null;
			SandGame game = s.game();
			s.drive(new SandSession.Hand() {
				private int seen = -1;
				private long spawnedAt;

				@Override
				public Input next(long tick) {
					if (ai != null) {
						return ai.poll();
					}
					if (game.piece() == null) { // 단계 0: 블록이 나오고 150ms 뒤 그 자리에 하드드롭만 한다 (곧 진다)
						return null;
					}
					if (game.spawns() != seen) {
						seen = game.spawns();
						spawnedAt = tick;
					}
					return tick - spawnedAt >= 150 ? Input.HD : null;
				}

				@Override
				public boolean softDrop() {
					return false;
				}
			});
			shown.set(s);
			long start = System.nanoTime();
			s.start();
			while (!s.over() && System.nanoTime() < end) {
				Thread.sleep(20);
				long expected = (System.nanoTime() - start) / 1_000_000 - SandSession.COUNTDOWN_MS;
				if (expected > 200 && !s.over()) {
					long lag = Math.max(0, expected - s.time());
					worstLag = Math.max(worstLag, lag);
					lagSum += lag;
					lagSamples++;
				}
			}
			if (s.over()) {
				lost++;
				Thread.sleep(1_500); // 결과 카드가 들어오는 것까지 그린다
			}
			s.quit();
			games++;
			played += s.game().time() > 0 ? 1 : 0;
			gameMs += s.game().time();
			pieces += s.game().pieces();
			failures += s.failure() != null ? 1 : 0;
			aiProblems += ai != null && ai.problem() != null ? 1 : 0;
			System.out.printf("판 %d: %.1f초, 점수 %,d, 블록 %d, 지움 %d, %s%n", games, s.game().time() / 1000.0,
					s.game().score(), s.game().pieces(), s.game().clears(), s.over() ? "짐" : "시간 끝");
		}
		int timed = Math.max(1, frames.get() - WARM_FRAMES);
		CpuMark m = mark.get();
		String cpu = m == null ? "CPU: 그림이 " + WARM_FRAMES + "장보다 적어 재지 못함" : cpuReport(m, painter.threadId(), timed);
		painter.interrupt();
		painter.join(5_000);
		int recorded = save.records().sand().games();
		boolean file = Files.exists(dir.resolve(SaveData.FILE_NAME));
		System.out.printf("모두 %d판 (진 판 %d), 판 시간 %.1f초, 블록 %d%n", games, lost, gameMs / 1000.0, pieces);
		System.out.printf("판의 시계가 실제 시간에 뒤처진 정도: 평균 %.1fms, 최대 %dms (%d번 잼)%n",
				lagSamples == 0 ? 0 : lagSum / (double) lagSamples, worstLag, lagSamples);
		System.out.printf("그리기: %d번, 평균 %.2fms, 최대 %.1fms (처음 %d장 빼고), 예외 %s%n", frames.get(),
				paintNanos.get() / 1e6 / timed, worstPaint.get() / 1e6, WARM_FRAMES,
				paintError.get() == null ? "없음" : paintError.get().toString());
		System.out.println(cpu);
		System.out.printf("진행 오류 %d, AI 고르기 오류 %d, 기록 %d판 (임시 폴더의 저장 파일 %s)%n", failures, aiProblems, recorded,
				file ? "있음" : "없음");
		StringBuilder sb = new StringBuilder("소리 요청:");
		tally.counts.forEach((k, v) -> {
			if (v.get() > 0) {
				sb.append(' ').append(k).append(' ').append(v.get());
			}
		});
		System.out.println(sb);
		if (paintError.get() != null) {
			paintError.get().printStackTrace();
		}
		return paintError.get() == null && failures == 0 && aiProblems == 0 && recorded == played && file
				&& worstLag < 250;
	}
}
