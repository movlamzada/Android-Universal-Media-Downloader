plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

// Detect the active Python 3 installation on the build host.
// Chaquopy requires the build interpreter to match the Python runtime bundled
// into the APK, so both settings use exactly the same launcher command.
val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val chaquopyPythonCommand = if (isWindowsHost) listOf("py", "-3") else listOf("python3")
val chaquopyPythonVersion = providers.exec {
    commandLine(
        *(chaquopyPythonCommand + listOf(
            "-c",
            "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
        )).toTypedArray()
    )
}.standardOutput.asText.get().trim()

val supportedChaquopyPythonVersions = setOf("3.10", "3.11", "3.12", "3.13", "3.14", "3.15")
if (chaquopyPythonVersion !in supportedChaquopyPythonVersions) {
    throw GradleException(
        "Detected Python $chaquopyPythonVersion. This project requires Python 3.10–3.15 " +
            "for Chaquopy 17.2. " +
            "Run 'py -3 --version' and ensure the Python Launcher selects Python 3.10–3.15."
    )
}

android {
    namespace = "com.mm.universalmedia"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mm.universalmedia"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        // The selected FFmpegKit Maintained 8.1 line publishes these 64-bit ABIs.
        // This intentionally excludes 32-bit-only Android devices.
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

chaquopy {
    defaultConfig {
        // Automatically use the active Python 3 version detected above.
        version = chaquopyPythonVersion
        buildPython(*chaquopyPythonCommand.toTypedArray())
        pip {
            // Pure-Python wheel from PyPI, resolved at build time (needs internet on the build machine).
            // YouTube and other sites change often: the lower bound only guards against very old extractors; pip resolves the newest release at build time.
            install("yt-dlp>=2025.10.22")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // LiveData -> Compose State adapter used for observing WorkManager work status.
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Full LGPL artifact includes FFmpeg HTTPS/TLS support and the base HLS/DASH
    // demuxers needed for clear, finite VOD manifests. No DRM or platform-bypass
    // functionality is included.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
