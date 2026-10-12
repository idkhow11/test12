package kr.ac.jbnu.se.tetris.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import kr.ac.jbnu.se.tetris.ai.AiCatalog;
import kr.ac.jbnu.se.tetris.ai.AiController;
import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Grid;

/**
 * 실제 시간으로 판을 돌리며 화면이 겪는 것을 잰다 (docs/GAME.md 5장의 "화면 스레드는 멈추지 않는다"). 화면처럼 15ms 마다
 * {@link GameSession#lock} 을 잡고 판을 읽는 스레드를 두고, 잠금을 얻기까지 기다린 시간(화면이 멈춘 시간)과 그때 판의 시계가
 * 실제 시간보다 늦은 정도(보드가 멈칫한 시간), 그 둘의 합(그 프레임이 보여 준 판이 실제보다 늦은 정도)을 모은다. 미리 고르기가
 * 맞은 비율도 센다. 판은 사람 자리를 비워 둔 사람 대 AI 다(사람이 가만히 있으므로 블록이 쌓여 언젠가 지고, 그러면 같은 설정으로
 * 다음 판을 이어 간다). 실행 ({@code gradlew testClasses} 뒤):
 * {@code java -cp <시험 클래스 경로> kr.ac.jbnu.se.tetris.ui.RealtimeBench [초] [단계] [AI 속도 %] [연산 스레드]}.
 * 판 기록은 언제나 끈다(사람 판 기록 폴더에 섞이지 않게).
 */
public final class RealtimeBench {

	private RealtimeBench() {
	}

	public static void main(String[] args) throws Exception {
		System.setProperty("tetris.records", "off"); // 사람이 두지 않은 판이 runs/human 에 남지 않게
		int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 60;
		int level = args.length > 1 ? Integer.parseInt(args[1]) : 5;
		int speed = args.length > 2 ? Integer.parseInt(args[2]) : 100;
		int threads = args.length > 3 ? Integer.parseInt(args[3]) : AiCatalog.GAME_THREADS;
		Path models = Path.of(System.getProperty("tetris.models", "models"));
		AiCatalog catalog = AiCatalog.load(models, threads);
		catalog.preload();
		AiCatalog.Persona neutral = catalog.personas().get(0);
		GameSession.Settings st = GameSession.Settings.versus(catalog.difficulties().get(level - 1), neutral, speed);
		List<Double> waits = new ArrayList<>();
		List<Double> lags = new ArrayList<>();
		List<Double> stale = new ArrayList<>();
		long planned = 0;
		long prefetched = 0;
		long seed = 980001;
		int games = 0;
		long until = System.nanoTime() + seconds * 1_000_000_000L;
		while (System.nanoTime() < until) {
			GameSession s = new GameSession(st, catalog, seed + games, x -> {
			});
			games++;
			s.start();
			long start = System.nanoTime();
			long sum = 0;
			while (System.nanoTime() < until) {
				Thread.sleep(15);
				long t0 = System.nanoTime();
				boolean over;
				synchronized (s.lock) {
					long t1 = System.nanoTime();
					double wait = (t1 - t0) / 1e6;
					waits.add(wait);
					long expected = (t1 - start) / 1_000_000 - GameSession.COUNTDOWN_MS;
					if (expected > 0 && !s.over()) {
						double lag = Math.max(0, expected - s.match.time());
						lags.add(lag);
						stale.add(wait + lag);
					}
					for (int i = 0; i < s.match.size(); i++) { // 그리기처럼 판을 읽는다
						GameModel g = s.match.player(i).game();
						for (int r = 0; r < Grid.HEIGHT; r++) {
							for (int c = 0; c < Grid.WIDTH; c++) {
								sum += g.cell(r, c);
							}
						}
						sum += g.preview().size();
					}
					over = s.over();
				}
				if (over) {
					break;
				}
			}
			s.stop();
			for (int i = 0; i < s.match.size(); i++) {
				AiController ai = s.ai(i);
				if (ai != null) {
					planned += ai.planned();
					prefetched += ai.prefetched();
				}
			}
			if (sum < 0) {
				System.out.println(sum);
			}
		}
		catalog.close();
		System.out.printf(Locale.ROOT, "사람(가만히) 대 %d단계 AI 속도 %d%%, 연산 스레드 %d, %d초, 판 %d개%n", level, speed,
				threads, seconds, games);
		report("화면이 잠금을 기다린 시간 (ms)", waits);
		report("판의 시계가 늦은 정도 (ms)", lags);
		report("프레임이 실제보다 늦은 정도 = 둘의 합 (ms)", stale);
		System.out.printf(Locale.ROOT, "미리 고른 답을 쓴 블록: %d / %d (%.1f%%)%n", prefetched, planned,
				planned == 0 ? 0 : 100.0 * prefetched / planned);
		System.exit(0);
	}

	private static void report(String name, List<Double> xs) {
		if (xs.isEmpty()) {
			System.out.println(name + ": 없음");
			return;
		}
		List<Double> s = new ArrayList<>(xs);
		Collections.sort(s);
		double sum = 0;
		int over16 = 0;
		for (double x : s) {
			sum += x;
			over16 += x > 16 ? 1 : 0;
		}
		System.out.printf(Locale.ROOT, "%s: %d번, 평균 %.2f, 중앙 %.2f, p95 %.2f, p99 %.2f, 최대 %.2f, 16ms 넘음 %.1f%%%n", name,
				s.size(), sum / s.size(), at(s, 0.5), at(s, 0.95), at(s, 0.99), s.get(s.size() - 1), 100.0 * over16 / s.size());
	}

	private static double at(List<Double> sorted, double q) {
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * sorted.size())));
	}
}
