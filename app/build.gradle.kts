import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

/** The key shake-to-report posts issues with. Never committed; CI hands it in as a secret. */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.lightphone.spotify"
    compileSdk = 36

    signingConfigs {
        create("release") {
            // keystore.properties is gitignored and written by CI from repo secrets
            // (RELEASE_KEYSTORE_BASE64 and friends). The keystore is never committed —
            // this repo is public, and the signing key is what identifies the app.
            if (keystorePropertiesFile.exists()) {
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "com.lightphone.spotify"
        minSdk = 33
        targetSdk = 36
        versionCode = 110
        versionName = "0.63.0"

        // Path C: native AudioTrack sink (set false to fall back to rodio/cpal).
        buildConfigField("boolean", "USE_AUDIOTRACK_SINK", "true")
        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        // Light Phone III is arm64-only. For emulator: add "x86_64" here (and rustup target).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            // On since v0.11.0. It was off because the rules here had never been exercised
            // against a real R8 run, and this app is unusually R8-hostile: JNA reflection,
            // UniFFI bindings, JNI callbacks arriving from a Rust player thread, Room's
            // generated implementation looked up by name, and bundled ML Kit. The rules in
            // proguard-rules.pro now name each of those mechanisms one at a time. The reason to
            // do it was cold start; the size was expected to barely move, since so much of the
            // APK is native libs and models, and it went 75.8MB -> 24.1MB anyway (build-53 vs
            // build-54). If a minified build misbehaves on device, suspect a missing keep
            // before anything else — and remember that in full mode a `-keep` on a class no
            // longer keeps its members.
            isMinifyEnabled = true
            isShrinkResources = true
            // Keep Log.e/w so OAuth diagnosis survives release (proguard-android-optimize
            // strips Log.d/v/i; we also pin OAuthWebView + Playback login logs).
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signing is mandatory for release: an unsigned or debug-signed release APK
            // installs once and then fails every update with Obtainium's "Failure:
            // Invalid", because Android keys an app on (packageName, certificate) and a
            // CI runner regenerates ~/.android/debug.keystore on every job. CI fails
            // before this point if the keystore secret is missing, so reaching here
            // without keystore.properties means a local release build — which is not a
            // thing we ship, hence the hard failure.
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Sign debug with the release key when available, so a local debug build can
            // replace an installed release in place instead of hitting a cert mismatch.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Media3's session/MediaSessionService surface is still @UnstableApi.
            optIn.add("androidx.media3.common.util.UnstableApi")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // The Rust build script populates src/main/jniLibs.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    packaging {
        // Both the Rust core and ShazamKit's signature library may carry libc++_shared; one
        // copy is fine, two is a packaging failure.
        jniLibs.pickFirsts += "**/libc++_shared.so"
    }
}

dependencies {
    // ShazamKit for Android — Apple ships it as a bare .aar behind an Apple-ID download
    // (developer.apple.com/download/all/?q=Android%20ShazamKit), not on Maven, so it lives in
    // libs/. Drop a newer copy in to upgrade; the filename stays.
    implementation(files("libs/shazamkit-android-release.aar"))
    // Not used by this app directly: ShazamKit's AAR talks to Apple through Retrofit but does
    // not bundle it, so the classes must be on the classpath or R8 fails the build. Same pair
    // (and versions) the SDK was shipped against.
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation(project(":light-ui"))

    // Shared Light* plumbing: the wheel/hardware-key layer and the LightSync backup provider.
    implementation("com.gios:light-common:1.2.3")
    // What actually applies the baseline profile the AAR ships. Below API 31 nothing on the
    // device reads a profile on its own, and even above it ProfileInstaller is what hands the
    // packaged profile to the runtime on first launch.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Media3 session for OS media controls (modern replacement for MediaSessionCompat).
    // 1.9.3 for the gapless audio-offload fix. ExoPlayer and exoplayer-dash are gone with
    // the TIDAL backend; playback is librespot via SimpleBasePlayer, which only needs
    // session + common.
    implementation("androidx.media3:media3-session:1.9.3")
    implementation("androidx.media3:media3-common:1.9.3")
    // SimpleBasePlayer handler methods return Guava ListenableFutures.
    implementation("com.google.guava:guava:33.3.1-android")

    // UniFFI relies on JNA as its FFI layer.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

  // Spotify Web API metadata (user dev-app OAuth).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // On-device QR scan (Passes pattern: CameraX + bundled ML Kit, no GMS bridge).
    implementation("androidx.camera:camera-core:1.5.0")
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Album art loading.
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Library disk cache.
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}

// --- Rust cross-compile + UniFFI binding generation -------------------------
// Builds rust/spotify-core into jniLibs and regenerates Kotlin bindings.
val cargoBuild by tasks.registering(Exec::class) {
    workingDir = rootDir
    val abis = android.defaultConfig.ndk.abiFilters.joinToString(" ")
    if (abis.isNotBlank()) {
        environment("ANDROID_ABIS", abis)
    }
    commandLine("bash", "scripts/build-rust.sh")
}

tasks.named("preBuild").configure {
    dependsOn(cargoBuild)
}
