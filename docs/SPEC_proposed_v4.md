# proposed v4: 실기기 run 을 위한 앱 구현 spec

(2026-10-20 받은 spec 원문. 같은 날 받은 정정·추가는 본문에 [추가 10-20] 으로 표시. 이름은 **Duel** 로 정함 (10-20): 앱 모드 `duel_trace` = L0, `duel_every` = L1.)

받는 쪽: 기존 폰 앱(`sos_control`)을 구현한 agent.
이 문서는 **실기기에서 돌릴 proposed v4 (`proposed_v4_L0`, `proposed_v4_L1`)** 를 적는다. 정의와 기준 구현(Python)은 부록 A 에 모두 있다.
이미 있는 것(manual capture, burst 요청, 이미지 형성 경로, `ae_phone`, sense-well(`proposed_p5_*`), run 디렉터리 구조, `candidate_dets.jsonl`·`frames.csv`·`manifest.json`)은 그대로 쓴다. 이름이나 방식이 기존과 다르면 기존 쪽에 맞추고 알려 주면 된다.
**이전 판(`proposed_v3cause`, 10-15 spec)의 놓친 이유별 해법·Δ 표·깜박임 거름·hysteresis 는 쓰지 않는다.**

(출처 표기용, 구현에는 필요 없음: 연구 기록 `revision/BACKLOG.md` 10-16 ~ 10-20 절, `revision/SYSTEM_MODULES.md`. 본문의 'S..' 는 그 기록의 이름이다.)

---

## 1. 한 줄 요약

**밝기(gain)는 물리로, 노출 시간(shutter)은 같은 순간 두 장의 비교로, 사진을 더 볼지는 흔적으로 정하고, 같은 순간의 사진만 합쳐 출력한다.**

| 무엇 | 어떻게 | 왜 |
|---|---|---|
| gain | 잡힌 차가 날아가지 않는(box 안 날아간 비율 < 0.7) 한 가장 높은 gain | 날아감은 pixel 산수로 알 수 있음. 0.7 = 검출기가 날아감을 견디는 정도를 정답 없이 잰 값 (S126a) |
| shutter | 한 칸 이웃 사진과 짝지어 '한쪽에서만 잡힌 차' 를 세고, 이웃이 낫다는 확신이 0.95 이상이면 옮김. 옮긴 직후엔 같은 방향으로 계속 비교(선 탐색) | 번짐이 검출에 주는 해는 계산할 수 없어 검출기에 직접 물음 (S108a); 지형이 봉우리 하나라 한 칸씩이면 닿음 (S109a) |
| 더 볼지 | L0: 직전 frame 의 base 사진에 흔적이 있으면 다음 frame 에 2 장 / L1: 매 frame 2 장 | 흔적으로 찍으면 같은 사진 수에서 더 많이 되찾음 (S120a) |
| 무엇을 더 | 같은 gain, shutter 만 한 칸 다른 사진(비교 사진과 같은 장) | 실기기에서 놓친 차는 거의 shutter 쪽에 있음; 같은 RAW 의 digital 사본은 못 살림 (S116a, S119a) |
| 합치기 | 두 장 사이 flow 로 맞추고, 날아간 만큼 덜 믿고, 묶은 뒤 한 번 판단 | (S113a) |

## 2. 모드 (2 개 + 비교 정책 3 개)

| mode | 2 장을 찍는 frame |
|---|---|
| `proposed_v4_L0` | 선 탐색 중, 또는 직전 frame 에 흔적 |
| `proposed_v4_L1` | 매 frame |
| `ae_phone` | — (기존) |
| sense-well P = 5 (`proposed_p5_*`, 오프로드 끔) | — (기존) |
| `fixed_1600_240` | — (늘 ISO 1600 · 1/240 s 한 장) |

- `fixed_1600_240` 은 **방법이 아니라 기준선**이다: 이전 기록(sos_cf_v1)의 정답으로 고른 사후 최적 고정 칸 — 세 조명 모두 이 칸이었다(격자 모서리라 격자 밖이 더 좋을 수도 있음). '미리 알아야만 고를 수 있는 고정 설정' 과 비교하려고 넣는다.

