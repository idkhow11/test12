package kr.ac.jbnu.se.tetris.sand;

import java.util.Arrays;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.SplitMix64;

/**
 * 알갱이 판과 모래 물리. 블록, 점수, 시간은 모르고 알갱이만 다룬다. 좌표는 {@code (행, 열)} 이고 <b>0 행이 맨 위</b>다.
 * <p>
 * <b>모래 스텝</b>({@link #step}, sand-0.2 부터): 아래 행부터 위로 훑는다(맨 아래 행은 움직일 곳이 없다). 행마다
 * 훑는 방향을 번갈아({@code (스텝 번호 + 행) 이 짝수면 왼쪽부터}) 한쪽으로 쏠리지 않게 한다. 알갱이마다
 * <ol>
 * <li>바로 아래가 비면 한 칸 내려간다.</li>
 * <li>아니면 아래 대각선으로 한 칸 간다. 단 <b>옆 칸, 대각선 칸, 대각선 칸의 바로 아래 칸이 모두 비어 있어야</b> 한다(옆
 * 칸: 모서리 사이로 빠져나가지 않음. 그 아래 칸: 두 알갱이 넘는 낭떠러지에서만 미끄러짐. 그 아래가 바닥이면 못 간다).
 * 양쪽 다 되면 물리 난수 {@code nextInt(2)} 가 0 이면 왼쪽, 1 이면 오른쪽.</li>
 * </ol>
 * 그래서 멈춘 모래는 이웃한 열의 높이 차가 2 알갱이 이하, 경사가 약 63°(가로 1 에 세로 2)까지 선다(sand-0.1 은 대각선 칸의
 * 아래를 보지 않아 차 1, 45° 였다). 움직인 알갱이는 이미 훑은 아래 행에 놓이므로 한 스텝에 두 번 움직이지 않는다. 반짝이는
 * 알갱이는 움직이지 않고 장애물이 된다. 움직이는 블록의 칸도 장애물로 넘길 수 있다.
 * <p>
 * <b>지우기</b>({@link #markSpans}, 규칙 {@code sand-0.3}): 색마다 왼쪽 벽의 알갱이에서 8방향(대각선 포함)으로 같은 색을
 * 따라가, 오른쪽 벽에 닿은 덩어리 가운데 알갱이가 이 판의 지우기 문턱({@link #minClear()}) 이상인 것 전체에 반짝임 표시를 한다.
 * 문턱보다 작은 덩어리("얇은 띠")는 남기고 자리만 적어 둔다({@link #thinCount()}, 화면 표시용). 표시한 알갱이는 {@link #remove}
 * 로 지운다. 게임 판({@link #standard})의 문턱은 {@link SandRules#MIN_CLEAR_GRAINS}, 크기만 주고 만든 판은 0(모두 지운다)이다.
 * <p>
 * <b>블록과의 경계</b>: 블록은 칸 단위로 움직이고 알갱이가 하나라도 있는 칸에는 들어가지 못한다({@link #cellGrid} 가 그
 * 칸들을 막은 core 판). 고정된 블록은 {@link #place} 로 알갱이가 된다.
 * <p>
 * <b>시뮬레이션</b>(AI): {@link #copy} 나 {@link #copyFrom} 으로 옮긴 판에 {@link #place} 와 {@link #settle}(또는
 * {@link #step}, {@link #markSpans}, {@link #remove})을 쓰면 게임과 같은 규칙으로 돌아간다(반짝임 시간만 빼고).
 * <p>
 * 같은 판, 같은 난수 상태, 같은 장애물이면 언제나 같은 결과가 나온다. 부동소수점과 시계를 쓰지 않는다.
 */
public final class SandField {

	private static final int[] NONE = new int[0];
	private static final int GRAIN = SandRules.GRAIN;

