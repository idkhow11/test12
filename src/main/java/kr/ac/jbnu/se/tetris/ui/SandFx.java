package kr.ac.jbnu.se.tetris.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.sand.SandRules;

/**
 * 모래 화면의 효과 (runs/quality/SPEC.md V14, Sandtrix 를 참고만 하고 옮기지 않았다). 모두 판의 복사본과
 * {@link SandSession} 의 효과 사건만 읽고, 판의 시계와 효과 난수({@link Fx#rand})로 계산하므로 같은 판이면 언제나 같은 그림이다
 * (프레임마다 시뮬레이션하지 않는다). 판의 규칙·시간·난수는 건드리지 않는다.
 * <ul>
 * <li><b>반짝임 한 번</b>: 지워질 덩어리는 10Hz 로 깜빡이지 않고 한 번만 밝아졌다 가라앉는다. 흰색 섞기
 * {@code 0.15 + 0.85·(1 − u)^1.5}, u = 나이 ÷ 200ms. 번쩍임 줄이기면 0.5 까지. 효과를 끄면 움직이지 않는 옅은 밝음
 * ({@value #STEADY_MIX}) 만 남는다(무엇이 사라질지는 언제나 보인다).</li>
 * <li><b>조각</b>: 덩어리가 사라지는 순간 지운 알갱이 16개마다 하나(많아야 120개, 효과 세기를 곱한다)가 흰색에서 덩어리 색으로
 * 바뀌며 튄다. 위로 2~4칸/초, 옆으로 0~2칸/초(무게중심 바깥쪽), 중력 20칸/초², 수명 약 0.4초, 크기 알갱이 2~3개.</li>
 * <li><b>점수 띄우기</b>: "+점수"(DISPLAY 1.1c, 모래 주황에 2px 갈색 테두리)가 덩어리의 무게중심에서 0.7초 동안 0.8칸
 * 오르고(easeOutCubic) 마지막 0.25초에 옅어진다. 배율 2 이상이면 "×배율"(0.6c, 금색)을 붙인다. 점수는 정보라 효과를 꺼도
 * 남는다.</li>
 * <li><b>꼬리</b>: 소프트드롭으로 떨어지는 블록 위에 0.25칸 간격으로 옅은 복사본 6개(알파 0.35 → 0.05). 하드드롭은
 * 내려앉은 자리 위에 같은 복사본을 두고 0.12초에 사라진다. 꼬리는 모래 그림 아래에 그려 알갱이를 가리지 않는다.</li>
 * <li><b>튕김</b>: 하드드롭하면 판(판과 그 위의 것만, 옆 카드와 점수는 그대로)이 감쇠 용수철로 2~4px 내려갔다 돌아온다(SPEC
 * V5 와 같은 식). 효과 세기와 "화면 흔들림" 을 따른다.</li>
 * <li><b>게임 끝</b> (판이 끝난 순간부터 실제 시간): 0.12초 멈춤 → 0.6초 동안 아래에서 위로 회색 → 0.72초에 옅은 흰
 * 번쩍임과 테두리 물결(번쩍임 줄이기면 없음) → 1초에 결과 카드, 1.15초부터 키를 받는다(대전 화면의 K.O. 와 같은 시간).</li>
 * <li><b>얇은 띠</b> (sand-0.3): 양쪽 벽을 이었지만 알갱이가 지우기 문턱({@link SandRules#MIN_CLEAR_GRAINS})보다 적어 남은
 * 덩어리를 옅게 밝히고(흰색 섞기 {@value #THIN_MIX}), 그 위로 빛 물결이 왼쪽 벽에서 오른쪽 벽 쪽으로 천천히 흐른다(물결
 * 사이 {@value #THIN_WAVE_GRAINS}알갱이를 {@value #THIN_WAVE_MS}ms 에, 섞기 +{@value #THIN_WAVE} 까지). 판 테두리 바로
 * 바깥, 띠가 벽에 닿은 자리에는 띠 색의 작은 삼각형이 판 쪽을 가리킨다. 띠가 나타나고 {@value #THIN_DELAY_MS}ms 뒤부터
 * {@value #THIN_IN_MS}ms 에 걸쳐 들어온다(모래가 흐르며 잠깐 이어졌다 끊기는 띠는 보이지 않는다). 효과를 끄거나 번쩍임
 * 줄이기면 물결 없이 고르게 밝힌다(섞기 {@value #THIN_STEADY}). 왜 안 지워지는지 알려 주는 정보라 효과를 꺼도 보인다. 목표
 * 카드의 "두툼하게" 도 함께 모래색이 된다({@link SandHud#goal}).</li>
 * </ul>
 * 효과 세기 m(끔 0 · 약하게 0.5 · 보통 1 · 강하게 1.5)은 조각 수, 튕김 크기, 반짝임·꼬리·번쩍임의 진하기, 빛의 맥박에 곱한다.
 * 그릴 때 객체를 거의 만들지 않는다(색은 {@link Fx#alpha}·{@link Fx#lighter} 의 표, 점수 글자는 미리 그린 조각 그림).
 */
