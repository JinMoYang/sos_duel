# RayNeo oscillating-turntable experiment

The rotation experiment is an additional path. It does not replace or change the existing
calibration, test-walk, main-walk, lap-session, or individual method controls.

## On-glass operation

1. Mount the glass and start the turntable. Keep its angle and speed unchanged for the session.
2. Swipe to **회전 실험** and tap once.
3. The first method opens while the app observes three complete oscillations. This overlaps camera,
   model, and GPU warm-up with phase calibration.
4. For every method the app waits for its first logged output, then for the next reference-side gyro
   reversal. Only the following complete reference-to-reference round trip is marked valid.
5. The comparison plan runs once forward and once in reverse, matching the main walk's drift control.
6. During a session, tap twice within three seconds to stop.

Changing turntable angle, speed, or the glass mounting direction requires a new session so the three
calibration cycles are learned again.

## Data

Each method keeps its normal `run_*` directory and files. `frames.csv` has five appended columns:

- `rotation_block`, `rotation_pass`
- `rotation_valid`: `1` only inside the complete aligned cycle; warm-up and transition frames are `0`
- `rotation_phase`: normalized `0..1` phase within that cycle
- `rotation_direction`: signed gyro-axis direction (`+1`/`-1`, not a physical left/right label)

The session root also contains `rotationsession_<timestamp>.csv`. It continuously records the sensor
timestamp, integrated yaw, yaw rate, controller state, method/run name, reversal events, phase anchors,
timeouts, and the exact valid-window start/end events. This is the authoritative offline alignment and
audit log.

## ADB options

```text
--ez rotation_session true
--es run rotation
--es rotation_plan method_a,method_b,...
--ei rotation_rounds 2
--ei rotation_start_delay_s 5
--ei rotation_calibration_cycles 3
```

The app rejects a method transition that does not produce a first output within 30 seconds. Missing
cycles use a period-derived timeout; one invalid cycle is retried, then the session stops rather than
silently advancing with incomparable data.
