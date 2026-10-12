package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.List;

import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.sand.SandPiece;

/**
 * 블록 하나에 대해 고른 결과 ({@link SandBrain#plan}). 후보를 점수 순으로 늘어놓고, 그중 고른 것을 가리킨다. 조작기는 고른
 * 목표에 갈 수 없게 되면 이 순서대로 지금 갈 수 있는 다음 후보로 바꾼다.
 *
 * @param ranked     후보, 좋은 것부터 (엔진으로 다시 잰 후보나 2수 탐색한 후보가 먼저, 그다음 나머지가 점수 순)
 * @param chosen     고른 후보의 {@code ranked} 안 위치 (온도가 0 이면 0). 후보가 없으면 -1
 * @param candidates 점수를 매긴 후보 수
 * @param nanos      고르는 데 걸린 시간 (ns, 측정용. 결과에는 영향이 없다)
 */
public record SandPlan(List<Target> ranked, int chosen, int candidates, long nanos) {

	/**
	 * 목표 하나: 홀드할지, 놓을 블록, 그 블록의 회전과 박스 열. 거기서 하드드롭한다.
	 *
	 * @param hold     홀드하고 꺼낸 블록을 놓는가
	 * @param piece    놓을 블록 (모양과 색)
	 * @param rotation 회전 상태 0~3
	 * @param x        회전 박스 왼쪽 열
	 * @param landing  고를 때 예상한 놓일 자리 (모래가 멈춘 뒤의 칸 판에서 하드드롭한 자리)
	 * @param path     목표까지의 조작 수 (홀드 포함, 하드드롭 빼고)
	 * @param score    점수 (클수록 좋다)
	 * @param cleared  예상한 지운 알갱이 수
	 * @param loses    놓으면 지는가 (정착한 모래가 보이는 판 위로)
	 */
	public record Target(boolean hold, SandPiece piece, int rotation, int x, PieceState landing, int path, double score,
			int cleared, boolean loses) {
	}

	/** 고른 목표, 후보가 없으면 null (그 자리에 둔다). */
	public Target target() {
		return chosen < 0 ? null : ranked.get(chosen);
	}
}
