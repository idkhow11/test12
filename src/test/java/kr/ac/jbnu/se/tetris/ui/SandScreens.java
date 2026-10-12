package kr.ac.jbnu.se.tetris.ui;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;

import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;
import kr.ac.jbnu.se.tetris.sand.SandGame;
import kr.ac.jbnu.se.tetris.sand.SandRules;
import kr.ac.jbnu.se.tetris.sand.ai.SandAiController;
import kr.ac.jbnu.se.tetris.sand.ai.SandAiLevel;
import kr.ac.jbnu.se.tetris.settings.SaveData;

/**
 * 모래 화면을 창 없이 그림 파일로 그린다 (모래 화면 확인용, runs/quality/SPEC.md V14). {@code build/screens-sand/} 에 창 크기
 * 1280 × 720 과 1920 × 1080 의 PNG 를 남긴다. 시계는 정해 넘기므로(판이 끝난 뒤의 움직임, 카운트다운, 일시정지 카드) 언제
 * 돌려도 같은 그림이다. 저장 파일은 메모리에만 두고(사용자 폴더를 읽거나 쓰지 않는다) 소리는 내지 않는다. IDE 에서 바로
 * 돌려도 그렇게 되도록 시작할 때 {@code TestSandbox.apply()} 를 부른다(창 없음, 소리·설정 폴더·사람 판 기록 끔).
 * <ul>
 * <li>{@code start}: 시작 카운트다운 "3", {@code go}: "GO!"</li>
 * <li>판 도중 (모래 AI 상급이 둔다): {@code play}, 지우기의 반짝임 {@code clear}, 사라질 때의 조각과 점수 {@code burst}, 단계
 * 오름 {@code levelup}, 같은 지우기를 색약 색상·무늬로 {@code colorblind}, 효과를 끄고 {@code effects-off}, 번쩍임 줄이기로
 * {@code calm}</li>
 * <li>떨어지는 블록: 소프트드롭 꼬리 {@code softdrop}, 하드드롭 꼬리와 튕김 {@code harddrop}</li>
 * <li>얇은 띠 (sand-0.3, 아무것도 누르지 않는 판 시드 990001 의 114.4초에 블록 하나가 더미 겉면을 덮어 양쪽 벽을 잇는다):
 * 표시가 다 들어온 {@code thin}, 효과를 끄고 {@code thin-effects-off}, 번쩍임 줄이기로 {@code thin-calm}</li>
 * <li>일시정지 {@code pause}, 계속하며 카드가 걷히는 순간 {@code resume}</li>
 * <li>끝 (블록이 나오고 150ms 뒤 하드드롭만 하는 손으로 일부러 진다): 위험 {@code danger}, 다음 블록이 0.9초 넘게 모래를
 * 기다림(흐린 블록과 "모래가 비켜나면 나와요") {@code waiting}, 회색으로 바뀌는 판 {@code over}, 새 기록 결과 카드
 * {@code result}, 같은 판 다시를 고른 결과 카드 {@code result-same}</li>
 * </ul>
 * 실행: {@code gradlew sandScreens}, 또는 시험 클래스까지 빌드한 뒤 시험 실행 경로(Gradle 의 test runtimeClasspath: 빌드한
 * 클래스·자원과 의존 jar, Windows 는 경로 구분이 {@code ;})로 돌린다. 시작할 때 부르는 {@code TestSandbox} 가 JUnit 의
 * {@code LauncherSessionListener} 를 구현하므로 경로에 JUnit jar 가 없으면 {@code NoClassDefFoundError} 로 멈춘다(빌드한 클래스
 * 폴더만으로는 안 된다):
 *
 * <pre>
 * java -Djava.awt.headless=true -Dtetris.sound=off -Dtetris.config=off -Dtetris.records=off -Dstdout.encoding=UTF-8 -cp &lt;시험 실행 경로&gt; kr.ac.jbnu.se.tetris.ui.SandScreens [폴더]
 * </pre>
 */
public final class SandScreens {

	private static final int[][] SIZES = { { 1280, 720 }, { 1920, 1080 } };
	private static final long SEED = 20261007L;
	private static final long MS = 1_000_000L;

	private SandScreens() {
	}

	public static void main(String[] args) {
		kr.ac.jbnu.se.tetris.TestSandbox.apply(); // IDE 에서 바로 돌려도 창·소리·사용자 설정 폴더·사람 판 기록을 건드리지 않게
		int code = 1;
		try {
			run(new File(args.length > 0 ? args[0] : "build/screens-sand"));
			code = 0;
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			// 창에 붙지 않은 패널은 다시 그리기 타이머를 켜지 않지만, 그리기가 띄운 AWT 스레드가 남을 수 있으므로 직접 끝낸다
			System.exit(code);
		}
	}

	private static final class Nothing implements GamePanel.Actions {
		@Override
		public void restart(boolean sameSeed) {
		}

		@Override
		public void menu() {
		}
	}

	static void run(File out) throws Exception {
		System.setProperty("java.awt.headless", "true");
		out.mkdirs();
		start(out);
		play(out);
		drops(out);
		thin(out);
		end(out);
		System.out.println("모래 화면 그림: " + out.getAbsolutePath());
	}

