package kr.ac.jbnu.se.tetris.train;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.Brain;
import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 승률 예측망의 실시간 자료 (docs/TRAINING.md 10.9): 게임의 AI(난이도 5단계, 4·5단계 최상급·불가능은 페르소나 포함)와 사람을 흉내 낸 AI를
 * 가상 시간 모드(RULES 12.3)로 두고 기록 {@code timed-1} 을 남긴다.
 * <p>
 * 사람 흉내: 난이도의 두뇌 하나에 생각 지연(150~1,500ms), 초당 입력(3~20), 고르기 온도(0~0.25)를 판마다 무작위로 준다.
 * 게임처럼 중력 상한은 게임 AI 쪽 단계를 따르고, 자리는 판마다 무작위다. 짝과 속도는 판의 시드로 정해져 같은 계획이면 같은
 * 자료가 나온다. 사용: {@code java ... TimedDataMain <plan.json>}.
 *
 * <pre>
 * {"format": "timedplan-1", "run": "wpt01", "models": "../models", "seed_base": 600000000, "first": 0,
 *  "count": 1000, "threads": 6, "max_ms": 600000, "out": "chunk-000"}
 * </pre>
 */
public final class TimedDataMain {

	public static final String PLAN_FORMAT = "timedplan-1";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final long PAIR_STREAM = 0x5EED_7A1E_D0C5L; // 짝 짓기 난수: 판 시드와 섞는다

