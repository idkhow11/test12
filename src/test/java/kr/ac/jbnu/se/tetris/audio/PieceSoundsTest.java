package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 블록 조작 소리 (PieceSounds): 성공한 조작에만 소리, 실패한 홀드는 DENY, 바닥에 처음 닿으면 LAND, 고정한 틱은 조용, 누르고
 * 있어 반복된 이동은 세기 2(3dB 작게).
 */
class PieceSoundsTest {

	private static final PieceState AT_3 = PieceState.spawn(PieceType.T);
	private static final PieceState AT_2 = new PieceState(PieceType.T, 0, 2, AT_3.y(), false, 0);
	private static final PieceState TURNED = new PieceState(PieceType.T, 1, 3, AT_3.y(), true, 0);

	/** 틱 하나: 앞 상태 → 조작 → 뒤 상태. */
	private static List<Cue> tick(PieceState before, boolean groundedBefore, Input input, PieceState after,
			int spawnsAfter, boolean groundedAfter, boolean locked) {
		PieceSounds p = new PieceSounds();
		p.before(before, 1, groundedBefore);
		return p.after(input, after, spawnsAfter, groundedAfter, locked);
	}

	@Test
	void successfulMovesAndTurnsMakeASound() {
		assertEquals(List.of(Cue.of(Sfx.MOVE)), tick(AT_3, false, Input.L, AT_2, 1, false, false));
		assertEquals(List.of(Cue.of(Sfx.ROTATE)), tick(AT_3, false, Input.CW, TURNED, 1, false, false));
		assertEquals(List.of(Cue.of(Sfx.ROTATE)), tick(AT_3, false, Input.CCW, TURNED, 1, false, false));
	}

	@Test
	void blockedMovesAndTurnsAreSilent() {
		assertEquals(List.of(), tick(AT_3, false, Input.R, AT_3, 1, false, false), "벽에 막힌 이동");
		assertEquals(List.of(), tick(AT_3, false, Input.CW, AT_3, 1, false, false), "막힌 회전");
		assertEquals(List.of(), tick(AT_3, false, null, AT_3, 1, false, false), "조작 없는 틱");
		assertEquals(List.of(), tick(AT_3, false, Input.D, AT_2, 1, false, false), "한 칸 내림은 소리 없음");
	}

	@Test
	void holdSucceedsWithANewPieceAndFailsWithDeny() {
		assertEquals(List.of(Cue.of(Sfx.HOLD)), tick(AT_3, false, Input.H, PieceState.spawn(PieceType.I), 2, false, false));
		assertEquals(List.of(Cue.of(Sfx.DENY)), tick(AT_3, false, Input.H, AT_3, 1, false, false), "이미 홀드를 썼다");
		assertEquals(List.of(Cue.of(Sfx.HOLD)), tick(AT_3, false, Input.H, PieceState.spawn(PieceType.O), 2, true, false),
				"막 나온 블록이 바로 닿아 있어도 LAND 는 없다");
	}

	@Test
	void touchingDownLandsOnce() {
		assertEquals(List.of(Cue.of(Sfx.LAND)), tick(AT_3, false, null, AT_3, 1, true, false), "중력·소프트드롭으로 닿음");
		assertEquals(List.of(), tick(AT_3, true, null, AT_3, 1, true, false), "닿아 있는 동안은 다시 내지 않는다");
		assertEquals(List.of(Cue.of(Sfx.MOVE), Cue.of(Sfx.LAND)), tick(AT_3, false, Input.L, AT_2, 1, true, false),
				"턱 위로 옮겨 닿음");
	}

	@Test
	void aLockingTickLeavesTheSoundToTheLock() {
		assertEquals(List.of(), tick(AT_3, true, Input.L, AT_2, 2, false, true), "15 번째 리셋으로 바로 고정");
		assertEquals(List.of(), tick(AT_3, false, Input.HD, AT_3, 2, false, true), "하드드롭");
	}

	/** 같은 PieceSounds 로 {@code idle} 틱을 조용히 보낸 뒤 x 를 {@code dx} 만큼 옮기는 틱 하나. */
	private static List<Cue> moveAfter(PieceSounds p, int idle, PieceState[] at, int dx) {
		for (int i = 0; i < idle; i++) {
			p.before(at[0], 1, false);
			p.after(null, at[0], 1, false, false);
		}
		PieceState next = new PieceState(PieceType.T, 0, at[0].x() + dx, at[0].y(), false, -1);
		p.before(at[0], 1, false);
		List<Cue> cues = p.after(dx < 0 ? Input.L : Input.R, next, 1, false, false);
		at[0] = next;
		return cues;
	}

	@Test
	void heldMovesThatRepeatQuicklyAreSofter() {
		PieceSounds p = new PieceSounds();
		PieceState[] at = { new PieceState(PieceType.T, 0, 1, AT_3.y(), false, -1) };
		assertEquals(List.of(Cue.of(Sfx.MOVE)), moveAfter(p, 0, at, 1), "누른 첫 이동은 보통 크기");
		assertEquals(List.of(Cue.of(Sfx.MOVE, 2)), moveAfter(p, 49, at, 1), "ARR(50ms) 로 반복된 이동은 세기 2");
		assertEquals(List.of(Cue.of(Sfx.MOVE, 2)), moveAfter(p, PieceSounds.REPEAT_GAP_MS - 1, at, 1), "0.1초 안이면 반복");
		assertEquals(List.of(Cue.of(Sfx.MOVE)), moveAfter(p, PieceSounds.REPEAT_GAP_MS, at, 1), "그보다 늦으면 새로 누른 것");
		assertEquals(List.of(Cue.of(Sfx.MOVE)), moveAfter(p, 0, at, -1), "방향을 바꾸면 새로 누른 것");
		assertEquals(List.of(Cue.of(Sfx.MOVE, 2)), moveAfter(p, 30, at, -1));
	}

	@Test
	void missingStatesAreSilent() {
		PieceSounds p = new PieceSounds();
		assertEquals(List.of(), p.after(Input.L, AT_2, 1, false, false), "before 를 부르지 않았다");
		assertEquals(List.of(), tick(null, false, Input.L, AT_2, 1, false, false), "모래 모드: 블록을 기다리던 중");
		assertEquals(List.of(), tick(AT_3, false, Input.L, null, 1, false, false), "판이 끝나 블록이 없다");
		p.before(AT_3, 1, false);
		assertEquals(List.of(Cue.of(Sfx.MOVE)), p.after(Input.L, AT_2, 1, false, false));
		assertEquals(List.of(), p.after(Input.L, AT_3, 1, false, false), "after 를 두 번 부르면 두 번째는 빈 목록");
	}
}
