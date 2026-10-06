# SourceCode_tetris

2인용 테트리스 팀 프로젝트. 플레이어가 **난이도**와 **AI 페르소나**(공격형·수비형·T-spin 전문 등)를 골라 AI와 대전하는 게임을 만든다. AI는 Java 휴리스틱 AI에서 출발해 **딥러닝 가치망**으로 확장하고, 페르소나는 **학습된 임베딩의 클러스터링**으로 나눈다. 관전용으로 **실시간 예상 승률**도 보여 준다.

> 이 문서는 팀원 공유용 요약이다. 자세한 내용은 [docs/](docs/)의 문서에 있다(13장). 또한 **설치하거나 사용하는 도구를 모두** 적는 곳이므로, 도구·라이브러리를 추가하거나 바꾸면 같은 커밋에서 이 문서도 고친다.

---

## 목차

1. [한눈에 보기](#1-한눈에-보기)
2. [확정된 설계 결정](#2-확정된-설계-결정)
3. [지금까지 구현된 것](#3-지금까지-구현된-것)
4. [검증 결과](#4-검증-결과)
5. [아직 안 된 것과 다음 작업](#5-아직-안-된-것과-다음-작업)
6. [처음 받았을 때](#6-처음-받았을-때)
7. [알아 두면 좋은 개념](#7-알아-두면-좋은-개념)
8. [팀 작업 규칙](#8-팀-작업-규칙)
9. [빌드·실행 명령](#9-빌드실행-명령)
10. [설치와 사용 도구](#10-설치와-사용-도구)
11. [Python 환경 (학습·분석)](#11-python-환경-학습분석)
12. [폴더 구조](#12-폴더-구조)
13. [문서 지도](#13-문서-지도)
14. [커밋 기록 요약](#14-커밋-기록-요약)

---

## 1. 한눈에 보기

| 영역 | 상태 |
|---|---|
| 대전 규칙 명세 ([RULES.md](docs/RULES.md) 0.3) | **완료**. 10 × 24 보드, 7-bag, SRS 회전, T-spin, 공격·가비지, 패배 판정, 진행 방식까지. 일부 시간 관련 수치는 `[초안]` |
| 규칙 엔진 (`core` 패키지) | **완료·검증**. 규칙대로 한 판을 끝까지 진행하는 `GameModel`, AI가 둘 수 있는 모든 자리를 찾는 `PlacementFinder` 포함 |
| 학습 모델 실행 통로 (`ai` 패키지) | **완료·검증**. 모델 입력 형식 2(상대 상태 포함). Python에서 만든 ONNX 모델을 Java가 실행하고, 두 언어의 결과가 같은 것까지 확인(실제 가치망 구조 포함) |
| 빌드·테스트 | **완료**. Gradle 9.8, Java 21, Java 테스트 197개와 Python 시험 62개 전부 통과, 경고 0 |
| 2인 대전 러너(학습용), 대전 기록 (`train` 패키지) | **완료·검증**. AI끼리 블록 동기 대전을 여러 스레드로 두고 기록(`match-1`)을 남긴다. 512판에 약 10초. 다음 블록까지 보는 2수 탐색을 선택할 수 있다 |
| 딥러닝 가치망 (단계 A) | **완료(10-02).** 중립 AI가 기준 AI를 **88%**(1수), **98%**(다음 블록까지 보는 2수 탐색) 이긴다. 학습·개발에 쓰지 않은 시드 1,000판으로 잰 값([TRAINING.md](docs/TRAINING.md) 12.2). 첫 학습이 76%에서 멈춘 원인은 기준 평가의 비중이 커서 신경망 판단을 누른 것 |
| 승률 예측망 (과제 2번) | **모델 완료(10-02).** 두 플레이어의 상태 → P1이 이길 확률(형식 `winprob-1`), Java `WinRate`. 시험 판 보정 ECE 0.006 ([TRAINING.md](docs/TRAINING.md) 10.6) |
| 페르소나 임베딩 (단계 B) | **학습·분석 도구 완료, 검증 실행(10-02).** AI 12개(원형 4 + 섞은 8)가 신경망 하나를 공유한다. 임베딩은 설계(보상 비율)를 담는다(순위 상관 최대 +0.63, 군집 ~ 설계 라벨 ARI 0.69). 10-04: 수비형 보상을 "위험할 때만 비용 + 생존"으로 바꾸고 본 학습 `p04`(31세대)를 마쳤다. 임베딩은 설계를 담지만(순위 상관 +0.47~+0.57) 사실상 한 축이고, 행동은 T-spin만 확실히 갈린다. 게임에는 세대 10의 원형 넷(공격·수비·T-spin·콤보형)을 넣었다 ([TRAINING.md](docs/TRAINING.md) 9.9) |
| 휴리스틱 페르소나 AI | 뒤로 미룸 (학습을 먼저 하기로 10-01 결정) |
| 게임 화면 (`ui` 패키지) | **첫 판 완료(10-04).** `gradlew run`. 사람 대 AI(난이도 5단계 × 페르소나), 혼자 연습. 2인 보드, 고스트, 홀드, 미리보기 5개, 받을 가비지 막대, **예측 승률 막대**, 결과 화면. 옛 1인용 코드는 지웠다. [GAME.md](docs/GAME.md) |
| 실시간 규칙, AI 조작 | **완료(10-04).** 1ms 틱의 중력·락 딜레이(500ms, 리셋 15회)·홀드(`TimedPlayer`, `TimedMatch`). AI는 생각 지연과 초당 입력 수 안에서 사람처럼 조작한다(`AiController`) |
| 난이도 5단계 | **[초안] 완료(10-04).** 두뇌는 **학습량**(학습 0세대 → 10 → 38 → 최종 → 최종 + 2수 탐색, 기준 AI 상대 0.17 → 0.68 → 0.76 → 0.90 → 0.99)으로, 조작 속도와 중력 상한은 계획서 7장을 바탕으로 나눈다. 10-04에 사용자가 해 보고 1~3단계를 약 20% 늦추고, 단계 이름을 초급·중급·상급·최상급·불가능으로 바꿨다([GAME.md](docs/GAME.md) 6.1) |

과제 기능표와의 대응:

| 과제 항목 | 이 프로젝트에서 | 상태 |
|---|---|---|
| **1번** 딥러닝 AI와 난이도별 대결, 학습 가중치 클러스터링으로 페르소나 | 딥러닝 가치망 + 학습된 페르소나 임베딩 클러스터링 + 난이도 5단계 | 가치망 완료(기준 AI 상대 88%, 2수 탐색 98%). **대결 화면과 난이도 5단계 완료(10-04).** 페르소나는 본 학습 `p04`까지 마쳤고 원형 넷을 4·5단계(최상급·불가능)에서 고른다 |
| **2번** (가능하면) 사전 학습 + 실시간 예상 승률 | 학습용 AI 대전 기록으로 학습하는 딥러닝 승률 예측망 | **완료**: 모델(10-02, 시험 판 ECE 0.006)과 화면의 예측 승률 막대(10-04). 10-04 저녁에 실시간 판으로 다시 학습한 `wp02`로 바꿨다(실시간 시험 판 log loss 0.360, 옛 모델 0.520) |
| **3번** 한 줄 지우면 상대에게 한 줄 공격 | 규칙 0.3: 지운 줄 수만큼 공격 (1/2/3/4) | **완료** |
| 13번 다음 블록 3~4개 + 원하는 블록으로 교체 | 반영하지 않음 (미리보기 5개 + 홀드 유지) | — |
| 7·9·10번 판 교체, 돌발 타임, 방해 블록 | 반영하지 않음 (10-01) | — |

## 2. 확정된 설계 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| 기준 코드 | 이 저장소에 09-26 리팩터링([REFACTORING.md](REFACTORING.md))을 반영한 코드 | 버그 5건이 이미 고쳐져 있다 |
| 보드 | **10열 × 24행** (위 4행은 숨김 영역, 아래 20행이 화면) | 위쪽 여유가 있어야 쓰레기 줄 때문에 억울하게 지지 않는다 |
| 언어 | **Java가 메인**(게임, 규칙, 휴리스틱 AI). Python은 학습·분석만 | 게임 실행 중에는 Python이 필요 없다 |
| 학습 모델 | **애프터스테이트 딥러닝 가치망**("이 자리에 놓은 뒤의 판이 얼마나 좋은가"를 배우는 신경망, TD-Gammon식) | 규칙을 이미 아는 게임이라 MuZero처럼 규칙까지 배울 필요가 없다. MuZero는 과제상 필수가 아니어서 주 경로에서 뺐고, 여유가 있으면 AlphaZero식 탐색으로 확장 |
| 학습 루프 | **Java가 두고 Python이 배운다.** Java가 현재 모델로 자기 대국 기록을 쌓고, Python(PyTorch)이 학습해 ONNX로 내보내는 것을 백그라운드로 반복 | 검증된 Java 엔진이 빠르고 규칙이 어긋날 위험이 없다 |
| 학습 상대 | **2인 대전(블록 동기)으로 학습**하고 상대 상태도 모델 입력에 넣는다. 기본 목표는 승패 | 상대와 공격·방어를 주고받는 법을 배우고, 페르소나 임베딩이 상대를 대하는 방식을 담게 한다. 같은 기록으로 승률 예측망도 학습한다 |
| 기준 평가 (사전 지식) | 배치 점수 = 보상 + 가치망 + **β × 기준 평가**. 학습은 β 0.05로 시작하고(학습 전 모델 = 기준 AI), 학습된 뒤에는 **β 0.01** | 승패만으로 시작하면 학습이 되지 않았고, 사전 지식을 가치망 안에 넣으면 무너졌다. β 0.05를 계속 두면 학습한 신경망의 판단을 눌러 76%에서 멈췄다([TRAINING.md](docs/TRAINING.md) 3.5, 3.6) |
| 학습 설정 (학습된 뒤) | 학습률을 줄여 가며(1e-4 → 2e-5), 세대당 1,024판, 반복 1번 | 판 결과를 외우고 자기 대전에 맞춰 흘러가는 것을 막는다([TRAINING.md](docs/TRAINING.md) 12.2) |
| 2수 탐색 | 가치망으로 다음 블록까지 보는 탐색(`BeamSearch`, beam 8)을 선택 기능으로 둔다. 보이지 않는 정보는 쓰지 않는다 | 1수 88% → 2수 98%. 결정 하나에 수십 ms라 게임에는 충분하지만, 학습용 대전에 쓰기에는 약 20배 느리다 |
| 모델 실행 | 학습한 모델을 **ONNX 파일**로 내보내 Java가 **ONNX Runtime**으로 실행 | 모델이 맞지 않거나 실행에 실패하면 휴리스틱 AI로 자동 대체 |
| 페르소나 | 보상 비율이 다른 여러 AI가 신경망을 공유하며 각자 **페르소나 임베딩**을 학습 → 임베딩을 클러스터링 → 실제 행동 지표로 검증 | 신경망 전체 가중치와 달리 임베딩은 같은 좌표계라 클러스터링이 의미 있다 |
| 예상 승률 | **딥러닝 승률 예측망**(두 플레이어의 결정 시점 상태 → P1이 이길 확률, 형식 `winprob-1`). 자리를 바꾸면 정확히 1 − p가 되도록 모델에 넣었다 | AI끼리의 대전 기록으로 학습한다. 사람과 둔 기록은 없다 |
| 공격 규칙 | 일반 클리어 **1/2/3/4**, T-spin 2/4/6, T-spin mini 1/2, 연속 보너스(B2B) +1, 콤보, 퍼펙트 클리어 +10 | 과제 3번. 한 줄만 지워도 공격이 되므로 콤보가 강하다 |
| 도구 | Gradle·외부 라이브러리 모두 사용, Java 21 | 사용한 도구는 모두 이 문서(10·11장)에 기록 |
| 모델 작업의 범위 | 학습, AI의 배치 고르기, 승률 예측까지. 화면·입력·시간 진행은 게임 쪽이 맡고, 모델 쪽은 모델 파일과 Java API를 넘긴다 | 10-01 결정 |

## 3. 지금까지 구현된 것

### 3.1 규칙 엔진 — `src/main/java/kr/ac/jbnu/se/tetris/core/`

화면과 무관한 순수 규칙 코드다. [RULES.md](docs/RULES.md)를 그대로 옮겼고, 같은 시드와 같은 선택이면 언제나 같은 결과가 나온다.

| 클래스 | 하는 일 |
|---|---|
| `GameModel` | **한 판 진행.** 블록 순서, 미리보기 5개, 홀드, 고정 처리 전체(줄 삭제 → 공격 → 상쇄 → 쓰레기 줄 상승 → 패배 판정), 1인 연습용 쓰레기 줄 공급(`S-3`, `S-mix`), 상대 공격 받기, 통계, **상태 통째 복사**(AI가 몇 수 앞을 미리 계산할 때). 실시간 조작용으로 블록 도중 홀드, 움직이던 블록을 그 자리에 고정, 블록 도중 위치에서 자리 찾기 |
| `TimedPlayer`, `TimedMatch` | **실시간 규칙**(RULES 6.3, 12.3). 1ms 틱마다 입력 → 중력 → 락 딜레이. 소프트드롭, 30초마다 오르는 중력 단계와 판의 상한, 두 플레이어의 송신은 틱 끝에 도착, 같은 틱에 둘 다 지면 무승부 |
| `PlacementFinder` | 지금 블록을 놓을 수 있는 **모든 최종 자리와 조작 순서**를 찾는다(홀드 포함 블록당 평균 42곳, 탐색 약 0.4ms). AI의 "손" |
| `Grid` | 10 × 24 보드, 줄 삭제, 쓰레기 줄 삽입 |
| `PieceType`, `PieceState`, `Op`, `Movement`, `Kicks` | 블록 7종의 회전 모양, 이동·회전 조작, SRS 벽 킥 |
| `TSpinDetector`, `Spin` | T-spin / mini 판정 (3코너 규칙, 킥 4 승격) |
| `Locking` | 고정, 락 아웃, 블록 아웃 판정 |
| `Attack` | 공격량, 콤보, B2B 계산 |
| `GarbageQueue` | 받을 쓰레기 줄 대기열, 상쇄, 한 번에 최대 8줄 상승 |
| `SevenBag`, `SplitMix64` | 시드로 정해지는 블록 순서와 난수. 두 플레이어가 같은 순서의 블록을 받는다 |
| `Rules` | 엔진이 구현한 규칙 버전(`0.3`) |

### 3.2 AI 부품과 학습용 대전 러너 — `src/main/java/kr/ac/jbnu/se/tetris/ai/`, `train/`

| 클래스 | 하는 일 |
|---|---|
| `PlacementScorer` | "후보 자리마다 점수를 매긴다"는 공통 인터페이스. 결정 시점의 상대 상태도 함께 받는다. 딥러닝·기준 AI가 이것을 구현한다 |
| `Afterstate`, `AfterstateBuilder` | 후보 자리에 놓은 **직후의 상태**(애프터스테이트)를 만든다. 모델에 넣을 정보다 |
| `Opponent` | 배치를 고르는 순간 보이는 **상대의 상태**(보드, 받을 가비지, 콤보, B2B, 홀드). 상대가 없으면 `Opponent.NONE` |
| `FeatureEncoder` | 애프터스테이트와 상대 상태를 모델 입력 숫자로 바꾼다(형식 2: 내 보드 + 특징 48개 + 상대 보드 + 상대 특징 10개). 형식은 [MODEL_IO.md](docs/MODEL_IO.md) |
| `OnnxScorer` | ONNX 모델을 불러 점수를 매긴다. 모델 꼬리표(형식·규칙 버전)와 입력 네 개·출력의 모양을 확인하고, 맞지 않으면 거부한다. 여러 스레드가 함께 쓸 수 있다 |
| `BaselineScorer` | 가장 단순한 기준 AI(높이·구멍·울퉁불퉁함·지운 줄). 학습의 세대 0 상대이자 비상용 대체 |
| `Chooser` | 점수 중 하나를 고른다. 온도 0이면 1등, 0보다 크면 확률로 뽑는다(학습 중 탐험, 난이도). 판 시드로 재현된다 |
| `BeamSearch` | **2수 탐색.** 1수 점수 상위 몇 개에 대해 다음 블록의 배치까지 가치망으로 본다. 새로 공개될 블록과 가비지 구멍 열처럼 보이지 않는 정보는 쓰지 않는다([TRAINING.md](docs/TRAINING.md) 6.2) |
| `PlayerState`, `WinRate` | 한 플레이어의 결정 시점 상태와, 두 상태로 P1이 이길 확률을 내는 **승률 예측망** 실행기(형식 `winprob-1`, [MODEL_IO.md](docs/MODEL_IO.md) 9장). 끝난 판은 모델 없이 결과를 돌려준다. `PlayerState.of(GameModel)`은 게임 스레드에서 부른다 |
| `Brain` | **배치 고르기**: 후보마다 애프터스테이트 → 점수 → 온도로 고르기(또는 2수 탐색). 학습 대전과 게임이 같은 코드를 쓴다 |
| `AiController` | **AI의 손**: 생각 지연 뒤 목표 자리를 정하고, 입력 간격마다 지금 위치에서 경로를 다시 찾아 한 조작씩 한다. 중력 때문에 못 가면 다시 고르고, 그 비율(실행 실패율)을 센다 |
| `AiCatalog` | `models/manifest.json`을 읽어 난이도·페르소나 목록과 두뇌를 만든다. 모델이 없거나 못 쓰면 기준 AI로 대신한다 |
| `train.MatchRunner` | **블록 동기 대전 한 판**을 두고 기록 `match-1`을 만든다([TRAINING.md](docs/TRAINING.md) 6, 7장) |
| `train.MatchMain` | 대전 계획 파일대로 여러 판을 여러 스레드로 둔다(`gradlew match`). 학습 루프가 세대마다 부른다 |

### 3.3 게임 화면 — `src/main/java/kr/ac/jbnu/se/tetris/ui/`

| 클래스 | 하는 일 |
|---|---|
| `TetrisApp` | 창과 진입점(`gradlew run`). 메뉴와 대전 화면을 오간다 |
| `MenuPanel` | 모드(사람 대 AI / 혼자 연습), 난이도, 페르소나, 혼자 연습의 중력 상한·쓰레기 줄 |
| `GameSession` | 한 판을 실제 시간으로 진행하는 스레드. 사람은 P1, AI는 P2. 블록이 고정될 때마다 예측 승률을 다시 잰다 |
| `GamePanel` | 그리기(보드, 고스트, 홀드, 미리보기, 쓰레기 줄 막대, 통계, 승률 막대, 결과)와 키 입력 |
| `HumanInput` | 키 → 틱마다 조작 하나. 좌우 자동 반복 DAS 170ms / ARR 50ms `[초안]` |

키: ← → 이동, ↓ 소프트드롭, Space 하드드롭, ↑ 또는 X 시계 회전, Z 반시계, C 또는 Shift 홀드, P 일시정지(혼자 연습), Esc 메뉴.

### 3.4 Python — `python/`

| 파일 | 하는 일 |
|---|---|
| `tetris_ai/model_io.py` | 모델 입력 인코더(형식 2). Java `FeatureEncoder`와 **같은 숫자**를 만든다 |
| `tetris_ai/records.py` | 대전 기록 `match-1`을 읽어 플레이어별 궤적(모델 입력 배열, 승패)으로 바꾼다 |
| `tetris_ai/net.py` | 가치망(PyTorch), 보드 통계, 보상, 기준 평가, ONNX 내보내기 |
| `tetris_ai/train.py`, `metrics.py` | TD(λ) 학습, 평가 지표(승률, APP, 상호작용 지표 등) |
| `tools/train_loop.py` | **학습 루프.** 세대마다 대전(Java) → 학습 → 내보내기 → 평가. β·학습률 일정, 다른 실행에서 이어 시작(`--init-from`), 과적합 확인 열 |
| `tools/evaluate.py` | 모델을 1,000판으로 다시 잰다(95% 범위). β만 바꿔 내보내기, 2수 탐색 |
| `tetris_ai/runner.py` | Java 대전 러너 부르기. 실행마다 jar 사본(`runs/<실행>/runner/`)을 고정해, 학습 중에 Java 코드를 고쳐도 학습이 깨지지 않는다 |
| `tools/eval_ladder.py` | **난이도 사다리**: 학습량이 다른 모델들을 기준 AI·이웃 후보와 대전시켜 단계를 고른다 |
| `tools/make_bundle.py` | 게임용 **모델 묶음** `models/`(난이도별 모델, 페르소나, 승률 모델, `manifest.json`)을 만든다 |
| `tetris_ai/personas.py` | 페르소나 학습 부품: AI 표(원형 4 + 디리클레로 섞은 8), 단계 A 체크포인트에서 임베딩 늘리기, 리그 대전 계획, AI별 내보내기, 지표 |
| `tetris_ai/persona_analysis.py` | 군집 분석: k-means·GMM·실루엣, ARI·NMI, Mantel 검정, 짝지은 순열 검정, PCA, 행동으로 이름 붙이기 |
| `tools/train_personas.py` | **페르소나 학습 루프** (AI 12개, 리그 대전, 세대마다 AI별 ONNX) |
| `tools/analyze_personas.py` | 페르소나 분석(고정 상대와 AI별 대전, 군집, PCA, 보고서)과 게임용 파일 `value_<페르소나>_<단계>.onnx` |
| `tetris_ai/winprob.py` | **승률 예측망:** 상태 인코더(Java `WinRate`와 같은 숫자), 기록 읽기와 판 단위 나누기, 자리 대칭 모델, ONNX 내보내기, 보정 지표 |
| `tools/make_winprob_data.py`, `train_winprob.py` | 승률 예측망 자료(여러 세대·온도의 AI끼리 4만 판, Java 러너)와 학습·평가·내보내기 |
| `tools/make_test_model.py`, `make_value_fixture.py`, `make_winprob_fixture.py` | 공통 테스트 사례와 시험용 ONNX 모델(선형 모델, 작은 가치망, 승률 모델, 거부 시험용 모델)을 만든다 |
| `tests/` | Python 시험: 공통 픽스처(모델 입출력, 대전 기록, 승률), 보드 통계·보상·λ-리턴 손 계산, ONNX 내보내기, 학습 루프 설정, 승률 예측망 |

### 3.5 공통 테스트 데이터 — `fixtures/`

Java와 Python이 **같은 JSON을 읽어 같은 결과를 내야 하는** 테스트 사례다. 형식은 [FIXTURES.md](docs/FIXTURES.md).

| 폴더 | 사례 수 | 확인하는 것 |
|---|---|---|
| `rng/` | 11 | 난수, 블록 순서, 쓰레기 줄 구멍 위치 |
| `rotation/` | 6 | 벽·바닥 킥, 모든 킥 실패, O 회전 |
| `tspin/` | 4 | T-spin 더블, mini, 킥 4 승격, 회전 뒤 내려와 spin이 사라지는 경우 |
| `attack/` | 13 | 공격표, 콤보, B2B, 퍼펙트 클리어 |
| `garbage/` | 6 | 상쇄, 받기, 8줄 상한, 삽입 순서, 쓰레기 줄 패배 |
| `placement/` | 4 | 빈 보드와 T-spin 자리에서 찾은 자리 목록 |
| `topout/` | 5 | 블록 아웃, 락 아웃 |
| `game/` | 4 | 한 판 진행(홀드, 테트리스 + 퍼펙트 클리어 뒤 쓰레기 줄, 패배 두 종류) |
| `model_io/` | 14 | 모델 입력 인코딩(형식 2, 상대 상태 포함), ONNX 실행 결과(Python 계산값과 비교), 꼬리표. 시험용 선형 모델과 실제 구조의 작은 가치망 |
| `match/` | 2 | 2인 대전 기록: Java로 재생해 엔진과 일치, Java·Python이 같은 모델 입력을 만드는지 |
| `winprob/` | 18 | 승률 예측망 입력(`winprob-1`) 인코딩, 기록 `state`에서 만든 입력, ONNX 실행 결과(Python 계산값과 비교, 손 계산 3개), 꼬리표 |

### 3.6 문서 — `docs/`

계획서(v5), 규칙 명세(0.3), 픽스처 형식, 모델 입출력 형식, 학습 설계(초안), 게임 설계, 진행 보고서. 13장 참고.

## 4. 검증 결과

| 검사 | 결과 |
|---|---|
| `gradlew build` | **Java 테스트 197개 통과**, 컴파일 경고 0, Gradle 경고 0 |
| Python 시험 | **62개 통과** (`python -m unittest discover -s python/tests`) |
| 공통 테스트 사례 | 16개 파일, **87개 사례** 모두 통과 |
| 독립 검증 | 엔진 코드를 보지 않은 검증 에이전트 4개가 규칙서만 보고 사례 35개의 답을 다시 계산 → **불일치 0** (규칙 0.2 당시). 이때 찾은 모호한 문장 30여 건을 규칙서에 반영 |
| 테스트가 오류를 잡는가 | 기대값을 일부러 틀리게 바꾼 복사본으로 시험: 규칙 오류 6/6, 규칙+형식 오류 10/10, 모델 입출력 오류 2/2, 승률 예측망 사례 4/4, 좌우 뒤집은 기대 보드 3/3 모두 검출 |
| Java ↔ Python 모델 | 입력(형식 2) **정확히 일치**, ONNX 실행 결과 **오차 10⁻⁵ 이내**. 시험용 선형 모델과, 학습 루프와 같은 구조의 작은 가치망 모두 |
| Java ↔ Python 대전 기록 | Java 러너가 쓴 기록을 Python으로 읽어 만든 모델 입력이 Java 인코더의 값과 **정확히 일치** |
| 대전 기록 재생 | 기록의 시드와 배치 키만으로 다시 두면 모든 상태·결과가 같다. 기록 값만으로 블록 동기 모드의 순서(송신은 턴 끝에 도착)도 확인 |
| 내보낸 모델 | 세대마다 내보낸 ONNX 모델이 PyTorch와 같은 값을 내는지 학습 루프가 확인(차이 10⁻⁶ 안팎) |
| Java ↔ Python 승률 예측망 | 입력(`winprob-1`) **정확히 일치**(픽스처, 대전 기록을 다시 두며 모든 턴 비교). 학습한 모델로 실제 상태 500쌍을 계산한 값의 차이 2 × 10⁻⁷ 이내 |
| 실력 측정 | 최종 결과는 개발·학습·평가에 쓰지 않은 시드 1,000판으로 한 번만 잰다(95% 범위 ±0.02~0.03). 200판 평가로 고른 "최고 세대"는 운이 섞여 부풀려진다(첫 학습에서 0.835 → 1,000판으로 0.76) |
| 2수 탐색 | 시험 7개(손 계산 기대값). 탐색으로 둔 대전 기록도 재생 검사를 통과 |
| 실시간 규칙·AI 조작 | 시험 25개: 중력·락 딜레이·리셋 15회·홀드·틱 끝 송신을 틱 단위로, 사람이 회전으로 끼운 T-spin, 중력이 없으면 AI가 시간 없는 모드와 같은 자리를 같은 순서로 둠 |
| 화면 | 창 없이 그린 화면(`gradlew screenshot` → `build/screens/*.png`)으로 배치 확인 |
| 자리 찾기 경로 | 자기 플레이 수천 블록에서 찾은 경로 수십만 건을 처음부터 재생해 모두 같은 자리에 도착 |
| 새로 clone해서 재현 | GitHub에서 새로 받아 이 문서대로 따라 해 빌드와 Python 환경 구성이 되는 것을 확인. 이 과정에서 한국어 Windows의 pip 설치 문제를 찾아 고쳤다 |
| 독립 리뷰 | 문서 일관성·코드 버그·모델 입출력 세 관점의 리뷰 에이전트가 지적을 내고, 지적마다 다른 에이전트가 반박을 시도(에이전트 총 23개). 반박을 견딘 18건을 고쳤다 |

**아직 확인하지 못한 것:** macOS에서의 빌드, **사람이 게임 창에서 직접 해 보는 확인**(키 감각, 단계별 체감 난이도), Python으로 규칙을 따로 구현해 교차 검증하는 것.

## 5. 아직 안 된 것과 다음 작업

**다음 작업 (권장 순서)**

10-04부터 **게임 전체도 함께 개발**하고, 모델은 백그라운드에서 계속 학습한다(사용자 결정). AI는 **2인 대전으로 학습**한다. 자세한 순서는 [TRAINING.md](docs/TRAINING.md) 13장과 [GAME.md](docs/GAME.md) 9장.

끝난 것:
- 10-01: 모델 입력 형식 2(상대 상태), Java 2인 대전 러너와 기록, Python 학습기(PyTorch), 학습 루프, 첫 학습(76%).
- 10-02: **단계 A 완료**(기준 AI 상대 88%, 2수 탐색 98%), 2수 탐색, **승률 예측망**(ECE 0.006, Java `WinRate`), **페르소나 학습·분석 도구와 검증 실행**. 세 작업을 에이전트 셋이 나눠 병렬로 했다. 결과는 [docs/PROGRESS.md](docs/PROGRESS.md) 0.6~0.8.

- 10-04: **게임 첫 판**(화면, 실시간 규칙, AI 조작기, 예측 승률 막대), **난이도 사다리와 5단계**, 수비형 보상 다시 설계, 학습 러너 고정. [docs/PROGRESS.md](docs/PROGRESS.md) 0.9.

1. **사람이 직접 해 보기:** 키 감각과 단계별 체감 난이도를 보고 생각 지연·초당 입력·온도를 조정(계획서 7장 목표: 단계별 사람 승률 대략 90 / 70 / 50 / 30 / 10%)
2. **승률 예측망을 사람 판으로 확인하기:** 실시간 판 84,000판으로 다시 학습한 `wp02`를 게임에 넣었다(실시간 시험 판 log loss 0.360, 옛 `wp01`은 0.520). 사람 판에서도 나은지는 약 50판이면 가려진다. 사람 판은 게임이 `runs/human/`에 자동 저장한다(`"-Dtetris.records=off"`로 끔) ([TRAINING.md](docs/TRAINING.md) 10.9~10.11)
3. 승률 막대를 사람 대 AI 대전에 맞춰 다시 보정할지

**아직 없는 것:** AI끼리 관전, 2인 키보드 대전, 키 설정 화면, 효과음, 휴리스틱 페르소나 AI.

**남은 결정 (팀 회의 필요)**

- 규칙서의 `[초안]` 항목(중력 단계표, 소프트드롭 속도, 쓰레기 줄 구멍 열 등). 락 딜레이의 틱 단위 절차는 10-04에 정했다(RULES 6.3)
- 학습 설계 초안([TRAINING.md](docs/TRAINING.md)) 검토: 보상 가중치, 대전 상대 구성 등 `[초안]` 수치
- 페르소나와 난이도를 섞는 방법(지금은 페르소나가 두뇌만 바꾸고 온도·조작 속도는 난이도를 따른다)
- 승률 막대를 어떤 대전 구성(사람 대 AI 난이도별, AI끼리)에 맞춰 보정할지
- 페르소나: 게임에 몇 개를 둘지(군집 분석은 2~3개를 가리키고 설계는 4개), 이름을 행동과 설계 중 무엇으로 붙일지
- 역할 분담 ([PLAN.md](docs/PLAN.md) 12장)
- 학습 모델을 시연할 PC: **Windows 또는 Apple Silicon Mac**이어야 한다(ONNX Runtime이 Intel Mac을 지원하지 않음)

자세한 진행 상황은 [docs/PROGRESS.md](docs/PROGRESS.md) 0장.

## 6. 처음 받았을 때

1. **JDK 21**을 설치한다(10장). Git도 필요하다.
2. 저장소를 받는다.
   ```bash
   git clone https://github.com/ZAHA034/SourceCode_tetris.git
   cd SourceCode_tetris
   ```
3. 빌드와 테스트를 돌린다. 처음에는 Gradle과 라이브러리를 내려받느라 몇 분 걸린다.
   ```bash
   .\gradlew.bat build     # Windows PowerShell
   ./gradlew build         # macOS / Linux
   ```
   끝에 `테스트 N개: 통과 N, 실패 0, 건너뜀 0`(10-04 기준 197개)이 나오면 정상이다. Intel Mac에서는 ONNX 관련 테스트가 "건너뜀"으로 나오는데, 이것도 정상이다.
4. IDE에서 연다(9장 끝). 게임은 `gradlew run`으로 띄운다. 학습한 모델 묶음 `models/`가 저장소에 들어 있어서, 받자마자 학습한 AI와 둘 수 있다. `models/`가 없으면 모든 단계가 기준 AI로 둔다. 묶음을 다시 만들려면 `python python/tools/make_bundle.py --p04-personas`를 쓰는데, 학습 결과 `runs/`가 필요하다.
5. 학습·분석 작업을 맡는다면 Python 환경을 만든다(11장).

## 7. 알아 두면 좋은 개념

| 개념 | 설명 |
|---|---|
| **좌표** | 문서·JSON·`core` 코드 모두 **0행이 맨 위, 23행이 바닥**이다. 0~3행은 화면에 안 보이는 숨김 영역. 칸은 `[행, 열]`로 쓴다 |
| **회전 박스 위치 `(x, y)`** | 블록 모양이 들어 있는 3×3(I·O는 4×4) 박스의 **왼쪽 위 칸의 (열, 행)**. 칸 좌표와 순서가 반대이니 주의 |
| **배치 키** | AI가 고르는 "자리"의 이름. 예: `h0\|full\|21,3;21,4;21,5;22,4` = 홀드 안 씀, 정식 T-spin, 칸 4개. 같은 칸이라도 T-spin 여부가 다르면 다른 자리다 |
| **애프터스테이트** | 블록을 놓고 줄 삭제·공격 계산까지 끝낸 직후의 상태. 딥러닝 모델은 이것의 좋고 나쁨을 점수로 매긴다 |
| **시드** | 같은 시드면 블록 순서와 난수가 늘 같다. 두 플레이어는 같은 시드로 같은 블록을 받는다 |
| **픽스처** | `fixtures/`의 JSON 테스트 사례. 규칙의 일부로 취급한다 |
| **모델 꼬리표** | ONNX 파일 안에 적는 형식 버전과 규칙 버전. 게임의 규칙 버전과 다르면 그 모델을 쓰지 않는다 |

## 8. 팀 작업 규칙

- **규칙을 바꿀 때는 순서를 지킨다:** `fixtures/`의 사례를 먼저 고치고 → [RULES.md](docs/RULES.md)의 규칙 버전을 올리고 → Java·Python 코드를 고친다. 규칙 버전이 바뀌면 학습한 모델도 다시 만들어야 한다.
- **커밋 전에 `gradlew build`가 통과하고 경고가 0인지 확인한다.**
- **도구·라이브러리를 추가하면 같은 커밋에서 이 README(10·11장)를 고친다.**
- **모델 입력 형식을 바꿀 때는** [MODEL_IO.md](docs/MODEL_IO.md) → `fixtures/model_io/` → Java·Python 코드 순서로 고치고, `python/tools/make_test_model.py`, `make_value_fixture.py`로 시험 모델을 다시 만든다. **대전 기록 형식**은 [TRAINING.md](docs/TRAINING.md) 7장 → `fixtures/match/`(`gradlew matchFixture`) → 코드 순서다.
- 학습 결과(`runs/`)는 저장소에 올리지 않는다. 게임에 쓰는 모델 묶음은 `models/`(ONNX)에, 이어 학습할 핵심 체크포인트는 `weights/`([weights/README.md](weights/README.md))에 올린다.
- 손으로 만든 테스트 사례에는 계산 과정을 `note`에 남긴다. 구현이 낸 값을 그대로 기대값으로 붙여 넣지 않는다.
- 줄바꿈은 `.gitattributes`가 LF로 맞춘다(Windows 배치 파일만 CRLF). `python/.venv/`, `build/`, `.idea/`는 올리지 않는다.
- `python/requirements*.txt`는 **ASCII로만** 쓴다(한국어 Windows에서 pip이 한글을 읽지 못한다).

## 9. 빌드·실행 명령

JDK 21만 설치돼 있으면 된다. Gradle은 `gradlew`가 처음 실행될 때 자동으로 내려받는다.

```bash
# Windows PowerShell (Windows 11 기본 터미널). 앞의 .\ 가 필요하다
.\gradlew.bat run      # 게임 실행
.\gradlew.bat build    # 컴파일 + 모든 테스트

# Windows 명령 프롬프트(cmd)
gradlew run
gradlew build

# macOS / Linux
./gradlew run
./gradlew build
```

아래 표의 `gradlew`는 줄여 쓴 것이다. PowerShell에서는 `.\gradlew.bat`, macOS·Linux에서는 `./gradlew`로 입력한다.

게임 설정(`-Dtetris.…`)을 줄 때 **PowerShell에서는 따옴표로 감싼다.** 감싸지 않으면 PowerShell이 `-Dtetris.fullscreen=true`를 `-Dtetris`와 `.fullscreen=true` 둘로 나눠 넘긴다.

```powershell
.\gradlew.bat run "-Dtetris.fullscreen=true"   # 전체 화면으로 시작 (F11로도 켜고 끈다)
.\gradlew.bat run "-Dtetris.records=off"       # 사람 판 기록을 남기지 않음
```

| 명령 | 하는 일 |
|---|---|
| `gradlew run` | 게임 실행 (`kr.ac.jbnu.se.tetris.ui.TetrisApp`). 모델 묶음은 `models/`(다른 폴더는 `-Dtetris.models=<폴더>`) |
| `gradlew build` | 컴파일과 모든 테스트(공통 픽스처 포함) |
| `gradlew test` | 모든 테스트. 끝에 `테스트 N개: 통과 …, 실패 …` 요약이 나오고, 자세한 결과는 `build/reports/tests/test/index.html` |
| `gradlew fixtures` | 공통 픽스처(`fixtures/`)만 실행. 규칙 엔진이 [RULES.md](docs/RULES.md)대로 동작하는지 확인 |
| `gradlew test -PfixturesDir=<경로>` | 다른 폴더의 픽스처로 테스트 (픽스처를 고치기 전에 시험할 때) |
| `gradlew bench --args="5000 1"` | 자리 찾기 속도 측정과 경로 검증 (블록 수, 시드) |
| `gradlew match --args="<plan.json>"` | 대전 계획 파일대로 AI끼리 대전시키고 기록을 남긴다. 보통은 학습 루프가 부른다([TRAINING.md](docs/TRAINING.md) 6.1) |
| `gradlew matchFixture` | `fixtures/match/`의 대전 기록 사례를 다시 만든다(기록 형식이나 엔진을 바꿨을 때만) |
| `gradlew screenshot` | 메뉴·대전·결과 화면을 창 없이 `build/screens/*.png`로 그린다(배치 확인용) |
| `gradlew levelBench --args="<결과.json> <판 수> <스레드>"` | 게임의 난이도 단계를 가상 시간 모드로 대전시켜 실행 실패율·초당 블록 수·이웃 단계 승률을 잰다 |

학습과 Python 시험 명령은 11장과 [TRAINING.md](docs/TRAINING.md) 12.1에 있다.

> **저장소를 OneDrive 아래(바탕 화면 등)에 두어도 된다.** OneDrive는 폴더마다 읽기 전용 속성을 붙이는데, Windows에서는 읽기 전용 폴더를 지울 수 없어 Gradle이 `Unable to delete directory`로 멈춘다. `build.gradle.kts`가 빌드를 시작할 때 `build/`와 `.gradle/` 아래 폴더의 이 속성을 먼저 풀어 준다.

**IDE**

- **IntelliJ IDEA:** `File > Open`으로 저장소 폴더(또는 `build.gradle.kts`)를 열면 Gradle 프로젝트로 인식한다. 프로젝트 JDK는 21로 맞춘다. `.idea/`, `*.iml`은 저장소에 올리지 않는다.
- **VS Code:** "Extension Pack for Java"와 "Gradle for Java" 확장을 설치하고 폴더를 연다.

## 10. 설치와 사용 도구

### 10.1 설치해야 하는 것

| 도구 | 버전 | 용도 | 설치 |
|---|---|---|---|
| **JDK** | 21 | Java 컴파일·실행, Gradle 실행 | Windows: `winget install EclipseAdoptium.Temurin.21.JDK` / macOS: `brew install --cask temurin@21` / 또는 [adoptium.net](https://adoptium.net) |
| **Git** | 2.x | 소스 관리 | Windows: `winget install Git.Git` / macOS: `xcode-select --install` 또는 `brew install git` |
| **Python** | 3.11 | 학습 AI, 데이터 분석 (게임 실행에는 필요 없음) | Windows: `winget install Python.Python.3.11` / macOS: `brew install python@3.11` |

- JDK 21이 아닌 다른 버전이 설치돼 있어도 괜찮다. Gradle이 JDK 21을 찾지 못하면 자동으로 내려받는다(아래 foojay 플러그인). 다만 Gradle 자체를 실행하려면 JDK 17 이상이 하나는 있어야 한다.
- Windows에서 설치 직후에는 **새 터미널을 열어야** PATH가 반영된다.

### 10.2 자동으로 받아지는 것 (설치 불필요)

| 도구·라이브러리 | 버전 | 용도 | 설정 위치 |
|---|---|---|---|
| Gradle (Wrapper) | 9.8.0 | 빌드 도구. `gradlew`가 내려받고 SHA-256을 확인한다 | [gradle/wrapper/gradle-wrapper.properties](gradle/wrapper/gradle-wrapper.properties) |
| foojay-resolver-convention | 1.0.0 | JDK 21이 없으면 자동으로 내려받는 Gradle 플러그인 | [settings.gradle.kts](settings.gradle.kts) |
| JUnit (Jupiter) | 6.1.3 | 테스트 프레임워크. JUnit 5와 같은 API(`@Test`, `@TestFactory`)이며 Java 17 이상 필요 | [build.gradle.kts](build.gradle.kts) (테스트 전용) |
| Jackson Databind | 2.22.3 | JSON 읽기·쓰기. 학습용 대전 계획·기록, 테스트의 픽스처 | [build.gradle.kts](build.gradle.kts) |
| ONNX Runtime (Java) | 1.30.0 | Python에서 학습해 내보낸 `.onnx` 모델을 게임 안에서 실행 ([docs/MODEL_IO.md](docs/MODEL_IO.md)) | [build.gradle.kts](build.gradle.kts) |

라이브러리는 [Maven Central](https://central.sonatype.com)에서 받는다. 버전을 올릴 때는 `build.gradle.kts`와 이 표를 함께 고친다.

> **ONNX Runtime이 도는 플랫폼:** jar 안에 **Windows x64, Linux x64·arm64, macOS Apple Silicon(M1 이후)** 용 실행 파일만 들어 있다. **Intel Mac**과 ARM Windows에서는 쓸 수 없다. 이런 PC에서도 `gradlew build`는 통과하지만 ONNX 테스트는 "건너뜀"으로 표시되고, 게임에서는 학습 모델 대신 휴리스틱 AI가 쓰인다.

### 10.3 보조 도구

| 파일 | 용도 | 실행 |
|---|---|---|
| [tools/rng_reference.ps1](tools/rng_reference.ps1) | 난수 픽스처 기대값을 따로 계산하는 참조 구현 | Windows PowerShell: `powershell -ExecutionPolicy Bypass -File tools\rng_reference.ps1` |
| [python/tools/make_test_model.py](python/tools/make_test_model.py) | 시험용 선형 ONNX 모델과 `fixtures/model_io/linear_v2.*` 생성 | 11장의 Python 환경에서 `python python/tools/make_test_model.py` |
| [python/tools/make_value_fixture.py](python/tools/make_value_fixture.py) | 작은 가치망과 `fixtures/model_io/value_tiny.*` 생성 (PyTorch 필요) | `python python/tools/make_value_fixture.py` |
| [python/tools/train_loop.py](python/tools/train_loop.py) | **학습 루프** (PyTorch 필요). 결과는 `runs/<실행 이름>/` | `python python/tools/train_loop.py --run r01 --gens 80` |
| [python/tools/evaluate.py](python/tools/evaluate.py) | 모델을 1,000판으로 다시 재기(95% 범위), β만 바꿔 내보내기, 2수 탐색 | `python python/tools/evaluate.py --model <모델.onnx> --seeds 930001:930500 --search '{"beam": 8}'` |
| [python/tools/make_winprob_data.py](python/tools/make_winprob_data.py), [train_winprob.py](python/tools/train_winprob.py) | 승률 예측망 자료 만들기(Java 대전 러너)와 학습·평가·내보내기 | `python python/tools/make_winprob_data.py --run wp01 --matches 40000`, `python python/tools/train_winprob.py --run wp01` |
| [python/tools/winprob_learning_curve.py](python/tools/winprob_learning_curve.py), [plot_winprob_curve.py](python/tools/plot_winprob_curve.py), [winprob_speed_subsets.py](python/tools/winprob_speed_subsets.py) | 승률 예측망 학습 곡선(판 수별 성적, 사람 판 몇 판이면 잴 수 있나), 그 그림(`docs/figures/`, matplotlib 필요), 속도 차이별 비교 ([TRAINING.md](docs/TRAINING.md) 10.10~10.11) | `python python/tools/plot_winprob_curve.py --run wplc02 --test-games 8000 --png docs/figures/winprob_learning_curve_80k.png` |
| [python/tools/make_winprob_fixture.py](python/tools/make_winprob_fixture.py) | `fixtures/winprob/` 사례와 시험용 승률 모델 생성 (PyTorch 필요) | `python python/tools/make_winprob_fixture.py` |
| [python/tools/train_personas.py](python/tools/train_personas.py) | 페르소나 학습 루프 (PyTorch 필요) | `python python/tools/train_personas.py --run p03 --gens 11 --init runs/a2/checkpoints/gen-055.pt` |
| [python/tools/analyze_personas.py](python/tools/analyze_personas.py) | 페르소나 분석과 게임용 파일 (scikit-learn 필요) | `python python/tools/analyze_personas.py --run p03 --gen 10 --reference runs/a2/models/value_ai00_gen-055.onnx` |

## 11. Python 환경 (학습·분석)

게임 실행에는 필요 없다. 학습 모델을 만들거나 분석할 때만 쓴다. 가상환경은 `python/.venv`에 만들고 저장소에는 올리지 않는다.

**Windows (PowerShell)**

```powershell
py -3.11 -m venv python\.venv                 # 가상환경 만들기 (처음 한 번)

# 가상환경 켜기. 처음 한 번은 스크립트 실행을 허용해야 한다(현재 사용자에게만 적용)
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
python\.venv\Scripts\Activate.ps1

python -m pip install --upgrade pip
python -m pip install -r python\requirements.txt
python python\tools\make_test_model.py        # 시험용 ONNX 모델과 fixtures/model_io 사례 다시 만들기
```

실행 정책을 바꾸고 싶지 않으면 켜지 말고 `python\.venv\Scripts\python.exe -m pip ...`처럼 가상환경의 python을 직접 불러도 된다. cmd에서는 `python\.venv\Scripts\activate.bat`로 켠다.

**macOS / Linux**

```bash
python3.11 -m venv python/.venv               # Homebrew 는 python 이 아니라 python3.11 명령을 만든다
source python/.venv/bin/activate              # 켠 뒤에는 python 명령을 쓸 수 있다

python -m pip install --upgrade pip
python -m pip install -r python/requirements.txt
python python/tools/make_test_model.py
```

**학습까지 하려면** PyTorch와 scikit-learn(페르소나 분석)을 더 설치한다. 약 3GB이고, RTX 50 시리즈 GPU는 CUDA 12.8 이상용 빌드가 필요해 CUDA 13.0 빌드를 받는다. NVIDIA GPU가 없는 PC(예: macOS)에서는 pip이 일반 빌드를 받는다.

```bash
python -m pip install -r python/requirements-train.txt
python -m unittest discover -s python/tests      # Python 시험 (픽스처 교차 확인, 모델)
python python/tools/train_loop.py --run r01 --gens 80
```

| 패키지 | 버전 | 용도 | 파일 |
|---|---|---|---|
| numpy | 2.4.6 | 모델 입력 텐서 만들기 | `requirements.txt` |
| onnx | 1.23.0 | ONNX 모델 만들기·검사 | `requirements.txt` |
| onnxruntime | 1.30.0 | 내보낸 모델을 Python에서 실행해 확인. Java 쪽 ONNX Runtime과 같은 버전으로 맞춘다 | `requirements.txt` |
| torch | 2.14.1 (+cu130) | 가치망 학습, ONNX 내보내기. 함께 설치되는 sympy, networkx, jinja2, filelock, fsspec, mpmath, MarkupSafe 포함 | `requirements-train.txt` |
| scikit-learn | 1.9.1 | 페르소나 임베딩 군집 분석(k-means, GMM, 실루엣, ARI, PCA). 함께 설치되는 scipy 1.17.1(헝가리안 할당·순위 상관에도 직접 쓴다), joblib 1.6.0, threadpoolctl 3.7.0, narwhals 2.26.0, cloudpickle 3.1.2 포함 | `requirements-train.txt` |

버전은 [python/requirements.txt](python/requirements.txt)와 [python/requirements-train.txt](python/requirements-train.txt)에 고정한다. 시험은 표준 `unittest`라 따로 설치할 것이 없다.

학습 곡선 그림(`python/tools/plot_winprob_curve.py`)만 **matplotlib**(3.11.2로 그림)이 더 필요하다. 그림을 다시 그릴 때만 쓰므로 requirements 파일에는 넣지 않았다(`python -m pip install matplotlib`).

## 12. 폴더 구조

```
build.gradle.kts, settings.gradle.kts, gradlew*   Gradle 빌드 설정
src/main/java/kr/ac/jbnu/se/tetris/ui/             게임 화면 (Swing): 메뉴, 대전 화면, 키 입력, 실제 시간 진행
src/main/java/kr/ac/jbnu/se/tetris/core/           규칙 엔진 (화면과 무관, RULES.md 구현, GameModel, 실시간 규칙 TimedPlayer·TimedMatch)
src/main/java/kr/ac/jbnu/se/tetris/ai/             AI 부품: 애프터스테이트, 상대 상태, 모델 입력 인코더, 점수 매기기(ONNX, 기준 AI), 고르기(Brain), AI 조작기, 모델 묶음
src/main/java/kr/ac/jbnu/se/tetris/train/          학습용 2인 대전 러너와 기록 쓰기 (gradlew match)
src/test/java/kr/ac/jbnu/se/tetris/                테스트: 공통 픽스처(FixtureTest), GameModel·AI·대전 러너 단위 테스트, 기록 재생(MatchReplay), 성능 측정
fixtures/                                          Java·Python 공통 테스트 데이터 (JSON, 시험용 .onnx)
python/tetris_ai/                                  Python 패키지: 모델 입력 형식, 기록 읽기, 가치망, 학습, 지표
python/tools/                                      학습 루프, 픽스처·시험 모델 생성
python/tests/                                      Python 시험 (unittest)
runs/                                              학습 결과 (git 제외)
models/                                            게임용 모델 묶음 (ONNX, 약 6MB, git 에 포함: 받자마자 학습한 AI 로 게임할 수 있다)
weights/                                           학습을 이어 할 때 쓰는 핵심 PyTorch 체크포인트 (약 10MB, weights/README.md)
docs/                                              계획서, 규칙 명세, 픽스처 형식, 모델 입출력 형식, 학습 설계, 진행 상황
tools/                                             보조 스크립트
REFACTORING.md                                     기존 코드 정리 기록 (2026-09-26)
```

## 13. 문서 지도

| 문서 | 내용 | 이런 때 읽는다 |
|---|---|---|
| [docs/PROGRESS.md](docs/PROGRESS.md) | 진행 상황, 결정 기록, 다음 작업 | 지금 어디까지 왔는지 알고 싶을 때 (0장부터) |
| [docs/PLAN.md](docs/PLAN.md) | 구현 계획서 v5 (목표, 구조, 난이도, 클러스터링, 학습 설계, 일정, 리스크) | 전체 설계를 알고 싶을 때. 학습 AI는 9장 |
| [docs/RULES.md](docs/RULES.md) | 대전 규칙 명세 0.3. Java와 Python이 똑같이 구현해야 하는 유일한 기준 | 규칙 관련 코드를 짜거나 고칠 때 |
| [docs/FIXTURES.md](docs/FIXTURES.md) | 공통 테스트 사례 형식 | 테스트 사례를 추가할 때 |
| [docs/MODEL_IO.md](docs/MODEL_IO.md) | 학습 모델(ONNX) 입력·출력 형식, 꼬리표, 페르소나 모델 파일 | 모델을 학습·내보내기하거나 Java에서 불러올 때 |
| [docs/TRAINING.md](docs/TRAINING.md) | 학습 설계 초안: 2인 대전 학습, 가치망 구조, 보상과 페르소나, TD(λ), 대전 기록 형식, 평가, 승률 예측망, 게임 쪽에 넘길 API | 학습 코드(Java 러너, Python 학습기)를 짜거나 학습 결과를 볼 때 |
| [docs/GAME.md](docs/GAME.md) | 게임 설계: 화면, 실시간 규칙(락 딜레이 절차), 사람의 키 입력, AI 조작기, 난이도·페르소나, 모델 묶음 | 화면이나 AI 조작, 난이도를 고칠 때 |
| [REFACTORING.md](REFACTORING.md) | 09-26 기존 코드 정리 기록 (그 옛 화면 코드는 10-04에 지웠다) | 옛 코드의 내력이 궁금할 때 |

## 14. 커밋 기록 요약

| 커밋 | 내용 |
|---|---|
| `afe399b` | 기존 코드 리팩터링 반영 (버그 5건 수정) |
| `5be8677` | 규칙 명세, 규칙 엔진, 공통 테스트 사례 추가 |
| `0c0c807` | 보드 10 × 24 확정, 규칙 0.2, 독립 검증 반영 |
| `80b526d` | Gradle 도입, 표준 폴더 구조 |
| `85d6955` | 테스트를 JUnit 6 + Jackson으로 |
| `2c7a2c0` | 학습 모델(ONNX) 실행 준비: 입력 형식, Java 실행기, Python 환경 |
| `00ed4c3`, `2d5d505` | 계획서 v4, 진행 보고서, 재현 시험과 독립 리뷰에서 나온 문제 수정 |
| `a990d7e` | `GameModel`: 한 판 진행 |
| `46a97ef` | 규칙 0.3: 과제 3번(지운 줄 수만큼 공격) |
| `fbf4521` | 계획서 v5: 딥러닝 학습 설계 확정 |
