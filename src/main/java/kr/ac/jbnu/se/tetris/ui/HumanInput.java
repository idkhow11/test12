package kr.ac.jbnu.se.tetris.ui;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.KeyTracker;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 사람의 키 입력을 틱마다 조작 하나로 바꾼다 (docs/GAME.md 4장). 키 이벤트는 화면 스레드가, {@link #next(long)} 은 게임
 * 진행 스레드가 부른다. 대전 화면과 모래 화면이 판마다 하나씩 쓴다.
 * <p>
 * <b>키.</b> 키 묶음·DAS·ARR 은 판을 시작할 때의 사용자 설정을 쓴다({@link #HumanInput(UserSettings)}). 키 이벤트는
 * {@link #keyPressed(int)}·{@link #keyReleased(int)} 로 넘기면 {@link KeyTracker} 가 동작의 "막 눌림"·"막 뗌" 으로 바꿔
 * 준다(운영체제 자동 반복 무시, 한 동작의 여러 키). 화면은 돌려받은 동작을 보고 블록 조작이면 {@link #press}·{@link #release}
 * 로, 화면 조작(일시정지, 메뉴, 다시 하기)이면 스스로 처리한다.
 * <p>
 * <b>조작.</b> 누를 때 한 번 하는 조작(회전, 하드드롭, 홀드, 그리고 좌우의 첫 한 칸)은 대기열에 넣어 하나도 빠뜨리지
 * 않는다. 좌우를 누르고 있으면 DAS 뒤부터 ARR 마다 반복한다. 양쪽을 다 누르고 있으면 나중에 누른 쪽만 반복한다. 운영체제의
 * 키 자동 반복은 <b>모든 키에서</b> 무시한다(누른 키는 뗄 때까지 다시 누른 것으로 치지 않는다).
 * <p>
 * <b>카운트다운이 끝날 때.</b> 화면은 카운트다운과 일시정지 동안 누른 조작을 버린다. 그동안 누르고 있던 이동(좌우 중 나중 것,
 * 소프트드롭)은 블록 조작을 다시 받기 시작하는 순간 {@link #resumeHeld()} 로 넘긴다(설정 화면이 생기기 전에는 운영체제의
 * 자동 반복이 GO 뒤에 다시 눌러 주던 일이다). 하드드롭·회전·홀드는 넘기지 않는다.
 */
final class HumanInput {

	/** 좌우 자동 반복이 시작되기까지 (ms). 설정의 기본값({@link UserSettings#DEFAULT_DAS_MS})과 같다. `[초안]` */
	static final int DAS_MS = 170;
	/** 자동 반복 간격 (ms). 설정의 기본값({@link UserSettings#DEFAULT_ARR_MS})과 같다. `[초안]` */
	static final int ARR_MS = 50;

	/** 키의 뜻 (블록 조작 일곱 가지, {@link KeyAction#controlsPiece()} 와 하나씩 대응한다). */
	enum Key {
		LEFT, RIGHT, SOFT_DROP, HARD_DROP, CW, CCW, HOLD
	}

	private final int dasMs;
	private final int arrMs;
	private final KeyTracker tracker; // 화면 스레드만 쓴다
	private final ConcurrentLinkedQueue<Input> presses = new ConcurrentLinkedQueue<>();
	private final Set<Key> held = EnumSet.noneOf(Key.class); // 화면 스레드만 쓴다
	private volatile boolean left;
	private volatile boolean right;
	private volatile boolean softDrop;
	private volatile int lastDir;
	private volatile boolean rearm; // reset() 뒤: 다음 틱에 좌우 반복을 처음부터 (다시 누르면 DAS 부터)

	// 게임 진행 스레드만 쓴다.
	private int dir;
	private long repeatAt;

	/** 기본 키와 기본 DAS·ARR (설정 화면이 생기기 전의 손맛). */
	HumanInput() {
		this(KeyBindings.defaults(), DAS_MS, ARR_MS);
	}

	/** 기본 키와 주어진 DAS·ARR. */
	HumanInput(int dasMs, int arrMs) {
		this(KeyBindings.defaults(), dasMs, arrMs);
	}

	/** 사용자 설정의 키 묶음, DAS, ARR. 판을 시작할 때의 설정으로 만들고 그 판 내내 쓴다. */
	HumanInput(UserSettings settings) {
		this(settings.keys(), settings.dasMs(), settings.arrMs());
	}

	/**
	 * @param keys  키 묶음
	 * @param dasMs 좌우 자동 반복이 시작되기까지 (ms, 0 이상)
	 * @param arrMs 자동 반복 간격 (ms). 0 이면 틱마다 한 칸
	 */
	HumanInput(KeyBindings keys, int dasMs, int arrMs) {
		if (dasMs < 0 || arrMs < 0) {
			throw new IllegalArgumentException("DAS " + dasMs + "ms, ARR " + arrMs + "ms");
		}
		this.tracker = new KeyTracker(keys);
		this.dasMs = dasMs;
		this.arrMs = arrMs;
	}

	// ------------------------------------------------------------------ 키 이벤트 (화면 스레드)

	/**
	 * 키를 눌렀다 ({@code KEY_PRESSED} 의 {@code getKeyCode()}).
	 *
	 * @return 막 눌린 동작. 자동 반복, 이미 눌린 동작의 다른 키, 묶이지 않은 키면 null
	 */
	KeyAction keyPressed(int keyCode) {
		return tracker.press(keyCode);
	}

	/**
	 * 키를 뗐다 ({@code KEY_RELEASED} 의 {@code getKeyCode()}).
	 *
	 * @return 그 동작의 키가 모두 떨어졌으면 그 동작, 아니면 null
	 */
	KeyAction keyReleased(int keyCode) {
		return tracker.release(keyCode);
	}

	/**
	 * 블록 조작을 다시 받기 시작할 때(시작 카운트다운이 끝남, 일시정지를 푼 뒤의 카운트다운이 끝남) 부른다: 그동안 누르고
	 * 있던 소프트드롭과 좌우 이동 중 나중 것을 {@link #press} 로 넘긴다({@link KeyTracker#heldMoves()}). 화면 스레드에서.
	 */
	void resumeHeld() {
		for (KeyAction a : tracker.heldMoves()) {
			press(keyOf(a));
		}
	}

	/** 창이 포커스를 잃었다: 모든 키를 뗀 것으로 한다(그동안의 뗌은 오지 않는다). 화면 스레드에서. */
	void releaseAll() {
		tracker.clear();
		reset();
	}

	/** 이 판의 키 묶음 (화면의 키 안내용). */
	KeyBindings keys() {
		return tracker.keys();
	}

	int dasMs() {
		return dasMs;
	}

	int arrMs() {
		return arrMs;
	}

	/** 블록 조작 동작의 키 뜻. 블록 조작이 아니면(일시정지, 메뉴 …) null. */
	static Key keyOf(KeyAction a) {
		switch (a) {
		case MOVE_LEFT:
			return Key.LEFT;
		case MOVE_RIGHT:
			return Key.RIGHT;
		case SOFT_DROP:
			return Key.SOFT_DROP;
		case HARD_DROP:
			return Key.HARD_DROP;
		case ROTATE_CW:
			return Key.CW;
		case ROTATE_CCW:
			return Key.CCW;
		case HOLD:
			return Key.HOLD;
		default:
			return null;
		}
	}

	// ------------------------------------------------------------------ 조작 (화면 스레드에서 넣고 진행 스레드가 꺼낸다)

	/** 키를 눌렀다. 이미 눌린 키의 반복 이벤트(운영체제 자동 반복)는 무시한다. */
	void press(Key k) {
		if (!held.add(k)) {
			return;
		}
		switch (k) {
		case LEFT:
			left = true;
			lastDir = -1;
			presses.add(Input.L);
			break;
		case RIGHT:
			right = true;
			lastDir = 1;
			presses.add(Input.R);
			break;
		case SOFT_DROP:
			softDrop = true;
			break;
		case HARD_DROP:
			presses.add(Input.HD);
			break;
		case CW:
			presses.add(Input.CW);
			break;
		case CCW:
			presses.add(Input.CCW);
			break;
		case HOLD:
			presses.add(Input.H);
			break;
		default:
			break;
		}
	}

	void release(Key k) {
		held.remove(k);
		switch (k) {
		case LEFT:
			left = false;
			break;
		case RIGHT:
			right = false;
			break;
		case SOFT_DROP:
			softDrop = false;
			break;
		default:
			break;
		}
	}

	/**
	 * 누르고 있던 조작을 모두 뗀 것으로 한다(일시정지와 그 해제). 좌우 반복은 다음 틱에 처음부터다(다시 누르면 한 칸 뒤 DAS).
	 * 키 추적은 지우지 않는다: 누르고 있는 키는 다시 받기 시작할 때 {@link #resumeHeld()} 로 넘긴다.
	 */
	void reset() {
		left = right = softDrop = false;
		held.clear();
		presses.clear();
		rearm = true;
	}

	/** 소프트드롭 키를 누르고 있는가. */
	boolean softDrop() {
		return softDrop;
	}

	/** 틱 {@code tick} 의 조작, 없으면 null. 틱마다 한 번 부른다. */
	Input next(long tick) {
		if (rearm) { // 멈춘 동안에는 틱이 없으므로, 같은 쪽을 다시 눌러도 지난 반복이 이어지지 않게
			rearm = false;
			dir = 0;
		}
		int want = left && right ? lastDir : left ? -1 : right ? 1 : 0;
		if (want != dir) {
			dir = want;
			repeatAt = tick + dasMs;
		}
		Input once = presses.poll();
		if (once != null) {
			return once;
		}
		if (dir != 0 && tick >= repeatAt) {
			repeatAt = tick + arrMs;
			return dir < 0 ? Input.L : Input.R;
		}
		return null;
	}
}
