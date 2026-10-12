package kr.ac.jbnu.se.tetris.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 키 이름: 키 모양 글자, 한국어 이름, 저장 이름. */
class KeyNamesTest {

	@Test
	void labelsLookLikeKeyCaps() {
		assertEquals("←", KeyNames.label(KeyEvent.VK_LEFT));
		assertEquals("→", KeyNames.label(KeyEvent.VK_RIGHT));
		assertEquals("↑", KeyNames.label(KeyEvent.VK_UP));
		assertEquals("↓", KeyNames.label(KeyEvent.VK_DOWN));
		assertEquals("Space", KeyNames.label(KeyEvent.VK_SPACE));
		assertEquals("Enter", KeyNames.label(KeyEvent.VK_ENTER));
		assertEquals("Esc", KeyNames.label(KeyEvent.VK_ESCAPE));
		assertEquals("Ctrl", KeyNames.label(KeyEvent.VK_CONTROL));
		assertEquals("Shift", KeyNames.label(KeyEvent.VK_SHIFT));
		assertEquals("F11", KeyNames.label(KeyEvent.VK_F11));
		assertEquals("F13", KeyNames.label(KeyEvent.VK_F13));
		assertEquals("A", KeyNames.label(KeyEvent.VK_A));
		assertEquals("Z", KeyNames.label(KeyEvent.VK_Z));
		assertEquals("7", KeyNames.label(KeyEvent.VK_7));
		assertEquals("Num 0", KeyNames.label(KeyEvent.VK_NUMPAD0));
		assertEquals("Num +", KeyNames.label(KeyEvent.VK_ADD));
		assertEquals(",", KeyNames.label(KeyEvent.VK_COMMA));
		assertEquals("\\", KeyNames.label(KeyEvent.VK_BACK_SLASH));
		assertEquals("키(99999)", KeyNames.label(99999), "표에 없는 키");
		assertEquals("없음", KeyNames.label(KeyNames.NONE));
	}

	@Test
	void reservedKeysHaveNamesForTheRefusalMessage() {
		// 묶을 수는 없지만 "한/영 키는 쓸 수 없어요" 처럼 알리려면 이름이 있어야 한다
		assertEquals("한/영", KeyNames.label(KeyEvent.VK_KANA));
		assertEquals("한자", KeyNames.label(KeyEvent.VK_KANJI));
		assertEquals("Alt", KeyNames.label(KeyEvent.VK_ALT));
		assertEquals("Win", KeyNames.label(KeyEvent.VK_WINDOWS));
		assertEquals("Num Lock", KeyNames.label(KeyEvent.VK_NUM_LOCK));
		assertEquals("PrtSc", KeyNames.label(KeyEvent.VK_PRINTSCREEN));
		assertEquals("윈도우", KeyNames.korean(KeyEvent.VK_WINDOWS));
	}

	@Test
	void phrasesTakeTheParticleOnTheWordKey() {
		// 조사는 언제나 '키' 에 붙는다: "Space 키가", "R 키는" (받침을 따질 일이 없다)
		assertEquals("Space 키", KeyNames.phrase(KeyEvent.VK_SPACE));
		assertEquals("한/영 키", KeyNames.phrase(KeyEvent.VK_KANA));
		assertEquals("키(99999)", KeyNames.phrase(99999), "'키(99999)는' 처럼 괄호 앞의 '키' 에 붙는다");
		assertEquals("그 키", KeyNames.phrase(KeyNames.NONE));
		assertEquals("그 키", KeyNames.phrase(-3));
	}

	@Test
	void koreanNamesAreForSentences() {
		assertEquals("스페이스바", KeyNames.korean(KeyEvent.VK_SPACE));
		assertEquals("왼쪽 화살표", KeyNames.korean(KeyEvent.VK_LEFT));
		assertEquals("엔터", KeyNames.korean(KeyEvent.VK_ENTER));
		assertEquals("숫자패드 5", KeyNames.korean(KeyEvent.VK_NUMPAD5));
		assertEquals("Z", KeyNames.korean(KeyEvent.VK_Z), "따로 없으면 키 모양 글자");
		assertEquals("키(99999)", KeyNames.korean(99999));
	}

