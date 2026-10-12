package kr.ac.jbnu.se.tetris.audio;

import java.util.Arrays;

/**
 * 작은 믹서: 지금 울리는 소리(목소리)들을 스테레오 float 로 섞는다. 믹서 스레드 하나만 쓴다(동기화하지 않는다).
 * <p>
 * 동시에 울리는 소리는 {@link #MAX_VOICES} 개까지다. 같은 소리가 {@link Sfx#maxVoices} 개를 넘으면 가장 오래된 것을, 전체가
 * 한도를 넘으면 중요도가 가장 낮고(같으면 가장 오래된) 것을 양보시킨다. 새 소리가 그보다도 덜 중요하면 새 소리를 버린다.
 * 양보한 소리는 뚝 끊지 않고 {@link #FADE_FRAMES} 동안 줄여서 딸깍 소리가 나지 않게 한다.
 * <p>
 * 소리마다 크기 배율과 읽는 빠르기(음 높이를 조금 바꿀 때, 표본 사이는 곧게 이어 읽는다)가 있고, 좌우 위치는 어디에 두어도
 * 전체 힘이 같다(가운데는 양쪽 모두 원래 크기). <b>덕킹</b>: 큰 사건 소리({@link Sfx#duckDb(int)} 가 0 보다 큰 것)가
 * 시작한 지 {@link #DUCK_HOLD_MS} 가 지나지 않았으면, 보상·경고 묶음({@link Sfx.Bus#duckable()})의 다른 소리를 그 양만큼
 * 줄인다. 이미 울리던 소리는 {@link #DUCK_ATTACK_MS} 에 걸쳐 줄고, 줄어 있는 동안 새로 시작한 소리는 처음부터 줄인 크기로
 * 난다. 큰 사건 소리끼리는 더 큰 쪽만 작은 쪽을 줄인다. 다 지나면 {@link Sfx#duckReleaseMs()} 에 걸쳐 다시 커진다. 위험
 * 심장 소리는 보상 소리가 시작한 지 {@link #DANGER_HOLD_MS} 안이면 {@link #DANGER_DUCK_DB} 만큼 줄인다. 이 크기는 블록
 * ({@link #render} 한 번)마다 정하고 블록 안에서는 곧게 옮겨 가므로 지지직거리지 않는다.
 */
final class Voices {

	/** 동시에 울리는 소리 수 (사라지는 중인 것은 세지 않는다). */
	static final int MAX_VOICES = 8;
	/** 양보한 소리가 사라지는 시간 (약 3.6ms). */
	static final int FADE_FRAMES = 160;
	/** 큰 사건 소리가 다른 소리를 줄이는 동안 (ms, 그 소리가 시작한 때부터). */
	static final int DUCK_HOLD_MS = 300;
	/** 이미 울리던 소리가 줄어드는 시간 상수 (ms). */
	static final double DUCK_ATTACK_MS = 15;
	/** 위험 심장 소리를 줄이는 동안 (ms, 보상 소리가 시작한 때부터). */
	static final int DANGER_HOLD_MS = 400;
	/** 보상 소리 직후 위험 심장 소리를 줄이는 양 (dB). */
	static final double DANGER_DUCK_DB = 6;
	/** 사라지는 중인 소리까지 담는 자리. */
	private static final int SLOTS = MAX_VOICES * 2;
	private static final long DUCK_HOLD_FRAMES = (long) DUCK_HOLD_MS * Synth.RATE / 1000;
	private static final long DANGER_HOLD_FRAMES = (long) DANGER_HOLD_MS * Synth.RATE / 1000;
	private static final float SQRT2 = (float) Math.sqrt(2);

	/** 울리는 소리 하나. */
	private static final class Voice {
		Sfx key;
		float[] pcm;
		double pos; // 읽는 자리 (표본, 소수 부분은 이어 읽기)
		double rate = 1; // 한 프레임에 나아가는 표본 수
		float gain = 1;
		int delay; // 시작까지 남은 프레임
		float left;
		float right;
		int fade = -1; // 0 이상이면 사라지는 중: 남은 프레임
		long serial; // 시작 순서 (작을수록 오래됨)
		long startFrame; // 소리가 나기 시작하는(또는 시작한) 프레임
		double ducks; // 이 소리가 다른 소리를 줄이는 양 (dB, 0 이면 줄이지 않음)
		double duckDb; // 지금 이 소리를 줄이고 있는 양 (dB)
		double releaseMs = Sfx.DUCK_RELEASE_MS; // 줄인 것이 풀리는 시간 상수
		boolean fresh = true; // 아직 한 프레임도 내지 않음
	}

	private final Voice[] slots = new Voice[SLOTS];
	private long serial;
	/** 지금까지 섞은 프레임 수 (덕킹의 시간 기준). */
	private long frame;

