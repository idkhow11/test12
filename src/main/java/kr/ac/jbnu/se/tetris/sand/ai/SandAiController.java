package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.SandGame;

/**
 * 모래 AI 가 사람과 같은 시간 규칙 안에서 조작한다 (고전 {@code ai.AiController} 와 같은 방식, docs/GAME.md 5장). 틱마다
 * {@link #poll()} 이 이번 틱의 조작을 하나(또는 없음) 돌려주고, 부른 쪽이 그것을 {@link SandGame#advance} 에 넣는다.
 * <ol>
 * <li><b>보기:</b> 고정 뒤 새 블록이 나온 틱에 판을 복사하고({@link Situation}) 이 블록의 난수 값을 하나 뽑는다. 일꾼을
 * 주었으면({@link #prefetchWith}) 그때 일꾼 스레드가 고르기 시작한다.</li>
 * <li><b>생각:</b> 생각 지연 동안 아무것도 하지 않다가, 지연이 끝나는 틱에 고른 결과를 받는다. 일꾼이 아직 끝내지 못했으면
 * 끝날 때까지 기다린다. 고르기는 복사해 둔 판과 난수 값만 보므로 <b>언제 계산했든, 일꾼이 있든 없든 판은 똑같이 흘러간다</b>.</li>
 * <li><b>실행:</b> 목표는 (홀드 여부, 회전, 박스 열)이다. 입력 간격마다 지금 칸 판에서 목표까지 가장 짧은 경로를 다시 구해
 * 첫 조작 하나만 한다({@link Reach}). 홀드 목표면 첫 조작은 홀드다. 목표에 닿으면 하드드롭한다.</li>
 * <li><b>모래 기다리기:</b> 두뇌는 모래가 멈춘 판에 놓는다고 보고 고른다. 그래서 목표에 닿았는데 판의 모래가 아직 움직이면
 * (흐르는 알갱이, 반짝이는 덩어리, 막 부서진 앞 블록, {@link SandGame#sandStill()}) 떨어뜨리지 않고 멈출 때까지 기다린다(틱마다
 * 다시 본다, 많아야 {@value #MAX_PACE_MS}ms). 움직이는 모래 위에 떨어뜨리면 고를 때 예상한 것과 다르게 쌓이기 때문이다. 규칙
 * sand-0.2(약 63° 경사)에서는 블록 하나가 판을 덮지 못해 지우기에 블록 여러 개가 맞아떨어져야 하므로 이것이 특히 중요하다:
 * 판 전체가 멈추기를 기다리자 불가능의 지우기 효율(지운 알갱이 ÷ 떨어뜨린 알갱이)이 75% 에서 97% 로 올랐다(10-07, 8판 ×
 * 240초). sand-0.1 때는 놓일 자리 아래 겉면만 보고 기다렸다. 그동안 중력으로 내려앉으면 블록은 흐르는 모래와 함께 내려가다 멈춘
 * 뒤 고정된다(엔진 규칙).</li>
 * <li><b>바꾸기:</b> 흐르는 모래나 중력 때문에 목표에 갈 수 없게 되면, 고를 때 매긴 순위대로 지금 갈 수 있는 다음 후보로 바꾼다
 * (다시 고르지 않으므로 기다림이 없다). 그런 블록의 비율이 실행 실패율이다. 홀드가 실패하면(꺼낼 블록의 스폰 자리를 모래가
 * 덮음) 홀드하지 않는 후보로 바꾼다.</li>
 * </ol>
 * 스레드: {@link #poll()} 과 지표는 게임 진행 스레드 하나만 부른다. 게임을 바꾸는 스레드가 그 스레드뿐이고 화면은 살아 있는
 * 게임이 아니라 복사본({@code SandSnapshot})만 읽으므로, {@link #poll()} 은 화면의 잠금 밖에서 불러도 된다(고정 뒤 첫 틱의
 * 판 복사, 지연이 끝나는 틱의 기다림이 잠금을 잡지 않게). 일꾼은 판의 복사본만 읽는다. 고르다 예외가 나면(버그) 그 블록은 그
 * 자리에 하드드롭하고 이유를 {@link #problem()} 에 남긴다. 판은 멈추지 않는다.
 */
public final class SandAiController {

