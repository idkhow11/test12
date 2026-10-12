package kr.ac.jbnu.se.tetris.settings;

import static kr.ac.jbnu.se.tetris.settings.PlayRecords.Outcome.DRAW;
import static kr.ac.jbnu.se.tetris.settings.PlayRecords.Outcome.LOSS;
import static kr.ac.jbnu.se.tetris.settings.PlayRecords.Outcome.WIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.settings.PlayRecords.Sand;
import kr.ac.jbnu.se.tetris.settings.PlayRecords.Solo;
import kr.ac.jbnu.se.tetris.settings.PlayRecords.Versus;

/** 플레이 기록: 난이도마다 전적, 혼자 연습(설정마다)과 모래 모드의 최고 기록. */
class PlayRecordsTest {

	/** 혼자 연습 "보통(초당 3칸)·섞어서" 설정. */
	private static final String NORMAL_MIX = PlayRecords.soloSetup(3000, "S-mix");

	@Test
	void emptyRecordsHaveNothing() {
		PlayRecords r = PlayRecords.empty();
		assertEquals(Versus.NONE, r.versus(3));
		assertTrue(r.versus().isEmpty());
		assertEquals(Solo.NONE, r.solo(NORMAL_MIX));
		assertTrue(r.solo().isEmpty());
		assertEquals(Solo.NONE, r.soloTotal());
		assertEquals(Sand.NONE, r.sand());
		assertTrue(Double.isNaN(Versus.NONE.winRate()));
	}

	@Test
	void versusCountsWinsStreaksAndTheFastestWin() {
		// 승 90초, 승 60초, 패, 승 75초, 무, 승 80초, 승 70초, 승 65초
		// 승 6, 패 1, 무 1. 연승: 1 2 0 1 0 1 2 3 → 지금 3, 가장 긴 3. 가장 빠른 승리: 60초
		PlayRecords r = PlayRecords.empty().withVersus(2, WIN, 90_000).withVersus(2, WIN, 60_000)
				.withVersus(2, LOSS, 40_000).withVersus(2, WIN, 75_000).withVersus(2, DRAW, 120_000)
				.withVersus(2, WIN, 80_000).withVersus(2, WIN, 70_000).withVersus(2, WIN, 65_000);
		Versus v = r.versus(2);
		assertEquals(new Versus(6, 1, 1, 3, 3, 60_000), v);
		assertEquals(8, v.games());
		assertEquals((6 + 0.5) / 8, v.winRate(), 1e-12, "무승부는 반 판");
		assertEquals(Versus.NONE, r.versus(3), "다른 난이도는 따로 센다");
		assertEquals(List.of(2), List.copyOf(r.versus().keySet()));
	}

	@Test
	void newRecordFlagsCompareWithTheRecordBeforeTheGame() {
		Versus first = Versus.NONE.plus(WIN, 90_000);
		assertFalse(first.newFastestWinSince(Versus.NONE), "첫 승은 새 기록으로 치지 않는다");
		assertFalse(first.newBestStreakSince(Versus.NONE));
		Versus second = first.plus(WIN, 80_000);
		assertTrue(second.newFastestWinSince(first), "90초 → 80초");
		assertTrue(second.newBestStreakSince(first), "연승 1 → 2");
		Versus third = second.plus(WIN, 85_000);
		assertFalse(third.newFastestWinSince(second), "85초는 80초보다 느리다");
		assertTrue(third.newBestStreakSince(second));
		Versus lost = third.plus(LOSS, 30_000);
		assertEquals(0, lost.streak());
		assertEquals(3, lost.bestStreak());
		assertEquals(80_000, lost.fastestWinMs(), "진 판의 길이는 가장 빠른 승리가 아니다");
		assertFalse(lost.newBestStreakSince(third));
		assertEquals(80_000, lost.plus(WIN, 0).fastestWinMs(), "길이를 모르는 승리(0)는 가장 빠른 승리를 바꾸지 않는다");
	}

