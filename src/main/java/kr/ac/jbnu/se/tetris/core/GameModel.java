package kr.ac.jbnu.se.tetris.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;

/**
 * 한 플레이어의 한 판 (RULES 4, 7, 10, 11, 13장). 시간 없는 모드(RULES 12.1): 배치 하나를 고르면 곧바로 고정한다.
 * <p>
 * 화면과 무관한 순수 규칙 상태이며 결정론적이다. 같은 시드, 같은 배치 순서면 언제나 같은 결과가 나온다.
 * 두 플레이어 대전은 이 객체 두 개와 {@link #receive(int)} 로 만든다(RULES 12.2, 12.3).
 * 시간(중력, 락 딜레이)은 {@link TimedPlayer} 가 맡고, 이 객체에는 실시간 조작에 필요한 것만 있다: 블록 도중의 홀드
 * ({@link #holdNow()}), 움직이던 블록을 그 자리에서 고정({@link #lock(PieceState)}), 블록 도중 상태에서의 배치 탐색
 * ({@link #placementsFrom(PieceState)}).
 */
public final class GameModel {

	/** 미리보기 개수 (RULES 4장). */
	public static final int PREVIEW = 5;

	/** 패배 종류 (RULES 11장). */
	public enum Topout {
		NONE, BLOCK_OUT, LOCK_OUT, GARBAGE_OUT;

		public String json() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** 1인용 가비지 스케줄러 (RULES 13장). N 번째, 2N 번째, … 고정 뒤에 공격 하나를 붙인다. */
	public enum Scheduler {
		NONE("none", 0), S_3("S-3", 10), S_MIX("S-mix", 10);

		private final String json;
		private final int every;

		Scheduler(String json, int every) {
			this.json = json;
			this.every = every;
		}

		public String json() {
			return json;
		}

		public static Scheduler fromJson(String s) {
			for (Scheduler v : values()) {
				if (v.json.equals(s)) {
					return v;
				}
			}
			throw new IllegalArgumentException("모르는 스케줄러: " + s);
		}

		int lines(SplitMix64 rng) {
			return this == S_3 ? 3 : 2 + rng.nextInt(3);
		}
	}

	/** 고정 한 번의 결과. */
	public static final class LockResult {
		private final Spin spin;
		private final int lines;
		private final boolean perfectClear;
		private final int attack;
		private final int sent;
		private final int garbageRisen;
		private final Topout topout;

		LockResult(Spin spin, int lines, boolean perfectClear, int attack, int sent, int garbageRisen, Topout topout) {
			this.spin = spin;
			this.lines = lines;
			this.perfectClear = perfectClear;
			this.attack = attack;
			this.sent = sent;
			this.garbageRisen = garbageRisen;
			this.topout = topout;
		}

		public Spin spin() {
			return spin;
		}

		public int lines() {
			return lines;
		}

		public boolean perfectClear() {
			return perfectClear;
		}

		/** 상쇄 전 공격 (RULES 9.3). */
		public int attack() {
			return attack;
		}

		/** 상쇄하고 남아 상대에게 보낼 양 (RULES 7장 7단계). 1인용에서는 버려진다. */
		public int sent() {
			return sent;
		}

		/** 이번 고정에서 올라온 가비지 줄 수. */
		public int garbageRisen() {
			return garbageRisen;
		}

		/** 이 고정으로 게임이 끝났으면 그 종류, 아니면 NONE. */
		public Topout topout() {
			return topout;
		}
	}

	private final Grid grid;
	private final SevenBag bag;
	private final GarbageQueue garbage;
	private final Scheduler scheduler;
	private final SplitMix64 schedulerRng;

	private PieceType current;
	private PieceType hold;
	private boolean holdUsed; // 지금 블록에서 홀드를 썼는가 (RULES 4장: 블록 하나당 1회)
	private int combo = -1;
	private boolean b2b;
	private Topout topout = Topout.NONE;

	private int pieces;
	private int linesTotal;
	private int attackTotal;
	private int sentTotal;

	/**
	 * @param seed      게임 시드 (RULES 5.2). 두 플레이어가 같은 값을 쓰면 블록 순서가 같다
	 * @param player    1 또는 2. 가비지 구멍 난수 스트림을 고른다
	 * @param scheduler 1인용 가비지 스케줄러. 대전이면 {@link Scheduler#NONE}
	 */
	public GameModel(long seed, int player, Scheduler scheduler) {
		this(seed, player, scheduler, new Grid());
	}

	/** 처음 보드를 정해 시작한다. 픽스처와 테스트용. 보드는 복사해서 쓴다. */
	public GameModel(long seed, int player, Scheduler scheduler, Grid initial) {
		if (player != 1 && player != 2) {
			throw new IllegalArgumentException("player 는 1 또는 2: " + player);
		}
		this.grid = initial.copy();
		this.bag = new SevenBag(seed);
		this.garbage = new GarbageQueue(
				SplitMix64.stream(seed, player == 1 ? SplitMix64.STREAM_GARBAGE_P1 : SplitMix64.STREAM_GARBAGE_P2));
		this.scheduler = scheduler;
		this.schedulerRng = SplitMix64.stream(seed, SplitMix64.STREAM_SCHEDULER);
		spawn(bag.next());
	}

