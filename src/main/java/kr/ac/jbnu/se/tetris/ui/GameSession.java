package kr.ac.jbnu.se.tetris.ui;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.node.ObjectNode;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.ai.Opponent;
import kr.ac.jbnu.se.tetris.ai.PlayerState;
import kr.ac.jbnu.se.tetris.ai.WinRate;
import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.MatchSounds;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.audio.SoundCues;
import kr.ac.jbnu.se.tetris.core.Attack;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.Spin;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;
import kr.ac.jbnu.se.tetris.train.TimedRecorder;

/**
 * 한 판을 실제 시간으로 진행한다 (docs/GAME.md 2, 3장). 판은 두 가지다: 사람(P1) 대 AI(P2), 혼자 연습.
 * <p>
 * <b>스레드.</b> 게임 진행 스레드 하나가 흐른 시간만큼 {@link TimedMatch} 를 1ms 씩 돌린다. 판의 상태를 바꾸는 것은 이
 * 스레드뿐이고, 바꾸는 동안(한 틱에 몇 마이크로초)만 {@link #lock} 을 잡는다. 화면은 {@link #lock} 을 잡고 읽어 그린다.
 * 오래 걸리는 일(AI 의 배치 고르기, 승률 예측, 기록 만들기와 저장)은 잠금 밖에서 하므로 화면과 키 입력이 기다리지 않는다.
 * 진행 스레드는 잠금 밖에서도 판을 읽을 수 있다(쓰는 스레드가 자기뿐이다). 다만 읽기처럼 보이는 {@link GameModel#preview()}
 * 가 가방을 채우며 상태를 바꿀 수 있으므로, 틱마다 잠금 안에서 미리 채워 둔다({@link #fill()}). 그러면 잠금 밖의 읽기와
 * 화면의 읽기가 아무것도 바꾸지 않는다. 잠금은 하나뿐이고, 잠금을 잡은 채 다른 스레드를 기다리는 곳이 없다.
 * <p>
 * <b>결정론.</b> 같은 시드, 같은 사람 입력(틱 번호와 함께)이면 같은 판이다. AI 가 생각 지연 동안 일꾼 스레드에서 미리
 * 고르는 것({@link AiController#prefetchWith})도 고르는 답을 바꾸지 않는다. 판 도중 모델이 오류를 내면 그 AI 는 그 틱에
 * 기준 AI 로 바뀌어 계속 두고(화면에 잠시 알린다), 예상하지 못한 오류는 판을 멈추고 오류 카드를 띄운다(조용히 멈추지 않는다).
 * <p>
 * <b>화면 효과용 사건.</b> 틱마다 잠금 안에서 블록 고정({@link LockFx}), 줄 지우기({@link ClearFx}), 받은 방해 줄
 * ({@link ReceiveFx}), 상쇄({@link CancelFx})를 플레이어마다 최근 {@value #FX_KEEP}개씩 적어 둔다(quality SPEC 1.4). 판의
 * 규칙, 시간, 난수에는 손대지 않고 읽기만 한다. 화면은 같은 잠금을 잡고 바뀌지 않는 목록으로 읽는다.
 * <p>
 * <b>소리.</b> 틱마다 잠금 안에서 틱 앞뒤를 비교해({@link MatchSounds}) 사람(P1)의 조작·고정·줄 지우기·방해 줄 소리를
 * 고르고, 잠금 밖에서 낸다. AI 의 조작과 줄 지우기는 소리가 없다. 판이 끝나면 한 번 결과 소리(K.O., 승리·패배, 새 기록,
 * {@link SoundCues#result})를 낸다. 기권하거나 오류로 멈춘 판, 떠난 판({@link #stop()} 뒤)은 소리를 내지 않는다.
 * 일시정지·카운트다운·위험 소리는 화면({@link GamePanel})이 낸다.
 * <p>
 * <b>모델.</b> AI 의 두뇌와 승률 예측망을 아직 불러오지 않았으면 화면 스레드(판을 만드는 곳)에서 기다리지 않고, 게임 진행
 * 스레드가 카운트다운 동안 불러온다({@link #aiLoading()}). 카운트다운이 끝날 때까지 다 불러오지 못하면 카운트다운을 1 에서
 * 멈춰 둔다(판의 시계가 가지 않는다). 모델을 쓸 수 없으면 기준 AI 가 대신 두고 그 짧은 이유를 보인다({@link #aiStatus()}).
 * <p>
 * <b>기록.</b> 판이 끝나면(또는 기권하면) 한 번, 판의 잠금 밖에서 플레이 기록({@link SaveData#updateRecords})에 더한다:
 * 사람 대 AI 는 난이도마다 승·패·무, 혼자 연습은 설정마다 지운 줄과 버틴 시간. 사람 대 AI 판은 기록 폴더를 켰을 때만
 * ({@link #recordsDir()}: 게임은 {@code TetrisApp.main} 이 {@code runs/human/data/games} 로 켜고, 시험·도구는 끈다) 그
 * 다음에 승률 예측망 자료({@link #saveRecord()})로도 남긴다. 그 파일은 임시 파일에 다 쓴 뒤 이름을 바꿔 끼우고, 진행 스레드를
 * 끼어들기로 깨우지 않으므로 판을 떠나도 반쯤 쓴 기록이 남지 않는다. 판이 끝난 뒤의 처리를 마치면 진행 스레드와 미리 고르기
 * 일꾼은 스스로 끝난다.
 */
final class GameSession {

	/** 시작 전 카운트다운 (3, 2, 1). 판의 시계는 그 뒤부터 간다. */
	static final int COUNTDOWN_MS = 3000;
	/** 시작한 뒤 "GO!" 를 보여 주는 시간. */
	static final int GO_MS = 700;
	/** 일시정지를 풀면 이만큼 카운트다운(2, 1)한 뒤 다시 간다. `[초안]` */
	static final int RESUME_MS = 2000;
	/** 판 도중 AI 가 기준 AI 로 바뀌었다는 알림을 보이는 시간 (판의 시계). */
	static final int NOTICE_MS = 3000;
	/** 플레이어마다 남겨 두는 화면 효과용 사건의 수 (종류마다). */
	static final int FX_KEEP = 4;
	/** {@link #overAtNanos()}: 아직 끝나지 않음. */
	static final long NOT_OVER = Long.MIN_VALUE;
	/** {@link #stop()}: 판이 끝난 뒤의 처리(기록 쓰기)가 진행 중이면 그것이 끝나기를 더 기다리는 시간 (ms). */
	static final int CONCLUDE_WAIT_MS = 10_000;

	/** 판의 종류. */
	enum Mode {
		/** 사람(P1) 대 AI(P2). */
		VERSUS,
		/** 혼자 연습: 사람 한 명과 방해 줄 스케줄러. */
		SOLO
	}

	/**
	 * AI 자리: 난이도와 페르소나. 페르소나를 고를 수 없는 단계(1~3단계)이거나 {@code persona} 가 null 이면 중립을 쓴다
	 * (GAME.md 6.2).
	 */
	record Seat(AiCatalog.Difficulty difficulty, AiCatalog.Persona persona) {
		Seat {
			Objects.requireNonNull(difficulty, "difficulty");
		}
	}

	/**
	 * 판 설정. 보통은 {@link #versus}, {@link #solo} 로 만든다.
	 *
	 * @param mode        판의 종류
	 * @param seat        P2 AI (사람 대 AI 에는 꼭 있고, 혼자 연습에서는 쓰지 않는다)
	 * @param gravityCapG 혼자 연습의 중력 상한 g. 사람 대 AI 는 AI 의 난이도로 정한다
	 * @param scheduler   혼자 연습의 방해 줄 스케줄러 (사람 대 AI 는 언제나 없음)
	 * @param aiSpeed     AI 속도 (%, 1 이상, 게임은 50~150). 사람 대 AI 의 AI 가 이 속도로 둔다
	 *                    ({@link AiCatalog.Difficulty#atSpeed}). 혼자 연습에서는 쓰지 않는다
	 * @param seed        정한 시드 ("같은 판 다시"), 판마다 새 시드면 null
	 */
	record Settings(Mode mode, Seat seat, int gravityCapG, GameModel.Scheduler scheduler, int aiSpeed, Long seed) {

		Settings {
			Objects.requireNonNull(mode, "mode");
			if (mode == Mode.VERSUS && seat == null) {
				throw new IllegalArgumentException("사람 대 AI 판에 AI 자리가 빠졌음");
			}
			if (aiSpeed < 1) {
				throw new IllegalArgumentException("AI 속도는 1% 이상: " + aiSpeed);
			}
			if (scheduler == null) {
				scheduler = GameModel.Scheduler.NONE;
			}
		}

		/** 사람(P1) 대 AI(P2), AI 속도 100%. 중력 상한은 AI 의 난이도로 정한다. */
		static Settings versus(AiCatalog.Difficulty difficulty, AiCatalog.Persona persona) {
			return versus(difficulty, persona, UserSettings.DEFAULT_AI_SPEED);
		}

		/** 사람(P1) 대 AI(P2), AI 속도 {@code aiSpeed}%. */
		static Settings versus(AiCatalog.Difficulty difficulty, AiCatalog.Persona persona, int aiSpeed) {
			return new Settings(Mode.VERSUS, new Seat(difficulty, persona), 0, GameModel.Scheduler.NONE, aiSpeed, null);
		}

		/** 혼자 연습: 중력 상한 g(초당 칸 수 × 1000)와 방해 줄 스케줄러. */
		static Settings solo(int gravityCapG, GameModel.Scheduler scheduler) {
			return new Settings(Mode.SOLO, null, gravityCapG, scheduler, UserSettings.DEFAULT_AI_SPEED, null);
		}

