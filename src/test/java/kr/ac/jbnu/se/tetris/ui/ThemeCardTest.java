package kr.ac.jbnu.se.tetris.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 둥근 카드의 조각 그림({@link ThemeCard}): 예전 그리기({@link Theme#cardDirect})와 같은 그림(합성의 반올림만큼, 0~255 에서
 * 몇 단계), 같은 카드는 그림을 다시 쓰고(멈춘 화면은 프레임마다 화소까지 같다), 회전·이상한 합성·안티앨리어싱 없는 그리기는
 * 예전처럼 그린다. 붙인 뒤의 색·변환·선은 예전과 같다.
 */
class ThemeCardTest {

	@BeforeAll
	static void headless() {
		System.setProperty("java.awt.headless", "true");
	}

	@BeforeEach
	@AfterEach
	void reset() {
		ThemeCard.enabled = true;
		ThemeCard.clear();
	}

	/** 카드 하나: 가상 좌표, 둥글기, 면, 테두리. */
	private record Card(double x, double y, double w, double h, double r, Color fill, Color border) {
	}

	private static final Card[] CARDS = { new Card(120, 148, 1040, 484, 28, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER),
			new Card(490, 562, 300, 54, 27, Theme.withAlpha(Theme.BG_TOP, 210), Theme.withAlpha(Theme.ACCENT, 200)),
			new Card(30.5, 40.25, 56, 22, 8, new Color(10, 12, 30, 110), new Color(255, 255, 255, 90)),
			new Card(12, 20, 29, 300, 6, Theme.SURFACE, Theme.BORDER), new Card(200, 30, 6, 80, 6, Theme.BOARD, Theme.BORDER),
			new Card(300.3, 300.6, 158, 218, 12, Theme.withAlpha(Theme.SURFACE, 170), Theme.BORDER),
			new Card(40, 400, 120, 5, 5, Theme.OK, null), new Card(500, 120, 400, 24, 24, Theme.withAlpha(Theme.WARN, 26), null),
			new Card(700, 200, 260, 42, 42, Theme.SURFACE_HI, Theme.withAlpha(Theme.ACCENT, 200)),
			new Card(900.75, 420.4, 90, 90, 200, Color.WHITE, Theme.P2) };

	private static BufferedImage canvas(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.background(g, w, h);
		g.dispose();
		return img;
	}

	/** {@code t} 로 카드들을 그린 그림. */
	private static BufferedImage draw(AffineTransform t, int w, int h, Consumer<Graphics2D> paint) {
		BufferedImage img = canvas(w, h);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.transform(t);
		paint.accept(g);
		g.dispose();
		return img;
	}

	private static void cards(Graphics2D g, boolean direct) {
		for (Card c : CARDS) {
			if (direct) {
				Theme.cardDirect(g, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
			} else {
				Theme.card(g, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
			}
		}
	}

	/** 두 그림의 차이: {가장 큰 채널 차이, 8 넘게 다른 화소 수}. */
	private static int[] diff(BufferedImage a, BufferedImage b) {
		int max = 0;
		int many = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				int p = a.getRGB(x, y);
				int q = b.getRGB(x, y);
				int d = 0;
				for (int sh = 0; sh <= 16; sh += 8) {
					d = Math.max(d, Math.abs((p >> sh & 0xFF) - (q >> sh & 0xFF)));
				}
				max = Math.max(max, d);
				if (d > 8) {
					many++;
				}
			}
		}
		return new int[] { max, many };
	}

	/** 예전 그리기와 조각 그림의 차이: {가장 큰 채널 차이, 8 넘게 다른 화소 수}. */
	private static int[] compare(AffineTransform t, int w, int h, Consumer<Graphics2D> direct, Consumer<Graphics2D> card) {
		BufferedImage before = draw(t, w, h, direct);
		long made = ThemeCard.spritesMade();
		BufferedImage after = draw(t, w, h, card);
		assertTrue(ThemeCard.spritesMade() > made, "조각 그림으로 그렸다");
		assertEquals(0, diff(after, draw(t, w, h, card))[0], "다시 그리면 화소까지 같다");
		return diff(before, after);
	}

	@Test
	void onWholeQuarterPixelsTheSpritesGiveTheSamePicture() {
		// 위치와 모서리가 1/4 화소에 맞으면 반올림 차이뿐이다 (메뉴의 1280 × 720, 1920 × 1080 이 그렇다)
		Card[] exact = { new Card(120, 148, 1040, 484, 28, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER),
				new Card(30.5, 40.25, 56, 22, 8, new Color(10, 12, 30, 110), new Color(255, 255, 255, 90)),
				new Card(700, 200, 260, 42, 42, Theme.SURFACE_HI, Theme.withAlpha(Theme.ACCENT, 200)),
				new Card(40, 400, 120, 5, 5, Theme.OK, null), new Card(900, 420, 90, 90, 200, Color.WHITE, Theme.P2),
				new Card(200, 30, 6, 80, 6, Theme.BOARD, Theme.BORDER) };
		for (double s : new double[] { 1, 1.5, 2 }) {
			AffineTransform t = AffineTransform.getScaleInstance(s, s);
			int[] d = compare(t, (int) (1280 * s), (int) (720 * s), g -> {
				for (Card c : exact) {
					Theme.cardDirect(g, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
				}
			}, g -> {
				for (Card c : exact) {
					Theme.card(g, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
				}
			});
			assertTrue(d[0] <= 3, "배율 " + s + ": 가장 큰 차이 " + d[0] + ", 8 넘게 다른 화소 " + d[1]);
		}
	}

	@Test
	void atAnyScaleAndOffsetTheSpritesStayClose() {
		double[] scales = { 1, 0.75, 1366 / 1280.0, 1.0667, 1.25, 1.5, 2 };
		double[] offsets = { 0, 0.13, 0.5, 0.77 };
		for (double s : scales) {
			for (double off : offsets) {
				AffineTransform t = new AffineTransform();
				t.translate(off, off * 0.7);
				t.scale(s, s);
				int[] d = compare(t, (int) Math.ceil(1280 * s) + 2, (int) Math.ceil(720 * s) + 2, g -> cards(g, true),
						g -> cards(g, false));
				String at = "배율 " + s + ", 위치 " + off + ": 가장 큰 차이 " + d[0] + ", 8 넘게 다른 화소 " + d[1];
				assertTrue(d[0] <= 4, at);
			}
		}
	}

	@Test
	void theSameCardIsRenderedOnceAndReused() {
		AffineTransform t = AffineTransform.getScaleInstance(1.5, 1.5);
		draw(t, 1920, 1080, g -> cards(g, false));
		long made = ThemeCard.spritesMade();
		assertEquals(CARDS.length, made, "카드마다 한 장");
		for (int i = 0; i < 5; i++) {
			draw(t, 1920, 1080, g -> cards(g, false));
		}
		assertEquals(made, ThemeCard.spritesMade(), "같은 카드는 그림을 다시 쓴다");
		// 크기만 다른 큰 카드 (모서리와 1/4 화소 위치가 같다): 같은 그림
		draw(t, 1920, 1080, g -> Theme.card(g, 120, 148, 900, 300, 28, Theme.withAlpha(Theme.SURFACE, 235), Theme.BORDER));
		assertEquals(made, ThemeCard.spritesMade(), "곧은 가장자리는 늘여 붙이므로 크기가 달라도 같은 그림");
		assertTrue(ThemeCard.cached() <= ThemeCard.CAPACITY);
	}

	@Test
	void unusualDrawingsAreDrawnDirectly() {
		Card c = CARDS[1];
		Consumer<Graphics2D> twice = g -> {
			Theme.card(g, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
			Theme.card(g, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
		};
		draw(AffineTransform.getRotateInstance(0.1), 1280, 720, twice);
		draw(AffineTransform.getScaleInstance(1.5, 1.2), 1280, 720, twice);
		BufferedImage img = canvas(1280, 720);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		twice.accept(g); // 안티앨리어싱 없음
		Theme.antialias(g);
		g.setComposite(AlphaComposite.Src);
		twice.accept(g); // SRC_OVER 가 아닌 합성
		g.dispose();
		assertEquals(0, ThemeCard.spritesMade(), "조각 그림을 만들지 않았다");
		ThemeCard.enabled = false;
		draw(new AffineTransform(), 1280, 720, twice);
		draw(new AffineTransform(), 1280, 720, twice);
		assertEquals(0, ThemeCard.spritesMade(), "끄면 언제나 바로 그린다");
	}

	@Test
	void anOuterAlphaFadesTheWholeCardAsOne() {
		// 카드가 나타나며 옅어지는 동안: 카드를 투명한 그림에 그려 한 번에 옅게 붙인 것과 같다
		AffineTransform t = AffineTransform.getScaleInstance(1.5, 1.5);
		Consumer<Graphics2D> group = g -> {
			for (Card c : CARDS) { // 카드마다 투명한 그림에 그려 반만 진하게 붙인다
				BufferedImage layer = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB_PRE);
				Graphics2D lg = layer.createGraphics();
				Theme.antialias(lg);
				lg.transform(g.getTransform());
				Theme.cardDirect(lg, c.x, c.y, c.w, c.h, c.r, c.fill, c.border);
				lg.dispose();
				AffineTransform keep = g.getTransform();
				g.setTransform(new AffineTransform());
				g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
				g.drawImage(layer, 0, 0, null);
				g.setTransform(keep);
			}
		};
		int[] d = compare(t, 1920, 1080, group, g -> {
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f));
			cards(g, false);
		});
		assertTrue(d[0] <= 4, "가장 큰 차이 " + d[0] + ", 8 넘게 다른 화소 " + d[1]);
	}

	@Test
	void theGraphicsStateIsLeftAsBefore() {
		BufferedImage img = canvas(400, 300);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.scale(1.25, 1.25);
		AffineTransform t = g.getTransform();
		Stroke stroke = new BasicStroke(3f);
		g.setStroke(stroke);
		for (int i = 0; i < 2; i++) { // 처음은 그림을 만들고, 다음은 그 그림을 다시 쓴다
			Theme.card(g, 10, 10, 200, 100, 18, Theme.SURFACE, Theme.BORDER);
			assertEquals(Theme.BORDER, g.getColor(), "테두리 색이 남는다 (예전과 같다)");
			Theme.card(g, 10, 120, 200, 40, 40, Theme.OK, null);
			assertEquals(Theme.OK, g.getColor(), "테두리가 없으면 면 색");
			assertEquals(t, g.getTransform());
			assertSame(stroke, g.getStroke());
		}
		g.dispose();
		assertTrue(ThemeCard.spritesMade() >= 2);
	}

	@Test
	void screensLookTheSameWithSprites() throws Exception {
		kr.ac.jbnu.se.tetris.settings.SaveData data = kr.ac.jbnu.se.tetris.settings.SaveData.inMemory();
		HelpPanel help = new HelpPanel(data::settings, () -> {
		});
		kr.ac.jbnu.se.tetris.audio.Sound quiet = kr.ac.jbnu.se.tetris.audio.Sound.silent();
		SettingsPanel settings = new SettingsPanel(data, () -> {
		}, s -> {
		}, () -> quiet);
		settings.clock = () -> 5_000_000L;
		settings.open();
		for (javax.swing.JComponent panel : new javax.swing.JComponent[] { help, settings }) {
			for (int[] wh : new int[][] { { 1280, 720 }, { 1920, 1080 }, { 1366, 768 } }) {
				ThemeCard.enabled = false;
				BufferedImage direct = paint(panel, wh[0], wh[1]);
				ThemeCard.enabled = true;
				BufferedImage sprite = paint(panel, wh[0], wh[1]);
				assertEquals(0, diff(sprite, paint(panel, wh[0], wh[1]))[0], "멈춘 화면은 다시 그려도 화소까지 같다");
				int[] d = diff(direct, sprite);
				String at = panel.getClass().getSimpleName() + " " + wh[0] + "×" + wh[1] + ": 가장 큰 차이 " + d[0]
						+ ", 8 넘게 다른 화소 " + d[1];
				assertTrue(d[0] <= 4, at);
			}
		}
	}

	private static BufferedImage paint(javax.swing.JComponent c, int w, int h) {
		c.setSize(w, h);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		c.printAll(g);
		g.dispose();
		return img;
	}
}
