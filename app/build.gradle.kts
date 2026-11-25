import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Instant

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ortVersion: String = providers.gradleProperty("onnxruntimeVersion").get()

// CI passes BUILD_NUMBER (monotonic GitHub run number) so every build can be installed over the previous one.
val buildNumber: Int = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
val gitSha: String = System.getenv("GIT_SHA")?.take(12) ?: runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("local")

android {
    namespace = "dk.jarvisbot.app"
    compileSdk = 35

    defaultConfig {
        // Keep the applicationId and signing key stable so updates install over existing data.
        applicationId = "dk.jarvisbot.app"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        buildConfigField("String", "BUILD_TIME", "\"${Instant.now()}\"")
        buildConfigField("String", "ORT_VERSION", "\"$ortVersion\"")
        // Real phones are ARM. Set JARVIS_ABIS=x86_64 for an emulator-only build.
        val abis = (System.getenv("JARVIS_ABIS") ?: "arm64-v8a,armeabi-v7a").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        ndk { abiFilters += abis }
    }

    // ONNX Runtime native libs are large; store them compressed to keep the APK download small.
    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        create("dev") {
            // Development key committed in keystore/ (see README). Override via env vars in CI if desired.
            storeFile = file(System.getenv("JARVIS_KEYSTORE_FILE") ?: "../keystore/jarvis-dev.jks")
            storePassword = System.getenv("JARVIS_KEYSTORE_PASSWORD") ?: "jarvisdev"
            keyAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
            keyPassword = System.getenv("JARVIS_KEY_PASSWORD") ?: "jarvisdev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources { noCompress += "onnx" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")
}

// The offline speech-recognition model is not in git (too large); tools/whisper/fetch_model.sh downloads it.
val whisperDir: File = file("src/main/assets/models/whisper")
tasks.named("preBuild") {
    doFirst {
        val missing = listOf("small-encoder.int8.onnx", "small-decoder.jarvis.onnx", "small-tokens.txt", "mel_filters_80.bin")
            .filter { !File(whisperDir, it).exists() }
        if (missing.isNotEmpty()) throw GradleException("Whisper model files missing $missing – run tools/whisper/fetch_model.sh first")
    }
}