	/** 소리 하나를 시작한다 (원래 크기, 원래 빠르기, 덕킹은 세기 {@link Sfx#base} 기준). */
	boolean start(Sfx key, float[] pcm, float pan, int delayFrames) {
		return start(key, key.base, pcm, pan, delayFrames, 1f, 1.0);
	}

	/** 크기 배율 {@code gain} 과 빠르기 {@code rate}(1 이 원래, 2^(cents/1200))로 소리 하나를 시작한다. */
	boolean start(Sfx key, float[] pcm, float pan, int delayFrames, float gain, double rate) {
		return start(key, key.base, pcm, pan, delayFrames, gain, rate);
	}

	/**
	 * 소리 하나를 시작한다.
	 *
	 * @param intensity   요청의 세기 (이 소리가 다른 소리를 줄이는지 정한다: 테트리스, T-spin 더블 등)
	 * @param pan         -1(왼쪽) ~ 1(오른쪽). 가운데면 양쪽 모두 원래 크기
	 * @param delayFrames 이만큼 뒤에 소리가 나기 시작한다
	 * @param gain        크기 배율
	 * @param rate        읽는 빠르기 (1 이 원래 높이). 0 이하나 NaN 이면 1
	 * @return 자리가 없고 새 소리가 덜 중요해서 버렸으면 false
	 */
	boolean start(Sfx key, int intensity, float[] pcm, float pan, int delayFrames, float gain, double rate) {
		int live = 0;
		int same = 0;
		Voice oldestSame = null;
		Voice weakest = null;
		for (Voice v : slots) {
			if (v == null || v.fade >= 0) {
				continue;
			}
			live++;
			if (v.key == key) {
				same++;
				if (oldestSame == null || v.serial < oldestSame.serial) {
					oldestSame = v;
				}
			}
			if (weakest == null || v.key.priority < weakest.key.priority
					|| v.key.priority == weakest.key.priority && v.serial < weakest.serial) {
				weakest = v;
			}
		}
		if (same >= key.maxVoices) {
			oldestSame.fade = FADE_FRAMES; // 같은 소리는 새 것으로 바꾼다 (겹겹이 쌓이지 않게)
		} else if (live >= MAX_VOICES) {
			if (weakest.key.priority > key.priority) {
				return false;
			}
			weakest.fade = FADE_FRAMES;
		}
		Voice v = new Voice();
		v.key = key;
		v.pcm = pcm;
		v.delay = Math.max(0, delayFrames);
		v.startFrame = frame + v.delay;
		v.gain = Float.isFinite(gain) ? Math.max(0f, gain) : 1f;
		v.rate = rate > 0 && rate < 8 ? rate : 1.0;
		v.ducks = key.duckDb(intensity);
		float p = Float.isNaN(pan) ? 0 : Math.max(-1, Math.min(1, pan));
		if (p == 0) {
			v.left = 1;
			v.right = 1;
		} else { // 같은 힘으로: L = √2·cos θ, R = √2·sin θ, θ = (p + 1)π/4 (가운데는 1, 1)
			double theta = (p + 1) * Math.PI / 4;
			v.left = (float) (SQRT2 * Math.cos(theta));
			v.right = (float) (SQRT2 * Math.sin(theta));
		}
		v.serial = serial++;
		slots[freeSlot()] = v;
		return true;
	}

	/** 빈 자리. 없으면(아주 짧은 사이에 양보가 많았을 때) 거의 다 사라진 소리의 자리를 쓴다. */
	private int freeSlot() {
		int best = -1;
		for (int i = 0; i < SLOTS; i++) {
			Voice v = slots[i];
			if (v == null) {
				return i;
			}
			if (v.fade >= 0 && (best < 0 || v.fade < slots[best].fade)) {
				best = i;
			}
		}
		if (best < 0) { // 생기지 않는다(사라지는 중이 아닌 소리는 MAX_VOICES 개를 넘지 않는다). 그래도 가장 오래된 자리를.
			best = 0;
			for (int i = 1; i < SLOTS; i++) {
				if (slots[i].serial < slots[best].serial) {
					best = i;
				}
			}
		}
		return best;
	}

