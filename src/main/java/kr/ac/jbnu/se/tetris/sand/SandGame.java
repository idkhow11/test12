package kr.ac.jbnu.se.tetris.sand;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 모래 모드 한 판 (혼자, 끝없이). 1ms 마다 {@link #advance(Input)} 를 부른다. 규칙 전체는 이 패키지 설명(package-info)에
 * 있다.
 * <p>
 * 블록 조작은 고전 게임과 같다: core 의 {@link Movement}(SRS 킥), {@link PieceState}, 7-bag, 홀드, 미리보기 5개,
 * 하드드롭, 소프트드롭, 락 딜레이 500ms·리셋 15회. 블록은 칸 단위로 움직이고, <b>알갱이가 하나라도 있는 칸</b>은 막힌
 * 칸이다. 그래서 알갱이 판에서 "알갱이가 있는 칸" 을 core {@link Grid} 로 옮겨 두고({@link SandField#cellGrid}) core 의
 * 충돌·회전 코드를 그대로 쓴다.
 * <p>
 * 화면 스레드와 함께 쓸 때는 바깥에서 잠금을 잡는다(이 객체는 스레드 안전하지 않다). 잠금 안에서 {@link #advance} 와
 * {@link #snapshot} 을 부르고, 그리기는 잠금 밖에서 복사본으로 한다. 같은 시드, 같은 입력 열(소프트드롭 포함)이면 언제나
 * 같은 결과가 나온다. 읽기 메서드를 몇 번 부르든 결과는 바뀌지 않는다.
 * <p>
 * 최고 점수는 이 객체가 저장하지 않는다. 화면이 저장된 기록을 생성자에 넘기고, 판이 끝나면 {@link #score()} 와
 * {@link #maxMultiplier()} 를 기록에 더한다(settings 의 {@code PlayRecords.withSand}).
 */
public final class SandGame implements SandView {

	private final long seed;
	private final SandField field;
	private final PieceQueue queue;
	private final long noiseBase;
	private final long savedBest;
	private final int startLevel;
	private final int gravityOverride;

	/** 알갱이가 있는 칸 = 막힌 칸. {@link #grid()} 가 칸 점유가 바뀌었을 때만 다시 만든다. */
	private final Grid grid = new Grid();
	private long gridVersion = -1;
	private final boolean[] obstacle = new boolean[SandRules.ROWS * SandRules.COLS];

	// 블록 (고전 TimedPlayer 와 같은 상태)
	private SandPiece current;
	private PieceState piece;
	private SandPiece hold;
	private boolean holdUsed;
	private boolean grounded;
	private long acc;
	private int lockTimer;
	private int resets;
	private int lowestRow;
	private boolean softDrop;
	private int spawns;

	// 시간과 단계
	private long time;
	private int level;
	private int gravity;
	private int sandStepMs;
	private int sandClock;

	// 점수
	private long score;
	private int multiplier = 1;
	private int comboLeft;
	private int maxMultiplier = 1;
	private int grainsCleared;
	private int clears;
	private int pieces;
	private ClearEvent lastClear;
	private LockEvent lastLock;
	/** 반짝이는 덩어리(칸 번호들)와 사라질 때. 반짝임 시간이 모두 같아서 먼저 찾은 것이 먼저 사라진다. */
	private final ArrayDeque<int[]> flashGroups = new ArrayDeque<>();
	private final ArrayDeque<Long> flashEnds = new ArrayDeque<>();

	private boolean over;
	/** 알갱이가 바뀌어 넘침 검사를 다시 해야 한다. */
	private boolean dirty;
	/** 모래가 멈췄다: 마지막 모래 스텝에서 움직인 알갱이가 없고, 그 뒤로 놓이거나 사라진 알갱이가 없고, 반짝이는 덩어리도 없다. */
	private boolean still;

	/**
	 * @param seed       게임 시드. 모양 순서는 같은 시드의 고전 게임과 같다
	 * @param startLevel 시작 단계 1~10 (벗어나면 자른다). 30초마다 한 단계씩 오른다
	 * @param best       저장된 최고 점수 (settings 의 {@code PlayRecords.Sand#bestScore}), 없으면 0
	 */
	public SandGame(long seed, int startLevel, long best) {
		this(seed, startLevel, best, null, -1);
	}

	/**
	 * 처음 판을 정해 시작한다 (시험, 화면 확인 도구, 연습 판). 시작할 때 지우기와 넘침을 한 번 검사하므로, 이미 좌우를 잇는
	 * 덩어리(알갱이 {@link SandRules#MIN_CLEAR_GRAINS} 이상)는 곧바로 반짝이고 보이는 판 위에 정착한 알갱이가 있으면 바로 끝난
	 * 판이다.
	 *
	 * @param initial 처음 판 ({@link SandRules#WIDTH} × {@link SandRules#HEIGHT}). 알갱이만 옮긴다(난수 상태는 시드로, 지우기
	 *                문턱은 규칙으로 정한다)
	 */
	public static SandGame fromField(long seed, int startLevel, long best, SandField initial) {
		return new SandGame(seed, startLevel, best, Objects.requireNonNull(initial, "initial"), -1);
	}

	/**
	 * 시험용: 처음 판과 중력을 정해 시작한다.
	 *
	 * @param initial 처음 판 (80 × 192, 알갱이만 옮긴다), 없으면 null. 있으면 시작할 때 지우기와 넘침을 한 번 검사한다
	 * @param gravity 0 이상이면 단계표 대신 이 중력 g 를 쓴다 (0 = 중력 없음). 음수면 단계표
	 */
	SandGame(long seed, int startLevel, long best, SandField initial, int gravity) {
		this(seed, startLevel, best, initial, gravity, SandRules.MIN_CLEAR_GRAINS);
	}

	/**
	 * 시험용: 지우기 문턱까지 정한다. 0 이면 지난 규칙(sand-0.2)처럼 양쪽 벽을 이은 덩어리를 크기와 상관없이 지운다(같은 판을 두
	 * 규칙으로 견줄 때).
	 *
	 * @param minClear 지우는 덩어리의 최소 알갱이 수 (0 이상)
	 */
	SandGame(long seed, int startLevel, long best, SandField initial, int gravity, int minClear) {
		this.seed = seed;
		this.field = new SandField(SandRules.WIDTH, SandRules.HEIGHT, SplitMix64.stream(seed, SandRules.STREAM_PHYSICS),
				minClear);
		this.queue = new PieceQueue(seed);
		this.noiseBase = SplitMix64.stream(seed, SandRules.STREAM_SHADES).next();
		this.savedBest = Math.max(0, best);
		this.startLevel = SandRules.clampLevel(startLevel);
		this.gravityOverride = gravity;
		setLevel(this.startLevel);
		sandClock = sandStepMs;
		current = queue.next();
		still = initial == null; // 처음 판이 있으면 첫 모래 스텝에서 멈췄는지 본다
		if (initial != null) {
			load(initial);
			checkClears();
			if (field.hasSettledAbove(SandRules.VISIBLE_TOP)) {
				over = true;
				return;
			}
		}
		trySpawn();
	}

	private void load(SandField initial) {
		if (initial.width() != SandRules.WIDTH || initial.height() != SandRules.HEIGHT) {
			throw new IllegalArgumentException("처음 판은 " + SandRules.WIDTH + " × " + SandRules.HEIGHT);
		}
		for (int y = 0; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				short v = initial.get(y, x);
				if (v != Grain.EMPTY) {
					field.set(y, x, v);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 진행

	/** 사람의 소프트드롭 키를 누르고 있는가 (중력이 {@code max(g, 20000)}). 다음 틱부터 쓴다. */
	public void setSoftDrop(boolean softDrop) {
		this.softDrop = softDrop;
	}

	/**
	 * 1ms 진행한다. 판이 끝났으면 아무것도 하지 않는다. 순서는
	 * <ol>
	 * <li><b>입력</b> (하드드롭이면 그 자리에서 고정하고 2·3 을 건너뛴다)</li>
	 * <li><b>중력</b> (한 틱에 많아야 한 칸)</li>
	 * <li><b>락 딜레이</b> (0 이 되거나 리셋 15회 뒤 바닥에 닿아 있으면 고정)</li>
	 * <li><b>모래</b>: 반짝임이 끝난 덩어리 지우기 → 스텝 차례면 모래 스텝 → 움직였으면 지우기 검사 → 콤보 시간 →
	 * 알갱이가 바뀌었으면 넘침 검사 → 다음 블록을 기다리는 중이면 다시 꺼내 보기</li>
	 * <li><b>바닥 다시 확인</b>: 모래가 흘러 받침이 사라졌으면 떨어지는 중(타이머 멈춤), 새로 닿았으면 타이머 500ms</li>
	 * </ol>
	 *
	 * @param input 이번 틱의 조작, 없으면 null. 실패한 조작은 아무것도 바꾸지 않는다. 다음 블록을 기다리는 동안의 조작은 버린다
	 */
	public void advance(Input input) {
		if (over) {
			return;
		}
		time++;
		int lvl = SandRules.clampLevel(startLevel + (int) Math.min(time / SandRules.LEVEL_MS, SandRules.MAX_LEVEL));
		if (lvl != level) {
			setLevel(lvl);
		}
		if (piece != null) {
			if (input == Input.HD) { // 그 자리에서 고정하고, 이 틱의 중력과 락 딜레이는 하지 않는다
				piece = Movement.hardDrop(grid(), piece);
				lock(true);
			} else {
				if (input == Input.H) {
					holdNow();
				} else if (input != null) {
					move(op(input));
				}
				fall();
				lockDelay();
			}
		}
		if (!over) {
			sand();
		}
		if (piece != null && !over) {
			settle(); // 모래가 흘러 받침이 사라졌거나 새로 생겼는지
		}
	}

	private static Op op(Input input) {
		switch (input) {
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
			throw new IllegalArgumentException(input.name());
		}
	}

	private void setLevel(int lvl) {
		level = lvl;
		gravity = gravityOverride >= 0 ? gravityOverride : SandRules.gravity(lvl);
		sandStepMs = SandRules.sandStepMs(lvl);
	}

	/** 알갱이가 있는 칸을 막은 core 판. 칸 점유가 바뀌었을 때만 다시 만든다. */
	private Grid grid() {
		long v = field.cellVersion();
		if (v != gridVersion) {
			field.cellGrid(grid);
			gridVersion = v;
		}
		return grid;
	}

	// ------------------------------------------------------------------ 블록 (고전 TimedPlayer 와 같은 절차)

	private void move(Op op) {
		PieceState next = Movement.apply(grid(), piece, op);
		if (next == null) {
			return;
		}
		boolean wasGrounded = grounded;
		piece = next;
		if (wasGrounded) { // 바닥에서 성공한 이동·회전은 락 딜레이를 되돌린다
			lockTimer = SandRules.LOCK_DELAY_MS;
			resets++;
		}
		updateLowest();
		settle();
	}

	/** 중력: 한 틱에 많아야 한 칸. 바닥에 닿아 있으면 누적값은 0. */
	private void fall() {
		if (grounded) {
			acc = 0;
			return;
		}
		acc += softDrop ? Math.max(gravity, SandRules.SOFT_DROP_G) : gravity;
		if (acc >= SandRules.GRAVITY_CELL) {
			acc -= SandRules.GRAVITY_CELL;
			PieceState down = Movement.apply(grid(), piece, Op.D);
			if (down != null) {
				piece = down;
				updateLowest();
			}
			settle();
		}
	}

	/** 바닥에 닿아 있는 틱마다 1씩 줄여 0 이 되면 고정. 리셋 15회 뒤에는 바로 고정. */
	private void lockDelay() {
		if (grounded && (resets >= SandRules.MAX_RESETS || --lockTimer <= 0)) {
			lock(false);
		}
	}

	/** 바닥에 닿았는지 다시 본다. 떨어져 있다가 닿으면 타이머를 500ms 로 놓는다. 떨어지면 타이머는 멈춘다. */
	private void settle() {
		boolean now = Movement.isGrounded(grid(), piece);
		if (now && !grounded) {
			lockTimer = SandRules.LOCK_DELAY_MS;
		}
		if (now) {
			acc = 0;
		}
		grounded = now;
	}

	/** 블록의 가장 아래 칸이 이 블록의 최저 기록보다 내려가면 리셋 횟수를 0 으로. */
	private void updateLowest() {
		int bottom = bottomRow(piece);
		if (bottom > lowestRow) {
			lowestRow = bottom;
			resets = 0;
		}
	}

	private static int bottomRow(PieceState s) {
		int[][] cells = s.cells();
		return cells[cells.length - 1][0]; // 행 오름차순
	}

	/**
	 * 홀드 (고전 RULES 4장): 비었으면 지금 블록을 넣고 다음 블록을, 차 있으면 맞바꿔 꺼낸다. 블록 하나당 한 번. 꺼낼 블록의
	 * 스폰 자리에 알갱이가 있으면 실패한 조작이다(아무것도 바뀌지 않는다).
	 */
	private void holdNow() {
		if (holdUsed) {
			return;
		}
		SandPiece out = hold != null ? hold : queue.peek(0);
		if (!fitsAtSpawn(out)) {
			return;
		}
		if (hold == null) {
			queue.next();
		}
		hold = current;
		current = out;
		holdUsed = true;
		trySpawn();
	}

	private boolean fitsAtSpawn(SandPiece p) {
		return grid().fits(p.type(), 0, PieceState.SPAWN_X, PieceState.SPAWN_Y);
	}

	/** 지금 블록을 스폰 상태로 꺼낸다. 스폰 자리에 알갱이가 있으면 꺼내지 않고 기다린다(piece = null). */
	private void trySpawn() {
		if (!fitsAtSpawn(current)) {
			piece = null;
			return;
		}
		piece = PieceState.spawn(current.type());
		spawns++;
		acc = 0;
		resets = 0;
		lowestRow = bottomRow(piece);
		grounded = false;
		settle();
	}

	/**
	 * 블록이 같은 색 알갱이 256개로 부서진다. 그 뒤 지우기 검사, 넘침 검사, 다음 블록. 블록은 언제나 바닥(알갱이가 있는
	 * 칸이나 판 바닥)에 닿은 채로 고정된다: 락 딜레이는 바닥에 닿아 있는 틱에만 고정하고, 그 틱에는 알갱이가 아직 움직이지
	 * 않았다(모래 단계가 고정 뒤에 온다).
	 */
	private void lock(boolean hardDrop) {
		assert Movement.isGrounded(grid(), piece) : "공중에서 고정: " + piece;
		short[] grains = new short[SandRules.PIECE_GRAINS];
		pieceGrains(grains);
		field.place(piece, grains);
		still = false;
		lastLock = new LockEvent(time, piece, current.color(), hardDrop);
		pieces++;
		piece = null;
		holdUsed = false;
		checkClears(); // 반짝이게 된 덩어리 위의 모래는 정착이 아니므로 넘침 검사보다 먼저
		dirty = false; // 지금 판으로 바로 검사한다
		if (field.hasSettledAbove(SandRules.VISIBLE_TOP)) {
			over = true;
			return;
		}
		current = queue.next();
		trySpawn();
	}

	// ------------------------------------------------------------------ 모래

	/** 반짝임이 끝난 덩어리 지우기 → 모래 스텝(차례면) → 지우기 검사 → 콤보 시간 → 넘침 검사 → 기다리던 블록. */
	private void sand() {
		while (!flashEnds.isEmpty() && flashEnds.peekFirst() <= time) {
			flashEnds.pollFirst();
			field.remove(flashGroups.pollFirst());
			dirty = true;
			still = false;
		}
		if (--sandClock <= 0) {
			sandClock = sandStepMs;
			int moved = field.step(obstacles());
			if (moved > 0) {
				dirty = true;
				checkClears();
			}
			still = moved == 0 && flashGroups.isEmpty();
		}
		if (comboLeft > 0 && --comboLeft == 0 && multiplier > 1) {
			multiplier--; // 한 단계씩 내려가고, 아직 ×2 이상이면 시간을 다시 준다
			if (multiplier > 1) {
				comboLeft = SandRules.COMBO_MS;
			}
		}
		if (dirty) {
			dirty = false;
			if (field.hasSettledAbove(SandRules.VISIBLE_TOP)) {
				over = true;
				return;
			}
		}
		if (piece == null) {
			trySpawn();
		}
	}

	/** 움직이는 블록의 칸 (모래가 들어갈 수 없다). */
	private boolean[] obstacles() {
		if (piece == null) {
			return null;
		}
		Arrays.fill(obstacle, false);
		for (int[] c : piece.cells()) {
			obstacle[c[0] * SandRules.COLS + c[1]] = true;
		}
		return obstacle;
	}

	/**
	 * 좌우 벽을 잇는 덩어리 가운데 지우기 문턱({@link SandRules#MIN_CLEAR_GRAINS}) 이상인 것을 찾아 반짝이게 하고 점수를 준다.
	 * 콤보 시간 안이면 배율이 한 단계 오른다. 문턱보다 작은 덩어리는 남고 얇은 띠로 적힌다({@link #thinGrains()}).
	 */
	private void checkClears() {
		int[] marked = field.markSpans();
		if (marked.length == 0) {
			return;
		}
		int colors = 0;
		int top = Integer.MAX_VALUE;
		int bottom = -1;
		for (int i : marked) {
			colors |= 1 << Grain.code(field.at(i));
			int y = i / SandRules.WIDTH;
			top = Math.min(top, y);
			bottom = Math.max(bottom, y);
		}
		flashGroups.addLast(marked);
		flashEnds.addLast(time + SandRules.FLASH_MS);
		multiplier = comboLeft > 0 ? Math.min(multiplier + 1, SandRules.MAX_MULTIPLIER) : 1;
		comboLeft = SandRules.COMBO_MS;
		maxMultiplier = Math.max(maxMultiplier, multiplier);
		long points = (long) marked.length * multiplier;
		score += points;
		grainsCleared += marked.length;
		clears++;
		lastClear = new ClearEvent(time, marked.length, multiplier, points, colors, top, bottom);
	}

	// ------------------------------------------------------------------ 읽기 (SandView)

	/** 화면용 복사본을 만든다. {@code reuse} 를 주면 그 배열을 다시 쓴다(null 이면 새로 만든다). 잠금 안에서 부른다. */
	public SandSnapshot snapshot(SandSnapshot reuse) {
		SandSnapshot s = reuse != null ? reuse : new SandSnapshot();
		s.fill(this);
		return s;
	}

	@Override
	public short grain(int gy, int gx) {
		return field.get(gy, gx);
	}

	@Override
	public void copyGrains(short[] dst) {
		field.copyTo(dst);
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
		return piece == null ? null : Movement.hardDrop(grid(), piece);
	}

	@Override
	public void pieceGrains(short[] dst) {
		if (piece == null) {
			return;
		}
		SandColor color = current.color();
		int perCell = SandRules.GRAIN * SandRules.GRAIN;
		for (int k = 0; k < 4; k++) {
			// 블록마다(꺼낸 순번), 칸마다 다른 열쇠. 출력 두 개(128비트)를 알갱이 64개가 2비트씩 명암으로 나눠 쓴다.
			SplitMix64 r = new SplitMix64(noiseBase + ((long) spawns << 2) + k);
			long lo = r.next();
			long hi = r.next();
			for (int i = 0; i < perCell; i++) {
				int shade = (int) ((i < 32 ? lo : hi) >>> ((i & 31) * 2)) & 3;
				dst[k * perCell + i] = Grain.of(color, shade, i / SandRules.GRAIN, i % SandRules.GRAIN);
			}
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
		List<SandPiece> out = new ArrayList<>(SandRules.PREVIEW);
		for (int i = 0; i < SandRules.PREVIEW; i++) {
			out.add(queue.peek(i));
		}
		return Collections.unmodifiableList(out);
	}

	@Override
	public long time() {
		return time;
	}

	@Override
	public int level() {
		return level;
	}

	/** 고른 시작 단계 1~10. */
	public int startLevel() {
		return startLevel;
	}

	/** 지금 중력 g (초당 칸 수 × 1000). */
	public int gravity() {
		return gravity;
	}

	/** 지금 모래 스텝 간격 (ms). */
	public int sandStepMs() {
		return sandStepMs;
	}

	/**
	 * 모래가 멈췄는가: 마지막 모래 스텝에서 움직인 알갱이가 없고, 그 뒤로 놓이거나 사라진 알갱이가 없고, 반짝이는 덩어리도
	 * 없다. 그래서 지금 판이 곧 "모래가 멈춘 판" 이다. 모래 AI 가 목표에 닿은 블록을 언제 떨어뜨릴지 정할 때 쓴다(멈춘 판에
	 * 놓아야 고를 때 예상한 대로 쌓인다).
	 */
	public boolean sandStill() {
		return still;
	}

	@Override
	public long score() {
		return score;
	}

	@Override
	public long best() {
		return Math.max(savedBest, score);
	}

	/** 저장된 기록이 있고(0 보다 크고) 이번 점수가 그보다 높은가. 첫 판은 새 기록으로 치지 않는다(settings 의 기록과 같다). */
	@Override
	public boolean newBest() {
		return savedBest > 0 && score > savedBest;
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

	/** 바닥에서 락 딜레이를 되돌린 횟수. */
	public int resets() {
		return resets;
	}

	@Override
	public int spawns() {
		return spawns;
	}

	@Override
	public int surfaceRow() {
		return field.topRow();
	}

	@Override
	public int settledTop() {
		return field.settledTop();
	}

	@Override
	public int thinGrains() {
		return field.thinCount();
	}

	@Override
	public void copyThin(int[] dst) {
		field.copyThin(dst);
	}

	/** 게임 시드 (결과 화면에 보여 준다). */
	public long seed() {
		return seed;
	}

	/** 알갱이 판의 독립된 복사본 (AI 가 배치를 시뮬레이션할 때). 난수 상태까지 옮긴다. */
	public SandField copyField() {
		return field.copy();
	}

	/** 알갱이가 있는 칸을 막은 core 칸 판의 복사본. AI 가 core {@code PlacementFinder} 로 갈 수 있는 자리를 찾을 때 쓴다. */
	public Grid cellGrid() {
		return grid().copy();
	}

	/** 시험용: 알갱이 판을 직접 본다. */
	SandField field() {
		return field;
	}
}
