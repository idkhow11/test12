package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * 효과음 표본 (SoundBank): 길이, NaN 없음, 0 에서 시작해 0 으로 끝남, 들리는 크기(F100)가 효과음 표의 목표와 0.3dB 안,
 * 가장 큰 표본 0.9 이하, 같은 코드면 같은 표본, 녹음을 쓰는 곳과 녹음이 없을 때의 합성음, 세기에 따른 변화(하드드롭 단계,
 * T-spin 미니, B2B 연속, 콤보 11·12), 들리는 크기의 순서(자주 나는 소리는 작게, 큰 사건은 크게), 작은 스피커에서도 들리는
 * 경고, 귀를 찌르지 않는 높은 배음.
 */
class SoundBankTest {

	private static final SoundBank BANK = SoundBank.generate();

	/** 녹음을 쓰는 소리. 나머지는 모두 합성한다. */
	private static final Set<Sfx> RECORDED = EnumSet.of(Sfx.MENU_MOVE, Sfx.MENU_SELECT, Sfx.MENU_BACK, Sfx.DENY,
			Sfx.LAND, Sfx.LOCK, Sfx.HARD_DROP, Sfx.KO, Sfx.SAND_LAND);

	/** 표본 제곱의 합 (소리 에너지). */
	private static double energy(float[] c) {
		double e = 0;
		for (float x : c) {
			e += x * x;
		}
		return e;
	}

	/** 주파수 {@code hz} 성분의 세기 (Goertzel). */
	private static double tone(float[] c, double hz) {
		double coeff = 2 * Math.cos(2 * Math.PI * hz / Synth.RATE);
		double s1 = 0;
		double s2 = 0;
		for (float x : c) {
			double s = x + coeff * s1 - s2;
			s2 = s1;
			s1 = s;
		}
		return s1 * s1 + s2 * s2 - coeff * s1 * s2;
	}

	/** {@code hz} 위의 에너지 (2 차 고역 통과를 두 번). */
	private static double above(float[] c, double hz) {
		double[] x = new double[c.length];
		for (int i = 0; i < c.length; i++) {
			x[i] = c[i];
		}
		double w0 = 2 * Math.PI * hz / Synth.RATE;
		double alpha = Math.sin(w0) / (2 * Math.sqrt(0.5));
		double cs = Math.cos(w0);
		double a0 = 1 + alpha;
		double b0 = (1 + cs) / 2 / a0;
		double b1 = -(1 + cs) / a0;
		double a1 = -2 * cs / a0;
		double a2 = (1 - alpha) / a0;
		for (int pass = 0; pass < 2; pass++) {
			double x1 = 0;
			double x2 = 0;
			double y1 = 0;
			double y2 = 0;
			for (int i = 0; i < x.length; i++) {
				double v = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2;
				x2 = x1;
				x1 = x[i];
				y2 = y1;
				y1 = v;
				x[i] = v;
			}
		}
		double e = 0;
		for (double v : x) {
			e += v * v;
		}
		return e;
	}

	/** 테트리스를 0 으로 한 들리는 크기 (dB, F100). */
	private static double rel(Sfx e, int v) {
		return Loudness.f100(BANK.clip(e, v)) - SoundBank.REFERENCE_F100;
	}

	@Test
	void everyClipIsShortCleanAndAtItsLoudnessTarget() {
		for (Sfx e : Sfx.values()) {
			assertEquals(e.variants, BANK.variants(e), e.name());
			for (int v = 0; v < e.variants; v++) {
				String at = e + " 변형 " + v;
				float[] c = BANK.clip(e, v);
				double ms = c.length * 1000.0 / Synth.RATE;
				assertTrue(ms >= 30 && ms <= 2500, at + ": 길이 " + ms + "ms");
				for (int i = 0; i < c.length; i++) {
					assertTrue(Float.isFinite(c[i]), at + ": " + i + "번째 표본이 NaN 이나 무한");
				}
				double loss = BANK.guardLossDb(e, v);
				assertTrue(loss >= 0 && loss <= 1, at + ": 가장 큰 표본의 상한 때문에 1dB 넘게 줄이지 않는다 (" + loss + ")");
				assertEquals(SoundBank.target(e, v) - loss, Loudness.f100(c), 0.3, at + ": 들리는 크기(F100)가 목표");
				assertTrue(Synth.peak(c) <= SoundBank.PEAK_LIMIT + 1e-6f,
						at + ": 가장 큰 표본은 0.9 이하 (" + Synth.peak(c) + ")");
				assertEquals(0f, c[0], 1e-6f, at + ": 0 에서 시작한다(딸깍 없음)");
				assertEquals(0f, c[c.length - 1], 1e-6f, at + ": 0 으로 끝난다");
			}
		}
	}

