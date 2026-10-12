package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * 작은 믹서 (Voices): 섞기, 크기 배율과 빠르기(이어 읽기), 같은 힘의 좌우 위치, 늦게 시작, 같은 소리는 쌓이지 않음, 동시 소리
 * 한도와 중요도, 딸깍 없는 양보, 덕킹(큰 사건 소리가 보상·경고 소리를 줄였다가 풀어 줌, 조작·메뉴·판 흐름은 그대로, 위험 심장
 * 소리는 보상 소리 직후 6dB).
 */
class VoicesTest {

	private static final int CHUNK = Sound.CHUNK;
	/** 덕킹 시험에서 끝까지 울리는 긴 소리 (약 4.5초). */
	private static final int LONG = 200_000;

	private static float[] constant(int frames, float v) {
		float[] c = new float[frames];
		Arrays.fill(c, v);
		return c;
	}

	private static float[] ramp(int frames) {
		float[] c = new float[frames];
		for (int i = 0; i < frames; i++) {
			c[i] = (i + 1) / (float) frames * 0.5f;
		}
		return c;
	}

	private static double gainDb(float out, float level) {
		return -20 * Math.log10(out / level);
	}

	/** 블록을 {@code chunks} 개 섞고 마지막 블록을 돌려준다. */
	private static float[] renderChunks(Voices v, int chunks) {
		float[] out = new float[CHUNK * 2];
		for (int i = 0; i < chunks; i++) {
			v.render(out, CHUNK);
		}
		return out;
	}

	private static int chunksFor(double ms) {
		return (int) Math.ceil(ms * Synth.RATE / 1000.0 / CHUNK);
	}

	@Test
	void silenceWhenNothingPlays() {
		Voices v = new Voices();
		float[] out = constant(512, 9f); // 덮어쓰는지 보려고 쓰레기 값을 넣어 둔다
		v.render(out, 256);
		for (float x : out) {
			assertEquals(0f, x);
		}
		assertTrue(v.idle());
	}

	@Test
	void centeredVoiceGoesToBothChannelsUnchangedAndEnds() {
		Voices v = new Voices();
		float[] pcm = ramp(100);
		assertTrue(v.start(Sfx.CLEAR, pcm, 0f, 0));
		float[] out = new float[512];
		v.render(out, 256);
		for (int i = 0; i < 100; i++) {
			assertEquals(pcm[i], out[2 * i], "왼쪽 " + i);
			assertEquals(pcm[i], out[2 * i + 1], "오른쪽 " + i);
		}
		assertEquals(0f, out[2 * 100], "끝난 뒤는 조용하다");
		assertTrue(v.idle(), "끝난 소리는 자리를 비운다");
	}

	@Test
	void panKeepsTheSamePowerEverywhere() {
		for (float p : new float[] { -1f, -0.45f, -0.1f, 0f, 0.3f, 0.5f, 1f }) {
			Voices v = new Voices();
			v.start(Sfx.CLEAR, constant(64, 0.4f), p, 0);
			float[] out = new float[128];
			v.render(out, 64);
			double l = out[0] / 0.4;
			double r = out[1] / 0.4;
			assertEquals(2.0, l * l + r * r, 1e-5, "위치 " + p + ": L² + R² = 2 (어디서나 같은 힘)");
			assertEquals(p < 0, l > r + 1e-6, "위치 " + p + ": 왼쪽이면 왼쪽이 크다");
		}
		Voices v = new Voices();
		v.start(Sfx.CLEAR, constant(64, 0.4f), 0f, 0);
		float[] out = new float[128];
		v.render(out, 64);
		assertEquals(0.4f, out[0], "가운데는 예전처럼 양쪽 모두 원래 크기");
		assertEquals(0.4f, out[1]);
		v = new Voices();
		v.start(Sfx.CLEAR, constant(64, 0.4f), -1f, 0);
		v.render(out, 64);
		assertEquals(0.4 * Math.sqrt(2), out[0], 1e-5, "왼쪽 끝: 왼쪽에 모든 힘");
		assertEquals(0f, out[1], 1e-6f, "왼쪽 끝: 오른쪽은 없음");
	}