	private final int width;
	private final int height;
	private final int cellCols;
	private final int cellRows;
	private final short[] g;
	private final int[] rowCount;
	private final int[] cellCount;
	private SplitMix64 rng;
	private long steps;
	private int count;
	private int flashing;
	private long cellVersion;
	/** 지우기 문턱: 양쪽 벽을 이은 덩어리라도 알갱이가 이보다 적으면 지우지 않는다(0 이면 모두 지운다). 복사본이 옮겨 받는다. */
	private int minClear;
	/** 마지막 {@link #markSpans} 가 찾은 얇은 띠(양쪽 벽을 이었지만 문턱보다 작은 덩어리)의 알갱이 자리와 그 수. */
	private int[] thin = NONE;
	private int thinCount;

	// 조용한 행 건너뛰기. 알갱이가 바뀔 때마다 오르는 시계, 행마다 마지막으로 바뀐 때(rowChanged)와 마지막으로 훑었는데
	// 아무것도 움직이지 못한 때(rowQuiet). 행 y 에서 움직일 수 있는지는 행 y, y + 1, y + 2 와 그 칸들의 장애물만으로
	// 정해지므로, 그 뒤로 셋 다 그대로면 이번에도 아무것도 움직이지 못한다(훑는 방향과 상관없고, 난수도 쓰지 않는다). 그래서
	// 건너뛰어도 결과가 비트 단위로 같다.
	private long changeClock;
	private final long[] rowChanged;
	private final long[] rowQuiet;
	private final boolean[] lastObstacles;

	// 지우기 검사에 다시 쓰는 버퍼 (방문 표시는 검사마다 번호를 바꿔 지우지 않고 쓴다)
	private final int[] visit;
	private int visitStamp;
	private final int[] queue;
	private final int[] marks;
	// 정착 검사에 다시 쓰는 줄 세 개 (이 행, 아래 행, 그 아래 행)
	private final boolean[] stableA;
	private final boolean[] stableB;
	private final boolean[] stableC;

	/**
	 * 지우기 문턱이 0 인 판 (양쪽 벽을 이은 덩어리는 크기와 상관없이 지운다). 손으로 만든 작은 시험 판과 도구가 쓴다. 게임과 같은
	 * 규칙의 판은 {@link #standard}.
	 *
	 * @param width  알갱이 열 수
	 * @param height 알갱이 행 수
	 * @param rng    양쪽 대각선이 모두 빌 때 쓰는 물리 난수. 이 판이 가진다
	 */
	public SandField(int width, int height, SplitMix64 rng) {
		this(width, height, rng, 0);
	}

	/**
	 * 지우기 문턱을 정해 만든다 (게임 판은 {@link #standard}, 시험은 0 이나 작은 값).
	 *
	 * @param minClear 지우는 덩어리의 최소 알갱이 수 (0 이상)
	 */
	SandField(int width, int height, SplitMix64 rng, int minClear) {
		if (width < 2 || height < 2) {
			throw new IllegalArgumentException("판이 너무 작다: " + width + " × " + height);
		}
		if (minClear < 0) {
			throw new IllegalArgumentException("지우기 문턱이 음수: " + minClear);
		}
		this.minClear = minClear;
		this.width = width;
		this.height = height;
		this.cellCols = (width + GRAIN - 1) / GRAIN;
		this.cellRows = (height + GRAIN - 1) / GRAIN;
		this.g = new short[width * height];
		this.rowCount = new int[height];
		this.cellCount = new int[cellCols * cellRows];
		this.rng = rng;
		this.rowChanged = new long[height];
		this.rowQuiet = new long[height];
		Arrays.fill(rowQuiet, -1);
		this.lastObstacles = new boolean[cellCols * cellRows];
		this.visit = new int[width * height];
		this.queue = new int[width * height];
		this.marks = new int[width * height];
		this.stableA = new boolean[width];
		this.stableB = new boolean[width];
		this.stableC = new boolean[width];
	}

