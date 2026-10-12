package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import kr.ac.jbnu.se.tetris.ai.Chooser;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandPiece;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 모래 AI 의 두뇌: 블록 하나를 어디에 놓을지 고른다 (규칙 기반, 학습 없음). {@link Situation} 과 난수 값 하나만 보고 고르므로
 * 같은 입력이면 언제나 같은 답이다. 상태가 없어 여러 스레드가 함께 써도 된다.
 * <ol>
 * <li><b>멈춘 판:</b> 지금 판에서 앞 블록의 모래와 반짝이는 덩어리가 모두 정리되어 멈춘 판을 만든다. 블록은 이 판 위에 놓는다고
 * 보고 고르고, 조작기는 목표에 닿은 뒤 판의 모래가 아직 움직이면 멈출 때까지 기다렸다가 떨어뜨린다({@link SandAiController}).
 * 움직이는 모래 위에 떨어뜨리면 예상과 다르게 쌓여(모래 탑) 빠른 단계가 스스로 판을 메우기 때문이다(10-06 벤치마크: 불가능 12판
 * 중 3판이 졌다가 0판으로. 규칙 sand-0.2 에서는 판 전체를 기다려야 했다: 10-07, 불가능 8판 모두 짐 → 0판).</li>
 * <li><b>목표에 닿는 순간의 판:</b> 블록은 생각 지연 + 조작 수 × 입력 간격 뒤에 목표에 닿는다. 그때의 판을 그 게임 단계의 모래
 * 스텝 간격대로 엔진 규칙({@link SandField#step})으로 흘려 조작 수(0~{@value #MAX_PATH})마다 미리 만들고({@link Timeline}),
 * 그때 놓일 자리가 멈춘 판의 자리보다 높은 만큼(그만큼 기다려야 한다) 깎는다({@link SandEval#RISE}).</li>
 * <li><b>후보:</b> 지금 블록(과 홀드를 쓰면 홀드로 꺼낼 블록)을 지금 칸 판에서 {@code L R CW CCW} 로 옮겨 닿을 수 있는 회전·박스
 * 열마다({@link Reach}) 멈춘 판에서 하드드롭한 자리. 놓일 칸이 같은 후보는 조작이 적은 하나만 남긴다.</li>
 * <li><b>점수:</b> 후보마다 멈춘 판의 복사본에 블록을 부수고 흘리고 지운 뒤({@link Pile}) 특징의 가중합({@link SandEval}).
 * 단계에 따라 상위 후보는 다음 블록까지 놓아 보고 그 가장 좋은 점수로 다시 매긴다({@link SandAiLevel#lookahead()}, 2수).</li>
 * <li><b>고르기:</b> 온도 0 이면 1등(점수가 같으면 먼저 센 후보), 아니면 고전 AI 와 같은 {@link Chooser} 로 뽑는다.</li>
 * </ol>
 */
public final class SandBrain implements Planner {

	/** 목표에 닿는 순간의 판을 미리 만들어 둘 조작 수 (이보다 긴 경로는 이 수로 본다). */
	static final int MAX_PATH = 12;
	/** 엔진으로 모래를 멈출 때까지 흘릴 때의 스텝 상한 (시험에서 쓴다. 블록 하나가 무너지는 데 많아야 몇백 스텝이다). */
	static final int SETTLE_STEPS = 4000;

	private final SandAiLevel level;
	private final SandEval.Weights weights;
	private final boolean random;

	public SandBrain(SandAiLevel level) {
		this(level, SandEval.Weights.DEFAULT);
	}

	public SandBrain(SandAiLevel level, SandEval.Weights weights) {
		this(level, weights, false);
	}

	private SandBrain(SandAiLevel level, SandEval.Weights weights, boolean random) {
		this.level = level;
		this.weights = weights;
		this.random = random;
	}

	/** 점수를 매기지 않고 갈 수 있는 후보 중 아무것이나 고르는 두뇌 (시험과 벤치마크의 비교 기준). */
	public static SandBrain random(SandAiLevel level) {
		return new SandBrain(level, SandEval.Weights.DEFAULT, true);
	}

	public SandAiLevel level() {
		return level;
	}

	public SandEval.Weights weights() {
		return weights;
	}

	/** 후보 하나 (고르는 동안만 쓴다). */
	private static final class Cand {
		final int index;
		final boolean hold;
		final SandPiece piece;
		final PieceState landing;
		int rotation;
		int x;
		int path;
		/** 목표에 닿을 때 놓일 자리가 멈춘 판의 자리보다 몇 칸 높은가 ({@link SandEval#RISE}). */
		int rise;
		double score;
		int cleared;
		boolean loses;
		/** 0 이면 2수까지 본 상위 묶음, 1 이면 나머지. 묶음 0 이 먼저 온다. */
		int group = 1;

		Cand(int index, boolean hold, SandPiece piece, PieceState landing) {
			this.index = index;
			this.hold = hold;
			this.piece = piece;
			this.landing = landing;
		}

		int top() {
			return topRow(landing);
		}

		SandPlan.Target target() {
			return new SandPlan.Target(hold, piece, rotation, x, landing, path, score, cleared, loses);
		}
	}

	/**
	 * 고른다. 같은 {@code s} 와 {@code noise} 면 언제나 같은 결과다(걸린 시간 {@link SandPlan#nanos()} 만 다르다).
	 *
	 * @param noise 이 블록의 난수 값 (조작기가 블록마다 하나씩 뽑는다). 판을 흘릴 때의 물리 난수와, 온도가 0 보다 크면 고르기에
	 *              쓴다
	 */
	@Override
	public SandPlan plan(Situation s, long noise) {
		long t0 = System.nanoTime();
		SplitMix64 rng = new SplitMix64(noise);
		Timeline tl = new Timeline(s, s.field(rng.next()), level.thinkMs(), level.intervalMs());
		Grid live = s.cellGrid();
		Pile rest = tl.last.copy();
		rest.settle();
		Grid restGrid = rest.cellGrid(new Grid());

		List<Cand> cands = new ArrayList<>();
		Map<Long, Cand> seen = new HashMap<>();
		collect(cands, seen, false, s.current(), s.piece(), live, tl, restGrid);
		if (level.hold() && !s.holdUsed()) {
			SandPiece out = s.holdOut();
			PieceState spawn = PieceState.spawn(out.type());
			if (!out.equals(s.current()) && live.fits(out.type(), 0, spawn.x(), spawn.y())) {
				collect(cands, seen, true, out, spawn, live, tl, restGrid);
			}
		}
		if (cands.isEmpty()) {
			return new SandPlan(List.of(), -1, 0, System.nanoTime() - t0);
		}
		if (random) {
			Cand pick = cands.get(rng.nextInt(cands.size()));
			List<SandPlan.Target> ranked = new ArrayList<>();
			ranked.add(pick.target());
			for (Cand c : cands) {
				if (c != pick) {
					ranked.add(c.target());
				}
			}
			return new SandPlan(ranked, 0, cands.size(), System.nanoTime() - t0);
		}

		// 1수: 빠른 시뮬레이션으로 모든 후보
		Pile work = new Pile();
		double[] f = new double[SandEval.FEATURES];
		for (Cand c : cands) {
			work.copyFrom(rest);
			work.drop(c.landing.cells(), c.piece.color().code());
			c.cleared = work.settle();
			SandEval.features(work, c.cleared, c.path, c.rise, c.top(), f);
			c.loses = f[SandEval.LOSS] > 0;
			c.score = SandEval.score(f, weights);
		}
		List<Cand> order = ranked(cands);

		// 2수: 상위 후보마다 다음 블록까지 놓아 보고, 그 가장 좋은 점수에 이 블록의 지우기·조작·기다림 값을 더한다
		if (level.lookahead() > 0 && s.preview().size() >= 2) {
			Pile after = new Pile();
			Grid grid = new Grid();
			for (Cand c : top(order, level.lookahead())) {
				after.copyFrom(rest);
				after.drop(c.landing.cells(), c.piece.color().code());
				int cleared = after.settle();
				SandPiece next = c.hold && s.hold() == null ? s.preview().get(1) : s.preview().get(0);
				double best = after.lost() ? Double.NEGATIVE_INFINITY
						: bestNext(after, after.cellGrid(grid), next, work, f);
				if (best > Double.NEGATIVE_INFINITY) {
					c.score = weights.get(SandEval.CLEARED) * cleared + weights.get(SandEval.PATH) * c.path
							+ weights.get(SandEval.RISE) * Math.max(0, c.rise)
							+ weights.get(SandEval.HIGH) * Math.max(0, SandEval.HIGH_ROW - c.top()) + best;
				}
				c.group = 0;
			}
			order = ranked(cands);
		}

		int pick = choose(order, rng);
		List<SandPlan.Target> ranked = new ArrayList<>(order.size());
		for (Cand c : order) {
			ranked.add(c.target());
		}
		return new SandPlan(ranked, pick, cands.size(), System.nanoTime() - t0);
	}

	/**
	 * 블록 {@code start} 가 지금 칸 판 {@code live} 에서 갈 수 있는 회전·박스 열마다, 멈춘 판 {@code restGrid} 에서 하드드롭한
	 * 자리를 후보로 넣는다. 놓일 칸(과 홀드 여부)이 같은 후보는 조작이 적은 것 하나만 남긴다.
	 *
	 * @param tl 목표에 닿는 순간의 판들 ({@link SandEval#RISE} 를 잴 때)
	 */
	private static void collect(List<Cand> out, Map<Long, Cand> seen, boolean hold, SandPiece piece, PieceState start,
			Grid live, Timeline tl, Grid restGrid) {
		Reach r = Reach.from(live, start);
		for (int rot = 0; rot < 4; rot++) {
			for (int x = Reach.minX(); x <= Reach.maxX(); x++) {
				PieceState st = r.state(rot, x);
				if (st == null || !restGrid.fits(st.type(), st.rotation(), st.x(), st.y())) {
					continue; // 갈 수 없거나, 모래가 멈추면 거기에 모래가 있다
				}
				PieceState landing = Movement.hardDrop(restGrid, st);
				int path = r.distance(rot, x) + (hold ? 1 : 0);
				long k = key(hold, landing);
				Cand old = seen.get(k);
				if (old != null && path >= old.path) {
					continue;
				}
				Grid g = tl.grids[Math.min(path, MAX_PATH)];
				Cand c = new Cand(old != null ? old.index : out.size(), hold, piece, landing);
				c.rotation = rot;
				c.x = x;
				c.path = path;
				c.rise = topRow(landing) - (g.fits(st.type(), st.rotation(), st.x(), st.y())
						? topRow(Movement.hardDrop(g, st))
						: topRow(st));
				if (old != null) {
					out.set(old.index, c);
				} else {
					out.add(c);
				}
				seen.put(k, c);
			}
		}
	}

	private static long key(boolean hold, PieceState landing) {
		long k = hold ? 1 : 0;
		for (int[] c : landing.cells()) {
			k = k * 256 + c[0] * 10 + c[1];
		}
		return k;
	}

	/** 블록의 맨 위 칸 행. */
	private static int topRow(PieceState s) {
		return s.cells()[0][0];
	}

	/** 다음 블록을 판 {@code p} 에 놓을 수 있는 자리 중 가장 좋은 점수. 놓을 곳이 없으면 음의 무한대. */
	private double bestNext(Pile p, Grid grid, SandPiece next, Pile work, double[] f) {
		PieceState spawn = PieceState.spawn(next.type());
		Reach r = Reach.from(grid, spawn);
		double best = Double.NEGATIVE_INFINITY;
		Map<Long, Boolean> seen = new HashMap<>();
		for (int rot = 0; rot < 4; rot++) {
			for (int x = Reach.minX(); x <= Reach.maxX(); x++) {
				PieceState st = r.state(rot, x);
				if (st == null) {
					continue;
				}
				PieceState landing = Movement.hardDrop(grid, st);
				if (seen.put(key(false, landing), Boolean.TRUE) != null) {
					continue;
				}
				work.copyFrom(p);
				work.drop(landing.cells(), next.color().code());
				int cleared = work.settle();
				SandEval.features(work, cleared, r.distance(rot, x), 0, topRow(landing), f);
				best = Math.max(best, SandEval.score(f, weights));
			}
		}
		return best;
	}

	/** 묶음(2수까지 본 것 먼저), 점수(높은 것 먼저), 센 순서로 늘어놓는다. */
	private static List<Cand> ranked(List<Cand> cands) {
		List<Cand> order = new ArrayList<>(cands);
		Collections.sort(order, (a, b) -> a.group != b.group ? Integer.compare(a.group, b.group)
				: a.score != b.score ? Double.compare(b.score, a.score) : Integer.compare(a.index, b.index));
		return order;
	}

	/** 지지 않는 후보 중 앞에서부터 n 개. 모두 지면 맨 앞 하나. */
	private static List<Cand> top(List<Cand> order, int n) {
		List<Cand> out = new ArrayList<>();
		for (Cand c : order) {
			if (out.size() < n && !c.loses) {
				out.add(c);
			}
		}
		if (out.isEmpty()) {
			out.add(order.get(0));
		}
		return out;
	}

	/**
	 * 고른 후보의 {@code order} 안 위치. 온도 0 이면 맨 앞. 아니면 맨 앞 묶음에서 지지 않는 후보만 센 순서로 놓고
	 * {@link Chooser#choose} 로 뽑는다(지는 후보는 점수 크기가 달라 표준편차를 망가뜨리므로 뺀다).
	 */
	private int choose(List<Cand> order, SplitMix64 rng) {
		if (level.temperature() <= 0) {
			return 0;
		}
		int group = order.get(0).group;
		List<Cand> pool = new ArrayList<>();
		for (Cand c : order) {
			if (c.group == group && !c.loses) {
				pool.add(c);
			}
		}
		if (pool.size() < 2) {
			return 0;
		}
		pool.sort((a, b) -> Integer.compare(a.index, b.index));
		double[] scores = new double[pool.size()];
		for (int i = 0; i < scores.length; i++) {
			scores[i] = pool.get(i).score;
		}
		Cand pick = pool.get(Chooser.choose(scores, level.temperature(), rng));
		return order.indexOf(pick);
	}

	/**
	 * 블록이 나온 틱부터 조작 p 번 뒤(생각 지연 + p × 입력 간격 ms) 목표에 닿는 순간의 칸 판들, p = 0~{@value #MAX_PATH}. 지금
	 * 판을 그 게임 단계의 모래 스텝 간격대로 엔진 규칙으로 흘려 만든다. 움직이는 블록은 판 위쪽에 있으므로 빼고, 반짝이는 덩어리와
	 * 흐르다 새로 생긴 덩어리는 반짝임 시간 없이 바로 지운다. 마지막 판은 빠른 시뮬레이션 판으로도 남긴다(그것을 멈출 때까지
	 * 흘린 것이 멈춘 판이다). 모래가 그전에 멈추면 거기서 그친다.
	 */
	private static final class Timeline {
		final Grid[] grids = new Grid[MAX_PATH + 1];
		final Pile last;

		Timeline(Situation s, SandField f, int thinkMs, int intervalMs) {
			int stepMs = SandRules.sandStepMs(s.level());
			f.removeFlashing();
			long done = 0;
			boolean still = false;
			for (int p = 0; p <= MAX_PATH; p++) {
				long steps = (thinkMs + (long) p * intervalMs) / stepMs;
				boolean changed = p == 0;
				while (!still && done < steps) {
					done++;
					if (f.step(null) == 0) {
						still = true; // 모래가 멈췄다: 그 뒤의 판은 모두 같다
					} else {
						f.remove(f.markSpans());
						changed = true;
					}
				}
				grids[p] = changed ? f.cellGrid(new Grid()) : grids[p - 1];
			}
			last = Pile.of(f);
		}
	}
}
