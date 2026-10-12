package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/**
 * 고전 판의 틱 소리 (MatchSounds): 실제 {@link TimedMatch} 를 틱 단위로 돌려 사람의 조작·닿음·고정, 하드드롭 거리 단계, 상쇄,
 * 혼자 연습의 방해 줄 경고와 상승, 속도 단계 오름이 제때 한 번씩 나오고, AI 의 조작과 고정은 조용한지 본다. 판의 상태는 읽기만
 * 한다(소리를 붙여도 판의 진행이 같다).
 */
class MatchSoundsTest {

	/** 틱 하나를 진행하고 그 틱의 소리를 돌려준다 (GameSession.advance 가 하는 순서 그대로). */
	private static List<Cue> tick(TimedMatch m, MatchSounds s, Input p1, Input p2) {
		s.before(m);
		if (m.size() == 1) {
			m.tick(p1);
		} else {
			m.tick(p1, p2);
		}
		return s.after(m, p1);
	}

	private static boolean has(List<Cue> cues, Sfx e) {
		return cues.stream().anyMatch(c -> c.sfx() == e);
	}

	private static Cue find(List<Cue> cues, Sfx e) {
		return cues.stream().filter(c -> c.sfx() == e).findFirst().orElse(null);
	}

	/** 첫 블록이 O 가 아닌 시드 (O 의 회전은 언제나 실패한다, RULES 3.3). */
	private static long seedWithoutO() {
		long seed = 1;
		while (new GameModel(seed, 1, GameModel.Scheduler.NONE).current() == PieceType.O) {
			seed++;
		}
		return seed;
	}

	private static Input input(Op op) {
		switch (op) {
		case L:
			return Input.L;
		case R:
			return Input.R;
		case CW:
			return Input.CW;
		case CCW:
			return Input.CCW;
		default:
			return Input.D;
		}
	}

	/** 경로 끝의 아래로 내리기 수 = 사람이 그 앞에서 하드드롭하면 떨어지는 칸 수. */
	private static int trailingDowns(List<Op> path) {
		int n = 0;
		for (int i = path.size() - 1; i >= 0 && path.get(i) == Op.D; i--) {
			n++;
		}
		return n;
	}

