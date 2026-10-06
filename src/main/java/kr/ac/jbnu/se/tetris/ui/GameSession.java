package kr.ac.jbnu.se.tetris.ui;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.ai.WinRate;
import kr.ac.jbnu.se.tetris.core.Attack;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.train.TimedRecorder;

/**
 * 한 판을 실제 시간으로 진행한다 (docs/GAME.md 2, 3장). 게임 진행 스레드 하나가 흐른 시간만큼 {@link TimedMatch} 를 1ms
 * 씩 돌리고, 화면은 {@link #lock} 을 잡고 상태를 읽어 그린다. 사람은 언제나 P1 이다.
 */
final class GameSession {

	/** 시작 전 카운트다운 (3, 2, 1). 판의 시계는 그 뒤부터 간다. */
	static final int COUNTDOWN_MS = 3000;
	/** 시작한 뒤 "GO!" 를 보여 주는 시간. */
	static final int GO_MS = 700;

	/** 판 설정. 사람 대 AI 면 {@code difficulty} 와 {@code persona}, 혼자 연습이면 {@code gravityCapG} 와 {@code scheduler}. */
	record Settings(boolean versusAi, AiCatalog.Difficulty difficulty, AiCatalog.Persona persona, int gravityCapG,
			GameModel.Scheduler scheduler) {
	}

	/** 플레이어 하나의 화면용 통계 (GameModel 이 세지 않는 것). */
	static final class Tally {
		int tspins;
		int tetrises;
		int maxCombo = -1;
		int perfectClears;
		String lastAction = "";
		long lastActionAt = -10_000;
		private boolean b2bBefore; // 이번 고정 전의 B2B 상태
		private int comboBefore = -1; // 이번 고정 전의 콤보

		// 마지막으로 줄을 지운 고정 (화면의 동작 기록: "2줄 → 공격 3 · 기본 2 + 콤보 1")
		long clearAt = -10_000;
		int clearLines;
		Spin clearSpin = Spin.NONE;
		int clearAttack;
		int clearSent;
		int clearCombo = -1;
		int[] clearParts = new int[4]; // 기본, B2B, 콤보, 퍼펙트 클리어 (Attack.breakdown)
		// 마지막으로 상대에게 보낸 공격 (날아가는 "+N" 표시)
		long sentAt = -10_000;
		int sentLines;
		// 마지막으로 방해 줄이 올라온 때
		long risenAt = -10_000;
		int risenLines;

		void add(GameModel.LockResult r, GameModel g, long time) {
			int[] parts = Attack.breakdown(comboBefore, b2bBefore, r.lines(), r.spin(), r.perfectClear());
			if (r.lines() > 0) {
				clearAt = time;
				clearLines = r.lines();
				clearSpin = r.spin();
				clearAttack = r.attack();
				clearSent = r.sent();
				clearCombo = g.combo();
				clearParts = parts;
			}
			if (r.sent() > 0) {
				sentAt = time;
				sentLines = r.sent();
			}
			if (r.garbageRisen() > 0) {
				risenAt = time;
				risenLines = r.garbageRisen();
			}
			StringBuilder s = new StringBuilder();
			if (r.spin() != Spin.NONE && r.lines() > 0) {
				tspins++;
				s.append(r.spin() == Spin.MINI ? "T-SPIN MINI " : "T-SPIN ");
				s.append(new String[] { "", "SINGLE", "DOUBLE", "TRIPLE" }[r.lines()]);
			} else if (r.lines() == 4) {
				tetrises++;
				s.append("TETRIS");
			}
			if (r.perfectClear()) {
				perfectClears++;
				s.append(s.length() > 0 ? "  " : "").append("PERFECT CLEAR");
			}
			if (r.lines() > 0 && g.combo() >= 1) {
				s.append(s.length() > 0 ? "  " : "").append(g.combo()).append(" COMBO");
			}
			maxCombo = Math.max(maxCombo, g.combo());
			if (s.length() > 0) {
				if (b2bBefore && g.b2b() && r.lines() > 0) { // B2B 대상이 이어졌다 (RULES 9.3)
					s.insert(0, "B2B ");
				}
				lastAction = s.toString();
				lastActionAt = time;
			}
			b2bBefore = g.b2b();
			comboBefore = g.combo();
		}
	}

