# v3-experiments 핸드오버 (2026-10-16 기준)

이 문서 하나로 다음 담당자(사람 또는 agent)가 (1) 지금까지 만든 것과 수집된 데이터를 이해하고, (2) 남은 세션을 돌리고, (3) 결과가 나오면 분석할 수 있게 하는 것이 목적이다. 코드 식별자·경로는 영어 그대로 둔다. 환경 준비와 빌드는 저장소 루트의 [README](../README.md)를 먼저 볼 것. `<repo>`는 이 저장소의 루트를 뜻한다.

---

## 1. 목표: 두 가지 질문

| | 질문 | 정답(GT) 필요? |
|---|---|---|
| **질문 1** | software 합성(digital regain + burst sum)이 실제로 그 설정으로 찍은 사진(physical counterfactual)을 충실하게 재현하는가 | 아니오 (합성 vs 실제의 *일치*가 목표) |
| **질문 2** | software 합성을 쓰는 proposed가 baseline들을 이기는가 | 예 (아직 정하지 않음, §9.4) |

역할 정의 (사용자 결정, 2026-10-15):

- **proposed = v3cause** (`proposed_v3cause`, θ = 0.10 하나만). 사용자가 붙여 준 spec "proposed v3 cause 판" Appendix A를 그대로 옮긴 것.
- **baseline = Sense-well(prop_c), AE-Phone, PhysSweep, ShinNM.** Sense-well은 원래 "우리 방법"이었지만 사용자가 baseline으로 분류했다(화면 표기 `baseline: Sense-well`도 사용자의 예시를 따른 것).
- 모든 비교 방법은 같은 바퀴 수를 받는다(사용자: "각 베이스라인 똑같아야", "proposed도 4바퀴").

질문 1은 두 실험으로 본다.
- **정지(static)**: 장면이 움직이지 않으므로 합성 사진과 실제 사진을 한 장 한 장 엄밀히 비교할 수 있다.
- **움직임(motion, Limo)**: 같은 순간을 두 번 찍을 수 없으므로 *평균*을 비교한다. 촬영 순서를 매 프레임 섞고, 실제 대 실제(같은 설정 반복) 차이를 기준선으로 쓴다.

---

## 2. 장비와 환경

- 폰: Galaxy S26 Ultra (`SM-S948N`). RAW 4080×3060. 센서 ISO 범위 9~1200, 노출 83 µs~176 ms, 프레임 간격 최소 16.75 ms.
- 플랫폼: Limo(토이카 로봇). 폰을 **가로로** 거치함(세로로 달아도 동작함, §4).
- 트랙: 타원. 한 바퀴 = 직선 → U턴 → 반대쪽 직선 → U턴 = 한 번 왕복, 약 12.5~13.4초, 자이로 기준 360°. 양쪽 벽을 따라 토이카들이 서 있음.
- 조명: 사용자가 직접 정함("조명은 내가 알아서 할게"). 조명 단계 이름이 로그에 기록되지 않으므로, 분석 때 §9.1의 밝기 지표로 구분하고 사용자에게 확인할 것.

---

## 3. 코드, 빌드, 설치

- 저장소: `<repo>`, 브랜치 **`v3-experiments`** (`limo-collection` 79c2ed6에서 분기). **GitHub에 push하지 않았다**(폴더나 git bundle로 전달됨). 서명 키(debug keystore)는 폰에 깔린 앱과 같아야 한다(README 참고).
- 앱 패키지: **`com.sos.integrate`**, activity `com.example.activeperception.MeasurementActivity`.
  - 같은 폰에 이름이 비슷한 **예전 앱 `com.sos.rayneox3.final`이 있다. 절대 그걸 띄우지 말 것.** 한 번 착각해서 잘못된 결론을 낸 적이 있다.
- 빌드: `JAVA_HOME=<JDK 21 경로> ./gradlew assembleDebug`. Windows PowerShell에서는 `$env:JAVA_HOME='<JDK 21 경로>'; .\gradlew.bat assembleDebug`.
  Java 25(최신 Android Studio의 기본 JBR)로는 Gradle 8.13이 깨진다. 반드시 JDK 21을 쓴다.
- 설치: `adb install -r app/build/outputs/apk/debug/app-debug.apk` 다음 `adb shell am start -n com.sos.integrate/com.example.activeperception.MeasurementActivity`.
  - **설치하면 앱이 재시작된다.** 설치 전에 폰에서 아무것도 돌고 있지 않은지 확인할 것: 가장 최근 run 폴더에 `summary.json`이 있는지, auto_collect 화면의 큰 글씨가 `ready/done/stopped`인지(`uiautomator dump`로 `id/expNow` 읽기).
  - Git Bash에서 adb 경로가 망가지지 않게 `export MSYS_NO_PATHCONV=1`.
  - 이미 떠 있는 task가 있으면 `am start ... --es run X`의 extra가 무시될 수 있다(기존 task가 앞으로 나올 뿐). `-S`(강제 종료 후 시작)는 사용자 허락을 받고 쓸 것.
- 커밋 주의: `app/.cxx/...` 빌드 산출물이 git에 추적되고 있어 항상 수정됨으로 보인다. **소스 파일만 골라서 add.**
- 원래 PC에서 겪은 것:
  - 저장소가 OneDrive 안에 있으면 OneDrive가 `app/build/test-results`를 잠가 단위 테스트가 실패한다. 그 폴더 이름을 바꿔 치우면 된다.
  - Windows의 Python은 아주 긴 경로를 못 연다. 작업 파일은 짧은 경로에 둔다.

