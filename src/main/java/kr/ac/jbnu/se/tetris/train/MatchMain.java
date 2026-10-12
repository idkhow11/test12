package kr.ac.jbnu.se.tetris.train;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

import kr.ac.jbnu.se.tetris.ai.BaselineScorer;
import kr.ac.jbnu.se.tetris.ai.BeamSearch;
import kr.ac.jbnu.se.tetris.ai.OnnxScorer;
import kr.ac.jbnu.se.tetris.ai.PlacementScorer;

/**
 * 대전 계획 파일({@code plan-1})대로 여러 판을 여러 스레드로 두고 기록을 남긴다 (docs/TRAINING.md 6, 7장).
 * <p>
 * 사용: {@code gradlew match --args="<plan.json>"}. 학습 루프가 세대마다 계획 파일을 만들어 부른다. 계획 파일 안의 상대 경로는
 * 계획 파일이 있는 폴더 기준이다. 결과는 {@code <out>/w<스레드>.jsonl.gz}(한 줄이 한 판)와 {@code <out>/summary.json} 이다.
 * <p>
 * 계획 파일 예:
 *
 * <pre>
 * {"format": "plan-1", "run": "r01", "gen": 3, "max_turns": 1000, "threads": 12, "out": "matches",
 *  "players": {"ai00": {"persona": "neutral", "model": "models/value_ai00_gen-003.onnx", "temperature": 0.3},
 *              "baseline": {"model": "baseline", "temperature": 0.3}},
 *  "matches": [{"seed": 3000000, "p1": "ai00", "p2": "baseline"}]}
 * </pre>
 *
 * 플레이어에 {@code "search": {"beam": 8}} 을 주면 그 플레이어는 2수 탐색으로 고른다(TRAINING.md 6.1, 6.2).
 */
public final class MatchMain {

	public static final String PLAN_FORMAT = "plan-1";

	private static final ObjectMapper JSON = new ObjectMapper();

