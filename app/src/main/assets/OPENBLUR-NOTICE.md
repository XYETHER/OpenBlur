# Third-party notices

OpenBlur includes or distributes components under the following licenses. This notice is an engineering inventory, not legal advice.

| Component | License | Location / source |
|---|---|---|
| MVTools (adapted source) | GPL-2.0-or-later | `app/src/main/cpp/vendor/mvtools/`; upstream pin and source process in `docs/NATIVE-SOURCE.md` |
| VapourSynth headers | LGPL-2.1-or-later | `app/src/main/cpp/vendor/vapoursynth/` with `COPYING.LESSER` retained |
| Inter font | SIL Open Font License 1.1 | `app/src/main/assets/Inter-LICENSE.txt` |
| Manrope font | SIL Open Font License 1.1 | `app/src/main/assets/Manrope-OFL.txt` |
| Gradle wrapper | Apache License 2.0 | `gradlew`, `gradlew.bat` headers |
| x264 assembly sources (vendored by MVTools) | GPL-2.0-or-later (or commercial license from x264) | Retained source headers in `vendor/mvtools/src/asm/`; distributed by OpenBlur under GPL-2.0-or-later |
| SSE2NEON (vendored by MVTools) | MIT | notice retained in `vendor/mvtools/src/sse2neon.h` |

The repository’s top-level GPL-2.0-or-later license applies to OpenBlur as a whole. Redistributors must preserve applicable notices and provide complete corresponding source with distributed binaries. See [LICENSE](LICENSE) and [docs/NATIVE-SOURCE.md](docs/NATIVE-SOURCE.md).
