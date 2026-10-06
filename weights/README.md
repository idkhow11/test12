# 학습 가중치 (weights/)

게임을 **하는 데는 필요 없다.** 게임은 `models/`(ONNX 모델 묶음)만 쓴다. 이 폴더는 **학습을 이어 하거나 다시 평가할 때** 쓰는 PyTorch 체크포인트 중 핵심만 골라 둔 것이다. 학습 결과 폴더 `runs/`는 git에 올리지 않으므로(용량), 팀원이 처음부터 다시 학습하지 않아도 되게 여기에 둔다.

폴더 모양은 `runs/<실행 이름>/`과 같다. 그래서 `runs/`로 복사하면 기존 도구가 그대로 읽는다.

| 폴더 | 무엇 | 쓰는 곳 |
|---|---|---|
| `r01/checkpoints/gen-038.pt` | 단계 A 첫 학습 `r01`의 세대 38 (가치망) | `a2`의 시작점. 게임의 3단계(상급) 두뇌는 이 세대의 ONNX |
| `a2/checkpoints/gen-055.pt` | 단계 A 이어 학습 `a2`의 세대 55 | 페르소나 학습(`p04`)의 시작점. 게임의 4·5단계 두뇌는 같은 실행의 세대 52 ONNX |
| `p04/checkpoints/gen-010.pt`, `gen-030.pt` | 페르소나 본 학습 `p04`(AI 12개, 임베딩 8차원)의 세대 10·30 | 게임의 페르소나 4개는 세대 10의 원형 AI (TRAINING.md 9.9) |
| `wp02/winprob_wp02.pt` | 승률 예측망 `wp02` (실시간 판 84,000판으로 학습, 10-04) | 게임의 예측 승률 막대 (`models/winprob.onnx`, TRAINING.md 10.11) |
| `wp01/winprob_wp01.pt` | 승률 예측망 `wp01` (블록 동기 AI 판 40,000판으로 학습) | 10-04까지 게임에 썼던 옛 모델. 비교용 |

각 폴더의 `config.json`은 그 실행의 학습 설정이다(`--config`로 그대로 쓸 수 있다). `p04/personas.json`은 AI 12개의 설계 비율과 보상 가중치, `wp01/metrics.json`과 `wp02/metrics.json`은 평가 결과다.

## 쓰는 법

```bash
# runs/ 로 복사해 두면 문서의 명령을 그대로 쓸 수 있다 (PowerShell 은 Copy-Item -Recurse)
cp -r weights/a2 runs/a2
cp -r weights/p04 runs/p04

# 페르소나 학습을 a2 세대 55 에서 새로 시작 (TRAINING.md 9.7)
python python/tools/train_personas.py --run p05 --gens 31 --init runs/a2/checkpoints/gen-055.pt

# 체크포인트를 β 만 바꿔 내보내 다시 재기 (TRAINING.md 12.1)
python python/tools/evaluate.py --checkpoint runs/a2/checkpoints/gen-055.pt --prior-weight 0.01 --seeds 930001:930200

# 페르소나 분석 (세대 10, 고정 상대는 시작 모델)
python python/tools/analyze_personas.py --run p04 --gen 10 --reference runs/a2/models/value_ai00_gen-055.onnx --no-export
```

분석·평가 명령 중 모델 파일(`runs/<run>/models/*.onnx`)이 필요한 것은 그 실행을 다시 내보내야 한다. 게임용 모델 묶음은 `python python/tools/make_bundle.py --p04-personas`로 다시 만든다(이때는 `runs/`의 원래 학습 결과가 필요하다).

체크포인트에는 신경망 가중치, 최적화기 상태, 신경망 설정(`config`), 세대 번호가 들어 있다. 개인 정보나 PC 경로는 없다.
