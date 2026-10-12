package kr.ac.jbnu.se.tetris.ai;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;

/**
 * 생각 지연 동안 일꾼 스레드에서 미리 고르기 (docs/GAME.md 5장). 고를 때 쓰는 것(내 게임 상태, 지금 블록, 상대 상태)을 복사해
 * 일꾼에게 넘긴다. 생각이 끝나는 틱에 그 값들이 그대로이고 둘 수 있는 배치의 키도 같으면 미리 고른 답을 쓰고, 하나라도 다르면
 * 버리고 그 자리에서 고른다. 온도 0 인 두뇌는 같은 입력에 언제나 같은 답을 내므로, 미리 골랐든 아니든 판은 똑같이 흘러간다.
 * 고르기 난수를 쓰는 두뇌(온도 > 0)는 미리 고르지 않는다({@link AiController}).
 * <p>
 * 그동안 블록이 중력으로 내려와도 괜찮다: 고르기는 배치의 키(spin 과 칸 4개)로만 갈리고, 키가 같으면 고정한 결과(애프터스테이트)도
 * 같다. 그래서 키의 집합만 맞으면 된다. 일꾼과 진행 스레드가 같은 두뇌를 함께 쓸 수 있으므로 점수 매기기는 여러 스레드가 함께
 * 써도 되는 것이어야 한다({@link OnnxScorer}, {@link BaselineScorer}, 2수 탐색 {@link BeamSearch} 모두 그렇다).
 */
final class Prefetch {

	/** 일꾼이 돌려주는 것: 시작 상태에서 둘 수 있는 배치의 키와 고른 답. */
	record Result(Set<String> keys, Brain.Decision decision) {
	}

	/** 고르기에 들어가는 값들의 복사본. 게임 진행 스레드만 읽는다(일꾼은 따로 복사한 게임을 쓴다). */
	private record Inputs(Grid grid, PieceType current, PieceType hold, boolean holdUsed, List<PieceType> preview,
			int combo, boolean b2b, int pending, Opponent view) {

		static Inputs of(GameModel g, Opponent view) {
			return new Inputs(g.grid(), g.current(), g.hold(), g.holdUsed(), g.preview(), g.combo(), g.b2b(),
					g.pendingLines(), view);
		}

		/** 지금 게임과 상대 상태로 고르면 같은 입력인가. 보드는 칸 값까지 같아야 한다. */
		boolean matches(GameModel g, Opponent v) {
			if (g.current() != current || g.hold() != hold || g.holdUsed() != holdUsed || g.combo() != combo
					|| g.b2b() != b2b || g.pendingLines() != pending || !g.preview().equals(preview)) {
				return false;
			}
			if (v.pending() != view.pending() || v.combo() != view.combo() || v.b2b() != view.b2b()
					|| v.hold() != view.hold()) {
				return false;
			}
			for (int r = 0; r < Grid.HEIGHT; r++) {
				for (int c = 0; c < Grid.WIDTH; c++) {
					if (g.cell(r, c) != grid.get(r, c) || v.cell(r, c) != view.cell(r, c)) {
						return false;
					}
				}
			}
			return true;
		}
	}

	private final Brain brain;
	private final Inputs inputs;
	private final AtomicBoolean started = new AtomicBoolean();
	private final FutureTask<Result> task;

	private Prefetch(Brain brain, Inputs inputs, GameModel copy, PieceState piece, Opponent view) {
		this.brain = brain;
		this.inputs = inputs;
		this.task = new FutureTask<>(() -> {
			started.set(true);
			Map<String, Placement> all = copy.placementsFrom(piece);
			return new Result(all.keySet(), brain.decide(copy, all, view));
		});
	}

	/**
	 * 지금 상태로 미리 고르기를 시작한다. 게임 진행 스레드에서 부른다.
	 *
	 * @return 시작한 고르기. 일꾼이 받지 않으면(닫는 중) null
	 */
	static Prefetch start(Executor pool, Brain brain, GameModel live, PieceState piece, Opponent view) {
		Prefetch p = new Prefetch(brain, Inputs.of(live, view), live.copy(), piece, view);
		try {
			pool.execute(p.task);
		} catch (RejectedExecutionException e) {
			return null;
		}
		return p;
	}

	/** 지금 고르면 넣을 값들(두뇌, 내 상태, 상대 상태)이 미리 고를 때와 같은가. 배치의 키는 답을 받은 뒤 {@link Result#keys()} 로 본다. */
	boolean sameInputs(Brain now, GameModel live, Opponent view) {
		return now == brain && inputs.matches(live, view);
	}

	/**
	 * 답을 기다린다. 일꾼이 아직 시작하지 않았으면(앞에서 버린 고르기를 하는 중) 기다리지 않고 null 이다. 그때와 고르다 실패했을
	 * 때는 부른 쪽이 그 자리에서 고른다(모델 오류도 거기서 다룬다).
	 */
	Result await() {
		if (!started.get()) {
			task.cancel(false);
			return null;
		}
		try {
			return task.get();
		} catch (ExecutionException | CancellationException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** 일꾼이 끝냈거나(답, 실패) 버려졌는가. */
	boolean done() {
		return task.isDone();
	}

	/** 버린다. 아직 시작하지 않았으면 일꾼이 하지 않고, 하는 중이면 끝까지 하되 답은 쓰지 않는다. */
	void cancel() {
		task.cancel(false);
	}
}