	@Test
	void theLoudnessTableKeepsTheHeroOrder() {
		assertEquals(0, rel(Sfx.CLEAR, 3), 0.3, "테트리스가 기준 0");
		assertTrue(rel(Sfx.PERFECT_CLEAR, 0) >= rel(Sfx.TSPIN, 3) && rel(Sfx.TSPIN, 3) >= rel(Sfx.CLEAR, 3),
				"퍼펙트 클리어 ≥ T-spin 트리플 ≥ 테트리스");
		assertTrue(rel(Sfx.CLEAR, 3) - rel(Sfx.CLEAR, 0) >= 4, "테트리스는 한 줄보다 4dB 넘게 크다");
		assertTrue(rel(Sfx.GARBAGE_RISE, 7) <= rel(Sfx.CLEAR, 3) - 5, "자주 오는 나쁜 소식(방해 줄 8줄 상승)은 테트리스보다 5dB 작다");
		for (int n = 1; n < 12; n++) {
			assertTrue(rel(Sfx.COMBO, n) >= rel(Sfx.COMBO, n - 1) - 0.5, (n + 1) + " 콤보는 " + n + " 콤보보다 작아지지 않는다");
		}
		for (Sfx e : Sfx.values()) {
			for (int v = 0; v < e.variants; v++) {
				assertTrue(rel(e, v) <= rel(Sfx.PERFECT_CLEAR, 0) + 0.3, e + " 변형 " + v + ": 퍼펙트 클리어가 가장 크다");
			}
		}
	}

	@Test
	void generationIsDeterministic() {
		SoundBank again = SoundBank.generate();
		for (Sfx e : Sfx.values()) {
			for (int v = 0; v < e.variants; v++) {
				assertArrayEquals(BANK.clip(e, v), again.clip(e, v), e + " 변형 " + v + ": 잡음과 녹음까지 같아야 한다");
			}
		}
	}

	@Test
	void generationDoesNotDependOnTheThread() {
		// 두 스레드가 동시에 만들어도 같다: 공유하는 바뀌는 상태가 없다 (게임은 믹서 스레드에서 만든다)
		CompletableFuture<SoundBank> a = CompletableFuture.supplyAsync(SoundBank::generate);
		CompletableFuture<SoundBank> b = CompletableFuture.supplyAsync(SoundBank::generate);
		for (SoundBank other : new SoundBank[] { a.join(), b.join() }) {
			for (Sfx e : Sfx.values()) {
				for (int v = 0; v < e.variants; v++) {
					assertArrayEquals(BANK.clip(e, v), other.clip(e, v), e + " 변형 " + v);
				}
			}
		}
	}

	@Test
	void recordingsAreUsedOnlyWhereSynthesisIsWeak() {
		for (Sfx e : Sfx.values()) {
			for (int v = 0; v < e.variants; v++) {
				assertEquals(RECORDED.contains(e), BANK.recorded(e, v), e + " 변형 " + v);
			}
		}
	}

