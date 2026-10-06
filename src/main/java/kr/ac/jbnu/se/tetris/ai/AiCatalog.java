package kr.ac.jbnu.se.tetris.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 게임에서 고를 수 있는 난이도와 페르소나, 그리고 그 모델 파일 (docs/GAME.md 6장). {@code models/manifest.json}
 * (형식 {@code models-1})을 읽는다. 파일이 없거나 모델을 쓸 수 없으면 기준 AI({@link BaselineScorer})로 대신하고 그 이유를
 * 남긴다.
 */
public final class AiCatalog implements AutoCloseable {

	public static final String FORMAT = "models-1";
	public static final String MANIFEST = "manifest.json";
	public static final String BASELINE = "baseline";
	public static final String NEUTRAL = "neutral";
	private static final String NEUTRAL_DESCRIPTION = "이기는 것만 배운 기본 AI";

	/**
	 * 난이도 하나 (GAME.md 6.1).
	 *
	 * @param model        {@code models/} 기준 모델 파일, 또는 {@link #BASELINE}
	 * @param beam         2수 탐색의 beam, 0 이면 1수만 본다
	 * @param gravityCapG  중력 상한 g (초당 칸 수 × 1000)
	 * @param description  메뉴에 보이는 짧은 한 줄 설명, 없으면 빈 문자열
	 * @param personas     이 단계에서 페르소나를 고를 수 있는가 (4·5단계 최상급·불가능만, GAME.md 6.2)
	 */
	public record Difficulty(int id, String name, String model, double temperature, int beam, int thinkMs,
			double inputsPerSec, int gravityCapG, String description, boolean personas) {
	}

	/**
	 * 페르소나 하나 (GAME.md 6.2). {@code model} 이 null 이면 난이도의 모델을 쓴다(중립). {@code description} 은 메뉴에
	 * 보이는 한 줄 설명(학습 보상), 없으면 빈 문자열.
	 */
	public record Persona(String id, String name, String model, String description) {
	}

	/** 고른 난이도·페르소나로 만든 두뇌와 화면에 쓸 설명. {@code fallback} 은 기준 AI 로 대신한 이유, 없으면 null. */
	public record Choice(Brain brain, String label, String fallback) {
	}

	// 모델 묶음과 같은 조작 속도와 중력 상한(GAME.md 6.1). 모델이 없을 때의 기본값이다(두뇌는 모두 기준 AI).
	// 1~3단계는 10-04 에 PLAN 7장보다 약 20% 늦췄다(900/500/300ms, 초당 5/8/12 에서).
	private static final List<Difficulty> DEFAULT_DIFFICULTIES = List.of(
			new Difficulty(1, "초급", BASELINE, 0.2, 0, 1450, 5, 2000, "처음 해 보는 분께 알맞은 느긋한 AI", false),
			new Difficulty(2, "중급", BASELINE, 0.1, 0, 700, 6, 3000, "기본기를 익히는 단계", false),
			new Difficulty(3, "상급", BASELINE, 0.0, 0, 400, 10, 5000, "꾸준히 줄을 지우는 AI", false),
			new Difficulty(4, "최상급", BASELINE, 0.0, 0, 180, 16, 8000, "빠르고 정확한 AI", false),
			new Difficulty(5, "불가능", BASELINE, 0.0, 8, 100, 25, 12000, "다음 블록까지 내다보는 가장 강한 AI", false));

	private final Path dir;
	private final List<Difficulty> difficulties;
	private final List<Persona> personas;
	private final String winprob;
	private final String problem;
	private final Map<Path, PlacementScorer> loaded = new HashMap<>();

	private AiCatalog(Path dir, List<Difficulty> difficulties, List<Persona> personas, String winprob, String problem) {
		this.dir = dir;
		this.difficulties = Collections.unmodifiableList(difficulties);
		this.personas = Collections.unmodifiableList(personas);
		this.winprob = winprob;
		this.problem = problem;
	}

