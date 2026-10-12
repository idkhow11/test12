package kr.ac.jbnu.se.tetris.settings;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * 저장 파일의 글 형식 {@code tetris-save-1}. Java properties 이고(읽기는 {@link Properties#load}), 한 줄에 이름 하나다.
 *
 * <pre>
 * format=tetris-save-1
 * das_ms=150                              설정: 기본값과 다른 것만 적는다
 * key.hold=A,SHIFT                        키: 하나라도 바꾸면 모든 동작을 적는다
 * menu.versus.level=4                     메뉴에서 마지막으로 고른 것
 * record.versus.4.wins=3                  기록: 난이도 id 마다
 * record.solo.3000_S-mix.best_lines=42    기록: 혼자 연습 설정마다 (PlayRecords#soloSetup)
 * record.sand.best_score=9000             기록: 모래 모드
 * record.sand.rules=3                     모래 기록의 규칙 세대 (PlayRecords#SAND_RULES, 다르면 모래 기록만 버린다)
 * </pre>
 *
 * 기본값과 같은 설정을 적지 않는 까닭: 플레이 시험으로 기본값({@code [초안]} 인 DAS·ARR 등)을 바꾸면 손대지 않은 사람에게도 새 값이
 * 가게 하려고. 키만은 묶음 전체를 적는다(기본 키가 바뀌어 사용자 키와 겹치는 일이 없게).
 * <p>
 * 읽기는 너그럽다. 읽을 수 없는 값은 그 항목만 기본값으로 두고 {@link Parsed#warnings()} 에 남긴다. 범위를 벗어난 수는 범위
 * 끝으로 맞춘다. 키가 서로 겹치면 키 전체를 기본값으로 둔다. 파일에 없는 동작(옛 파일, 새로 생긴 동작)은 비어 있는 기본 키를,
 * 기본 키가 모두 쓰이고 있으면 남는 키 하나를 받는다. {@code format} 줄이 없거나 읽을 수 없으면 이 게임이 쓴 파일이 아니라고
 * 보고 모두 기본값이다. 이름·경로·날짜 같은 개인 정보는 쓰지 않는다. 글자는 UTF-8 이고, 값의 ASCII 밖 글자는 properties 의
 * 유니코드 이스케이프로 쓴다.
 * <p>
 * <b>버전.</b> 뜻이 바뀌는 변경(이름 바꾸기, 단위 바꾸기, 빼기)을 하면 {@link #VERSION} 을 올리고, {@link #read} 에서
 * 옛 버전을 새 뜻으로 옮기는 길을 남긴다. 항목을 더하기만 하면 올리지 않는다(옛 게임은 모르는 항목을 버리고 읽는다). 더 높은
 * 버전의 파일은 {@link SaveData} 가 읽기만 하고 덮어쓰지 않는다.
 */
final class SaveFormat {

	static final String FORMAT_KEY = "format";
	static final String FORMAT_PREFIX = "tetris-save-";
	/** 지금 쓰는 형식 버전. */
	static final int VERSION = 1;

	static final String DAS = "das_ms";
	static final String ARR = "arr_ms";
	static final String AI_SPEED = "ai_speed";
	static final String GHOST = "ghost";
	static final String COLOR_BLIND = "color_blind";
	static final String PATTERNS = "patterns";
	static final String EFFECTS = "effects";
	static final String SHAKE = "shake";
	static final String CALM_FLASH = "calm_flash";
	static final String VOLUME = "volume";
	static final String MUTED = "muted";
	static final String HEARTBEAT = "heartbeat";
	static final String FULLSCREEN = "fullscreen_on_start";
	static final String KEY_PREFIX = "key.";
	static final String MENU_PREFIX = "menu.";
	static final String RECORD_PREFIX = "record.";

	private static final String[] VERSUS_FIELDS = { "wins", "losses", "draws", "streak", "best_streak",
			"fastest_win_ms" };
	private static final String[] SOLO_FIELDS = { "games", "best_lines", "best_time_ms", "total_lines" };
	/** 모래 기록의 칸. 마지막 {@code rules} 는 기록을 세운 모래 규칙 세대({@link PlayRecords#SAND_RULES})다. */
	private static final String[] SAND_FIELDS = { "games", "best_score", "best_multiplier", "rules" };

	/** 파일에 없는 동작의 기본 키가 모두 쓰이고 있을 때 대신 줄 키 (앞에서부터 비어 있는 것). */
	private static final int[] SPARE_KEYS;

	static {
		List<Integer> spare = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			spare.add(KeyEvent.VK_F1 + i);
		}
		for (char c = 'A'; c <= 'Z'; c++) {
			spare.add((int) c);
		}
		for (char c = '0'; c <= '9'; c++) {
			spare.add((int) c);
		}
		SPARE_KEYS = spare.stream().mapToInt(Integer::intValue).toArray();
	}

	/**
	 * 읽은 결과.
	 *
	 * @param version  {@code format} 줄의 버전. 없거나 읽을 수 없으면 0 (그때 설정과 기록은 기본값)
	 * @param warnings 기본값으로 둔 항목과 그 까닭 (기록용, 화면에는 보이지 않는다)
	 */
	record Parsed(UserSettings settings, PlayRecords records, int version, List<String> warnings) {

		Parsed {
			warnings = List.copyOf(warnings);
		}

		/** 이 게임이 쓴 형식의 파일인가 ({@code format} 줄이 있다). */
		boolean recognized() {
			return version > 0;
		}
	}

	private SaveFormat() {
	}

	// ------------------------------------------------------------------ 쓰기

	/** 설정과 기록을 파일 글로 쓴다. 같은 값이면 언제나 같은 글이다(시각을 적지 않고 순서가 정해져 있다). */
	static String write(UserSettings s, PlayRecords r) {
		StringBuilder out = new StringBuilder(1024);
		UserSettings d = UserSettings.defaults();
		StringBuilder actions = new StringBuilder();
		for (KeyAction a : KeyAction.values()) {
			actions.append(actions.length() == 0 ? "" : ", ").append(a.id());
		}
		comment(out, "Tetris Battle 설정과 기록. 게임이 바꿀 때마다 다시 쓴다.");
		comment(out, "지우면 설정과 기록이 처음으로 돌아간다. 개인 정보는 담지 않는다.");
		comment(out, "기본값과 같은 설정은 적지 않는다. 적을 수 있는 설정 (기본값, 범위):");
		comment(out, "  " + DAS + "=" + d.dasMs() + " (" + UserSettings.MIN_DAS_MS + "~" + UserSettings.MAX_DAS_MS + ")   "
				+ ARR + "=" + d.arrMs() + " (" + UserSettings.MIN_ARR_MS + "~" + UserSettings.MAX_ARR_MS + ")   " + VOLUME
				+ "=" + d.volume() + " (0~" + UserSettings.MAX_VOLUME + ")");
		comment(out, "  " + AI_SPEED + "=" + d.aiSpeed() + " (" + UserSettings.MIN_AI_SPEED + "~" + UserSettings.MAX_AI_SPEED
				+ ", " + UserSettings.AI_SPEED_STEP + " 단위)   " + EFFECTS + "=" + d.effects() + " (" + UserSettings.EFFECTS_OFF
				+ " 끔 ~ " + UserSettings.EFFECTS_HIGH + " 강하게)");
		comment(out, "  " + GHOST + "=" + d.ghost() + "   " + COLOR_BLIND + "=" + d.colorBlind() + "   " + PATTERNS + "="
				+ d.patterns() + "   " + SHAKE + "=" + d.shake() + "   " + CALM_FLASH + "=" + d.calmFlash());
		comment(out, "  " + MUTED + "=" + d.muted() + "   " + HEARTBEAT + "=" + d.heartbeat() + "   " + FULLSCREEN + "="
				+ d.fullscreenOnStart());
		comment(out, "  " + KEY_PREFIX + "<동작>=키,키 (예: " + KEY_PREFIX + KeyAction.HOLD.id() + "=C,SHIFT)");
		comment(out, "  동작: " + actions);
		line(out, FORMAT_KEY, FORMAT_PREFIX + VERSION);

		List<String[]> settings = new ArrayList<>();
		if (s.dasMs() != d.dasMs()) {
			settings.add(pair(DAS, s.dasMs()));
		}
		if (s.arrMs() != d.arrMs()) {
			settings.add(pair(ARR, s.arrMs()));
		}
		if (s.aiSpeed() != d.aiSpeed()) {
			settings.add(pair(AI_SPEED, s.aiSpeed()));
		}
		if (s.ghost() != d.ghost()) {
			settings.add(pair(GHOST, s.ghost()));
		}
		if (s.colorBlind() != d.colorBlind()) {
			settings.add(pair(COLOR_BLIND, s.colorBlind()));
		}
		if (s.patterns() != d.patterns()) {
			settings.add(pair(PATTERNS, s.patterns()));
		}
		if (s.effects() != d.effects()) {
			settings.add(pair(EFFECTS, s.effects()));
		}
		if (s.shake() != d.shake()) {
			settings.add(pair(SHAKE, s.shake()));
		}
		if (s.calmFlash() != d.calmFlash()) {
			settings.add(pair(CALM_FLASH, s.calmFlash()));
		}
		if (s.volume() != d.volume()) {
			settings.add(pair(VOLUME, s.volume()));
		}
		if (s.muted() != d.muted()) {
			settings.add(pair(MUTED, s.muted()));
		}
		if (s.heartbeat() != d.heartbeat()) {
			settings.add(pair(HEARTBEAT, s.heartbeat()));
		}
		if (s.fullscreenOnStart() != d.fullscreenOnStart()) {
			settings.add(pair(FULLSCREEN, s.fullscreenOnStart()));
		}
		section(out, "설정", settings);

		List<String[]> keys = new ArrayList<>();
		if (!s.keys().isDefault()) {
			for (KeyAction a : KeyAction.values()) {
				StringBuilder v = new StringBuilder();
				for (int code : s.keys().keys(a)) {
					v.append(v.length() == 0 ? "" : ",").append(KeyNames.token(code));
				}
				keys.add(new String[] { KEY_PREFIX + a.id(), v.toString() });
			}
		}
		section(out, "키", keys);

		List<String[]> menu = new ArrayList<>();
		for (Map.Entry<String, String> e : s.menu().values().entrySet()) {
			menu.add(new String[] { MENU_PREFIX + e.getKey(), e.getValue() });
		}
		section(out, "메뉴에서 마지막으로 고른 것", menu);

		List<String[]> records = new ArrayList<>();
		for (Map.Entry<Integer, PlayRecords.Versus> e : r.versus().entrySet()) {
			PlayRecords.Versus v = e.getValue();
			long[] values = { v.wins(), v.losses(), v.draws(), v.streak(), v.bestStreak(), v.fastestWinMs() };
			fields(records, RECORD_PREFIX + "versus." + e.getKey() + ".", VERSUS_FIELDS, values);
		}
		for (Map.Entry<String, PlayRecords.Solo> e : r.solo().entrySet()) {
			PlayRecords.Solo v = e.getValue();
			fields(records, RECORD_PREFIX + "solo." + e.getKey() + ".", SOLO_FIELDS,
					new long[] { v.games(), v.bestLines(), v.bestTimeMs(), v.totalLines() });
		}
		if (!r.sand().equals(PlayRecords.Sand.NONE)) {
			PlayRecords.Sand v = r.sand();
			fields(records, RECORD_PREFIX + "sand.", SAND_FIELDS,
					new long[] { v.games(), v.bestScore(), v.bestMultiplier(), PlayRecords.SAND_RULES });
		}
		section(out, "기록", records);
		return out.toString();
	}

	private static String[] pair(String name, Object value) {
		return new String[] { name, String.valueOf(value) };
	}

	private static void fields(List<String[]> out, String prefix, String[] names, long[] values) {
		for (int i = 0; i < names.length; i++) {
			out.add(new String[] { prefix + names[i], Long.toString(values[i]) });
		}
	}

	private static void section(StringBuilder out, String title, List<String[]> lines) {
		if (lines.isEmpty()) {
			return;
		}
		out.append('\n');
		comment(out, title);
		for (String[] l : lines) {
			line(out, l[0], l[1]);
		}
	}

	private static void comment(StringBuilder out, String text) {
		out.append("# ").append(text).append('\n');
	}

	private static void line(StringBuilder out, String name, String value) {
		out.append(escape(name, true)).append('=').append(escape(value, false)).append('\n');
	}

	/**
	 * properties 의 이스케이프. 역슬래시, 줄바꿈 같은 제어 문자, {@code = : # !}, 이름의 공백과 값의 첫 공백을 이스케이프하고,
	 * ASCII 밖 글자는 유니코드 이스케이프(역슬래시 + u + 16진수 넷)로 쓴다. {@link Properties#load} 가 그대로 되읽는다.
	 */
	static String escape(String s, boolean name) {
		StringBuilder b = new StringBuilder(s.length() + 8);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\' -> b.append("\\\\");
			case '\t' -> b.append("\\t");
			case '\n' -> b.append("\\n");
			case '\r' -> b.append("\\r");
			case '\f' -> b.append("\\f");
			case '=', ':', '#', '!' -> b.append('\\').append(c);
			case ' ' -> b.append(name || i == 0 ? "\\ " : " ");
			default -> {
				if (c < 0x20 || c > 0x7e) {
					String hex = Integer.toHexString(c).toUpperCase(Locale.ROOT);
					b.append('\\').append('u').append("0".repeat(4 - hex.length())).append(hex);
				} else {
					b.append(c);
				}
			}
			}
		}
		return b.toString();
	}

	// ------------------------------------------------------------------ 읽기

	/** 파일 글을 읽는다. 예외를 던지지 않는다. */
	static Parsed read(String text) {
		List<String> warnings = new ArrayList<>();
		Properties p = new Properties();
		try {
			p.load(new StringReader(text));
		} catch (IOException | RuntimeException e) { // 잘못된 유니코드 이스케이프는 IllegalArgumentException
			warnings.add("properties 로 읽지 못함: " + e.getMessage());
			return new Parsed(UserSettings.defaults(), PlayRecords.empty(), 0, warnings);
		}
		int version = version(p.getProperty(FORMAT_KEY));
		if (version <= 0) {
			warnings.add("format 줄이 없거나 읽을 수 없음: " + p.getProperty(FORMAT_KEY));
			return new Parsed(UserSettings.defaults(), PlayRecords.empty(), 0, warnings);
		}
		Map<String, String> all = new TreeMap<>();
		for (String name : p.stringPropertyNames()) {
			all.put(name, p.getProperty(name));
		}
		all.remove(FORMAT_KEY);
		// 옛 버전을 옮기는 자리: if (version < 2) { ... all 의 이름·값을 새 뜻으로 바꾼다 ... }
		UserSettings d = UserSettings.defaults();
		int das = readInt(all, DAS, d.dasMs(), UserSettings.MIN_DAS_MS, UserSettings.MAX_DAS_MS, warnings);
		int arr = readInt(all, ARR, d.arrMs(), UserSettings.MIN_ARR_MS, UserSettings.MAX_ARR_MS, warnings);
		int aiSpeed = readInt(all, AI_SPEED, d.aiSpeed(), UserSettings.MIN_AI_SPEED, UserSettings.MAX_AI_SPEED, warnings);
		if (aiSpeed % UserSettings.AI_SPEED_STEP != 0) {
			warnings.add(AI_SPEED + ": " + UserSettings.AI_SPEED_STEP + " 단위가 아니라 가까운 값으로 맞춤: " + aiSpeed);
		}
		boolean ghost = readBoolean(all, GHOST, d.ghost(), warnings);
		boolean colorBlind = readBoolean(all, COLOR_BLIND, d.colorBlind(), warnings);
		boolean patterns = readBoolean(all, PATTERNS, d.patterns(), warnings);
		int effects = readInt(all, EFFECTS, d.effects(), UserSettings.EFFECTS_OFF, UserSettings.EFFECTS_HIGH, warnings);
		boolean shake = readBoolean(all, SHAKE, d.shake(), warnings);
		boolean calmFlash = readBoolean(all, CALM_FLASH, d.calmFlash(), warnings);
		int volume = readInt(all, VOLUME, d.volume(), 0, UserSettings.MAX_VOLUME, warnings);
		boolean muted = readBoolean(all, MUTED, d.muted(), warnings);
		boolean heartbeat = readBoolean(all, HEARTBEAT, d.heartbeat(), warnings);
		boolean fullscreen = readBoolean(all, FULLSCREEN, d.fullscreenOnStart(), warnings);
		KeyBindings keys = readKeys(all, warnings);
		MenuMemory menu = readMenu(all, warnings);
		PlayRecords records = readRecords(all, warnings);
		for (String name : all.keySet()) {
			warnings.add("모르는 항목이라 버림: " + name);
		}
		UserSettings s = new UserSettings(keys, das, arr, aiSpeed, ghost, colorBlind, patterns, effects, shake, calmFlash,
				volume, muted, heartbeat, fullscreen, menu);
		return new Parsed(s, records, version, warnings);
	}

	/** "tetris-save-N" 의 N, 읽을 수 없으면 0. */
	static int version(String format) {
		if (format == null) {
			return 0;
		}
		String f = format.strip();
		if (!f.startsWith(FORMAT_PREFIX)) {
			return 0;
		}
		long n = digits(f.substring(FORMAT_PREFIX.length()), 9);
		return n < 0 ? 0 : (int) n;
	}

	/** 0 이상의 십진수 (자릿수 {@code maxDigits} 이하), 아니면 -1. */
	private static long digits(String s, int maxDigits) {
		if (s.isEmpty() || s.length() > maxDigits || !s.chars().allMatch(c -> c >= '0' && c <= '9')) {
			return -1;
		}
		return Long.parseLong(s);
	}

	private static int readInt(Map<String, String> all, String name, int fallback, int min, int max,
			List<String> warnings) {
		String v = all.remove(name);
		if (v == null) {
			return fallback;
		}
		String t = v.strip();
		boolean negative = t.startsWith("-");
		long n = digits(negative ? t.substring(1) : t, 9);
		if (n < 0) {
			warnings.add(name + ": 수가 아니라 기본값 " + fallback + ": " + v);
			return fallback;
		}
		long x = negative ? -n : n;
		if (x < min || x > max) {
			warnings.add(name + ": " + min + "~" + max + " 밖이라 맞춤: " + v);
		}
		return (int) Math.max(min, Math.min(max, x));
	}

	private static boolean readBoolean(Map<String, String> all, String name, boolean fallback, List<String> warnings) {
		String v = all.remove(name);
		if (v == null) {
			return fallback;
		}
		String t = v.strip();
		if (t.equalsIgnoreCase("true")) {
			return true;
		}
		if (t.equalsIgnoreCase("false")) {
			return false;
		}
		warnings.add(name + ": true/false 가 아니라 기본값 " + fallback + ": " + v);
		return fallback;
	}

	/**
	 * {@code key.*} 를 읽는다. 읽을 수 없는 키 이름은 버린다. 동작끼리 키가 겹치면 키 전체를 기본값으로 둔다. 키가 하나도 남지
	 * 않은 동작과 적지 않은 동작은 비어 있는 기본 키를, 기본 키가 모두 쓰이고 있으면 남는 키({@link #SPARE_KEYS}) 하나를
	 * 받는다. 그래서 새 버전에서 동작이 늘어도 사용자가 바꾼 키는 남는다.
	 */
	private static KeyBindings readKeys(Map<String, String> all, List<String> warnings) {
		Map<KeyAction, List<Integer>> found = new EnumMap<>(KeyAction.class);
		for (Iterator<Map.Entry<String, String>> it = all.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, String> e = it.next();
			if (!e.getKey().startsWith(KEY_PREFIX)) {
				continue;
			}
			it.remove();
			KeyAction a = KeyAction.byId(e.getKey().substring(KEY_PREFIX.length()));
			if (a == null) {
				warnings.add("모르는 동작이라 버림: " + e.getKey());
				continue;
			}
			List<Integer> list = new ArrayList<>();
			for (String token : e.getValue().split(",")) {
				if (token.isBlank()) {
					continue;
				}
				int code = KeyNames.code(token);
				if (code < 0 || !KeyNames.bindable(code) || !KeyBindings.allowed(a, code)) {
					warnings.add(e.getKey() + ": 쓸 수 없는 키라 버림: " + token.strip());
				} else if (list.contains(code)) {
					continue;
				} else if (list.size() >= KeyBindings.MAX_KEYS) {
					warnings.add(e.getKey() + ": 키는 " + KeyBindings.MAX_KEYS + "개까지라 버림: " + token.strip());
				} else {
					list.add(code);
				}
			}
			if (list.isEmpty()) {
				warnings.add(e.getKey() + ": 쓸 수 있는 키가 없어 기본 키를 씀");
			} else {
				found.put(a, list);
			}
		}
		if (found.isEmpty()) {
			return KeyBindings.defaults();
		}
		List<KeyBindings.Conflict> conflicts = KeyBindings.conflicts(found);
		if (!conflicts.isEmpty()) {
			warnings.add("키가 겹쳐 모든 키를 기본값으로: " + conflicts.get(0).message());
			return KeyBindings.defaults();
		}
		Set<Integer> taken = new HashSet<>();
		for (List<Integer> l : found.values()) {
			taken.addAll(l);
		}
		for (KeyAction a : KeyAction.values()) {
			if (found.containsKey(a)) {
				continue;
			}
			List<Integer> free = new ArrayList<>();
			for (int k : a.defaultKeys()) {
				if (!taken.contains(k)) {
					free.add(k);
				}
			}
			if (free.isEmpty()) {
				for (int k : SPARE_KEYS) {
					if (!taken.contains(k) && KeyBindings.allowed(a, k)) {
						free.add(k);
						warnings.add(KEY_PREFIX + a.id() + ": 기본 키를 다른 동작이 써서 " + KeyNames.token(k) + " 를 줌");
						break;
					}
				}
			}
			if (free.isEmpty()) {
				warnings.add(KEY_PREFIX + a.id() + ": 줄 키가 없어 모든 키를 기본값으로");
				return KeyBindings.defaults();
			}
			found.put(a, free);
			taken.addAll(free);
		}
		String why = KeyBindings.problem(found);
		if (why != null) {
			warnings.add("키를 쓸 수 없어 모든 키를 기본값으로: " + why);
			return KeyBindings.defaults();
		}
		return KeyBindings.of(found);
	}

	private static MenuMemory readMenu(Map<String, String> all, List<String> warnings) {
		TreeMap<String, String> m = new TreeMap<>();
		for (Iterator<Map.Entry<String, String>> it = all.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, String> e = it.next();
			if (!e.getKey().startsWith(MENU_PREFIX)) {
				continue;
			}
			it.remove();
			String k = e.getKey().substring(MENU_PREFIX.length());
			String v = e.getValue().strip();
			if (!MenuMemory.validKey(k) || !MenuMemory.validValue(v)) {
				warnings.add("메뉴 기억에 쓸 수 없어 버림: " + e.getKey());
			} else if (MenuMemory.retired(k, v)) {
				warnings.add("없어진 모드의 메뉴 기억이라 버림: " + e.getKey() + "=" + v);
			} else if (m.size() >= MenuMemory.MAX_ENTRIES) {
				warnings.add("메뉴 기억이 너무 많아 버림: " + e.getKey());
			} else {
				m.put(k, v);
			}
		}
		return m.isEmpty() ? MenuMemory.EMPTY : new MenuMemory(m);
	}

	private static PlayRecords readRecords(Map<String, String> all, List<String> warnings) {
		Map<Integer, long[]> versus = new TreeMap<>();
		Map<String, long[]> solo = new TreeMap<>();
		long[] sand = new long[SAND_FIELDS.length];
		for (Iterator<Map.Entry<String, String>> it = all.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, String> e = it.next();
			if (!e.getKey().startsWith(RECORD_PREFIX)) {
				continue;
			}
			it.remove();
			String[] parts = e.getKey().substring(RECORD_PREFIX.length()).split("\\.", -1);
			long value = digits(e.getValue().strip(), 18);
			if (value < 0) {
				warnings.add(e.getKey() + ": 0 이상의 수가 아니라 버림: " + e.getValue());
				continue;
			}
			int field = -1;
			long[] target = null;
			if (parts.length == 3 && parts[0].equals("versus")) {
				long level = digits(parts[1], 2);
				field = indexOf(VERSUS_FIELDS, parts[2]);
				if (level >= 0 && PlayRecords.validLevel((int) level) && field >= 0) {
					target = versus.computeIfAbsent((int) level, x -> new long[VERSUS_FIELDS.length]);
				}
			} else if (parts.length == 3 && parts[0].equals("solo")) {
				field = indexOf(SOLO_FIELDS, parts[2]);
				if (PlayRecords.validSetup(parts[1]) && field >= 0) {
					if (solo.containsKey(parts[1]) || solo.size() < PlayRecords.MAX_SOLO_SETUPS) {
						target = solo.computeIfAbsent(parts[1], x -> new long[SOLO_FIELDS.length]);
					} else {
						warnings.add("혼자 연습 설정이 너무 많아 버림: " + e.getKey());
						continue;
					}
				}
			} else if (parts.length == 2 && parts[0].equals("sand")) {
				field = indexOf(SAND_FIELDS, parts[1]);
				target = field >= 0 ? sand : null;
			}
			if (target == null) {
				warnings.add("모르는 기록이라 버림: " + e.getKey());
			} else {
				target[field] = value;
			}
		}
		TreeMap<Integer, PlayRecords.Versus> vs = new TreeMap<>();
		for (Map.Entry<Integer, long[]> e : versus.entrySet()) {
			long[] f = e.getValue();
			vs.put(e.getKey(), new PlayRecords.Versus(toInt(f[0]), toInt(f[1]), toInt(f[2]), toInt(f[3]), toInt(f[4]),
					f[5]));
		}
		TreeMap<String, PlayRecords.Solo> so = new TreeMap<>();
		for (Map.Entry<String, long[]> e : solo.entrySet()) {
			long[] f = e.getValue();
			so.put(e.getKey(), new PlayRecords.Solo(toInt(f[0]), toInt(f[1]), f[2], f[3]));
		}
		PlayRecords.Sand sandRecord = new PlayRecords.Sand(toInt(sand[0]), sand[1], toInt(sand[2]));
		if (!sandRecord.equals(PlayRecords.Sand.NONE) && sand[3] != PlayRecords.SAND_RULES) {
			// 모래 규칙이 바뀌었다 (rules 칸이 없으면 그것을 적기 전의 sand-0.1·0.2): 점수의 뜻이 달라 모래 기록만 새로 시작한다
			warnings.add("모래 규칙이 바뀌어 모래 기록은 새로 시작: 규칙 세대 " + sand[3] + " → " + PlayRecords.SAND_RULES + ", "
					+ sandRecord);
			sandRecord = PlayRecords.Sand.NONE;
		}
		return new PlayRecords(vs, so, sandRecord);
	}

	private static int indexOf(String[] names, String name) {
		for (int i = 0; i < names.length; i++) {
			if (names[i].equals(name)) {
				return i;
			}
		}
		return -1;
	}

	private static int toInt(long v) {
		return (int) Math.min(Integer.MAX_VALUE, v);
	}
}
