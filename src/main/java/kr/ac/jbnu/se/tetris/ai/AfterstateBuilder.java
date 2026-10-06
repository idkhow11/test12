package kr.ac.jbnu.se.tetris.ai;

import java.util.ArrayList;
import java.util.List;

import kr.ac.jbnu.se.tetris.core.Attack;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Locking;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;

/**
 * 결정 시점의 상태와 후보 배치 하나로 애프터스테이트를 만든다 (docs/MODEL_IO.md 2장).
 * <p>
 * 원래 보드는 바꾸지 않는다. 락 아웃이 되는 배치는 지는 수이므로 애프터스테이트를 만들지 않고 null 을 돌려준다.
 */
public final class AfterstateBuilder {

	/** 블록을 놓기 전, 결정하는 순간의 상태. */
	public static final class Context {
		final PieceType current;
		final PieceType hold;
		final List<PieceType> preview;
		final int combo;
		final boolean b2b;
		final int pending;

		/**
		 * @param current 지금 놓을 차례인 블록
		 * @param hold    홀드 칸, 비었으면 null
		 * @param preview 미리보기 p1..p5 (다음 블록이 먼저). 더 길게 주면 앞 5개만 쓴다. 화면에 보이지 않은 블록이
		 *                모델 입력에 섞이면 안 되기 때문이다(MODEL_IO.md 2장)
		 * @param combo   지금까지의 콤보, 없으면 -1
		 * @param b2b     지금까지의 B2B 연속 여부
		 * @param pending 대기 가비지 줄 수의 합
		 */
		public Context(PieceType current, PieceType hold, List<PieceType> preview, int combo, boolean b2b,
				int pending) {
			this.current = current;
			this.hold = hold;
			this.preview = new ArrayList<>(preview.subList(0, Math.min(Afterstate.MAX_QUEUE, preview.size())));
			this.combo = combo;
			this.b2b = b2b;
			this.pending = pending;
		}
	}

	private AfterstateBuilder() {
	}

	/** 진행 중인 게임의 지금 상태를 결정 시점 정보로 옮긴다. */
	public static Context contextOf(GameModel g) {
		return new Context(g.current(), g.hold(), g.preview(), g.combo(), g.b2b(), g.pendingLines());
	}

	/** @return 애프터스테이트, 락 아웃이면 null */
	public static Afterstate build(Grid before, Placement p, Context ctx) {
		Grid g = before.copy();
		Locking.Outcome out = Locking.lock(g, p.state());
		if (out.lockOut()) {
			return null;
		}
		Attack a = Attack.compute(ctx.combo, ctx.b2b, out.lines(), out.spin(), out.perfectClear());
		// 상쇄는 앞 덩어리부터지만(RULES 10.1) 남은 줄 수의 합에는 순서가 영향을 주지 않는다.
		int pendingAfter = Math.max(0, ctx.pending - a.attack());

		List<PieceType> queue;
		PieceType holdAfter;
		if (!p.hold()) {
			queue = ctx.preview;
			holdAfter = ctx.hold;
		} else if (ctx.hold != null) {
			// 홀드 블록을 놓고 현재 블록이 홀드로. 가방은 진행하지 않는다(RULES 4장).
			queue = ctx.preview;
			holdAfter = ctx.current;
		} else {
			// 홀드가 비어 있었으면 p1 을 놓았으므로 알고 있는 대기열은 p2 부터다.
			// preview 는 Context 에서 이미 앞 5개로 잘렸으므로 여기서 6번째 블록이 들어올 수 없다.
			queue = ctx.preview.subList(Math.min(1, ctx.preview.size()), ctx.preview.size());
			holdAfter = ctx.current;
		}
		return new Afterstate(g, queue, holdAfter, a.combo(), a.b2b(), out.lines(), a.attack(), pendingAfter);
	}
}
