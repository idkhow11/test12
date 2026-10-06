package kr.ac.jbnu.se.tetris.core;

import java.util.Locale;

/** T-spin 판정 결과 (RULES 8장). JSON 에서는 소문자 이름을 쓴다. */
public enum Spin {
	NONE, MINI, FULL;

	/** 기본 로캘과 무관하게 소문자로 바꾼다(예: 터키어 로캘에서 "MINI" → "mını" 가 되는 것을 막음). */
	public String json() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static Spin fromJson(String s) {
		return valueOf(s.toUpperCase(Locale.ROOT));
	}
}