	private TimedDataMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("사용: TimedDataMain <plan.json>");
			System.exit(2);
		}
		Path planPath = Path.of(args[0]).toAbsolutePath().normalize();
		JsonNode plan = JSON.readTree(planPath.toFile());
		if (!PLAN_FORMAT.equals(plan.path("format").asText())) {
			throw new IllegalArgumentException("format 은 " + PLAN_FORMAT + " 이어야 함");
		}
		Path base = planPath.getParent();
		String run = plan.path("run").asText("timed");
		long seedBase = plan.path("seed_base").asLong();
		long first = plan.path("first").asLong(0);
		int count = plan.path("count").asInt();
		int threads = Math.max(1, plan.path("threads").asInt(4));
		long maxMs = plan.path("max_ms").asLong(600_000);
		Path out = base.resolve(plan.path("out").asText("matches")).normalize();
		Files.createDirectories(out);
		try (AiCatalog catalog = AiCatalog.load(base.resolve(plan.path("models").asText("models")).normalize())) {
			if (catalog.problem() != null) {
				throw new IllegalStateException("모델 묶음을 읽지 못함: " + catalog.problem());
			}
			runAll(catalog, run, seedBase + first, count, threads, maxMs, out);
		}
	}

	private static void runAll(AiCatalog catalog, String run, long seed0, int count, int threads, long maxMs, Path out)
			throws Exception {
		long start = System.nanoTime();
		AtomicInteger next = new AtomicInteger();
		AtomicInteger done = new AtomicInteger();
		AtomicLong steps = new AtomicLong();
		AtomicLong gameMs = new AtomicLong();
		AtomicInteger[] results = { new AtomicInteger(), new AtomicInteger(), new AtomicInteger() };
		AtomicBoolean failed = new AtomicBoolean();
		int report = Math.max(1, count / 10);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<?>> futures = new ArrayList<>();
		for (int w = 0; w < threads; w++) {
			Path file = out.resolve("w" + w + ".jsonl.gz");
			futures.add(pool.submit(() -> {
				try (Writer writer = new BufferedWriter(new OutputStreamWriter(
						new GZIPOutputStream(Files.newOutputStream(file), 1 << 16), StandardCharsets.UTF_8))) {
					int i;
					while (!failed.get() && (i = next.getAndIncrement()) < count) {
						ObjectNode record = play(catalog, run, seed0 + i, maxMs);
						writer.write(JSON.writeValueAsString(record));
						writer.write('\n');
						steps.addAndGet(record.get("turns").asInt());
						gameMs.addAndGet(record.get("time_ms").asLong());
						String r = record.get("result").asText();
						results["p1".equals(r) ? 0 : "p2".equals(r) ? 1 : 2].incrementAndGet();
						int d = done.incrementAndGet();
						if (d % report == 0 || d == count) {
							System.out.printf(Locale.ROOT, "[timed] %d/%d판, %.0f초%n", d, count,
									(System.nanoTime() - start) / 1e9);
						}
					}
				} catch (IOException | RuntimeException e) {
					failed.set(true);
					throw e;
				}
				return null;
			}));
		}
		pool.shutdown();
		for (Future<?> f : futures) {
			f.get();
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		ObjectNode summary = JSON.createObjectNode();
		summary.put("format", "timed-summary-1");
		summary.put("matches", count);
		summary.put("seconds", Math.round(seconds * 10) / 10.0);
		summary.put("steps", steps.get());
		summary.put("game_seconds_mean", count == 0 ? 0 : gameMs.get() / 1000.0 / count);
		summary.put("p1_wins", results[0].get());
		summary.put("p2_wins", results[1].get());
		summary.put("draws", results[2].get());
		JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.resolve("summary.json").toFile(), summary);
		System.out.printf(Locale.ROOT, "[timed] 끝: %d판, %.1f초, 상태 %d개, 판 평균 %.1f초%n", count, seconds, steps.get(),
				count == 0 ? 0 : gameMs.get() / 1000.0 / count);
	}

	/** 한 판: 게임 AI 대 사람 흉내 AI. 짝·속도·자리는 시드로 정해진다. */
	static ObjectNode play(AiCatalog catalog, String run, long seed, long maxMs) {
		SplitMix64 rng = new SplitMix64(seed ^ PAIR_STREAM);
		List<AiCatalog.Difficulty> levels = catalog.difficulties();
		List<AiCatalog.Persona> personas = catalog.personas();

		// 게임 AI: 단계는 고르게, 4·5단계면 페르소나도 고르게(중립 포함)
		AiCatalog.Difficulty level = levels.get(rng.nextInt(levels.size()));
		AiCatalog.Persona persona = level.personas() ? personas.get(rng.nextInt(personas.size())) : personas.get(0);
		// 사람 흉내: 두뇌 하나와 무작위 속도·온도
		AiCatalog.Difficulty brainLevel = levels.get(rng.nextInt(levels.size()));
		int think = 150 + rng.nextInt(1351);
		double ips = 3 + rng.nextInt(18);
		double tau = new double[] { 0, 0, 0.05, 0.1, 0.15, 0.25 }[rng.nextInt(6)];
		boolean humanFirst = rng.nextInt(2) == 0;
		int aiSeat = humanFirst ? 2 : 1;
		int humanSeat = humanFirst ? 1 : 2;

		TimedMatch m = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, level.gravityCapG());
		AiCatalog.Choice ai = catalog.choose(level, persona, seed, aiSeat);
		AiCatalog.Choice brain = catalog.choose(brainLevel, personas.get(0), seed, humanSeat);
		Brain human = new Brain(brain.brain().scorer(), tau, null, Chooser.rng(seed, humanSeat));
		int ai0 = aiSeat - 1;
		int hu0 = humanSeat - 1;
		AiController[] c = new AiController[2];
		c[ai0] = new AiController(m.player(ai0), () -> Opponent.of(m.player(hu0).game()), ai.brain(), level.thinkMs(),
				level.inputsPerSec());
		c[hu0] = new AiController(m.player(hu0), () -> Opponent.of(m.player(ai0).game()), human, think, ips);

		ObjectNode aiDesc = TimedRecorder.player("level" + level.id() + (level.personas() ? "-" + persona.id() : ""),
				ai.label(), level.temperature());
		aiDesc.put("think_ms", level.thinkMs());
		aiDesc.put("inputs_per_sec", level.inputsPerSec());
		ObjectNode huDesc = TimedRecorder.player("proxy-level" + brainLevel.id(), brain.label(), tau);
		huDesc.put("think_ms", think);
		huDesc.put("inputs_per_sec", ips);
		TimedRecorder rec = humanFirst ? new TimedRecorder(run, seed, huDesc, aiDesc)
				: new TimedRecorder(run, seed, aiDesc, huDesc);
		while (!m.isOver() && m.time() < maxMs) {
			m.tick(c[0].poll(), c[1].poll());
			rec.afterTick(m);
		}
		ObjectNode record = rec.finish(m);
		record.put("gravity_cap", level.gravityCapG() / 1000.0);
		return record;
	}
}
