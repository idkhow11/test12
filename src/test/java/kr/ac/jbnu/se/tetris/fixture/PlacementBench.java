package kr.ac.jbnu.se.tetris.fixture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.Locking;
import kr.ac.jbnu.se.tetris.core.Movement;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PlacementFinder.Placement;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.core.TSpinDetector;

/**
 * PlacementFinder 의 속도 측정과 경로 검증 (계획서 11장 "블록당 처리 시간 측정").
 * <p>
 * {@link GameModel} 로 한 판씩 진행하면서, 아주 단순한 평가(높이·구멍)로 스스로 쌓아 실제 게임과 비슷한 보드를
 * 만든다. 매 블록마다 현재 블록과 홀드 블록 두 번 탐색하고(홀드 포함 1수 탐색에 해당), 모든 배치의 경로를 처음부터
 * 다시 재생해 같은 칸·같은 spin 에 도착하는지 확인한다. GameModel 의 홀드·가방 진행을 오래 돌려 보는 시험도 된다.
 * 휴리스틱 AI 가 아니며 성능은 의미가 없다.
 * <p>
 * 실행: {@code java ... PlacementBench [블록 수] [시드]}
 */
public final class PlacementBench {

	public static void main(String[] args) {
		int pieces = args.length > 0 ? Integer.parseInt(args[0]) : 5000;
		long seed = args.length > 1 ? Long.parseLong(args[1]) : 1;

		SplitMix64 noise = SplitMix64.stream(seed, SplitMix64.STREAM_SCHEDULER);
		GameModel game = new GameModel(seed, 1, GameModel.Scheduler.NONE);
		List<Long> perPieceNanos = new ArrayList<>();
		List<Integer> counts = new ArrayList<>();
		int games = 1;
		int checkedPaths = 0;

		// JIT 이 자리 잡기 전의 측정은 버린다.
		int warmup = Math.min(500, pieces / 5);

		for (int i = 0; i < pieces + warmup; i++) {
			Grid grid = game.grid();
			long t0 = System.nanoTime();
			Map<String, Placement> all = game.placements(); // 현재 블록 + 홀드 블록, BFS 두 번
			long t1 = System.nanoTime();
			if (i >= warmup) {
				perPieceNanos.add(t1 - t0);
				counts.add(all.size());
			}

			for (Placement p : all.values()) {
				verifyPath(grid, p);
				checkedPaths++;
			}

			Placement pick = null;
			double bestScore = Double.NEGATIVE_INFINITY;
			for (Placement p : all.values()) {
				double s = score(grid, p) + noise.nextInt(1000) / 1e6;
				if (s > bestScore) {
					bestScore = s;
					pick = p;
				}
			}
			if (pick == null || game.apply(pick).topout() != GameModel.Topout.NONE) {
				game = new GameModel(seed + games, 1, GameModel.Scheduler.NONE);
				games++;
			}
		}

		long[] ns = perPieceNanos.stream().mapToLong(Long::longValue).sorted().toArray();
		int[] cs = counts.stream().mapToInt(Integer::intValue).sorted().toArray();
		System.out.printf("블록 %d개 (워밍업 %d개 제외), 게임 %d판, 경로 검증 %d건 모두 통과%n", ns.length, warmup, games, checkedPaths);
		System.out.printf("블록당 탐색 시간(현재+홀드 2회): 평균 %.3f ms, 중앙값 %.3f ms, p95 %.3f ms, 최대 %.3f ms%n",
				Arrays.stream(ns).average().orElse(0) / 1e6, ns[ns.length / 2] / 1e6, ns[(int) (ns.length * 0.95)] / 1e6,
				ns[ns.length - 1] / 1e6);
		System.out.printf("블록당 배치 수(홀드 포함): 평균 %.1f, 최소 %d, 중앙값 %d, 최대 %d%n",
				Arrays.stream(cs).average().orElse(0), cs[0], cs[cs.length / 2], cs[cs.length - 1]);
	}

	/** 경로를 처음부터 재생해 같은 상태·같은 spin 에 도착하는지. 틀리면 즉시 중단한다. */
	private static void verifyPath(Grid grid, Placement p) {
		PieceState s = PieceState.spawn(p.state().type());
		for (Op op : p.path()) {
			s = Movement.apply(grid, s, op);
			if (s == null) {
				throw new IllegalStateException("경로 재생 실패: " + p.key() + " " + p.path());
			}
		}
		if (!Movement.isGrounded(grid, s)) {
			throw new IllegalStateException("경로 끝이 바닥에 닿지 않음: " + p.key());
		}
		if (!Arrays.deepEquals(s.cells(), p.state().cells()) || TSpinDetector.detect(grid, s) != p.spin()) {
			throw new IllegalStateException("경로 끝이 다른 배치: " + p.key() + " " + p.path());
		}
	}

	/** 놓은 뒤 보드의 높이와 구멍만 보는 단순 평가. 줄 삭제를 조금 선호한다. */
	private static double score(Grid grid, Placement p) {
		Grid g = grid.copy();
		Locking.Outcome out = Locking.lock(g, p.state());
		if (out.lockOut()) {
			return Double.NEGATIVE_INFINITY;
		}
		int holes = 0;
		int aggregate = 0;
		int maxH = 0;
		for (int c = 0; c < Grid.WIDTH; c++) {
			int top = Grid.HEIGHT;
			for (int r = 0; r < Grid.HEIGHT; r++) {
				if (g.get(r, c) != Grid.EMPTY) {
					top = r;
					break;
				}
			}
			int h = Grid.HEIGHT - top;
			aggregate += h;
			maxH = Math.max(maxH, h);
			for (int r = top + 1; r < Grid.HEIGHT; r++) {
				if (g.get(r, c) == Grid.EMPTY) {
					holes++;
				}
			}
		}
		return -0.5 * aggregate - 3.0 * holes - 0.5 * maxH + 0.8 * out.lines();
	}
}
