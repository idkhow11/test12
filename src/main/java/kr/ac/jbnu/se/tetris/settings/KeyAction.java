package kr.ac.jbnu.se.tetris.settings;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;

/**
 * 키로 하는 동작 (docs/GAME.md 4장). 기본 키는 설정 화면이 생기기 전의 키와 같다(GamePanel 의 {@code key()}·{@code onKey()},
 * TetrisApp 의 F11): ← → 이동, ↓ 소프트드롭, Space 하드드롭, ↑·X 시계 회전, Z·Ctrl 반시계 회전, C·Shift 홀드, P 일시정지,
 * Esc 메뉴·기권, Enter·R 다시 하기, F11 전체 화면.
 * <p>
 * 앞의 일곱 가지는 블록 조작({@link #controlsPiece()})이고 고전 대전, 혼자 연습, 모래 모드가 같은 키를 쓴다. 나머지는 화면
 * 조작이다. {@link #id()} 는 저장 파일에 쓰는 이름이라 바꾸지 않는다. 동작을 더할 때는 기본 키가 다른 동작의 기본 키와 겹치지
 * 않게 한다(겹치면 {@link KeyBindings#defaults()} 를 만들 때 예외가 난다). 옛 저장 파일에는 새 동작이 없으므로, 읽을 때
 * 기본 키가 비어 있으면 그것을, 다 쓰이고 있으면 남는 키 하나를 준다(사용자가 바꾼 다른 키는 그대로 둔다).
 */
public enum KeyAction {

	MOVE_LEFT("move_left", "왼쪽 이동", true, KeyEvent.VK_LEFT),
	MOVE_RIGHT("move_right", "오른쪽 이동", true, KeyEvent.VK_RIGHT),
	SOFT_DROP("soft_drop", "소프트드롭", true, KeyEvent.VK_DOWN),
	HARD_DROP("hard_drop", "하드드롭", true, KeyEvent.VK_SPACE),
	ROTATE_CW("rotate_cw", "시계 회전", true, KeyEvent.VK_UP, KeyEvent.VK_X),
	ROTATE_CCW("rotate_ccw", "반시계 회전", true, KeyEvent.VK_Z, KeyEvent.VK_CONTROL),
	HOLD("hold", "홀드", true, KeyEvent.VK_C, KeyEvent.VK_SHIFT),
	PAUSE("pause", "일시정지", false, KeyEvent.VK_P),
	/** 혼자 연습은 바로 메뉴로, 대전은 2초 안에 한 번 더 누르면 기권, 결과 화면에서는 메뉴로. */
	MENU("menu", "메뉴·기권", false, KeyEvent.VK_ESCAPE),
	/** 결과 화면에서 같은 설정으로 한 판 더. */
	RETRY("retry", "다시 하기", false, KeyEvent.VK_ENTER, KeyEvent.VK_R),
	/** 창 어디서나 듣는다(메뉴 화면 포함). */
	FULLSCREEN("fullscreen", "전체 화면", false, KeyEvent.VK_F11);

	private final String id;
	private final String label;
	private final boolean controlsPiece;
	private final List<Integer> defaultKeys;

	KeyAction(String id, String label, boolean controlsPiece, int... defaultKeys) {
		this.id = id;
		this.label = label;
		this.controlsPiece = controlsPiece;
		this.defaultKeys = Arrays.stream(defaultKeys).boxed().toList();
	}

	/** 저장 파일에 쓰는 이름 ({@code key.<id>=...}). */
	public String id() {
		return id;
	}

	/** 화면에 보이는 이름 ("하드드롭"). */
	public String label() {
		return label;
	}

	/** 블록을 움직이는 조작인가 (HumanInput 이 받는 일곱 가지). 아니면 화면 조작(일시정지, 메뉴, 다시 하기, 전체 화면)이다. */
	public boolean controlsPiece() {
		return controlsPiece;
	}

	/**
	 * 창 어디서나(메뉴, 설정, 대전 화면) 듣는 동작인가. 그런 동작에는 메뉴 화면의 고정 키({@link KeyBindings#MENU_KEYS})와
	 * 수식 키(Shift, Ctrl)를 쓸 수 없다.
	 */
	public boolean global() {
		return this == FULLSCREEN;
	}

	/** 기본 키 (바꿀 수 없는 목록, 첫 키가 대표 키). */
	public List<Integer> defaultKeys() {
		return defaultKeys;
	}

	/** {@link #id()} 로 찾는다. 없으면 null. */
	public static KeyAction byId(String id) {
		for (KeyAction a : values()) {
			if (a.id.equals(id)) {
				return a;
			}
		}
		return null;
	}
}
