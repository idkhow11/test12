# 공통 테스트 픽스처 형식

> 규칙 버전 `0.3` (보드 10 × 24, 과제 3번 공격표) | 상태: 초안 | 규칙: [RULES.md](RULES.md)

Java 규칙 엔진(`core`, 이후 `GameModel`)과 Python 학습 환경이 같은 규칙을 구현했는지 확인하는 테스트 데이터이다. 두 쪽 테스트(JUnit 6 Jupiter, pytest)가 **같은 JSON 파일을 읽어** 같은 결과를 내야 한다.

## 1. 원칙

- 픽스처는 규칙의 일부이다. 규칙을 바꿀 때는 **픽스처를 먼저 고치고**, 두 구현이 모두 통과할 때까지 병합하지 않는다.
- 기대값은 가능하면 **손으로 계산해 검증할 수 있는 작은 사례**로 만든다. 구현이 뽑아낸 값을 그대로 기대값으로 붙여 넣지 않는다(버그까지 굳어진다). 불가피하면 `"source"`에 출처를 적고 다른 쪽 구현으로 교차 확인한다.
- 한 파일은 한 주제, 여러 사례(`cases`)를 담는다.

## 2. 디렉터리

```
fixtures/
  rng/         SplitMix64, 스트림 파생, 7-bag, 가비지 구멍
  rotation/    이동·회전·SRS 킥
  tspin/       T-spin 판정 (full / mini / 킥 4 승격 / 거리 0 하드드롭)
  attack/      공격·콤보·B2B·퍼펙트 클리어
  garbage/     상쇄, 받기, 상승(8줄 상한), 가비지 아웃
  placement/   PlacementFinder 정규화 키 집합
  topout/      블록 아웃, 락 아웃 (가비지 아웃은 garbage/)
  model_io/    학습 모델 입력 인코딩과 ONNX 실행 (docs/MODEL_IO.md). 시험용 .onnx 파일도 여기 있다
  game/        시드 + 조작 열 → 최종 상태 (통합)
  match/       2인 대전 기록 match-1 과 턴마다의 모델 입력 (docs/TRAINING.md 7장)
  winprob/     승률 예측망 입력 winprob-1 인코딩과 ONNX 실행 (docs/MODEL_IO.md 9장). 시험용 .onnx 파일도 여기 있다
```

JSON 파일만 사례로 읽는다. `.onnx` 같은 다른 파일은 사례가 가리키는 자료다.

## 3. 공통 머리

```json
{
  "rules_version": "0.3",
  "type": "tspin",
  "description": "파일 전체 설명",
  "source": ["hand"],
  "cases": [ { "id": "고유한 사례 이름", "...": "..." } ]
}
```

| 필드 | 설명 |
|---|---|
| `rules_version` | RULES.md의 규칙 버전과 같아야 한다. 다르면 테스트 러너가 실패 처리 |
| `type` | 디렉터리 이름과 같음. 테스트 러너가 이 값으로 사례 형식을 고른다 |
| `source` | **항상 배열.** `hand`(손 계산), `reference-ps`(PowerShell 참조 구현), `java`, `python` 중 하나 이상 |
| `cases[].id` | 파일 안에서 고유. 실패 메시지에 그대로 출력 |

## 4. 공통 값 표기

