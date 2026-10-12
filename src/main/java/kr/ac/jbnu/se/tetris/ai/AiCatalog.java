package kr.ac.jbnu.se.tetris.ai;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 게임에서 고를 수 있는 난이도와 페르소나, 그리고 그 모델 파일 (docs/GAME.md 6장). {@code models/manifest.json}
 * (형식 {@code models-1})을 읽는다. 파일이 없거나 모델을 쓸 수 없으면 기준 AI({@link BaselineScorer})로 대신하고 그 이유를
 * 남긴다.
 * <p>
 * <b>스레드.</b> 모델 불러오기(처음 하나는 ONNX Runtime 을 띄우느라 약 0.2초, 그 뒤로는 하나에 10~20ms)는 이 객체의 잠금을
 * 잡고 한다. 이미 불러 본 모델(불러왔거나 쓸 수 없다고 안 모델)로 고르는 것은 잠금 없이 바로 한다. 그래서 배경 스레드가
 * 다른 모델을 불러오는 동안에도 기다리지 않는다. 화면 스레드는 기다리지 않는 {@link #chooseIfReady}·{@link #winRateReady()}
 * 만 쓰고, 아직 불러오지 않은 모델은 게임 진행 스레드가 {@link #choose}·{@link #winRate()} 로 불러온다.
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

		/** AI 속도를 바꿔도 생각 지연은 이보다 짧아지지 않는다 (ms). 더 빨라야 하면 초당 입력 수를 올린다. */
		public static final int MIN_THINK_MS = 50;
		/** AI 속도를 올려도 초당 입력 수는 이를 넘지 않는다. */
		public static final double MAX_INPUTS_PER_SEC = 30;
		/** 블록 하나에 드는 입력 수의 어림값 (PLAN 7장: PPS ≈ 1 / (생각 지연 + 6 / 초당 입력)). */
		public static final int INPUTS_PER_PIECE = 6;

		/**
		 * AI 속도 {@code percent}(%)로 둘 때의 조작 속도 (사용자 설정 "AI 속도", 100 이면 이 단계 그대로). 결정 품질(모델,
		 * 온도, 탐색)과 중력 상한은 바꾸지 않는다. 블록 하나에 드는 시간 T = 생각 지연 + 6000 / 초당 입력 (ms) 을
		 * {@code 100 / percent} 배 한다.
		 * <ul>
		 * <li>느림은 생각 지연으로 만든다(PLAN 7장): 초당 입력 수는 그대로 두고 생각 지연 = T' − 6000 / 초당 입력.</li>
		 * <li>그 생각 지연이 {@link #MIN_THINK_MS} 보다 짧아지면 생각 지연은 {@link #MIN_THINK_MS} 로 두고 초당 입력 수를
		 * 6000 / (T' − {@link #MIN_THINK_MS}) 로 올린다(많아야 {@link #MAX_INPUTS_PER_SEC}, 원래 값보다 낮추지는 않는다).</li>
		 * </ul>
		 * 순수 함수다. {@code percent} 가 클수록 생각 지연은 짧아지거나 같고 초당 입력 수는 많아지거나 같다.
		 *
		 * @param percent AI 속도 (%). 1 이상. 게임은 50~150 을 10 단위로 쓴다
		 * @return 조작 속도를 바꾼 난이도. 100 이면 이 객체 그대로
		 * @throws IllegalArgumentException {@code percent} 가 1 보다 작으면
		 */
		public Difficulty atSpeed(int percent) {
			if (percent < 1) {
				throw new IllegalArgumentException("AI 속도는 1% 이상: " + percent);
			}
			if (percent == 100) {
				return this;
			}
			double moveMs = INPUTS_PER_PIECE * 1000.0 / inputsPerSec;
			double pieceMs = (thinkMs + moveMs) * 100.0 / percent;
			long think = Math.round(pieceMs - moveMs);
			double ips = inputsPerSec;
			if (think < MIN_THINK_MS) {
				think = MIN_THINK_MS;
				double left = pieceMs - MIN_THINK_MS;
				double faster = left <= 0 ? MAX_INPUTS_PER_SEC : INPUTS_PER_PIECE * 1000.0 / left;
				ips = Math.max(inputsPerSec, Math.min(MAX_INPUTS_PER_SEC, faster));
			}
			return new Difficulty(id, name, model, temperature, beam, (int) Math.min(Integer.MAX_VALUE, think), ips,
					gravityCapG, description, personas);
		}
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
	// 1~3단계는 10-04 에 PLAN 7장보다 약 20% 늦췄고(900/500/300ms, 초당 5/8/12 에서), 10-06 에 다섯 단계 모두
	// 생각 지연을 늘렸다(1450/700/400/180/100ms 에서). 생각 지연은 52~125% 늘었고, 블록 하나에 드는 시간
	// (생각 지연 + 6000 / 초당 입력)은 26~37% 늘었다(초당 블록 수로는 21~27% 줄었다).
	private static final List<Difficulty> DEFAULT_DIFFICULTIES = List.of(
			new Difficulty(1, "초급", BASELINE, 0.2, 0, 2200, 5, 2000, "처음 해 보는 분께 알맞은 느긋한 AI", false),
			new Difficulty(2, "중급", BASELINE, 0.1, 0, 1150, 6, 3000, "기본기를 익히는 단계", false),
			new Difficulty(3, "상급", BASELINE, 0.0, 0, 700, 10, 5000, "꾸준히 줄을 지우는 AI", false),
			new Difficulty(4, "최상급", BASELINE, 0.0, 0, 360, 16, 8000, "빠르고 정확한 AI", false),
			new Difficulty(5, "불가능", BASELINE, 0.0, 8, 225, 25, 12000, "다음 블록까지 내다보는 가장 강한 AI", false));

	/**
	 * 게임에서 모델 하나가 쓸 ONNX 연산 스레드 수: 논리 코어의 절반, 많아야 4. 불가능 단계의 2수 탐색처럼 큰 묶음이 약 2.2배
	 * 빨라지고(10-04 측정, 4000개 묶음 96ms → 43ms), 점수는 스레드 수와 상관없이 비트 단위로 같다.
	 */
	public static final int GAME_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

	private final Path dir;
	private final List<Difficulty> difficulties;
	private final List<Persona> personas;
	private final String winprob;
	private final String problem;
	private final int threads;
	// 잠금을 잡고 쓰고, 잠금 없이도 읽는다(이미 불러 본 모델로 고를 때)
	private final Map<Path, PlacementScorer> loaded = new ConcurrentHashMap<>();
	private final Map<Path, String> failures = new ConcurrentHashMap<>(); // 불러오지 못한 모델과 이유 (다시 불러 보지 않는다)
	private volatile boolean winRateTried; // winRate 를 쓴 뒤에 참으로 바꾼다
	private volatile WinRate winRate;
	private volatile String winRateProblem;

	private AiCatalog(Path dir, List<Difficulty> difficulties, List<Persona> personas, String winprob, String problem,
			int threads) {
		this.dir = dir;
		this.difficulties = Collections.unmodifiableList(difficulties);
		this.personas = Collections.unmodifiableList(personas);
		this.winprob = winprob;
		this.problem = problem;
		this.threads = threads;
	}

	/**
	 * {@code dir/manifest.json} 을 읽는다. 모델은 연산 스레드 하나로 불러온다(여러 판을 스레드마다 나눠 돌리는 도구용). 게임은
	 * {@link #load(Path, int)} 에 {@link #GAME_THREADS} 를 준다.
	 */
	public static AiCatalog load(Path dir) {
		return load(dir, 1);
	}

	/**
	 * {@code dir/manifest.json} 을 읽는다. 없거나 읽을 수 없으면 기본값(모두 기준 AI)이고 {@link #problem()} 에 이유가 남는다.
	 *
	 * @param intraOpThreads 모델 하나가 한 번 실행할 때 쓸 연산 스레드 수 (1 이상). 판 하나를 두는 게임은
	 *                       {@link #GAME_THREADS}, 여러 판을 스레드마다 나눠 돌리는 도구는 1
	 */
	public static AiCatalog load(Path dir, int intraOpThreads) {
		int threads = Math.max(1, intraOpThreads);
		Path manifest = dir.resolve(MANIFEST);
		if (!Files.isRegularFile(manifest)) {
			return defaults(dir, "모델 묶음이 없음: " + manifest, threads);
		}
		try {
			JsonNode root = new ObjectMapper().readTree(manifest.toFile());
			if (!FORMAT.equals(root.path("format").asText())) {
				return defaults(dir, "manifest.json 의 format 이 " + FORMAT + " 이 아님", threads);
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
				return defaults(dir, "manifest.json 에 난이도가 없음", threads);
			}
			if (ps.stream().noneMatch(p -> p.model() == null)) {
				ps.add(0, new Persona(NEUTRAL, "중립", null, NEUTRAL_DESCRIPTION));
			}
			JsonNode w = root.get("winprob");
			return new AiCatalog(dir, ds, ps, w == null || w.isNull() ? null : w.asText(), null, threads);
		} catch (IOException | RuntimeException e) {
			return defaults(dir, "manifest.json 을 읽지 못함: " + e.getMessage(), threads);
		}
	}

	private static AiCatalog defaults(Path dir, String problem, int threads) {
		return new AiCatalog(dir, DEFAULT_DIFFICULTIES, List.of(new Persona(NEUTRAL, "중립", null, NEUTRAL_DESCRIPTION)),
				null, problem, threads);
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
	 * 탐색은 난이도를 따른다(GAME.md 6.2). 모델을 쓸 수 없으면 기준 AI 로 대신한다. 모델을 아직 불러오지 않았으면 불러온다
	 * (기다릴 수 있다: 화면 스레드에서는 {@link #chooseIfReady} 를 쓴다).
	 *
	 * @param seed   판 시드. 고르기 난수 스트림을 정한다 ({@link Chooser#rng})
	 * @param player AI 의 자리 (1 또는 2)
	 */
	public Choice choose(Difficulty d, Persona p, long seed, int player) {
		Persona used = d.personas() ? p : personas.get(0); // 이 단계에서는 페르소나를 고를 수 없다 (GAME.md 6.2)
		String model = modelOf(d, used);
		Path path = BASELINE.equals(model) ? null : dir.resolve(model).normalize();
		if (path != null && !tried(path)) {
			synchronized (this) {
				scorer(path);
			}
		}
		return choice(d, used, model, path, seed, player);
	}

	/**
	 * {@link #choose} 와 같은 두뇌를 기다리지 않고 만든다. 그 모델을 이미 불러 봤으면(불러왔거나, 쓸 수 없다고 알거나, 처음부터
	 * 기준 AI 인 단계) 같은 결과를, 아직 불러 보지 않았으면 null 을 돌려준다. 잠금을 잡지 않으므로 다른 스레드가 모델을
	 * 불러오는 중이어도 바로 돌아온다. 화면 스레드가 판을 만들 때 쓴다.
	 */
	public Choice chooseIfReady(Difficulty d, Persona p, long seed, int player) {
		Persona used = d.personas() ? p : personas.get(0);
		String model = modelOf(d, used);
		Path path = BASELINE.equals(model) ? null : dir.resolve(model).normalize();
		return path == null || tried(path) ? choice(d, used, model, path, seed, player) : null;
	}

	/** 불러 본 모델로 두뇌를 만든다 ({@code path} 가 null 이면 처음부터 기준 AI). 잠금 없이 부른다. */
	private Choice choice(Difficulty d, Persona p, String model, Path path, long seed, int player) {
		PlacementScorer scorer = path == null ? null : loaded.get(path);
		String fallback = null;
		if (path != null && scorer == null) {
			fallback = path.getFileName() + " 을 쓸 수 없어 기준 AI 로 대신함: " + failures.get(path);
		}
		if (scorer == null) {
			scorer = new BaselineScorer();
		}
		SplitMix64 rng = Chooser.rng(seed, player);
		Brain brain = new Brain(scorer, d.temperature(), d.beam() > 0 ? new BeamSearch(d.beam()) : null, rng);
		String label = d.name() + " · " + p.name() + (fallback != null || BASELINE.equals(model) ? " (기준 AI)" : "");
		return new Choice(brain, label, fallback);
	}

	/** 그 모델을 이미 불러 봤는가 (불러왔거나 쓸 수 없다고 안다). 잠금 없이 읽는다. */
	private boolean tried(Path path) {
		return loaded.containsKey(path) || failures.containsKey(path);
	}

	/** 그 난이도·페르소나가 쓸 모델 파일, 또는 {@link #BASELINE}. 페르소나를 고를 수 없는 단계면 난이도의 모델이다. */
	private String modelOf(Difficulty d, Persona p) {
		Persona used = d.personas() ? p : personas.get(0);
		return used.model() != null ? used.model() : d.model();
	}

	/**
	 * 불러 둔 모델. 처음이면 불러 둔다. 쓸 수 없으면 null 이고 이유는 {@code failures} 에 남는다(다시 불러 보지 않는다).
	 * 잠금을 잡고 부른다.
	 */
	private PlacementScorer scorer(Path path) {
		PlacementScorer s = loaded.get(path);
		if (s != null || failures.containsKey(path)) {
			return s;
		}
		try {
			s = OnnxScorer.load(path, threads);
			loaded.put(path, s);
		} catch (Exception | LinkageError e) {
			failures.put(path, String.valueOf(e.getMessage()));
			System.err.println("모델을 불러오지 못함: " + path + " (" + e + ")");
		}
		return s;
	}

	/**
	 * 그 난이도·페르소나로 고르면 기준 AI 로 대신하게 되는 이유, 모델을 쓸 수 있거나 처음부터 기준 AI 인 단계면 null. 모델을
	 * 아직 불러오지 않았으면 불러 본다. 메뉴의 모델 상태 표시용.
	 */
	public synchronized String modelProblem(Difficulty d, Persona p) {
		String model = modelOf(d, p);
		if (BASELINE.equals(model)) {
			return null;
		}
		Path path = dir.resolve(model).normalize();
		return scorer(path) != null ? null : path.getFileName() + ": " + failures.get(path);
	}

	/**
	 * 모든 난이도·페르소나의 모델과 승률 예측망을 미리 불러 본다. 게임을 켤 때 배경 스레드에서 부르면 판을 시작할 때 모델을
	 * 불러오느라 기다리지 않고, 쓸 수 없는 모델을 미리 알 수 있다({@link #modelProblem}, {@link #winRateProblem()}). 여러 번
	 * 불러도 된다. 쓸 수 없는 모델이 하나라도 있으면 true.
	 */
	public boolean preload() {
		boolean bad = false;
		for (Difficulty d : difficulties) {
			for (Persona p : d.personas() ? personas : personas.subList(0, 1)) {
				bad |= modelProblem(d, p) != null;
			}
		}
		winRate();
		return bad || winRateProblem() != null;
	}

	/**
	 * 승률 예측망. 처음 부를 때 한 번 불러 두고 판마다 같이 쓴다(여러 스레드가 함께 써도 된다). 묶음에 없거나 쓸 수 없으면 null
	 * 이고, 쓸 수 없는 이유는 {@link #winRateProblem()} 에 남는다. 닫기는 이 카탈로그가 한다({@link #close()}). 이미 불러
	 * 봤으면({@link #winRateReady()}) 잠금 없이 바로 돌아온다.
	 */
	public WinRate winRate() {
		if (winRateTried) {
			return winRate;
		}
		synchronized (this) {
			if (!winRateTried) {
				Path path = winprob();
				if (path != null) {
					try {
						winRate = WinRate.load(path, 1);
					} catch (Exception | LinkageError e) {
						winRateProblem = path.getFileName() + ": " + e.getMessage();
						System.err.println("승률 예측망을 불러오지 못함: " + path + " (" + e + ")");
					}
				}
				winRateTried = true;
			}
			return winRate;
		}
	}

	/** 승률 예측망을 이미 불러 봤는가 (그러면 {@link #winRate()} 가 기다리지 않는다). 잠금 없이 읽는다. */
	public boolean winRateReady() {
		return winRateTried;
	}

	/** 승률 예측망이 묶음에 있는데 쓸 수 없으면 그 이유, 아니면(없거나, 쓸 수 있거나, 아직 불러 보지 않음) null. */
	public String winRateProblem() {
		return winRateProblem;
	}

	@Override
	public synchronized void close() {
		loaded.values().forEach(PlacementScorer::close);
		loaded.clear();
		if (winRate != null) {
			winRate.close();
			winRate = null;
		}
	}
}
