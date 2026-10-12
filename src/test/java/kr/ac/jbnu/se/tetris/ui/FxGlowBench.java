package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;

/**
 * 빛 번짐({@link Theme#glow}) 한 번의 비용을 예전 방법(선 다섯 겹, {@link FxGlow#strokes})과 조각 그림({@link FxGlow#draw})으로 재고,
 * 두 그림이 얼마나 같은지 비교한다 (runs/quality/SPEC.md V3(f)). 창 없이 소프트웨어로 그린다.
 * <p>
 * 실행: {@code gradlew glowBench} (build/look-foundation), 또는 시험 클래스까지 빌드한 뒤 {@code java "-Djava.awt.headless=true"
 * "-Dtetris.sound=off" "-Dtetris.config=off" "-Dtetris.records=off" -cp
 * "build/classes/java/main;build/classes/java/test;build/resources/main;<junit-platform-launcher jar>"
 * kr.ac.jbnu.se.tetris.ui.FxGlowBench [출력 폴더]} (기본 build/look-foundation, Windows 는 경로 구분이 {@code ;}, PowerShell 에서는
 * {@code -D} 를 따옴표로 감싼다). 결과 표를 출력하고 비교 그림 glow.png 를 쓴다. 시작할 때 {@code TestSandbox.apply()} 를 불러 IDE
 * 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않는다(준 값은 덮지 않는다). 그 클래스가 JUnit 의
 * LauncherSessionListener 를 구현하므로 {@code java} 로 돌릴 때는 junit-platform-launcher jar 가 클래스 경로에 있어야 한다.
 */
public final class FxGlowBench {

	/** 잴 경우: 이름, 화면 크기, 변환 배율, 사각형, 모서리, 색, 세기(음수면 위험 깜빡임처럼 매번 바뀜). */
	record Case(String name, int w, int h, double scale, double x, double y, double rw, double rh, double r, Color c,
			double strength) {
	}

	static final Case[] CASES = {
			// 대전 보드 (GamePanel: pad 0.18c, 모서리 0.6c, 10 × 20 칸)
			new Case("board c=24 P1 0.35", 1280, 720, 1, 400 - 4.32, 150 - 4.32, 240 + 8.64, 480 + 8.64, 14.4,
					Theme.P1, 0.35),
			new Case("board c=36 P2 0.35", 1920, 1080, 1, 600 - 6.48, 220 - 6.48, 360 + 12.96, 720 + 12.96, 21.6,
					Theme.P2, 0.35),
			new Case("board c=36 danger pulse", 1920, 1080, 1, 600 - 6.48, 220 - 6.48, 360 + 12.96, 720 + 12.96, 21.6,
					Theme.DANGER, -1),
			new Case("board c=24 x1.25 (HiDPI)", 1600, 900, 1.25, 400 - 4.32, 150 - 4.32, 248.64, 488.64, 14.4,
					Theme.P1, 0.6),
			// 메뉴 (1280 × 720 가상 화면을 창에 맞춰 키운다)
			new Case("menu card x1.5 ACCENT 0.8", 1920, 1080, 1.5, 120, 260, 340, 300, 34, Theme.ACCENT, 0.8),
			new Case("menu pill 1.0", 1280, 720, 1, 500, 600, 280, 54, 54, Theme.ACCENT_2, 1.0),
			new Case("menu circle 54 0.6", 1280, 720, 1, 613, 333, 54, 54, 54, Theme.ACCENT_2, 0.6),
			new Case("settings key 0.55+pulse", 1280, 720, 1, 700, 400, 120, 33, 10, Theme.ACCENT_2, -2), };

	public static void main(String[] args) throws IOException {
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않게
		File out = new File(args.length > 0 ? args[0] : "build/look-foundation");
		if (!out.isDirectory() && !out.mkdirs()) {
			throw new IOException("폴더를 만들 수 없음: " + out);
		}
		System.out.printf(Locale.ROOT, "%-30s %10s %10s %7s %9s %9s%n", "case", "old us", "new us", "x", "max diff",
				"px diff>2");
		BufferedImage sheet = new BufferedImage(1220, ROW_H * CASES.length + 20, BufferedImage.TYPE_INT_RGB);
		Graphics2D sg = sheet.createGraphics();
		Theme.antialias(sg);
		sg.setColor(Theme.BG_TOP);
		sg.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
		int row = 0;
		for (Case k : CASES) {
			double oldUs = time(k, false);
			double newUs = time(k, true);
			BufferedImage a = render(k, false);
			BufferedImage b = render(k, true);
			int[] diff = diff(a, b);
			System.out.printf(Locale.ROOT, "%-30s %10.1f %10.1f %7.1f %9d %9d%n", k.name, oldUs, newUs, oldUs / newUs,
					diff[0], diff[1]);
			paintRow(sg, k, a, b, 10, 10 + row * ROW_H);
			row++;
		}
		sg.dispose();
		ImageIO.write(sheet, "png", new File(out, "glow.png"));
		System.out.println("그림: " + new File(out, "glow.png").getPath() + ", 만든 조각 그림 " + FxGlow.spritesMade() + "장");
	}

