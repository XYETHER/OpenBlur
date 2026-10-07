# Building a release

Releases should be reproducible from a clean tag and must include corresponding source. Never commit a keystore, private key, password, token, `key.properties`, or populated environment file.

## 1. Prepare and verify

1. Start from a clean working tree and select the release version.
2. Confirm `versionCode` and `versionName` in `app/build.gradle.kts`.
3. Confirm the native pins and generated hashes in `docs/NATIVE-SOURCE.md` and `app/src/main/cpp/vendor/manifest.json`.
4. Run the relevant gates:

```bash
./gradlew clean testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
python tools/native/build_and_test.py --device SERIAL --sizes 128x96 130x98 1920x1080
python tools/native/test_jni.py SERIAL
```

On a compatible phone, test H.264 and H.265 with automatic and manual bitrates. Compare encoder effort endpoints at a fixed bitrate when supported. While exporting, open/close Advanced and edit settings; verify the original request completes and the next export uses the edits.

Run `tools/verify_render.py` against device-produced preview/export fixtures. Record any device-specific skipped tests; do not describe an unrun gate as passing.

## 2. Build an unsigned release APK

```bash
./gradlew clean assembleRelease
```

With the repository's current Gradle configuration, the expected input is:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

Use the `zipalign` and `apksigner` binaries from the same installed Android SDK Build Tools version.

## 3. Sign without embedding credentials

Keep the release keystore outside the repository, preferably in an OS/CI secret store with restricted permissions. Set only non-secret paths/names as local shell variables:

```bash
export OPENBLUR_KEYSTORE=/secure/location/openblur-release.jks
export OPENBLUR_KEY_ALIAS=openblur
export ANDROID_BUILD_TOOLS="$ANDROID_SDK_ROOT/build-tools/<version>"

"$ANDROID_BUILD_TOOLS/zipalign" -p -f 4 \
  app/build/outputs/apk/release/app-release-unsigned.apk \
  app/build/outputs/apk/release/openblur-release-aligned.apk

"$ANDROID_BUILD_TOOLS/apksigner" sign \
  --ks "$OPENBLUR_KEYSTORE" \
  --ks-key-alias "$OPENBLUR_KEY_ALIAS" \
  --out app/build/outputs/apk/release/openblur-release.apk \
  app/build/outputs/apk/release/openblur-release-aligned.apk
```

`apksigner` prompts for passwords. Do not put passwords in the command, shell history, Gradle files, or repository. In CI, inject them from the CI secret store through a protected mechanism supported by the runner, use an ephemeral keystore file, mask logs, and delete temporary signing material after the job.

Verify the signed artifact:

```bash
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose --print-certs \
  app/build/outputs/apk/release/openblur-release.apk
```

Compare the reported certificate digest with the independently recorded release certificate. Test installation/upgrade and a real render on supported hardware.

## 4. Source, hashes, and tag

Create a source archive from the exact release commit. It must include the tracked vendor tree, build scripts, and license material described in `docs/NATIVE-SOURCE.md`; do not use an archive that omits corresponding source.

Generate checksums locally, for example:

```bash
sha256sum app/build/outputs/apk/release/openblur-release.apk openblur-<version>-source.tar.gz > SHA256SUMS
```

On Windows without `sha256sum`, use `certutil -hashfile <file> SHA256` and record the exact output.

Create and verify a signed Git tag without exposing the signing key:

```bash
git tag -s v<version> -m "OpenBlur v<version>"
git verify-tag v<version>
```

The signing key remains in the maintainer's configured GPG/SSH agent or hardware token; it is never copied into the repository.

## 5. Publish

Publish together:

- signed APK;
- `SHA256SUMS`;
- immutable corresponding-source archive or tag;
- release notes listing verified devices/tests and known limitations; and
- a link to `LICENSE` and `docs/NATIVE-SOURCE.md`.

Before publishing, download the uploaded assets, re-run signature/checksum verification, and confirm that the source archive corresponds to the released commit and APK.
