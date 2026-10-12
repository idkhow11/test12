package kr.ac.jbnu.se.tetris.settings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 사용자 설정과 플레이 기록을 담은 저장 파일 하나 ({@value #FILE_NAME}, 형식은 {@link SaveFormat}). 폴더는
 * {@link ConfigDir} 가 정한다. 게임 전체에서 객체 하나를 함께 쓴다(설정 화면, 메뉴, 판이 끝날 때 기록).
 * <p>
 * <b>읽기는 너그럽다.</b> 파일이 없거나 비었으면 기본값이다. 이 게임이 쓴 파일이 아니거나 망가졌으면 기본값으로 시작하고, 옛
 * 파일은 {@value #FILE_NAME}{@value #BROKEN_SUFFIX} 로 남긴 뒤 다음 저장 때 새로 쓴다. 값 하나가 잘못됐으면 그 항목만
 * 기본값이다. 새 버전 게임이 쓴 파일(형식 버전이 더 높음)이거나, 읽다가 입출력 오류가 났거나, 너무 큰 파일이면 저장하지
 * 않는다(남의 기록을 덮어쓰지 않게). 그 까닭은 {@link #problem()} 에 한 줄로 남는다.
 * <p>
 * <b>읽기 오류는 잠깐일 수 있다.</b> 백신·검색 색인·클라우드 동기화나 다른 창의 바꿔 끼우기가 파일을 잠깐 잡고 있으면 열 때
 * 조금 기다렸다가 다시 읽는다(모두 합쳐 0.2초 안). 그래도 읽지 못했으면 기본값으로 시작하고 저장을 멈추지만, 고칠 때마다 다시
 * 읽어 본다. 읽히면 파일의 값 위에 그동안 고친 것을 차례로 다시 하고 저장을 다시 시작한다(이번 실행에서 바꾼 설정과 기록을
 * 잃지 않는다).
 * <p>
 * <b>쓰기는 원자적이다.</b> 같은 폴더의 임시 파일에 다 쓰고 디스크에 내린 다음 이름을 바꿔 끼운다. 쓰다가 꺼져도 옛 파일이나
 * 새 파일 중 하나가 온전히 남는다(남은 임시 파일은 다음에 열 때 지운다). 고칠 때마다({@link #updateSettings},
 * {@link #updateRecords}) 바로 저장한다. 파일이 작아서(1~2KB) 화면 스레드에서 불러도 된다. 저장에 실패해도 예외를 던지지
 * 않고 이번 실행 동안은 고친 값을 쓴다.
 * <p>
 * 다른 프로그램(같은 게임을 하나 더 켬, 사람이 직접 고침)이 파일을 바꿨으면 고치기 전에 다시 읽고 그 위에 고친다. 그래서 두
 * 창에서 한 판씩 해도 기록이 사라지지 않는다(거의 같은 순간에 함께 쓰면 늦은 쪽이 이긴다). 모든 메서드는 여러 스레드에서
 * 불러도 된다.
 */
public final class SaveData {

	/** 저장 파일 이름. */
	public static final String FILE_NAME = "settings.properties";
	/** 망가진 파일을 남길 때 이름 뒤에 붙인다. */
	public static final String BROKEN_SUFFIX = ".broken";
	/** 이보다 큰 파일은 이 게임이 쓴 파일이 아니라고 보고 읽지 않는다. */
	static final long MAX_BYTES = 1 << 20;
	/** 이보다 오래된 임시 파일은 쓰다가 꺼져 남은 것으로 보고 열 때 지운다 (다른 창이 쓰는 중인 것은 건드리지 않게 넉넉히). */
	static final long STALE_TMP_MS = 10 * 60 * 1000L;
	/** 이름 바꿔 끼우기가 잠깐의 잠김으로 실패했을 때 다시 해 보는 횟수. */
	private static final int MOVE_RETRIES = 4;
	/** 열 때 읽기가 실패했을 때 다시 해 보는 횟수 (25, 50, 100ms 뒤). */
	private static final int READ_RETRIES = 3;
	/** 저장을 멈춘 동안 고친 것을 기억하는 수의 상한. 넘으면 다시 읽기를 그만두고 이번 실행은 저장하지 않는다. */
	static final int MAX_PENDING = 2000;
	/** {@link #warnings()} 에 남기는 수의 상한. */
	private static final int MAX_WARNINGS = 200;
	/** 바이트 순서 표시 (U+FEFF). Windows 메모장이 UTF-8 파일 맨 앞에 붙이곤 한다. */
	private static final char BOM = 0xFEFF;

	/** 파일 읽기. 시험은 잠깐의 잠김을 흉내 내는 것을 넣는다. */
	@FunctionalInterface
	interface Reader {
		/** 파일 내용, 없으면 null. */
		byte[] read(Path file) throws IOException;
	}

	private final Path file;
	private final Reader reader;
	private volatile UserSettings settings = UserSettings.defaults();
	private volatile PlayRecords records = PlayRecords.empty();
	private volatile String loadProblem;
	private volatile String saveProblem;
	/** 바꾸는 것은 잠금 안에서만, 읽는 것은 잠금 없이도 된다({@link #persistent()} 는 화면을 그릴 때 불린다). */
	private volatile boolean readOnly;
	/** 열 때 읽기 오류로 저장을 멈췄다(잠깐의 잠김일 수 있다). 고칠 때마다 다시 읽어 본다. */
	private boolean readFailed;
	/** {@link #readFailed} 동안 고친 것. 다시 읽게 되면 읽은 값 위에 차례로 다시 한다. */
	private final List<UnaryOperator<UserSettings>> pendingSettings = new ArrayList<>();
	private final List<UnaryOperator<PlayRecords>> pendingRecords = new ArrayList<>();
	/** 마지막으로 읽거나 쓴 파일의 내용. 파일이 없었으면 null. 이것과 다르면 밖에서 바뀐 것이다. */
	private byte[] known;
	private final List<String> warnings = new ArrayList<>();
	/** 경고를 받는 곳 ({@link #reportWarnings}), 없으면 null. */
	private Consumer<String> warningSink;

	private SaveData(Path file, Reader reader) {
		this.file = file;
		this.reader = reader;
	}

	/** 이 PC 의 설정 폴더({@link ConfigDir#resolve()})에서 연다. {@code -Dtetris.config=off} 면 파일 없이 연다. */
	public static SaveData open() {
		return open(ConfigDir.resolve());
	}

	/** 폴더 {@code dir} 의 {@value #FILE_NAME} 을 연다(파일이 없어도 된다). {@code dir} 이 null 이면 파일 없이 연다. */
	public static SaveData open(Path dir) {
		return open(dir, SaveData::readFile);
	}

	/** 파일 읽기를 바꿔 넣는다 (시험). */
	static SaveData open(Path dir, Reader reader) {
		if (dir == null) {
			return inMemory();
		}
		SaveData d = new SaveData(dir.resolve(FILE_NAME), Objects.requireNonNull(reader, "reader"));
		synchronized (d) {
			d.load(true);
		}
		removeStaleTemporaries(d.file);
		return d;
	}

	/** 파일을 읽지도 쓰지도 않는 저장소 (화면 확인 도구, 시험, {@code -Dtetris.config=off}). 처음엔 기본값이다. */
	public static SaveData inMemory() {
		return new SaveData(null, SaveData::readFile);
	}

	/** 지금 설정. */
	public UserSettings settings() {
		return settings;
	}

	/** 지금 기록. */
	public PlayRecords records() {
		return records;
	}

	/**
	 * 설정을 고치고 바로 저장한다. {@code change} 는 지금 설정을 받아 새 설정을 돌려준다(파일이 밖에서 바뀌었으면 다시 읽은
	 * 설정을 받는다). 예: {@code data.updateSettings(s -> s.withGhost(false))}.
	 *
	 * @return 고친 뒤의 설정 (저장에 실패해도 이 실행 동안은 이 값을 쓴다)
	 */
	public synchronized UserSettings updateSettings(UnaryOperator<UserSettings> change) {
		refreshIfChanged();
		UserSettings next = Objects.requireNonNull(change.apply(settings), "새 설정");
		if (readFailed) {
			remember(pendingSettings, change);
		}
		if (!next.equals(settings)) {
			settings = next;
			save();
		}
		return next;
	}

	/**
	 * 기록을 고치고 바로 저장한다. 예: {@code data.updateRecords(r -> r.withSolo(lines, timeMs))}.
	 *
	 * @return 고친 뒤의 기록
	 */
	public synchronized PlayRecords updateRecords(UnaryOperator<PlayRecords> change) {
		refreshIfChanged();
		PlayRecords next = Objects.requireNonNull(change.apply(records), "새 기록");
		if (readFailed) {
			remember(pendingRecords, change);
		}
		if (!next.equals(records)) {
			records = next;
			save();
		}
		return next;
	}

	/** 저장을 멈춘 동안 고친 것을 남긴다. 너무 많으면 다시 읽기를 그만둔다(이번 실행은 저장하지 않는다). */
	private <T> void remember(List<UnaryOperator<T>> pending, UnaryOperator<T> change) {
		if (pendingSettings.size() + pendingRecords.size() >= MAX_PENDING) {
			readFailed = false;
			pendingSettings.clear();
			pendingRecords.clear();
			warn("읽지 못한 동안 고친 것이 너무 많아 다시 읽기를 그만둠");
			return;
		}
		pending.add(change);
	}

	/**
	 * 지금 설정과 기록을 파일에 쓴다. 보통은 {@code updateX} 가 부르므로 따로 부를 일이 없다.
	 *
	 * @return 썼거나 파일 없이 쓰는 저장소면 true. 저장하지 않는 상태({@link #readOnly()})이거나 쓰지 못했으면 false
	 */
	public synchronized boolean save() {
		if (file == null) {
			return true;
		}
		if (readOnly) {
			return false;
		}
		try {
			byte[] bytes = SaveFormat.write(settings, records).getBytes(StandardCharsets.UTF_8);
			Files.createDirectories(file.toAbsolutePath().getParent());
			writeAtomically(file, bytes);
			known = bytes;
			saveProblem = null;
			return true;
		} catch (IOException | RuntimeException e) {
			saveProblem = "설정을 저장하지 못했어요";
			warn("저장 실패: " + e);
			return false;
		}
	}

	/** 저장 파일, 파일 없이 쓰는 저장소면 null. */
	public Path file() {
		return file;
	}

	/**
	 * 화면에 보일 만한 문제 한 줄, 없으면 null. 마지막 저장이 실패했으면 그것을, 아니면 열 때의 문제(망가진 파일, 새 버전의
	 * 파일, 읽기 오류)를 준다.
	 */
	public String problem() {
		String s = saveProblem;
		return s != null ? s : loadProblem;
	}

	/** 저장하지 않는 상태인가 (새 버전 게임의 파일, 읽기 오류, 너무 큰 파일). 이때도 이 실행 동안의 설정은 바뀐다. */
	public synchronized boolean readOnly() {
		return readOnly;
	}

	/**
	 * 바꾼 것이 파일에 남는가: 파일이 있고({@code -Dtetris.config=off} 나 {@link #inMemory()} 가 아님), 저장을 멈추지
	 * 않았고({@link #readOnly()}), 마지막 저장이 실패하지 않았다. 아니면 이번 실행 동안만 바뀐다(설정 화면의 머리말). 잠금 없이
	 * 읽으므로 다른 스레드가 저장하는 중에도 기다리지 않는다(화면을 그릴 때 불러도 된다).
	 */
	public boolean persistent() {
		return file != null && !readOnly && saveProblem == null;
	}

	/** 읽기·쓰기에서 기본값으로 둔 항목과 오류 (기록용, 화면에 보일 문구는 아니다). */
	public synchronized List<String> warnings() {
		return List.copyOf(warnings);
	}

	/**
	 * 경고({@link #warnings()})를 {@code sink} 에도 알린다: 지금까지의 경고는 바로, 그 뒤에 생기는 경고는 생길 때마다. 같은 글은 한
	 * 번만 알리고, 모두 {@value #MAX_WARNINGS}개까지다. 게임은 표준 오류로 잇는다: 판이 끝날 때 기록을 저장하지 못해도 화면의
	 * "설정을 저장하지 못했어요" 한 줄과 함께 그 까닭(예외)이 남는다. {@code sink} 는 이 저장소의 잠금 안에서 불리므로 오래 걸리면
	 * 안 된다.
	 */
	public synchronized void reportWarnings(Consumer<String> sink) {
		warningSink = Objects.requireNonNull(sink, "sink");
		for (String w : new LinkedHashSet<>(warnings)) {
			sink.accept(w);
		}
	}

	// ------------------------------------------------------------------ 읽기

	/** {@link #MAX_BYTES} 보다 큰 파일. */
	private static final class TooBigException extends IOException {
		private static final long serialVersionUID = 1L;

		TooBigException() {
			super("파일이 " + MAX_BYTES + "바이트보다 큼");
		}
	}

	/**
	 * 파일을 읽는다. {@code first} 는 열 때이고, 아니면 밖에서 바뀐 파일을 다시 읽는 것이다. 다시 읽을 때는 망가진 파일이나
	 * 읽기 오류가 지금 값을 지우지 않는다(다음 저장이 망가진 파일을 고친다). 파일이 사라졌으면 처음으로 돌아간다(지워서
	 * 처음으로 돌리려 한 것으로 본다).
	 */
	private void load(boolean first) {
		byte[] bytes;
		try {
			bytes = first ? readPatiently() : reader.read(file);
		} catch (TooBigException e) {
			warn(e.getMessage());
			readOnly = true; // 이 게임이 쓴 파일이 아니다. 덮어쓰지 않는다
			if (first) {
				reset("설정 파일이 너무 커서 기본값으로 시작해요");
			}
			return;
		} catch (IOException | RuntimeException e) {
			warn("읽기 실패: " + e);
			if (first) { // 읽지 못한 파일은 덮어쓰지 않는다. 고칠 때마다 다시 읽어 본다(refreshIfChanged)
				readOnly = true;
				readFailed = true;
				reset("설정 파일을 읽지 못해 기본값으로 시작해요");
			}
			return;
		}
		apply(bytes, first);
	}

	/** 읽은 내용으로 설정과 기록을 정한다 ({@link #load} 의 나머지). */
	private void apply(byte[] bytes, boolean first) {
		known = bytes;
		if (bytes == null) {
			settings = UserSettings.defaults();
			records = PlayRecords.empty();
			return;
		}
		String text = new String(bytes, StandardCharsets.UTF_8); // 잘못된 바이트는 대체 문자가 된다
		if (!text.isEmpty() && text.charAt(0) == BOM) { // 메모장이 붙이는 BOM
			text = text.substring(1);
		}
		SaveFormat.Parsed p = SaveFormat.read(text);
		for (String w : p.warnings()) {
			warn(w);
		}
		if (p.recognized()) {
			settings = p.settings();
			records = p.records();
			if (p.version() > SaveFormat.VERSION) {
				readOnly = true;
				loadProblem = "새 버전에서 만든 설정이라 바꾼 내용은 저장하지 않아요";
			}
		} else if (text.isBlank()) {
			settings = UserSettings.defaults();
			records = PlayRecords.empty();
		} else {
			backup();
			if (first) {
				reset("설정 파일이 망가져 기본값으로 시작해요");
			}
		}
	}

	/** 설정과 기록을 처음으로 되돌리고 그 까닭을 남긴다. */
	private void reset(String problem) {
		settings = UserSettings.defaults();
		records = PlayRecords.empty();
		loadProblem = problem;
	}

	/** 파일 내용, 없으면 null ({@link Reader} 의 기본). */
	static byte[] readFile(Path file) throws IOException {
		try {
			if (Files.size(file) > MAX_BYTES) {
				throw new TooBigException();
			}
			return Files.readAllBytes(file);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	/**
	 * 열 때의 읽기. 백신·검색 색인·클라우드 동기화나 다른 창의 바꿔 끼우기가 파일을 잠깐 잡고 있으면(접근 거부, 공유 위반,
	 * 잠긴 구역) 25·50·100ms 기다렸다가 다시 읽는다. 너무 큰 파일은 기다리지 않는다.
	 */
	private byte[] readPatiently() throws IOException {
		for (int attempt = 0;; attempt++) {
			try {
				return reader.read(file);
			} catch (TooBigException e) {
				throw e;
			} catch (IOException e) {
				if (attempt >= READ_RETRIES) {
					throw e;
				}
				try {
					Thread.sleep(25L << attempt);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	/** 망가진 파일을 옆에 남긴다. 실패해도 계속한다. */
	private void backup() {
		try {
			Files.copy(file, file.resolveSibling(FILE_NAME + BROKEN_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException | RuntimeException e) {
			warn("망가진 파일을 남기지 못함: " + e);
		}
	}

	/** 마지막으로 읽거나 쓴 뒤에 밖에서 파일이 바뀌었으면 다시 읽는다. 열 때 읽지 못했으면 다시 읽어 본다. */
	private void refreshIfChanged() {
		if (file == null) {
			return;
		}
		if (readFailed) {
			retryFailedRead();
			return;
		}
		if (readOnly) {
			return;
		}
		byte[] now;
		try {
			now = reader.read(file);
		} catch (IOException | RuntimeException e) {
			load(false); // 너무 커졌으면 저장을 멈추고, 읽기 오류면 지금 값을 그대로 쓴다
			return;
		}
		if (!Arrays.equals(now, known)) {
			load(false);
		}
	}

	/**
	 * 열 때 읽지 못한 파일을 다시 읽어 본다(기다리지 않고 한 번). 읽히면 저장을 다시 시작한다: 파일을 열 때처럼 읽고(망가졌으면
	 * 옆에 남기고 기본값, 새 버전의 파일이면 계속 저장하지 않음), 그 위에 그동안 고친 것을 차례로 다시 한 뒤 저장한다.
	 */
	private void retryFailedRead() {
		byte[] bytes;
		try {
			bytes = reader.read(file);
		} catch (TooBigException e) {
			warn(e.getMessage()); // 이제 이 게임의 파일이 아님이 분명하다: 저장하지 않는 채로 지금 값을 쓴다
			readFailed = false;
			pendingSettings.clear();
			pendingRecords.clear();
			loadProblem = "설정 파일이 너무 커서 바꾼 내용은 저장하지 않아요";
			return;
		} catch (IOException | RuntimeException e) {
			return; // 아직 읽을 수 없다
		}
		readFailed = false;
		readOnly = false;
		loadProblem = null;
		apply(bytes, true);
		boolean replayed = !pendingSettings.isEmpty() || !pendingRecords.isEmpty();
		settings = replay(pendingSettings, settings);
		records = replay(pendingRecords, records);
		if (replayed && !readOnly) {
			save();
		}
	}

	/** {@code pending} 을 차례로 {@code value} 에 다시 하고 비운다. 오류가 난 것은 건너뛴다. */
	private <T> T replay(List<UnaryOperator<T>> pending, T value) {
		T v = value;
		for (UnaryOperator<T> change : pending) {
			try {
				T next = change.apply(v);
				if (next != null) {
					v = next;
				}
			} catch (RuntimeException e) {
				warn("다시 읽은 뒤 고치기 실패: " + e);
			}
		}
		pending.clear();
		return v;
	}

	private void warn(String w) {
		if (warnings.size() >= MAX_WARNINGS) {
			return;
		}
		boolean repeat = warnings.contains(w);
		warnings.add(w);
		Consumer<String> sink = warningSink;
		if (sink != null && !repeat) {
			sink.accept(w);
		}
	}

	// ------------------------------------------------------------------ 쓰기

	/** 같은 폴더의 임시 파일에 다 쓰고 디스크에 내린 뒤 이름을 바꿔 끼운다. */
	static void writeAtomically(Path target, byte[] bytes) throws IOException {
		Path dir = target.toAbsolutePath().getParent();
		Path tmp = Files.createTempFile(dir, target.getFileName() + ".", ".tmp");
		try {
			try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
				ByteBuffer buf = ByteBuffer.wrap(bytes);
				while (buf.hasRemaining()) {
					ch.write(buf);
				}
				ch.force(true);
			}
			moveIntoPlace(tmp, target);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * 이름 바꿔 끼우기. Windows 에서 백신·검색 색인·클라우드 동기화가 파일을 잠깐 잡고 있으면(접근 거부, 공유 위반) 조금
	 * 기다렸다가 다시 해 본다(모두 합쳐 0.3초 안).
	 */
	private static void moveIntoPlace(Path tmp, Path target) throws IOException {
		for (int attempt = 0;; attempt++) {
			try {
				Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				return;
			} catch (NoSuchFileException e) {
				throw e; // 폴더가 사라짐: 기다려도 소용없다
			} catch (FileSystemException e) { // AccessDeniedException 과 공유 위반
				if (attempt >= MOVE_RETRIES) {
					throw e;
				}
				try {
					Thread.sleep(25L * (attempt + 1));
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	/** 쓰다가 꺼져 남은 오래된 임시 파일({@code settings.properties.*.tmp})을 지운다. 실패해도 계속한다. */
	private static void removeStaleTemporaries(Path file) {
		Path dir = file.toAbsolutePath().getParent();
		String prefix = file.getFileName() + ".";
		long cutoff = System.currentTimeMillis() - STALE_TMP_MS;
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, p -> {
			String n = p.getFileName().toString();
			return n.startsWith(prefix) && n.endsWith(".tmp");
		})) {
			for (Path p : s) {
				try {
					if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < cutoff) {
						Files.deleteIfExists(p);
					}
				} catch (IOException | RuntimeException e) {
					// 다른 창이 쓰는 중일 수 있다
				}
			}
		} catch (IOException | RuntimeException e) {
			// 폴더가 아직 없거나 읽을 수 없다
		}
	}
}
