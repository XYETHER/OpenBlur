# OpenBlur

Motion blur for Android, made by xyether. Import a video, choose how much blur you want, and preview it before exporting.

[Download the APK](https://github.com/XYETHER/OpenBlur/releases/latest)

## Using OpenBlur

Open **Studio**, import your video, and select a range. Start with a blur preset, then render a preview to compare the original with the result. **Advanced** has vector quality and Dynamic blur controls if you want to adjust them.

In **Settings → Encoding**, choose H.264 or H.265 and set the bitrate. Some phones also let you adjust encoding speed. Export when you're happy with the preview, then use **Save As** to keep a copy or **Share** to send it.

You can open Advanced while exporting. Any changes you make apply to the next export.

## Before you install

You'll need Android 8.0 or newer, a 64-bit ARM phone, OpenGL ES 3.1, and compatible video hardware. OpenBlur currently supports SDR video; HDR and protected videos won't work.

Processing runs on your phone. Exports stay in app storage until you save a copy, so save anything you want to keep before uninstalling.

This is still in development. Blur can look rough on some clips, and phone compatibility varies. [Report a problem](https://github.com/XYETHER/OpenBlur/issues) with your phone model, Android version, and the settings you used.

## Building

Use JDK 17+, Android SDK 34, NDK 25.1.8937393, and CMake 3.22.1.

```bash
./gradlew testDebugUnitTest assembleDebug
```

On Windows, use `gradlew.bat`. Release builds need your own signing key. The native source is included in the repo.

[Architecture](docs/ARCHITECTURE.md) · [Native source](docs/NATIVE-SOURCE.md) · [Release builds](docs/RELEASE.md)

OpenBlur includes adapted [MVTools](https://github.com/dubhater/vapoursynth-mvtools) code, [VapourSynth](https://github.com/vapoursynth/vapoursynth) headers, and Inter and Manrope fonts. See [NOTICE.md](NOTICE.md) for credits and licenses.

Licensed under [GPL-2.0-or-later](LICENSE). Each APK release includes its corresponding source.