	/**
	 * 게임 판 (80 × 192 알갱이, 지우기 문턱 {@link SandRules#MIN_CLEAR_GRAINS}, 물리 난수는 게임 시드의 스트림
	 * {@link SandRules#STREAM_PHYSICS}).
	 */
	public static SandField standard(long seed) {
		return standard(SplitMix64.stream(seed, SandRules.STREAM_PHYSICS));
	}

	/** 게임 판과 크기·지우기 문턱이 같은 빈 판. 물리 난수만 따로 준다 (AI 가 고를 때 쓰는 판). */
	public static SandField standard(SplitMix64 rng) {
		return new SandField(SandRules.WIDTH, SandRules.HEIGHT, rng, SandRules.MIN_CLEAR_GRAINS);
	}

	/** 알갱이, 개수, 난수 상태, 지우기 문턱까지 옮긴 독립된 복사본. 이어서 같은 스텝을 하면 원본과 같은 결과가 나온다. */
	public SandField copy() {
		SandField c = new SandField(width, height, null); // 난수는 copyFrom 이 옮긴다
		c.copyFrom(this);
		return c;
	}

	/**
	 * {@code o} 의 알갱이, 개수, 스텝 수, 난수 상태, 지우기 문턱(과 마지막으로 찾은 얇은 띠)을 이 판에 옮긴다. 배열을 새로 만들지
	 * 않으므로 AI 가 후보마다 같은 판에서 시작해 볼 때 판 하나를 되풀이해 쓸 수 있다.
	 *
	 * @throws IllegalArgumentException 크기가 다르면
	 */
	public void copyFrom(SandField o) {
		if (o.width != width || o.height != height) {
			throw new IllegalArgumentException("크기가 다른 판: " + o.width + " × " + o.height);
		}
		if (o == this) {
			return;
		}
		System.arraycopy(o.g, 0, g, 0, g.length);
		System.arraycopy(o.rowCount, 0, rowCount, 0, rowCount.length);
		System.arraycopy(o.cellCount, 0, cellCount, 0, cellCount.length);
		rng = o.rng.copy();
		steps = o.steps;
		count = o.count;
		flashing = o.flashing;
		minClear = o.minClear;
		if (thin.length < o.thinCount) {
			thin = new int[o.thin.length];
		}
		System.arraycopy(o.thin, 0, thin, 0, o.thinCount);
		thinCount = o.thinCount;
		cellVersion = Math.max(cellVersion, o.cellVersion) + 1; // 이 판의 칸 판을 캐시한 쪽이 반드시 다시 만들게
		Arrays.fill(rowQuiet, -1); // 다음 스텝은 모든 행을 훑는다
	}

	// ------------------------------------------------------------------ 알갱이 읽기·쓰기

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	public boolean inside(int y, int x) {
		return y >= 0 && y < height && x >= 0 && x < width;
	}

	/** 알갱이 값 ({@link Grain}), 빈칸이면 0. */
	public short get(int y, int x) {
		return g[y * width + x];
	}

	/** 칸 번호({@code 행 × 너비 + 열})로 읽는다. */
	short at(int index) {
		return g[index];
	}

	/** 알갱이를 놓거나({@code v != 0}) 지운다({@code v == 0}). 개수와 칸 점유를 함께 고친다. */
	public void set(int y, int x, short v) {
		int i = y * width + x;
		short old = g[i];
		rowChanged[y] = ++changeClock;
		int cell = (y / GRAIN) * cellCols + x / GRAIN;
		if (old != Grain.EMPTY) {
			count--;
			rowCount[y]--;
			if (Grain.flashing(old)) {
				flashing--;
			}
			if (--cellCount[cell] == 0) {
				cellVersion++;
			}
		}
		g[i] = v;
		if (v != Grain.EMPTY) {
			count++;
			rowCount[y]++;
			if (Grain.flashing(v)) {
				flashing++;
			}
			if (cellCount[cell]++ == 0) {
				cellVersion++;
			}
		}
	}

