package kr.ac.jbnu.se.tetris.audio;

import java.util.ArrayList;
import java.util.List;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 고전 판(사람 대 AI, 혼자 연습)의 한 틱에서 낼 소리를 틱 앞뒤의 상태를 비교해 고른다. 판의 진행 코드는 잠금 안에서 이렇게
 * 부른다:
 *
 * <pre>
 * sounds.before(match);
 * match.tick(p1, p2);
 * List&lt;Cue&gt; cues = sounds.after(match, p1);   // p1 = 이번 틱의 P1(사람) 조작
 * ...
 * Sound.get().play(cues);                     // 막히지 않으므로 잠금 안에서 불러도 된다
 * </pre>
 *
 * P1 은 사람이다. 그 블록의 조작 소리({@link PieceSounds}), 고정·하드드롭(떨어진 거리의 단계)·줄 지우기·T-spin(미니
 * 포함)·콤보·B2B(연속 수)·퍼펙트 클리어·방해 줄 상승({@link SoundCues#lock}), 내 공격의 상쇄({@link SoundCues#cancel}),
 * 새로 받은 방해 줄 경고(대전은 공격 칩이 막대에 닿는 때, {@link SoundCues#garbage}), 속도 단계 오름. AI(P2)의 조작과 줄
 * 지우기는 소리가 없다(그 공격이 나에게 오면 경고가 울린다).
 * <p>
 * 판의 끝(K.O., 승리·패배 음악, 최고 기록)은 여기서 내지 않는다. 최고 기록을 아는 곳(판이 끝났을 때의 처리)에서
 * {@link SoundCues#result} 로 낸다. 틱마다 소리 엔진에 "판이 진행 중" 이라고 알리므로(PieceSounds 를 거쳐) 화면 코드는
 * 따로 부를 것이 없다.
 * <p>
 * 게임 진행 스레드 하나만 쓴다(화면 확인 도구와 시험처럼 스레드 없이 진행해도 된다). 판의 상태는 읽기만 한다. 소리가 없는
 * 틱(거의 모든 틱)은 아무것도 새로 만들지 않는다.
 */
public final class MatchSounds {

	private final boolean humanP1;
	private final PieceSounds piece = new PieceSounds();
	private boolean b2b;
	private int streak;
	private int pending;
	private int level;
	private boolean ready;
	/** 하드드롭 거리를 잰 블록과 그때의 꺼낸 횟수: 같으면 보드도 같으므로 다시 재지 않는다. */
	private PieceState measured;
	private int measuredSpawns = -1;
	private int dropRows;

	/** P1 을 사람이 두는 판 (사람 대 AI, 혼자 연습). */
	public MatchSounds() {
		this(true);
	}

	/**
	 * @param humanP1 P1 을 사람이 두는가. 게임의 모든 고전 판은 true 다. false 면(사람이 없는 판, 시험·도구) 속도 단계 오름만
	 *                낸다
	 */
	public MatchSounds(boolean humanP1) {
		this.humanP1 = humanP1;
	}

	/** 틱 전에 부른다 (판을 바꾸는 잠금 안에서). */
	public void before(TimedMatch m) {
		ready = true;
		level = m.gravityLevel();
		if (humanP1) {
			TimedPlayer p = m.player(0);
			GameModel g = p.game();
			b2b = g.b2b();
			piece.before(p.piece(), p.spawns(), p.grounded());
			pending = g.pendingLines();
			PieceState s = p.piece();
			if (s != measured || p.spawns() != measuredSpawns) { // 블록이 움직였거나 새 블록: 하드드롭 거리를 다시 잰다
				measured = s;
				measuredSpawns = p.spawns();
				dropRows = s == null || g.isOver() ? 0 : Math.max(0, p.ghost().y() - s.y());
			}
		}
	}

	/**
	 * 틱 뒤에 부른다. 앞에 {@link #before} 를 부르지 않았으면 빈 목록.
	 *
	 * @param p1 이번 틱의 P1 조작(없으면 null). 사람이 두는 P1 의 조작 소리와 하드드롭을 가르는 데 쓴다
	 * @return 낼 소리 (없으면 빈 목록)
	 */
	public List<Cue> after(TimedMatch m, Input p1) {
		if (!ready) {
			return List.of();
		}
		ready = false;
		List<Cue> out = null;
		if (humanP1) {
			TimedPlayer p = m.player(0);
			GameModel g = p.game();
			GameModel.LockResult r = m.lastLock(0);
			out = add(out, piece.after(p1, p.piece(), p.spawns(), p.grounded(), r != null));
			if (r != null) {
				streak = nextStreak(streak, b2b, g.b2b(), r.lines());
				int b2bStreak = r.lines() > 0 ? streak : 0;
				out = add(out, SoundCues.lock(r, p1 == Input.HD ? dropRows : -1, g.combo(), b2bStreak));
				int cancelled = r.attack() - r.sent();
				if (cancelled > 0) {
					out = add(out, List.of(SoundCues.cancel(cancelled)));
				}
			}
			int incoming = SoundCues.incoming(pending, g.pendingLines(), r);
			if (incoming > 0) {
				out = add(out, List.of(SoundCues.garbage(incoming, m.size() == 2)));
			}
		}
		if (!m.isOver() && m.gravityLevel() > level) {
			out = add(out, List.of(Cue.of(Sfx.LEVEL_UP)));
		}
		return out == null ? List.of() : out;
	}

	/**
	 * B2B 연속 수의 다음 값 (화면의 "B2B ×N" 과 같은 셈). 줄을 지운 고정에서 고정 전에도 뒤에도 B2B 면 하나 늘고, 아니면(B2B 가
	 * 막 시작했거나 끊김) 0. 줄을 지우지 않은 고정은 B2B 를 바꾸지 않으므로 그대로.
	 */
	static int nextStreak(int streak, boolean b2bBefore, boolean b2bAfter, int lines) {
		if (lines <= 0) {
			return streak;
		}
		return b2bBefore && b2bAfter ? streak + 1 : 0;
	}

	private static List<Cue> add(List<Cue> out, List<Cue> more) {
		if (more.isEmpty()) {
			return out;
		}
		if (out == null) {
			out = new ArrayList<>(more.size() + 4);
		}
		out.addAll(more);
		return out;
	}
}
