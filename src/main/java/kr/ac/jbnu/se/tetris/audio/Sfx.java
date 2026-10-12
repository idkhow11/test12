package kr.ac.jbnu.se.tetris.audio;

/**
 * 효과음 종류. 게임은 {@code Sound.get().play(Sfx.MOVE)} 나 {@code play(Sfx.CLEAR, 줄 수)} 처럼 부른다. 세기
 * ({@code intensity})를 받는 소리는 그 값으로 미리 만든 변형 묶음 하나를 고른다(지운 줄 수, 콤보 수, 방해 줄 수, 연쇄 수,
 * 하드드롭 단계, B2B 연속 수). 한 묶음에 비슷한 변형이 여럿이면 엔진이 그중 하나를 고르되 바로 앞의 것은 피한다(같은 소리가
 * 기계처럼 반복되지 않게).
 * <p>
 * 소리마다 다시 울릴 수 있는 최소 간격(쿨다운)이 있어서 키를 빠르게 눌러도 소리가 겹겹이 쌓이지 않는다. 같은 소리가 동시에
 * 울릴 수 있는 수(대개 1)를 넘으면 앞의 것을 짧게 줄이고 새로 울린다. 동시에 울리는 소리가 한도를 넘으면 중요도가 낮은
 * 것부터 양보한다. 소리마다 묶음({@link Bus})이 있어서, 큰 사건 소리가 나는 동안 보상·경고 묶음의 다른 소리를 잠깐
 * 줄인다(덕킹, {@link #duckDb(int)}). 조작과 메뉴 소리는 줄이지 않는다. 어느 게임 사건에서 무엇을 부를지는
 * {@link SoundCues} 와 이 패키지 설명(package-info)에 있다.
 */
public enum Sfx {

	// 인자: 쿨다운(ms), 중요도(0 낮음 ~ 3 높음), 같은 소리 동시 수, 세기 묶음 수, 묶음마다 섞어 쓰는 변형 수, 첫 묶음의 세기,
	// 묶음(버스), 음 높이 흔들기(±cents), 크기 흔들기(±dB). 흔들기는 매번 조금씩 다르게 들리게 하는 것으로, 음 높이가 정보인
	// 소리(줄 지우기, T-spin, 콤보, 연쇄, 카운트다운, 음악)에는 쓰지 않는다.

	// ---- 메뉴
	/** 메뉴에서 줄이나 항목을 옮김: 짧고 가벼운 톡 (녹음). */
	MENU_MOVE(25, 0, 1, 1, 1, 1, Bus.UI, 30, 0),
	/** 고름·시작: 위로 오르는 화음 (녹음). */
	MENU_SELECT(60, 1, 1, 1, 1, 1, Bus.UI, 0, 0),
	/** 뒤로·닫기: 내려가는 화음 (녹음). */
	MENU_BACK(60, 1, 1, 1, 1, 1, Bus.UI, 0, 0),
	/** 안 됨(잠긴 항목, 더 갈 수 없음, 이미 쓴 홀드): 짧게 내려가는 버저 (녹음). */
	DENY(150, 1, 1, 1, 1, 1, Bus.UI, 0, 0),

	// ---- 블록 조작 (사람이 조작하는 블록만)
	/**
	 * 좌우 이동에 성공함. 높이가 조금씩 다른 소리 셋을 섞어 쓴다. 세기 2 는 누르고 있어 저절로 반복된 이동(DAS·ARR)으로,
	 * 3dB 작게 낸다.
	 */
	MOVE(20, 0, 1, 1, 3, 1, Bus.HANDLING, 40, 1),
	/** 회전에 성공함. */
	ROTATE(25, 0, 1, 1, 2, 1, Bus.HANDLING, 30, 0),
	/** 홀드에 성공함. */
	HOLD(60, 1, 1, 1, 1, 1, Bus.HANDLING, 0, 0),
	/** 블록이 바닥이나 쌓인 블록에 처음 닿음(소프트드롭·중력). 아직 고정 전이다 (녹음). */
	LAND(60, 0, 1, 1, 1, 1, Bus.HANDLING, 30, 0),
	/**
	 * 하드드롭 (고정 소리를 대신한다). 세기 = 떨어진 거리의 단계: 0 은 2칸 이하, 1 은 3~9칸, 2 는 10칸 이상(반음 낮고 크다).
	 * 거리 → 단계는 {@link SoundCues#lock(kr.ac.jbnu.se.tetris.core.GameModel.LockResult, int, int, int)} 이 정한다. 세기 없이
	 * 부르면 가운데 단계 (녹음 + 합성한 쉭 소리).
	 */
	HARD_DROP(30, 1, 1, 3, 2, 0, Bus.HANDLING, 25, 0),
	/** 하드드롭이 아닌 고정(락 딜레이가 끝남) (녹음). */
	LOCK(30, 1, 1, 1, 2, 1, Bus.HANDLING, 30, 0),

