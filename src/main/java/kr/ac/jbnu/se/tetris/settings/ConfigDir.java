package kr.ac.jbnu.se.tetris.settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 사용자마다 설정 파일을 두는 폴더.
 * <ul>
 * <li>{@code -Dtetris.config=<폴더>} 를 주면 그 폴더 (시험, 깨끗한 설정으로 해 보기). {@code off} 면 파일 없이 기본값만 쓴다.</li>
 * <li>Windows: {@code %APPDATA%\TetrisBattle} (보통 {@code C:\Users\<이름>\AppData\Roaming\TetrisBattle})</li>
 * <li>macOS: {@code ~/Library/Application Support/TetrisBattle}</li>
 * <li>그 밖(Linux 등): {@code $XDG_CONFIG_HOME/tetris-battle}, 없으면 {@code ~/.config/tetris-battle}</li>
 * </ul>
 * 폴더는 여기서 만들지 않는다. 처음 저장할 때 {@link SaveData} 가 만든다.
 */
public final class ConfigDir {

	/** 폴더를 바꾸는 시스템 속성. */
	public static final String PROPERTY = "tetris.config";
	/** {@link #PROPERTY} 를 이 값으로 주면 파일을 읽지도 쓰지도 않는다. */
	public static final String OFF = "off";
	/** Windows 와 macOS 의 폴더 이름. */
	public static final String APP_NAME = "TetrisBattle";
	/** 그 밖의 운영체제의 폴더 이름. */
	public static final String UNIX_NAME = "tetris-battle";

	private ConfigDir() {
	}

	/** 이 PC 의 설정 폴더. 파일 없이 써야 하면(off, 홈 폴더를 모름, 잘못된 경로) null. */
	public static Path resolve() {
		return resolve(System.getProperty(PROPERTY), System.getProperty("os.name", ""), System.getenv(),
				System.getProperty("user.home", ""));
	}

	/**
	 * 주어진 값으로 설정 폴더를 정한다 (시험용으로 따로 둔다).
	 *
	 * @param override {@code -Dtetris.config} 값, 없으면 null
	 * @param osName   {@code os.name} 값
	 * @param env      환경 변수 ({@code APPDATA}, {@code XDG_CONFIG_HOME} 을 본다)
	 * @param userHome {@code user.home} 값
	 * @return 설정 폴더, 파일 없이 써야 하면 null
	 */
	public static Path resolve(String override, String osName, Map<String, String> env, String userHome) {
		try {
			if (override != null && !override.isBlank()) {
				return OFF.equalsIgnoreCase(override.strip()) ? null : Path.of(override.strip());
			}
			String os = osName == null ? "" : osName;
			boolean noHome = userHome == null || userHome.isBlank();
			if (os.startsWith("Windows")) {
				String appData = env.get("APPDATA");
				if (appData != null && !appData.isBlank()) {
					return Path.of(appData).resolve(APP_NAME);
				}
				return noHome ? null : Path.of(userHome, "AppData", "Roaming", APP_NAME);
			}
			if (noHome) {
				return null;
			}
			if (os.startsWith("Mac") || os.startsWith("Darwin")) {
				return Path.of(userHome, "Library", "Application Support", APP_NAME);
			}
			String xdg = env.get("XDG_CONFIG_HOME");
			if (xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()) { // XDG 규칙: 상대 경로는 무시한다
				return Path.of(xdg).resolve(UNIX_NAME);
			}
			return Path.of(userHome, ".config", UNIX_NAME);
		} catch (InvalidPathException e) {
			return null;
		}
	}
}