		/** 같은 설정에 시드를 정한 것 ("같은 판 다시": 지난 판의 {@link GameSession#seed}). */
		Settings withSeed(long seed) {
			return new Settings(mode, seat, gravityCapG, scheduler, aiSpeed, seed);
		}

		/** 같은 설정에 판마다 새 시드 ("다시 하기"). */
		Settings withNewSeed() {
			return seed == null ? this : new Settings(mode, seat, gravityCapG, scheduler, aiSpeed, null);
		}

		/** 같은 설정에 AI 속도만 바꾼 것. */
		Settings withAiSpeed(int percent) {
			return new Settings(mode, seat, gravityCapG, scheduler, percent, seed);
		}

		/** 정한 시드가 있으면 그것, 없으면 {@code fresh}. */
		long seedOr(long fresh) {
			return seed != null ? seed : fresh;
		}

		/** 사람 대 AI 판인가. */
		boolean versusAi() {
			return mode == Mode.VERSUS;
		}

		/** P2 AI 의 난이도 (모델 묶음의 값 그대로). 혼자 연습이면 null. */
		AiCatalog.Difficulty difficulty() {
			return seat == null ? null : seat.difficulty();
		}

		/** P2 AI 가 실제로 두는 조작 속도: 난이도를 AI 속도로 바꾼 것. 혼자 연습이면 null. */
		AiCatalog.Difficulty aiDifficulty() {
			return seat == null ? null : seat.difficulty().atSpeed(aiSpeed);
		}

		/** P2 AI 의 페르소나. 혼자 연습이면 null. */
		AiCatalog.Persona persona() {
			return seat == null ? null : seat.persona();
		}

		/** 자리 i(0 = P1)를 두는 AI, 사람이 두면 null. */
		Seat ai(int i) {
			return mode == Mode.VERSUS && i == 1 ? seat : null;
		}

		int players() {
			return mode == Mode.SOLO ? 1 : 2;
		}
	}

	// ------------------------------------------------------------------ 화면 효과용 사건 (quality SPEC 1.4 V3)

	/**
	 * 블록 하나가 고정됐다. 배열은 만든 뒤 바뀌지 않고, 읽을 때마다 복사본을 준다.
	 *
	 * @param at       판 시각 (ms)
	 * @param cells    고정한 칸의 최종 자리 {행, 열}: 줄을 지우고 위가 내려온 뒤, 또는 방해 줄이 오른 뒤의 보드 행(0 = 맨 위
	 *                 숨김 행, 화면 행 = 행 − {@code Grid.HIDDEN_ROWS}). 지운 줄에 있던 칸은 빠진다(0~4개). 방해 줄 아웃으로
	 *                 판이 끝난 고정만은 오르다 만 줄을 빼지 못해 놓은 자리 그대로다
	 * @param code     블록의 칸 값 ({@code PieceType.code()}, 1~7)
	 * @param hardDrop 하드드롭으로 고정했는가
	 * @param dropRows 하드드롭으로 내려온 줄 수 (하드드롭이 아니면 0)
	 * @param landed   블록이 내려앉은 자리 {행, 열} 4칸: 줄을 지우기 전의 행에서 오른 방해 줄만큼 올린 것. 지운 줄의 칸도
	 *                 남으므로 칸이 모두 지워진 고정(테트리스 등)에도 하드드롭 꼬리를 그릴 수 있다. 방해 줄 아웃으로 끝난 고정은
	 *                 {@code cells} 처럼 놓은 자리 그대로다
	 */
	record LockFx(long at, int[][] cells, int code, boolean hardDrop, int dropRows, int[][] landed) {
		@Override
		public int[][] cells() {
			return copy(cells);
		}

		@Override
		public int[][] landed() {
			return copy(landed);
		}
	}

	/**
	 * 줄을 지웠다.
	 *
	 * @param at           판 시각 (ms)
	 * @param rows         지운 줄의 보드 행 (지우기 전, 오름차순. 화면 행 = 행 − {@code Grid.HIDDEN_ROWS})
	 * @param codes        지운 줄마다 지우기 직전의 칸 값 {@code [줄][열 10개]} (rows 와 같은 차례)
	 * @param lines        지운 줄 수 (1~4)
	 * @param spin         T-spin 판정
	 * @param perfectClear 퍼펙트 클리어
	 * @param b2bStreak    이 지우기까지 이어진 B2B 횟수 (B2B 가 아니면 0, B2B 로 이어진 첫 지우기가 1)
	 * @param combo        이 지우기 뒤의 콤보 (첫 지우기는 0)
	 */
	record ClearFx(long at, int[] rows, int[][] codes, int lines, Spin spin, boolean perfectClear, int b2bStreak,
			int combo) {
		@Override
		public int[] rows() {
			return rows.clone();
		}

		@Override
		public int[][] codes() {
			return copy(codes);
		}
	}

	/** 방해 줄을 새로 받았다 (받을 줄 막대에 쌓임). 대전은 상대가 보낸 공격, 혼자 연습은 스케줄러. */
	record ReceiveFx(long at, int lines) {
	}

	/** 내 공격으로 받을 방해 줄을 상쇄했다. */
	record CancelFx(long at, int lines) {
	}

	/**
	 * 결과 카드에 쓸 플레이어 하나의 통계. {@link GameSession#stats} 가 잠금 안에서 만든다.
	 *
	 * @param timeMs          판 시간 (ms)
	 * @param pieces          고정한 블록 수
	 * @param pps             초당 블록 수
	 * @param attack          공격 (상쇄 전) 합
	 * @param sent            상대에게 보낸 줄 합 (혼자 연습은 실제로 보내지 않지만 센다)
	 * @param apm             분당 공격
	 * @param lines           지운 줄
	 * @param maxCombo        최대 콤보 (없으면 0)
	 * @param tspins          줄을 지운 T-spin 횟수
	 * @param quads           4줄 지우기(테트리스) 횟수
	 * @param maxB2bStreak    가장 길게 이어진 B2B 횟수
	 * @param garbageReceived 받은 방해 줄 (받을 줄 막대에 쌓인 줄의 합, 상쇄한 것 포함)
	 */
	record PlayerStats(long timeMs, int pieces, double pps, int attack, int sent, double apm, int lines, int maxCombo,
			int tspins, int quads, int maxB2bStreak, int garbageReceived) {
	}

	/**
	 * AI 모델의 상태 (화면 표시용).
	 *
	 * @param fallback 학습한 모델 대신 기준 AI 가 두는가
	 * @param reason   그 짧은 한국어 이유, 아니면 빈 문자열
	 */
	record AiStatus(boolean fallback, String reason) {
		static final AiStatus OK = new AiStatus(false, "");
	}

	/** 플레이어 하나의 화면용 통계와 사건 (GameModel 이 세지 않는 것). 진행 스레드가 lock 을 잡고 쓰고, 화면이 lock 을 잡고 읽는다. */
	static final class Tally {
		int tspins;
		int tetrises;
		int maxCombo = -1;
		int perfectClears;
		/** 지금 이어지는 B2B 횟수 ({@link ClearFx#b2bStreak()}). */
		int b2bStreak;
		int maxB2bStreak;
		/** 받은 방해 줄의 합. */
		int garbageReceived;
		String lastAction = "";
		long lastActionAt = -10_000;
		private boolean b2bBefore; // 이번 고정 전의 B2B 상태
		private int comboBefore = -1; // 이번 고정 전의 콤보

		// 마지막으로 줄을 지운 고정 (화면의 동작 기록: "2줄 → 공격 3 · 기본 2 + 콤보 1")
		long clearAt = -10_000;
		int clearLines;
		Spin clearSpin = Spin.NONE;
		int clearAttack;
		int clearSent;
		int clearCombo = -1;
		int[] clearParts = new int[4]; // 기본, B2B, 콤보, 퍼펙트 클리어 (Attack.breakdown)
		// 마지막으로 상대에게 보낸 공격 (날아가는 "+N" 표시)
		long sentAt = -10_000;
		int sentLines;
		// 마지막으로 방해 줄이 올라온 때
		long risenAt = -10_000;
		int risenLines;

		// 화면 효과용 사건: 최근 FX_KEEP 개, 오래된 것부터. 바뀔 때마다 새 목록으로 바꾸므로 읽는 쪽은 복사하지 않아도 된다.
		private List<LockFx> locks = List.of();
		private List<ClearFx> clears = List.of();
		private List<ReceiveFx> receives = List.of();
		private List<CancelFx> cancels = List.of();

		/**
		 * 고정 하나를 더한다.
		 *
		 * @param dropFrom 하드드롭이면 고정 전 블록의 맨 위 칸의 행, 아니면 -1
		 */
		void add(GameModel.LockResult r, GameModel g, long time, int dropFrom) {
			int[] parts = Attack.breakdown(comboBefore, b2bBefore, r.lines(), r.spin(), r.perfectClear());
			boolean b2bContinued = b2bBefore && g.b2b() && r.lines() > 0; // B2B 대상이 이어졌다 (RULES 9.3)
			if (r.lines() > 0) {
				b2bStreak = b2bContinued ? b2bStreak + 1 : 0;
				maxB2bStreak = Math.max(maxB2bStreak, b2bStreak);
				clearAt = time;
				clearLines = r.lines();
				clearSpin = r.spin();
				clearAttack = r.attack();
				clearSent = r.sent();
				clearCombo = g.combo();
				clearParts = parts;
				clears = push(clears, new ClearFx(time, r.clearedRows(), r.clearedCodes(), r.lines(), r.spin(),
						r.perfectClear(), b2bStreak, Math.max(0, g.combo())));
			}
			if (r.sent() > 0) {
				sentAt = time;
				sentLines = r.sent();
			}
			if (r.garbageRisen() > 0) {
				risenAt = time;
				risenLines = r.garbageRisen();
			}
			int cancelled = r.attack() - r.sent();
			if (cancelled > 0) {
				cancels = push(cancels, new CancelFx(time, cancelled));
			}
			locks = push(locks, lockEvent(r, time, dropFrom));
			StringBuilder s = new StringBuilder();
			if (r.spin() != Spin.NONE && r.lines() > 0) {
				tspins++;
				s.append(r.spin() == Spin.MINI ? "T-SPIN MINI " : "T-SPIN ");
				s.append(new String[] { "", "SINGLE", "DOUBLE", "TRIPLE" }[r.lines()]);
			} else if (r.lines() == 4) {
				tetrises++;
				s.append("TETRIS");
			}
			if (r.perfectClear()) {
				perfectClears++;
				s.append(s.length() > 0 ? "  " : "").append("PERFECT CLEAR");
			}
			if (r.lines() > 0 && g.combo() >= 1) {
				s.append(s.length() > 0 ? "  " : "").append(g.combo()).append(" COMBO");
			}
			maxCombo = Math.max(maxCombo, g.combo());
			if (s.length() > 0) {
				if (b2bContinued) {
					s.insert(0, "B2B ");
				}
				lastAction = s.toString();
				lastActionAt = time;
			}
			b2bBefore = g.b2b();
			comboBefore = g.combo();
		}

