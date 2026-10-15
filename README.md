# RayNeo SoS (통합 앱)

카메라의 노출(셔터)과 게인(ISO)을 **검출 결과를 보고 고르는** 연구용 Android 앱이다. 같은 APK가 두 기기에서 돈다.

| 기기 | 화면 | 용도 |
|---|---|---|
| RayNeo X3 Pro 글래스 | 터치패드 조작 UI | 원래 최종 빌드(아래 부록) |
| Galaxy S26 Ultra (`SM-S948N`) | 폰 화면 UI, Limo(토이카 로봇) 주행 | 지금 진행 중인 실험 |

제조사가 RayNeo면 글래스 UI, 아니면 폰 UI가 자동으로 뜬다.

## 지금 상태 (2026-10-16)

- 작업 브랜치: **`v3-experiments`**. 이 브랜치와 `limo-collection`은 **GitHub에 올라가 있지 않다.** 이 폴더(또는 git bundle)로만 전달된다.
- 진행 중인 실험은 두 질문이다.
  1. software 합성(digital regain + burst sum)이 실제로 그 설정으로 찍은 사진을 충실히 재현하는가.
  2. proposed(v3cause)가 baseline들(Sense-well, AE-Phone, PhysSweep, ShinNM)을 이기는가.
- **다음 담당자는 [docs/HANDOVER_v3_experiments.md](docs/HANDOVER_v3_experiments.md)부터 읽는다.** 목적, 수집된 데이터와 유효 여부, 로그 형식, 분석 방법, 남은 일이 모두 거기 있다.

## 새 환경 준비

1. **저장소**: 폴더를 통째로 복사한다. `app/build/`, `.gradle/`은 빌드 때 다시 생기므로 빼도 된다. 또는 원래 PC에서 `git bundle create RayNeo_SoS_all.bundle --all`로 묶어 받은 뒤, `git clone RayNeo_SoS_all.bundle RayNeo_SoS`, `git checkout v3-experiments`.
2. **도구**: SDK Manager에서 설치한다.
   - Android SDK platform 36
   - NDK `27.0.12077973`
   - CMake `3.22.1`
   - platform-tools(adb)
3. **JDK 21** (예: Android Studio의 JBR 21). **Java 25로는 Gradle 8.13이 깨진다.** Gradle은 wrapper가 8.13을 알아서 받는다.
4. **`local.properties`**: `sdk.dir=<Android SDK 경로>`. git에 없으며 Android Studio가 만들어 준다.
5. **서명 키**: 폰에 이미 깔린 앱과 **같은 debug keystore**(`~/.android/debug.keystore`, Windows는 `%USERPROFILE%\.android\debug.keystore`)가 있어야 덮어 설치(`install -r`)가 된다. 키는 따로 전달된다.
   - 키가 다르면 앱을 지우고 다시 깔아야 한다. 그러면 앱 전용 폴더가 지워진다.
   - 그 폴더에는 실험이 쓰는 read noise 기준값 `read_noise.json`이 있으니, 지우기 전에 `/sdcard/Android/data/com.sos.integrate/files/`를 먼저 백업한다.

## 빌드와 설치

```bash
# macOS / Linux / Git Bash
JAVA_HOME=<JDK 21 경로> ./gradlew assembleDebug
```

```powershell
# Windows PowerShell
$env:JAVA_HOME = '<JDK 21 경로>'; .\gradlew.bat assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.sos.integrate/com.example.activeperception.MeasurementActivity
```

- Application ID: **`com.sos.integrate`**. 폰에 예전 앱 `com.sos.rayneox3.final`(아래 부록의 글래스 빌드)이 함께 깔려 있을 수 있다. 이름이 비슷하니 혼동하지 말 것.
- 설치하면 앱이 재시작된다. **측정 중이 아닐 때만** 설치한다.
- Windows Git Bash에서 adb를 쓸 때는 `export MSYS_NO_PATHCONV=1`로 경로 변환을 끈다.
- `app/.cxx/`의 빌드 파일 일부가 git에 추적되고 있어 빌드 후 항상 수정됨으로 보인다. 커밋할 때는 소스 파일만 고른다.

## 실험 앱 사용 (Galaxy S26 Ultra)