final class SandFx {

	/** 반짝임이 가라앉은 뒤의 흰색 섞기 (SPEC V14). */
	static final double PULSE_FLOOR = 0.15;
	/** 번쩍임 줄이기의 흰색 섞기 상한 (SPEC V14). */
	static final double CALM_MAX = 0.5;
	/** 효과를 끈 판에서 지워질 덩어리의 밝음 (움직이지 않는다). */
	static final double STEADY_MIX = 0.4;
	/** 조각의 수명 (ms, 조각마다 85~100%). */
	static final int PIECE_LIFE_MS = 400;
	/** 점수 띄우기 (ms): 오르는 시간과 마지막에 옅어지는 시간. */
	static final int POP_MS = 700;
	static final int POP_FADE_MS = 250;
	/** 하드드롭 꼬리가 사라지는 시간 (ms). */
	static final int DROP_TRAIL_MS = 120;
	/** 하드드롭 튕김이 끝나는 시간 (ms). */
	static final int BUMP_MS = 250;
	/** 게임 끝: 멈춤, 회색이 다 번지는 때, 번쩍임, 결과 카드, 키를 받기 시작 (ms, 판이 끝난 순간부터). */
	static final int OVER_HOLD_MS = 120;
	static final int OVER_GREY_MS = 600;
	static final int OVER_ROW_FADE_MS = 90;
	static final int OVER_FLASH_AT = 720;
	static final int OVER_CARD_AT = 1000;
	static final int OVER_KEYS_AT = 1150;

	/** 얇은 띠 표시: 띠가 나타난 뒤 이만큼(판의 ms) 기다렸다가 {@link #THIN_IN_MS} 에 걸쳐 들어온다. */
	static final int THIN_DELAY_MS = 150;
	static final int THIN_IN_MS = 250;
	/** 얇은 띠의 흰색 섞기: 바탕, 흐르는 물결의 더함(효과 세기를 곱한다), 물결이 없을 때(효과 끔, 번쩍임 줄이기)의 고른 값. */
	static final double THIN_MIX = 0.28;
	static final double THIN_WAVE = 0.24;
	static final double THIN_STEADY = 0.38;
	/** 물결 사이 (알갱이)와 물결이 그만큼 흐르는 시간 (판의 ms). */
	static final int THIN_WAVE_GRAINS = 40;
	static final int THIN_WAVE_MS = 1400;

	/** 점수 글자 색 (모래 주황)과 테두리 (진한 갈색), SPEC V14. */
	static final Color POP_FILL = new Color(0xF0A64B);
	static final Color POP_EDGE = new Color(0x5A3A12);

	private static final int G = SandRules.GRAIN;
	private static final int W = SandRules.WIDTH;
	/** 그리는 알갱이 행 수 (스폰 여백 2칸 + 보이는 판 20칸 = 176). */
	private static final int DRAWN = SandRules.HEIGHT - SandRules.DRAW_TOP;

