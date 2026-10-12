package kr.ac.jbnu.se.tetris.ui;

import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 대전 화면 효과의 비용을 잰다 (runs/quality/SPEC.md 1.4(f): 효과 전부 프레임당 2ms 이하, 1920×1080). 같은 시드·같은 손으로 두
 * 판을 나란히 진행하되 하나는 효과 "강하게"(m = 1.5, 흔들림 켬), 하나는 "끔"으로 둔다(효과는 판을 바꾸지 않으므로 두 판은 같다).
 * 판 시각 16ms 마다 두 판을 창 없이 그려 걸린 시간의 차이를 효과의 비용으로 본다. 잡음을 줄이려고 프레임마다 세 번 그려 가장 짧은
 * 값을 쓰고, 두 판 중 먼저 그리는 쪽을 프레임마다 바꾼다(JIT 가 다시 컴파일하는 순간이 한쪽에 몰리지 않게). 효과가 살아 있는
 * 프레임(지우기·고정·방해 줄 사건 뒤 0.8초 안)과 판이 끝난 뒤 3초(K.O., 결과 카드)도 따로 센다. 그리는 스레드의 CPU 와 프로세스
 * CPU 도 판마다 보고한다.
 * <p>
 * <b>그리는 곳.</b> 기본은 소프트웨어 그리기(BufferedImage, {@code java.awt.headless=true})다. 그런데 소프트웨어는 비용 순위를
 * 뒤집는다: 화면 전체 덮개(fillRect 하나)는 소프트웨어에서 가장 비싸고 D3D 에서는 거의 0 이며, 둥근 카드(Theme.card)는 반대다. 그래서
 * 넷째 인자 {@code d3d} 를 주면 Windows 의 실제 화면 파이프라인으로 잰다: 기본 화면 장치의 VolatileImage 에 그리고(창을 띄우지
 * 않고 화면을 찍지도 않는다) 그릴 때마다 {@link Toolkit#sync()} 로 그리기 큐를 비운다. 그때는 D3D 큐를 실행하는 AWT-Windows
 * 스레드의 CPU 도 따로 보고한다. 이 모드는 창 있는 실행이어야 하므로 {@code -Djava.awt.headless=true} 를 주지 않는다(주면
 * 소프트웨어로 잰다). 프레임당 MaskFill 수(D3D 에서 둥근 카드 하나가 32×32 타일마다 하나씩 낸다)는 JVM 옵션
 * {@code -Dsun.java2d.trace=count} 를 더하면 끝날 때 찍히는 표에서 본다.
 * <p>
 * 실행: {@code gradlew battleBench --args="120 1920 1080"} (판 초, 가로, 세로). D3D 로 재려면 {@code gradlew battleBench -Pd3d},
 * 인자를 바꿀 때는 {@code gradlew battleBench -Pd3d --args="60 1920 1080 d3d"} 처럼 넷째 인자 d3d 까지 준다({@code --args} 가
 * 기본 인자를 갈아 끼운다). {@code -Pd3d} 이면 Gradle 이 {@code java.awt.headless} 를 빼고 인자 기본값을 "120 1920 1080 d3d" 로
 * 둔다. 명령 줄에 준 {@code sun.java2d.*} 설정(예: {@code -Dsun.java2d.trace=count})은 Gradle 이 넘긴다. 또는
 * {@code java -Djava.awt.headless=true -Dtetris.config=off -Dtetris.sound=off -Dtetris.records=off -cp …
 * kr.ac.jbnu.se.tetris.ui.BattleFxBench [판 초=120] [가로=1920] [세로=1080] [sw|d3d]} (d3d 는 headless 를 빼고).
 * <p>
 * IDE 에서 바로 돌려도 소리 장치, 사용자 설정 폴더, 사람 판 기록을 건드리지 않게 시작할 때 {@code TestSandbox.apply()} 를
 * 부른다(준 값은 덮지 않는다). 창도 끄지만 d3d 이면 {@code java.awt.headless} 만 실행에 준 값으로 되돌린다.
 */
public final class BattleFxBench {

	private BattleFxBench() {
	}

	public static void main(String[] args) throws Exception {
		String headless = System.getProperty("java.awt.headless"); // 실행에 준 값 (d3d 는 이것을 따른다)
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창 없이, 소리 장치·사용자 설정 폴더·사람 판 기록 끔
		int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 120;
		int w = args.length > 1 ? Integer.parseInt(args[1]) : 1920;
		int h = args.length > 2 ? Integer.parseInt(args[2]) : 1080;
		boolean d3d = args.length > 3 && args[3].equalsIgnoreCase("d3d");
		if (d3d) {
			// D3D 는 창 있는 실행이어야 하므로 headless 만 실행에 준 값으로 되돌린다(창은 띄우지 않는다). AWT 는 이 값을
			// 처음 쓸 때 한 번 읽으므로 아직 아무것도 그리지 않은 지금 되돌리면 된다. 소리·설정 폴더·기록 값은 그대로 둔다.
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
		Target target = new Target(d3d, w, h);
		System.out.println("그리는 곳: " + target.describe());
		try (AiCatalog catalog = AiCatalog.load(Path.of("models"))) {
			run(catalog, seconds, target);
		}
		System.exit(0);
	}

	// ------------------------------------------------------------------ 그리는 곳과 CPU

	/** 그리는 곳: 소프트웨어(BufferedImage) 또는 D3D(화면 장치의 VolatileImage, 창 없음). */
	private static final class Target {
		final boolean d3d;
		final int w;
		final int h;
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

		/** 패널을 한 번 그린다 (D3D 면 그리기 큐를 비울 때까지). */
		void paint(GamePanel p) {
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

	private static final com.sun.management.ThreadMXBean MX = (com.sun.management.ThreadMXBean) ManagementFactory
			.getThreadMXBean();
	private static final com.sun.management.OperatingSystemMXBean OS = (com.sun.management.OperatingSystemMXBean) ManagementFactory
			.getOperatingSystemMXBean();

	/** 한 판을 그리는 데 든 CPU (그리는 스레드, AWT-Windows 스레드, 프로세스 전체) 와 그린 횟수. */
	private static final class Cpu {
		long drawNs;
		long awtNs;
		long procNs;
		long paints;

		String report() {
			double n = Math.max(1, paints);
			return String.format(Locale.ROOT, "그리는 스레드 %.2f, AWT-Windows %s, 프로세스 %.2f", drawNs / 1e6 / n,
					awtId < 0 ? "-" : String.format(Locale.ROOT, "%.2f", awtNs / 1e6 / n), procNs / 1e6 / n);
		}
	}

	/** D3D 그리기 큐를 실행하는 스레드 (창 없는 실행에는 없다: -1). */
	private static long awtId = -1;

	private static long awtThread() {
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if ("AWT-Windows".equals(t.getName())) {
				return t.threadId();
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------ 재기

	private static void run(AiCatalog catalog, int seconds, Target target) throws Exception {
		int w = target.w;
		int h = target.h;
		GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(Math.min(3,
				catalog.difficulties().size() - 1)), catalog.personas().get(0));
		long seed = 20261007L;
		BattleScreens.Rig fx = new BattleScreens.Rig(st, catalog, seed,
				save(UserSettings.EFFECTS_HIGH), null);
		BattleScreens.Rig off = new BattleScreens.Rig(st, catalog, seed, save(UserSettings.EFFECTS_OFF), null);
		fx.panel.setSize(w, h);
		off.panel.setSize(w, h);
		long me = Thread.currentThread().threadId();
		Cpu ignore = new Cpu();
		// 데우기: 다른 시드의 판 30초를 같은 방법으로 그려 JIT 를 데운다 (재지 않는다)
		BattleScreens.Rig warmFx = new BattleScreens.Rig(st, catalog, seed + 1, save(UserSettings.EFFECTS_HIGH), null);
		BattleScreens.Rig warmOff = new BattleScreens.Rig(st, catalog, seed + 1, save(UserSettings.EFFECTS_OFF), null);
		warmFx.panel.setSize(w, h);
		warmOff.panel.setSize(w, h);
		for (int f = 0; f < 30_000 / 16 && !warmFx.s.over(); f++) {
			for (int t = 0; t < 16 && !warmFx.s.over(); t++) {
				warmFx.step();
				warmOff.step();
			}
			best(warmFx.panel, target, ignore);
			best(warmOff.panel, target, ignore);
		}
		awtId = awtThread(); // 첫 그리기가 툴킷을 띄운 뒤에 찾는다
		int frames = seconds * 1000 / 16;
		double[] all = new double[frames];
		double[] active = new double[frames];
		double[] withFx = new double[frames];
		double[] without = new double[frames];
		long[] bytesFx = new long[frames];
		long[] bytesOff = new long[frames];
		long[] when = new long[frames];
		Cpu cpuFx = new Cpu();
		Cpu cpuOff = new Cpu();
		int n = 0;
		int na = 0;
		for (int f = 0; f < frames && !fx.s.over() && !off.s.over(); f++) {
			for (int t = 0; t < 16 && !fx.s.over(); t++) {
				fx.step();
				off.step();
			}
			// 두 판을 번갈아 먼저 그린다: JIT 가 다시 컴파일하는 순간이 한쪽에만 몰리지 않게
			double tf;
			double to;
			long a0 = MX.getThreadAllocatedBytes(me);
			long a1;
			long a2;
			if ((f & 1) == 0) {
				tf = best(fx.panel, target, cpuFx);
				a1 = MX.getThreadAllocatedBytes(me);
				to = best(off.panel, target, cpuOff);
				a2 = MX.getThreadAllocatedBytes(me);
			} else {
				to = best(off.panel, target, cpuOff);
				long m1 = MX.getThreadAllocatedBytes(me);
				tf = best(fx.panel, target, cpuFx);
				a2 = MX.getThreadAllocatedBytes(me);
				a1 = a0 + (a2 - m1); // 효과 판의 할당
				a2 = a1 + (m1 - a0); // 효과 끈 판의 할당
			}
			withFx[n] = tf;
			without[n] = to;
			all[n] = tf - to;
			bytesFx[n] = (a1 - a0) / 3;
			bytesOff[n] = (a2 - a1) / 3;
			if (active(fx.s)) {
				active[na++] = tf - to;
			}
			when[n] = fx.s.match.time();
			n++;
		}
		// 판이 끝난 뒤: K.O. 와 결과 카드 (세션 시계로 3초, 16ms 마다)
		int ko = 0;
		double[] after = new double[200];
		if (fx.s.over()) {
			for (long ms = 0; ms <= 3000 && ko < after.length; ms += 16) {
				fx.afterOver(ms);
				off.afterOver(ms);
				double bf = Double.MAX_VALUE;
				double bo = Double.MAX_VALUE;
				for (int rep = 0; rep < 4; rep++) { // 번갈아 그린다
					if ((rep & 1) == 0) {
						bf = Math.min(bf, best(fx.panel, target, ignore));
						bo = Math.min(bo, best(off.panel, target, ignore));
					} else {
						bo = Math.min(bo, best(off.panel, target, ignore));
						bf = Math.min(bf, best(fx.panel, target, ignore));
					}
				}
				after[ko++] = bf - bo;
			}
		}
		System.out.printf(Locale.ROOT, "%d×%d, %d 프레임 (판 %.1f초, 결과 %s), 효과가 살아 있는 프레임 %d%n", w, h, n,
				fx.s.match.time() / 1000.0, fx.s.result(), na);
		System.out.printf(Locale.ROOT, "프레임 시간 (ms) 효과 강하게: 평균 %.2f, p95 %.2f, 최대 %.2f%n", mean(withFx, n),
				pct(withFx, n, 0.95), pct(withFx, n, 1));
		System.out.printf(Locale.ROOT, "프레임 시간 (ms) 효과 끔:     평균 %.2f, p95 %.2f, 최대 %.2f%n", mean(without, n),
				pct(without, n, 0.95), pct(without, n, 1));
		System.out.println("프레임당 CPU (ms) 효과 강하게: " + cpuFx.report());
		System.out.println("프레임당 CPU (ms) 효과 끔:     " + cpuOff.report());
		System.out.printf(Locale.ROOT, "효과 비용 (강하게 − 끔, ms) 모든 프레임: 평균 %.3f, p50 %.3f, p95 %.3f, 최대 %.3f%n",
				mean(all, n), pct(all, n, 0.5), pct(all, n, 0.95), pct(all, n, 1));
		System.out.printf(Locale.ROOT, "효과 비용 (ms) 효과가 살아 있는 프레임: 평균 %.3f, p50 %.3f, p95 %.3f, 최대 %.3f%n",
				mean(active, na), pct(active, na, 0.5), pct(active, na, 0.95), pct(active, na, 1));
		System.out.printf(Locale.ROOT, "프레임당 할당 (바이트) 효과 강하게: 평균 %.0f, 효과 끔: 평균 %.0f%n", meanL(bytesFx, n),
				meanL(bytesOff, n));
		if (ko > 0) {
			System.out.printf(Locale.ROOT, "효과 비용 (ms) 판이 끝난 뒤 3초 (K.O., 결과 카드): 평균 %.3f, p95 %.3f, 최대 %.3f%n",
					mean(after, ko), pct(after, ko, 0.95), pct(after, ko, 1));
		}
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> Double.compare(all[b], all[a]));
		StringBuilder top = new StringBuilder("가장 비싼 프레임 (판 시각 ms: 비용 ms):");
		long[] again = new long[Math.min(5, n)];
		for (int k = 0; k < again.length; k++) {
			top.append(String.format(Locale.ROOT, " %d: %.2f", when[order[k]], all[order[k]]));
			again[k] = when[order[k]];
		}
		System.out.println(top);
		System.out.printf(Locale.ROOT, "효과 비용 (ms) 모든 프레임 p99: %.3f%n", pct(all, n, 0.99));
		// 가장 비싼 프레임을 새 판으로 다시 만들어 번갈아 20번씩 잰다: 한 번만 비쌌으면 다른 일(다른 프로그램, GC, JIT)의 잡음이다
		Arrays.sort(again);
		BattleScreens.Rig fx2 = new BattleScreens.Rig(st, catalog, seed, save(UserSettings.EFFECTS_HIGH), null);
		BattleScreens.Rig off2 = new BattleScreens.Rig(st, catalog, seed, save(UserSettings.EFFECTS_OFF), null);
		fx2.panel.setSize(w, h);
		off2.panel.setSize(w, h);
		StringBuilder re = new StringBuilder("다시 잰 비용 (판 시각 ms: 비용 ms):");
		for (long t : again) {
			fx2.runTo(t);
			off2.runTo(t);
			double bf = Double.MAX_VALUE;
			double bo = Double.MAX_VALUE;
			for (int rep = 0; rep < 20; rep++) {
				if ((rep & 1) == 0) {
					bf = Math.min(bf, best(fx2.panel, target, ignore));
					bo = Math.min(bo, best(off2.panel, target, ignore));
				} else {
					bo = Math.min(bo, best(off2.panel, target, ignore));
					bf = Math.min(bf, best(fx2.panel, target, ignore));
				}
			}
			re.append(String.format(Locale.ROOT, " %d: %.2f", t, bf - bo));
		}
		System.out.println(re);
	}

	private static SaveData save(int effects) {
		SaveData s = SaveData.inMemory();
		s.updateSettings(u -> u.withEffects(effects).withShake(true));
		return s;
	}

	/** 효과가 살아 있는가: 어느 보드든 마지막 지우기·고정·방해 줄 사건이 0.8초 안. */
	private static boolean active(GameSession s) {
		long now = s.match.time();
		for (int i = 0; i < s.match.size(); i++) {
			List<GameSession.ClearFx> c = s.clearFx(i);
			List<GameSession.LockFx> l = s.lockFx(i);
			if (!c.isEmpty() && now - c.get(c.size() - 1).at() < 800 || !l.isEmpty() && now - l.get(l.size() - 1).at() < 250
					|| now - s.tallies[i].risenAt < 400) {
				return true;
			}
		}
		return false;
	}

	/** 세 번 그려 가장 짧은 시간 (ms). 세 번의 CPU 는 {@code cpu} 에 더한다. */
	private static double best(GamePanel p, Target target, Cpu cpu) {
		long me = Thread.currentThread().threadId();
		long c0 = MX.getThreadCpuTime(me);
		long w0 = awtId < 0 ? 0 : MX.getThreadCpuTime(awtId);
		long p0 = OS.getProcessCpuTime();
		double b = Double.MAX_VALUE;
		for (int k = 0; k < 3; k++) {
			long t0 = System.nanoTime();
			target.paint(p);
			b = Math.min(b, (System.nanoTime() - t0) / 1e6);
		}
		cpu.drawNs += MX.getThreadCpuTime(me) - c0;
		if (awtId >= 0) {
			cpu.awtNs += Math.max(0, MX.getThreadCpuTime(awtId) - w0);
		}
		cpu.procNs += OS.getProcessCpuTime() - p0;
		cpu.paints += 3;
		return b;
	}

	private static double mean(double[] v, int n) {
		double s = 0;
		for (int i = 0; i < n; i++) {
			s += v[i];
		}
		return n == 0 ? 0 : s / n;
	}

	private static double meanL(long[] v, int n) {
		double s = 0;
		for (int i = 0; i < n; i++) {
			s += v[i];
		}
		return n == 0 ? 0 : s / n;
	}

	private static double pct(double[] v, int n, double q) {
		if (n == 0) {
			return 0;
		}
		double[] c = Arrays.copyOf(v, n);
		Arrays.sort(c);
		return c[Math.min(n - 1, (int) Math.floor(q * (n - 1)))];
	}
}