	/** 판 전체를 {@code dst} 에 복사한다(행 우선, 길이 너비 × 높이). 화면이 잠금을 짧게 잡고 그릴 때 쓴다. */
	public void copyTo(short[] dst) {
		System.arraycopy(g, 0, dst, 0, g.length);
	}

	/** 알갱이 수. */
	public int count() {
		return count;
	}

	/** 반짝이는(지워질) 알갱이 수. */
	public int flashing() {
		return flashing;
	}

	/** 지우기 문턱: 양쪽 벽을 이은 덩어리의 알갱이가 이보다 적으면 지우지 않는다 (0 이면 모두 지운다). */
	public int minClear() {
		return minClear;
	}

	/**
	 * 마지막 {@link #markSpans} 가 찾은 얇은 띠(양쪽 벽을 이었지만 알갱이가 {@link #minClear()} 보다 적어 남긴 같은 색 덩어리)의
	 * 알갱이 수, 없으면 0. 화면이 "이어졌지만 아직 얇다" 를 살짝 표시할 때 쓴다. 게임은 알갱이가 움직이거나 놓일 때마다 검사하므로
	 * 게임 판에서는 언제나 지금 판의 값이다(반짝이는 덩어리가 사라져도 남은 알갱이의 연결은 그대로다).
	 */
	public int thinCount() {
		return thinCount;
	}

	/** 얇은 띠의 알갱이 자리({@code 행 × 너비 + 열}, 덩어리 차례)를 {@code dst} 앞에서부터 {@link #thinCount()} 개 쓴다. */
	public void copyThin(int[] dst) {
		System.arraycopy(thin, 0, dst, 0, thinCount);
	}

	public int rowCount(int y) {
		return rowCount[y];
	}

	/** 알갱이가 있는 가장 위 행, 비었으면 {@link #height()}. */
	public int topRow() {
		for (int y = 0; y < height; y++) {
			if (rowCount[y] > 0) {
				return y;
			}
		}
		return height;
	}

	/** 칸({@link SandRules#GRAIN} × GRAIN 알갱이) 안에 알갱이가 하나라도 있는가. 블록은 이런 칸에 들어갈 수 없다. */
	public boolean cellOccupied(int cellRow, int cellCol) {
		return cellCount[cellRow * cellCols + cellCol] > 0;
	}

	/** 어떤 칸의 점유가 바뀔 때마다 오르는 번호. 블록 충돌용 칸 판을 다시 만들지 정할 때 쓴다. */
	public long cellVersion() {
		return cellVersion;
	}

	/**
	 * 알갱이가 하나라도 있는 칸을 막은 칸({@link Grid#GARBAGE}), 나머지를 빈칸으로 {@code dst} 에 쓴다. 블록 충돌과 배치
	 * 탐색(core {@code PlacementFinder})이 이 판을 그대로 쓴다. 칸 판과 크기가 같은 표준 판(80 × 192)만 된다.
	 *
	 * @return {@code dst}
	 */
	public Grid cellGrid(Grid dst) {
		if (cellCols != Grid.WIDTH || cellRows != Grid.HEIGHT) {
			throw new IllegalStateException("표준 판만 칸 판으로 옮길 수 있다: " + width + " × " + height);
		}
		for (int r = 0; r < cellRows; r++) {
			for (int c = 0; c < cellCols; c++) {
				dst.set(r, c, cellCount[r * cellCols + c] > 0 ? Grid.GARBAGE : Grid.EMPTY);
			}
		}
		return dst;
	}

