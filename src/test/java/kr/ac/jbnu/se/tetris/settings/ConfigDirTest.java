package kr.ac.jbnu.se.tetris.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 설정 폴더: 운영체제마다 정해진 곳, 시스템 속성으로 바꾸기. */
class ConfigDirTest {

	private static final Map<String, String> NO_ENV = Map.of();

	@TempDir
	Path home;

	private String home() {
		return home.toString();
	}

	@Test
	void overrideWinsAndOffMeansNoFile() {
		Map<String, String> env = Map.of("APPDATA", home.resolve("roaming").toString());
		assertEquals(Path.of("cfg"), ConfigDir.resolve("cfg", "Windows 11", env, home()));
		assertEquals(Path.of("cfg"), ConfigDir.resolve("  cfg ", "Linux", NO_ENV, home()));
		assertNull(ConfigDir.resolve("off", "Windows 11", env, home()));
		assertNull(ConfigDir.resolve("OFF", "Linux", NO_ENV, home()));
		assertEquals(home.resolve("TetrisBattle"), ConfigDir.resolve("  ", "Windows 11",
				Map.of("APPDATA", home.toString()), home()), "빈 값은 준 것으로 치지 않는다");
	}

	@Test
	void windowsUsesAppData() {
		Path roaming = home.resolve("AppData").resolve("Roaming");
		assertEquals(roaming.resolve("TetrisBattle"),
				ConfigDir.resolve(null, "Windows 11", Map.of("APPDATA", roaming.toString()), home()));
		assertEquals(Path.of(home(), "AppData", "Roaming", "TetrisBattle"),
				ConfigDir.resolve(null, "Windows 10", NO_ENV, home()), "APPDATA 가 없으면 홈 아래");
	}

	@Test
	void macUsesApplicationSupport() {
		assertEquals(Path.of(home(), "Library", "Application Support", "TetrisBattle"),
				ConfigDir.resolve(null, "Mac OS X", NO_ENV, home()));
	}

	@Test
	void othersUseXdgConfigHomeOrDotConfig() {
		Path xdg = home.resolve("xdg");
		assertEquals(xdg.resolve("tetris-battle"),
				ConfigDir.resolve(null, "Linux", Map.of("XDG_CONFIG_HOME", xdg.toString()), home()));
		assertEquals(Path.of(home(), ".config", "tetris-battle"),
				ConfigDir.resolve(null, "Linux", Map.of("XDG_CONFIG_HOME", "relative/dir"), home()),
				"XDG 규칙대로 상대 경로는 무시한다");
		assertEquals(Path.of(home(), ".config", "tetris-battle"), ConfigDir.resolve(null, "FreeBSD", NO_ENV, home()));
	}

	@Test
	void unknownHomeMeansNoFile() {
		assertNull(ConfigDir.resolve(null, "Linux", NO_ENV, ""));
		assertNull(ConfigDir.resolve(null, "Mac OS X", NO_ENV, null));
		assertNull(ConfigDir.resolve(null, "Windows 11", NO_ENV, " "));
	}

	@Test
	void systemPropertyIsUsed() {
		String old = System.getProperty(ConfigDir.PROPERTY);
		try {
			System.setProperty(ConfigDir.PROPERTY, home.toString());
			assertEquals(home, ConfigDir.resolve());
			System.setProperty(ConfigDir.PROPERTY, ConfigDir.OFF);
			assertNull(ConfigDir.resolve());
			assertNull(SaveData.open().file(), "off 면 파일 없이 연다");
		} finally {
			if (old == null) {
				System.clearProperty(ConfigDir.PROPERTY);
			} else {
				System.setProperty(ConfigDir.PROPERTY, old);
			}
		}
	}
}