		/** 방해 줄을 새로 받았다. */
		void received(long time, int lines) {
			garbageReceived += lines;
			receives = push(receives, new ReceiveFx(time, lines));
		}

		/**
		 * 고정한 칸의 최종 자리(지운 줄의 칸은 빼고, 아래에서 지운 줄 수만큼 내리고, 오른 방해 줄만큼 올린다)와 내려앉은 자리(지우기
		 * 전의 행에서 오른 방해 줄만큼 올린다, 4칸 모두).
		 */
		private static LockFx lockEvent(GameModel.LockResult r, long time, int dropFrom) {
			int[][] placed = r.placedCells(); // 행 오름차순
			int[] cleared = r.clearedRows();
			int[][] cells = new int[placed.length][];
			int[][] landed = new int[placed.length][];
			int n = 0;
			for (int k = 0; k < placed.length; k++) {
				int[] c = placed[k];
				landed[k] = new int[] { c[0] - r.garbageRisen(), c[1] };
				int below = 0;
				boolean gone = false;
				for (int row : cleared) {
					gone |= row == c[0];
					below += row > c[0] ? 1 : 0;
				}
				if (!gone) {
					cells[n++] = new int[] { c[0] + below - r.garbageRisen(), c[1] };
				}
			}
			boolean hardDrop = dropFrom >= 0;
			int dropRows = hardDrop ? Math.max(0, placed[0][0] - dropFrom) : 0;
			return new LockFx(time, java.util.Arrays.copyOf(cells, n), r.type().code(), hardDrop, dropRows, landed);
		}
	}

	/** 목록 끝에 하나를 붙이고 최근 {@link #FX_KEEP} 개만 남긴 바뀌지 않는 새 목록. */
	private static <T> List<T> push(List<T> list, T item) {
		int from = Math.max(0, list.size() + 1 - FX_KEEP);
		List<T> next = new ArrayList<>(FX_KEEP);
		next.addAll(list.subList(Math.min(from, list.size()), list.size()));
		next.add(item);
		return List.copyOf(next);
	}

	private static int[][] copy(int[][] a) {
		int[][] out = new int[a.length][];
		for (int i = 0; i < a.length; i++) {
			out[i] = a[i].clone();
		}
		return out;
	}

	final Object lock = new Object();
	final Settings settings;
	/** 이 판의 실제 시드 ("같은 판 다시" 는 {@code settings.withSeed(seed)}). */
	final long seed;
	/** 판을 시작할 때의 사용자 설정 (키, DAS·ARR, 화면 효과 …). 그 판 내내 이 값을 쓴다. */
	final UserSettings user;
	final TimedMatch match;
	final HumanInput human;
	final Tally[] tallies;

	// 자리마다의 AI 와 두뇌 (사람이 두면 null). 모델을 정하는 스레드가 lock 을 잡고 한 번 넣는다(install).
	private final AiController[] ais;
	private final AiCatalog.Choice[] choices;
	/** 모델 묶음을 읽지 못해 모든 단계가 기준 AI 인 이유({@link AiCatalog#problem()}), 아니면 null. 시험용 생성자는 null. */
	private final String catalogProblem;
	private final String[] names;
	/** 승률 예측망, 없으면 null. {@link #install} 에서 정하고 그 뒤로는 진행 스레드만 읽는다. */
	private WinRate winRate;
	/** 사람 대 AI 판의 기록 (승률 예측망 자료, TRAINING.md 10.9). 다른 판이거나 기록을 끄면 null. {@link #install} 에서 정한다. */
	private TimedRecorder recorder;
	/** 아직 불러오지 않은 모델을 불러와 정하는 일 (기다릴 수 있다). 처음부터 정했으면 null. */
	private final Supplier<Models> loadModels;
	/** 모델을 정했고 첫 승률도 쟀다({@link #prepare()}). */
	private volatile boolean prepared;
	/** 플레이 기록을 남길 곳, 없으면 null(남기지 않는다). */
	private final SaveData save;
	private final LongSupplier clock;
	private final Consumer<GameSession> onOver;
	/** 틱마다 사람(P1)의 소리를 고른다. 진행 스레드만 쓴다(lock 안에서). */
	private final MatchSounds sounds;
	/** 고른 소리를 낼 곳 (보통 {@link Sound#get()}, 시험은 {@link #soundsTo} 로 바꾼다). */
	private volatile Consumer<List<Cue>> soundOut = cues -> Sound.get().play(cues);
	/** 판을 떠났다({@link #stop()}): 이 판은 더 소리를 내지 않는다. */
	private volatile boolean quiet;

	// 진행 스레드가 lock 을 잡고 쓰고, 화면이 lock 을 잡고 읽는다.
	private final double[] think; // AI 가 생각하는 진행률(0~1), 아니면 -1
	private final long[] noticeAt; // 판 도중 기준 AI 로 바뀐 판 시각, 없으면 -1
	private final int[] pendingBefore; // 틱 전 받을 방해 줄 (진행 스레드만)
	private final int[] dropFrom; // 틱 전 하드드롭한 블록의 맨 위 행, 아니면 -1 (진행 스레드만)
	private long overAtNanos = NOT_OVER;
	/** 예측 승률의 흐름: {판 시각 ms, P1 이 이길 확률}. lock 을 잡고 쓰고 읽는다. */
	final List<double[]> winHistory = new ArrayList<>();
	/**
	 * 예측 승률이 원인과 함께 한 번에 5%p 이상 움직인({@link #isSwing}) 마지막 순간과 그 크기(P1 기준), 원인 (화면의
	 * "▲ 12%p · AI 공격 3줄").
	 */
	long swingAt = -10_000;
	double swingDelta;
	String swingCause = "";

	private volatile double p1Win = Double.NaN;
	private volatile boolean ended; // 누가 졌다
	private volatile boolean forfeited; // 사람이 기권했다 (판을 떠남)
	private volatile String failure; // 오류로 멈춘 이유, 아니면 null
	private boolean winRateBroken; // 승률 예측이 이 판에서 한 번 실패했다 (진행 스레드만 쓰고 읽는다)
	private final AtomicBoolean recorded = new AtomicBoolean();
	private final AtomicBoolean recordSaved = new AtomicBoolean(); // 승률 자료를 남겼다
	private final AtomicBoolean concluded = new AtomicBoolean(); // 판이 끝난 뒤의 처리를 했다
	private volatile boolean concluding; // 판이 끝난 뒤의 처리(기록 쓰기) 중
	private volatile boolean newBest;
	private volatile PlayRecords recordsAfter; // 이 판을 더한 기록, 더하지 않았으면 null

	// 시계: lock 을 잡고 쓰고 읽는다 (paused, started, running 은 잠금 없이도 읽는다)
	private volatile boolean started;
	private volatile boolean running;
	private volatile boolean paused;
	private long startNanos;
	private long pausedNanos;
	private long pauseStarted;
	private long resumeAt;
	private boolean resuming;
	/** 판의 시계가 간 뒤에 멈춘 적이 있다: "GO!" 는 처음에만 보인다(다시 갈 때는 2·1 뒤 바로 간다). */
	private boolean pausedInPlay;

	private Thread thread;
	private final List<ExecutorService> pools = new ArrayList<>();

	/**
	 * 게임: 창({@link TetrisApp})이 화면 스레드에서 만든다. 모델을 이미 불러 뒀으면(메뉴가 배경에서 미리 불러 둔다) 바로 정하고,
	 * 아니면 기다리지 않고 진행 스레드가 카운트다운 동안 불러온다({@link #aiLoading()}). 스레드 없이 진행하는 도구·시험은 첫
	 * {@link #advance} 가 불러온다.
	 *
	 * @param seed   {@code settings} 에 정한 시드가 없을 때 쓸 새 시드
	 * @param save   판을 시작할 때의 사용자 설정을 읽고 끝난 판의 기록을 더할 곳. null 이면 기본 설정, 기록 없음
	 * @param onOver 판이 끝나거나 오류로 멈추면 게임 진행 스레드에서 한 번 부른다(기권은 부르지 않는다). 기록과 결과 소리는
	 *               이미 끝났다. 짧게 두고 화면 스레드를 기다리지 않는다({@link #stop()} 이 진행 스레드를 기다리므로). 보통
	 *               다시 그리기만 한다
	 */
	GameSession(Settings settings, AiCatalog catalog, long seed, SaveData save, Consumer<GameSession> onOver) {
		this(settings, catalog, seed, save, onOver, System::nanoTime);
	}

