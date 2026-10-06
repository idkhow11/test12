package kr.ac.jbnu.se.tetris.ai;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Rules;

/**
 * Python 에서 학습해 ONNX 로 내보낸 애프터스테이트 가치 모델로 점수를 매긴다 (docs/MODEL_IO.md).
 * <p>
 * 불러올 때 모델 꼬리표(MODEL_IO.md 5장)의 형식·규칙 버전과 입력·출력의 이름·자료형·모양(3, 4장)을 확인하고,
 * 맞지 않으면 예외를 던진다. 호출한 쪽은 그 경우 휴리스틱 AI 로 대신한다.
 * <p>
 * ONNX Runtime 1.30 의 jar 에는 Windows x64, Linux x64·arm64, macOS Apple Silicon 용 실행 파일만 들어 있다.
 * 그 밖의 플랫폼(예: Intel Mac)에서는 {@link #isAvailable()} 이 false 이고 {@link #load} 는 IllegalStateException 을 던진다.
 */
public final class OnnxScorer implements PlacementScorer {

	public static final String KEY_MODEL_IO = "tetris.model_io";
	public static final String KEY_RULES = "tetris.rules";
	public static final String KEY_PERSONA = "tetris.persona";
	public static final String KEY_STAGE = "tetris.stage";

	/** 꼬리표에 페르소나가 없을 때 쓰는 값 (MODEL_IO.md 5장). */
	public static final String DEFAULT_PERSONA = "neutral";

	private static final String INPUT_BOARD = "board";
	private static final String INPUT_FEATURES = "features";
	private static final String INPUT_OPP_BOARD = "opp_board";
	private static final String INPUT_OPP_FEATURES = "opp_features";
	private static final String OUTPUT_VALUE = "value";

	/** 첫 번째 축은 후보 수이며 크기가 정해져 있지 않아야 한다(-1). */
	private static final long[] BOARD_SHAPE = { -1, Grid.HEIGHT, Grid.WIDTH };
	private static final long[] FEATURES_SHAPE = { -1, FeatureEncoder.FEATURES };
	private static final long[] OPP_FEATURES_SHAPE = { -1, FeatureEncoder.OPP_FEATURES };
	private static final long[] VALUE_SHAPE = { -1, 1 };

	private final OrtEnvironment env;
	private final OrtSession session;
	private final Map<String, String> metadata;
	private final String name;
	private boolean closed;

	private OnnxScorer(OrtEnvironment env, OrtSession session, Map<String, String> metadata, String name) {
		this.env = env;
		this.session = session;
		this.metadata = metadata;
		this.name = name;
	}

	/** 이 플랫폼에서 ONNX Runtime 네이티브 라이브러리를 불러올 수 있는가. */
	public static boolean isAvailable() {
		try {
			OrtEnvironment.getEnvironment();
			return true;
		} catch (LinkageError | RuntimeException e) {
			return false;
		}
	}

	/**
	 * ONNX Runtime 이 정한 수의 연산 스레드로 불러온다. 게임처럼 한 번에 한 판만 둘 때 쓴다.
	 *
	 * @throws OrtException             파일을 읽을 수 없거나 ONNX 모델이 아닐 때
	 * @throws IllegalArgumentException 꼬리표가 없거나 버전이 맞지 않을 때, 입력·출력이 MODEL_IO.md 와 다를 때
	 * @throws IllegalStateException    이 플랫폼에서 ONNX Runtime 을 쓸 수 없을 때
	 */
	public static OnnxScorer load(Path model) throws OrtException {
		return load(model, 0);
	}

