package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.audio.FakeSounds;
import kr.ac.jbnu.se.tetris.audio.Sound;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.MenuMemory;
import kr.ac.jbnu.se.tetris.settings.SaveData;
import kr.ac.jbnu.se.tetris.settings.UserSettings;

/**
 * 설정 화면: 키 바꾸기(기다림, 맞바꿈, 거절, 자동 반복, 빼기, 되돌리기), 메뉴 키로 옮겨 다니기, 막대·스위치, 소리 엔진에 넘기기,
 * 마우스, 두 번 눌러야 하는 버튼, 효과 세기·화면 흔들림·번쩍임 줄이기·심장 소리, AI 속도(메뉴와 같은 값), 효과 미리 보기,
 * 그리기. 창 없이(헤드리스) 돌고 공용 소리 엔진과 사용자 설정 폴더는 건드리지 않는다.
 */
class SettingsPanelTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	/** 패널 하나와 그 주변: 메모리 저장소, 바뀐 설정 기록, 메뉴로 간 횟수, 따로 쓰는 소리 엔진, 멈춘 시계. */
	private static final class Fixture {
		final SaveData data = SaveData.inMemory();
		final List<UserSettings> changes = new ArrayList<>();
		final Sound sound;
		final long[] now = { 5_000_000L };
		final SettingsPanel panel;
		int backs;

		Fixture() {
			this(Sound.silent());
		}

		Fixture(Sound sound) {
			this.sound = sound;
			panel = new SettingsPanel(data, () -> backs++, changes::add, () -> sound);
			panel.clock = () -> now[0];
			panel.setSize(1280, 720);
			panel.open();
		}

		void press(int code) {
			press(code, 0);
		}

		void press(int code, int modifiers) {
			KeyEvent e = new KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
			for (KeyListener l : panel.getKeyListeners()) {
				l.keyPressed(e);
			}
		}

		void release(int code) {
			KeyEvent e = new KeyEvent(panel, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
			for (KeyListener l : panel.getKeyListeners()) {
				l.keyReleased(e);
			}
		}

		void tap(int code) {
			press(code);
			release(code);
		}

		void tap(int code, int times) {
			for (int i = 0; i < times; i++) {
				tap(code);
			}
		}

		void mouse(int id, int x, int y) {
			MouseEvent e = new MouseEvent(panel, id, 0, 0, x, y, 1, false, MouseEvent.BUTTON1);
			for (MouseListener l : panel.getMouseListeners()) {
				if (id == MouseEvent.MOUSE_PRESSED) {
					l.mousePressed(e);
				} else if (id == MouseEvent.MOUSE_RELEASED) {
					l.mouseReleased(e);
				}
			}
			for (MouseMotionListener l : panel.getMouseMotionListeners()) {
				if (id == MouseEvent.MOUSE_MOVED) {
					l.mouseMoved(e);
				} else if (id == MouseEvent.MOUSE_DRAGGED) {
					l.mouseDragged(e);
				}
			}
		}

		void click(int x, int y) {
			mouse(MouseEvent.MOUSE_PRESSED, x, y);
			mouse(MouseEvent.MOUSE_RELEASED, x, y);
		}

		UserSettings settings() {
			return data.settings();
		}

		KeyBindings keys() {
			return data.settings().keys();
		}

		List<Integer> keys(KeyAction a) {
			return keys().keys(a);
		}

		BufferedImage paint(int w, int h) {
			panel.setSize(w, h);
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			panel.printAll(g);
			g.dispose();
			panel.setSize(1280, 720);
			return img;
		}
	}

	// ------------------------------------------------------------------ 처음 값

	@Test
	void defaultsAreTheGameOfToday() {
		// 설정 화면이 생기기 전의 손맛: HumanInput 의 DAS·ARR, 고스트 켬, 지금 키 (KeyBindingsTest), 창으로 시작
		assertEquals(HumanInput.DAS_MS, UserSettings.DEFAULT_DAS_MS);
		assertEquals(HumanInput.ARR_MS, UserSettings.DEFAULT_ARR_MS);
		Fixture f = new Fixture();
		assertEquals(UserSettings.defaults(), f.settings(), "열기만 해서는 아무것도 바꾸지 않는다");
		assertTrue(f.changes.isEmpty());
		assertEquals("tab:CONTROLS", f.panel.focusId(), "처음에는 조작 카드에 초점");
		assertFalse(f.panel.capturing());
	}

	@Test
	void paintsEverySectionAndStateAtBothSizes() {
		Fixture f = new Fixture();
		for (SettingsPanel.Section s : SettingsPanel.Section.values()) {
			f.panel.show(s);
			for (int[] wh : new int[][] { { 1280, 720 }, { 1920, 1080 }, { 960, 540 } }) {
				BufferedImage img = f.paint(wh[0], wh[1]);
				assertTrue(colours(img) > 20, s + " " + wh[0] + ": 그림이 그려졌다");
			}
		}
		// 기다림, 거절 알림, 긴 키 이름, 맞바꾼 줄의 반짝임
		f.panel.show(SettingsPanel.Section.CONTROLS);
		assertTrue(f.panel.focus("key:hard_drop:1"));
		f.tap(KeyEvent.VK_ENTER);
		f.paint(1280, 720);
		f.tap(KeyEvent.VK_PAGE_DOWN);
		assertTrue(f.panel.focus("key:hold:0"));
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_SCROLL_LOCK);
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_WINDOWS);
		f.paint(1280, 720);
		f.tap(KeyEvent.VK_ESCAPE);
		f.now[0] += 200;
		f.paint(1920, 1080);
		assertEquals(List.of(KeyEvent.VK_SPACE, KeyEvent.VK_PAGE_DOWN), f.keys(KeyAction.HARD_DROP));
		assertEquals(List.of(KeyEvent.VK_SCROLL_LOCK, KeyEvent.VK_SHIFT), f.keys(KeyAction.HOLD));
	}

	// ------------------------------------------------------------------ 키 바꾸기

	@Test
	void enterWaitsForANewKeyThenSavesIt() {
		Fixture f = new Fixture();
		assertTrue(f.panel.focus("key:hard_drop:0"));
		f.tap(KeyEvent.VK_ENTER);
		assertTrue(f.panel.capturing());
		assertTrue(f.panel.takesKey(KeyEvent.VK_F11), "기다리는 동안에는 전체 화면 키도 이 화면이 받는다");
		f.tap(KeyEvent.VK_W);
		assertFalse(f.panel.capturing());
		assertEquals(List.of(KeyEvent.VK_W), f.keys(KeyAction.HARD_DROP));
		assertEquals(1, f.changes.size(), "한 번 저장하고 알린다");
		assertEquals(f.settings(), f.changes.get(0));
		assertEquals("key:hard_drop:0", f.panel.focusId());
		assertNotNull(f.panel.messageText());
		assertTrue(f.panel.messageText().contains("하드드롭"), f.panel.messageText());
		assertFalse(f.panel.takesKey(KeyEvent.VK_F11));
		assertTrue(f.panel.takesKey(KeyEvent.VK_DELETE), "이 화면은 Delete 로 키를 뺀다");
	}

	@Test
	void choosingAKeyOfAnotherActionSwapsAndSaysSo() {
		Fixture f = new Fixture();
		f.panel.focus("key:hard_drop:0");
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_C); // 홀드의 키
		assertEquals(List.of(KeyEvent.VK_C), f.keys(KeyAction.HARD_DROP));
		assertEquals(List.of(KeyEvent.VK_SPACE, KeyEvent.VK_SHIFT), f.keys(KeyAction.HOLD), "홀드는 하드드롭이 내놓은 Space 를 받는다");
		assertEquals("홀드 키는 이제 Space / Shift", f.panel.messageText());
		assertNull(KeyBindings.problem(f.keys().asMap()), "언제나 한 키는 한 동작에만");
	}

	@Test
	void unusableKeyIsRefusedAndTheWaitGoesOn() {
		Fixture f = new Fixture();
		f.panel.focus("key:fullscreen:0");
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_ALT);
		assertTrue(f.panel.capturing(), "거절해도 계속 기다린다");
		assertEquals("Alt 키는 쓸 수 없어요", f.panel.messageText());
		f.tap(KeyEvent.VK_TAB); // 전체 화면에는 메뉴 키를 쓸 수 없다
		assertTrue(f.panel.capturing());
		assertEquals("Tab 키는 전체 화면에 쓸 수 없어요", f.panel.messageText());
		f.tap(KeyEvent.VK_ESCAPE); // 취소: 메뉴로 가지 않는다
		assertFalse(f.panel.capturing());
		assertEquals(0, f.backs);
		assertEquals(KeyBindings.defaults(), f.keys());
		assertTrue(f.changes.isEmpty());
	}

	@Test
	void autoRepeatOfTheEnterThatStartedTheWaitIsIgnored() {
		Fixture f = new Fixture();
		f.panel.focus("key:hard_drop:0");
		f.press(KeyEvent.VK_ENTER); // 누른 채로
		assertTrue(f.panel.capturing());
		f.press(KeyEvent.VK_ENTER); // 운영체제의 자동 반복
		f.press(KeyEvent.VK_ENTER);
		assertTrue(f.panel.capturing(), "누르고 있던 Enter 는 새 키가 아니다");
		assertEquals(KeyBindings.defaults(), f.keys());
		f.release(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_ENTER); // 떼었다 다시 누르면 그 키로 묶는다 (다시 하기의 Enter 와 맞바꿈)
		assertEquals(List.of(KeyEvent.VK_ENTER), f.keys(KeyAction.HARD_DROP));
		assertEquals(List.of(KeyEvent.VK_SPACE, KeyEvent.VK_R), f.keys(KeyAction.RETRY));
	}

	@Test
	void addingASecondKeyAndRemovingKeys() {
		Fixture f = new Fixture();
		assertTrue(f.panel.focus("key:hard_drop:1"), "키가 셋보다 적으면 더할 칸이 있다");
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_W);
		assertEquals(List.of(KeyEvent.VK_SPACE, KeyEvent.VK_W), f.keys(KeyAction.HARD_DROP));
		assertTrue(f.panel.focus("key:hard_drop:0"));
		f.tap(KeyEvent.VK_DELETE);
		assertEquals(List.of(KeyEvent.VK_W), f.keys(KeyAction.HARD_DROP));
		f.tap(KeyEvent.VK_BACK_SPACE); // 하나뿐인 키는 빼지 않는다
		assertEquals(List.of(KeyEvent.VK_W), f.keys(KeyAction.HARD_DROP));
		assertEquals("하드드롭에는 키가 하나는 있어야 해요", f.panel.messageText());
		// 세 칸이 다 차면 더할 칸이 없다
		f.panel.focus("key:rotate_cw:2");
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_E);
		assertEquals(List.of(KeyEvent.VK_UP, KeyEvent.VK_X, KeyEvent.VK_E), f.keys(KeyAction.ROTATE_CW));
		assertFalse(f.panel.focus("key:rotate_cw:3"));
	}

	@Test
	void resetOneActionAndAllKeysWithConfirmation() {
		Fixture f = new Fixture();
		assertFalse(f.panel.focus("keyReset:hard_drop"), "처음 키면 되돌리기 버튼이 없다");
		assertFalse(f.panel.focus("keysDefault"));
		f.panel.focus("key:hard_drop:0");
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_C);
		assertTrue(f.panel.focus("keyReset:hard_drop"));
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(List.of(KeyEvent.VK_SPACE), f.keys(KeyAction.HARD_DROP));
		assertEquals(KeyBindings.defaults(), f.keys(), "되돌리면서 홀드도 C 를 돌려받는다");
		assertFalse(f.panel.focus("keyReset:hard_drop"));

		f.panel.focus("key:pause:0");
		f.tap(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_Q);
		assertTrue(f.panel.focus("keysDefault"));
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(List.of(KeyEvent.VK_Q), f.keys(KeyAction.PAUSE), "한 번은 묻기만 한다");
		f.now[0] += SettingsPanel.CONFIRM_MS + 1;
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(List.of(KeyEvent.VK_Q), f.keys(KeyAction.PAUSE), "시간이 지나면 다시 묻는다");
		f.now[0] += 100;
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(KeyBindings.defaults(), f.keys());
	}

	@Test
	void focusLostCancelsTheWait() {
		Fixture f = new Fixture();
		f.panel.focus("key:hold:0");
		f.tap(KeyEvent.VK_ENTER);
		assertTrue(f.panel.capturing());
		FocusEvent lost = new FocusEvent(f.panel, FocusEvent.FOCUS_LOST, true);
		for (FocusListener l : f.panel.getFocusListeners()) {
			l.focusLost(lost);
		}
		assertFalse(f.panel.capturing());
		f.tap(KeyEvent.VK_W);
		assertEquals(KeyBindings.defaults(), f.keys());
	}

	// ------------------------------------------------------------------ 옮겨 다니기

	@Test
	void arrowsMoveBetweenTabsRowsAndColumns() {
		Fixture f = new Fixture();
		f.tap(KeyEvent.VK_RIGHT);
		assertEquals("tab:FEEL", f.panel.focusId());
		assertEquals(SettingsPanel.Section.FEEL, f.panel.section(), "카드에 초점이 가면 그 갈래가 보인다");
		f.tap(KeyEvent.VK_LEFT);
		assertEquals(SettingsPanel.Section.CONTROLS, f.panel.section());
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("key:move_left:0", f.panel.focusId());
		f.tap(KeyEvent.VK_DOWN, 3);
		assertEquals("key:hard_drop:0", f.panel.focusId());
		f.tap(KeyEvent.VK_RIGHT);
		assertEquals("key:hard_drop:1", f.panel.focusId(), "← → 는 칸 사이");
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("key:rotate_cw:1", f.panel.focusId(), "↓ 는 같은 칸의 아래 줄");
		f.tap(KeyEvent.VK_RIGHT, 2);
		assertTrue(f.panel.focusId().startsWith("key:") && f.panel.focusId().contains(":0"),
				"오른쪽 끝에서 → 는 화면 조작 열로: " + f.panel.focusId());
		f.panel.focus("key:hold:0");
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("key:hold:0", f.panel.focusId(), "아래 끝에서는 그대로");
		f.press(KeyEvent.VK_TAB, InputEvent.SHIFT_DOWN_MASK);
		f.release(KeyEvent.VK_TAB);
		assertEquals("key:rotate_ccw:0", f.panel.focusId(), "Shift+Tab 은 위로");
		f.tap(KeyEvent.VK_TAB);
		assertEquals("key:hold:0", f.panel.focusId(), "Tab 은 아래로");
		f.panel.focus("key:pause:0"); // 오른쪽 열 위의 카드는 '화면' 이지만, 지금 갈래의 카드로 간다
		f.tap(KeyEvent.VK_UP);
		assertEquals("tab:CONTROLS", f.panel.focusId());
		assertEquals(SettingsPanel.Section.CONTROLS, f.panel.section());
		f.tap(KeyEvent.VK_RIGHT, 3);
		f.tap(KeyEvent.VK_UP);
		assertEquals("resetAll", f.panel.focusId(), "오른쪽 카드 위는 모두 기본값으로");
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("tab:EFFECTS", f.panel.focusId(), "위 버튼에서 내려오면 지금 갈래의 카드");
		f.tap(KeyEvent.VK_LEFT, 3);
		f.panel.focus("key:move_left:0");
		f.tap(KeyEvent.VK_UP);
		assertEquals("tab:CONTROLS", f.panel.focusId());
		f.tap(KeyEvent.VK_UP);
		assertEquals("back", f.panel.focusId(), "카드 위는 메뉴 버튼");
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(1, f.backs);
	}

	@Test
	void enterOnATabGoesIntoTheSection() {
		Fixture f = new Fixture();
		f.panel.show(SettingsPanel.Section.DISPLAY);
		f.tap(KeyEvent.VK_ENTER);
		assertEquals("ghost", f.panel.focusId());
		f.panel.show(SettingsPanel.Section.SOUND);
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("volume", f.panel.focusId());
	}

	@Test
	void theEnterThatOpenedThisScreenIsNotANewPress() {
		// 메뉴에서 Enter 로 설정을 열고 계속 누르고 있다: 그 자동 반복으로 갈래 안에 들어가지 않는다
		Fixture f = new Fixture();
		f.panel.heldKeys(java.util.Set.of(KeyEvent.VK_ENTER));
		f.press(KeyEvent.VK_ENTER);
		f.press(KeyEvent.VK_ENTER);
		assertEquals("tab:CONTROLS", f.panel.focusId());
		f.release(KeyEvent.VK_ENTER);
		f.tap(KeyEvent.VK_ENTER); // 떼었다 다시 누르면 받는다
		assertEquals("key:move_left:0", f.panel.focusId());
	}

	@Test
	void escapeGoesBackOnceEvenWhenHeld() {
		Fixture f = new Fixture();
		f.press(KeyEvent.VK_ESCAPE);
		f.press(KeyEvent.VK_ESCAPE); // 자동 반복
		assertEquals(1, f.backs);
		f.release(KeyEvent.VK_ESCAPE);
		f.tap(KeyEvent.VK_ESCAPE);
		assertEquals(2, f.backs);
	}

	// ------------------------------------------------------------------ 막대와 스위치

	@Test
	void dasAndArrStepWithinTheirRanges() {
		Fixture f = new Fixture();
		f.panel.show(SettingsPanel.Section.FEEL);
		assertTrue(f.panel.focus("das"));
		assertFalse(f.panel.focus("feelDefault"), "처음 값이면 되돌리기 버튼이 없다");
		f.tap(KeyEvent.VK_RIGHT);
		assertEquals(UserSettings.DEFAULT_DAS_MS + UserSettings.DAS_STEP_MS, f.settings().dasMs());
		f.tap(KeyEvent.VK_LEFT, 100);
		assertEquals(UserSettings.MIN_DAS_MS, f.settings().dasMs(), "끝에서 멈춘다");
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("arr", f.panel.focusId());
		f.tap(KeyEvent.VK_RIGHT, 100);
		assertEquals(UserSettings.MAX_ARR_MS, f.settings().arrMs());
		f.tap(KeyEvent.VK_LEFT, 100);
		assertEquals(UserSettings.MIN_ARR_MS, f.settings().arrMs());
		assertEquals(0, f.settings().arrMs() % UserSettings.ARR_STEP_MS);
		assertTrue(f.panel.focus("feelDefault"));
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(UserSettings.DEFAULT_DAS_MS, f.settings().dasMs());
		assertEquals(UserSettings.DEFAULT_ARR_MS, f.settings().arrMs());
	}

	@Test
	void displayTogglesFlipAndSave() {
		Fixture f = new Fixture();
		f.panel.show(SettingsPanel.Section.DISPLAY);
		f.panel.focus("ghost");
		f.tap(KeyEvent.VK_ENTER);
		assertFalse(f.settings().ghost());
		f.tap(KeyEvent.VK_LEFT); // 이미 끔
		assertFalse(f.settings().ghost());
		f.tap(KeyEvent.VK_RIGHT);
		assertTrue(f.settings().ghost());
		f.tap(KeyEvent.VK_DOWN);
		f.tap(KeyEvent.VK_SPACE);
		assertTrue(f.settings().colorBlind());
		f.tap(KeyEvent.VK_DOWN);
		f.tap(KeyEvent.VK_RIGHT);
		assertTrue(f.settings().patterns());
		f.tap(KeyEvent.VK_DOWN);
		f.tap(KeyEvent.VK_ENTER);
		assertTrue(f.settings().fullscreenOnStart());
		assertEquals(5, f.changes.size(), "바뀐 만큼만 알린다");
		assertEquals(UserSettings.defaults().withColorBlind(true).withPatterns(true).withFullscreenOnStart(true),
				f.settings());
	}

	@Test
	void soundSettingsGoToTheEngine() {
		Fixture f = new Fixture();
		f.panel.show(SettingsPanel.Section.SOUND);
		f.panel.focus("volume");
		f.tap(KeyEvent.VK_RIGHT);
		assertEquals(80, f.settings().volume());
		assertEquals(0.8, f.sound.volume(), 1e-9);
		f.tap(KeyEvent.VK_LEFT, 3);
		assertEquals(50, f.settings().volume());
		assertEquals(0.5, f.sound.volume(), 1e-9);
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("muted", f.panel.focusId());
		f.tap(KeyEvent.VK_ENTER);
		assertTrue(f.settings().muted());
		assertTrue(f.sound.muted());
		assertEquals(50, f.settings().volume(), "음소거해도 크기는 남는다");
		f.panel.focus("volume");
		f.tap(KeyEvent.VK_RIGHT); // 크기를 바꾸면 음소거가 풀린다
		assertFalse(f.settings().muted());
		assertFalse(f.sound.muted());
		assertEquals(60, f.settings().volume());
		f.panel.focus("test");
		f.tap(KeyEvent.VK_ENTER); // 소리를 쓰지 않는 엔진(시험): 소리 대신 그 까닭을 알린다
		assertEquals("소리를 쓰지 않는 실행이에요", f.panel.messageText());
	}

	@Test
	void theTestButtonTellsWhenThereIsNoDevice() {
		Sound none = FakeSounds.noDevice();
		Sound ready = FakeSounds.ready();
		try {
			Fixture f = new Fixture(none);
			f.panel.show(SettingsPanel.Section.SOUND);
			f.panel.focus("test");
			f.tap(KeyEvent.VK_ENTER);
			assertEquals("소리 장치를 찾지 못했어요", f.panel.messageText());
			f.paint(1280, 720);

			Fixture g = new Fixture(ready);
			g.panel.show(SettingsPanel.Section.SOUND);
			g.panel.focus("test");
			assertEquals(Sound.State.READY, ready.state(), "흉내 엔진은 소리를 낼 수 있다");
			g.tap(KeyEvent.VK_ENTER);
			assertNull(g.panel.messageText(), "소리가 나면 알림 없이 들려준다");
			g.paint(1280, 720);
		} finally {
			none.close();
			ready.close();
		}
	}

	@Test
	void theSoundLineSaysWhyThereIsNoSound() {
		// 실행 옵션으로 끈 것을 "장치를 찾지 못했어요" 라고 하지 않는다
		assertEquals("실행 옵션으로 소리를 꺼 두었어요", SettingsPanel.soundStatus(Sound.OffCause.OPTION, false, 70).head());
		assertEquals("소리를 쓰지 않는 실행이에요", SettingsPanel.soundStatus(Sound.OffCause.UNUSED, false, 70).head());
		SettingsPanel.SoundStatus noDevice = SettingsPanel.soundStatus(Sound.OffCause.NO_DEVICE, false, 70);
		assertEquals("소리 장치를 찾지 못했어요", noDevice.head());
		assertTrue(noDevice.warn());
		assertEquals("소리 장치가 응답하지 않아요", SettingsPanel.soundStatus(Sound.OffCause.DEVICE_LOST, false, 70).head());
		assertEquals("효과음을 만들지 못했어요", SettingsPanel.soundStatus(Sound.OffCause.BROKEN, false, 70).head());
		SettingsPanel.SoundStatus on = SettingsPanel.soundStatus(Sound.OffCause.NONE, false, 70);
		assertEquals("효과음이 켜져 있어요", on.head());
		assertTrue(on.on());
		assertEquals("", on.tail(), "켜져 있으면 덧붙일 말이 없다");
		assertEquals("음소거를 끄면 들려요", SettingsPanel.soundStatus(Sound.OffCause.NONE, true, 70).tail());
		assertEquals("크기를 올리면 들려요", SettingsPanel.soundStatus(Sound.OffCause.NONE, false, 0).tail());
		for (Sound.OffCause c : Sound.OffCause.values()) {
			SettingsPanel.SoundStatus st = SettingsPanel.soundStatus(c, false, 70);
			assertFalse(st.head().isBlank(), c.name());
			assertFalse(st.on() && st.warn(), c.name());
		}
	}

	@Test
	void theSubtitleSaysWhenChangesAreNotKept(@TempDir Path dir) {
		Fixture memory = new Fixture(); // 파일 없이: -Dtetris.config=off 와 같다
		assertEquals("이번 실행 동안만 바뀌어요", memory.panel.subtitle());
		SettingsPanel onDisk = new SettingsPanel(SaveData.open(dir), () -> {
		}, s -> {
		}, Sound::silent);
		assertEquals("바꾸면 바로 저장돼요", onDisk.subtitle());
	}

	@Test
	void resetAllNeedsTwoPressesAndKeepsTheMenuMemory() {
		Fixture f = new Fixture();
		MenuMemory menu = MenuMemory.EMPTY.with(MenuMemory.MODE, MenuMemory.MODE_SOLO);
		f.data.updateSettings(s -> s.withGhost(false).withVolume(30).withDasMs(100).withMenu(menu));
		assertTrue(f.panel.focus("resetAll"));
		f.tap(KeyEvent.VK_ENTER);
		assertFalse(f.settings().ghost(), "한 번은 묻기만 한다");
		assertEquals("한 번 더 누르면 모든 설정을 기본값으로 돌려요", f.panel.messageText());
		f.tap(KeyEvent.VK_ENTER);
		assertEquals(UserSettings.defaults().withMenu(menu), f.settings());
		assertEquals(UserSettings.DEFAULT_VOLUME / 100.0, f.sound.volume(), 1e-9, "소리 크기도 엔진에 넘긴다");
	}

	@Test
	void onTheEffectsTabOnlyThePreviewIsRedrawnWhileNothingElseMoves() {
		Fixture f = new Fixture();
		assertFalse(f.panel.previewOnly(), "조작 갈래에는 미리 보기가 없다");
		f.panel.show(SettingsPanel.Section.EFFECTS);
		assertTrue(f.panel.previewOnly());
		assertEquals(new java.awt.Rectangle(666, 242, 374, 292), f.panel.previewArea(), "미리 보기 카드 + 여유 8 화소");
		f.panel.setSize(1920, 1080);
		assertEquals(new java.awt.Rectangle(1003, 367, 553, 430), f.panel.previewArea(), "창 배율을 따른다");
		f.panel.setSize(1280, 720);
		assertTrue(f.panel.focus("resetAll"));
		f.tap(KeyEvent.VK_ENTER); // 확인을 묻는 알림이 뜬다: 화면 전체를 다시 그린다
		assertFalse(f.panel.previewOnly());
		f.now[0] += SettingsPanel.MESSAGE_MS;
		assertTrue(f.panel.previewOnly(), "알림이 사라지면 다시 미리 보기만");
	}

	@Test
	void messagesFadeAway() {
		Fixture f = new Fixture();
		f.panel.focus("key:hard_drop:0");
		f.tap(KeyEvent.VK_DELETE);
		assertNotNull(f.panel.messageText());
		f.now[0] += SettingsPanel.MESSAGE_MS;
		assertNull(f.panel.messageText());
	}

	// ------------------------------------------------------------------ 마우스

	@Test
	void mouseClicksTabsTogglesKeysAndDragsSliders() {
		Fixture f = new Fixture();
		// 갈래 카드: 화면 (셋째 카드의 가운데)
		f.click(tabCenter(SettingsPanel.Section.DISPLAY), 189);
		assertEquals(SettingsPanel.Section.DISPLAY, f.panel.section());
		f.click(248 + 200, 250 + 36); // 고스트 줄
		assertFalse(f.settings().ghost());
		// 조작: 키를 눌러 기다리고, 빈 곳을 눌러 취소
		f.click(248 + 90, 189);
		assertEquals(SettingsPanel.Section.CONTROLS, f.panel.section());
		f.click(248 + 116 + 30, 280 + 3 * 44 + 15); // 하드드롭 첫 칸
		assertTrue(f.panel.capturing());
		f.click(640, 700);
		assertFalse(f.panel.capturing());
		// 키 위에 마우스를 올리면 × 가 생기고, 누르면 그 키를 뺀다
		int shiftX = 248 + 116 + 74 + 30;
		int holdY = 280 + 6 * 44 + 15;
		f.mouse(MouseEvent.MOUSE_MOVED, shiftX, holdY);
		f.click(248 + 116 + 74 + 68 - 3, 280 + 6 * 44 + 2);
		assertEquals(List.of(KeyEvent.VK_C), f.keys(KeyAction.HOLD));
		// 소리 크기 막대를 끌어 놓으면 그 값으로 저장 (끄는 동안에는 저장하지 않는다)
		f.click(tabCenter(SettingsPanel.Section.SOUND), 189);
		assertEquals(SettingsPanel.Section.SOUND, f.panel.section());
		int x0 = 248 + 12;
		int width = 784 - 24;
		f.mouse(MouseEvent.MOUSE_PRESSED, x0 + width / 10, 295);
		f.mouse(MouseEvent.MOUSE_DRAGGED, x0 + width * 3 / 10, 295);
		assertEquals(UserSettings.DEFAULT_VOLUME, f.settings().volume());
		f.mouse(MouseEvent.MOUSE_RELEASED, x0 + width * 3 / 10, 295);
		assertEquals(30, f.settings().volume());
		assertEquals(0.3, f.sound.volume(), 1e-9);
	}

	@Test
	void aDragWhoseReleaseWasLostIsDropped() {
		// 막대를 끄는 중에 Alt+Tab: 마우스 뗌이 오지 않는다. 다음에 어디를 누르고 떼도 끄던 값이 저장되지 않는다
		Fixture f = new Fixture();
		f.panel.show(SettingsPanel.Section.SOUND);
		int x0 = 248 + 12;
		int width = 784 - 24;
		f.mouse(MouseEvent.MOUSE_PRESSED, x0 + width / 10, 295);
		f.mouse(MouseEvent.MOUSE_DRAGGED, x0 + width / 2, 295);
		FocusEvent lost = new FocusEvent(f.panel, FocusEvent.FOCUS_LOST, false);
		for (FocusListener l : f.panel.getFocusListeners()) {
			l.focusLost(lost);
		}
		f.mouse(MouseEvent.MOUSE_RELEASED, 640, 700); // 돌아와서 늦게 온 뗌
		f.click(640, 700); // 빈 곳
		assertEquals(UserSettings.DEFAULT_VOLUME, f.settings().volume(), "놓친 끌기의 값은 저장하지 않는다");
		assertTrue(f.changes.isEmpty());

		// 포커스를 잃지 않고 뗌만 잃었어도(창 밖에서 뗌) 새 누름이 지난 끌기를 끝낸다
		f.mouse(MouseEvent.MOUSE_PRESSED, x0 + width / 10, 295);
		f.mouse(MouseEvent.MOUSE_DRAGGED, x0 + width / 2, 295);
		f.click(248 + 404 + 100, 380 + 36); // 소리 들어 보기 카드
		assertEquals(UserSettings.DEFAULT_VOLUME, f.settings().volume());
		assertTrue(f.changes.isEmpty());
	}

	@Test
	void worksAtAnyWindowSize() {
		Fixture f = new Fixture();
		f.panel.setSize(1920, 1080); // 1.5 배: 가상 화면 좌표 × 1.5
		f.click((int) (tabCenter(SettingsPanel.Section.DISPLAY) * 1.5), (int) (189 * 1.5));
		assertEquals(SettingsPanel.Section.DISPLAY, f.panel.section());
		f.panel.setSize(1000, 1000); // 가로에 맞추고 위아래는 가운데
		double scale = 1000 / 1280.0;
		double top = (1000 - 720 * scale) / 2;
		f.click((int) ((248 + 90) * scale), (int) (top + 189 * scale));
		assertEquals(SettingsPanel.Section.CONTROLS, f.panel.section());
	}

	// ------------------------------------------------------------------ 효과, 심장 소리, AI 속도 (SPEC V15, AI 속도)

	@Test
	void effectRowsStepToggleAndSave() {
		Fixture f = new Fixture();
		f.panel.show(SettingsPanel.Section.EFFECTS);
		f.tap(KeyEvent.VK_ENTER);
		assertEquals("effects", f.panel.focusId(), "갈래에서 Enter: 효과 세기");
		assertEquals(UserSettings.EFFECTS_NORMAL, f.settings().effects(), "처음은 보통");
		f.tap(KeyEvent.VK_RIGHT);
		assertEquals(UserSettings.EFFECTS_HIGH, f.settings().effects());
		f.tap(KeyEvent.VK_RIGHT); // 끝
		assertEquals(UserSettings.EFFECTS_HIGH, f.settings().effects());
		f.tap(KeyEvent.VK_LEFT, 5);
		assertEquals(UserSettings.EFFECTS_OFF, f.settings().effects());
		f.tap(KeyEvent.VK_ENTER); // Enter 는 다음 세기
		assertEquals(UserSettings.EFFECTS_LOW, f.settings().effects());
		// 마우스로 칸 누르기 (넷째 칸 = 강하게)
		java.awt.Rectangle seg = SettingsPanel.effectSegment(3);
		f.click((int) seg.getCenterX(), (int) seg.getCenterY());
		assertEquals(UserSettings.EFFECTS_HIGH, f.settings().effects());
		assertEquals("effects", f.panel.focusId());
		f.click(SettingsPanel.EFFECTS_CARD.x + 120, SettingsPanel.EFFECTS_CARD.y + 25); // 카드의 제목 자리: 초점만
		assertEquals(UserSettings.EFFECTS_HIGH, f.settings().effects(), "칸 밖을 눌러서는 바뀌지 않는다");
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("shake", f.panel.focusId());
		f.tap(KeyEvent.VK_ENTER);
		assertFalse(f.settings().shake());
		f.tap(KeyEvent.VK_DOWN);
		assertEquals("calmFlash", f.panel.focusId());
		f.tap(KeyEvent.VK_RIGHT);
		assertTrue(f.settings().calmFlash());
		assertEquals(UserSettings.defaults().withEffects(UserSettings.EFFECTS_HIGH).withShake(false).withCalmFlash(true),
				f.settings());
		f.paint(1280, 720);

		f.panel.show(SettingsPanel.Section.SOUND);
		assertTrue(f.panel.focus("heartbeat"));
		f.tap(KeyEvent.VK_ENTER);
		assertFalse(f.settings().heartbeat(), "심장 소리 끔");
		f.tap(KeyEvent.VK_UP);
		assertEquals("muted", f.panel.focusId(), "심장 소리 위는 음소거");
		f.paint(1280, 720);
	}

	@Test
	void aiSpeedIsTheSameValueAsTheMenus() {
		SaveData data = SaveData.inMemory();
		List<UserSettings> changes = new ArrayList<>();
		SettingsPanel panel = new SettingsPanel(data, () -> {
		}, changes::add, Sound::silent, AiCatalog.load(Path.of("models")).difficulties());
		panel.setSize(1280, 720);
		panel.open();
		panel.show(SettingsPanel.Section.AI);
		assertFalse(panel.focus("aiDefault"), "처음 값이면 되돌리기 단추가 없다");
		assertTrue(panel.focus("aiSpeed"));
		key(panel, KeyEvent.VK_LEFT);
		key(panel, KeyEvent.VK_LEFT);
		key(panel, KeyEvent.VK_LEFT);
		assertEquals(70, data.settings().aiSpeed(), "10 씩, 바로 저장");
		assertEquals(3, changes.size());
		for (int i = 0; i < 10; i++) {
			key(panel, KeyEvent.VK_LEFT);
		}
		assertEquals(UserSettings.MIN_AI_SPEED, data.settings().aiSpeed());
		paint(panel);
		assertTrue(panel.focus("aiDefault"));
		key(panel, KeyEvent.VK_ENTER);
		assertEquals(UserSettings.DEFAULT_AI_SPEED, data.settings().aiSpeed());
		// 메뉴가 바꾼 값이 그대로 보인다(같은 저장 값)
		data.updateSettings(u -> u.withAiSpeed(140));
		paint(panel);
		// 블록 하나에 드는 시간: 100% 는 난이도 그대로, 느리게 하면 길어진다 (중급 1150 + 6000/6 = 2150ms)
		AiCatalog.Difficulty mid = AiCatalog.load(Path.of("models")).difficulties().get(1);
		assertEquals(2150, SettingsPanel.pieceMs(mid, 100), 1e-6);
		assertEquals(4300, SettingsPanel.pieceMs(mid, 50), 1e-6);
		assertTrue(SettingsPanel.pieceMs(mid, 150) < SettingsPanel.pieceMs(mid, 100));
	}

	@Test
	void theEffectsPreviewFollowsStrengthAndCalmFlash() {
		// 줄을 지우는 순간(40ms) 맨 아래 줄의 밝기: 강하게 > 번쩍임 줄이기 > 끔
		java.awt.Rectangle r = new java.awt.Rectangle(0, 0, 362, 276);
		UserSettings high = UserSettings.defaults().withEffects(UserSettings.EFFECTS_HIGH);
		double strong = rowBrightness(r, high, 40);
		double calm = rowBrightness(r, high.withCalmFlash(true), 40);
		double off = rowBrightness(r, high.withEffects(UserSettings.EFFECTS_OFF), 40);
		assertTrue(strong > calm + 10, strong + " > " + calm);
		assertTrue(calm > off + 5, calm + " > " + off);
		// 같은 시각이면 같은 그림 (상태가 없다)
		assertEquals(rowBrightness(r, high, 1234), rowBrightness(r, high, 1234 + SettingsDemo.CYCLE_MS), 1e-9);
	}

	/** 미리 보기 판에서 지우는 두 줄 자리의 평균 밝기. */
	private static double rowBrightness(java.awt.Rectangle r, UserSettings s, long now) {
		BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		SettingsDemo.paint(g, r, s, now);
		g.dispose();
		// 판: 가로 6 × 28 을 가운데 (+6), 위 끝 52, 지우는 줄은 둘째 줄 아래부터 두 줄
		int wx = (r.width - 168) / 2 + 6;
		int wy = 52 + 2 * 28;
		long sum = 0;
		int n = 0;
		for (int y = wy + 3; y < wy + 53; y++) {
			for (int x = wx + 3; x < wx + 165; x++) {
				int rgb = img.getRGB(x, y);
				sum += ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
				n++;
			}
		}
		return sum / (3.0 * n);
	}

	private static void key(SettingsPanel panel, int code) {
		KeyEvent down = new KeyEvent(panel, KeyEvent.KEY_PRESSED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
		KeyEvent up = new KeyEvent(panel, KeyEvent.KEY_RELEASED, 0, 0, code, KeyEvent.CHAR_UNDEFINED);
		for (KeyListener l : panel.getKeyListeners()) {
			l.keyPressed(down);
			l.keyReleased(up);
		}
	}

	private static void paint(SettingsPanel panel) {
		BufferedImage img = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		panel.printAll(g);
		g.dispose();
	}

	// ------------------------------------------------------------------ 도우미

	/** 갈래 카드 {@code s} 의 가운데 x (가상 화면): 카드 여섯이 x 248 부터 너비 120, 사이 12. */
	private static int tabCenter(SettingsPanel.Section s) {
		return 248 + s.ordinal() * (120 + 12) + 60;
	}

	/** 그림에 쓰인 서로 다른 색의 수 (대략). */
	private static int colours(BufferedImage img) {
		java.util.Set<Integer> set = new java.util.HashSet<>();
		for (int y = 0; y < img.getHeight(); y += 7) {
			for (int x = 0; x < img.getWidth(); x += 7) {
				set.add(img.getRGB(x, y));
			}
		}
		return set.size();
	}
}
