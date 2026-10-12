package kr.ac.jbnu.se.tetris.audio;

import java.util.Objects;

/**
 * 소리 하나를 낼 요청: 무엇을, 얼마나 세게, 어느 쪽에서, 얼마 뒤에. {@link SoundCues} 가 게임 사건에서 만들고
 * {@link Sound#play(Cue)} 가 받는다.
 *
 * @param sfx       소리
 * @param intensity 세기 (지운 줄 수, 콤보 수 등. 세기가 없는 소리는 무시한다)
 * @param pan       -1(왼쪽) ~ 1(오른쪽), 0 이 가운데. 범위를 벗어나면 가까운 끝으로
 * @param delayMs   0 이면 바로. 예: 공격 칩이 방해 줄 막대에 닿는 때(0.45초 뒤)에 맞춘다
 */
public record Cue(Sfx sfx, int intensity, float pan, int delayMs) {

	public Cue {
		Objects.requireNonNull(sfx, "sfx");
		pan = Float.isNaN(pan) ? 0f : Math.max(-1f, Math.min(1f, pan));
		delayMs = Math.max(0, delayMs);
	}

	public static Cue of(Sfx sfx) {
		return new Cue(sfx, 1, 0f, 0);
	}

	public static Cue of(Sfx sfx, int intensity) {
		return new Cue(sfx, intensity, 0f, 0);
	}

	/** 같은 요청을 {@code pan} 쪽에서. */
	public Cue withPan(double pan) {
		return new Cue(sfx, intensity, (float) pan, delayMs);
	}

	/** 같은 요청을 {@code ms} 뒤에. */
	public Cue withDelay(int ms) {
		return new Cue(sfx, intensity, pan, ms);
	}
}
