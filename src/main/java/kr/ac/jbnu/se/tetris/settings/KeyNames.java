package kr.ac.jbnu.se.tetris.settings;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 키 코드({@link KeyEvent} 의 {@code VK_} 값, {@code KeyEvent#getKeyCode()})의 이름. 네 가지가 있다.
 * <ul>
 * <li>{@link #label(int)}: 키 모양 안에 쓰는 짧은 글자. 자판에 찍힌 대로 영어와 화살표다 ("←", "Space", "Ctrl", "Num 0").</li>
 * <li>{@link #phrase(int)}: 문장 안에서 조사를 붙일 이름 ("Space 키"). 조사는 언제나 '키' 에 붙으므로 "Space 키가",
 * "R 키는" 처럼 받침을 따질 일이 없다.</li>
 * <li>{@link #korean(int)}: 문장 안에 쓰는 한국어 이름 ("왼쪽 화살표", "스페이스바", "엔터"). 따로 없으면 {@code label} 과 같다.</li>
 * <li>{@link #token(int)}: 저장 파일에 쓰는 이름. {@code VK_} 를 뗀 이름이고({@code LEFT}, {@code SPACE}), 표에 없는 키는
 * {@code CODE_<번호>} 다.</li>
 * </ul>
 * 묶을 수 없는 키(Alt, Windows, 한/영 …)에도 이름을 붙여 "한/영 키는 쓸 수 없어요" 처럼 알린다. 키 코드는 컴파일할 때 숫자로
 * 바뀌므로 이 클래스는 AWT 를 띄우지 않는다(창 없는 시험에서도 돈다).
 */
public final class KeyNames {

	/** 키가 없음 ({@link KeyEvent#VK_UNDEFINED}). */
	public static final int NONE = KeyEvent.VK_UNDEFINED;

	private static final String CODE_PREFIX = "CODE_";
	/** 사람이 {@code KeyEvent} 문서를 보고 적은 이름({@code VK_SPACE})도 읽는다. */
	private static final String VK_PREFIX = "VK_";
	/** 표에 없는 키의 글자 ("키(123)"). 괄호 앞의 '키' 에 조사가 붙는다. */
	private static final String UNKNOWN = "키(";

	private record Name(int code, String token, String label, String korean) {
	}

	private static final Map<Integer, Name> BY_CODE = new HashMap<>();
	private static final Map<String, Name> BY_TOKEN = new HashMap<>();
	/** 묶을 수 없는 키: 운영체제와 입력기가 먼저 가져가거나(Windows 키, 한/영, 한자, Print Screen) 다른 키의 뜻을 바꾸는 키. */
	private static final Set<Integer> RESERVED = new HashSet<>();

	static {
		add(KeyEvent.VK_LEFT, "LEFT", "←", "왼쪽 화살표");
		add(KeyEvent.VK_RIGHT, "RIGHT", "→", "오른쪽 화살표");
		add(KeyEvent.VK_UP, "UP", "↑", "위쪽 화살표");
		add(KeyEvent.VK_DOWN, "DOWN", "↓", "아래쪽 화살표");
		add(KeyEvent.VK_SPACE, "SPACE", "Space", "스페이스바");
		add(KeyEvent.VK_ENTER, "ENTER", "Enter", "엔터");
		add(KeyEvent.VK_ESCAPE, "ESCAPE", "Esc", "Esc");
		add(KeyEvent.VK_TAB, "TAB", "Tab", "탭");
		add(KeyEvent.VK_BACK_SPACE, "BACK_SPACE", "Backspace", "백스페이스");
		add(KeyEvent.VK_SHIFT, "SHIFT", "Shift", "Shift");
		add(KeyEvent.VK_CONTROL, "CONTROL", "Ctrl", "Ctrl");
		add(KeyEvent.VK_CAPS_LOCK, "CAPS_LOCK", "Caps Lock", "Caps Lock");
		add(KeyEvent.VK_INSERT, "INSERT", "Insert", "Insert");
		add(KeyEvent.VK_DELETE, "DELETE", "Delete", "Delete");
		add(KeyEvent.VK_HOME, "HOME", "Home", "Home");
		add(KeyEvent.VK_END, "END", "End", "End");
		add(KeyEvent.VK_PAGE_UP, "PAGE_UP", "Page Up", "Page Up");
		add(KeyEvent.VK_PAGE_DOWN, "PAGE_DOWN", "Page Down", "Page Down");
		add(KeyEvent.VK_PAUSE, "PAUSE", "Pause", "Pause");
		add(KeyEvent.VK_SCROLL_LOCK, "SCROLL_LOCK", "Scroll Lock", "Scroll Lock");
		add(KeyEvent.VK_CLEAR, "CLEAR", "Clear", "Clear");
		add(KeyEvent.VK_HELP, "HELP", "Help", "Help");
		add(KeyEvent.VK_CANCEL, "CANCEL", "Break", "Break");
		for (char c = 'A'; c <= 'Z'; c++) {
			String s = String.valueOf(c);
			add(c, s, s, s);
		}
		for (char c = '0'; c <= '9'; c++) {
			String s = String.valueOf(c);
			add(c, s, s, s);
		}
		for (int i = 0; i < 12; i++) {
			String s = "F" + (i + 1);
			add(KeyEvent.VK_F1 + i, s, s, s);
		}
		for (int i = 0; i < 12; i++) {
			String s = "F" + (i + 13);
			add(KeyEvent.VK_F13 + i, s, s, s);
		}
		for (int i = 0; i <= 9; i++) {
			add(KeyEvent.VK_NUMPAD0 + i, "NUMPAD" + i, "Num " + i, "숫자패드 " + i);
		}
		add(KeyEvent.VK_MULTIPLY, "MULTIPLY", "Num *", "숫자패드 *");
		add(KeyEvent.VK_ADD, "ADD", "Num +", "숫자패드 +");
		add(KeyEvent.VK_SUBTRACT, "SUBTRACT", "Num -", "숫자패드 -");
		add(KeyEvent.VK_DIVIDE, "DIVIDE", "Num /", "숫자패드 /");
		add(KeyEvent.VK_DECIMAL, "DECIMAL", "Num .", "숫자패드 .");
		add(KeyEvent.VK_SEPARATOR, "SEPARATOR", "Num ,", "숫자패드 ,");
		add(KeyEvent.VK_KP_UP, "KP_UP", "Num ↑", "숫자패드 위");
		add(KeyEvent.VK_KP_DOWN, "KP_DOWN", "Num ↓", "숫자패드 아래");
		add(KeyEvent.VK_KP_LEFT, "KP_LEFT", "Num ←", "숫자패드 왼쪽");
		add(KeyEvent.VK_KP_RIGHT, "KP_RIGHT", "Num →", "숫자패드 오른쪽");
		add(KeyEvent.VK_COMMA, "COMMA", ",", ",");
		add(KeyEvent.VK_PERIOD, "PERIOD", ".", ".");
		add(KeyEvent.VK_SLASH, "SLASH", "/", "/");
		add(KeyEvent.VK_SEMICOLON, "SEMICOLON", ";", ";");
		add(KeyEvent.VK_QUOTE, "QUOTE", "'", "'");
		add(KeyEvent.VK_OPEN_BRACKET, "OPEN_BRACKET", "[", "[");
		add(KeyEvent.VK_CLOSE_BRACKET, "CLOSE_BRACKET", "]", "]");
		add(KeyEvent.VK_BACK_SLASH, "BACK_SLASH", "\\", "\\");
		add(KeyEvent.VK_MINUS, "MINUS", "-", "-");
		add(KeyEvent.VK_EQUALS, "EQUALS", "=", "=");
		add(KeyEvent.VK_BACK_QUOTE, "BACK_QUOTE", "`", "`");
		// 미국식이 아닌 자판에서 나오는 키
		add(KeyEvent.VK_LESS, "LESS", "<", "<");
		add(KeyEvent.VK_GREATER, "GREATER", ">", ">");
		add(KeyEvent.VK_AT, "AT", "@", "@");
		add(KeyEvent.VK_COLON, "COLON", ":", ":");
		add(KeyEvent.VK_CIRCUMFLEX, "CIRCUMFLEX", "^", "^");
		add(KeyEvent.VK_DOLLAR, "DOLLAR", "$", "$");
		add(KeyEvent.VK_EURO_SIGN, "EURO_SIGN", "€", "€");
		add(KeyEvent.VK_EXCLAMATION_MARK, "EXCLAMATION_MARK", "!", "!");
		add(KeyEvent.VK_INVERTED_EXCLAMATION_MARK, "INVERTED_EXCLAMATION_MARK", "¡", "¡");
		add(KeyEvent.VK_LEFT_PARENTHESIS, "LEFT_PARENTHESIS", "(", "(");
		add(KeyEvent.VK_RIGHT_PARENTHESIS, "RIGHT_PARENTHESIS", ")", ")");
		add(KeyEvent.VK_NUMBER_SIGN, "NUMBER_SIGN", "#", "#");
		add(KeyEvent.VK_PLUS, "PLUS", "+", "+");
		add(KeyEvent.VK_UNDERSCORE, "UNDERSCORE", "_", "_");
		add(KeyEvent.VK_AMPERSAND, "AMPERSAND", "&", "&");
		add(KeyEvent.VK_ASTERISK, "ASTERISK", "*", "*");
		add(KeyEvent.VK_QUOTEDBL, "QUOTEDBL", "\"", "\"");
		add(KeyEvent.VK_BRACELEFT, "BRACELEFT", "{", "{");
		add(KeyEvent.VK_BRACERIGHT, "BRACERIGHT", "}", "}");
		// 묶을 수 없는 키 (아래 RESERVED). 이름은 "… 키는 쓸 수 없어요" 알림에만 쓴다
		add(KeyEvent.VK_ALT, "ALT", "Alt", "Alt");
		add(KeyEvent.VK_ALT_GRAPH, "ALT_GRAPH", "Alt Gr", "Alt Gr");
		add(KeyEvent.VK_META, "META", "Cmd", "Command");
		add(KeyEvent.VK_WINDOWS, "WINDOWS", "Win", "윈도우");
		add(KeyEvent.VK_CONTEXT_MENU, "CONTEXT_MENU", "Menu", "메뉴");
		add(KeyEvent.VK_PRINTSCREEN, "PRINTSCREEN", "PrtSc", "Print Screen");
		add(KeyEvent.VK_NUM_LOCK, "NUM_LOCK", "Num Lock", "Num Lock");
		add(KeyEvent.VK_COMPOSE, "COMPOSE", "Compose", "Compose");
		add(KeyEvent.VK_KANA, "KANA", "한/영", "한/영"); // Windows 의 VK_HANGUL 과 같은 값
		add(KeyEvent.VK_KANJI, "KANJI", "한자", "한자"); // Windows 의 VK_HANJA 와 같은 값
		add(KeyEvent.VK_INPUT_METHOD_ON_OFF, "INPUT_METHOD_ON_OFF", "한/영", "한/영");

		int[] reserved = { KeyEvent.VK_UNDEFINED, KeyEvent.VK_ALT, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_META,
				KeyEvent.VK_WINDOWS, KeyEvent.VK_CONTEXT_MENU, KeyEvent.VK_PRINTSCREEN, KeyEvent.VK_NUM_LOCK,
				KeyEvent.VK_COMPOSE,
				// 입력기: 한/영(Windows 의 VK_HANGUL 과 같은 값), 한자, 그리고 일본어·중국어 입력기 키
				KeyEvent.VK_KANA, KeyEvent.VK_KANJI, KeyEvent.VK_FINAL, KeyEvent.VK_CONVERT, KeyEvent.VK_NONCONVERT,
				KeyEvent.VK_ACCEPT, KeyEvent.VK_MODECHANGE, KeyEvent.VK_ALPHANUMERIC, KeyEvent.VK_KATAKANA,
				KeyEvent.VK_HIRAGANA, KeyEvent.VK_FULL_WIDTH, KeyEvent.VK_HALF_WIDTH, KeyEvent.VK_ROMAN_CHARACTERS,
				KeyEvent.VK_ALL_CANDIDATES, KeyEvent.VK_PREVIOUS_CANDIDATE, KeyEvent.VK_CODE_INPUT,
				KeyEvent.VK_JAPANESE_KATAKANA, KeyEvent.VK_JAPANESE_HIRAGANA, KeyEvent.VK_JAPANESE_ROMAN,
				KeyEvent.VK_KANA_LOCK, KeyEvent.VK_INPUT_METHOD_ON_OFF };
		for (int k : reserved) {
			RESERVED.add(k);
		}
		// 데드 키(악센트를 붙이는 키)
		for (int k = KeyEvent.VK_DEAD_GRAVE; k <= KeyEvent.VK_DEAD_SEMIVOICED_SOUND; k++) {
			RESERVED.add(k);
		}
	}

	private KeyNames() {
	}

	private static void add(int code, String token, String label, String korean) {
		Name n = new Name(code, token, label, korean);
		if (BY_CODE.put(code, n) != null || BY_TOKEN.put(token, n) != null) {
			throw new AssertionError("키 이름이 겹침: " + token);
		}
	}

	/** 키 모양 안에 쓰는 짧은 글자 ("←", "Space", "X", "Num 0"). 표에 없는 키는 "키(123)", 키 없음은 "없음". */
	public static String label(int keyCode) {
		Name n = BY_CODE.get(keyCode);
		return n != null ? n.label() : keyCode == NONE ? "없음" : UNKNOWN + keyCode + ")";
	}

	/**
	 * 문장 안에서 조사를 붙일 이름: "Space 키", "한/영 키". 표에 없는 키는 "키(123)", 키 없음과 음수는 "그 키". 뒤에 붙는
	 * 조사는 언제나 '키' 의 것이다("Space 키가", "키(123)는", "그 키는").
	 */
	public static String phrase(int keyCode) {
		if (keyCode <= NONE) {
			return "그 키";
		}
		String l = label(keyCode);
		return l.startsWith(UNKNOWN) ? l : l + " 키";
	}

	/** 문장 안에 쓰는 한국어 이름 ("왼쪽 화살표", "스페이스바"). 따로 없으면 {@link #label(int)} 과 같다. */
	public static String korean(int keyCode) {
		Name n = BY_CODE.get(keyCode);
		return n != null ? n.korean() : label(keyCode);
	}

	/** 저장 파일에 쓰는 이름 ("LEFT", "SPACE", "X"). 표에 없는 키는 "CODE_123". */
	public static String token(int keyCode) {
		Name n = BY_CODE.get(keyCode);
		return n != null ? n.token() : CODE_PREFIX + keyCode;
	}

	/** {@link #token(int)} 의 반대. 대소문자는 가리지 않고 앞의 {@code VK_} 는 떼고 읽는다. 읽을 수 없으면 -1. */
	public static int code(String token) {
		if (token == null) {
			return -1;
		}
		String t = token.trim().toUpperCase(Locale.ROOT);
		if (t.startsWith(VK_PREFIX)) {
			t = t.substring(VK_PREFIX.length());
		}
		Name n = BY_TOKEN.get(t);
		if (n != null) {
			return n.code();
		}
		if (t.startsWith(CODE_PREFIX) && t.length() > CODE_PREFIX.length()) {
			String digits = t.substring(CODE_PREFIX.length());
			if (digits.length() <= 9 && digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
				return Integer.parseInt(digits);
			}
		}
		return -1;
	}

	/** 동작에 묶을 수 있는 키인가. 키 없음, 음수, 운영체제·입력기가 먼저 가져가는 키는 묶을 수 없다. */
	public static boolean bindable(int keyCode) {
		return keyCode > 0 && !RESERVED.contains(keyCode);
	}

	/** 키 여러 개를 " / " 로 이어 쓴다 ("↑ / X"). */
	public static String join(List<Integer> keyCodes) {
		StringJoiner j = new StringJoiner(" / ");
		for (int k : keyCodes) {
			j.add(label(k));
		}
		return j.toString();
	}
}
