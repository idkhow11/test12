package kr.ac.jbnu.se.tetris.ui;

import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.settings.KeyAction;
import kr.ac.jbnu.se.tetris.settings.KeyBindings;
import kr.ac.jbnu.se.tetris.settings.KeyNames;
import kr.ac.jbnu.se.tetris.settings.PlayRecords;

/**
 * 결과 카드 (runs/quality/SPEC.md V9, V10). 진 판은 K.O. 뒤 1초에 뜨고(키는 1.15초부터 받는다). 오류로 멈춘 판은 결과 카드 대신
 * 오류 카드다. 기권한 판의 카드({@link #cardAt} 0, 이유 "기권")는 판을 직접 그리는 화면 확인 도구와 시험에서만 보인다: 게임은
 * 기권하면 결과 카드 없이 바로 메뉴로 간다(창이 판을 멈추고 메뉴로 바꾼다).
 * <ul>
 * <li>제목(승리!/패배/무승부/게임 끝)이 1.25배에서 240ms 에 1배로. 새 기록이면 금색 "새 기록" 꼬리표, 그 아래 줄에 무슨 기록인지
 * (사람 대 AI 는 "최단 승리 0:41" 또는 "3연승", 혼자 연습은 "최장 시간 0:41" 또는 "26줄", {@link #recordDetail}).</li>
 * <li>끝난 이유 · 시간 · 시드 (기준 AI 가 둔 사람 대 AI 판은 전적에 남지 않으므로 "기록 안 함"), 그 아래 큰 숫자 한 줄: 사람 대
 * AI 는 "나 47 : 49 AI" (공격), 혼자 연습은 지운 줄.</li>
 * <li>표: 사람 대 AI 는 두 사람의 값과, 숫자 아래 가운데에서 바깥으로 자라는 얇은 비교 막대(더 나은 쪽이 진하게). 더 나은 쪽의
 * 숫자는 그 사람의 색이다. 막대가 숫자 뒤에 있지 않아 숫자는 언제나 카드 바탕 위에 있다(대비 5.5:1 이상). 적을수록 나은 줄(받은
 * 줄)은 막대 없이 숫자 색만 쓴다. 혼자 연습은 한 줄씩. 줄마다 120 + 40k ms 에 0.5c 아래에서 올라오며 나타나고 숫자는 400ms 동안
 * 세어 올라간다.</li>
 * <li>예측 승률 흐름 (사람 대 AI).</li>
 * <li>고르기: 다시 하기(새 시드) · 같은 판 다시(같은 시드) · 메뉴로. 사용자의 이동 키(←→)로 고르고 확인 키(Enter)로 정한다.
 * 다시 하기의 다른 키(R)는 언제나 다시 하기, 메뉴 키(Esc)는 언제나 메뉴로({@link #confirmKey}). 마우스로 눌러도 된다. 아래 키 안내도
 * 지금 키 묶음을 따른다({@link #keyHints}).</li>
 * </ul>
 * 카드는 260ms 동안 나타나며 0.94배에서 1배로 커진다. 효과를 끄면 크기는 그대로 두고 옅어지기만 한다. 카드 너비는 사람 대 AI 18c,
 * 혼자 연습 13.5c 이고, 키 안내가 들어가지 않으면 그만큼 넓힌다.
 */
final class BattleResult {

	/** 진 판에서 결과 카드가 뜨는 때 (판이 끝난 뒤 ms). 그 전에는 K.O. 를 보인다. */
	static final int CARD_DELAY_MS = 1000;
	/** 진 판에서 결과 카드가 키를 받기 시작하는 때 (판이 끝난 뒤 ms): 판 끝에 누르던 키로 넘어가지 않게. */
	static final int KEYS_MS = 1150;
	/** 고르기: 다시 하기 (같은 설정, 새 시드). */
	static final int RETRY = 0;
	/** 고르기: 같은 판 다시 (같은 설정, 같은 시드). */
	static final int SAME = 1;
	/** 고르기: 메뉴로. */
	static final int MENU = 2;
	/** 표의 줄무늬 (짝수 줄 바탕). */
	private static final Color STRIPE = new Color(255, 255, 255, 8);
	/** 고르기 단추의 글 (왼쪽부터). */
	static final String[] CHOICES = { "다시 하기", "같은 판 다시", "메뉴로" };
	/** 카드가 나타나는 시간 (ms). */
	static final int ENTER_MS = 260;
	/** 숫자가 세어 올라가는 시간 (ms). */
	static final int COUNT_MS = 400;

