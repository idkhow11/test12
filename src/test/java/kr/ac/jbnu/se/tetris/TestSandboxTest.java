package kr.ac.jbnu.se.tetris;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.LauncherSessionListener;

/** 모든 JUnit 실행의 안전장치({@link TestSandbox}): JUnit 이 찾는 등록, 없는 값만 끄기, 창 없음. */
class TestSandboxTest {

	@Test
	void junitFindsTheSandboxThroughTheServiceFile() {
		// JUnit 은 이 등록 파일로 찾아 시험을 찾기 전에 부른다. 파일 이름이나 줄이 틀리면 IDE 실행에서 조용히 빠진다.
		assertTrue(ServiceLoader.load(LauncherSessionListener.class, TestSandboxTest.class.getClassLoader()).stream()
				.anyMatch(p -> p.type() == TestSandbox.class));
	}

	@Test
	void missingOrBlankValuesBecomeOffAndGivenValuesStay() {
		Properties p = new Properties(); // 진짜 시스템 속성은 건드리지 않는다
		p.setProperty("tetris.config", "  ");
		p.setProperty("tetris.records", "build/records-here");
		p.setProperty("java.awt.headless", "false");
		TestSandbox.apply(p);
		assertEquals("off", p.getProperty("tetris.sound"), "없으면 끈다");
		assertEquals("off", p.getProperty("tetris.config"), "비어 있으면 준 것으로 치지 않는다(ConfigDir 과 같다)");
		assertEquals("build/records-here", p.getProperty("tetris.records"), "시험이 준 폴더는 그대로 둔다");
		assertEquals("true", p.getProperty("java.awt.headless"), "창은 언제나 끈다");
	}

	@Test
	void thisRunIsSandboxed() {
		// Gradle 이든 IDE 든 이 JVM 은 창 없이, 소리 장치·설정 파일·사람 판 기록 없이(또는 시험이 준 값으로) 돈다.
		assertEquals("true", System.getProperty("java.awt.headless"));
		for (String key : TestSandbox.OFF_UNLESS_GIVEN) {
			String v = System.getProperty(key);
			assertTrue(v != null && !v.isBlank(), key + " 이 비었다");
		}
	}
}
