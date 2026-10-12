package kr.ac.jbnu.se.tetris.settings;

import java.util.Objects;

/**
 * 사용자 설정: 키, 좌우 자동 반복(DAS/ARR), AI 속도, 고스트, 색약 색상, 블록 무늬, 화면 효과(세기, 흔들림, 번쩍임 줄이기),
 * 소리(크기, 음소거, 심장 소리), 전체 화면으로 시작, 메뉴에서 마지막으로 고른 것. 값이 바뀌지 않는 객체이고 {@code withX} 가 새
 * 객체를 돌려준다. 범위를 벗어난 수는 만들 때 범위 끝으로 맞춘다.
 * <p>
 * 기본값은 설정 화면이 생기기 전의 게임과 같다: DAS 170ms·ARR 50ms(HumanInput 의 {@code DAS_MS}·{@code ARR_MS}), AI 속도
 * 100%(난이도 그대로), 고스트 켬, 키는 {@link KeyBindings#defaults()} (docs/GAME.md 4장), 창으로 시작. 색약 색상과 블록 무늬는
 * 끔, 효과 세기는 보통, 화면 흔들림은 켬, 번쩍임 줄이기는 끔, 소리는 {@value #DEFAULT_VOLUME}% 로 켬, 심장 소리는 켬이다.
 *
 * @param keys              동작마다 묶은 키
 * @param dasMs             좌우를 누르고 있을 때 자동 반복이 시작되기까지 (ms)
 * @param arrMs             자동 반복 간격 (ms). 0 이면 틱마다 한 칸(가장 빠름)
 * @param aiSpeed           사람 대 AI 의 AI 속도 (%), {@value #MIN_AI_SPEED}~{@value #MAX_AI_SPEED}, {@value #AI_SPEED_STEP}
 *                          단위. 100 이면 난이도 그대로다({@code AiCatalog.Difficulty#atSpeed})
 * @param ghost             고스트(하드드롭하면 놓일 자리)를 그리는가
 * @param colorBlind        색약 색상: 색약이 있어도 잘 구분되는 색 묶음(예: Okabe-Ito)으로 블록과 모래를 그린다
 * @param patterns          블록 무늬: 블록 종류(모래는 색)마다 무늬를 더해 색 없이도 구분한다. 색약 색상과 따로 켠다
 * @param effects           효과 세기 {@link #EFFECTS_OFF} 끔 · {@link #EFFECTS_LOW} 약하게 · {@link #EFFECTS_NORMAL} 보통 ·
 *                          {@link #EFFECTS_HIGH} 강하게. 곱하는 값은 {@link #effectsScale()}
 * @param shake             화면 흔들림: 큰 공격을 받거나 하드드롭하면 보드가 흔들린다
 * @param calmFlash         번쩍임 줄이기: 번쩍이는 효과를 약하게 한다(빛나는 고리·흰 번쩍임 없음)
 * @param volume            효과음 크기 0~100 (%)
 * @param muted             음소거. 켜도 {@code volume} 은 그대로 남는다
 * @param heartbeat         심장 소리: 보드가 위험할 때 심장 소리를 낸다
 * @param fullscreenOnStart 게임을 켤 때 전체 화면으로 시작한다 ({@code -Dtetris.fullscreen=true} 와 같은 효과)
 * @param menu              메뉴에서 마지막으로 고른 것
 */
