package kr.ac.jbnu.se.tetris.audio;

import java.util.ArrayList;
import java.util.List;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.core.TimedMatch;

/**
 * 게임 사건 → 낼 소리. 화면과 게임 진행 코드가 같은 규칙으로 소리를 고르도록 한곳에 모았다. 결과는 {@link Sound#play(List)}
 * 에 그대로 넘긴다. 틱 앞뒤를 비교해 사건을 찾는 일은 {@link MatchSounds}(고전 판)와 {@link PieceSounds}(블록 조작)가,
 * 카운트다운은 {@link CountdownSounds} 가 한다. 어디서 무엇을 부를지는 이 패키지 설명(package-info)의 표에 있다.
 * <p>
 * 한 번의 고정에서 여러 소리가 날 때는 겹쳐도 어울리게 시간을 조금씩 벌린다: 줄 지우기와 콤보는 바로, 상쇄는 0.06초 뒤,
 * B2B 는 0.11초 뒤, 퍼펙트 클리어는 0.22초 뒤.
 */
public final class SoundCues {

	/** 대전 화면에서 공격 칩이 상대의 방해 줄 막대까지 날아가는 시간 (GamePanel 의 공격 표시와 같게). */
	public static final int ATTACK_FLIGHT_MS = 450;
	/** K.O. 소리 뒤 승리·패배 음악까지의 틈. */
	public static final int RESULT_GAP_MS = 650;
	/** 줄 지우기 뒤 상쇄 소리까지 (줄 지우기 소리의 시작에 묻히지 않게). */
	static final int CANCEL_GAP_MS = 60;
	/** 줄 지우기 뒤 B2B 소리까지. */
	static final int B2B_GAP_MS = 110;
	/** 줄 지우기 뒤 퍼펙트 클리어 소리까지. */
	static final int PERFECT_GAP_MS = 220;
	/** 승리 음악 뒤 최고 기록 소리까지 (승리 음악이 끝나 갈 때). */
	static final int RECORD_AFTER_WIN_MS = 1100;
	/** 위험 심장 소리가 빠른 박자(0.6초)로 바뀌는 채움: 쌓인 높이 + 곧 올라올 방해 줄. 보드는 20 줄 보인다. */
	public static final int DANGER_FAST_FILL = 18;
	/** 거리를 모르는 하드드롭(세기 없이 부른 것)이 쓰는 거리: 가운데 단계(3~9칸). */
	static final int DEFAULT_DROP_ROWS = 5;

	private SoundCues() {
	}

	/**
	 * 하드드롭으로 떨어진 거리(칸) → 소리 단계 ({@link Sfx#HARD_DROP} 의 세기): 2칸 이하 0, 3~9칸 1, 10칸 이상 2(반음 낮고
	 * 크다).
	 */
	public static int dropTier(int dropRows) {
		return dropRows <= 2 ? 0 : dropRows <= 9 ? 1 : 2;
	}

	/**
	 * 사람이 조작하는 블록 하나가 고정됐을 때. 하드드롭이면 하드드롭 소리가 고정 소리를 대신한다. 줄을 지웠으면 T-spin(미니
	 * 포함) 또는 줄 수에 맞는 소리, 콤보, B2B, 퍼펙트 클리어를 겹친다. 방해 줄이 올라왔으면 그 소리도.
	 *
	 * @param r          고정 결과 ({@code TimedMatch.lastLock(0)})
	 * @param dropRows   하드드롭으로 떨어진 칸 수 (0 이상), 하드드롭이 아니면 -1
	 * @param comboAfter 고정 뒤의 콤보 ({@code GameModel.combo()}, 없으면 -1)
	 * @param b2bStreak  이번 고정으로 B2B 가 이어졌으면 그 연속 수(B2B ×1 부터), 아니면 0. 화면의 "B2B" 표시와 같은 조건:
	 *                   고정 전에도 B2B 였고, 고정 뒤에도 B2B 이고, 줄을 지웠다
	 */
	public static List<Cue> lock(GameModel.LockResult r, int dropRows, int comboAfter, int b2bStreak) {
		return lock(dropRows, r.lines(), r.spin(), r.perfectClear(), comboAfter, b2bStreak, r.garbageRisen());
	}