| 값 | 표기 |
|---|---|
| 보드 | 24개 문자열(0행이 맨 위, 0~3행은 숨김 영역), 각 10글자. `.` 빈칸, `IJLOSTZ` 블록, `G` 가비지, `#` 종류 무관 |
| 보드 비교 | 기본은 **채워짐/비어 있음만** 비교. `"compare": "exact"`이면 글자까지 비교 (`#`은 exact에서 쓰지 않음). exact는 아직 러너가 지원하지 않음(6장) |
| 빈 행 생략 | `"board_bottom": [...]`로 아래쪽 행만 적을 수 있다(마지막 문자열이 23행). 위쪽은 빈 행으로 채워 24행을 만든다. 숨김 영역처럼 위쪽 행을 적을 때는 `"board_top": [...]`(첫 문자열이 0행)을 쓰고, 둘을 함께 쓸 수 있다. 둘을 합쳐 24행을 넘으면(겹치면) 픽스처 오류다 |
| 기대 보드 | `expect` 안에서도 **같은 이름** `board_bottom` / `board_top`을 쓴다. 위와 같은 규칙으로 24행을 채운 뒤 **보드 전체**를 비교한다(적지 않은 행은 비어 있어야 한다). `expect`에 두 필드가 모두 없으면 보드는 비교하지 않는다 |
| 기대 필드 | 모든 형식에서, `expect`에 **적힌 필드만** 비교한다 |
| 블록 상태 | `{"piece": "T", "rotation": 2, "x": 3, "y": 20}` (박스 왼쪽 위 기준). 블록 도중의 상태처럼 마지막 조작을 이어받아야 하면 `"last_rotation": true, "last_kick": 0`을 더한다(RULES 6.1, 14.1). 없으면 `false`, `-1`이고, `last_kick`은 `last_rotation`이 true일 때만 0 이상이다 |
| 조작 | `"L" "R" "CW" "CCW" "D" "HD" "H"` |
| 64비트 정수 | 10진 **문자열**로 쓴다(JSON 숫자는 2⁵³ 이상에서 정밀도가 깨진다) |

## 5. 사례 형식

### 5.1 `rng`

```json
{ "id": "...", "kind": "splitmix_raw",  "state": "0",  "expect_next": ["...", "..."] }
{ "id": "...", "kind": "stream_first",  "seed": 0, "stream": 1, "expect_next": ["..."] }
{ "id": "...", "kind": "bag_sequence",  "seed": 0, "expect": "ISOTJZL ZSTJOLI ..." }
{ "id": "...", "kind": "garbage_holes", "seed": 0, "stream": 2, "expect": [0, 7, 0] }
{ "id": "...", "kind": "scheduler_mix", "seed": 0, "expect": [3, 2, 3] }
```

- `stream_first`: 시드 `seed`에서 파생한 스트림(RULES 5.2)의 처음 몇 개 `next()` 출력.
- `bag_sequence`: 처음 몇 가방을 이어 붙인 블록 순서. 공백은 가방 경계 표시이며 비교할 때는 무시한다.
- `garbage_holes`: 해당 스트림으로 **송신량이 0이 아닌 공격을 연속으로 받았을 때** 각 덩어리의 구멍 열(RULES 5.4, 첫 공격은 `prev_hole = null`).
- `scheduler_mix`: `S-mix`가 차례로 보내는 줄 수(RULES 13).
- 기대값은 [tools/rng_reference.ps1](../tools/rng_reference.ps1)로 계산했고, Java `core` 구현으로 교차 확인했다(`source: ["reference-ps", "java"]`). Python 구현이 생기면 `python`을 더한다.

### 5.2 `rotation`

```json
{
  "id": "...",
  "board_bottom": ["..."],
  "start": { "piece": "I", "rotation": 1, "x": -2, "y": 10 },
  "ops": ["CW"],
  "expect": { "rotation": 2, "x": 0, "y": 10, "last_kick": 2 }
}
```

- `last_kick`은 마지막 조작의 킥 번호(RULES 6.2, 0부터)다.
- 조작이 실패해야 하는 사례는 `"expect": { "unchanged": true }`로 적는다. 이때 `ops`의 **모든** 조작이 실패해야 한다.
- 시작 상태가 보드에 놓일 수 없는 자리이면 사례 자체가 잘못된 것으로 보고 실패 처리한다(`tspin`, `placement`도 같다).

### 5.3 `tspin`

```json
{
  "id": "...",
  "board_bottom": ["..."],
  "start": "spawn",
  "piece": "T",
  "ops": ["CW", "D", "...", "CW", "HD"],
  "expect": {
    "locked_cells": [[21,3],[21,4],[21,5],[22,4]],
    "spin": "full",
    "lines": 2,
    "board_bottom": ["..."]
  }
}
```

- `expect.board_bottom`은 줄 삭제까지 끝난 뒤의 보드다(RULES 7장 4단계까지).

- `ops`는 블록이 고정될 때까지의 전체 조작이다. 마지막은 보통 `HD`이다.
- `start`는 `"spawn"` 또는 블록 상태 객체.

### 5.4 `attack`

보드 없이 9장의 절차만 검사한다.