public record UserSettings(KeyBindings keys, int dasMs, int arrMs, int aiSpeed, boolean ghost, boolean colorBlind,
		boolean patterns, int effects, boolean shake, boolean calmFlash, int volume, boolean muted, boolean heartbeat,
		boolean fullscreenOnStart, MenuMemory menu) {

	/** 기본 DAS (ms). HumanInput 의 첫 값과 같다. 바꾸면 손맛이 바뀐다. */
	public static final int DEFAULT_DAS_MS = 170;
	public static final int MIN_DAS_MS = 50;
	public static final int MAX_DAS_MS = 300;
	/** 설정 화면에서 한 번에 바꾸는 양 (ms). */
	public static final int DAS_STEP_MS = 10;

	/** 기본 ARR (ms). HumanInput 의 첫 값과 같다. */
	public static final int DEFAULT_ARR_MS = 50;
	public static final int MIN_ARR_MS = 0;
	public static final int MAX_ARR_MS = 150;
	/** 설정 화면에서 한 번에 바꾸는 양 (ms). */
	public static final int ARR_STEP_MS = 5;

	/** 기본 AI 속도 (%): 난이도 그대로. */
	public static final int DEFAULT_AI_SPEED = 100;
	public static final int MIN_AI_SPEED = 50;
	public static final int MAX_AI_SPEED = 150;
	/** AI 속도의 단위 (%). 메뉴와 설정 화면이 한 번에 바꾸는 양이고, 이 단위가 아닌 값은 가까운 단위로 맞춘다. */
	public static final int AI_SPEED_STEP = 10;

	/** 효과 세기: 끔 (정보를 주는 움직임만 남는다: 공격 표시, 받을 줄 막대, 카운트다운, 결과 숫자, 고정 직전 색). */
	public static final int EFFECTS_OFF = 0;
	/** 효과 세기: 약하게. */
	public static final int EFFECTS_LOW = 1;
	/** 효과 세기: 보통 (기본). */
	public static final int EFFECTS_NORMAL = 2;
	/** 효과 세기: 강하게. */
	public static final int EFFECTS_HIGH = 3;
	public static final int DEFAULT_EFFECTS = EFFECTS_NORMAL;
	/** 효과 세기 단계마다 곱하는 값 (끔·약하게·보통·강하게). */
	private static final double[] EFFECTS_SCALE = { 0, 0.5, 1, 1.5 };

	/** 기본 효과음 크기 (%). */
	public static final int DEFAULT_VOLUME = 70;
	public static final int MAX_VOLUME = 100;
	/** 설정 화면에서 한 번에 바꾸는 양 (%). */
	public static final int VOLUME_STEP = 10;

	/** 전체 화면으로 시작을 이번 실행만 바꾸는 시스템 속성 ({@code -Dtetris.fullscreen=true}, docs/GAME.md 4장). */
	public static final String FULLSCREEN_PROPERTY = "tetris.fullscreen";

	private static final UserSettings DEFAULTS = new UserSettings(KeyBindings.defaults(), DEFAULT_DAS_MS,
			DEFAULT_ARR_MS, DEFAULT_AI_SPEED, true, false, false, DEFAULT_EFFECTS, true, false, DEFAULT_VOLUME, false, true,
			false, MenuMemory.EMPTY);

	public UserSettings {
		Objects.requireNonNull(keys, "keys");
		Objects.requireNonNull(menu, "menu");
		dasMs = clamp(dasMs, MIN_DAS_MS, MAX_DAS_MS);
		arrMs = clamp(arrMs, MIN_ARR_MS, MAX_ARR_MS);
		aiSpeed = snapAiSpeed(aiSpeed);
		effects = clamp(effects, EFFECTS_OFF, EFFECTS_HIGH);
		volume = clamp(volume, 0, MAX_VOLUME);
	}

	/** 기본 설정 (설정 화면이 생기기 전의 게임과 같다). */
	public static UserSettings defaults() {
		return DEFAULTS;
	}

	static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	/** AI 속도를 범위 안의 가장 가까운 {@value #AI_SPEED_STEP} 단위로 (가운데면 위로). */
	static int snapAiSpeed(int v) {
		int c = clamp(v, MIN_AI_SPEED, MAX_AI_SPEED);
		return clamp(Math.floorDiv(c + AI_SPEED_STEP / 2, AI_SPEED_STEP) * AI_SPEED_STEP, MIN_AI_SPEED, MAX_AI_SPEED);
	}

	public UserSettings withKeys(KeyBindings v) {
		return new UserSettings(v, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withDasMs(int v) {
		return new UserSettings(keys, v, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withArrMs(int v) {
		return new UserSettings(keys, dasMs, v, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	/** AI 속도 (%). 범위 밖이면 끝으로, 단위가 아니면 가까운 단위로 맞춘다. */
	public UserSettings withAiSpeed(int v) {
		return new UserSettings(keys, dasMs, arrMs, v, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withGhost(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, v, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withColorBlind(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, v, patterns, effects, shake, calmFlash, volume, muted,
				heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withPatterns(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, v, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	/** 효과 세기 {@link #EFFECTS_OFF}~{@link #EFFECTS_HIGH}. 범위 밖이면 끝으로 맞춘다. */
	public UserSettings withEffects(int v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, v, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withShake(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, v, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withCalmFlash(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, v, volume, muted,
				heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withVolume(int v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, v,
				muted, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withMuted(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				v, heartbeat, fullscreenOnStart, menu);
	}

	public UserSettings withHeartbeat(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, v, fullscreenOnStart, menu);
	}

	public UserSettings withFullscreenOnStart(boolean v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, v, menu);
	}

	public UserSettings withMenu(MenuMemory v) {
		return new UserSettings(keys, dasMs, arrMs, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash, volume,
				muted, heartbeat, fullscreenOnStart, v);
	}

	/** 조작 감각(키, DAS, ARR)만 기본값으로 되돌린 설정. AI 속도·화면·소리·메뉴 기억은 그대로다. */
	public UserSettings withDefaultControls() {
		return new UserSettings(KeyBindings.defaults(), DEFAULT_DAS_MS, DEFAULT_ARR_MS, aiSpeed, ghost, colorBlind,
				patterns, effects, shake, calmFlash, volume, muted, heartbeat, fullscreenOnStart, menu);
	}

	/** 모든 설정을 기본값으로 되돌리되 메뉴 기억은 남긴 설정 (설정 화면의 "모두 처음으로"). */
	public UserSettings withDefaultsKeepingMenu() {
		return DEFAULTS.withMenu(menu);
	}

	/**
	 * 효과 세기에 곱하는 값: 끔 0, 약하게 0.5, 보통 1, 강하게 1.5. 화면 효과(조각 수, 튕김·흔들림 크기, 번쩍임의 진하기, 빛의
	 * 맥박)에 곱한다. 0 이어도 정보를 주는 움직임(공격 표시, 받을 줄 막대, 카운트다운, 결과 숫자, 고정 직전 색)은 남긴다.
	 */
	public double effectsScale() {
		return EFFECTS_SCALE[effects];
	}

	/** 실제로 낼 소리 크기 0~1. 음소거면 0. */
	public double soundLevel() {
		return muted ? 0 : volume / (double) MAX_VOLUME;
	}

	/**
	 * 게임을 켤 때 전체 화면으로 시작하는가. 시스템 속성 {@value #FULLSCREEN_PROPERTY} 가 있으면 그 값이 이번 실행만 이기고
	 * ({@code -Dtetris.fullscreen=false} 로 저장한 켬을 끌 수도 있다), 없으면 저장한 {@link #fullscreenOnStart}.
	 *
	 * @param property {@code System.getProperty(FULLSCREEN_PROPERTY)}, 없으면 null
	 */
	public boolean startsFullscreen(String property) {
		if (property == null || property.isBlank()) {
			return fullscreenOnStart;
		}
		return Boolean.parseBoolean(property.strip());
	}
}