	// ---- 줄 지우기
	/** 줄 지우기. 세기 = 지운 줄 수 1~4. 4(테트리스)는 따로 만든 큰 소리이고, 잠깐 다른 보상·경고 소리를 줄인다. */
	CLEAR(40, 2, 2, 4, 1, 1, Bus.REWARD, 0, 0),
	/**
	 * T-spin 으로 줄을 지움. 세기 = 지운 줄 수 1~3, T-spin 미니는 0. 더블·트리플은 잠깐 다른 보상·경고 소리를 줄인다.
	 */
	TSPIN(40, 2, 2, 4, 1, 0, Bus.REWARD, 0, 0),
	/** 콤보. 세기 = 콤보 수(1 부터). 10 까지는 음이 오르고, 11·12 는 같은 높이에 아래 음을 더 쌓는다. */
	COMBO(40, 2, 1, 12, 1, 1, Bus.REWARD, 0, 0),
	/** B2B 가 이어짐 (테트리스·T-spin 연속). 세기 = 연속 수(B2B ×1 부터): 1~3, 4~7, 8 이상이 점점 화려하다. */
	B2B(40, 2, 1, 3, 1, 1, Bus.REWARD, 0, 0),
	/** 퍼펙트 클리어. 가장 큰 소리이고 다른 보상·경고 소리를 가장 많이, 길게 줄인다. */
	PERFECT_CLEAR(300, 3, 1, 1, 1, 1, Bus.REWARD, 0, 0),

	// ---- 대전
	/** 받을 방해 줄이 생김(상대 공격 도착): 경고하는 쿵. 세기 = 줄 수 1~8, 많을수록 크고 여러 번 울린다. */
	GARBAGE(120, 2, 1, 8, 1, 1, Bus.ALERT, 0, 0),
	/** 방해 줄이 보드로 올라옴. 세기 = 올라온 줄 수 1~8. */
	GARBAGE_RISE(120, 1, 1, 8, 1, 1, Bus.ALERT, 0, 0),
	/** 내 공격으로 받을 방해 줄을 상쇄함: 막아 내는 맑은 쨍. 세기 = 상쇄한 줄 수(소리는 같다). */
	GARBAGE_CANCEL(120, 1, 1, 1, 1, 1, Bus.ALERT, 20, 0),
	/**
	 * 위험(높이 쌓임)할 때의 심장 소리. 위험한 동안 계속 불러도 쿨다운이 박자를 맞춘다(약 0.85초마다). 세기 2 는 더
	 * 위험할 때로, 박자가 빨라진다(0.6초). 보상 소리가 난 직후에는 6dB 작게 들린다.
	 */
	DANGER(850, 1, 1, 2, 1, 1, Bus.ALERT, 0, 0),
	/** 속도(중력) 단계가 오름. */
	LEVEL_UP(500, 1, 1, 1, 1, 1, Bus.ALERT, 0, 0),

