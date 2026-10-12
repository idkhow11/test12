package kr.ac.jbnu.se.tetris.sand;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;

/**
 * 모래 모드의 규칙 수치 (규칙 버전 {@link #VERSION}). 고전 규칙(core, docs/RULES.md 0.3)과는 따로 간다.
 * <p>
 * {@code [초안]} 은 플레이 테스트로 바꿀 값이다. 판의 칸 배치는 core {@link Grid} 와 같은 10 × 24 이고, 칸 하나가
 * {@link #GRAIN} × {@link #GRAIN} 알갱이다. 좌표는 고전과 같이 <b>0 행이 맨 위</b>다.
 */
public final class SandRules {

	/** 모래 모드 규칙 버전. 고전 규칙 버전({@code core.Rules.VERSION})과 섞지 않는다. */
	public static final String VERSION = "sand-0.3";

	/** 칸 하나의 가로·세로 알갱이 수. {@code [초안]} (원작 Sandtrix 와 같은 8) */
	public static final int GRAIN = 8;
	/** 판의 칸 열 수 (core Grid 와 같다). */
	public static final int COLS = Grid.WIDTH;
	/** 판의 칸 행 수. 0~1 행은 위로 킥될 여유(그리지 않음), 2~3 행은 스폰 여백(그림), 4~23 행이 보이는 판이다. */
	public static final int ROWS = Grid.HEIGHT;
	/** 알갱이 단위 판 너비 (80). */
	public static final int WIDTH = COLS * GRAIN;
	/** 알갱이 단위 판 높이 (192). */
	public static final int HEIGHT = ROWS * GRAIN;
	/** 그리는 첫 알갱이 행 (16): 스폰 여백(칸 2 행)의 맨 위. 그 위는 킥 여유라 그리지 않는다. */
	public static final int DRAW_TOP = (Grid.HIDDEN_ROWS - 2) * GRAIN;
	/** 보이는 판의 첫 알갱이 행 (32, 칸 4 행). 이보다 위에 <b>정착한</b> 알갱이가 있으면 진다. */
	public static final int VISIBLE_TOP = Grid.HIDDEN_ROWS * GRAIN;
	/** 블록 하나가 부서져 나오는 알갱이 수 (256). */
	public static final int PIECE_GRAINS = 4 * GRAIN * GRAIN;
	/** 미리보기 블록 수 (5). 고전과 같다. */
	public static final int PREVIEW = GameModel.PREVIEW;

	/** 락 딜레이 (ms). 고전과 같다. */
	public static final int LOCK_DELAY_MS = TimedPlayer.LOCK_DELAY_MS;
	/** 바닥에서 이동·회전으로 락 딜레이를 되돌릴 수 있는 횟수. 고전과 같다. */
	public static final int MAX_RESETS = TimedPlayer.MAX_RESETS;
	/** 사람이 소프트드롭을 누르고 있을 때의 최소 중력 g (초당 20칸). 고전과 같다. */
	public static final int SOFT_DROP_G = TimedPlayer.SOFT_DROP_G;
	/** 중력 누적값이 이만큼 쌓이면 블록이 한 칸 내려간다 (RULES 6.3 과 같은 단위). */
	public static final long GRAVITY_CELL = 1_000_000;

	/**
	 * 지우는 덩어리의 최소 알갱이 수 (640 = 판 한 줄, 칸 10개 분량. 판 너비로 펴면 평균 한 칸 두께). 양쪽 벽을 이은 같은 색
	 * 덩어리라도 이보다 작으면 지우지 않고 남긴다(sand-0.3, 화면은 "얇은 띠" 로 살짝 표시한다). 블록 하나(256알)가 더미의
	 * 비탈을 얇게 덮어 혼자 양쪽 벽을 잇고 지워지는 바람에 아무것도 하지 않는 손이 지지 않던 것을 막는다. {@code [초안]}
	 */
	public static final int MIN_CLEAR_GRAINS = 640;
	/** 지워질 덩어리가 반짝이는 시간 (ms). 그동안 그 알갱이는 움직이지 않는 장애물이다. {@code [초안]} */
	public static final int FLASH_MS = 200;
	/** 콤보 시간 (ms): 이 안에 다시 지우면 배율이 한 단계 오르고, 지우지 못하면 한 단계씩 내려간다. {@code [초안]} */
	public static final int COMBO_MS = 1500;
	/** 콤보 배율의 상한. */
	public static final int MAX_MULTIPLIER = 10;
	/** 단계가 오르는 간격 (ms). {@code [초안]} */
	public static final int LEVEL_MS = 30_000;
	/** 가장 높은 단계. */
	public static final int MAX_LEVEL = 10;

	/** 단계 1~10 의 중력 g = 초당 칸 수 × 1000. 고전 RULES 6.3 표와 같다. {@code [초안]} */
	private static final int[] GRAVITY = { 1000, 1500, 2000, 3000, 4000, 5000, 6500, 8000, 10000, 12000 };
	/** 단계 1~10 의 모래 스텝 간격 (ms). 한 스텝에 알갱이가 많아야 한 칸(알갱이) 내려간다. {@code [초안]} */
	private static final int[] SAND_STEP_MS = { 10, 10, 9, 9, 8, 8, 7, 7, 6, 6 };

	/** 색 수. {@code [초안]} */
	public static final int COLORS = 4;
	/** 색 가방에 색마다 넣는 개수 (4색 × 3 = 12개 가방). {@code [초안]} */
	public static final int COLOR_COPIES = 3;

	/**
	 * 난수 스트림 번호 (RULES 5.2 의 {@code SplitMix64.stream}). core 가 1~4, AI 의 고르기({@code Chooser})가 101·102 를
	 * 쓰므로 5~7 을 모래 모드에 쓴다.
	 */
	public static final int STREAM_COLORS = 5;
	/** 모래 스텝에서 양쪽 대각선이 모두 빌 때의 좌우 고르기. */
	public static final int STREAM_PHYSICS = 6;
	/** 알갱이 명암 무늬. */
	public static final int STREAM_SHADES = 7;

	private SandRules() {
	}

	/** 단계를 1~{@link #MAX_LEVEL} 로 자른다. */
	public static int clampLevel(int level) {
		return Math.max(1, Math.min(MAX_LEVEL, level));
	}

	/** 단계의 중력 g (초당 칸 수 × 1000). */
	public static int gravity(int level) {
		return GRAVITY[clampLevel(level) - 1];
	}

	/** 단계의 모래 스텝 간격 (ms). */
	public static int sandStepMs(int level) {
		return SAND_STEP_MS[clampLevel(level) - 1];
	}
}