### 이 브랜치의 커밋 (오래된 것부터)

| 커밋 | 내용 |
|---|---|
| 00e472f | 공통 이미지 경로(binning decode, area resample), 초점 2.0 dpt 고정, StepClock 타임스탬프, ae_phone을 tensor 경로로 |
| cabe000 | proposed_v3cause(+pilot), read noise 측정, `V3Cause.kt` / `SparseFlow.kt` / `RawBoxSampler.kt` |
| 05d64b2 | Collect 5×5 (질문 1 수집), 예전/새 파이프라인 A/B |
| 7f9f2d6, 50d1535, 81614eb, 4417460 | Lab 실험 선택(이후 대체됨), collect 메모리 수정, decode 검증 |
| 09ae97e | **auto_collect 화면**(큰 글씨로 현재 방법 표시, 버튼 3개) |
| ae233f1 | Limo: 바퀴마다 방법 변경, v3cause θ0.10만, static/read noise 소리 |
| ff20327 | **폰이 옆으로 놓이면 중력으로 사진을 90/270° 회전** |
| bbf15c4, 96698d3, fe8c0cb | Limo 바퀴 재분배 → 최종: collect 20, 방법별 4 (96698d3은 중간안, fe8c0cb가 대체) |
| 53fba7b | static 30프레임 |

### 주요 파일

| 파일 | 역할 |
|---|---|
| `MeasurementActivity.kt` | UI, auto_collect(`EXP_PRESETS`, `LIMO_ROUND`, `methodTitle`, `toggleLapSession`), adb 실행(`launchRunnerCommand`, `v3Command`), 회전(`applyMountOffset`, `mountOnSide`) |
| `MeasurementController.kt` | 각 방법의 실행 루프: `runContinuousProposed`(prop_c), `runPhysSweep`, `runShinNM`, `runV3Cause`, `runCollect55`, `runReadNoise`, `ReadNoiseTable`, 로깅 |
| `LapSession.kt` | IMU(자이로) 기반 바퀴 판정과 블록 전환 |
| `acquire/V3Cause.kt` | v3cause 판단 로직(순수 Kotlin, 오프라인 재현 가능) |
| `acquire/SparseFlow.kt`, `acquire/RawBoxSampler.kt` | v3cause의 흔들림 측정(LK flow), 박스 안 RAW 통계 |
| `ParallelRawCandidateSource.kt`, `cpp/native_tensor_preprocessor.cpp` | RAW → 640 텐서 형성(모든 방법 공통) |
| `RawSensorCapturer.kt` | Camera2 RAW 촬영, 고정 초점, decode |
| `StepClock.kt` | 프레임별 타임스탬프 |
| `SensorDataManager.kt` | 가속도/자이로, 중력 고정(`gravityXyz()`) |

---

## 4. 모든 방법이 공유하는 이미지 경로

`manifest.json`의 `image_pipeline`, `image_formation`에 그대로 기록된다.

1. RAW decode: 4×4 센서 블록마다 같은 색 4픽셀의 **평균**(모든 픽셀 사용) → 2×2 Bayer quad. black 빼고 부호 유지, 여기서는 clip 안 함.
2. Burst sum(해당하는 경우) → demosaic → **area 평균**으로 축소(선형값) → digital gain → **clip 1번** → sRGB LUT → 회전.
3. White balance, 색 변환 없음(의도된 선택, 사용자 "패스"). 그래서 사진이 초록빛이다.
4. 초점: 모든 요청에서 수동 2.0 dpt(약 50 cm).
5. 회전: 실행 시작 직전 중력으로 결정. 폰이 세로면 0/180(기존 "180" 버튼), 옆으로 누우면 왼쪽 모서리가 위일 때 90°, 오른쪽이 위일 때 270°. 적용 값은 `manifest.sensor_orientation`(센서 기본 90 + 장착 보정): 90 = 세로, 270 = 세로 뒤집힘, 180 = 왼쪽 위, 0 = 오른쪽 위.
6. 검출기: YOLOv8n 640 fp16 (GPU). conf floor 0.01, NMS IoU 0.45. 비교 방법들은 차량 클래스 `{1,2,3,5,7}`(bicycle, car, motorcycle, bus, truck)만 남긴다. **collect는 80개 클래스를 모두 남기므로 분석 때 반드시 차량 클래스로 거를 것**(안 거르면 새·가위 같은 오검출이 섞임).
7. **박스 좌표**: `xyxy`는 회전된 1020×765(가로) 또는 765×1020(세로) quad 좌표다. 저장된 JPEG는 640×640 letterbox:
   - `s = 640 / 1020`, 가로일 때 `x_jpg = x·s`, `y_jpg = y·s + 80`; 세로일 때 `x_jpg = x·s + 80`, `y_jpg = y·s`.
   - 정지 실험 사진에 박스를 그려 정확히 겹치는 것을 확인함.
8. 타임스탬프: `SENSOR_TIMESTAMP`, `elapsedRealtimeNanos`, IMU, lap session의 `elapsed_ns`가 **모두 같은 시계**다(`manifest.timestamp_source = 1`).