	// ---- 판 흐름
	/** 시작 카운트다운 3·2·1 의 삑. */
	COUNTDOWN(400, 1, 1, 1, 1, 1, Bus.ALERT, 0, 0),
	/** 카운트다운 뒤 "GO!". */
	GO(400, 2, 1, 1, 1, 1, Bus.ALERT, 0, 0),
	PAUSE(120, 1, 1, 1, 1, 1, Bus.UI, 0, 0),
	RESUME(120, 1, 1, 1, 1, 1, Bus.UI, 0, 0),
	/** 패배 판정(K.O., 혼자 연습·모래 모드의 게임 끝): 합성음 + 부서지는 녹음. */
	KO(500, 3, 1, 1, 1, 1, Bus.MATCH, 0, 0),
	/** 승리 음악 (K.O. 소리 뒤에). */
	WIN(1000, 3, 1, 1, 1, 1, Bus.MATCH, 0, 0),
	/** 패배 음악 (K.O. 소리 뒤에). 무겁지 않게. */
	LOSE(1000, 3, 1, 1, 1, 1, Bus.MATCH, 0, 0),
	/** 최고 기록을 깸. */
	NEW_RECORD(1000, 3, 1, 1, 1, 1, Bus.MATCH, 0, 0),

	// ---- 모래 모드
	/** 블록이 모래로 내려앉으며 부서짐. 사각거리는 녹음 셋을 섞어 쓰고 합성한 알갱이 소리를 겹친다. */
	SAND_LAND(50, 0, 2, 1, 3, 1, Bus.HANDLING, 40, 1.5),
	/** 모래가 사라짐 (연쇄의 첫 번째). */
	SAND_CLEAR(80, 2, 1, 1, 1, 1, Bus.REWARD, 0, 0),
	/** 연쇄로 또 사라짐. 세기 = 연쇄 수(2 부터 10 까지). 높을수록 음이 오른다. 5 이상은 잠깐 다른 소리를 줄인다. */
	SAND_CHAIN(80, 2, 1, 9, 1, 2, Bus.REWARD, 0, 0);

	/**
	 * 소리의 묶음(버스). 큰 사건 소리가 나면 보상·경고 묶음의 다른 소리를 잠깐 줄인다(덕킹). 0ms 줄 지우기라 플레이어는 그
	 * 사이에도 계속 조작하므로 조작·메뉴 소리는 줄이지 않는다. 판 흐름(K.O., 음악)도 줄이지 않는다.
	 */
	public enum Bus {
		/** 조작: 이동, 회전, 홀드, 닿음, 고정, 하드드롭, 모래 부서짐. 줄이지 않는다. */
		HANDLING(false),
		/** 보상: 줄 지우기, T-spin, 콤보, B2B, 퍼펙트 클리어, 모래 지움·연쇄. */
		REWARD(true),
		/** 경고와 알림: 방해 줄, 상쇄, 위험, 속도 단계, 카운트다운. */
		ALERT(true),
		/** 메뉴와 일시정지. 줄이지 않는다. */
		UI(false),
		/** 판 흐름: K.O., 승리·패배 음악, 최고 기록. 줄이지 않는다. */
		MATCH(false);

		private final boolean duckable;

		Bus(boolean duckable) {
			this.duckable = duckable;
		}

		/** 큰 사건 소리가 날 때 이 묶음의 소리를 줄이는가. */
		public boolean duckable() {
			return duckable;
		}
	}

	/** DANGER 세기 2 의 쿨다운 (ms). */
	private static final int DANGER_FAST_MS = 600;
	/** 덕킹이 풀리는 시간 상수 (ms). 퍼펙트 클리어는 {@link #PERFECT_RELEASE_MS}. */
	static final int DUCK_RELEASE_MS = 400;
	/** 퍼펙트 클리어가 줄인 소리가 다시 커지는 시간 상수 (ms). */
	static final int PERFECT_RELEASE_MS = 600;
	/** 누르고 있어 저절로 반복된 이동(세기 2)을 줄이는 양 (dB). */
	static final double REPEAT_MOVE_DB = -3;

