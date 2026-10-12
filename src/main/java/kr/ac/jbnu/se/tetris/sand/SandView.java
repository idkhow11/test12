package kr.ac.jbnu.se.tetris.sand;

import java.util.List;

import kr.ac.jbnu.se.tetris.core.PieceState;

/**
 * 모래 판을 그리는 데 필요한 것만 읽는 창. 살아 있는 게임({@link SandGame})과 복사본({@link SandSnapshot})이 함께 구현한다.
 * <p>
 * 화면은 게임 진행 스레드와 같은 잠금 안에서 {@link SandGame#snapshot} 으로 복사본을 만들고, 잠금을 놓은 뒤 복사본으로
 * 그리면 된다(잠금을 잡는 시간이 수십 μs 로 짧다).
 * <p>
 * 좌표: 알갱이는 {@code (행 gy, 열 gx)}, 판은 {@link SandRules#WIDTH} × {@link SandRules#HEIGHT}. 블록은 core 의
 * {@link PieceState} 그대로이고 {@code cells()} 가 칸 {@code {행, 열}} 을 준다(칸 r 은 알갱이 행 r × 8 ~ r × 8 + 7).
 * 화면에는 알갱이 행 {@link SandRules#DRAW_TOP}(스폰 여백)부터 그린다. 알갱이 하나의 색은 {@link Grain#look} 으로 고른다.
 */
public interface SandView {

	/** 알갱이 값 ({@link Grain} 으로 읽는다), 빈칸이면 0. */
	short grain(int gy, int gx);

	/** 판 전체를 {@code dst}(길이 {@code WIDTH × HEIGHT}, 행 우선)에 복사한다. */
	void copyGrains(short[] dst);

	/** 움직이는 블록. 다음 블록을 기다리는 중(스폰 자리에 아직 모래가 흐름)이거나 고정하며 끝난 판이면 null. */
	PieceState piece();

	/** 움직이는 블록의 모양과 색. 기다리는 중이면 곧 나올 블록, 고정하며 끝난 판이면 마지막 블록. */
	SandPiece current();

	/**
	 * 지금 하드드롭하면 놓일 자리 (고스트). 블록이 없으면 null. 알갱이가 하나라도 있는 칸 위에 놓이므로 아래 모래가
	 * 흐르는 동안에는 고스트도 따라 움직인다(지금 하드드롭하면 실제로 거기 놓인다).
	 */
	PieceState ghost();

	/**
	 * 움직이는 블록이 지금 고정되면 남길 알갱이 값 256개를 {@code dst} 에 쓴다. 순서는 {@code k × 64 + 행 × 8 + 열} 이고
	 * k 는 {@code piece().cells()} 의 칸 순서다. 떨어지는 블록을 내려앉을 모래와 같은 무늬로 그릴 때 쓴다. 블록이 없으면
	 * 아무것도 쓰지 않는다.
	 */
	void pieceGrains(short[] dst);

	/** 홀드 칸, 비었으면 null. */
	SandPiece hold();

	/** 지금 블록에서 홀드를 이미 썼는가. */
	boolean holdUsed();

	/** 미리보기 {@link SandRules#PREVIEW}개 (다음 블록이 먼저). 모양과 색이 함께 있다. */
	List<SandPiece> preview();

	/** 판 시작부터 흐른 시간 (ms). */
	long time();

	/** 지금 단계 1~10. */
	int level();

	long score();

	/** 최고 점수: 저장된 기록과 이번 점수 중 큰 것. */
	long best();

	/** 이번 점수가 저장된 기록(0 보다 큰)을 넘었는가. 첫 판은 새 기록으로 치지 않는다. */
	boolean newBest();

	/** 콤보 배율 1~10. */
	int multiplier();

	/** 콤보 배율이 한 단계 내려가기까지 남은 시간 (ms, 0 이면 멈춤). 막대는 이 값 ÷ {@link SandRules#COMBO_MS}. */
	int comboLeft();

	/** 지금까지 지운 알갱이 수. */
	int grainsCleared();

	/** 지금까지 지우기 횟수. */
	int clears();

	/** 지금까지 고정한 블록 수. */
	int pieces();

	/** 이번 판의 가장 높은 콤보 배율. */
	int maxMultiplier();

	/** 마지막 지우기, 없으면 null. {@link #clears()} 가 바뀌면 새 지우기다. */
	ClearEvent lastClear();

	/** 마지막 고정, 없으면 null. {@link #pieces()} 가 바뀌면 새 고정이다. */
	LockEvent lastLock();

	/** 판이 끝났는가 (정착한 모래가 스폰 여백이나 그 위에 있음). */
	boolean over();

	/** 블록이 바닥(알갱이가 있는 칸이나 판 바닥)에 닿아 있는가. */
	boolean grounded();

	/** 바닥에 닿아 있을 때 남은 락 딜레이 (ms). */
	int lockTimer();

	/** 지금까지 블록을 꺼낸 횟수 (홀드로 꺼낸 것 포함). 화면이 새 블록을 알아챌 때 쓴다. */
	int spawns();

	/** 알갱이가 있는 가장 위 알갱이 행 (흐르는 알갱이 포함), 비었으면 {@link SandRules#HEIGHT}. */
	int surfaceRow();

	/**
	 * 정착한(더 움직일 수 없는) 알갱이가 있는 가장 위 알갱이 행, 없으면 {@link SandRules#HEIGHT}. 높은 곳에서 부서진 블록이
	 * 잠깐 지나가도 바뀌지 않으므로 위험 표시에 쓴다. {@link SandRules#VISIBLE_TOP} 보다 작아지면 판이 끝난다.
	 */
	int settledTop();

	/**
	 * 얇은 띠의 알갱이 수: 양쪽 벽을 이었지만 알갱이가 지우기 문턱({@link SandRules#MIN_CLEAR_GRAINS})보다 적어 지워지지 않고
	 * 남은 같은 색 덩어리들(sand-0.3). 없으면 0. 화면이 "이어졌지만 아직 얇다" 를 살짝 표시할 때 쓴다.
	 */
	int thinGrains();

	/**
	 * 얇은 띠의 알갱이 자리({@code 행 × WIDTH + 열}, 덩어리 차례)를 {@code dst} 앞에서부터 {@link #thinGrains()} 개 쓴다.
	 * {@code dst} 는 그만큼 길어야 한다({@code WIDTH × HEIGHT} 면 언제나 된다).
	 */
	void copyThin(int[] dst);

	/**
	 * 다음 블록이 스폰 자리의 모래가 비켜나기를 기다리는 중인가. 이때는 블록이 없고 조작은 버려진다. 화면은
	 * {@link #current()} 를 스폰 자리에 흐리게 보여 줄 수 있다.
	 */
	default boolean waiting() {
		return piece() == null && !over();
	}
}
