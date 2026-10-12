package kr.ac.jbnu.se.tetris.core;

import java.util.Arrays;

/**
 * 가상 시간 모드 (RULES 12.3, docs/GAME.md 3.3): 한 명(혼자 연습) 또는 두 명을 공통 시계로 1ms 씩 진행한다. 실제 게임은
 * 이것을 실제 시간으로 돌린 것이고, 헤드리스 시험은 실제 시간보다 빠르게 돌린다.
 * <p>
 * 한 틱 안에서는 P1 의 처리를 모두 마친 뒤 P2 를 처리한다. 틱 안에서 생긴 송신은 틱이 끝날 때 상대 대기열에 붙이므로
 * 처리 순서가 결과에 영향을 주지 않는다. 패배는 두 플레이어의 처리가 끝난 뒤 판정하고, 같은 틱에 둘 다 지면 무승부다.
 */
public final class TimedMatch {

	/** 중력 단계가 오르는 간격 (RULES 6.3). */
	public static final int LEVEL_MS = 30_000;
	/** 중력 단계별 g = 초당 칸 수 × 1000 (RULES 6.3 표). */
	private static final int[] GRAVITY = { 1000, 1500, 2000, 3000, 4000, 5000, 6500, 8000, 10000, 12000 };

	/** 판의 결과. */
	public enum Result {
		/** 아직 진행 중. */
		NONE,
		/** P1 승 (혼자 연습에서는 쓰지 않는다). */
		P1,
		/** P2 승. */
		P2,
		/** 같은 틱에 둘 다 패배. */
		DRAW,
		/** 혼자 연습이 끝남(패배). */
		OVER
	}

	private final TimedPlayer[] players;
	private final int maxLevel;
	private final GameModel.LockResult[] lastLocks;
	private long time;
	private int level;
	private Result result = Result.NONE;

	/**
	 * @param seed         게임 시드 (RULES 5.2). 두 플레이어가 같은 순서의 블록을 받는다
	 * @param players      1(혼자 연습) 또는 2
	 * @param scheduler    혼자 연습의 가비지 스케줄러 (RULES 13장). 두 명이면 {@link GameModel.Scheduler#NONE} 이어야 한다
	 * @param gravityCapG  중력 상한 g (초당 칸 수 × 1000). 이 값을 넘지 않는 가장 높은 단계까지 오른다. 0 이면 중력 없음
	 */
	public TimedMatch(long seed, int players, GameModel.Scheduler scheduler, int gravityCapG) {
		this(gravityCapG, games(seed, players, scheduler));
	}

	/** 게임을 직접 넘긴다. 처음 보드를 정해 두는 시험용. */
	TimedMatch(int gravityCapG, GameModel... games) {
		this.maxLevel = gravityCapG <= 0 ? -1 : maxLevel(gravityCapG);
		this.players = new TimedPlayer[games.length];
		for (int i = 0; i < games.length; i++) {
			this.players[i] = new TimedPlayer(games[i], gravityAt(0));
		}
		this.lastLocks = new GameModel.LockResult[games.length];
	}

	private static GameModel[] games(long seed, int players, GameModel.Scheduler scheduler) {
		if (players != 1 && players != 2) {
			throw new IllegalArgumentException("플레이어는 1 또는 2 명: " + players);
		}
		if (players == 2 && scheduler != GameModel.Scheduler.NONE) {
			throw new IllegalArgumentException("대전에는 가비지 스케줄러를 쓰지 않는다");
		}
		GameModel[] out = new GameModel[players];
		for (int i = 0; i < players; i++) {
			out[i] = new GameModel(seed, i + 1, scheduler);
		}
		return out;
	}

	/** g 상한을 넘지 않는 가장 높은 단계. 가장 낮은 단계보다 작으면 0 단계(초당 1칸)다. */
	static int maxLevel(int capG) {
		int level = 0;
		for (int i = 0; i < GRAVITY.length; i++) {
			if (GRAVITY[i] <= capG) {
				level = i;
			}
		}
		return level;
	}

	private int gravityAt(int lvl) {
		return maxLevel < 0 ? 0 : GRAVITY[Math.min(lvl, maxLevel)];
	}

	/**
	 * 1ms 진행한다. 판이 이미 끝났으면 아무것도 하지 않는다.
	 *
	 * @param inputs 플레이어마다 이번 틱의 조작(없으면 null). 길이는 플레이어 수
	 */
	public void tick(TimedPlayer.Input... inputs) {
		if (inputs.length != players.length) {
			throw new IllegalArgumentException("조작은 플레이어마다 하나: " + inputs.length);
		}
		if (result != Result.NONE) {
			return;
		}
		time++;
		int lvl = (int) Math.min(time / LEVEL_MS, Integer.MAX_VALUE);
		if (lvl != level) {
			level = lvl;
			for (TimedPlayer p : players) {
				p.setGravity(gravityAt(level));
			}
		}
		Arrays.fill(lastLocks, null);
		for (int i = 0; i < players.length; i++) {
			lastLocks[i] = players[i].tick(inputs[i]);
		}
		// 틱이 끝날 때 송신을 상대 대기열에 붙인다. 판이 끝났으면 붙이지 않는다(결과에 영향이 없다).
		boolean over1 = players[0].game().isOver();
		if (players.length == 1) {
			if (over1) {
				result = Result.OVER;
			}
			return;
		}
		boolean over2 = players[1].game().isOver();
		if (over1 || over2) {
			result = over1 && over2 ? Result.DRAW : over1 ? Result.P2 : Result.P1;
			return;
		}
		if (lastLocks[0] != null) {
			players[1].game().receive(lastLocks[0].sent());
		}
		if (lastLocks[1] != null) {
			players[0].game().receive(lastLocks[1].sent());
		}
	}

	/** 플레이어 i(0 부터). */
	public TimedPlayer player(int i) {
		return players[i];
	}

	public int size() {
		return players.length;
	}

	/** 이번 틱에 플레이어 i 가 고정한 결과, 없으면 null. */
	public GameModel.LockResult lastLock(int i) {
		return lastLocks[i];
	}

	/** 판 시작부터 흐른 시간 (ms). */
	public long time() {
		return time;
	}

	/** 지금 중력 단계 (RULES 6.3 표의 번호). 중력이 없으면 -1. */
	public int gravityLevel() {
		return maxLevel < 0 ? -1 : Math.min(level, maxLevel);
	}

	public Result result() {
		return result;
	}

	public boolean isOver() {
		return result != Result.NONE;
	}
}