## 3. 설정 격자

- shutter: 1/240, 1/120, 1/60, 1/30, 1/15 s (sos_cf_v1 과 같음). '한 칸' = 이 목록에서 이웃.
- gain(ISO): 100, 200, 400, 800, 1600. analog 상한 1200, 1600 = analog 1200 × digital 1.333 (sos_cf_v1 과 같음). **1600 이 상한.**
- 이미지 형성은 기존 경로 그대로(black 빼기 → 합 → demosaic → digital gain → clip 한 번 → sRGB → 640 letterbox).

## 4. frame 마다

### 4.1 들고 있는 상태
```
base_g, base_s          # base 설정 (gain 칸, shutter 칸)
w[dir], l[dir]          # dir ∈ {short, long}: 이웃의 이김·짐 수 (base 를 옮기면 0 으로)
line                    # 선 탐색 방향: None | short | long
prev_trace              # 직전 frame base 사진에 흔적이 있었나
prev_cars               # 직전 frame base 사진에서 잡힌 box 들 (+ 그 사진의 선형 pixel 접근)
rng                     # Thompson 용 난수 (씨앗을 manifest 에)
```
시작: `base_g, base_s` = 폰 AE 의 첫 설정을 격자에서 가장 가까운 칸으로; w = l = 0; line = None; prev_* = 없음.

[정정 10-20] w, l 은 비교 횟수가 아니라 '한쪽에서만 잡힌 차' 의 수다. 한 frame 에서 여러 대가 함께 세어질 수 있다 (예: 4:0 은 한 frame 에서도 나올 수 있음).

### 4.2 순서
```
1. 이번 frame 계획 (직전 정보만 씀 — 이번 사진을 보기 전에 정할 수 있어야 함)
   nb_dir = None
   if line and 격자 안(base_s ± 1 in line):        nb_dir = line,  reason = "line"
   elif mode is L1:                                   nb_dir = thompson(), reason = "always"
   elif prev_trace:                                   nb_dir = thompson(), reason = "trace"
   else:                                              reason = "none"
2. gain: base_g = gain_rule(prev_cars)                                # 4.5 (잡힌 차가 없으면 그대로)
3. 찍기: nb_dir 가 None 이면 1 장 (base_g, base_s);
         아니면 한 요청 burst 2 장: ① (base_g, base_s) ② (base_g, base_s ± 1)
4. 검출: 각 사진 per_image(conf >= 0.05 전부) → out(>= 0.25), low, weak(흔적)   # 부록 A
5. 출력: 1 장이면 base 의 out;  2 장이면 align(②→① 시각) 후 fuse([①, ②])        # 4.4, 부록 A
6. 배우기 (2 장일 때): (w_t, l_t) = discord(①, ②) ; w[nb_dir] += w_t ; l[nb_dir] += l_t   # 4.3
7. 옮기기: if P(p > 0.5 | Beta(w[nb_dir]+1, l[nb_dir]+1)) >= 0.95:
              base_s 를 nb_dir 로 한 칸; w = l = 0 (두 방향 모두); line = nb_dir
           elif line and l_t > w_t: line = None                       # 이웃이 이번에 지면 선 탐색 끝 (비김은 계속)
8. 기록 갱신: prev_trace = (①의 weak 가 있나), prev_cars = ①의 out
```
- `thompson()`: 갈 수 있는 방향(격자 안)마다 Beta(w+1, l+1) 에서 하나씩 뽑아 큰 방향. 하나뿐이면 그 방향.
- 격자 끝에 닿으면 1단계에서 그 방향이 막혀 선 탐색은 저절로 끝난다.

### 4.3 배우기: 한쪽에서만 잡힌 차 (discord)
- ①②를 합친 출력(5단계의 fuse 결과)의 box 마다, ① 의 out 에 그 box 와 IoU >= 0.3 인 box 가 있나(①에서 잡힘), ② 의 out 에 (② 시각으로 옮긴 자리와) IoU >= 0.3 인 box 가 있나(②에서 잡힘).
- ② 에서만 잡힘 → w_t += 1, ① 에서만 → l_t += 1, 둘 다·둘 다 아님 → 안 셈.

