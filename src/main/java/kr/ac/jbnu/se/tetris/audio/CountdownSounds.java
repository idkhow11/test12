package kr.ac.jbnu.se.tetris.audio;

/**
 * 카운트다운 숫자가 바뀔 때의 소리: 3·2·1(일시정지를 푼 뒤에는 2·1)은 삑, 0 이 되어 판이 움직이기 시작하면 GO. 화면
 * 스레드가 그릴 때마다 지금 보이는 숫자를 넘긴다. 멈춤이나 판 끝 때문에 숫자가 사라질 때는 소리를 내지 않는다(멈추면
 * {@code GameSession.countdown()} 이 0 이 되지만 GO 가 아니다). 판이 바뀌면 {@link #reset()}. 화면 스레드 하나만 쓴다.
 */
public final class CountdownSounds {

	private int last = -1;

	/** 새 판: 앞 판의 숫자를 잊는다. */
	public void reset() {
		last = -1;
	}

	/**
	 * @param shown   지금 보이는 카운트다운 숫자 ({@code GameSession.countdown()}: 시작 전 3·2·1, 다시 갈 때 2·1, 아니면 0)
	 * @param running 판이 진행 중인가 (멈추지도 끝나지도 않음)
	 * @return 낼 소리, 없으면 null
	 */
	public Cue update(int shown, boolean running) {
		int before = last;
		last = shown;
		if (!running || shown == before) {
			return null;
		}
		if (shown > 0) {
			return SoundCues.countdown(shown);
		}
		return before > 0 ? SoundCues.countdown(0) : null;
	}
}