---

## 5. 방법 목록

| plan 이름 | run 폴더 태그 | 화면 표기 | 동작 요약 | 저장 사진 이름 |
|---|---|---|---|---|
| `v3cause_t010` | `v3cause_t010` | proposed: v3cause θ0.10 | 매 프레임 base 촬영(노출 T0 고정, analog gain은 박스 포화 기준으로 조정). 약한 박스(0.05~0.25)가 있으면 원인(포화/흔들림/어둠/밝기)을 RAW로 판단해 다음 프레임에 보정(digital ×2^Δ, 짧은 노출, 긴 노출)을 함께 찍고 결과를 융합 | `frame_NNNN_base.jpg` |
| `prop_c` | `proposed_v2_p5` | baseline: Sense-well | 5×5 격자(ISO 100~1600 × 1/240~1/15). 5프레임마다 base ISO burst로 합성 probe(같은 ISO 1/2/4장 합), 그 외엔 한 장 + digital ÷2/×1/×2. 2단계 hysteresis | `frame_NNNN_cellCC.jpg` |
| `ae_phone` | `ae_phone` | baseline: AE-Phone | 폰 자체 AE. 사진 형성·검출은 공통 tensor 경로 | `frame_NNNN_isoI_expEus.jpg` |
| `physsweep` | `physsweep_v2_full_h25` | baseline: PhysSweep | 25칸을 한 프레임씩 실제로 돌아보고 가장 좋은 칸을 25프레임 유지 | 같은 형식 |
| `shin_nm` | `shin_nm_v2` | baseline: ShinNM | Nelder-Mead로 이미지 지표 최적화(검출 안 씀) | 같은 형식 |
| `collect55` | `collect_5x5` | collect: 5×5 | 질문 1 수집(§6) | `frame_NNNN_real_gG_eE.jpg`, `..._sNxE_bB_gG.jpg`(합성: E µs N장 합, base B, 목표 G), `..._repeat_gG_eE.jpg` |
| `collect55_ab` | `collect_5x5_ab` | collect: 5×5 A/B | 정지 실험. 프레임마다 새/예전 파이프라인을 번갈아 씀 | 같음 |
| `readnoise` | `readnoise` | read noise | 렌즈를 가리고 ISO 6단계 × 20장 | 없음 |

격자: ISO 100/200/400/800/1600(1600 = analog 1194 × digital 1.34, `gain_captured_at` 참고), 셔터 4167/8333/16667/33333/66667 µs. cell id = `gain_idx * 5 + shutter_idx`.

v3cause 주요 상수(manifest `method_params`): θ 0.10, two-shot 0.20, flicker window 10, 포화 장소 0.5, blur ratio 0.03, κ 5, base 포화 0.1, hysteresis 10, Δ 후보 1~5, digital 최대 ×16, T0 = min(1/60 s, AE 노출 × AE ISO ÷ 최저 ISO), 시작 gain = AE ISO × AE 노출 ÷ T0(9~1200으로 clamp), 긴 노출 상한 = min(176 ms, T0 × 0.03 ÷ T0에서의 blur ratio).

---

## 6. 실험 절차 (auto_collect)

주행 화면 오른쪽 위 **auto_collect** 버튼 → 검은 화면에 큰 글씨(지금 실행 중인 방법)와 작은 진행 줄, 아래 버튼 3개.

| 버튼 | 언제 | 내용 | 시간 |
|---|---|---|---|
| **read noise** | 폰마다 한 번 | 렌즈를 완전히 가리고(엎어 놓기 권장) ISO 9/100/200/400/800/1200 × 20장. 중간에 멈춘 측정은 `complete:false`로 표시되어 앱 기준값을 덮어쓰지 않음 | 약 12초 |
| **static** | 조명마다 | `collect55_ab` 30프레임. 폰을 고정하고 차가 보이게 | 약 1분 |
| **Limo** | 조명마다 | 10바퀴 묶음 × 4 = 40바퀴 | 약 9분 |

- 소리: Limo는 시작(5초 카운트다운 후 준비 바퀴 진입) 때 "삑" 1번, 바퀴 전환마다 짧은 진동, 끝나면 "삑" 3번과 긴 진동. static/read noise는 시작 1번, 끝 3번. **손으로 멈추면 소리 없음.** 볼륨 키는 이 화면에서 무시된다.
- Limo 묶음 순서(`LIMO_ROUND`): collect → Sense-well → collect → v3cause → collect → AE-Phone → collect → PhysSweep → collect → ShinNM. collect는 연속으로 오지 않고, collect 바퀴마다 base ISO가 100→200→400→800→1600 순으로 돈다(세션당 base ISO별 4바퀴).
- 바퀴 판정(`LapSession`):
  - 시작 후 한 바퀴(준비 바퀴, 기록 안 함)로 바퀴 시간과 "가장 빨리 도는 지점"을 찾아 그곳을 기준선으로 삼는다(보통 먼 쪽 U턴 한가운데).
  - 이후 자이로 360°마다 기준선 통과로 보고 방법을 바꾼다. 각 방법은 정확히 한 바퀴(가는 길 + 오는 길)를 탄다. 서 있는 쪽에서 보면 "갈 때 A, 올 때 B"처럼 보이지만 정상이다.
  - 전환 때 기준선 뒤 0.4~1.7초(약 20~40°)는 이전 실행을 끝내고 새 실행을 여는 시간이라 기록이 없다. 모든 방법이 같은 U턴 구간을 똑같이 놓친다.
  - **모든 방법은 바퀴마다 새로 시작한다.** v3cause는 매번 AE로 시작값을 다시 재고 Δ 시험(각 5회)과 base gain hysteresis(10프레임)를 다시 한다.
  - 바퀴가 안 잡히면 시간 모드(바퀴 시간 추정값)로 넘어간다(`fallback_time` 이벤트).
