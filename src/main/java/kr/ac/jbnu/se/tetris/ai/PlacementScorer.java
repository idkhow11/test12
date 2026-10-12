package kr.ac.jbnu.se.tetris.ai;

import java.util.List;

/**
 * 후보 배치들의 애프터스테이트에 점수를 매긴다. 클수록 좋다.
 * <p>
 * 후보는 언제나 Java 의 PlacementFinder 가 만든, 실제로 갈 수 있는 배치다. 구현체는 규칙을 몰라도 된다.
 * 구현: {@link OnnxScorer}(학습 모델), {@link BaselineScorer}(비상용 기준 AI).
 */
public interface PlacementScorer extends AutoCloseable {

	/**
	 * @param candidates 후보 배치의 애프터스테이트
	 * @param opponent   결정 시점의 상대 상태 (MODEL_IO.md 2.2). 상대가 없으면 {@link Opponent#NONE}
	 * @return 후보와 같은 순서의 점수. 후보가 없으면 빈 배열
	 * @throws IllegalStateException 점수를 매길 수 없을 때(모델 실행 오류 등). 호출한 쪽은 다른 구현으로 대신한다
	 */
	double[] score(List<Afterstate> candidates, Opponent opponent);

	/** 로그와 화면에 쓸 이름. */
	String name();

	/** 모델 같은 자원을 놓아 준다. 기본은 아무것도 하지 않는다. */
	@Override
	default void close() {
	}
}