```json
{
  "id": "...",
  "before": { "combo": 0, "b2b": true },
  "event":  { "lines": 2, "spin": "full", "piece": "T", "perfect_clear": false },
  "expect": { "attack": 6, "combo": 1, "b2b": true }
}
```

### 5.5 `garbage`

구멍 열은 난수와 분리해 검사할 수 있도록 **덩어리에 직접 적는다**(받기 사례만 난수를 쓴다).

```json
{
  "id": "...",
  "board_bottom": ["..."],
  "queue": [ { "lines": 3, "hole": 2 }, { "lines": 1, "hole": 7 } ],
  "action": { "type": "cancel", "attack": 2 },
  "expect": { "queue": [ { "lines": 1, "hole": 2 }, { "lines": 1, "hole": 7 } ], "sent": 0 }
}
```

| `action.type` | 필드 | 기대 필드 |
|---|---|---|
| `cancel` | `attack` | `queue`, `sent` |
| `rise` | 없음 (줄 없는 고정 1회) | `queue`, `board_bottom`/`board_top`, `topout`. 가비지 아웃 사례는 `topout`만 적는다(RULES 7장 8단계: 패배 뒤 상태는 정하지 않음) |
| `receive` | `seed`, `player`(1 또는 2), `attacks`(송신량 배열), `prev_hole`(**반드시 적는다**. 이전에 받은 공격이 없으면 `null`) | `queue` |

### 5.6 `placement`

```json
{
  "id": "...",
  "board_bottom": [],
  "piece": "O",
  "start": "spawn",
  "hold_allowed": false,
  "expect": {
    "count": 9,
    "keys": ["h0|none|22,0;22,1;23,0;23,1", "..."],
    "must_include": [],
    "must_exclude": []
  }
}
```

- `keys`가 있으면 **집합이 정확히 같아야** 한다. 너무 많아 다 적기 어려우면 `count`와 `must_include` / `must_exclude`만 쓴다.
- 경로(조작 열)는 비교하지 않는다. 필요하면 `"path_length": {"키": 길이}`로 최단 조작 수만 확인한다. 길이는 RULES 14.2대로 `L, R, CW, CCW, D`만 센다(`H`, 마지막 `HD` 제외). 아직 러너가 지원하지 않는다.

### 5.7 `topout`

```json
{ "id": "...", "board_top": ["..."], "event": { "type": "spawn", "piece": "T" }, "expect": { "topout": "block_out" } }
{ "id": "...", "board_top": ["..."], "event": { "type": "lock", "state": { "piece": "T", "rotation": 0, "x": 3, "y": 2 } }, "expect": { "topout": "lock_out" } }
```

- `spawn`: 해당 블록을 스폰 위치에 놓을 수 있는지(블록 아웃).
- `lock`: 주어진 상태로 곧바로 고정했을 때 락 아웃인지(RULES 7장 **1~3단계만**). 놓을 수 있는 자리인지와 9단계(다음 스폰, 블록 아웃)는 검사하지 않는다. 헷갈리지 않도록 `none`을 기대하는 사례는 블록이 스폰 칸(3~6열, 2~3행)을 덮지 않게 만든다.
- 가비지 아웃은 `garbage`의 `rise` 사례로 검사한다.
- `topout` 값: `"none" "block_out" "lock_out" "garbage_out"`.

### 5.8 `game` (통합)

```json
{
  "id": "...",
  "seed": 0,
  "player": 1,
  "mode": "timeless",
  "scheduler": "none",
  "board_bottom": ["#########.", "..."],
  "steps": ["h0|none|20,9;21,9;22,9;23,9", { "receive": 3 }, "h0|none|22,1;22,2;23,0;23,1"],
  "expect": {
    "board_bottom": ["..."],
    "current": "O",
    "queue_preview": ["T", "J", "Z", "L", "Z"],
    "hold": null,
    "combo": -1,
    "b2b": true,
    "pending": [],
    "pieces": 2,
    "lines_total": 4,
    "attack_total": 14,
    "sent_total": 14,
    "topout": "none"
  }
}
```