	/** 블록마다 뽑는 난수의 스트림 번호 (RULES 5.2 의 1~4, 모래 5~7, 고전 AI 의 101·102 와 겹치지 않는다). */
	public static final int STREAM_P1 = 111;
	public static final int STREAM_P2 = 112;
	/** 목표에 닿은 뒤 판의 모래가 멈추기를 기다리는 시간의 상한 (ms). */
	public static final int MAX_PACE_MS = 1000;

	private enum HoldState {
		NONE, TRIED, DONE, FAILED
	}

	private final SandGame game;
	private final SandBrain brain;
	private final Planner planner;
	private final int thinkMs;
	private final int intervalMs;
	private final SplitMix64 rng;

	private Executor pool;
	private int seenPieces = -1;
	private Situation situation; // 이번 블록을 고를 때 볼 것 (블록이 나온 틱의 복사본)
	private long noise;
	private Job job;
	private SandPlan plan;
	private SandPlan.Target target;
	private boolean decided;
	private HoldState holdState = HoldState.NONE;
	private boolean switchedThisPiece;
	private int wait;
	private long paceStart = -1; // 목표에 닿아 기다리기 시작한 때, 아니면 -1

	private int planned;
	private int failed;
	private int switches;
	private int prefetched;
	private int holdFailures;
	private long waitedNanos;
	private int paced;
	private long pacedMs;
	private String problem;

	/**
	 * @param level 단계 (조작 속도와 두뇌)
	 * @param seed  판 시드. 블록마다 뽑는 난수 스트림을 정한다
	 * @param seat  자리 1 또는 2 (같은 시드의 두 AI 가 다른 난수를 쓰게)
	 */
	public SandAiController(SandGame game, SandAiLevel level, long seed, int seat) {
		this(game, new SandBrain(level), level.thinkMs(), level.inputsPerSec(), rng(seed, seat));
	}

	/**
	 * @param thinkMs      생각 지연 (블록이 나온 뒤 첫 입력까지, ms)
	 * @param inputsPerSec 초당 입력 수
	 * @param rng          블록마다 난수 값을 하나씩 뽑는다 ({@link #rng(long, int)})
	 */
	public SandAiController(SandGame game, SandBrain brain, int thinkMs, double inputsPerSec, SplitMix64 rng) {
		this(game, brain, brain, thinkMs, inputsPerSec, rng);
	}

	/** 시험용: 두뇌 대신 {@code planner} 로 고른다 ({@link #brain()} 은 null 일 수 있다). */
	SandAiController(SandGame game, SandBrain brain, Planner planner, int thinkMs, double inputsPerSec, SplitMix64 rng) {
		if (thinkMs < 0 || inputsPerSec <= 0 || inputsPerSec > 1000) {
			throw new IllegalArgumentException("생각 지연 " + thinkMs + "ms, 초당 입력 " + inputsPerSec);
		}
		this.game = game;
		this.brain = brain;
		this.planner = planner;
		this.thinkMs = thinkMs;
		this.intervalMs = Math.max(1, (int) Math.round(1000 / inputsPerSec));
		this.rng = rng;
	}

	/** 판 시드와 자리(1 또는 2)로 정해지는 블록 난수. */
	public static SplitMix64 rng(long seed, int seat) {
		if (seat != 1 && seat != 2) {
			throw new IllegalArgumentException("자리는 1 또는 2: " + seat);
		}
		return SplitMix64.stream(seed, seat == 1 ? STREAM_P1 : STREAM_P2);
	}

	/**
	 * 블록이 나온 틱부터 {@code pool} 의 일꾼 스레드에서 고른다. 고르는 답은 일꾼이 없을 때와 같고, 생각 지연이 끝나는 틱에
	 * 진행 스레드가 계산하느라 멈추지 않게 될 뿐이다. 일꾼은 부른 쪽이 만들고 닫는다(AI 하나에 일꾼 하나를 권한다). null 이면 끈다.
	 */
	public void prefetchWith(Executor pool) {
		this.pool = pool;
		if (pool == null) {
			dropJob();
		}
	}