### 4.4 두 장 맞추기 (align)
- ① 과 ② 의 회색 저해상도 이미지 사이 optical flow (DIS 등 dense; 또는 box 안 점 몇 개의 sparse). ② 의 box 마다 그 box 안 flow 중앙값만큼 ① 시각으로 옮긴다.
- 두 장 사이 시간 차는 burst 한 장분(예상 ~17 ms)이라 작다. 시간 차를 `frames.csv` 에 남긴다.

### 4.5 gain 규칙 (`gain_rule`)
- 확인할 자리 R = `prev_cars`. **잡힌 차가 없으면 gain 을 바꾸지 않는다** (이때 gain 이 멈춰 버리는 문제가 실제로 있는지는 4.6 에서 확인).
- 후보 gain G (100…1600) 마다, R 의 각 자리에서 '날아간 비율' = 직전 base 사진의 선형 값(clip 전, black 뺀 값) × (G ÷ 그때의 gain) >= white 인 pixel 비율. **새로 찍거나 검출하지 않는 산수다.**
- 모든 자리에서 그 비율 < 0.7 인 가장 큰 G. 그런 G 가 없으면 100.
- gain 은 바꾸면 바로 적용되므로 기다림(hysteresis) 없음. 반영 지연 frame 수를 알려 준다.

### 4.6 확인할 것: 잡힌 차도 흔적도 없을 때 설정이 멈춰 버리나
지금 규칙에서 잡힌 차가 없으면 gain 이 그대로이고, 흔적도 없으면 L0 은 이웃을 찍지 않아 shutter 도 그대로다. 아주 어두워 아무것도 안 보이면 영영 그 설정에 머물 수 있다(예전 실차 P1 실명과 같은 꼴). **이번 run 에서는 해법을 넣지 않고, 이 문제가 실제로 생기는지만 확인한다** — 생기면 그때 해법을 설계한다.
- 기록: frame 마다 `nothing` (잡힌 차도 흔적도 없음), 그리고 그런 frame 이 이어진 길이와 그동안 gain·shutter 가 바뀌었는지(5.1).
- 컴퓨터에서 볼 것(9절): `nothing` 이 이어진 구간마다 길이, 그 구간에 오라클 정답(gt_pipeline)으로는 차가 있었는지, 같은 자리에서 다른 정책(ae_phone, L1)은 차를 잡았는지.

## 5. 저장할 것

원칙: **결정마다 그 결정에 쓴 입력을 함께 남겨, 컴퓨터에서 같은 결정을 다시 계산할 수 있게 한다.**

### 5.1 `decisions.jsonl` — frame 마다 한 줄
```
{ "frame": t, "mode": "...",
  "plan": {"reason": "line|always|trace|none", "nb_dir": "short|long|null", "thompson_draws": {"short": x, "long": y} | null},
  "base": {"g_req", "g_applied", "s_req", "s_applied"},
  "gain": {"n_cars", "clip_by_G": {"100": max_frac, ...} | null, "chosen": G, "changed": bool},
  "images": [{"role": "base|neighbor", "exposure_us", "iso", "analog", "digital"}],
  "discord": {"w_t", "l_t"} | null,
  "votes_after": {"short": [w, l], "long": [w, l]}, "p_better": {"short": p, "long": p},
  "move": {"from_s", "to_s"} | null, "line_after": "short|long|null",
  "nothing": bool, "nothing_run": n }              # n = 잡힌 차도 흔적도 없는 frame 이 지금까지 이어진 수 (4.6)
```

### 5.2 이미지와 출력
- `candidate_dets.jsonl`: 사진마다 — `frame`, `role`, 설정, box 마다 `xyxy`, `conf`, `class`, box 안 날아간 비율. **conf 0.05 이상 전부.**
- `dets.jsonl`: frame 마다 출력 box 와 근거(묶인 사진별 반응, 무게, evidence), 지운 box 와 이유.
- 사진 저장: 기존처럼(검출기 입력 JPEG). 가능하면 test run 에서 base 사진의 선형 값(축소본)도.

