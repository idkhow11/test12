package kr.ac.jbnu.se.tetris.sand;

/**
 * 모래의 색 4가지 {@code [초안]}. 색은 블록 모양과 따로 정해진다(12개짜리 색 가방, {@link SandRules#COLOR_COPIES}).
 * <p>
 * 색마다 무늬({@link #pattern})도 다르게 두어, 색을 구분하기 어려운 사람도 알아볼 수 있게 한다. 무늬는 알갱이가 원래 있던
 * 칸 안 위치로 정하므로 막 부서진 모래에서는 무늬가 또렷하고, 흘러내려 섞여도 색마다 무늬 알갱이의 비율(밀도)이 달라서
 * 구분된다.
 */
public enum SandColor {
	RED("빨강"), BLUE("파랑"), GREEN("초록"), YELLOW("노랑");

	/** {@link #values()} 는 부를 때마다 배열을 새로 만들므로 한 번만 만들어 둔다(화면이 알갱이마다 부른다). */
	private static final SandColor[] ALL = values();

	private final String label;

	SandColor(String label) {
		this.label = label;
	}

	/** 알갱이 값에 들어가는 색 번호 1~4. 0 은 빈칸이라 쓰지 않는다. */
	public int code() {
		return ordinal() + 1;
	}

	/** 화면에 쓰는 한국어 이름. */
	public String label() {
		return label;
	}

	/** 색 번호 1~4 의 색. */
	public static SandColor of(int code) {
		return ALL[code - 1];
	}

	/** 시험·디버깅용 글자 (R, B, G, Y). */
	public char letter() {
		return name().charAt(0);
	}

	/** {@link #letter()} 의 반대. 대문자만 받는다. */
	public static SandColor fromLetter(char c) {
		for (SandColor v : ALL) {
			if (v.letter() == c) {
				return v;
			}
		}
		throw new IllegalArgumentException("모르는 색 글자: " + c);
	}

	/**
	 * 색약 대응 무늬: 원래 칸 안의 (행, 열) 0~7 자리 알갱이에 무늬 표시가 있는가. {@code [초안]}
	 * <ul>
	 * <li>빨강: 민무늬 (0/64)</li>
	 * <li>노랑: 성긴 점 (4/64)</li>
	 * <li>파랑: 가로줄 (16/64)</li>
	 * <li>초록: 2 × 2 바둑판 (32/64). 빨강과 가장 헷갈리기 쉬운 색이라 가장 다른 무늬를 준다</li>
	 * </ul>
	 * 화면이 무늬 알갱이를 조금 밝게 또는 어둡게 그린다.
	 */
	public boolean pattern(int row, int col) {
		switch (this) {
		case BLUE:
			return row % 4 == 1;
		case GREEN:
			return ((row >> 1) + (col >> 1)) % 2 == 0;
		case YELLOW:
			return row % 4 == 2 && col % 4 == 2;
		default:
			return false;
		}
	}
}
