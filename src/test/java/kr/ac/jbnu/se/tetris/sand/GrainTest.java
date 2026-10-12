package kr.ac.jbnu.se.tetris.sand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 알갱이 값과 그리기 번호, 색마다 다른 무늬 (package-info 1장). */
class GrainTest {

	@Test
	void aGrainKeepsItsColourShadeAndOriginAndCanFlash() {
		short v = Grain.of(SandColor.GREEN, 3, 5, 6);
		assertEquals(SandColor.GREEN, Grain.color(v));
		assertEquals(3, Grain.code(v));
		assertEquals(3, Grain.shade(v));
		assertEquals(5, Grain.originRow(v));
		assertEquals(6, Grain.originCol(v));
		assertFalse(Grain.flashing(v));
		short f = Grain.flash(v);
		assertTrue(Grain.flashing(f));
		assertEquals(SandColor.GREEN, Grain.color(f), "반짝여도 색은 그대로");
		assertNull(Grain.color(Grain.EMPTY));
		assertFalse(Grain.pattern(Grain.EMPTY));
	}

	@Test
	void eachColourHasItsOwnPatternDensity() {
		// 빨강 민무늬, 노랑 성긴 점, 파랑 가로줄, 초록 2 × 2 바둑판: 칸 하나(64알)에 0, 4, 16, 32알
		int[] expected = { 0, 16, 32, 4 }; // RED, BLUE, GREEN, YELLOW
		for (SandColor c : SandColor.values()) {
			int n = 0;
			for (int r = 0; r < SandRules.GRAIN; r++) {
				for (int col = 0; col < SandRules.GRAIN; col++) {
					if (c.pattern(r, col)) {
						n++;
					}
					assertEquals(c.pattern(r, col), Grain.pattern(Grain.of(c, 2, r, col)), c + " " + r + "," + col);
				}
			}
			assertEquals(expected[c.ordinal()], n, c.label());
		}
		assertTrue(SandColor.BLUE.pattern(1, 0) && !SandColor.BLUE.pattern(0, 0), "파랑은 4행마다 1행");
		assertTrue(SandColor.YELLOW.pattern(2, 2) && !SandColor.YELLOW.pattern(2, 3), "노랑은 4 × 4 마다 한 점");
		assertTrue(SandColor.GREEN.pattern(0, 1) && !SandColor.GREEN.pattern(0, 2), "초록은 2 × 2 덩어리 바둑판");
	}

	@Test
	void theLookNumberHoldsColourFlashShadeAndPatternOnly() {
		assertEquals(0, Grain.look(Grain.EMPTY));
		Map<Integer, String> seen = new HashMap<>();
		for (SandColor c : SandColor.values()) {
			for (int shade = 0; shade < 4; shade++) {
				for (int r = 0; r < SandRules.GRAIN; r++) {
					for (int col = 0; col < SandRules.GRAIN; col++) {
						for (boolean flash : new boolean[] { false, true }) {
							short v = Grain.of(c, shade, r, col);
							if (flash) {
								v = Grain.flash(v);
							}
							int look = Grain.look(v);
							assertTrue(look > 0 && look < Grain.LOOKS);
							assertEquals(c, Grain.lookColor(look));
							assertEquals(shade, Grain.lookShade(look));
							assertEquals(flash, Grain.lookFlashing(look));
							assertEquals(c.pattern(r, col), Grain.lookPattern(look));
							String what = c + "/" + shade + "/" + flash + "/" + c.pattern(r, col);
							String before = seen.putIfAbsent(look, what);
							assertTrue(before == null || before.equals(what), "같은 번호는 같은 모습: " + before + " " + what);
						}
					}
				}
			}
		}
		// 빨강은 무늬가 없어 2 × 4 × 1, 나머지는 2 × 4 × 2 가지
		assertEquals(8 + 16 * 3, seen.size());
		assertNull(Grain.lookColor(0));
	}
}
