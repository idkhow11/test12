package kr.ac.jbnu.se.tetris.ai;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * AI 결정 시간을 잰다 (docs/GAME.md 5장, PLAN 7장의 "고수 결정 p95 ≤ 20ms"). {@code models/} 의 단계 a(P1) 대 b(P2)를
 * 가상 시간 모드로 두면서, P1 의 {@link AiController#poll()} 중 배치를 고른(생각이 끝난 틱, 재결정) 호출만 실제 시간을 잰다.
 * 승률 예측망 한 번의 계산 시간도 함께 잰다. 실행:
 * {@code java -cp <시험 클래스 경로> kr.ac.jbnu.se.tetris.ai.DecisionBench [a] [b] [판 수] [연산 스레드] [prefetch]}.
 * 연산 스레드는 모델 하나가 쓰는 ONNX 스레드 수다(기본 1, 게임은 {@link AiCatalog#GAME_THREADS}). 끝에 {@code prefetch} 를
 * 주면 게임처럼 실제 시간에 맞춰 두고(한 판에 60초까지) 두 AI 가 생각 지연 동안 일꾼 스레드에서 미리 고른다. 그때 재는 것은
 * 결정 틱에 진행 스레드가 기다린 시간이다(미리 고른 답이 맞으면 거의 0).
 */
public final class DecisionBench {

	private static final long LIMIT_MS = 4 * 60_000;
	private static final long PACED_LIMIT_MS = 60_000;

	private DecisionBench() {
	}

	public static void main(String[] args) throws Exception {
		int a = args.length > 0 ? Integer.parseInt(args[0]) : 5;
		int b = args.length > 1 ? Integer.parseInt(args[1]) : 5;
		int games = args.length > 2 ? Integer.parseInt(args[2]) : 4;
		int threads = args.length > 3 ? Integer.parseInt(args[3]) : 1;
		boolean paced = args.length > 4 && "prefetch".equals(args[4]);
		Path models = Path.of(System.getProperty("tetris.models", "models"));
		ExecutorService pool1 = paced ? Executors.newSingleThreadExecutor() : null;
		ExecutorService pool2 = paced ? Executors.newSingleThreadExecutor() : null;
		try (AiCatalog catalog = AiCatalog.load(models, threads)) {
			if (catalog.problem() != null) {
				System.out.println("경고: " + catalog.problem());
			}
			WinRate win = catalog.winprob() == null ? null : WinRate.load(catalog.winprob(), 1);
			AiCatalog.Difficulty da = catalog.difficulties().get(a - 1);
			AiCatalog.Difficulty db = catalog.difficulties().get(b - 1);
			AiCatalog.Persona neutral = catalog.personas().get(0);
			List<Double> decide = new ArrayList<>();
			List<Double> winMs = new ArrayList<>();
			List<Double> scoring = new ArrayList<>();
			List<Double> states = new ArrayList<>();
			long planned = 0;
			long prefetched = 0;
			long seed = 970001;
			for (int k = 0; k < games + 1; k++) { // 첫 판은 데우기(JIT, ONNX 첫 실행)로 버린다
				TimedMatch m = new TimedMatch(seed + k, 2, GameModel.Scheduler.NONE,
						Math.max(da.gravityCapG(), db.gravityCapG()));
				Brain b1 = catalog.choose(da, neutral, seed + k, 1).brain();
				TimedScorer timed = new TimedScorer(b1.scorer());
				AiController p1 = new AiController(m.player(0), () -> Opponent.of(m.player(1).game()),
						new Brain(paced ? b1.scorer() : timed, da.temperature(),
								da.beam() > 0 ? new BeamSearch(da.beam()) : null, Chooser.rng(seed + k, 1)),
						da.thinkMs(), da.inputsPerSec());
				AiController p2 = new AiController(m.player(1), () -> Opponent.of(m.player(0).game()),
						catalog.choose(db, neutral, seed + k, 2).brain(), db.thinkMs(), db.inputsPerSec());
				p1.prefetchWith(pool1);
				p2.prefetchWith(pool2);
				long start = System.nanoTime();
				while (!m.isOver() && m.time() < (paced ? PACED_LIMIT_MS : LIMIT_MS)) {
					// 게임 진행 스레드처럼: 판의 시계가 실제 시간을 앞서면 잔다(늦었으면 따라잡는다)
					while (paced && (System.nanoTime() - start) / 1_000_000 < m.time()) {
						Thread.sleep(1);
					}
					int before = p1.planned() + p1.replans();
					long t0 = System.nanoTime();
					var in1 = p1.poll();
					long dt = System.nanoTime() - t0;
					if (k > 0 && p1.planned() + p1.replans() != before) {
						decide.add(dt / 1e6);
						scoring.add(timed.nanos / 1e6);
						states.add((double) timed.states);
					}
					timed.nanos = 0;
					timed.states = 0;
					m.tick(in1, p2.poll());
					if (win != null && k > 0 && (m.lastLock(0) != null || m.lastLock(1) != null) && !m.isOver()) {
						long w0 = System.nanoTime();
						win.p1Win(m.player(0).game(), m.player(1).game());
						winMs.add((System.nanoTime() - w0) / 1e6);
					}
				}
				if (k > 0) {
					planned += p1.planned();
					prefetched += p1.prefetched();
				}
				System.out.printf(Locale.ROOT, "판 %d: %s, %.0f초, P1 블록 %d%n", k, m.isOver() ? m.result() : "LIMIT",
						m.time() / 1000.0, m.player(0).game().pieces());
			}
			System.out.println(da.name() + " 대 " + db.name() + ", 연산 스레드 " + threads + (paced ? ", 실제 시간·미리 고르기" : ""));
			if (paced) {
				report("결정 틱에 진행 스레드가 기다린 시간 (ms)", decide);
				System.out.printf(Locale.ROOT, "미리 고른 답을 쓴 블록: %d / %d (%.1f%%)%n", prefetched, planned,
						planned == 0 ? 0 : 100.0 * prefetched / planned);
			} else {
				report(da.name() + " 결정 (ms)", decide);
				report("그중 점수 매기기 (ms)", scoring);
				report("점수 매긴 상태 수", states);
			}
			report("승률 예측 (ms)", winMs);
			if (win != null) {
				win.close();
			}
		} finally {
			for (ExecutorService pool : new ExecutorService[] { pool1, pool2 }) {
				if (pool != null) {
					pool.shutdownNow();
				}
			}
		}
	}

	/** 점수 매기기에 든 시간과 상태 수를 센다. */
	private static final class TimedScorer implements PlacementScorer {
		private final PlacementScorer inner;
		long nanos;
		long states;

		TimedScorer(PlacementScorer inner) {
			this.inner = inner;
		}

		@Override
		public double[] score(List<Afterstate> candidates, Opponent opponent) {
			long t0 = System.nanoTime();
			double[] out = inner.score(candidates, opponent);
			nanos += System.nanoTime() - t0;
			states += candidates.size();
			return out;
		}

		@Override
		public String name() {
			return inner.name();
		}
	}

	private static void report(String name, List<Double> xs) {
		if (xs.isEmpty()) {
			System.out.println(name + ": 없음");
			return;
		}
		List<Double> s = new ArrayList<>(xs);
		Collections.sort(s);
		double sum = 0;
		for (double x : s) {
			sum += x;
		}
		System.out.printf(Locale.ROOT, "%s: %d번, 평균 %.2f, 중앙 %.2f, p95 %.2f, p99 %.2f, 최대 %.2f%n", name, s.size(),
				sum / s.size(), at(s, 0.5), at(s, 0.95), at(s, 0.99), s.get(s.size() - 1));
	}

	private static double at(List<Double> sorted, double q) {
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * sorted.size())));
	}
}