	/**
	 * 블록을 알갱이로 부순다: 블록의 칸 4개에 알갱이 {@link SandRules#PIECE_GRAINS}개를 놓는다.
	 *
	 * @param grains 칸마다 64개씩, 순서는 {@code k × 64 + 칸 안 행 × 8 + 칸 안 열} (k 는 {@code piece.cells()} 의 칸 순서)
	 * @throws IllegalStateException 블록 칸이 판 밖이거나 알갱이가 이미 있으면. 이때 판은 그대로다
	 */
	public void place(PieceState piece, short[] grains) {
		int[][] cells = piece.cells();
		for (int[] c : cells) {
			if (c[0] < 0 || c[0] >= cellRows || c[1] < 0 || c[1] >= cellCols || cellCount[c[0] * cellCols + c[1]] > 0) {
				throw new IllegalStateException("블록을 놓을 수 없는 칸: " + piece);
			}
		}
		for (int k = 0; k < cells.length; k++) {
			for (int oy = 0; oy < GRAIN; oy++) {
				for (int ox = 0; ox < GRAIN; ox++) {
					set(cells[k][0] * GRAIN + oy, cells[k][1] * GRAIN + ox, grains[(k * GRAIN + oy) * GRAIN + ox]);
				}
			}
		}
	}

	/** {@link #place(PieceState, short[])} 를 명암 0 의 알갱이로 한다 (AI 의 시뮬레이션처럼 무늬가 필요 없을 때). */
	public void place(PieceState piece, SandColor color) {
		short[] grains = new short[SandRules.PIECE_GRAINS];
		for (int i = 0; i < grains.length; i++) {
			grains[i] = Grain.of(color, 0, (i / GRAIN) % GRAIN, i % GRAIN);
		}
		place(piece, grains);
	}

	/** 지금까지 한 모래 스텝 수. 다음 스텝의 훑는 방향을 정한다. */
	public long steps() {
		return steps;
	}

	// ------------------------------------------------------------------ 모래 스텝

	/**
	 * 모래 스텝 한 번. 지난번에 훑었을 때 아무것도 움직이지 못했고 그 뒤로 그 행과 아래 두 행(장애물 포함)이 그대로인 행은
	 * 건너뛴다. 결과는 모든 행을 훑을 때와 비트 단위로 같다(SandFieldTest 의 기준 구현과 비교).
	 *
	 * @param obstacleCells 알갱이가 들어갈 수 없는 칸(칸 행 × 칸 열 수 + 칸 열, 움직이는 블록), 없으면 null
	 * @return 움직인 알갱이 수. 0 이면 이 스텝에서 판이 그대로다
	 */
	public int step(boolean[] obstacleCells) {
		noteObstacles(obstacleCells);
		long s = steps++;
		int moved = 0;
		for (int y = height - 2; y >= 0; y--) {
			long quiet = rowQuiet[y];
			if (rowCount[y] == 0 || rowChanged[y] <= quiet && rowChanged[y + 1] <= quiet
					&& (y + 2 >= height || rowChanged[y + 2] <= quiet)) {
				continue; // 비었거나, 지난번에 아무것도 못 움직였고 그 뒤로 이 행과 아래 두 행이 그대로다
			}
			int before = moved;
			boolean leftFirst = ((s + y) & 1) == 0;
			for (int k = 0; k < width; k++) {
				int x = leftFirst ? k : width - 1 - k;
				short v = g[y * width + x];
				if (v == Grain.EMPTY || Grain.flashing(v)) {
					continue;
				}
				if (free(y + 1, x, obstacleCells)) {
					move(y, x, y + 1, x);
					moved++;
					continue;
				}
				boolean left = x > 0 && canSlide(y, x - 1, obstacleCells);
				boolean right = x < width - 1 && canSlide(y, x + 1, obstacleCells);
				if (left && right) {
					if (rng.nextInt(2) == 0) {
						right = false;
					} else {
						left = false;
					}
				}
				if (left) {
					move(y, x, y + 1, x - 1);
					moved++;
				} else if (right) {
					move(y, x, y + 1, x + 1);
					moved++;
				}
			}
			if (moved == before) {
				rowQuiet[y] = changeClock;
			}
		}
		return moved;
	}

