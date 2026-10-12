package kr.ac.jbnu.se.tetris;

import java.util.List;
import java.util.Properties;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * 모든 JUnit 실행(Gradle, VS Code, IntelliJ)에서 시험이 창을 띄우거나, 진짜 소리 장치를 열거나, 사용자 설정 폴더의 저장
 * 파일을 읽고 쓰거나, 사람 판 기록({@code runs/human})에 봇 판을 남기지 않게 한다. Gradle 은 build.gradle.kts 에서 같은 값을
 * 시험 JVM 에 주지만 IDE 에서 바로 돌린 시험에는 그 값이 없다. 그래서 JUnit 이 시험을 찾기 전에 이 클래스를 부르도록
 * {@code src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener} 에 등록했다.
 * 창은 언제나 끄고({@code java.awt.headless=true}), {@code tetris.*} 는 이미 준 값(시험이 주는 임시 폴더 등)을 덮지 않는다.
 */
public final class TestSandbox implements LauncherSessionListener {

	/** 값이 없거나 비었을 때만 {@code off} 로 두는 속성: 소리 장치, 설정 폴더, 사람 판 기록. */
	static final List<String> OFF_UNLESS_GIVEN = List.of("tetris.sound", "tetris.config", "tetris.records");

	@Override
	public void launcherSessionOpened(LauncherSession session) {
		apply();
	}

	/** 이 JVM 의 시스템 속성에 적용한다. */
	public static void apply() {
		apply(System.getProperties());
	}

	/** 주어진 속성에 적용한다(시험용으로 따로 둔다). 빈 값은 준 것으로 치지 않는다({@code ConfigDir} 과 같다). */
	static void apply(Properties props) {
		props.setProperty("java.awt.headless", "true");
		for (String key : OFF_UNLESS_GIVEN) {
			String v = props.getProperty(key);
			if (v == null || v.isBlank()) {
				props.setProperty(key, "off");
			}
		}
	}
}
