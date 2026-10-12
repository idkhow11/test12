package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;

/**
 * 메뉴의 움직이는 장식 (runs/quality/SPEC.md V13): 양옆 여백에 천천히 떨어지는 옅은 블록, 초점 꺾쇠. 그림은 시계(ms)만으로
 * 정해진다(상태가 없다). 그래서 화면 확인 도구가 시계를 멈추면 언제나 같은 그림이 나온다.
 * <p>
 * <b>떨어지는 블록</b>: 블록 i 는 {@code i × }{@value #SPAWN_MS}ms 에 화면 위({@code y = −4·칸})에서 나와 아래로 떨어진다. 칸
 * {@value #MIN_CELL}~{@value #MAX_CELL} 화소(클수록 빠르게 초당 {@value #MIN_SPEED}~{@value #MAX_SPEED} 화소), 진하기 20~31
 * (255 중), 왼쪽(가운데 x 24~156)과 오른쪽(1124~1256) 여백을 번갈아 쓴다. 종류·회전·크기는 {@link Fx#rand} 로 블록 번호에서
 * 정한다. Techmino 의 "blockfall" 을 옅게 한 것이다. 효과 세기가 끔이면 그리지 않는다(메뉴가 정한다).
 */
final class MenuDecor {

	/** 블록이 하나씩 나오는 간격 (ms). */
	static final int SPAWN_MS = 800;
	static final int MIN_CELL = 20;
	static final int MAX_CELL = 40;
	static final int MIN_SPEED = 30;
	static final int MAX_SPEED = 90;
	/** 메뉴 가상 화면의 높이. */
	private static final int H = 720;
	/** 가장 느린 블록이 화면을 다 지나는 동안 나오는 블록 수 (이보다 오래된 블록은 이미 화면 아래로 나갔다). */
	private static final int MAX_ALIVE = (int) Math.ceil((H + 4.0 * MAX_CELL) / MIN_SPEED * 1000 / SPAWN_MS) + 1;
	private static final long SEED = 0x4D454E55L; // "MENU"

	/** [종류][회전] → 칸 {행, 열} (왼쪽 위가 0, 0 이 되게 옮긴 것). 한 번 만들어 둔다. */
	private static final int[][][][] SHAPES = new int[PieceType.values().length][PieceType.ROTATIONS][][];
	/** [종류][회전] → {너비, 높이} (칸). */
	private static final int[][][] SIZES = new int[PieceType.values().length][PieceType.ROTATIONS][];

	static {
		for (PieceType t : PieceType.values()) {
			for (int r = 0; r < PieceType.ROTATIONS; r++) {
				int[][] cells = new PieceState(t, r, 0, 0, false, -1).cells();
				int minR = Integer.MAX_VALUE;
				int minC = Integer.MAX_VALUE;
				int maxR = Integer.MIN_VALUE;
				int maxC = Integer.MIN_VALUE;
				for (int[] c : cells) {
					minR = Math.min(minR, c[0]);
					minC = Math.min(minC, c[1]);
					maxR = Math.max(maxR, c[0]);
					maxC = Math.max(maxC, c[1]);
				}
				int[][] norm = new int[cells.length][];
				for (int i = 0; i < cells.length; i++) {
					norm[i] = new int[] { cells[i][0] - minR, cells[i][1] - minC };
				}
				SHAPES[t.ordinal()][r] = norm;
				SIZES[t.ordinal()][r] = new int[] { maxC - minC + 1, maxR - minR + 1 };
			}
		}
	}

	private MenuDecor() {
	}

	/** 블록 i 의 무작위 값 k (0~1). */
	private static double rand(long i, int k) {
		return Fx.rand(SEED, i, 0, 0, k);
	}

	/** 블록 i 의 칸 크기 (화소). */
	static int cell(long i) {
		return MIN_CELL + (int) Math.floor(rand(i, 0) * (MAX_CELL - MIN_CELL + 1));
	}

	/** 칸 크기에 따른 떨어지는 빠르기 (초당 화소): 클수록 빠르다. */
	static double speed(int cell) {
		return MIN_SPEED + (cell - MIN_CELL) / (double) (MAX_CELL - MIN_CELL) * (MAX_SPEED - MIN_SPEED);
	}

	/** 블록 i 의 위 끝 y (시각 {@code t} ms). */
	static double top(long i, long t) {
		int c = cell(i);
		return -4.0 * c + speed(c) * (t - i * (double) SPAWN_MS) / 1000.0;
	}