- adb로 개별 실행: `am start ... --es run <plan 이름>` (+ `--ei collect_frames N`, `--ez save_images false`, `--es focus_dpt X`, `--es lap_plan "..."`). 이 extra들은 한 번만 적용된다.

---

## 7. 데이터 형식

폰 경로: `/sdcard/Documents/sos/run_<platform>_<tag>_<epoch_ms>/`, lap session 로그는 `/sdcard/Documents/sos/lapsession_<epoch_ms>.csv`. read noise 기준값은 `/sdcard/Android/data/com.sos.integrate/files/read_noise.json`.

공통 파일:

| 파일 | 내용 |
|---|---|
| `manifest.json` | 방법, 파라미터, 이미지 경로, 회전, 검출기 설정, 센서 정보 |
| `summary.json` | 프레임 수, 검출 합계(진행 확인용. 분석에 쓰지 말 것) |
| `frames.csv` | 프레임별 한 줄. `iso_applied`, `exp_applied`, `lux`, `yaw_rate`, `heading_angle`, `n_det`, `img_path`, StepClock `t_request_ns, t_first_exposure_ns, t_last_exposure_end_ns, t_captured_ns, t_formed_ns, t_detected_ns, t_decided_ns, t_logged_ns`, v3cause 전용 `base_again, weak_max, k, plan, applied, delta_d, delta_l, n_images, n_captures, t_capture_start, t_capture2_start, t_capture_end, t_output, flow_ms, fusion_ms, plan_ms` |
| `dets.jsonl` | 프레임별 출력 박스. 일반: `{"frame","method","cell","dets":[{"xyxy","conf","cls"}]}`. **v3cause는 `{"dets":[{"xyxy","evidence","peak","class","n_pic","r","w"}],"dropped":[...],"roles":[...]}` — 필드 이름이 `evidence`/`class`** |
| `imu.csv` | `ts, source(accel/gyro/light), ax, ay, az, gx, gy, gz, lux` |
| `img/` | 출력 사진 JPEG(640×640 letterbox) |

방법별 추가 파일:

- prop_c: `candidates.csv`(frame, cell, gain, exposure_us, sum_conf, chosen, tie_break), `cont.csv`(rung, phys_iso, residual, shutter_idx, probe, pending), `router.csv`.
- v3cause:
  - `decisions.jsonl`: 프레임별 base(gain, 이유, hysteresis), applied, Δ 갱신과 Δ 표, plan_next(s, k, skip_reason, cause_counts, chosen), read_noise_dn.
  - `places.jsonl`: 장소별 박스, 원인, signal_over_rn, blur_ratio, 결과.
  - `candidate_dets.jsonl`: 촬영 역할별(base/보정) 후보 박스, gain, 노출, 포화.
  - `decode_check.json`: native vs Kotlin decode 불일치 수(지금까지 항상 0).
- collect:
  - `collect_frames.csv`: 프레임별 base, 파이프라인, 촬영 순서, 시간, yaw, lux.
  - `collect_shots.csv`: 촬영 한 장마다 역할(burst/real/repeat), 칸, 요청·적용 ISO와 노출, `sensor_ts_ns`, `raw_mean`, `raw_clip_frac`, 초점.
  - `collect_cells.csv`: 이미지 한 장마다 kind(synth/real/repeat), 칸, base, burst 셔터, `n_frames`(합한 장수), `gapped`(틈 있는 합), `digital_ratio`, `mean_luma`, `clip_frac`, `n_det`, `sum_conf_vehicle`, `img_path`.
  - `collect_dets.jsonl`: 이미지별 박스(80개 클래스, `pipeline`, `kind`, `cell`, `base_gi`, `burst_sj`, `n_frames`).
- readnoise: `readnoise.csv`, `read_noise.json`(ISO별 per-pixel rn, offset, fpn, `complete`).

lap session CSV: `wall_ms, elapsed_ns, event(countdown/start/leadin_done/block_start/lap/block_stop_request/block_ended/block_ended_early/lap_during_switch/switch_timeout/fallback_time/done/abort), block_idx, method, lap_no, laps_in_block, lap_s, total_yaw_deg, yaw_rate_rad_s, mode, note`. 각 방법 실행은 `block_start`에서 시작해 다음 `lap`(기준선 통과)에서 끝난다.

---

## 8. 지금까지 모인 데이터 (2026-10-15 저녁)