| 필드 | 뜻 |
|---|---|
| `seed` | 게임 시드 (RULES 5.2) |
| `player` | 1 또는 2 (기본 1). 가비지 구멍 난수 스트림을 고른다 |
| `mode` | 지금은 `"timeless"`(시간 없는 모드, RULES 12.1)만 지원 |
| `scheduler` | `"none"`, `"S-3"`, `"S-mix"` (RULES 13장, 기본 `"none"`) |
| `board_bottom` / `board_top` | 처음 보드 (4장 규칙). 없으면 빈 보드 |
| `steps` | 차례로 적용할 단계. 문자열은 배치의 **정규화 키**(RULES 14.2), `{"receive": n}`은 상대 공격 n줄 받기(RULES 10.2) |
| `expect` | 모든 단계 뒤의 상태. `current`는 지금 놓을 블록, `queue_preview`는 미리보기 5개, `pending`은 대기 가비지 덩어리, `pieces`는 패배 없이 고정한 블록 수, `attack_total`은 상쇄 전 공격의 합, `sent_total`은 상쇄 뒤 송신량의 합(1인용에서도 센다) |

- 배치를 칸이 아니라 정규화 키로 적는다. 같은 칸이라도 spin이 다르면 공격·B2B가 달라지기 때문이다. 그 단계에서 둘 수 없는 키이면 테스트 실패다.
- 마지막 단계 전에 게임이 끝나면 테스트 실패다. 패배로 끝나는 사례는 패배를 만드는 단계를 마지막에 둔다.
- 블록 순서를 먼저 알아야 손으로 만들 수 있다. 시드 0은 `I S O T J Z L | Z S T J O L I | O L J Z T I S`(`rng/basic.json`)다.
- 실행하는 쪽: Java `GameModel`. Python 학습 환경도 같은 사례를 통과해야 한다.

### 5.9 `model_io`

[MODEL_IO.md](MODEL_IO.md) 형식 2의 인코딩과 모델 실행을 검사한다. 파일 머리에 모델 파일 이름 `"model"`(이 JSON과 같은 폴더 기준)이 있다.

```json
{
  "rules_version": "0.3",
  "type": "model_io",
  "source": ["python"],
  "model": "linear_v2.onnx",
  "cases": [
    { "id": "...", "kind": "encode", "afterstate": { ... },
      "expect": { "board_filled": 12, "features": [0, 0, 1, "... 48개"] } },
    { "id": "...", "kind": "encode_opponent", "opponent": { ... },
      "expect": { "board_filled": 25, "features": [6, 4, 1, "... 10개"] } },
    { "id": "...", "kind": "run", "afterstates": [ { ... }, { ... } ], "opponent": { ... },
      "expect": { "values": [-0.05, -0.19], "tolerance": 1e-5 } },
    { "id": "...", "kind": "metadata", "expect": { "tetris.model_io": "2", "tetris.rules": "0.3" } }
  ]
}
```

- `afterstate` 객체: `board_bottom` / `board_top`(4장 규칙), `queue`(블록 문자 0~5개), `hold`(문자 또는 `null`), `combo`(기본 −1), `b2b`(기본 false), `lines`, `attack`, `pending`(기본 0). 뜻은 MODEL_IO.md 2.1.
- `opponent` 객체: `board_bottom` / `board_top`, `pending`(기본 0), `combo`(기본 −1), `b2b`(기본 false), `hold`(문자 또는 `null`). 뜻은 MODEL_IO.md 2.2.
- `encode`: `features`는 48개 값이 **정확히** 같아야 한다. board 텐서는 보드의 채워짐 여부를 행 우선(0행이 맨 위)으로 편 것과 같아야 하고, `board_filled`는 1의 개수다.
- `encode_opponent`: `features`는 `opp_features` 10개 값이 정확히 같아야 하고, `opp_board` 텐서와 `board_filled`는 `encode`와 같은 방법으로 비교한다.
- `run`: 기대값은 **Python onnxruntime이 계산한 값**이다. 실행하는 쪽의 결과가 `tolerance` 안에 들어야 한다. `opponent`가 없으면 상대가 없는 것(상대 입력이 모두 0)이다.
- `metadata`: 모델 꼬리표(MODEL_IO.md 5장)가 적힌 값과 같아야 한다.
- 파일은 두 개다. `linear_v2.json`(시험용 선형 모델, 입력 형식 확인)은 [python/tools/make_test_model.py](../python/tools/make_test_model.py), `value_tiny.json`(학습 루프와 같은 구조의 작은 가치망, Java ONNX Runtime이 실제 모델의 연산을 Python과 같은 값으로 실행하는지 확인)은 [python/tools/make_value_fixture.py](../python/tools/make_value_fixture.py)로 만든다. 손으로 고치지 않는다. `value_tiny.onnx`는 PyTorch 버전이 다르면 바이트가 달라질 수 있다.

