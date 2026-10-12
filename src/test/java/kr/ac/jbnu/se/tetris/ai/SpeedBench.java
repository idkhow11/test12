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
 * 사용자 설정 "AI 속도"(50~150%)로 바꾼 조작 속도를 가상 시간 모드(RULES 12.3)로 잰다 (PLAN 4.3, 7장). 고른 단계와 속도마다
 * 같은 단계·같은 속도의 AI 끼리 그 단계의 중력 상한으로 두어(게임과 같은 두뇌, {@link AiCatalog.Difficulty#atSpeed}),
 * <ul>
 * <li><b>초당 블록 수</b>(PPS)와 PLAN 7장의 어림값 1000 / (생각 지연 + 6000 / 초당 입력),</li>
 * <li><b>실행 실패율</b>: 목표를 한 번이라도 바꾼 블록 ÷ 목표를 정한 블록 (목표 5% 이하),</li>
 * <li><b>생각 중 고정</b>: 생각 지연이 끝나기 전에 중력·락 딜레이로 고정돼 버린 블록 ÷ 고정한 블록 (생각이 너무 길면 늘어난다)</li>
 * </ul>
 * 을 낸다. 실행: {@code gradlew speedBench --args="<결과.json> [단계 1,3,5] [속도 50,100,150] [판 수] [판 상한 초] [스레드]"}.
 */
public final class SpeedBench {

	private static final long SEED0 = 990001;

	/** 한 판. {@code late}: 목표를 정하기 전에(생각 지연 동안) 고정돼 버린 블록 수. */
	private record Game(int level, int speed, long ms, int[] pieces, int[] planned, int[] failed, int[] late) {
	}

	private SpeedBench() {
	}

	public static void main(String[] args) throws Exception {
		File out = new File(args.length > 0 ? args[0] : "build/speed.json");
		int[] levels = ints(args.length > 1 ? args[1] : "1,3,5");
		int[] speeds = ints(args.length > 2 ? args[2] : "50,100,150");
		int games = args.length > 3 ? Integer.parseInt(args[3]) : 4;
		long limitMs = (args.length > 4 ? Long.parseLong(args[4]) : 120) * 1000;
		int threads = args.length > 5 ? Integer.parseInt(args[5]) : 3;
		try (AiCatalog catalog = AiCatalog.load(Path.of(System.getProperty("tetris.models", "models")))) {
			if (catalog.problem() != null) {
				System.out.println("경고: " + catalog.problem());
			}
			catalog.preload();
			AiCatalog.Persona neutral = catalog.personas().get(0);
			ExecutorService pool = Executors.newFixedThreadPool(threads);
			List<Future<Game>> futures = new ArrayList<>();
			for (int level : levels) {
				AiCatalog.Difficulty d = catalog.difficulties().get(level - 1);
				for (int speed : speeds) {
					for (int k = 0; k < games; k++) {
						long seed = SEED0 + k; // 속도마다 같은 시드: 속도만 다르다
						futures.add(pool.submit(() -> play(catalog, d, neutral, speed, seed, limitMs)));
					}
				}
			}
			pool.shutdown();
			List<Game> done = new ArrayList<>();
			for (Future<Game> f : futures) {
				done.add(f.get());
			}
			report(catalog, levels, speeds, done, limitMs, out);
		}
		System.exit(0);
	}

	private static int[] ints(String csv) {
		String[] parts = csv.split(",");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++) {
			out[i] = Integer.parseInt(parts[i].trim());
		}
		return out;
	}

	/** 단계 d 를 속도 speed% 로 둔 AI 둘의 한 판 (게임처럼 그 단계의 중력 상한). */
	private static Game play(AiCatalog catalog, AiCatalog.Difficulty d, AiCatalog.Persona persona, int speed, long seed,
			long limitMs) {
		AiCatalog.Difficulty played = d.atSpeed(speed);
		TimedMatch m = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, d.gravityCapG());
		AiController[] ai = new AiController[2];
		for (int i = 0; i < 2; i++) {
			int other = 1 - i;
			AiCatalog.Choice c = catalog.choose(d, persona, seed, i + 1);
			ai[i] = new AiController(m.player(i), () -> Opponent.of(m.player(other).game()), c.brain(),
					played.thinkMs(), played.inputsPerSec());
		}
		int[] late = new int[2];
		int[] plannedAtLock = new int[2];
		while (!m.isOver() && m.time() < limitMs) {
			m.tick(ai[0].poll(), ai[1].poll());
			for (int i = 0; i < 2; i++) {
				if (m.lastLock(i) != null) { // 블록마다 목표는 한 번 정한다: 지난 고정 뒤로 정한 적이 없으면 생각 중에 고정됐다
					late[i] += ai[i].planned() == plannedAtLock[i] ? 1 : 0;
					plannedAtLock[i] = ai[i].planned();
				}
			}
		}
		return new Game(d.id(), speed, m.time(), new int[] { m.player(0).game().pieces(), m.player(1).game().pieces() },
				new int[] { ai[0].planned(), ai[1].planned() }, new int[] { ai[0].failed(), ai[1].failed() }, late);
	}

	private static void report(AiCatalog catalog, int[] levels, int[] speeds, List<Game> games, long limitMs, File out)
			throws Exception {
		ObjectMapper json = new ObjectMapper();
		ObjectNode root = json.createObjectNode();
		root.put("format", "speed-1");
		root.put("mode", "virtual-time (RULES 12.3), 같은 단계·같은 속도끼리, 그 단계의 중력 상한");
		root.put("limit_seconds", limitMs / 1000);
		ArrayNode rows = root.putArray("rows");
		System.out.println("단계    속도  생각ms 초당입력  PPS(어림)     실행 실패율  생각 중 고정  판 길이(초)  판 수");
		for (int level : levels) {
			AiCatalog.Difficulty d = catalog.difficulties().get(level - 1);
			for (int speed : speeds) {
				AiCatalog.Difficulty p = d.atSpeed(speed);
				long pieces = 0;
				long planned = 0;
				long failed = 0;
				long late = 0;
				long ms = 0;
				int n = 0;
				for (Game g : games) {
					if (g.level() != level || g.speed() != speed) {
						continue;
					}
					for (int i = 0; i < 2; i++) {
						pieces += g.pieces()[i];
						planned += g.planned()[i];
						failed += g.failed()[i];
						late += g.late()[i];
					}
					ms += g.ms();
					n++;
				}
				double pps = ms == 0 ? 0 : pieces / (2.0 * ms / 1000.0);
				double estimate = 1000.0 / (p.thinkMs() + AiCatalog.Difficulty.INPUTS_PER_PIECE * 1000.0 / p.inputsPerSec());
				double fail = planned == 0 ? 0 : (double) failed / planned;
				double lateRate = pieces == 0 ? 0 : (double) late / pieces;
				ObjectNode o = rows.addObject();
				o.put("level", level);
				o.put("name", d.name());
				o.put("speed_pct", speed);
				o.put("think_ms", p.thinkMs());
				o.put("inputs_per_sec", p.inputsPerSec());
				o.put("games", n);
				o.put("pps", pps);
				o.put("pps_estimate", estimate);
				o.put("failure_rate", fail);
				o.put("failed", failed);
				o.put("planned", planned);
				o.put("locked_while_thinking", lateRate);
				o.put("late", late);
				o.put("pieces", pieces);
				o.put("mean_seconds", n == 0 ? 0 : ms / 1000.0 / n);
				System.out.printf(Locale.ROOT, "%d %-4s %4d%%  %5d  %5.1f   %.2f (%.2f)   %6.2f%% (%d/%d)  %6.2f%% (%d)  %6.1f  %d%n",
						level, d.name(), speed, p.thinkMs(), p.inputsPerSec(), pps, estimate, 100 * fail, failed, planned,
						100 * lateRate, late, n == 0 ? 0 : ms / 1000.0 / n, n);
			}
		}
		File parent = out.getAbsoluteFile().getParentFile();
		parent.mkdirs();
		json.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out, root);
		System.out.println("→ " + out);
	}
}