| 데이터 | 상태 |
|---|---|
| `lapsession_1792059146151` + run 60개(epoch 1792059169832~1792059922337), 19:12~19:25 | **무효.** 폰을 옆으로 달았는데 회전 수정 전이라 사진이 누움 → 모든 방법 검출 0. 사용자가 지우기로 함(아래 명령. agent는 영구 삭제 금지) |
| static `collect_5x5_ab_1792061117004`, 19:45, 58프레임(손으로 멈춤) | 유효. 회전 수정 후, 사진 바로 섬. 폰 조도센서 중앙값 약 27.6 |
| static `..._1792061592937` 19:53 / `..._1792061664103` 19:54 / `..._1792061751218` 19:55, 각 30프레임 | 아직 안 봄. 조도센서 중앙값 27.4 / 2.6 / 1.6 → 밝음/중간/어두움으로 보이나 **사용자에게 확인할 것** |
| Limo dark: `lapsession_1792061839643` + run 40개(1792061869416~1792062371279), 19:57~20:06 | 유효. 40/40바퀴, 회전 180, 폰 조도센서 5~6. 단, 미리보기 변환이 느리던 빌드로 찍혔다(§9.5). 남은 조명과 조건을 맞추려면 **수정된 빌드로 다시 찍기를 권장** |
| read noise | `1792058310965`(18:58, 엎어서 측정)가 **앱 기준값**. `1792057653242`는 손으로 가려 빛이 샘. `1792057646317`, `1792062446069`는 덜 가린 채 멈춘 것(무시) |
| 밝은 조명 Limo | **다시 해야 함**(첫 세션이 무효) |
| 18:18~18:23 `run_car_*` | 책상 점검용. 실험 데이터 아님 |

무효 세션 삭제 명령(사용자가 직접 실행):
```bash
adb shell 'cd /sdcard/Documents/sos && rm -r run_limo_*_17920591[6-9]* run_limo_*_1792059[2-9]* lapsession_1792059146151.csv'
```
이 glob은 정확히 그 60개 + 1개만 맞는다는 것을 개수로 확인했다(이후 run은 epoch가 더 크므로 맞지 않음).

---

## 9. 분석 방법

### 9.1 공통 전처리

1. **가져오기**: 로그만 빠르게 볼 때는 `img/`, `raw/`를 빼고 pull(세션당 약 12 MB). GT 작업이나 사진 비교에는 `img/` 포함(Limo 세션당 약 300~400 MB).
2. **차량 클래스 필터**: `cls`(v3cause는 `class`) ∈ {1,2,3,5,7}. conf는 v3cause만 `evidence`.
3. **조명 구분 지표**: 폰 조도센서(`lux`)는 화면이 어디를 보느냐에 따라 달라서 거치 방식이 다르면 비교할 수 없다. 대신 `collect_shots.csv`에서 `raw_mean / (iso_applied × exp_applied_us)`의 중앙값(고정된 칸, 예: real g400 e16667)을 장면 밝기 지표로 쓰고, 그 값으로 조명 단계를 묶은 뒤 사용자에게 확인한다.
4. **바퀴 위상(트랙 위치)**: lap session의 `lap` 이벤트 `elapsed_ns`(기준선 통과)를 경계로, 어떤 촬영 시각 t의 위상 = (t − t_line_k) / (t_line_{k+1} − t_line_k). 바퀴 시간이 ±3%로 일정해서 시간 위상만으로도 위치가 꽤 맞는다. 직선/U턴 구분은 `imu.csv`의 자이로를 중력축으로 투영한 yaw rate로 한다(가로 거치면 `gx`, 세로면 `gy`가 주성분. U턴 약 1.1 rad/s). 예전 분석에서 U턴 구간에는 차가 멀고 작게만 보였으므로 직선과 U턴을 **나눠서** 보고할 것.
5. **회전 확인**: 모든 run의 `manifest.sensor_orientation`이 세션 안에서 같은지, 사진 몇 장이 바로 섰는지 먼저 본다.

### 9.2 질문 1 — 정지(static)

데이터: 조명별 `collect_5x5_ab` 30프레임. 프레임마다 base ISO가 바뀌고(f mod 5), 파이프라인이 번갈아 바뀐다(짝수 current, 홀수 legacy).

한 프레임 안에 있는 것:
- **real**: 25칸 각각을 실제로 찍은 사진.
- **synth**: base ISO에서 찍은 burst(4×1/240, 4×1/120, 4×1/60, 2×1/30)로 만든 사진. 1장·2장·4장 합(`n_frames`)과 digital 배율(`digital_ratio`)로 목표 칸을 만든다. 1장짜리는 모든 ISO, 합은 base와 인접 ISO.
- **기준선(yardstick, 실제 대 실제)**: burst 첫 장은 그 자체로 (base, burst 셔터) 칸의 실제 사진이다. 같은 프레임의 real (base, 그 셔터)와 비교하면 실제 대 실제 차이가 나온다. 1/15는 `repeat`로 따로 있다.

