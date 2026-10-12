package kr.ac.jbnu.se.tetris.ui;

import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.Sound;

/**
 * 대전 화면이 내는 소리 (audio 패키지 설명의 "GamePanel" 줄): 일시정지·계속, 카운트다운 3·2·1·GO, 위험할 때의 심장 소리, 기권
 * 확인, 결과 카드의 고르기와 다시 하기·같은 판 다시·메뉴로. 판의 진행 소리(조작, 줄 지우기, 방해 줄, 결과 음악)는
 * {@link GameSession} 이 낸다. 게임은 공용 엔진({@link #engine()})을 쓰고, 시험은 요청을 적어 두는 것을 넘긴다.
 */
interface BattleSounds {

	/** 소리 하나. null 이면 아무것도 하지 않는다. 막히지 않는다. */
	void play(Cue cue);

	/** 울리고 있는 소리와 아직 울리지 않은 늦춘 소리(앞 판의 승리 음악 등)를 모두 끝낸다. */
	void stopAll();

	/** 세기 1 의 소리 하나. */
	default void play(Sfx sfx) {
		play(Cue.of(sfx));
	}

	/** 판을 떠난다: 앞 판의 소리를 끝내고 {@code sfx} 를 낸다 (다시 하기는 MENU_SELECT, 메뉴로는 MENU_BACK). */
	default void leave(Sfx sfx) {
		stopAll();
		play(sfx);
	}

	/** 공용 엔진 ({@link Sound#get()}). 엔진을 켜기 전, 창 없는 실행, {@code -Dtetris.sound=off} 에서는 아무 일도 하지 않는다. */
	static BattleSounds engine() {
		return new BattleSounds() {
			@Override
			public void play(Cue cue) {
				Sound.get().play(cue);
			}

			@Override
			public void stopAll() {
				Sound.get().stopAll();
			}
		};
	}
}
