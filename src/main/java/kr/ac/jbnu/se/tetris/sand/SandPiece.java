package kr.ac.jbnu.se.tetris.sand;

import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 블록 하나: 모양과 색. 미리보기와 홀드에도 이 짝으로 들어간다(홀드해도 색은 그대로다).
 *
 * @param type  모양 (7-bag, 고전과 같은 수열)
 * @param color 색 (12개 색 가방)
 */
public record SandPiece(PieceType type, SandColor color) {
}
