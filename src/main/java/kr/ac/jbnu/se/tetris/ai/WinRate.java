package kr.ac.jbnu.se.tetris.ai;

import java.io.IOException;
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
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.Rules;

/**
 * 승률 예측망 (docs/MODEL_IO.md 9장, 형식 {@code winprob-1}). 두 플레이어의 상태로 P1 이 이길 확률을 낸다.
 * <p>
 * 정확히는 P1 의 기대 점수 {@code P(P1 승) + 0.5·P(무승부)} 이고, P2 쪽은 {@code 1 − p} 다. 불러올 때 모델 꼬리표의
 * 형식·규칙 버전과 입력·출력의 이름·자료형·모양을 확인하고, 맞지 않으면 예외를 던진다. 게임은 그때 승률 막대를 숨긴다.
 * <p>
 * 입력은 Python 의 {@code tetris_ai.winprob} 과 비트 단위로 같아야 하므로 정수를 float 로 바꾸는 것 말고는 계산하지 않는다
 * (fixtures/winprob 로 확인). 객체 하나를 여러 스레드가 함께 써도 된다. {@link #close()} 는 모든 호출이 끝난 뒤에 부른다.
 * <p>
 * 모델 파일 옆에 그 모델의 보정 파일({@link WinCalibration#fileFor(Path)}, 예: {@code models/winprob_calibration.json})이
 * 있으면 불러올 때 함께 붙인다(TRAINING.md 10.12). 게임의 승률 막대는 보정한 값 {@link #p1Win(PlayerState, PlayerState, long)}
 * 을 쓴다. 보정 파일이 없거나 쓸 수 없어도 모델은 그대로 쓰고({@link #calibrationProblem()}), 시간을 받지 않는 메서드들은
 * 언제나 모델 출력 그대로다.
 */
public final class WinRate implements AutoCloseable {

	public static final String MODEL_IO_VERSION = "winprob-1";

	public static final int BOARD_SIZE = Grid.HEIGHT * Grid.WIDTH;
	public static final int FEATURES = 52;

	private static final int PIECES = PieceType.values().length;
	// p1_features, p2_features 의 위치 (MODEL_IO.md 9.4)
	static final int CURRENT = 0;
	static final int QUEUE_OFFSET = CURRENT + PIECES; // 7
	static final int HOLD_OFFSET = QUEUE_OFFSET + PlayerState.MAX_QUEUE * PIECES; // 42
	static final int COMBO = HOLD_OFFSET + PIECES; // 49
	static final int B2B = COMBO + 1;
	static final int PENDING = B2B + 1; // 51

	static {
		if (PENDING + 1 != FEATURES) {
			throw new AssertionError("p_features 배치가 MODEL_IO.md 9.4 와 다름");
		}
	}

	private static final String INPUT_P1_BOARD = "p1_board";
	private static final String INPUT_P1_FEATURES = "p1_features";
	private static final String INPUT_P2_BOARD = "p2_board";
	private static final String INPUT_P2_FEATURES = "p2_features";
	private static final String OUTPUT = "p1_win";

	/** 첫 번째 축은 상태 쌍의 수이며 크기가 정해져 있지 않아야 한다(-1). */
	private static final long[] BOARD_SHAPE = { -1, Grid.HEIGHT, Grid.WIDTH };
	private static final long[] FEATURES_SHAPE = { -1, FEATURES };
	private static final long[] OUTPUT_SHAPE = { -1, 1 };

	private final OrtEnvironment env;
	private final OrtSession session;
	private final Map<String, String> metadata;
	private final String name;
	private final WinCalibration calibration;
	private final String calibrationProblem;
	private volatile boolean closed;

	private WinRate(OrtEnvironment env, OrtSession session, Map<String, String> metadata, String name,
			WinCalibration calibration, String calibrationProblem) {
		this.env = env;
		this.session = session;
		this.metadata = metadata;
		this.name = name;
		this.calibration = calibration;
		this.calibrationProblem = calibrationProblem;
	}

	/** 이 플랫폼에서 ONNX Runtime 을 쓸 수 있는가 ({@link OnnxScorer#isAvailable()} 과 같다). */
	public static boolean isAvailable() {
		return OnnxScorer.isAvailable();
	}

	/**
	 * ONNX Runtime 이 정한 수의 연산 스레드로 불러온다. 모델 옆에 그 모델의 보정 파일이 있으면 붙인다(클래스 설명). 보정
	 * 파일이 다른 모델의 것이거나 읽을 수 없으면 붙이지 않고 그 이유를 {@link #calibrationProblem()} 에 남긴다(예외는 없다).
	 *
	 * @throws OrtException             파일을 읽을 수 없거나 ONNX 모델이 아닐 때
	 * @throws IllegalArgumentException 꼬리표가 없거나 버전이 맞지 않을 때, 입력·출력이 MODEL_IO.md 9장과 다를 때
	 * @throws IllegalStateException    이 플랫폼에서 ONNX Runtime 을 쓸 수 없을 때
	 */
	public static WinRate load(Path model) throws OrtException {
		return load(model, 0);
	}