	@Test
	void everyKeyCodeRoundTripsThroughItsToken() {
		for (int code = 1; code <= 0x10000; code++) {
			String t = KeyNames.token(code);
			assertEquals(code, KeyNames.code(t), t);
		}
		assertEquals("SPACE", KeyNames.token(KeyEvent.VK_SPACE));
		assertEquals("CODE_99999", KeyNames.token(99999));
	}

	@Test
	void tokensIgnoreCaseAndBadOnesAreRejected() {
		assertEquals(KeyEvent.VK_SPACE, KeyNames.code("space"));
		assertEquals(KeyEvent.VK_SPACE, KeyNames.code(" SPACE "));
		assertEquals(KeyEvent.VK_NUMPAD3, KeyNames.code("numpad3"));
		assertEquals(1234, KeyNames.code("CODE_1234"));
		assertEquals(KeyEvent.VK_A, KeyNames.code("VK_A"), "KeyEvent 문서의 이름도 읽는다");
		assertEquals(KeyEvent.VK_PAGE_UP, KeyNames.code("vk_page_up"));
		for (String bad : new String[] { "SPACEBAR", "", " ", "CODE_", "CODE_12x", "CODE_-1", "CODE_9999999999", "VK_",
				"VK_VK_A", "스페이스" }) {
			assertEquals(-1, KeyNames.code(bad), bad);
		}
		assertEquals(-1, KeyNames.code(null));
	}

	@Test
	void tokensAreJavaKeyEventNamesWithoutTheVkPrefix() throws IllegalAccessException {
		// 사람이 저장 파일을 열어 KeyEvent 문서와 같은 이름을 보게 한다. 같은 값을 가진 VK 이름이 여럿이면 그중 하나면 된다.
		Map<Integer, List<String>> names = new HashMap<>();
		for (Field f : KeyEvent.class.getFields()) {
			if (f.getName().startsWith("VK_") && f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
				names.computeIfAbsent(f.getInt(null), k -> new ArrayList<>()).add(f.getName().substring(3));
			}
		}
		int checked = 0;
		for (Map.Entry<Integer, List<String>> e : names.entrySet()) {
			String token = KeyNames.token(e.getKey());
			if (!token.startsWith("CODE_")) {
				assertTrue(e.getValue().contains(token), token + " 은 " + e.getValue() + " 중 하나여야 한다");
				checked++;
			}
		}
		assertTrue(checked > 100, "표의 키 대부분을 확인했다: " + checked);
	}

	@Test
	void reservedKeysCannotBeBound() {
		assertTrue(KeyNames.bindable(KeyEvent.VK_A));
		assertTrue(KeyNames.bindable(KeyEvent.VK_F11));
		assertTrue(KeyNames.bindable(KeyEvent.VK_CAPS_LOCK));
		assertTrue(KeyNames.bindable(KeyEvent.VK_CONTROL));
		assertTrue(KeyNames.bindable(99999), "표에 없는 키도 묶을 수 있다");
		assertFalse(KeyNames.bindable(KeyEvent.VK_WINDOWS));
		assertFalse(KeyNames.bindable(KeyEvent.VK_KANA), "한/영");
		assertFalse(KeyNames.bindable(KeyEvent.VK_KANJI), "한자");
		assertFalse(KeyNames.bindable(KeyEvent.VK_ALT));
		assertFalse(KeyNames.bindable(KeyEvent.VK_DEAD_ACUTE));
		assertFalse(KeyNames.bindable(KeyEvent.VK_INPUT_METHOD_ON_OFF));
		assertFalse(KeyNames.bindable(KeyNames.NONE));
		assertFalse(KeyNames.bindable(-1));
	}

	@Test
	void joinUsesSlashes() {
		assertEquals("↑ / X", KeyNames.join(List.of(KeyEvent.VK_UP, KeyEvent.VK_X)));
		assertEquals("", KeyNames.join(List.of()));
	}
}
