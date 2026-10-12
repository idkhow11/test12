package kr.ac.jbnu.se.tetris.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * 소리를 내보낼 곳. 실제로는 Java Sound 의 출력 줄({@link #openLine()})이고, 시험은 받은 PCM 을 모으는 가짜를 쓴다. 믹서
 * 스레드 하나만 부른다. 연 직후에는 멈춰 있고 {@link #resume()} 으로 움직이기 시작한다.
 */
interface AudioOut {

	/** 출력 형식: 44.1kHz, 16 비트, 스테레오, 부호 있음, 작은 끝. */
	AudioFormat FORMAT = new AudioFormat(Synth.RATE, 16, 2, true, false);

	/** PCM 을 쓴다. 장치 버퍼가 차 있으면 자리가 날 때까지 기다린다. 쓴 바이트 수를 돌려준다. */
	int write(byte[] data, int off, int len);

	/** 한동안 조용할 때 장치를 쉬게 한다(남은 것은 버린다). */
	void pause();

	/** 장치를 움직인다 (처음 시작, 그리고 쉬던 장치를 다시). */
	void resume();

	/**
	 * 장치에 남은 소리를 다 내보낼 때까지 기다린다 (닫기 전에, 움직이는 장치에서만 부른다). 기본은 아무것도 하지 않는다(시험의
	 * 가짜 출력).
	 */
	default void drain() {
	}

	/**
	 * 지금 기다리지 않고 쓸 수 있는 바이트 수. 장치 버퍼 크기({@link #bufferBytes()})와 같으면 버퍼가 비었다: 소리가 끊겼다
	 * (언더런). 모르면 -1 (기본, 시험의 가짜 출력).
	 */
	default int available() {
		return -1;
	}

	/** 장치 버퍼의 크기 (바이트). 모르면 -1 (기본). */
	default int bufferBytes() {
		return -1;
	}

	/** 장치를 닫는다. 남은 소리는 버린다(그래서 닫기 전에 줄이고 {@link #drain()} 한다). */
	void close();

	/** 출력을 여는 방법. 열 수 없으면 예외를 던진다(엔진은 소리 없이 계속한다). */
	@FunctionalInterface
	interface Opener {
		AudioOut open() throws Exception;
	}

	/**
	 * 기본 소리 장치의 출력 줄을 연다(아직 움직이지 않는다). 장치 버퍼는 {@code bufferFrames} 프레임: 작을수록 소리가 빨리
	 * 나지만 스레드가 잠깐 밀리면 끊길 수 있다.
	 *
	 * @throws LineUnavailableException 장치를 쓸 수 없을 때
	 * @throws IllegalArgumentException 이 형식을 받는 장치가 없을 때(소리 장치가 없는 PC, 헤드리스 서버)
	 */
	static AudioOut openLine(int bufferFrames) throws LineUnavailableException {
		SourceDataLine line = AudioSystem.getSourceDataLine(FORMAT);
		try {
			line.open(FORMAT, bufferFrames * FORMAT.getFrameSize());
		} catch (LineUnavailableException | RuntimeException e) {
			line.close();
			throw e;
		}
		return new AudioOut() {
			@Override
			public int write(byte[] data, int off, int len) {
				return line.write(data, off, len);
			}

			@Override
			public void pause() {
				line.stop();
				line.flush();
			}

			@Override
			public void resume() {
				line.start();
			}

			@Override
			public void drain() {
				line.drain(); // 버퍼 25~40ms. 장치가 멈춰 위치가 움직이지 않으면 JDK 가 스스로 그만둔다
			}

			@Override
			public int available() {
				return line.available();
			}

			@Override
			public int bufferBytes() {
				return line.getBufferSize();
			}

			@Override
			public void close() {
				line.stop();
				line.flush();
				line.close();
			}
		};
	}
}
