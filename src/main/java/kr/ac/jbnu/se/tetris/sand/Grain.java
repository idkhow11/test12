package kr.ac.jbnu.se.tetris.sand;

/**
 * 알갱이 하나의 값(16비트 {@code short})을 만들고 읽는다. <b>0 은 빈칸</b>이다.
 *
 * <pre>
 * 비트 0~2    색 번호 1~4 ({@link SandColor#code()})
 * 비트 3      반짝임: 지워질 덩어리. 움직이지 않는 장애물이고, 지우기·정착 검사에서 빠진다
 * 비트 4~5    명암 0~3: 알갱이마다 다른 작은 밝기 차이 (화면이 색 팔레트의 칸 번호로 쓴다)
 * 비트 6~8    원래 칸 안의 열 0~7
 * 비트 9~11   원래 칸 안의 행 0~7
 * </pre>
 *
 * 원래 칸 안 위치는 블록이 부서질 때 정해지고 알갱이와 함께 움직인다. 화면은 이것으로 색약 대응 무늬
 * ({@link SandColor#pattern})를 그린다. ({@link SandRules#GRAIN} 이 8 보다 크면 3비트에 들어가지 않는다.)
 * <p>
 * <b>그리기 번호</b>({@link #look}): 색·반짝임·명암·무늬를 0~{@value #LOOKS}-1 의 수 하나로 줄인 것이다. 화면은 번호마다
 * 색을 한 번 만들어 두고({@link #lookColor}, {@link #lookShade}, {@link #lookFlashing}, {@link #lookPattern}) 알갱이마다
 * 표에서 고르면 된다. 알갱이마다 객체를 만들지 않는다.
 */
public final class Grain {

	public static final short EMPTY = 0;

	/** 그리기 번호의 수 ({@link #look} 은 0 이상 이 값 미만). 0 은 빈칸이다. */
	public static final int LOOKS = 128;

	private static final int COLOR_MASK = 0x7;
	private static final int FLASH = 0x8;
	private static final int SHADE_SHIFT = 4;
	private static final int COL_SHIFT = 6;
	private static final int ROW_SHIFT = 9;
	/** 그리기 번호의 무늬 비트. 아래 6비트는 알갱이 값과 같다(색, 반짝임, 명암). */
	private static final int LOOK_PATTERN = 0x40;
	/** 알갱이 값의 아래 12비트 → 무늬 표시가 있는가. */
	private static final boolean[] PATTERN = new boolean[1 << 12];

	static {
		for (int v = 0; v < PATTERN.length; v++) {
			int code = v & COLOR_MASK;
			if (code >= 1 && code <= SandRules.COLORS) {
				PATTERN[v] = SandColor.of(code).pattern((v >> ROW_SHIFT) & 7, (v >> COL_SHIFT) & 7);
			}
		}
	}

	private Grain() {
	}

	/**
	 * @param shade     명암 0~3
	 * @param originRow 원래 칸 안의 행 0~7
	 * @param originCol 원래 칸 안의 열 0~7
	 */
	public static short of(SandColor color, int shade, int originRow, int originCol) {
		return (short) (color.code() | ((shade & 3) << SHADE_SHIFT) | ((originCol & 7) << COL_SHIFT)
				| ((originRow & 7) << ROW_SHIFT));
	}

	/** 색 번호 1~4, 빈칸이면 0. */
	public static int code(short v) {
		return v & COLOR_MASK;
	}

	/** 색, 빈칸이면 null. */
	public static SandColor color(short v) {
		return v == EMPTY ? null : SandColor.of(v & COLOR_MASK);
	}

	/** 지워지기 전에 반짝이는 중인가. */
	public static boolean flashing(short v) {
		return (v & FLASH) != 0;
	}

	/** 명암 0~3. */
	public static int shade(short v) {
		return (v >> SHADE_SHIFT) & 3;
	}

	/** 원래 칸 안의 행 0~7. */
	public static int originRow(short v) {
		return (v >> ROW_SHIFT) & 7;
	}

	/** 원래 칸 안의 열 0~7. */
	public static int originCol(short v) {
		return (v >> COL_SHIFT) & 7;
	}

	/** 이 알갱이에 색약 대응 무늬 표시가 있는가 ({@link SandColor#pattern}). 빈칸이면 false. */
	public static boolean pattern(short v) {
		return PATTERN[v & 0xFFF];
	}

	/**
	 * 그리기 번호 0~{@value #LOOKS}-1. 빈칸은 0 이고, 아니면 색·반짝임·명암·무늬가 모두 같은 알갱이끼리만 번호가 같다.
	 * 원래 칸 안 위치는 무늬로만 남는다.
	 */
	public static int look(short v) {
		return (v & 0x3F) | (PATTERN[v & 0xFFF] ? LOOK_PATTERN : 0);
	}

	/** 그리기 번호의 색. 빈칸이거나 쓰지 않는 번호면 null. */
	public static SandColor lookColor(int look) {
		int code = look & COLOR_MASK;
		return code >= 1 && code <= SandRules.COLORS ? SandColor.of(code) : null;
	}

	/** 그리기 번호의 명암 0~3. */
	public static int lookShade(int look) {
		return (look >> SHADE_SHIFT) & 3;
	}

	/** 그리기 번호가 반짝이는 알갱이인가. */
	public static boolean lookFlashing(int look) {
		return (look & FLASH) != 0;
	}

	/** 그리기 번호에 색약 대응 무늬 표시가 있는가. */
	public static boolean lookPattern(int look) {
		return (look & LOOK_PATTERN) != 0;
	}

	/** 반짝임 표시를 붙인 값. */
	static short flash(short v) {
		return (short) (v | FLASH);
	}
}
