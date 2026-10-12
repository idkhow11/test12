package kr.ac.jbnu.se.tetris.sand.ai;

import java.util.List;
import java.util.Objects;

import kr.ac.jbnu.se.tetris.core.Grid;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.SplitMix64;
import kr.ac.jbnu.se.tetris.sand.Grain;
import kr.ac.jbnu.se.tetris.sand.SandField;
import kr.ac.jbnu.se.tetris.sand.SandPiece;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.sand.SandView;

/**
 * AI 가 블록 하나의 자리를 고를 때 보는 것: 블록이 나온 틱의 알갱이 판, 움직이는 블록, 홀드, 미리보기, 콤보. 게임 진행 스레드가
 * 그 틱에 복사해 두고(알갱이 배열 복사 한 번이라 몇 μs) 계산은 일꾼 스레드가 하므로, 일꾼이 언제 계산하든 같은 답이 나온다.
 * 불변이다(알갱이 배열은 이 객체만 갖고 바꾸지 않는다).
 */
public final class Situation {

	private final short[] grains;
	private final PieceState piece;
	private final SandPiece current;
	private final SandPiece hold;
	private final boolean holdUsed;
	private final List<SandPiece> preview;
	private final int level;
	private final int multiplier;
	private final int comboLeft;

	/**
	 * @param grains 알갱이 판 ({@link SandRules#WIDTH} × {@link SandRules#HEIGHT}, 행 우선). 복사해 둔다
	 * @param piece  움직이는 블록 (null 이 아님)
	 */
	public Situation(short[] grains, PieceState piece, SandPiece current, SandPiece hold, boolean holdUsed,
			List<SandPiece> preview, int level, int multiplier, int comboLeft) {
		if (grains.length != SandRules.WIDTH * SandRules.HEIGHT) {
			throw new IllegalArgumentException("알갱이 판 크기: " + grains.length);
		}
		this.grains = grains.clone();
		this.piece = Objects.requireNonNull(piece, "piece");
		this.current = Objects.requireNonNull(current, "current");
		this.hold = hold;
		this.holdUsed = holdUsed;
		this.preview = List.copyOf(preview);
		this.level = level;
		this.multiplier = multiplier;
		this.comboLeft = comboLeft;
	}

	/**
	 * 지금 게임(또는 복사본)에서 고를 때 쓸 것을 복사한다. 게임 진행 스레드에서, 블록이 있을 때 부른다.
	 *
	 * @throws IllegalStateException 움직이는 블록이 없으면 (다음 블록을 기다리는 중이거나 끝난 판)
	 */
	public static Situation of(SandView g) {
		if (g.piece() == null) {
			throw new IllegalStateException("움직이는 블록이 없음");
		}
		short[] grains = new short[SandRules.WIDTH * SandRules.HEIGHT];
		g.copyGrains(grains);
		return new Situation(grains, g.piece(), g.current(), g.hold(), g.holdUsed(), g.preview(), g.level(),
				g.multiplier(), g.comboLeft());
	}

	/** 알갱이 값 (행 gy, 열 gx). */
	public short grain(int gy, int gx) {
		return grains[gy * SandRules.WIDTH + gx];
	}

	public PieceState piece() {
		return piece;
	}

	public SandPiece current() {
		return current;
	}

	/** 홀드 칸, 비었으면 null. */
	public SandPiece hold() {
		return hold;
	}

	public boolean holdUsed() {
		return holdUsed;
	}

	/** 미리보기 (다음 블록이 먼저). */
	public List<SandPiece> preview() {
		return preview;
	}

	/** 게임 단계 1~10 (중력이 정해진다). */
	public int level() {
		return level;
	}

	/** 콤보 배율 1~10. */
	public int multiplier() {
		return multiplier;
	}

	/** 콤보 배율이 내려가기까지 남은 시간 (ms). */
	public int comboLeft() {
		return comboLeft;
	}

	/** 홀드하면 꺼낼 블록 (홀드 칸, 비었으면 다음 블록). */
	public SandPiece holdOut() {
		return hold != null ? hold : preview.get(0);
	}

	/** 이 판의 엔진 판 (알갱이만 옮기고, 지우기 문턱은 게임 규칙, 물리 난수는 {@code seed}). */
	SandField field(long seed) {
		SandField f = SandField.standard(new SplitMix64(seed));
		for (int y = 0; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				short v = grains[y * SandRules.WIDTH + x];
				if (v != Grain.EMPTY) {
					f.set(y, x, v);
				}
			}
		}
		return f;
	}

	/** 알갱이가 하나라도 있는 칸을 막은 칸 판 (게임의 블록 충돌과 같다). */
	Grid cellGrid() {
		Grid g = new Grid();
		int gr = SandRules.GRAIN;
		for (int y = 0; y < SandRules.HEIGHT; y++) {
			for (int x = 0; x < SandRules.WIDTH; x++) {
				if (grains[y * SandRules.WIDTH + x] != Grain.EMPTY) {
					g.set(y / gr, x / gr, Grid.GARBAGE);
				}
			}
		}
		return g;
	}
}
