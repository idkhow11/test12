# 학습 가중치 (weights/)

게임을 **하는 데는 필요 없다.** 게임은 `models/`(ONNX 모델 묶음)만 쓴다. 이 폴더는 **학습을 이어 하거나 다시 평가할 때** 쓰는 PyTorch 체크포인트 중 핵심만 골라 둔 것이다(모두 약 12MB). 학습 결과 폴더 `runs/`는 git에 올리지 않으므로(이 PC에서 약 7.5GB), 팀원이 처음부터 다시 학습하지 않아도 되게 여기에 둔다.

폴더 모양은 `runs/<실행 이름>/`과 같다. 그래서 `runs/`로 복사하면 기존 도구가 그대로 읽는다.

| 폴더 | 무엇 | 쓰는 곳 |
|---|---|---|
| `r01/checkpoints/gen-038.pt` | 단계 A 첫 학습 `r01`의 세대 38 (가치망) | `a2`의 시작점. 게임의 3단계(상급) 두뇌는 이 세대의 ONNX |
| `a2/checkpoints/gen-055.pt` | 단계 A 이어 학습 `a2`의 세대 55 | 페르소나 학습(`p04`, `p05a`)의 시작점. 게임의 4·5단계 두뇌는 같은 실행의 세대 52 ONNX |
| `p04/checkpoints/gen-010.pt`, `gen-030.pt` | 페르소나 본 학습 `p04`(AI 12개, 임베딩 8차원)의 세대 10·30 | 10-04~10-11 게임의 페르소나 4개는 세대 10의 원형 AI (TRAINING.md 9.9) |
| `p05a/checkpoints/gen-030.pt` | 페르소나 다시 학습 `p05a`(AI 10개: 원형 4 × 복제 2 + 중립 2)의 세대 30. 폴더 전체 약 2.1MB(체크포인트, `config.json`, `personas.json`, `README.md`) | **지금 게임의 페르소나 4개**는 원형마다 첫 AI(10-11, TRAINING.md 9.10, `p05a/README.md`) |
| `wp02/winprob_wp02.pt` | 승률 예측망 `wp02` (실시간 판 84,000판으로 학습, 10-04) | 게임의 예측 승률 막대 (`models/winprob.onnx`, TRAINING.md 10.11) |
| `wp01/winprob_wp01.pt` | 승률 예측망 `wp01` (블록 동기 AI 판 40,000판으로 학습) | 10-04까지 게임에 썼던 옛 모델. 비교용 |

각 폴더의 `config.json`은 그 실행의 학습 설정이다(`--config`로 그대로 쓸 수 있다). `p04/personas.json`은 AI 12개의, `p05a/personas.json`은 AI 10개의 설계 비율과 보상 가중치다. `wp01/metrics.json`과 `wp02/metrics.json`은 평가 결과다.

- **체크포인트가 없는 게임 모델:** 1·2단계 두뇌(`r01` 세대 0·10)와 4·5단계 두뇌(`a2` 세대 52)는 학습 중에 체크포인트가 정리되어, `models/`의 ONNX 파일이 유일한 원본이다.
- 승률 막대의 보정층(`models/winprob_calibration.json`)은 계수 5개짜리 JSON이라 체크포인트가 따로 없다(TRAINING.md 10.12).

## 새로 받은 PC에서 되는 것과 안 되는 것

`runs/`가 없는 PC(저장소를 새로 받은 상태)를 기준으로 한다.

| 된다 | 이 PC의 `runs/`가 있어야 한다 |
|---|---|
| 게임 전체(`models/`만 쓴다. 난이도·페르소나 13가지가 모두 학습 모델로 켜진다) | `p04`·`p05a` 이어 학습(`--resume`): `log.csv`, 최근 4세대의 대전 기록, 최근 5세대의 모델 파일이 더 필요하다 |
| `weights/a2` 세대 55나 `weights/r01` 세대 38에서 페르소나 학습 **새 실행** 시작(`train_personas.py --init`) | `train_loop.py --init-from`(원래 실행의 최근 세대 모델 파일을 이전 세대 상대로 복사한다), `analyze_personas.py --run p04`·`--run p05a` 처럼 그 실행의 세대별 모델 파일(`runs/<run>/models/*.onnx`)을 읽는 분석 |
| `evaluate.py --checkpoint`로 체크포인트를 다시 내보내 재기 | 게임용 묶음 다시 만들기(`make_bundle.py --p05-personas`, 원래 학습 결과를 복사한다) |
| `models/`의 ONNX를 고정 상대로 쓰는 평가·분석 | 승률 막대 보정층 다시 맞추기(`fit_winprob_calibration.py`, `runs/wpt01` 자료와 `runs/wpcal02`) |
| `weights/p05a` 체크포인트에서 게임용 페르소나 ONNX를 다시 내보내기(묶음 파일과 바이트까지 같다) | `persona_probe.py`(결정 묶음 `runs/p05-design/probe/ana10`, 123MB) |
| Python 시험(`runs/`가 없어 3개가 '건너뜀'으로 나오는데 정상이다) | |

학습 도구는 `JAVA_HOME`(없으면 PATH)의 Java로 대전 러너를 띄우므로 그 Java가 21 이상이어야 한다. Python 환경은 README.md 11장.

## 쓰는 법

```bash
# runs/ 로 복사해 두면 문서의 명령을 그대로 쓸 수 있다
# (PowerShell: New-Item -ItemType Directory -Force runs; Copy-Item -Recurse weights\a2 runs\a2)
mkdir -p runs
cp -r weights/a2 runs/a2
cp -r weights/p04 runs/p04
cp -r weights/p05a runs/p05a

# 페르소나 학습을 a2 세대 55 에서 p05a 와 같은 설정으로 새로 시작 (TRAINING.md 9.10)
python python/tools/train_personas.py --run p05x --gens 41 --config python/configs/p05a.json --init runs/a2/checkpoints/gen-055.pt

# 체크포인트를 β 만 바꿔 내보내 다시 재기 (TRAINING.md 12.1)
python python/tools/evaluate.py --checkpoint runs/a2/checkpoints/gen-055.pt --prior-weight 0.01 --seeds 930001:930200
```

- `train_personas.py`를 `--config` 없이 부르면 `p04`의 기본값(9.3)으로 돈다. `p05` 설계(보상 중심 빼기, 복제, 새 보상 항목)는 `python/configs/p05a.json`을 줘야 쓴다.
- 체크포인트의 텐서는 GPU(`cuda:0`)에서 저장했다. GPU가 없는 PC에서 직접 열 때는 `torch.load(path, map_location="cpu")`로 연다(저장소의 도구는 알아서 맞춘다).
- 분석·평가 명령 중 모델 파일(`runs/<run>/models/*.onnx`)이 필요한 것은 그 실행을 다시 내보내야 한다. 게임용 모델 묶음은 `python python/tools/make_bundle.py --p05-personas`로 다시 만든다(이때는 `runs/`의 원래 학습 결과가 필요하다). 10-04~10-11의 묶음은 `--p04-personas`가 바이트까지 다시 만든다.

체크포인트에는 신경망 가중치, 최적화기 상태, 신경망 설정(`config`), 세대 번호가 들어 있다(`p05a`는 AI 표와 그 세대에 뺀 보상 평균 `style_centers`도). 개인 정보나 PC 경로는 없다.