	/**
	 * @param intraOpThreads 한 번의 실행에 쓸 연산 스레드 수. 0 이면 ONNX Runtime 기본값. 여러 판을 스레드마다 나눠
	 *                       돌릴 때는 1 로 둬야 코어를 서로 빼앗지 않는다(TRAINING.md 6장). 같은 객체를 여러 스레드가
	 *                       함께 써도 된다
	 * @see #load(Path)
	 */
	public static OnnxScorer load(Path model, int intraOpThreads) throws OrtException {
		OrtEnvironment env;
		try {
			env = OrtEnvironment.getEnvironment();
		} catch (LinkageError e) {
			throw new IllegalStateException("이 플랫폼에서 ONNX Runtime 을 쓸 수 없음: " + e, e);
		}
		OrtSession session;
		try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
			if (intraOpThreads > 0) {
				options.setIntraOpNumThreads(intraOpThreads);
			}
			session = env.createSession(model.toString(), options);
		}
		try {
			Map<String, String> meta = Collections.unmodifiableMap(new HashMap<>(session.getMetadata().getCustomMetadata()));
			require(meta, KEY_MODEL_IO, FeatureEncoder.MODEL_IO_VERSION);
			require(meta, KEY_RULES, Rules.VERSION);
			if (session.getNumInputs() != 4 || session.getNumOutputs() < 1) {
				throw new IllegalArgumentException("입력은 board, features, opp_board, opp_features 네 개여야 함: 입력 "
						+ session.getInputNames() + ", 출력 " + session.getOutputNames());
			}
			requireFloatTensor(session.getInputInfo(), INPUT_BOARD, BOARD_SHAPE);
			requireFloatTensor(session.getInputInfo(), INPUT_FEATURES, FEATURES_SHAPE);
			requireFloatTensor(session.getInputInfo(), INPUT_OPP_BOARD, BOARD_SHAPE);
			requireFloatTensor(session.getInputInfo(), INPUT_OPP_FEATURES, OPP_FEATURES_SHAPE);
			requireFloatTensor(session.getOutputInfo(), OUTPUT_VALUE, VALUE_SHAPE);
			String persona = meta.getOrDefault(KEY_PERSONA, DEFAULT_PERSONA);
			String stage = meta.getOrDefault(KEY_STAGE, "?");
			return new OnnxScorer(env, session, meta, "onnx:" + model.getFileName() + "(" + persona + ", " + stage + ")");
		} catch (RuntimeException | OrtException e) {
			closeQuietly(session);
			throw e;
		}
	}

	private static void require(Map<String, String> meta, String key, String expected) {
		String actual = meta.get(key);
		if (!expected.equals(actual)) {
			throw new IllegalArgumentException("모델 꼬리표 " + key + " 가 " + actual + " (필요: " + expected + ")");
		}
	}

	/** float32 텐서이고 모양이 expected 와 같은지. expected 의 -1 은 크기가 정해지지 않은 축이어야 한다. */
	private static void requireFloatTensor(Map<String, NodeInfo> infos, String name, long[] expected) {
		NodeInfo node = infos.get(name);
		if (node == null || !(node.getInfo() instanceof TensorInfo)) {
			throw new IllegalArgumentException(name + " 텐서가 없음: " + infos.keySet());
		}
		TensorInfo info = (TensorInfo) node.getInfo();
		long[] shape = info.getShape();
		if (info.type != OnnxJavaType.FLOAT || !Arrays.equals(shape, expected)) {
			throw new IllegalArgumentException(name + " 는 float32 " + Arrays.toString(expected) + " 여야 함: "
					+ info.type + " " + Arrays.toString(shape));
		}
	}

	private static void closeQuietly(OrtSession session) {
		try {
			session.close();
		} catch (OrtException | RuntimeException e) {
			// 닫는 중의 오류는 게임 진행에 영향이 없으므로 무시한다.
		}
	}

	@Override
	public double[] score(List<Afterstate> candidates, Opponent opponent) {
		int n = candidates.size();
		if (n == 0) {
			return new double[0];
		}
		if (closed) {
			throw new IllegalStateException("이미 닫힌 모델");
		}
		long[] boardShape = { n, Grid.HEIGHT, Grid.WIDTH };
		long[] featureShape = { n, FeatureEncoder.FEATURES };
		long[] oppFeatureShape = { n, FeatureEncoder.OPP_FEATURES };
		try (OnnxTensor board = OnnxTensor.createTensor(env, FloatBuffer.wrap(FeatureEncoder.boards(candidates)), boardShape);
				OnnxTensor features = OnnxTensor.createTensor(env, FloatBuffer.wrap(FeatureEncoder.features(candidates)),
						featureShape);
				OnnxTensor oppBoard = OnnxTensor.createTensor(env,
						FloatBuffer.wrap(FeatureEncoder.opponentBoards(opponent, n)), boardShape);
				OnnxTensor oppFeatures = OnnxTensor.createTensor(env,
						FloatBuffer.wrap(FeatureEncoder.opponentFeatures(opponent, n)), oppFeatureShape);
				OrtSession.Result result = session.run(Map.of(INPUT_BOARD, board, INPUT_FEATURES, features,
						INPUT_OPP_BOARD, oppBoard, INPUT_OPP_FEATURES, oppFeatures))) {
			Object raw = result.get(OUTPUT_VALUE).orElseThrow(() -> new IllegalStateException("출력 value 가 없음")).getValue();
			if (!(raw instanceof float[][]) || ((float[][]) raw).length != n) {
				throw new IllegalStateException("출력 value 의 모양이 [" + n + ", 1] 이 아님");
			}
			float[][] value = (float[][]) raw;
			double[] out = new double[n];
			for (int i = 0; i < n; i++) {
				if (value[i].length != 1) {
					throw new IllegalStateException("출력 value 의 모양이 [" + n + ", 1] 이 아님");
				}
				out[i] = value[i][0];
			}
			return out;
		} catch (OrtException | RuntimeException e) {
			// PlacementScorer 약속대로 모든 실행 오류를 IllegalStateException 으로 알려, 호출한 쪽이 다른 구현으로 대신하게 한다.
			throw e instanceof IllegalStateException ? (IllegalStateException) e
					: new IllegalStateException("모델 실행 실패: " + e.getMessage(), e);
		}
	}

	/** 모델 꼬리표 전체. */
	public Map<String, String> metadata() {
		return metadata;
	}

	@Override
	public String name() {
		return name;
	}

	/** 여러 번 불러도 된다. */
	@Override
	public void close() {
		if (!closed) {
			closed = true;
			closeQuietly(session);
		}
	}
}
