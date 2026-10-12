package kr.ac.jbnu.se.tetris.core;

/**
 * 한 플레이어를 실제 시간 규칙으로 진행한다 (RULES 6.3, docs/GAME.md 3장). 시간 단위는 정수 밀리초이고 한 번의
 * {@link #tick(Input)} 이 1ms 다.
 * <p>
 * 한 틱은 <b>입력 → 중력 → 락 딜레이</b> 순서다. 규칙 상태(보드, 가방, 가비지, 공격)는 {@link GameModel} 이 갖고,
 * 이 객체는 움직이는 블록과 시간 상태(중력 누적값, 락 딜레이 타이머, 리셋 횟수)만 갖는다. 같은 게임 시드와 같은 입력
 * 열이면 언제나 같은 결과가 나온다.
 */
public final class TimedPlayer {

	/** 락 딜레이 (RULES 6.3). */
	public static final int LOCK_DELAY_MS = 500;
	/** 바닥에 닿은 채 이동·회전으로 락 딜레이를 되돌릴 수 있는 횟수 (RULES 6.3). */
	public static final int MAX_RESETS = 15;
	/** 사람이 소프트드롭을 누르고 있을 때의 최소 중력 g (RULES 6.3 `[초안]`, 초당 20칸). */
	public static final int SOFT_DROP_G = 20_000;
	/** 중력 누적값이 이만큼 쌓이면 한 칸 내린다 (RULES 6.3). */
	static final long CELL = 1_000_000;

	/** 한 틱의 조작 (RULES 6.1). {@code D} 는 한 칸, {@code HD} 는 하드드롭, {@code H} 는 홀드. */
	public enum Input {
		L, R, CW, CCW, D, HD, H;

		Op op() {
			switch (this) {
			case L:
				return Op.L;
			case R:
				return Op.R;
			case CW:
				return Op.CW;
			case CCW:
				return Op.CCW;
			case D:
				return Op.D;
			default:
				throw new IllegalStateException(name() + " 은 이동 조작이 아님");
			}
		}
	}

	private final GameModel game;

	private PieceState piece;
	private int gravity; // g = 초당 칸 수 × 1000
	private boolean softDrop;
	private long acc;
	private boolean grounded;
	private int lockTimer;
	private int resets;
	private int lowestRow;
	private long ticks;
	private int spawns;

	/** @param gravity 처음 중력 g (초당 칸 수 × 1000). 0 이면 중력 없음 */
	public TimedPlayer(GameModel game, int gravity) {
		this.game = game;
		this.gravity = gravity;
		spawnActive();
	}

	/**
	 * 1ms 진행한다.
	 *
	 * @param input 이번 틱의 조작, 없으면 null. 실패한 조작은 아무것도 바꾸지 않는다(RULES 6.1)
	 * @return 이번 틱에 블록이 고정됐으면 그 결과, 아니면 null. 게임이 끝났으면 아무것도 하지 않고 null
	 */
	public GameModel.LockResult tick(Input input) {
		if (game.isOver()) {
			return null;
		}
		ticks++;
		Grid grid = game.gridView();

		// 1. 입력
		if (input == Input.HD) {
			return lockNow(Movement.hardDrop(grid, piece)); // 이 틱의 중력과 락 딜레이는 하지 않는다
		}
		if (input == Input.H) {
			if (game.holdNow()) {
				spawnActive();
				if (game.isOver()) { // 꺼낸 블록이 블록 아웃
					return null;
				}
			}
		} else if (input != null) {
			move(grid, input.op());
		}

		// 2. 중력: 한 틱에 많아야 한 칸. 바닥에 닿아 있으면 누적값은 0.
		if (grounded) {
			acc = 0;
		} else {
			acc += softDrop ? Math.max(gravity, SOFT_DROP_G) : gravity;
			if (acc >= CELL) {
				acc -= CELL;
				PieceState down = Movement.apply(grid, piece, Op.D);
				if (down != null) {
					piece = down;
					updateLowest();
				}
				settle(grid);
			}
		}

		// 3. 락 딜레이: 바닥에 닿아 있는 틱마다 1씩 줄여 0 이 되는 틱에 고정. 리셋 15 회 뒤에는 바로 고정.
		if (grounded) {
			if (resets >= MAX_RESETS) {
				return lockNow(piece);
			}
			if (--lockTimer <= 0) {
				return lockNow(piece);
			}
		}
		return null;
	}

