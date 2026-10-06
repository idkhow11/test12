package kr.ac.jbnu.se.tetris.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 한 플레이어의 결정 시점 상태 (docs/MODEL_IO.md 9.2). 불변.
 * <p>
 * 지금 놓을 블록을 아직 놓지 않은 상태로, 기록 {@code match-1} 의 {@code state}(TRAINING.md 7.3)와 같은 항목이다. 승률
 * 예측망({@link WinRate})의 입력이다. 게임 스레드에서 {@link #of(GameModel)} 로 복사해 두면 다른 스레드에서 써도 된다.
 */
public final class PlayerState {

	/** 미리보기의 최대 개수 (RULES 4장). */
	public static final int MAX_QUEUE = GameModel.PREVIEW;

	private final Grid board;
	private final PieceType current;
	private final List<PieceType> queue;
	private final PieceType hold;
	private final int combo;
	private final boolean b2b;
	private final int pending;

	/**
	 * @param board   마지막으로 고정한 뒤(가비지 상승 포함)의 보드. 복사해서 보관한다
	 * @param current 지금 놓을 차례인 블록
	 * @param queue   미리보기, 다음 블록이 먼저 (최대 5개)
	 * @param hold    홀드 칸, 없으면 null
	 * @param combo   지금 콤보, 없으면 -1
	 * @param b2b     지금 B2B 연속 여부
	 * @param pending 받을 대기 가비지 줄 수의 합
	 */
	public PlayerState(Grid board, PieceType current, List<PieceType> queue, PieceType hold, int combo, boolean b2b,
			int pending) {
		Objects.requireNonNull(current, "current");
		if (queue.size() > MAX_QUEUE) {
			throw new IllegalArgumentException("queue 는 최대 " + MAX_QUEUE + "개: " + queue);
		}
		for (PieceType p : queue) {
			Objects.requireNonNull(p, "queue 의 블록");
		}
		if (combo < -1 || pending < 0) {
			throw new IllegalArgumentException("combo >= -1, pending >= 0 이어야 함: " + combo + ", " + pending);
		}
		this.board = board.copy();
		this.current = current;
		this.queue = Collections.unmodifiableList(new ArrayList<>(queue));
		this.hold = hold;
		this.combo = combo;
		this.b2b = b2b;
		this.pending = pending;
	}

	/**
	 * 진행 중인 게임의 지금 상태. 끝난 게임은 승률 예측망에 넣지 않는다({@link WinRate#p1Win(GameModel, GameModel)}).
	 * <p>
	 * 읽기만 하는 것처럼 보이지만 {@link GameModel#preview()} 가 미리보기를 채우느라 가방을 진행시킬 수 있다. 그래서 게임을
	 * 진행하는 스레드에서 부른다(다른 스레드가 같은 게임을 쓰거나 읽는 동안 부르면 안 된다). 만든 뒤의 객체는 불변이다.
	 */
	public static PlayerState of(GameModel g) {
		return new PlayerState(g.grid(), g.current(), g.preview(), g.hold(), g.combo(), g.b2b(), g.pendingLines());
	}

	/** 칸 값. 복사 없이 읽는다. */
	public int cell(int row, int col) {
		return board.get(row, col);
	}

	public PieceType current() {
		return current;
	}

	public List<PieceType> queue() {
		return queue;
	}

	public PieceType hold() {
		return hold;
	}

	public int combo() {
		return combo;
	}

	public boolean b2b() {
		return b2b;
	}

	public int pending() {
		return pending;
	}
}
