package kr.ac.jbnu.se.tetris.settings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 눌린 키를 세어, 키 이벤트를 동작의 "막 눌림"·"막 뗌" 으로 바꾼다. 대전 화면과 모래 화면이 판마다 하나 만들어 화면
 * 스레드에서만 쓴다(스레드 안전하지 않다).
 * <ul>
 * <li>운영체제의 자동 반복(누르고 있으면 KEY_PRESSED 가 초당 약 30번 다시 옴)은 무시한다. 그래서 Esc 를 누르고 있어도
 * 기권 확인이 저절로 넘어가지 않고, P 를 누르고 있어도 일시정지가 깜빡이지 않는다.</li>
 * <li>한 동작에 키가 둘 이상이면(↑·X) 처음 눌린 키에서 눌림, 마지막 키를 뗄 때 뗌이다. 그래서 ← 를 누른 채 다른 왼쪽 키를
 * 눌렀다 떼도 자동 반복(DAS)이 끊기지 않고, ↑ 를 누른 채 X 를 눌러도 회전은 한 번이다.</li>
 * <li>묶이지 않은 키는 무시한다.</li>
 * </ul>
 * 자동 반복을 거르므로, 카운트다운이나 일시정지 동안 버린 눌림은 다시 오지 않는다. 그동안 누르고 있던 이동 키는 블록 조작을
 * 다시 받기 시작할 때 {@link #heldMoves()} 로 넘긴다(그러지 않으면 GO 뒤에 다시 눌러야 움직인다).
 * <p>
 * 키 묶음은 만들 때의 것을 쓴다. 설정이 바뀌면 새로 만든다.
 */
public final class KeyTracker {

	private final KeyBindings keys;
	private final Set<Integer> down = new HashSet<>();
	private final Map<KeyAction, Integer> held = new EnumMap<>(KeyAction.class);
	/** 눌려 있는 동작이 눌린 차례 (클수록 나중). */
	private final Map<KeyAction, Long> since = new EnumMap<>(KeyAction.class);
	private long order;

	public KeyTracker(KeyBindings keys) {
		this.keys = Objects.requireNonNull(keys, "keys");
	}

	/** 이 추적기가 쓰는 키 묶음. */
	public KeyBindings keys() {
		return keys;
	}

	/**
	 * 키를 눌렀다 ({@code KEY_PRESSED} 의 {@code getKeyCode()}).
	 *
	 * @return 이 키로 동작이 막 눌렸으면 그 동작. 자동 반복, 이미 눌린 동작의 다른 키, 묶이지 않은 키면 null
	 */
	public KeyAction press(int keyCode) {
		KeyAction a = keys.actionOf(keyCode);
		if (a == null || !down.add(keyCode)) {
			return null;
		}
		int n = held.merge(a, 1, Integer::sum);
		if (n != 1) {
			return null;
		}
		since.put(a, ++order);
		return a;
	}

	/**
	 * 키를 뗐다 ({@code KEY_RELEASED} 의 {@code getKeyCode()}).
	 *
	 * @return 이 동작의 키가 모두 떨어졌으면 그 동작. 아직 다른 키를 누르고 있거나, 누른 적 없는 키(다른 화면에서 누른 키)면
	 *         null
	 */
	public KeyAction release(int keyCode) {
		if (!down.remove(keyCode)) {
			return null;
		}
		KeyAction a = keys.actionOf(keyCode);
		Integer n = held.get(a);
		if (n == null || n <= 1) {
			held.remove(a);
			since.remove(a);
			return a;
		}
		held.put(a, n - 1);
		return null;
	}

	/** 동작 {@code a} 의 키를 하나라도 누르고 있는가. */
	public boolean held(KeyAction a) {
		return held.containsKey(a);
	}

	/**
	 * 블록 조작을 다시 받기 시작할 때(시작 카운트다운이 끝남, 일시정지를 푼 뒤의 카운트다운이 끝남) HumanInput 에 넘길 동작:
	 * 누르고 있는 소프트드롭, 그리고 왼쪽·오른쪽 이동 가운데 나중에 눌린 하나. 이 차례로 {@code human.press} 에 넘긴다.
	 * <ul>
	 * <li>설정 화면이 생기기 전에는 카운트다운 동안 누르고 있던 키를 운영체제의 자동 반복이 GO 뒤에 다시 눌러 주었다. 이것이
	 * 그 일을 대신한다(GO 와 함께 한 칸 움직이고 DAS 가 시작된다).</li>
	 * <li>좌우를 둘 다 누르고 있으면 나중 것만 넘긴다: 둘 다 넘기면 첫 한 칸이 왼쪽·오른쪽으로 한 번씩 움직인다. 나중 쪽으로
	 * 가는 것은 HumanInput 의 "양쪽을 다 누르면 나중에 누른 쪽" 과 같다.</li>
	 * <li>하드드롭·회전·홀드는 넘기지 않는다: GO 직후 뜻하지 않은 하드드롭이 나지 않게.</li>
	 * </ul>
	 *
	 * @return 넘길 동작 (0~2개, 바꿀 수 없는 목록)
	 */
	public List<KeyAction> heldMoves() {
		List<KeyAction> out = new ArrayList<>(2);
		if (held(KeyAction.SOFT_DROP)) {
			out.add(KeyAction.SOFT_DROP);
		}
		Long left = since.get(KeyAction.MOVE_LEFT);
		Long right = since.get(KeyAction.MOVE_RIGHT);
		if (left != null || right != null) {
			out.add(right == null || left != null && left > right ? KeyAction.MOVE_LEFT : KeyAction.MOVE_RIGHT);
		}
		return List.copyOf(out);
	}

	/**
	 * 모두 뗀 것으로 한다. 창이 포커스를 잃었을 때 부른다(그동안의 뗌은 오지 않는다). 뗌을 따로 알리지는 않는다. 일시정지에는
	 * 부르지 않는다: P 를 누르고 있으면 그 자동 반복이 새 눌림이 되어 일시정지가 바로 풀린다.
	 */
	public void clear() {
		down.clear();
		held.clear();
		since.clear();
	}
}
