package kr.ac.jbnu.se.tetris.ui;

import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 한 판을 그리는 데 쓰는 사용자 설정 (runs/quality/SPEC.md 1.4(c)(e)): 블록 색과 무늬, 고스트, 효과 세기 m, 화면 흔들림, 번쩍임
 * 줄이기. 판을 시작할 때의 설정({@link GameSession#user})에서 만들므로 그 판 내내 같다.
 * <ul>
 * <li>효과 세기 m(끔·약하게·보통·강하게 = 0 / 0.5 / 1 / 1.5)은 조각 수, 튀는 반동과 흔들림의 폭, 번쩍임의 알파(1 까지),
 * 빛의 맥박에 곱한다. m = 0 이어도 정보를 주는 움직임(공격 칩, 막대가 차오름, 카운트다운, 결과 숫자, 고정 지연 색)은 남는다.</li>
 * <li>사람 대 AI 의 AI 보드는 효과를 절반(m/2)으로 하고 흔들거나 튀지 않는다. 큰 지우기의 빛만은 그대로 둔다(AI 의 테트리스를
 * 알아채게).</li>
 * <li>번쩍임 줄이기: 보드 위 번쩍임 알파 0.3 까지, 고리·흰 번쩍임 없음, 깜빡임 1 Hz 까지.</li>
 * </ul>
 *
 * @param look   블록 색 묶음과 무늬 ({@link ColorPalettes#look(UserSettings)})
 * @param m      효과 세기 배수
 * @param shake  화면 흔들림 (튀는 반동 포함)
 * @param calm   번쩍임 줄이기
 * @param ghost  고스트를 그리는가
 * @param versus 사람 대 AI 판인가 (P2 가 AI)
 */
record BattleStyle(ColorPalettes.Look look, double m, boolean shake, boolean calm, boolean ghost, boolean versus) {

	/** 판 {@code s} 를 시작할 때의 사용자 설정으로. */
	static BattleStyle of(GameSession s) {
		UserSettings u = s.user;
		return new BattleStyle(ColorPalettes.look(u), u.effectsScale(), u.shake(), u.calmFlash(), u.ghost(),
				s.settings.versusAi());
	}

	/** 보드 i 를 AI 가 두는가 (사람 대 AI 의 P2). */
	boolean ai(int i) {
		return versus && i == 1;
	}

	/** 보드 i 의 효과 세기: AI 보드는 절반. */
	double m(int i) {
		return ai(i) ? m / 2 : m;
	}

	/** 보드 i 의 번쩍임 알파 배수: m 을 곱하되 1 을 넘지 않는다. */
	double flash(int i) {
		return Math.min(1, m(i));
	}

	/** 보드 i 를 흔들고 튀게 하는가: 화면 흔들림이 켜져 있고, 효과가 켜져 있고, 사람의 보드다. */
	boolean shakes(int i) {
		return shake && m > 0 && !ai(i);
	}

	/** 보드 위를 덮는 번쩍임의 알파 상한: 0.6, 번쩍임 줄이기면 0.3 (SPEC 0장 규칙 5). */
	double flashCap() {
		return calm ? 0.3 : 0.6;
	}

	/** 깜빡임·맥박의 빠르기 상한 (Hz): 2, 번쩍임 줄이기면 1 (SPEC 6장 2). */
	double maxHz() {
		return calm ? 1 : 2;
	}
}
