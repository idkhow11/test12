package kr.ac.jbnu.se.tetris.settings;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 동작마다 묶은 키 (docs/GAME.md 4장). 값이 바뀌지 않는 객체이고, 고치는 메서드는 새 객체를 돌려준다. 여러 스레드가 함께
 * 읽어도 된다.
 * <p>
 * 언제나 지키는 것:
 * <ul>
 * <li>모든 동작에 키가 1~{@value #MAX_KEYS}개 있고, 한 동작 안에서 같은 키가 두 번 나오지 않는다.</li>
 * <li>한 키는 한 동작에만 쓴다(화면이 달라도). 그래서 판이 끝나는 순간 하드드롭을 연타해도 '다시 하기'가 눌리지 않고,
 * {@link #actionOf} 의 답이 언제나 하나다.</li>
 * <li>묶을 수 없는 키({@link KeyNames#bindable})는 없다.</li>
 * <li>창 어디서나 듣는 동작(전체 화면)에는 메뉴 화면의 고정 키({@link #MENU_KEYS})와 수식 키(Shift, Ctrl)를 쓰지 않는다.</li>
 * </ul>
 * 설정 화면에서 다른 동작이 쓰던 키를 고르면 두 동작의 키를 맞바꾼다({@link #assign}, 알림 글은 {@link #assignNotice}). 맞바꿀
 * 키가 없어 그 동작의 키가 하나도 남지 않게 되면 바꾸지 않는다({@link #reasonAgainst} 가 이유를 준다). 화면에 보이는 글은 모두
 * 짧은 한국어 한 줄이다.
 */
public final class KeyBindings {

	/** 동작 하나에 둘 수 있는 키의 수. */
	public static final int MAX_KEYS = 3;

	/**
	 * 메뉴·설정·도움말 화면이 언제나 쓰는 고정 키 (↑ ↓ ← →, Enter, Space, Esc, Tab). 그런 화면은 이 키만 쓰고, 창 어디서나 듣는
	 * 동작에는 이 키를 묶을 수 없다.
	 */
	public static final Set<Integer> MENU_KEYS = Set.of(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT,
			KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_ESCAPE, KeyEvent.VK_TAB);

	/** 수식 키. 창 어디서나 듣는 동작에 쓰면 Shift 를 누를 때마다 그 동작이 일어나므로 쓰지 않는다. */
	private static final Set<Integer> MODIFIERS = Set.of(KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL);

	/** 한 키를 두 동작이 함께 쓰는 곳 ({@link #conflicts}). {@code first} 가 동작 순서로 앞선다. */
	public record Conflict(int keyCode, KeyAction first, KeyAction second) {

		/** 화면에 보일 한 줄 ("Space 키가 하드드롭·홀드에 함께 있어요"). */
		public String message() {
			return KeyNames.phrase(keyCode) + "가 " + first.label() + "·" + second.label() + "에 함께 있어요";
		}
	}

	private static final KeyBindings DEFAULTS;

	static {
		Map<KeyAction, List<Integer>> map = new EnumMap<>(KeyAction.class);
		for (KeyAction a : KeyAction.values()) {
			map.put(a, a.defaultKeys());
		}
		DEFAULTS = of(map);
	}

	private final Map<KeyAction, List<Integer>> keys;
	private final Map<Integer, KeyAction> owners;

	/** {@code map} 은 {@link #problem} 이 null 이어야 한다. */
	private KeyBindings(Map<KeyAction, ? extends List<Integer>> map) {
		Map<KeyAction, List<Integer>> k = new EnumMap<>(KeyAction.class);
		Map<Integer, KeyAction> o = new HashMap<>();
		for (KeyAction a : KeyAction.values()) {
			List<Integer> list = List.copyOf(map.get(a));
			k.put(a, list);
			for (int code : list) {
				o.put(code, a);
			}
		}
		this.keys = Collections.unmodifiableMap(k);
		this.owners = o;
	}

	/** 처음 키 (설정 화면이 생기기 전과 같다, {@link KeyAction} 참고). */
	public static KeyBindings defaults() {
		return DEFAULTS;
	}

	/**
	 * 동작 → 키 목록으로 만든다.
	 *
	 * @throws IllegalArgumentException 지켜야 할 것을 어기면({@link #problem} 의 문구)
	 */
	public static KeyBindings of(Map<KeyAction, ? extends List<Integer>> map) {
		String why = problem(map);
		if (why != null) {
			throw new IllegalArgumentException(why);
		}
		return new KeyBindings(map);
	}

	/**
	 * 동작 → 키 목록이 지켜야 할 것을 어기는 첫 이유 (화면에 보일 한 줄), 문제가 없으면 null. 빠진 동작, 키가 없거나 너무
	 * 많은 동작, 한 동작 안에서 겹친 키, 묶을 수 없는 키, 전체 화면에 쓸 수 없는 키, 두 동작이 함께 쓰는 키를 본다.
	 */
	public static String problem(Map<KeyAction, ? extends Collection<Integer>> map) {
		for (KeyAction a : KeyAction.values()) {
			Collection<Integer> list = map.get(a);
			if (list == null || list.isEmpty()) {
				return a.label() + "에 키가 없어요";
			}
			if (list.size() > MAX_KEYS) {
				return a.label() + "의 키는 " + MAX_KEYS + "개까지예요";
			}
			Set<Integer> seen = new HashSet<>();
			for (Integer code : list) {
				if (code == null || !KeyNames.bindable(code)) {
					return a.label() + "에 쓸 수 없는 키가 있어요";
				}
				if (!seen.add(code)) {
					return a.label() + "에 " + KeyNames.phrase(code) + "가 두 번 있어요";
				}
				if (!allowed(a, code)) {
					return notAllowedReason(a, code);
				}
			}
		}
		List<Conflict> c = conflicts(map);
		return c.isEmpty() ? null : c.get(0).message();
	}

	/** 동작 → 키 목록에서 두 동작이 함께 쓰는 키를 모두 찾는다 (동작 순서, 그 안에서 키 순서). 빠진 동작은 건너뛴다. */
	public static List<Conflict> conflicts(Map<KeyAction, ? extends Collection<Integer>> map) {
		List<Conflict> out = new ArrayList<>();
		Map<Integer, KeyAction> first = new HashMap<>();
		for (KeyAction a : KeyAction.values()) {
			Collection<Integer> list = map.get(a);
			if (list == null) {
				continue;
			}
			for (Integer code : list) {
				if (code == null) {
					continue;
				}
				KeyAction before = first.putIfAbsent(code, a);
				if (before != null && before != a) {
					out.add(new Conflict(code, before, a));
				}
			}
		}
		return out;
	}

	/** 동작 {@code a} 의 키 (바꿀 수 없는 목록, 비지 않음). 첫 키가 대표 키다. */
	public List<Integer> keys(KeyAction a) {
		return keys.get(a);
	}

	/** 키 {@code keyCode} 를 쓰는 동작, 없으면 null. 화면이 키를 받으면 이것으로 동작을 찾는다({@code e.getKeyCode()}). */
	public KeyAction actionOf(int keyCode) {
		return owners.get(keyCode);
	}

	/** 동작 {@code a} 의 키를 이어 쓴다 ("↑ / X"). */
	public String describe(KeyAction a) {
		return KeyNames.join(keys(a));
	}

	/** 동작 {@code a} 의 앞 {@code max} 개 키만 이어 쓴다 (화면 아래 짧은 안내: {@code describe(ROTATE_CW, 1)} = "↑"). */
	public String describe(KeyAction a, int max) {
		List<Integer> k = keys(a);
		return KeyNames.join(k.subList(0, Math.max(0, Math.min(max, k.size()))));
	}

	/** 모든 동작이 기본 키인가. */
	public boolean isDefault() {
		return equals(DEFAULTS);
	}

	/** 동작 {@code a} 가 기본 키인가 (순서까지). */
	public boolean isDefault(KeyAction a) {
		return keys(a).equals(a.defaultKeys());
	}

	/** 동작 → 키 목록 (바꿀 수 없는 지도, 동작 순서). */
	public Map<KeyAction, List<Integer>> asMap() {
		return keys;
	}

	/**
	 * 동작 {@code a} 의 {@code slot} 번째 키를 {@code keyCode} 로 바꿀 수 없는 이유 (화면에 보일 한 줄), 바꿀 수 있으면 null.
	 * {@code slot} 이 지금 키의 수와 같으면 키를 하나 더하는 것이다. 예: "한/영 키는 쓸 수 없어요", "P 키는 일시정지의 하나뿐인
	 * 키예요", "키는 동작마다 3개까지예요".
	 *
	 * @throws IndexOutOfBoundsException {@code slot} 이 0 보다 작거나 키의 수보다 크면
	 */
	public String reasonAgainst(KeyAction a, int slot, int keyCode) {
		List<Integer> mine = keys(a);
		if (slot < 0 || slot > mine.size()) {
			throw new IndexOutOfBoundsException("slot " + slot + " (키 " + mine.size() + "개)");
		}
		if (!KeyNames.bindable(keyCode)) {
			return KeyNames.phrase(keyCode) + "는 쓸 수 없어요";
		}
		if (!allowed(a, keyCode)) {
			return notAllowedReason(a, keyCode);
		}
		boolean adding = slot == mine.size();
		if (mine.contains(keyCode)) {
			return null; // 이미 이 동작의 키: 자리만 바꾸거나 그대로 둔다
		}
		if (adding && mine.size() >= MAX_KEYS) {
			return "키는 동작마다 " + MAX_KEYS + "개까지예요";
		}
		KeyAction holder = actionOf(keyCode);
		if (holder != null && keys(holder).size() == 1) {
			int old = adding ? KeyNames.NONE : mine.get(slot);
			if (old == KeyNames.NONE || !allowed(holder, old)) {
				return KeyNames.phrase(keyCode) + "는 " + holder.label() + "의 하나뿐인 키예요";
			}
		}
		return null;
	}

	/**
	 * 동작 {@code a} 의 {@code slot} 번째 키를 {@code keyCode} 로 바꾼다({@code slot} 이 키의 수와 같으면 더한다).
	 * <ul>
	 * <li>이미 {@code a} 의 키면 두 자리를 맞바꾼다(더하는 것이면 그대로).</li>
	 * <li>다른 동작이 쓰던 키면 그 동작은 {@code a} 가 내놓은 키를 받는다(맞바꿈). 내놓은 키가 없거나 그 동작에 쓸 수 없는
	 * 키면 그 동작에서 빼기만 한다.</li>
	 * </ul>
	 * 다른 동작이 어떻게 바뀌는지는 바꾸기 전에 {@link #assignNotice} 로 알 수 있다.
	 *
	 * @throws IllegalArgumentException {@link #reasonAgainst} 가 이유를 주면 (같은 문구)
	 */
	public KeyBindings assign(KeyAction a, int slot, int keyCode) {
		String why = reasonAgainst(a, slot, keyCode);
		if (why != null) {
			throw new IllegalArgumentException(why);
		}
		List<Integer> mine = new ArrayList<>(keys(a));
		boolean adding = slot == mine.size();
		int old = adding ? KeyNames.NONE : mine.get(slot);
		if (old == keyCode) {
			return this;
		}
		Map<KeyAction, List<Integer>> next = new EnumMap<>(keys);
		int at = mine.indexOf(keyCode);
		if (at >= 0) {
			if (adding) {
				return this;
			}
			mine.set(at, old);
			mine.set(slot, keyCode);
		} else {
			KeyAction holder = actionOf(keyCode);
			if (holder != null) {
				List<Integer> theirs = new ArrayList<>(keys(holder));
				int i = theirs.indexOf(keyCode);
				if (old != KeyNames.NONE && allowed(holder, old)) {
					theirs.set(i, old);
				} else {
					theirs.remove(i);
				}
				next.put(holder, theirs);
			}
			if (adding) {
				mine.add(keyCode);
			} else {
				mine.set(slot, keyCode);
			}
		}
		next.put(a, mine);
		return of(next);
	}

	/**
	 * {@code assign(a, slot, keyCode)} 를 하면 <b>다른</b> 동작의 키가 어떻게 되는지 알리는 한 줄, 다른 동작이 바뀌지 않거나
	 * 바꿀 수 없으면 null. 바꾸기 전의 묶음에서 부른다. 예: 홀드의 C 자리에 Space 를 주면 "하드드롭 키는 이제 C", 시계 회전의
	 * X 를 홀드에 더하면 "시계 회전 키는 이제 ↑".
	 */
	public String assignNotice(KeyAction a, int slot, int keyCode) {
		if (slot < 0 || slot > keys(a).size() || reasonAgainst(a, slot, keyCode) != null) {
			return null;
		}
		KeyAction holder = actionOf(keyCode);
		if (holder == null || holder == a) {
			return null;
		}
		return holder.label() + " 키는 이제 " + assign(a, slot, keyCode).describe(holder);
	}

	/**
	 * 동작 {@code a} 의 {@code slot} 번째 키를 뺀다.
	 *
	 * @throws IllegalArgumentException 하나뿐인 키를 빼려 하면
	 */
	public KeyBindings remove(KeyAction a, int slot) {
		List<Integer> mine = new ArrayList<>(keys(a));
		if (slot < 0 || slot >= mine.size()) {
			throw new IndexOutOfBoundsException("slot " + slot + " (키 " + mine.size() + "개)");
		}
		if (mine.size() == 1) {
			throw new IllegalArgumentException(a.label() + "에는 키가 하나는 있어야 해요");
		}
		mine.remove(slot);
		Map<KeyAction, List<Integer>> next = new EnumMap<>(keys);
		next.put(a, mine);
		return of(next);
	}

	/**
	 * 동작 {@code a} 만 기본 키로 되돌린다. 기본 키를 다른 동작이 쓰고 있으면 {@link #assign} 처럼 맞바꾸고, 하나뿐인 키라
	 * 가져올 수 없는 기본 키는 건너뛴다. 기본 키보다 많던 키는 뺀다. 모두 되돌리기는 {@link #defaults()} 다.
	 */
	public KeyBindings reset(KeyAction a) {
		KeyBindings r = this;
		List<Integer> want = a.defaultKeys();
		for (int i = 0; i < want.size(); i++) {
			int slot = Math.min(i, r.keys(a).size());
			if (r.reasonAgainst(a, slot, want.get(i)) == null) {
				r = r.assign(a, slot, want.get(i));
			}
		}
		while (r.keys(a).size() > want.size()) {
			r = r.remove(a, r.keys(a).size() - 1);
		}
		return r;
	}

	/** 동작 {@code a} 에 키 {@code keyCode} 를 쓸 수 있는가 (창 어디서나 듣는 동작은 메뉴 고정 키와 수식 키를 못 쓴다). */
	static boolean allowed(KeyAction a, int keyCode) {
		return !a.global() || !(MENU_KEYS.contains(keyCode) || MODIFIERS.contains(keyCode));
	}

	private static String notAllowedReason(KeyAction a, int keyCode) {
		return KeyNames.phrase(keyCode) + "는 " + a.label() + "에 쓸 수 없어요";
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof KeyBindings other && keys.equals(other.keys);
	}

	@Override
	public int hashCode() {
		return keys.hashCode();
	}

	@Override
	public String toString() {
		StringJoiner j = new StringJoiner(", ", "KeyBindings{", "}");
		for (KeyAction a : KeyAction.values()) {
			StringJoiner t = new StringJoiner(",");
			for (int code : keys(a)) {
				t.add(KeyNames.token(code));
			}
			j.add(a.id() + "=" + t);
		}
		return j.toString();
	}
}