	/** 장애물 칸이 지난 스텝과 다르면 그 칸의 알갱이 행들을 바뀐 것으로 적는다(다음 스텝에 다시 훑게). */
	private void noteObstacles(boolean[] obstacleCells) {
		for (int i = 0; i < lastObstacles.length; i++) {
			boolean now = obstacleCells != null && obstacleCells[i];
			if (now != lastObstacles[i]) {
				lastObstacles[i] = now;
				int top = (i / cellCols) * GRAIN;
				long t = ++changeClock;
				for (int y = top; y < Math.min(top + GRAIN, height); y++) {
					rowChanged[y] = t;
				}
			}
		}
	}

	private boolean free(int y, int x, boolean[] obstacleCells) {
		return g[y * width + x] == Grain.EMPTY
				&& (obstacleCells == null || !obstacleCells[(y / GRAIN) * cellCols + x / GRAIN]);
	}

	/**
	 * 행 y 의 알갱이가 옆 열 {@code nx} 로 미끄러질 수 있는가: 옆 칸 (y, nx), 아래 대각선 칸 (y + 1, nx), 그 바로 아래 칸
	 * (y + 2, nx) 이 모두 비었다. 그 아래 칸이 바닥(판 밖)이면 미끄러지지 않는다.
	 */
	private boolean canSlide(int y, int nx, boolean[] obstacleCells) {
		return y + 2 < height && free(y, nx, obstacleCells) && free(y + 1, nx, obstacleCells)
				&& free(y + 2, nx, obstacleCells);
	}

	private void move(int y, int x, int ny, int nx) {
		int from = y * width + x;
		int to = ny * width + nx;
		g[to] = g[from];
		g[from] = Grain.EMPTY;
		rowCount[y]--;
		rowCount[ny]++;
		long t = ++changeClock;
		rowChanged[y] = t;
		rowChanged[ny] = t;
		int fc = (y / GRAIN) * cellCols + x / GRAIN;
		int tc = (ny / GRAIN) * cellCols + nx / GRAIN;
		if (fc != tc) {
			if (--cellCount[fc] == 0) {
				cellVersion++;
			}
			if (cellCount[tc]++ == 0) {
				cellVersion++;
			}
		}
	}

	// ------------------------------------------------------------------ 지우기

	/**
	 * 왼쪽 벽과 오른쪽 벽을 잇는 같은 색 덩어리(8방향 연결) 가운데 알갱이가 지우기 문턱({@link #minClear()}) 이상인 것을 모두
	 * 찾아 반짝임 표시를 한다(sand-0.3). 문턱보다 작은 덩어리는 그대로 두고 얇은 띠로 적어 둔다({@link #thinCount()}). 이미
	 * 반짝이는 알갱이는 연결에 쓰지 않는다. 한 번에 찾은 덩어리는 모두 함께 표시하므로 찾는 순서와 결과가 무관하다.
	 *
	 * @return 표시한 알갱이의 칸 번호({@code 행 × 너비 + 열}). 없으면 빈 배열
	 */
	public int[] markSpans() {
		thinCount = 0;
		int rightColors = 0;
		for (int y = 0; y < height; y++) {
			short v = g[y * width + width - 1];
			if (v != Grain.EMPTY && !Grain.flashing(v)) {
				rightColors |= 1 << Grain.code(v);
			}
		}
		if (rightColors == 0) {
			return NONE;
		}
		if (++visitStamp == Integer.MAX_VALUE) {
			Arrays.fill(visit, 0);
			visitStamp = 1;
		}
		int tail = 0;
		int marked = 0;
		for (int y = 0; y < height; y++) {
			int seed = y * width;
			short v = g[seed];
			if (v == Grain.EMPTY || Grain.flashing(v) || visit[seed] == visitStamp) {
				continue;
			}
			int color = Grain.code(v);
			if ((rightColors & (1 << color)) == 0) {
				continue;
			}
			int start = tail;
			int head = tail;
			queue[tail++] = seed;
			visit[seed] = visitStamp;
			boolean reached = false;
			while (head < tail) {
				int i = queue[head++];
				int cy = i / width;
				int cx = i - cy * width;
				if (cx == width - 1) {
					reached = true;
				}
				for (int ny = Math.max(cy - 1, 0); ny <= Math.min(cy + 1, height - 1); ny++) {
					for (int nx = Math.max(cx - 1, 0); nx <= Math.min(cx + 1, width - 1); nx++) {
						int n = ny * width + nx;
						if (visit[n] == visitStamp) {
							continue;
						}
						short w = g[n];
						if (w == Grain.EMPTY || Grain.flashing(w) || Grain.code(w) != color) {
							continue;
						}
						visit[n] = visitStamp;
						queue[tail++] = n;
					}
				}
			}
			if (reached) {
				if (tail - start >= minClear) {
					for (int k = start; k < tail; k++) {
						marks[marked++] = queue[k];
					}
				} else {
					keepThin(start, tail); // 양쪽 벽을 이었지만 아직 얇다: 남기고 자리만 적는다
				}
			}
		}
		for (int k = 0; k < marked; k++) {
			int i = marks[k];
			g[i] = Grain.flash(g[i]);
		}
		flashing += marked;
		return marked == 0 ? NONE : Arrays.copyOf(marks, marked);
	}

