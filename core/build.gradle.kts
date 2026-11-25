import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val ortVersion: String = providers.gradleProperty("onnxruntimeVersion").get()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // The Android app supplies the identical ai.onnxruntime API via onnxruntime-android at runtime.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Optional local verification against reference clips; skipped when the directory is absent.
    systemProperty("jarvis.models", rootProject.file("app/src/main/assets/models").absolutePath)
    systemProperty("jarvis.testdata", System.getenv("JARVIS_TESTDATA") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