	private BattleResult() {
	}

	/** 결과 카드가 뜨는 때 (판이 끝난 뒤 ms): 진 판은 K.O. 뒤 1초, 기권·오류는 바로. */
	static long cardAt(GameSession s) {
		return s.forfeited() || s.failure() != null ? 0 : CARD_DELAY_MS;
	}

	/** 결과 카드가 키를 받기 시작하는 때 (판이 끝난 뒤 ms). */
	static long keysAt(GameSession s) {
		return s.forfeited() || s.failure() != null ? 0 : KEYS_MS;
	}

	/**
	 * 결과 카드에서 고른 단추를 정하는 키("확인"): 다시 하기에 묶인 Enter, Enter 가 다시 하기의 키가 아니면 다시 하기의 첫 키.
	 * 다시 하기의 나머지 키(기본 R)는 고른 단추와 상관없이 "다시 하기"다. 모래 화면의 결과 카드도 같은 규칙을 쓴다.
	 */
	static int confirmKey(KeyBindings keys) {
		List<Integer> retry = keys.keys(KeyAction.RETRY);
		return retry.contains(KeyEvent.VK_ENTER) ? KeyEvent.VK_ENTER : retry.get(0);
	}

	/**
	 * 결과 카드 아래의 키 안내 {키, 설명}: "← → 고르기 · Enter 확인 · R 다시 하기 · Esc 메뉴로" (지금 키 묶음의 키 이름). 다시
	 * 하기에 확인 키 말고 다른 키가 없으면 "다시 하기" 칸은 없다. 모래 화면의 결과 카드도 같은 안내를 쓴다.
	 */
	static String[][] keyHints(KeyBindings keys) {
		int confirm = confirmKey(keys);
		List<String[]> out = new ArrayList<>();
		out.add(new String[] { keys.describe(KeyAction.MOVE_LEFT, 1) + " " + keys.describe(KeyAction.MOVE_RIGHT, 1), "고르기" });
		out.add(new String[] { KeyNames.label(confirm), "확인" });
		for (int code : keys.keys(KeyAction.RETRY)) {
			if (code != confirm) {
				out.add(new String[] { KeyNames.label(code), "다시 하기" });
				break;
			}
		}
		out.add(new String[] { keys.describe(KeyAction.MENU, 1), "메뉴로" });
		return out.toArray(new String[0][]);
	}

	/** {@link Theme#keys} 가 그릴 키 안내 한 줄의 너비 (같은 글꼴, 같은 간격). */
	static double keysWidth(Graphics2D g, String[][] keys, int c) {
		FontMetrics fm = g.getFontMetrics(Theme.font(Math.max(13, 0.48 * c), true));
		double total = -0.9 * c;
		for (String[] k : keys) {
			total += fm.stringWidth(k[0]) + 0.6 * c + 0.25 * c + fm.stringWidth(k[1]) + 0.9 * c;
		}
		return total;
	}

	/** 표의 한 줄. {@code lowerBetter} 면 작은 값이 낫다 (받은 줄). */
	private record Row(String label, double a, double b, boolean decimals, boolean lowerBetter) {
	}

