package kr.ac.jbnu.se.tetris.ai;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 배치를 고르는 순간 보이는 상대의 상태 (docs/MODEL_IO.md 2.2). 불변.
 * <p>
 * 블록 동기 모드에서는 턴 시작 시점, 실제 게임에서는 AI 가 배치를 고르는 순간의 상태다. 후보 배치와 상관없이 같다.
 */
public final class Opponent {

	/** 상대가 없을 때(1인용). 빈 보드, 대기 가비지 0, 콤보 없음, B2B 아님, 홀드 없음이라 모델 입력이 모두 0 이 된다. */
	public static final Opponent NONE = new Opponent(new Grid(), 0, -1, false, null);

	private final Grid board;
	private final int pending;
	private final int combo;
	private final boolean b2b;
	private final PieceType hold;

	/**
	 * @param board   상대가 마지막으로 고정한 뒤(가비지 상승 포함)의 보드. 복사해서 보관한다
	 * @param pending 상대가 받을 대기 가비지 줄 수의 합
	 * @param combo   상대의 지금 콤보, 없으면 -1
	 * @param b2b     상대의 지금 B2B 연속 여부
	 * @param hold    상대의 홀드 칸, 없으면 null
	 */
	public Opponent(Grid board, int pending, int combo, boolean b2b, PieceType hold) {
		if (pending < 0 || combo < -1) {
			throw new IllegalArgumentException("pending >= 0, combo >= -1 이어야 함: " + pending + ", " + combo);
		}
		this.board = board.copy();
		this.pending = pending;
		this.combo = combo;
		this.b2b = b2b;
		this.hold = hold;
	}

	/** 진행 중인 게임의 지금 상태. 상대 쪽 {@link GameModel} 로 만든다. */
	public static Opponent of(GameModel g) {
		return new Opponent(g.grid(), g.pendingLines(), g.combo(), g.b2b(), g.hold());
	}

	/** 대기 가비지만 바꾼 상대 상태. 2수 탐색이 첫 수의 송신량을 상대 대기열에 더할 때 쓴다 (TRAINING.md 6.2). */
	public Opponent withPending(int pending) {
		return new Opponent(board, pending, combo, b2b, hold);
	}

	/** 칸 값. 복사 없이 읽는다. */
	public int cell(int row, int col) {
		return board.get(row, col);
	}

	public int pending() {
		return pending;
	}

	public int combo() {
		return combo;
	}

	public boolean b2b() {
		return b2b;
	}

	public PieceType hold() {
		return hold;
	}
}