	private MatchMain() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) {
			System.err.println("사용: gradlew match --args=\"<plan.json>\"");
			System.exit(2);
		}
		// 경로는 모두 정리(normalize)해서 쓴다. 계획 파일이 저장소 밖에 있으면 ".." 가 겹겹이 붙은 경로가 260 자를 넘어
		// 긴 경로를 허용하지 않는 Windows 에서 모델 파일을 열지 못한다.
		Path planPath = Path.of(args[0]).toAbsolutePath().normalize();
		JsonNode plan = JSON.readTree(planPath.toFile());
		require(PLAN_FORMAT.equals(plan.path("format").asText()), "format 은 " + PLAN_FORMAT + " 이어야 함");
		Path base = planPath.getParent();
		String run = text(plan, "run");
		int gen = plan.path("gen").asInt(0);
		int maxTurns = plan.path("max_turns").asInt(1000);
		int threads = Math.max(1, plan.path("threads").asInt(Runtime.getRuntime().availableProcessors()));
		Path out = base.resolve(text(plan, "out")).normalize();
		Files.createDirectories(out);

		Map<String, PlacementScorer> scorers = new HashMap<>();
		Map<String, MatchRunner.Player> players = new LinkedHashMap<>();
		try {
			for (Map.Entry<String, JsonNode> e : plan.path("players").properties()) {
				JsonNode p = e.getValue();
				String model = text(p, "model");
				boolean baseline = "baseline".equals(model);
				Path modelPath = baseline ? null : base.resolve(model).normalize();
				PlacementScorer scorer = scorers.get(model);
				if (scorer == null) {
					// 같은 모델 파일은 한 번만 불러 모든 스레드가 함께 쓴다. 판마다 스레드 하나라서 연산 스레드는 1 개로 둔다.
					scorer = baseline ? new BaselineScorer() : OnnxScorer.load(modelPath, 1);
					scorers.put(model, scorer);
				}
				String file = baseline ? model : modelPath.getFileName().toString();
				players.put(e.getKey(), new MatchRunner.Player(e.getKey(), p.path("persona").asText("neutral"), file,
						p.path("temperature").asDouble(0), scorer, search(p.get("search"))));
			}
			List<JsonNode> matches = new ArrayList<>();
			plan.path("matches").forEach(matches::add);
			for (JsonNode m : matches) {
				require(players.containsKey(text(m, "p1")) && players.containsKey(text(m, "p2")), "모르는 플레이어: " + m);
			}
			runAll(run, gen, maxTurns, threads, out, players, matches);
		} finally {
			scorers.values().forEach(PlacementScorer::close);
		}
	}

	private static void runAll(String run, int gen, int maxTurns, int threads, Path out,
			Map<String, MatchRunner.Player> players, List<JsonNode> matches) throws Exception {
		long start = System.nanoTime();
		AtomicInteger next = new AtomicInteger();
		AtomicInteger done = new AtomicInteger();
		AtomicLong turnsTotal = new AtomicLong();
		AtomicBoolean failed = new AtomicBoolean();
		// AI 이름 → [이김, 짐, 비김, 같은 AI 끼리 둔 판]. 자리와 상관없이 센다.
		Map<String, int[]> tally = new TreeMap<>();
		int report = Math.max(1, matches.size() / 10);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<Future<?>> futures = new ArrayList<>();
		for (int w = 0; w < threads; w++) {
			Path file = out.resolve("w" + w + ".jsonl.gz");
			futures.add(pool.submit(() -> {
				Writer writer = null;
				try {
					int i;
					while (!failed.get() && (i = next.getAndIncrement()) < matches.size()) {
						JsonNode m = matches.get(i);
						MatchRunner.Player p1 = players.get(m.get("p1").asText());
						MatchRunner.Player p2 = players.get(m.get("p2").asText());
						ObjectNode record = MatchRunner.play(run, gen, m.get("seed").asLong(), maxTurns, p1, p2);
						if (writer == null) {
							writer = new BufferedWriter(new OutputStreamWriter(
									new GZIPOutputStream(Files.newOutputStream(file), 1 << 16), StandardCharsets.UTF_8));
						}
						writer.write(JSON.writeValueAsString(record));
						writer.write('\n');
						turnsTotal.addAndGet(record.get("turns").asInt());
						count(tally, p1.ai(), p2.ai(), record.get("result").asText());
						int d = done.incrementAndGet();
						if (d % report == 0 || d == matches.size()) {
							System.out.printf("[match] %d/%d판, %.0f초%n", d, matches.size(), (System.nanoTime() - start) / 1e9);
						}
					}
				} catch (RuntimeException | IOException e) {
					failed.set(true);
					throw e;
				} finally {
					if (writer != null) {
						writer.close();
					}
				}
				return null;
			}));
		}
		pool.shutdown();
		Exception first = null;
		for (Future<?> f : futures) {
			try {
				f.get();
			} catch (java.util.concurrent.ExecutionException e) {
				if (first == null) {
					first = e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
				}
			}
		}
		if (first != null) {
			throw first;
		}

		double seconds = (System.nanoTime() - start) / 1e9;
		ObjectNode summary = JSON.createObjectNode();
		summary.put("format", "summary-1");
		summary.put("matches", matches.size());
		summary.put("seconds", Math.round(seconds * 10) / 10.0);
		summary.put("turns_mean", matches.isEmpty() ? 0 : (double) turnsTotal.get() / matches.size());
		ObjectNode byAi = summary.putObject("players");
		tally.forEach((ai, c) -> {
			ObjectNode o = byAi.putObject(ai);
			o.put("wins", c[0]);
			o.put("losses", c[1]);
			o.put("draws", c[2]);
			o.put("self_play", c[3]);
		});
		JSON.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.resolve("summary.json").toFile(), summary);
		System.out.printf("[match] 끝: %d판, %.1f초, 평균 %.1f턴, %s%n", matches.size(), seconds,
				summary.get("turns_mean").asDouble(), byAi);
	}

	/** 같은 AI 끼리 둔 판은 한 AI 의 이김과 짐이 함께 되므로 승패로 세지 않고 따로 센다. */
	private static void count(Map<String, int[]> tally, String p1, String p2, String result) {
		synchronized (tally) {
			if (p1.equals(p2)) {
				tally.computeIfAbsent(p1, k -> new int[4])[3]++;
				return;
			}
			int[] a = tally.computeIfAbsent(p1, k -> new int[4]);
			int[] b = tally.computeIfAbsent(p2, k -> new int[4]);
			switch (result) {
			case "p1":
				a[0]++;
				b[1]++;
				break;
			case "p2":
				a[1]++;
				b[0]++;
				break;
			default:
				a[2]++;
				b[2]++;
			}
		}
	}

	/** 플레이어의 {@code search} (TRAINING.md 6.1, 6.2). 없거나 null 이면 1수만 본다. */
	static BeamSearch search(JsonNode s) {
		if (s == null || s.isNull()) {
			return null;
		}
		require(s.isObject() && s.path("beam").isInt() && s.get("beam").asInt() >= 1,
				"search 는 {\"beam\": 1 이상의 정수} 여야 함: " + s);
		return new BeamSearch(s.get("beam").asInt());
	}

	private static String text(JsonNode m, String field) {
		JsonNode v = m.get(field);
		require(v != null && v.isTextual(), "필드 " + field + " 는 문자열이어야 함");
		return v.asText();
	}

	private static void require(boolean ok, String message) {
		if (!ok) {
			throw new IllegalArgumentException("대전 계획 파일 오류: " + message);
		}
	}
}