### 5.3 `frames.csv` (기존 열 + 아래)
`n_images`, `n_captures`, `reason`, `base_iso`, `base_shutter`, `nb_shutter`, `t_capture_start`, `t_capture2_start`, `t_capture_end`, `t_output`, `capture_ms`, `formation_ms`, `flow_ms`, `infer_ms`, `fusion_ms`, `iso_applied_lag_frames`.

### 5.4 노출마다 잡음 기록 (conversion gain 용)
- 찍은 사진마다 Camera2 `CaptureResult.SENSOR_NOISE_PROFILE` (채널마다 (S, O); 잡음 분산 = S·x + O, x = 정규화 신호) 를 그 사진의 ISO 와 함께 `noise_profile.jsonl` 에. **다음 판의 gain 규칙('잡음이 실제로 줄어들 때만 올림', S129a)에 쓸 값**이다.

### 5.5 `manifest.json`
mode, 격자(shutter·ISO 목록, analog 상한), τ = 0.7, 확신 0.95, conf 0.25 / 0.05, IoU 0.3 / 0.5, flow 방법, Thompson 씨앗, 시작 설정(AE 값과 고른 칸), 앱 build.

### 5.6 replay 확인 (넘겨받을 때)
`decisions.jsonl` + `candidate_dets.jsonl` 로 컴퓨터에서 계획·gain·discord·옮김·선 탐색을 다시 계산했을 때 앱과 같아야 한다.

## 6. 기기에서 한 번 잴 것 (test run 전에)
1. **conversion gain**: (A) 5.4 의 noise profile 이 나오는지·ISO 마다 값; (B) 안 나오거나 이상하면 photon transfer — 정지 장면, ISO 100…1600 마다 같은 설정 2 장, 두 장 차이의 분산 ÷ 2 대 평균(밝기 구간별) → 기울기. RAW 에서 black 을 빼되 0 에서 자르지 않은 값. 렌즈를 가린 20 장으로 read noise 도(기존 `read_noise.json` 방식).
2. **burst 2 장의 시간**: 1 장 frame 과 2 장 frame 이 섞일 때 frame 시간, 두 장 시작 시각 차, 노출 1/15 s 일 때.
3. **gain 반영 지연**: ISO 를 바꾼 뒤 몇 frame 째에 적용되나.
4. **(선택) τ 다시 재기**: 세워 둔 채 차가 보이는 장면에서, base 사진의 RAW 로 digital gain ×2, ×4 사본을 만들어 검출 → 잡힌 차가 사본에서도 잡히는 비율 대 날아간 비율. 0.7 근처면 그대로.

## 7. test run (짧게, 본 run 전)
- 세워 둔 채로 각 mode 20 frame: (1) L0 에서 흔적이 있으면 다음 frame 이 2 장인지, (2) 손전등으로 차를 날리면 gain 이 내려가는지, (3) 렌즈를 거의 가려 아무것도 안 잡히게 했다가 다시 열었을 때 gain·shutter 가 어떻게 되는지(멈춰 있다가 차가 다시 잡히면 움직이는지 — 4.6 의 확인), (4) `decisions.jsonl` 로 replay 확인(5.6).
- 트랙에서 조명마다 각 mode 3~5 분: shutter 가 옮겨 가는지(`move`), 선 탐색이 켜지고 꺼지는지, frame 시간.

## 8. 본 run
- 장소·조명: 같은 Limo 트랙, bright / dim / dark (sos_cf_v1 과 같은 조건).
- 정책 5 개(2절). **(정책, 조명)마다 run 3 개 이상** (v9 에서 run 사이 흔들림이 .04~.10 이었음), run 마다 3 바퀴 이상.
- 순서: 한 조명 세션 안에서 정책 순서를 run 마다 돌린다(같은 순서 반복 금지).
- 채점(컴퓨터에서): 기존 `gt_pipeline` 으로 run 마다 오라클 정답 + `eval_v2` 의 envelope 분모(R_inv). own-oracle recall 은 쓰지 않는다.

