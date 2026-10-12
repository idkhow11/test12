package kr.ac.jbnu.se.tetris.sand;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 모래 모드가 "질 수 있는 판" 인지 창 없이 가상 시간으로 잰다. 손은 가장 게으른 것: <b>블록이 나오고 150ms 뒤 그 자리에서
 * 하드드롭</b>만 한다(회전·이동·홀드·소프트드롭 없음). 판마다 시간 상한까지(또는 질 때까지) 두고 버틴 시간, 점수, 지우기,
 * 정착한 모래의 가장 높은 높이, 다음 블록이 스폰 자리의 모래를 기다린 시간의 비율을 잰다.
 * <p>
 * 10-07 측정(시드 8개, 상한 600초): 규칙 sand-0.1 에서는 1·5 단계 16판 모두 10분을 버텼다(판당 약 440만·490만 점, 분당 지우기
 * 53·58번. 모래가 45° 로 흘러 블록 하나가 더미 겉면을 얇게 덮으며 양쪽 벽을 이었다). sand-0.2(63°)에서는 모두 진다: 1 단계
 * 57~91초(평균 63초), 5 단계 45~80초(평균 52초), 지우기는 판당 0~14번이다.
 * <p>
 * 실행: {@code gradlew sandDropBench --args="1,5 8 600 4 150"}, 또는 (시험 클래스까지 빌드한 뒤, Windows 는 경로 구분이
 * {@code ;}):
 *
 * <pre>
 * java -Djava.awt.headless=true -Dtetris.sound=off -Dtetris.config=off -Dtetris.records=off -Dstdout.encoding=UTF-8 -cp build/classes/java/main;build/classes/java/test kr.ac.jbnu.se.tetris.sand.SandDropBench [시작 단계] [판 수] [초] [스레드] [지연 ms]
 * </pre>
 * 시작 단계는 {@code 1,5} 나 {@code 1-10} 처럼 쓴다. 기본은 {@code 1,5} 단계, 단계마다 8판, 600초, 스레드 4, 지연 150ms. 시드는
 * {@code 990001} 부터({@code SandAiBench} 와 같다). 같은 인자면 언제나 같은 결과다.
 */
public final class SandDropBench {

	private static final long SEED = 990_001;

	private SandDropBench() {
	}

	/** 한 판의 결과. {@code maxHeight} 는 1초마다 잰 정착한 모래 높이(알갱이)의 최대. */
	record Game(long seed, int startLevel, boolean over, long timeMs, long score, int clears, int grains, int pieces,
			int maxMultiplier, int maxHeight, long waitMs) {
	}

	public static void main(String[] args) throws Exception {
		List<Integer> levels = parseLevels(args.length > 0 ? args[0] : "1,5");
		int games = args.length > 1 ? Integer.parseInt(args[1]) : 8;
		int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 600;
		int threads = args.length > 3 ? Integer.parseInt(args[3]) : 4;
		int delay = args.length > 4 ? Integer.parseInt(args[4]) : 150;
		System.out.println("규칙 " + SandRules.VERSION + ", 하드드롭만 하는 손(스폰 " + delay + "ms 뒤), 판 " + games + "개 × 최대 "
				+ seconds + "초, 스레드 " + threads);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int level : levels) {
				List<Future<Game>> fs = new ArrayList<>();
				for (int k = 0; k < games; k++) {
					long seed = SEED + k;
					fs.add(pool.submit(() -> play(seed, level, delay, seconds * 1000L)));
				}
				List<Game> done = new ArrayList<>();
				for (Future<Game> f : fs) {
					done.add(f.get());
				}
				report(level, done);
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * 한 판: 블록이 나온 틱부터 {@code delayMs} 번째 틱에 하드드롭한다. 다음 블록이 기다리는 동안은 아무것도 하지 않는다.
	 *
	 * @param limitMs 시간 상한 (ms). 그때까지 지지 않으면 버틴 판이다
	 */
	static Game play(long seed, int startLevel, int delayMs, long limitMs) {
		SandGame g = new SandGame(seed, startLevel, 0);
		int seen = -1;
		long spawnedAt = 0;
		int maxHeight = 0;
		long waitMs = 0;
		while (!g.over() && g.time() < limitMs) {
			Input in = null;
			if (g.piece() != null) {
				if (g.spawns() != seen) {
					seen = g.spawns();
					spawnedAt = g.time(); // 이 블록은 이 틱(시각 time)에 나왔다
				}
				if (g.time() + 1 - spawnedAt >= delayMs) {
					in = Input.HD;
				}
			}
			g.advance(in);
			if (g.waiting()) {
				waitMs++;
			}
			if (g.time() % 1000 == 0) {
				maxHeight = Math.max(maxHeight, SandRules.HEIGHT - g.settledTop());
			}
		}
		maxHeight = Math.max(maxHeight, SandRules.HEIGHT - g.settledTop());
		return new Game(seed, startLevel, g.over(), g.time(), g.score(), g.clears(), g.grainsCleared(), g.pieces(),
				g.maxMultiplier(), maxHeight, waitMs);
	}

	static List<Integer> parseLevels(String s) {
		List<Integer> out = new ArrayList<>();
		for (String part : s.split(",")) {
			String[] r = part.trim().split("-");
			int a = Integer.parseInt(r[0]);
			int b = r.length > 1 ? Integer.parseInt(r[1]) : a;
			for (int i = a; i <= b; i++) {
				out.add(i);
			}
		}
		return out;
	}

	private static void report(int level, List<Game> games) {
		List<Long> times = new ArrayList<>();
		int overs = 0;
		double minutes = 0;
		long score = 0;
		long clears = 0;
		long pieces = 0;
		long waitMs = 0;
		int maxMul = 0;
		int maxHeight = 0;
		for (Game g : games) {
			System.out.printf(Locale.ROOT, "  시작 %d단계 시드 %d: %s %.1f초, 점수 %d, 지우기 %d, 블록 %d, 최고 배율 %d, 최고 높이 %.1f칸, "
					+ "기다림 %.1f%%%n", level, g.seed(), g.over() ? "짐" : "버팀", g.timeMs() / 1000.0, g.score(), g.clears(),
					g.pieces(), g.maxMultiplier(), g.maxHeight() / (double) SandRules.GRAIN,
					100.0 * g.waitMs() / Math.max(1, g.timeMs()));
			times.add(g.timeMs());
			overs += g.over() ? 1 : 0;
			minutes += g.timeMs() / 60_000.0;
			score += g.score();
			clears += g.clears();
			pieces += g.pieces();
			waitMs += g.waitMs();
			maxMul = Math.max(maxMul, g.maxMultiplier());
			maxHeight = Math.max(maxHeight, g.maxHeight());
		}
		Collections.sort(times);
		int n = games.size();
		System.out.printf(Locale.ROOT, "시작 %d단계: 판 %d (짐 %d), 버틴 시간 평균 %.1f초 (중앙 %.1f, 최소 %.1f, 최대 %.1f), 판당 점수 "
				+ "%.0f, 분당 지우기 %.1f, 분당 블록 %.1f, 최고 배율 %d, 최고 높이 %.1f칸, 기다림 %.1f%%%n", level, n, overs,
				minutes * 60 / n, times.get(n / 2) / 1000.0, times.get(0) / 1000.0, times.get(n - 1) / 1000.0,
				score / (double) n, clears / minutes, pieces / minutes, maxMul, maxHeight / (double) SandRules.GRAIN,
				100.0 * waitMs / (minutes * 60_000));
	}
}