	@Test
	void gainAndRateScaleAndStretchTheSound() {
		Voices v = new Voices();
		float[] pcm = { 0f, 0.2f, 0.4f, 0.6f };
		assertTrue(v.start(Sfx.MOVE, pcm, 0f, 0, 0.5f, 0.5));
		float[] out = new float[64];
		v.render(out, 32);
		float[] want = { 0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.3f }; // 반 빠르기: 표본 사이를 곧게 이어 읽는다
		for (int i = 0; i < want.length; i++) {
			assertEquals(want[i] * 0.5f, out[2 * i], 1e-6f, i + " 번째 프레임 (크기 0.5 배)");
		}
		assertEquals(0f, out[2 * want.length], "두 배로 늘어난 뒤 끝난다");
		assertTrue(v.idle());

		v = new Voices();
		double semitoneUp = Math.pow(2, 100 / 1200.0);
		v.start(Sfx.MOVE, constant(1000, 0.25f), 0f, 0, 1f, semitoneUp);
		int frames = 0;
		while (!v.idle()) {
			v.render(out, 32);
			frames += 32;
		}
		assertEquals(1000 / semitoneUp, frames, 32, "반음 높이면 그만큼 빨리 끝난다");
	}

	@Test
	void delayedVoiceStartsLaterEvenAcrossBlocks() {
		Voices v = new Voices();
		float[] pcm = ramp(50);
		v.start(Sfx.GARBAGE, pcm, 0f, 300);
		float[] out = new float[512];
		v.render(out, 256);
		for (float x : out) {
			assertEquals(0f, x, "아직 시작 전");
		}
		v.render(out, 256);
		assertEquals(0f, out[2 * 43], "300 - 256 = 44 번째 프레임부터");
		assertEquals(pcm[0], out[2 * 44]);
		assertEquals(pcm[49], out[2 * 93]);
	}

	@Test
	void theSameSoundReplacesItselfInsteadOfStacking() {
		Voices v = new Voices();
		for (int i = 0; i < 5; i++) {
			assertTrue(v.start(Sfx.MOVE, constant(4000, 0.1f), 0f, 0));
		}
		assertEquals(1, v.live(Sfx.MOVE), "이동 소리는 하나만 (앞의 것은 짧게 사라진다)");
		for (int i = 0; i < 3; i++) {
			v.start(Sfx.CLEAR, constant(4000, 0.1f), 0f, 0);
		}
		assertEquals(2, v.live(Sfx.CLEAR), "줄 지우기는 둘까지 (빨리 이어진 두 줄 지우기)");
	}

	@Test
	void polyphonyIsLimitedAndLessImportantSoundsGiveWay() {
		Voices v = new Voices();
		Sfx[] fill = { Sfx.CLEAR, Sfx.CLEAR, Sfx.TSPIN, Sfx.TSPIN, Sfx.COMBO, Sfx.B2B, Sfx.GARBAGE, Sfx.GO };
		for (Sfx e : fill) {
			assertTrue(v.start(e, constant(40_000, 0.05f), 0f, 0), e.name());
		}
		assertEquals(Voices.MAX_VOICES, v.live());
		assertFalse(v.start(Sfx.MOVE, constant(100, 0.05f), 0f, 0), "덜 중요한 새 소리는 버린다");
		assertEquals(Voices.MAX_VOICES, v.live());
		assertTrue(v.start(Sfx.KO, constant(40_000, 0.05f), 0f, 0), "더 중요한 소리는 자리를 얻는다");
		assertEquals(Voices.MAX_VOICES, v.live(), "가장 덜 중요하고 오래된 소리가 양보한다");
		assertEquals(1, v.live(Sfx.CLEAR), "처음 시작한 줄 지우기가 양보했다");
		assertTrue(v.start(Sfx.SAND_CLEAR, constant(100, 0.05f), 0f, 0), "같은 중요도면 가장 오래된 것과 바꾼다");
		assertEquals(Voices.MAX_VOICES, v.live());
	}

	@Test
	void aVoiceThatGivesWayFadesWithoutAClick() {
		Voices v = new Voices();
		v.start(Sfx.MOVE, constant(10_000, 0.5f), 0f, 0);
		float[] out = new float[512];
		v.render(out, 256);
		v.start(Sfx.MOVE, new float[10_000], 0f, 0); // 같은 소리로 바꾼다: 앞의 0.5 짜리가 사라진다
		v.render(out, 256);
		float maxStep = 0;
		float prev = 0.5f;
		for (int i = 0; i < 256; i++) {
			maxStep = Math.max(maxStep, Math.abs(out[2 * i] - prev));
			prev = out[2 * i];
		}
		assertEquals(0f, out[2 * Voices.FADE_FRAMES + 2], "사라짐이 끝나면 조용하다");
		assertTrue(maxStep <= 0.5f / Voices.FADE_FRAMES + 1e-6f, "한 표본에 조금씩만 줄어든다: " + maxStep);
	}

	@Test
	void releaseAllFadesEverythingQuickly() {
		Voices v = new Voices();
		v.start(Sfx.CLEAR, constant(40_000, 0.2f), 0f, 0);
		v.start(Sfx.KO, constant(40_000, 0.2f), 0f, 0);
		v.start(Sfx.WIN, constant(40_000, 0.2f), 0f, 0);
		v.releaseAll();
		assertEquals(0, v.live());
		float[] out = new float[512];
		v.render(out, 256);
		assertTrue(v.idle(), Voices.FADE_FRAMES + " 프레임 안에 모두 끝난다");
	}

