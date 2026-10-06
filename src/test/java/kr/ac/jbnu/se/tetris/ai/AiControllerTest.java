package kr.ac.jbnu.se.tetris.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import kr.ac.jbnu.se.tetris.core.GameModel;
import kr.ac.jbnu.se.tetris.core.Op;
import kr.ac.jbnu.se.tetris.core.PieceType;
import kr.ac.jbnu.se.tetris.core.TimedMatch;
import kr.ac.jbnu.se.tetris.core.TimedPlayer;
import kr.ac.jbnu.se.tetris.core.TimedPlayer.Input;

/** AI 조작기 (PLAN 4.3, docs/GAME.md 5장). */
class AiControllerTest {

	private static Brain baseline(long seed) {
		return new Brain(new BaselineScorer(), 0.0, null, Chooser.rng(seed, 1));
	}

	/** 한 틱: 조작기에게 묻고 그 조작으로 진행한다. */
	private static GameModel.LockResult tick(AiController ai) {
		return ai.player().tick(ai.poll());
	}

	@Test
	void withoutGravityTheAiPlaysExactlyTheTimelessChoices() {
		// 중력이 없으면 생각이 끝날 때 블록은 스폰 위치에 있으므로, 시간 없는 모드와 같은 배치를 같은 순서로 둔다.
		long seed = 77;
		GameModel timeless = new GameModel(seed, 1, GameModel.Scheduler.S_MIX);
		Brain plain = baseline(seed);
		TimedPlayer p = new TimedPlayer(new GameModel(seed, 1, GameModel.Scheduler.S_MIX), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, baseline(seed), 50, 30);
		for (int piece = 0; piece < 40 && !timeless.isOver(); piece++) {
			timeless.apply(plain.decide(timeless, timeless.placements(), Opponent.NONE).placement());
			int ticks = 0;
			while (p.game().pieces() == piece && !p.game().isOver()) {
				tick(ai);
				assertTrue(++ticks < 10_000, "블록 하나에 10초 넘게 걸림");
			}
			assertTrue(timeless.grid().sameOccupancy(p.game().grid()), (piece + 1) + " 번째 블록 뒤 보드가 다름");
			assertEquals(timeless.hold(), p.game().hold());
		}
		assertEquals(0, ai.failed(), "중력이 없으면 목표를 바꿀 일이 없다");
		assertTrue(ai.planned() >= 40);
	}

	@Test
	void respectsThinkDelayAndInputInterval() {
		TimedPlayer p = new TimedPlayer(new GameModel(3, 1, GameModel.Scheduler.NONE), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, baseline(3), 300, 10); // 100ms 간격
		List<Long> inputTicks = new ArrayList<>();
		while (p.game().pieces() == 0) {
			Input in = ai.poll();
			if (in != null) {
				inputTicks.add(p.ticks());
			}
			p.tick(in);
		}
		assertEquals(300L, inputTicks.get(0), "300 틱(0 부터) 동안 생각하고 다음 틱에 첫 입력");
		for (int i = 1; i < inputTicks.size(); i++) {
			assertEquals(100L, inputTicks.get(i) - inputTicks.get(i - 1), "입력 사이 100ms");
		}
	}

	@Test
	void holdPlacementsPressHoldFirst() {
		// 홀드 칸에 I 가 남는 배치만 좋아하는 점수: 시드 0(현재 I, 다음 S)에서는 S 를 두고 I 를 홀드하는 배치를 고른다.
		PlacementScorer likesHeldI = new PlacementScorer() {
			@Override
			public double[] score(List<Afterstate> candidates, Opponent opponent) {
				double[] out = new double[candidates.size()];
				for (int i = 0; i < out.length; i++) {
					out[i] = candidates.get(i).hold() == PieceType.I ? 1 : 0;
				}
				return out;
			}

			@Override
			public String name() {
				return "held-I";
			}
		};
		TimedPlayer p = new TimedPlayer(new GameModel(0, 1, GameModel.Scheduler.NONE), 0);
		AiController ai = new AiController(p, () -> Opponent.NONE, new Brain(likesHeldI, 0, null, Chooser.rng(0, 1)),
				0, 1000);
		assertEquals(Input.H, ai.poll());
		p.tick(Input.H);
		while (p.game().pieces() == 0) {
			tick(ai);
		}
		assertEquals(PieceType.I, p.game().hold());
		assertEquals(0, ai.failed());
	}

	@Test
	void fastGravityForcesReplansButThePlayKeepsGoing() {
		TimedMatch m = new TimedMatch(11, 1, GameModel.Scheduler.NONE, 12000); // 처음부터 30초마다 오르는 중력
		TimedPlayer p = m.player(0);
		AiController ai = new AiController(p, () -> Opponent.NONE, baseline(11), 600, 2); // 아주 느린 손
		while (!m.isOver() && m.time() < 300_000) {
			m.tick(ai.poll());
		}
		assertTrue(p.game().pieces() > 10, "블록을 계속 둔다: " + p.game().pieces());
		assertTrue(ai.failed() > 0, "느린 손으로는 목표를 바꿔야 할 때가 있다");
		assertTrue(ai.failureRate() <= 1.0);
	}

	@Test
	void twoAisPlayAFullTimedMatch() {
		TimedMatch m = new TimedMatch(5, 2, GameModel.Scheduler.NONE, 12000);
		AiController a = new AiController(m.player(0), () -> Opponent.of(m.player(1).game()), baseline(5), 100, 25);
		AiController b = new AiController(m.player(1), () -> Opponent.of(m.player(0).game()),
				new Brain(new BaselineScorer(), 0.0, null, Chooser.rng(5, 2)), 400, 8);
		while (!m.isOver() && m.time() < 1_200_000) {
			m.tick(a.poll(), b.poll());
		}
		assertTrue(m.isOver(), "20분 안에 끝난다");
		assertNotNull(m.result());
		assertTrue(m.player(0).game().sentTotal() + m.player(1).game().sentTotal() > 0);
	}

	@Test
	void firstInputTurnsPathsIntoInputs() {
		assertEquals(Input.HD, AiController.firstInput(List.of()));
		assertEquals(Input.HD, AiController.firstInput(List.of(Op.D, Op.D)), "내리기뿐이면 하드드롭");
		assertEquals(Input.D, AiController.firstInput(List.of(Op.D, Op.CW)), "끼워 넣기 전에 내려가야 하면 한 칸씩");
		assertEquals(Input.CCW, AiController.firstInput(List.of(Op.CCW, Op.D)));
	}
}
