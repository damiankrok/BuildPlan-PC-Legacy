plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The project analyzer (STAGE-023A). A plain JVM module on purpose: nothing in
// it may touch Android, Compose or the renderer, and being outside the Android
// plugin is what makes that a compile-time fact rather than a test. It also
// gives the evaluation tests a full JDK, so plan rasters decode through
// ImageIO on the JVM while the device uses BitmapFactory behind the same
// RasterCodec seam.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Deterministic HTML parsing of a supported project page. Pure Java (MIT),
    // Android API 21+, no native code.
    implementation(libs.jsoup)

    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Evaluation tests read this to find the cached source evidence outside the repository.
    System.getenv("BUILDPLAN_ANALYZER_EVIDENCE_DIR")?.let { environment("BUILDPLAN_ANALYZER_EVIDENCE_DIR", it) }
    System.getenv("BUILDPLAN_ANALYZER_LIVE")?.let { environment("BUILDPLAN_ANALYZER_LIVE", it) }
    System.getenv("BUILDPLAN_ANALYZER_PROBE")?.let { environment("BUILDPLAN_ANALYZER_PROBE", it) }
    System.getenv("BUILDPLAN_ANALYZER_PROBE_MASKS")?.let { environment("BUILDPLAN_ANALYZER_PROBE_MASKS", it) }
}