	@Test
	void soloKeepsBestLinesBestTimeAndTotals() {
		// 40줄 120초, 25줄 150초, 60줄 90초 → 판 3, 최고 60줄, 최장 150초, 합 125줄
		Solo s = Solo.NONE.plus(40, 120_000).plus(25, 150_000).plus(60, 90_000);
		assertEquals(new Solo(3, 60, 150_000, 125), s);
		Solo before = Solo.NONE.plus(40, 120_000);
		Solo after = before.plus(60, 90_000);
		assertTrue(after.newBestLinesSince(before));
		assertFalse(after.newBestTimeSince(before));
		assertFalse(before.newBestLinesSince(Solo.NONE), "첫 판은 새 기록으로 치지 않는다");
		assertEquals(new Solo(1, 0, 0, 0), Solo.NONE.plus(-5, -10), "음수는 0 으로");
		assertEquals(s, PlayRecords.empty().withSolo(NORMAL_MIX, 40, 120_000).withSolo(NORMAL_MIX, 25, 150_000)
				.withSolo(NORMAL_MIX, 60, 90_000).solo(NORMAL_MIX));
	}

	@Test
	void soloSetupNamesComeFromSpeedAndGarbage() {
		assertEquals("3000_S-mix", PlayRecords.soloSetup(3000, "S-mix"));
		assertEquals("12000_none", PlayRecords.soloSetup(12_000, "none"));
		assertEquals("1000_S-3", PlayRecords.soloSetup(1000, "S-3"));
		assertThrows(IllegalArgumentException.class, () -> PlayRecords.soloSetup(-1, "none"));
		assertThrows(IllegalArgumentException.class, () -> PlayRecords.soloSetup(3000, "a.b"), "점은 저장 파일의 구분자");
		assertThrows(IllegalArgumentException.class, () -> PlayRecords.soloSetup(3000, "섞어서"));
		assertTrue(PlayRecords.validSetup("all"));
		for (String bad : new String[] { "", "_x", "-x", "a b", "x".repeat(33), null }) {
			assertFalse(PlayRecords.validSetup(bad), String.valueOf(bad));
		}
	}

	@Test
	void eachSoloSetupKeepsItsOwnBest() {
		// 아주 느리게·방해 줄 없음은 오래 버틸 수 있다. 그 기록이 빠른 설정의 새 기록을 막지 않는다
		String easy = PlayRecords.soloSetup(1000, "none");
		String fast = PlayRecords.soloSetup(12_000, "S-mix");
		PlayRecords r = PlayRecords.empty().withSolo(easy, 300, 900_000).withSolo(fast, 20, 60_000);
		PlayRecords after = r.withSolo(fast, 25, 50_000);
		assertTrue(after.solo(fast).newBestLinesSince(r.solo(fast)), "20줄 → 25줄");
		assertFalse(after.solo(fast).newBestTimeSince(r.solo(fast)));
		assertEquals(new Solo(1, 300, 900_000, 300), after.solo(easy), "다른 설정은 그대로");
		// 모든 설정을 합하면: 판 3, 최고 300줄, 최장 900초, 합 345줄
		assertEquals(new Solo(3, 300, 900_000, 345), after.soloTotal());
		assertThrows(IllegalArgumentException.class, () -> r.withSolo("bad name", 1, 1));
	}

	@Test
	void soloSetupsHaveALimit() {
		PlayRecords r = PlayRecords.empty();
		for (int i = 0; i < PlayRecords.MAX_SOLO_SETUPS; i++) {
			r = r.withSolo("s" + i, 1, 1);
		}
		assertEquals(PlayRecords.MAX_SOLO_SETUPS, r.solo().size());
		assertSame(r, r.withSolo("one-more", 5, 5), "새 설정은 더 남기지 않는다");
		assertEquals(2, r.withSolo("s0", 5, 5).solo("s0").games(), "있는 설정은 계속 센다");
		TreeMap<String, Solo> many = new TreeMap<>();
		for (int i = 0; i <= PlayRecords.MAX_SOLO_SETUPS; i++) {
			many.put("s" + i, Solo.NONE.plus(1, 1));
		}
		assertThrows(IllegalArgumentException.class, () -> new PlayRecords(new TreeMap<>(), many, Sand.NONE));
	}

	@Test
	void sandKeepsBestScoreAndMultiplier() {
		// 1,200점 ×3, 4,500점 ×2, 800점 ×5 → 판 3, 최고 4,500점, 최고 배율 ×5
		PlayRecords r = PlayRecords.empty().withSand(1_200, 3).withSand(4_500, 2).withSand(800, 5);
		assertEquals(new Sand(3, 4_500, 5), r.sand());
		Sand before = new Sand(3, 4_500, 5);
		assertTrue(before.plus(5_000, 1).newBestScoreSince(before));
		assertFalse(before.plus(4_500, 1).newBestScoreSince(before), "같은 점수는 새 기록이 아니다");
		assertFalse(Sand.NONE.plus(100, 1).newBestScoreSince(Sand.NONE), "첫 판");
	}