	@Test
	void aTetrisDucksRewardsThatStartWithIt() {
		// 테트리스(왼쪽 끝)와 콤보(오른쪽 끝)를 같이: 오른쪽 채널이 콤보만의 크기다
		Voices v = new Voices();
		v.start(Sfx.CLEAR, 4, constant(LONG, 0.5f), -1f, 0, 1f, 1.0);
		v.start(Sfx.COMBO, 3, constant(LONG, 0.5f), 1f, 0, 1f, 1.0);
		float level = (float) (0.5 * Math.sqrt(2));
		float[] out = renderChunks(v, 1);
		assertEquals(5.0, gainDb(out[1], level), 0.01, "함께 시작한 콤보는 첫 표본부터 5dB 작다");
		assertEquals(0.0, gainDb(out[0], level), 1e-4, "테트리스 자신은 그대로");
		out = renderChunks(v, chunksFor(Voices.DUCK_HOLD_MS) - 2);
		assertEquals(5.0, gainDb(out[2 * CHUNK - 1], level), 0.01, "0.3초 동안 줄인 채로");
		out = renderChunks(v, 2 + chunksFor(Sfx.DUCK_RELEASE_MS));
		assertEquals(5 / Math.E, v.duckDb(Sfx.COMBO), 0.4, "다음 0.4초(시간 상수)에 1/e 만큼 남는다");
		out = renderChunks(v, chunksFor(4 * Sfx.DUCK_RELEASE_MS));
		assertEquals(0.0, v.duckDb(Sfx.COMBO), 0.1, "다 풀린다");
		assertEquals(0.0, v.duckDb(Sfx.CLEAR), 1e-9);
	}

	@Test
	void soundsAlreadyPlayingAreDuckedSmoothly() {
		Voices v = new Voices();
		v.start(Sfx.GARBAGE, 1, constant(LONG, 0.5f), 1f, 0, 1f, 1.0); // 경고 묶음
		renderChunks(v, 4);
		assertEquals(0.0, v.duckDb(Sfx.GARBAGE), 1e-9, "큰 사건 전에는 그대로");
		v.start(Sfx.PERFECT_CLEAR, 1, constant(LONG, 0.5f), -1f, 0, 1f, 1.0);
		float[] out = renderChunks(v, 1);
		double oneBlock = 6 * (1 - Math.exp(-(CHUNK * 1000.0 / Synth.RATE) / Voices.DUCK_ATTACK_MS));
		assertEquals(oneBlock, v.duckDb(Sfx.GARBAGE), 0.01, "울리던 소리는 15ms 시간 상수로 줄어든다");
		float step = 0;
		for (int i = 1; i < CHUNK; i++) {
			step = Math.max(step, Math.abs(out[2 * i + 1] - out[2 * i - 1]));
		}
		assertTrue(step < 0.002f, "블록 안에서 곧게 옮겨 가 지지직거리지 않는다: " + step);
		renderChunks(v, chunksFor(80));
		assertEquals(6.0, v.duckDb(Sfx.GARBAGE), 0.05, "퍼펙트 클리어는 6dB 줄인다");
		renderChunks(v, chunksFor(Voices.DUCK_HOLD_MS) + chunksFor(Sfx.PERFECT_RELEASE_MS) - chunksFor(80) - 1);
		assertEquals(6 / Math.E, v.duckDb(Sfx.GARBAGE), 0.5, "퍼펙트 클리어 뒤에는 0.6초 시간 상수로 풀린다");
	}