	@Test
	void aMissingRecordingFallsBackToTheSynthesizedSound() {
		List<String> said = new ArrayList<>();
		SoundBank bank = SoundBank.generate(name -> name.equals("land") ? null : Samples.read(name), said::add);
		assertFalse(bank.recorded(Sfx.LAND, 0), "읽지 못한 녹음은 합성음으로");
		assertTrue(bank.recorded(Sfx.LOCK, 0), "다른 녹음은 그대로 쓴다");
		assertEquals(SoundBank.target(Sfx.LAND, 0), Loudness.f100(bank.clip(Sfx.LAND, 0)), 0.3, "합성음도 같은 크기");
		assertEquals(List.of(), said, "null 은 loader 의 뜻이라 알리지 않는다 (까닭은 loader 가 알린다: Samples.load)");
		Function<String, float[]> failing = name -> {
			throw new IllegalStateException("읽다 실패 (시험)");
		};
		SoundBank broken = SoundBank.generate(failing, said::add);
		for (Sfx e : RECORDED) {
			assertFalse(broken.recorded(e, 0), e + ": 읽다가 예외가 나도 합성음으로");
			assertEquals(SoundBank.target(e, 0), Loudness.f100(broken.clip(e, 0)), 0.3, e.name());
		}
		// 녹음 이름은 JVM 전체에서 한 번만 알린다. 이 시험 말고는 녹음 이름을 알리는 곳이 없다(개발 환경에서는 13 개가 다 읽힌다)
		String head = "효과음 녹음을 읽지 못해 합성음으로 대신함: ";
		String tail = " (java.lang.IllegalStateException: 읽다 실패 (시험))";
		Set<String> named = new HashSet<>();
		for (String line : said) {
			assertTrue(line.startsWith(head) && line.endsWith(tail), "loader 가 던진 예외를 알린다: " + line);
			assertTrue(named.add(line.substring(head.length(), line.length() - tail.length())), "이름마다 한 번: " + line);
		}
		assertEquals(new HashSet<>(Samples.NAMES), named, "읽으려 한 녹음 13 개를 모두 알린다");
		SoundBank.generate(failing, said::add);
		assertEquals(Samples.NAMES.size(), said.size(), "다시 만들어도 이미 알린 녹음은 알리지 않는다");
	}

	@Test
	void theSynthOptionForcesSynthesisEverywhere() {
		String old = System.getProperty("tetris.sfx");
		System.setProperty("tetris.sfx", "synth");
		try {
			SoundBank synth = SoundBank.generate();
			for (Sfx e : RECORDED) {
				for (int v = 0; v < e.variants; v++) {
					assertFalse(synth.recorded(e, v), e + " 변형 " + v + ": -Dtetris.sfx=synth 면 녹음을 쓰지 않는다");
				}
			}
			assertArrayEquals(SoundBank.generate(name -> null).clip(Sfx.LOCK, 1), synth.clip(Sfx.LOCK, 1),
					"녹음이 없을 때와 같은 합성음");
		} finally {
			if (old == null) {
				System.clearProperty("tetris.sfx");
			} else {
				System.setProperty("tetris.sfx", old);
			}
		}
	}

	@Test
	void theSineTableIsAccurate() {
		double worst = 0;
		for (int i = 0; i <= 100_000; i++) {
			double c = i / 100_000.0 * 3 - 1; // -1 ~ 2 주기 (음수와 1 을 넘는 값도)
			worst = Math.max(worst, Math.abs(Synth.sin2pi(c) - StrictMath.sin(2 * Math.PI * c)));
		}
		assertTrue(worst < 1e-6, "표를 보간한 사인의 오차는 들리지 않을 만큼 작다 (-120dB 아래): " + worst);
		assertEquals(0.0, Synth.sin2pi(-1e-18), 1e-9, "거의 0 인 음수도 표 밖으로 나가지 않는다");
	}

	@Test
	void frequentSoundsAreShortAndQuiet() {
		for (Sfx e : new Sfx[] { Sfx.MOVE, Sfx.ROTATE, Sfx.MENU_MOVE, Sfx.LAND, Sfx.LOCK }) {
			for (int v = 0; v < e.variants; v++) {
				float[] c = BANK.clip(e, v);
				double limit = e == Sfx.LAND || e == Sfx.LOCK ? 200 : 100;
				assertTrue(c.length * 1000.0 / Synth.RATE <= limit, e + ": " + limit + "ms 안에 끝난다");
				assertTrue(rel(e, v) <= -12 + 0.3, e + ": 테트리스보다 12dB 넘게 작다");
			}
		}
		assertTrue(rel(Sfx.MOVE, 0) < rel(Sfx.CLEAR, 0) - 10, "이동은 한 줄 지우기보다 10dB 넘게 작다");
	}

	@Test
	void clearsGrowWithLinesAndTetrisStandsOut() {
		double previous = 0;
		for (int v = 0; v < 3; v++) {
			double e = energy(BANK.clip(Sfx.CLEAR, v));
			assertTrue(e > previous, (v + 1) + "줄은 " + v + "줄보다 크다");
			previous = e;
		}
		float[] triple = BANK.clip(Sfx.CLEAR, 2);
		float[] tetris = BANK.clip(Sfx.CLEAR, 3);
		assertTrue(tetris.length > triple.length * 1.3, "테트리스는 더 길다");
		assertTrue(energy(tetris) > 2 * energy(triple), "테트리스는 훨씬 크다");
		assertSame(tetris, BANK.clip(Sfx.CLEAR, Sfx.CLEAR.tier(4)), "세기 4 = 테트리스");
	}

