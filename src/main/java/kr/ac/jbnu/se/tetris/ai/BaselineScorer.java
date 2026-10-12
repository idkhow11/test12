package kr.ac.jbnu.se.tetris.ai;

import java.util.List;

import kr.ac.jbnu.se.tetris.core.Grid;

/**
 * 비상용 기준 AI. 학습 모델을 쓸 수 없을 때 대신 쓰는 가장 단순한 평가다.
 * <p>
 * 높이 합, 구멍, 울퉁불퉁함, 지운 줄만 본다(널리 쓰이는 4-특징 평가의 가중치). 상대 상태는 보지 않는다.
 * 학습의 세대 0 도 이 AI 끼리 둔다(docs/TRAINING.md 2장).
 */
public final class BaselineScorer implements PlacementScorer {

	private static final double W_HEIGHT = -0.510066;
	private static final double W_LINES = 0.760666;
	private static final double W_HOLES = -0.35663;
	private static final double W_BUMPINESS = -0.184483;

	@Override
	public double[] score(List<Afterstate> candidates, Opponent opponent) {
		double[] out = new double[candidates.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = evaluate(candidates.get(i));
		}
		return out;
	}

	static double evaluate(Afterstate a) {
		int aggregate = 0;
		int holes = 0;
		int bumpiness = 0;
		int prev = -1;
		for (int c = 0; c < Grid.WIDTH; c++) {
			int top = Grid.HEIGHT;
			for (int r = 0; r < Grid.HEIGHT; r++) {
				if (a.cell(r, c) != Grid.EMPTY) {
					top = r;
					break;
				}
			}
			int h = Grid.HEIGHT - top;
			aggregate += h;
			for (int r = top + 1; r < Grid.HEIGHT; r++) {
				if (a.cell(r, c) == Grid.EMPTY) {
					holes++;
				}
			}
			if (prev >= 0) {
				bumpiness += Math.abs(h - prev);
			}
			prev = h;
		}
		return W_HEIGHT * aggregate + W_LINES * a.lines() + W_HOLES * holes + W_BUMPINESS * bumpiness;
	}

	@Override
	public String name() {
		return "baseline";
	}
}
