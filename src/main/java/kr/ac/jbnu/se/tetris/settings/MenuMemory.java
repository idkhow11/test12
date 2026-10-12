package kr.ac.jbnu.se.tetris.settings;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 메뉴에서 마지막으로 고른 것 (모드, 난이도, 페르소나, 혼자 연습의 속도와 방해 줄 …). 메뉴를 다시 열거나 게임을 다시 켜도
 * 고른 것이 처음으로 돌아가지 않게 한다. 값이 바뀌지 않는 이름 → 문자열 지도이고, 적지 않은 이름은 "고른 적 없음"이라 메뉴가
 * 자기 기본값을 쓴다. 그래서 처음 켠 게임의 메뉴는 이 기능이 생기기 전과 같다(사람 대 AI, 2단계 중급 …).
 * <p>
 * 이름은 아래 상수를 쓴다. 메뉴에 고를 것이 늘면 상수를 더하면 된다(저장 형식은 그대로). 이름은 영어 소문자·숫자와
 * {@code . _ -}, 값은 {@value #MAX_VALUE_LENGTH}자 이하의 한 줄이다. 난이도는 순서가 아니라 {@code AiCatalog.Difficulty#id},
 * 페르소나는 {@code AiCatalog.Persona#id} 로 적는다(모델 묶음이 바뀌어도 엉뚱한 것을 고르지 않게). 찾을 수 없는 값이면
 * 메뉴는 자기 기본값을 쓴다({@link #indexOf} 가 도와준다).
 *
 * @param values 이름 → 값 (이름 순서, 바꿀 수 없는 지도)
 */
public record MenuMemory(SortedMap<String, String> values) {

	/** 모드: {@link #MODE_VERSUS}, {@link #MODE_SOLO}, {@link #MODE_SAND}. */
	public static final String MODE = "mode";
	/** 사람 대 AI 의 난이도 id (정수). */
	public static final String VERSUS_LEVEL = "versus.level";
	/** 사람 대 AI 의 페르소나 id. */
	public static final String VERSUS_PERSONA = "versus.persona";
	/** 혼자 연습의 블록 속도 = 중력 상한 (초당 칸 수, 예: 3). */
	public static final String SOLO_SPEED = "solo.speed";
	/** 혼자 연습의 방해 줄 = {@code GameModel.Scheduler#json()} ({@code none}, {@code S-3}, {@code S-mix}). */
	public static final String SOLO_GARBAGE = "solo.garbage";
	/** 모래 모드의 시작 단계 (1~10, {@code SandGame} 의 {@code startLevel}). */
	public static final String SAND_LEVEL = "sand.level";

	/** 사람 대 AI ({@code GameSession.Mode.VERSUS}). */
	public static final String MODE_VERSUS = "versus";
	/** 혼자 연습 ({@code GameSession.Mode.SOLO}). */
	public static final String MODE_SOLO = "solo";
	/** 모래 모드. */
	public static final String MODE_SAND = "sand";

	/** 이름의 최대 길이. */
	public static final int MAX_KEY_LENGTH = 40;
	/** 값의 최대 길이. */
	public static final int MAX_VALUE_LENGTH = 64;
	/** 적을 수 있는 이름의 수. */
	public static final int MAX_ENTRIES = 64;

	private static final Pattern KEY = Pattern.compile("[a-z0-9]+([._-][a-z0-9]+)*");
	/** 없어진 모드(AI 관전, 10-07 에 뺐다)의 이름과 모드 값. 옛 저장 파일에 남아 있으면 읽을 때 버린다({@link #retired}). */
	private static final String RETIRED_PREFIX = "spectate.";
	private static final String RETIRED_MODE = "spectate";

	/** 아무것도 고른 적 없음. */
	public static final MenuMemory EMPTY = new MenuMemory(new TreeMap<>());

	/**
	 * @throws IllegalArgumentException 이름이나 값이 규칙({@link #validKey}, {@link #validValue})에 맞지 않거나 너무 많으면
	 */
	public MenuMemory {
		Objects.requireNonNull(values, "values");
		if (values.size() > MAX_ENTRIES) {
			throw new IllegalArgumentException("메뉴 기억은 " + MAX_ENTRIES + "개까지: " + values.size());
		}
		TreeMap<String, String> copy = new TreeMap<>();
		for (Map.Entry<String, String> e : values.entrySet()) {
			if (!validKey(e.getKey()) || !validValue(e.getValue())) {
				throw new IllegalArgumentException("메뉴 기억에 쓸 수 없는 항목: " + e.getKey());
			}
			copy.put(e.getKey(), e.getValue());
		}
		values = Collections.unmodifiableSortedMap(copy);
	}

	/** 이름으로 쓸 수 있는가 (영어 소문자·숫자 덩어리를 {@code . _ -} 로 이은 것, {@value #MAX_KEY_LENGTH}자 이하). */
	public static boolean validKey(String key) {
		return key != null && key.length() <= MAX_KEY_LENGTH && KEY.matcher(key).matches();
	}

	/** 값으로 쓸 수 있는가 (비지 않은 한 줄, 제어 문자 없음, 앞뒤 공백 없음, {@value #MAX_VALUE_LENGTH}자 이하). */
	public static boolean validValue(String value) {
		if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH || !value.equals(value.strip())) {
			return false;
		}
		return value.chars().noneMatch(Character::isISOControl);
	}

	/**
	 * 없어진 모드의 항목인가: AI 관전(10-07 에 뺐다)의 {@code spectate.*} 이름과 {@code mode=spectate}. 옛 저장 파일을 읽을 때
	 * 이런 항목은 버린다(파일은 그대로 읽고, 다음에 저장할 때 빠진다).
	 */
	public static boolean retired(String key, String value) {
		return key.startsWith(RETIRED_PREFIX) || MODE.equals(key) && RETIRED_MODE.equals(value);
	}

	/** 값, 고른 적 없으면 null. */
	public String get(String key) {
		return values.get(key);
	}

	/** 값, 고른 적 없으면 {@code fallback}. */
	public String get(String key, String fallback) {
		return values.getOrDefault(key, fallback);
	}

	/** 정수 값, 고른 적 없거나 정수가 아니면 {@code fallback}. */
	public int getInt(String key, int fallback) {
		String v = values.get(key);
		if (v == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/** 실수 값, 고른 적 없거나 유한한 수가 아니면 {@code fallback}. */
	public double getDouble(String key, double fallback) {
		String v = values.get(key);
		if (v == null) {
			return fallback;
		}
		try {
			double d = Double.parseDouble(v);
			return Double.isFinite(d) ? d : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * 고른 값이 {@code choices} 의 몇 번째인가. 메뉴가 항목 목록(난이도 id, 페르소나 id, 방해 줄 이름, 속도 …)에서 지난번 항목을
	 * 찾을 때 쓴다. 항목은 {@code String.valueOf} 로, 수는 같은 값이면 같은 것으로 비교한다(3, 3.0, "3" 은 같다).
	 *
	 * @return 찾은 자리, 고른 적 없거나 목록에 없으면(모델 묶음이 바뀜) {@code fallback}
	 */
	public int indexOf(String key, List<?> choices, int fallback) {
		String v = values.get(key);
		if (v == null) {
			return fallback;
		}
		for (int i = 0; i < choices.size(); i++) {
			if (same(v, choices.get(i))) {
				return i;
			}
		}
		return fallback;
	}

	private static boolean same(String stored, Object choice) {
		if (choice == null) {
			return false;
		}
		if (choice instanceof Number n) {
			try {
				return new BigDecimal(stored).compareTo(new BigDecimal(n.toString())) == 0;
			} catch (NumberFormatException e) {
				return false;
			}
		}
		return stored.equals(String.valueOf(choice));
	}

	/**
	 * {@code key} 를 {@code value} 로 고친 새 기억. {@code value} 가 null 이거나 비었으면 지운다(고른 적 없음으로).
	 *
	 * @throws IllegalArgumentException 이름이나 값을 쓸 수 없으면
	 */
	public MenuMemory with(String key, String value) {
		if (!validKey(key)) {
			throw new IllegalArgumentException("메뉴 기억에 쓸 수 없는 이름: " + key);
		}
		TreeMap<String, String> next = new TreeMap<>(values);
		if (value == null || value.isEmpty()) {
			next.remove(key);
		} else {
			next.put(key, value);
		}
		return next.equals(values) ? this : new MenuMemory(next);
	}

	/** 정수 값으로 고친 새 기억. */
	public MenuMemory with(String key, int value) {
		return with(key, Integer.toString(value));
	}

	/**
	 * 실수 값으로 고친 새 기억 (3.0 은 "3", 2.5 는 "2.5").
	 *
	 * @throws IllegalArgumentException 유한한 수가 아니면
	 */
	public MenuMemory with(String key, double value) {
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException("유한한 수가 아님: " + value);
		}
		return with(key, BigDecimal.valueOf(value).stripTrailingZeros().toPlainString());
	}

	/** 고른 것이 하나도 없는가. */
	public boolean isEmpty() {
		return values.isEmpty();
	}
}
