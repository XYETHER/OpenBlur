# Contributing to OpenBlur

Thanks for contributing. OpenBlur is an Android/NDK media app, so small changes can affect codec timing, GPU ownership, native licensing, and device behavior.

## Before opening a pull request

1. Create a focused branch and keep unrelated formatting/refactors out of the change.
2. Do not commit APKs/AABs, build directories, keystores, `key.properties`, media captures, device logs, tokens, or local SDK paths.
3. Run the relevant checks:

   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   # When a device/media/GLES path changes:
   ./gradlew connectedDebugAndroidTest
   ```

4. For render changes, validate a real exported output—not just UI state or an encoder success callback.
5. Describe supported device(s), media fixture(s), exact commands, and known gaps in the PR.

## Engineering rules

- Keep the normal production pixel path GPU-only: do not introduce `Image` extraction, I420 `ByteArray`s, JNI pixel buffers, `glReadPixels`, or other full-frame CPU readback.
- Do not silently fall back to CPU processing. If GPU requirements are not met, expose the unavailable state.
- Preserve timestamps, trim/VFR behavior, rotation, AAC handling, cancellation, EOS, and cleanup.
- Treat UI controls as contracts: `Dynamic blur` defaults on; disabling it selects fixed MVTools-style behavior; `Ultra quality` is opt-in.
- Do not change accepted GPU analysis/synthesis logic as part of UI or documentation work.

## Native and license changes

`app/src/main/cpp/vendor/` contains tracked GPL/LGPL material. Do not edit it casually. Follow [docs/NATIVE-SOURCE.md](docs/NATIVE-SOURCE.md): refresh pinned inputs deterministically, retain notices, update the manifest hashes, and document any patch series. Native-source/provenance changes need a release-level review.

## Reporting bugs

Use the issue template. For media failures, include device model, Android version, input codec/resolution/frame rate, selected settings, the exact error/log, and whether a produced output opens. Do **not** upload personal media without permission; use a minimal synthetic fixture when possible.

## Code of conduct

Be direct, respectful, and evidence-driven. Personal attacks, harassment, and sharing someone else’s media or private data are not acceptable.
