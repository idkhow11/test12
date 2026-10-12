package kr.ac.jbnu.se.tetris.sand;

import kr.ac.jbnu.se.tetris.core.SevenBag;

/**
 * 블록 대기열: 모양은 7-bag(고전과 같은 스트림 1), 색은 12개 색 가방(스트림 5)에서 따로 뽑아 순번대로 짝짓는다. 그래서
 * 미리보기에 색까지 미리 보이고, 같은 시드면 언제나 같은 (모양, 색) 수열이다.
 */
final class PieceQueue {

	private final SevenBag shapes;
	private final ColorBag colors;

	PieceQueue(long seed) {
		this.shapes = new SevenBag(seed);
		this.colors = new ColorBag(seed);
	}

	SandPiece next() {
		return new SandPiece(shapes.next(), colors.next());
	}

	/** 앞으로 나올 블록 i 번째(0 부터). 소비하지 않는다. */
	SandPiece peek(int i) {
		return new SandPiece(shapes.peek(i), colors.peek(i));
	}
}