	@Test
	void hardDropTiersGrowWithTheDistanceAndTheLongestFallIsLower() {
		assertEquals(6, Sfx.HARD_DROP.variants, "단계 셋 × 녹음 둘");
		for (int tier = 0; tier < 3; tier++) {
			for (int which = 0; which < 2; which++) {
				double want = new double[] { -10, -8, -6 }[tier];
				assertEquals(want, rel(Sfx.HARD_DROP, Sfx.HARD_DROP.variant(tier, which)), 0.3,
						"단계 " + tier + " 녹음 " + which);
			}
		}
		float[] mid = BANK.clip(Sfx.HARD_DROP, Sfx.HARD_DROP.variant(1, 0));
		float[] far = BANK.clip(Sfx.HARD_DROP, Sfx.HARD_DROP.variant(2, 0));
		double ratio = far.length / (double) mid.length;
		assertEquals(SoundBank.SEMITONE, ratio, 0.01, "10칸 이상은 같은 녹음을 반음 낮게 (그만큼 느리게 읽어 길다)");
		assertNotSame(BANK.clip(Sfx.HARD_DROP, 0), BANK.clip(Sfx.HARD_DROP, 1), "단계마다 녹음 둘을 번갈아 쓴다");
	}

	@Test
	void theMiniTSpinIsItsOwnLighterSound() {
		float[] mini = BANK.clip(Sfx.TSPIN, Sfx.TSPIN.tier(0));
		float[] single = BANK.clip(Sfx.TSPIN, Sfx.TSPIN.tier(1));
		assertNotSame(mini, single);
		assertTrue(mini.length < single.length, "미니는 더 짧다");
		assertTrue(tone(single, Synth.midi(50)) > 1000 * tone(mini, Synth.midi(50)), "미니에는 낮은 울림이 없다");
		assertEquals(-5, rel(Sfx.TSPIN, 0), 0.3, "미니는 한 줄 지우기만큼");
		assertEquals(-3, rel(Sfx.TSPIN, 1), 0.3, "싱글");
	}

	@Test
	void b2bStreaksAddLayers() {
		float[] one = BANK.clip(Sfx.B2B, Sfx.B2B.tier(1));
		float[] four = BANK.clip(Sfx.B2B, Sfx.B2B.tier(4));
		float[] eight = BANK.clip(Sfx.B2B, Sfx.B2B.tier(8));
		double d7 = Synth.midi(98);
		assertTrue(tone(four, d7) > 20 * tone(one, d7), "4 연속부터 한 옥타브 위 화음(레 D7)이 겹친다");
		assertTrue(above(eight, 5000) > 2 * above(four, 5000), "8 연속부터 5kHz 위의 반짝임이 겹친다");
		assertTrue(rel(Sfx.B2B, 2) > rel(Sfx.B2B, 1) && rel(Sfx.B2B, 1) > rel(Sfx.B2B, 0), "연속이 길수록 조금씩 크다");
	}

	@Test
	void comboPitchClimbsToTenThenStacksNotes() {
		for (int n = 1; n < 10; n++) {
			assertTrue(SoundBank.comboHz(n + 1) > SoundBank.comboHz(n), (n + 1) + " 콤보는 " + n + " 콤보보다 높다");
		}
		assertEquals(Synth.midi(100), SoundBank.comboHz(10), 1e-9, "10 콤보 = 미(E7)");
		assertEquals(SoundBank.comboHz(10), SoundBank.comboHz(12), 1e-9, "11·12 콤보는 라(A7)까지 오르지 않고 미에 머문다");
		assertEquals(SoundBank.comboHz(12), SoundBank.comboHz(30), 1e-9, "그 위도 같은 높이");
		for (int n = 1; n <= 10; n++) {
			float[] c = BANK.clip(Sfx.COMBO, Sfx.COMBO.tier(n));
			double own = tone(c, SoundBank.comboHz(n));
			if (n > 1) {
				assertTrue(own > 10 * tone(c, SoundBank.comboHz(n - 1)), n + " 콤보 소리는 제 높이로 울린다");
			}
			if (n < 10) {
				assertTrue(own > 10 * tone(c, SoundBank.comboHz(n + 1)), n + " 콤보 소리는 제 높이로 울린다");
			}
		}
		double e6 = Synth.midi(88);
		double b6 = Synth.midi(95);
		float[] ten = BANK.clip(Sfx.COMBO, 9);
		float[] eleven = BANK.clip(Sfx.COMBO, 10);
		float[] twelve = BANK.clip(Sfx.COMBO, 11);
		assertTrue(tone(eleven, e6) > 1000 * tone(ten, e6), "11 콤보는 한 옥타브 아래(E6)를 쌓는다");
		assertTrue(tone(twelve, b6) > 1000 * tone(eleven, b6), "12 콤보는 5도(B6)를 더 쌓는다");
	}

