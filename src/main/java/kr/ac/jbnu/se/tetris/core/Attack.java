package kr.ac.jbnu.se.tetris.core;

/** 공격 계산 (RULES 9장). 결과는 불변 값 객체다. */
public final class Attack {

	private static final int[] COMBO_BONUS = { 0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5 };
	/** 규칙 0.3(과제 3번): 지운 줄 수만큼 공격한다. 한 줄을 지우면 상대에게 한 줄. */
	private static final int[] NORMAL = { 0, 1, 2, 3, 4 };
	private static final int[] TSPIN_FULL = { 0, 2, 4, 6 };
	/** mini 는 일반 클리어와 같은 줄 수. 대신 B2B 대상이다. */
	private static final int[] TSPIN_MINI = { 0, 1, 2 };
	private static final int PERFECT_CLEAR_BONUS = 10;

	private final int attack;
	private final int combo;
	private final boolean b2b;

	private Attack(int attack, int combo, boolean b2b) {
		this.attack = attack;
		this.combo = combo;
		this.b2b = b2b;
	}

	/**
	 * @param combo 블록을 놓기 전 콤보 (없으면 -1)
	 * @param b2b   블록을 놓기 전 B2B 연속 여부
	 */
	public static Attack compute(int combo, boolean b2b, int lines, Spin spin, boolean perfectClear) {
		if (lines == 0) {
			// 줄 없는 배치는 T-spin 이든 아니든 B2B 연속을 끊지 않는다.
			return new Attack(0, -1, b2b);
		}
		int newCombo = combo + 1;
		int attack;
		switch (spin) {
		case FULL:
			attack = TSPIN_FULL[lines];
			break;
		case MINI:
			attack = TSPIN_MINI[lines];
			break;
		default:
			attack = NORMAL[lines];
			break;
		}
		boolean eligible = lines == 4 || spin != Spin.NONE;
		boolean newB2b;
		if (eligible) {
			if (b2b) {
				attack += 1;
			}
			newB2b = true;
		} else {
			newB2b = false;
		}
		attack += COMBO_BONUS[Math.min(newCombo, COMBO_BONUS.length - 1)];
		if (perfectClear) {
			attack += PERFECT_CLEAR_BONUS;
		}
		return new Attack(attack, newCombo, newB2b);
	}

	/**
	 * 공격의 내역 {기본, B2B 보너스, 콤보 보너스, 퍼펙트 클리어 보너스} (화면 표시용). 합은 언제나
	 * {@link #compute} 의 {@link #attack()} 과 같다. 인자는 compute 와 같다.
	 */
	public static int[] breakdown(int combo, boolean b2b, int lines, Spin spin, boolean perfectClear) {
		if (lines == 0) {
			return new int[4];
		}
		int base = spin == Spin.FULL ? TSPIN_FULL[lines] : spin == Spin.MINI ? TSPIN_MINI[lines] : NORMAL[lines];
		boolean eligible = lines == 4 || spin != Spin.NONE;
		int b2bBonus = eligible && b2b ? 1 : 0;
		int comboBonus = COMBO_BONUS[Math.min(combo + 1, COMBO_BONUS.length - 1)];
		return new int[] { base, b2bBonus, comboBonus, perfectClear ? PERFECT_CLEAR_BONUS : 0 };
	}

	/** 상쇄 전 총 공격 줄 수. */
	public int attack() {
		return attack;
	}

	/** 계산 후 콤보. 줄을 못 지웠으면 -1. */
	public int combo() {
		return combo;
	}

	/** 계산 후 B2B 연속 여부. */
	public boolean b2b() {
		return b2b;
	}
}