	/** 검사 줄의 {@code queue[from, to)} (덩어리 하나)를 얇은 띠로 적어 둔다. */
	private void keepThin(int from, int to) {
		int n = to - from;
		if (thinCount + n > thin.length) {
			thin = Arrays.copyOf(thin, Math.max(thinCount + n, Math.max(256, 2 * thin.length)));
		}
		System.arraycopy(queue, from, thin, thinCount, n);
		thinCount += n;
	}

	/** 칸 번호의 알갱이를 지운다 (반짝임이 끝난 덩어리). */
	public void remove(int[] indices) {
		for (int i : indices) {
			int y = i / width;
			set(y, i - y * width, Grain.EMPTY);
		}
	}

	/** 반짝이는 알갱이를 모두 지운다. @return 지운 수 */
	public int removeFlashing() {
		int n = 0;
		for (int i = 0; i < g.length && flashing > 0; i++) {
			if (Grain.flashing(g[i])) {
				int y = i / width;
				set(y, i - y * width, Grain.EMPTY);
				n++;
			}
		}
		return n;
	}

	// ------------------------------------------------------------------ 정착

	/**
	 * 행 {@code row} 보다 위(행 번호가 작은 곳)에 정착한 알갱이가 있는가.
	 * <p>
	 * <b>정착</b>: 바로 아래가 바닥이나 정착한 알갱이이고, 양옆 저마다 벽이거나 아래 대각선 칸이나 그 바로 아래 칸이 바닥이나
	 * 정착한 알갱이인 알갱이(바닥 = 판의 맨 아래 밖). 아래 행부터 한 번 훑어 정한다. 이런 알갱이는 지우기로 무언가 사라지기
	 * 전에는 다시 움직일 수 없다(떨어질 곳이 막혔고, 양옆 모두 미끄러지는 데 필요한 세 칸 중 하나가 영영 막혔다). 모래가 다
	 * 멈추면 반짝이는 덩어리 위의 것을 빼고는 모든 알갱이가 정착이다: 옆 칸이 차 있는데 대각선 칸이 비면 그 옆 칸 알갱이가
	 * 떨어지므로, 멈춘 판에서는 양옆마다 대각선 칸이나 그 아래 칸이 차 있다. 반짝이는 알갱이와 움직이는 블록은 곧 사라지거나
	 * 움직이므로 받침으로 치지 않는다.
	 */
	public boolean hasSettledAbove(int row) {
		for (int y = 0; y < Math.min(row, height); y++) {
			if (rowCount[y] > 0) {
				return settledTop() < row; // 그 위에 알갱이가 있을 때만 훑는다
			}
		}
		return false;
	}