	/**
	 * {@link #lock(GameModel.LockResult, int, int, int)} 의 예전 모양: 하드드롭 거리를 모르면 가운데 단계, B2B 가 이어졌으면
	 * 연속 1 로 친다.
	 *
	 * @param hardDrop     이 고정이 하드드롭인가 (이번 틱의 사람 조작이 {@code Input.HD})
	 * @param b2bContinued 이번 고정으로 B2B 가 이어졌는가
	 */
	public static List<Cue> lock(GameModel.LockResult r, boolean hardDrop, int comboAfter, boolean b2bContinued) {
		return lock(r, hardDrop ? DEFAULT_DROP_ROWS : -1, comboAfter, b2bContinued ? 1 : 0);
	}

	/**
	 * 고정 한 번의 소리. 위 메서드의 본체이고, {@code LockResult} 가 없는 곳에서도 쓸 수 있게 값으로 받는다.
	 *
	 * @param dropRows     하드드롭으로 떨어진 칸 수, 하드드롭이 아니면 -1
	 * @param spin         T-spin 판정 ({@link Spin#MINI} 는 미니 소리)
	 * @param b2bStreak    B2B 연속 수, 이어지지 않았으면 0
	 * @param garbageRisen 이번 고정에서 보드로 올라온 방해 줄 수
	 */
	public static List<Cue> lock(int dropRows, int lines, Spin spin, boolean perfectClear, int comboAfter,
			int b2bStreak, int garbageRisen) {
		List<Cue> out = new ArrayList<>(5);
		out.add(dropRows >= 0 ? Cue.of(Sfx.HARD_DROP, dropTier(dropRows)) : Cue.of(Sfx.LOCK));
		if (lines > 0) {
			if (spin == Spin.MINI) {
				out.add(Cue.of(Sfx.TSPIN, 0));
			} else {
				out.add(Cue.of(spin == Spin.FULL ? Sfx.TSPIN : Sfx.CLEAR, lines));
			}
			if (comboAfter >= 1) {
				out.add(Cue.of(Sfx.COMBO, comboAfter));
			}
			if (b2bStreak > 0) {
				out.add(Cue.of(Sfx.B2B, b2bStreak).withDelay(B2B_GAP_MS));
			}
			if (perfectClear) {
				out.add(Cue.of(Sfx.PERFECT_CLEAR).withDelay(PERFECT_GAP_MS));
			}
		}
		if (garbageRisen > 0) {
			out.add(Cue.of(Sfx.GARBAGE_RISE, garbageRisen));
		}
		return out;
	}

	/**
	 * 받을 방해 줄이 {@code lines} 줄 새로 생겼을 때의 경고. 대전에서는 공격 칩이 막대에 닿는 때({@link #ATTACK_FLIGHT_MS}
	 * 뒤)에 울리도록 {@code flying} 을 true 로, 칩이 없는 혼자 연습(스케줄러 공격)은 false 로.
	 */
	public static Cue garbage(int lines, boolean flying) {
		return Cue.of(Sfx.GARBAGE, lines).withDelay(flying ? ATTACK_FLIGHT_MS : 0);
	}

	/**
	 * 내 고정의 공격이 받을 방해 줄을 {@code lines} 줄 상쇄했을 때 (공격 - 보냄 &gt; 0). 화면의 "상쇄 N" 과 줄어드는 방해 줄
	 * 막대에 맞춘 소리로, 줄 지우기 소리 조금 뒤에 난다.
	 */
	public static Cue cancel(int lines) {
		return Cue.of(Sfx.GARBAGE_CANCEL, lines).withDelay(CANCEL_GAP_MS);
	}

	/**
	 * 한 틱 동안 새로 받은 방해 줄 수. 틱 앞뒤의 받을 줄 수({@code GameModel.pendingLines()})에서, 이번 틱의 내 고정이
	 * 상쇄하거나 보드로 올린 만큼을 되돌려 센다. 대전의 상대 공격과 혼자 연습의 스케줄러 공격을 똑같이 센다.
	 *
	 * @param own 이번 틱의 내 고정 결과, 없으면 null
	 */
	public static int incoming(int pendingBefore, int pendingAfter, GameModel.LockResult own) {
		return own == null ? incoming(pendingBefore, pendingAfter, 0, 0)
				: incoming(pendingBefore, pendingAfter, own.attack() - own.sent(), own.garbageRisen());
	}

