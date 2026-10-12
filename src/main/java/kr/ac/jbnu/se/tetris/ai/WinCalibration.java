package kr.ac.jbnu.se.tetris.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 승률 막대의 보정층 (docs/TRAINING.md 10.12, 형식 {@code winprob-calibration-1}). 승률 예측망({@link WinRate})의 출력
 * p 를 두 플레이어가 지금까지 고정한 블록 수와 판 시간으로 고친다. 두 플레이어의 속도가 비슷한 판에서 예측망의 확신이 지나친
 * 것(TRAINING.md 10.11)을 줄이고, 블록 수의 차이를 속도 차이의 신호로 더한다.
 * <p>
 * 계수는 {@code python/tools/fit_winprob_calibration.py} 가 검증 판으로만 맞춘 것이다. 식은 그 도구의 {@code calibrate()}
 * 와 같은 순서로 double 로 계산한다(fixtures/winprob_calibration 으로 확인). 종류는 둘이다.
 * <ul>
 * <li>{@code general}: {@code r = (min(n1, n2) + k) / (max(n1, n2) + k)}, {@code e = exp(−t / tau)},
 * {@code d = ln((n1 + k) / (n2 + k))} 일 때 {@code logit p' = exp(S(r, e)) · logit p + (b0 + b_e · e) · d}.
 * {@code S = s0 + s_r r + s_r2 r² + s_r3 r³ + e (s_e + s_er r + s_er2 r²)}
 * <li>{@code rbin_temperature}: 속도 비 r 의 구간마다 온도 하나, {@code logit p' = exp(log_scale[구간]) · logit p}
 * </ul>
 * 두 종류 모두 자리 대칭이다. P1 과 P2 를 바꾸면({@code p → 1 − p}, {@code n1 ↔ n2}) 결과가 {@code 1 − p'} 이다.
 * <p>
 * 계수는 한 모델 전용이다. 모델 파일 옆의 보정 파일({@link #fileFor(Path)})은 그 파일의 {@code model_sha256} 이 모델
 * 파일의 SHA-256 과 같을 때만 쓴다({@link #forModel(Path)}). 불변이라 여러 스레드가 함께 써도 된다.
 */
public final class WinCalibration {

	public static final String FORMAT = "winprob-calibration-1";
	/** 모델 파일 옆 보정 파일 이름의 꼬리. {@code models/winprob.onnx} 의 보정 파일은 {@code models/winprob_calibration.json} 이다. */
	public static final String SUFFIX = "_calibration.json";

	static final String GENERAL = "general";
	static final String RBIN_TEMPERATURE = "rbin_temperature";

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private final String kind;
	private final String candidate;
	private final double eps;
	private final double k;
	// general
	private final double tauMs;
	private final double s0;
	private final double sR;
	private final double sR2;
	private final double sR3;
	private final double sE;
	private final double sEr;
	private final double sEr2;
	private final double b0;
	private final double bE;
	// rbin_temperature: 구간의 아래 끝(오름차순)과 구간마다 log 온도
	private final double[] edges;
	private final double[] logScale;
	private final String model;
	private final String modelSha256;

	private WinCalibration(String kind, String candidate, double eps, double k, double tauMs, double[] c, double[] edges,
			double[] logScale, String model, String modelSha256) {
		this.kind = kind;
		this.candidate = candidate;
		this.eps = eps;
		this.k = k;
		this.tauMs = tauMs;
		this.s0 = c[0];
		this.sR = c[1];
		this.sR2 = c[2];
		this.sR3 = c[3];
		this.sE = c[4];
		this.sEr = c[5];
		this.sEr2 = c[6];
		this.b0 = c[7];
		this.bE = c[8];
		this.edges = edges;
		this.logScale = logScale;
		this.model = model;
		this.modelSha256 = modelSha256;
	}

	// ------------------------------------------------------------------ 읽기

	/** 일반 식의 계수 이름 (fit_winprob_calibration.py 의 COEFFS 와 같은 순서). */
	private static final String[] COEFFS = { "s0", "s_r", "s_r2", "s_r3", "s_e", "s_er", "s_er2", "b0", "b_e" };

	/**
	 * 보정 파일 하나를 읽는다. 어느 모델의 것인지는 확인하지 않는다({@link #forModel(Path)} 가 확인한다).
	 *
	 * @throws IOException              파일을 읽을 수 없거나 JSON 이 아닐 때
	 * @throws IllegalArgumentException 형식이 맞지 않을 때 ({@link #parse(JsonNode)})
	 */
	public static WinCalibration load(Path file) throws IOException {
		return parse(JSON.readTree(file.toFile()));
	}

	/**
	 * calibration.json 의 객체 하나. 쓰는 항목은 {@code format}, {@code kind}, {@code eps}, {@code k}, 일반 식이면
	 * {@code tau_ms} 와 {@code coefficients}(9개 모두), 구간 온도면 {@code edges} 와 {@code log_scale} 이고, 있으면
	 * {@code candidate}, {@code model}, {@code model_sha256} 이다. 나머지(맞춘 자료, 성적, 설명)는 읽지 않는다.
	 *
	 * @throws IllegalArgumentException 형식 버전이 다르거나 값이 없거나 유한한 수가 아닐 때, 일반 식인데 k 가 0 이하일 때
	 */
	public static WinCalibration parse(JsonNode spec) {
		if (spec == null || !spec.isObject()) {
			throw new IllegalArgumentException("보정층은 JSON 객체여야 함");
		}
		String format = spec.path("format").asText("");
		if (!FORMAT.equals(format)) {
			throw new IllegalArgumentException("format 이 " + FORMAT + " 이 아님: " + spec.path("format"));
		}
		String kind = spec.path("kind").asText("");
		double eps = number(spec, "eps");
		if (!(eps > 0 && eps < 0.5)) {
			throw new IllegalArgumentException("eps 는 0 과 0.5 사이여야 함: " + eps);
		}
		double k = number(spec, "k");
		double tauMs = Double.NaN;
		double[] c = new double[COEFFS.length];
		double[] edges = null;
		double[] logScale = null;
		switch (kind) {
		case GENERAL: {
			if (!(k > 0)) { // k = 0 이면 n1 = n2 = 0 에서 r, d 가 0 ÷ 0 이다 (Python 도 거부한다)
				throw new IllegalArgumentException("일반 식은 k > 0 이어야 함: " + k);
			}
			tauMs = number(spec, "tau_ms");
			if (!(tauMs > 0)) {
				throw new IllegalArgumentException("tau_ms 는 0 보다 커야 함: " + tauMs);
			}
			JsonNode coefficients = spec.path("coefficients");
			for (int i = 0; i < COEFFS.length; i++) {
				c[i] = number(coefficients, COEFFS[i]);
			}
			break;
		}
		case RBIN_TEMPERATURE: {
			if (!(k >= 0)) {
				throw new IllegalArgumentException("k 는 0 이상이어야 함: " + k);
			}
			edges = numbers(spec, "edges");
			logScale = numbers(spec, "log_scale");
			for (int i = 1; i < edges.length; i++) {
				if (!(edges[i - 1] <= edges[i])) {
					throw new IllegalArgumentException("edges 는 오름차순이어야 함: " + spec.path("edges"));
				}
			}
			break;
		}
		default:
			throw new IllegalArgumentException("모르는 kind: " + spec.path("kind"));
		}
		String sha = optionalText(spec, "model_sha256");
		if (sha != null) {
			sha = sha.toLowerCase(Locale.ROOT);
			if (!SHA256.matcher(sha).matches()) {
				throw new IllegalArgumentException("model_sha256 은 16진수 64자여야 함: " + sha);
			}
		}
		String candidate = optionalText(spec, "candidate");
		return new WinCalibration(kind, candidate == null ? kind : candidate, eps, k, tauMs, c, edges, logScale,
				optionalText(spec, "model"), sha);
	}

	private static double number(JsonNode parent, String key) {
		JsonNode v = parent.get(key);
		if (v == null || !v.isNumber() || !Double.isFinite(v.doubleValue())) {
			throw new IllegalArgumentException(key + " 는 유한한 수여야 함: " + v);
		}
		return v.doubleValue();
	}

	private static double[] numbers(JsonNode parent, String key) {
		JsonNode v = parent.get(key);
		if (v == null || !v.isArray() || v.size() == 0) {
			throw new IllegalArgumentException(key + " 는 비어 있지 않은 수의 배열이어야 함: " + v);
		}
		double[] out = new double[v.size()];
		for (int i = 0; i < out.length; i++) {
			if (!v.get(i).isNumber() || !Double.isFinite(v.get(i).doubleValue())) {
				throw new IllegalArgumentException(key + "[" + i + "] 는 유한한 수여야 함: " + v.get(i));
			}
			out[i] = v.get(i).doubleValue();
		}
		return out;
	}

	private static String optionalText(JsonNode parent, String key) {
		JsonNode v = parent.get(key);
		if (v == null || v.isNull()) {
			return null;
		}
		if (!v.isTextual()) {
			throw new IllegalArgumentException(key + " 는 문자열이어야 함: " + v);
		}
		return v.asText();
	}

	// ------------------------------------------------------------------ 모델과 짝 짓기

	/** 모델 파일의 보정 파일 경로: 같은 폴더의 {@code <확장자를 뺀 이름>_calibration.json}. */
	public static Path fileFor(Path model) {
		String name = model.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return model.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + SUFFIX);
	}

	/**
	 * 모델 파일 옆의 보정 파일을 읽어 그 모델의 것이면 돌려준다. 보정 파일이 없으면 null 이다(보정하지 않음).
	 *
	 * @throws IOException              보정 파일이나 모델 파일을 읽을 수 없을 때, 보정 파일이 JSON 이 아닐 때
	 * @throws IllegalArgumentException 형식이 맞지 않을 때, {@code model_sha256} 이 없거나 모델 파일의 SHA-256 과 다를 때
	 */
	public static WinCalibration forModel(Path model) throws IOException {
		Path file = fileFor(model);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		WinCalibration c = load(file);
		if (c.modelSha256 == null) {
			throw new IllegalArgumentException("model_sha256 이 없어 어느 모델의 보정층인지 알 수 없음");
		}
		String actual = sha256(model);
		if (!actual.equals(c.modelSha256)) {
			throw new IllegalArgumentException("다른 모델(" + c.model + ", sha256 " + c.modelSha256.substring(0, 12)
					+ "…)의 보정층이다. " + model.getFileName() + " 의 sha256 은 " + actual.substring(0, 12) + "…");
		}
		return c;
	}

	/** 파일의 SHA-256 (16진수 소문자 64자). Python 의 {@code hashlib.sha256(...).hexdigest()} 와 같다. */
	public static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 을 쓸 수 없음", e); // 모든 Java 플랫폼에 있어야 한다
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				digest.update(buf, 0, n);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	// ------------------------------------------------------------------ 계산

	/**
	 * 보정한 P1 승률. fit_winprob_calibration.py 의 {@code calibrate()} 와 같은 식을 같은 순서로 계산한다.
	 * <p>
	 * p 는 logit 전에 {@code [eps, 1 − eps]} 로 자른다(모델의 float32 출력이 정확히 0 이나 1 일 수 있다). 판 시작(블록 수 0,
	 * 시간 0, 두 상태가 같아 p = 0.5)은 정확히 0.5 다. 결과는 자르지 않는다. 값을 고칠 수 없으면 p 를 그대로 돌려준다: p 가
	 * NaN 일 때, 블록 수나 시간이 음수(모름)일 때, 계산이 넘쳐 NaN 이 될 때(Python 은 이때 NaN 을 낸다).
	 *
	 * @param p   승률 예측망의 출력 {@link WinRate#p1Win(PlayerState, PlayerState)} (float32 를 그대로 double 로)
	 * @param n1  P1 이 지금까지 패배 없이 고정한 블록 수 ({@code GameModel.pieces()})
	 * @param n2  P2 의 블록 수
	 * @param tMs 판 시작부터 흐른 게임 시간 ms ({@code TimedMatch.time()})
	 */
	public double apply(double p, int n1, int n2, long tMs) {
		if (Double.isNaN(p) || n1 < 0 || n2 < 0 || tMs < 0) {
			return p;
		}
		double pc = Math.min(Math.max(p, eps), 1.0 - eps);
		double z = Math.log(pc / (1.0 - pc));
		double a = n1;
		double b = n2;
		double zc;
		if (edges != null) { // rbin_temperature
			double lo = Math.min(a, b) + k;
			double hi = Math.max(a, b) + k;
			double r = hi > 0 ? lo / hi : 1.0;
			zc = Math.exp(logScale[bin(r)]) * z;
		} else { // general
			double r = (Math.min(a, b) + k) / (Math.max(a, b) + k);
			double e = Math.exp(-(double) tMs / tauMs);
			double d = Math.log((a + k) / (b + k));
			double s = s0 + sR * r + sR2 * r * r + sR3 * r * r * r + e * (sE + sEr * r + sEr2 * r * r);
			zc = Math.exp(s) * z + (b0 + bE * e) * d;
		}
		double out = 1.0 / (1.0 + Math.exp(-zc));
		return Double.isNaN(out) ? p : out;
	}

	/** numpy 의 {@code searchsorted(edges, r, side="right") − 1} 을 {@code [0, 구간 수 − 1]} 로 자른 것. */
	private int bin(double r) {
		int b = -1;
		for (double edge : edges) {
			if (edge <= r) {
				b++;
			}
		}
		return Math.max(0, Math.min(b, logScale.length - 1));
	}

	// ------------------------------------------------------------------ 정보

	/** {@code general} 또는 {@code rbin_temperature}. */
	public String kind() {
		return kind;
	}

	/** 맞춘 도구가 고른 후보 이름(예: speed5), 없으면 kind. */
	public String candidate() {
		return candidate;
	}

	/** 계수를 맞춘 모델의 이름(예: wp02), 없으면 null. */
	public String model() {
		return model;
	}

	/** 계수를 맞춘 모델 파일의 SHA-256, 없으면 null. */
	public String modelSha256() {
		return modelSha256;
	}

	@Override
	public String toString() {
		return "보정층 " + candidate + " (" + kind + ", 모델 " + model + ")";
	}
}
