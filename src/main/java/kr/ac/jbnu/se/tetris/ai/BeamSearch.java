package kr.ac.jbnu.se.tetris.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import kr.ac.jbnu.se.tetris.core.GarbageQueue;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Locking;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;

/**
 * 다음 블록까지 보는 2수 탐색 (docs/TRAINING.md 6.2, PLAN 7장 레벨 5).
 * <p>
 * 1수 점수가 높은 후보 {@code beam} 개만 펼친다. 후보마다 다음 블록(대기열의 첫 블록, 또는 홀드)의 모든 배치를 두어 보고
 * 그 점수의 최댓값을 후보의 값으로 한다. 다음 블록을 둘 수 없으면(가비지 아웃, 블록 아웃, 모든 배치가 락 아웃) 그 후보의
 * 값은 {@link #LOSS} 다.
 * <p>
 * 결정하는 순간 보이지 않는 정보는 쓰지 않는다. 입력이 애프터스테이트와 상대 상태뿐이라 구조상 쓸 수도 없다.
 * <ul>
 * <li>둘째 수의 대기열에는 새로 보일 블록(지금 미리보기 다음 블록)이 없다. 그 칸은 빈 채로 모델에 넣는다.</li>
 * <li>첫 수 뒤에 가비지가 올라오면 구멍 열을 모르므로 10 열을 같은 확률로 보고 값을 평균한다. 한 번에 올라오는 줄은 모두
 * 같은 열에 구멍이 있다고 본다(실제로는 받은 공격마다 열이 다르다, RULES 5.4).</li>
 * <li>상대는 결정 시점 그대로 두고 첫 수의 송신량만 상대의 대기 가비지에 더한다. 상대가 이번 턴에 두는 수는 모른다.</li>
 * </ul>
 * 첫 수의 즉시 보상 R 은 값에 들어가지 않는다(점수 모델이 R 을 따로 내지 않는다). 보조 보상이 끝난 중립 AI 는 R = 0 이라
 * 영향이 없다. 상태가 없어 여러 스레드가 함께 써도 된다.
 */
public final class BeamSearch {

	/** 다음 블록을 둘 수 없어 지는 후보의 값. 어떤 점수보다도 낮다. */
	public static final double LOSS = -1000;

	private static final int[] ALL_HOLES = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
	private static final int[] NO_RISE = { -1 };

	/** 펼친 후보와 그 값. */
	public static final class Result {
		private final int[] candidates;
		private final double[] values;

		Result(int[] candidates, double[] values) {
			this.candidates = candidates;
			this.values = values;
		}

		/** 펼친 후보의 수. */
		public int size() {
			return candidates.length;
		}

		/** k 번째로 펼친 후보의 1수 후보 위치. 위치의 오름차순(정규화 키 순서)이다. */
		public int candidate(int k) {
			return candidates[k];
		}

		/** 펼친 후보의 값. {@link #candidate(int)} 와 같은 순서다. 복사본이다. */
		public double[] values() {
			return values.clone();
		}

		/**
		 * 지는 후보({@link #LOSS})를 뺀 결과. 모두 지는 후보면 그대로다. 온도 τ > 0 으로 고를 때 −1000 이 표준편차를 키워
		 * 나머지 후보의 확률이 고르게 퍼지지 않게 한다(TRAINING.md 6.2). τ = 0 이면 고르는 후보는 같다.
		 */
		public Result withoutLosses() {
			int keep = 0;
			for (double v : values) {
				keep += v > LOSS ? 1 : 0;
			}
			if (keep == 0 || keep == values.length) {
				return this;
			}
			int[] c = new int[keep];
			double[] v = new double[keep];
			for (int i = 0, j = 0; i < values.length; i++) {
				if (values[i] > LOSS) {
					c[j] = candidates[i];
					v[j++] = values[i];
				}
			}
			return new Result(c, v);
		}
	}

	/** 같은 상대 상태로 한꺼번에 점수를 매길 둘째 수 애프터스테이트들. */
	private static final class Batch {
		final Opponent opponent;
		final List<Afterstate> states = new ArrayList<>();
		final List<int[]> segments = new ArrayList<>(); // {펼친 후보 번호, 시작, 끝}

		Batch(Opponent opponent) {
			this.opponent = opponent;
		}
	}

	private final int beam;

	/** @param beam 펼칠 후보 수. 1 이상 */
	public BeamSearch(int beam) {
		if (beam < 1) {
			throw new IllegalArgumentException("beam 은 1 이상이어야 함: " + beam);
		}
		this.beam = beam;
	}

	public int beam() {
		return beam;
	}

