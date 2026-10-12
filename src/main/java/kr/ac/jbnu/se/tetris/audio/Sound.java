package kr.ac.jbnu.se.tetris.audio;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 효과음 엔진. 시작할 때 모든 효과음을 만들고(대부분 코드로 합성, 몇 가지는 함께 배포하는 CC0 녹음, {@link SoundBank}), 자기
 * 데몬 스레드에서 작은 믹서로 섞어 소리 장치에 쓴다. {@link #play} 는 쿨다운을 보고 요청을 대기열에 넣기만 하므로 게임 진행
 * 스레드나 화면 스레드를 막지 않는다. 어느 스레드에서 불러도 된다.
 * <p>
 * 동시에 울리는 소리는 8 개까지이고, 소리마다 쿨다운이 있다({@link Sfx}). 전체 볼륨과 음소거가 있다. 소리 장치가 없으면
 * (헤드리스 서버, CI, 장치 없는 PC, {@code -Dtetris.sound=off}) 모든 호출이 아무 일도 하지 않고 예외도 내지 않는다. 장치를
 * 열지 못했거나 쓰던 장치가 멈춰 소리를 끄면 그 까닭을 표준 오류에 한 줄 남긴다("효과음을 끔: …").
 * <p>
 * 장치 버퍼는 25ms 다(키를 누르고 소리가 나기까지 짧게). 처음 30초 동안 소리가 끊긴 것(버퍼가 빔)을 세어 3 번을 넘으면 그
 * 실행 동안은 40ms 로 늘리고 표준 오류에 한 줄 남긴다. 판이 화면에서 진행되는 동안({@link MatchSounds}·{@link PieceSounds}
 * 가 틱마다 알린다, 화면 코드는 따로 부를 것이 없다)은 조용해도 장치를 쉬게 하지 않고 무음을 써서, 첫 소리가 장치를 다시
 * 켜느라 늦지 않게 한다. 판이 멈추거나 끝나 조용해지면 약 2초 뒤에 장치를 쉬게 한다.
 * <p>
 * 게임에서는 {@link #start()} 를 한 번 부르고(TetrisApp.main), 어디서든 {@code Sound.get().play(Sfx.MOVE)} 처럼 쓴다.
 * {@link #start()} 전에는 {@link #get()} 이 소리 없는 엔진을 돌려주므로 시험과 화면 확인 도구는 소리를 내지 않는다. 시험과
 * 도구는 {@link #start()} 를 부르지 않는다(빌드 중에 진짜 소리 장치를 열지 않게).
 */
public final class Sound implements AutoCloseable {

	/** 엔진 상태. */
	public enum State {
		/** 장치를 열고 소리를 만드는 중 (보통 0.2~0.3초). 이때의 요청은 버린다. */
		STARTING,
		/** 소리를 낸다. */
		READY,
		/** 장치가 없거나 꺼 둠. 아무 소리도 내지 않는다(까닭은 {@link #offCause()}, 자세히는 {@link #reason()}). */
		OFF,
		/** 닫음. */
		CLOSED
	}

	/** 소리를 내지 않는 까닭의 종류 ({@link #offCause()}). 설정 화면이 알맞은 문구를 고를 때 쓴다. */
	public enum OffCause {
		/** 소리를 낸다(또는 곧 낸다). */
		NONE,
		/** 실행 옵션 {@code -Dtetris.sound=off} 로 껐다. */
		OPTION,
		/** 소리를 쓰지 않는 실행: 창 없이(헤드리스) 실행, {@link #start()} 전, 시험·도구의 {@link #silent()}, 닫은 뒤. */
		UNUSED,
		/** 소리 장치를 열 수 없다 (장치 없음, 다른 프로그램이 독점, 소리 라이브러리 없음). */
		NO_DEVICE,
		/** 쓰던 장치가 응답하지 않거나 출력이 멈췄다 (이어폰을 뽑음 등). */
		DEVICE_LOST,
		/** 효과음을 만들지 못했다 (메모리 부족 등). */
		BROKEN
	}

	/**
	 * 처음 볼륨 (0~1). 설정의 기본값(70%)과 같다. 실제로 곱하는 값은 볼륨의 제곱(0.49)이고, 소리 자체도 작게 만들어서 가장
	 * 큰 소리(퍼펙트 클리어)도 최대 크기의 약 3 분의 1 이다.
	 */
	public static final double DEFAULT_VOLUME = 0.7;

	/** 한 번에 섞는 프레임 (약 5.8ms). */
	static final int CHUNK = 256;
	/** 한 블록의 길이 (ms, 버림). 이 안에 시작할 요청은 이번 블록에서 프레임 단위로 맞춰 시작한다. */
	private static final int CHUNK_MS = CHUNK * 1000 / Synth.RATE;
	/**
	 * 장치 버퍼 (25ms): 키를 누르고 소리가 나기까지의 지연과, 스레드가 잠깐 밀릴 때(AI 계산, 가비지 수집) 끊기지 않을 여유
	 * 사이의 타협. 끊김이 잦으면 {@link #SAFE_BUFFER_FRAMES} 로 늘린다.
	 */
	static final int LINE_BUFFER_FRAMES = Synth.RATE / 40;
	/** 소리가 자주 끊기는 PC 에서 그 실행 동안 쓰는 장치 버퍼 (40ms). */
	static final int SAFE_BUFFER_FRAMES = Synth.RATE / 25;
	/** 끊김을 세는 동안: 장치에 쓴 처음 30초. */
	static final long UNDERRUN_WINDOW_FRAMES = 30L * Synth.RATE;
	/** 그 동안 끊김이 이보다 많으면 버퍼를 늘린다. */
	static final int MAX_UNDERRUNS = 3;
	/** 이만큼 조용하면 장치를 쉬게 하고 다음 요청까지 잠든다. */
	static final int IDLE_MS = 2000;
	/** 판이 진행 중이라는 알림({@link #markActive()})이 이만큼 이어진다. 알림이 끊기면(멈춤, 판 끝) 다시 쉴 수 있다. */
	static final int ACTIVE_HOLD_MS = 1000;
	/** 이보다 늦게 처리하게 된 요청은 버린다(스레드가 밀렸을 때 지난 소리가 한꺼번에 울리지 않게). */
	static final int STALE_MS = 250;
	/** 대기열 한도. 넘는 요청은 버린다. */
	static final int MAX_QUEUE = 64;
	/** 늦춤의 상한 (ms). 잘못 준 아주 긴 늦춤이 대기열에 남지 않게 자른다. */
	static final int MAX_DELAY_MS = 5000;
	/** 장치가 이만큼 연달아 받지 않으면(약 1초) 장치를 잃은 것으로 보고 소리를 끈다. */
	private static final int MAX_STALLS = 200;
	/** 닫을 때 소리를 0 까지 줄이는 블록 수 (약 12ms: 한 블록은 줄이고, 한 블록은 0). */
	static final int CLOSE_FADE_CHUNKS = 2;

	private static volatile Sound current = new Sound(OffCause.UNUSED, "시작 전");

	/** 요청 하나: 시각 {@code dueMs} 에 울린다. {@code sfx} 가 null 이면 "앞의 것을 모두 멈춤" 표시({@link #stopAll()}). */
	private record Request(Sfx sfx, int intensity, float pan, long dueMs) {
	}

	/** {@link #stopAll()} 의 표시. */
	private static final Request STOP = new Request(null, 0, 0f, 0);

	/** 닫은 장치 대신 두는 아무것도 하지 않는 출력. */
	private static final AudioOut CLOSED_OUT = new AudioOut() {
		@Override
		public int write(byte[] data, int off, int len) {
			return len;
		}

		@Override
		public void pause() {
		}

		@Override
		public void resume() {
		}

		@Override
		public void close() {
		}
	};

	private final AudioOut.Opener opener;
	private final AudioOut.Opener safeOpener;
	private final Supplier<SoundBank> bankMaker;
	private final LongSupplier clock;
	private final int idleMs;
	private final Thread thread;
	private final Object stateLock = new Object();
	private final Cooldown cooldown = new Cooldown();
	private final ConcurrentLinkedQueue<Request> queue = new ConcurrentLinkedQueue<>();
	private final AtomicInteger queued = new AtomicInteger();
	private final AtomicLong accepted = new AtomicLong();
	private final AtomicLong written = new AtomicLong();
	private volatile State state;
	private volatile OffCause cause;
	private volatile String reason;
	private volatile double volume = DEFAULT_VOLUME;
	private volatile boolean muted;
	/** 판이 진행 중이라고 볼 마지막 시각 (ms). */
	private volatile long activeUntil = Long.MIN_VALUE;
	/** 믹서 스레드가 장치를 쉬게 하고 잠들어 있는가. */
	private volatile boolean resting;
	/** 처음 30초 동안 센 끊김 수. */
	private volatile int underruns;
	/** 장치 버퍼를 늘렸는가 (그 실행 동안 한 번). */
	private volatile boolean safe;
	private final Variation variation; // 믹서 스레드만 쓴다
	private SoundBank bank; // 믹서 스레드만 쓴다
	private AudioOut out; // 믹서 스레드만 쓴다
	private boolean windowOver; // 믹서 스레드만 쓴다: 끊김을 세는 30초가 지났다

	/** 소리 없는 엔진. */
	private Sound(OffCause cause, String reason) {
		this.opener = null;
		this.safeOpener = null;
		this.bankMaker = null;
		this.clock = Sound::nowMs;
		this.idleMs = IDLE_MS;
		this.thread = null;
		this.variation = null;
		this.state = State.OFF;
		this.cause = cause;
		this.reason = reason;
	}

	private Sound(AudioOut.Opener opener, AudioOut.Opener safeOpener, Supplier<SoundBank> bankMaker, LongSupplier clock,
			int idleMs, long seed) {
		this.opener = opener;
		this.safeOpener = safeOpener;
		this.bankMaker = bankMaker;
		this.clock = clock;
		this.idleMs = idleMs;
		this.variation = new Variation(seed);
		this.state = State.STARTING;
		this.cause = OffCause.NONE;
		this.reason = "";
		this.thread = new Thread(this::run, "sound-mixer");
		thread.setDaemon(true);
		thread.setPriority(Math.min(Thread.MAX_PRIORITY, Thread.NORM_PRIORITY + 2)); // 소리가 끊기지 않게 조금 높게
	}

	// ------------------------------------------------------------------ 만들기

	/** 지금 쓰는 엔진. {@link #start()} 전에는 소리 없는 엔진이다(볼륨·음소거 값은 기억했다가 시작할 때 넘긴다). */
	public static Sound get() {
		return current;
	}

	/**
	 * 게임을 시작할 때 한 번 부른다. 장치를 열고 소리를 만드는 일은 믹서 스레드가 하므로 바로 돌아온다. 이미 시작했으면 그
	 * 엔진을 돌려준다. {@code -Dtetris.sound=off} 이거나 창 없이(헤드리스) 실행하면 소리 없는 엔진을 쓴다.
	 */
	public static synchronized Sound start() {
		Sound before = current;
		if (before.thread != null && before.state != State.CLOSED) {
			return before;
		}
		String off = offReason();
		Sound s = off != null ? new Sound(offByOption() ? OffCause.OPTION : OffCause.UNUSED, off)
				: new Sound(() -> AudioOut.openLine(LINE_BUFFER_FRAMES), () -> AudioOut.openLine(SAFE_BUFFER_FRAMES),
						SoundBank::generate, Sound::nowMs, IDLE_MS, System.nanoTime());
		s.volume = before.volume; // 믹서 스레드가 움직이기 전에 넘긴다
		s.muted = before.muted;
		if (s.thread != null) {
			s.thread.start();
		}
		current = s;
		return s;
	}

	/** 소리를 내지 않을 이유 (설정으로 껐거나 창 없이 실행), 없으면 null. */
	static String offReason() {
		if (offByOption()) {
			return "꺼 둠 (-Dtetris.sound=off)";
		}
		if (GraphicsEnvironment.isHeadless()) {
			return "창 없이 실행 (headless)";
		}
		return null;
	}

	private static boolean offByOption() {
		return "off".equalsIgnoreCase(System.getProperty("tetris.sound", "on").trim());
	}

	/** 아무 소리도 내지 않는 엔진 (시험, 소리를 쓰지 않는 도구). */
	public static Sound silent() {
		return new Sound(OffCause.UNUSED, "소리 없음");
	}

	/** 출력을 {@code opener} 로 여는 엔진을 시작한다(시험은 가짜 출력과 시계를 넘긴다). */
	static Sound open(AudioOut.Opener opener, LongSupplier clock, int idleMs) {
		return open(opener, SoundBank::generate, clock, idleMs);
	}

	/** 소리 만들기까지 바꿔 넣는다(시험이 만든 횟수를 세거나 실패를 흉내 낼 때). 끊김이 잦아도 버퍼를 늘릴 출력이 없다. */
	static Sound open(AudioOut.Opener opener, Supplier<SoundBank> bankMaker, LongSupplier clock, int idleMs) {
		return open(opener, null, bankMaker, clock, idleMs, 1L);
	}

	/**
	 * 모두 바꿔 넣는다: 끊김이 잦을 때 쓸 출력 {@code safeOpener}(null 이면 늘리지 않는다), 변형을 고르고 음 높이를 흔드는
	 * 난수의 시드.
	 */
	static Sound open(AudioOut.Opener opener, AudioOut.Opener safeOpener, Supplier<SoundBank> bankMaker,
			LongSupplier clock, int idleMs, long seed) {
		Sound s = new Sound(opener, safeOpener, bankMaker, clock, idleMs, seed);
		s.thread.start();
		return s;
	}

	/** {@link #get()} 이 돌려줄 엔진을 바꾼다 (시험이 원래대로 되돌릴 때). */
	static void install(Sound s) {
		current = s;
	}

	private static long nowMs() {
		return System.nanoTime() / 1_000_000;
	}

	// ------------------------------------------------------------------ 소리 내기

	public void play(Sfx sfx) {
		enqueue(sfx, 1, 0f, 0);
	}

	/** 세기가 있는 소리 (지운 줄 수, 콤보 수, 방해 줄 수, 연쇄 수, 하드드롭 단계, B2B 연속 수). */
	public void play(Sfx sfx, int intensity) {
		enqueue(sfx, intensity, 0f, 0);
	}

	/** 세기와 좌우 위치(-1 왼쪽 ~ 1 오른쪽). 어느 위치에서나 전체 크기는 같다. */
	public void play(Sfx sfx, int intensity, double pan) {
		enqueue(sfx, intensity, (float) pan, 0);
	}

	public void play(Cue cue) {
		if (cue != null) {
			enqueue(cue.sfx(), cue.intensity(), cue.pan(), cue.delayMs());
		}
	}

	/** 여러 소리를 한꺼번에 (예: {@link SoundCues#lock} 의 결과). */
	public void play(List<Cue> cues) {
		if (cues != null) {
			for (Cue c : cues) {
				play(c);
			}
		}
	}

	/**
	 * 울리고 있는 소리와 아직 울리지 않은 늦춘 소리(승리 음악 뒤의 최고 기록 소리 등)를 모두 짧게(약 4ms) 줄여 끝낸다. 판을
	 * 떠날 때(다시 하기, 메뉴로) 앞 판의 긴 음악이 다음 화면까지 이어지지 않게 부른다. 이 뒤에 부른 {@link #play} 는 그대로
	 * 난다(순서를 지킨다). 막히지 않는다.
	 */
	public void stopAll() {
		if (state != State.READY) {
			return;
		}
		queued.incrementAndGet(); // 한도와 상관없이 넣는다(드물고, 버리면 음악이 남는다)
		queue.add(STOP);
		LockSupport.unpark(thread);
	}

	private void enqueue(Sfx sfx, int intensity, float pan, int delayMs) {
		if (sfx == null || muted || volume <= 0 || state != State.READY) {
			return;
		}
		long now = clock.getAsLong();
		if (!cooldown.tryAcquire(sfx, intensity, now)) {
			return;
		}
		if (queued.incrementAndGet() > MAX_QUEUE) {
			queued.decrementAndGet();
			return;
		}
		queue.add(new Request(sfx, intensity, pan, now + Math.max(0, Math.min(MAX_DELAY_MS, delayMs))));
		accepted.incrementAndGet();
		LockSupport.unpark(thread);
	}

	/**
	 * 판이 화면에서 진행 중이다. {@link MatchSounds} 와 {@link PieceSounds} 가 틱마다(몇십 ms 에 한 번) 부르므로 화면 코드는
	 * 부르지 않아도 된다. 그동안({@link #ACTIVE_HOLD_MS} 씩 이어진다) 믹서는 조용해도 장치를 쉬게 하지 않고 무음을 써서, 첫
	 * 소리가 장치를 다시 켜느라 늦지 않게 한다. 쉬고 있었으면 장치를 깨운다. 막히지 않는다.
	 */
	void markActive() {
		if (state != State.READY) {
			return;
		}
		activeUntil = clock.getAsLong() + ACTIVE_HOLD_MS;
		if (resting) {
			LockSupport.unpark(thread);
		}
	}

	private boolean active(long now) {
		return now < activeUntil;
	}

	// ------------------------------------------------------------------ 설정과 상태

	/** 전체 볼륨 0~1 (범위 밖이면 가까운 끝). 다음 블록(수 ms)부터 부드럽게 바뀐다. 0 이면 음소거와 같다. */
	public void setVolume(double v) {
		volume = Double.isNaN(v) ? DEFAULT_VOLUME : Math.max(0, Math.min(1, v));
	}

	public double volume() {
		return volume;
	}

	/** 음소거. 켜면 울리던 소리도 곧바로(수 ms 안에) 부드럽게 사라진다. */
	public void setMuted(boolean m) {
		muted = m;
	}

	public boolean muted() {
		return muted;
	}

	public State state() {
		return state;
	}

	/** 소리를 낼 수 있거나 곧 낼 수 있는가 (READY 또는 STARTING). 설정 화면이 "소리 장치 없음" 을 보일 때 쓴다. */
	public boolean isAvailable() {
		State s = state;
		return s == State.READY || s == State.STARTING;
	}

	/** 소리가 꺼진 이유 (OFF 일 때). 오류 기록에 쓴다(화면에는 {@link #offCause()} 로 고른 짧은 문구를 쓴다). */
	public String reason() {
		return reason;
	}

	/** 소리를 내지 않는 까닭의 종류. 소리를 내거나 곧 낼 때(READY, STARTING)는 NONE, 닫았으면 UNUSED. */
	public OffCause offCause() {
		switch (state) {
		case READY:
		case STARTING:
			return OffCause.NONE;
		case CLOSED:
			return OffCause.UNUSED;
		default:
			return cause;
		}
	}

	/** 지금까지 받아들인 요청 수 (쿨다운·음소거로 버린 것 제외). */
	long accepted() {
		return accepted.get();
	}

	/** 장치에 쓴 프레임 수. */
	long framesWritten() {
		return written.get();
	}

	/** 처음 30초 동안 센 끊김(장치 버퍼가 빈 채로 쓰게 된 때) 수. */
	int underruns() {
		return underruns;
	}

	/** 끊김이 잦아 장치 버퍼를 40ms 로 늘렸는가. */
	boolean safeBuffer() {
		return safe;
	}

	/** 판이 진행 중이라는 알림이 아직 살아 있는가. */
	boolean activeNow() {
		return active(clock.getAsLong());
	}

	/**
	 * 소리를 멈추고 장치를 닫는다. 울리던 소리는 뚝 끊지 않고 약 12ms 에 걸쳐 0 까지 줄인 뒤, 장치에 남은 소리(버퍼 25ms)를 다
	 * 내보내고 닫는다(딸깍·퍽 소리가 나지 않게). 믹서 스레드가 끝나기를 잠깐(최대 1초, 보통 0.1초 안) 기다린다. 여러 번 불러도
	 * 된다.
	 */
	@Override
	public void close() {
		synchronized (stateLock) {
			if (state == State.CLOSED) {
				return;
			}
			state = State.CLOSED;
		}
		if (thread != null) {
			LockSupport.unpark(thread);
			if (Thread.currentThread() != thread) {
				try {
					thread.join(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	/** 소리를 낸다(READY). 이미 닫혔으면 바꾸지 않고 false. */
	private boolean moveToReady() {
		synchronized (stateLock) {
			if (state == State.CLOSED) {
				return false;
			}
			state = State.READY;
			cause = OffCause.NONE;
			reason = "";
			return true;
		}
	}

	/**
	 * 장치나 소리 만들기에 문제가 생겨 끈다(OFF). 이미 닫혔으면 바꾸지 않는다. 까닭은 콘솔(표준 오류)에 한 줄 남긴다: 창으로
	 * 실행하면 사람이 볼 곳이 없으므로, 소리가 나지 않을 때 원인을 찾을 수 있게.
	 */
	private void turnOff(OffCause why, String detail) {
		synchronized (stateLock) {
			if (state == State.CLOSED) {
				return;
			}
			cause = why; // 상태보다 먼저: 잠금 없이 읽는 쪽이 OFF 와 옛 까닭을 함께 보지 않게
			reason = detail;
			state = State.OFF;
		}
		System.err.println("효과음을 끔: " + detail);
	}

	// ------------------------------------------------------------------ 믹서 스레드

	/**
	 * 장치를 먼저 연다: 장치가 없는 PC 에서는 소리를 만들지 않고(약 0.2초, 7MB) 바로 끈다. 연 장치는 소리를 다 만든 뒤에
	 * 움직인다.
	 */
	private void run() {
		try {
			out = opener.open();
		} catch (Exception | LinkageError e) {
			turnOff(OffCause.NO_DEVICE, "소리 장치를 열 수 없음 (" + describe(e) + ")");
			return;
		}
		try {
			if (state == State.CLOSED) { // 여는 동안 닫혔다
				return;
			}
			try {
				bank = bankMaker.get();
			} catch (RuntimeException | LinkageError | OutOfMemoryError e) {
				turnOff(OffCause.BROKEN, "효과음을 만들 수 없음 (" + describe(e) + ")");
				return;
			}
			out.resume();
			if (moveToReady()) {
				loop();
			}
		} catch (RuntimeException | LinkageError e) {
			turnOff(OffCause.DEVICE_LOST, "소리 출력이 멈춤 (" + describe(e) + ")");
		} finally {
			dropQueued();
			try {
				out.close();
			} catch (RuntimeException e) {
				// 닫다가 난 오류는 무시한다(게임은 계속한다)
			}
		}
	}

	private void loop() {
		Voices voices = new Voices();
		OutputStage stage = new OutputStage();
		List<Request> later = new ArrayList<>();
		float[] mix = new float[CHUNK * 2];
		byte[] pcm = new byte[CHUNK * AudioOut.FORMAT.getFrameSize()];
		long quietSince = clock.getAsLong();
		long sinceResume = 0; // 장치를 (다시) 움직인 뒤 쓴 프레임
		while (state == State.READY) {
			long now = clock.getAsLong();
			boolean silent = muted || volume <= 0;
			boolean wasIdle = voices.idle();
			if (silent) {
				voices.releaseAll();
				later.clear();
				dropQueued();
			} else {
				take(now, voices, later);
			}
			if (voices.idle() && later.isEmpty()) {
				if (now - quietSince >= idleMs && !active(now)) { // 조용하고 판도 없다: 장치를 쉬게 하고 다음 요청까지 잔다
					out.pause();
					resting = true;
					while (state == State.READY && !hasSound() && !active(clock.getAsLong())) {
						LockSupport.park(this);
						Thread.interrupted(); // 누가 끼어들어도 바쁘게 돌지 않게(멈춤은 state 로만 한다)
					}
					resting = false;
					if (state != State.READY) {
						return;
					}
					out.resume();
					sinceResume = 0;
					quietSince = clock.getAsLong();
					continue;
				}
			} else {
				quietSince = now;
			}
			float target = silent ? 0f : (float) (volume * volume);
			if (wasIdle) {
				stage.snap(target); // 조용하던 중이면 볼륨을 바로 맞춘다
			}
			voices.render(mix, CHUNK);
			stage.process(mix, CHUNK, target, pcm);
			countUnderrun(sinceResume);
			if (!writeAll(out, pcm)) {
				turnOff(OffCause.DEVICE_LOST, "소리 장치가 응답하지 않음");
				return;
			}
			written.addAndGet(CHUNK);
			sinceResume += CHUNK;
			if (!safe && underruns > MAX_UNDERRUNS) {
				if (!useSafeBuffer()) {
					return;
				}
				sinceResume = 0;
			}
		}
		if (state == State.CLOSED) {
			fadeOut(voices, stage, mix, pcm);
		}
	}

	/**
	 * 쓰기 전에 장치 버퍼가 비어 있으면(소리가 끊김) 센다. 장치를 (다시) 움직인 뒤 버퍼를 한 번 채우기 전과, 처음 30초가 지난
	 * 뒤에는 세지 않는다.
	 */
	private void countUnderrun(long sinceResume) {
		if (windowOver || safe) {
			return;
		}
		if (written.get() >= UNDERRUN_WINDOW_FRAMES) {
			windowOver = true;
			return;
		}
		int cap = out.bufferBytes();
		if (cap <= 0 || sinceResume * AudioOut.FORMAT.getFrameSize() < cap) {
			return;
		}
		if (out.available() >= cap) {
			underruns++;
		}
	}

	/**
	 * 끊김이 잦다: 그 실행 동안 장치 버퍼를 40ms 로 늘린다(남은 소리를 내보내고 닫은 뒤 다시 연다). 늘린 장치를 열지 못하면
	 * 원래 크기로 다시 열고, 그것도 안 되면 소리를 끈다(false).
	 */
	private boolean useSafeBuffer() {
		safe = true;
		int n = underruns;
		if (safeOpener == null) {
			System.err.println("효과음: 소리가 자주 끊김 (" + n + "번). 장치 버퍼를 늘릴 수 없어 그대로 둠");
			return true;
		}
		AudioOut old = out;
		try {
			old.drain();
			old.close();
		} catch (RuntimeException e) {
			// 닫다가 난 오류는 무시하고 새로 연다
		}
		out = CLOSED_OUT;
		AudioOut next;
		try {
			next = safeOpener.open();
		} catch (Exception | LinkageError e) {
			try {
				next = opener.open();
			} catch (Exception | LinkageError e2) {
				turnOff(OffCause.DEVICE_LOST, "소리 장치를 다시 열 수 없음 (" + describe(e2) + ")");
				return false;
			}
		}
		out = next;
		next.resume();
		System.err.println("효과음: 소리가 자주 끊겨 (" + n + "번) 장치 버퍼를 " + ms(LINE_BUFFER_FRAMES) + "ms 에서 "
				+ ms(SAFE_BUFFER_FRAMES) + "ms 로 늘림");
		return true;
	}

	/**
	 * 닫을 때: 울리던 소리를 짧게 줄여 끝내고, 볼륨을 한 블록에 걸쳐 0 까지 내린 뒤 0 인 블록을 하나 더 쓴다. 그다음 장치에 남은
	 * 소리를 다 내보낸다. 그래서 장치를 닫는 순간 출력이 이미 0 이라 딸깍 소리가 나지 않는다. 장치가 쉬던 중(조용함)이면 부르지
	 * 않는다: 그때는 버퍼가 비어 있다.
	 */
	private void fadeOut(Voices voices, OutputStage stage, float[] mix, byte[] pcm) {
		voices.releaseAll();
		for (int i = 0; i < CLOSE_FADE_CHUNKS; i++) {
			voices.render(mix, CHUNK);
			stage.process(mix, CHUNK, 0f, pcm);
			if (!writeAll(out, pcm)) {
				return;
			}
			written.addAndGet(CHUNK);
		}
		out.drain();
	}

	/**
	 * 대기열의 요청을 꺼내고, 이번 블록 안에 시작할 것을 프레임 단위로 맞춰 시작한다. 나중 것은 later 에 남긴다. 멈춤 표시를
	 * 만나면 그때까지의 소리(울리는 것과 늦춘 것)를 모두 끝낸다. 변형은 세기 묶음 안에서 바로 앞의 것을 피해 고르고, 조작·메뉴
	 * 소리 몇 가지는 음 높이와 크기를 조금씩 흔든다({@link Variation}).
	 */
	private void take(long now, Voices voices, List<Request> later) {
		Request r;
		while ((r = queue.poll()) != null) {
			queued.decrementAndGet();
			if (r.sfx() == null) {
				voices.releaseAll();
				later.clear();
			} else {
				later.add(r);
			}
		}
		for (Iterator<Request> it = later.iterator(); it.hasNext();) {
			Request q = it.next();
			long wait = q.dueMs() - now;
			if (wait > CHUNK_MS) {
				continue;
			}
			it.remove();
			if (-wait > STALE_MS) {
				continue;
			}
			Sfx e = q.sfx();
			int tier = e.tier(q.intensity());
			int v = e.variant(tier, variation.choose(e, tier));
			voices.start(e, q.intensity(), bank.clip(e, v), q.pan(), wait > 0 ? Synth.frames(wait) : 0,
					variation.gain(e, q.intensity()), variation.rate(e));
		}
	}

	/** 낼 소리 요청이 있는가. 잠든 동안 온 멈춤 표시는 멈출 것이 없으므로 버린다(장치를 깨우지 않는다). 믹서 스레드만 부른다. */
	private boolean hasSound() {
		Request r;
		while ((r = queue.peek()) != null && r.sfx() == null) {
			queue.poll();
			queued.decrementAndGet();
		}
		return r != null;
	}

	private void dropQueued() {
		while (queue.poll() != null) {
			queued.decrementAndGet();
		}
	}

	/** 장치가 다 받을 때까지 쓴다. 장치가 오래 받지 않으면 false. */
	private boolean writeAll(AudioOut out, byte[] pcm) {
		int off = 0;
		int stalls = 0;
		while (off < pcm.length) {
			int n = out.write(pcm, off, pcm.length - off);
			if (n > 0) {
				off += n;
				stalls = 0;
				continue;
			}
			if (state != State.READY) {
				return true;
			}
			if (++stalls > MAX_STALLS) {
				return false;
			}
			LockSupport.parkNanos(5_000_000L);
		}
		return true;
	}

	/** 프레임 수 → ms (반올림). */
	private static long ms(int frames) {
		return Math.round(frames * 1000.0 / Synth.RATE);
	}

	private static String describe(Throwable e) {
		String m = e.getMessage();
		return e.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
	}
}
