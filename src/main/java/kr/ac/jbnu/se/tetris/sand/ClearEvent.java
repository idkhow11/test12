package kr.ac.jbnu.se.tetris.sand;

/**
 * 지우기 한 번: 한 번의 검사에서 찾은, 좌우 벽을 잇는 같은 색 덩어리들. 덩어리는 {@link #time()} 부터
 * {@link SandRules#FLASH_MS} 동안 반짝인 뒤 사라진다.
 *
 * @param time       찾은 때 (판 시작부터 ms)
 * @param grains     지운 알갱이 수
 * @param multiplier 이 지우기에 곱한 콤보 배율 (1~10)
 * @param points     얻은 점수 = 알갱이 수 × 배율
 * @param colors     지운 색 비트 ({@code 1 << SandColor.code()})
 * @param topRow     지운 알갱이 중 가장 위 알갱이 행 (화면의 "+점수" 자리)
 * @param bottomRow  지운 알갱이 중 가장 아래 알갱이 행
 */
public record ClearEvent(long time, int grains, int multiplier, long points, int colors, int topRow, int bottomRow) {

	/** 이 지우기에 색 c 가 들어 있는가. */
	public boolean has(SandColor c) {
		return (colors & (1 << c.code())) != 0;
	}
}
