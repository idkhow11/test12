package kr.ac.jbnu.se.tetris.settings;

/**
 * 창 어디서나 듣는 전체 화면 키({@link KeyAction#FULLSCREEN})를 누를 때마다 한 번만 바꾸게 한다. TetrisApp 의
 * KeyEventDispatcher 가 화면 스레드에서 쓴다(스레드 안전하지 않다).
 * <p>
 * 전체 화면을 바꾸면 창을 닫았다 다시 열므로, 그 사이에 뗀 KEY_RELEASED 를 받지 못할 수 있다. 그래서 뗌만 믿지 않고 시간도
 * 본다: 같은 키의 눌림이 {@value #REPEAT_GAP_MS}ms 안에 다시 오면 운영체제의 자동 반복으로 본다(누르고 있으면 첫 반복까지
 * 길어야 약 1초, 그 뒤로는 초당 약 30번 온다).
 * <ul>
 * <li>누르고 있어도 처음 한 번만 바꾼다. 창을 다시 연 뒤에 오는 자동 반복으로 화면이 깜빡이지 않는다.</li>
 * <li>뗌을 받지 못했어도 다음 눌림이 무시되지 않는다: 뗀 뒤 다시 누르기까지는 보통 그보다 길고, 짧았어도 그 다음 눌림부터는
 * 뗌을 받는다.</li>
 * </ul>
 */
public final class FullscreenKey {

	/** 같은 키의 눌림 사이가 이보다 짧으면 자동 반복으로 본다 (ms). Windows 의 반복 시작 지연은 최대 약 1초다. */
	public static final long REPEAT_GAP_MS = 1100;

	private int down = KeyNames.NONE;
	private long lastAt;

	/**
	 * 전체 화면 키를 눌렀다 (KEY_PRESSED, 키가 {@code keys().actionOf(code) == FULLSCREEN} 인지는 부르는 쪽이 본다).
	 *
	 * @param keyCode {@code getKeyCode()}
	 * @param nowMs   지금 시각 (ms, {@code System.currentTimeMillis()} 나 {@code getWhen()})
	 * @return 전체 화면을 바꿀 때면 true. 자동 반복이면 false
	 */
	public boolean press(int keyCode, long nowMs) {
		boolean repeat = keyCode == down && nowMs - lastAt < REPEAT_GAP_MS;
		down = keyCode;
		lastAt = nowMs;
		return !repeat;
	}

	/** 키를 뗐다 (KEY_RELEASED). */
	public void release(int keyCode) {
		if (keyCode == down) {
			down = KeyNames.NONE;
		}
	}

	/**
	 * 창을 떠났다: 사람이 다른 창으로 갔을 때의 windowDeactivated. 전체 화면을 바꾸느라 창을 다시 만드는 동안의 비활성화에는
	 * 부르지 않는다(누르고 있던 키의 자동 반복이 다시 바꾸게 된다).
	 */
	public void reset() {
		down = KeyNames.NONE;
	}
}
