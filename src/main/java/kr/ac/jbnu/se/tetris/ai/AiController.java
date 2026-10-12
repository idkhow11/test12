package kr.ac.jbnu.se.tetris.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
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
 * 일꾼을 주면({@link #prefetchWith}) 생각 지연 동안 일꾼 스레드에서 미리 고른다. 고르는 답은 같고, 부르는 스레드가 오래 걸리는
 * 2수 탐색(불가능 단계에서 결정 하나에 중앙값 약 26ms, 길면 0.2초 넘게, 연산 스레드 1)을 기다리지 않게 될 뿐이다. 판 도중
 * 모델이 오류를 내면(실행 실패, NaN 점수) 그 틱에 기준 AI 로 바꿔 다시 고르고 계속 둔다({@link #fallback()}). 바꾸는 일은
 * 언제나 이 조작기를 부르는 스레드가 그 틱에 한다(일꾼이 낸 오류는 버리고 그 자리에서 다시 골라 본다).
 * <p>
 * 스레드: {@link #poll()} 과 지표는 한 스레드(게임 진행 스레드)만 부른다. 일꾼은 판의 복사본만 읽는다.
 */
public final class AiController {

	private final TimedPlayer me;
	private final Supplier<Opponent> opponent;
	private final int thinkMs;
	private final int intervalMs;
	private Brain brain; // 모델이 오류를 내면 기준 AI 두뇌로 바뀐다

	private int seenPieces = -1;
	private String target; // 목표 키에서 홀드 표시("h0|", "h1|")를 뗀 것: spin 과 칸 4 개
	private boolean targetHold;
	private int wait;
	private boolean replannedThisPiece;

	private Executor pool; // 미리 고르기 일꾼, null 이면 생각이 끝나는 틱에 그 자리에서 고른다
	private Prefetch prefetch; // 지금 블록을 미리 고르는 중인 것
	private String fallback;

	private int planned;
	private int failed;
	private int replans;
	private int prefetched;

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

	/**
	 * 생각 지연 동안 {@code pool} 의 일꾼 스레드에서 미리 고른다. 생각이 끝나는 틱에 고를 때 쓰는 값(내 상태, 상대 상태, 둘 수
	 * 있는 배치)이 미리 고를 때와 같으면 그 답을, 다르면 그 자리에서 고른 답을 쓰므로 판은 미리 고르지 않을 때와 똑같다. 일꾼은
	 * 부른 쪽이 만들고 닫는다(일꾼 하나에 AI 하나를 권한다). null 이면 끈다.
	 */
	public void prefetchWith(Executor pool) {
		this.pool = pool;
		if (pool == null) {
			dropPrefetch();
		}
	}

	/** 이번 틱의 조작. 할 것이 없으면 null. 틱마다 정확히 한 번, {@link TimedPlayer#tick} 바로 전에 부른다. */
	public Input poll() {
		GameModel g = me.game();
		if (g.isOver()) {
			dropPrefetch();
			return null;
		}
		if (g.pieces() != seenPieces) { // 고정 뒤 새 블록 (홀드로 꺼낸 블록은 같은 계획을 이어 간다)
			seenPieces = g.pieces();
			target = null;
			wait = thinkMs;
			replannedThisPiece = false;
			dropPrefetch();
		}
		if (wait > 0) {
			wait--;
			if (target == null && pool != null) {
				refreshPrefetch();
			}
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
		Map<String, Placement> all = g.placementsFrom(me.piece());
		Opponent view = opponent.get();
		Brain.Decision d = takePrefetch(g, all, view);
		if (d == null) {
			d = decideNow(g, all, view);
		}
		Placement p = d.placement();
		targetHold = p.hold();
		target = p.key().substring(3);
	}

	/** 미리 고른 답이 지금 고를 답과 같은 입력에서 나왔으면 그 답, 아니면 null. 미리 고르기는 여기서 끝난다. */
	private Brain.Decision takePrefetch(GameModel g, Map<String, Placement> all, Opponent view) {
		Prefetch p = prefetch;
		prefetch = null;
		if (p == null) {
			return null;
		}
		if (!p.sameInputs(brain, g, view)) {
			p.cancel();
			return null;
		}
		Prefetch.Result r = p.await();
		if (r == null || !r.keys().equals(all.keySet())) {
			return null;
		}
		prefetched++;
		return r.decision();
	}

	/**
	 * 그 자리에서 고른다. 모델이 오류를 내면(실행 실패, NaN 점수 등) 기준 AI 로 바꿔 다시 고르고, 이 AI 는 그 뒤로 기준 AI 로
	 * 둔다. 기준 AI 마저 고르지 못하면 모델 문제가 아니므로 예외를 그대로 던진다.
	 */
	private Brain.Decision decideNow(GameModel g, Map<String, Placement> all, Opponent view) {
		try {
			return brain.decide(g, all, view);
		} catch (RuntimeException | LinkageError e) {
			if (brain.scorer() instanceof BaselineScorer) {
				throw e;
			}
			fallback = brain.scorer().name() + ": " + e;
			System.err.println("AI 모델이 오류를 내 기준 AI 로 바꿈 (" + fallback + ")");
			e.printStackTrace();
			brain = brain.withScorer(new BaselineScorer());
			return brain.decide(g, all, view);
		}
	}

	/** 생각 지연 동안 틱마다: 미리 고르기가 없거나 고를 때 쓰는 값이 바뀌었으면 지금 상태로 다시 시작한다. */
	private void refreshPrefetch() {
		if (brain.temperature() > 0) {
			return; // 고르기 난수를 미리 쓰면 판이 달라진다
		}
		GameModel g = me.game();
		Opponent view = opponent.get();
		if (prefetch != null && prefetch.sameInputs(brain, g, view)) {
			return;
		}
		dropPrefetch();
		prefetch = Prefetch.start(pool, brain, g, me.piece(), view);
	}

	private void dropPrefetch() {
		if (prefetch != null) {
			prefetch.cancel();
			prefetch = null;
		}
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

	/** 판 도중 모델 오류로 기준 AI 로 바꿨으면 그 이유(로그용), 아니면 null. */
	public String fallback() {
		return fallback;
	}

	/** 지금 쓰는 두뇌 (기준 AI 로 바뀌었을 수 있다). */
	public Brain brain() {
		return brain;
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

	/** 미리 고르는 중인 것이 없거나 일꾼이 끝냈는가 (시험에서 일꾼을 기다릴 때 쓴다). */
	boolean prefetchSettled() {
		Prefetch p = prefetch;
		return p == null || p.done();
	}

	/** 미리 고른 답을 그대로 쓴 블록 수 ({@link #prefetchWith}). */
	public int prefetched() {
		return prefetched;
	}

	/** 실행 실패율 = 목표를 바꾼 블록 ÷ 목표를 정한 블록. 아직 없으면 0. */
	public double failureRate() {
		return planned == 0 ? 0 : (double) failed / planned;
	}

	public TimedPlayer player() {
		return me;
	}
}