비교(current 파이프라인 먼저, 칸별):
1. **사진 단위**: 장면이 고정이므로 synth와 real JPEG를 픽셀 단위로 비교(평균 밝기, clip 비율, 차이의 RMS나 SSIM). 같은 지표를 기준선 쌍에도 계산해서, **합성-실제 차이가 실제-실제 차이 범위 안에 드는지** 본다. 합성이 실제와 같다는 것은 이 범위 안에 든다는 뜻이다.
2. **검출 단위**: 같은 프레임의 synth와 real 박스를 IoU 0.5로 짝지어 일치율(F1)과 conf 차이를 구하고, 역시 기준선 쌍과 비교한다. 칸별로 프레임을 bootstrap해서 95% CI.
3. **결정 단위**: synth로 매긴 칸 순위와 real로 매긴 칸 순위가 같은 칸을 1등으로 고르는지(prop_c/v3cause의 결정이 바뀌는지).
4. **A/B**: 같은 지표를 legacy에서도 구해, 예전 파이프라인(1/4 픽셀만 읽는 decoder + bilinear)이 결과를 얼마나 바꿨는지 기록. 10/13 세션 결과를 해석할 때 필요하다.
5. 나눠 볼 축: 조명, digital 배율(×1~×16), 합한 장수, gapped 여부, ISO 1600(analog 1194 + digital 1.34라 순수 실제가 아님).

이미 본 것(밝음, 1792061117004, current): 칸별 차량 conf 합의 평균이 real과 1장 synth 모두 대략 2.1~2.9이고, 서로 0.3 안쪽이었다. ISO 1600 × 1/15는 real과 synth 모두 포화로 검출이 거의 0이었다. 정식 분석은 아직.

### 9.3 질문 1 — 움직임(motion)

데이터: Limo 세션의 collect 바퀴 20개(base ISO별 4바퀴). 한 프레임(약 1.9초, 40장)을 찍는 동안 Limo가 움직이므로 synth와 real은 다른 위치에서 찍힌다. 그래서 **같은 위치끼리 평균**을 비교한다.

1. `collect_shots.sensor_ts_ns`로 사진마다 촬영 시각을 구한다(synth는 재료 burst 장들의 평균 시각) → §9.1의 바퀴 위상.
2. 위상을 구간(예: 25개, 약 0.5초)으로 나누고, 구간 × 칸마다 synth와 real의 검출 지표(차량 수, 최대 conf, 또는 GT가 생기면 recall)를 바퀴들에 걸쳐 평균한다. 촬영 순서가 매 프레임 섞이므로 이 평균 비교는 치우치지 않는다.
3. 같은 방식의 실제 대 실제(burst 첫 장 vs real, repeat vs real) 차이를 기준선으로 쓴다.
4. 범위: 1/60·1/30 장들의 합은 틈 없이 이어져(프레임 간격 16.75 ms, 노출 16.67 ms) 움직임에서도 실제 긴 노출과 같다. 1/240·1/120의 합(`gapped=1`)은 구조적으로 다르므로 **따로** 보고한다.
5. 직선과 U턴을 나눈다.

이미 본 것(dark, 위상 무시, base 섞임): 1/120~1/60에서는 synth와 real이 비슷했다. 짧은 셔터 + 높은 ISO에서는 synth가 낮았다(ISO 1600 1/240: real 1.29, synth 0.87 대/이미지). 어두울 때 낮은 base ISO에서 digital로 밝히면 잡음 때문에 손해를 본다는 예전 결과와 같은 방향이다. base ISO별로 나눠 다시 볼 것.

### 9.4 질문 2 — proposed가 baseline을 이기는가

**GT와 평가 단위는 아직 정하지 않았다**(사용자: "evaluation은 나중에"). 사용자와 정할 것:

- **GT 후보**
  - (A) 각 방법이 저장한 사진(`img/`)의 일부에 사람이 박스를 그린다. 예: 바퀴당 5장 간격 → 조명당 약 250장.
  - (B) 차가 고정이므로 위상 구간마다 "보여야 하는 차"를 collect 바퀴들의 좋은 칸에서 합의로 정한다(pseudo-GT). 위치가 바퀴마다 조금 달라 IoU 짝짓기는 약하므로, 개수 기반 recall이 현실적이다.
  - (C) 더 큰 검출기로 오프라인 재검출. 이 PC에는 tflite/onnx/torch 런타임이 없다. 설치(다운로드)는 사용자 허락 후.
  - 권장: B로 전체를 덮고, A로 표본을 확인한다.
- **평가 단위**: 사진 한 장당(precision/recall/AP)과 시간당(초당 맞힌 차 수) 둘 다 보고한다. 방법마다 프레임 속도가 달라서(바퀴당 45~66장) 둘의 결론이 다를 수 있다.
- **통계**: 분석 단위는 바퀴. 한 묶음(10바퀴) 안에 모든 방법이 한 번씩 있으므로 **묶음 안에서 짝지어** 차이를 구하면 시간에 따른 변화(조명, 배터리, 속도)가 상쇄된다. 조명당 묶음 4개 × 조명 수. 묶음/바퀴 단위 bootstrap CI. 직선/U턴을 나눈다. 각 바퀴의 첫 몇 프레임(재시작 직후)을 뺀 결과도 함께 낸다.
- **하지 말 것**: 방법이 스스로 낸 conf 합(`sum_conf`, `summary.json`)으로 순위를 매기지 말 것. prop_c는 conf로 출력을 고르므로 conf가 부풀려진다(여러 잡음 점수 중 최대값).

이미 본 것(dark, GT 없는 대략 지표 = conf ≥ 0.25 차량 박스 수/프레임): ShinNM 1.07 (ISO 1194, 1/240) > Sense-well 0.84 (1194, 1/120) > v3cause 0.74 (1194, 1/60) > PhysSweep 0.44 (398, 1/240) ≈ AE-Phone 0.42 (538, 1/30). collect 최고 칸은 1.29~1.34. 어두워도 "최대 ISO + 짧은 셔터"가 유리했다. 주행 흔들림이 어둠보다 큰 문제로 보인다. 오검출이 섞인 지표이므로 결론으로 쓰지 말 것.