	/**
	 * 결과 카드를 그린다.
	 *
	 * @param ms    카드가 뜬 뒤 흐른 시간 (ms)
	 * @param focus 고른 단추 ({@link #RETRY}, {@link #SAME}, {@link #MENU})
	 * @param hits  단추 셋의 자리를 여기에 적는다 (패널 좌표, 마우스용)
	 */
	static void paint(Graphics2D g, GameSession s, int width, int height, int c, long ms, int focus,
			Rectangle2D.Double[] hits, BattleStyle st) {
		boolean vs = s.match.size() == 2;
		double in = Motion.enter(Motion.clamp01(ms / (double) ENTER_MS));
		BattleOverlay.veil(g, width, height, 0.62 * in);
		KeyBindings keys = s.human.keys();
		String[][] hints = keyHints(keys);
		double w = Math.max((vs ? 18 : 13.5) * c, keysWidth(g, hints, c) + 1.6 * c); // 키 안내가 카드 안에 들어가게
		double h = (vs ? 19 : 16.6) * c;
		double x = (width - w) / 2;
		double y = (height - h) / 2;
		double cx = x + w / 2;
		boolean motion = st.m() > 0;
		layoutButtons(hits, cx, y + h - 2.55 * c, c, vs);

		AffineTransform saved = g.getTransform();
		Composite base = BattleFx.alpha(g, in);
		Composite card = g.getComposite();
		if (motion && in < 1) {
			double k = 0.94 + 0.06 * in;
			g.translate(cx, y + h / 2);
			g.scale(k, k);
			g.translate(-cx, -(y + h / 2));
		}
		Color tc = titleColor(s.result());
		Theme.card(g, x, y, w, h, 0.9 * c, Theme.SURFACE, Theme.BORDER);
		Theme.glow(g, x, y, w, h, 0.9 * c, tc, 0.7);
		title(g, s, cx, y + 2.3 * c, c, ms, motion, tc, card);
		String why = why(s);
		String meta = (why.isEmpty() ? "" : why + "  ·  ") + clock(s.match.time()) + "  ·  시드 " + s.seed
				+ (unrecorded(s) ? "  ·  기록 안 함" : "");
		g.setColor(Theme.LABEL);
		Theme.Type.BODY.draw(g, meta, 0.5 * c, cx, y + 3.2 * c, Theme.Align.CENTER);
		double count = Motion.easeOutCubic(Motion.clamp01((ms - 120) / (double) COUNT_MS));
		if (vs) {
			GameSession.PlayerStats a = s.stats(0);
			GameSession.PlayerStats b = s.stats(1);
			hero(g, s, cx, y + 5.6 * c, c, a.attack() * count, b.attack() * count);
			Row[] rows = { new Row("PPS", a.pps(), b.pps(), true, false),
					new Row("APP", app(a), app(b), true, false),
					new Row("지운 줄", a.lines(), b.lines(), false, false),
					new Row("보냄", a.sent(), b.sent(), false, false),
					new Row("받은 줄", a.garbageReceived(), b.garbageReceived(), false, true),
					new Row("최대 콤보", a.maxCombo(), b.maxCombo(), false, false),
					new Row("T-SPIN", a.tspins(), b.tspins(), false, false),
					new Row("테트리스", a.quads(), b.quads(), false, false) };
			table(g, rows, x, w, y + 6.9 * c, c, ms, motion, card);
			if (s.winHistory.size() >= 2) {
				g.setColor(Theme.LABEL);
				Theme.Type.TITLE.draw(g, "예측 승률 흐름", 0.44 * c, x + 1.0 * c, y + 14.15 * c, Theme.Align.LEFT);
				BattleHud.winChart(g, s, s.match.time(), x + 1.0 * c, y + 14.35 * c, w - 2.0 * c, 1.75 * c, true, c);
			}
		} else {
			GameSession.PlayerStats a = s.stats(0);
			soloHero(g, cx, y + 5.6 * c, c, a.lines() * count);
			Row[] rows = { new Row("블록", a.pieces(), 0, false, false), new Row("PPS", a.pps(), 0, true, false),
					new Row("공격", a.attack(), 0, false, false), new Row("APP", app(a), 0, true, false),
					new Row("최대 콤보", a.maxCombo(), 0, false, false), new Row("T-SPIN", a.tspins(), 0, false, false),
					new Row("테트리스", a.quads(), 0, false, false),
					new Row("받은 줄", a.garbageReceived(), 0, false, false) };
			soloTable(g, rows, x, w, y + 6.9 * c, c, ms, motion, card);
		}
		for (int k = 0; k < CHOICES.length; k++) {
			button(g, hits[k], CHOICES[k], k == focus, c);
		}
		Theme.keys(g, hints, cx, y + h - 0.6 * c, c);
		g.setTransform(saved);
		g.setComposite(base);
	}

