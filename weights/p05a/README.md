# p05a 세대 30 (페르소나 다시 학습)

게임의 4·5단계(최상급·불가능) 페르소나 넷 `models/value_{attacker,defender,tspin,combo}.onnx`는 이 체크포인트의 AI 0~3(원형마다 첫 AI)을 내보낸 것이다. 게임을 하는 데는 필요 없다.

| 파일 | 크기 | 내용 |
|---|---|---|
| `checkpoints/gen-030.pt` | 2,106,230바이트 | 신경망(AI 10개, 임베딩 8차원), 최적화기 상태, AI 표, 실행 설정, 세대 30에 뺀 AI별 블록당 보상 평균(`style_centers`) |
| `config.json` | 1,005바이트 | 실행 설정(`python/configs/p05a.json`과 실행 관리 값) |
| `personas.json` | 3,727바이트 | AI 10개(원형 4 × 복제 2 + 중립 2)의 설계 비율과 보상 가중치 |

- 시작점은 `weights/a2/checkpoints/gen-055.pt`다. 세대 30은 결과를 보기 전에 정한 규칙(`python/configs/p05_selection.json`)으로 골랐고, 확인 결과는 TRAINING.md 9.10에 있다.
- 이 체크포인트의 AI 0~3을 `tetris_ai.personas.export_ai`로 다시 내보내면 묶음의 파일과 바이트까지 같다(10-11 확인).
- 이어 학습(`train_personas.py --resume`)은 이 폴더만으로는 할 수 없다. `log.csv`, 최근 4세대의 대전 기록, 최근 5세대의 모델 파일이 더 필요하다.
