package kr.ac.jbnu.se.tetris.ai;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 게임의 난이도 단계를 가상 시간 모드(RULES 12.3)로 잰다 (docs/GAME.md 5, 6장, PLAN 4.3, 7장). 모델 묶음
 * {@code models/} 의 단계마다
 * <ul>
 * <li>같은 단계끼리 두어 <b>실행 실패율</b>(목표를 바꾼 블록 비율, 목표 5% 이하)과 초당 블록 수(PPS)를 재고,</li>
 * <li>이웃한 단계끼리(위 ↔ 아래, 위 단계의 중력 상한) 두어 위 단계가 이기는지 본다.</li>
 * </ul>
 * 실행: {@code gradlew levelBench --args="<결과.json> <짝마다 판 수> <스레드>"}.
 */
public final class LevelBench {

	private static final long SEED0 = 950001;
	private static final long LIMIT_MS = 10 * 60_000; // 10분이 넘으면 무승부로 센다

	private record Game(int a, int b, long seed, String result, long ms, int[] pieces, int[] planned, int[] failed) {
	}

	private LevelBench() {
	}

	public static void main(String[] args) throws Exception {
		File out = new File(args.length > 0 ? args[0] : "build/levels.json");
		int games = args.length > 1 ? Integer.parseInt(args[1]) : 16;
		int threads = args.length > 2 ? Integer.parseInt(args[2]) : 3;
		try (AiCatalog catalog = AiCatalog.load(Path.of(System.getProperty("tetris.models", "models")))) {
			if (catalog.problem() != null) {
				System.out.println("경고: " + catalog.problem());
			}
			List<AiCatalog.Difficulty> levels = catalog.difficulties();
			AiCatalog.Persona neutral = catalog.personas().get(0);
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			List<Future<Game>> futures = new ArrayList<>();
			long seed = SEED0;
			for (int i = 0; i < levels.size(); i++) {
				for (int k = 0; k < games; k++) {
					final int lv = i;
					final long s = seed++;
					futures.add(pool.submit(() -> play(catalog, levels, neutral, lv, lv, s)));
				}
			}
			for (int i = 1; i < levels.size(); i++) {
				for (int k = 0; k < games; k++) {
					final int hi = i;
					final long s = seed + k / 2; // 같은 시드로 자리를 바꿔 두 판
					final boolean swap = k % 2 == 1;
					futures.add(pool.submit(() -> swap ? play(catalog, levels, neutral, hi - 1, hi, s)
							: play(catalog, levels, neutral, hi, hi - 1, s)));
				}
				seed += games;
			}
			pool.shutdown();
			List<Game> done = new ArrayList<>();
			for (Future<Game> f : futures) {
				done.add(f.get());
			}
			report(levels, done, out);
		}
		System.exit(0);
	}

	/** 단계 a(P1) 대 단계 b(P2) 한 판. 중력 상한은 둘 중 높은 단계의 것. */
	private static Game play(AiCatalog catalog, List<AiCatalog.Difficulty> levels, AiCatalog.Persona persona, int a,
			int b, long seed) {
		AiCatalog.Difficulty da = levels.get(a);
		AiCatalog.Difficulty db = levels.get(b);
		TimedMatch m = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, Math.max(da.gravityCapG(), db.gravityCapG()));
		AiController[] ai = new AiController[2];
		AiCatalog.Difficulty[] ds = { da, db };
		for (int i = 0; i < 2; i++) {
			int other = 1 - i;
			AiCatalog.Choice c = catalog.choose(ds[i], persona, seed, i + 1);
			ai[i] = new AiController(m.player(i), () -> Opponent.of(m.player(other).game()), c.brain(),
					ds[i].thinkMs(), ds[i].inputsPerSec());
		}
		while (!m.isOver() && m.time() < LIMIT_MS) {
			m.tick(ai[0].poll(), ai[1].poll());
		}
		String result = m.isOver() ? m.result().name() : "LIMIT";
		return new Game(a, b, seed, result, m.time(),
				new int[] { m.player(0).game().pieces(), m.player(1).game().pieces() },
				new int[] { ai[0].planned(), ai[1].planned() }, new int[] { ai[0].failed(), ai[1].failed() });
	}

	private static void report(List<AiCatalog.Difficulty> levels, List<Game> games, File out) throws Exception {
		ObjectMapper json = new ObjectMapper();
		ObjectNode root = json.createObjectNode();
		root.put("format", "levels-1");
		root.put("mode", "virtual-time (RULES 12.3)");
		ArrayNode self = root.putArray("self");
		System.out.println("단계  실행 실패율  PPS   평균 판 길이(초)   판 수");
		for (int i = 0; i < levels.size(); i++) {
			long planned = 0;
			long failed = 0;
			long pieces = 0;
			long ms = 0;
			int n = 0;
			for (Game g : games) {
				if (g.a == i && g.b == i) {
					planned += g.planned[0] + g.planned[1];
					failed += g.failed[0] + g.failed[1];
					pieces += g.pieces[0] + g.pieces[1];
					ms += g.ms;
					n++;
				}
			}
			double fail = planned == 0 ? 0 : (double) failed / planned;
			double pps = ms == 0 ? 0 : pieces / (2.0 * ms / 1000.0);
			ObjectNode o = self.addObject();
			o.put("level", levels.get(i).id());
			o.put("name", levels.get(i).name());
			o.put("games", n);
			o.put("failure_rate", fail);
			o.put("pps", pps);
			o.put("mean_seconds", n == 0 ? 0 : ms / 1000.0 / n);
			System.out.printf(Locale.ROOT, "%d %s  %.3f      %.2f  %.1f  %d%n", levels.get(i).id(), levels.get(i).name(),
					fail, pps, n == 0 ? 0 : ms / 1000.0 / n, n);
		}
		ArrayNode nb = root.putArray("neighbors");
		System.out.println("위 단계 대 아래 단계: 위 단계 승률 (이김/짐/비김·시간 초과)");
		for (int i = 1; i < levels.size(); i++) {
			int win = 0;
			int loss = 0;
			int draw = 0;
			for (Game g : games) {
				boolean hiFirst = g.a == i && g.b == i - 1;
				boolean hiSecond = g.a == i - 1 && g.b == i;
				if (!hiFirst && !hiSecond) {
					continue;
				}
				String hi = hiFirst ? "P1" : "P2";
				String lo = hiFirst ? "P2" : "P1";
				if (g.result.equals(hi)) {
					win++;
				} else if (g.result.equals(lo)) {
					loss++;
				} else {
					draw++;
				}
			}
			int n = win + loss + draw;
			double rate = n == 0 ? 0 : (win + 0.5 * draw) / n;
			ObjectNode o = nb.addObject();
			o.put("upper", levels.get(i).id());
			o.put("lower", levels.get(i - 1).id());
			o.put("games", n);
			o.put("upper_win_rate", rate);
			o.put("wins", win);
			o.put("losses", loss);
			o.put("draws_or_limit", draw);
			System.out.printf(Locale.ROOT, "%d 대 %d: %.3f (%d/%d/%d)%n", levels.get(i).id(), levels.get(i - 1).id(), rate,
					win, loss, draw);
		}
		out.getParentFile().mkdirs();
		json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out, root);
		System.out.println("→ " + out);
	}
}
