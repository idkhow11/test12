package kr.ac.jbnu.se.tetris.sand;

import kr.ac.jbnu.se.tetris.core.PieceState;

/**
 * 고정 한 번: 블록이 그 자리에서 알갱이 {@link SandRules#PIECE_GRAINS}개로 부서졌다. 화면이 내려앉은 자리에 먼지 같은 효과를
 * 그릴 때 쓴다({@code piece().cells()} 가 부서진 칸).
 *
 * @param time     고정한 때 (판 시작부터 ms)
 * @param piece    고정한 자리의 블록
 * @param color    블록의 색
 * @param hardDrop 하드드롭으로 고정했는가 (아니면 락 딜레이)
 */
public record LockEvent(long time, PieceState piece, SandColor color, boolean hardDrop) {
}