	/**
	 * 시각 {@code t} (ms) 의 떨어지는 블록을 그린다 (가상 화면 1280 × 720 좌표). 색은 {@code look} 의 블록 색(색약 색상을 따른다)이고
	 * 무늬는 그리지 않는다(진하기가 1할 남짓이라 보이지 않는다). 처음 쓰는 색 말고는 객체를 만들지 않는다.
	 *
	 * @return 그린 블록 수 (시험용)
	 */
	static int paintFalling(Graphics2D g, ColorPalettes.Look look, long t) {
		Composite old = g.getComposite();
		long last = Math.floorDiv(t, SPAWN_MS);
		int drawn = 0;
		for (long i = last - MAX_ALIVE; i <= last; i++) {
			double y = top(i, t);
			if (y >= H) {
				continue; // 이미 화면 아래로 나갔다
			}
			int c = cell(i);
			int type = (int) Math.floor(rand(i, 3) * PieceType.values().length);
			int rot = (int) Math.floor(rand(i, 4) * PieceType.ROTATIONS);
			int[] size = SIZES[type][rot];
			double center = Math.floorMod(i, 2) == 0 ? 24 + rand(i, 1) * 132 : 1124 + rand(i, 1) * 132;
			int x0 = (int) Math.round(center - size[0] * c / 2.0);
			int y0 = (int) Math.round(y);
			int alpha = 20 + (int) Math.floor(rand(i, 2) * 12);
			g.setComposite(Fx.composite(alpha / 255.0));
			Color[] shades = shades(look.piece(PieceType.values()[type].code()));
			for (int[] cellRc : SHAPES[type][rot]) {
				cell(g, x0 + cellRc[1] * c, y0 + cellRc[0] * c, c, shades);
			}
			drawn++;
		}
		g.setComposite(old);
		return drawn;
	}

	/** 블록 색 → {바탕, 밝은 테, 어두운 테} (Theme.block 과 같은 섞음). 색마다 한 번 만든다. */
	private static final java.util.Map<Color, Color[]> SHADES = new java.util.concurrent.ConcurrentHashMap<>();

	private static Color[] shades(Color base) {
		return SHADES.computeIfAbsent(base,
				b -> new Color[] { b, Theme.mix(b, Color.WHITE, 0.38), Theme.mix(b, Color.BLACK, 0.38) });
	}

	/**
	 * 블록 한 칸을 {@code Theme.block} 과 같은 입체 모양으로, 겹치지 않는 사각형 다섯 개로 칠한다. 겹쳐 칠하면 반투명일 때 가운데가
	 * 진해지므로(세 겹), 장식의 진하기(20~31)가 칸 전체에서 그대로 나오게 한다.
	 */
	private static void cell(Graphics2D g, int x, int y, int size, Color[] shades) {
		int gap = size >= 14 ? 1 : 0;
		int s = size - 2 * gap;
		int b = Math.max(2, s / 7);
		int x0 = x + gap;
		int y0 = y + gap;
		g.setColor(shades[1]); // 밝은 테: 위, 왼쪽
		g.fillRect(x0, y0, s - b, b);
		g.fillRect(x0, y0 + b, b, s - 2 * b);
		g.setColor(shades[2]); // 어두운 테: 오른쪽, 아래
		g.fillRect(x0 + s - b, y0, b, s);
		g.fillRect(x0, y0 + s - b, s - b, b);
		g.setColor(shades[0]);
		g.fillRect(x0 + b, y0 + b, s - 2 * b, s - 2 * b);
	}

	/** 초점 꺾쇠 (Tricky Towers): 상자 {@code r} 의 4 화소 바깥 네 귀퉁이에 팔 14, 굵기 2 의 L 자. */
	static void brackets(Graphics2D g, Rectangle2D r, Color c) {
		double pad = 4;
		double arm = 14;
		double x0 = r.getX() - pad;
		double y0 = r.getY() - pad;
		double x1 = r.getMaxX() + pad;
		double y1 = r.getMaxY() + pad;
		Path2D.Double p = new Path2D.Double();
		p.moveTo(x0, y0 + arm);
		p.lineTo(x0, y0);
		p.lineTo(x0 + arm, y0);
		p.moveTo(x1 - arm, y0);
		p.lineTo(x1, y0);
		p.lineTo(x1, y0 + arm);
		p.moveTo(x1, y1 - arm);
		p.lineTo(x1, y1);
		p.lineTo(x1 - arm, y1);
		p.moveTo(x0 + arm, y1);
		p.lineTo(x0, y1);
		p.lineTo(x0, y1 - arm);
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(c);
		g.draw(p);
		g.setStroke(old);
	}
}
