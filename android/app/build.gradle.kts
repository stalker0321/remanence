plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Local compile-time rollback switch. The legacy contour path remains the
// default; only the debug variant consumes this opt-in property. Release is
// deliberately hard-disabled so an experimental debug build cannot enable v2.
val v2LineLocalizationEnabled = when (
    (findProperty("remanence.localization.v2.enabled") as? String ?: "false").lowercase()
) {
    "true" -> "true"
    "false" -> "false"
    else -> error("remanence.localization.v2.enabled must be true or false")
}

android {
    namespace = "dev.hryshyn.remanence"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "dev.hryshyn.remanence"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 11
        versionName = "0.1.0-m3-registration-maintenance-preview.1"
    }

    buildTypes {
        named("debug") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("http://127.0.0.1:8000/")),
            )
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", v2LineLocalizationEnabled)
        }
        named("release") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("https://invalid.invalid/")),
            )
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(project(":core:model"))
    implementation(project(":core:recognition"))
    implementation("org.opencv:opencv:4.10.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp)
    debugImplementation(libs.compose.ui.test.manifest)

    // Native-capability probe only (androidTest source set; never packaged
    // into the release APK or production runtime graph).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.5.0")
}

fun remanenceApiBaseUrl(default: String): String {
    val raw = (findProperty("remanence.apiBaseUrl") as? String) ?: default
    if (raw != raw.trim()) {
        throw GradleException("remanence.apiBaseUrl must not have surrounding whitespace")
    }
    if (!raw.endsWith("/")) {
        throw GradleException("remanence.apiBaseUrl must end with '/'")
    }
    if (raw.any { ch -> ch == '"' || ch == '\\' || ch.code < 32 || ch.code == 127 }) {
        throw GradleException("remanence.apiBaseUrl contains an illegal character")
    }
    return raw
}

fun quoteBuildConfigString(value: String): String = "\"$value\""
