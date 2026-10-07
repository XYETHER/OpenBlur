## Summary

<!-- What changed and why? Keep this focused. -->

## Verification

- [ ] `./gradlew testDebugUnitTest assembleDebug` passed
- [ ] `./gradlew assembleRelease` passed when release/native/build files changed
- [ ] Connected-device evidence included when GLES, codecs, timing, or exports changed
- [ ] A real exported MP4 was validated when render behavior changed

## Release / provenance checks

- [ ] No APK/AAB, keystore, secret, private media, local path, or generated build artifact was added
- [ ] Native/vendor changes update `docs/NATIVE-SOURCE.md` and the vendor manifest as required
- [ ] UI-only changes do not alter approved GPU processing behavior

## Device evidence (when applicable)

<!-- Device model, Android version, input fixture characteristics, commands, and result. -->