### 5.10 `match`

2인 대전 기록 `match-1`([TRAINING.md](TRAINING.md) 7장)을 Java와 Python이 같은 값으로 읽는지 확인한다.

```json
{
  "id": "baseline-cap",
  "description": "...",
  "match": { "format": "match-1", "seed": 7, "steps": [ ... ], "...": "..." },
  "expect": {
    "inputs": [
      { "p1": { "board": ["..."], "features": [ ... 48개 ], "opp_board": ["..."], "opp_features": [ ... 10개 ] },
        "p2": { ... } }
    ]
  }
}
```

- `match`: 기록 한 판 그대로다.
- `expect.inputs[t]`: 턴 `t`에 각 플레이어가 고른 배치의 모델 입력(MODEL_IO.md 형식 2). 보드 두 개는 4장 보드 표기(24개 문자열, `#` 채움)로, 특징은 정수 배열로 적는다. 모든 배치가 락 아웃이던 턴은 `null`이다.
- **Java:** 기록의 시드와 배치 키만으로 대전을 다시 두어 기록의 모든 값과 판 결과가 엔진과 같은지 확인하고([MatchReplay](../src/test/java/kr/ac/jbnu/se/tetris/train/MatchReplay.java)), 기록 값만으로 블록 동기 모드의 순서(RULES 12.2)도 확인한다. 그다음 자기 인코더의 값이 `inputs`와 같은지 본다.
- **Python:** 기록을 읽어(`tetris_ai.records`) 만든 모델 입력이 `inputs`와 같은지 본다. 기대값은 Java 인코더가 만든 것이므로 두 구현의 교차 확인이다.
- 이 폴더의 파일은 `gradlew matchFixture`(Java 대전 러너, `MatchFixtureTool`)로 만든다. 손으로 고치지 않는다.

### 5.11 `winprob`

승률 예측망 형식 `winprob-1`([MODEL_IO.md](MODEL_IO.md) 9장)의 인코딩과 모델 실행을 검사한다. 파일 머리에 모델 파일 이름 `"model"`(이 JSON과 같은 폴더 기준)이 있다.

```json
{
  "rules_version": "0.3",
  "type": "winprob",
  "source": ["python"],
  "model": "winprob_linear.onnx",
  "cases": [
    { "id": "...", "kind": "encode",
      "state": { "board_bottom": ["..."], "current": "T", "queue": ["I", "O", "S", "L", "Z"], "hold": null, "combo": -1, "b2b": false, "pending": 0 },
      "expect": { "board_filled": 12, "features": [0, 0, 0, 0, 0, 1, 0, "... 52개"] } },
    { "id": "...", "kind": "encode_record",
      "state": { "rows": [0, "...", 1023], "current": "O", "queue": "JTLIO", "hold": "S", "combo": -1, "b2b": false, "pending": 1 },
      "expect": { "board_bottom": ["#.........", "...", ".#######.."], "board_filled": 28, "features": [ "... 52개" ] } },
    { "id": "...", "kind": "run", "pairs": [ { "p1": { "...": "..." }, "p2": { "...": "..." } } ],
      "expect": { "values": [0.5, 0.450166], "tolerance": 1e-5 } },
    { "id": "...", "kind": "metadata", "expect": { "tetris.model_io": "winprob-1", "tetris.rules": "0.3" } }
  ]
}
```

