# Architecture

OpenBlur separates user-interface state, media transport, GPU effect execution, and native-reference code so the normal render path can be inspected and tested without conflating them.

## Production GPU route

```text
System document picker
  → MediaExtractor / MediaCodec decoder Surface
  → SurfaceTexture external-OES texture
  → worker-owned EGL context and 2D temporal texture ring
  → MvToolsGpuProcessor (GLES vector analysis + two-sided blur)
  → recordable EGL surface / MediaCodec AVC encoder
  → MediaMuxer MP4 + supported AAC packet copy
```

The normal production path must not use `Image` extraction, Java I420 byte arrays, JNI pixel-array processing, `glReadPixels`, or full-frame CPU readback. Texture ownership is confined to the EGL worker that creates the resources.

## Module boundaries

| Module | Responsibility | Must not do |
|---|---|---|
| `ui/` | Compose screens, controls, persisted draft state | Process video pixels |
| `logic/` | Pure editor/render models and validation | Depend on Android codec/GLES APIs |
| `media/` | Input selection, decode/encode, trim, timestamps, muxing | Choose motion-vector policy |
| `gpu/` | EGL ownership, texture transport, motion analysis, synthesis | Read full frames back to the CPU in production |
| `render/` | Backend selection and render orchestration | Hide a backend substitution |
| `cpp/` | JNI CPU reference/provenance support | Become an automatic fallback |

## Motion settings contract

- **Dynamic blur: on** preserves the adaptive scene-protection behavior of the accepted GPU route.
- **Dynamic blur: off** uses fixed MVTools-style blur behavior and does not apply the adaptive scene gate.
- **Vector quality** controls vector-analysis work only. `Ultra quality` has a larger hierarchy/search budget; default Quality behavior remains unchanged.

These controls are persisted in `EditorState` and mapped through `GpuVideoPipeline` to the GPU configuration. UI changes must not silently alter the processor’s accepted analysis or synthesis constants.

## CPU reference route

`RenderEngine`, `VideoPipeline`, and `NativeMotionBlur` preserve a CPU/JNI route around adapted MVTools code. It exists for reference, source reproducibility, and selected tests. `RenderBackendPolicy` does not automatically choose it when GPU requirements are absent.

The GPU and CPU reference implementations are not bit-identical and the project makes no claim that they reproduce every upstream MVTools filter-graph stage.

## Lifecycle and correctness invariants

1. Preserve source presentation timestamps through the encoder surface.
2. Treat trim boundaries as display intervals, including held VFR frames.
3. Copy supported AAC packets without re-encoding and finalize muxing only after encoder EOS.
4. Release decoder, encoder, EGL, SurfaceTexture, and muxer resources on success, cancellation, and failure.
5. Surface unsupported media/capability states as errors—not silent degradation.
6. Keep native vendor provenance deterministic and auditable; see [NATIVE-SOURCE.md](NATIVE-SOURCE.md).

## Testing layers

| Layer | Evidence |
|---|---|
| JVM | Pure policies: quality settings, trim selection, render state |
| Native | ARM64 build/smoke and JNI ABI checks |
| Device instrumentation | EGL/GLES allocation, media transport, export readability, workflow |
| Media validation | Pulled output opened/inspected as an actual MP4 |

Device GPU and codec claims require physical-device evidence. A host build or emulator run alone is insufficient.