첫 화면(주행 화면) 오른쪽 위 **auto_collect** → 검은 화면에 지금 실행 중인 방법이 큰 글씨로 뜨고, 아래에 버튼이 셋 있다.

| 버튼 | 언제 | 시간 |
|---|---|---|
| read noise | 폰마다 한 번, 렌즈를 완전히 가리고(엎어 놓기) | 약 12초 |
| static | 조명마다, 폰을 고정하고 차가 보이게 | 약 1분 (30프레임) |
| Limo | 조명마다, Limo 주행 | 약 9분 (40바퀴: collect 20, 비교 방법 5개 × 4) |

- 시작할 때 "삑" 1번, 끝나면 "삑" 3번이 난다. 손으로 멈추면 소리가 나지 않는다.
- 실행 중인 버튼을 다시 누르면 멈춘다.
- 폰은 세로든 가로든 달아도 된다. 실행마다 중력으로 사진 방향을 맞춘다.
- 결과는 폰의 `/sdcard/Documents/sos/`에 저장된다.
  - 실행마다 `run_<platform>_<method>_<epoch_ms>/` 폴더(`manifest.json`, `frames.csv`, `dets.jsonl`, `img/` 등)
  - Limo 세션은 `lapsession_<epoch_ms>.csv`
  - 가져오기: `adb pull /sdcard/Documents/sos/<폴더>`

자세한 절차, 바퀴 판정, 로그 형식은 핸드오버 문서 §5~§7에 있다.

## 코드 구조 (주요 파일)

`app/src/main/java/com/example/activeperception/`

| 파일 | 역할 |
|---|---|
| `MeasurementActivity.kt` | UI, auto_collect, adb 실행 명령, 화면 방향 |
| `MeasurementController.kt` | 방법별 실행 루프(prop_c, physsweep, shin_nm, v3cause, collect, read noise)와 로깅 |
| `LapSession.kt` | 자이로 기반 바퀴 판정과 방법 전환 |
| `acquire/V3Cause.kt` | proposed(v3cause) 판단 로직 |
| `ParallelRawCandidateSource.kt`, `app/src/main/cpp/native_tensor_preprocessor.cpp` | RAW → 검출기 입력(모든 방법 공통) |
| `RawSensorCapturer.kt` | Camera2 RAW 촬영, 고정 초점 |

## 문서

| 문서 | 내용 |
|---|---|
| [docs/HANDOVER_v3_experiments.md](docs/HANDOVER_v3_experiments.md) | **지금 실험의 전부**: 목적, 데이터, 분석 방법, 남은 일 |
| [docs/PIPELINE.md](docs/PIPELINE.md), [docs/IMPLEMENTATION_NOTES.md](docs/IMPLEMENTATION_NOTES.md), [docs/FINAL_BUILD.md](docs/FINAL_BUILD.md), [docs/TOUCHPAD_CONTROLS.md](docs/TOUCHPAD_CONTROLS.md) | RayNeo 글래스 최종 빌드 당시의 문서 |

---

## 부록: RayNeo X3 Pro 글래스 최종 빌드 (원래 README, 2026-08)

> 통합 이전에 쓰인 내용이다. 지금 Application ID는 `com.sos.integrate`이고, 아래의 `com.sos.rayneox3.final`은 그때의 별도 앱이다.

RayNeo X3 Pro (`ARGF20`, Android 12)용 최종 SoS 앱이다. 실험과 중간 버전은 기존
`/Users/silver/Desktop/sos_glass`에 보존하고, 이 프로젝트에는 검증 후 채택한 실행
경로와 필요한 모델만 둔다.

### 기본 실행 경로

```text
Camera2 RAW10 4032×3024
→ SENSOR_TIMESTAMP 기반 RAW/metadata 매칭
→ CFA별 dynamic black-level 차감 + 2× subsampling
→ P-step: N=4 RAW burst / K=9 exposure×gain 후보
   non-P-step: physical single RAW / K=3 gain 후보
→ fused native ARM path
   (first-N 합산 → 2×2 demosaic → digital gain → sRGB OETF
    → 90° 회전 → 640 letterbox/normalization → tensor)
→ 5-class YOLOv8n FP16, OpenCL GPU fixed batch B=9/B=3
→ allocation-reuse Decode/NMS → Σconfidence argmax
→ selected candidate만 Bitmap/overlay 생성
→ 다음 sensor request, UI, logging, optional cloud offload
```