	/** 다시 울릴 수 있는 최소 간격 (ms). */
	final int cooldownMs;
	/** 동시에 울리는 소리가 한도를 넘을 때 양보하는 순서. 낮은 것이 먼저 양보한다. */
	final int priority;
	/** 같은 소리가 동시에 울릴 수 있는 수. 넘으면 가장 오래된 것을 짧게 줄인다. */
	final int maxVoices;
	/** 세기 묶음 수. 세기가 범위를 벗어나면 가장 가까운 묶음을 쓴다. */
	final int tiers;
	/** 한 묶음 안에서 섞어 쓰는 변형 수 (1 이면 섞지 않는다). */
	final int choices;
	/** 미리 만들어 두는 변형 수 (= 묶음 수 × 묶음마다의 변형 수). 변형 번호는 묶음 × choices + 고른 것. */
	final int variants;
	/** 첫 묶음에 해당하는 세기 (CLEAR 는 1줄, TSPIN 은 미니(0), SAND_CHAIN 은 연쇄 2, HARD_DROP 은 단계 0). */
	final int base;
	/** 묶음. */
	final Bus bus;
	/** 매번 음 높이를 이 범위(±cents) 안에서 조금씩 바꾼다. 0 이면 바꾸지 않는다. */
	final double jitterCents;
	/** 매번 크기를 이 범위(±dB) 안에서 조금씩 바꾼다. 0 이면 바꾸지 않는다. */
	final double jitterDb;

	Sfx(int cooldownMs, int priority, int maxVoices, int tiers, int choices, int base, Bus bus, double jitterCents,
			double jitterDb) {
		this.cooldownMs = cooldownMs;
		this.priority = priority;
		this.maxVoices = maxVoices;
		this.tiers = tiers;
		this.choices = choices;
		this.variants = tiers * choices;
		this.base = base;
		this.bus = bus;
		this.jitterCents = jitterCents;
		this.jitterDb = jitterDb;
	}

	/** 이 소리의 묶음. */
	public Bus bus() {
		return bus;
	}

	/** 이 세기의 쿨다운 (ms). DANGER 만 세기 2 이상에서 짧아진다. */
	int cooldownMs(int intensity) {
		return this == DANGER && intensity >= 2 ? DANGER_FAST_MS : cooldownMs;
	}

	/**
	 * 세기 → 묶음 번호. 대부분은 세기 - {@link #base} 를 범위 안으로 자른 값이고(넘치지 않게 long 으로), B2B 는 연속 수를
	 * 1~3, 4~7, 8 이상의 세 단계로 나눈다.
	 */
	int tier(int intensity) {
		if (this == B2B) {
			return intensity >= 8 ? 2 : intensity >= 4 ? 1 : 0;
		}
		return (int) Math.max(0, Math.min(tiers - 1, (long) intensity - base));
	}

	/** 묶음 {@code tier} 의 {@code choice} 번째 변형 번호. */
	int variant(int tier, int choice) {
		return tier * choices + choice;
	}

	/**
	 * 이 소리가 다른 보상·경고 소리를 줄이는 양 (dB, 양수). 0 이면 줄이지 않는다. 테트리스 5, T-spin 더블·트리플 5, 퍼펙트
	 * 클리어 6, K.O. 6, 승리·패배·최고 기록 5, 모래 연쇄 5 이상 4.
	 */
	double duckDb(int intensity) {
		switch (this) {
		case CLEAR:
			return intensity >= 4 ? 5 : 0;
		case TSPIN:
			return intensity >= 2 ? 5 : 0;
		case PERFECT_CLEAR:
		case KO:
			return 6;
		case WIN:
		case LOSE:
		case NEW_RECORD:
			return 5;
		case SAND_CHAIN:
			return intensity >= 5 ? 4 : 0;
		default:
			return 0;
		}
	}

	/** 이 소리가 줄인 소리가 다시 커지는 시간 상수 (ms). */
	int duckReleaseMs() {
		return this == PERFECT_CLEAR ? PERFECT_RELEASE_MS : DUCK_RELEASE_MS;
	}

	/** 세기에 따른 크기 조정 (dB). 누르고 있어 반복된 이동(MOVE 세기 2 이상)만 3dB 작다. */
	double gainDb(int intensity) {
		return this == MOVE && intensity >= 2 ? REPEAT_MOVE_DB : 0;
	}
}
