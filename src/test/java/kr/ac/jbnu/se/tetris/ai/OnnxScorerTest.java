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

	/**
	 * 게임은 모델 하나에 연산 스레드를 여럿 쓴다({@link AiCatalog#GAME_THREADS}, PC 마다 1~4). 점수가 스레드 수와 상관없이
	 * 비트 단위로 같아야 같은 시드·입력의 판이 어느 PC 에서나, 학습 대전(스레드 1)과도 같다. 저장소의 실제 가치망으로 본다
	 * (없으면 건너뜀).
	 */
	@Test
	void scoresDoNotDependOnTheNumberOfThreads() throws OrtException {
		Path model = Path.of("models", "value_a2-g052.onnx");
		assumeTrue(java.nio.file.Files.isRegularFile(model), "models/ 에 가치망이 없어 건너뜀");
		// 기준 AI 로 한 판을 두며 블록마다 모든 후보의 애프터스테이트를 모은다 (2수 탐색의 큰 묶음과 비슷한 크기)
		kr.ac.jbnu.se.tetris.core.GameModel g = new kr.ac.jbnu.se.tetris.core.GameModel(42, 1,
				kr.ac.jbnu.se.tetris.core.GameModel.Scheduler.S_MIX);
		Brain baseline = new Brain(new BaselineScorer(), 0, null, Chooser.rng(42, 1));
		List<Afterstate> states = new java.util.ArrayList<>();
		for (int k = 0; k < 40 && !g.isOver(); k++) {
			var all = g.placements();
			AfterstateBuilder.Context ctx = AfterstateBuilder.contextOf(g);
			for (var p : all.values()) {
				Afterstate a = AfterstateBuilder.build(g.grid(), p, ctx);
				if (a != null) {
					states.add(a);
				}
			}
			g.apply(baseline.decide(g, all, Opponent.NONE).placement());
		}
		Opponent view = new Opponent(g.grid(), 3, 1, true, PieceType.T);
		try (OnnxScorer one = OnnxScorer.load(model, 1); OnnxScorer four = OnnxScorer.load(model, 4)) {
			double[] a = one.score(states, view);
			double[] b = four.score(states, view);
			assertTrue(states.size() > 1000, "묶음 크기 " + states.size());
			for (int i = 0; i < a.length; i++) {
				assertEquals(Double.doubleToLongBits(a[i]), Double.doubleToLongBits(b[i]), "후보 " + i);
			}
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