	/** {@code dir/manifest.json} 을 읽는다. 없거나 읽을 수 없으면 기본값(모두 기준 AI)이고 {@link #problem()} 에 이유가 남는다. */
	public static AiCatalog load(Path dir) {
		Path manifest = dir.resolve(MANIFEST);
		if (!Files.isRegularFile(manifest)) {
			return defaults(dir, "모델 묶음이 없음: " + manifest);
		}
		try {
			JsonNode root = new ObjectMapper().readTree(manifest.toFile());
			if (!FORMAT.equals(root.path("format").asText())) {
				return defaults(dir, "manifest.json 의 format 이 " + FORMAT + " 이 아님");
			}
			List<Difficulty> ds = new ArrayList<>();
			for (JsonNode d : root.path("difficulties")) {
				ds.add(new Difficulty(d.path("id").asInt(), d.path("name").asText(), d.path("model").asText(BASELINE),
						d.path("temperature").asDouble(0), d.path("beam").asInt(0), d.path("think_ms").asInt(),
						d.path("inputs_per_sec").asDouble(), (int) Math.round(d.path("gravity_cap").asDouble() * 1000),
						d.path("description").asText(""), d.path("personas").asBoolean(false)));
			}
			List<Persona> ps = new ArrayList<>();
			for (JsonNode p : root.path("personas")) {
				JsonNode m = p.get("model");
				ps.add(new Persona(p.path("id").asText(), p.path("name").asText(),
						m == null || m.isNull() ? null : m.asText(), p.path("description").asText("")));
			}
			if (ds.isEmpty()) {
				return defaults(dir, "manifest.json 에 난이도가 없음");
			}
			if (ps.stream().noneMatch(p -> p.model() == null)) {
				ps.add(0, new Persona(NEUTRAL, "중립", null, NEUTRAL_DESCRIPTION));
			}
			JsonNode w = root.get("winprob");
			return new AiCatalog(dir, ds, ps, w == null || w.isNull() ? null : w.asText(), null);
		} catch (IOException | RuntimeException e) {
			return defaults(dir, "manifest.json 을 읽지 못함: " + e.getMessage());
		}
	}

	private static AiCatalog defaults(Path dir, String problem) {
		return new AiCatalog(dir, DEFAULT_DIFFICULTIES, List.of(new Persona(NEUTRAL, "중립", null, NEUTRAL_DESCRIPTION)),
				null, problem);
	}

	public List<Difficulty> difficulties() {
		return difficulties;
	}

	public List<Persona> personas() {
		return personas;
	}

	/** 묶음을 읽지 못해 기본값을 쓰는 이유, 문제가 없으면 null. */
	public String problem() {
		return problem;
	}

	/** 승률 예측 모델 파일, 없으면 null. */
	public Path winprob() {
		return winprob == null ? null : dir.resolve(winprob).normalize();
	}

	/**
	 * 난이도와 페르소나로 두뇌를 만든다. 페르소나에 모델이 있으면 그 모델을, 없으면(중립) 난이도의 모델을 쓰고, 온도와
	 * 탐색은 난이도를 따른다(GAME.md 6.2). 모델을 쓸 수 없으면 기준 AI 로 대신한다.
	 *
	 * @param seed   판 시드. 고르기 난수 스트림을 정한다 ({@link Chooser#rng})
	 * @param player AI 의 자리 (1 또는 2)
	 */
	public synchronized Choice choose(Difficulty d, Persona p, long seed, int player) {
		if (!d.personas()) {
			p = personas.get(0); // 이 단계에서는 페르소나를 고를 수 없다 (GAME.md 6.2)
		}
		String model = p.model() != null ? p.model() : d.model();
		PlacementScorer scorer = null;
		String fallback = null;
		if (!BASELINE.equals(model)) {
			Path path = dir.resolve(model).normalize();
			try {
				scorer = loaded.get(path);
				if (scorer == null) {
					scorer = OnnxScorer.load(path, 1);
					loaded.put(path, scorer);
				}
			} catch (Exception | LinkageError e) {
				fallback = path.getFileName() + " 을 쓸 수 없어 기준 AI 로 대신함: " + e.getMessage();
				scorer = null;
			}
		}
		if (scorer == null) {
			scorer = new BaselineScorer();
		}
		SplitMix64 rng = Chooser.rng(seed, player);
		Brain brain = new Brain(scorer, d.temperature(), d.beam() > 0 ? new BeamSearch(d.beam()) : null, rng);
		String label = d.name() + " · " + p.name() + (fallback != null || BASELINE.equals(model) ? " (기준 AI)" : "");
		return new Choice(brain, label, fallback);
	}

	@Override
	public synchronized void close() {
		loaded.values().forEach(PlacementScorer::close);
		loaded.clear();
	}
}
