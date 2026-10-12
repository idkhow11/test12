package kr.ac.jbnu.se.tetris.sand.ai;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 모래 AI 를 창 없이 가상 시간으로 두며 잰다: 단계마다 몇 판씩, 판마다 시간 상한까지(또는 질 때까지). 판은 모래 1 단계(또는
 * {@code -Dsand.start} 단계)에서 시작해 30초마다 빨라진다. 잰 것:
 * <ul>
 * <li>버틴 시간, 판당 점수, 분당 점수·지우기·지운 알갱이·블록, 가장 높은 콤보 배율(연쇄)</li>
 * <li>지우기 효율: 지운 알갱이 ÷ 떨어뜨린 알갱이(블록 × 256). 100% 보다 낮으면 그만큼 판에 모래가 쌓인다(보이는 판은 알갱이
 * 약 1.3만 개를 담는다)</li>
 * <li>정착한 모래 높이(1초마다, 칸), 다음 블록이 스폰 자리의 모래를 기다린 시간의 비율, 블록당 조작 수</li>
 * <li>목표에 닿은 뒤 판의 모래가 멈추기를 기다린 블록의 비율과 평균 시간, 실행 실패율(목표를 바꾼 블록)</li>
 * <li>결정 시간: 진행 스레드에서 그 자리에서 고를 때의 계산 시간 p50·p95·최대 (게임은 일꾼 스레드가 생각 지연 동안 계산한다)</li>
 * </ul>
 * 실행: {@code gradlew sandBench --args="0-5 12 240 6"}, 또는 (시험 클래스까지 빌드한 뒤, Windows 는 경로 구분이 {@code ;}):
 *
 * <pre>
 * java -Djava.awt.headless=true -Dtetris.sound=off -Dtetris.config=off -Dtetris.records=off -Dstdout.encoding=UTF-8 -cp build/classes/java/main;build/classes/java/test kr.ac.jbnu.se.tetris.sand.ai.SandAiBench [단계] [판 수] [초] [스레드]
 * </pre>
 * 단계는 {@code 1-5}, {@code 3}, {@code 1,3,5} 처럼 쓰고 {@code 0} 은 아무 데나 두는 비교 기준(같은 조작기와 상급 속도, 고르기만
 * 무작위)이다. 기본은 {@code 0-5} 단계, 단계마다 4판, 180초, 스레드 1 (스레드를 늘리면 빨리 끝나지만 결정 시간이 부풀 수
 * 있다). 가중치를 바꿔 보려면 {@code -Dsand.weights=1,-0.5,...} (특징 {@link SandEval#FEATURES}개), 두뇌 설정은
 * {@code -Dsand.temperature}, {@code -Dsand.hold}, {@code -Dsand.lookahead} 로 모든 단계에 덮어쓴다. 조작 속도는
 * {@link SandAiLevel#LEVELS} 의 기본값이고, {@code -Dsand.catalog=models} 를 주면 그 폴더의 모델 묶음(메뉴가 고르는 고전 단계)의
 * 생각 지연과 초당 입력을 쓴다({@link SandAiLevel#of(AiCatalog.Difficulty)}, 화면에 붙일 때와 같다). 시드는 {@code 990001}
 * 부터.
 */
public final class SandAiBench {

	private static final long SEED = 990_001;

	private SandAiBench() {
	}

	/** 한 판의 결과. {@code heightSum} 은 1초마다 잰 정착한 모래 높이(알갱이)의 합, {@code samples} 는 잰 횟수. */
	record Game(long seed, boolean over, long timeMs, long score, int clears, int grains, int pieces, int maxMultiplier,
			int planned, int failed, long inputs, long waitMs, long heightSum, int samples, int maxHeight, int paced,
			long pacedMs, List<Double> decideMs) {
	}

	public static void main(String[] args) throws Exception {
		List<Integer> levels = parseLevels(args.length > 0 ? args[0] : "0-5");
		int games = args.length > 1 ? Integer.parseInt(args[1]) : 4;
		int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 180;
		int threads = args.length > 3 ? Integer.parseInt(args[3]) : 1;
		int start = Integer.getInteger("sand.start", 1);
		SandEval.Weights weights = weightsFromProperty();
		System.out.println("가중치 " + weights + ", 판 " + games + "개 × 최대 " + seconds + "초, 시작 단계 " + start
				+ ", 스레드 " + threads);
		warmUp(weights);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int level : levels) {
				List<Future<Game>> fs = new ArrayList<>();
				for (int k = 0; k < games; k++) {
					long seed = SEED + k;
					fs.add(pool.submit(() -> play(level, weights, seed, start, seconds * 1000L)));
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

	/** 단계 {@code level}(0 이면 무작위 비교 기준)로 한 판. 고르기는 진행 스레드에서 그 자리에서 한다(시간을 재려고). */
	static Game play(int level, SandEval.Weights weights, long seed, int startLevel, long limitMs) {
		SandAiLevel lv = level(level == 0 ? 3 : level);
		SandBrain brain = level == 0 ? SandBrain.random(lv) : new SandBrain(lv, weights);
		SandGame g = new SandGame(seed, startLevel, 0);
		SandAiController ai = new SandAiController(g, brain, lv.thinkMs(), lv.inputsPerSec(),
				SandAiController.rng(seed, 1));
		List<Double> ms = new ArrayList<>();
		int seen = 0;
		long inputs = 0;
		long waitMs = 0;
		long heightSum = 0;
		int samples = 0;
		int maxHeight = 0;
		while (!g.over() && g.time() < limitMs) {
			Input in = ai.poll();
			if (in != null && g.piece() != null) {
				inputs++;
			}
			g.advance(in);
			if (g.waiting()) {
				waitMs++;
			}
			if (g.time() % 1000 == 0) {
				int h = SandRules.HEIGHT - g.settledTop();
				heightSum += h;
				samples++;
				maxHeight = Math.max(maxHeight, h);
			}
			if (ai.planned() != seen) {
				seen = ai.planned();
				ms.add(ai.plan().nanos() / 1e6);
			}
		}
		return new Game(seed, g.over(), g.time(), g.score(), g.clears(), g.grainsCleared(), g.pieces(),
				g.maxMultiplier(), ai.planned(), ai.failed(), inputs, waitMs, heightSum, samples, maxHeight, ai.paced(),
				ai.pacedMs(), ms);
	}

	/** 단계의 기본값(또는 {@code -Dsand.catalog} 모델 묶음의 속도)에 {@code -Dsand.*} 덮어쓰기를 한 것. */
	static SandAiLevel level(int id) {
		String catalog = System.getProperty("sand.catalog");
		SandAiLevel lv = catalog == null || catalog.isBlank() ? SandAiLevel.of(id)
				: SandAiLevel.of(AiCatalog.load(Path.of(catalog)).difficulties().get(id - 1));
		return lv.with(Double.parseDouble(System.getProperty("sand.temperature", String.valueOf(lv.temperature()))),
				Boolean.parseBoolean(System.getProperty("sand.hold", String.valueOf(lv.hold()))),
				Integer.getInteger("sand.lookahead", lv.lookahead()));
	}

	static SandEval.Weights weightsFromProperty() {
		String s = System.getProperty("sand.weights");
		if (s == null || s.isBlank()) {
			return SandEval.Weights.DEFAULT;
		}
		String[] parts = s.split(",");
		double[] w = new double[parts.length];
		for (int i = 0; i < parts.length; i++) {
			w[i] = Double.parseDouble(parts[i].trim());
		}
		return new SandEval.Weights(w);
	}

	/** JIT 데우기: 짧은 판 하나 (결과는 버린다). */
	private static void warmUp(SandEval.Weights weights) {
		play(5, weights, 1, 1, 20_000);
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
		double minutes = 0;
		long score = 0;
		long clears = 0;
		long grains = 0;
		long pieces = 0;
		int overs = 0;
		int maxMul = 0;
		double sumMul = 0;
		long planned = 0;
		long failed = 0;
		long inputs = 0;
		long waitMs = 0;
		long heightSum = 0;
		long samples = 0;
		int maxHeight = 0;
		long paced = 0;
		long pacedMs = 0;
		List<Double> ms = new ArrayList<>();
		List<Long> survival = new ArrayList<>();
		for (Game g : games) {
			minutes += g.timeMs() / 60_000.0;
			score += g.score();
			clears += g.clears();
			grains += g.grains();
			pieces += g.pieces();
			overs += g.over() ? 1 : 0;
			maxMul = Math.max(maxMul, g.maxMultiplier());
			sumMul += g.maxMultiplier();
			planned += g.planned();
			failed += g.failed();
			inputs += g.inputs();
			waitMs += g.waitMs();
			heightSum += g.heightSum();
			samples += g.samples();
			maxHeight = Math.max(maxHeight, g.maxHeight());
			paced += g.paced();
			pacedMs += g.pacedMs();
			ms.addAll(g.decideMs());
			survival.add(g.timeMs());
		}
		Collections.sort(ms);
		Collections.sort(survival);
		double cell = SandRules.GRAIN;
		SandAiLevel lv = level(level == 0 ? 3 : level);
		String name = level == 0 ? "0 무작위" : level + " " + lv.name();
		System.out.printf(Locale.ROOT,
				"%-7s 판 %d (짐 %d), 버틴 시간 평균 %.0f초(최소 %.0f), 판당 점수 %.0f, 분당 점수 %.0f, 지우기 %.1f, 알갱이 %.0f, "
						+ "블록 %.1f, 지우기 효율 %.0f%%, 최고 배율 평균 %.1f(최대 %d), 높이 평균 %.1f칸(최대 %.1f), 기다림 %.1f%%, "
						+ "블록당 조작 %.2f, 모래 기다린 블록 %.1f%%(평균 %.0fms), 실행 실패율 %.2f%%, 결정 ms p50 %.2f p95 %.2f "
						+ "최대 %.2f (생각 %dms, 초당 %.0f, 2수 %d)%n",
				name, games.size(), overs, minutes * 60 / games.size(), survival.get(0) / 1000.0,
				score / (double) games.size(), score / minutes, clears / minutes, grains / minutes, pieces / minutes,
				pieces == 0 ? 0 : 100.0 * grains / (pieces * (double) SandRules.PIECE_GRAINS), sumMul / games.size(), maxMul,
				samples == 0 ? 0 : heightSum / (double) samples / cell, maxHeight / cell,
				100.0 * waitMs / (minutes * 60_000), pieces == 0 ? 0 : inputs / (double) pieces,
				planned == 0 ? 0 : 100.0 * paced / planned, paced == 0 ? 0 : pacedMs / (double) paced,
				planned == 0 ? 0 : 100.0 * failed / planned, at(ms, 0.5), at(ms, 0.95),
				ms.isEmpty() ? 0 : ms.get(ms.size() - 1), lv.thinkMs(), lv.inputsPerSec(), lv.lookahead());
	}

	private static double at(List<Double> sorted, double q) {
		return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * sorted.size())));
	}
}