	private GameModel(GameModel o) {
		this.grid = o.grid.copy();
		this.bag = o.bag.copy();
		this.garbage = o.garbage.copy();
		this.scheduler = o.scheduler;
		this.schedulerRng = o.schedulerRng.copy();
		this.current = o.current;
		this.hold = o.hold;
		this.holdUsed = o.holdUsed;
		this.combo = o.combo;
		this.b2b = o.b2b;
		this.topout = o.topout;
		this.pieces = o.pieces;
		this.linesTotal = o.linesTotal;
		this.attackTotal = o.attackTotal;
		this.sentTotal = o.sentTotal;
	}

	/** 모든 상태(보드, 가방, 가비지, 난수)를 옮긴 독립된 복사본. AI 가 몇 수 앞을 미리 둘 때 쓴다. */
	public GameModel copy() {
		return new GameModel(this);
	}

	// ------------------------------------------------------------------ 진행

	/**
	 * 지금 둘 수 있는 모든 최종 배치 (RULES 14장). 현재 블록의 배치와, 홀드를 쓰는 배치(키가 {@code h1})를 함께 준다.
	 * 홀드 블록이 현재 블록과 같거나 이 블록에서 이미 홀드를 썼으면 홀드 배치는 빼고, 게임이 끝났으면 비어 있다.
	 * 락 아웃이 되는 배치도 규칙상 둘 수 있는 수라서 들어 있다.
	 */
	public Map<String, Placement> placements() {
		return isOver() ? new LinkedHashMap<>() : placementsFrom(PieceState.spawn(current));
	}

	/**
	 * 블록 도중의 상태 {@code start} 에서 둘 수 있는 배치 (RULES 14.1 "블록 도중 재결정"). 시작 상태의 마지막 조작과 킥
	 * 번호를 이어받는다. 홀드 배치는 {@link #placements()} 와 같은 조건으로 스폰 상태에서 찾는다.
	 */
	public Map<String, Placement> placementsFrom(PieceState start) {
		if (start.type() != current) {
			throw new IllegalArgumentException("움직이는 블록은 " + current + " 인데 시작 상태는 " + start.type());
		}
		Map<String, Placement> all = new LinkedHashMap<>();
		if (isOver()) {
			return all;
		}
		all.putAll(PlacementFinder.find(grid, start, false));
		if (!holdUsed && hold != current) {
			all.putAll(PlacementFinder.find(grid, PieceState.spawn(holdOutPiece()), true));
		}
		return all;
	}

	/** 정규화 키로 배치를 골라 둔다. 그 키가 지금 둘 수 있는 배치가 아니면 IllegalArgumentException. */
	public LockResult applyKey(String key) {
		Placement p = placements().get(key);
		if (p == null) {
			throw new IllegalArgumentException("지금 둘 수 없는 배치: " + key);
		}
		return apply(p);
	}

	/**
	 * 배치 하나를 두고 RULES 7장 순서대로 고정한다. {@link #placements()} 가 준 배치만 넣어야 한다.
	 * 도달 가능성은 다시 확인하지 않고, 블록 종류와 "놓일 수 있고 바닥에 닿아 있음"만 확인한다.
	 */
	public LockResult apply(Placement p) {
		if (p.hold() && holdUsed) {
			throw new IllegalArgumentException("이 블록은 이미 홀드를 썼다: " + p.key());
		}
		check(p.hold() ? holdOutPiece() : current, p.state());

		// RULES 4장: 홀드가 비었으면 현재 블록을 넣고 다음 블록(p1)을 쓴다. 차 있으면 맞바꾼다.
		if (p.hold()) {
			if (hold == null) {
				bag.next();
			}
			hold = current;
		}
		return lockState(p.state());
	}

	/**
	 * 움직이던 블록을 상태 {@code s} 그대로 고정한다(실시간 조작의 하드드롭·락 딜레이 만료, GAME.md 3장). 홀드는
	 * {@link #holdNow()} 로 이미 했어야 한다. {@code s} 는 지금 블록이고, 놓일 수 있고 바닥에 닿아 있어야 한다.
	 */
	public LockResult lock(PieceState s) {
		check(current, s);
		return lockState(s);
	}

	/**
	 * 지금 블록을 홀드한다 (RULES 4장). 홀드가 비었으면 지금 블록을 넣고 다음 블록을, 차 있으면 맞바꿔 꺼낸 블록을
	 * 스폰한다. 이 블록에서 이미 홀드를 썼거나 게임이 끝났으면 아무것도 하지 않고 false. 스폰에서 블록 아웃이 날 수 있다.
	 */
	public boolean holdNow() {
		if (isOver() || holdUsed) {
			return false;
		}
		PieceType out = hold != null ? hold : bag.next();
		hold = current;
		holdUsed = true;
		spawn(out);
		return true;
	}