	@Test
	void handlingMenuAndMatchSoundsAreNeverDucked() {
		Voices v = new Voices();
		v.start(Sfx.CLEAR, 4, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		for (Sfx e : new Sfx[] { Sfx.MOVE, Sfx.HARD_DROP, Sfx.MENU_MOVE, Sfx.PAUSE, Sfx.LEVEL_UP }) {
			v.start(e, e.base, constant(LONG, 0.1f), 0f, 0, 1f, 1.0);
		}
		renderChunks(v, chunksFor(100));
		assertEquals(0.0, v.duckDb(Sfx.MOVE), 1e-9, "조작 소리는 줄이지 않는다 (계속 조작하므로)");
		assertEquals(0.0, v.duckDb(Sfx.HARD_DROP), 1e-9);
		assertEquals(0.0, v.duckDb(Sfx.MENU_MOVE), 1e-9, "메뉴 소리도");
		assertEquals(0.0, v.duckDb(Sfx.PAUSE), 1e-9);
		assertEquals(5.0, v.duckDb(Sfx.LEVEL_UP), 0.05, "경고 묶음은 줄인다");

		v = new Voices();
		v.start(Sfx.PERFECT_CLEAR, 1, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		v.start(Sfx.WIN, 1, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		renderChunks(v, chunksFor(100));
		assertEquals(0.0, v.duckDb(Sfx.WIN), 1e-9, "판 흐름(승리 음악)은 더 큰 사건 밑에서도 그대로");
	}

	@Test
	void onlyAStrongerHeroDucksAnotherHero() {
		Voices v = new Voices();
		v.start(Sfx.CLEAR, 4, constant(LONG, 0.5f), 0f, 0, 1f, 1.0); // 테트리스 5dB
		v.start(Sfx.PERFECT_CLEAR, 1, constant(LONG, 0.5f), 0f, 0, 1f, 1.0); // 퍼펙트 클리어 6dB
		v.start(Sfx.TSPIN, 2, constant(LONG, 0.5f), 0f, 0, 1f, 1.0); // T-spin 더블 5dB
		renderChunks(v, chunksFor(100));
		assertEquals(0.0, v.duckDb(Sfx.PERFECT_CLEAR), 1e-9, "가장 큰 사건은 줄이지 않는다");
		assertEquals(6.0, v.duckDb(Sfx.CLEAR), 0.05, "테트리스는 퍼펙트 클리어 밑으로");
		assertEquals(6.0, v.duckDb(Sfx.TSPIN), 0.05);

		v = new Voices();
		v.start(Sfx.CLEAR, 4, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		v.start(Sfx.TSPIN, 3, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		renderChunks(v, chunksFor(100));
		assertEquals(0.0, v.duckDb(Sfx.CLEAR), 1e-9, "같은 크기의 큰 사건끼리는 서로 줄이지 않는다");
		assertEquals(0.0, v.duckDb(Sfx.TSPIN), 1e-9);
	}

	@Test
	void smallClearsDoNotDuck() {
		Voices v = new Voices();
		v.start(Sfx.CLEAR, 3, constant(LONG, 0.5f), 0f, 0, 1f, 1.0); // 세 줄
		v.start(Sfx.TSPIN, 1, constant(LONG, 0.5f), 0f, 0, 1f, 1.0); // T-spin 싱글
		v.start(Sfx.COMBO, 2, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		renderChunks(v, chunksFor(100));
		assertEquals(0.0, v.duckDb(Sfx.COMBO), 1e-9, "작은 줄 지우기는 다른 소리를 줄이지 않는다");
	}

	@Test
	void theHeartbeatGivesWayToARewardForAMoment() {
		Voices v = new Voices();
		v.start(Sfx.DANGER, 1, constant(LONG, 0.5f), 0f, 0, 1f, 1.0);
		renderChunks(v, 4);
		assertEquals(0.0, v.duckDb(Sfx.DANGER), 1e-9);
		v.start(Sfx.CLEAR, 1, constant(LONG, 0.5f), 0f, 0, 1f, 1.0); // 한 줄 (덕커가 아닌 보상 소리)
		renderChunks(v, chunksFor(100));
		assertEquals(Voices.DANGER_DUCK_DB, v.duckDb(Sfx.DANGER), 0.05, "보상 소리 직후 심장 소리는 6dB 작다");
		renderChunks(v, chunksFor(Voices.DANGER_HOLD_MS - 100) + chunksFor(5 * Sfx.DUCK_RELEASE_MS));
		assertEquals(0.0, v.duckDb(Sfx.DANGER), 0.15, "0.4초가 지나면 다시 커진다");
	}

	@Test
	void aDelayedVoiceJoinsTheDuckWhenItStarts() {
		Voices v = new Voices();
		v.start(Sfx.CLEAR, 4, constant(LONG, 0.5f), -1f, 0, 1f, 1.0);
		v.start(Sfx.B2B, 1, constant(LONG, 0.5f), 1f, Synth.frames(110), 1f, 1.0); // B2B 는 0.11초 뒤
		float level = (float) (0.5 * Math.sqrt(2));
		float[] out = new float[CHUNK * 2];
		int rendered = 0;
		int start = Synth.frames(110);
		while (rendered + CHUNK <= start) {
			v.render(out, CHUNK);
			rendered += CHUNK;
		}
		v.render(out, CHUNK);
		int first = start - rendered;
		assertEquals(0f, out[2 * first - 1], "늦춘 소리는 아직 조용하다");
		assertEquals(5.0, gainDb(out[2 * first + 1], level), 0.01, "시작하는 순간부터 테트리스 밑으로 5dB");
	}
}
