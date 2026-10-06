package kr.ac.jbnu.se.tetris.ai;

import java.util.List;
import java.util.function.Supplier;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * AI 가 사람과 같은 시간 규칙 안에서 조작한다 (PLAN 4.3, docs/GAME.md 5장). 틱마다 {@link #poll()} 이 이번 틱의 조작을
 * 하나(또는 없음) 돌려주고, 부른 쪽이 그것을 {@link TimedPlayer#tick} 에 넣는다.
 * <ol>
 * <li><b>생각:</b> 블록이 새로 나오면(고정 뒤) 생각 지연 동안 아무것도 하지 않다가, 지연이 끝나는 틱에 {@link Brain} 으로
 * 그 순간의 위치에서 목표 배치를 고른다.</li>
 * <li><b>실행:</b> 목표의 칸 집합과 spin 을 고정하고, 입력 간격마다 지금 위치에서 배치 탐색을 다시 해 목표까지의 첫 조작
 * 하나만 한다. 목표가 홀드 배치면 첫 조작은 홀드다. 남은 경로가 없거나 아래로 내리기뿐이면 하드드롭한다.</li>
 * <li><b>재결정:</b> 지금 위치에서 목표에 갈 수 없게 되면 생각 지연 없이 다시 고른다. 그런 블록의 비율이 실행 실패율이다.</li>
 * </ol>
 */
public final class AiController {

	private final TimedPlayer me;
	private final Supplier<Opponent> opponent;
	private final Brain brain;
	private final int thinkMs;
	private final int intervalMs;

	private int seenPieces = -1;
	private String target; // 목표 키에서 홀드 표시("h0|", "h1|")를 뗀 것: spin 과 칸 4 개
	private boolean targetHold;
	private int wait;
	private boolean replannedThisPiece;

	private int planned;
	private int failed;
	private int replans;

	/**
	 * @param opponent     고를 때마다 부르는 상대 상태. 혼자 연습이면 {@code () -> Opponent.NONE}
	 * @param thinkMs      생각 지연 (블록이 나온 뒤 첫 입력까지, ms)
	 * @param inputsPerSec 초당 입력 수
	 */
	public AiController(TimedPlayer me, Supplier<Opponent> opponent, Brain brain, int thinkMs, double inputsPerSec) {
		if (thinkMs < 0 || inputsPerSec <= 0 || inputsPerSec > 1000) {
			throw new IllegalArgumentException("생각 지연 " + thinkMs + "ms, 초당 입력 " + inputsPerSec);
		}
		this.me = me;
		this.opponent = opponent;
		this.brain = brain;
		this.thinkMs = thinkMs;
		this.intervalMs = Math.max(1, (int) Math.round(1000 / inputsPerSec));
	}

	/** 이번 틱의 조작. 할 것이 없으면 null. 틱마다 정확히 한 번, {@link TimedPlayer#tick} 바로 전에 부른다. */
	public Input poll() {
		GameModel g = me.game();
		if (g.isOver()) {
			return null;
		}
		if (g.pieces() != seenPieces) { // 고정 뒤 새 블록 (홀드로 꺼낸 블록은 같은 계획을 이어 간다)
			seenPieces = g.pieces();
			target = null;
			wait = thinkMs;
			replannedThisPiece = false;
		}
		if (wait > 0) {
			wait--;
			return null;
		}
		if (target == null) {
			choose();
			planned++;
		}
		wait = intervalMs - 1;
		return step();
	}

	private Input step() {
		for (int attempt = 0; attempt < 2; attempt++) {
			GameModel g = me.game();
			if (targetHold && !g.holdUsed()) {
				return Input.H;
			}
			Placement p = g.placementsFrom(me.piece()).get("h0|" + target);
			if (p != null) {
				return firstInput(p.path());
			}
			// 중력이나 락 딜레이 때문에 목표에 갈 수 없게 됐다: 지금 위치에서 다시 고른다.
			replans++;
			if (!replannedThisPiece) {
				replannedThisPiece = true;
				failed++;
			}
			choose();
		}
		return Input.HD; // 방금 지금 위치에서 고른 배치에도 못 가는 일은 없지만, 만약을 위해 그 자리에 둔다
	}

	private void choose() {
		GameModel g = me.game();
		Placement p = brain.decide(g, g.placementsFrom(me.piece()), opponent.get()).placement();
		targetHold = p.hold();
		target = p.key().substring(3);
	}

	/** 경로의 첫 조작. 경로가 없거나 남은 것이 아래로 내리기뿐이면 하드드롭(같은 칸에 닿고, 마지막이 이동이라 spin 도 같다). */
	static Input firstInput(List<Op> path) {
		boolean onlyDown = true;
		for (Op op : path) {
			if (op != Op.D) {
				onlyDown = false;
				break;
			}
		}
		if (onlyDown) {
			return Input.HD;
		}
		switch (path.get(0)) {
		case L:
			return Input.L;
		case R:
			return Input.R;
		case CW:
			return Input.CW;
		case CCW:
			return Input.CCW;
		default:
			return Input.D;
		}
	}

	// ------------------------------------------------------------------ 지표 (PLAN 4.3 실행 실패율)

	/** 새 블록을 받고 생각하는 중이면 그 진행률(0~1), 조작하는 중이거나 끝났으면 -1. 화면의 "생각 중" 표시용. */
	public double thinkProgress() {
		if (target != null || thinkMs <= 0 || me.game().isOver()) {
			return -1;
		}
		return 1 - Math.max(0, wait) / (double) thinkMs;
	}

	/** 목표를 정한 블록 수. */
	public int planned() {
		return planned;
	}

	/** 목표를 한 번이라도 바꾼 블록 수. */
	public int failed() {
		return failed;
	}

	/** 다시 고른 횟수 (한 블록에서 여러 번일 수 있다). */
	public int replans() {
		return replans;
	}

	/** 실행 실패율 = 목표를 바꾼 블록 ÷ 목표를 정한 블록. 아직 없으면 0. */
	public double failureRate() {
		return planned == 0 ? 0 : (double) failed / planned;
	}

	public TimedPlayer player() {
		return me;
	}
}
