package kr.ac.jbnu.se.tetris.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;
import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * AI 의 배치 고르기 (docs/TRAINING.md 6장): 후보마다 애프터스테이트를 만들어 점수를 매기고, 온도 τ 로 고른다. 2수 탐색을
 * 주면 펼친 후보 중에서 탐색 값으로 고른다(6.2). 학습 대전({@code train.MatchRunner})과 게임의 AI 조작기
 * ({@link AiController})가 같은 코드를 쓴다.
 */
public final class Brain {

	/** 고른 결과. 모든 배치가 락 아웃이면 {@code after} 가 null, {@code n} 이 0, {@code rank} 가 -1 이다. */
	public static final class Decision {
		private final Placement placement;
		private final Afterstate after;
		private final int n;
		private final int rank;

		Decision(Placement placement, Afterstate after, int n, int rank) {
			this.placement = placement;
			this.after = after;
			this.n = n;
			this.rank = rank;
		}

		public Placement placement() {
			return placement;
		}

		public Afterstate after() {
			return after;
		}

		/** 점수를 매긴 후보 수 (락 아웃이 아닌 배치). */
		public int n() {
			return n;
		}

		/** 고른 배치의 점수 순위 (0 = 1등). 탐색을 쓰면 펼친 후보 안에서 센다. */
		public int rank() {
			return rank;
		}
	}

	private final PlacementScorer scorer;
	private final double temperature;
	private final BeamSearch search;
	private final SplitMix64 rng;

	/**
	 * @param temperature 고르기 온도 τ (0 이면 언제나 1등)
	 * @param search      2수 탐색, null 이면 1수만 본다
	 * @param rng         고르기 난수 ({@link Chooser#rng(long, int)})
	 */
	public Brain(PlacementScorer scorer, double temperature, BeamSearch search, SplitMix64 rng) {
		this.scorer = scorer;
		this.temperature = temperature;
		this.search = search;
		this.rng = rng;
	}

	/** 후보 {@code all}(보통 {@link GameModel#placements()} 나 {@link GameModel#placementsFrom}) 중에서 고른다. */
	public Decision decide(GameModel g, Map<String, Placement> all, Opponent view) {
		return decide(g, all, view, scorer, temperature, search, rng);
	}

	public PlacementScorer scorer() {
		return scorer;
	}

	/** 고르기 온도 τ. 0 이면 고르기 난수를 쓰지 않는다. */
	public double temperature() {
		return temperature;
	}

	/**
	 * 점수 매기기만 바꾼 두뇌. 온도와 탐색은 그대로이고 고르기 난수는 같은 객체를 이어 쓴다. 게임 도중 모델이 오류를 내면
	 * 기준 AI 로 바꿀 때 쓴다({@link AiController}).
	 */
	public Brain withScorer(PlacementScorer other) {
		return new Brain(other, temperature, search, rng);
	}

	/**
	 * 후보를 정규화 키의 사전 순으로 늘어놓고(동점 처리, TRAINING.md 6장) 락 아웃이 아닌 것에만 점수를 매겨 고른다.
	 * 탐색을 쓰면 펼친 후보 중에서 탐색 값으로 고르고, 순위도 그 안에서 센다(TRAINING.md 6.2).
	 * 모든 배치가 락 아웃이면 키 순으로 첫 배치를 두어 규칙대로 진다(TRAINING.md 7.3).
	 */
	public static Decision decide(GameModel g, Map<String, Placement> all, Opponent view, PlacementScorer scorer,
			double temperature, BeamSearch search, SplitMix64 rng) {
		if (all.isEmpty()) {
			throw new IllegalStateException("둘 수 있는 배치가 없음 (게임이 끝났나?)");
		}
		List<String> keys = new ArrayList<>(all.keySet());
		Collections.sort(keys);
		AfterstateBuilder.Context ctx = AfterstateBuilder.contextOf(g);
		Grid grid = g.grid();
		List<Placement> candidates = new ArrayList<>();
		List<Afterstate> states = new ArrayList<>();
		for (String k : keys) {
			Afterstate a = AfterstateBuilder.build(grid, all.get(k), ctx);
			if (a != null) {
				candidates.add(all.get(k));
				states.add(a);
			}
		}
		if (states.isEmpty()) {
			return new Decision(all.get(keys.get(0)), null, 0, -1);
		}
		double[] scores = scorer.score(states, view);
		if (search == null) {
			int i = Chooser.choose(scores, temperature, rng);
			return new Decision(candidates.get(i), states.get(i), states.size(), Chooser.rank(scores, i));
		}
		BeamSearch.Result r = search.search(states, scores, g.pendingLines(), view, scorer).withoutLosses();
		double[] values = r.values();
		int k = Chooser.choose(values, temperature, rng);
		int i = r.candidate(k);
		return new Decision(candidates.get(i), states.get(i), states.size(), Chooser.rank(values, k));
	}
}