	/** 이번 틱의 조작. 할 것이 없으면 null. 틱마다 정확히 한 번, {@link SandGame#advance} 바로 전에 부른다. */
	public Input poll() {
		if (game.over()) {
			dropJob();
			return null;
		}
		if (game.pieces() != seenPieces) { // 고정 뒤 새 블록 (홀드로 꺼낸 블록은 같은 계획을 이어 간다)
			seenPieces = game.pieces();
			dropJob();
			situation = null;
			plan = null;
			target = null;
			decided = false;
			holdState = HoldState.NONE;
			switchedThisPiece = false;
			endPace(); // 기다리던 블록이 스스로 고정됐을 수 있다
		}
		if (game.piece() == null) {
			return null; // 다음 블록이 스폰 자리의 모래가 비켜나기를 기다리는 중
		}
		if (situation == null) {
			situation = Situation.of(game);
			noise = rng.next();
			wait = thinkMs;
			submit();
		}
		if (wait > 0) {
			wait--;
			return null;
		}
		if (!decided) {
			plan = takePlan();
			target = plan.target();
			decided = true;
			planned++;
		}
		wait = intervalMs - 1;
		return step();
	}

	// ------------------------------------------------------------------ 생각

	/** 일꾼 스레드의 고르기 하나. 아직 시작하지 않은 것은 버리고 그 자리에서 고른다(앞에서 버린 고르기가 일꾼을 붙잡고 있을 때). */
	private static final class Job {
		final AtomicBoolean started = new AtomicBoolean();
		final FutureTask<SandPlan> task;

		Job(Planner planner, Situation s, long noise) {
			task = new FutureTask<>(() -> {
				started.set(true);
				return planner.plan(s, noise);
			});
		}
	}

	private void submit() {
		if (pool == null) {
			return;
		}
		Job j = new Job(planner, situation, noise);
		try {
			pool.execute(j.task);
			job = j;
		} catch (RejectedExecutionException e) {
			job = null; // 닫는 중: 지연이 끝나는 틱에 그 자리에서 고른다
		}
	}

	private void dropJob() {
		if (job != null) {
			job.task.cancel(false);
			job = null;
		}
	}

	/**
	 * 고른 결과. 일꾼이 하고 있으면 끝날 때까지 기다리고, 일꾼이 없거나 아직 시작하지 않았거나 실패했으면 그 자리에서 고른다.
	 * 어느 쪽이든 같은 입력({@link #situation}, {@link #noise})이라 같은 답이다. 그 자리에서도 실패하면 빈 계획(그 자리에
	 * 하드드롭)이다.
	 */
	private SandPlan takePlan() {
		Job j = job;
		job = null;
		if (j != null) {
			if (!j.started.get()) {
				j.task.cancel(false);
			}
			if (!j.task.isCancelled()) {
				long t0 = System.nanoTime();
				try {
					SandPlan p = j.task.get();
					prefetched++;
					return p;
				} catch (ExecutionException | CancellationException e) {
					// 그 자리에서 다시 고른다
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt(); // 멈추는 중: 그 자리에서 고르고 부른 쪽이 멈추게 둔다
				} finally {
					waitedNanos += System.nanoTime() - t0;
				}
			}
		}
		try {
			return planner.plan(situation, noise);
		} catch (RuntimeException e) {
			if (problem == null) {
				System.err.println("모래 AI 가 고르지 못해 그 자리에 둠: " + e);
				e.printStackTrace();
			}
			problem = String.valueOf(e);
			return new SandPlan(List.of(), -1, 0, 0);
		}
	}

	// ------------------------------------------------------------------ 실행

	private Input step() {
		if (holdState == HoldState.TRIED) { // 지난 입력이 홀드였다
			holdState = game.holdUsed() ? HoldState.DONE : HoldState.FAILED;
			if (holdState == HoldState.FAILED) {
				holdFailures++;
			}
		}
		if (target != null && target.hold() && holdState == HoldState.NONE) {
			holdState = HoldState.TRIED;
			return Input.H;
		}
		PieceState p = game.piece();
		if (target == null || !fits(target) || p.rotation() != target.rotation() || p.x() != target.x()) {
			Reach reach = Reach.from(game.cellGrid(), p);
			if (target == null || !fits(target) || !reach.reachable(target.rotation(), target.x())) {
				switchTarget(reach);
			}
			if (target == null) {
				return Input.HD; // 갈 곳이 없다: 그 자리에 둔다
			}
			Op op = reach.first(target.rotation(), target.x());
			if (op != null) {
				return input(op);
			}
		}
		// 목표에 닿았다 (중력으로 내려왔어도 회전과 박스 열이 같으면 같은 자리에 놓인다)
		if (sandStillFalling()) {
			wait = 0; // 다음 틱에 다시 본다
			return null;
		}
		endPace();
		return Input.HD;
	}

