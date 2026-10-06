package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ai.onnxruntime.OrtException;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 모델을 불러올 때의 확인(MODEL_IO.md 3~5장)과 빈 입력·닫기 처리. 모델 실행 결과 자체는 fixtures/model_io 가 확인한다.
 * ONNX Runtime 이 없는 플랫폼(예: Intel Mac)에서는 모두 건너뛴다.
 */
class OnnxScorerTest {

	private static final Path DIR = Path.of(System.getProperty("fixtures.dir", "fixtures"), "model_io");

	@BeforeAll
	static void requireOnnxRuntime() {
		assumeTrue(OnnxScorer.isAvailable(), "이 플랫폼에는 ONNX Runtime 이 없어 건너뜀");
	}

	@Test
	void rejectsModelWithOtherRulesVersion() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> OnnxScorer.load(DIR.resolve("linear_v2_wrong_rules.onnx")));
		assertTrue(e.getMessage().contains("tetris.rules"), e.getMessage());
	}

	@Test
	void rejectsModelWithOtherModelIoVersion() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> OnnxScorer.load(DIR.resolve("linear_v2_wrong_model_io.onnx")));
		assertTrue(e.getMessage().contains("tetris.model_io"), e.getMessage());
	}

	@Test
	void rejectsModelWhoseValueIsNotNByOne() {
		// PyTorch 에서 흔히 나오는 [N] 출력. 불러올 때 거부해야 점수 매기기 도중에 깨지지 않는다.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> OnnxScorer.load(DIR.resolve("linear_v2_flat_output.onnx")));
		assertTrue(e.getMessage().contains("value"), e.getMessage());
	}

	@Test
	void rejectsModelWithoutOpponentInputs() {
		// 꼬리표만 형식 2 이고 입력은 형식 1 처럼 두 개. 상대 상태를 모르는 모델을 쓰면 안 된다.
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> OnnxScorer.load(DIR.resolve("linear_v2_two_inputs.onnx")));
		assertTrue(e.getMessage().contains("opp_board"), e.getMessage());
	}

	@Test
	void closeTwiceIsHarmlessAndScoringAfterCloseFailsCleanly() throws OrtException {
		OnnxScorer s = OnnxScorer.load(DIR.resolve("linear_v2.onnx"));
		s.close();
		s.close();
		Afterstate a = new Afterstate(new Grid(), List.of(), null, -1, false, 0, 0, 0);
		assertThrows(IllegalStateException.class, () -> s.score(List.of(a), Opponent.NONE));
	}

	@Test
	void rejectsFileThatIsNotAModel() {
		assertThrows(OrtException.class, () -> OnnxScorer.load(DIR.resolve("linear_v2.json")));
	}

	@Test
	void emptyCandidatesGiveEmptyScores() throws OrtException {
		try (OnnxScorer s = OnnxScorer.load(DIR.resolve("linear_v2.onnx"))) {
			assertEquals(0, s.score(List.of(), Opponent.NONE).length);
			assertTrue(s.name().contains("linear_v2.onnx"), s.name());
		}
	}

	@Test
	void sameCandidateTwiceGivesSameScore() throws OrtException {
		Afterstate a = new Afterstate(new Grid(), List.of(), null, -1, false, 0, 0, 0);
		try (OnnxScorer s = OnnxScorer.load(DIR.resolve("linear_v2.onnx"), 1)) {
			double[] v = s.score(List.of(a, a), Opponent.NONE);
			assertEquals(v[0], v[1], 0.0);
			// 빈 보드, 빈 대기열, 모든 특징 0, 상대 없음이면 편향값만 남는다 (make_test_model.py 의 bias = 0.25).
			assertEquals(0.25, v[0], 1e-6);
		}
	}

	@Test
	void opponentChangesScore() throws OrtException {
		// 상대 입력이 실제로 모델에 들어가는지. make_test_model.py 의 상대 특징 가중치는 (i - 4) * 0.03 이라
		// 대기 가비지(0번)가 늘면 점수가 바뀐다.
		Afterstate a = new Afterstate(new Grid(), List.of(), null, -1, false, 0, 0, 0);
		Opponent threatened = new Opponent(new Grid(), 6, -1, false, PieceType.T);
		try (OnnxScorer s = OnnxScorer.load(DIR.resolve("linear_v2.onnx"))) {
			assertNotEquals(s.score(List.of(a), Opponent.NONE)[0], s.score(List.of(a), threatened)[0]);
		}
	}
}
