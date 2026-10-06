package kr.ac.jbnu.se.tetris.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 도달 가능한 최종 배치를 모두 찾는다 (RULES 14장).
 * <p>
 * 조작 {@link Op} 로 BFS 를 돌리고, {@code D} 가 실패하는 상태를 최종 배치로 모은다.
 * 같은 정규화 키로 모이는 배치는 경로 하나만 남긴다: 조작 수 → 회전 수 → 사전식 순서.
 */
public final class PlacementFinder {

	/** 최종 배치 하나. 불변. */
	public static final class Placement {
		private final boolean hold;
		private final Spin spin;
		private final PieceState state;
		private final List<Op> path;

		Placement(boolean hold, Spin spin, PieceState state, List<Op> path) {
			this.hold = hold;
			this.spin = spin;
			this.state = state;
			this.path = Collections.unmodifiableList(path);
		}

		/** 홀드로 꺼낸 블록을 놓는 배치인가. */
		public boolean hold() {
			return hold;
		}

		public Spin spin() {
			return spin;
		}

		/** 고정 직전 상태. 경로를 재생하기 위한 대표값이다. */
		public PieceState state() {
			return state;
		}

		/** 시작 상태에서 {@link #state()} 까지의 조작. 이 뒤에 거리 0 하드드롭으로 고정한다. */
		public List<Op> path() {
			return path;
		}

		/** RULES 14.2 정규화 키. 예: {@code h0|full|21,3;21,4;21,5;22,4} */
		public String key() {
			return key(hold, spin, state.cells());
		}

		public static String key(boolean hold, Spin spin, int[][] sortedCells) {
			StringBuilder sb = new StringBuilder();
			sb.append(hold ? "h1|" : "h0|").append(spin.json()).append('|');
			for (int i = 0; i < sortedCells.length; i++) {
				if (i > 0) {
					sb.append(';');
				}
				sb.append(sortedCells[i][0]).append(',').append(sortedCells[i][1]);
			}
			return sb.toString();
		}
	}

	private PlacementFinder() {
	}

	/**
	 * 시작 상태에서 도달 가능한 최종 배치. 시작 상태가 이미 막혀 있으면 빈 결과다.
	 *
	 * @return 정규화 키 → 배치. 순서는 처음 발견한 순서
	 */
	public static Map<String, Placement> find(Grid grid, PieceState start, boolean hold) {
		Map<String, Placement> result = new LinkedHashMap<>();
		if (!grid.fits(start.type(), start.rotation(), start.x(), start.y())) {
			return result;
		}

		// 탐색 상태 → 지금까지 찾은 가장 좋은 경로. 같은 층에서 더 좋은 경로가 나오면 교체한다.
		// 한 층의 노드는 모두 앞 층을 다 처리한 뒤에 꺼내므로, 꺼낼 때는 경로가 이미 확정돼 있다.
		Map<PieceState, List<Op>> best = new HashMap<>();
		ArrayDeque<PieceState> queue = new ArrayDeque<>();
		PieceState origin = searchKey(start);
		best.put(origin, Collections.emptyList());
		queue.add(start);

		while (!queue.isEmpty()) {
			PieceState s = queue.poll();
			List<Op> path = best.get(searchKey(s));

			for (Op op : Op.values()) {
				PieceState next = Movement.apply(grid, s, op);
				if (next == null) {
					continue;
				}
				List<Op> nextPath = append(path, op);
				PieceState k = searchKey(next);
				List<Op> known = best.get(k);
				if (known == null) {
					best.put(k, nextPath);
					queue.add(next);
				} else if (known.size() == nextPath.size() && better(nextPath, known)) {
					best.put(k, nextPath);
				}
			}

			if (Movement.isGrounded(grid, s)) {
				Placement p = new Placement(hold, TSpinDetector.detect(grid, s), s, path);
				String key = p.key();
				Placement existing = result.get(key);
				if (existing == null || better(path, existing.path())) {
					result.put(key, p);
				}
			}
		}
		return result;
	}

	/**
	 * T 가 아닌 블록은 "마지막 조작"이 결과에 영향을 주지 않으므로 탐색 상태에서 뺀다.
	 * O 는 회전이 언제나 실패하므로(RULES 3.3) 회전 상태 0 인 상태만 나온다.
	 */
	private static PieceState searchKey(PieceState s) {
		if (s.type() == PieceType.T) {
			return s;
		}
		return new PieceState(s.type(), s.rotation(), s.x(), s.y(), false, -1);
	}

	private static List<Op> append(List<Op> path, Op op) {
		List<Op> out = new ArrayList<>(path.size() + 1);
		out.addAll(path);
		out.add(op);
		return out;
	}

	/** RULES 14.2 동점 규칙. a 가 b 보다 앞서면 true. */
	static boolean better(List<Op> a, List<Op> b) {
		if (a.size() != b.size()) {
			return a.size() < b.size();
		}
		int ra = rotations(a);
		int rb = rotations(b);
		if (ra != rb) {
			return ra < rb;
		}
		for (int i = 0; i < a.size(); i++) {
			int c = a.get(i).compareTo(b.get(i));
			if (c != 0) {
				return c < 0;
			}
		}
		return false;
	}

	private static int rotations(List<Op> path) {
		int n = 0;
		for (Op op : path) {
			if (op.isRotation()) {
				n++;
			}
		}
		return n;
	}
}
