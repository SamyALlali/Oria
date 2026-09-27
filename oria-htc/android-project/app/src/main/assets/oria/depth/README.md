# Experimental Android relative depth

This directory packages the distinct input252 Depth Anything V2 Small FP32 ONNX artifact for the explicitly selected experimental generic-obstacle mode. It does not replace YOLO. The model is Apache-2.0; the adjacent licence is included in the application.

The model file is intentionally ignored by Git. Recreate the asset from the independently verified local export:

```sh
python3 oria-htc/surface-ml/prepare_depth_android.py
```

The preparation script verifies size and SHA-256 before staging, rejects conflicting assets, and never downloads or modifies source weights.

Exact artifact: 99,117,601 bytes, SHA-256 `3467d320122172aa5e28a961ff2a1ee6e9e3d52db6f0fa8b04ab663efa4c0cba`. Source revision and conversion fidelity are in `model_manifest.json`. The source checkpoint and original input518 export are preserved under surface-ml. Input252 is a separate spatial-resolution variant, not a promise of equivalent predictions. CPU518 on the HTC passed export parity but measured about2016ms/inference; input252 was introduced to reduce this latency. XNNPACK is disabled for depth after two measured initialization failures (optimized graph and Resize kernel). The application requests four CPU threads after paired forward/reverse HTC benchmarks: full synthetic467×832 detect medians259.5/261.5ms with four threads versus415.7/416.1ms with two, with both fixture parity checks passing. These results do not establish live camera/audio latency or sustained thermal performance.

Android reads RGB pixels without rotation or mirror. `DepthImageProcessing` uses separable antialiased Catmull-Rom bicubic convolution with RGB8 intermediate rounding before ImageNet normalization, and antialiased floating-point bilinear convolution for 252×252 →128×128. The filter definition and sample-centre convention follow the primary [Pillow implementation](https://github.com/python-pillow/Pillow/blob/12.0.0/src/libImaging/Resample.c). The Android implementation uses plain Kotlin and does not call Android's bilinear bitmap scaler. Eight JVM tests include pixel-exact RGB comparisons against Pillow on both portrait and landscape fixtures; floating-depth and normalization have explicit tolerances. An immutable synchronized16-entry LRU reuses filter coefficients, and an exact histogram computes8-bit luminance P95 without sorting. Additional tests cover concurrent kernel/dimension reuse and exact percentile agreement with sorted references. This is fixture parity, not a universal image-pipeline equivalence proof.

Quality uses the same `rgb-quality-v1` signal heuristics as Oria Lab: low light or insufficient texture prevents temporal confirmation. The original map remains inspectable. A degenerate map has `available=false`, empty `values`, and an explicit reason. Every accepted map is finite, normalized per frame using P2/P98; values have no metric units and cannot estimate approach speed across frames.

The cache is in `Context.noBackupFilesDir`, avoiding Android backup of the 99 MB model. It is extracted using a temporary file and SHA-256 verification, then loaded by filename to avoid a second 99 MB Java byte array. Existing cache corruption fails explicitly. Detector construction/inference/closure must run on a worker; caller must discard results from old sessions and enforce freshness after inference.

Instrumented tests are explicit bounded synthetic workloads, never camera/audio activation. Target `OnnxDepthDetectorInstrumentedTest#cpuParityAndBoundedBenchmark` with runner arguments:

- `depthThreads=1,2,4` (or one of those values; default2).
- `depthRuns=3` or `5` (default5).
- `depthLabel=baseline` (safe short label for distinct report names).
- `depthWidth=467`, `depthHeight=832` (default camera-size synthetic workload, bounded128..2048).

One deployment supports all thread counts. Original fixture images remain untouched for parity. The timing image is a synthetic portrait scaled once to the reported camera dimensions using Android only to create the workload; model preprocessing still uses the exact Kotlin convolution. Each count runs two fixture parity checks, one full-pipeline warm-up, then3 or5 measurements of preprocess/inference/postprocess and complete detect wall time. The entire invocation shares a90-second budget, checked between native calls; a single native ONNX call cannot be interrupted by that cooperative check. Reports persist as `files/depth_validation/cpu_threads_{N}_{label}.json`, including failure diagnostics. Repeat in reverse order with another label to expose order effects. These brief synthetic workloads do not validate sustained thermal behaviour, live freshness, audio or walking safety. The companion `unsupportedXnnpackFailsExplicitly` test confirms CPU-only enforcement.
