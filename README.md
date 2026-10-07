# OpenBlur 💨

Add motion blur to your videos on Android. Made by **xyether**, with processing that runs locally on your phone.

**[Download the APK](https://github.com/XYETHER/OpenBlur/releases/latest)** · [Report a bug](https://github.com/XYETHER/OpenBlur/issues)

## What it does

- Adds motion-compensated blur to a selected part of a video.
- Offers Light, Medium, Strong, and Extreme blur presets.
- Lets you preview the result and compare it with the original.
- Includes adaptive Dynamic blur and a fixed blur mode.
- Exports H.264 or supported H.265 in MP4, with supported AAC audio.
- Includes bitrate and supported encoding-speed controls in Settings.

No account, uploads, or internet connection needed.

## How to use it

1. Install the APK from Releases.
2. Import a video in **Studio** and select the range you want.
3. Choose a blur preset. Open **Advanced** if you want to adjust vector quality or Dynamic blur.
4. Render a preview and compare **Original** with **Processed**.
5. In **Settings → Encoding**, choose your encoder and bitrate.
6. Export, then **Save As** or **Share**.

Opening Advanced won’t cancel an export. If you change settings while exporting, the changes apply to the next export.

## Settings

| Setting | What it changes |
| --- | --- |
| Blur strength | How much motion blur is added. |
| Vector quality | How much work goes into motion analysis. Higher settings take longer. |
| Dynamic blur | Adapts the blur around scene changes. Turn it off for fixed blur. |
| Encoder | H.264 or H.265, depending on your phone. |
| Bitrate | Automatic, or your own value. More bitrate usually retains more detail and makes a larger file. |
| Encoding speed | Faster or higher quality, when the hardware encoder supports adjustable effort. |

## Requirements

- **Android 8.0+**, with a **64-bit ARM** processor.
- **OpenGL ES 3.1+** and compatible video decoding/encoding support.
- SDR video. HDR and protected media aren’t supported yet.

> 🚧 Still being developed. Results aren’t perfect, and compatibility varies by phone. If something goes wrong, report your device, Android version, and settings in an issue.

Save any exports you want to keep before uninstalling the app.

## Build from source

Use Android Studio with **JDK 17+**, **Android SDK 34**, **NDK 25.1.8937393**, and **CMake 3.22.1**.

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat`. Release builds need your own signing key. Native source is included; a normal build doesn’t download MVTools source.

More details: [architecture](docs/ARCHITECTURE.md) · [native source and credits](docs/NATIVE-SOURCE.md) · [building a release](docs/RELEASE.md).

## Credits and license

Created by **xyether**. Includes adapted [MVTools](https://github.com/dubhater/vapoursynth-mvtools) code and [VapourSynth](https://github.com/vapoursynth/vapoursynth) headers. Uses Inter and Manrope fonts.

OpenBlur is licensed under **GPL-2.0-or-later**. See [LICENSE](LICENSE) and [third-party notices](NOTICE.md). The corresponding source for each APK is available with its release.
