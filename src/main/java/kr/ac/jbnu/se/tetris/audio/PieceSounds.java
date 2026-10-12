package kr.ac.jbnu.se.tetris.audio;

import java.util.List;

import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 사람이 조작하는 블록의 조작 소리(이동, 회전, 홀드, 바닥에 닿음)를 한 틱 앞뒤의 블록 상태를 비교해 고른다. 조작이 실제로
 * 성공했을 때만 소리를 낸다: 벽에 막힌 이동과 막힌 회전은 조용하고, 실패한 홀드(이미 씀, 모래 모드에서 꺼낼 자리가 막힘)는
 * {@link Sfx#DENY}. 이번 틱에 블록이 고정됐으면 조작 소리는 내지 않는다(고정 소리가 대신한다). 같은 쪽으로
 * {@link #REPEAT_GAP_MS} 안에 이어진 이동은 누르고 있어 저절로 반복된 것(DAS·ARR)으로 보고 3dB 작게 낸다
 * ({@code MOVE} 세기 2).
 * <p>
 * 고전 판은 {@link MatchSounds} 가 이것을 쓰고, 모래 모드는 진행 코드가 직접 쓴다:
 *
 * <pre>
 * pieceSounds.before(game.piece(), game.spawns(), game.grounded());
 * game.advance(input);
 * Sound.get().play(pieceSounds.after(input, game.piece(), game.spawns(), game.grounded(), 고정했는가));
 * </pre>
 *
 * 한 번의 before/after 가 한 틱(1ms)이다. 틱마다 소리 엔진에 "판이 진행 중" 이라고 알려서, 판 동안은 장치를 쉬게 하지 않아
 * 첫 소리가 늦지 않다(화면 코드는 따로 부를 것이 없다). 게임 진행 스레드 하나만 부른다(동기화하지 않는다).
 */
public final class PieceSounds {

	/** 같은 쪽 이동이 이 간격(ms, 틱) 안에 이어지면 저절로 반복된 이동으로 본다. ARR 이 이보다 길면 모두 누른 이동처럼 낸다. */
	static final int REPEAT_GAP_MS = 100;
	/** 소리 엔진에 "판이 진행 중" 이라고 알리는 간격 (틱). */
	static final int ACTIVE_EVERY = 32;

	private PieceState piece;
	private int spawns;
	private boolean grounded;
	private boolean ready;
	/** 지금까지 진행한 틱 수 (after 를 부른 수). */
	private long ticks;
	/** 마지막으로 성공한 이동의 틱과 방향(-1 왼쪽, 1 오른쪽, 0 없음). */
	private long moveTick;
	private int moveDir;

	/**
	 * 틱 전에 부른다.
	 *
	 * @param piece    움직이는 블록, 없으면 null (모래 모드에서 다음 블록을 기다릴 때, 판이 끝났을 때)
	 * @param spawns   지금까지 꺼낸 블록 수 ({@code spawns()}: 홀드로 꺼낸 블록도 센다)
	 * @param grounded 블록이 바닥이나 쌓인 블록에 닿아 있는가
	 */
	public void before(PieceState piece, int spawns, boolean grounded) {
		this.piece = piece;
		this.spawns = spawns;
		this.grounded = grounded;
		ready = true;
	}

	/**
	 * 틱 뒤에 부른다. 앞에 {@link #before} 를 부르지 않았으면 빈 목록.
	 *
	 * @param input  이번 틱의 사람 조작, 없으면 null
	 * @param piece  틱 뒤의 움직이는 블록, 없으면 null
	 * @param locked 이번 틱에 블록이 고정됐는가
	 * @return 낼 소리 0~2 개 (바꿀 수 없는 목록)
	 */
	public List<Cue> after(Input input, PieceState piece, int spawns, boolean grounded, boolean locked) {
		if (!ready) {
			return List.of();
		}
		ready = false;
		if (ticks++ % ACTIVE_EVERY == 0) {
			Sound.get().markActive();
		}
		if (locked || this.piece == null || piece == null) {
			return List.of();
		}
		if (spawns != this.spawns) { // 고정 없이 새 블록이 나왔다 = 홀드에 성공했다
			return input == Input.H ? List.of(Cue.of(Sfx.HOLD)) : List.of();
		}
		Cue op = input == null ? null : operation(input, this.piece, piece);
		Cue land = grounded && !this.grounded ? Cue.of(Sfx.LAND) : null;
		if (op == null) {
			return land == null ? List.of() : List.of(land);
		}
		return land == null ? List.of(op) : List.of(op, land);
	}

	/** 조작 하나의 소리: 성공한 이동·회전, 실패한 홀드. 소리가 없으면 null. */
	private Cue operation(Input input, PieceState was, PieceState now) {
		switch (input) {
		case L:
		case R:
			return now.x() != was.x() ? move(now.x() > was.x() ? 1 : -1) : null;
		case CW:
		case CCW:
			return now.rotation() != was.rotation() ? Cue.of(Sfx.ROTATE) : null;
		case H:
			return Cue.of(Sfx.DENY); // 새 블록이 나오지 않았다 = 홀드가 막혔다
		default:
			return null; // D(한 칸 내림)는 소리가 없고, HD 는 언제나 고정한다
		}
	}

	/** 성공한 이동 소리. 같은 쪽으로 바로 이어진 이동이면 반복(세기 2, 3dB 작게). */
	private Cue move(int dir) {
		long now = ticks - 1; // 이번 틱
		boolean repeat = dir == moveDir && now - moveTick <= REPEAT_GAP_MS;
		moveTick = now;
		moveDir = dir;
		return repeat ? Cue.of(Sfx.MOVE, 2) : Cue.of(Sfx.MOVE);
	}
}
