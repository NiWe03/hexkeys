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
    val ndk = "$sdk/ndk/27.0.12077973"
    environment("ANDROID_NDK_HOME", ndk)
    // whisper.cpp, which the speech input uses, builds with cmake: through
    // the NDK's toolchain for each target, with Ninja, and with the Rust
    // bindings its crate ships rather than generated ones, which would need
    // libclang.
    environment("ANDROID_NDK_ROOT", ndk)
    environment("CMAKE_GENERATOR", "Ninja")
    environment("WHISPER_DONT_GENERATE_BINDINGS", "1")
    // ggml builds for the plain instruction set when cross-compiling, several
    // times slower. Phones since 2018 (ARMv8.2) have dot products and half
    // floats; the x86_64 build is for the emulator on a PC with AVX2.
    environment("GGML_CPU_ARM_ARCH", "armv8.2-a+dotprod+fp16")
    environment("GGML_AVX", "ON")
    environment("GGML_AVX2", "ON")
    environment("GGML_FMA", "ON")
    environment("GGML_F16C", "ON")
    val toolchains = rootProject.file("../jni/cmake").absolutePath
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", "$toolchains/arm64-v8a/android.toolchain.cmake")
    environment("CMAKE_TOOLCHAIN_FILE_x86_64_linux_android", "$toolchains/x86_64/android.toolchain.cmake")
    commandLine(
        "cargo", "ndk",
        "-t", "x86_64", "-t", "arm64-v8a",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release", "-p", "hexkeys-jni",
    )
    // whisper.cpp's crate links the NDK's shared C++ runtime, which the app
    // then has to carry next to the core.
    doLast {
        val sysroot = "$ndk/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"
        for ((abi, triple) in listOf("arm64-v8a" to "aarch64-linux-android", "x86_64" to "x86_64-linux-android")) {
            // Copied by bytes: the NDK in the Nix store is read-only, and a copy keeping its
            // mode could not be overwritten by the next build.
            val target = file("src/main/jniLibs/$abi/libc++_shared.so")
            target.parentFile.mkdirs()
            target.delete()
            target.writeBytes(file("$sysroot/$triple/libc++_shared.so").readBytes())
        }
    }
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

dependencies {
    // Styles for the autofill service's inline suggestions, in the format services render.
    implementation("androidx.autofill:autofill:1.1.0")
}
