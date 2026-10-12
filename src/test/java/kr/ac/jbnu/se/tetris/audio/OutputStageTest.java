package kr.ac.jbnu.se.tetris.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** 마지막 단계 (OutputStage): 볼륨은 부드럽게 바뀌고, 아무리 많이 겹쳐도 16 비트 범위를 넘거나 찢어지지 않는다. */
class OutputStageTest {

	private static final int FRAMES = 256;

	private static short sample(byte[] pcm, int frame, int channel) {
		int at = 4 * frame + 2 * channel;
		return (short) ((pcm[at] & 0xFF) | (pcm[at + 1] << 8));
	}

	private static float[] mix(float v) {
		float[] m = new float[FRAMES * 2];
		Arrays.fill(m, v);
		return m;
	}

	@Test
	void silenceStaysSilent() {
		OutputStage s = new OutputStage();
		byte[] pcm = new byte[FRAMES * 4];
		Arrays.fill(pcm, (byte) 7);
		s.process(new float[FRAMES * 2], FRAMES, 1f, pcm);
		for (byte b : pcm) {
			assertEquals(0, b);
		}
	}

	@Test
	void quietSoundsPassUnchanged() {
		OutputStage s = new OutputStage();
		byte[] pcm = new byte[FRAMES * 4];
		s.process(mix(0f), FRAMES, 1f, pcm); // 볼륨을 1 로 올려 둔다
		s.process(mix(0.25f), FRAMES, 1f, pcm);
		for (int f = 0; f < FRAMES; f++) {
			assertEquals(Math.round(0.25f * 32767), sample(pcm, f, 0));
			assertEquals(Math.round(0.25f * 32767), sample(pcm, f, 1));
		}
	}

	@Test
	void hugeMixesAreLimitedNeverWrappedOrClipped() {
		OutputStage s = new OutputStage();
		byte[] pcm = new byte[FRAMES * 4];
		s.process(mix(0f), FRAMES, 1f, pcm);
		float[] loud = new float[FRAMES * 2];
		for (int i = 0; i < loud.length; i++) {
			loud[i] = (i / 2 % 2 == 0 ? 1 : -1) * (2f + i % 7); // 소리 8 개가 다 겹친 것보다 크다
		}
		s.process(loud, FRAMES, 1f, pcm);
		int limit = Math.round(OutputStage.THRESHOLD * 32767) + 1;
		for (int f = 0; f < FRAMES; f++) {
			for (int ch = 0; ch < 2; ch++) {
				short x = sample(pcm, f, ch);
				assertTrue(Math.abs(x) <= limit, "리미터가 눌러 준다: " + x);
				assertEquals(loud[2 * f + ch] > 0, x > 0, "부호가 뒤집히지 않는다(넘침 없음)");
			}
		}
	}

	@Test
	void volumeChangesRampAcrossTheBlock() {
		OutputStage s = new OutputStage();
		byte[] pcm = new byte[FRAMES * 4];
		s.process(mix(0.5f), FRAMES, 1f, pcm); // 볼륨 0 에서 1 로
		assertTrue(sample(pcm, 0, 0) < 200, "처음에는 거의 0 에서");
		assertEquals(Math.round(0.5f * 32767), sample(pcm, FRAMES - 1, 0), "블록 끝에서 목표 볼륨");
		for (int f = 1; f < FRAMES; f++) {
			assertTrue(sample(pcm, f, 0) >= sample(pcm, f - 1, 0), "곧게 올라간다(지지직 없음)");
		}
		s.process(mix(0.5f), FRAMES, 0f, pcm); // 음소거: 한 블록에 걸쳐 0 으로
		assertEquals(0, sample(pcm, FRAMES - 1, 0));
		s.process(mix(0.5f), FRAMES, 0f, pcm);
		for (int f = 0; f < FRAMES; f++) {
			assertEquals(0, sample(pcm, f, 0));
		}
	}

	@Test
	void snapSetsTheVolumeAtOnceAfterSilence() {
		OutputStage s = new OutputStage(); // 처음 볼륨은 0
		byte[] pcm = new byte[FRAMES * 4];
		s.snap(1f);
		s.process(mix(0.25f), FRAMES, 1f, pcm);
		assertEquals(Math.round(0.25f * 32767), sample(pcm, 0, 0), "첫 표본부터 제 크기(짧은 소리의 시작이 흐려지지 않는다)");
	}

	@Test
	void saturationApproachesButNeverExceedsFullScale() {
		assertEquals(0.5f, OutputStage.saturate(0.5f));
		assertTrue(OutputStage.saturate(1.5f) < 1f, "조금 넘친 소리는 1 보다 작게");
		assertTrue(OutputStage.saturate(-1.5f) > -1f);
		assertTrue(OutputStage.saturate(1e6f) <= 1f, "아무리 커도 1 을 넘지 않는다");
		assertTrue(OutputStage.saturate(-1e6f) >= -1f);
		assertTrue(OutputStage.saturate(0.95f) > OutputStage.saturate(0.92f), "커질수록 계속 커진다(모양이 뒤집히지 않음)");
		assertEquals(0f, OutputStage.saturate(Float.NaN));
	}

	@Test
	void aNanInTheMixDoesNotSilenceLaterBlocks() {
		OutputStage s = new OutputStage();
		byte[] pcm = new byte[FRAMES * 4];
		float[] bad = mix(0.25f);
		bad[10] = Float.NaN;
		s.process(bad, FRAMES, 1f, pcm);
		s.process(mix(0.25f), FRAMES, 1f, pcm);
		assertEquals(Math.round(0.25f * 32767), sample(pcm, FRAMES - 1, 0), "다음 블록은 정상");
	}
}