	private void move(Grid grid, Op op) {
		PieceState next = Movement.apply(grid, piece, op);
		if (next == null) {
			return;
		}
		boolean wasGrounded = grounded;
		piece = next;
		if (wasGrounded) { // 바닥에서 성공한 이동·회전은 락 딜레이를 되돌린다(D 는 바닥에서 언제나 실패한다)
			lockTimer = LOCK_DELAY_MS;
			resets++;
		}
		updateLowest();
		settle(grid);
	}

	/** 바닥에 닿았는지 다시 본다. 떨어져 있다가 닿으면 타이머를 500ms 로 놓는다. */
	private void settle(Grid grid) {
		boolean now = Movement.isGrounded(grid, piece);
		if (now && !grounded) {
			lockTimer = LOCK_DELAY_MS;
		}
		if (now) {
			acc = 0;
		}
		grounded = now;
	}

	/** 블록의 가장 아래 칸이 이 블록의 최저 기록보다 내려가면 리셋 횟수를 0 으로 (RULES 6.3). */
	private void updateLowest() {
		int bottom = bottomRow(piece);
		if (bottom > lowestRow) {
			lowestRow = bottom;
			resets = 0;
		}
	}

	private GameModel.LockResult lockNow(PieceState s) {
		GameModel.LockResult r = game.lock(s);
		if (!game.isOver()) {
			spawnActive();
		}
		return r;
	}

	/** GameModel 이 정한 지금 블록을 스폰 상태로 꺼낸다(RULES 4장: 스폰 직후 자동으로 내리지 않는다). */
	private void spawnActive() {
		piece = PieceState.spawn(game.current());
		spawns++;
		acc = 0;
		resets = 0;
		lowestRow = bottomRow(piece);
		grounded = false;
		if (!game.isOver()) {
			settle(game.gridView());
		}
	}

	private static int bottomRow(PieceState s) {
		int[][] cells = s.cells();
		return cells[cells.length - 1][0]; // 행 오름차순으로 정렬돼 있다
	}

	// ------------------------------------------------------------------ 설정과 상태 읽기

	/** 중력 g (초당 칸 수 × 1000). 다음 틱부터 쓴다. */
	public void setGravity(int gravity) {
		this.gravity = gravity;
	}

	public int gravity() {
		return gravity;
	}

	/** 사람의 소프트드롭 키를 누르고 있는가 (RULES 6.3). */
	public void setSoftDrop(boolean softDrop) {
		this.softDrop = softDrop;
	}

	public GameModel game() {
		return game;
	}

	/** 움직이는 블록. 게임이 끝났으면 마지막 값. */
	public PieceState piece() {
		return piece;
	}

	/** 지금 하드드롭하면 놓일 상태 (화면의 고스트). */
	public PieceState ghost() {
		return Movement.hardDrop(game.gridView(), piece);
	}

	public boolean grounded() {
		return grounded;
	}

	/** 바닥에 닿아 있을 때 남은 락 딜레이 (ms). */
	public int lockTimer() {
		return lockTimer;
	}

	public int resets() {
		return resets;
	}

	/** 지금까지 진행한 틱 수 (ms). */
	public long ticks() {
		return ticks;
	}

	/** 지금까지 블록을 꺼낸 횟수(처음 블록과 홀드로 꺼낸 블록 포함). 화면이 새 블록을 알아챌 때 쓴다. */
	public int spawns() {
		return spawns;
	}
}
