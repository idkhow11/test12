package kr.ac.jbnu.se.tetris.sand;

import java.util.List;

import kr.ac.jbnu.se.tetris.core.PieceState;

/**
 * 그리기용 복사본. 게임 진행 스레드의 잠금 안에서 {@link SandGame#snapshot} 으로 채우고, 잠금 밖에서 마음껏 읽는다. 배열은
 * 다시 쓰므로 프레임마다 새로 만들 필요가 없다.
 */
public final class SandSnapshot implements SandView {

	private final short[] grains = new short[SandRules.WIDTH * SandRules.HEIGHT];
	private final short[] pieceGrains = new short[SandRules.PIECE_GRAINS];
	private PieceState piece;
	private SandPiece current;
	private PieceState ghost;
	private SandPiece hold;
	private boolean holdUsed;
	private List<SandPiece> preview = List.of();
	private long time;
	private int level;
	private long score;
	private long best;
	private boolean newBest;
	private int multiplier;
	private int comboLeft;
	private int grainsCleared;
	private int clears;
	private int pieces;
	private int maxMultiplier;
	private ClearEvent lastClear;
	private LockEvent lastLock;
	private boolean over;
	private boolean grounded;
	private int lockTimer;
	private int spawns;
	private int surfaceRow;
	private int settledTop;
	private int[] thin = new int[0];
	private int thinGrains;

	/** 다른 창(보통 살아 있는 게임)의 지금 상태를 옮긴다. */
	void fill(SandView v) {
		v.copyGrains(grains);
		piece = v.piece();
		current = v.current();
		ghost = v.ghost();
		if (piece != null) {
			v.pieceGrains(pieceGrains);
		}
		hold = v.hold();
		holdUsed = v.holdUsed();
		preview = v.preview();
		time = v.time();
		level = v.level();
		score = v.score();
		best = v.best();
		newBest = v.newBest();
		multiplier = v.multiplier();
		comboLeft = v.comboLeft();
		grainsCleared = v.grainsCleared();
		clears = v.clears();
		pieces = v.pieces();
		maxMultiplier = v.maxMultiplier();
		lastClear = v.lastClear();
		lastLock = v.lastLock();
		over = v.over();
		grounded = v.grounded();
		lockTimer = v.lockTimer();
		spawns = v.spawns();
		surfaceRow = v.surfaceRow();
		settledTop = v.settledTop();
		thinGrains = v.thinGrains();
		if (thin.length < thinGrains) {
			thin = new int[Math.max(thinGrains, 2 * thin.length)];
		}
		v.copyThin(thin);
	}

	@Override
	public short grain(int gy, int gx) {
		return grains[gy * SandRules.WIDTH + gx];
	}

	@Override
	public void copyGrains(short[] dst) {
		System.arraycopy(grains, 0, dst, 0, grains.length);
	}

	@Override
	public PieceState piece() {
		return piece;
	}

	@Override
	public SandPiece current() {
		return current;
	}

	@Override
	public PieceState ghost() {
		return ghost;
	}

	@Override
	public void pieceGrains(short[] dst) {
		if (piece != null) {
			System.arraycopy(pieceGrains, 0, dst, 0, pieceGrains.length);
		}
	}

	@Override
	public SandPiece hold() {
		return hold;
	}

	@Override
	public boolean holdUsed() {
		return holdUsed;
	}

	@Override
	public List<SandPiece> preview() {
		return preview;
	}

	@Override
	public long time() {
		return time;
	}

	@Override
	public int level() {
		return level;
	}

	@Override
	public long score() {
		return score;
	}

	@Override
	public long best() {
		return best;
	}

	@Override
	public boolean newBest() {
		return newBest;
	}

	@Override
	public int multiplier() {
		return multiplier;
	}

	@Override
	public int comboLeft() {
		return comboLeft;
	}

	@Override
	public int grainsCleared() {
		return grainsCleared;
	}

	@Override
	public int clears() {
		return clears;
	}

	@Override
	public int pieces() {
		return pieces;
	}

	@Override
	public int maxMultiplier() {
		return maxMultiplier;
	}

	@Override
	public ClearEvent lastClear() {
		return lastClear;
	}

	@Override
	public LockEvent lastLock() {
		return lastLock;
	}

	@Override
	public boolean over() {
		return over;
	}

	@Override
	public boolean grounded() {
		return grounded;
	}

	@Override
	public int lockTimer() {
		return lockTimer;
	}

	@Override
	public int spawns() {
		return spawns;
	}

	@Override
	public int surfaceRow() {
		return surfaceRow;
	}

	@Override
	public int settledTop() {
		return settledTop;
	}

	@Override
	public int thinGrains() {
		return thinGrains;
	}

	@Override
	public void copyThin(int[] dst) {
		System.arraycopy(thin, 0, dst, 0, thinGrains);
	}
}