## 9. 컴퓨터에서 볼 것 (참고)
- 조명별 recall / precision / F1(R_inv 기준), 순간당 찍은 사진·검출 수, frame 시간.
- L0 대 L1(흔적을 직전 frame 에서 가져오는 손해).
- 4.6: 잡힌 차도 흔적도 없는 구간의 길이·빈도, 그 구간에 오라클로는 차가 있었는지(있었다면 설정이 멈춰 놓친 것), 그동안 gain·shutter 변화.
- base 가 최적 쪽(gain 1600·짧은 shutter)에 닿는 시간, 선 탐색 횟수.
- [추가 10-20] 첫 바퀴를 따로 보고한다 (AE 출발에서 옮겨 가는 구간).
- [추가 10-20] 1/240 에 닿기까지 걸린 frame 수를 센다. → frame 마다 바퀴 번호가 로그에 있어야 함.

## 10. 알려 줬으면 하는 것
- burst 2 장이 frame 속도를 얼마나 늦추는지, 두 장 시간 차.
- ISO·노출 반영 지연.
- noise profile 이 나오는지.
- 어려운 것과 대신 한 것.

---

## 부록 A. 기준 구현 (Python, 외부 의존 없음)

box 는 `[x0, y0, x1, y1]` (pixel), 검출 하나는 `(conf, box)`.

```python
import math, random

VEH = {1, 2, 3, 5, 7}
OUT_THR, WEAK_LO, TAU, CONF_MOVE = 0.25, 0.05, 0.7, 0.95
ISOS = [100, 200, 400, 800, 1600]
SHUTTERS = [1/240, 1/120, 1/60, 1/30, 1/15]

def iou(a, b):
    x1, y1, x2, y2 = max(a[0], b[0]), max(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])
    if x2 <= x1 or y2 <= y1: return 0.0
    inter = (x2 - x1) * (y2 - y1)
    return inter / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)

def contain(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    aa = (a[2] - a[0]) * (a[3] - a[1]); return ix * iy / aa if aa > 0 else 0.0

def nms(dets, thr=0.3):
    keep = []
    for c, b in sorted(dets, key=lambda d: -d[0]):
        if all(iou(b, kb) < thr for _, kb in keep): keep.append((c, b))
    return keep

def per_image(raw_dets):
    """raw_dets: [(conf, box, cls)] (conf >= 0.05) -> (out, low, weak)"""
    v = [(c, b) for c, b, k in raw_dets if k in VEH]
    low = nms([d for d in v if d[0] >= WEAK_LO]); out = nms([d for d in v if d[0] >= OUT_THR])
    weak = [(c, b) for c, b in low if c < OUT_THR and all(iou(b, ob) < 0.3 for _, ob in out)]
    return out, low, weak

def fuse(images):
    """images: [{'out', 'low', 'sat': f(box)->날아간 비율}] (base 가 첫째, 둘째 box 는 이미 base 시각으로 옮김) -> [(box, evidence)]"""
    allb = sorted(((c, b) for im in images for c, b in im['out']), key=lambda x: -x[0]); cl = []
    for c, b in allb:
        if not any(iou(g['box'], b) >= 0.5 for g in cl): cl.append({'box': b, 'peak': c})
    if not cl: return []
    n = len(images); nmax = max(1, round(n / 5)); rows = []
    for g in cl:
        r = [max([c for c, b in im['low'] if iou(g['box'], b) >= 0.5], default=0.0) for im in images]
        w = [1.0 - im['sat'](g['box']) for im in images]
        rows.append((g, sum(a * b for a, b in zip(w, r)) / max(sum(w), 1e-9), sum(x >= OUT_THR for x in r)))
    keep = []
    for g, ev, npic in sorted(rows, key=lambda x: (-x[1], -x[0]['peak'])):
        if all(iou(g['box'], k[0]['box']) < 0.3 for k in keep): keep.append((g, ev, npic))
    out = []
    for g, ev, npic in keep:
        if npic <= nmax and any(contain(g['box'], o[0]) >= 0.5 for o in out): continue
        out.append((g['box'], ev))
    return out

def discord(fused, base_out, nb_out_at_nb_time, move_to_nb):
    """fused: fuse 결과 box 들(base 시각). move_to_nb(box) -> 이웃 시각의 자리. -> (w_t, l_t)"""
    w_t = l_t = 0
    for b, _ in fused:
        hb = any(iou(b, o) >= 0.3 for _, o in base_out)
        hn = any(iou(move_to_nb(b), o) >= 0.3 for _, o in nb_out_at_nb_time)
        w_t += hn and not hb; l_t += hb and not hn
    return w_t, l_t

def p_better(w, l):
    """P(p > 0.5), p ~ Beta(w+1, l+1) = P(Binomial(w+l+1, 0.5) <= w) (정수 w, l)"""
    n = w + l + 1; return sum(math.comb(n, k) for k in range(w + 1)) / 2 ** n

def thompson(dirs, w, l, rng):
    return max(dirs, key=lambda d: rng.betavariate(w[d] + 1, l[d] + 1))

def clip_frac(lin_pixels, ratio, white):
    """lin_pixels: 자리 안 선형 값(black 뺀 값, clip 전) -> ratio 배 했을 때 white 이상인 비율"""
    if not lin_pixels: return 0.0
    return sum(p * ratio >= white for p in lin_pixels) / len(lin_pixels)

def gain_rule(cur_iso, regions, white):
    """regions: 직전 frame 에 잡힌 차마다 box 안 선형 pixel 목록. 비면 None (= gain 을 바꾸지 않음, 4.6)"""
    if not regions: return None
    ok = [G for G in ISOS if all(clip_frac(r, G / cur_iso, white) < TAU for r in regions)]
    return max(ok) if ok else ISOS[0]

def step(state, mode):
    """4.2 의 1단계: 이번 frame 의 (이웃 방향, 이유)"""
    s = state['base_s']; dirs = [d for d, v in (('short', s - 1), ('long', s + 1)) if 0 <= v < len(SHUTTERS)]
    if state['line'] in dirs: return state['line'], 'line'
    if mode.startswith('L1'): return thompson(dirs, state['w'], state['l'], state['rng']), 'always'
    if state['prev_trace']: return thompson(dirs, state['w'], state['l'], state['rng']), 'trace'
    return None, 'none'

def after_compare(state, nb_dir, w_t, l_t):
    """4.2 의 6~7단계"""
    if nb_dir is None: return
    state['w'][nb_dir] += w_t; state['l'][nb_dir] += l_t
    if p_better(state['w'][nb_dir], state['l'][nb_dir]) >= CONF_MOVE:
        state['base_s'] += -1 if nb_dir == 'short' else 1
        state['w'] = {'short': 0, 'long': 0}; state['l'] = {'short': 0, 'long': 0}; state['line'] = nb_dir
    elif state['line'] and l_t > w_t:
        state['line'] = None
```