	/** 카탈로그로, 시계를 정해서 (시험용: 모델을 불러오는 동안의 카운트다운). */
	GameSession(Settings settings, AiCatalog catalog, long seed, SaveData save, Consumer<GameSession> onOver,
			LongSupplier clock) {
		this(settings, settings.seedOr(seed), save, onOver, modelsNow(settings, catalog, settings.seedOr(seed)),
				() -> modelsLoaded(settings, catalog, settings.seedOr(seed)), catalog.problem(), clock);
	}

	/** 기록과 사용자 설정 없이 (화면 확인 도구, 시험). */
	GameSession(Settings settings, AiCatalog catalog, long seed, Consumer<GameSession> onOver) {
		this(settings, catalog, seed, null, onOver);
	}

	/** 두뇌와 승률 예측망, 시계를 직접 넘긴다(시험용). 기록과 사용자 설정 없이. */
	GameSession(Settings settings, long seed, Consumer<GameSession> onOver, AiCatalog.Choice[] choices, WinRate winRate,
			LongSupplier clock) {
		this(settings, seed, null, onOver, choices, winRate, clock);
	}

	/**
	 * 모든 것을 직접 넘긴다(시험용).
	 *
	 * @param seed    {@code settings} 에 정한 시드가 없을 때 쓸 새 시드
	 * @param save    사용자 설정과 플레이 기록, 없으면 null
	 * @param choices 자리마다 AI 의 두뇌({@link Settings#ai(int)} 가 null 인 자리는 null)
	 * @param winRate 승률 예측망, 없으면 null
	 * @param clock   실제 시간 (나노초). 판의 규칙에는 쓰지 않고 몇 틱을 돌릴지만 정한다. 화면 효과의 시계이기도 하다
	 */
	GameSession(Settings settings, long seed, SaveData save, Consumer<GameSession> onOver, AiCatalog.Choice[] choices,
			WinRate winRate, LongSupplier clock) {
		this(settings, seed, save, onOver, new Models(choices, winRate), null, null, clock);
	}

	/**
	 * @param now            지금 정한 모델, 아직 불러와야 하면 null
	 * @param later          {@code now} 가 null 일 때 모델을 불러와 정하는 일 (진행 스레드가 부른다)
	 * @param catalogProblem 모델 묶음을 읽지 못한 이유({@link AiCatalog#problem()}), 아니면 null
	 */
	private GameSession(Settings settings, long seed, SaveData save, Consumer<GameSession> onOver, Models now,
			Supplier<Models> later, String catalogProblem, LongSupplier clock) {
		this.settings = settings;
		this.seed = settings.seedOr(seed);
		this.save = save;
		this.catalogProblem = catalogProblem;
		this.user = save == null ? UserSettings.defaults() : save.settings();
		this.human = new HumanInput(user);
		this.onOver = onOver;
		this.clock = clock;
		int players = settings.players();
		match = players == 1 ? new TimedMatch(this.seed, 1, settings.scheduler(), settings.gravityCapG())
				: new TimedMatch(this.seed, 2, GameModel.Scheduler.NONE, settings.difficulty().gravityCapG());
		ais = new AiController[players];
		choices = new AiCatalog.Choice[players];
		names = settings.mode() == Mode.SOLO ? new String[] { "연습" } : new String[] { "나", "AI" };
		tallies = new Tally[players];
		think = new double[players];
		noticeAt = new long[players];
		pendingBefore = new int[players];
		dropFrom = new int[players];
		for (int i = 0; i < players; i++) {
			tallies[i] = new Tally();
			think[i] = -1;
			noticeAt[i] = -1;
		}
		sounds = new MatchSounds(settings.ai(0) == null);
		fill();
		loadModels = now == null ? later : null;
		if (now != null) {
			install(now); // 모델이 이미 있다: 기다리지 않는다 (첫 승률은 진행 스레드가 잰다, prepare)
		}
	}

	/** 자리마다의 AI 두뇌와 승률 예측망. */
	private record Models(AiCatalog.Choice[] choices, WinRate winRate) {
	}

	/**
	 * 기다리지 않고 정할 수 있는 모델: 자리마다의 두뇌(이미 불러 본 모델이거나 기준 AI)와 승률 예측망(이미 불러 봤으면). 아직
	 * 불러오지 않은 모델이 하나라도 있으면 null. 혼자 연습에는 모델이 없다.
	 */
	private static Models modelsNow(Settings settings, AiCatalog catalog, long seed) {
		AiCatalog.Choice[] out = new AiCatalog.Choice[settings.players()];
		for (int i = 0; i < out.length; i++) {
			Seat seat = settings.ai(i);
			if (seat != null) {
				out[i] = catalog.chooseIfReady(seat.difficulty(), persona(seat, catalog), seed, i + 1);
				if (out[i] == null) {
					return null;
				}
			}
		}
		if (settings.players() == 1 || catalog.winprob() == null) { // 승률 예측망이 없다
			return new Models(out, null);
		}
		return catalog.winRateReady() ? new Models(out, catalog.winRate()) : null;
	}

	/** 모델을 (필요하면 불러와서) 정한다. 기다릴 수 있으므로 화면 스레드에서 부르지 않는다. */
	private static Models modelsLoaded(Settings settings, AiCatalog catalog, long seed) {
		AiCatalog.Choice[] out = new AiCatalog.Choice[settings.players()];
		for (int i = 0; i < out.length; i++) { // 고르기 난수는 자리 번호로 나뉜다(P2 는 102 스트림, Chooser)
			Seat seat = settings.ai(i);
			if (seat != null) {
				out[i] = catalog.choose(seat.difficulty(), persona(seat, catalog), seed, i + 1);
			}
		}
		return new Models(out, settings.players() == 2 ? catalog.winRate() : null);
	}

	/** 자리의 페르소나, 없으면 중립(카탈로그의 첫 페르소나). */
	private static AiCatalog.Persona persona(Seat seat, AiCatalog catalog) {
		return seat.persona() != null ? seat.persona() : catalog.personas().get(0);
	}

	/**
	 * 정한 모델로 AI 조작기와 판 기록을 만든다. 한 번만, 생성자나 {@link #prepare()} 에서 부른다. AI 는 AI 속도를 반영한 조작
	 * 속도로 둔다({@link Settings#aiDifficulty()}).
	 */
	private void install(Models m) {
		synchronized (lock) {
			for (int i = 0; i < ais.length; i++) {
				if (settings.ai(i) != null) {
					choices[i] = m.choices()[i];
					AiCatalog.Difficulty d = settings.aiDifficulty();
					TimedPlayer you = match.player(1 - i);
					ais[i] = new AiController(match.player(i), () -> Opponent.of(you.game()), choices[i].brain(),
							d.thinkMs(), d.inputsPerSec());
				}
			}
			winRate = ais.length == 2 ? m.winRate() : null;
			recorder = settings.mode() == Mode.VERSUS && recordsDir() != null
					? versusRecorder(settings, choices[1], this.seed)
					: null;
		}
	}

	/**
	 * 판을 둘 준비: 아직 정하지 않은 모델을 불러와 정하고(기다릴 수 있다), 첫 승률(0.5 근처)을 잰다. 진행 스레드가 시작하자마자
	 * (카운트다운 동안), 스레드 없이 진행하면 첫 {@link #advance} 가 부른다. 카운트다운이 이미 끝났으면 그 넘친 시간만큼 판의
	 * 시작을 미룬다(그동안 카운트다운은 1 에 멈춰 있었다). 한 번만 하고, 두 번째부터는 아무것도 하지 않는다. 스레드 없이 진행하는
	 * 화면 확인 도구가 첫 틱 전의 화면(승률 막대 0.5)을 그리려면 먼저 부른다. 화면 스레드에서는 부르지 않는다.
	 */
	void prepare() {
		if (prepared) {
			return;
		}
		if (loadModels != null) {
			install(loadModels.get());
		}
		synchronized (lock) {
			if (started && loadModels != null) { // 불러오느라 카운트다운을 넘겼으면: 판의 시계는 지금부터 간다
				long late = elapsedMs() - COUNTDOWN_MS;
				if (late > 0) {
					pausedNanos += late * 1_000_000L;
				}
			}
			prepared = true;
		}
		if (winRate != null && (running || !started)) { // 불러오는 동안 판을 떠났으면 모델을 더 쓰지 않는다 (stop)
			predict(PlayerState.of(match.player(0).game()), PlayerState.of(match.player(1).game()), 0, "");
		}
	}

	/**
	 * 사람 대 AI 판의 기록. P2 설명에는 AI 속도와 그 속도로 바꾼 생각 지연·초당 입력을 함께 적는다
	 * ({@code ai_speed_pct}, {@code think_ms}, {@code inputs_per_sec}; Python 읽기 코드는 모르는 키를 무시한다).
	 */
	private static TimedRecorder versusRecorder(Settings s, AiCatalog.Choice ai, long seed) {
		AiCatalog.Difficulty d = s.difficulty();
		AiCatalog.Difficulty played = s.aiDifficulty();
		String persona = s.persona() == null ? AiCatalog.NEUTRAL : s.persona().id();
		ObjectNode p2 = TimedRecorder.player("level" + d.id() + (d.personas() ? "-" + persona : ""), ai.label(),
				d.temperature());
		p2.put("ai_speed_pct", s.aiSpeed());
		p2.put("think_ms", played.thinkMs());
		p2.put("inputs_per_sec", played.inputsPerSec());
		return new TimedRecorder("human", seed, TimedRecorder.player("human", "human", 0), p2);
	}

	// ------------------------------------------------------------------ 진행

	/**
	 * 판을 시작한다: 카운트다운이 돌기 시작하고, 진행 스레드가 아직 정하지 않은 모델을 불러온 뒤({@link #prepare()}) 판을
	 * 진행한다. 화면 스레드에서 부르고 바로 돌아온다.
	 */
	void start() {
		synchronized (lock) {
			startNanos = clock.getAsLong();
			started = true;
			running = true;
		}
		thread = new Thread(this::loop, "game-loop");
		thread.setDaemon(true);
		thread.start();
	}

