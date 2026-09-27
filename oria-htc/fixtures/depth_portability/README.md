# Kotlin depth geometry: portable analytic fixtures

These six public fixtures contain manufactured planes and rectangular reliefs only.
They contain no recording, private image, model weight, distance or category label.
`geometry_cases.tsv` supplies the slope, rectangle bounds and expected Python
candidate count/reference status. `DepthObstacleGeometryTest` constructs the same
Float32 grid and asserts these expectations on the JVM.

Verify the Python side from `oria-htc`:

```sh
ml/.venv/bin/python fixtures/depth_portability/verify_geometry_fixtures.py
```

The grid is already dimensionless input to geometry. This fixture does not test
RGB preprocessing or infer a calibrated geometric model. Complete pose changes,
low objects outside the ROI and coplanar doors remain limitations.

## Deliberate runtime differences

Python uses NumPy PCG64 to sample affine-plane hypotheses and a spectral condition
guard. Kotlin uses seeded xorshift32 and a conservative Frobenius condition bound,
versioned as `relative-depth-occupancy-kotlin-xorshift32-v1`. Both use the same
ROI, component size, slope/support criteria, optional reference suppression and
zone fractions. They are **not bit-identical implementations**.

A private comparison on 256 existing captured maps, quantized to Float32 before
both geometry implementations, found 11 frames differing in reference status or
candidate count. Four differed in candidate count; the largest difference was
734 pixels, with a maximum zone-fraction difference of 0.160508. Running the same
Python temporal policy on either set of zones produced the same 7 and 13 proposals
in the two recordings. This is a bounded observation, not universal parity, model
accuracy or a physical obstacle-detection score. Raw maps/results remain ignored
under `validation/geometry-20260927/review-portability-*`.

## Android temporal and audio contract

The independent Kotlin policy keeps three consecutive depth observations over
500 ms, breaks evidence after a gap exceeding 1500 ms, prefers a confirmed center
and uses an 8 s per-zone repeat interval. A proposed intention never consumes that
interval: only a correlated completed-playback ticket does. A failed attempt uses
a provisional 1000 ms retry backoff. Confirmation is a playback callback, not a
human hearing check. A center in cooldown does not fall back to a side.

The controller rejects frames older than 500 ms before starting inference to
avoid spending the processing budget on an already old image. Depth results have
an experimental 1500 ms total freshness budget, checked again before the first
PCM sample; this does not change YOLO's 500 ms budget. Invalidated pending playback
cannot be revived by later evidence. Session/mode changes invalidate ticket
identity, and ambiguous audio requires an explicit backend reset or a correlated
resolution. Calls into the policy must be serialized by one owner thread.

The Kotlin policy's audio-aware cooldown is intentionally different from the Lab
policy, which records a descriptive proposal immediately and emits no sound.
Neither policy knows an object's physical identity, distance or approach speed.
