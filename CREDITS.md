# 만든 것과 가져온 것 (CREDITS)

이 게임의 그림(블록, 카드, 효과)은 모두 코드(Java2D)로 직접 그린다. 효과음도 대부분 코드로 합성한다(`audio/SoundBank`).
밖에서 가져와 함께 배포하는 파일은 아래 글꼴 두 가지와 효과음 13개뿐이고, 모두 수정·재배포가 허용된 라이선스다.

## 글꼴 (`src/main/resources/fonts/`, 원본 그대로)
- **Pretendard** 1.3.9 — `Pretendard-Regular.otf`, `Pretendard-Bold.otf`
  Copyright (c) 2021, Kil Hyung-jin (https://github.com/orioncactus/pretendard), with Reserved Font Name Pretendard.
  SIL Open Font License 1.1 — `Pretendard-LICENSE.txt`.
  Pretendard 는 Inter (Copyright (c) 2016 The Inter Project Authors), Source Han Sans (Copyright 2014-2021 Adobe),
  M PLUS 1p (Copyright 2021 The M+ FONTS Project Authors)를 바탕으로 만들어졌다(모두 SIL OFL 1.1).
- **Barlow Condensed** 1.408 — `BarlowCondensed-Bold.ttf`, `BarlowCondensed-Black.ttf`
  Copyright 2017 The Barlow Project Authors (https://github.com/jpt/barlow).
  SIL Open Font License 1.1 — `BarlowCondensed-OFL.txt`. 받은 곳: https://github.com/google/fonts/tree/main/ofl/barlowcondensed

## 효과음 (`src/main/resources/sfx/`)
Kenney (www.kenney.nl)의 오디오 묶음에서 골랐다. 라이선스는 Creative Commons Zero (CC0 1.0, 퍼블릭 도메인,
https://creativecommons.org/publicdomain/zero/1.0/)이다. 표시 의무는 없지만 출처를 남긴다:
"Sound effects: Kenney (www.kenney.nl), CC0 1.0". OGG 원본을 16비트 모노 WAV 로 바꾸고, 앞뒤를 자르고, 크기를 맞췄다.

| 우리 파일 | 쓰는 곳 | 원본 | 묶음 |
|---|---|---|---|
| ui_move.wav | 메뉴 이동 | select_002.ogg | Interface Sounds 1.0 (https://kenney.nl/assets/interface-sounds) |
| ui_select.wav | 메뉴 고르기 | confirmation_001.ogg | Interface Sounds 1.0 |
| ui_back.wav | 메뉴 뒤로 | question_002.ogg | Interface Sounds 1.0 |
| ui_deny.wav | 안 됨 | error_004.ogg | Interface Sounds 1.0 |
| land.wav | 블록이 바닥에 닿음 | impactGeneric_light_003.ogg | Impact Sounds 1.0 (https://kenney.nl/assets/impact-sounds) |
| lock_1.wav, lock_2.wav | 블록 고정 | impactGeneric_light_000.ogg, impactGeneric_light_002.ogg | Impact Sounds 1.0 |
| hard_drop_1.wav, hard_drop_2.wav | 하드드롭 (합성음과 겹침) | impactWood_medium_000.ogg, impactWood_medium_002.ogg | Impact Sounds 1.0 |
| sand_land_1~3.wav | 모래 모드 블록 부서짐 (합성음과 겹침) | footstep_snow_003.ogg, footstep_snow_000.ogg, footstep_snow_002.ogg | Impact Sounds 1.0 |
| ko_crunch.wav | K.O. (합성음과 겹침) | explosionCrunch_000.ogg | Sci-fi Sounds 1.0 (https://kenney.nl/assets/sci-fi-sounds) |

그 밖의 효과음(줄 지우기, T-spin, 콤보, B2B, 퍼펙트 클리어, 방해 줄, 심장 소리, 카운트다운, 승리·패배 음악 등)은
이 저장소의 코드가 합성한 것이다. 테트리스 공식 음악(Korobeiniki)은 쓰지 않는다.

## 참고만 한 것 (파일·코드를 가져오지 않음)
- 화면 배치, 효과 시간, 글자 크기 같은 아이디어: TETR.IO, Tetris Effect: Connected, Puyo Puyo Tetris 1·2, Tetris 99,
  Jstris, Techmino, NullpoMino, Sandtrix, Tricky Towers. 스크린샷은 저장소에 넣지 않았다.
  Techmino(LGPL-3.0)와 NullpoMino(BSD-3-Clause)의 소스에서는 숫자(지속 시간 등)만 읽었고 코드는 옮기지 않았다.
- 지침: Material Design (motion, sound), WCAG 2.1, Xbox Accessibility Guidelines, Game Accessibility Guidelines,
  Okabe & Ito "Color Universal Design".

## 상표
"Tetris"는 The Tetris Company 의 상표다. 이 프로젝트는 수업 과제로 만든 비공식 학생 프로젝트이며
The Tetris Company 와 관계가 없다.