### 원본 SoS에서 유지한 기능

- exposure×gain candidate grid와 `P` 주기의 full-grid probing
- burst 합산으로 exposure 후보, digital re-gain으로 gain 후보 생성
- 전체 후보의 batched YOLO 추론과 confidence-sum 기반 최적 cell 선택
- 검출이 전혀 없을 때 entropy/laplacian/tenengrad/Crete/safe-cell fallback
- 선택 exposure를 실제 sensor에 적용하고 다음 step의 anchor로 사용
- Fixed, phone/custom AE, AE-quantized, Proposed 모드
- cross-exposure consistency 기반 비동기 cloud advisory/offload
- P=5/9/12, 회전 턴테이블 시작 동기화, 터치패드 조작
- 후보별 detection JSONL, frame/candidate/router/timing CSV, 선택 JPEG 저장

### RayNeo 최적화

- RayNeo camera 0의 RAW10, 4032×3024, 90° orientation을 사용한다.
- 수신 순서가 아니라 sensor timestamp와 실제 exposure/ISO metadata로 RAW를 승인한다.
- 12-frame 고정 guard 대신 적용 metadata가 확인된 첫 RAW를 사용한다.
- finite single sequence와 다음 burst prefetch로 capture wait를 처리와 겹친다.
- AWB/CCM은 제거하고 원본 SoS와 같은 digital gain + sRGB OETF를 사용한다.
- Bayer sum, demosaic, gain, sRGB, rotation, resize를 하나의 native formation 경로로 합친다.
- 모든 후보의 ARGB/Bitmap을 만들지 않고 tensor로 직접 추론한 뒤 선택 후보만 표시한다.
- tensor/RAW/LUT/decode/NMS 버퍼를 재사용한다.
- 모델은 검증된 640 FP16 COCO5 B=1/3/9만 포함한다. 정확도 저하와 CPU fallback이
  확인된 Full-INT8 512/640 및 NNAPI/NPU 경로는 포함하지 않는다.

COCO5 출력 순서는 `cup`, `wine glass`, `banana`, `bus`, `dining table`이며 앱 내부에서
원래 COCO class ID로 다시 매핑한다.

### 검증된 steady-state 기준

선택 Bitmap/overlay와 비동기 저장을 제외한 detection-ready 기준:

| Step | 평균 지연 |
| --- | ---: |
| K=3 single probing | 약 **0.32 s** |
| K=9 burst probing | 약 **0.98 s** |

새 최종 APK는 독립 빌드와 정적 구성을 확인했다. glass가 현재 연결되어 있지 않아 설치
후 camera/GPU smoke test는 아래 순서로 진행해야 한다.

### 빌드와 설치

```bash
./gradlew :app:assembleDebug --offline
adb -s <DEVICE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

- Application ID: `com.sos.rayneox3.final`
- 앱 이름: `RayNeo SoS`
- 기본 설정: Proposed, P=5, Safe fallback, digital boost 2×, confidence 0.25

최초 Start 시 camera와 FP16 GPU B=1/3/9를 로드·워밍업하므로 첫 실행은 오래 걸릴 수
있다. 이후 처리 시간은 steady-state 값으로 수렴한다.

### 실기 확인 체크리스트

1. 앱 목록에서 `RayNeo SoS`가 보이고 camera 권한 요청이 뜨는지 확인한다.
2. Proposed/P=5 Start 후 `K=9 burst` 1회와 `K=3 single` 4회가 반복되는지 확인한다.
3. 화면 방향, 선택 이미지, bounding box 좌표가 일치하는지 확인한다.
4. `metadata_match=1`이 유지되고 exposure가 선택된 shutter로 바뀌는지 확인한다.
5. GPU backend가 B=1/3/9 모두 GPU이고 crash/thermal throttling이 없는지 확인한다.
6. 앱 전용 files 디렉터리의 run 폴더에서 `manifest.json`, `frames.csv`, `exp55.csv`,
   `candidates.csv`, `candidate_dets.jsonl`, `router.csv`, `img/`를 확인한다.

세부 구조는 [docs/PIPELINE.md](docs/PIPELINE.md), 터치 조작은
[docs/TOUCHPAD_CONTROLS.md](docs/TOUCHPAD_CONTROLS.md)를 참고한다.
