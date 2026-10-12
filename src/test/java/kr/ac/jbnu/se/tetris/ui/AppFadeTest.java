package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 메뉴 ↔ 판 바꾸기 (SPEC V11): 120ms 동안 떠나는 화면(판을 떠날 때는 그 순간의 그림, 메뉴에서는 살아 있는 화면)이 어두워지고,
 * 다 어두워지면 할 일을 하고, 250ms 동안 밝아진다. 그림이 다 덮는 동안은 유리판이 불투명하다(아래 화면을 다시 그리지 않는다).
 * 어두워지는 동안의 두 번째 요청은 무시하고, 밝아지는 동안의 요청은 바로 한다. 창 없이 시계를 바꿔 넣고 직접 진행한다.
 */
class AppFadeTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	/** 흰 판 (떠나는 화면). */
	private static JPanel white() {
		JPanel p = new JPanel() {
			private static final long serialVersionUID = 1L;

			@Override
			protected void paintComponent(Graphics g) {
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, getWidth(), getHeight());
			}
		};
		p.setSize(200, 100);
		return p;
	}

	@Test
	void theCurvesRunFromClearToBlackAndBack() {
		assertEquals(0, AppFade.outVeil(0), 1e-9);
		assertEquals(1, AppFade.outVeil(AppFade.OUT_MS), 1e-9);
		assertEquals(1, AppFade.inVeil(0), 1e-9);
		assertEquals(0, AppFade.inVeil(AppFade.IN_MS), 1e-9);
		for (int ms = 10; ms <= AppFade.OUT_MS; ms += 10) {
			assertTrue(AppFade.outVeil(ms) >= AppFade.outVeil(ms - 10), "어두워지기만 한다");
		}
		for (int ms = 10; ms <= AppFade.IN_MS; ms += 10) {
			assertTrue(AppFade.inVeil(ms) <= AppFade.inVeil(ms - 10), "밝아지기만 한다");
		}
		assertTrue(AppFade.outVeil(AppFade.OUT_MS / 2.0) < 0.5, "나갈 때는 천천히 시작해 빨라진다 (0.3, 0, 1, 1)");
		assertTrue(AppFade.inVeil(AppFade.IN_MS / 4.0) < 0.3, "들어올 때는 빠르게 시작해 부드럽게 멈춘다 (easeOutQuint)");
	}

	@Test
	void theActionWaitsUntilTheScreenIsBlack() {
		long[] now = { 10_000 };
		AppFade fade = new AppFade();
		fade.clock = () -> now[0];
		fade.setSize(200, 100);
		List<String> done = new ArrayList<>();
		assertTrue(fade.run(white(), () -> done.add("start")));
		assertTrue(fade.blocking(), "어두워지는 동안은 입력을 막는다");
		assertTrue(fade.isVisible());
		assertTrue(fade.frozen() && fade.isOpaque(), "떠나는 화면의 그림이 다 덮는다: 아래 화면을 다시 그리지 않는다");
		assertTrue(fade.contains(10, 10), "어두워지는 동안은 마우스도 받는다");
		assertFalse(fade.run(white(), () -> done.add("again")), "어두워지는 중의 두 번째 요청은 무시");
		now[0] += 60;
		fade.tick();
		assertTrue(done.isEmpty(), "아직 다 어두워지지 않았다");
		assertTrue(fade.veil() > 0 && fade.veil() < 1);
		// 어두워지는 동안의 그림: 떠나는 화면(흰색)이 반쯤 어둡다
		int mid = brightness(paint(fade), 100, 50);
		assertTrue(mid > 40 && mid < 230, "반쯤 어둡다: " + mid);
		now[0] += 70;
		fade.tick();
		assertEquals(List.of("start"), done, "다 어두워지면 할 일을 한다");
		assertFalse(fade.blocking(), "밝아지는 동안은 막지 않는다");
		assertFalse(fade.frozen() || fade.isOpaque(), "밝아지는 동안은 새 화면이 비친다");
		assertFalse(fade.contains(10, 10), "마우스는 아래 화면으로 간다");
		assertEquals(1, fade.veil(), 1e-9);
		now[0] += 100;
		double before = fade.veil();
		assertTrue(before < 0.2, "easeOutQuint: 0.1초면 거의 밝다: " + before);
		// 밝아지는 동안의 요청은 바로 하고, 지금 어둡기에서 이어서 밝아진다
		assertTrue(fade.run(white(), () -> done.add("menu")));
		assertEquals(List.of("start", "menu"), done);
		assertEquals(before, fade.veil(), 1e-9, "어둡기가 튀지 않는다");
		now[0] += AppFade.IN_MS;
		fade.tick();
		assertFalse(fade.active());
		assertFalse(fade.isVisible());
		assertEquals(0, fade.veil(), 1e-9);
	}

	@Test
	void fromTheMenuTheLiveScreenShowsThroughWithoutASnapshot() {
		long[] now = { 10_000 };
		AppFade fade = new AppFade();
		fade.clock = () -> now[0];
		fade.setSize(200, 100);
		List<String> done = new ArrayList<>();
		assertTrue(fade.run(white(), false, () -> done.add("start")));
		assertTrue(fade.blocking());
		assertFalse(fade.frozen() || fade.isOpaque(), "그림을 뜨지 않고 살아 있는 화면 위에 막만 칠한다");
		now[0] += 60;
		fade.tick();
		int rgb = paint(fade).getRGB(100, 50);
		int red = (rgb >> 16) & 0xFF;
		int green = (rgb >> 8) & 0xFF;
		assertTrue(red == 0 && green > 40 && green < 230, "아래 화면(초록)이 반쯤 어둡게 비친다: " + Integer.toHexString(rgb));
		now[0] += 70;
		fade.tick();
		assertEquals(List.of("start"), done, "다 어두워지면 할 일을 한다");
	}

	@Test
	void aContentWithoutSizeSwitchesAtOnce() {
		AppFade fade = new AppFade();
		List<String> done = new ArrayList<>();
		assertTrue(fade.run(new JPanel(), () -> done.add("now")));
		assertEquals(List.of("now"), done);
		assertFalse(fade.active());
	}

	private static BufferedImage paint(AppFade fade) {
		BufferedImage img = new BufferedImage(fade.getWidth(), fade.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.GREEN); // 아래 화면 대신: 어두워지는 동안에는 떠나는 화면의 그림이 덮는다
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		fade.paint(g);
		g.dispose();
		return img;
	}

	private static int brightness(BufferedImage img, int x, int y) {
		int rgb = img.getRGB(x, y);
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		assertEquals(r, b, "초록(아래 화면)이 비치지 않는다");
		return (r + g + b) / 3;
	}
}
