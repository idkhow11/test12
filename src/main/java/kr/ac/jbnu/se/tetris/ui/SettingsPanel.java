package kr.ac.jbnu.se.tetris.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import javax.swing.JPanel;
import javax.swing.Timer;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.audio.Cue;
import kr.ac.jbnu.se.tetris.audio.Sfx;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.core.PieceState;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.sand.SandColor;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.KeyNames;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 설정 화면. 메뉴와 같은 모양(1280 × 720 가상 화면, 둥근 카드, 알약 버튼, 키 모양)을 직접 그린다. 위쪽 카드로 여섯 갈래를
 * 고른다.
 * <ul>
 * <li><b>조작:</b> 동작마다 키 1~3개. 칸을 고르고 Enter(또는 누르기) → 새 키를 누른다. 다른 동작이 쓰던 키면 두 동작의 키를
 * 맞바꾸고 알림을 띄우며 그 줄이 잠깐 반짝인다. 쓸 수 없는 키면 이유 한 줄. Esc 는 기다림을 취소한다. Delete·Backspace(또는
 * 키 위의 ×)로 키를 빼고, 줄 끝의 ↺ 로 그 동작만 기본 키로 돌린다.</li>
 * <li><b>감도:</b> DAS·ARR 막대(기본값 눈금), 누르고 있을 때 블록이 언제 움직이는지 보여 주는 시간 줄.</li>
 * <li><b>화면:</b> 고스트, 색약 색상, 블록 무늬, 전체 화면으로 시작. 오른쪽에 지금 고른 색과 무늬의 미리 보기.</li>
 * <li><b>효과:</b> 효과 세기(끔·약하게·보통·강하게), 화면 흔들림, 번쩍임 줄이기 (runs/quality/SPEC.md V15). 오른쪽에 줄을
 * 지우는 작은 판의 미리 보기({@link SettingsDemo})가 지금 설정대로 움직인다.</li>
 * <li><b>소리:</b> 효과음 크기(바꾸면 그 크기로 작은 소리를 들려준다), 음소거, 소리 들어 보기, 심장 소리. 소리가 나지 않으면 그
 * 까닭(실행 옵션으로 끔, 장치 없음, 장치가 멈춤 …, {@link Sound#offCause()})을 알린다.</li>
 * <li><b>AI 속도:</b> 사람 대 AI 의 AI 속도(50~150%, 메뉴의 사람 대 AI 카드와 같은 값). 아래에 난이도마다 AI 가 블록 하나를
 * 두는 데 드는 시간을 보인다({@link AiCatalog.Difficulty#atSpeed}).</li>
 * </ul>
 * 바꿀 때마다 {@link SaveData#updateSettings} 로 바로 저장하고 {@code onChanged} 로 새 설정을 알린다. 파일에 남지 않는
 * 실행이면({@link SaveData#persistent()} 가 거짓) 제목 아래 줄이 "이번 실행 동안만 바뀌어요" 다. 소리 크기·음소거는 이
 * 화면이 소리 엔진에 바로 넘긴다. 키보드는 메뉴 키(↑↓←→ 옮기기·값 바꾸기, Tab·Shift+Tab 다음·앞, Enter·Space 고르기, Esc
 * 메뉴로)와 키 빼기의 Delete·Backspace 만 쓴다. 옮기기는 화면 위 자리로 가장 가까운 항목에 간다. 키를 기다리는 동안
 * ({@link #capturing()})에는 모든 키를 이 화면이 받는다({@link #takesKey}). 소리는 audio 패키지의 약속을 따른다: 옮기기·값
 * 바꾸기 MENU_MOVE, 켜고 끄기·키 바꾸기 MENU_SELECT, 끝·거절 DENY, 기다림 취소 MENU_BACK. 나가는 소리(MENU_BACK)는 창
 * (TetrisApp)이 낸다.
 * <p>
 * 붙이는 법 (TetrisApp, 10-07): {@code new SettingsPanel(data, onBack, onChanged, Sound::get, catalog.difficulties())} 를 카드로
 * 더하고, 보일 때마다 {@link #open()}, {@link #heldKeys}(그때 눌려 있던 키) 뒤 {@code requestFocusInWindow()}. 창 전체의 전체
 * 화면 키는 {@link #takesKey} 가 true 면 건너뛴다. 움직이는 것(기다림, 알림, 효과 미리 보기)이 있고 화면에 보일 때만 33ms
 * 타이머가 돈다. 효과 갈래에서 미리 보기만 움직이는 동안은 그 카드 자리만 다시 그린다({@link #previewArea()}).
 */
final class SettingsPanel extends JPanel {

	private static final long serialVersionUID = 1L;
	private static final int W = 1280;
	private static final int H = 720;

	/** 설정의 갈래 (위쪽 카드). */
	enum Section {
		CONTROLS("조작", "키 바꾸기"), FEEL("감도", "자동 반복"), DISPLAY("화면", "고스트 · 색"), EFFECTS("효과", "세기 · 흔들림"),
		SOUND("소리", "크기 · 음소거"), AI("AI 속도", "사람 대 AI");

		final String title;
		final String sub;

		Section(String title, String sub) {
			this.title = title;
			this.sub = sub;
		}
	}

	/** 두 번 눌러야 하는 버튼(모두 기본값으로, 키 모두 기본값으로)의 확인 시간 (ms). */
	static final int CONFIRM_MS = 2500;
	/** 알림이 보이는 시간 (ms). 끝의 0.6초에 옅어진다. */
	static final int MESSAGE_MS = 4000;
	/** 다른 동작의 키가 바뀌었을 때 그 줄이 반짝이는 시간 (ms). */
	static final int FLASH_MS = 1400;

	// ---- 가상 화면 위 자리
	private static final int CARD_X = 220;
	private static final int CARD_Y = 140;
	private static final int CARD_W = 840;
	private static final int CARD_H = 502;
	private static final int IN_X = 248;
	private static final int IN_W = 784;
	private static final int TAB_Y = 160;
	private static final int TAB_H = 58;
	private static final int TAB_GAP = 12;
	private static final int MSG_Y = 604;

	// ---- 조작 갈래
	private static final int ROW_Y = 280;
	private static final int ROW_H = 44;
	private static final int CAP_W = 68;
	private static final int CAP_H = 34;
	private static final int CAP_GAP = 6;
	private static final int LABEL_W = 116;
	private static final int RIGHT_X = 656;
	private static final KeyAction[] PIECE_ACTIONS = { KeyAction.MOVE_LEFT, KeyAction.MOVE_RIGHT, KeyAction.SOFT_DROP,
			KeyAction.HARD_DROP, KeyAction.ROTATE_CW, KeyAction.ROTATE_CCW, KeyAction.HOLD };
	private static final KeyAction[] SCREEN_ACTIONS = { KeyAction.PAUSE, KeyAction.MENU, KeyAction.RETRY,
			KeyAction.FULLSCREEN };

	/** 막대 하나: 값의 범위, 한 칸, 기본값, 단위, 설정에서 읽고 쓰기. */
	private record Slider(int min, int max, int step, int def, String unit, ToIntFunction<UserSettings> get,
			BiFunction<UserSettings, Integer, UserSettings> set) {

		int clamp(int v) {
			int snapped = min + Math.round((v - min) / (float) step) * step;
			return Math.max(min, Math.min(max, snapped));
		}
	}

	private static final Slider DAS = new Slider(UserSettings.MIN_DAS_MS, UserSettings.MAX_DAS_MS,
			UserSettings.DAS_STEP_MS, UserSettings.DEFAULT_DAS_MS, "ms", UserSettings::dasMs, UserSettings::withDasMs);
	private static final Slider ARR = new Slider(UserSettings.MIN_ARR_MS, UserSettings.MAX_ARR_MS,
			UserSettings.ARR_STEP_MS, UserSettings.DEFAULT_ARR_MS, "ms", UserSettings::arrMs, UserSettings::withArrMs);
	private static final Slider VOLUME = new Slider(0, UserSettings.MAX_VOLUME, UserSettings.VOLUME_STEP,
			UserSettings.DEFAULT_VOLUME, "%", UserSettings::volume, UserSettings::withVolume);
	private static final Slider AI_SPEED = new Slider(UserSettings.MIN_AI_SPEED, UserSettings.MAX_AI_SPEED,
			UserSettings.AI_SPEED_STEP, UserSettings.DEFAULT_AI_SPEED, "%", UserSettings::aiSpeed,
			UserSettings::withAiSpeed);

	/** 효과 세기 단계의 이름 ({@link UserSettings#EFFECTS_OFF} … {@link UserSettings#EFFECTS_HIGH}). */
	static final String[] EFFECT_NAMES = { "끔", "약하게", "보통", "강하게" };

	/** 알림의 종류. */
	private enum Tone {
		INFO, OK, WARN
	}

	private record Message(String text, Tone tone, long at) {
	}

	/** 고를 수 있는 것 하나 (가상 화면 좌표). 화면을 그릴 때마다, 그리고 키·마우스를 받을 때마다 새로 만든다. */
	private static final class Item {
		final String id;
		final Rectangle r;
		final boolean focusable;
		/** Enter·Space·누르기. */
		Runnable press;
		/** ←→ (-1, +1). 값을 바꿨으면 true, 끝이라 못 바꿨으면 false. null 이면 ←→ 로 옆 항목에 간다. */
		IntPredicate step;
		/** 값을 바꿨을 때 낼 소리. */
		Sfx stepSound = Sfx.MENU_MOVE;
		/** 막대면 그 막대 (누른 채 끌어 값 정하기). */
		Slider slider;

		Item(String id, Rectangle r, boolean focusable) {
			this.id = id;
			this.r = r;
			this.focusable = focusable;
		}
	}

	private final transient SaveData data;
	private final transient Runnable onBack;
	private final transient Consumer<UserSettings> onChanged;
	private final transient Supplier<Sound> sound;
	/** AI 속도 갈래의 난이도 표 (모델 묶음의 난이도). 비었으면 표를 그리지 않는다. */
	private final transient List<AiCatalog.Difficulty> levels;
	/** 시계 (ms). 시험과 화면 확인 도구가 멈춘 시계를 넣는다. */
	transient LongSupplier clock = System::currentTimeMillis;

	private Section section = Section.CONTROLS;
	private String focus = tabId(Section.CONTROLS);
	private transient Rectangle focusRect;
	private String hover;
	private KeyAction captureAction;
	private int captureSlot;
	private transient Message message;
	private KeyAction flashAction;
	private long flashAt;
	private String armedId;
	private long armedAt;
	private String dragId;
	private int dragValue;
	private final transient Set<Integer> down = new HashSet<>();
	private final transient Timer anim;
	/** 지난 틱에 미리 보기 말고도 움직이는 것이 있었는가 (그것이 끝난 틱에 한 번 더 다 그려 지운다). */
	private boolean othersMoved;

	/**
	 * @param data      저장소 (게임 전체에서 하나, 시험과 화면 확인 도구는 {@link SaveData#inMemory()})
	 * @param onBack    메뉴로 돌아가기 (Esc, 왼쪽 위 버튼)
	 * @param onChanged 설정이 바뀔 때마다 새 설정으로 부른다 (화면 스레드)
	 */
	SettingsPanel(SaveData data, Runnable onBack, Consumer<UserSettings> onChanged) {
		this(data, onBack, onChanged, Sound::get);
	}

	/** 소리 엔진을 바꿔 넣는다 (시험은 {@link Sound#silent()} 를 넘겨 공용 엔진을 건드리지 않는다). AI 속도 갈래의 표는 없다. */
	SettingsPanel(SaveData data, Runnable onBack, Consumer<UserSettings> onChanged, Supplier<Sound> sound) {
		this(data, onBack, onChanged, sound, List.of());
	}

	/**
	 * @param levels AI 속도 갈래에 보일 난이도 (보통 {@code catalog.difficulties()}), 없으면 빈 목록
	 */
	SettingsPanel(SaveData data, Runnable onBack, Consumer<UserSettings> onChanged, Supplier<Sound> sound,
			List<AiCatalog.Difficulty> levels) {
		this.data = Objects.requireNonNull(data, "data");
		this.onBack = Objects.requireNonNull(onBack, "onBack");
		this.onChanged = Objects.requireNonNull(onChanged, "onChanged");
		this.sound = Objects.requireNonNull(sound, "sound");
		this.levels = List.copyOf(levels);
		setPreferredSize(new Dimension(W, H));
		setFocusable(true);
		enableInputMethods(false); // 한/영이 '한'이어도 글자 키가 입력기에 먹히지 않게
		setFocusTraversalKeysEnabled(false); // Tab 도 키로 받는다
		anim = new Timer(33, e -> {
			boolean others = othersMoving();
			if (section == Section.EFFECTS && !others && !othersMoved) {
				repaint(previewArea()); // 미리 보기만 움직인다: 그 자리만 (바탕과 다른 카드는 그대로)
			} else {
				repaint();
			}
			othersMoved = others;
			if (!animating() || !isShowing()) { // 메뉴로 돌아가면 멈춘다 (다시 보이면 HierarchyListener 가 돌린다)
				((Timer) e.getSource()).stop();
			}
		});
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (onKey(e.getKeyCode(), e.isShiftDown())) {
					e.consume();
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
				down.remove(e.getKeyCode());
				if (capturing()) {
					e.consume();
				}
			}
		});
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				Item h = itemAt(e.getX(), e.getY());
				String id = h == null ? null : h.id;
				if (!Objects.equals(id, hover)) {
					hover = id;
					setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (hover != null && dragId == null) {
					hover = null;
					repaint();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				requestFocusInWindow();
				click(e.getX(), e.getY());
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				drag(e.getX(), e.getY());
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				release();
			}
		};
		addMouseListener(mouse);
		addMouseMotionListener(mouse);
		addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				down.clear();
				// 막대를 끄는 중에 다른 창으로 가면 마우스 뗌을 받지 못한다: 끄던 값은 버린다(저장된 값 그대로)
				dragId = null;
				if (capturing()) { // 다른 창으로 가면 기다림을 조용히 취소한다
					captureAction = null;
					message = null;
				}
				repaint();
			}
		});
		// 화면에 보이게 되면 움직이는 것(효과 갈래의 미리 보기 등)의 타이머를 다시 돌린다
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
				poke();
			}
		});
	}

	// ------------------------------------------------------------------ 밖에서 쓰는 것

	/**
	 * 새 키를 기다리는 중인가. 이때는 모든 키(전체 화면 키 포함)를 이 화면이 받으므로, 창 전체에서 전체 화면 키를 듣는 곳
	 * (TetrisApp 의 KeyEventDispatcher)은 이것이 true 면 건너뛴다.
	 */
	boolean capturing() {
		return captureAction != null;
	}

	/**
	 * 이 화면이 지금 키 {@code keyCode} 를 스스로 쓰는가: 키를 기다리는 중이거나, 키를 빼는 Delete·Backspace. 창 전체에서
	 * 전체 화면 키를 듣는 곳은 이것이 true 면 건너뛴다(전체 화면 키를 Delete 로 바꿔도 이 화면에서는 키 빼기가 된다).
	 */
	boolean takesKey(int keyCode) {
		return capturing() || keyCode == KeyEvent.VK_DELETE || keyCode == KeyEvent.VK_BACK_SPACE;
	}

	/**
	 * 화면을 보일 때마다 부른다: 기다림·알림·확인을 지우고 지금 갈래의 카드에 초점을 둔다(↓ 로 들어간다). 갈래는 지난번 것을
	 * 그대로 둔다.
	 */
	void open() {
		captureAction = null;
		message = null;
		armedId = null;
		flashAction = null;
		dragId = null;
		hover = null;
		down.clear();
		focus = tabId(section);
		focusRect = null;
		repaint();
	}

	/**
	 * 이 화면을 열 때 이미 눌려 있던 키 ({@link #open()} 뒤에 부른다). 메뉴에서 Enter 로 이 화면을 열고 계속 누르고 있으면
	 * 운영체제의 자동 반복이 이 화면에 오는데, 그것을 새 눌림으로 받지 않는다(그 키를 떼었다 다시 누르면 받는다).
	 */
	void heldKeys(java.util.Collection<Integer> codes) {
		down.addAll(codes);
	}

	/** 지금 갈래. */
	Section section() {
		return section;
	}

	/** 갈래를 고르고 그 카드에 초점을 둔다 (화면 확인 도구, 시험). */
	void show(Section s) {
		section = Objects.requireNonNull(s);
		focus = tabId(s);
		focusRect = null;
		poke();
		repaint();
	}

	/** 초점이 있는 항목의 이름 (시험): "tab:CONTROLS", "key:hard_drop:0", "das", "ghost", "volume" 등. */
	String focusId() {
		return focus;
	}

	/** 항목 {@code id} 에 초점을 둔다 (시험, 화면 확인 도구). 없는 항목이면 false. */
	boolean focus(String id) {
		for (Item it : items()) {
			if (it.id.equals(id) && it.focusable) {
				setFocus(it);
				repaint();
				return true;
			}
		}
		return false;
	}

	/** 지금 보이는 알림 글, 없으면 null (시험). */
	String messageText() {
		Message m = message;
		return m == null || expired(m) ? null : m.text();
	}

	/**
	 * 제목 아래 한 줄: 바꾼 것이 파일에 남으면 "바꾸면 바로 저장돼요", 아니면(파일 없이 실행, 새 버전의 파일이라 저장하지 않음,
	 * 읽기·저장 실패) "이번 실행 동안만 바뀌어요". 까닭은 화면 맨 아래 줄({@link SaveData#problem()})에 있다.
	 */
	String subtitle() {
		return data.persistent() ? "바꾸면 바로 저장돼요" : "이번 실행 동안만 바뀌어요";
	}

	/**
	 * 소리 갈래 아래의 상태 줄.
	 *
	 * @param head 왼쪽 굵은 글 (상태)
	 * @param tail 오른쪽 옅은 글 (그래서 어떻게 하면 되는지), 없으면 ""
	 * @param on   소리가 나는 상태 (초록 점)
	 * @param warn 장치·소리 문제 (주황 점). 둘 다 아니면 옅은 점: 일부러 끈 것
	 */
	record SoundStatus(String head, String tail, boolean on, boolean warn) {
	}

	/** 소리 엔진의 까닭({@link Sound#offCause()})과 설정으로 상태 줄의 글을 고른다. */
	static SoundStatus soundStatus(Sound.OffCause cause, boolean muted, int volume) {
		String keepPlaying = "게임은 소리 없이 그대로 할 수 있어요";
		switch (cause) {
		case NONE:
			if (muted || volume == 0) {
				return new SoundStatus("지금은 소리가 꺼져 있어요", muted ? "음소거를 끄면 들려요" : "크기를 올리면 들려요", false, false);
			}
			return new SoundStatus("효과음이 켜져 있어요", "", true, false);
		case OPTION:
			return new SoundStatus("실행 옵션으로 소리를 꺼 두었어요", "", false, false);
		case UNUSED:
			return new SoundStatus("소리를 쓰지 않는 실행이에요", "", false, false);
		case NO_DEVICE:
			return new SoundStatus("소리 장치를 찾지 못했어요", keepPlaying, false, true);
		case DEVICE_LOST:
			return new SoundStatus("소리 장치가 응답하지 않아요", keepPlaying, false, true);
		default:
			return new SoundStatus("효과음을 만들지 못했어요", keepPlaying, false, true);
		}
	}

	// ------------------------------------------------------------------ 바꾸기

	/** 설정을 바꾸고 저장한 뒤 알린다. 소리 크기·음소거가 바뀌었으면 엔진에도 넘긴다. */
	private UserSettings change(UnaryOperator<UserSettings> f) {
		UserSettings before = data.settings();
		UserSettings after = data.updateSettings(f);
		if (!after.equals(before)) {
			if (after.volume() != before.volume() || after.muted() != before.muted()) {
				Sound s = sound.get();
				s.setVolume(after.volume() / 100.0);
				s.setMuted(after.muted());
			}
			onChanged.accept(after);
		}
		return after;
	}

	private void sfx(Sfx s) {
		sound.get().play(s);
	}

	private void say(String text, Tone tone) {
		message = new Message(text, tone, clock.getAsLong());
		poke();
	}

	private boolean expired(Message m) {
		return clock.getAsLong() - m.at() >= MESSAGE_MS;
	}

	/** 두 번 눌러야 하는 버튼: 처음이면 묻고 false, 시간 안에 같은 버튼을 다시 누르면 true. */
	private boolean confirm(String id, String question) {
		long now = clock.getAsLong();
		if (id.equals(armedId) && now - armedAt < CONFIRM_MS) {
			armedId = null;
			return true;
		}
		armedId = id;
		armedAt = now;
		say(question, Tone.WARN);
		sfx(Sfx.MENU_MOVE);
		return false;
	}

	private boolean armed(String id) {
		return id.equals(armedId) && clock.getAsLong() - armedAt < CONFIRM_MS;
	}

	private void resetAll() {
		if (!confirm("resetAll", "한 번 더 누르면 모든 설정을 기본값으로 돌려요")) {
			return;
		}
		UserSettings before = data.settings();
		change(UserSettings::withDefaultsKeepingMenu);
		say(before.withDefaultsKeepingMenu().equals(before) ? "이미 기본 설정이에요" : "모든 설정을 기본값으로 돌렸어요", Tone.OK);
		sfx(Sfx.MENU_SELECT);
	}

	private void resetKeys() {
		if (!confirm("keysDefault", "한 번 더 누르면 모든 키를 기본값으로 돌려요")) {
			return;
		}
		change(s -> s.withKeys(KeyBindings.defaults()));
		say("모든 키를 기본값으로 돌렸어요", Tone.OK);
		sfx(Sfx.MENU_SELECT);
	}

	private void resetAction(KeyAction a) {
		KeyBindings before = data.settings().keys();
		KeyBindings after = change(s -> s.withKeys(s.keys().reset(a))).keys();
		KeyAction other = null;
		for (KeyAction o : KeyAction.values()) {
			if (o != a && !before.keys(o).equals(after.keys(o))) {
				other = o;
				break;
			}
		}
		if (other != null) {
			say(other.label() + " 키는 이제 " + after.describe(other), Tone.INFO);
			flash(other);
		} else {
			say(a.label() + " 키를 기본값으로 돌렸어요", Tone.OK);
		}
		sfx(Sfx.MENU_SELECT);
	}

	private void flash(KeyAction a) {
		flashAction = a;
		flashAt = clock.getAsLong();
		poke();
	}

	private void startCapture(KeyAction a, int slot) {
		captureAction = a;
		captureSlot = slot;
		armedId = null;
		message = null;
		sfx(Sfx.MENU_SELECT);
		poke();
	}

	/** 키를 기다리는 중에 받은 키. Esc 는 취소, 쓸 수 없는 키는 이유를 보이고 계속 기다린다. */
	private void captured(int code) {
		KeyAction a = captureAction;
		int slot = captureSlot;
		if (code == KeyEvent.VK_ESCAPE) {
			captureAction = null;
			message = null;
			sfx(Sfx.MENU_BACK);
			return;
		}
		KeyBindings keys = data.settings().keys();
		String why = keys.reasonAgainst(a, Math.min(slot, keys.keys(a).size()), code);
		if (why != null) {
			say(why, Tone.WARN);
			sfx(Sfx.DENY);
			return;
		}
		String notice = keys.assignNotice(a, Math.min(slot, keys.keys(a).size()), code);
		KeyAction other = keys.actionOf(code);
		UserSettings after = change(s -> {
			int at = Math.min(slot, s.keys().keys(a).size());
			return s.keys().reasonAgainst(a, at, code) == null ? s.withKeys(s.keys().assign(a, at, code)) : s;
		});
		captureAction = null;
		if (notice != null) {
			say(notice, Tone.INFO);
			if (other != null && other != a) {
				flash(other);
			}
		} else {
			say(a.label() + " 키: " + after.keys().describe(a), Tone.OK);
		}
		focus = keyId(a, Math.min(slot, after.keys().keys(a).size() - 1));
		sfx(Sfx.MENU_SELECT);
	}

	/** 초점이 있는 키 칸의 키를 뺀다 (Delete·Backspace, 키 위의 ×). */
	private void removeKey(KeyAction a, int slot) {
		KeyBindings keys = data.settings().keys();
		if (slot >= keys.keys(a).size()) {
			sfx(Sfx.DENY);
			return;
		}
		if (keys.keys(a).size() == 1) {
			say(a.label() + "에는 키가 하나는 있어야 해요", Tone.WARN);
			sfx(Sfx.DENY);
			return;
		}
		int code = keys.keys(a).get(slot);
		change(s -> slot < s.keys().keys(a).size() && s.keys().keys(a).size() > 1 ? s.withKeys(s.keys().remove(a, slot))
				: s);
		say(a.label() + "에서 " + KeyNames.phrase(code) + "를 뺐어요", Tone.OK);
		sfx(Sfx.MENU_SELECT);
	}

	private void toggle(String id) {
		UserSettings s = data.settings();
		boolean on = toggleValue(s, id);
		setToggle(id, !on);
		sfx(Sfx.MENU_SELECT);
	}

	private static boolean toggleValue(UserSettings s, String id) {
		switch (id) {
		case "ghost":
			return s.ghost();
		case "colorBlind":
			return s.colorBlind();
		case "patterns":
			return s.patterns();
		case "fullscreen":
			return s.fullscreenOnStart();
		case "muted":
			return s.muted();
		case "shake":
			return s.shake();
		case "calmFlash":
			return s.calmFlash();
		case "heartbeat":
			return s.heartbeat();
		default:
			throw new IllegalArgumentException(id);
		}
	}

	private void setToggle(String id, boolean v) {
		switch (id) {
		case "ghost":
			change(s -> s.withGhost(v));
			break;
		case "colorBlind":
			change(s -> s.withColorBlind(v));
			break;
		case "patterns":
			change(s -> s.withPatterns(v));
			break;
		case "fullscreen":
			change(s -> s.withFullscreenOnStart(v));
			break;
		case "muted":
			change(s -> s.withMuted(v));
			break;
		case "shake":
			change(s -> s.withShake(v));
			break;
		case "calmFlash":
			change(s -> s.withCalmFlash(v));
			break;
		case "heartbeat":
			change(s -> s.withHeartbeat(v));
			break;
		default:
			throw new IllegalArgumentException(id);
		}
	}

	/** 효과 세기를 {@code d} 단계 옮긴다 (끝이면 그대로 false). */
	private boolean stepEffects(int d) {
		int v = data.settings().effects() + d;
		if (v < UserSettings.EFFECTS_OFF || v > UserSettings.EFFECTS_HIGH) {
			return false;
		}
		change(s -> s.withEffects(v));
		poke();
		return true;
	}

	/** 효과 세기를 {@code v} 로 (마우스로 칸을 누름). */
	private void setEffects(int v) {
		focus = "effects";
		if (data.settings().effects() == v) {
			return;
		}
		change(s -> s.withEffects(v));
		sfx(Sfx.MENU_MOVE);
		poke();
	}

	/** 막대를 한 칸 옮긴다. 소리 크기를 바꾸면 음소거를 푼다(크기를 들려줘야 하므로). */
	private boolean stepSlider(Slider sl, int d) {
		UserSettings s = data.settings();
		int v = sl.get().applyAsInt(s);
		int next = sl.clamp(v + d * sl.step());
		if (next == v) {
			return false;
		}
		setSlider(sl, next);
		return true;
	}

	private void setSlider(Slider sl, int v) {
		change(s -> {
			UserSettings n = sl.set().apply(s, sl.clamp(v));
			return sl == VOLUME ? n.withMuted(false) : n;
		});
	}

	private void playTest() {
		Sound s = sound.get();
		UserSettings u = data.settings();
		if (!s.isAvailable()) {
			say(soundStatus(s.offCause(), u.muted(), u.volume()).head(), Tone.WARN);
			sfx(Sfx.DENY);
			return;
		}
		if (u.muted() || u.volume() == 0) {
			say(u.muted() ? "음소거를 끄면 들려요" : "크기를 올리면 들려요", Tone.WARN);
			return;
		}
		s.play(List.of(Cue.of(Sfx.HARD_DROP), Cue.of(Sfx.CLEAR, 4).withDelay(110)));
	}

	// ------------------------------------------------------------------ 키

	/** 키를 눌렀다. 이 화면이 쓴 키면 true. */
	private boolean onKey(int code, boolean shift) {
		boolean repeat = !down.add(code); // 운영체제의 자동 반복
		if (capturing()) {
			if (!repeat) { // 고르려고 누른 Enter 를 누르고 있어도 그 키로 묶이지 않게
				captured(code);
			}
			repaint();
			return true;
		}
		List<Item> items = items();
		Item f = find(items, focus);
		switch (code) {
		case KeyEvent.VK_ESCAPE:
			if (!repeat) {
				dragId = null;
				onBack.run();
			}
			return true;
		case KeyEvent.VK_UP:
			move(items, f, 0, -1);
			break;
		case KeyEvent.VK_DOWN:
			move(items, f, 0, 1);
			break;
		case KeyEvent.VK_TAB:
			move(items, f, 0, shift ? -1 : 1);
			break;
		case KeyEvent.VK_LEFT:
		case KeyEvent.VK_RIGHT:
			int d = code == KeyEvent.VK_LEFT ? -1 : 1;
			if (f != null && f.step != null) {
				armedId = null;
				sfx(f.step.test(d) ? f.stepSound : Sfx.DENY);
			} else {
				move(items, f, d, 0);
			}
			break;
		case KeyEvent.VK_ENTER:
		case KeyEvent.VK_SPACE:
			if (!repeat && f != null && f.press != null) {
				if (!f.id.equals(armedId)) {
					armedId = null;
				}
				f.press.run();
			}
			break;
		case KeyEvent.VK_DELETE:
		case KeyEvent.VK_BACK_SPACE:
			if (!repeat && f != null && f.id.startsWith("key:")) {
				removeKey(actionOf(f.id), slotOf(f.id));
			}
			break;
		default:
			return false;
		}
		repaint();
		return true;
	}

	/** 방향 (dx, dy) 으로 가장 가까운 항목에 초점을 옮긴다. 갈래 카드에 닿으면 그 갈래를 고른다. 끝이면 조용히. */
	private void move(List<Item> items, Item from, int dx, int dy) {
		if (from == null) {
			Item tab = find(items, tabId(section));
			if (tab != null) {
				setFocus(tab);
			}
			return;
		}
		Item best = null;
		double bestScore = Double.MAX_VALUE;
		Rectangle a = from.r;
		for (Item it : items) {
			if (it == from || !it.focusable) {
				continue;
			}
			Rectangle b = it.r;
			double along;
			double cross;
			if (dx != 0) {
				along = dx > 0 ? b.x - a.getMaxX() : a.x - b.getMaxX();
				cross = gap(a.y, a.getMaxY(), b.y, b.getMaxY());
			} else {
				along = dy > 0 ? b.y - a.getMaxY() : a.y - b.getMaxY();
				cross = gap(a.x, a.getMaxX(), b.x, b.getMaxX());
			}
			if (along < -4) {
				continue; // 그 방향에 있지 않다
			}
			double off = dx != 0 ? Math.abs(b.getCenterY() - a.getCenterY()) : Math.abs(b.getCenterX() - a.getCenterX());
			double score = Math.max(0, along) + 3 * cross + 0.02 * off + 1e-4 * (b.x + b.y); // 똑같으면 왼쪽·위쪽 것
			if (score < bestScore) {
				bestScore = score;
				best = it;
			}
		}
		if (best == null) {
			return;
		}
		if (best.id.startsWith("tab:") && !from.id.startsWith("tab:")) {
			// 내용이나 위 버튼에서 카드 줄로 들어오면 언제나 지금 갈래의 카드로 (옆 갈래로 몰래 바뀌지 않게)
			Item current = find(items, tabId(section));
			if (current != null) {
				best = current;
			}
		}
		armedId = null;
		setFocus(best);
		sfx(Sfx.MENU_MOVE);
	}

	/** 두 구간 사이의 거리 (겹치면 0). */
	private static double gap(double a0, double a1, double b0, double b1) {
		return Math.max(0, Math.max(b0 - a1, a0 - b1));
	}

	private void setFocus(Item it) {
		focus = it.id;
		focusRect = it.r;
		if (it.id.startsWith("tab:")) {
			Section s = Section.valueOf(it.id.substring(4));
			if (s != section) {
				section = s;
				dragId = null;
				poke(); // 효과 갈래의 미리 보기가 움직이게
			}
		}
	}

	// ------------------------------------------------------------------ 마우스

	private void click(int x, int y) {
		dragId = null; // 새로 눌렀으면 지난 끌기는 끝났다(그 뗌을 받지 못했어도 끄던 값은 저장하지 않는다)
		Item h = itemAt(x, y);
		if (capturing()) { // 기다리는 중에 누르면 취소. 다른 항목을 눌렀으면 그 일도 한다
			String waiting = keyId(captureAction, captureSlot);
			captureAction = null;
			message = null;
			if (h == null || h.id.equals(waiting)) {
				sfx(Sfx.MENU_BACK);
				repaint();
				return;
			}
		}
		if (h == null) {
			return;
		}
		if (!h.id.equals(armedId)) {
			armedId = null;
		}
		if (h.focusable) {
			boolean tabChange = h.id.startsWith("tab:") && !h.id.equals(tabId(section));
			setFocus(h);
			if (tabChange) {
				sfx(Sfx.MENU_MOVE);
				repaint();
				return;
			}
		}
		if (h.slider != null) {
			dragId = h.id;
			dragValue = valueAt(h, x, y);
			repaint();
			return;
		}
		// 효과 세기 카드는 칸을 눌러 고른다 (카드의 빈 곳을 누르면 초점만): Enter 의 "다음 세기" 는 키보드에만
		if (h.press != null && !h.id.startsWith("tab:") && !h.id.equals("effects")) {
			h.press.run();
		}
		repaint();
	}

	private void drag(int x, int y) {
		if (dragId == null) {
			return;
		}
		Item it = find(items(), dragId);
		if (it != null && it.slider != null) {
			int v = valueAt(it, x, y);
			if (v != dragValue) {
				dragValue = v;
				repaint();
			}
		}
	}

	/** 끌던 막대를 놓으면 그 값으로 저장한다. */
	private void release() {
		if (dragId == null) {
			return;
		}
		Item it = find(items(), dragId);
		dragId = null;
		if (it != null && it.slider != null) {
			int before = it.slider.get().applyAsInt(data.settings());
			if (dragValue != before) {
				setSlider(it.slider, dragValue);
			}
			sfx(Sfx.MENU_MOVE);
		}
		repaint();
	}

	/** 막대 위 화면 좌표 → 값 (한 칸에 맞춤). */
	private int valueAt(Item it, int x, int y) {
		Point2D v = toVirtual(x, y);
		double t = (v.getX() - (it.r.x + 12)) / (it.r.width - 24.0);
		Slider sl = it.slider;
		return sl.clamp((int) Math.round(sl.min() + Math.max(0, Math.min(1, t)) * (sl.max() - sl.min())));
	}

	private Item itemAt(int x, int y) {
		Point2D v = toVirtual(x, y);
		if (v == null) {
			return null;
		}
		for (Item it : items()) {
			if (it.r.contains(v)) {
				return it;
			}
		}
		return null;
	}

	private AffineTransform transform() {
		double scale = Math.min(getWidth() / (double) W, getHeight() / (double) H);
		if (!(scale > 0)) {
			scale = 1;
		}
		AffineTransform t = new AffineTransform();
		t.translate((getWidth() - W * scale) / 2, (getHeight() - H * scale) / 2);
		t.scale(scale, scale);
		return t;
	}

	private Point2D toVirtual(int x, int y) {
		try {
			return transform().inverseTransform(new Point2D.Double(x, y), null);
		} catch (NoninvertibleTransformException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ 배치

	private static String tabId(Section s) {
		return "tab:" + s.name();
	}

	private static String keyId(KeyAction a, int slot) {
		return a == null ? "" : "key:" + a.id() + ":" + slot;
	}

	private static KeyAction actionOf(String id) {
		String[] p = id.split(":");
		return KeyAction.byId(p[1]);
	}

	private static int slotOf(String id) {
		String[] p = id.split(":");
		return Integer.parseInt(p[2]);
	}

	private static Item find(List<Item> items, String id) {
		if (id == null) {
			return null;
		}
		for (Item it : items) {
			if (it.id.equals(id)) {
				return it;
			}
		}
		return null;
	}

	private static Item add(List<Item> list, String id, Rectangle r) {
		Item it = new Item(id, r, true);
		list.add(it);
		return it;
	}

	/** 지금 상태의 항목들. 초점이 있던 항목이 사라졌으면(키를 빼서 칸이 줄어듦 등) 그 자리에서 가장 가까운 항목으로 옮긴다. */
	private List<Item> items() {
		List<Item> list = new ArrayList<>();
		UserSettings s = data.settings();
		add(list, "back", new Rectangle(40, 40, 150, 44)).press = onBack;
		add(list, "resetAll", new Rectangle(1090, 40, 150, 44)).press = this::resetAll;
		int tabW = (IN_W - TAB_GAP * (Section.values().length - 1)) / Section.values().length;
		for (Section sec : Section.values()) {
			Item t = add(list, tabId(sec), new Rectangle(IN_X + sec.ordinal() * (tabW + TAB_GAP), TAB_Y, tabW, TAB_H));
			t.press = () -> {
				section = sec;
				enterSection();
			};
		}
		switch (section) {
		case CONTROLS:
			layoutControls(list, s.keys());
			break;
		case FEEL:
			layoutFeel(list, s);
			break;
		case DISPLAY:
			layoutDisplay(list);
			break;
		case EFFECTS:
			layoutEffects(list);
			break;
		case SOUND:
			layoutSound(list);
			break;
		default:
			layoutAi(list, s);
			break;
		}
		Item f = find(list, focus);
		if (f == null || !f.focusable) {
			Item near = nearest(list, focusRect);
			if (near != null) {
				focus = near.id;
				focusRect = near.r;
			}
		} else {
			focusRect = f.r;
		}
		return list;
	}

	/** 갈래 카드에서 Enter: 내용의 첫 항목으로. */
	private void enterSection() {
		List<Item> items = items();
		for (Item it : items) {
			if (it.focusable && !it.id.startsWith("tab:") && !it.id.equals("back") && !it.id.equals("resetAll")) {
				setFocus(it);
				sfx(Sfx.MENU_MOVE);
				return;
			}
		}
	}

	private Item nearest(List<Item> items, Rectangle r) {
		if (r == null) {
			return find(items, tabId(section));
		}
		Item best = null;
		double bestD = Double.MAX_VALUE;
		for (Item it : items) {
			if (!it.focusable) {
				continue;
			}
			double d = Point2D.distance(r.getCenterX(), r.getCenterY(), it.r.getCenterX(), it.r.getCenterY());
			if (d < bestD) {
				bestD = d;
				best = it;
			}
		}
		return best;
	}

	private static Rectangle slotRect(int colX, int row, int slot) {
		return new Rectangle(colX + LABEL_W + slot * (CAP_W + CAP_GAP), ROW_Y + row * ROW_H, CAP_W, CAP_H);
	}

	private static Rectangle resetRect(int colX, int row) {
		return new Rectangle(colX + LABEL_W + KeyBindings.MAX_KEYS * (CAP_W + CAP_GAP), ROW_Y + row * ROW_H, 30, CAP_H);
	}

	/** 화면 조작 열 아래 안내 카드의 위 끝 (화면 조작 줄 다음). */
	private static final int KEY_NOTE_Y = ROW_Y + SCREEN_ACTIONS.length * ROW_H + 2;
	private static final int KEY_NOTE_H = 80;
	private static final Rectangle KEYS_DEFAULT = new Rectangle(RIGHT_X, 552, 196, 34);

	private void layoutControls(List<Item> list, KeyBindings keys) {
		for (int col = 0; col < 2; col++) {
			KeyAction[] actions = col == 0 ? PIECE_ACTIONS : SCREEN_ACTIONS;
			int colX = col == 0 ? IN_X : RIGHT_X;
			for (int row = 0; row < actions.length; row++) {
				KeyAction a = actions[row];
				int n = keys.keys(a).size();
				int slots = Math.min(KeyBindings.MAX_KEYS, n + 1);
				for (int slot = 0; slot < slots; slot++) {
					Rectangle r = slotRect(colX, row, slot);
					int sl = slot;
					if (slot < n && n > 1 && hoverSlot(a, slot)) { // 마우스를 올린 키의 ×
						Item del = new Item("del:" + a.id() + ":" + slot, new Rectangle(r.x + r.width - 11, r.y - 6, 17, 17),
								false);
						del.press = () -> removeKey(a, sl);
						list.add(del);
					}
					add(list, keyId(a, slot), r).press = () -> startCapture(a, sl);
				}
				if (!keys.isDefault(a)) {
					add(list, "keyReset:" + a.id(), resetRect(colX, row)).press = () -> resetAction(a);
				}
			}
		}
		if (!keys.isDefault()) {
			add(list, "keysDefault", KEYS_DEFAULT).press = this::resetKeys;
		}
	}

	/** 마우스가 이 키 칸(또는 그 ×) 위에 있는가. */
	private boolean hoverSlot(KeyAction a, int slot) {
		String h = hover;
		return h != null && (h.equals(keyId(a, slot)) || h.equals("del:" + a.id() + ":" + slot));
	}

	private static final Rectangle DAS_BAR = new Rectangle(IN_X, 278, IN_W, 34);
	private static final Rectangle ARR_BAR = new Rectangle(IN_X, 398, IN_W, 34);
	private static final Rectangle FEEL_DEFAULT = new Rectangle(IN_X + IN_W - 150, 478, 150, 32);

	private void layoutFeel(List<Item> list, UserSettings s) {
		slider(list, "das", DAS_BAR, DAS);
		slider(list, "arr", ARR_BAR, ARR);
		if (s.dasMs() != DAS.def() || s.arrMs() != ARR.def()) {
			add(list, "feelDefault", FEEL_DEFAULT).press = () -> {
				change(u -> u.withDasMs(DAS.def()).withArrMs(ARR.def()));
				say("DAS·ARR을 기본값으로 돌렸어요", Tone.OK);
				sfx(Sfx.MENU_SELECT);
			};
		}
	}

	private void slider(List<Item> list, String id, Rectangle r, Slider sl) {
		Item it = add(list, id, r);
		it.slider = sl;
		it.step = d -> stepSlider(sl, d);
	}

	private static final String[] DISPLAY_TOGGLES = { "ghost", "colorBlind", "patterns", "fullscreen" };
	private static final int TOGGLE_W = 404;
	private static final int TOGGLE_H = 72;

	private static Rectangle toggleRect(int i) {
		return new Rectangle(IN_X, 250 + i * (TOGGLE_H + 10), TOGGLE_W, TOGGLE_H);
	}

	private void layoutDisplay(List<Item> list) {
		for (int i = 0; i < DISPLAY_TOGGLES.length; i++) {
			toggleItem(list, DISPLAY_TOGGLES[i], toggleRect(i));
		}
	}

	private void toggleItem(List<Item> list, String id, Rectangle r) {
		Item it = add(list, id, r);
		it.press = () -> toggle(id);
		it.stepSound = Sfx.MENU_SELECT;
		it.step = d -> { // ← 끔, → 켬
			boolean want = d > 0;
			if (toggleValue(data.settings(), id) == want) {
				return false;
			}
			setToggle(id, want);
			return true;
		};
	}

	// ---- 효과: 왼쪽에 효과 세기(4 칸)·흔들림·번쩍임 줄이기, 오른쪽에 미리 보기
	static final Rectangle EFFECTS_CARD = new Rectangle(IN_X, 250, TOGGLE_W, 112);
	private static final Rectangle SHAKE = new Rectangle(IN_X, 372, TOGGLE_W, TOGGLE_H);
	private static final Rectangle CALM = new Rectangle(IN_X, 454, TOGGLE_W, TOGGLE_H);
	private static final Rectangle DEMO = new Rectangle(IN_X + TOGGLE_W + 22, 250, IN_W - TOGGLE_W - 22, 276);

	/** 효과 세기의 칸 {@code i} (0 끔 … 3 강하게) 자리. */
	static Rectangle effectSegment(int i) {
		int x0 = EFFECTS_CARD.x + 20;
		int w = EFFECTS_CARD.width - 40;
		int gap = 6;
		int sw = (w - gap * (EFFECT_NAMES.length - 1)) / EFFECT_NAMES.length;
		return new Rectangle(x0 + i * (sw + gap), EFFECTS_CARD.y + 70, sw, 28);
	}

	private void layoutEffects(List<Item> list) {
		for (int i = 0; i < EFFECT_NAMES.length; i++) { // 칸을 누르면 그 세기로 (카드보다 먼저 찾게 앞에 둔다)
			int v = i;
			Item seg = new Item("effects:" + i, effectSegment(i), false);
			seg.press = () -> setEffects(v);
			list.add(seg);
		}
		Item it = add(list, "effects", EFFECTS_CARD);
		it.step = this::stepEffects;
		it.press = () -> { // Enter: 다음 세기 (강하게 다음은 끔)
			int next = (data.settings().effects() + 1) % EFFECT_NAMES.length;
			change(s -> s.withEffects(next));
			sfx(Sfx.MENU_MOVE);
			poke();
		};
		toggleItem(list, "shake", SHAKE);
		toggleItem(list, "calmFlash", CALM);
	}

	// ---- 소리: 크기 막대, 그 아래 2 × 2 카드 (음소거, 들어 보기 / 심장 소리, 장치 상태)
	private static final Rectangle VOLUME_BAR = new Rectangle(IN_X, 278, IN_W, 34);
	private static final Rectangle MUTE = new Rectangle(IN_X, 380, 380, TOGGLE_H);
	private static final Rectangle TEST = new Rectangle(IN_X + 404, 380, 380, TOGGLE_H);
	private static final Rectangle HEARTBEAT = new Rectangle(IN_X, 462, 380, TOGGLE_H);
	private static final Rectangle STATUS = new Rectangle(IN_X + 404, 462, 380, TOGGLE_H);

	private void layoutSound(List<Item> list) {
		slider(list, "volume", VOLUME_BAR, VOLUME);
		toggleItem(list, "muted", MUTE);
		add(list, "test", TEST).press = this::playTest;
		toggleItem(list, "heartbeat", HEARTBEAT);
	}

	// ---- AI 속도: 막대와 난이도마다 블록 하나에 드는 시간
	private static final Rectangle AI_BAR = new Rectangle(IN_X, 278, IN_W, 34);
	private static final Rectangle AI_DEFAULT = new Rectangle(IN_X + IN_W - 150, 562, 150, 32);

	private void layoutAi(List<Item> list, UserSettings s) {
		slider(list, "aiSpeed", AI_BAR, AI_SPEED);
		if (s.aiSpeed() != AI_SPEED.def()) {
			add(list, "aiDefault", AI_DEFAULT).press = () -> {
				change(u -> u.withAiSpeed(AI_SPEED.def()));
				say("AI 속도를 기본값으로 돌렸어요", Tone.OK);
				sfx(Sfx.MENU_SELECT);
			};
		}
	}

	// ------------------------------------------------------------------ 움직임

	private boolean animating() {
		return othersMoving() || section == Section.EFFECTS;
	}

	/** 효과 미리 보기 말고 움직이는 것: 키 기다림, 알림, 맞바꾼 줄의 반짝임, 두 번 눌러야 하는 버튼의 확인. */
	private boolean othersMoving() {
		long now = clock.getAsLong();
		Message m = message;
		return capturing() || m != null && now - m.at() < MESSAGE_MS || flashAction != null && now - flashAt < FLASH_MS
				|| armedId != null && now - armedAt < CONFIRM_MS;
	}

	/** 효과 갈래에서 미리 보기만 움직이는가 (그러면 다시 그리기 타이머가 미리 보기 자리만 다시 그린다). */
	boolean previewOnly() {
		return section == Section.EFFECTS && !othersMoving();
	}

	/** 효과 미리 보기 카드의 화면 자리 (패널 좌표, 흔들림과 테두리 여유 8 화소). */
	Rectangle previewArea() {
		Rectangle r = transform().createTransformedShape(DEMO).getBounds();
		r.grow(8, 8);
		return r;
	}

	/** 움직이는 것(기다림, 알림, 반짝임)이 있는 동안만 다시 그리기 타이머를 돌린다. 창에 보이지 않으면 돌리지 않는다. */
	private void poke() {
		if (isShowing() && animating() && !anim.isRunning()) {
			anim.start();
		}
	}

	@Override
	public void removeNotify() {
		anim.stop();
		super.removeNotify();
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics.create();
		Theme.antialias(g);
		Theme.backgroundCached(g, getWidth(), getHeight());
		g.transform(transform());
		List<Item> items = items();
		UserSettings s = data.settings();
		long now = clock.getAsLong();
		paintDecor(g);
		paintHeader(g, "설정", subtitle());
		Item back = find(items, "back");
		paintBack(g, back.r, focused(back), hovered(back));
		paintResetAll(g, find(items, "resetAll"), now);
		Theme.card(g, CARD_X, CARD_Y, CARD_W, CARD_H, 28, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER);
		paintTabs(g, items);
		switch (section) {
		case CONTROLS:
			paintControls(g, items, s.keys(), now);
			break;
		case FEEL:
			paintFeel(g, items, s);
			break;
		case DISPLAY:
			paintDisplay(g, items, s);
			break;
		case EFFECTS:
			paintEffects(g, items, s, now);
			break;
		case SOUND:
			paintSound(g, items, s);
			break;
		default:
			paintAi(g, items, s);
			break;
		}
		paintMessage(g, now);
		paintHints(g, find(items, focus));
		if (data.problem() != null) {
			g.setFont(Theme.font(12, false));
			g.setColor(Theme.WARN);
			g.drawString(data.problem(), 24, H - 10);
		}
		g.dispose();
	}

	private boolean focused(Item it) {
		return it != null && it.id.equals(focus) && !capturing();
	}

	private boolean hovered(Item it) {
		return it != null && it.id.equals(hover);
	}

	/** 양옆에 옅게 깔린 블록 무늬 (메뉴와 같은 느낌, 더 옅게). */
	static void paintDecor(Graphics2D g) {
		int[][][] shapes = { { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 2, 1 } }, { { 0, 1 }, { 1, 0 }, { 1, 1 }, { 1, 2 } },
				{ { 0, 0 }, { 0, 1 }, { 1, 0 }, { 1, 1 } }, { { 0, 0 }, { 1, 0 }, { 2, 0 }, { 2, 1 } } };
		int[][] places = { { 64, 196, 5 }, { 120, 520, 6 }, { 1132, 230, 4 }, { 1150, 500, 3 } };
		for (int i = 0; i < places.length; i++) {
			for (int[] c : shapes[i]) {
				Theme.block(g, places[i][0] + c[1] * 30, places[i][1] + c[0] * 30, 30, places[i][2], 28, false);
			}
		}
	}

	/** 위 띠: 왼쪽 위 메뉴로 버튼, 가운데 제목과 짧은 설명. 도움말 화면도 같은 띠를 쓴다. */
	static void paintHeader(Graphics2D g, String title, String sub) {
		g.setFont(Theme.font(44, true));
		g.setColor(Theme.TEXT);
		Theme.centered(g, title, W / 2.0, 82);
		g.setPaint(new GradientPaint(W / 2f - 28, 0, Theme.P1, W / 2f + 28, 0, Theme.ACCENT_2));
		g.fill(new RoundRectangle2D.Double(W / 2.0 - 28, 96, 56, 4, 4, 4));
		g.setFont(Theme.font(15, false));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, sub, W / 2.0, 126);
	}

	/** 왼쪽 위 "메뉴로" 버튼 (Esc 키 모양과 함께). */
	static void paintBack(Graphics2D g, Rectangle r, boolean focused, boolean hovered) {
		Theme.card(g, r.x, r.y, r.width, r.height, r.height,
				hovered ? Theme.SURFACE_HI : Theme.withAlpha(Theme.SURFACE, 220), hovered ? Theme.TEXT_FAINT : Theme.BORDER);
		if (focused) {
			Theme.glow(g, r.x, r.y, r.width, r.height, r.height, Theme.ACCENT, 0.9);
		}
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Theme.TEXT);
		double cx = r.x + 24;
		double cy = r.getCenterY();
		Path2D.Double chevron = new Path2D.Double();
		chevron.moveTo(cx + 4, cy - 7);
		chevron.lineTo(cx - 3, cy);
		chevron.lineTo(cx + 4, cy + 7);
		g.draw(chevron);
		g.setStroke(old);
		g.setFont(Theme.font(16, true));
		g.drawString("메뉴로", r.x + 40, (int) cy + 6);
		keyCapSmall(g, "Esc", r.x + r.width - 52, (int) cy - 11, 40);
	}

	/** 작은 키 모양 (버튼 안, 아래 안내 줄). */
	static void keyCapSmall(Graphics2D g, String text, int x, int y, int w) {
		Theme.card(g, x, y, w, 22, 8, new Color(10, 12, 30, 120), new Color(255, 255, 255, 70));
		g.setFont(Theme.font(11, true));
		g.setColor(Theme.TEXT_DIM);
		Theme.centered(g, text, x + w / 2.0, y + 15);
	}

	private void paintResetAll(Graphics2D g, Item it, long now) {
		if (it == null) {
			return;
		}
		Rectangle r = it.r;
		boolean armed = armed(it.id);
		boolean hov = hovered(it);
		Theme.card(g, r.x, r.y, r.width, r.height, r.height,
				armed ? Theme.withAlpha(Theme.WARN, 40) : hov ? Theme.SURFACE_HI : Theme.withAlpha(Theme.SURFACE, 220),
				armed ? Theme.WARN : hov ? Theme.TEXT_FAINT : Theme.BORDER);
		if (focused(it) || armed) {
			Theme.glow(g, r.x, r.y, r.width, r.height, r.height, armed ? Theme.WARN : Theme.ACCENT,
					armed ? 0.6 + 0.4 * Math.sin((now - armedAt) / 110.0) : 0.9);
		}
		resetIcon(g, r.x + 26, r.getCenterY(), 8, armed ? Theme.WARN : Theme.TEXT_DIM);
		g.setFont(Theme.font(15, true));
		g.setColor(armed ? Theme.WARN : Theme.TEXT);
		g.drawString(armed ? "한 번 더" : "모두 기본값으로", r.x + 44, (int) r.getCenterY() + 5);
	}

	/** ↺ 모양: 열린 원과 화살촉. */
	static void resetIcon(Graphics2D g, double cx, double cy, double rad, Color c) {
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke((float) Math.max(1.8, rad / 3.6), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(c);
		g.draw(new Arc2D.Double(cx - rad, cy - rad, 2 * rad, 2 * rad, 100, 280, Arc2D.OPEN));
		double ax = cx + rad * Math.cos(Math.toRadians(100));
		double ay = cy - rad * Math.sin(Math.toRadians(100));
		Path2D.Double head = new Path2D.Double();
		head.moveTo(ax - rad * 0.55, ay - rad * 0.5);
		head.lineTo(ax + rad * 0.1, ay);
		head.lineTo(ax - rad * 0.45, ay + rad * 0.55);
		g.draw(head);
		g.setStroke(old);
	}

	private void paintTabs(Graphics2D g, List<Item> items) {
		for (Section sec : Section.values()) {
			Item it = find(items, tabId(sec));
			Rectangle r = it.r;
			boolean sel = sec == section;
			boolean hov = hovered(it);
			Color fill = sel ? Theme.SURFACE_HI
					: hov ? Theme.withAlpha(Theme.SURFACE_HI, 160) : Theme.withAlpha(Theme.BG_TOP, 200);
			Theme.card(g, r.x, r.y, r.width, r.height, 18, fill, hov && !sel ? Theme.TEXT_FAINT : Theme.BORDER);
			if (sel) {
				Theme.glow(g, r.x, r.y, r.width, r.height, 18, Theme.ACCENT, focused(it) ? 1 : 0.45);
			}
			// 여섯 갈래라 카드가 좁다: 그림 22, 글자는 그 오른쪽에, 넘치면 줄인다
			tabIcon(g, sec, r.x + 12, r.y + 18, 22, sel ? Theme.P1 : Theme.LABEL);
			int tx = r.x + 42;
			int maxW = r.x + r.width - 8 - tx;
			fitLeft(g, sec.title, tx, r.y + 28, maxW, 17, true, sel ? Theme.TEXT : Theme.TEXT_DIM);
			fitLeft(g, sec.sub, tx, r.y + 46, maxW, 11, false, sel ? Theme.TEXT_DIM : Theme.LABEL);
		}
	}

	/** 폭에 맞춰 글자를 줄여 왼쪽 맞춤으로 쓴다. */
	private static void fitLeft(Graphics2D g, String text, int x, int baseline, int maxW, double px, boolean bold,
			Color c) {
		double size = px;
		g.setFont(Theme.font(size, bold));
		while (size > 9 && g.getFontMetrics().stringWidth(text) > maxW) {
			size -= 1;
			g.setFont(Theme.font(size, bold));
		}
		g.setColor(c);
		g.drawString(text, x, baseline);
	}

	/** 갈래 카드의 작은 그림 ({@code size} × {@code size}, 28 크기로 그린 것을 줄인다). */
	private static void tabIcon(Graphics2D g, Section s, int x, int y, double size, Color c) {
		AffineTransform at = g.getTransform();
		g.translate(x, y);
		g.scale(size / 28.0, size / 28.0);
		tabIcon(g, s, 0, 0, c);
		g.setTransform(at);
	}

	/** 갈래 카드의 작은 그림 (28 × 28). */
	private static void tabIcon(Graphics2D g, Section s, int x, int y, Color c) {
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(c);
		switch (s) {
		case CONTROLS: // 자판
			g.draw(new RoundRectangle2D.Double(x, y + 5, 28, 19, 6, 6));
			for (int i = 0; i < 4; i++) {
				g.fill(new RoundRectangle2D.Double(x + 4 + i * 5.6, y + 9, 3.2, 3.2, 1.5, 1.5));
			}
			g.fill(new RoundRectangle2D.Double(x + 7, y + 16.5, 14, 3.2, 1.5, 1.5));
			break;
		case FEEL: // 시간 줄 위의 블록 셋
			g.draw(new java.awt.geom.Line2D.Double(x, y + 22, x + 28, y + 22));
			for (int i = 0; i < 3; i++) {
				double bx = x + i * 10;
				g.fill(new RoundRectangle2D.Double(bx + (i == 0 ? 0 : 2), y + 8 + (i == 0 ? 0 : 4), 7, 7, 2, 2));
			}
			break;
		case DISPLAY: // 화면 위의 T 블록
			g.draw(new RoundRectangle2D.Double(x, y + 2, 28, 22, 6, 6));
			g.fill(new RoundRectangle2D.Double(x + 9.5, y + 8, 9, 5, 1.5, 1.5));
			g.fill(new RoundRectangle2D.Double(x + 5, y + 13, 18, 5, 1.5, 1.5));
			break;
		case EFFECTS: { // 반짝임: 네 갈래 별과 작은 별 둘
			Path2D.Double star = new Path2D.Double();
			double cx = x + 12;
			double cy = y + 15;
			star.moveTo(cx, cy - 11);
			star.quadTo(cx + 1.5, cy - 1.5, cx + 11, cy);
			star.quadTo(cx + 1.5, cy + 1.5, cx, cy + 11);
			star.quadTo(cx - 1.5, cy + 1.5, cx - 11, cy);
			star.quadTo(cx - 1.5, cy - 1.5, cx, cy - 11);
			star.closePath();
			g.fill(star);
			g.fill(new Ellipse2D.Double(x + 22, y + 3, 4.5, 4.5));
			g.fill(new Ellipse2D.Double(x + 23, y + 22, 3.5, 3.5));
			break;
		}
		case AI: { // 속도계: 반원 눈금과 바늘
			g.draw(new Arc2D.Double(x + 1, y + 4, 26, 26, -10, 200, Arc2D.OPEN));
			double cx = x + 14;
			double cy = y + 17;
			double ang = Math.toRadians(40);
			g.draw(new java.awt.geom.Line2D.Double(cx, cy, cx + 9 * Math.cos(ang), cy - 9 * Math.sin(ang)));
			g.fill(new Ellipse2D.Double(cx - 3, cy - 3, 6, 6));
			for (int i = 0; i < 3; i++) { // 눈금 셋
				double a = Math.toRadians(170 - i * 80);
				g.draw(new java.awt.geom.Line2D.Double(cx + 9.5 * Math.cos(a), cy - 9.5 * Math.sin(a), cx + 12 * Math.cos(a),
						cy - 12 * Math.sin(a)));
			}
			break;
		}
		default: // 스피커와 소리 물결
			Path2D.Double sp = new Path2D.Double();
			sp.moveTo(x + 2, y + 10);
			sp.lineTo(x + 8, y + 10);
			sp.lineTo(x + 15, y + 4);
			sp.lineTo(x + 15, y + 24);
			sp.lineTo(x + 8, y + 18);
			sp.lineTo(x + 2, y + 18);
			sp.closePath();
			g.fill(sp);
			g.draw(new Arc2D.Double(x + 12, y + 7, 9, 14, -50, 100, Arc2D.OPEN));
			g.draw(new Arc2D.Double(x + 12, y + 2, 15, 24, -50, 100, Arc2D.OPEN));
			break;
		}
		g.setStroke(old);
	}

	// ---- 조작

	private void paintControls(Graphics2D g, List<Item> items, KeyBindings keys, long now) {
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		g.drawString("블록 조작", IN_X, 262);
		g.drawString("화면 조작", RIGHT_X, 262);
		for (int col = 0; col < 2; col++) {
			KeyAction[] actions = col == 0 ? PIECE_ACTIONS : SCREEN_ACTIONS;
			int colX = col == 0 ? IN_X : RIGHT_X;
			for (int row = 0; row < actions.length; row++) {
				paintKeyRow(g, items, keys, actions[row], colX, row, now);
			}
		}
		// 오른쪽 아래: 짧은 안내와 "키 모두 기본값으로"
		int bx = RIGHT_X;
		int by = KEY_NOTE_Y;
		Theme.card(g, bx, by, 376, KEY_NOTE_H, 16, Theme.withAlpha(Theme.BG_TOP, 150), Theme.withAlpha(Theme.BORDER, 200));
		g.setFont(Theme.font(11, true));
		int enterW = g.getFontMetrics().stringWidth("Enter") + 16;
		int deleteW = g.getFontMetrics().stringWidth("Delete") + 16;
		int tx = bx + 16;
		int ty = by + 31;
		keyCapSmall(g, "Enter", tx, ty - 15, enterW);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("칸을 고르고 새 키 누르기", tx + enterW + 10, ty);
		ty += 30;
		keyCapSmall(g, "Delete", tx, ty - 15, deleteW);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		int x2 = tx + deleteW + 10;
		g.drawString("키 빼기", x2, ty);
		x2 += g.getFontMetrics().stringWidth("키 빼기") + 22;
		resetIcon(g, x2 + 7, ty - 5, 6, Theme.TEXT_DIM);
		g.drawString("기본 키로", x2 + 20, ty);
		Item kd = find(items, "keysDefault");
		paintButton(g, KEYS_DEFAULT, armed("keysDefault") ? "한 번 더 누르면 돌려요" : "키 모두 기본값으로", kd != null, focused(kd),
				hovered(kd), armed("keysDefault"), true);
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.LABEL);
		Theme.right(g, "쓰던 키를 고르면 서로 바꿔요", bx + 376, KEYS_DEFAULT.y + 22);
	}

	private void paintKeyRow(Graphics2D g, List<Item> items, KeyBindings keys, KeyAction a, int colX, int row, long now) {
		int y = ROW_Y + row * ROW_H;
		boolean rowFocus = !capturing() && focus != null
				&& (focus.startsWith("key:" + a.id() + ":") || focus.equals("keyReset:" + a.id()));
		boolean rowCapture = capturing() && captureAction == a;
		String h = hover;
		boolean rowHover = h != null && (h.startsWith("key:" + a.id() + ":") || h.startsWith("del:" + a.id() + ":")
				|| h.equals("keyReset:" + a.id()));
		if (flashAction == a && now - flashAt < FLASH_MS) { // 맞바꿔서 키가 바뀐 줄: 세 번 깜빡이며 옅어진다
			double t = (now - flashAt) / (double) FLASH_MS;
			double beat = 0.55 + 0.45 * Math.cos(t * Math.PI * 6);
			int alpha = (int) Math.round(255 * (1 - t) * beat);
			int fw = LABEL_W + KeyBindings.MAX_KEYS * (CAP_W + CAP_GAP) + 44;
			Theme.card(g, colX - 12, y - 5, fw, CAP_H + 7, 14, Theme.withAlpha(Theme.P1, alpha * 40 / 255),
					Theme.withAlpha(Theme.P1, alpha * 200 / 255));
		}
		label(g, a.label(), colX, y + 23, rowFocus || rowCapture);
		List<Integer> k = keys.keys(a);
		int slots = Math.min(KeyBindings.MAX_KEYS, k.size() + 1);
		for (int slot = 0; slot < slots; slot++) {
			Rectangle r = slotRect(colX, row, slot);
			Item it = find(items, keyId(a, slot));
			boolean cap = rowCapture && captureSlot == slot;
			if (cap) {
				paintCaptureCap(g, r, now);
			} else if (slot < k.size()) {
				paintKeyCap(g, r, KeyNames.label(k.get(slot)), focused(it), hovered(it) || hoverSlot(a, slot), false);
				Item del = find(items, "del:" + a.id() + ":" + slot);
				if (del != null) {
					paintDelete(g, del.r, hovered(del));
				}
			} else {
				paintAddCap(g, r, focused(it), hovered(it), rowFocus || rowHover);
			}
		}
		Item reset = find(items, "keyReset:" + a.id());
		if (reset != null) {
			Rectangle r = reset.r;
			if (focused(reset) || hovered(reset)) {
				Theme.card(g, r.x, r.y, r.width, r.height, 12, Theme.SURFACE_HI, focused(reset) ? null : Theme.TEXT_FAINT);
				if (focused(reset)) {
					Theme.glow(g, r.x, r.y, r.width, r.height, 12, Theme.ACCENT, 0.9);
				}
			}
			boolean lit = focused(reset) || hovered(reset);
			resetIcon(g, r.getCenterX(), r.getCenterY(), 6.5, lit ? Theme.TEXT : Theme.LABEL);
		}
	}

	/** 키 모양: 아래로 살짝 두꺼운 둥근 상자와 가운데 글자. */
	private static void paintKeyCap(Graphics2D g, Rectangle r, String text, boolean focused, boolean hovered,
			boolean faint) {
		Theme.card(g, r.x, r.y + 3, r.width, r.height - 3, 10, Theme.withAlpha(Color.BLACK, 90), null);
		Theme.card(g, r.x, r.y, r.width, r.height - 3, 10, focused || hovered ? Theme.SURFACE_HI : Theme.SURFACE,
				focused ? Theme.ACCENT : hovered ? Theme.TEXT_FAINT : Theme.BORDER);
		if (focused) {
			Theme.glow(g, r.x, r.y, r.width, r.height - 3, 10, Theme.ACCENT, 0.9);
		}
		fitCentered(g, text, r.getCenterX(), r.y + (r.height - 3) / 2.0 + 5, r.width - 12, 14, true,
				faint ? Theme.TEXT_FAINT : Theme.TEXT);
	}

	/**
	 * 비어 있는 칸 (키를 더할 자리). 줄에 초점이나 마우스가 있을 때만 점선 테두리를 그리고, 아니면 옅은 + 만 (화면이 어지럽지
	 * 않게).
	 */
	private static void paintAddCap(Graphics2D g, Rectangle r, boolean focused, boolean hovered, boolean rowActive) {
		if (focused || hovered || rowActive) {
			Stroke old = g.getStroke();
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] { 4f, 4f },
					0f));
			g.setColor(focused ? Theme.ACCENT : hovered ? Theme.TEXT_DIM : Theme.withAlpha(Theme.TEXT_FAINT, 150));
			g.draw(new RoundRectangle2D.Double(r.x + 0.5, r.y + 0.5, r.width - 1, r.height - 4, 10, 10));
			g.setStroke(old);
		}
		if (focused) {
			Theme.glow(g, r.x, r.y, r.width, r.height - 3, 10, Theme.ACCENT, 0.7);
		}
		g.setFont(Theme.font(18, false));
		g.setColor(focused || hovered ? Theme.TEXT : Theme.withAlpha(Theme.TEXT_FAINT, rowActive ? 220 : 110));
		Theme.centered(g, "+", r.getCenterX(), r.y + 22);
	}

	/** 새 키를 기다리는 칸: 숨 쉬듯 빛나는 강조색. */
	private static void paintCaptureCap(Graphics2D g, Rectangle r, long now) {
		double pulse = 0.5 + 0.5 * Math.sin(now / 180.0);
		g.setPaint(new GradientPaint(r.x, r.y, Theme.ACCENT, r.x + r.width, r.y + r.height, Theme.ACCENT_2));
		g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height - 3, 10, 10));
		Theme.glow(g, r.x, r.y, r.width, r.height - 3, 10, Theme.ACCENT_2, 0.55 + 0.45 * pulse);
		g.setFont(Theme.font(13, true));
		g.setColor(Color.WHITE);
		Theme.centered(g, "키 입력", r.getCenterX(), r.y + 20);
	}

	/** 키 위의 × (마우스를 올렸을 때만). */
	private static void paintDelete(Graphics2D g, Rectangle r, boolean hovered) {
		g.setColor(hovered ? Theme.DANGER : Theme.withAlpha(Theme.TEXT_FAINT, 230));
		g.fill(new Ellipse2D.Double(r.x, r.y, r.width, r.height));
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(Color.WHITE);
		double c = r.width / 2.0;
		double d = r.width * 0.2;
		g.draw(new java.awt.geom.Line2D.Double(r.x + c - d, r.y + c - d, r.x + c + d, r.y + c + d));
		g.draw(new java.awt.geom.Line2D.Double(r.x + c + d, r.y + c - d, r.x + c - d, r.y + c + d));
		g.setStroke(old);
	}

	/** 작은 알약 버튼. 쓸 수 없으면(이미 기본값 등) 옅게만 그린다. */
	private static void paintButton(Graphics2D g, Rectangle r, String text, boolean enabled, boolean focused,
			boolean hovered, boolean armed, boolean icon) {
		Color border = armed ? Theme.WARN : hovered ? Theme.TEXT_FAINT : Theme.BORDER;
		Theme.card(g, r.x, r.y, r.width, r.height, r.height,
				armed ? Theme.withAlpha(Theme.WARN, 40) : hovered ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 200),
				enabled ? border : Theme.withAlpha(Theme.BORDER, 120));
		if (focused || armed) {
			Theme.glow(g, r.x, r.y, r.width, r.height, r.height, armed ? Theme.WARN : Theme.ACCENT, 0.85);
		}
		Color fg = !enabled ? Theme.withAlpha(Theme.TEXT_FAINT, 150) : armed ? Theme.WARN : Theme.TEXT;
		int tx = r.x + r.width / 2;
		if (icon) {
			resetIcon(g, r.x + 20, r.getCenterY(), 6.5, fg);
			tx += 8;
		}
		g.setFont(Theme.font(14, true));
		g.setColor(fg);
		Theme.centered(g, text, tx, r.getCenterY() + 5);
	}

	/** 줄 이름: 초점이 있는 줄은 밝게, 왼쪽에 강조색 막대 (메뉴와 같다). */
	private static void label(Graphics2D g, String text, int x, int y, boolean focused) {
		g.setFont(Theme.font(15, true));
		g.setColor(focused ? Theme.TEXT : Theme.TEXT_DIM);
		g.drawString(text, x, y);
		if (focused) {
			Stroke old = g.getStroke();
			g.setColor(Theme.ACCENT);
			g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(x - 12, y - 12, x - 12, y + 1);
			g.setStroke(old);
		}
	}

	/** 폭에 맞춰 글자를 줄여 가운데에 쓴다. */
	static void fitCentered(Graphics2D g, String text, double cx, double baseline, double maxW, double px, boolean bold,
			Color c) {
		double size = px;
		g.setFont(Theme.font(size, bold));
		while (size > 9 && g.getFontMetrics().stringWidth(text) > maxW) {
			size -= 1;
			g.setFont(Theme.font(size, bold));
		}
		g.setColor(c);
		Theme.centered(g, text, cx, baseline);
	}

	// ---- 감도

	private void paintFeel(Graphics2D g, List<Item> items, UserSettings s) {
		int das = valueOf(find(items, "das"), s);
		int arr = valueOf(find(items, "arr"), s);
		paintSlider(g, find(items, "das"), "자동 반복 시작", "DAS", das, "좌우 키를 누르고 있으면 이만큼 지난 뒤부터 저절로 움직여요",
				false);
		paintSlider(g, find(items, "arr"), "반복 간격", "ARR", arr, arr == 0 ? "0이면 가장 빨라요: 벽까지 한 번에 미끄러져요"
				: "저절로 움직이는 간격이에요. 작을수록 빨라요", false);
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		g.drawString(KeyNames.phrase(s.keys().keys(KeyAction.MOVE_RIGHT).get(0)) + "를 누르고 있으면", IN_X, 498);
		paintTimeline(g, das, arr, IN_X, 540, IN_W);
		Item def = find(items, "feelDefault");
		paintButton(g, FEEL_DEFAULT, "기본값으로", def != null, focused(def), hovered(def), false, true);
	}

	/** 끄는 중이면 끄는 값, 아니면 저장된 값. */
	private int valueOf(Item it, UserSettings s) {
		if (it.id.equals(dragId)) {
			return dragValue;
		}
		return it.slider.get().applyAsInt(s);
	}

	private void paintSlider(Graphics2D g, Item it, String title, String tag, int value, String desc, boolean dim) {
		Slider sl = it.slider;
		Rectangle r = it.r;
		boolean foc = focused(it) || it.id.equals(dragId);
		int labelY = r.y - 12;
		label(g, title, r.x, labelY, foc);
		int tx = r.x + g.getFontMetrics(Theme.font(15, true)).stringWidth(title) + 10;
		if (tag != null) {
			Theme.chip(g, tag, tx, labelY - 1, 11, Theme.TEXT_DIM, Theme.withAlpha(Theme.TEXT_DIM, 36));
		}
		g.setColor(Theme.TEXT);
		String v = value + sl.unit();
		// 값은 고정 폭 숫자로 (끌거나 ←→ 로 바꿀 때 글자가 옆으로 흔들리지 않게, SPEC V2)
		int vw = Math.round(Theme.Type.NUMERIC.draw(g, v, 26, r.x + r.width, labelY + 3, Theme.Align.RIGHT));
		if (value == sl.def()) {
			g.setFont(Theme.font(11, true));
			int cw = g.getFontMetrics().stringWidth("기본") + (int) Math.round(11 * 1.2);
			Theme.chip(g, "기본", r.x + r.width - vw - 10 - cw, labelY - 2, 11, Theme.BG_TOP, Theme.withAlpha(Theme.OK, 220));
		}
		// 막대
		double x0 = r.x + 12;
		double x1 = r.x + r.width - 12;
		double cy = r.getCenterY();
		double t = (value - sl.min()) / (double) (sl.max() - sl.min());
		double kx = x0 + t * (x1 - x0);
		g.setColor(Theme.withAlpha(Theme.BORDER, 255));
		g.fill(new RoundRectangle2D.Double(x0, cy - 4, x1 - x0, 8, 8, 8));
		if (dim) { // 음소거: 값은 남아 있지만 지금은 쓰지 않는다
			g.setColor(Theme.withAlpha(Theme.TEXT_FAINT, 170));
		} else {
			g.setPaint(new GradientPaint((float) x0, 0, Theme.P1, (float) x1, 0, Theme.ACCENT_2));
		}
		g.fill(new RoundRectangle2D.Double(x0, cy - 4, Math.max(8, kx - x0), 8, 8, 8));
		// 기본값 눈금
		double dt = (sl.def() - sl.min()) / (double) (sl.max() - sl.min());
		double dx = x0 + dt * (x1 - x0);
		g.setColor(Theme.withAlpha(Theme.TEXT, 200));
		g.fill(new RoundRectangle2D.Double(dx - 1.5, cy - 11, 3, 22, 3, 3));
		g.setFont(Theme.font(12, true));
		g.setColor(Theme.LABEL);
		Theme.centered(g, "기본 " + sl.def(), dx, cy + 28);
		g.setFont(Theme.font(12, false));
		g.drawString(sl.min() + sl.unit(), (float) (x0 - 4), (float) (cy + 28));
		Theme.right(g, sl.max() + sl.unit(), x1 + 4, cy + 28);
		// 손잡이
		if (foc) {
			g.setColor(Theme.withAlpha(Theme.ACCENT, 70));
			g.fill(new Ellipse2D.Double(kx - 17, cy - 17, 34, 34));
		}
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Double(kx - 11, cy - 11, 22, 22));
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2.5f));
		g.setColor(dim ? Theme.TEXT_FAINT : foc ? Theme.ACCENT : Theme.withAlpha(Theme.ACCENT, 200));
		g.draw(new Ellipse2D.Double(kx - 11, cy - 11, 22, 22));
		g.setStroke(old);
		if (desc != null) {
			g.setFont(Theme.font(14, false));
			g.setColor(Theme.TEXT_DIM);
			g.drawString(desc, r.x, (int) (cy + 52));
		}
	}

	/**
	 * 시간 줄: → 를 누르고 있을 때 0.6초 동안 블록이 움직이는 때. 누르자마자 한 칸, DAS 뒤부터 ARR 마다 한 칸(ARR 0 이면 멈춤
	 * 없이 벽까지 미끄러진다).
	 */
	private static void paintTimeline(Graphics2D g, int das, int arr, int x, int y, int w) {
		int span = 600;
		double x0 = x + 8;
		double x1 = x + w - 8;
		double scale = (x1 - x0) / span;
		g.setColor(Theme.withAlpha(Theme.BORDER, 255));
		g.fill(new RoundRectangle2D.Double(x0, y - 1.5, x1 - x0, 3, 3, 3));
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.LABEL);
		for (int ms = 0; ms <= span; ms += 100) {
			double tx = x0 + ms * scale;
			g.fill(new java.awt.geom.Rectangle2D.Double(tx - 0.5, y + 4, 1, 5));
			String label = ms == 0 ? "누름" : String.format(Locale.ROOT, "%.1f초", ms / 1000.0);
			Theme.centered(g, label, tx, y + 24);
		}
		// DAS 기다림 구간
		double dasX = x0 + Math.min(span, das) * scale;
		g.setColor(Theme.withAlpha(Theme.WARN, 60));
		g.fill(new RoundRectangle2D.Double(x0, y - 9, dasX - x0, 18, 9, 9));
		g.setFont(Theme.font(12, true));
		g.setColor(Theme.WARN);
		if (dasX - x0 > 70) {
			Theme.centered(g, das + "ms 기다림", (x0 + dasX) / 2, y - 16);
		}
		int size = 16;
		block(g, x0, y, size); // 누르자마자 한 칸
		if (arr == 0) {
			g.setPaint(new GradientPaint((float) dasX, 0, Theme.withAlpha(Theme.P1, 200), (float) x1, 0,
					Theme.withAlpha(Theme.P1, 40)));
			g.fill(new RoundRectangle2D.Double(dasX, y - 6, x1 - dasX, 12, 12, 12));
			g.setFont(Theme.font(12, true));
			g.setColor(Theme.P1);
			Theme.centered(g, "멈춤 없이 벽까지", Math.min(x1 - 80, dasX + 90), y - 16);
			block(g, dasX, y, size);
			return;
		}
		int count = 0;
		for (int t = das; t <= span; t += arr) {
			block(g, x0 + t * scale, y, size);
			count++;
		}
		if (das + arr <= span) {
			double ax = x0 + das * scale;
			double bx = x0 + (das + arr) * scale;
			g.setFont(Theme.font(12, true));
			g.setColor(Theme.P1);
			String txt = arr + "ms마다";
			if (bx - ax > 46) {
				Theme.centered(g, txt, (ax + bx) / 2, y - 16);
			} else {
				g.drawString(txt, (float) (ax + 2), (float) (y - 16));
			}
		}
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.TEXT_DIM);
		Theme.right(g, "0.6초 동안 " + (count + 1) + "칸", x1, y + 44);
	}

	/** 시간 줄 위의 작은 블록 (가운데가 (cx, cy)). */
	private static void block(Graphics2D g, double cx, double cy, int size) {
		Theme.block(g, (int) Math.round(cx - size / 2.0), (int) Math.round(cy - size / 2.0), size, 1, 255, false);
	}

	// ---- 화면

	private static final String[][] TOGGLE_TEXT = { { "고스트", "떨어질 자리를 옅게 보여 줘요" }, { "색약 색상", "색을 구분하기 쉬운 색 묶음으로 그려요" },
			{ "블록 무늬", "블록마다 글자 모양 무늬를 새겨요" }, { "전체 화면으로 시작", "" } };

	private void paintDisplay(Graphics2D g, List<Item> items, UserSettings s) {
		for (int i = 0; i < DISPLAY_TOGGLES.length; i++) {
			String id = DISPLAY_TOGGLES[i];
			String desc = TOGGLE_TEXT[i][1];
			if (id.equals("fullscreen")) {
				desc = "게임을 켤 때부터 · " + KeyNames.phrase(s.keys().keys(KeyAction.FULLSCREEN).get(0)) + "로 언제든 바꿔요";
			}
			paintToggle(g, find(items, id), TOGGLE_TEXT[i][0], desc, toggleValue(s, id));
		}
		paintPreview(g, IN_X + TOGGLE_W + 22, 250, IN_W - TOGGLE_W - 22, 4 * TOGGLE_H + 30, s);
	}

	/** 켜고 끄는 줄: 카드 안에 이름·설명, 오른쪽에 스위치. */
	private void paintToggle(Graphics2D g, Item it, String title, String desc, boolean on) {
		Rectangle r = it.r;
		boolean foc = focused(it);
		boolean hov = hovered(it);
		Theme.card(g, r.x, r.y, r.width, r.height, 18, foc || hov ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 200),
				hov && !foc ? Theme.TEXT_FAINT : Theme.BORDER);
		if (foc) {
			Theme.glow(g, r.x, r.y, r.width, r.height, 18, Theme.ACCENT, 0.9);
		}
		g.setFont(Theme.font(17, true));
		g.setColor(Theme.TEXT);
		g.drawString(title, r.x + 20, r.y + 31);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString(desc, r.x + 20, r.y + 54);
		paintSwitch(g, r.x + r.width - 76, (int) r.getCenterY() - 15, on);
	}

	/** 스위치: 켜면 강조색 바탕에 손잡이가 오른쪽. */
	static void paintSwitch(Graphics2D g, int x, int y, boolean on) {
		int w = 56;
		int h = 30;
		if (on) {
			g.setPaint(new GradientPaint(x, y, Theme.ACCENT, x + w, y + h, Theme.ACCENT_2));
			g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
		} else {
			Theme.card(g, x, y, w, h, h, Theme.withAlpha(Theme.BORDER, 255), Theme.withAlpha(Theme.TEXT_FAINT, 120));
		}
		double kx = on ? x + w - h + 4 : x + 4;
		g.setColor(on ? Color.WHITE : Theme.TEXT_DIM);
		g.fill(new Ellipse2D.Double(kx, y + 4, h - 8, h - 8));
		g.setFont(Theme.font(11, true));
		g.setColor(on ? Theme.withAlpha(Color.WHITE, 230) : Theme.TEXT_DIM);
		Theme.centered(g, on ? "켬" : "끔", on ? x + 17 : x + w - 16, y + 19);
	}

	/** 미리 보기: 블록 일곱 가지(지금 색과 무늬), 고스트, 모래 네 색. */
	private static void paintPreview(Graphics2D g, int x, int y, int w, int h, UserSettings s) {
		ColorPalettes.Look look = ColorPalettes.look(s);
		Theme.card(g, x, y, w, h, 18, Theme.withAlpha(Theme.BOARD, 235), Theme.BORDER);
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		g.drawString("미리 보기", x + 18, y + 28);
		String tag = look.palette().label() + (look.patterns() ? " · 무늬" : "");
		g.setFont(Theme.font(11, true));
		int tw = g.getFontMetrics().stringWidth(tag) + (int) Math.round(11 * 1.2);
		Theme.chip(g, tag, x + w - 16 - tw, y + 27, 11, s.colorBlind() ? Theme.P1 : Theme.TEXT_DIM,
				Theme.withAlpha(s.colorBlind() ? Theme.P1 : Theme.TEXT_DIM, 36));

		int c = 22;
		PieceType[][] rows = { { PieceType.I, PieceType.O, PieceType.T },
				{ PieceType.S, PieceType.Z, PieceType.J, PieceType.L } };
		int py = y + 48;
		for (PieceType[] row : rows) {
			int total = 0;
			for (PieceType p : row) {
				total += width(p) * c;
			}
			int gap = 16;
			total += gap * (row.length - 1);
			int px = x + (w - total) / 2;
			for (PieceType p : row) {
				piece(g, look, p, px, py, c);
				px += width(p) * c + gap;
			}
			py += 2 * c + 14;
		}

		// 고스트: 방해 줄 위로 떨어지는 T 와 그 고스트
		int by = py + 4;
		int bc = 16;
		int bw = 6 * bc;
		int bh = 7 * bc;
		int bx = x + 26;
		Theme.card(g, bx - 4, by - 4, bw + 8, bh + 8, 8, Theme.BOARD, Theme.BORDER);
		int[][] garbage = { { 5, 0 }, { 5, 1 }, { 5, 2 }, { 5, 3 }, { 5, 5 }, // 방해 줄 두 줄 (구멍 열 4, 2)
				{ 6, 0 }, { 6, 1 }, { 6, 3 }, { 6, 4 }, { 6, 5 } };
		for (int[] gc : garbage) {
			look.block(g, bx + gc[1] * bc, by + gc[0] * bc, bc, ColorPalettes.GARBAGE, 255, false);
		}
		int t = PieceType.T.code();
		int[][] falling = { { 0, 2 }, { 1, 1 }, { 1, 2 }, { 1, 3 } };
		if (s.ghost()) {
			for (int[] fc : falling) {
				look.ghost(g, bx + fc[1] * bc, by + (fc[0] + 3) * bc, bc, t);
			}
		}
		for (int[] fc : falling) {
			look.block(g, bx + fc[1] * bc, by + fc[0] * bc, bc, t, 255, false);
		}
		g.setFont(Theme.font(12, false));
		g.setColor(Theme.LABEL);
		Theme.centered(g, s.ghost() ? "고스트 켬" : "고스트 끔", bx + bw / 2.0, by + bh + 20);

		// 모래 네 색 (알갱이 무늬는 sand.SandColor#pattern)
		int sx = bx + bw + 34;
		int sw = 40; // 모래 블록 한 칸 (알갱이 8 × 8, 한 알갱이 5)
		g.setFont(Theme.font(12, true));
		g.setColor(Theme.LABEL);
		g.drawString("모래", sx, by + 9);
		for (int i = 0; i < SandColor.values().length; i++) {
			SandColor col = SandColor.values()[i];
			int ox = sx + (i % 2) * (sw + 34);
			int oy = by + 20 + (i / 2) * (sw + 28);
			look.sandCell(g, ox, oy, sw, col.code(), 255, col::pattern);
			g.setFont(Theme.font(11, false));
			g.setColor(Theme.TEXT_DIM);
			Theme.centered(g, col.label(), ox + sw / 2.0, oy + sw + 15);
		}
	}

	private static int width(PieceType p) {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		for (int[] cell : PieceState.spawn(p).cells()) {
			min = Math.min(min, cell[1]);
			max = Math.max(max, cell[1]);
		}
		return max - min + 1;
	}

	/** 블록 하나를 나올 때 모양으로 (왼쪽 위가 (x, y)). */
	static void piece(Graphics2D g, ColorPalettes.Look look, PieceType p, int x, int y, int c) {
		int[][] cells = PieceState.spawn(p).cells();
		int minR = Integer.MAX_VALUE;
		int minC = Integer.MAX_VALUE;
		for (int[] cell : cells) {
			minR = Math.min(minR, cell[0]);
			minC = Math.min(minC, cell[1]);
		}
		for (int[] cell : cells) {
			look.block(g, x + (cell[1] - minC) * c, y + (cell[0] - minR) * c, c, p.code(), 255, false);
		}
	}

	// ---- 효과

	private void paintEffects(Graphics2D g, List<Item> items, UserSettings s, long now) {
		Item eff = find(items, "effects");
		Rectangle r = eff.r;
		boolean foc = focused(eff);
		String h = hover;
		boolean hov = hovered(eff) || h != null && h.startsWith("effects:");
		Theme.card(g, r.x, r.y, r.width, r.height, 18, foc || hov ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 200),
				hov && !foc ? Theme.TEXT_FAINT : Theme.BORDER);
		if (foc) {
			Theme.glow(g, r.x, r.y, r.width, r.height, 18, Theme.ACCENT, 0.9);
		}
		g.setFont(Theme.font(17, true));
		g.setColor(Theme.TEXT);
		g.drawString("효과 세기", r.x + 20, r.y + 31);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("줄 지우기·충격 효과의 세기예요", r.x + 20, r.y + 54);
		for (int i = 0; i < EFFECT_NAMES.length; i++) {
			Rectangle sg = effectSegment(i);
			boolean sel = s.effects() == i;
			boolean segHover = ("effects:" + i).equals(h);
			if (sel) {
				g.setPaint(new GradientPaint(sg.x, sg.y, Theme.ACCENT, sg.x + sg.width, sg.y + sg.height, Theme.ACCENT_2));
				g.fill(new RoundRectangle2D.Double(sg.x, sg.y, sg.width, sg.height, sg.height, sg.height));
			} else {
				Theme.card(g, sg.x, sg.y, sg.width, sg.height, sg.height, Theme.withAlpha(Theme.BG_TOP, 220),
						segHover ? Theme.TEXT_DIM : Theme.BORDER);
			}
			g.setFont(Theme.font(13, sel));
			g.setColor(sel ? Color.WHITE : segHover ? Theme.TEXT : Theme.TEXT_DIM);
			Theme.centered(g, EFFECT_NAMES[i], sg.getCenterX(), sg.y + 19);
		}
		paintToggle(g, find(items, "shake"), "화면 흔들림", "큰 줄 지우기·큰 공격·하드드롭 때 보드가 흔들려요", s.shake());
		paintToggle(g, find(items, "calmFlash"), "번쩍임 줄이기", "번쩍이는 효과를 약하게 해요", s.calmFlash());
		SettingsDemo.paint(g, DEMO, s, now);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.LABEL);
		g.drawString("효과를 꺼도 공격 표시·받을 줄 막대·카운트다운은 그대로 보여요", IN_X, 558);
	}

	// ---- AI 속도

	private void paintAi(Graphics2D g, List<Item> items, UserSettings s) {
		Item it = find(items, "aiSpeed");
		int v = valueOf(it, s);
		String what = v == AI_SPEED.def() ? "100%가 기본 속도예요"
				: v < AI_SPEED.def() ? "AI가 블록마다 더 오래 생각해요" : "AI가 블록마다 덜 생각하고 빨리 둬요";
		paintSlider(g, it, "AI 속도", null, v, what + " · 메뉴의 사람 대 AI 카드에서도 바꿀 수 있어요", false);
		if (!levels.isEmpty()) {
			paintLevels(g, IN_X, 382, IN_W, 166, v);
		}
		Item def = find(items, "aiDefault");
		paintButton(g, AI_DEFAULT, "기본값으로", def != null, focused(def), hovered(def), false, true);
	}

	/**
	 * 난이도마다 AI 가 블록 하나를 두는 데 드는 시간 (생각 시간 + 조작 6번, PLAN 7장의 어림). 지금 AI 속도의 값을 크게, 100% 와
	 * 다르면 기본값(100%)을 작게 쓰고, 막대는 초당 블록 수(가장 빠른 단계의 150% 를 끝으로)다.
	 */
	private void paintLevels(Graphics2D g, int x, int y, int w, int h, int speed) {
		Theme.card(g, x, y, w, h, 18, Theme.withAlpha(Theme.BG_TOP, 150), Theme.withAlpha(Theme.BORDER, 200));
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.LABEL);
		g.drawString("블록 하나를 두는 데 드는 시간 (어림)", x + 20, y + 28);
		int n = levels.size();
		double fastest = 0;
		for (AiCatalog.Difficulty d : levels) {
			fastest = Math.max(fastest, 1000.0 / pieceMs(d, UserSettings.MAX_AI_SPEED));
		}
		int colW = (w - 40) / n;
		for (int i = 0; i < n; i++) {
			AiCatalog.Difficulty d = levels.get(i);
			double cx = x + 20 + colW * (i + 0.5);
			g.setFont(Theme.font(15, true));
			g.setColor(Theme.TEXT);
			Theme.centered(g, d.name(), cx, y + 60);
			double ms = pieceMs(d, speed);
			String num = seconds(ms);
			g.setColor(Theme.TEXT);
			float nw = Theme.Type.NUMERIC.width(g, num, 30);
			float uw = Theme.Type.TITLE.width(g, "초", 14);
			double left = cx - (nw + 3 + uw) / 2;
			Theme.Type.NUMERIC.draw(g, num, 30, left, y + 98, Theme.Align.LEFT);
			g.setColor(Theme.TEXT_DIM);
			Theme.Type.TITLE.draw(g, "초", 14, left + nw + 3, y + 98, Theme.Align.LEFT);
			// 초당 블록 수 막대: 지금 값, 흰 눈금은 100%
			double bw = colW - 36;
			double bx = cx - bw / 2;
			double by = y + 114;
			Color col = Theme.mix(Theme.OK, Theme.DANGER, n == 1 ? 0 : i / (double) (n - 1));
			g.setColor(Theme.withAlpha(Theme.BORDER, 255));
			g.fill(new RoundRectangle2D.Double(bx, by, bw, 6, 6, 6));
			g.setColor(col);
			g.fill(new RoundRectangle2D.Double(bx, by, Math.max(6, bw * share(1000.0 / ms, fastest)), 6, 6, 6));
			double base = pieceMs(d, AI_SPEED.def());
			double tick = bx + bw * share(1000.0 / base, fastest);
			g.setColor(Theme.withAlpha(Theme.TEXT, 200));
			g.fill(new RoundRectangle2D.Double(tick - 1, by - 3, 2, 12, 2, 2));
			if (speed != AI_SPEED.def()) {
				g.setFont(Theme.font(12, false));
				g.setColor(Theme.LABEL);
				Theme.centered(g, "기본 " + seconds(base) + "초", cx, y + 146);
			}
		}
	}

	/** AI 속도 {@code percent} 에서 블록 하나에 드는 시간 (ms): 생각 시간 + 6 / 초당 입력. 초당 입력이 없는 묶음이면 무한대. */
	static double pieceMs(AiCatalog.Difficulty d, int percent) {
		AiCatalog.Difficulty a = d.atSpeed(percent);
		return a.thinkMs() + AiCatalog.Difficulty.INPUTS_PER_PIECE * 1000.0 / a.inputsPerSec();
	}

	/** ms → "2.2" (초, 소수 한 자리). 셀 수 없는 값(초당 입력이 없는 모델 묶음)은 "–". */
	static String seconds(double ms) {
		return Double.isFinite(ms) && ms >= 0 ? String.format(Locale.ROOT, "%.1f", ms / 1000.0) : "–";
	}

	/** 막대의 비율 0~1 (셀 수 없으면 0). */
	private static double share(double v, double max) {
		double r = v / max;
		return Double.isFinite(r) ? Math.max(0, Math.min(1, r)) : 0;
	}

	// ---- 소리

	private void paintSound(Graphics2D g, List<Item> items, UserSettings s) {
		Item vol = find(items, "volume");
		int v = valueOf(vol, s);
		paintSlider(g, vol, "효과음 크기", null, v, s.muted() ? "음소거 중이에요. 크기를 바꾸면 음소거가 풀려요"
				: v == 0 ? "0이면 소리가 나지 않아요" : "바꾸면 그 크기로 작은 소리를 들려줘요", s.muted());
		paintToggle(g, find(items, "muted"), "음소거", "켜도 크기는 그대로 남아요", s.muted());
		Item test = find(items, "test");
		Rectangle r = test.r;
		boolean foc = focused(test);
		boolean hov = hovered(test);
		Theme.card(g, r.x, r.y, r.width, r.height, 18, foc || hov ? Theme.SURFACE_HI : Theme.withAlpha(Theme.BG_TOP, 200),
				hov && !foc ? Theme.TEXT_FAINT : Theme.BORDER);
		if (foc) {
			Theme.glow(g, r.x, r.y, r.width, r.height, 18, Theme.ACCENT, 0.9);
		}
		g.setPaint(new GradientPaint(r.x + 20, r.y + 16, Theme.P1, r.x + 60, r.y + 56, Theme.ACCENT_2));
		g.fill(new Ellipse2D.Double(r.x + 20, r.y + 16, 40, 40));
		Path2D.Double play = new Path2D.Double();
		play.moveTo(r.x + 35, r.y + 26);
		play.lineTo(r.x + 49, r.y + 36);
		play.lineTo(r.x + 35, r.y + 46);
		play.closePath();
		g.setColor(Color.WHITE);
		g.fill(play);
		g.setFont(Theme.font(17, true));
		g.setColor(Theme.TEXT);
		g.drawString("소리 들어 보기", r.x + 76, r.y + 31);
		g.setFont(Theme.font(13, false));
		g.setColor(Theme.TEXT_DIM);
		g.drawString("하드드롭과 테트리스 소리", r.x + 76, r.y + 54);

		paintToggle(g, find(items, "heartbeat"), "심장 소리", "위험할 때 심장 소리를 내요", s.heartbeat());

		// 장치 상태 (끈 까닭을 구분한다: 실행 옵션, 장치 없음, 장치가 멈춤 …)
		SoundStatus st = soundStatus(sound.get().offCause(), s.muted(), s.volume());
		Rectangle sr = STATUS;
		Theme.card(g, sr.x, sr.y, sr.width, sr.height, 18, Theme.withAlpha(Theme.BG_TOP, 150),
				Theme.withAlpha(Theme.BORDER, 200));
		g.setColor(st.warn() ? Theme.WARN : st.on() ? Theme.OK : Theme.TEXT_FAINT);
		g.fill(new Ellipse2D.Double(sr.x + 20, sr.y + (st.tail().isEmpty() ? 30 : 20), 12, 12));
		int headY = st.tail().isEmpty() ? sr.y + 41 : sr.y + 31;
		fitLeft(g, st.head(), sr.x + 44, headY, sr.width - 60, 15, true, Theme.TEXT);
		if (!st.tail().isEmpty()) {
			fitLeft(g, st.tail(), sr.x + 44, sr.y + 54, sr.width - 60, 13, false, Theme.TEXT_DIM);
		}
	}

	// ---- 알림과 안내

	private void paintMessage(Graphics2D g, long now) {
		String text;
		Color c;
		float alpha = 1f;
		if (capturing()) {
			text = captureAction.label() + "에 쓸 새 키를 누르세요 · Esc 취소";
			c = Theme.ACCENT;
			Message m = message;
			if (m != null && m.tone() == Tone.WARN && now - m.at() < MESSAGE_MS) {
				text = m.text() + " · 다른 키를 누르세요";
				c = Theme.WARN;
			}
		} else {
			Message m = message;
			if (m == null || now - m.at() >= MESSAGE_MS) {
				return;
			}
			long left = MESSAGE_MS - (now - m.at());
			alpha = left < 600 ? left / 600f : 1f;
			text = m.text();
			c = m.tone() == Tone.WARN ? Theme.WARN : m.tone() == Tone.OK ? Theme.OK : Theme.P1;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		g.setFont(Theme.font(14, true));
		FontMetrics fm = g.getFontMetrics();
		int w = fm.stringWidth(text) + 48;
		int h = 30;
		int x = (W - w) / 2;
		Theme.card(g, x, MSG_Y, w, h, h, Theme.withAlpha(c, 34), Theme.withAlpha(c, 150));
		g.setColor(c);
		g.fill(new Ellipse2D.Double(x + 14, MSG_Y + 11, 8, 8));
		g.setColor(Theme.TEXT);
		g.drawString(text, x + 30, MSG_Y + 20);
		g.setComposite(old);
	}

	/** 화면 아래 안내 줄: 지금 할 수 있는 키 (메뉴의 안내 줄과 같은 모양). */
	private void paintHints(Graphics2D g, Item f) {
		String[][] keys;
		if (capturing()) {
			keys = new String[][] { { "아무 키", "새 키로" }, { "Esc", "취소" } };
		} else if (f != null && (f.slider != null || f.id.equals("effects"))) {
			keys = new String[][] { { "← →", "값 바꾸기" }, { "↑ ↓", "옮기기" }, { "Esc", "메뉴로" } };
		} else if (f != null && f.id.startsWith("key:")) {
			keys = new String[][] { { "Enter", "키 바꾸기" }, { "Delete", "키 빼기" }, { "↑ ↓ ← →", "옮기기" }, { "Esc", "메뉴로" } };
		} else if (f != null && f.step != null) {
			keys = new String[][] { { "Enter", "켜고 끄기" }, { "↑ ↓", "옮기기" }, { "Esc", "메뉴로" } };
		} else {
			keys = new String[][] { { "↑ ↓ ← →", "옮기기" }, { "Enter", "고르기" }, { "Esc", "메뉴로" } };
		}
		paintKeyHints(g, keys, 676);
	}

	/** 키 모양 상자와 설명을 가운데 맞춰 한 줄로 (메뉴의 키 안내와 같은 모양). */
	static void paintKeyHints(Graphics2D g, String[][] keys, int y) {
		g.setFont(Theme.font(13, true));
		FontMetrics fm = g.getFontMetrics();
		int total = 0;
		for (String[] k : keys) {
			total += fm.stringWidth(k[0]) + 16 + 6 + fm.stringWidth(k[1]) + 22;
		}
		total -= 22;
		int x = (W - total) / 2;
		for (String[] k : keys) {
			int kw = fm.stringWidth(k[0]) + 16;
			Theme.card(g, x, y - 17, kw, 24, 8, Theme.SURFACE, Theme.BORDER);
			g.setColor(Theme.TEXT);
			g.drawString(k[0], x + 8, y);
			x += kw + 6;
			g.setColor(Theme.TEXT_DIM);
			g.drawString(k[1], x, y);
			x += fm.stringWidth(k[1]) + 22;
		}
	}
}
