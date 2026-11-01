# Third-party components and licensing notes

This project uses the following components. Review the exact transitive dependency and license set before commercial or government deployment.

- **Android Gradle Plugin 9.1.1** — Android build tooling; see Google's Android Gradle Plugin license terms.
- **Gradle 9.3.1** — build system; Apache License 2.0.
- **Chaquopy 17.2.0** — Python-for-Android Gradle plugin/runtime; review Chaquopy's current license and commercial terms.
- **Jetpack Compose, AndroidX, WorkManager, DocumentFile, AppCompat, Kotlin Coroutines** — AndroidX / Kotlin ecosystem licenses, primarily Apache License 2.0.
- **FFmpegKit Maintained 8.1.9 (`ffmpeg-kit-full`)** — LGPL-3.0 according to the published artifact metadata. Preserve notices and comply with LGPL distribution and relinking obligations. The exact FFmpeg configuration and bundled codecs must be audited for the release artifact.
- **Python standard library** — Python Software Foundation License.

No yt-dlp, yt-dlp-ejs, QuickJS-NG, proxy pool or platform-attestation component is bundled in this version.

The application is a generic direct-media client. Users and deploying organizations are responsible for ensuring that each media source, download and conversion is authorized by the relevant rights holder and provider terms.