	/**
	 * 정착한 알갱이({@link #hasSettledAbove} 의 정의)가 있는 가장 위 행, 없으면 {@link #height()}. 흐르는 알갱이를 빼고 본
	 * 쌓인 높이라서, 높은 곳에서 부서진 블록이 잠깐 지나가도 바뀌지 않는다(화면의 위험 표시).
	 */
	public int settledTop() {
		boolean[] below2 = stableC; // 두 행 아래
		boolean[] below = stableA; // 한 행 아래
		boolean[] here = stableB;
		Arrays.fill(below2, true); // 바닥
		Arrays.fill(below, true);
		int top = height;
		for (int y = height - 1; y >= 0 && rowCount[y] > 0; y--) {
			boolean anyStable = false;
			int base = y * width;
			for (int x = 0; x < width; x++) {
				short v = g[base + x];
				boolean s = v != Grain.EMPTY && !Grain.flashing(v) && below[x]
						&& (x == 0 || below[x - 1] || below2[x - 1])
						&& (x == width - 1 || below[x + 1] || below2[x + 1]);
				here[x] = s;
				anyStable |= s;
			}
			if (!anyStable) {
				break; // 이 행에 정착한 알갱이가 없으면 그 위에도 없다 (바로 아래가 정착해야 정착이다)
			}
			top = y;
			boolean[] t = below2;
			below2 = below;
			below = here;
			here = t;
		}
		return top;
	}

	// ------------------------------------------------------------------ 시뮬레이션 (AI)

	/**
	 * 모래가 멈출 때까지 스텝을 되풀이한다(움직이는 블록 없음). 이미 반짝이는 덩어리는 곧 사라질 것이므로 먼저 지운다(세지
	 * 않는다). 처음과, 모래가 멈출 때마다 지우기 검사를 해 찾은 덩어리는 반짝임 없이 바로 지우고 다시 흘린다. 게임은 스텝마다
	 * 검사하고 덩어리가 200ms 반짝이므로 끝 모습이 조금 다를 수 있지만, 배치를 비교하는 데는 충분하고 훨씬 빠르다.
	 *
	 * @param maxSteps 스텝 상한 (넘으면 멈춘 데까지)
	 * @return 새로 지운 알갱이 수
	 */
	public int settle(int maxSteps) {
		removeFlashing();
		int removed = 0;
		int done = 0;
		while (true) {
			int[] marked = markSpans();
			removed += marked.length;
			remove(marked);
			boolean moved = false;
			while (done < maxSteps && step(null) > 0) {
				done++;
				moved = true;
			}
			if (!moved || done >= maxSteps) {
				return removed; // 지운 뒤에도 움직인 것이 없으면 새 덩어리는 생기지 않는다
			}
		}
	}

	// ------------------------------------------------------------------ 글자 판 (시험·디버깅)

	/** 글자 판: {@code .} 빈칸, {@code R B G Y} 색, 소문자는 반짝이는 알갱이. 명암과 원래 칸 위치는 0. */
	static SandField parse(SplitMix64 rng, String... rows) {
		SandField f = new SandField(rows[0].length(), rows.length, rng);
		for (int y = 0; y < rows.length; y++) {
			if (rows[y].length() != f.width) {
				throw new IllegalArgumentException("행마다 길이가 같아야 한다: " + rows[y]);
			}
			for (int x = 0; x < f.width; x++) {
				char ch = rows[y].charAt(x);
				if (ch == '.') {
					continue;
				}
				short v = Grain.of(SandColor.fromLetter(Character.toUpperCase(ch)), 0, 0, 0);
				f.set(y, x, Character.isLowerCase(ch) ? Grain.flash(v) : v);
			}
		}
		return f;
	}

	/** {@link #parse} 와 같은 글자 판. 행마다 줄바꿈. */
	public String toText() {
		StringBuilder sb = new StringBuilder();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				short v = get(y, x);
				if (v == Grain.EMPTY) {
					sb.append('.');
				} else {
					char ch = Grain.color(v).letter();
					sb.append(Grain.flashing(v) ? Character.toLowerCase(ch) : ch);
				}
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