	/**
	 * {@code frames} 만큼 섞어 {@code out} 에 덮어쓴다(스테레오 교차: 왼, 오, 왼, 오, …). 끝난 소리는 자리를 비운다. 덕킹의
	 * 크기는 이 블록의 시작에서 정하고 블록 안에서 곧게 옮겨 간다.
	 */
	void render(float[] out, int frames) {
		Arrays.fill(out, 0, frames * 2, 0f);
		double blockMs = frames * 1000.0 / Synth.RATE;
		boolean rewardYoung = rewardStartedWithin(DANGER_HOLD_FRAMES);
		for (int s = 0; s < SLOTS; s++) {
			Voice v = slots[s];
			if (v == null) {
				continue;
			}
			boolean fresh = v.fresh;
			double was = v.duckDb;
			double now = nextDuck(v, rewardYoung, blockMs);
			if (fresh) {
				was = now; // 줄어 있는 동안 시작한 소리는 처음부터 줄인 크기로
			}
			float g0 = v.gain * dbToGain(was);
			float g1 = v.gain * dbToGain(now);
			int f = 0;
			if (v.delay > 0) {
				f = Math.min(v.delay, frames);
				v.delay -= f;
			}
			float[] pcm = v.pcm;
			int len = pcm.length;
			double pos = v.pos;
			double rate = v.rate;
			for (; f < frames; f++) {
				if (pos >= len) {
					break;
				}
				int i = (int) pos;
				float x = pcm[i];
				double frac = pos - i;
				if (frac != 0) {
					float next = i + 1 < len ? pcm[i + 1] : 0f;
					x += (float) ((next - x) * frac);
				}
				pos += rate;
				if (v.fade >= 0) {
					if (v.fade == 0) {
						pos = len;
						break;
					}
					x *= v.fade / (float) FADE_FRAMES;
					v.fade--;
				}
				x *= g0 + (g1 - g0) * (f + 1) / frames;
				out[2 * f] += x * v.left;
				out[2 * f + 1] += x * v.right;
				v.fresh = false;
			}
			v.pos = pos;
			if (pos >= len) {
				slots[s] = null;
			}
		}
		frame += frames;
	}

	/**
	 * 이 블록 끝에서 소리 {@code v} 를 줄일 양(dB)을 정해 {@code v.duckDb} 에 넣고 돌려준다. 아직 소리를 내지 않은 소리는
	 * 목표 크기에서 바로 시작하고, 울리던 소리는 시간 상수로 옮겨 간다.
	 */
	private double nextDuck(Voice v, boolean rewardYoung, double blockMs) {
		double target = 0;
		double release = v.releaseMs;
		if (v.key.bus.duckable()) {
			for (Voice d : slots) {
				if (d == null || d == v || d.fade >= 0 || d.ducks <= v.ducks || d.ducks <= target) {
					continue;
				}
				long age = frame - d.startFrame;
				if (age >= 0 && age < DUCK_HOLD_FRAMES) {
					target = d.ducks;
					release = d.key.duckReleaseMs();
				}
			}
			if (v.key == Sfx.DANGER && rewardYoung && DANGER_DUCK_DB > target) {
				target = DANGER_DUCK_DB;
				release = Sfx.DUCK_RELEASE_MS;
			}
		}
		if (target > 0) {
			v.releaseMs = release;
		}
		double cur = v.duckDb;
		double next;
		if (v.fresh) {
			next = target;
		} else {
			double tau = target > cur ? DUCK_ATTACK_MS : v.releaseMs;
			next = target + (cur - target) * Math.exp(-blockMs / tau);
			if (next < 0.01 && target == 0) {
				next = 0;
			}
		}
		v.duckDb = next;
		return next;
	}

	/** 보상 묶음의 소리가 시작한 지 {@code frames} 가 지나지 않았는가 (사라지는 중인 것은 빼고). */
	private boolean rewardStartedWithin(long frames) {
		for (Voice d : slots) {
			if (d == null || d.fade >= 0 || d.key.bus != Sfx.Bus.REWARD) {
				continue;
			}
			long age = frame - d.startFrame;
			if (age >= 0 && age < frames) {
				return true;
			}
		}
		return false;
	}

	private static float dbToGain(double db) {
		return db == 0 ? 1f : (float) Math.pow(10, -db / 20);
	}

	/** 울리는 소리를 모두 짧게 줄여 끝낸다 (음소거). */
	void releaseAll() {
		for (Voice v : slots) {
			if (v != null && v.fade < 0) {
				v.fade = FADE_FRAMES;
			}
		}
	}

	/** 아무 소리도 없는가 (사라지는 중인 것도 없음). */
	boolean idle() {
		for (Voice v : slots) {
			if (v != null) {
				return false;
			}
		}
		return true;
	}

	/** 사라지는 중이 아닌 소리 수. */
	int live() {
		int n = 0;
		for (Voice v : slots) {
			if (v != null && v.fade < 0) {
				n++;
			}
		}
		return n;
	}

	/** 사라지는 중이 아닌 소리 {@code key} 의 수. */
	int live(Sfx key) {
		int n = 0;
		for (Voice v : slots) {
			if (v != null && v.fade < 0 && v.key == key) {
				n++;
			}
		}
		return n;
	}

	/** 사라지는 중이 아닌 소리 {@code key} 를 지금 줄이고 있는 양 (dB, 가장 많이 줄인 것). 없으면 0. 시험이 덕킹을 볼 때 쓴다. */
	double duckDb(Sfx key) {
		double most = 0;
		for (Voice v : slots) {
			if (v != null && v.fade < 0 && v.key == key) {
				most = Math.max(most, v.duckDb);
			}
		}
		return most;
	}
}
