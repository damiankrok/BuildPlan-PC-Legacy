plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Working decision — see ARCHITECTURE.md. Must be confirmed by the owner
    // before any Google Play release.
    namespace = "com.buildplan.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.buildplan.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        // The domain uses java.time, which is only in the platform from API 26.
        // Desugaring keeps minSdk 24 supported rather than dropping Android 7.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // STAGE-012 renderer spike. Debug only: filament-android draws the model and
    // filamat-android compiles its one material on the device, so no matc binary
    // and no committed .filamat are needed while the renderer is still a
    // candidate. Neither reaches a release build.
    debugImplementation(libs.filament.android)
    debugImplementation(libs.filamat.android)

    // The project analyzer. A pure-JVM module, so its core cannot import Android by
    // construction and its raster evaluation runs on a plain JDK.
    //
    // STAGE-024 moved it from `debugImplementation` to the release path: the app now offers
    // project import, so the module and jsoup ship, and the main manifest asks for INTERNET.
    // What did *not* move is the Analyzer Lab, the evaluation fixtures and the benchmark
    // values — those stay in `src/debug` and `analyzer/src/test`, and `ReleaseBoundaryTest`
    // checks that they do.
    implementation(project(":analyzer"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
