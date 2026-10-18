import java.net.URI
import java.security.MessageDigest

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
        // The release workflow passes the tag's version; versionCode follows
        // it as major * 10000 + minor * 100 + patch so every release is newer.
        val version = (findProperty("hexkeys.version") as String?) ?: "1.0.0"
        val (major, minor, patch) = version.split(".").map { it.toInt() }
        versionCode = major * 10000 + minor * 100 + patch
        versionName = version
        // A phone and the emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release builds are signed with the key the release workflow takes from
    // its secrets; without them a release build stays unsigned.
    val keystore = System.getenv("HEXKEYS_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("HEXKEYS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HEXKEYS_KEY_ALIAS")
                keyPassword = System.getenv("HEXKEYS_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            // Shrunk, which also drops the debug log: its writes hang on
            // BuildConfig.DEBUG, false here, so nothing of it ships.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { buildConfig = true }

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
    commandLine(
        "cargo", "ndk",
        "-t", "x86_64", "-t", "arm64-v8a",
        "-o", file("src/main/jniLibs").absolutePath,
        "build", "--release", "-p", "hexkeys-jni",
    )
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

/**
 * sherpa-onnx, which runs the speech model, as its release's Android
 * library: fetched once into libs, checked against the hash it had when it
 * was picked, and kept out of the repository like the core's libraries.
 */
val sherpaVersion = "1.13.8"
val sherpaHash = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpa = file("libs/sherpa-onnx-$sherpaVersion.aar").also { aar ->
    fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
    if (!aar.exists() || sha256(aar) != sherpaHash) {
        aar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        val partial = File(aar.path + ".part")
        URI(url).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        check(sha256(partial) == sherpaHash) { "sherpa-onnx $sherpaVersion does not have the hash it was picked with" }
        partial.renameTo(aar)
    }
}

dependencies {
    // Styles for the autofill service's inline suggestions, in the format services render.
    implementation("androidx.autofill:autofill:1.1.0")
    implementation(files(sherpa))
}