	final Object lock = new Object();
	final Settings settings;
	final long seed;
	final TimedMatch match;
	final HumanInput human = new HumanInput();
	final Tally[] tallies;
	final String aiLabel;
	final String aiWarning;

	private final AiController ai;
	private final WinRate winRate;
	/** 사람 대 AI 판의 기록 (승률 예측망 자료, TRAINING.md 10.9). 기록을 끄면 null. */
	private final TimedRecorder recorder;
	private volatile double p1Win = Double.NaN;
	/** 예측 승률의 흐름: {판 시각 ms, P1 이 이길 확률}. lock 을 잡고 쓰고 읽는다. */
	final List<double[]> winHistory = new ArrayList<>();
	/** 예측 승률이 한 번에 5%p 이상 움직인 마지막 순간과 그 크기(P1 기준), 원인 (화면의 "▲ 12%p · AI 공격 3줄"). */
	long swingAt = -10_000;
	double swingDelta;
	String swingCause = "";
	private volatile boolean started;
	private final Consumer<GameSession> onOver;

	private Thread thread;
	private volatile boolean running;
	private volatile boolean paused;
	private long startNanos;
	private long pausedNanos;
	private long pauseStarted;

	/**
	 * @param onOver 판이 끝나면 게임 진행 스레드에서 한 번 부른다
	 */
	GameSession(Settings settings, AiCatalog catalog, long seed, Consumer<GameSession> onOver) {
		this.settings = settings;
		this.seed = seed;
		this.onOver = onOver;
		if (settings.versusAi()) {
			AiCatalog.Difficulty d = settings.difficulty();
			match = new TimedMatch(seed, 2, GameModel.Scheduler.NONE, d.gravityCapG());
			AiCatalog.Choice c = catalog.choose(d, settings.persona(), seed, 2);
			TimedPlayer me = match.player(1);
			TimedPlayer you = match.player(0);
			ai = new AiController(me, () -> Opponent.of(you.game()), c.brain(), d.thinkMs(), d.inputsPerSec());
			aiLabel = c.label();
			aiWarning = c.fallback();
			winRate = loadWinRate(catalog.winprob());
			recorder = recordsDir() == null ? null
					: new TimedRecorder("human", seed, TimedRecorder.player("human", "human", 0),
							TimedRecorder.player("level" + d.id() + (d.personas() ? "-" + settings.persona().id() : ""),
									c.label(), d.temperature()));
		} else {
			match = new TimedMatch(seed, 1, settings.scheduler(), settings.gravityCapG());
			ai = null;
			aiLabel = null;
			aiWarning = null;
			winRate = null;
			recorder = null;
		}
		tallies = new Tally[match.size()];
		for (int i = 0; i < tallies.length; i++) {
			tallies[i] = new Tally();
		}
		updateWinRate();
	}

	private static WinRate loadWinRate(Path path) {
		if (path == null) {
			return null;
		}
		try {
			return WinRate.load(path, 1);
		} catch (Exception | LinkageError e) {
			return null; // 승률 막대만 숨긴다
		}
	}

	void start() {
		running = true;
		started = true;
		startNanos = System.nanoTime();
		thread = new Thread(this::loop, "game-loop");
		thread.setDaemon(true);
		thread.start();
	}

	/** 진행을 멈추고 스레드가 끝나기를 기다린다. 승률 모델은 스레드가 끝난 뒤 닫는다(두뇌의 모델은 카탈로그가 갖고 있다). */
	void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
			try {
				thread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		if (winRate != null) {
			synchronized (lock) {
				winRate.close();
			}
		}
	}

	/** 혼자 연습만 멈출 수 있다. */
	void togglePause() {
		if (settings.versusAi() || match.isOver()) {
			return;
		}
		synchronized (lock) {
			if (paused) {
				pausedNanos += System.nanoTime() - pauseStarted;
				paused = false;
			} else {
				pauseStarted = System.nanoTime();
				paused = true;
				human.reset();
			}
		}
	}

	boolean paused() {
		return paused;
	}

