package kr.ac.jbnu.se.tetris.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 공격 내역 (화면의 "2줄 → 공격 3 · 기본 2 + 콤보 1" 표시): 합은 언제나 RULES 9장 계산과 같다. */
class AttackBreakdownTest {

	@Test
	void breakdownAlwaysSumsToTheAttack() {
		for (int combo = -1; combo <= 12; combo++) {
			for (boolean b2b : new boolean[] { false, true }) {
				for (Spin spin : Spin.values()) {
					int maxLines = spin == Spin.FULL ? 3 : spin == Spin.MINI ? 2 : 4;
					for (int lines = 0; lines <= maxLines; lines++) {
						for (boolean pc : new boolean[] { false, true }) {
							int[] parts = Attack.breakdown(combo, b2b, lines, spin, pc && lines > 0);
							int want = Attack.compute(combo, b2b, lines, spin, pc && lines > 0).attack();
							assertEquals(want, Arrays.stream(parts).sum(),
									"combo " + combo + " b2b " + b2b + " " + spin + " lines " + lines + " pc " + pc);
						}
					}
				}
			}
		}
	}

	@Test
	void handExamples() {
		// 연속 두 번째 더블: 기본 2 + 콤보 1 = 3
		assertArrayEquals(new int[] { 2, 0, 1, 0 }, Attack.breakdown(0, false, 2, Spin.NONE, false));
		// B2B 테트리스, 콤보 시작: 기본 4 + B2B 1
		assertArrayEquals(new int[] { 4, 1, 0, 0 }, Attack.breakdown(-1, true, 4, Spin.NONE, false));
		// T-spin 더블, B2B 없음: 기본 4
		assertArrayEquals(new int[] { 4, 0, 0, 0 }, Attack.breakdown(-1, false, 2, Spin.FULL, false));
		// 퍼펙트 클리어 싱글: 기본 1 + 퍼펙트 클리어 10
		assertArrayEquals(new int[] { 1, 0, 0, 10 }, Attack.breakdown(-1, false, 1, Spin.NONE, true));
		assertArrayEquals(new int[4], Attack.breakdown(3, true, 0, Spin.FULL, false), "줄을 못 지우면 공격 없음");
	}
}
