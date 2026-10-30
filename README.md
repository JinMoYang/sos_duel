# RayNeo SoS (`share`)

검출 결과를 보고 카메라 노출(셔터·ISO)을 고르는 Android 앱. Galaxy S26 Ultra를 Limo에 달고, 트랙의 장난감 차 검출로 방법들을 비교한다. 이전 실험 전부는 `topdown-base` 브랜치에 있다.

## TL;DR

- **구조 (프레임 예산 파이프라인)**: 10 fps 마감(100 ms)마다 출력 하나. 촬영 thread는 센서를 쉬지 않고 읽고, 분석 thread는 마감마다 최신 RAW를 쓴다. 출력 하나의 예산 = RAW 6장, 검출 4장.
- **proposed (`addl_3_sep_every`)**: key 설정을 반응 곡선 + gyro로 예측 → 같은 설정 RAW 최대 3장을 더 찍어 장마다 검출 → 합집합. (1/30이면 추가 1장, 1/15면 0장)
- **baseline**: AE-Phone, Sense-well, PhysSweep(fast), ShinNM, Neural-AE, fixed 1600·1/240(oracle). 모두 같은 병렬 구조(`par_`), 결정 규칙은 원래 그대로.
- **결과 (10-29, 프레임당 확인된 차)**: 어두울수록 proposed가 앞서고, 밝으면 비긴다.

| | 밝음 94 lux | dim 9.8 | 어두움 4.6 |
|---|---|---|---|
| **proposed** | 2.78 | **2.28** | **0.89** |
| Sense-well | 2.83 | 2.14 | 0.81 |
| fixed (oracle) | **2.86** | 2.16 | 0.67 |
| PhysSweep (fast) | 2.37 | 1.85 | 0.55 |
| Neural-AE | 1.63 | 1.55 | 0.66 |
| ShinNM | 1.79 | 0.62 | 0.37 |
| AE-Phone | 1.72 | 0.89 | 0.36 |
| 표준오차 | ±.14 | ±.14 | ±.08 |

대가: 지연 ~120 ms (fixed ~70), 밝을 때 아무도 못 본 박스 0.13–0.15 / 프레임.

알려진 문제: PhysSweep(fast)가 밝은 세션 한 run에서 18.7초에 멈춤(링 버퍼 밀어내기, 미수정). Neural-AE·ShinNM은 출력 간격이 ~134 ms(마감 100 ms 초과)로 원래부터 느림.

## 빌드·설치

JDK 21, Android SDK 36, NDK `27.0.12077973`, CMake `3.22.1`.

```bash
JAVA_HOME=<JDK 21> ./gradlew assembleDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb push nae_hist_scalar_3x3.bin /sdcard/Android/data/com.sos.integrate/files/   # Neural-AE 가중치 (아래)
```

## 실험

앱을 열면 **experiments** 화면. Limo 속도를 고르고 버튼을 누른다(폰 잠금 해제, 화면 켜 둠).

| 탭 | 버튼 | 내용 | 시간 |
|---|---|---|---|
| Main | bright / dim / dark | 최종 비교: 7방법 × 3라운드 | ~12분 |
| Check | verify dim / dark | 추가 3장: 흔적 뒤 vs 매 프레임, 합침 vs 따로 | ~6분 |
| Check | read noise | 렌즈 가리고 폰마다 1번 | ~12초 |
| Record | bright / dim / dark | 반응 곡선용 기록 (`collect_cf2`) | ~4.5분 |

조명: dark 4–6 lux, dim ~10, bright ~95. adb: `adb shell am start -S -n com.sos.integrate/com.example.activeperception.MeasurementActivity --es run <이름>` (멈춤: `KEYCODE_BACK`).

## 평가

`/sdcard/Documents/sos/`에서 세션 하나(`lapsession_*.csv` + 그 `run_*` 폴더들)를 받아:

```bash
python tools/eval/eval_session.py <세션> [--key-only]       # 점수, 라운드 승, 오검출, 시간
python tools/eval/eval_session.py <세션> --kind addl_every  # Check 탭 실험
python tools/eval/unsupported_boxes.py <세션>              # 아무도 못 본 박스
```

확인된 차 = 같은 트랙 위치(±1/60 바퀴, IoU ≥ .3)에서 다른 run 3개 이상도 본 박스(정답 라벨 없음). 정의는 `tools/eval/sos_eval.py`.

## 반응 곡선 · Neural-AE 가중치

10-16 기록(`sos_cf_v1`, 따로 전달)에서 PC로 만든다.

```bash
./gradlew :app:compileDebugUnitTestKotlin
tools/curve/run_jvm.sh ToleranceCurveSosCf <sos_cf_v1> curve_gyro.txt -Dsos.speed=gyro   # → assets/tolerance_curve_gyro.txt
tools/curve/run_jvm.sh NaeTrainSosCf <sos_cf_v1> <출력 폴더>                             # → nae_hist_scalar_3x3.bin
```

## 코드

| 파일 | |
|---|---|
| `MeasurementController.kt` | 방법별 루프: `runAddl`(proposed), baseline들, `runPhysSweepFast`, `runCollect55`, `runBudgetProbe` |
| `RawSensorCapturer.kt` | Camera2 RAW, 병렬 모드(`startParallel`) |
| `MeasurementActivity.kt` | 화면, 버튼 계획, adb 명령 |
| `acquire/HowToSense.kt` | 반응 곡선으로 key 설정 고르기 |
| `acquire/V3Cause.kt` | 사진별 검출 정리, 융합 |
| `acquire/BurstAlign.kt`, `SparseFlow.kt` | 트래킹 |
