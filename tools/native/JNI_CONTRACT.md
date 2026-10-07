# CPU reference JNI interface
Library `openblur_mvtools`; class `dev.motionblur.app.render.NativeMotionBlur`, instance methods:
```kotlin
external fun create(width:Int,height:Int,quality:Int,maxStrength:Float):Long
external fun process(handle:Long,previous:ByteArray,current:ByteArray,next:ByteArray,elapsedSeconds:Double):ByteArray
external fun destroy(handle:Long)
```
Use `tools/native/test_jni.py` to check this interface on your device. It is the CPU reference route; normal app rendering uses the GPU.

quality0 Fast,1 Balanced,2 Quality. MaxStrength10..200. Exact packed8-bit I420 size w*h*3/2; even dimensions64..4096 subject to768MiB/session memory estimate. Native owns compensated-error dynamic policy and elapsedSeconds recovery. Caller MUST duplicate current for cross-cut neighbors before process, and recreate session after seek/source/quality/maximum changes. Exact duplicated neighbors trigger strength10 protection. Destroy idempotent; calls serialized. No mid-frame native cancellation.

No Python/VS runtime, no FFTW, no GPU. Genuine pinned MVTools core + unchanged FlowBlur kernel; custom glue/probe resize documented, not claimed bit-identical to desktop. GPL source/license obligations apply.
