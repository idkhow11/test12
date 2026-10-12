package kr.ac.jbnu.se.tetris.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 블록을 놓고 RULES 7장 1~7단계까지 진행한 상태 (docs/MODEL_IO.md 2장). 불변.
 * <p>
 * 가비지 상승(8단계)은 반영하지 않는다. 대신 {@link #pending()} 과 {@link #lines()} 로 이번 고정에서
 * 가비지가 올라오는지를 알 수 있다.
 */
public final class Afterstate {

	/** 이 배치 뒤에 이미 알고 있는 블록의 최대 개수(결정 시점의 미리보기 수). */
	public static final int MAX_QUEUE = 5;

	private final Grid board;
	private final List<PieceType> queue;
	private final PieceType hold;
	private final int combo;
	private final boolean b2b;
	private final int lines;
	private final int attack;
	private final int pending;

	/**
	 * @param board   줄 삭제 후, 가비지 상승 전 보드. 복사해서 보관한다
	 * @param queue   이 배치 뒤에 이미 알고 있는 블록, 다음에 나올 블록이 먼저 (최대 5개)
	 * @param hold    이 배치 뒤의 홀드 칸, 없으면 null
	 * @param combo   이 배치 뒤의 콤보, 없으면 -1
	 * @param b2b     이 배치 뒤의 B2B 연속 여부
	 * @param lines   이 배치가 지운 줄 수
	 * @param attack  이 배치의 공격 줄 수(상쇄 전)
	 * @param pending 상쇄한 뒤 남은 대기 가비지 줄 수의 합
	 */
	public Afterstate(Grid board, List<PieceType> queue, PieceType hold, int combo, boolean b2b, int lines,
			int attack, int pending) {
		if (queue.size() > MAX_QUEUE) {
			throw new IllegalArgumentException("queue 는 최대 " + MAX_QUEUE + "개: " + queue);
		}
		if (combo < -1 || lines < 0 || lines > 4 || attack < 0 || pending < 0) {
			throw new IllegalArgumentException(
					"combo >= -1, 0 <= lines <= 4, attack >= 0, pending >= 0 이어야 함: " + combo + ", " + lines + ", "
							+ attack + ", " + pending);
		}
		this.board = board.copy();
		this.queue = Collections.unmodifiableList(new ArrayList<>(queue));
		this.hold = hold;
		this.combo = combo;
		this.b2b = b2b;
		this.lines = lines;
		this.attack = attack;
		this.pending = pending;
	}

	/** 보드의 복사본. */
	public Grid board() {
		return board.copy();
	}

	/** 칸 값. 복사 없이 읽는다. */
	public int cell(int row, int col) {
		return board.get(row, col);
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

	public int lines() {
		return lines;
	}

	public int attack() {
		return attack;
	}

	public int pending() {
		return pending;
	}

	/** 이번 고정에서 가비지가 올라오는가: 줄을 못 지웠고 대기 가비지가 남아 있음 (RULES 7장 8단계). */
	public boolean garbageRises() {
		return lines == 0 && pending > 0;
	}
}