	/** 한 번 부르는 데 걸리는 시간(µs): 데우고 나서 여러 번의 평균. */
	static double time(Case k, boolean sprite) {
		BufferedImage img = new BufferedImage(k.w, k.h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.scale(k.scale, k.scale);
		int warm = 300;
		int n = 1500;
		for (int i = 0; i < warm; i++) {
			call(g, k, sprite, i);
		}
		long t0 = System.nanoTime();
		for (int i = 0; i < n; i++) {
			call(g, k, sprite, i);
		}
		long t1 = System.nanoTime();
		g.dispose();
		return (t1 - t0) / 1000.0 / n;
	}

	static void call(Graphics2D g, Case k, boolean sprite, int frame) {
		double s = strength(k, frame);
		if (sprite) {
			FxGlow.draw(g, k.x, k.y, k.rw, k.rh, k.r, k.c, s);
		} else {
			FxGlow.strokes(g, k.x, k.y, k.rw, k.rh, k.r, k.c, s);
		}
	}

	/** 세기: 음수면 프레임마다 바뀌는 깜빡임 (-1: 위험 0.6 + 0.4 sin, -2: 설정 키 0.55 + 0.45 맥박). 15 ms 마다 한 프레임. */
	static double strength(Case k, int frame) {
		long now = frame * 15L;
		if (k.strength == -1) {
			return 0.6 + 0.4 * Math.sin(now / 120.0);
		}
		if (k.strength == -2) {
			return 0.55 + 0.45 * (0.5 + 0.5 * Math.sin(now / 180.0));
		}
		return k.strength;
	}

	/** 사각형 둘레만 잘라 그린 그림 (보드 색 바탕). */
	static BufferedImage render(Case k, boolean sprite) {
		int pad = 24;
		int w = (int) Math.ceil(k.rw * k.scale) + 2 * pad;
		int h = (int) Math.ceil(k.rh * k.scale) + 2 * pad;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		Theme.antialias(g);
		g.setColor(Theme.BOARD);
		g.fillRect(0, 0, w, h);
		// 같은 소수 위치가 되도록 원래 위치의 소수 부분을 살린다
		double fx = k.x * k.scale - Math.floor(k.x * k.scale);
		double fy = k.y * k.scale - Math.floor(k.y * k.scale);
		g.translate(pad + fx, pad + fy);
		g.scale(k.scale, k.scale);
		double s = k.strength < 0 ? 0.73 : k.strength;
		if (sprite) {
			FxGlow.draw(g, 0, 0, k.rw, k.rh, k.r, k.c, s);
		} else {
			FxGlow.strokes(g, 0, 0, k.rw, k.rh, k.r, k.c, s);
		}
		g.dispose();
		return img;
	}

	/** {가장 큰 채널 차이, 채널 차이가 2 를 넘는 화소 수}. */
	static int[] diff(BufferedImage a, BufferedImage b) {
		int max = 0;
		int count = 0;
		for (int y = 0; y < a.getHeight(); y++) {
			for (int x = 0; x < a.getWidth(); x++) {
				int d = channelDiff(a.getRGB(x, y), b.getRGB(x, y));
				max = Math.max(max, d);
				if (d > 2) {
					count++;
				}
			}
		}
		return new int[] { max, count };
	}

	static int channelDiff(int p, int q) {
		int d = 0;
		for (int sh = 0; sh <= 16; sh += 8) {
			d = Math.max(d, Math.abs((p >> sh & 0xFF) - (q >> sh & 0xFF)));
		}
		return d;
	}

	/** 비교 그림 한 줄의 높이. */
	static final int ROW_H = 178;

	/** 한 줄: 왼쪽 위 모서리 ×2 (예전 | 조각 그림 | 차이 ×16), 그리고 원래 크기 (예전 | 조각 그림). */
	static void paintRow(Graphics2D g, Case k, BufferedImage a, BufferedImage b, int x, int y) {
		g.setFont(Theme.font(13, true));
		g.setColor(Theme.TEXT);
		g.drawString(k.name, x, y + 14);
		int crop = 64;
		int zoom = 2;
		BufferedImage d = new BufferedImage(crop, crop, BufferedImage.TYPE_INT_RGB);
		for (int yy = 0; yy < crop; yy++) {
			for (int xx = 0; xx < crop; xx++) {
				int v = Math.min(255, channelDiff(a.getRGB(xx, yy), b.getRGB(xx, yy)) * 16);
				d.setRGB(xx, yy, v << 16 | v << 8 | v);
			}
		}
		int top = y + 22;
		g.drawImage(a.getSubimage(0, 0, crop, crop), x, top, crop * zoom, crop * zoom, null);
		g.drawImage(b.getSubimage(0, 0, crop, crop), x + 140, top, crop * zoom, crop * zoom, null);
		g.drawImage(d, x + 280, top, crop * zoom, crop * zoom, null);
		int fw = Math.min(a.getWidth(), 360);
		int fh = Math.min(a.getHeight(), crop * zoom);
		g.drawImage(a.getSubimage(0, 0, fw, fh), x + 430, top, null);
		g.drawImage(b.getSubimage(0, 0, fw, fh), x + 430 + 380, top, null);
		g.setFont(Theme.font(11, false));
		g.setColor(Theme.LABEL);
		int label = top + crop * zoom + 14;
		g.drawString("예전 (선 5겹) ×2", x, label);
		g.drawString("조각 그림 ×2", x + 140, label);
		g.drawString("차이 ×16", x + 280, label);
		g.drawString("예전 (원래 크기)", x + 430, label);
		g.drawString("조각 그림 (원래 크기)", x + 810, label);
	}

	private FxGlowBench() {
	}
}
