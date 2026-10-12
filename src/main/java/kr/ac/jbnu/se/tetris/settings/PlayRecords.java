package kr.ac.jbnu.se.tetris.settings;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 플레이 기록: 모드마다 최고 기록과 간단한 통계. 사람 대 AI 는 난이도마다 전적(승·패·무, 연승, 가장 빠른 승리), 혼자 연습은
 * 설정(블록 속도 × 방해 줄)마다 가장 많이 지운 줄과 가장 오래 버틴 시간, 모래 모드는 최고 점수와 가장 높은 콤보 배율이다.
 * <p>
 * 혼자 연습을 설정마다 따로 세는 까닭: "아주 느리게·방해 줄 없음" 은 끝없이 버틸 수 있어서, 하나로 세면 다른 설정에서는
 * 새 기록이 나오지 않는다.
 * <p>
 * 값이 바뀌지 않는 객체이고, 한 판을 더하는 {@code withX} 가 새 객체를 돌려준다. 수는 음수가 되지 않고 넘치지 않는다(최댓값에서
 * 멈춘다). 이름·날짜 같은 개인 정보는 담지 않는다.
 * <p>
 * "새 기록" 표시는 판을 더하기 전의 값과 비교한다({@link Solo#newBestLinesSince} 등). 첫 판은 새 기록으로 치지 않는다.
 * <p>
 * 모래 모드 기록은 지금 모래 규칙 세대({@link #SAND_RULES})의 것만 담는다. 저장 파일이 다른 세대의 모래 기록을 담고 있으면 읽을 때
 * 버리고 새로 시작한다({@code SaveFormat}). 다른 모드의 기록은 그대로다.
 *
 * @param versus 난이도 id({@code AiCatalog.Difficulty#id}) → 전적. 한 판도 하지 않은 난이도는 없다
 * @param solo   혼자 연습 설정 이름({@link #soloSetup}) → 기록. 한 판도 하지 않은 설정은 없다
 * @param sand   모래 모드
 */
public record PlayRecords(SortedMap<Integer, Versus> versus, SortedMap<String, Solo> solo, Sand sand) {

	/** 전적을 남길 수 있는 난이도 id 의 범위 (1 ~ 이 값). */
	public static final int MAX_LEVEL = 99;
	/** 혼자 연습 설정을 몇 개까지 따로 세는가 (메뉴의 속도 6 × 방해 줄 3 = 18 이면 충분하다). */
	public static final int MAX_SOLO_SETUPS = 64;

	/**
	 * 모래 기록의 규칙 세대: 모래 규칙 {@code sand-0.N} 의 N ({@code sand.SandRules.VERSION}, 지금 sand-0.3). 저장 파일에
	 * {@code record.sand.rules} 로 함께 남기고, 세대가 다른 모래 기록(이 값을 적기 전의 sand-0.1·0.2 기록 포함)은 읽을 때 버린다.
	 * 규칙이 바뀌면 같은 점수라도 뜻이 달라서다: sand-0.2 에서는 아무것도 하지 않아도 지지 않아 최고 점수가 끝없이 오를 수 있었다.
	 * 모래 규칙의 지우기·점수·패배가 바뀔 때 올린다.
	 */
	public static final int SAND_RULES = 3;

	/** 혼자 연습 설정 이름: 영어·숫자로 시작하고 영어·숫자·{@code _ -} 로 32자까지 (점은 저장 파일의 구분자라 쓰지 않는다). */
	private static final Pattern SETUP = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,31}");

	/** 사람 대 AI 한 판의 결과 (사람 쪽에서 본). 기권은 {@link #LOSS} 로 남긴다. */
	public enum Outcome {
		WIN, LOSS, DRAW
	}

	/**
	 * 사람 대 AI, 난이도 하나의 전적.
	 *
	 * @param streak       지금 이어지는 연승 (지거나 비기면 0)
	 * @param bestStreak   가장 긴 연승
	 * @param fastestWinMs 가장 빨리 이긴 판의 길이 (ms, 판의 시계). 이긴 적 없으면 0
	 */
	public record Versus(int wins, int losses, int draws, int streak, int bestStreak, long fastestWinMs) {

		public static final Versus NONE = new Versus(0, 0, 0, 0, 0, 0);

		public Versus {
			wins = Math.max(0, wins);
			losses = Math.max(0, losses);
			draws = Math.max(0, draws);
			streak = Math.max(0, streak);
			bestStreak = Math.max(bestStreak, streak);
			fastestWinMs = Math.max(0, fastestWinMs);
		}

		/** 한 판 수. */
		public long games() {
			return (long) wins + losses + draws;
		}

		/** 이긴 비율 (무승부는 반 판), 한 판도 없으면 NaN. */
		public double winRate() {
			long n = games();
			return n == 0 ? Double.NaN : (wins + 0.5 * draws) / n;
		}

		/** 한 판을 더한 전적. {@code timeMs} 는 판의 길이(이겼을 때 가장 빠른 승리에 쓴다, 0 이하면 모름). */
		public Versus plus(Outcome outcome, long timeMs) {
			Objects.requireNonNull(outcome, "outcome");
			return switch (outcome) {
			case WIN -> {
				int s = inc(streak);
				long fastest = timeMs <= 0 ? fastestWinMs
						: fastestWinMs == 0 ? timeMs : Math.min(fastestWinMs, timeMs);
				yield new Versus(inc(wins), losses, draws, s, Math.max(bestStreak, s), fastest);
			}
			case LOSS -> new Versus(wins, inc(losses), draws, 0, bestStreak, fastestWinMs);
			case DRAW -> new Versus(wins, losses, inc(draws), 0, bestStreak, fastestWinMs);
			};
		}

		/** {@code before} 에 판을 더해 이 전적이 됐을 때, 가장 빠른 승리를 새로 세웠는가 (첫 승은 세지 않는다). */
		public boolean newFastestWinSince(Versus before) {
			return before.fastestWinMs > 0 && fastestWinMs > 0 && fastestWinMs < before.fastestWinMs;
		}

		/** {@code before} 에 판을 더해 이 전적이 됐을 때, 가장 긴 연승을 새로 세웠는가 (첫 승은 세지 않는다). */
		public boolean newBestStreakSince(Versus before) {
			return before.bestStreak > 0 && bestStreak > before.bestStreak;
		}
	}

	/**
	 * 혼자 연습, 설정 하나.
	 *
	 * @param bestLines  한 판에 가장 많이 지운 줄
	 * @param bestTimeMs 한 판을 가장 오래 버틴 시간 (ms, 판의 시계)
	 * @param totalLines 모든 판에서 지운 줄
	 */
	public record Solo(int games, int bestLines, long bestTimeMs, long totalLines) {

		public static final Solo NONE = new Solo(0, 0, 0, 0);

		public Solo {
			games = Math.max(0, games);
			bestLines = Math.max(0, bestLines);
			bestTimeMs = Math.max(0, bestTimeMs);
			totalLines = Math.max(0, totalLines);
		}

		/** 한 판(지운 줄, 버틴 시간 ms)을 더한 기록. */
		public Solo plus(int lines, long timeMs) {
			int l = Math.max(0, lines);
			long t = Math.max(0, timeMs);
			return new Solo(inc(games), Math.max(bestLines, l), Math.max(bestTimeMs, t), add(totalLines, l));
		}

		/** {@code before} 에 판을 더해 이 기록이 됐을 때, 줄 수 최고 기록을 새로 세웠는가 (첫 판은 세지 않는다). */
		public boolean newBestLinesSince(Solo before) {
			return before.games > 0 && bestLines > before.bestLines;
		}

		/** {@code before} 에 판을 더해 이 기록이 됐을 때, 버틴 시간 최고 기록을 새로 세웠는가 (첫 판은 세지 않는다). */
		public boolean newBestTimeSince(Solo before) {
			return before.games > 0 && bestTimeMs > before.bestTimeMs;
		}
	}

	/**
	 * 모래 모드 (지금 규칙 세대 {@link #SAND_RULES} 의 판만).
	 *
	 * @param bestScore      한 판의 최고 점수
	 * @param bestMultiplier 가장 높이 오른 콤보 배율 ({@code SandGame#maxMultiplier()}, ×1 ~ ×10)
	 */
	public record Sand(int games, long bestScore, int bestMultiplier) {

		public static final Sand NONE = new Sand(0, 0, 0);

		public Sand {
			games = Math.max(0, games);
			bestScore = Math.max(0, bestScore);
			bestMultiplier = Math.max(0, bestMultiplier);
		}

		/** 한 판(점수, 그 판에서 가장 높이 오른 콤보 배율)을 더한 기록. */
		public Sand plus(long score, int multiplier) {
			return new Sand(inc(games), Math.max(bestScore, Math.max(0, score)),
					Math.max(bestMultiplier, Math.max(0, multiplier)));
		}

		/** {@code before} 에 판을 더해 이 기록이 됐을 때, 최고 점수를 새로 세웠는가 (첫 판은 세지 않는다). */
		public boolean newBestScoreSince(Sand before) {
			return before.games > 0 && bestScore > before.bestScore;
		}
	}

	private static final PlayRecords EMPTY = new PlayRecords(new TreeMap<>(), new TreeMap<>(), Sand.NONE);

	/**
	 * @throws IllegalArgumentException 난이도 id 가 1 ~ {@value #MAX_LEVEL} 밖이거나, 혼자 연습 설정 이름이 규칙
	 *                                  ({@link #validSetup})에 맞지 않거나 {@value #MAX_SOLO_SETUPS}개보다 많으면
	 */
	public PlayRecords {
		Objects.requireNonNull(versus, "versus");
		Objects.requireNonNull(solo, "solo");
		Objects.requireNonNull(sand, "sand");
		TreeMap<Integer, Versus> v = new TreeMap<>();
		for (Map.Entry<Integer, Versus> e : versus.entrySet()) {
			if (!validLevel(e.getKey())) {
				throw new IllegalArgumentException("난이도 id 는 1~" + MAX_LEVEL + ": " + e.getKey());
			}
			if (!Versus.NONE.equals(Objects.requireNonNull(e.getValue(), "versus 값"))) {
				v.put(e.getKey(), e.getValue());
			}
		}
		TreeMap<String, Solo> s = new TreeMap<>();
		for (Map.Entry<String, Solo> e : solo.entrySet()) {
			if (!validSetup(e.getKey())) {
				throw new IllegalArgumentException("혼자 연습 설정 이름으로 쓸 수 없음: " + e.getKey());
			}
			if (!Solo.NONE.equals(Objects.requireNonNull(e.getValue(), "solo 값"))) {
				s.put(e.getKey(), e.getValue());
			}
		}
		if (s.size() > MAX_SOLO_SETUPS) {
			throw new IllegalArgumentException("혼자 연습 설정은 " + MAX_SOLO_SETUPS + "개까지: " + s.size());
		}
		versus = Collections.unmodifiableSortedMap(v);
		solo = Collections.unmodifiableSortedMap(s);
	}

	/** 아무 판도 하지 않은 기록. */
	public static PlayRecords empty() {
		return EMPTY;
	}

	/** 전적을 남길 수 있는 난이도 id 인가. */
	public static boolean validLevel(Integer level) {
		return level != null && level >= 1 && level <= MAX_LEVEL;
	}

	/** 혼자 연습 설정 이름으로 쓸 수 있는가 (영어·숫자로 시작, 영어·숫자·{@code _ -}, 32자까지). */
	public static boolean validSetup(String setup) {
		return setup != null && SETUP.matcher(setup).matches();
	}

	/**
	 * 혼자 연습 설정 이름: 중력 상한과 방해 줄. 예: 초당 3칸, {@code S-mix} → {@code "3000_S-mix"}.
	 *
	 * @param gravityCapG 중력 상한 g (초당 칸 수 × 1000, {@code GameSession.Settings#gravityCapG})
	 * @param garbage     방해 줄 스케줄러 이름 ({@code GameModel.Scheduler#json()}: {@code none}, {@code S-3}, {@code S-mix})
	 * @throws IllegalArgumentException 중력이 음수이거나 방해 줄 이름에 쓸 수 없는 글자가 있으면
	 */
	public static String soloSetup(int gravityCapG, String garbage) {
		if (gravityCapG < 0) {
			throw new IllegalArgumentException("중력 상한이 음수: " + gravityCapG);
		}
		String s = gravityCapG + "_" + garbage;
		if (!validSetup(s)) {
			throw new IllegalArgumentException("혼자 연습 설정 이름으로 쓸 수 없음: " + s);
		}
		return s;
	}

	/** 난이도 {@code level} 의 전적. 한 판도 없으면 {@link Versus#NONE}. */
	public Versus versus(int level) {
		return versus.getOrDefault(level, Versus.NONE);
	}

	/** 모든 난이도를 합한 전적 (연승과 가장 빠른 승리는 난이도 중 가장 좋은 값, 지금 연승은 0). */
	public Versus versusTotal() {
		int w = 0;
		int l = 0;
		int d = 0;
		int best = 0;
		long fastest = 0;
		for (Versus v : versus.values()) {
			w = sum(w, v.wins());
			l = sum(l, v.losses());
			d = sum(d, v.draws());
			best = Math.max(best, v.bestStreak());
			if (v.fastestWinMs() > 0) {
				fastest = fastest == 0 ? v.fastestWinMs() : Math.min(fastest, v.fastestWinMs());
			}
		}
		return new Versus(w, l, d, 0, best, fastest);
	}

	/** 혼자 연습 설정 {@code setup}({@link #soloSetup})의 기록. 한 판도 없으면 {@link Solo#NONE}. */
	public Solo solo(String setup) {
		return solo.getOrDefault(setup, Solo.NONE);
	}

	/** 모든 설정을 합한 혼자 연습 기록 (판 수와 지운 줄은 더하고, 최고 기록은 설정 중 가장 좋은 값). */
	public Solo soloTotal() {
		int games = 0;
		int bestLines = 0;
		long bestTime = 0;
		long total = 0;
		for (Solo s : solo.values()) {
			games = sum(games, s.games());
			bestLines = Math.max(bestLines, s.bestLines());
			bestTime = Math.max(bestTime, s.bestTimeMs());
			total = add(total, s.totalLines());
		}
		return new Solo(games, bestLines, bestTime, total);
	}

	/**
	 * 사람 대 AI 한 판을 더한 기록.
	 *
	 * @param level  난이도 id (1 ~ {@value #MAX_LEVEL})
	 * @param timeMs 판의 길이 (ms, 판의 시계)
	 * @throws IllegalArgumentException 난이도 id 가 범위 밖이면
	 */
	public PlayRecords withVersus(int level, Outcome outcome, long timeMs) {
		if (!validLevel(level)) {
			throw new IllegalArgumentException("난이도 id 는 1~" + MAX_LEVEL + ": " + level);
		}
		TreeMap<Integer, Versus> next = new TreeMap<>(versus);
		next.put(level, versus(level).plus(outcome, timeMs));
		return new PlayRecords(next, solo, sand);
	}

	/**
	 * 혼자 연습 한 판을 더한 기록. 설정이 이미 {@value #MAX_SOLO_SETUPS}개면 새 설정은 남기지 않는다(그대로 돌려준다).
	 *
	 * @param setup  설정 이름 ({@link #soloSetup})
	 * @param lines  지운 줄
	 * @param timeMs 버틴 시간 (ms, 판의 시계)
	 * @throws IllegalArgumentException 설정 이름을 쓸 수 없으면
	 */
	public PlayRecords withSolo(String setup, int lines, long timeMs) {
		if (!validSetup(setup)) {
			throw new IllegalArgumentException("혼자 연습 설정 이름으로 쓸 수 없음: " + setup);
		}
		if (!solo.containsKey(setup) && solo.size() >= MAX_SOLO_SETUPS) {
			return this;
		}
		TreeMap<String, Solo> next = new TreeMap<>(solo);
		next.put(setup, solo(setup).plus(lines, timeMs));
		return new PlayRecords(versus, next, sand);
	}

	/** 모래 모드 한 판(점수, 그 판에서 가장 높이 오른 콤보 배율)을 더한 기록. */
	public PlayRecords withSand(long score, int multiplier) {
		return new PlayRecords(versus, solo, sand.plus(score, multiplier));
	}

	static int inc(int v) {
		return v == Integer.MAX_VALUE ? v : v + 1;
	}

	static int sum(int a, int b) {
		long s = (long) a + b;
		return s > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) s;
	}

	static long add(long a, long b) {
		long s = a + b;
		return ((a ^ s) & (b ^ s)) < 0 ? Long.MAX_VALUE : s;
	}
}
