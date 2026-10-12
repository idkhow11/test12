/**
 * 사용자 설정, 키 묶음, 플레이 기록과 그 저장 파일. 화면은 없고, 화면(ui 의 메뉴·대전·설정·도움말, 모래 모드)이 아래처럼 쓴다.
 * JDK 만 쓰고 AWT 를 띄우지 않으므로 창 없는 시험에서도 돈다(키 코드는 컴파일할 때 숫자가 되는 {@code KeyEvent.VK_} 상수다).
 *
 * <p><b>1. 무엇이 있나</b>
 * <ul>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.SaveData}: 저장 파일 하나(설정 + 기록). 게임 전체에서 하나를 함께 쓴다.
 * 고칠 때마다 바로, 원자적으로 저장한다. 망가진 파일·없는 파일은 기본값이고, 새 버전 게임의 파일은 덮어쓰지 않는다.</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.UserSettings}: 키, DAS/ARR, AI 속도(50~150%, 10 단위), 고스트, 색약 색상,
 * 블록 무늬, 효과 세기(끔·약하게·보통·강하게 = 0/0.5/1/1.5 배), 화면 흔들림, 번쩍임 줄이기, 소리 크기·음소거, 심장 소리,
 * 전체 화면으로 시작, 메뉴 기억. 기본값은 설정 화면이 생기기 전의 게임과 같다(DAS 170ms, ARR 50ms, AI 속도 100%, 고스트
 * 켬, 지금 키, 창으로 시작). 새 효과 설정의 기본은 보통·흔들림 켬·번쩍임 줄이기 끔·심장 소리 켬이다.</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.KeyBindings}, {@link kr.ac.jbnu.se.tetris.settings.KeyAction},
 * {@link kr.ac.jbnu.se.tetris.settings.KeyNames}: 동작마다 키 1~3개, 한 키는 한 동작에만(겹치면 맞바꾸기), 키 이름.</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.KeyTracker}: 키 이벤트 → 동작의 막 눌림·막 뗌 (자동 반복 무시, 한 동작의 여러
 * 키), 카운트다운이 끝날 때 넘길 이동({@code heldMoves()}).</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.FullscreenKey}: 창 어디서나 듣는 전체 화면 키를 누를 때마다 한 번만(창을 다시
 * 만드는 동안 뗌을 잃어도).</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.MenuMemory}: 메뉴에서 마지막으로 고른 것(모드, 난이도 id, 페르소나 id …).</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.PlayRecords}: 난이도마다 전적, 혼자 연습(설정마다)과 모래 모드의 최고 기록.</li>
 * <li>{@link kr.ac.jbnu.se.tetris.settings.ConfigDir}: 파일을 둘 폴더. Windows {@code %APPDATA%\TetrisBattle}, macOS
 * {@code ~/Library/Application Support/TetrisBattle}, 그 밖 {@code ~/.config/tetris-battle}.
 * {@code -Dtetris.config=<폴더>} 로 바꾸고 {@code off} 면 파일 없이 쓴다.</li>
 * </ul>
 *
 * <p><b>2. 붙이는 법</b>
 * <ul>
 * <li><b>TetrisApp (붙어 있음, 10-07):</b> {@code main} 에서 {@code SaveData.open()} 을 한 번 부르고 화면마다 넘긴다. 전체
 * 화면으로 시작은 {@code settings().startsFullscreen(System.getProperty(UserSettings.FULLSCREEN_PROPERTY))}.
 * {@code problem()} 이 있으면 메뉴와 설정 화면 아래 경고 줄에 보인다. {@code warnings()} 는 콘솔에만. 설정·도움말 화면은 메뉴
 * 오른쪽 위 단추({@code MenuPanel.addLink})로 연다.</li>
 * <li><b>전체 화면 키(TetrisApp, 붙어 있음 10-07):</b> InputMap 의 F11 고정 대신(KEY_PRESSED 마다 바뀌므로 누르고 있으면
 * 자동 반복으로 다시 바뀔 수 있다) {@code KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher} 로
 * 듣고, 창에 하나 {@code FullscreenKey fs} 를 둔다.
 * <ul>
 * <li>KEY_PRESSED 의 {@code getKeyCode()} 가 {@code keys().actionOf(..) == FULLSCREEN} 이고 지금 화면이 그 키를 스스로 쓰지
 * 않으면(설정 화면의 {@code takesKey(code)}: 키를 기다리는 중, Delete·Backspace) {@code fs.press(code, e.getWhen())} 가 true 일
 * 때만 바꾼다. 어느 쪽이든 그 이벤트는 소비한다(true 를 돌려준다). 수식 키와 상관없이 듣는다.</li>
 * <li>KEY_RELEASED 는 {@code fs.release(code)}. windowDeactivated 에서는 지금의 {@code refitting} 이 거짓일 때(사람이
 * 다른 창으로 감)만 {@code fs.reset()}. 전체 화면을 바꾸느라 창을 다시 만드는 동안의 비활성화에는 부르지 않는다.</li>
 * <li>그러면 누르고 있어도 한 번만 바뀌고(창을 다시 연 뒤의 자동 반복을 거른다), 창을 다시 만드는 사이에 뗌을 잃어도 다음
 * 눌림이 무시되지 않는다({@code FullscreenKey.REPEAT_GAP_MS} 의 시간으로 자동 반복을 가린다).</li>
 * </ul></li>
 * <li><b>HumanInput (붙어 있음, 10-07):</b> 판마다 {@code new HumanInput(UserSettings)} 가 키 묶음·DAS·ARR 을 가져가고,
 * 안에 {@link kr.ac.jbnu.se.tetris.settings.KeyTracker} 를 둔다. {@code DAS_MS}·{@code ARR_MS} 상수는 기본값으로 남았다(인자
 * 없는 생성자 = 기본 키, 170·50). {@code HumanInput.Key} 는 {@code KeyAction} 의 블록 조작 일곱 가지와 하나씩 대응한다
 * ({@code HumanInput.keyOf(KeyAction)}).</li>
 * <li><b>GamePanel 과 모래 화면 (붙어 있음, 10-07):</b> keyPressed 에서 {@code human.keyPressed(code)}, keyReleased 에서
 * {@code human.keyReleased(code)} 가 준 동작만 처리한다(null 이면 아무것도 하지 않음). 판이 끝났으면 RETRY → 다시 하기,
 * MENU → 메뉴로. 판 중에는 MENU → 기권 흐름, PAUSE → 일시정지, 블록 조작 → HumanInput 의 press·release (멈춘 동안과
 * 카운트다운 동안의 눌림은 버리고, 뗌은 언제나 넘긴다). 포커스를 잃으면 {@code human.releaseAll()} (추적기도 비운다.
 * 일시정지에는 비우지 않는다: P 를 누르고 있으면 다시 풀린다). 카드의 키 글자는 {@code keys().describe(RETRY, 1)} 처럼 지금
 * 키로 그린다. 고스트는 {@code ghost()} 일 때만, 색은 {@code colorBlind()}, 무늬는 {@code patterns()} 를 보고 그린다
 * (ColorPalettes, 화면 담당).</li>
 * <li><b>카운트다운 동안 누르고 있던 이동 (붙어 있음, 10-07):</b> 블록 조작을 다시 받기 시작하는 순간, 곧
 * {@code !paused() && !over() && countdown() == 0} 이 거짓에서 참이 될 때 화면의 15ms 타이머({@code handOff()})가
 * {@code human.resumeHeld()} 를 부른다: {@code tracker.heldMoves()} 를 차례로 {@code press} 에 넘긴다. 그래서 카운트다운 동안
 * 누르고 있던 ←/→/↓ 가 GO 직후 바로 움직이고 DAS 가 시작된다(설정 화면이 생기기 전 운영체제의 자동 반복이 하던 일).
 * 하드드롭·회전·홀드는 넘기지 않는다(GO 직후 뜻하지 않은 하드드롭을 막는다).</li>
 * <li><b>기록:</b> 판이 끝나면 {@code updateRecords} 로 더한다. 게임 진행 스레드에서 불러도 되지만 판의 잠금 밖에서
 * 부른다. "새 기록" 표시는 {@code updateRecords} 전의 {@code records()} 와 돌려받은 값을 비교한다
 * ({@code after.solo(setup).newBestLinesSince(before.solo(setup))}).
 * <ul>
 * <li>사람 대 AI (붙어 있음, GameSession): {@code withVersus(난이도 id, WIN/LOSS/DRAW, 판 시간 ms)}. 판을 떠나면(메뉴로,
 * Esc 두 번, 창 닫기) 기권 = LOSS. 카운트다운 중 나감, 오류로 멈춘 판, AI 가 기준 AI 로 둔 판(불러오지 못함, 판 도중
 * 바뀜)은 남기지 않는다. 기록은 난이도마다이고 AI 속도로 나누지 않는다.</li>
 * <li>혼자 연습 (붙어 있음, GameSession): {@code withSolo(PlayRecords.soloSetup(gravityCapG, scheduler.json()), 지운 줄, 판
 * 시간 ms)}. 진 판과, 카운트다운이 끝난 뒤 그만둔 판을 남긴다.</li>
 * <li>모래 모드 (붙어 있음, SandSession): {@code new SandGame(seed, level, records().sand().bestScore())} 로 시작하고, 끝나면
 * {@code withSand(score(), maxMultiplier())}. 모래 패키지는 최고 점수를 따로 저장하지 않는다. 저장 파일의 모래 기록에는 규칙
 * 세대({@code record.sand.rules}, {@link kr.ac.jbnu.se.tetris.settings.PlayRecords#SAND_RULES})를 함께 적고, 세대가 다른 옛
 * 기록(sand-0.2 까지)은 읽을 때 버려 모래 최고 점수를 sand-0.3 에서 새로 시작한다. 파일은 그대로 읽힌다.</li>
 * </ul></li>
 * <li><b>MenuPanel (붙어 있음, 10-07):</b> 열 때 {@code settings().menu()} 로 지난번 것을 고른다. 예: {@code difficulty =
 * menu.indexOf(VERSUS_LEVEL, 난이도 id 목록, 1)}, {@code persona = menu.indexOf(VERSUS_PERSONA, 페르소나 id 목록, 0)},
 * {@code soloCap = menu.indexOf(SOLO_SPEED, [1,2,3,5,8,12], 2)}, {@code scheduler = menu.indexOf(SOLO_GARBAGE, json
 * 목록, 0)}. 없거나 목록에 없으면(모델 묶음이 바뀜) 지금의 기본값이다. 시작할 때 {@code updateSettings(s ->
 * s.withMenu(s.menu().with(MODE, ..).with(VERSUS_LEVEL, d.id())...))} 로 남긴다. 페르소나가 잠긴 단계로 시작해도
 * {@code VERSUS_PERSONA} 는 마지막으로 고른 것을 그대로 둔다(최상급으로 돌아오면 다시 보이게). 아래 키 안내는 키를
 * {@code keys().describe(a, 1)} 로, 설명을 {@code a.label()} 로 그린다: 설정·도움말 화면과 같은 말("시계 회전",
 * "반시계 회전")을 쓴다. 사람 대 AI 카드의 AI 속도(‹ 100% ›)는 바꾸면 바로 {@code updateSettings(s -> s.withAiSpeed(..))}
 * 로 저장한다(설정 화면의 AI 속도와 같은 값). 메뉴·설정·도움말 화면은
 * {@link kr.ac.jbnu.se.tetris.settings.KeyBindings#MENU_KEYS} 만 쓴다.</li>
 * <li><b>설정 화면 (붙어 있음, 10-07):</b> 갈래는 조작·감도·화면·효과(효과 세기, 화면 흔들림, 번쩍임 줄이기)·소리(크기,
 * 음소거, 심장 소리)·AI 속도. 바꿀 때마다 {@code updateSettings} 로 저장하고 {@code onChanged} 로 알린다(전체 화면 키, 소리
 * 크기가 바로 바뀌게). 저장 한 번은 2ms 안팎이라 ←→ 한 번마다 저장해도 되지만, 마우스로 끄는 막대는 놓을 때 저장한다.
 * 바꾼 것이 파일에 남지 않는 실행이면({@code persistent()} 가 거짓: 파일 없이 실행, 새 버전의 파일, 읽기·저장 실패) 제목
 * 아래 줄이 "이번 실행 동안만 바뀌어요" 다.
 * 키 바꾸기: 키를 기다리다 KEY_PRESSED 를 받으면 {@code e.consume()} 하고(전체 화면 키가 함께 일하지 않게),
 * {@code reasonAgainst(동작, 자리, 키)} 가 이유를 주면 그 한 줄을 보이고, 아니면 {@code assignNotice} 로 알림을
 * 받아 둔 뒤 {@code assign}. Esc 는 기다림을 취소한다(Esc 를 다시 쓰려면 그 동작의 {@code reset}). 패널은
 * {@code enableInputMethods(false)} 와 {@code setFocusTraversalKeysEnabled(false)} 로 한/영·Tab 도 키로 받는다. 수는
 * {@code UserSettings} 의 MIN·MAX·STEP 상수로 움직이고, "모두 처음으로" 는 {@code withDefaultsKeepingMenu()}, "조작만
 * 처음으로" 는 {@code withDefaultControls()}, 기록 지우기는 {@code updateRecords(r -> PlayRecords.empty())}.</li>
 * <li><b>소리:</b> 켤 때와 설정이 바뀔 때 {@code Sound.get().setVolume(volume() / 100.0)},
 * {@code Sound.get().setMuted(muted())} (음소거를 따로 넘겨야 소리가 부드럽게 사라진다). 크기 하나만 받는 곳은
 * {@code soundLevel()}(0~1, 음소거면 0).</li>
 * <li><b>시험과 화면 확인 도구:</b> {@code SaveData.open()}(인자 없음)을 부르지 않는다. {@code SaveData.inMemory()} 나 시험
 * 폴더의 {@code SaveData.open(dir)} 을 넘겨 사용자 폴더를 읽거나 쓰지 않게 한다. 빌드가 그래도 사용자 폴더를 건드리지
 * 않도록 Gradle 의 시험 JVM 에 {@code -Dtetris.config=off} 를 준다(아래 3장). 앱을 자동으로 띄워 확인할 때(스크린샷, 시연
 * 녹화)는 {@code -Dtetris.config=<임시 폴더>} 로 띄운다: 사용자의 실제 기록에 시험 판이 섞이지 않는다.</li>
 * </ul>
 *
 * <p><b>3. Gradle (build.gradle.kts, 10-07 에 넣음)</b>
 * <pre>
 * tasks.withType&lt;Test&gt;().configureEach {
 *     systemProperty("java.awt.headless", "true")
 *     systemProperty("tetris.sound", "off")
 *     systemProperty("tetris.config", "off")
 *     systemProperty("tetris.records", "off")
 * }
 * </pre>
 * {@code screenshot} 과 벤치·화면 확인 도구를 돌리는 JavaExec 작업에도 같은 네 값을 {@code jvmArgs("-D…")} 로 준다. 그래서
 * 시험이나 도구가 실수로 {@code Sound.start()}·{@code SaveData.open()} 을 부르거나 사람 대 AI 판을 끝까지 돌려도 빌드 중에
 * 진짜 소리 장치를 열거나, 사용자의 설정 파일에 쓰거나, {@code runs/human} 에 판을 남기지 않는다.
 */
package kr.ac.jbnu.se.tetris.settings;