### 9.5 지연(latency)

모든 방법의 `frames.csv`에 StepClock 열이 있다(같은 시계).
- 결과 주기 = 연속 프레임의 `t_first_exposure_ns` 차이.
- 끝단 지연 = `t_detected_ns − t_first_exposure_ns`(또는 노출 끝 기준).
- 결정 지연 = `t_decided_ns − t_detected_ns`.
- v3cause는 보정 촬영 시각과 flow/fusion/plan ms가 따로 있다.

보고: 방법별 중앙값과 95번째 백분위, 첫 몇 프레임(시작) 제외. 모든 세션이 사진을 저장하며 돌았으므로, 저장 부담을 재려면 사진 저장을 끈 짧은 실행(`--ez save_images false`, 방법당 한 바퀴)을 한 번 따로 한다(아직 안 함, 선택).

책상 점검 값(2026-10-15, 차 없음, 사진 저장): 주기 v3cause 약 100 ms, prop_c 131 ms, ae_phone 166 ms, physsweep 193 ms, shin_nm 233 ms. 노출 시작→검출 62/77/67/116/65 ms.

**미리보기 변환 병목 (2026-10-16에 고침)**
- 원인: 모든 방법이 검출 직후, 다음 촬영을 요청하기 전에 측정 스레드에서 검출기 입력(640×640×3 float)을 Bitmap으로 바꿨다(`ParallelRawCandidateSource.selectedTensorBitmap`). 픽셀마다 `ByteBuffer.getFloat`를 세 번 부르는 Kotlin 루프였다.
- 크기: dark 세션에서 프레임당 약 43~46 ms. 주기(133~168 ms)의 약 30%였다. StepClock에서 방법에 따라 `detected→decided` 또는 `decided→logged` 구간에 잡힌다.
- 영향: 노출→검출 지연에는 들어가지 않는다(검출 뒤에 일어남). 다음 촬영을 늦춰서 프레임 속도와 다음 프레임에 반영되는 결정을 늦춘다.
- JPEG 쓰기는 원래 별도 스레드였고, collect는 원래 데이터를 복사해 별도 스레드에서 변환했으므로 영향이 없었다.
- 수정: 네이티브 `NativeTensorPreprocessor.tensorLaneToArgb`(약 1~2 ms로 예상)로 바꿨다. 프로세스당 첫 변환에서 네이티브와 Kotlin 결과를 픽셀 단위로 비교하고, 하나라도 다르면 Kotlin으로 되돌린다.
- 확인 방법: `adb logcat -s ParallelRawSource`에 `tensorLaneToArgb native vs Kotlin: 0 mismatching pixels`가 찍히는지 본다. 이어서 짧은 실행에서 위 구간이 수 ms로 줄었는지 frames.csv로 본다.
- **수정 전후 빌드로 찍은 세션의 주기·초당 지표는 서로 비교하지 말 것.**

**폰 확인 결과 (2026-10-16 오전, 책상, 수정 빌드 4acfda0)**
- 픽셀 일치: `0 mismatching pixels`.
- 검출 뒤 구간(검출→다음 요청): v3cause 22 → 9 ms, AE-Phone 31 → 4 ms, Sense-well 26 → 8 ms.
- 그런데 주기는 거의 그대로였다: v3cause 100.5 → 100.5 ms, AE-Phone 166.7 → 166.8 ms. Sense-well은 probe가 아닌 프레임이 대부분 8칸(134 ms) → 7칸(117 ms)으로 줄었다. 다만 화면 잠김으로 약 8프레임만 측정했다.
- 이유: 루프가 센서 프레임 칸(16.75 ms)에 맞춰 돈다. 주기는 항상 칸의 정수배다(100.5 = 6칸, 151 = 9칸, 167 = 10칸).
  - 한 번에 촬영 요청 하나만 보내므로, 요청 → 노출 시작(20~60 ms) → 노출과 RAW 도착(45~80 ms) → 처리 순서로 돈다.
  - 처리에서 아낀 시간이 칸 경계를 넘을 때만 주기가 준다.
  - AE-Phone은 폰 AE 촬영 호출 자체가 약 10칸을 차지한다.
- 그래서 "미리보기 때문에 느리다"는 진단은 절반만 맞다. 변환은 실제 낭비였지만(이제 제거됨), 주기의 더 큰 부분(v3cause에서 약 70%)은 카메라를 기다리는 시간이다.
- 이를 더 줄이려면 처리 중에 다음 촬영을 미리 요청(pipelining)해야 한다. 하지만 그러면 결정이 한 프레임 오래된 사진을 쓰게 되어 방법의 동작이 바뀌므로, 실험 도중에는 하지 않았다.
- 주행 중에는 변환이 책상의 두 배(약 45 ms)였으므로 이득이 더 클 것이다(AE-Phone 외에는 1~3칸 예상). **dark Limo 재촬영으로 수정 전 세션과 방법별 주기를 비교해 확인할 것.**
- 확인용 실행(실험 데이터 아님, 분석에서 제외): `run_limo_v3cause_t010_1792117338860`(6프레임, 화면 잠김으로 중단), `run_limo_v3cause_t010_1792117550923`(200프레임), `run_limo_ae_phone_1792117581748`, `run_limo_proposed_v2_p5_1792117670046`(12프레임, 화면 잠김으로 중단).