- 상태 객체(`encode`, `run`): `board_bottom` / `board_top`(4장 규칙), `current`(블록 문자, 필수), `queue`(블록 문자 0~5개의 **배열**, 문자열이면 픽스처 오류), `hold`(문자 또는 `null`), `combo`(기본 −1), `b2b`(기본 false), `pending`(기본 0). 뜻은 MODEL_IO.md 9.2.
- `encode`: `features`는 52개 값이 **정확히** 같아야 한다. 보드 텐서는 보드의 채워짐 여부를 행 우선(0행이 맨 위)으로 편 것과 같아야 하고, `board_filled`는 1의 개수다.
- `encode_record`: `state`는 기록 `match-1`의 `state` 객체 그대로다(TRAINING.md 7.3: `rows`는 정수 24개, `queue`는 문자열, 모든 필드가 있다). `expect`에는 `rows`를 푼 보드를 4장 표기(`board_bottom` / `board_top`)로 **반드시** 적고, 보드 텐서가 이 보드와 같아야 한다. 그래야 `rows`를 푸는 방법(비트 `c`가 `c`열, 0행이 맨 위)이 언어마다 같은지 드러난다. 특징은 `encode`와 같이 비교한다. Python 학습 자료가 기록에서 입력을 만드는 경로를 Java와 맞춰 본다. 사례의 상태는 Java 대전 러너가 쓴 `fixtures/match/basic.json`에서 가져왔다.
- `run`: `pairs`의 쌍마다 `p1_win` 하나. 기대값은 **Python onnxruntime이 계산한 값**이고, 실행하는 쪽의 결과가 `tolerance` 안에 들어야 한다.
- `metadata`: 모델 꼬리표가 적힌 값과 같아야 한다.
- 파일은 두 개다. `winprob_linear.json`(가중치를 식으로 정한 선형 모델 `σ(w · (P1 입력 − P2 입력))`, 인코딩 사례와 손으로 확인할 수 있는 실행 사례: 같은 두 상태는 0.5, P1의 대기 가비지 4줄만 다르면 `σ(−0.05 · 4) = 0.450166`)와 `winprob_tiny.json`(학습에 쓰는 구조를 아주 작게 만든 모델, 실행 사례). 둘 다 [python/tools/make_winprob_fixture.py](../python/tools/make_winprob_fixture.py)로 만들며 손으로 고치지 않는다. `winprob_tiny.onnx`는 PyTorch 버전이 다르면 바이트가 달라질 수 있다.
- **Java:** `FixtureTest`가 상태 객체를 `ai.PlayerState`로 읽어 `ai.WinRate`로 인코딩·실행한다. 기록을 다시 두며 `PlayerState.of(GameModel)`과 기록의 `state`가 같은 입력을 만드는지는 `WinRateTest`가 따로 확인한다(MODEL_IO.md 9.7).
- **Python:** [python/tests/test_winprob.py](../python/tests/test_winprob.py)가 `tetris_ai.winprob`의 인코더와 onnxruntime으로 같은 사례를 돌린다.

## 6. 테스트 러너 요구 사항

- `fixtures/` 아래 모든 `*.json`을 찾아 `type`별 검사기로 넘긴다. 모르는 `type`은 실패 처리한다(조용히 건너뛰지 않는다).
- 실패 메시지에는 파일 경로, `id`, 기대값, 실제값을 모두 출력한다.
- **Java:** [src/test/java/…/fixture/FixtureTest.java](../src/test/java/kr/ac/jbnu/se/tetris/fixture/FixtureTest.java). JUnit `@TestFactory`로 파일마다 묶음 하나, 사례마다 테스트 하나를 만들고, 파일 머리(3장: 규칙 버전, type, 배열인 source, 고유한 id)도 따로 검사한다. JSON은 Jackson으로 읽는다. `gradlew fixtures`(픽스처만) 또는 `gradlew test`/`gradlew build`로 실행한다. 다른 폴더의 픽스처는 `-PfixturesDir=<경로>`로 돌린다.
- **Python:** [python/tests/test_fixtures.py](../python/tests/test_fixtures.py) (표준 `unittest`, 사례마다 `subTest`). 지금은 `model_io`와 `match`만 실행하고, `winprob`은 [python/tests/test_winprob.py](../python/tests/test_winprob.py)가 실행한다. 규칙 사례는 Python 규칙 구현이 없어 실행하지 않는다. `python -m unittest discover -s python/tests`로 돌린다.
- 아직 러너가 지원하지 않는 필드: `placement`의 `hold_allowed: true`, `garbage` `receive`에서 `null`이 **아닌** `prev_hole`(이 두 가지는 실패로 처리된다), 보드 비교의 `"compare": "exact"`와 `placement`의 `path_length`(무시된다).

## 7. 현재 픽스처와 남은 사례