	private void check(PieceType expected, PieceState s) {
		if (isOver()) {
			throw new IllegalStateException("이미 끝난 게임: " + topout);
		}
		if (s.type() != expected) {
			throw new IllegalArgumentException("놓을 블록은 " + expected + " 인데 배치는 " + s.type());
		}
		if (!grid.fits(s.type(), s.rotation(), s.x(), s.y()) || !Movement.isGrounded(grid, s)) {
			throw new IllegalArgumentException("놓일 수 없거나 바닥에 닿지 않은 배치: " + s);
		}
	}

	/** RULES 7장 순서대로 고정한다. 홀드 처리는 끝난 뒤다. */
	private LockResult lockState(PieceState s) {
		Locking.Outcome out = Locking.lock(grid, s); // 1~5단계
		if (out.lockOut()) {
			topout = Topout.LOCK_OUT;
			return new LockResult(out.spin(), 0, false, 0, 0, 0, topout);
		}
		Attack a = Attack.compute(combo, b2b, out.lines(), out.spin(), out.perfectClear()); // 6단계
		combo = a.combo();
		b2b = a.b2b();
		int sent = garbage.cancel(a.attack()); // 7단계
		pieces++;
		linesTotal += out.lines();
		attackTotal += a.attack();
		sentTotal += sent;

		int risen = 0;
		if (out.lines() == 0) { // 8단계
			int before = garbage.totalLines();
			if (garbage.rise(grid)) {
				topout = Topout.GARBAGE_OUT;
				return new LockResult(out.spin(), 0, false, a.attack(), sent, 0, topout);
			}
			risen = before - garbage.totalLines();
		}
		if (scheduler != Scheduler.NONE && pieces % scheduler.every == 0) { // 13장: 9단계 전
			garbage.receive(scheduler.lines(schedulerRng));
		}
		holdUsed = false; // 9단계
		spawn(bag.next());
		return new LockResult(out.spin(), out.lines(), out.perfectClear(), a.attack(), sent, risen, topout);
	}

	/** 상대가 보낸 공격 하나를 받는다 (RULES 10.2). 송신량 0 은 무시된다. */
	public void receive(int lines) {
		garbage.receive(lines);
	}

	private void spawn(PieceType next) {
		current = next;
		if (Locking.isBlockOut(grid, current)) {
			topout = Topout.BLOCK_OUT;
		}
	}

	/** 홀드를 쓰면 놓게 되는 블록: 홀드 블록, 홀드가 비었으면 미리보기 첫 블록. */
	private PieceType holdOutPiece() {
		return hold != null ? hold : bag.peek(0);
	}

	// ------------------------------------------------------------------ 상태 읽기

	/** 보드의 복사본. */
	public Grid grid() {
		return grid.copy();
	}

	/** 보드를 복사 없이 넘긴다. 같은 패키지의 {@link TimedPlayer} 만 읽기용으로 쓴다. */
	Grid gridView() {
		return grid;
	}

	/** 칸 값. 복사 없이 읽는다. */
	public int cell(int row, int col) {
		return grid.get(row, col);
	}

	/** 지금 놓을 차례인 블록. 게임이 끝났어도 마지막 값을 돌려준다. */
	public PieceType current() {
		return current;
	}

	/** 미리보기 p1..p5 (다음 블록이 먼저). */
	public List<PieceType> preview() {
		List<PieceType> out = new ArrayList<>(PREVIEW);
		for (int i = 0; i < PREVIEW; i++) {
			out.add(bag.peek(i));
		}
		return Collections.unmodifiableList(out);
	}

	/** 홀드 칸, 비었으면 null. */
	public PieceType hold() {
		return hold;
	}

	/** 지금 블록에서 홀드를 이미 썼는가. 썼으면 블록이 고정될 때까지 다시 홀드할 수 없다. */
	public boolean holdUsed() {
		return holdUsed;
	}

	/** 지금까지의 콤보 (RULES 9.1, 없으면 -1). */
	public int combo() {
		return combo;
	}

	public boolean b2b() {
		return b2b;
	}

	/** 받을 가비지 대기열 (앞이 먼저 받은 것). */
	public List<GarbageQueue.Chunk> pending() {
		return garbage.chunks();
	}

	public int pendingLines() {
		return garbage.totalLines();
	}

	public Topout topout() {
		return topout;
	}

	public boolean isOver() {
		return topout != Topout.NONE;
	}

	public Scheduler scheduler() {
		return scheduler;
	}

	/** 패배 없이 고정한 블록 수. */
	public int pieces() {
		return pieces;
	}

	public int linesTotal() {
		return linesTotal;
	}

	/** 상쇄 전 공격의 합. */
	public int attackTotal() {
		return attackTotal;
	}

	/** 상쇄하고 남은 송신량의 합. 1인용에서는 실제로 보내지 않지만 센다. */
	public int sentTotal() {
		return sentTotal;
	}
}
