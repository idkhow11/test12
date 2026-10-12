package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.Arrays;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 멈춘 모래 판을 "열마다 바닥부터 쌓인 색" 으로 본 빠른 시뮬레이션 판. AI 가 후보마다 블록을 부수고, 흘리고, 지우는 일을
 * 엔진의 모래 스텝({@link SandField#settle})보다 수십 배 빨리 흉내 낸다.
 * <p>
 * 멈춘 모래에는 빈 곳이 없다: 알갱이는 바로 아래가 비면 떨어지므로 열마다 바닥부터 꽉 차 있고, 이웃한 열의 높이 차는 2 이하다
 * (모래 package-info 4장, sand-0.2 부터). 그래서 판을 열 높이와 열마다의 색 순서만으로 적을 수 있다. 열 x 의 바닥에서
 * l 번째(0 부터) 알갱이는 알갱이 행 {@code 191 - l} 에 있다.
 * <ol>
 * <li><b>부서짐</b>({@link #drop}): 블록의 알갱이는 제 열에서 곧장 떨어져 그 열 위에 쌓인다.</li>
 * <li><b>흘러내림</b>({@link #relax}): 열이 이웃 열보다 3 이상 높으면 그 절벽에서 미끄러질 수 있는 가장 낮은 알갱이(이웃 열
 * 높이 + 2 번째: 대각선 칸과 그 아래 칸이 비어야 미끄러진다)가 이웃 열 위로 미끄러지고 그 위 알갱이들이 한 칸씩 내려앉는다.
 * 엔진의 모래 스텝이 아래 행부터 훑어 절벽이 밑에서부터 무너지는 것과 같다(그래서 무너지는 더미 위에 놓인 블록은 더미와 함께
 * 가라앉는다). 모든 열이 안정할 때까지 되풀이한다(먼저 들어온 열부터, 한 번에 한 알갱이). 양쪽 다 낮으면 더 낮은 쪽, 같으면
 * 번갈아 고른다. 엔진은 이때 물리 난수로 고르므로 알갱이 하나하나의 자리는 다를 수 있지만 쌓인 모양(약 63° 경사)은 같다.</li>
 * <li><b>지우기</b>({@link #settle}): 열마다 같은 색이 이어진 토막으로 나누고, 이웃한 열의 같은 색 토막이 8방향으로 닿으면(높이
 * 구간이 1 이내로 겹치면) 한 덩어리로 묶는다. 왼쪽 벽과 오른쪽 벽에 모두 닿았고 알갱이가 지우기 문턱({@link #minClear}, 규칙
 * {@code sand-0.3} 의 {@link SandRules#MIN_CLEAR_GRAINS}) 이상인 덩어리를 지우고, 열을 눌러 담은 뒤(위 알갱이가 떨어진다) 다시
 * 흘리고 검사한다(연쇄). 엔진의 {@code settle} 처럼 모래가 멈출 때마다 검사하고 반짝임 시간은 없다. 문턱은 엔진 판에서 옮겨 올
 * 때 그 판의 것을 읽으므로({@link #of(SandField)}) 엔진과 같은 덩어리를 지운다.</li>
 * </ol>
 * 블록을 막 놓은 판에서 움직이는 알갱이는 모두 그 블록의 것(한 색)이라, 어느 알갱이가 먼저 미끄러지는지는 색 배치에 영향이
 * 없다. 연쇄로 여러 색이 함께 떨어질 때만 엔진과 조금 달라질 수 있다(PileTest 가 엔진과 견준다).
 * <p>
 * 한 스레드만 쓴다. 부동소수점과 난수를 쓰지 않으므로 같은 판, 같은 조작이면 언제나 같은 결과다.
 */
final class Pile {

	static final int W = SandRules.WIDTH;
	static final int H = SandRules.HEIGHT;
	private static final int G = SandRules.GRAIN;
	/** 이 높이(알갱이 수)를 넘으면 진다: 정착한 알갱이가 보이는 판 위(알갱이 0~31 행)에 있다. */
	static final int LOSS_HEIGHT = H - SandRules.VISIBLE_TOP;
	/**
	 * 열이 이웃 열보다 이만큼 높으면 흘러내린다 (3). 엔진의 알갱이는 대각선 칸과 그 바로 아래 칸이 모두 비어야 미끄러지므로,
	 * 멈춘 판에서 이웃한 열의 높이 차는 {@code SLIDE_DROP - 1} = 2 이하다.
	 */
	static final int SLIDE_DROP = 3;

	/** {@code color[x * H + l]}: 열 x 의 바닥에서 l 번째 알갱이의 색 번호 1~4. {@code l >= height[x]} 는 쓰지 않는다. */
	final byte[] color = new byte[W * H];
	/** 열마다 알갱이 수 (= 높이). */
	final int[] height = new int[W];
	/** 지우기 문턱: 양쪽 벽을 이은 덩어리라도 알갱이가 이보다 적으면 지우지 않는다. 처음은 게임 규칙, 엔진 판에서 옮기면 그 판의 것. */
	int minClear = SandRules.MIN_CLEAR_GRAINS;

	// 흘러내림 작업 목록: 열 번호, 먼저 들어온 것부터. 한 열은 한 번만 들어 있으므로 크기 W 의 고리면 된다.
	private final int[] work = new int[W];
	private final boolean[] queued = new boolean[W];
	private int head;
	private int size;
	private boolean flip;

	// 토막(열마다 같은 색이 이어진 구간)과 덩어리(union-find). 크기는 필요할 때 늘린다.
	private final int[] runStart = new int[W + 1];
	private byte[] runColor = new byte[0];
	private short[] runX = new short[0];
	private short[] runLo = new short[0];
	private short[] runHi = new short[0];
	private int[] parent = new int[0];
	private int[] minX = new int[0];
	private int[] maxX = new int[0];
	private int[] grains = new int[0];
	private int runs;
	/** 마지막 {@link #analyze} 이후로 판이 바뀌지 않았는가. */
	private boolean analyzed;

	/** 같은 크기의 빈 판 (지우기 문턱은 게임 규칙 {@link SandRules#MIN_CLEAR_GRAINS}). */
	Pile() {
	}

	/**
	 * 엔진 판을 옮긴다. 반짝이는 알갱이는 곧 사라질 것이므로 빼고, 열 안의 빈 곳은 위 알갱이가 떨어진 것으로 눌러 담는다(멈춘
	 * 판이면 그대로다). 흘리거나 지우지는 않는다. 지우기 문턱은 그 판의 것({@link SandField#minClear()})을 쓴다.
	 */
	static Pile of(SandField f) {
		short[] g = new short[W * H];
		f.copyTo(g);
		Pile p = of(g);
		p.minClear = f.minClear();
		return p;
	}

	/** 알갱이 배열(행 우선, 0 행이 맨 위)을 옮긴다. {@link #of(SandField)} 와 같고 지우기 문턱은 게임 규칙이다. */
	static Pile of(short[] g) {
		Pile p = new Pile();
		for (int x = 0; x < W; x++) {
			int l = 0;
			for (int y = H - 1; y >= 0; y--) {
				short v = g[y * W + x];
				if (v != Grain.EMPTY && !Grain.flashing(v)) {
					p.color[x * H + l++] = (byte) Grain.code(v);
				}
			}
			p.height[x] = l;
		}
		return p;
	}

	/** {@code o} 의 알갱이와 지우기 문턱을 이 판에 옮긴다 (배열을 새로 만들지 않는다). */
	void copyFrom(Pile o) {
		for (int x = 0; x < W; x++) {
			int n = o.height[x];
			System.arraycopy(o.color, x * H, color, x * H, n);
			height[x] = n;
		}
		head = 0;
		size = 0;
		Arrays.fill(queued, false);
		flip = o.flip;
		minClear = o.minClear;
		analyzed = false;
	}

	Pile copy() {
		Pile p = new Pile();
		p.copyFrom(this);
		return p;
	}

	/** 열 x 의 바닥에서 l 번째 알갱이 색 번호, 없으면 0. */
	int colorAt(int x, int l) {
		return l >= 0 && l < height[x] ? color[x * H + l] : 0;
	}

	/** 가장 높은 열의 높이. */
	int maxHeight() {
		int m = 0;
		for (int h : height) {
			m = Math.max(m, h);
		}
		return m;
	}

	/** 알갱이 수. */
	int count() {
		int n = 0;
		for (int h : height) {
			n += h;
		}
		return n;
	}

	/** 정착한 알갱이가 보이는 판 위에 있는가 (게임이라면 진 판). */
	boolean lost() {
		return maxHeight() > LOSS_HEIGHT;
	}

	/**
	 * 알갱이가 하나라도 있는 칸을 막은 칸 판 (블록 충돌용, 엔진의 {@code cellGrid} 와 같은 규칙). 칸 열마다 가장 높은 알갱이가
	 * 든 칸부터 바닥까지 막힌다.
	 */
	Grid cellGrid(Grid dst) {
		for (int c = 0; c < Grid.WIDTH; c++) {
			int top = Grid.HEIGHT; // 막힌 칸이 시작하는 칸 행
			for (int x = c * G; x < c * G + G; x++) {
				if (height[x] > 0) {
					top = Math.min(top, (H - height[x]) / G);
				}
			}
			for (int r = 0; r < Grid.HEIGHT; r++) {
				dst.set(r, c, r >= top ? Grid.GARBAGE : Grid.EMPTY);
			}
		}
		return dst;
	}

	// ------------------------------------------------------------------ 부서짐과 흘러내림

	/**
	 * 블록이 그 자리에서 알갱이로 부서져 제 열로 곧장 떨어진다. 칸마다 8 × 8 알갱이. 블록 칸은 그 열의 모래보다 위에 있어야
	 * 한다(칸 판에서 하드드롭한 자리면 그렇다). 그 뒤 {@link #settle} 로 흘리고 지운다.
	 *
	 * @param cells 칸 {행, 열}
	 * @param code  색 번호 1~4
	 */
	void drop(int[][] cells, int code) {
		byte k = (byte) code;
		for (int[] c : cells) {
			for (int x = c[1] * G; x < c[1] * G + G; x++) {
				int from = x * H + height[x];
				Arrays.fill(color, from, from + G, k);
				height[x] += G;
			}
		}
		for (int[] c : cells) {
			enqueue(c[1] * G - 1);
			for (int x = c[1] * G; x <= c[1] * G + G; x++) {
				enqueue(x);
			}
		}
		analyzed = false;
	}

	private void enqueue(int x) {
		if (x >= 0 && x < W && !queued[x]) {
			queued[x] = true;
			work[(head + size++) % W] = x;
		}
	}

	/**
	 * 모든 열이 안정할 때까지 흘린다: 이웃 열보다 {@value #SLIDE_DROP} 이상 높은 열이 없을 때까지 그 절벽에서 미끄러질 수 있는
	 * 가장 낮은 알갱이를 낮은 쪽 이웃 위로 옮긴다.
	 *
	 * @return 옮긴 횟수 (알갱이 하나가 한 열 옮길 때마다 1)
	 */
	int relax() {
		int moves = 0;
		while (size > 0) {
			int x = work[head];
			head = (head + 1) % W;
			size--;
			queued[x] = false;
			int hx = height[x];
			int hl = x > 0 ? height[x - 1] : Integer.MAX_VALUE;
			int hr = x < W - 1 ? height[x + 1] : Integer.MAX_VALUE;
			boolean left = hl <= hx - SLIDE_DROP;
			boolean right = hr <= hx - SLIDE_DROP;
			if (!left && !right) {
				continue;
			}
			int to;
			if (left && right) {
				to = hl < hr ? x - 1 : hr < hl ? x + 1 : (flip = !flip) ? x - 1 : x + 1;
			} else {
				to = left ? x - 1 : x + 1;
			}
			// 엔진처럼 절벽의 아래에서 빠진다: 옆 열 높이 + 2 의 알갱이(그보다 낮은 알갱이는 대각선 칸의 아래가 차 있어 못 간다)가
			// 옆 열 위로 미끄러지고, 그 위는 한 칸씩 내려앉는다
			int src = x * H + height[to] + SLIDE_DROP - 1;
			byte k = color[src];
			System.arraycopy(color, src + 1, color, src, x * H + hx - 1 - src);
			color[to * H + height[to]] = k;
			height[to]++;
			height[x] = hx - 1;
			moves++;
			enqueue(x); // 아직 높을 수 있다
			enqueue(to); // 옮겨 간 알갱이가 더 굴러갈 수 있다
			enqueue(2 * x - to); // x 가 낮아져 반대쪽 이웃이 불안정해질 수 있다
		}
		if (moves > 0) {
			analyzed = false;
		}
		return moves;
	}

	/**
	 * 흘리고, 좌우 벽을 잇는 덩어리(알갱이가 {@link #minClear} 이상)를 지우고, 다시 흘리기를 지울 것이 없을 때까지 되풀이한다.
	 * 끝나면 덩어리 분석({@link #components})이 지금 판의 것이다.
	 *
	 * @return 지운 알갱이 수 (연쇄 포함)
	 */
	int settle() {
		int removed = 0;
		while (true) {
			relax();
			analyze();
			int r = removeSpans();
			if (r == 0) {
				return removed;
			}
			removed += r;
			for (int x = 0; x < W; x++) {
				enqueue(x);
			}
		}
	}

	// ------------------------------------------------------------------ 덩어리

	/** 토막을 만들고 이웃한 열의 같은 색 토막을 8방향으로 묶는다. 판이 그대로면 다시 하지 않는다. */
	void analyze() {
		if (analyzed) {
			return;
		}
		buildRuns();
		link();
		for (int i = 0; i < runs; i++) {
			minX[i] = runX[i];
			maxX[i] = runX[i];
			grains[i] = runHi[i] - runLo[i];
		}
		for (int i = 0; i < runs; i++) {
			int r = find(i);
			if (r != i) {
				minX[r] = Math.min(minX[r], minX[i]);
				maxX[r] = Math.max(maxX[r], maxX[i]);
				grains[r] += grains[i];
			}
		}
		analyzed = true;
	}

	private void buildRuns() {
		runs = 0;
		for (int x = 0; x < W; x++) {
			runStart[x] = runs;
			int h = height[x];
			int base = x * H;
			int l = 0;
			while (l < h) {
				byte k = color[base + l];
				int lo = l;
				while (++l < h && color[base + l] == k) {
					// 같은 색이 이어지는 동안
				}
				if (runs == runColor.length) {
					grow();
				}
				runColor[runs] = k;
				runX[runs] = (short) x;
				runLo[runs] = (short) lo;
				runHi[runs] = (short) l;
				parent[runs] = runs;
				runs++;
			}
		}
		runStart[W] = runs;
	}

	private void grow() {
		int n = Math.max(1024, runColor.length * 2);
		runColor = Arrays.copyOf(runColor, n);
		runX = Arrays.copyOf(runX, n);
		runLo = Arrays.copyOf(runLo, n);
		runHi = Arrays.copyOf(runHi, n);
		parent = Arrays.copyOf(parent, n);
		minX = Arrays.copyOf(minX, n);
		maxX = Arrays.copyOf(maxX, n);
		grains = Arrays.copyOf(grains, n);
	}

	/**
	 * 이웃한 두 열의 토막 a(높이 lo~top), b 는 같은 색이고 {@code b.lo <= a.top + 1}, {@code a.lo <= b.top + 1} 이면 8방향으로
	 * 닿는다. 토막은 열마다 높이 순이라 두 열을 한 번씩 훑으면 된다.
	 */
	private void link() {
		for (int x = 0; x + 1 < W; x++) {
			int j = runStart[x + 1];
			int end = runStart[x + 2];
			for (int i = runStart[x]; i < runStart[x + 1]; i++) {
				int lo = runLo[i];
				int top = runHi[i] - 1;
				while (j < end && runHi[j] - 1 < lo - 1) {
					j++; // 이 토막과 그 위 토막들 모두에 닿기에 너무 낮다
				}
				for (int b = j; b < end && runLo[b] <= top + 1; b++) {
					if (runColor[b] == runColor[i]) {
						union(i, b);
					}
				}
			}
		}
	}

	private int find(int i) {
		while (parent[i] != i) {
			parent[i] = parent[parent[i]];
			i = parent[i];
		}
		return i;
	}

	private void union(int a, int b) {
		a = find(a);
		b = find(b);
		if (a != b) {
			if (a < b) {
				parent[b] = a;
			} else {
				parent[a] = b;
			}
		}
	}

	/** 덩어리(뿌리 r)가 지워지는가: 좌우 벽에 모두 닿았고 알갱이가 지우기 문턱 이상이다 (엔진의 {@code markSpans} 와 같다). */
	private boolean clears(int r) {
		return minX[r] == 0 && maxX[r] == W - 1 && grains[r] >= minClear;
	}

	/** 좌우 벽을 잇고 문턱 이상인 덩어리의 알갱이를 지우고 열을 눌러 담는다. @return 지운 알갱이 수 */
	private int removeSpans() {
		boolean any = false;
		for (int i = 0; i < runs && !any; i++) {
			any = parent[i] == i && clears(i);
		}
		if (!any) {
			return 0;
		}
		int removed = 0;
		for (int x = 0; x < W; x++) {
			int base = x * H;
			int w = 0;
			for (int i = runStart[x]; i < runStart[x + 1]; i++) {
				int r = find(i);
				int len = runHi[i] - runLo[i];
				if (clears(r)) {
					removed += len;
					continue;
				}
				if (w != runLo[i]) {
					System.arraycopy(color, base + runLo[i], color, base + w, len);
				}
				w += len;
			}
			height[x] = w;
		}
		analyzed = false;
		return removed;
	}

	/**
	 * 덩어리 하나씩 {@code visitor} 에 넘긴다 (색 번호, 가장 왼쪽 열, 가장 오른쪽 열, 알갱이 수). {@link #analyze} 나
	 * {@link #settle} 뒤에 부른다.
	 */
	void components(ComponentVisitor visitor) {
		analyze();
		for (int i = 0; i < runs; i++) {
			if (parent[i] == i) {
				visitor.visit(runColor[i], minX[i], maxX[i], grains[i]);
			}
		}
	}

	/** 덩어리 하나. */
	interface ComponentVisitor {
		void visit(int code, int minX, int maxX, int grains);
	}

	/** 글자 판 (시험·디버깅): 엔진 {@code SandField#toText} 와 같은 모양으로, 위 행부터. */
	String toText() {
		StringBuilder sb = new StringBuilder();
		for (int y = 0; y < H; y++) {
			int l = H - 1 - y;
			for (int x = 0; x < W; x++) {
				int k = colorAt(x, l);
				sb.append(k == 0 ? '.' : "?RBGY".charAt(k));
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