	/**
	 * @param states        1수 후보의 애프터스테이트 (락 아웃 제외). 비어 있으면 안 된다
	 * @param scores        scorer 가 states 에 매긴 1수 점수
	 * @param pendingBefore 결정 시점(상쇄 전)의 내 대기 가비지 줄 수의 합. 첫 수의 송신량을 계산한다
	 * @param opponent      결정 시점의 상대 상태
	 * @param scorer        1수 점수를 매긴 것과 같은 점수 모델
	 * @return 1수 점수가 높은 순으로 고른 beam 개(같으면 앞선 후보)를 위치 순으로 늘어놓은 것과 그 값
	 */
	public Result search(List<Afterstate> states, double[] scores, int pendingBefore, Opponent opponent,
			PlacementScorer scorer) {
		int n = states.size();
		if (n == 0 || scores.length != n) {
			throw new IllegalArgumentException("후보 " + n + "개, 점수 " + scores.length + "개");
		}
		Integer[] order = new Integer[n];
		for (int i = 0; i < n; i++) {
			order[i] = i;
		}
		Arrays.sort(order, (a, b) -> scores[a] != scores[b] ? Double.compare(scores[b], scores[a]) : Integer.compare(a, b));
		int k = Math.min(beam, n);
		int[] picked = new int[k];
		for (int j = 0; j < k; j++) {
			picked[j] = order[j];
		}
		Arrays.sort(picked);

		// 둘째 수를 첫 수의 송신량(곧 상대 상태)별로 모아 모델을 한 번씩만 부른다.
		Map<Integer, Batch> batches = new TreeMap<>();
		for (int j = 0; j < k; j++) {
			Afterstate first = states.get(picked[j]);
			int sent = Math.max(0, first.attack() - pendingBefore);
			Batch batch = batches.computeIfAbsent(sent, s -> new Batch(opponent.withPending(opponent.pending() + s)));
			int rise = first.lines() == 0 ? Math.min(GarbageQueue.MAX_RISE, first.pending()) : 0;
			for (int hole : rise > 0 ? ALL_HOLES : NO_RISE) {
				int from = batch.states.size();
				batch.states.addAll(nextStates(first, rise, hole));
				batch.segments.add(new int[] { j, from, batch.states.size() });
			}
		}
		double[] sum = new double[k];
		int[] count = new int[k];
		for (Batch batch : batches.values()) {
			double[] s = batch.states.isEmpty() ? new double[0] : scorer.score(batch.states, batch.opponent);
			for (int[] seg : batch.segments) {
				double best = LOSS;
				for (int i = seg[1]; i < seg[2]; i++) {
					best = Math.max(best, s[i]);
				}
				sum[seg[0]] += best;
				count[seg[0]]++;
			}
		}
		double[] values = new double[k];
		for (int j = 0; j < k; j++) {
			values[j] = sum[j] / count[j];
		}
		return new Result(picked, values);
	}

	/**
	 * 첫 수 뒤에 다음 블록을 둔 애프터스테이트들 (RULES 7장 8~9단계를 거친 뒤의 배치). 다음 블록을 둘 수 없으면 빈 목록이다.
	 *
	 * @param rise 첫 수 뒤에 올라오는 가비지 줄 수
	 * @param hole rise 가 0 보다 크면 그 줄들의 구멍 열
	 */
	static List<Afterstate> nextStates(Afterstate first, int rise, int hole) {
		Grid grid = first.board();
		for (int i = 0; i < rise; i++) {
			if (grid.insertGarbageRow(hole)) {
				return List.of(); // 가비지 아웃
			}
		}
		List<PieceType> queue = first.queue();
		PieceType current = queue.get(0);
		if (Locking.isBlockOut(grid, current)) {
			return List.of();
		}
		PieceType hold = first.hold();
		List<PieceType> preview = queue.subList(1, queue.size());
		// GameModel.placements 와 같다: 홀드가 비었으면 미리보기 첫 블록을, 차 있으면 홀드 블록을 꺼내 놓는다.
		Map<String, Placement> all = new LinkedHashMap<>(PlacementFinder.find(grid, PieceState.spawn(current), false));
		PieceType holdOut = hold != null ? hold : preview.isEmpty() ? null : preview.get(0);
		if (hold != current && holdOut != null) {
			all.putAll(PlacementFinder.find(grid, PieceState.spawn(holdOut), true));
		}
		AfterstateBuilder.Context ctx = new AfterstateBuilder.Context(current, hold, preview, first.combo(), first.b2b(),
				first.pending() - rise);
		List<Afterstate> out = new ArrayList<>(all.size());
		for (Placement p : all.values()) {
			Afterstate a = AfterstateBuilder.build(grid, p, ctx);
			if (a != null) {
				out.add(a);
			}
		}
		return out;
	}
}