### 9.6 v3cause 진단과 spec 작성자에게 보낼 메모

- `decisions.jsonl`로 k 분포, skip 이유, 원인별 횟수, 실제 보정 횟수, base gain 변화를 집계한다.
- `places.jsonl`의 outcome으로 보정이 박스를 늘렸는지 본다.
- dark에서 본 것:
  - T0는 매 바퀴 1/60 s였다. AE가 1/30이라 규칙상 min(1/60, …) = 1/60이 된다.
  - 시작 gain 약 500에서 11~29프레임 뒤 1194에 도달했다.
  - 79% 프레임에서 k=0(θ 미만)이었고, 보정은 4바퀴 동안 15번(long 9, short_blur 6)뿐이었다.
  - gain 약 500 구간과 1194 구간의 검출 차이는 작았다(0.70 대 0.75). 그러니 고정 1/60이 ShinNM(1/240)에 지는 주원인으로 보인다.
- spec 작성자에게 보낼 메모(미작성):
  - spec §10 질문들에 대한 답.
  - spec에 없는 clamp 이유 `dgain_max`, `blur_limit`.
  - 센서 최저 ISO가 9라는 점.
  - 출력 필드 이름이 `class`라는 점.
  - 바퀴마다 다시 시작하는 조건.
  - 어두울 때 T0 = 1/60 고정으로 생기는 흔들림.
  - pilot(spec §7.3 표)은 이번 세션들에서 뺐다는 점(이름으로 실행 가능: `v3cause_pilot`).

---

## 10. 알려진 문제와 주의

- **회전**: 회전 수정(ff20327) 전 데이터는 가로 거치 시 무효. 세션 시작 직전 폰을 다른 방향으로 바꾸면 그 실행부터 새 방향이 적용된다(실행마다 중력으로 결정).
- 사진 구석에 거치대/케이블(검은 막대)과 Limo 부품이 찍힌다. 그 영역은 모든 방법에 공통.
- read noise: 엎어 놓고 쟀어도 빛이 조금 들어왔을 수 있다. ISO 1194에서 실제 rn은 3.1~4.5 DN 범위(기준값 4.49).
- 폰 조도센서는 거치 방향에 따라 달라서 조명 비교에 쓰면 안 된다(§9.1).
- `96698d3`은 중간안(v3cause 8바퀴)이다. 이 빌드로 돈 세션은 없다(이후 바로 fe8c0cb 설치).
- 모든 세션은 사진 저장을 켠 채로 돌았다.
- 예전(10/13) collect 5×3 세션은 다른 브랜치(`limo-collection`)·예전 파이프라인이다. 직접 비교하지 말 것.

---

## 11. 남은 일

| # | 할 일 | 누가 |
|---|---|---|
| 0 | 미리보기 변환 수정 빌드를 설치하고 폰에서 확인(logcat 0 mismatch, 주기 감소). 그 뒤 dark Limo 다시 찍기 | agent(설치·확인) + 사용자(촬영) |
| 1 | 무효 세션 삭제(§8 명령) | 사용자 |
| 2 | 밝은 조명 Limo 재실행, 그 밖에 원하는 조명들의 static + Limo | 사용자(agent는 첫 바퀴 뒤 사진·검출 확인) |
| 3 | static 3개(19:53~19:55)의 조명 단계 확인 | agent + 사용자 |
| 4 | 모든 유효 데이터를 사진 포함해 PC로 가져오기(위치는 사용자와 정함) | agent |
| 5 | GT와 평가 단위 결정(§9.4) | 사용자 결정 |
| 6 | 질문 1 정지·움직임 분석, 질문 2 분석, latency | agent |
| 7 | spec 작성자 메모(§9.6) | agent |
| 8 | (선택) 사진 저장 끈 latency 실행 | 사용자 실행 |

---

## 12. 사용자와 일하는 방식

- 한국어로, **원리부터 쉽게** 설명한다. 새로 지어낸 용어를 쓰지 않는다. 공학적 처리는 공학적 처리라고 밝힌다.
- 질문에 하나씩 반응하지 말고, **하나의 계획**을 갖고 권고안을 낸다. 실험 구성은 "두 질문"으로 설명하고 긴 목록으로 늘어놓지 않는다.
- 폰에서 **긴 실행을 agent가 시작하지 않는다.** 짧은 확인은 괜찮다. 폰과 Limo 조작은 사용자가 한다.
- 데이터를 **영구 삭제하지 않는다.** 삭제가 필요하면 명령을 주고 사용자가 실행한다.
- 커밋은 이 브랜치에서 허락된 범위로만, **push 금지**.
- 기존 앱 기능을 크게 바꾸지 않는다(필요한 것만 덧붙인다).
- 화면은 멀리서 보이는 큰 글씨 한 줄로, 설명 문구는 넣지 않는다.
- 원본 데이터는 폰(`/sdcard/Documents/sos/`)에 있다. 원래 PC의 임시 사본과 이전 agent의 작업 메모는 공유 대상이 아니며, 그 내용은 이 문서에 모두 옮겨 두었다.