규칙 버전 0.3 기준 **16개 파일, 87개 사례**가 모두 통과한다(2026-10-02). 아래는 10-01 당시의 69개 사례 설명이다. 규칙 사례 49개는 core 엔진, `game` 사례 4개는 `GameModel`, `model_io` 사례 14개(형식 2)는 Java·Python 인코더와 두 쪽의 ONNX Runtime, `match` 사례 2개는 Java 재생과 Java·Python 인코더가 검사 대상이다.

2026-10-02에 승률 예측망 픽스처 `winprob` **2개 파일, 18개 사례**(인코딩 10, 실행 6, 꼬리표 2)를 더했고 모두 통과한다. Java·Python 인코더와 두 쪽의 ONNX Runtime이 검사 대상이다. 손 계산으로 확인한 것은 선형 모델의 실행 사례 3개(0.5, σ(−0.2), σ(−0.03))와 인코딩 사례의 원-핫 위치이고, 나머지 기대값은 Python으로 만들어 Java로 교차 확인했다.

규칙 사례 중 난수(`rng`)를 뺀 35개는(규칙 0.2 당시) 엔진 코드를 보지 않고 RULES.md만 읽은 독립 검증자 네 명이 기대값을 다시 계산해 **모두 일치**를 확인했다(2026-09-29). 그 뒤에 추가한 `O-rotation-always-fails`와 TSD 보드의 mini 키, `model_io` 사례는 이 독립 검증에 들어가지 않았다. 이때 나온 모호한 문장은 RULES 0.2에 반영했다. 0.3에서 공격표를 바꾸며 `attack` 사례의 기대값 7개를 손으로 다시 계산했고 사례 2개(`single-first`, `triple-breaks-b2b`)를 더했다.

| 주제 | 있음 | 아직 없음 |
|---|---|---|
| `rng` | SplitMix64 검증값, 시드 2개의 스트림·가방 3개·구멍 수열·스케줄러 | Python 구현으로 교차 확인 |
| `rotation` | I·T 벽 킥, I 바닥 킥(킥 4), 모든 킥 실패(시계·반시계), O 회전은 항상 실패 | S·Z·J·L 킥, 스폰 직후 회전 |
| `tspin` | TSD, 킥 4 승격 TSD, mini 싱글, 스폰에서 회전한 뒤 소프트드롭으로 내려와(마지막 조작이 이동) spin이 없는 사례 | 줄 없는 T-spin(보드 사례), mini 더블, TST, 자리에서 회전한 뒤 한 칸 더 내려가 spin이 사라지는 사례 |
| `attack` | 9장 절차의 모든 분기 | — |
| `garbage` | 부분 상쇄, 전부 상쇄 후 송신, 8줄 상한과 잘린 덩어리, 삽입 순서, 가비지 아웃, 숨김 영역으로 밀려 올라가도 패배가 아닌 경우, 받기 | `prev_hole` 이어받기 |
| `placement` | 빈 보드의 O·I·T, TSD 자리가 있는 보드(full·none·mini 키) | 홀드 포함 사례, 경로 길이 확인 |
| `topout` | 블록 아웃(T, I), 락 아웃, 락 아웃 아님 | — |
| `model_io` | 형식 2: 애프터스테이트 4종과 상대 상태 2종의 인코딩, 상대가 있는·없는 실행(Python onnxruntime 값과 비교), 꼬리표, 작은 가치망 실행 | 배치에서 애프터스테이트 만들기 사례 |
| `match` | 기준 AI 끼리 턴 상한 무승부, 거의 무작위로 두어 블록 아웃으로 지는 판 | 락 아웃으로 지는 판, 가비지 아웃으로 지는 판 |
| `winprob` | 상태 6종과 기록 `state` 4종의 인코딩, 선형 모델(손 계산 3개, 자리를 바꾼 쌍), 학습 구조의 작은 모델 실행, 꼬리표, 거부할 모델 3개(`WinRateTest`) | 학습한 모델의 실행 사례(게임 쪽 회귀 시험용) |
| `game` | 홀드(빈 경우·찬 경우)와 미리보기 진행, 테트리스 + 퍼펙트 클리어 뒤 받은 가비지 상승, 락 아웃, 고정 뒤 블록 아웃 | 스케줄러 `S-3`·`S-mix`(지금은 Java 단위 테스트만), 콤보가 이어지는 판, T-spin이 섞인 판 |