	@Test
	void sandChainRisesLikeCombos() {
		for (int chain = 2; chain < 10; chain++) {
			assertTrue(SoundBank.chainHz(chain + 1) > SoundBank.chainHz(chain), "연쇄 " + (chain + 1) + " 은 더 높다");
		}
		for (int chain = 2; chain <= 10; chain++) {
			float[] c = BANK.clip(Sfx.SAND_CHAIN, Sfx.SAND_CHAIN.tier(chain));
			double own = tone(c, SoundBank.chainHz(chain));
			if (chain < 10) {
				assertTrue(own > 10 * tone(c, SoundBank.chainHz(chain + 1)), "연쇄 " + chain + " 은 제 높이로 울린다");
			}
		}
	}

	@Test
	void garbageWarningGrowsWithLinesAndCarriesAnAlarmTone() {
		double previous = 0;
		for (int lines = 1; lines <= 8; lines++) {
			float[] c = BANK.clip(Sfx.GARBAGE, Sfx.GARBAGE.tier(lines));
			double e = energy(c);
			assertTrue(e > previous, lines + "줄 경고는 더 작은 경고보다 크다");
			previous = e;
		}
		assertTrue(BANK.clip(Sfx.GARBAGE, 7).length > BANK.clip(Sfx.GARBAGE, 0).length, "많이 받으면 더 길게(여러 번) 울린다");
		for (int v = 0; v < 2; v++) {
			float[] c = BANK.clip(Sfx.GARBAGE, v);
			assertTrue(tone(c, 440) > 10 * tone(c, 523.25), (v + 1) + "줄: 쿵마다 라(440Hz) 경보음이 겹친다");
		}
		previous = 0;
		for (int lines = 1; lines <= 8; lines++) {
			double len = BANK.clip(Sfx.GARBAGE_RISE, lines - 1).length;
			assertTrue(len > previous, "방해 줄이 많이 올라올수록 길다");
			previous = len;
		}
	}

	@Test
	void theCancelIsAShortRisingClink() {
		float[] c = BANK.clip(Sfx.GARBAGE_CANCEL, 0);
		assertTrue(c.length * 1000.0 / Synth.RATE < 300, "짧다");
		assertTrue(tone(c, Synth.midi(89)) > 5 * tone(c, Synth.midi(88)), "미(E6)에서 파(F6)로 올라 파에 머문다");
		assertTrue(tone(c, Synth.midi(89)) > 100 * tone(c, Synth.midi(84)), "도(C6)가 아니라 파로 울린다");
		assertEquals(-8, rel(Sfx.GARBAGE_CANCEL, 0), 0.3);
	}

	@Test
	void shuffledVariantsSoundAlike() {
		for (Sfx e : Sfx.values()) {
			if (e.choices < 2) {
				continue;
			}
			for (int tier = 0; tier < e.tiers; tier++) {
				double first = energy(BANK.clip(e, e.variant(tier, 0)));
				for (int k = 1; k < e.choices; k++) {
					double ratio = energy(BANK.clip(e, e.variant(tier, k))) / first;
					assertTrue(ratio > 0.6 && ratio < 1.6, e + " 묶음 " + tier + " 변형 " + k + ": 섞어 쓰는 소리는 크기가 비슷하다 ("
							+ ratio + ")");
				}
			}
		}
	}

	@Test
	void outOfRangeVariantsUseTheNearestClip() {
		assertSame(BANK.clip(Sfx.CLEAR, 3), BANK.clip(Sfx.CLEAR, 99));
		assertSame(BANK.clip(Sfx.CLEAR, 0), BANK.clip(Sfx.CLEAR, -5));
	}