	/** AI 하나에 일꾼 하나: 생각 지연 동안 미리 고른다. 진행 스레드가 모델을 정한 뒤 부른다. 이미 멈췄으면 만들지 않는다. */
	private void startWorkers() {
		synchronized (pools) {
			for (int i = 0; i < ais.length && running; i++) {
				if (ais[i] != null) {
					ExecutorService pool = Executors.newSingleThreadExecutor(daemon("ai-think-" + (i + 1)));
					pools.add(pool);
					ais[i].prefetchWith(pool);
				}
			}
		}
	}

	private static java.util.concurrent.ThreadFactory daemon(String name) {
		return r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			return t;
		};
	}

	/**
	 * 진행을 멈추고 진행 스레드와 미리 고르기 일꾼이 끝나기를 기다린다(합쳐 몇 초 안). 기록은 더하지 않는다({@link #forfeit()}).
	 * 이 판은 그 뒤로 소리를 내지 않는다. 모델(두뇌, 승률 예측망)은 카탈로그가 갖고 있으므로 닫지 않는다. 이 메서드가 돌아온
	 * 뒤에는 이 판이 모델을 쓰지 않으므로 카탈로그를 닫아도 된다. 여러 번 불러도 된다.
	 * <p>
	 * 진행 스레드를 끼어들기(interrupt)로 깨우지 않는다: 판이 끝난 직후 기록을 저장하는 중이면 끼어들기가 쓰기를 끊어 기록을
	 * 잃을 수 있기 때문이다. 루프는 틱마다 멈춤을 보고 1ms 씩만 자므로 곧 끝난다. 판이 끝난 뒤의 처리(기록 쓰기) 중이면
	 * 그것이 끝날 때까지 조금 더({@link #CONCLUDE_WAIT_MS}) 기다린다. 진행 스레드가 아직 모델을 불러오는 중이면
	 * ({@link #aiLoading()}) 기다리지 않는다: 그 스레드는 불러오기를 마치면 모델을 쓰지 않고, 판도 두지 않고 끝난다(카탈로그를
	 * 닫는 쪽은 카탈로그의 잠금이 불러오기가 끝나기를 기다린다).
	 */
	void stop() {
		quiet = true;
		running = false;
		// onOver 안에서 불러도 자기를 기다리지 않는다. 모델을 불러오는 중이면 화면 스레드가 그것을 기다리지 않는다
		if (thread != null && thread != Thread.currentThread() && !aiLoading()) {
			try {
				thread.join(2000);
				if (thread.isAlive() && concluding) {
					thread.join(CONCLUDE_WAIT_MS); // 기록을 다 쓰게 둔다
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		// 일꾼은 하던 고르기(불가능 단계에서 길어야 0.2초 남짓)를 마치면 끝난다. 모델 실행 중에는 끊을 수 없으므로 기다린다.
		synchronized (pools) {
			pools.forEach(ExecutorService::shutdownNow);
			long until = System.nanoTime() + 1_000_000_000L;
			for (ExecutorService pool : pools) {
				try {
					if (!pool.awaitTermination(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS)) {
						System.err.println("AI 일꾼이 1초 안에 끝나지 않음");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
	}

	/**
	 * 기권: 판을 떠난다(메뉴로, 다시 하기, 창 닫기, 대전의 Esc 두 번). 진행을 멈추고, 끝나지 않은 판이면 사람이 진 것으로 끝낸다
	 * ({@link #forfeited()}, 결과는 사람 대 AI 면 P2 승, 혼자 연습이면 끝). 카운트다운이 끝난 뒤의 판이면 플레이 기록에 한 번
	 * 더한다: 사람 대 AI 는 패배, 혼자 연습은 그때까지 지운 줄과 버틴 시간. 이미 끝난 판이나 오류로 멈춘 판에서는 멈추기만 한다.
	 * 화면 스레드에서 부르고, {@code onOver} 는 부르지 않는다. 여러 번 불러도 된다.
	 */
	void forfeit() {
		stop(); // 진행 스레드가 끝난 뒤에 정한다: 판이 끝나는 순간과 겹쳐도 기록이 한 번만 남는다
		boolean now;
		synchronized (lock) {
			now = !over();
			if (now) {
				forfeited = true;
				overAtNanos = clock.getAsLong();
			}
		}
		if (now) {
			recordPlay();
		}
	}

	/**
	 * 진행 스레드. 판이 끝나면(누가 짐) 끝난 뒤의 처리({@link #conclude()})와 알림을 한 번 하고 끝난다: 결과 카드를 띄워 둔 동안
	 * 1ms 마다 깨어나지 않는다. 그때 미리 고르기 일꾼도 닫는다(하던 고르기만 마치고 끝난다). 떠나거나({@link #stop()}) 오류로 멈춰도
	 * 끝난다.
	 */
	private void loop() {
		try {
			prepare(); // 카운트다운 동안: 모델을 불러와야 하면 여기서 (화면 스레드는 기다리지 않는다)
			startWorkers();
			while (running) {
				long target;
				synchronized (lock) {
					target = paused ? Long.MIN_VALUE : elapsedMs() - COUNTDOWN_MS;
				}
				while (running && !paused && !over() && match.time() < target) { // 멈추면 남은 틱도 바로 멈춘다
					if (ais[0] == null) { // P1 이 사람
						match.player(0).setSoftDrop(human.softDrop()); // 진행 스레드만 읽는 값
						advance(human.next(match.time()));
					} else {
						advance(null);
					}
				}
				if (over() && !forfeited) {
					conclude();
					notifyOver();
					break;
				}
				Thread.sleep(1);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); // 누가 끼어들었으면 그냥 끝낸다
		} catch (RuntimeException | Error e) {
			fail(e);
		} finally {
			synchronized (pools) { // 판이 끝났거나 멈췄다: 일꾼에게 더 맡길 고르기가 없다 (stop 은 그래도 끝나기를 기다린다)
				pools.forEach(ExecutorService::shutdown);
			}
		}
	}

	/** 진행 스레드와 미리 고르기 일꾼이 모두 끝났는가 (판이 끝나면 떠나지 않아도 끝난다). 시험용. */
	boolean threadsDone() {
		Thread t = thread;
		if (t != null && t.isAlive()) {
			return false;
		}
		synchronized (pools) {
			return pools.stream().allMatch(ExecutorService::isTerminated);
		}
	}

	/**
	 * 판이 끝났을 때 한 번(진행 스레드, 또는 스레드 없이 진행한 도구·시험의 {@link #finishIfOver()}). 결과 소리
	 * ({@link SoundCues#result}: K.O. 뒤 승리·패배 음악)를 판이 끝난 그 순간에 내고(화면의 K.O. 와 맞게), 플레이 기록에 더해 새
	 * 기록인지 정하고({@link #newBest()}), 그 다음에 승률 자료를 남긴다({@link #saveRecord()}, 사람 대 AI, 기록 폴더를 켰을
	 * 때). 사용자의 기록과 "새 기록" 이 그 쓰기를 기다리지 않는다. 새 기록이면 그 소리를 원래 자리(K.O. 뒤 0.65초, 이긴 판은 승리
	 * 음악이 끝나 갈 때)에 맞춰 더한다. 기권했거나 오류로 멈춘 판, 이미 떠난 판은 소리를 내지 않는다. 잠금 밖에서 부른다(쓰기는
	 * 수십 ms).
	 */
	private void conclude() {
		if (!concluded.compareAndSet(false, true)) {
			return;
		}
		concluding = true;
		try {
			boolean sound = failure == null && !forfeited;
			TimedMatch.Result result = result();
			if (sound) {
				play(SoundCues.result(result, false));
			}
			long t0 = System.nanoTime();
			recordPlay();
			if (sound && newBest) {
				List<Cue> full = SoundCues.result(result, true);
				Cue record = full.get(full.size() - 1); // 새 기록 소리
				int spent = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - t0) / 1_000_000);
				play(List.of(record.withDelay(Math.max(0, record.delayMs() - spent))));
			}
			saveRecord();
		} finally {
			concluding = false;
		}
	}

	/** 소리를 낸다. 떠난 판({@link #stop()} 뒤)은 내지 않는다. */
	private void play(List<Cue> cues) {
		if (!cues.isEmpty() && !quiet) {
			try {
				soundOut.accept(cues);
			} catch (RuntimeException e) { // 소리 때문에 판이 멈추지 않게
				System.err.println("소리를 내지 못함: " + e);
			}
		}
	}

	/**
	 * 고른 소리를 낼 곳을 바꾼다 (시험이 들은 소리를 적을 때, 화면 확인 도구가 소리를 끌 때). 처음에는
	 * {@code Sound.get().play}.
	 */
	void soundsTo(Consumer<List<Cue>> out) {
		soundOut = Objects.requireNonNull(out, "out");
	}

	/**
	 * 1ms 진행한다. P1 의 사람 조작은 {@code mine}. 게임 진행 스레드가 부르고, 화면 확인 도구와 시험은 스레드 없이 직접
	 * 부른다. 판을 바꾸는 동안만 잠그고, AI 의 고르기, 승률 예측, 기록 만들기는 잠금 밖에서 한다(판을 쓰는 스레드가 이
	 * 스레드뿐이라 잠금 밖에서 읽어도 된다). 화면 효과용 사건은 잠금 안에서 적는다(판을 바꾸지 않는다).
	 */
	void advance(Input mine) {
		if (over()) {
			return;
		}
		prepare(); // 스레드 없이 진행하면 첫 틱에 모델을 정한다 (진행 스레드는 시작할 때 이미 했다)
		Input p1 = ais[0] != null ? ais[0].poll() : mine; // 잠금 밖: 생각이 끝나는 틱에는 고르기를 한다
		Input p2 = ais.length > 1 && ais[1] != null ? ais[1].poll() : null;
		PlayerState s1 = null;
		PlayerState s2 = null;
		String cause = "";
		long at = 0;
		List<Cue> cues;
		synchronized (lock) {
			int n = match.size();
			for (int i = 0; i < n; i++) { // 화면 효과: 틱 전의 받을 줄과 하드드롭 시작 행
				TimedPlayer pl = match.player(i);
				pendingBefore[i] = pl.game().pendingLines();
				dropFrom[i] = (i == 0 ? p1 : p2) == Input.HD ? topRow(pl.piece()) : -1;
			}
			sounds.before(match); // 소리: 틱 앞의 사람 블록 (읽기만 한다)
			if (n == 1) {
				match.tick(p1);
			} else {
				match.tick(p1, p2);
			}
			boolean locked = false;
			long now = match.time();
			for (int i = 0; i < n; i++) {
				GameModel g = match.player(i).game();
				GameModel.LockResult r = match.lastLock(i);
				if (r != null) {
					tallies[i].add(r, g, now, dropFrom[i]);
					locked = true;
				}
				// 새로 받은 방해 줄: 받을 줄이 상쇄·상승으로 줄어든 것보다 더 남았다 (SoundCues.incoming 과 같은 셈)
				int cancelled = r == null ? 0 : r.attack() - r.sent();
				int risen = r == null ? 0 : r.garbageRisen();
				int incoming = Math.max(0, g.pendingLines() - (pendingBefore[i] - cancelled - risen));
				if (incoming > 0) {
					tallies[i].received(now, incoming);
				}
			}
			cues = sounds.after(match, p1); // 사람(P1)의 조작·고정·줄 지우기·방해 줄. AI 의 조작은 소리가 없다
			fill();
			for (int i = 0; i < ais.length; i++) {
				think[i] = ais[i] == null ? -1 : ais[i].thinkProgress();
				if (ais[i] != null && noticeAt[i] < 0 && ais[i].fallback() != null) {
					noticeAt[i] = now;
				}
			}
			ended = match.isOver();
			if (ended && overAtNanos == NOT_OVER) {
				overAtNanos = clock.getAsLong();
			}
			if (locked && winRate != null && !winRateBroken) {
				GameModel g1 = match.player(0).game();
				GameModel g2 = match.player(1).game();
				if (g1.isOver() || g2.isOver()) { // 끝난 판은 모델 없이 결과로
					addWin(g1.isOver() == g2.isOver() ? 0.5 : g1.isOver() ? 0.0 : 1.0, now, swingCause());
				} else { // 상태만 복사해 두고 계산은 잠금 밖에서
					s1 = PlayerState.of(g1);
					s2 = PlayerState.of(g2);
					cause = swingCause();
					at = now;
				}
			}
		}
		play(cues); // 잠금 밖에서 (막히지 않지만 화면을 기다리게 하지 않는다)
		if (recorder != null) {
			recorder.afterTick(match); // 블록이 고정된 틱이면 두 상태를 남긴다
		}
		if (s1 != null) {
			predict(s1, s2, at, cause);
		}
	}

	/** 블록의 맨 위 칸의 행 (칸은 행 오름차순이다). */
	private static int topRow(PieceState piece) {
		return piece.cells()[0][0];
	}

	/**
	 * 가방을 미리 채운다(미리보기 5개). {@link GameModel#preview()} 와 홀드 배치 탐색은 가방이 모자라면 채우며 상태를 바꾸므로,
	 * 잠금 안에서 채워 두면 그 뒤의 읽기(화면, 잠금 밖의 AI)는 아무것도 바꾸지 않는다. 블록 순서는 바뀌지 않는다(RULES 5.3).
	 */
	private void fill() {
		for (int i = 0; i < match.size(); i++) {
			match.player(i).game().preview();
		}
	}

	/**
	 * 승률 예측망을 잠금 밖에서 부르고, 그 값을 잠금 안에서 흐름에 붙인다. 모델 옆에 보정층이 있으면 블록 수와 판 시각으로
	 * 보정한 값이다({@link WinRate#p1Win(PlayerState, PlayerState, long)}, 없으면 모델 출력 그대로). 실패하면 막대를
	 * 숨기고(NaN) 이 판에서는 더 부르지 않는다. 게임은 그대로 이어 간다.
	 */
	private void predict(PlayerState s1, PlayerState s2, long at, String cause) {
		double p;
		try {
			p = winRate.p1Win(s1, s2, at);
		} catch (RuntimeException e) {
			winRateBroken = true;
			System.err.println("승률 예측 실패, 이 판에서는 막대를 숨김: " + e);
			p1Win = Double.NaN;
			return;
		}
		synchronized (lock) {
			addWin(p, at, cause);
		}
	}

	/** 예측 승률 하나를 흐름에 붙이고, 원인이 있는 큰 움직임({@link #isSwing})이면 그 순간과 원인을 남긴다. lock 을 잡고 부른다. */
	private void addWin(double p, long at, String cause) {
		double before = p1Win;
		p1Win = p;
		winHistory.add(new double[] { at, p });
		if (isSwing(before, p, cause)) {
			swingAt = at;
			swingDelta = p - before;
			swingCause = cause;
		}
	}

	/**
	 * 예측 승률이 {@code before} 에서 {@code p} 로 바뀐 것을 화면의 꼬리표("AI ▲ 12%p · AI 공격 3줄")로 알릴 것인가: 한 번에
	 * 5%p 이상 움직였고 그 원인({@link #swingCause()})이 있다. 원인 없는 움직임(판을 시작하고 처음 놓은 블록에서 시작값 0.5 를
	 * 벗어나는 것 등)은 막대만 움직이고 꼬리표는 띄우지 않는다.
	 */
	static boolean isSwing(double before, double p, String cause) {
		return !Double.isNaN(before) && Math.abs(p - before) >= 0.05 && !cause.isEmpty();
	}

	/**
	 * 이번 틱에 승률을 움직인 일: 보낸 공격("AI 공격 3줄"), 올라온 방해 줄("나 방해 줄 2줄 올라옴"), 지운 줄("나 1줄 지움").
	 * 없으면 빈 문자열. lock 을 잡고 부른다.
	 */
	String swingCause() {
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null && r.sent() > 0) {
				return names[i] + " 공격 " + r.sent() + "줄";
			}
		}
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null && r.garbageRisen() > 0) {
				return names[i] + " 방해 줄 " + r.garbageRisen() + "줄 올라옴";
			}
		}
		for (int i = 0; i < match.size(); i++) {
			GameModel.LockResult r = match.lastLock(i);
			if (r != null && r.lines() > 0) {
				return names[i] + " " + r.lines() + "줄 지움";
			}
		}
		return "";
	}

	private void notifyOver() {
		try {
			onOver.accept(this);
		} catch (RuntimeException e) {
			System.err.println("판이 끝난 뒤의 처리에서 오류: " + e);
		}
	}

	/** 진행 중에 예상하지 못한 오류가 났다: 판을 멈추고 화면에 오류 카드를 띄운다(조용히 멈추지 않는다). */
	void fail(Throwable t) {
		System.err.println("게임 진행 중 오류로 판을 멈춤: " + t);
		t.printStackTrace();
		synchronized (lock) {
			if (overAtNanos == NOT_OVER) {
				overAtNanos = clock.getAsLong();
			}
		}
		failure = "판을 이어 갈 수 없어 멈췄어요";
		running = false;
		notifyOver();
	}

	// ------------------------------------------------------------------ 일시정지와 시계

	/**
	 * P 키: 멈춤과 계속을 바꾼다. 판의 시계가 하나라서 대전도 두 쪽이 함께 멈춘다(AI 도 두지 않는다). 멈춘 동안에는 화면이
	 * 보드를 가린다(사람 대 AI, 혼자 연습 모두): 멈춰 놓고 더미를 살펴보는 이득이 없게({@link GamePanel}). 계속할 때는
	 * {@link #RESUME_MS} 동안 카운트다운한 뒤 다시 간다(시작 카운트다운 중에 멈췄으면 그 카운트다운을 이어 간다). 끝난 판에서는
	 * 아무것도 하지 않는다. 화면 스레드에서 부른다.
	 */
	void togglePause() {
		synchronized (lock) {
			if (paused) {
				resume();
			} else if (!over()) {
				pause();
			}
		}
	}

	/**
	 * 창이 포커스를 잃었거나 최소화됐을 때 부른다(화면 스레드). 사람이 자리를 비운 동안 블록이 떨어지거나 AI 만 두는 일이 없게
	 * 멈춘다. 대전은 시계가 하나라 두 쪽이 함께 멈추므로 공정하다. 창으로 돌아와도 저절로 가지 않고, 사람이 P 로 다시 간다
	 * ({@link #togglePause()}). 멈췄으면 true. 여러 번 불러도 되고, 끝난 판이나 이미 멈춘 판에서는 아무것도 하지 않는다.
	 */
	boolean autoPause() {
		synchronized (lock) {
			if (paused || over()) {
				return false;
			}
			pause();
			return true;
		}
	}

	private void pause() {
		long now = clock.getAsLong();
		if (resuming && now - resumeAt < 0) {
			pausedNanos -= resumeAt - now; // 다시 가기 전 카운트다운 중에 또 멈췄다: 남은 카운트다운은 멈춘 시간에서 뺀다
		}
		resuming = false;
		pausedInPlay |= match.time() > 0; // 시작 카운트다운 중에 멈춘 것이 아니다: 다시 갈 때 GO! 를 다시 보이지 않는다
		pauseStarted = now;
		paused = true;
		human.reset();
	}

	private void resume() {
		long now = clock.getAsLong();
		long wait = match.time() > 0 ? RESUME_MS * 1_000_000L : 0;
		pausedNanos += now - pauseStarted + wait; // 카운트다운도 멈춘 시간으로 친다: 그동안 판의 시계가 가지 않는다
		resumeAt = now + wait;
		resuming = wait > 0;
		paused = false;
		human.reset();
	}

	boolean paused() {
		return paused;
	}

	/** 시작(카운트다운 포함)부터 흐른 실제 시간 (멈춘 시간 제외, ms). lock 을 잡고 부른다. */
	private long elapsedMs() {
		long now = clock.getAsLong();
		long pausedNow = paused ? now - pauseStarted : 0;
		return (now - startNanos - pausedNanos - pausedNow) / 1_000_000;
	}

	/** 화면의 카운트다운 숫자: 시작 전 3·2·1, 일시정지를 푼 뒤 2·1. 아니면(시작했거나, 끝났거나, 스레드 없이 진행하면) 0. */
	int countdown() {
		long left = countdownMs();
		return left <= 0 ? 0 : (int) ((left + 999) / 1000);
	}

	/**
	 * 카운트다운에 남은 시간 (ms): 시작 전 3초, 일시정지를 푼 뒤 2초에서 0 까지. 카운트다운 중이 아니면(멈춤, 진행 중, 끝남,
	 * 스레드 없이 진행) 0. 숫자의 움직임(quality SPEC V8)에 쓴다. AI 모델을 아직 불러오는 중이면({@link #aiLoading()}) 0 이
	 * 되지 않고 1 에서 멈춘다(판도 시작하지 않는다).
	 */
	long countdownMs() {
		synchronized (lock) {
			if (!started || paused || over()) {
				return 0;
			}
			long now = clock.getAsLong();
			if (resuming && now - resumeAt < 0) {
				return (resumeAt - now + 999_999) / 1_000_000;
			}
			long left = Math.max(0, COUNTDOWN_MS - elapsedMs());
			return aiLoading() ? Math.max(1, left) : left;
		}
	}

	/** 막 시작해 "GO!" 를 보여 줄 때인가. */
	boolean showGo() {
		return goAgeMs() >= 0;
	}

	/**
	 * "GO!" 가 뜬 뒤 흐른 시간 (판의 시계, 0 ~ {@link #GO_MS}), 보여 줄 때가 아니면 -1. 멈춘 동안에는 보이지 않고, "GO!" 는
	 * 처음에만 뜬다: 판이 간 뒤에 멈췄다 다시 가면(2·1 카운트다운 뒤) 남은 시간만큼 다시 뜨지 않는다. 시작 카운트다운 중에 멈췄다
	 * 풀면 그 카운트다운을 이어 가므로 "GO!" 도 그대로 뜬다.
	 */
	long goAgeMs() {
		if (!started || paused || over() || countdown() != 0) {
			return -1;
		}
		synchronized (lock) {
			long t = match.time();
			return t < GO_MS && !pausedInPlay ? t : -1;
		}
	}

	/** 판의 시계 (ms). */
	long time() {
		synchronized (lock) {
			return match.time();
		}
	}

	/**
	 * 이 판의 실제 시간 시계 (나노초, 처음 값은 정해지지 않음). 판이 끝난 뒤의 움직임(K.O., 결과 카드)은 판의 시계가 서므로 이
	 * 시계로 잰다: {@code (clockNanos() - overAtNanos()) / 1e6} ms. 시험과 화면 확인 도구는 이 시계를 정해 넘긴다.
	 */
	long clockNanos() {
		return clock.getAsLong();
	}

	/** 판이 끝난(누가 짐, 기권, 오류) 순간의 {@link #clockNanos()}, 아직 끝나지 않았으면 {@link #NOT_OVER}. lock 을 잡고 부른다. */
	long overAtNanos() {
		return overAtNanos;
	}

	// ------------------------------------------------------------------ 화면이 읽는 것

	/** 판이 끝났는가: 누가 졌거나, 기권했거나, 오류로 멈췄다. 어느 스레드에서 불러도 된다. */
	boolean over() {
		return ended || forfeited || failure != null;
	}

	/** 판의 결과. 기권했으면 사람 대 AI 는 P2 승, 혼자 연습은 끝. 진행 중이거나 오류로 멈췄으면 NONE. */
	TimedMatch.Result result() {
		if (match.isOver()) {
			return match.result();
		}
		if (forfeited) {
			return settings.players() == 1 ? TimedMatch.Result.OVER : TimedMatch.Result.P2;
		}
		return TimedMatch.Result.NONE;
	}

	/** 사람이 기권해 끝난 판인가 ({@link #forfeit()}). */
	boolean forfeited() {
		return forfeited;
	}

	/** 오류로 멈췄으면 화면에 보일 짧은 이유, 아니면 null. */
	String failure() {
		return failure;
	}

	/** 자리 i 의 이름: 나 / AI / 연습. 승률 막대, 결과 카드, 승률 알림에도 쓴다. */
	String name(int i) {
		return names[i];
	}

	/** 자리 i 를 두는 AI 의 설정, 사람이 두면 null. */
	Seat seat(int i) {
		return settings.ai(i);
	}

	/** 이 판의 AI 속도 (%). 혼자 연습이면 100. */
	int aiSpeed() {
		return settings.aiSpeed();
	}

	/**
	 * 자리 i 의 AI 가 기준 AI 로 두는가: 모델 묶음을 읽지 못했거나({@link AiCatalog#problem()}), 고른 모델을 불러오지 못했거나,
	 * 판 도중 모델이 오류를 냈다. 사람 자리는 아니다. lock 을 잡고 부른다.
	 */
	boolean baseline(int i) {
		return loadedAsBaseline(i) || noticeAt[i] >= 0;
	}

	/** 자리 i 의 AI 가 처음부터 기준 AI 로 대신 두는가: 모델 묶음을 읽지 못했거나 고른 모델을 불러오지 못했다. */
	private boolean loadedAsBaseline(int i) {
		return settings.ai(i) != null && catalogProblem != null || choices[i] != null && choices[i].fallback() != null;
	}

	/** 판 도중 자리 i 의 AI 가 기준 AI 로 바뀌었다는 알림을 보일 때인가 ({@link #NOTICE_MS}). lock 을 잡고 부른다. */
	boolean notice(int i) {
		long age = noticeAge(i);
		return age >= 0 && age < NOTICE_MS;
	}

	/**
	 * 판 도중 자리 i 의 AI 가 기준 AI 로 바뀐 뒤 흐른 판 시간 (ms), 바뀌지 않았으면 -1. 판의 시계라 멈춘 동안에는 늘지 않는다.
	 * 알림({@link #notice})은 이 값이 0 이상 {@link #NOTICE_MS} 미만일 때다. lock 을 잡고 부른다.
	 */
	long noticeAge(int i) {
		return noticeAt[i] < 0 ? -1 : match.time() - noticeAt[i];
	}

	/** 모델 묶음이나 고른 모델을 불러오지 못해 처음부터 기준 AI 로 대신한 자리가 있는가. */
	boolean loadFallback() {
		for (int i = 0; i < choices.length; i++) {
			if (loadedAsBaseline(i)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * AI 의 모델을 아직 불러오는 중인가 (사람 대 AI 를 모델을 미리 불러 두기 전에 시작했다). 그동안 카운트다운은 1 에서 멈추고
	 * AI 는 두지 않는다. 보통 판을 시작한 뒤 0.2초 안에 끝난다. 어느 스레드에서 불러도 된다.
	 */
	boolean aiLoading() {
		return !prepared && loadModels != null;
	}

	/**
	 * AI 모델의 상태 (화면 표시용): 학습한 모델로 두면 {@link AiStatus#OK}, 기준 AI 가 대신 두면 그 짧은 이유. 처음부터 대신하는
	 * 두 이유(모델 묶음을 읽지 못함, 고른 모델을 쓸 수 없음)는 메뉴의 알림과 같은 문구다. 혼자 연습은 언제나 OK. 모델을 불러오는
	 * 동안({@link #aiLoading()})도 OK 다. lock 을 잡고 부른다.
	 */
	AiStatus aiStatus() {
		if (settings.mode() != Mode.VERSUS) {
			return AiStatus.OK;
		}
		if (catalogProblem != null) {
			return new AiStatus(true, "학습한 AI를 불러오지 못해 기준 AI로 둬요");
		}
		if (choices[1] != null && choices[1].fallback() != null) {
			return new AiStatus(true, "고른 AI의 모델을 쓸 수 없어 기준 AI로 둬요");
		}
		if (noticeAt[1] >= 0) {
			return new AiStatus(true, "AI 모델에 문제가 생겨 기준 AI로 바꿨어요");
		}
		return AiStatus.OK;
	}

	/** 자리 i 의 AI 가 새 블록을 보고 생각하는 진행률(0~1), 아니면 -1. lock 을 잡고 부른다. */
	double think(int i) {
		return think[i];
	}

	/** P1 이 이길 확률. 모델이 없거나 혼자 연습이면 NaN. */
	double p1Win() {
		return p1Win;
	}

	/** 자리 i 의 AI 조작기, 사람이 두면 null. */
	AiController ai(int i) {
		return ais[i];
	}

	/** 자리 i 의 결과 카드용 통계. lock 을 잡고 부른다. */
	PlayerStats stats(int i) {
		GameModel g = match.player(i).game();
		Tally t = tallies[i];
		long time = match.time();
		double sec = Math.max(1.0, time / 1000.0);
		return new PlayerStats(time, g.pieces(), g.pieces() / sec, g.attackTotal(), g.sentTotal(),
				g.attackTotal() * 60.0 / sec, g.linesTotal(), Math.max(0, t.maxCombo), t.tspins, t.tetrises,
				t.maxB2bStreak, t.garbageReceived);
	}

	/** 자리 i 의 최근 블록 고정 (오래된 것부터, 많아야 {@link #FX_KEEP} 개, 바뀌지 않는 목록). lock 을 잡고 부른다. */
	List<LockFx> lockFx(int i) {
		return tallies[i].locks;
	}

	/** 자리 i 의 최근 줄 지우기 (오래된 것부터, 많아야 {@link #FX_KEEP} 개). lock 을 잡고 부른다. */
	List<ClearFx> clearFx(int i) {
		return tallies[i].clears;
	}

	/** 자리 i 가 최근 받은 방해 줄 (오래된 것부터, 많아야 {@link #FX_KEEP} 개). lock 을 잡고 부른다. */
	List<ReceiveFx> receiveFx(int i) {
		return tallies[i].receives;
	}

	/** 자리 i 의 최근 상쇄 (오래된 것부터, 많아야 {@link #FX_KEEP} 개). lock 을 잡고 부른다. */
	List<CancelFx> cancelFx(int i) {
		return tallies[i].cancels;
	}

	/** 자리 i 의 지금 이어지는 B2B 횟수 (B2B 가 아니면 0). lock 을 잡고 부른다. */
	int b2bStreak(int i) {
		return tallies[i].b2bStreak;
	}

	// ------------------------------------------------------------------ 기록

	/**
	 * 이 판이 플레이 기록에 새 최고 기록을 세웠는가 (결과 카드의 "새 기록"과 NEW_RECORD 소리, {@link #conclude()}). 기록에 더한 뒤에만
	 * 참일 수 있다. 사람 대 AI 는 그 난이도의 가장 빠른 승리나 가장 긴 연승, 혼자 연습은 그 설정의 가장 많이 지운 줄이나 가장
	 * 오래 버틴 시간. 첫 판은 새 기록으로 치지 않는다({@link PlayRecords}). 참이면 {@link #records()} 는 이 판을 더한 기록이다:
	 * 사람 대 AI 는 이 판의 이긴 시간이 그 가장 빠른 승리와 같거나 지금 연승이 가장 긴 연승이고, 혼자 연습은 이 판의 버틴 시간이나
	 * 지운 줄이 그 최고 값과 같다({@link BattleResult#recordDetail} 가 이것으로 무슨 기록인지 고른다).
	 */
	boolean newBest() {
		return newBest;
	}

	/** 이 판을 더한 플레이 기록, 아직 더하지 않았거나 남기지 않는 판이면 저장된 지금 기록(기록할 곳이 없으면 빈 기록). */
	PlayRecords records() {
		PlayRecords r = recordsAfter;
		return r != null ? r : save == null ? PlayRecords.empty() : save.records();
	}

	/**
	 * 판이 끝났으면 진행 스레드가 판 끝에 하는 일을 한 번 한다: 승률 자료(사람 대 AI), 플레이 기록과 새 기록, 결과 소리
	 * ({@link #conclude()}). 기권한 판은 기록만 이미 더했다. 스레드 없이 진행한 도구·시험이 판이 끝난 뒤 부른다.
	 */
	void finishIfOver() {
		if (over()) {
			conclude();
		}
	}

	/**
	 * 끝난(또는 기권한) 판을 한 번만 플레이 기록에 더한다. 판의 잠금 밖에서 부른다(저장은 몇 ms). 남기지 않는 판: 기록할 곳이
	 * 없음, 오류로 멈춤, 카운트다운 중에 나감, 사람 대 AI 에서 AI 가 기준 AI 로 둔 판(모델 묶음이나 고른 모델을 불러오지 못했거나
	 * 판 도중 바뀜). 사람 대 AI 의 전적은 난이도마다이고 AI 속도로 나누지 않는다(저장 형식 그대로). 새 기록인지는 이 판을 더하기
	 * 바로 전의 기록과 비교한다: 다른 창이 그사이 파일에 남긴 판도 그 안에 있다({@link SaveData#updateRecords} 가 다시 읽는다).
	 */
	private void recordPlay() {
		if (save == null || failure != null) {
			return;
		}
		long time;
		TimedMatch.Result result;
		int lines;
		boolean fallback;
		synchronized (lock) {
			time = match.time();
			result = result();
			lines = match.player(0).game().linesTotal();
			fallback = settings.mode() == Mode.VERSUS && baseline(1);
		}
		if (time <= 0 || result == TimedMatch.Result.NONE || fallback || !recorded.compareAndSet(false, true)) {
			return;
		}
		try {
			PlayRecords[] before = new PlayRecords[1]; // 이 판을 더한 바로 그 기록 (다시 읽은 파일 위)
			PlayRecords after;
			boolean best;
			if (settings.mode() == Mode.VERSUS) {
				int level = settings.difficulty().id();
				PlayRecords.Outcome outcome = result == TimedMatch.Result.P1 ? PlayRecords.Outcome.WIN
						: result == TimedMatch.Result.DRAW ? PlayRecords.Outcome.DRAW : PlayRecords.Outcome.LOSS;
				after = save.updateRecords(r -> {
					keepFirst(before, r);
					return r.withVersus(level, outcome, time);
				});
				PlayRecords.Versus a = after.versus(level);
				PlayRecords.Versus b = before[0].versus(level);
				best = a.newFastestWinSince(b) || a.newBestStreakSince(b);
			} else {
				String setup = PlayRecords.soloSetup(settings.gravityCapG(), settings.scheduler().json());
				after = save.updateRecords(r -> {
					keepFirst(before, r);
					return r.withSolo(setup, lines, time);
				});
				PlayRecords.Solo a = after.solo(setup);
				PlayRecords.Solo b = before[0].solo(setup);
				best = a.newBestLinesSince(b) || a.newBestTimeSince(b);
			}
			// 차례를 바꾸지 않는다: newBest 를 참으로 본 화면은 records() 에서 이 판을 더한 기록을 읽어 무슨 기록인지 정한다
			// (BattleResult.recordDetail: 이 판의 시간·줄·연승이 그 기록의 값과 같은지 본다). newBest(volatile)를 나중에 쓰므로
			// 그것을 참으로 읽은 스레드는 recordsAfter 도 이 값으로 읽는다.
			recordsAfter = after;
			newBest = best;
		} catch (RuntimeException e) { // 저장에 실패해도 게임은 계속한다 (SaveData 는 보통 예외를 던지지 않는다)
			System.err.println("플레이 기록을 남기지 못함: " + e);
		}
	}

	/**
	 * 기록 고치기({@link SaveData#updateRecords})가 처음 받은 기록을 남긴다. 저장소가 읽기에 실패해 같은 고치기를 나중에 다시 하더라도
	 * 처음 값(돌려받은 기록을 만든 값)이 비교 기준이다.
	 */
	private static void keepFirst(PlayRecords[] slot, PlayRecords r) {
		if (slot[0] == null) {
			slot[0] = r;
		}
	}

	/**
	 * 사람 대 AI 판의 기록을 둘 폴더({@code -Dtetris.records=<폴더>}), 남기지 않으면 null. 기본은 끔이다: 게임 실행
	 * ({@code TetrisApp.main})만 속성이 없을 때 {@code runs/human/data/games}(git 에 올리지 않는 폴더)로 켠다. 그래서 IDE 에서
	 * 바로 돌린 시험이나 도구가 사람이 두지 않은 판을 사람 판 자료에 섞지 않는다. 비었거나 {@code off} 면 남기지 않는다. 판의
	 * 상태만 남고 개인 정보는 없다. 혼자 연습은 남기지 않는다. 폴더는 첫 기록을 남길 때 만든다({@link TimedRecorder#writeSingle}:
	 * 새로 받은 저장소에는 {@code runs/} 가 없다).
	 */
	static Path recordsDir() {
		return recordsDir(System.getProperty("tetris.records"));
	}

	/** {@link #recordsDir()} 의 본체: {@code tetris.records} 의 값 {@code v}(없으면 null) → 기록 폴더, 남기지 않으면 null. */
	static Path recordsDir(String v) {
		return v == null || v.isBlank() || "off".equalsIgnoreCase(v.strip()) ? null : Path.of(v.strip());
	}

	/**
	 * 끝난 사람 대 AI 판만 승률 예측망 자료로 남긴다(기권한 판은 결과가 규칙으로 정해지지 않았으므로 남기지 않는다). 판 도중 AI
	 * 가 기준 AI 로 바뀌었으면 P2 설명에 {@code fallback_ms}(바뀐 판 시각)를 붙인다: 그 뒤의 수는 적힌 모델이 둔 것이 아니다.
	 * 진행 스레드(또는 스레드 없이 진행한 시험)가 판이 끝난 뒤 부르므로 잠그지 않는다. 한 판에 한 번만 남긴다. 임시 파일에 다
	 * 쓴 뒤 이름을 바꿔 끼우므로({@link TimedRecorder#writeSingle}) 쓰는 도중에 꺼져도 반쯤 쓴 기록 파일은 남지 않는다. 기록을
	 * 만들거나 쓰다 실패해도 한 줄만 남기고 게임은 계속한다(정상으로 끝난 판에 오류 카드를 띄우지 않는다).
	 */
	void saveRecord() {
		Path dir = recordsDir();
		if (recorder == null || dir == null || !match.isOver() || !recordSaved.compareAndSet(false, true)) {
			return;
		}
		try {
			ObjectNode rec = recorder.finish(match);
			if (noticeAt[1] >= 0) {
				((ObjectNode) rec.get("players").get(1)).put("fallback_ms", noticeAt[1]);
			}
			String name = "human-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
					+ seed + ".jsonl.gz";
			TimedRecorder.writeSingle(rec, dir.resolve(name));
		} catch (java.io.IOException | RuntimeException e) {
			System.err.println("판 기록을 남기지 못함: " + e);
		}
	}
}