	@Test
	void countersStopAtTheirMaximum() {
		Versus v = new Versus(Integer.MAX_VALUE, 0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 0).plus(WIN, 1);
		assertEquals(Integer.MAX_VALUE, v.wins());
		assertEquals(Integer.MAX_VALUE, v.streak());
		Solo s = new Solo(Integer.MAX_VALUE, 1, 1, Long.MAX_VALUE - 1).plus(5, 1);
		assertEquals(Integer.MAX_VALUE, s.games());
		assertEquals(Long.MAX_VALUE, s.totalLines());
		Versus big = new Versus(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 0);
		assertEquals(3L * Integer.MAX_VALUE, big.games(), "판 수는 long 으로 더한다");
	}

	@Test
	void handEditedNonsenseIsCleanedUp() {
		assertEquals(new Versus(0, 0, 0, 5, 5, 0), new Versus(-1, -2, -3, 5, 2, -7), "연승이 가장 긴 연승보다 길 수 없다");
		assertEquals(Solo.NONE, new Solo(-1, -1, -1, -1));
		assertEquals(Sand.NONE, new Sand(-1, -1, -1));
	}

	@Test
	void levelsOutsideTheRangeAreRejected() {
		PlayRecords r = PlayRecords.empty();
		assertThrows(IllegalArgumentException.class, () -> r.withVersus(0, WIN, 1));
		assertThrows(IllegalArgumentException.class, () -> r.withVersus(PlayRecords.MAX_LEVEL + 1, WIN, 1));
		assertEquals(1, r.withVersus(PlayRecords.MAX_LEVEL, WIN, 1).versus(PlayRecords.MAX_LEVEL).wins());
		TreeMap<Integer, Versus> bad = new TreeMap<>();
		bad.put(-1, Versus.NONE.plus(WIN, 1));
		assertThrows(IllegalArgumentException.class, () -> new PlayRecords(bad, new TreeMap<>(), Sand.NONE));
		TreeMap<String, Solo> badSetup = new TreeMap<>();
		badSetup.put("a.b", Solo.NONE.plus(1, 1));
		assertThrows(IllegalArgumentException.class, () -> new PlayRecords(new TreeMap<>(), badSetup, Sand.NONE));
	}

	@Test
	void versusTotalAddsUpTheLevels() {
		PlayRecords r = PlayRecords.empty().withVersus(1, WIN, 50_000).withVersus(1, WIN, 70_000)
				.withVersus(4, LOSS, 30_000).withVersus(4, WIN, 45_000).withVersus(5, DRAW, 90_000);
		// 1단계 승 2 (연승 2, 최단 50초), 4단계 패 1 승 1 (최단 45초), 5단계 무 1 → 합 승 3 패 1 무 1, 가장 긴 연승 2, 최단 45초
		assertEquals(new Versus(3, 1, 1, 0, 2, 45_000), r.versusTotal());
		assertEquals(Versus.NONE, PlayRecords.empty().versusTotal());
	}

	@Test
	void recordsAreValues() {
		PlayRecords a = PlayRecords.empty().withVersus(3, WIN, 1_000).withSolo(NORMAL_MIX, 10, 2_000);
		PlayRecords b = PlayRecords.empty().withSolo(NORMAL_MIX, 10, 2_000).withVersus(3, WIN, 1_000);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertThrows(UnsupportedOperationException.class, () -> a.versus().put(4, Versus.NONE));
		assertThrows(UnsupportedOperationException.class, () -> a.solo().put("x", Solo.NONE));
		// 한 판도 없는 난이도·설정은 담지 않는다
		TreeMap<Integer, Versus> withNone = new TreeMap<>();
		withNone.put(2, Versus.NONE);
		TreeMap<String, Solo> soloNone = new TreeMap<>();
		soloNone.put(NORMAL_MIX, Solo.NONE);
		assertEquals(PlayRecords.empty(), new PlayRecords(withNone, soloNone, Sand.NONE));
	}
}