	/** 점수 글자 조각 그림 (최근 몇 개). 잠그고 쓴다. */
	private static final Map<String, BufferedImage> POPS = new LinkedHashMap<>(8, 0.75f, true) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
			return size() > 6;
		}
	};

	private SandFx() {
	}

	// ------------------------------------------------------------------ 반짝임

	/**
	 * 지우기 하나의 흰색 섞기: 나이 {@code age}(ms, 0 ~ {@link SandRules#FLASH_MS}) 에서 한 번의 맥박이 가라앉는다.
	 *
	 * @param m     효과 세기 (0 이면 움직이지 않는 {@link #STEADY_MIX})
	 * @param calm  번쩍임 줄이기 ({@link #CALM_MAX} 까지)
	 */
	static double pulseMix(long age, double m, boolean calm) {
		if (m <= 0) {
			return STEADY_MIX;
		}
		double u = Motion.clamp01(age / (double) SandRules.FLASH_MS);
		double mix = PULSE_FLOOR + Math.min(1, m) * (1 - PULSE_FLOOR) * Math.pow(1 - u, 1.5);
		return calm ? Math.min(CALM_MAX, mix) : Math.min(1, mix);
	}

	/** 사건 없이 반짝이는 알갱이(드묾)와 바탕 표의 섞기: 효과를 껐으면 {@link #STEADY_MIX}, 아니면 가라앉은 값. */
	static double restMix(double m) {
		return m <= 0 ? STEADY_MIX : PULSE_FLOOR;
	}

	// ------------------------------------------------------------------ 얇은 띠

	/**
	 * 얇은 띠 표시가 들어온 정도 0~1: 띠가 나타난 뒤 {@code age}(판의 ms)가 {@value #THIN_DELAY_MS} 를 넘으면
	 * {@value #THIN_IN_MS}ms 에 걸쳐 1 로. 음수(띠 없음)는 0.
	 */
	static double thinIn(long age) {
		return age < 0 ? 0 : Motion.clamp01((age - THIN_DELAY_MS) / (double) THIN_IN_MS);
	}

	/**
	 * 열 {@code gx} 의 얇은 띠 흰색 섞기 (0~1, 들어온 정도를 곱하기 전): 바탕에 왼쪽 벽에서 오른쪽 벽으로 흐르는 물결을 더한다.
	 * 효과를 끄거나 번쩍임 줄이기면 움직이지 않는 {@link #THIN_STEADY}.
	 */
	static double thinMix(int gx, long now, double m, boolean calm) {
		if (m <= 0 || calm) {
			return THIN_STEADY;
		}
		double phase = gx / (double) THIN_WAVE_GRAINS - now / (double) THIN_WAVE_MS;
		return THIN_MIX + THIN_WAVE * Math.min(1, m) * (0.5 + 0.5 * Math.cos(2 * Math.PI * phase));
	}

	// ------------------------------------------------------------------ 조각

	/** 지우기 하나에서 튈 조각 수: 지운 알갱이 16개마다 하나 × 효과 세기, 많아야 120개(그리고 골라 둔 수). */
	static int pieceCount(SandSession.ClearFx f, double m) {
		int want = (int) Math.round(f.grains() / (double) SandSession.GRAINS_PER_PIECE * m);
		return Math.max(0, Math.min(Math.min(SandSession.MAX_PIECES, f.pieceCount()), want));
	}

	/**
	 * 사라지는 덩어리의 조각을 그린다. 부르는 쪽이 판 둘레로 잘라 둔다(clip).
	 *
	 * @param seed 판의 시드 (효과 난수)
	 * @param now  판의 시계 (ms)
	 */
	static void pieces(Graphics2D g, SandPanel.Geo geo, List<SandSession.ClearFx> clears, SandColors colors, long seed,
			long now, double m) {
		if (m <= 0) {
			return;
		}
		int c = geo.c();
		int px = geo.g();
		for (SandSession.ClearFx f : clears) {
			long t0 = f.at() + SandRules.FLASH_MS; // 덩어리가 사라지는 순간
			long age = now - t0;
			if (age < 0 || age >= PIECE_LIFE_MS) {
				continue;
			}
			int count = pieceCount(f, m);
			for (int k = 0; k < count; k++) {
				int p = f.piece(k);
				int idx = p & 0xFFFF;
				int code = p >>> 16;
				double life = PIECE_LIFE_MS * Fx.rand(seed, t0, idx, k, 3, 0.85, 1.0);
				if (age >= life) {
					continue;
				}
				double t = age / 1000.0;
				double gx = idx % W + 0.5;
				double gy = idx / W - SandRules.DRAW_TOP + 0.5;
				double side = gx < f.cx() ? -1 : 1;
				double vx = side * Fx.rand(seed, t0, idx, k, 4, 0, 2) * c; // 화소/초
				double vy = -Fx.rand(seed, t0, idx, k, 5, 2, 4) * c;
				double size = Fx.rand(seed, t0, idx, k, 6, 2, 3) * px;
				double x = geo.boardX() + gx * px + vx * t;
				double y = geo.boardY() + gy * px + vy * t + 0.5 * 20 * c * t * t;
				double u = age / life;
				Color tint = Fx.lighter(colors.sand(code), 1 - u); // 흰색 → 덩어리 색
				g.setColor(Fx.alpha(tint, 1 - u * u));
				int s = (int) Math.max(1, Math.round(size * (1 - 0.3 * u)));
				g.fillRect((int) Math.round(x - s / 2.0), (int) Math.round(y - s / 2.0), s, s);
			}
		}
	}

	// ------------------------------------------------------------------ 점수 띄우기

	/** 가장 최근의, 아직 떠 있는 지우기의 "+점수" 를 그린다. 판 둘레 안으로 자리를 맞춘다. */
	static void scorePop(Graphics2D g, SandPanel.Geo geo, List<SandSession.ClearFx> clears, long now) {
		for (int i = clears.size() - 1; i >= 0; i--) {
			SandSession.ClearFx f = clears.get(i);
			long age = now - f.at();
			if (age < 0) {
				continue;
			}
			if (age >= POP_MS) {
				return; // 더 오래된 것은 이미 사라졌다
			}
			int c = geo.c();
			BufferedImage img = popImage(g, "+" + SandPanel.number(f.points()),
					f.multiplier() > 1 ? "×" + f.multiplier() : null, c);
			double rise = 0.8 * c * Motion.easeOutCubic(age / (double) POP_MS);
			double alpha = age > POP_MS - POP_FADE_MS ? (POP_MS - age) / (double) POP_FADE_MS : 1;
			double cx = geo.boardX() + f.cx() * geo.g();
			double cy = geo.boardY() + (f.cy() - SandRules.DRAW_TOP) * geo.g() - rise;
			int x = (int) Math.round(Math.max(geo.boardX() + 2, Math.min(geo.boardX() + geo.boardW() - img.getWidth() - 2,
					cx - img.getWidth() / 2.0)));
			int y = (int) Math.round(Math.max(geo.boardY() + 0.9 * c, Math.min(geo.boardY() + geo.boardH() - img.getHeight(),
					cy - img.getHeight() / 2.0)));
			Fx.draw(g, img, x, y, alpha);
			return;
		}
	}

	/**
	 * "+점수" (DISPLAY 1.1c, 주황에 2px 갈색 테두리)와 배율 "×N" (0.6c, 금색)을 한 장의 조각 그림으로 만든다. 같은 글자·크기면
	 * 만들어 둔 것을 다시 쓴다(점수 하나가 떠 있는 0.7초 동안 그림 하나).
	 */
	static BufferedImage popImage(Graphics2D g, String points, String mult, int c) {
		String key = points + "|" + mult + "|" + c;
		synchronized (POPS) { // 보통은 화면 스레드 하나지만, 시험·도구는 다른 스레드에서도 그린다
			BufferedImage img = POPS.get(key);
			if (img == null) {
				img = renderPop(g, points, mult, c);
				POPS.put(key, img);
			}
			return img;
		}
	}

	private static BufferedImage renderPop(Graphics2D g, String points, String mult, int c) {
		Font big = Theme.Type.DISPLAY.fit(1.1 * c, points);
		Font small = mult == null ? null : Theme.Type.DISPLAY.fit(0.6 * c, mult);
		FontRenderContext frc = new FontRenderContext(null, true, false);
		GlyphVector gv = big.createGlyphVector(frc, points);
		GlyphVector sv = small == null ? null : small.createGlyphVector(frc, mult);
		double edge = Math.max(2, c / 12.0);
		double bw = gv.getLogicalBounds().getWidth();
		double sw = sv == null ? 0 : sv.getLogicalBounds().getWidth() + 0.18 * c;
		FontMetrics fm = g.getFontMetrics(big);
		int w = (int) Math.ceil(bw + sw + 2 * edge + 2);
		int h = (int) Math.ceil(fm.getAscent() + fm.getDescent() + 2 * edge);
		BufferedImage img = Fx.sprite(g, w, h);
		Graphics2D sg = img.createGraphics();
		Theme.antialias(sg);
		sg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		double base = edge + fm.getAscent() * 0.92;
		Shape outline = gv.getOutline((float) edge + 1, (float) base);
		sg.setStroke(new BasicStroke((float) (2 * edge), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		sg.setColor(POP_EDGE);
		sg.draw(outline);
		sg.setColor(POP_FILL);
		sg.fill(outline);
		if (sv != null) {
			Shape so = sv.getOutline((float) (edge + 1 + bw + 0.18 * c), (float) base);
			sg.setStroke(new BasicStroke((float) edge, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			sg.setColor(POP_EDGE);
			sg.draw(so);
			sg.setColor(Theme.GOLD);
			sg.fill(so);
		}
		sg.dispose();
		return img;
	}

	// ------------------------------------------------------------------ 꼬리

	/**
	 * 소프트드롭으로 떨어지는 블록 위의 꼬리: 0.25칸 간격의 옅은 복사본 6개 (알파 0.35 → 0.05, 효과 세기를 곱한다). 모래 그림보다
	 * 먼저 그려 알갱이를 가리지 않는다.
	 */
	static void softTrail(Graphics2D g, SandPanel.Geo geo, PieceState p, Color color, double m) {
		if (m <= 0 || p == null) {
			return;
		}
		int c = geo.c();
		int top = SandRules.DRAW_TOP / G;
		int[][] cells = p.cells();
		for (int k = 1; k <= 6; k++) {
			double a = (0.35 - 0.06 * (k - 1)) * Math.min(1, m);
			g.setColor(Fx.alpha(color, a));
			int off = (int) Math.round(k * 0.25 * c);
			for (int[] cell : cells) {
				int y = geo.boardY() + (cell[0] - top) * c - off;
				g.fillRect(geo.boardX() + cell[1] * c, Math.max(geo.boardY(), y), c, Math.min(c, y + c - geo.boardY()));
			}
		}
	}

	/**
	 * 하드드롭 꼬리: 내려앉은 자리 바로 위에 0.25칸 간격의 블록 복사본 6개(소프트드롭 꼬리와 같은 모양)를 두고
	 * {@value #DROP_TRAIL_MS}ms 에 걸쳐 사라진다. 2칸 이상 내려왔을 때만. 판 위쪽(스폰 여백 위)은 자른다.
	 */
	static void dropTrail(Graphics2D g, SandPanel.Geo geo, List<SandSession.LockFx> locks, SandColors colors, long now,
			double m) {
		if (m <= 0 || locks.isEmpty()) {
			return;
		}
		SandSession.LockFx f = locks.get(locks.size() - 1);
		long age = now - f.at();
		if (!f.hardDrop() || f.dropRows() < 2 || age < 0 || age >= DROP_TRAIL_MS) {
			return;
		}
		int c = geo.c();
		int top = SandRules.DRAW_TOP / G;
		double u = age / (double) DROP_TRAIL_MS;
		double fade = (1 - u) * (1 - u) * Math.min(1, m);
		Color color = colors.sand(f.colorCode());
		for (int k = 1; k <= 6; k++) {
			g.setColor(Fx.alpha(color, (0.35 - 0.06 * (k - 1)) * fade));
			int off = (int) Math.round(k * 0.25 * c);
			for (int i = 0; i < f.size(); i++) {
				int y = geo.boardY() + (f.row(i) - top) * c - off;
				if (y + c <= geo.boardY()) {
					continue;
				}
				int y0 = Math.max(geo.boardY(), y);
				g.fillRect(geo.boardX() + f.col(i) * c, y0, c, y + c - y0);
			}
		}
	}

	// ------------------------------------------------------------------ 튕김

	/**
	 * 하드드롭 튕김 (px, 아래로 +): p·spring(t), p = (0.08 + 0.09k)·c·m, k = clamp((내려온 칸 − 2)/13, 0, 1). 2칸
	 * 이하로 내려왔으면 작게, 15칸이면 가장 크다(보통 세기에서 c = 24 일 때 약 2~4px).
	 */
	static int bump(List<SandSession.LockFx> locks, long now, int c, double m) {
		if (m <= 0 || locks.isEmpty()) {
			return 0;
		}
		SandSession.LockFx f = locks.get(locks.size() - 1);
		long age = now - f.at();
		if (!f.hardDrop() || age < 0 || age >= BUMP_MS) {
			return 0;
		}
		double k = Motion.clamp01((f.dropRows() - 2) / 13.0);
		double p = (0.08 + 0.09 * k) * c * m;
		return (int) Math.round(p * Motion.spring(age));
	}

	// ------------------------------------------------------------------ 위험, 게임 끝

	/**
	 * 위험할 때 테두리 빛의 세기. 보통 약 1.3Hz 로 숨 쉬고, 번쩍임 줄이기면 0.8Hz(1Hz 아래). 효과를 끄면 움직이지 않는다(빨간
	 * 빛은 정보라 남는다).
	 */
	static double dangerGlow(long now, double m, boolean calm) {
		double wave = calm ? Math.sin(2 * Math.PI * 0.8 * now / 1000.0) : Math.sin(now / 120.0);
		return 0.6 + 0.4 * Math.min(1, m) * wave;
	}

	/** 위쪽 빨간 번짐의 알파 (0~255). */
	static int dangerWash(long now, double m, boolean calm) {
		double wave = calm ? Math.sin(2 * Math.PI * 0.8 * now / 1000.0 + 1) : Math.sin(now / 160.0);
		return (int) (80 * (0.55 + 0.45 * Math.min(1, m) * wave));
	}

	/**
	 * 게임 끝에 알갱이 행 {@code y}(그리는 행, 0 이 스폰 여백 맨 위)가 회색으로 바뀐 정도 0~256. 아래 행부터 0.6초 동안
	 * 위로 번지고 행마다 90ms 에 걸쳐 바뀐다.
	 *
	 * @param overAge 판이 끝난 뒤의 시간 (ms)
	 */
	static int greyAt(int y, long overAge) {
		double start = OVER_HOLD_MS + (DRAWN - 1 - y) * (OVER_GREY_MS / (double) DRAWN);
		double f = (overAge - start) / OVER_ROW_FADE_MS;
		return f <= 0 ? 0 : f >= 1 ? 256 : (int) (f * 256);
	}

	/**
	 * 게임 끝의 흰 번쩍임 알파 (0.72초부터 0.12초, 0.5 → 0). 번쩍임 줄이기면 없음, 효과 세기를 곱한다.
	 */
	static double overFlash(long overAge, double m, boolean calm) {
		if (calm || m <= 0) {
			return 0;
		}
		long t = overAge - OVER_FLASH_AT;
		if (t < 0 || t >= 120) {
			return 0;
		}
		return Math.min(1, 0.5 * m) * (1 - t / 120.0);
	}

	/**
	 * 게임 끝의 테두리 물결: 0.72초부터 0.5초 동안 0.2c 굵기의 선이 판 둘레에서 0.6c 바깥으로 퍼지며 사라진다. 번쩍임 줄이기면
	 * 없음.
	 */
	static void overRipple(Graphics2D g, double x, double y, double w, double h, double arc, int c, long overAge,
			double m, boolean calm) {
		if (calm || m <= 0) {
			return;
		}
		long t = overAge - OVER_FLASH_AT;
		if (t < 0 || t >= 500) {
			return;
		}
		double u = t / 500.0;
		double grow = 0.6 * c * Motion.easeOutCubic(u);
		java.awt.Stroke old = g.getStroke();
		g.setStroke(new BasicStroke((float) (0.2 * c * (1 - 0.5 * u))));
		g.setColor(Fx.alpha(Theme.TEXT, (1 - u) * Math.min(1, m)));
		g.draw(new java.awt.geom.RoundRectangle2D.Double(x - grow, y - grow, w + 2 * grow, h + 2 * grow, arc + grow,
				arc + grow));
		g.setStroke(old);
	}

	/** 크기 s 의 비율로 (cx, cy) 를 가운데 두고 키우거나 줄인다. 돌려준 값으로 되돌린다. */
	static AffineTransform scaleAbout(Graphics2D g, double cx, double cy, double s) {
		AffineTransform old = g.getTransform();
		g.translate(cx, cy);
		g.scale(s, s);
		g.translate(-cx, -cy);
		return old;
	}
}