	// ------------------------------------------------------------------ 카운트다운, GO

	private static void start(File out) throws Exception {
		SaveData save = saveWithBest(64_120);
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(SEED, 1, save.records().sand().bestScore()), save, x -> {
		}, now::get);
		SandPanel panel = new SandPanel(new Nothing(), save);
		panel.setSession(s);
		s.start(); // 시계가 서 있으므로 카운트다운이 끝나기 전에는 판이 가지 않는다
		try {
			now.set(300 * MS); // "3" 이 뜨고 0.3초
			shoot(panel, out, "start");
			now.set((SandSession.COUNTDOWN_MS + 120) * MS);
			await(() -> s.time() >= 120);
			shoot(panel, out, "go");
		} finally {
			s.stop();
		}
	}

	// ------------------------------------------------------------------ 판 도중

	private static void play(File out) throws Exception {
		SaveData save = saveWithBest(64_120);
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(SEED, 1, save.records().sand().bestScore()), save, x -> {
		}, now::get);
		SandPanel panel = new SandPanel(new Nothing(), save);
		SandPanel colorBlind = new SandPanel(new Nothing(), settings(u -> u.withColorBlind(true).withPatterns(true)));
		SandPanel off = new SandPanel(new Nothing(), settings(u -> u.withEffects(0)));
		SandPanel calm = new SandPanel(new Nothing(), settings(u -> u.withCalmFlash(true)));
		for (SandPanel p : new SandPanel[] { panel, colorBlind, off, calm }) {
			p.setSession(s);
		}
		SandAiController ai = new SandAiController(s.game(), SandAiLevel.of(3), SEED, 1);
		long clearAt = -1;
		boolean pulse = false;
		boolean burst = false;
		boolean level = false;
		while (!s.over() && s.game().time() < 50_000) {
			s.advance(ai.poll(), false);
			SandGame g = s.game();
			long t = g.time();
			List<SandSession.ClearFx> clears = s.clearFx();
			SandSession.ClearFx last = clears.isEmpty() ? null : clears.get(clears.size() - 1);
			if (clearAt < 0 && t > 8_000 && last != null && last.at() == t && last.grains() >= 900) {
				clearAt = t; // 크고 늦은 지우기 하나를 골라 그 순간들을 찍는다
				System.out.println("지우기 " + t + "ms: 알갱이 " + last.grains() + ", 점수 +" + last.points() + ", ×"
						+ last.multiplier() + ", 조각 " + SandFx.pieceCount(last, 1));
			}
			if (clearAt >= 0 && !pulse && t == clearAt + 40) {
				shoot(panel, out, "clear");
				shoot(colorBlind, out, "colorblind");
				shoot(off, out, "effects-off");
				shoot(calm, out, "calm");
				pulse = true;
			}
			if (clearAt >= 0 && !burst && t == clearAt + SandRules.FLASH_MS + 130) {
				shoot(panel, out, "burst");
				shoot(off, out, "burst-effects-off");
				burst = true;
			}
			if (!level && t == SandRules.LEVEL_MS + 300) {
				shoot(panel, out, "levelup");
				level = true;
			}
		}
		shoot(panel, out, "play");
		now.set(5_000 * MS);
		s.togglePause();
		now.set(5_300 * MS);
		shoot(panel, out, "pause");
		s.togglePause();
		now.set(5_370 * MS); // 카드가 걷히는 중 (0.15초 중 0.07초)
		shoot(panel, out, "resume");
		System.out.println("판 도중 " + s.game().time() / 1000 + "초: 점수 " + s.game().score() + ", 지움 " + s.game().clears()
				+ ", 블록 " + s.game().pieces() + ", 반짝임 " + pulse + ", 조각 " + burst);
	}

	// ------------------------------------------------------------------ 떨어지는 블록의 꼬리

	private static void drops(File out) throws Exception {
		SaveData save = saveWithBest(64_120);
		SandSession s = new SandSession(new SandGame(SEED + 1, 3, save.records().sand().bestScore()), save, x -> {
		}, () -> 0L);
		SandPanel panel = new SandPanel(new Nothing(), save);
		panel.setSession(s);
		for (int t = 0; t < 120; t++) { // 첫 블록이 조금 내려온다
			s.advance(null, false);
		}
		for (int t = 0; t < 140; t++) { // 소프트드롭
			s.advance(null, true);
		}
		shoot(panel, out, "softdrop");
		s.advance(Input.HD, false);
		for (int t = 0; t < 30; t++) {
			s.advance(null, false);
		}
		shoot(panel, out, "harddrop"); // 하드드롭 0.03초 뒤: 꼬리와 튕김
	}

	// ------------------------------------------------------------------ 얇은 띠

	private static void thin(File out) throws Exception {
		SaveData save = SaveData.inMemory();
		SandSession s = new SandSession(new SandGame(990_001L, 1, 0), save, x -> {
		}, () -> 0L);
		SandPanel panel = new SandPanel(new Nothing(), save);
		SandPanel off = new SandPanel(new Nothing(), settings(u -> u.withEffects(0)));
		SandPanel calm = new SandPanel(new Nothing(), settings(u -> u.withCalmFlash(true)));
		for (SandPanel p : new SandPanel[] { panel, off, calm }) {
			p.setSession(s);
		}
		long since = -1;
		while (!s.over() && s.game().time() < 200_000) { // 아무것도 누르지 않는다
			s.advance(null, false);
			if (since < 0 && s.game().thinGrains() > 0) {
				since = s.game().time();
			}
			if (since >= 0 && s.game().time() == since + 600) {
				break;
			}
		}
		shoot(panel, out, "thin");
		shoot(off, out, "thin-effects-off");
		shoot(calm, out, "thin-calm");
		System.out.println("얇은 띠 " + since + "ms 부터: 알갱이 " + s.game().thinGrains() + ", 지움 " + s.game().clears());
	}

	// ------------------------------------------------------------------ 위험, 기다림, 끝, 결과

	private static void end(File out) throws Exception {
		SaveData save = saveWithBest(1_500);
		AtomicLong now = new AtomicLong();
		SandSession s = new SandSession(new SandGame(SEED + 2, 1, save.records().sand().bestScore()), save, x -> {
		}, now::get);
		SandPanel panel = new SandPanel(new Nothing(), save);
		panel.setSession(s);
		SandAiController ai = new SandAiController(s.game(), SandAiLevel.of(3), SEED + 2, 1);
		while (!s.over() && s.game().time() < 25_000) { // 점수를 조금 낸 뒤
			s.advance(ai.poll(), false);
		}
		boolean danger = false;
		boolean waiting = false;
		int seen = -1;
		long spawnedAt = 0;
		while (!s.over() && s.game().time() < 600_000) { // 블록이 나오고 150ms 뒤 그 자리에 하드드롭만 한다
			SandGame g = s.game();
			Input in = null;
			if (g.piece() != null) {
				if (g.spawns() != seen) {
					seen = g.spawns();
					spawnedAt = g.time();
				} else if (g.time() - spawnedAt >= 150) {
					in = Input.HD;
				}
			}
			s.advance(in, false);
			if (!danger && !g.over() && g.piece() != null && g.settledTop() < SandRules.VISIBLE_TOP + 3 * SandRules.GRAIN) {
				shoot(panel, out, "danger");
				danger = true;
			}
			if (!waiting && !g.over() && g.waiting() && g.time() > 30_000) {
				// 0.9초 넘게 기다리는 순간 (안내 꼬리표는 0.6초부터 0.15초에 걸쳐 들어온다). 블록이 없는 동안이라 손은 할 일이 없다
				while (g.waiting() && SandPanel.waitedMs(g) < SandPanel.WAIT_HINT_MS + 300) {
					s.advance(null, false);
				}
				if (g.waiting()) {
					shoot(panel, out, "waiting");
					waiting = true;
				}
			}
		}
		s.finishIfOver();
		long overAt = s.overAtNanos();
		now.set(overAt + 450 * MS);
		shoot(panel, out, "over");
		now.set(overAt + (SandFx.OVER_CARD_AT + 700) * MS);
		shoot(panel, out, "result");
		panel.onKey(java.awt.event.KeyEvent.VK_RIGHT, true);
		panel.onKey(java.awt.event.KeyEvent.VK_RIGHT, false);
		shoot(panel, out, "result-same");
		System.out.println("끝 " + s.game().time() / 1000 + "초: 점수 " + s.game().score() + ", 새 기록 " + s.newBest()
				+ ", 기록 " + save.records().sand() + ", 위험 " + danger + ", 기다림 " + waiting);
	}

	// ------------------------------------------------------------------ 도구

	private static SaveData saveWithBest(long best) {
		SaveData save = SaveData.inMemory();
		save.updateRecords(r -> r.withSand(best / 2, 3).withSand(best, 5)); // 두 판: 다음 판이 넘으면 새 기록
		return save;
	}

	private static SaveData settings(java.util.function.UnaryOperator<kr.ac.jbnu.se.tetris.settings.UserSettings> f) {
		SaveData save = SaveData.inMemory();
		save.updateSettings(f);
		return save;
	}

	private static void await(java.util.function.BooleanSupplier done) throws InterruptedException {
		long until = System.nanoTime() + 20_000_000_000L;
		while (!done.getAsBoolean()) {
			if (System.nanoTime() > until) {
				throw new IllegalStateException("기다리다 시간이 다 됨");
			}
			Thread.sleep(2);
		}
	}

	private static void shoot(SandPanel panel, File out, String name) throws Exception {
		for (int[] wh : SIZES) {
			panel.setSize(wh[0], wh[1]);
			BufferedImage img = new BufferedImage(wh[0], wh[1], BufferedImage.TYPE_INT_RGB);
			Graphics2D g = img.createGraphics();
			panel.printAll(g);
			g.dispose();
			ImageIO.write(img, "png", new File(out, "sand-" + name + "-" + wh[0] + ".png"));
		}
	}
}
