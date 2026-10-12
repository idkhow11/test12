package kr.ac.jbnu.se.tetris.sand.ai;

/**
 * 블록 하나의 자리를 고르는 것 ({@link SandBrain}). 조작기({@link SandAiController})는 이것만 보므로, 시험이 일부러 실패하는
 * 두뇌를 넣어 볼 수 있다. 같은 입력이면 언제나 같은 답을 내야 하고, 여러 스레드가 함께 불러도 되어야 한다.
 */
interface Planner {

	/** @param noise 이 블록의 난수 값 */
	SandPlan plan(Situation s, long noise);
}
