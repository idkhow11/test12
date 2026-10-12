/**
 * 효과음. 대부분은 코드로 합성하고({@link kr.ac.jbnu.se.tetris.audio.SoundBank}), 합성으로는 잘 안 되는 몇 가지(부딪히는
 * 소리, 모래가 사각거리는 소리, K.O. 의 부서짐, 메뉴 소리)만 함께 배포하는 CC0 녹음 13 개({@code src/main/resources/sfx/},
 * 출처는 그 폴더의 README.txt 와 저장소의 CREDITS.md)를 쓰거나 합성음에 겹친다. 자기 스레드의 작은 믹서로 섞어 내고, 외부
 * 라이브러리 없이 JDK 의 {@code javax.sound.sampled} 만 쓴다.
 *
 * <h2>쓰는 법</h2>
 * <ul>
 * <li>{@code TetrisApp.main} 에서 설정을 읽은 뒤 {@code Sound.get().setVolume(settings.volume() / 100.0)},
 * {@code setMuted(settings.muted())}, 그리고 {@link kr.ac.jbnu.se.tetris.audio.Sound#start()} 한 번. 바로 돌아온다(장치 열기와
 * 소리 만들기 약 0.2~0.3초는 믹서 스레드가 한다). 장치를 열지 못하거나 쓰던 장치가 멈춰 소리를 끄면 엔진이 그 까닭을 표준
 * 오류에 한 줄("효과음을 끔: …") 남기므로 따로 찍지 않아도 된다.</li>
 * <li>창을 닫을 때(windowClosing) {@code System.exit} 전에 {@code Sound.get().close()}. 울리던 소리를 약 12ms 에 걸쳐 0 까지
 * 줄이고 장치에 남은 소리(25ms)를 내보낸 뒤 닫으므로 퍽 소리가 나지 않는다. 보통 0.1초 안에 돌아오고 길어야 1초다.</li>
 * <li>어디서든 {@code Sound.get().play(Sfx.MOVE)}, {@code play(Sfx.CLEAR, 줄 수)}, {@code play(Cue)},
 * {@code play(List<Cue>)}. 어느 스레드에서 불러도 되고, 쿨다운을 보고 대기열에 넣기만 하므로 막히지 않는다({@code GameSession.lock} 안에서 불러도
 * 된다).</li>
 * <li>판을 떠날 때(다시 하기, 같은 판 다시, 메뉴로) {@code Sound.get().stopAll()} 을 먼저 부른다: 앞 판의 승리 음악 같은 긴
 * 소리와 아직 울리지 않은 늦춘 소리를 끝낸다. 그 뒤에 부른 소리(메뉴 소리)는 그대로 난다.</li>
 * <li>설정이 바뀌면 다시 {@code setVolume}·{@code setMuted}. 실제로 곱하는 크기는 볼륨의 제곱이고, 볼륨 0 은 음소거와 같다.
 * start() 전에 정한 값도 이어받는다.</li>
 * <li>판이 진행 중인지는 엔진이 스스로 안다: {@link kr.ac.jbnu.se.tetris.audio.MatchSounds}·
 * {@link kr.ac.jbnu.se.tetris.audio.PieceSounds} 가 틱마다 알리고, 그동안은 조용해도 장치를 쉬게 하지 않는다(첫 소리가 늦지
 * 않게). 화면 코드가 부를 것은 없다.</li>
 * <li>소리 장치가 없거나, 창 없이(헤드리스) 실행하거나, {@code -Dtetris.sound=off} 면 모든 호출이 아무 일도 하지 않는다.
 * 소리를 낼 수 있는지는 {@code isAvailable()}, 그렇지 않은 까닭의 종류는 {@code offCause()}(설정 화면이 "실행 옵션으로 소리를
 * 꺼 두었어요", "소리 장치를 찾지 못했어요" 같은 문구를 고른다), 자세한 글은 {@code reason()}(기록용).</li>
 * <li>{@code -Dtetris.sfx=synth} 면 녹음을 하나도 쓰지 않고 모든 소리를 합성한다(예전 소리와 견주어 들어 볼 때). 녹음 파일을
 * 읽지 못해도 그 소리만 합성음으로 대신하고, 녹음 이름마다 한 번 표준 오류에 "효과음 녹음을 읽지 못해 합성음으로 대신함:
 * 이름 (까닭)" 을 남긴다. {@code -Dtetris.sfx=synth} 로 일부러 끈 것은 남기지 않는다.</li>
 * <li>시험과 화면 확인 도구는 {@code start()} 를 부르지 않는다(빌드 중에 진짜 소리 장치를 열지 않게). 소리 엔진이 필요한
 * 다른 패키지의 시험과 도구는 {@code Sound.silent()} 나 시험 폴더의 {@code FakeSounds}(장치 없이 READY 인 엔진, 장치가
 * 없는 엔진)를 쓴다. 혹시 부르더라도 장치를 열지 않도록 Gradle 의 시험 JVM 에 {@code -Dtetris.sound=off} 와
 * {@code -Djava.awt.headless=true} 를 준다(settings 패키지 설명 3장).</li>
 * <li>들어 보기: 시험 폴더의 {@code SoundPreview} 가 모든 소리를 WAV 로 쓰고({@code build/sfx-preview}) 소리마다 들리는
 * 크기(F100)와 목표를 표로 찍는다. {@code play} 를 주면 차례로 들려준다.</li>
 * </ul>
 *
 * <h2>어디서 무엇을 부르나</h2>
 * 조작 소리(이동·회전·홀드·닿음·고정)는 사람이 조작하는 블록에만 낸다. AI 의 조작은 소리가 없고, 사람 대 AI 에서 AI 가 지운
 * 줄도 소리가 없다(그 공격이 나에게 오면 방해 줄 경고가 울린다).
 *
 * <table>
 * <caption>사건 → 소리</caption>
 * <tr><th>부를 곳</th><th>사건</th><th>부를 것</th></tr>
 * <tr><td rowspan="4">MenuPanel·SettingsPanel·HelpPanel (화면 스레드)</td><td>↑↓·Tab 으로 줄이 실제로 바뀜, ←→·마우스로 값이
 * 실제로 바뀜 (AI 속도 포함)</td><td>{@code MENU_MOVE}</td></tr>
 * <tr><td>←→ 가 끝이라 값이 안 바뀜, 잠긴 페르소나 줄을 고르려 함 (↑↓ 의 끝은 조용히)</td><td>{@code DENY}</td></tr>
 * <tr><td>시작(Enter·Space·시작 버튼), 설정·도움말 화면 열기</td><td>{@code MENU_SELECT}</td></tr>
 * <tr><td>설정·도움말 화면에서 돌아옴</td><td>{@code MENU_BACK}</td></tr>
 * <tr><td>GameSession.advance (게임 진행 스레드, 잠금 안)</td><td>매 틱: 조작·닿음·고정·하드드롭(거리 단계)·줄 지우기·T-spin
 * (미니 포함)·콤보·B2B(연속 수)·퍼펙트 클리어·상쇄·방해 줄 경고와 상승·속도 단계 오름</td><td>필드
 * {@code new MatchSounds()}. 틱 전에 {@code before(match)}, 틱 뒤에 {@code play(after(match, p1))} (p1 = 이번 틱의 사람
 * 조작)</td></tr>
 * <tr><td rowspan="2">판이 끝났을 때의 처리 (GameSession.loop 의 {@code over() && !reported}, 또는 그것이 부르는 onOver.
 * 오류로 멈췄으면 소리 없음)</td><td>사람 대 AI</td><td>{@code SoundCues.result(result(), false)}: K.O. 뒤 승리·패배
 * 음악</td></tr>
 * <tr><td>혼자 연습</td><td>{@code SoundCues.result(Result.OVER, 최고 기록을 깼는가)}</td></tr>
 * <tr><td rowspan="5">GamePanel (화면 스레드)</td><td>P 키로 일시정지 / 계속 (창을 떠나 저절로 멈출 때는 소리 없이)</td>
 * <td>{@code PAUSE} / {@code RESUME}</td></tr>
 * <tr><td>그릴 때마다: 카운트다운 숫자 (시작 3·2·1, 일시정지 뒤 2·1, 0 이 되면 GO)</td>
 * <td>{@link kr.ac.jbnu.se.tetris.audio.CountdownSounds}{@code .update(countdown(), !paused() && !over())}, 판이 바뀌면
 * {@code reset()}</td></tr>
 * <tr><td>그릴 때마다: 사람의 보드가 위험(빨간 테두리)하고 멈춤·판 끝이 아니고, 설정의 "심장 소리" 가 켜져 있음</td>
 * <td>{@code SoundCues.danger(높이 + 방해 줄)}: 쿨다운이 심장 박자를 맞춘다</td></tr>
 * <tr><td>결과 화면에서 다시 하기 / 같은 판 다시 / 메뉴로</td><td>{@code stopAll()} 뒤 {@code MENU_SELECT} /
 * {@code MENU_SELECT} / {@code MENU_BACK}</td></tr>
 * <tr><td>대전 중 Esc 두 번으로 기권, 혼자 연습·멈춘 판에서 Esc</td><td>{@code stopAll()} 뒤 {@code MENU_BACK}</td></tr>
 * <tr><td rowspan="4">모래 모드 진행 (SandGame.advance 를 부르는 곳, 잠금 안). 틱 전에 {@code pieces()}, {@code clears()},
 * {@code level()} 을, 조작이 하드드롭이면 {@code ghost().y() - piece().y()}(떨어질 칸 수)를 적어 둔다</td><td>이동·회전·
 * 홀드·닿음</td><td>{@link kr.ac.jbnu.se.tetris.audio.PieceSounds}: {@code before(piece(), spawns(), grounded())}, 뒤에
 * {@code after(input, …, 고정했는가)}</td></tr>
 * <tr><td>{@code pieces()} 가 늘어남 (블록이 모래로 부서짐)</td><td>{@code SoundCues.sandLock(하드드롭이면 떨어진 칸 수, 아니면
 * -1)}</td></tr>
 * <tr><td>{@code clears()} 가 늘어남 (덩어리가 반짝이기 시작)</td><td>{@code SoundCues.sandClear(lastClear().multiplier())}</td></tr>
 * <tr><td>{@code level()} 이 올라감</td><td>{@code LEVEL_UP}</td></tr>
 * <tr><td>모래 모드 끝 (한 번)</td><td>{@code over()} 가 됨</td><td>{@code SoundCues.result(Result.OVER, newBest())}</td></tr>
 * </table>
 *
 * <h2>크기와 섞기</h2>
 * <ul>
 * <li>모든 소리는 귀에 들리는 크기 한 가지 기준으로 맞춘다: 가장 시끄러운 0.1초의 K 가중 크기(F100,
 * {@link kr.ac.jbnu.se.tetris.audio.Loudness}). 테트리스(4줄)가 기준 0 이고, 퍼펙트 클리어 +2 가 가장 크며, 자주 나는 조작
 * 소리는 작다(이동 -20, 회전·닿음 -16, 고정 -12, 하드드롭은 떨어진 거리에 따라 -10·-8·-6). 줄 지우기는 1~3줄이
 * -5·-3.5·-2 로 줄 수만큼 커진다. 표 전체는 {@code SoundBank.rel}.</li>
 * <li>소리마다 묶음({@link kr.ac.jbnu.se.tetris.audio.Sfx.Bus})이 있다. 큰 사건(테트리스·T-spin 더블과 트리플 5dB, 퍼펙트
 * 클리어 6dB, K.O. 6dB, 승리·패배·최고 기록 5dB, 모래 연쇄 5 이상 4dB)이 시작한 뒤 0.3초 동안 보상·경고 묶음의 다른 소리를
 * 그만큼 줄인다(덕킹). 조작·메뉴·판 흐름 소리는 줄이지 않는다(0ms 줄 지우기라 그 사이에도 계속 조작한다). 위험 심장 소리는
 * 보상 소리 직후 0.4초 동안 6dB 작다.</li>
 * <li>변형이 여럿인 소리(이동 3, 회전 2, 고정 2, 하드드롭은 단계마다 2, 모래 3)는 바로 앞의 것을 피해 고른다. 조작·메뉴
 * 소리 몇 가지는 음 높이(±20~40 cents)와 크기를 매번 조금씩 바꾼다. 음 높이가 정보인 소리(줄 지우기, T-spin, 콤보, 모래
 * 연쇄, 카운트다운, GO, 음악)는 바꾸지 않는다. 누르고 있어 반복된 이동(DAS·ARR)은 3dB 작다.</li>
 * <li>좌우 위치는 어디에 두어도 전체 힘이 같다(가운데는 예전과 같다). 게임의 소리는 모두 가운데에서 난다.</li>
 * <li>장치 버퍼는 25ms. 처음 30초에 소리가 3 번 넘게 끊기면 그 실행 동안 40ms 로 늘리고 표준 오류에 한 줄 남긴다.</li>
 * </ul>
 *
 * <h2>구조</h2>
 * <ul>
 * <li>{@link kr.ac.jbnu.se.tetris.audio.Sfx}: 소리 종류와 쿨다운·중요도·변형·묶음·덕킹. {@link kr.ac.jbnu.se.tetris.audio.Cue}:
 * 요청 하나(세기, 좌우 위치, 늦춤). {@link kr.ac.jbnu.se.tetris.audio.SoundCues}: 게임 사건 → 요청.
 * {@link kr.ac.jbnu.se.tetris.audio.MatchSounds}·{@link kr.ac.jbnu.se.tetris.audio.PieceSounds}·
 * {@link kr.ac.jbnu.se.tetris.audio.CountdownSounds}: 앞뒤 상태를 비교해 사건을 찾는다.</li>
 * <li>{@link kr.ac.jbnu.se.tetris.audio.Sound}: 엔진. play() 는 쿨다운(소리마다)을 보고 대기열에 넣는다. 믹서 스레드가 약
 * 5.8ms 씩 섞어(동시 8 개, Voices: 덕킹, 좌우, 빠르기·크기; Variation: 변형 고르기와 흔들기) 볼륨·리미터(OutputStage)를 거쳐
 * 장치(AudioOut)에 쓴다. 판이 없고 2초 조용하면 장치를 쉬게 하고 잔다.</li>
 * <li>{@link kr.ac.jbnu.se.tetris.audio.SoundBank}: 소리마다의 설계와 크기 맞춤. Synth(파형·포락선·필터·잔향 부품), Samples
 * (녹음 읽기), Loudness(들리는 크기)를 쓴다. 31 종, 변형까지 84 개(모두 약 43초, 7.6MB)를 0.2~0.3초에 만든다. 같은 코드와 같은
 * 녹음이면 어느 PC 에서나 비트까지 같은 소리다.</li>
 * </ul>
 */
package kr.ac.jbnu.se.tetris.audio;