- `p_better` 확인용 값: (w, l) = (4, 0) → 0.969, (5, 1) → 0.938, (6, 1) → 0.965.
- 위 숫자의 출처: 0.25·0.05·IoU 0.3/0.5 = 공통 약속, 0.95 = 통계 관례, 0.7 = 정답 없이 잰 검출기의 날아감 견딤(S126a, 실기기·sim 같은 값).

## 부록 B. 연구 쪽 근거 (replay, 해석용)
- 실기기 replay (frame 단위, 찍힌 burst 자리 채점) F1 bright / dim / dark: L0(같은 순간 흔적, 상한) .802 / .732 / .689 @ 1.5 장, L0(직전 순간 흔적, 순간 간격 1.2 초라 하한) .798 / .687 / .659, L1 .807 / .750 / .718 @ 2.0 장. 최적 고정(정답으로 고름) .792 / .730 / .668, sense-well P=5 .788 / .754 / .716 @ 5.8 장, AE .713 / .381 / .397.
- sim: .693 / .665 / .663 (최적 고정 .686 / .669 / .682); day 는 sim sensor 의 gain 이 digital 이라 낮음.
- 잡힌 차도 흔적도 없는 구간은 replay 기록에 거의 없어, 설정이 멈추는 문제(4.6)는 replay 로 확인하지 못했다.
