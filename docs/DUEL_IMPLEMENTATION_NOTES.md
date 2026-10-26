# Duel (proposed v4) 구현 메모 — spec 10절에 대한 답

spec: `docs/SPEC_proposed_v4.md`. 이름은 **Duel** (모드 `duel_trace` = L0, `duel_every` = L1).
코드: `acquire/Duel.kt` (판단 규칙, 부록 A 이식), `MeasurementController.runDuel`, 확인 스크립트 `tools/duel_replay_check.py` (spec 5.6).

## 0. 10-26 base 고르기 교체 (top-down proposed, `Actdiff/revision/PROPOSED_TOPDOWN_2026-10-26.md`)

바뀐 것은 base 고르기 하나. burst 구조(같은 burst read 합), L0/L1, Thompson 방향, 합치기는 그대로.
모드 이름은 `duel2_trace` (L0), `duel2_every` (L1): 이전 Duel 기록(`duel_*`)과 섞이지 않게. 이전 판은 이 branch 에서 더 돌릴 수 없음 (`main` 에 남아 있음).

- 지움: 4.5 의 gain 규칙(τ .7), 짝 비교로 shutter 옮기기(pBetter ≥ .95), 선 탐색. Thompson 의 w/l 표는 방향 고르기에만 쓰고 base 가 옮겨도 지우지 않음.
- 새 규칙 `acquire/HowToSense.kt`: frame 마다 이번 base 사진(검출기 입력의 640×480, 8-bit)으로 다음 frame 의 base 를 정함. 16×12 칸(40 px)마다 25 칸의 밝기·포화(사진 × 노출 비율), 번짐(직전 base 와의 flow 속도 × shutter ÷ 칸의 차 크기)을 계산하고, 곡선 × 포화 인자 × 지도 무게의 합 E 가 가장 큰 칸으로, E 의 차가 곡선 표준오차보다 클 때만 옮김. 첫 frame 은 폰 AE 에 가장 가까운 칸, 직전 base 가 없는 첫 frame 다음 결정은 그대로 둠.
- 지도: 차 종류 conf ≥ .25 검출 box(둘째 사진 것은 base 시각으로 옮겨서)의 칸 면적 누적, 칸의 차 크기 = 쌓인 box 긴 변의 중앙값.
- 곡선 파일 `app/src/main/assets/tolerance_curve.txt` (형식은 `HowToSense.Curve` 주석). 없으면 Duel 은 시작하지 않음.
- 확인: `HowToSenseTest.studyFramesGiveTheSameChoice` 가 `app/src/test/resources/howtosense/` 의 연구 frame(curve.txt, case_k.png/txt, 형식은 테스트 주석)과 같은 값을 내는지 봄.
- 기록: decisions.jsonl `base_choice`(E 25 개, best, threshold, moved, speed 중앙값, 잰 칸 수, ms), `move`(ISO·shutter); duel_frames.csv `choose_ms`, `moved_to_iso`; dets.jsonl `two_trace`(두 흔적 규칙이 더했을 box, 출력에는 안 넣음).

## 1. spec과 달라진 것

| 무엇 | spec | 구현 | 이유 |
|---|---|---|---|
| 옆 칸 사진 | 한 요청 burst 2 장, 셔터가 다른 두 물리 노출 | **burst 합으로 합성**: 비교하는 frame은 두 셔터 중 짧은 쪽으로 2 frame 을 찍고, 긴 쪽 사진 = 두 frame 의 합(FIRST_N), 짧은 쪽 사진 = 첫 frame. 비교 없는 frame 은 기본 셔터 1 frame | 연구의 방법이 합성(burst 합 + digital regain)이므로 (사용자 결정, 10-20). 두 사진이 같은 순간에 시작하고 노출이 겹친다 |
| ISO | 4.5 규칙(차 box 70%)으로 gain 을 정함 | 같은 규칙 하나로 **사진 ISO** 를 정하고, analog 는 그 값을 따라감(상한 1200), 모자란 만큼만 digital (1600 = 1200 x 1.333) | 찍는 ISO 를 따로 정할 이유가 없음: RAW 를 그 ISO 그대로(또는 더 밝게) 쓰므로 RAW 에서 날아간 부분은 사진에서도 하얗고, 그건 70% 규칙이 센다 |
| gain 쪽 비교 | 없음 | 없음 (확인 후 유지) | 아래 3절 |
| 시작 | AE 첫 설정의 가장 가까운 칸 | 같음. AE 는 두 frame 연속 5% 안에 들 때까지(6~20 frame) | |
| 한 장 frame 의 요청 | — | preview template; 두 frame 은 still template (captureFast 와 같은 규칙) | 다른 방법들과 같은 조건 |

