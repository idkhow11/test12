package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.List;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;

/**
 * 모래 AI 의 단계 1~5. 이름과 조작 속도(생각 지연, 초당 입력)는 고전 AI 의 단계(docs/GAME.md 6.1)를 따른다. 두뇌는 모든 단계가
 * 같은 규칙 평가({@link SandEval})를 쓰고, 단계마다 고르기 온도, 홀드, 2수 탐색의 폭만 다르다. 고전 AI 처럼 사람이 느끼는 난이도는
 * 주로 조작 속도가 정한다(벤치마크 {@code SandAiBench}).
 * <p>
 * 규칙 sand-0.2(약 63° 경사)부터는 모든 단계가 다음 블록까지 본다(2수). 블록 하나로는 좌우 벽을 잇기 어려워 지우기가 블록 두
 * 개 이상이 맞아떨어질 때 생기므로, 1수만 보면 지우기를 거의 내다보지 못한다(10-07, 모래 기다리기와 함께 8판 × 240초: 2수
 * 없이 상급 8판 중 6판 짐·지우기 효율 79%, 2수 6후보로 0판 짐·96%).
 *
 * @param id           단계 1~5
 * @param name         이름 (고전 단계와 같은 초급·중급·상급·최상급·불가능)
 * @param thinkMs      생각 지연: 블록이 나온 뒤 첫 입력까지 (ms)
 * @param inputsPerSec 초당 입력 수
 * @param temperature  고르기 온도 τ. 0 이면 언제나 1등이고, 크면 점수가 낮은 후보도 고른다(고전 {@code Chooser} 와 같은 방법)
 * @param hold         홀드를 쓰는가
 * @param lookahead    다음 블록까지 보는 2수 탐색에서 펼칠 상위 후보 수 (0 이면 1수만 본다)
 */
public record SandAiLevel(int id, String name, int thinkMs, double inputsPerSec, double temperature, boolean hold,
		int lookahead) {

	/**
	 * 단계 1~5. 조작 속도는 docs/GAME.md 6.1 의 고전 AI 단계(10-04 조정)와 같은 1,450/700/400/180/100ms, 초당 5/6/10/16/25 이다.
	 * 고전 단계의 속도가 모델 묶음에서 바뀌어도 따라가려면 메뉴가 고른 고전 단계를 {@link #of(AiCatalog.Difficulty)} 로 넘긴다.
	 * 2수 탐색은 초급·중급이 상위 3후보, 상급부터 상위 6후보다(초급·중급은 3과 6의 차이가 없었다).
	 */
	public static final List<SandAiLevel> LEVELS = List.of(
			new SandAiLevel(1, "초급", 1450, 5, 0.3, false, 3),
			new SandAiLevel(2, "중급", 700, 6, 0.1, false, 3),
			new SandAiLevel(3, "상급", 400, 10, 0, true, 6),
			new SandAiLevel(4, "최상급", 180, 16, 0, true, 6),
			new SandAiLevel(5, "불가능", 100, 25, 0, true, 6));

	public SandAiLevel {
		if (thinkMs < 0 || inputsPerSec <= 0 || inputsPerSec > 1000 || temperature < 0 || lookahead < 0) {
			throw new IllegalArgumentException("모래 AI 단계 값이 이상함: " + id + " " + name);
		}
	}

	/** 단계 1~5 의 기본값. */
	public static SandAiLevel of(int id) {
		if (id < 1 || id > LEVELS.size()) {
			throw new IllegalArgumentException("모래 AI 단계는 1~" + LEVELS.size() + ": " + id);
		}
		return LEVELS.get(id - 1);
	}

	/**
	 * 고전 AI 단계와 같은 번호의 모래 단계. 이름과 조작 속도는 {@code d} 를 따르므로, 메뉴가 고른 고전 단계를 넘기면 모델
	 * 묶음({@code models/manifest.json})의 속도와 언제나 같다. 두뇌 설정(온도, 홀드, 2수)은 같은 번호의 기본값이다. 번호는 1~5 로
	 * 자른다.
	 */
	public static SandAiLevel of(AiCatalog.Difficulty d) {
		SandAiLevel base = of(Math.max(1, Math.min(LEVELS.size(), d.id())));
		return new SandAiLevel(base.id, d.name(), d.thinkMs(), d.inputsPerSec(), base.temperature, base.hold,
				base.lookahead);
	}

	/** 입력 사이 간격 (ms): {@code round(1000 ÷ 초당 입력)}, 고전 AI 와 같다. */
	public int intervalMs() {
		return Math.max(1, (int) Math.round(1000 / inputsPerSec));
	}

	/** 같은 단계에서 두뇌 설정만 바꾼 것 (벤치마크와 시험용). */
	public SandAiLevel with(double temperature, boolean hold, int lookahead) {
		return new SandAiLevel(id, name, thinkMs, inputsPerSec, temperature, hold, lookahead);
	}
}