	/** 판의 시간 "m:ss" (초 아래는 버린다). */
	private static String clock(long ms) {
		long sec = ms / 1000;
		return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60);
	}

	/**
	 * 이 판이 플레이 기록(전적)에 남지 않는가: 기준 AI 가 둔 사람 대 AI 판(모델을 불러오지 못했거나 판 도중 바뀜,
	 * {@code GameSession.recordPlay} 와 같은 조건). lock 을 잡고 부른다.
	 */
	static boolean unrecorded(GameSession s) {
		return s.settings.versusAi() && s.baseline(1);
	}

	/**
	 * 새 기록의 내용 (꼬리표 둘째 줄). 사람 대 AI 는 "최단 승리 0:41" 또는 "3연승", 혼자 연습은 "최장 시간 0:41" 또는 "26줄".
	 * 이 판을 더한 플레이 기록({@link GameSession#records()})에서 이 판의 값과 같은 최고 기록을 찾는다: 이긴 시간이 가장 빠른
	 * 승리와 같으면 최단 승리, 아니면 지금 연승이 가장 긴 연승이면 연승. 혼자 연습은 버틴 시간(ms 까지 같은 판은 사실상 없다)을
	 * 먼저 보고 그다음 지운 줄을 본다(같은 줄 수는 흔하다). 새 기록이 아니거나 알 수 없으면 빈 문자열. lock 을 잡고 부른다.
	 */
	static String recordDetail(GameSession s) {
		if (!s.newBest()) {
			return "";
		}
		PlayRecords r = s.records();
		long t = s.match.time();
		if (s.settings.versusAi()) {
			PlayRecords.Versus v = r.versus(s.settings.difficulty().id());
			if (s.result() == TimedMatch.Result.P1 && v.fastestWinMs() == t) {
				return "최단 승리 " + clock(t);
			}
			return v.streak() >= 2 && v.streak() == v.bestStreak() ? v.streak() + "연승" : "";
		}
		PlayRecords.Solo so = r.solo(PlayRecords.soloSetup(s.settings.gravityCapG(), s.settings.scheduler().json()));
		if (so.bestTimeMs() == t) {
			return "최장 시간 " + clock(t);
		}
		int lines = s.match.player(0).game().linesTotal();
		return so.bestLines() == lines ? lines + "줄" : "";
	}

	private static Color titleColor(TimedMatch.Result r) {
		return switch (r) {
		case P1 -> Theme.OK;
		case P2 -> Theme.DANGER;
		case DRAW -> Theme.TEXT_DIM;
		default -> Theme.ACCENT;
		};
	}

	private static String titleText(TimedMatch.Result r) {
		return switch (r) {
		case P1 -> "승리!";
		case P2 -> "패배";
		case DRAW -> "무승부";
		default -> "게임 끝";
		};
	}

	/** 제목 (1.25배에서 240ms 에 1배로) 과 새 기록 꼬리표 (무슨 기록인지 알면 두 줄: "새 기록" / "최단 승리 0:41"). */
	private static void title(Graphics2D g, GameSession s, double cx, double baseline, int c, long ms, boolean motion,
			Color tc, Composite card) {
		String text = titleText(s.result());
		double k = motion ? 1.25 - 0.25 * Motion.easeOutBack(Motion.clamp01(ms / 240.0)) : 1;
		AffineTransform saved = g.getTransform();
		g.translate(cx, baseline);
		g.scale(k, k);
		g.setColor(tc);
		float tw = Theme.Type.TITLE.draw(g, text, 2.2 * c, 0, 0, Theme.Align.CENTER);
		g.setTransform(saved);
		if (!s.newBest()) {
			return;
		}
		// 새 기록: 240ms 뒤에 튀어나온다
		double u = Motion.clamp01((ms - 240) / 200.0);
		if (u <= 0) {
			return;
		}
		double pk = motion ? 0.6 + 0.4 * Motion.easeOutBack(u) : 1;
		String detail = recordDetail(s);
		Font head = Theme.font(0.5 * c, true);
		Font sub = Theme.font(Math.max(11, 0.42 * c), true);
		g.setFont(head);
		double lw = Theme.textWidth(g, "새 기록");
		if (!detail.isEmpty()) {
			g.setFont(sub);
			lw = Math.max(lw, Theme.textWidth(g, detail));
		}
		double chipW = lw + 0.9 * c;
		double chipH = detail.isEmpty() ? 0.9 * c : 1.5 * c;
		double arc = Math.min(chipH, 0.9 * c);
		double chipX = cx + tw / 2 + 0.45 * c;
		double chipCy = baseline - 0.78 * c;
		g.translate(chipX + chipW / 2, chipCy);
		g.scale(pk, pk);
		BattleFx.alpha(g, u);
		Theme.card(g, -chipW / 2, -chipH / 2, chipW, chipH, arc, Theme.GOLD, null);
		Theme.glow(g, -chipW / 2, -chipH / 2, chipW, chipH, arc, Theme.GOLD, 0.6);
		g.setColor(Theme.BG_TOP);
		g.setFont(head);
		if (detail.isEmpty()) {
			Theme.text(g, "새 기록", 0, 0.18 * c, Theme.Align.CENTER);
		} else {
			Theme.text(g, "새 기록", 0, -0.12 * c, Theme.Align.CENTER);
			g.setFont(sub);
			Theme.text(g, detail, 0, 0.47 * c, Theme.Align.CENTER);
		}
		g.setTransform(saved);
		g.setComposite(card);
	}

	/** 사람 대 AI 의 큰 숫자 한 줄: "나 47 : 49 AI" (공격), 위에 작은 "공격". */
	private static void hero(Graphics2D g, GameSession s, double cx, double baseline, int c, double a, double b) {
		g.setColor(Theme.LABEL);
		Theme.Type.TITLE.draw(g, "공격", 0.5 * c, cx, baseline - 1.4 * c, Theme.Align.CENTER);
		g.setFont(Theme.display(1.6 * c));
		g.setColor(Theme.TEXT_DIM);
		Theme.text(g, ":", cx, baseline - 0.08 * c, Theme.Align.CENTER);
		g.setColor(Theme.P1);
		float wa = Theme.drawNumber(g, String.valueOf((long) Math.floor(a + 1e-9)), cx - 0.55 * c, baseline,
				Theme.Align.RIGHT);
		g.setColor(Theme.P2);
		float wb = Theme.drawNumber(g, String.valueOf((long) Math.floor(b + 1e-9)), cx + 0.55 * c, baseline,
				Theme.Align.LEFT);
		g.setColor(Theme.P1);
		Theme.Type.TITLE.draw(g, s.name(0), 0.62 * c, cx - 0.55 * c - wa - 0.3 * c, baseline, Theme.Align.RIGHT);
		g.setColor(Theme.P2);
		Theme.Type.TITLE.draw(g, s.name(1), 0.62 * c, cx + 0.55 * c + wb + 0.3 * c, baseline, Theme.Align.LEFT);
	}

	/** 혼자 연습의 큰 숫자 한 줄: 지운 줄. */
	private static void soloHero(Graphics2D g, double cx, double baseline, int c, double lines) {
		g.setColor(Theme.LABEL);
		Theme.Type.TITLE.draw(g, "지운 줄", 0.5 * c, cx, baseline - 1.4 * c, Theme.Align.CENTER);
		g.setFont(Theme.display(1.6 * c));
		g.setColor(Theme.P1);
		Theme.drawNumber(g, String.valueOf((long) Math.floor(lines + 1e-9)), cx, baseline, Theme.Align.CENTER);
	}

	/**
	 * 사람 대 AI 의 표: 가운데에 이름, 0.27w·0.73w 에 두 사람의 값, 숫자 아래(기준선 0.12c 밑, 0.16c 굵기)에 가운데에서 바깥으로
	 * 자라는 비교 막대. 더 나은 쪽은 막대가 진하고 숫자가 그 사람의 색이다. 숫자는 막대 위에 있지 않으므로 언제나 카드 바탕(줄무늬)
	 * 위에서 읽힌다. 적을수록 나은 줄(받은 줄)은 막대 길이가 나은 쪽과 거꾸로 되므로 막대 없이 숫자 색만 쓴다.
	 */
	private static void table(Graphics2D g, Row[] rows, double x, double w, double first, int c, long ms,
			boolean motion, Composite card) {
		double step = 0.9 * c;
		double cx = x + w / 2;
		double gap = 1.5 * c;
		double full = w / 2 - gap - 1.1 * c; // 가장 긴 막대도 카드 안쪽 줄무늬 안에서 끝난다
		double barH = Math.max(2, 0.16 * c);
		for (int k = 0; k < rows.length; k++) {
			Row r = rows[k];
			long start = 120 + 40L * k;
			double u = Motion.easeOutCubic(Motion.clamp01((ms - start) / 200.0));
			if (u <= 0) {
				continue;
			}
			double e = Motion.easeOutCubic(Motion.clamp01((ms - start) / (double) COUNT_MS));
			double by = first + k * step + (motion ? 0.5 * c * (1 - u) : 0);
			g.setComposite(card);
			BattleFx.alpha(g, u);
			if (k % 2 == 0) {
				g.setColor(STRIPE);
				g.fill(new RoundRectangle2D.Double(x + 0.8 * c, by - 0.6 * c, w - 1.6 * c, step, 0.3 * c, 0.3 * c));
			}
			int better = r.a() == r.b() ? -1 : (r.a() > r.b()) != r.lowerBetter() ? 0 : 1;
			double sum = r.a() + r.b();
			if (sum > 0 && !r.lowerBetter()) {
				double barY = by + 0.12 * c;
				double la = r.a() / sum * full * e;
				double lb = r.b() / sum * full * e;
				g.setColor(Fx.alpha(Theme.P1, better == 0 ? 0.9 : 0.35));
				g.fill(new RoundRectangle2D.Double(cx - gap - la, barY, la, barH, barH, barH));
				g.setColor(Fx.alpha(Theme.P2, better == 1 ? 0.9 : 0.35));
				g.fill(new RoundRectangle2D.Double(cx + gap, barY, lb, barH, barH, barH));
			}
			g.setColor(Theme.LABEL);
			Theme.Type.LABEL.draw(g, r.label(), 0.5 * c, cx, by, Theme.Align.CENTER);
			g.setColor(better == 0 ? Theme.P1 : Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, value(r.a(), r.decimals(), e), 0.75 * c, x + w * 0.27, by, Theme.Align.CENTER);
			g.setColor(better == 1 ? Theme.P2 : Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, value(r.b(), r.decimals(), e), 0.75 * c, x + w * 0.73, by, Theme.Align.CENTER);
		}
		g.setComposite(card);
	}

	/** 혼자 연습의 표: 이름은 왼쪽, 값은 오른쪽. */
	private static void soloTable(Graphics2D g, Row[] rows, double x, double w, double first, int c, long ms,
			boolean motion, Composite card) {
		double step = 0.9 * c;
		for (int k = 0; k < rows.length; k++) {
			Row r = rows[k];
			long start = 120 + 40L * k;
			double u = Motion.easeOutCubic(Motion.clamp01((ms - start) / 200.0));
			if (u <= 0) {
				continue;
			}
			double e = Motion.easeOutCubic(Motion.clamp01((ms - start) / (double) COUNT_MS));
			double by = first + k * step + (motion ? 0.5 * c * (1 - u) : 0);
			g.setComposite(card);
			BattleFx.alpha(g, u);
			if (k % 2 == 0) {
				g.setColor(STRIPE);
				g.fill(new RoundRectangle2D.Double(x + 0.8 * c, by - 0.6 * c, w - 1.6 * c, step, 0.3 * c, 0.3 * c));
			}
			g.setColor(Theme.LABEL);
			Theme.Type.LABEL.draw(g, r.label(), 0.5 * c, x + 1.4 * c, by, Theme.Align.LEFT);
			g.setColor(Theme.TEXT);
			Theme.Type.NUMERIC.draw(g, value(r.a(), r.decimals(), e), 0.75 * c, x + w - 1.4 * c, by, Theme.Align.RIGHT);
		}
		g.setComposite(card);
	}

	/** 세어 올라가는 값: 진행도 e (0~1) 만큼. 소수는 둘째 자리까지. */
	private static String value(double v, boolean decimals, double e) {
		double shown = v * e;
		return decimals ? String.format(Locale.ROOT, "%.2f", shown) : String.valueOf((long) Math.floor(shown + 1e-9));
	}

	/** 블록당 공격. */
	private static double app(GameSession.PlayerStats p) {
		return p.pieces() == 0 ? 0 : (double) p.attack() / p.pieces();
	}

	// ------------------------------------------------------------------ 고르기 단추

	/** 단추 셋의 자리 (가운데 맞춤, 위쪽 {@code top}). 있던 사각형을 고쳐 쓴다. */
	private static void layoutButtons(Rectangle2D.Double[] hits, double cx, double top, int c, boolean vs) {
		double bw = (vs ? 4.6 : 3.95) * c;
		double bh = 1.1 * c;
		double gap = 0.35 * c;
		double left = cx - (3 * bw + 2 * gap) / 2;
		for (int k = 0; k < CHOICES.length; k++) {
			if (hits[k] == null) {
				hits[k] = new Rectangle2D.Double();
			}
			hits[k].setRect(left + k * (bw + gap), top, bw, bh);
		}
	}

	/** 단추 하나: 고른 단추는 강조색 그라데이션과 빛, 나머지는 어두운 바탕에 테두리. */
	private static void button(Graphics2D g, Rectangle2D.Double r, String label, boolean focused, int c) {
		double arc = r.height;
		if (focused) {
			g.setPaint(new GradientPaint((float) r.x, (float) r.y, Theme.ACCENT, (float) r.getMaxX(), (float) r.getMaxY(),
					Theme.ACCENT_2));
			g.fill(new RoundRectangle2D.Double(r.x, r.y, r.width, r.height, arc, arc));
			Theme.glow(g, r.x, r.y, r.width, r.height, arc, Theme.ACCENT_2, 0.8);
			g.setColor(Color.WHITE);
		} else {
			Theme.card(g, r.x, r.y, r.width, r.height, arc, Theme.withAlpha(Theme.BG_TOP, 210), Theme.BORDER);
			g.setColor(Theme.TEXT_DIM);
		}
		Theme.Type.TITLE.draw(g, label, 0.5 * c, r.getCenterX(), r.getCenterY() + 0.18 * c, Theme.Align.CENTER);
	}

	// ------------------------------------------------------------------ 끝난 이유

	/**
	 * 결과 카드의 끝난 이유: 기권, 또는 진 까닭 ("꼭대기까지 쌓임", "방해 줄에 밀려 넘침"). 사람 대 AI 에서 내가 졌으면 그대로,
	 * AI 가 졌으면 "AI가 꼭대기까지 쌓임" 처럼 이름을 붙인다. 혼자 연습은 그대로.
	 */
	static String why(GameSession s) {
		if (s.forfeited()) {
			return "기권";
		}
		TimedMatch m = s.match;
		GameModel a = m.player(0).game();
		if (m.size() == 1) {
			return topout(a.topout());
		}
		GameModel b = m.player(1).game();
		if (a.isOver() && b.isOver()) {
			return "동시에 끝남";
		}
		if (a.isOver()) {
			return topout(a.topout());
		}
		String t = topout(b.topout());
		return t.isEmpty() ? "" : s.name(1) + "가 " + t; // P2 의 이름은 "AI" (받침 없음)
	}

	/** 진 까닭 (블록 아웃·락 아웃은 꼭대기까지 쌓임, 가비지 아웃은 방해 줄에 밀려 넘침). 다른 화면과 같은 말("방해 줄")을 쓴다. */
	private static String topout(GameModel.Topout t) {
		return switch (t) {
		case BLOCK_OUT, LOCK_OUT -> "꼭대기까지 쌓임";
		case GARBAGE_OUT -> "방해 줄에 밀려 넘침";
		default -> "";
		};
	}
}