	/**
	 * @param intraOpThreads 한 번의 실행에 쓸 연산 스레드 수. 0 이면 ONNX Runtime 기본값. 게임 화면처럼 가끔 한 쌍씩 부를 때는
	 *                       1 이면 충분하다
	 * @see #load(Path)
	 */
	public static WinRate load(Path model, int intraOpThreads) throws OrtException {
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
			require(meta, OnnxScorer.KEY_MODEL_IO, MODEL_IO_VERSION);
			require(meta, OnnxScorer.KEY_RULES, Rules.VERSION);
			if (session.getNumInputs() != 4 || session.getNumOutputs() < 1) {
				throw new IllegalArgumentException("입력은 p1_board, p1_features, p2_board, p2_features 네 개여야 함: 입력 "
						+ session.getInputNames() + ", 출력 " + session.getOutputNames());
			}
			requireFloatTensor(session.getInputInfo(), INPUT_P1_BOARD, BOARD_SHAPE);
			requireFloatTensor(session.getInputInfo(), INPUT_P1_FEATURES, FEATURES_SHAPE);
			requireFloatTensor(session.getInputInfo(), INPUT_P2_BOARD, BOARD_SHAPE);
			requireFloatTensor(session.getInputInfo(), INPUT_P2_FEATURES, FEATURES_SHAPE);
			requireFloatTensor(session.getOutputInfo(), OUTPUT, OUTPUT_SHAPE);
			String stage = meta.getOrDefault(OnnxScorer.KEY_STAGE, "?");
			WinCalibration calibration = null;
			String problem = null;
			try {
				calibration = WinCalibration.forModel(model);
			} catch (IOException | RuntimeException e) { // 보정층이 없어도 막대는 모델 출력으로 그린다
				problem = WinCalibration.fileFor(model).getFileName() + ": " + e.getMessage();
				System.err.println("승률 보정층을 쓰지 않음(모델 출력을 그대로 씀): " + problem);
			}
			return new WinRate(env, session, meta, "winprob:" + model.getFileName() + "(" + stage + ")", calibration,
					problem);
		} catch (RuntimeException | OrtException e) {
			closeQuietly(session);
			throw e;
		}
	}

	// OnnxScorer 와 같은 확인이다(MODEL_IO.md 5장). 그쪽 코드를 건드리지 않으려고 따로 둔다.

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

	// ------------------------------------------------------------------ 인코딩 (MODEL_IO.md 9.3, 9.4)

	/** 보드 텐서 한 개를 {@code dst[offset ..]} 에 쓴다: 행 우선, 0 행이 맨 위, 채워진 칸 1. */
	public static void encodeBoard(PlayerState s, float[] dst, int offset) {
		for (int r = 0; r < Grid.HEIGHT; r++) {
			for (int c = 0; c < Grid.WIDTH; c++) {
				dst[offset + r * Grid.WIDTH + c] = s.cell(r, c) == Grid.EMPTY ? 0f : 1f;
			}
		}
	}

	/** 특징 텐서 한 개(52개)를 {@code dst[offset ..]} 에 쓴다. */
	public static void encodeFeatures(PlayerState s, float[] dst, int offset) {
		for (int i = 0; i < FEATURES; i++) {
			dst[offset + i] = 0f;
		}
		dst[offset + CURRENT + s.current().ordinal()] = 1f;
		List<PieceType> queue = s.queue();
		for (int k = 0; k < queue.size(); k++) {
			dst[offset + QUEUE_OFFSET + k * PIECES + queue.get(k).ordinal()] = 1f;
		}
		if (s.hold() != null) {
			dst[offset + HOLD_OFFSET + s.hold().ordinal()] = 1f;
		}
		dst[offset + COMBO] = s.combo() + 1;
		dst[offset + B2B] = s.b2b() ? 1f : 0f;
		dst[offset + PENDING] = s.pending();
	}

	// ------------------------------------------------------------------ 실행

	/** 진행 중인 두 상태에서 P1 이 이길 확률 (0 이상 1 이하). */
	public double p1Win(PlayerState p1, PlayerState p2) {
		return p1Win(List.of(p1), List.of(p2))[0];
	}

	/**
	 * 두 게임의 지금 상태에서 P1 이 이길 확률. 어느 한쪽이라도 끝났으면 모델을 부르지 않고 결과를 돌려준다:
	 * P1 승 1, P2 승 0, 둘 다 끝남(무승부) 0.5. 상태를 읽는 {@link PlayerState#of(GameModel)} 가 게임의 가방을 진행시킬 수
	 * 있으므로 게임을 진행하는 스레드에서 부른다. 다른 스레드에서 계산하려면 게임 스레드에서 {@link PlayerState#of(GameModel)}
	 * 로 복사해 두고 {@link #p1Win(PlayerState, PlayerState)} 를 쓴다.
	 */
	public double p1Win(GameModel p1, GameModel p2) {
		if (p1.isOver() || p2.isOver()) {
			return p1.isOver() == p2.isOver() ? 0.5 : p1.isOver() ? 0.0 : 1.0;
		}
		return p1Win(PlayerState.of(p1), PlayerState.of(p2));
	}

	/**
	 * 승률 막대에 보이는 P1 승률: 모델 출력 {@link #p1Win(PlayerState, PlayerState)} 를 보정층으로 고친 값(TRAINING.md
	 * 10.12). 보정층은 두 상태에 복사해 둔 블록 수({@link PlayerState#pieces()})와 판 시간을 쓴다. 보정층이 없거나(파일이
	 * 없거나 이 모델의 것이 아님) 어느 한쪽의 블록 수를 모르면({@code pieces() < 0}) 모델 출력 그대로다.
	 *
	 * @param timeMs 판 시작부터 흐른 게임 시간 ms. 두 상태를 복사한 틱의 {@code TimedMatch.time()} 이다
	 */
	public double p1Win(PlayerState p1, PlayerState p2, long timeMs) {
		double p = p1Win(p1, p2);
		return calibration == null ? p : calibration.apply(p, p1.pieces(), p2.pieces(), timeMs);
	}

	/**
	 * 두 게임의 지금 상태에서 보정한 P1 승률. 어느 한쪽이라도 끝났으면 {@link #p1Win(GameModel, GameModel)} 처럼 결과를
	 * 돌려준다(보정하지 않음). 게임을 진행하는 스레드에서 부른다.
	 *
	 * @param timeMs 판 시작부터 흐른 게임 시간 ms ({@code TimedMatch.time()})
	 */
	public double p1Win(GameModel p1, GameModel p2, long timeMs) {
		if (p1.isOver() || p2.isOver()) {
			return p1Win(p1, p2);
		}
		return p1Win(PlayerState.of(p1), PlayerState.of(p2), timeMs);
	}

	/**
	 * 상태 쌍 N 개를 한 번에 계산한다. {@code p1.get(i)} 와 {@code p2.get(i)} 가 한 쌍이다.
	 *
	 * @throws IllegalStateException 닫힌 모델이거나 실행에 실패했을 때, 출력이 [N, 1] 의 확률이 아닐 때
	 */
	public double[] p1Win(List<PlayerState> p1, List<PlayerState> p2) {
		int n = p1.size();
		if (p2.size() != n) {
			throw new IllegalArgumentException("P1 상태 " + n + "개, P2 상태 " + p2.size() + "개");
		}
		if (n == 0) {
			return new double[0];
		}
		if (closed) {
			throw new IllegalStateException("이미 닫힌 모델");
		}
		float[] b1 = new float[n * BOARD_SIZE];
		float[] f1 = new float[n * FEATURES];
		float[] b2 = new float[n * BOARD_SIZE];
		float[] f2 = new float[n * FEATURES];
		for (int i = 0; i < n; i++) {
			encodeBoard(p1.get(i), b1, i * BOARD_SIZE);
			encodeFeatures(p1.get(i), f1, i * FEATURES);
			encodeBoard(p2.get(i), b2, i * BOARD_SIZE);
			encodeFeatures(p2.get(i), f2, i * FEATURES);
		}
		long[] boardShape = { n, Grid.HEIGHT, Grid.WIDTH };
		long[] featureShape = { n, FEATURES };
		try (OnnxTensor tb1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(b1), boardShape);
				OnnxTensor tf1 = OnnxTensor.createTensor(env, FloatBuffer.wrap(f1), featureShape);
				OnnxTensor tb2 = OnnxTensor.createTensor(env, FloatBuffer.wrap(b2), boardShape);
				OnnxTensor tf2 = OnnxTensor.createTensor(env, FloatBuffer.wrap(f2), featureShape);
				OrtSession.Result result = session.run(Map.of(INPUT_P1_BOARD, tb1, INPUT_P1_FEATURES, tf1,
						INPUT_P2_BOARD, tb2, INPUT_P2_FEATURES, tf2))) {
			Object raw = result.get(OUTPUT).orElseThrow(() -> new IllegalStateException("출력 p1_win 이 없음")).getValue();
			if (!(raw instanceof float[][]) || ((float[][]) raw).length != n) {
				throw new IllegalStateException("출력 p1_win 의 모양이 [" + n + ", 1] 이 아님");
			}
			float[][] value = (float[][]) raw;
			double[] out = new double[n];
			for (int i = 0; i < n; i++) {
				if (value[i].length != 1 || !(value[i][0] >= 0f && value[i][0] <= 1f)) {
					throw new IllegalStateException("출력 p1_win 이 [" + n + ", 1] 의 확률이 아님: " + Arrays.toString(value[i]));
				}
				out[i] = value[i][0];
			}
			return out;
		} catch (OrtException | RuntimeException e) {
			throw e instanceof IllegalStateException ? (IllegalStateException) e
					: new IllegalStateException("모델 실행 실패: " + e.getMessage(), e);
		}
	}

	/** 모델 꼬리표 전체. */
	public Map<String, String> metadata() {
		return metadata;
	}

	/** 붙인 보정층, 없으면 null. */
	public WinCalibration calibration() {
		return calibration;
	}

	/** 모델 옆에 보정 파일이 있었지만 쓰지 못한 이유, 아니면(붙였거나 파일이 없음) null. */
	public String calibrationProblem() {
		return calibrationProblem;
	}

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
