# RayNeo 기기 단가 측정 (X3 Pro, 2026-11-03)

앱 전달 spec(2026-11-03) §0b "기기 자원을 재고 fps · read 수 · 추가 사진 수를 정함"에 대한 답이다. 예전 값은 쓰지 않고 이날 안경에서 새로 쟀다.

## 0. 결론
- **2 fps에서 B_max = 2 (key + 정렬한 2장 합), 추가 사진 1장.** spec의 잠정값(RayNeo 2 fps · B 3)은 맞지 않는다.
  - B2: 처리 p50 367 ms, p90 387 ms (10분, USB 뺀 상태). 450 ms를 넘은 출력은 0%였다.
  - B3 (key + 2장 합 + 4장 합): p50 715, p90 750 ms. 타일 25%만 정렬해도 p90 654 ms라 2 fps에 들어가지 않는다. 정렬보다 형성이 더 크다.
- **설정 반영:** 반복 요청을 바꾸면 새 설정은 8 read 뒤(약 295 ms)에 나온다. 기기 보고값과 RAW 밝기가 늘 같았다(어긋난 read 0/201).
- **열:** 10분 동안 처리 시간이 늘지 않았다. 배터리 31 °C, CPU 1996 MHz로 고정이었다.
- **에너지:** B2는 출력당 0.69 J(1.38 W), B1은 0.58 J(1.17 W)이다(2 fps).

## 1. 조건
- 앱: `rayneo` 브랜치 + 이 문서와 같은 커밋의 측정 도구(`runBudgetProbe` 확장, `runReadProbe` 신설).
- RAW_SENSOR 4032×3024, decode 6×6 블록, ISO 1600 × 1/240(read 측정은 ISO 800), 책상 위 정지.
- `com.iap.mobile.ar_pay`가 다시 켜져 있었다. 다만 측정 중 로그 폭주(GatewayController)는 0이었다.
- 처리 시간 = 출력 하나의 정렬 + 형성 + 검출 + 합집합 + 다음 설정 고르기. 사진을 기다린 시간은 뺐다.
  - 고르기(약 73 ms)는 사진 통계(~67 ms) + choose(~6 ms)를 **순서대로** 잰 상한이다. 앱(`runAddl`)은 통계를 검출과 겹쳐 돌리므로, 실제 처리 시간은 약 70 ms 짧다.

## 2. 단가표

| 항목 | 값 | 측정 |
|---|---|---|
| read 간격 (반복 요청) | 1/240·1/120·1/60: 33.3 ms, 1/30: 34.3 ms, 1/15: 67.7 ms | read_probe |
| 설정 반영 (반복 요청 교체) | 8 read, 약 295 ms | read_probe |
| 반복 요청 옆에 한 장 요청 | 약 298 ms (반복 요청 뒤에 줄 섬), 설정·이웃 read 모두 정상 | read_probe |
| 한 장씩만 요청, 3장 대기 | 약 104 ms, 대신 read 간격 45.8 ms (센서 약 27% 쉼) | read_probe |
| RAW decode | read 하나 63 ms (CPU, 4 thread) | budget_probe lazy |
| 사진 형성 | key 28 ms, key + 2장 합 49~54 ms, key + 2장 합 + 4장 합 202~227 ms | budget_probe nested |
| 화소 정렬 | 더하는 read 하나 약 60~70 ms (타일 전체), 타일 25%: 1장 45 ms, 3장 95 ms | budget_probe nested |
| 검출 | 1 / 2 / 3장 = 80 / 155 / 236 ms + 출력 decode 15~30 ms (묶어도 이득 없음) | budget_probe |
| 합집합 (.25 이상, IoU .3 NMS) | 1 ms 미만 | budget_probe |
| 다음 설정 고르기 | 약 73 ms (순서대로, 상한) | budget_probe |
| 출력 처리 p50 / p90 | B1 197 / 202, B2 367 / 387, B3 715 / 750 (타일 25%: 625 / 654) | budget_probe, 2 fps |
| 에너지 | B1 0.58 J, B2 0.69 J / 출력 | budget_health, USB 뺀 10분 |
| send 폰 쪽 일 | 못 잼 (앱에 send 코드 없음) | – |