	/** 줄을 가장 많이 지우고, 같으면 구멍이 적고 낮은 배치 (홀드 없이). */
	private static Placement best(GameModel g) {
		Placement best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Placement p : g.placements().values()) {
			if (p.hold()) {
				continue;
			}
			GameModel c = g.copy();
			GameModel.LockResult r = c.apply(p);
			double score = r.lines() * 1000 - holes(c.grid()) * 30 - height(c.grid()) * 2;
			if (score > bestScore) {
				bestScore = score;
				best = p;
			}
		}
		return best;
	}

	/** 그 배치로 줄을 몇 줄 지우는가. */
	private static int linesOf(GameModel g, Placement p) {
		return g.copy().apply(p).lines();
	}

	private static int height(Grid grid) {
		for (int row = 0; row < Grid.HEIGHT; row++) {
			for (int col = 0; col < Grid.WIDTH; col++) {
				if (grid.get(row, col) != Grid.EMPTY) {
					return Grid.HEIGHT - row;
				}
			}
		}
		return 0;
	}

	private static int holes(Grid grid) {
		int holes = 0;
		for (int col = 0; col < Grid.WIDTH; col++) {
			boolean roof = false;
			for (int row = 0; row < Grid.HEIGHT; row++) {
				boolean filled = grid.get(row, col) != Grid.EMPTY;
				roof |= filled;
				if (roof && !filled) {
					holes++;
				}
			}
		}
		return holes;
	}

	/** 사람처럼 둔다: 경로대로 옮기고 남은 아래로 내리기는 하드드롭으로. 그동안의 소리를 모두 모은다. */
	private static List<Cue> play(TimedMatch m, MatchSounds s, Placement p) {
		List<Cue> all = new ArrayList<>();
		List<Op> path = p.path();
		int moves = path.size() - trailingDowns(path);
		for (int i = 0; i < moves; i++) {
			all.addAll(tick(m, s, input(path.get(i)), null));
		}
		all.addAll(tick(m, s, Input.HD, null));
		return all;
	}

	@Test
	void theHumansPieceSoundsOnlyWhenItReallyMoves() {
		TimedMatch m = new TimedMatch(seedWithoutO(), 1, GameModel.Scheduler.NONE, 0); // 중력 없음
		MatchSounds s = new MatchSounds();
		assertEquals(List.of(Cue.of(Sfx.MOVE)), tick(m, s, Input.L, null));
		assertEquals(List.of(), tick(m, s, null, null), "조작 없는 틱은 조용하다");
		assertEquals(List.of(Cue.of(Sfx.ROTATE)), tick(m, s, Input.CW, null));
		for (int i = 0; i < 12; i++) {
			tick(m, s, Input.R, null);
		}
		assertEquals(List.of(), tick(m, s, Input.R, null), "벽에 막힌 이동은 조용하다");
		assertEquals(List.of(Cue.of(Sfx.HOLD)), tick(m, s, Input.H, null));
		assertEquals(List.of(Cue.of(Sfx.DENY)), tick(m, s, Input.H, null), "블록 하나에 홀드는 한 번");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 2)), tick(m, s, Input.HD, null),
				"하드드롭은 고정 소리를 대신한다 (빈 보드의 맨 위에서 바닥까지: 10칸 이상 단계)");
		assertEquals(List.of(), s.after(m, null), "before 없이 부른 after 는 빈 목록");
	}

	@Test
	void hardDropTiersFollowTheDistanceFallen() {
		TimedMatch m = new TimedMatch(seedWithoutO(), 1, GameModel.Scheduler.NONE, 0);
		MatchSounds s = new MatchSounds();
		TimedPlayer p = m.player(0);
		int full = p.ghost().y() - p.piece().y();
		assertTrue(full >= 10, "빈 보드: " + full + "칸");
		for (int i = 0; i < full - 5; i++) {
			tick(m, s, Input.D, null); // 한 칸씩 내려 바닥 5칸 위로
		}
		assertEquals(5, p.ghost().y() - p.piece().y());
		assertEquals(Cue.of(Sfx.HARD_DROP, 1), find(tick(m, s, Input.HD, null), Sfx.HARD_DROP), "3~9칸");
		int d = p.ghost().y() - p.piece().y();
		for (int i = 0; i < d - 1; i++) {
			tick(m, s, Input.D, null);
		}
		assertEquals(Cue.of(Sfx.HARD_DROP, 0), find(tick(m, s, Input.HD, null), Sfx.HARD_DROP), "1칸");
		assertEquals(Cue.of(Sfx.HARD_DROP, 2), find(tick(m, s, Input.HD, null), Sfx.HARD_DROP), "맨 위에서");
	}

	@Test
	void aGreedyHumanHearsTheRightTierForEveryDrop() {
		TimedMatch m = new TimedMatch(11, 1, GameModel.Scheduler.NONE, 0);
		MatchSounds s = new MatchSounds();
		for (int piece = 0; piece < 25 && !m.isOver(); piece++) {
			Placement target = best(m.player(0).game());
			int drop = trailingDowns(target.path());
			List<Cue> cues = play(m, s, target);
			assertEquals(Cue.of(Sfx.HARD_DROP, SoundCues.dropTier(drop)), find(cues, Sfx.HARD_DROP),
					piece + " 번째 블록: " + drop + "칸 떨어짐");
			assertFalse(has(cues, Sfx.LOCK), "하드드롭한 블록은 고정 소리가 따로 없다");
		}
	}

	@Test
	void cancellingPendingGarbageClinksOnce() {
		TimedMatch m = new TimedMatch(5, 2, GameModel.Scheduler.NONE, 0);
		MatchSounds s = new MatchSounds();
		GameModel g = m.player(0).game();
		for (int piece = 0; piece < 60; piece++) {
			Placement target = best(g);
			if (linesOf(g, target) > 0) {
				g.receive(4); // 상대 공격 4줄이 막 도착해 있다
				List<Cue> cues = play(m, s, target);
				GameModel.LockResult r = m.lastLock(0);
				int cancelled = r.attack() - r.sent();
				assertTrue(cancelled > 0, "줄을 지운 공격이 받을 줄을 상쇄했다");
				assertEquals(SoundCues.cancel(cancelled), find(cues, Sfx.GARBAGE_CANCEL), "상쇄 소리 한 번 (줄 지우기 조금 뒤)");
				assertFalse(has(cues, Sfx.GARBAGE), "상쇄는 새로 받은 방해 줄이 아니다");
				return;
			}
			List<Cue> cues = play(m, s, target);
			assertFalse(has(cues, Sfx.GARBAGE_CANCEL), "받을 줄이 없으면 상쇄 소리도 없다");
		}
		throw new AssertionError("60 블록 안에 줄을 지우지 못했다 (시험의 탐욕 배치를 고칠 것)");
	}

	@Test
	void b2bStreakCountsConsecutiveDifficultClears() {
		assertEquals(0, MatchSounds.nextStreak(0, false, true, 4), "첫 테트리스: B2B 가 막 시작 (×0)");
		assertEquals(1, MatchSounds.nextStreak(0, true, true, 4), "두 번째: B2B ×1");
		assertEquals(2, MatchSounds.nextStreak(1, true, true, 2), "T-spin 으로 이어감: ×2");
		assertEquals(2, MatchSounds.nextStreak(2, true, true, 0), "줄 없는 고정은 그대로");
		assertEquals(0, MatchSounds.nextStreak(5, true, false, 1), "보통 한 줄로 끊김");
		assertEquals(8, MatchSounds.nextStreak(7, true, true, 4), "×8 부터 가장 화려한 단계");
		assertEquals(2, Sfx.B2B.tier(8));
	}

	@Test
	void aSoftDroppedPieceLandsOnceThenLocks() {
		TimedMatch m = new TimedMatch(7, 1, GameModel.Scheduler.NONE, 1000);
		MatchSounds s = new MatchSounds();
		m.player(0).setSoftDrop(true);
		long landAt = -1;
		long lockAt = -1;
		for (int t = 0; t < 5000 && lockAt < 0; t++) {
			List<Cue> cues = tick(m, s, null, null);
			if (has(cues, Sfx.LAND)) {
				assertEquals(-1, landAt, "닿는 소리는 한 번");
				landAt = m.time();
			}
			if (has(cues, Sfx.LOCK)) {
				assertEquals(List.of(Cue.of(Sfx.LOCK)), cues);
				lockAt = m.time();
			}
		}
		assertTrue(landAt > 0, "바닥에 닿았다");
		long wait = lockAt - landAt;
		assertTrue(wait >= TimedPlayer.LOCK_DELAY_MS - 1 && wait <= TimedPlayer.LOCK_DELAY_MS,
				"닿고 락 딜레이 뒤에 고정 소리: " + wait + "ms");
	}

	@Test
	void soloGarbageWarnsAtOnceAndRumblesWhenItRises() {
		TimedMatch m = new TimedMatch(3, 1, GameModel.Scheduler.S_3, 0);
		MatchSounds s = new MatchSounds();
		List<Cue> tenth = List.of();
		for (int piece = 1; piece <= 10; piece++) {
			spread(m, s, piece);
			List<Cue> cues = tick(m, s, Input.HD, null);
			if (piece < 10) {
				assertFalse(has(cues, Sfx.GARBAGE), piece + " 번째 고정: 아직 방해 줄 없음");
			} else {
				tenth = cues;
			}
		}
		assertEquals(3, m.player(0).game().pendingLines());
		assertTrue(tenth.contains(SoundCues.garbage(3, false)), "10 번째 고정 뒤 3줄: 날아오는 칩이 없으니 바로 경고 " + tenth);
		spread(m, s, 11);
		List<Cue> rise = tick(m, s, Input.HD, null);
		assertEquals(0, m.player(0).game().pendingLines());
		assertTrue(rise.contains(Cue.of(Sfx.GARBAGE_RISE, 3)), "줄을 못 지운 고정에 3줄이 올라온다 " + rise);
		assertFalse(has(rise, Sfx.GARBAGE), "올라온 줄은 새로 받은 줄이 아니다");
		assertFalse(has(rise, Sfx.GARBAGE_CANCEL), "줄을 못 지웠으니 상쇄도 없다");
	}

	/** 블록을 왼쪽·가운데·오른쪽으로 번갈아 보낸다(한 곳에 쌓여 지지 않게). */
	private static void spread(TimedMatch m, MatchSounds s, int piece) {
		Input dir = piece % 3 == 0 ? Input.L : piece % 3 == 1 ? Input.R : null;
		for (int i = 0; dir != null && i < 4; i++) {
			tick(m, s, dir, null);
		}
	}

	@Test
	void gravityLevelUpsAreAnnouncedOnceEach() {
		TimedMatch m = new TimedMatch(5, 1, GameModel.Scheduler.NONE, 1500); // 1500g = 1 단계까지
		MatchSounds s = new MatchSounds();
		List<Long> ups = new ArrayList<>();
		for (int t = 0; t < 2 * TimedMatch.LEVEL_MS + 1000 && !m.isOver(); t++) {
			if (has(tick(m, s, null, null), Sfx.LEVEL_UP)) {
				ups.add(m.time());
			}
		}
		assertFalse(m.isOver(), "블록이 저절로 떨어져 쌓이기만 했다");
		assertEquals(List.of((long) TimedMatch.LEVEL_MS), ups, "30초에 한 번, 상한(1 단계)에 닿은 뒤(60초)에는 없다");
	}

	@Test
	void theAiOpponentIsSilentInVersus() {
		TimedMatch m = new TimedMatch(9, 2, GameModel.Scheduler.NONE, 0);
		MatchSounds s = new MatchSounds();
		assertEquals(List.of(), tick(m, s, null, Input.HD), "AI(P2)의 고정은 조용하다");
		assertEquals(List.of(), tick(m, s, null, Input.L), "AI 의 조작도");
		assertEquals(List.of(Cue.of(Sfx.HARD_DROP, 2)), tick(m, s, Input.HD, Input.HD), "같은 틱의 사람 고정은 들린다");
	}

	@Test
	void aMatchWithoutAHumanOnlyAnnouncesLevels() {
		TimedMatch m = new TimedMatch(9, 2, GameModel.Scheduler.NONE, 1500);
		MatchSounds s = new MatchSounds(false);
		assertEquals(List.of(), tick(m, s, Input.L, Input.R), "조작 소리 없음");
		assertEquals(List.of(), tick(m, s, Input.HD, Input.HD), "고정 소리 없음");
	}

	@Test
	void soundsDoNotChangeTheMatch() {
		// 소리를 붙인 판과 붙이지 않은 판이 같은 입력에 같은 결과를 낸다 (MatchSounds 는 읽기만 한다)
		TimedMatch with = new TimedMatch(21, 2, GameModel.Scheduler.NONE, 3000);
		TimedMatch without = new TimedMatch(21, 2, GameModel.Scheduler.NONE, 3000);
		MatchSounds s = new MatchSounds();
		Input[] script = { Input.L, null, Input.CW, null, Input.R, Input.HD, null, Input.H, Input.CCW, Input.D,
				Input.HD };
		for (int t = 0; t < 20_000 && !with.isOver(); t++) {
			Input p1 = script[t % script.length];
			Input p2 = script[(t * 7 + 3) % script.length];
			tick(with, s, p1, p2);
			without.tick(p1, p2);
		}
		for (int i = 0; i < 2; i++) {
			assertTrue(with.player(i).game().grid().sameOccupancy(without.player(i).game().grid()), "보드가 같다");
			assertEquals(without.player(i).game().pieces(), with.player(i).game().pieces());
			assertEquals(without.player(i).game().pendingLines(), with.player(i).game().pendingLines());
		}
		assertEquals(without.time(), with.time());
		assertEquals(without.result(), with.result());
	}
}