기록에는 사진마다 `made_by` (`frame` / `sum2_gapfree` / `sum2_gapped`), `n_frames`, 등가 노출이 남는다. 1/240·1/120 의 합은 frame 사이 빈틈이 있어(frame 16.75 ms) `gapped` — sos_cf_v1 의 질문 1 결과로는 어두운 곳·움직임에서 실제보다 손해.

## 2. 기기에서 잰 것 (10-20, 책상, 차 없음; 두 물리 노출 판으로 잼)

- frame 시간: 1 장 83 ms (capture ~65, 형성 ~4, 추론 ~12), 2 장 133 ms (capture 75~100, 추론 22~34, flow 7~10). 차가 없는 2 장 frame 은 flow 를 건너뛰도록 고침(-7 ms).
- 두 노출의 SENSOR_TIMESTAMP 차 = 16.75 ms - (노출2 - 노출1): 노출이 끝나는 시점이 한 frame 간격.
- noise profile: 나옴. 그러나 O(read noise 항)가 늘 0 이고, S 는 ISO 99 에서 1.94e-4, 795 에서 3.40e-4 로 ISO 8 배에 1.75 배만 커짐 (신호 대비 shot noise 라면 ISO 에 비례해야 함). 측정값이 아니라 HAL 모델로 보여 gain 규칙 다음 판에 쓰기 어려움 -> spec 6.1(B) photon transfer 로 재야 함.
- AE 시작: AE ISO 89~90, 1/120 -> 시작 칸 ISO 100, 1/120 (6 frame 만에 안정).
- 차가 없을 때 L0 는 계속 1 장 (`nothing_run` 20 까지) — 4.6 의 멈춤 상황 그대로.
- 아직 안 잰 것: gain 반영 지연(6.3), 1/15 에서의 2 frame 시간, 차가 있을 때의 판단 경로 전부, burst 합 판의 기기 동작.

## 3. "같은 RAW 의 digital 사본은 못 살림"(S116a, S119a) 확인 — sos_cf_v1

같은 순간·같은 셔터의 다섯 ISO 사진은 한 RAW 에 배율만 다르게 곱한 것이라 그대로 잴 수 있다. 진짜 차 = 같은 순간 따로 찍은 다른 노출(다른 burst, real_check)에서도 잡힘 (IoU 0.3; 셔터가 긴 쪽은 번지므로 보수적).

- 밝기만 바꿔도 검출은 크게 바뀐다. 1/240, ISO 800 사본 대 1600 사본: dark 1.16 -> 1.47 대/순간 (1600 에서만 72 대, 그중 진짜 45; 800 에서만 3), dim 2.16 -> 2.42 (60 / 21; 1 / 0), bright 2.89 -> 3.03.
- 그러나 70% 규칙이 고른 기본(dim·dark 는 1600)에 더 어두운 사본을 합쳐도 거의 그대로: dark 1.111 -> 1.116, dim 1.551 -> 1.551, bright 1/240 2.009 -> 2.018 진짜 차/순간. 예외는 1600 이 날아가는 경우(bright 1/60: 1.44 -> 1.68)로, 70% 규칙이 애초에 고르지 않는다.
- 결론: ISO 상한 1600 안에서는 gain 쪽 비교가 더할 것이 없다. spec 의 주장은 이 범위에서 맞고, 일반적으로("정보가 같으니 검출도 같다")는 틀리다. 1600 보다 밝은 digital 사본은 데이터셋에 없어 시험하지 않음.
- 스크립트: `%TEMP%\sos_fpn\gain_copy_check.py` (결과 json 같은 곳).

## 4. 비교 정책 쪽

- 본실험 8 개: duel_trace, duel_every, ae_phone, Sense-well(prop_c, P = 5 로 고정), PhysSweep, ShinNM, Neural-AE, fixed_1600_240. 조명마다 각 3 run x 3 바퀴, 회차마다 순서 한 칸씩 돌림 (auto_collect > Main > bright/dim/dark).
- ShinNM: 시작을 AE 설정(연속 stop)으로 — 논문대로. 예전엔 (최대 gain, 최단 셔터) 모서리가 남아 있었고 그게 우연히 최적 고정 칸이었다.
- Neural-AE: AE 시작, T_MAX 15 ms (sim 과 같음), sos_cf_v1 로 새로 학습(같은 순간 최적 칸 정답, 바퀴 단위 확인용 분리; 확인용 MSE 0.535, '안 바꿈' 2.086). T_MAX 15 ms 때문에 최적 고정 칸(1600·1/240)에는 설 수 없다.
- 모든 run 의 manifest 에 lighting, experiment_session; frames.csv 에 run_lap (0 = run 의 첫 바퀴).