	/** {@link #incoming(int, int, GameModel.LockResult)} 를 값으로: 상쇄한 줄 {@code cancelled}, 올라온 줄 {@code risen}. */
	public static int incoming(int pendingBefore, int pendingAfter, int cancelled, int risen) {
		return Math.max(0, pendingAfter - (pendingBefore - cancelled - risen));
	}

	/** 카운트다운에서 보이는 숫자가 바뀔 때: 3·2·1 이면 삑, 0(시작)이면 GO. 숫자를 따라가는 것은 {@link CountdownSounds}. */
	public static Cue countdown(int secondsLeft) {
		return secondsLeft > 0 ? Cue.of(Sfx.COUNTDOWN, secondsLeft) : Cue.of(Sfx.GO);
	}

	/**
	 * 위험할 때의 심장 소리. 화면이 사람의 보드를 빨갛게 표시하는 동안(멈춤·판 끝이 아닐 때, 설정의 심장 소리가 켜져 있을 때)
	 * 그릴 때마다 불러도 쿨다운이 박자(0.85초, 더 위험하면 0.6초)를 맞춘다.
	 *
	 * @param fill 쌓인 높이 + 곧 올라올 방해 줄(최대 8). 화면이 위험을 가를 때 쓰는 값과 같게 넘긴다.
	 *             {@link #DANGER_FAST_FILL} 이상이면 빠른 박자
	 */
	public static Cue danger(int fill) {
		return Cue.of(Sfx.DANGER, fill >= DANGER_FAST_FILL ? 2 : 1);
	}

	/** 모래 모드: 블록이 모래로 부서짐(고정). 하드드롭이면 하드드롭 소리(가운데 단계)를 함께 낸다. */
	public static List<Cue> sandLock(boolean hardDrop) {
		return sandLock(hardDrop ? DEFAULT_DROP_ROWS : -1);
	}

	/**
	 * 모래 모드: 블록이 모래로 부서짐(고정). 하드드롭이면 떨어진 거리의 단계로 하드드롭 소리를 함께 낸다.
	 *
	 * @param dropRows 하드드롭으로 떨어진 칸 수(틱 전의 {@code ghost().y() - piece().y()}), 하드드롭이 아니면 -1
	 */
	public static List<Cue> sandLock(int dropRows) {
		return dropRows >= 0 ? List.of(Cue.of(Sfx.HARD_DROP, dropTier(dropRows)), Cue.of(Sfx.SAND_LAND))
				: List.of(Cue.of(Sfx.SAND_LAND));
	}

	/** 모래 모드: 덩어리가 지워지기 시작함(반짝임). 배율 1 이면 {@code SAND_CLEAR}, 2 이상이면 그 배율의 연쇄 소리. */
	public static Cue sandClear(int multiplier) {
		return multiplier <= 1 ? Cue.of(Sfx.SAND_CLEAR) : Cue.of(Sfx.SAND_CHAIN, multiplier);
	}

	/**
	 * 판이 끝났을 때 (사람이 P1). 언제나 K.O. 소리가 먼저 나고, 이기면 승리·지면 패배 음악이 뒤따른다. 무승부와 혼자 연습·모래
	 * 모드의 끝은 K.O. 만. 최고 기록을 깼으면 마지막에 최고 기록 소리.
	 */
	public static List<Cue> result(TimedMatch.Result result, boolean newRecord) {
		List<Cue> out = new ArrayList<>(3);
		out.add(Cue.of(Sfx.KO));
		int recordAt = RESULT_GAP_MS;
		if (result == TimedMatch.Result.P1) {
			out.add(Cue.of(Sfx.WIN).withDelay(RESULT_GAP_MS));
			recordAt += RECORD_AFTER_WIN_MS;
		} else if (result == TimedMatch.Result.P2) {
			out.add(Cue.of(Sfx.LOSE).withDelay(RESULT_GAP_MS));
		}
		if (newRecord) {
			out.add(Cue.of(Sfx.NEW_RECORD).withDelay(recordAt));
		}
		return out;
	}
}