## 3. 규칙 적용: B_max(fps)

p90 ≤ 출력 간격의 90%. 왼쪽 값은 고르기를 순서대로 잰 값이고, 오른쪽 괄호는 통계를 검출과 겹쳤을 때(약 −70 ms)의 추정이다.

| fps | 처리 한도 | B1 (202 / ~130) | B2 (387 / ~315) | B3 (750 / ~680) | B_max |
|---|---|---|---|---|---|
| 10 | 90 ms | 안 됨 | 안 됨 | 안 됨 | 0 |
| 5 | 180 ms | 겹칠 때만 | 안 됨 | 안 됨 | 1 (겹칠 때) |
| 3 | 300 ms | 됨 | 안 됨 | 안 됨 | 1 |
| 2.5 | 360 ms | 됨 | 겹칠 때만 | 안 됨 | 1~2 |
| 2 | 450 ms | 됨 | **됨** | 안 됨 | **2** |
| 1.33 | 675 ms | 됨 | 됨 | 타일 25% + 겹칠 때만 | 2~3 |
| 1 | 900 ms | 됨 | 됨 | 됨 | 3 |

## 4. 촬영 방식에 주는 뜻
- **센서를 쉬지 않고 읽기(spec)**: 반복 요청이 맞다. 다만 모든 read를 decode하면 CPU가 모자라므로(read 63 ms × 30 fps), 다음 출력이 쓸 가장 새 read B장만 출력 전에 decode한다. 이번 B2 값이 그 부하(출력당 2장 decode)에서 잰 값이다.
- **설정을 바꿀 때**: 반복 요청 교체는 약 0.3초 걸린다. 2 fps에서는 출력 하나가 늦게 반응하는 정도다. 10/31 proposed는 119출력 중 14번 바꿨다.
- **출력마다 설정을 바꾸는 baseline**(PhysSweep, ShinNM 등): 반복 요청은 0.3초씩 걸려 맞지 않는다. 한 장씩 요청(약 0.1초, 센서 27% 쉼)이 그 방법들의 자연스러운 꼴이다.
- 측정 중 배운 것: 출력이 모으는 중인 read를 버퍼가 지우면 출력이 멈춘다(이날 lazy 6장 조합, 10/31 PhysSweep, 실험자 보고의 조기 종료). 측정 도구에서는 고른 read를 지우지 않게 고쳤다. 본 촬영 코드에도 같은 보호가 필요하다.
- 측정 중 배운 것: 안경을 쓰지 않고 USB를 빼면 약 50초 뒤 잠들어 실행이 멈춘다. 에너지 측정은 착용한 채로 했다.

## 5. 다시 재는 법

```
adb shell am start -S -n com.sos.integrate/com.example.activeperception.MeasurementActivity --es run read_probe --ei probe_iso 800
adb shell am start -S -n com.sos.integrate/com.example.activeperception.MeasurementActivity --es run budget_probe --es budget_mode paced --ei budget_ahead 1 --ez budget_nested true --ei budget_outputs 60 --ei budget_period_ms 500 --es budget_patterns "1\|1,2\|1,2,4"
```

- `--es budget_align_frac 0.25`: 타일 25%만 정렬. `--ei budget_outputs 600`: 조합마다 출력 600개(5분).
- budget_probe는 첫 조합 시작에 삑 1번, 다음 조합에 2번, 끝에 3번 울린다.
- 기록: `probe_reads.csv`, `probe_events.csv` (read_probe); `budget_outputs.csv`, `budget_health.csv`, `budget_summary.json` (budget_probe).

## 6. 측정 run (안경 `/sdcard/Android/data/com.sos.integrate/files/sos/`)
- read: `run_rayneo_read_probe_1793650240434` (첫 시도 `…1793649950595`는 밝기 계산이 무거워 사진이 밀렸다 → 버림)
- 장당 비용: `run_rayneo_budget_probe_lazy_1793648530112`, `run_rayneo_budget_probe_paced_1793649020010`
- 2 fps spec 조합: `run_rayneo_budget_probe_paced_1793650294867` (타일 전체), `…1793650450588` (타일 25%)
- 10분 + 에너지: `run_rayneo_budget_probe_paced_1793650960875` (B2 5분 → B1 5분)