	/**
	 * 목표에 닿은 블록을 아직 떨어뜨리지 말아야 하는가: 판의 모래가 아직 움직인다({@link SandGame#sandStill()} 이 아니다). 기다리기
	 * 시작한 지 {@value #MAX_PACE_MS}ms 가 지났으면 false (그냥 떨어뜨린다).
	 */
	private boolean sandStillFalling() {
		if (game.sandStill()) {
			return false;
		}
		long now = game.time();
		if (paceStart < 0) {
			paceStart = now;
			paced++;
		}
		return now - paceStart < MAX_PACE_MS;
	}

	/** 모래를 기다리던 중이면 그 시간을 지표에 더하고 끝낸다. */
	private void endPace() {
		if (paceStart >= 0) {
			pacedMs += game.time() - paceStart;
			paceStart = -1;
		}
	}

	/** 목표가 지금 블록에 맞는가: 홀드 목표는 홀드한 뒤의 블록, 아니면 홀드하지 않은 지금 블록. */
	private boolean fits(SandPlan.Target t) {
		if (!t.piece().equals(game.current())) {
			return false;
		}
		return t.hold() ? holdState == HoldState.DONE : holdState != HoldState.DONE;
	}

	/** 순위대로 지금 블록에 맞고 지금 갈 수 있는 다음 후보로 바꾼다. 없으면 목표 없음(그 자리에 둔다). */
	private void switchTarget(Reach reach) {
		SandPlan.Target old = target;
		target = null;
		if (plan != null) {
			for (SandPlan.Target t : plan.ranked()) {
				if (t != old && fits(t) && reach.reachable(t.rotation(), t.x())) {
					target = t;
					break;
				}
			}
		}
		if (old != null) {
			switches++;
			if (!switchedThisPiece) {
				switchedThisPiece = true;
				failed++;
			}
		}
	}

	private static Input input(Op op) {
		switch (op) {
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

	// ------------------------------------------------------------------ 지표

	/** 새 블록을 보고 생각하는 중이면 그 진행률(0~1), 조작하는 중이거나 기다리는 중이거나 끝났으면 -1. 화면의 "생각 중" 표시용. */
	public double thinkProgress() {
		if (decided || situation == null || thinkMs <= 0 || game.over()) {
			return -1;
		}
		return 1 - Math.max(0, wait) / (double) thinkMs;
	}

	/** 지금 블록의 계획, 아직 고르지 않았으면 null. */
	public SandPlan plan() {
		return decided ? plan : null;
	}

	/** 지금 목표, 없으면 null. */
	public SandPlan.Target target() {
		return target;
	}

	/** 고른 블록 수. */
	public int planned() {
		return planned;
	}

	/** 목표를 한 번이라도 바꾼 블록 수 (홀드 실패 포함). */
	public int failed() {
		return failed;
	}

	/** 목표를 바꾼 횟수 (한 블록에서 여러 번일 수 있다). */
	public int switches() {
		return switches;
	}

	/** 홀드가 실패한 횟수. */
	public int holdFailures() {
		return holdFailures;
	}

	/** 실행 실패율 = 목표를 바꾼 블록 ÷ 고른 블록. 아직 없으면 0. */
	public double failureRate() {
		return planned == 0 ? 0 : (double) failed / planned;
	}

	/** 일꾼이 고른 답을 받은 블록 수 ({@link #prefetchWith}). */
	public int prefetched() {
		return prefetched;
	}

	/** 생각 지연이 끝나는 틱에 일꾼을 기다린 시간의 합 (ns). */
	public long waitedNanos() {
		return waitedNanos;
	}

	/** 목표에 닿은 뒤 판의 모래가 멈추기를 기다린 블록 수. */
	public int paced() {
		return paced;
	}

	/** 모래가 멈추기를 기다린 시간의 합 (ms). */
	public long pacedMs() {
		return pacedMs;
	}

	/** 고르다 예외가 났으면 그 이유 (로그·화면 알림용), 아니면 null. */
	public String problem() {
		return problem;
	}

	/** 일꾼이 맡은 고르기가 없거나 끝났는가 (시험에서 일꾼을 기다릴 때 쓴다). */
	boolean jobSettled() {
		Job j = job;
		return j == null || j.task.isDone();
	}

	public SandBrain brain() {
		return brain;
	}

	public SandGame game() {
		return game;
	}
}
