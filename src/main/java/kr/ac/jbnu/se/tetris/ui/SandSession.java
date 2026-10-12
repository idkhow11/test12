package kr.ac.jbnu.se.tetris.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.PieceSounds;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.audio.SoundCues;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.ClearEvent;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.LockEvent;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.sand.SandSnapshot;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 모래 모드 한 판을 실제 시간으로 진행한다 (sand 패키지 설명, docs/GAME.md). {@link GameSession} 과 같은 방식이다: 게임 진행
 * 스레드 하나가 흐른 시간만큼 {@link SandGame#advance} 를 1ms 씩 돌리고(판이 끝나면 끝 처리를 한 번 하고 스레드도 끝난다),
 * 판을 바꾸는 동안(한 틱에 몇 마이크로초)만 {@link #lock} 을 잡는다. 화면은 잠금 안에서 복사본({@link #snapshot})과 효과 사건만 읽고 잠금 밖에서 그린다. 사람의 키는
 * {@link HumanInput} 이 틱마다 조작 하나로 바꾼다(키 묶음과 DAS/ARR 은 판을 시작할 때의 사용자 설정 {@link #user}, 고전과
 * 같다). 화면 확인 도구와 시험은 손을 모래 AI 로 바꿀 수 있다({@link #drive}).
 * <p>
 * 시작 카운트다운 3초, P 로 멈춤과 계속(계속하면 2·1 카운트다운), 창을 벗어나면 저절로 멈춤({@link #autoPause()}), 오류로
 * 멈추면 오류 카드. 시계는 진행할 틱 수만 정하고 판의 규칙에는 쓰지 않으므로, 같은 시드와 같은 입력 열이면 같은 판이다. 판이
 * 끝난 뒤의 움직임(회색으로 바뀌는 판, 결과 카드)은 판의 시계가 서므로 이 판의 실제 시간 시계로 잰다({@link #clockNanos()},
 * {@link #overAtNanos()}).
 * <p>
 * <b>소리</b> (audio 패키지 설명의 표). 틱마다 잠금 안에서 앞뒤를 비교해 고르고 잠금 밖에서 낸다: 사람 블록의 이동·회전·홀드
 * (실패하면 DENY)·닿음({@link PieceSounds}), 블록이 모래로 부서짐(하드드롭이면 떨어진 거리의 단계로 하드드롭 소리를 겹친다),
 * 덩어리 지우기(배율 2 이상이면 연쇄), 단계 오름. 판이 끝나면 한 번 {@code SoundCues.result(OVER, 새 기록인가)}. 판을 떠난
 * 뒤({@link #stop()}, {@link #quit()})에는 이 판이 소리를 내지 않는다. 카운트다운, 일시정지, 위험 심장 소리, 메뉴 소리는
 * 화면({@link SandPanel})이 낸다. 소리는 {@link Speaker} 로 내므로 시험은 들은 소리를 적어 둘 수 있다.
 * <p>
 * <b>효과 사건</b> (runs/quality/SPEC.md V14). 블록이 부서진 일({@link LockFx}: 하드드롭 꼬리, 판 튕김)과 덩어리를 지운 일
 * ({@link ClearFx}: 한 번의 반짝임, 사라질 때의 조각, 점수 띄우기)을 최근 {@value #FX_KEEP} 개씩 적어 둔다. 얇은 띠(양쪽 벽을
 * 이었지만 지우기 문턱보다 작은 덩어리, sand-0.3)가 판에 나타난 시각도 적어 둔다({@link #thinSince()}: 화면이 표시를 천천히
 * 들인다). 판의 규칙과 난수는 건드리지 않는다(읽기만 한다).
 * <p>
 * <b>기록.</b> 끝난 판과, 카운트다운이 끝난 뒤 그만둔 판을 한 번만 플레이 기록에 더한다({@code PlayRecords.withSand}, 혼자
 * 연습과 같은 규칙). 오류로 멈춘 판은 남기지 않는다. 저장은 판의 잠금 밖에서 한다. 새 기록인지는 이 판을 더하기 바로 전의 저장
 * 파일과 비교한다(다른 창이 그사이 남긴 점수도 넣어서). 모래 판은 학습 자료({@code runs/human})에는 어디에도 남기지 않는다.
 */
final class SandSession {

	/** 시작 전 카운트다운 (3, 2, 1). 판의 시계는 그 뒤부터 간다. 고전 화면과 같다. */
	static final int COUNTDOWN_MS = GameSession.COUNTDOWN_MS;
	/** 시작한 뒤 "GO!" 를 보여 주는 시간. */
	static final int GO_MS = GameSession.GO_MS;
	/** 일시정지를 풀면 이만큼 카운트다운(2, 1)한 뒤 다시 간다. */
	static final int RESUME_MS = GameSession.RESUME_MS;
	/** 효과 사건을 몇 개까지 적어 두는가 (오래된 것부터 버린다). */
	static final int FX_KEEP = GameSession.FX_KEEP;
	/** {@link #overAtNanos()}·{@link #pausedAtNanos()}·{@link #resumedAtNanos()}: 그런 일이 아직 없음. */
	static final long NOT_OVER = GameSession.NOT_OVER;
	/** {@link #thinSince()}: 지금 판에 얇은 띠가 없음. */
	static final long NO_THIN = -1;
	/** 지운 알갱이 이만큼마다 사라질 때의 조각 하나 (효과 세기 보통, SPEC V14). */
	static final int GRAINS_PER_PIECE = 16;
	/** 한 번의 지우기에서 나오는 조각의 상한 (SPEC V14). */
	static final int MAX_PIECES = 120;
	/** 효과 세기의 가장 큰 곱 (강하게 1.5). 조각 자리는 이만큼까지 미리 골라 둔다. */
	private static final double MAX_EFFECTS = 1.5;

	/** 판을 두는 손: 틱마다 조작 하나(없으면 null)와 소프트드롭 키. 게임은 사람의 키({@link #human})다. */
	interface Hand {
		/** 틱 {@code tick} 의 조작, 없으면 null. 게임 진행 스레드가 틱마다 한 번 부른다. */
		Input next(long tick);

		/** 소프트드롭 키를 누르고 있는가. */
		boolean softDrop();
	}

	/** 소리를 내는 곳. 게임은 소리 엔진({@link Sound#get()})이고 시험은 들은 소리를 적어 둔다. 막히지 않아야 한다. */
	interface Speaker {
		void play(Cue cue);

		default void play(List<Cue> cues) {
			for (Cue c : cues) {
				play(c);
			}
		}

		/** 울리는 소리와 늦춘 소리를 끝낸다 (판을 떠날 때). */
		void stopAll();

		/** 소리 엔진 ({@code Sound.get()}: 시작 전, 시험, 헤드리스, 소리 끔이면 아무것도 하지 않는다). */
		Speaker ENGINE = new Speaker() {
			@Override
			public void play(Cue cue) {
				Sound.get().play(cue);
			}

			@Override
			public void play(List<Cue> cues) {
				Sound.get().play(cues);
			}

			@Override
			public void stopAll() {
				Sound.get().stopAll();
			}
		};
	}

	/**
	 * 블록 하나가 모래로 부서졌다 (화면 효과: 하드드롭 꼬리, 판 튕김). 배열은 만든 뒤 바뀌지 않는다.
	 *
	 * @param at        판 시각 (ms)
	 * @param cells     부서진 칸 {행, 열} (칸 단위, 0 행이 맨 위, 알갱이 행 = 행 × {@link SandRules#GRAIN})
	 * @param colorCode 모래 색 번호 1~4 ({@code SandColor.code()})
	 * @param hardDrop  하드드롭으로 고정했는가
	 * @param dropRows  하드드롭으로 내려온 칸 수 (하드드롭이 아니면 0)
	 */
	record LockFx(long at, int[][] cells, int colorCode, boolean hardDrop, int dropRows) {
		@Override
		public int[][] cells() {
			int[][] out = new int[cells.length][];
			for (int i = 0; i < cells.length; i++) {
				out[i] = cells[i].clone();
			}
			return out;
		}

		/** 칸 수 (4). */
		int size() {
			return cells.length;
		}

		/** k 번째 칸의 행 (복사하지 않는다, 그릴 때). */
		int row(int k) {
			return cells[k][0];
		}

		/** k 번째 칸의 열. */
		int col(int k) {
			return cells[k][1];
		}
	}

	/**
	 * 덩어리를 지웠다: {@link #at} 부터 {@link SandRules#FLASH_MS} 동안 반짝인 뒤 사라진다. 배열은 만든 뒤 바뀌지 않는다.
	 *
	 * @param at         찾은 때 = 반짝이기 시작한 때 (판 시각 ms)
	 * @param multiplier 이 지우기의 콤보 배율
	 * @param points     얻은 점수
	 * @param colors     지운 색 비트 ({@code 1 << SandColor.code()})
	 * @param cx         지운 알갱이의 무게중심 열 (알갱이 단위, 0~{@link SandRules#WIDTH})
	 * @param cy         무게중심 행 (알갱이 단위, 0 이 맨 위)
	 * @param cells      이번에 반짝이기 시작한 알갱이 자리 {@code 행 × WIDTH + 열} (오름차순)
	 * @param pieces     사라질 때 튈 조각의 자리와 색 {@code 자리 | 색 번호 << 16}. 덩어리 전체에 고르게 고른 뒤 섞어 두어
	 *                   앞에서부터 몇 개만 써도 고르게 퍼진다
	 */
	record ClearFx(long at, int multiplier, long points, int colors, double cx, double cy, int[] cells, int[] pieces) {
		@Override
		public int[] cells() {
			return cells.clone();
		}

		@Override
		public int[] pieces() {
			return pieces.clone();
		}

		/** 이번에 지운 알갱이 수. */
		int grains() {
			return cells.length;
		}

		/** k 번째 알갱이 자리 (복사하지 않는다, 그릴 때). */
		int cell(int k) {
			return cells[k];
		}

		/** 미리 골라 둔 조각 수. */
		int pieceCount() {
			return pieces.length;
		}

		/** k 번째 조각 ({@code 자리 | 색 번호 << 16}). */
		int piece(int k) {
			return pieces[k];
		}
	}

	final Object lock = new Object();
	/** 판을 시작할 때의 사용자 설정 (키, DAS·ARR …). */
	final UserSettings user;
	/** 사람의 키 → 조작. 판을 시작할 때의 사용자 설정(키 묶음, DAS, ARR)으로 만든다. */
	final HumanInput human;

	private final SandGame game;
	private final SaveData save;
	private final LongSupplier clock;
	private final Consumer<SandSession> onOver;
	private final AtomicBoolean recorded = new AtomicBoolean();
	private final AtomicBoolean finished = new AtomicBoolean();
	/** 판을 시작할 때의 최고 점수 (기록에 더하기 전 끝 카드의 "이전 최고", {@link #previousBest()}). */
	private final long startBest;
	private final PieceSounds pieceSounds = new PieceSounds();
	private volatile Speaker speaker = Speaker.ENGINE;
	private volatile Hand hand;
	/** 판을 떠났다({@link #stop()}): 이 판은 더 소리를 내지 않는다(고정·연쇄, 게임 끝). {@link GameSession} 과 같다. */
	private volatile boolean quiet;

	private volatile boolean ended; // 판이 끝났다 (정착한 모래가 넘침)
	private volatile String failure; // 오류로 멈춘 이유, 아니면 null
	private volatile boolean newBest; // 기록에 더하며 최고 점수를 새로 세웠다
	private volatile PlayRecords recordsBefore; // 이 판을 더하기 바로 전의 기록 (다시 읽은 파일), 더하지 않았으면 null
	private volatile PlayRecords recordsAfter; // 이 판을 더한 기록, 더하지 않았으면 null

	// 효과 사건: lock 을 잡고 쓰고 읽는다. 바뀔 때마다 새 목록으로 바꾸므로 읽는 쪽은 복사하지 않아도 된다.
	private List<LockFx> lockFx = List.of();
	private List<ClearFx> clearFx = List.of();
	/** 아직 반짝이는 지우기 (새 지우기의 알갱이를 앞 지우기의 것과 가르는 데 쓴다). */
	private final ArrayDeque<ClearFx> flashing = new ArrayDeque<>();
	private short[] scan; // 지우기를 찾았을 때 판을 옮겨 보는 곳 (처음 쓸 때 만든다)
	private boolean[] owned;
	private int seenClears;
	private boolean softDropping;
	private long overAtNanos = NOT_OVER;
	/** 얇은 띠가 지금 판에 처음 나타난 판 시각 (ms), 없으면 {@link #NO_THIN}. */
	private long thinSince = NO_THIN;

	// 시계: lock 을 잡고 쓰고 읽는다 (paused, started, running 은 잠금 없이도 읽는다)
	private volatile boolean started;
	private volatile boolean running;
	private volatile boolean paused;
	private long startNanos;
	private long pausedNanos;
	private long pauseStarted;
	private long resumeAt;
	private boolean resuming;
	private long resumedNanos = NOT_OVER;

	private volatile Thread thread;

	/**
	 * @param seed       게임 시드 ("같은 판 다시" 는 지난 판의 {@link #seed()})
	 * @param startLevel 시작 단계 1~10
	 * @param save       사용자 설정(키, DAS·ARR)과 플레이 기록 (최고 점수를 읽고, 판이 끝나면 더한다)
	 * @param onOver     판이 끝나거나 오류로 멈추면 게임 진행 스레드에서 한 번 부른다(짧게, EDT 를 기다리지 않게)
	 */
	SandSession(long seed, int startLevel, SaveData save, Consumer<SandSession> onOver) {
		this(new SandGame(seed, startLevel, save.records().sand().bestScore()), save, onOver, System::nanoTime);
	}

	/**
	 * 판과 시계를 직접 넘긴다 (시험, 화면 확인 도구). 판은 {@code save} 의 지금 최고 점수로 만든 것이어야 끝 카드의
	 * "이전 최고" 가 맞다.
	 *
	 * @param clock 실제 시간 (나노초). 몇 틱을 돌릴지와 판이 끝난 뒤의 움직임만 정한다
	 */
	SandSession(SandGame game, SaveData save, Consumer<SandSession> onOver, LongSupplier clock) {
		this.game = game;
		this.save = save;
		this.user = save.settings();
		this.human = new HumanInput(user);
		this.onOver = onOver;
		this.clock = clock;
		this.startBest = save.records().sand().bestScore(); // 판의 최고 점수도 이 값에서 시작했다
		synchronized (lock) {
			if (game.clears() > 0) { // 처음 판(fromField)에 이미 양쪽 벽을 잇는 덩어리가 있었다
				captureClear();
			}
			seenClears = game.clears();
			noteThin();
			if (game.over()) {
				ended = true;
				overAtNanos = clock.getAsLong();
			}
		}
	}

	// ------------------------------------------------------------------ 진행

	/**
	 * 손을 바꾼다 (화면 확인 도구와 시험: 모래 AI). {@link #start()} 전에 부른다. 화면의 키는 그래도 일시정지·메뉴를 받는다.
	 */
	void drive(Hand h) {
		this.hand = h;
	}

	/** 소리를 내는 곳을 바꾼다 (시험: 들은 소리를 적어 둔다). */
	void speaker(Speaker s) {
		this.speaker = s;
	}

	/** 소리를 내는 곳 (화면이 카운트다운·일시정지·메뉴 소리를 같은 곳으로 낸다). */
	Speaker speaker() {
		return speaker;
	}

	void start() {
		synchronized (lock) {
			startNanos = clock.getAsLong();
			started = true;
			running = true;
		}
		thread = new Thread(this::loop, "sand-loop");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * 진행을 멈추고 진행 스레드가 끝나기를 기다린다. 기록은 남기지 않는다({@link #quit()}). 이 판은 그 뒤로 소리를 내지 않는다:
	 * 진행 스레드가 멈추기 전 마지막 틱이나 막 끝난 판의 끝 처리(게임 끝 소리)도 소리 없이 지나간다(기록은 그대로 남긴다). 여러
	 * 번 불러도 된다. 스레드를 끼어들기(interrupt)로 깨우지 않는다: 판이 끝난 직후 진행 스레드가 기록을 저장하는 중이면 파일
	 * 채널이 끼어들기로 닫혀 기록을 잃기 때문이다. 루프는 틱마다 {@code running} 을 보고 1ms 씩만 자므로 곧 끝난다.
	 */
	void stop() {
		quiet = true;
		running = false;
		if (thread != null && thread != Thread.currentThread()) {
			try {
				thread.join(2000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * 판을 떠난다(메뉴로, 다시 하기, 창 닫기): 진행을 멈추고, 카운트다운이 끝난 뒤의 판이면 기록에 더한다(이미 더했으면 다시
	 * 더하지 않는다). 화면 스레드에서 부른다.
	 */
	void quit() {
		stop();
		if (failure == null) {
			record();
		}
	}

	/**
	 * 진행 스레드. 판이 끝나면 끝 처리({@link #finish()}: 기록, 게임 끝 소리, 알림)를 한 번 하고 끝난다: 결과 카드를 띄워 둔 동안
	 * 1ms 마다 깨어나지 않는다. 떠나거나({@link #stop()}) 오류로 멈춰도 끝난다. 멈춘 동안은 1ms 씩 자며 기다린다.
	 */
	private void loop() {
		Hand h = hand != null ? hand : humanHand();
		try {
			while (running) {
				long target;
				synchronized (lock) {
					target = paused ? Long.MIN_VALUE : elapsedMs() - COUNTDOWN_MS;
				}
				while (running && !paused && !ended && game.time() < target) { // 멈추면 남은 틱도 바로 멈춘다
					advance(h.next(game.time()), h.softDrop());
				}
				if (ended) {
					finish();
					break;
				}
				Thread.sleep(1);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); // 누가 끼어들었으면 그냥 끝낸다
		} catch (RuntimeException | Error e) {
			fail(e);
		}
	}

	/** 진행 스레드가 끝났는가(시작하지 않았으면 참). 판이 끝나면 떠나지 않아도 끝난다. 시험용. */
	boolean threadDone() {
		Thread t = thread;
		return t == null || !t.isAlive();
	}

	private Hand humanHand() {
		return new Hand() {
			@Override
			public Input next(long tick) {
				return human.next(tick);
			}

			@Override
			public boolean softDrop() {
				return human.softDrop();
			}
		};
	}

	/**
	 * 1ms 진행한다. 진행 스레드가 부르고, 화면 확인 도구와 시험은 스레드 없이 직접 부른다. 판을 바꾸는 동안만 잠그고, 소리는
	 * 잠금 밖에서 낸다(떠난 판은 내지 않는다).
	 *
	 * @param input    이번 틱의 조작, 없으면 null
	 * @param softDrop 소프트드롭 키를 누르고 있는가
	 */
	void advance(Input input, boolean softDrop) {
		if (ended) {
			return;
		}
		List<Cue> cues;
		synchronized (lock) {
			cues = step(input, softDrop);
		}
		if (cues != null && !quiet) {
			speaker.play(cues);
		}
	}

	/** 한 틱 (lock 안): 판을 진행하고 효과 사건을 적는다. 낼 소리를 돌려준다, 없으면 null. */
	private List<Cue> step(Input input, boolean softDrop) {
		PieceState piece = game.piece();
		int dropRows = -1;
		if (input == Input.HD && piece != null) {
			dropRows = game.ghost().y() - piece.y(); // 떨어질 칸 수 (하드드롭 소리의 단계, 꼬리의 길이)
		}
		pieceSounds.before(piece, game.spawns(), game.grounded());
		int pieces = game.pieces();
		int level = game.level();
		game.setSoftDrop(softDrop);
		game.advance(input);
		softDropping = softDrop;
		boolean locked = game.pieces() != pieces;
		List<Cue> moves = pieceSounds.after(input, game.piece(), game.spawns(), game.grounded(), locked);
		List<Cue> out = moves.isEmpty() ? null : new ArrayList<>(moves);
		if (locked) {
			LockEvent e = game.lastLock();
			int rows = e.hardDrop() ? Math.max(0, dropRows) : -1;
			lockFx = push(lockFx, new LockFx(e.time(), e.piece().cells(), e.color().code(), e.hardDrop(), Math.max(0, rows)));
			out = add(out, SoundCues.sandLock(rows));
		}
		if (game.clears() != seenClears) {
			captureClear();
			seenClears = game.clears();
			out = add(out, List.of(SoundCues.sandClear(game.lastClear().multiplier())));
		}
		if (!game.over() && game.level() > level) {
			out = add(out, List.of(Cue.of(Sfx.LEVEL_UP)));
		}
		noteThin();
		if (game.over()) {
			ended = true;
			overAtNanos = clock.getAsLong();
		}
		return out;
	}

	/**
	 * 얇은 띠가 처음 나타난 판 시각을 적는다(lock 안). 띠가 남아 있는 동안은 그대로 두고, 모두 사라지면(지워졌거나 끊겼거나) 없음으로
	 * 되돌린다. 판의 틱으로만 정하므로 같은 판이면 언제나 같은 값이다.
	 */
	private void noteThin() {
		if (game.thinGrains() == 0) {
			thinSince = NO_THIN;
		} else if (thinSince == NO_THIN) {
			thinSince = game.time();
		}
	}

	private static List<Cue> add(List<Cue> out, List<Cue> more) {
		List<Cue> list = out != null ? out : new ArrayList<>(4);
		list.addAll(more);
		return list;
	}

	/** 목록 끝에 하나를 붙이고 최근 {@link #FX_KEEP} 개만 남긴 바뀌지 않는 새 목록. */
	private static <T> List<T> push(List<T> list, T item) {
		int from = Math.max(0, list.size() + 1 - FX_KEEP);
		List<T> next = new ArrayList<>(FX_KEEP);
		next.addAll(list.subList(from, list.size()));
		next.add(item);
		return List.copyOf(next);
	}

	/**
	 * 새 지우기 (lock 안): 이번에 반짝이기 시작한 알갱이(아직 반짝이는 앞 지우기의 알갱이를 뺀 것)를 모아 효과 사건으로 적는다.
	 * 반짝이는 알갱이는 사라질 때까지 움직이지 않으므로 앞 지우기의 자리와 겹치지 않는다. 한 틱에 지우기가 둘(고정에서 하나, 같은
	 * 틱의 모래 스텝에서 하나)이면 하나로 적고 점수는 마지막 지우기의 것을 쓴다(드물다). 판 전체를 한 번 훑는다(약 1.5만 칸,
	 * 지우기마다 한 번).
	 */
	private void captureClear() {
		ClearEvent e = game.lastClear();
		long now = game.time();
		while (!flashing.isEmpty() && flashing.peekFirst().at() + SandRules.FLASH_MS <= now) {
			flashing.pollFirst(); // 이미 사라졌다
		}
		int size = SandRules.WIDTH * SandRules.HEIGHT;
		if (scan == null) {
			scan = new short[size];
			owned = new boolean[size];
		}
		game.copyGrains(scan);
		for (ClearFx f : flashing) {
			for (int i : f.cells) {
				owned[i] = true;
			}
		}
		int n = 0;
		for (int i = 0; i < size; i++) {
			if (Grain.flashing(scan[i]) && !owned[i]) {
				n++;
			}
		}
		int[] cells = new int[n];
		long sx = 0;
		long sy = 0;
		int k = 0;
		for (int i = 0; i < size; i++) {
			if (Grain.flashing(scan[i]) && !owned[i]) {
				cells[k++] = i;
				sx += i % SandRules.WIDTH;
				sy += i / SandRules.WIDTH;
			}
		}
		for (ClearFx f : flashing) {
			for (int i : f.cells) {
				owned[i] = false;
			}
		}
		if (n == 0 || e == null) {
			return;
		}
		ClearFx fx = new ClearFx(e.time(), e.multiplier(), e.points(), e.colors(), sx / (double) n + 0.5,
				sy / (double) n + 0.5, cells, pieces(cells, e.time()));
		flashing.addLast(fx);
		clearFx = push(clearFx, fx);
	}

	/**
	 * 사라질 때 튈 조각의 자리: 덩어리를 훑는 차례로 고르게 나눈 칸마다 하나씩(칸 안의 자리는 효과 난수) 고른 뒤 섞는다. 판의
	 * 시드와 지우기 시각만 쓰므로 같은 판이면 같은 조각이다(판의 난수는 쓰지 않는다).
	 */
	private int[] pieces(int[] cells, long at) {
		int n = cells.length;
		int count = (int) Math.min(MAX_PIECES, Math.ceil(n * MAX_EFFECTS / GRAINS_PER_PIECE));
		int[] out = new int[count];
		long seed = game.seed();
		for (int j = 0; j < count; j++) {
			int idx = (int) Math.min(n - 1, (long) ((j + Fx.rand(seed, at, j, n, 1)) * n / count));
			int cell = cells[idx];
			out[j] = cell | Grain.code(scan[cell]) << 16;
		}
		for (int j = count - 1; j > 0; j--) { // 섞기 (Fisher-Yates, 효과 난수)
			int r = (int) (Fx.rand(seed, at, j, n, 2) * (j + 1));
			int t = out[j];
			out[j] = out[r];
			out[r] = t;
		}
		return out;
	}

	/** 판이 끝났으면 기록에 한 번 더한다. 스레드 없이 진행한 도구·시험이 판이 끝난 뒤 부른다. */
	void finishIfOver() {
		if (ended) {
			finish();
		}
	}

	/** 판이 끝났을 때 한 번: 기록에 더하고, 게임 끝 소리(새 기록이면 그 소리도, 떠난 판은 없음)를 내고, 끝났다고 알린다. */
	private void finish() {
		if (!finished.compareAndSet(false, true)) {
			return;
		}
		record();
		if (!quiet) {
			speaker.play(SoundCues.result(TimedMatch.Result.OVER, newBest));
		}
		notifyOver();
	}

	/**
	 * 카운트다운이 끝난 뒤의 판(시계가 0 보다 큼)을 한 번만 기록에 더한다. 판의 잠금 밖에서 저장한다. 새 기록인지는 이 판을 더하기
	 * 바로 전의 기록과 비교한다: 다른 창이 그사이 파일에 남긴 점수도 그 안에 있다({@link SaveData#updateRecords} 가 다시 읽는다,
	 * {@code GameSession.recordPlay} 와 같다).
	 */
	private void record() {
		long score;
		int multiplier;
		boolean played;
		synchronized (lock) {
			score = game.score();
			multiplier = game.maxMultiplier();
			played = game.time() > 0;
		}
		if (!played || !recorded.compareAndSet(false, true)) {
			return;
		}
		try {
			PlayRecords[] before = new PlayRecords[1]; // 이 판을 더한 바로 그 기록 (다시 읽은 파일 위)
			PlayRecords after = save.updateRecords(r -> {
				if (before[0] == null) { // 저장소가 읽기에 실패해 같은 고치기를 나중에 다시 하더라도 처음 값이 기준이다
					before[0] = r;
				}
				return r.withSand(score, multiplier);
			});
			recordsBefore = before[0];
			recordsAfter = after;
			newBest = after.sand().newBestScoreSince(before[0].sand());
		} catch (RuntimeException e) { // 저장에 실패해도 게임은 계속한다 (SaveData 는 보통 예외를 던지지 않는다)
			System.err.println("모래 모드 기록을 남기지 못함: " + e);
		}
	}

	/**
	 * 이 판을 기록에 더하며 최고 점수를 새로 세웠는가 (첫 판은 새 기록이 아니다, {@link PlayRecords.Sand#newBestScoreSince}).
	 * 기록에 더한 뒤에만 참일 수 있고, 더하기 바로 전의 저장 파일과 비교한다({@link #record()}). 판 도중의 "새 기록" 표시는
	 * {@code SandView.newBest()} 가 판을 시작할 때의 최고 점수로 같은 규칙을 써서 판다.
	 */
	boolean newBest() {
		return newBest;
	}

	/** 이 판을 더한 플레이 기록, 아직 더하지 않았으면 저장된 지금 기록. */
	PlayRecords records() {
		PlayRecords r = recordsAfter;
		return r != null ? r : save.records();
	}

	/**
	 * 이 판을 더하기 바로 전의 최고 점수 (새 기록을 세웠을 때 끝 카드의 "이전 최고"). 기록에 더했으면 그때 다시 읽은 저장 파일의
	 * 값({@link #newBest()} 가 비교한 값, 다른 창이 그사이 남긴 점수도 들어 있다), 아직이면 판을 시작할 때의 값.
	 */
	long previousBest() {
		PlayRecords b = recordsBefore;
		return b != null ? b.sand().bestScore() : startBest;
	}

	/**
	 * 끝 카드의 "최고": 기록에 더했으면 더한 뒤의 최고 점수(다른 창이 그사이 남긴 점수도 들어 있다), 아직이면 {@code beforeRecord}
	 * (판의 최고 점수: 시작할 때의 최고와 이 판의 점수 중 큰 것).
	 */
	long bestScore(long beforeRecord) {
		PlayRecords r = recordsAfter;
		return r != null ? r.sand().bestScore() : beforeRecord;
	}

	private void notifyOver() {
		try {
			onOver.accept(this);
		} catch (RuntimeException e) {
			System.err.println("판이 끝난 뒤의 처리에서 오류: " + e);
		}
	}

	/** 진행 중에 예상하지 못한 오류가 났다: 판을 멈추고 화면에 오류 카드를 띄운다(조용히 멈추지 않는다). */
	void fail(Throwable t) {
		System.err.println("모래 모드 진행 중 오류로 판을 멈춤: " + t);
		t.printStackTrace();
		synchronized (lock) {
			if (overAtNanos == NOT_OVER) {
				overAtNanos = clock.getAsLong();
			}
		}
		failure = "판을 이어 갈 수 없어 멈췄어요";
		running = false;
		notifyOver();
	}

	// ------------------------------------------------------------------ 일시정지와 시계 (GameSession 과 같다)

	/**
	 * P 키: 멈춤과 계속을 바꾼다. 계속할 때는 {@link #RESUME_MS} 동안 카운트다운한 뒤 다시 간다(시작 카운트다운 중에 멈췄으면
	 * 그 카운트다운을 이어 간다). 끝난 판에서는 아무것도 하지 않는다. 화면 스레드에서 부른다.
	 */
	void togglePause() {
		synchronized (lock) {
			if (paused) {
				resume();
			} else if (!over()) {
				pause();
			}
		}
	}

	/**
	 * 창이 포커스를 잃었거나 최소화됐을 때 부른다. 사람이 자리를 비운 동안 블록이 떨어지지 않게 멈추고, 돌아와도 저절로 가지
	 * 않는다(P 로 계속). 멈췄으면 true. 끝난 판이나 이미 멈춘 판에서는 아무것도 하지 않는다.
	 */
	boolean autoPause() {
		synchronized (lock) {
			if (paused || over()) {
				return false;
			}
			pause();
			return true;
		}
	}

	private void pause() {
		long now = clock.getAsLong();
		if (resuming && now - resumeAt < 0) {
			pausedNanos -= resumeAt - now; // 다시 가기 전 카운트다운 중에 또 멈췄다: 남은 카운트다운은 멈춘 시간에서 뺀다
		}
		resuming = false;
		pauseStarted = now;
		paused = true;
		human.reset();
	}

	private void resume() {
		long now = clock.getAsLong();
		long wait = game.time() > 0 ? RESUME_MS * 1_000_000L : 0;
		pausedNanos += now - pauseStarted + wait; // 카운트다운도 멈춘 시간으로 친다: 그동안 판의 시계가 가지 않는다
		resumeAt = now + wait;
		resuming = wait > 0;
		resumedNanos = now;
		paused = false;
		human.reset();
	}

	boolean paused() {
		return paused;
	}

	/** 시작(카운트다운 포함)부터 흐른 실제 시간 (멈춘 시간 제외, ms). lock 을 잡고 부른다. */
	private long elapsedMs() {
		long now = clock.getAsLong();
		long pausedNow = paused ? now - pauseStarted : 0;
		return (now - startNanos - pausedNanos - pausedNow) / 1_000_000;
	}

	/** 화면의 카운트다운 숫자: 시작 전 3·2·1, 일시정지를 푼 뒤 2·1. 아니면(시작했거나, 끝났거나, 스레드 없이 진행하면) 0. */
	int countdown() {
		long left = countdownMs();
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	/**
	 * 카운트다운에 남은 시간 (ms): 시작 전 3초, 일시정지를 푼 뒤 2초에서 0 까지. 카운트다운 중이 아니면(멈춤, 진행 중, 끝남,
	 * 스레드 없이 진행) 0. 숫자의 움직임(SPEC V8)에 쓴다.
	 */
	long countdownMs() {
		synchronized (lock) {
			if (!started || paused || over()) {
				return 0;
			}
			long now = clock.getAsLong();
			if (resuming && now - resumeAt < 0) {
				return (resumeAt - now + 999_999) / 1_000_000;
			}
			return Math.max(0, COUNTDOWN_MS - elapsedMs());
		}
	}

	/** 막 시작해 "GO!" 를 보여 줄 때인가. */
	boolean showGo() {
		return goAgeMs() >= 0;
	}

	/** "GO!" 가 뜬 뒤 흐른 시간 (판의 시계, 0 ~ {@link #GO_MS}), 보여 줄 때가 아니면 -1. */
	long goAgeMs() {
		if (!started || paused || over() || countdown() != 0) {
			return -1;
		}
		long t = time();
		return t < GO_MS ? t : -1;
	}

	/**
	 * 이 판의 실제 시간 시계 (나노초, 처음 값은 정해지지 않음). 판이 끝난 뒤와 멈춘 동안의 움직임(회색으로 바뀌는 판, 결과 카드,
	 * 일시정지 카드)은 판의 시계가 서므로 이 시계로 잰다. 시험과 화면 확인 도구는 이 시계를 정해 넘긴다.
	 */
	long clockNanos() {
		return clock.getAsLong();
	}

	/** 판이 끝난(모래가 넘침, 오류) 순간의 {@link #clockNanos()}, 아직 끝나지 않았으면 {@link #NOT_OVER}. */
	long overAtNanos() {
		synchronized (lock) {
			return overAtNanos;
		}
	}

	/** 지금 멈춰 있으면 멈춘 순간의 {@link #clockNanos()}, 아니면 {@link #NOT_OVER} (일시정지 카드가 들어오는 움직임). */
	long pausedAtNanos() {
		synchronized (lock) {
			return paused ? pauseStarted : NOT_OVER;
		}
	}

	/** 마지막으로 P 로 계속한 순간의 {@link #clockNanos()}, 없으면 {@link #NOT_OVER} (일시정지 카드가 나가는 움직임). */
	long resumedAtNanos() {
		synchronized (lock) {
			return resumedNanos;
		}
	}

	// ------------------------------------------------------------------ 화면이 읽는 것

	/** 화면용 복사본을 잠금 안에서 채운다. {@code reuse} 의 배열을 다시 쓴다(null 이면 새로 만든다). */
	SandSnapshot snapshot(SandSnapshot reuse) {
		synchronized (lock) {
			return game.snapshot(reuse);
		}
	}

	/** 최근 블록 부서짐 (오래된 것부터, 많아야 {@link #FX_KEEP} 개, 바뀌지 않는 목록). */
	List<LockFx> lockFx() {
		synchronized (lock) {
			return lockFx;
		}
	}

	/** 최근 지우기 (오래된 것부터, 많아야 {@link #FX_KEEP} 개, 바뀌지 않는 목록). */
	List<ClearFx> clearFx() {
		synchronized (lock) {
			return clearFx;
		}
	}

	/**
	 * 얇은 띠(양쪽 벽을 이었지만 지우기 문턱보다 작은 덩어리)가 지금 판에 처음 나타난 판 시각 (ms), 없으면 {@link #NO_THIN}. 띠가
	 * 남아 있는 동안은 바뀌지 않는다. 화면이 얇은 띠 표시를 들이는 데 쓴다({@link SandFx#thinIn}).
	 */
	long thinSince() {
		synchronized (lock) {
			return thinSince;
		}
	}

	/** 마지막 틱에 소프트드롭 키를 누르고 있었는가 (떨어지는 블록의 꼬리). */
	boolean softDropping() {
		synchronized (lock) {
			return softDropping;
		}
	}

	/** 판이 끝났는가: 모래가 넘쳤거나 오류로 멈췄다. 어느 스레드에서 불러도 된다. */
	boolean over() {
		return ended || failure != null;
	}

	/** 오류로 멈췄으면 화면에 보일 짧은 이유, 아니면 null. */
	String failure() {
		return failure;
	}

	/** 판의 시계 (ms). */
	long time() {
		synchronized (lock) {
			return game.time();
		}
	}

	long seed() {
		return game.seed();
	}

	int startLevel() {
		return game.startLevel();
	}

	/** 이 판이 기록에 더해졌는가. */
	boolean recorded() {
		return recorded.get();
	}

	/** 판 자체 (화면 확인 도구의 자동 손, 시험). 진행 스레드가 돌 때는 {@link #lock} 을 잡고 읽는다. */
	SandGame game() {
		return game;
	}
}