	@Test
	void allClipsFitInAFewMegabytes() {
		long frames = BANK.totalFrames();
		assertTrue(frames < 60L * Synth.RATE, "모두 합해 60초(약 10MB) 미만: " + frames / (double) Synth.RATE + "초");
	}

	/** 가장 시끄러운 0.4초의 들리는 크기 (dB). */
	private static double loud(Sfx e, int v) {
		return Loudness.max(BANK.clip(e, v), 400);
	}

	@Test
	void heardLoudnessFollowsTheImportanceOfTheEvent() {
		for (int v = 1; v < 4; v++) {
			assertTrue(loud(Sfx.CLEAR, v) > loud(Sfx.CLEAR, v - 1) + 0.5,
					(v + 1) + "줄 지우기는 " + v + "줄보다 크게 들린다 (낮은 쿵이 아니라 들리는 크기로)");
		}
		double single = loud(Sfx.CLEAR, 0);
		for (Sfx e : new Sfx[] { Sfx.MOVE, Sfx.ROTATE, Sfx.MENU_MOVE, Sfx.LAND, Sfx.SAND_LAND }) {
			for (int v = 0; v < e.variants; v++) {
				assertTrue(loud(e, v) < single - 10, e + " 변형 " + v + ": 자주 나는 소리는 한 줄 지우기보다 10dB 넘게 작다 ("
						+ loud(e, v) + " / " + single + ")");
			}
		}
		for (Sfx e : new Sfx[] { Sfx.KO, Sfx.WIN, Sfx.PERFECT_CLEAR, Sfx.NEW_RECORD }) {
			assertTrue(loud(e, 0) > single + 2, e + ": 판의 큰 사건은 한 줄 지우기보다 크다");
		}
		assertTrue(loud(Sfx.CLEAR, 3) > single + 3, "테트리스는 한 줄보다 확실히 크다");
	}

	@Test
	void warningsCanBeHeardOnLaptopSpeakers() {
		// 노트북 스피커는 250Hz 아래를 거의 내지 못한다. 그래도 방해 줄 경고와 위험 심장 소리는 회전 소리보다 크게 들려야 한다
		double rotate = Loudness.max(Loudness.smallSpeaker(BANK.clip(Sfx.ROTATE, 0)), 400);
		for (Sfx e : new Sfx[] { Sfx.GARBAGE, Sfx.DANGER }) {
			double small = Loudness.max(Loudness.smallSpeaker(BANK.clip(e, 0)), 400);
			assertTrue(small > rotate + 1, e + ": 작은 스피커에서 " + small + " dB, 회전 " + rotate + " dB");
		}
	}

	@Test
	void recordedImpactsLoseLessOnLaptopSpeakersThanTheSynthesizedThumps() {
		SoundBank synth = SoundBank.generate(name -> null);
		for (Sfx e : new Sfx[] { Sfx.LAND, Sfx.LOCK, Sfx.HARD_DROP }) {
			double recorded = loss(BANK.clip(e, 0));
			double synthesized = loss(synth.clip(e, 0));
			assertTrue(recorded + 1 < synthesized,
					e + ": 노트북에서 녹음은 " + recorded + "dB, 합성음은 " + synthesized + "dB 작아진다");
		}
	}

	/** 노트북 스피커 흉내에서 작아지는 양 (dB). */
	private static double loss(float[] c) {
		return Loudness.max(c, 400) - Loudness.max(Loudness.smallSpeaker(c), 400);
	}

	@Test
	void highSparklesStayBelowEightKilohertz() {
		assertEquals(4, SoundBank.harmonicBelow(784, 4, SoundBank.SPARKLE_MAX_HZ), "솔(G5): 4 배음 3.1kHz 그대로");
		assertEquals(2, SoundBank.harmonicBelow(3520, 4, SoundBank.SPARKLE_MAX_HZ), "라(A7): 4 배음 14kHz 대신 2 배음");
		assertEquals(1, SoundBank.harmonicBelow(9000, 4, SoundBank.SPARKLE_MAX_HZ), "기본음보다 낮출 수는 없다");
		float[] top = BANK.clip(Sfx.COMBO, Sfx.COMBO.tier(12));
		double f = SoundBank.comboHz(12);
		assertTrue(tone(top, 4 * f) < tone(top, 3 * f) / 1000, "12 콤보의 시작 반짝임은 10.5kHz 가 아니라 7.9kHz 에 있다");
	}
}