	/** 시작(카운트다운 포함)부터 흐른 실제 시간 (멈춘 시간 제외, ms). */
	private long elapsedMs() {
		long pausedNow = paused ? System.nanoTime() - pauseStarted : 0;
		return (System.nanoTime() - startNanos - pausedNanos - pausedNow) / 1_000_000;
	}

	/** 카운트다운에 남은 초 (3, 2, 1). 시작했거나 스레드 없이 진행하면 0. */
	int countdown() {
		if (!started) {
			return 0;
		}
		long left = COUNTDOWN_MS - elapsedMs();
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	/** 막 시작해 "GO!" 를 보여 줄 때인가. */
	boolean showGo() {
		return started && countdown() == 0 && match.time() < GO_MS;
	}

	/** P1 이 이길 확률. 모델이 없거나 혼자 연습이면 NaN. */
	double p1Win() {
		return p1Win;
	}

	AiController ai() {
		return ai;
	}

	private void loop() {
		boolean reported = false;
		while (running) {
			synchronized (lock) {
				if (!paused) {
					long target = elapsedMs() - COUNTDOWN_MS;
					while (running && match.time() < target && !match.isOver()) {
						match.player(0).setSoftDrop(human.softDrop());
						advance(human.next(match.time()));
					}
				}
			}
			if (match.isOver() && !reported) {
				reported = true;
				saveRecord();
				onOver.accept(this);
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	/** 1ms. P1 의 조작은 {@code mine}. lock 을 잡은 채로 부른다(화면 확인 도구는 스레드 없이 직접 부른다). */
	void advance(Input mine) {
		if (ai == null) {
			match.tick(mine);
		} else {
			match.tick(mine, ai.poll());
		}
		if (recorder != null) {
			recorder.afterTick(match);
		}
		boolean locked = false;
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null) {
				tallies[i].add(r, match.player(i).game(), match.time());
				locked = true;
			}
		}
		if (locked) {
			updateWinRate();
		}
	}

	/**
	 * 사람 대 AI 판의 기록을 둘 폴더. {@code -Dtetris.records=<폴더>} 로 바꾸고 {@code off} 면 남기지 않는다. 기본은
	 * {@code runs/human/data/games} 다(git 에 올리지 않는 폴더). 판의 상태만 남고 개인 정보는 없다.
	 */
	static Path recordsDir() {
		String v = System.getProperty("tetris.records", "runs/human/data/games");
		return "off".equalsIgnoreCase(v) ? null : Path.of(v);
	}

	/** 끝난 판만 남긴다(기권하고 나간 판은 결과를 모르므로 남기지 않는다). 실패해도 게임은 계속한다. */
	void saveRecord() {
		Path dir = recordsDir();
		if (recorder == null || dir == null || !match.isOver()) {
			return;
		}
		try {
			String name = "human-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
					+ seed + ".jsonl.gz";
			synchronized (lock) {
				TimedRecorder.writeSingle(recorder.finish(match), dir.resolve(name));
			}
		} catch (java.io.IOException | RuntimeException e) {
			System.err.println("판 기록을 남기지 못함: " + e.getMessage());
		}
	}

	/** 이번 틱에 승률을 움직인 일: 보낸 공격, 올라온 방해 줄, 지운 줄 (없으면 빈 문자열). */
	private String swingCause() {
		String[] who = { "나", "AI" };
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null && r.sent() > 0) {
				return who[i] + " 공격 " + r.sent() + "줄";
			}
		}
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null && r.garbageRisen() > 0) {
				return who[i] + " 방해 줄 " + r.garbageRisen() + "줄";
			}
		}
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null && r.lines() > 0) {
				return who[i] + " " + r.lines() + "줄 지움";
			}
		}
		return "";
	}

	private void updateWinRate() {
		if (winRate == null || match.size() < 2) {
			return;
		}
		try {
			double before = p1Win;
			p1Win = winRate.p1Win(match.player(0).game(), match.player(1).game());
			winHistory.add(new double[] { match.time(), p1Win });
			if (!Double.isNaN(before) && Math.abs(p1Win - before) >= 0.05) {
				swingAt = match.time();
				swingDelta = p1Win - before;
				swingCause = swingCause();
			}
		} catch (RuntimeException e) {
			p1Win = Double.NaN;
		}
	}
}
