package kr.ac.jbnu.se.tetris.ui;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 사람의 키 입력을 틱마다 조작 하나로 바꾼다 (docs/GAME.md 4장). 키 이벤트는 화면 스레드가, {@link #next(long)} 은 게임
 * 진행 스레드가 부른다.
 * <p>
 * 누를 때 한 번 하는 조작(회전, 하드드롭, 홀드, 그리고 좌우의 첫 한 칸)은 대기열에 넣어 하나도 빠뜨리지 않는다. 좌우를
 * 누르고 있으면 DAS 뒤부터 ARR 마다 반복한다. 양쪽을 다 누르고 있으면 나중에 누른 쪽만 반복한다. 운영체제의 키 자동
 * 반복은 <b>모든 키에서</b> 무시한다(누른 키는 뗄 때까지 다시 누른 것으로 치지 않는다).
 */
final class HumanInput {

	/** 좌우 자동 반복이 시작되기까지 (ms). `[초안]` */
	static final int DAS_MS = 170;
	/** 자동 반복 간격 (ms). `[초안]` */
	static final int ARR_MS = 50;

	/** 키의 뜻. */
	enum Key {
		LEFT, RIGHT, SOFT_DROP, HARD_DROP, CW, CCW, HOLD
	}

	private final ConcurrentLinkedQueue<Input> presses = new ConcurrentLinkedQueue<>();
	private final Set<Key> held = EnumSet.noneOf(Key.class); // 화면 스레드만 쓴다
	private volatile boolean left;
	private volatile boolean right;
	private volatile boolean softDrop;
	private volatile int lastDir;

	// 게임 진행 스레드만 쓴다.
	private int dir;
	private long repeatAt;

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

	/** 누르고 있던 키를 모두 뗀 것으로 한다(창이 포커스를 잃었을 때, 일시정지). 반복 상태는 다음 틱에 저절로 풀린다. */
	void reset() {
		left = right = softDrop = false;
		held.clear();
		presses.clear();
	}

	/** 소프트드롭 키를 누르고 있는가. */
	boolean softDrop() {
		return softDrop;
	}

	/** 틱 {@code tick} 의 조작, 없으면 null. 틱마다 한 번 부른다. */
	Input next(long tick) {
		int want = left && right ? lastDir : left ? -1 : right ? 1 : 0;
		if (want != dir) {
			dir = want;
			repeatAt = tick + DAS_MS;
		}
		Input once = presses.poll();
		if (once != null) {
			return once;
		}
		if (dir != 0 && tick >= repeatAt) {
			repeatAt = tick + ARR_MS;
			return dir < 0 ? Input.L : Input.R;
		}
		return null;
	}
}
