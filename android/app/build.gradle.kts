plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tieo.hexkeys"
    compileSdk = 36
    // The NDK declared in Nix, named rather than left to the plugin's default:
    // the store path is read-only, so a plugin wanting another version cannot
    // install it and stops the build instead.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.tieo.hexkeys"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // A phone and the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The core, built from the Rust workspace rather than committed: a checked-in
// .so drifts the moment the crate changes.
val cargoNdk by tasks.registering(Exec::class) {
    val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    workingDir = rootProject.file("..")
    environment("ANDROID_NDK_HOME", "$sdk/ndk/27.0.12077973")
    commandLine(
        "cargo", "ndk",
        "-t", "x86_64", "-t", "arm64-v8a",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release", "-p", "hexkeys-jni",
    )
}

tasks.named("preBuild") { dependsOn(cargoNdk) }
