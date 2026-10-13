import java.net.URI

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

val remanenceProductVersion = providers.gradleProperty("remanence.productVersion").get()
val recoveryEnabled = providers.gradleProperty("remanence.recovery.enabled").orElse("false").get().also {
    require(it == "true" || it == "false") { "remanence.recovery.enabled must be true or false" }
}
val googleSignInEnabled = providers.gradleProperty("remanence.google.enabled").orElse("false").get().also {
    require(it == "true" || it == "false")
    require(it != "true" || recoveryEnabled == "true") { "Google onboarding requires recovery" }
}
val remanenceAndroidVersionCode =
    providers.gradleProperty("remanence.androidVersionCode").get().toInt()

android {
    namespace = "dev.hryshyn.remanence"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "dev.hryshyn.remanence"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = remanenceAndroidVersionCode
        versionName = remanenceProductVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "REMANENCE_PLAY_BETA", "false")
    }

    buildTypes {
        named("debug") {
            if (recoveryEnabled == "true") {
                // Test installation must not replace the owner's current app/data.
                applicationIdSuffix = ".recoverytest"
                versionNameSuffix = "-recovery-test"
            }
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("http://127.0.0.1:8000/")),
            )
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", v2LineLocalizationEnabled)
            buildConfigField("boolean", "REMANENCE_RECOVERY_ENABLED", recoveryEnabled)
            buildConfigField("boolean", "REMANENCE_GOOGLE_SIGN_IN_ENABLED", googleSignInEnabled)
        }
        named("release") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("https://invalid.invalid/")),
            )
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", "false")
            buildConfigField("boolean", "REMANENCE_RECOVERY_ENABLED", "false")
            buildConfigField("boolean", "REMANENCE_GOOGLE_SIGN_IN_ENABLED", "false")
        }
        // Closed Play beta is explicit, non-debuggable and separate from the
        // legacy release defaults. Never publish the recoverytest debug APK.
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = false
            buildConfigField("boolean", "REMANENCE_PLAY_BETA", "true")
            buildConfigField("boolean", "REMANENCE_RECOVERY_ENABLED", "true")
            buildConfigField("boolean", "REMANENCE_GOOGLE_SIGN_IN_ENABLED", "true")
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", "false")
            buildConfigField("String", "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("https://invalid.invalid/")))
            versionNameSuffix = "-beta"
            val keystore = providers.environmentVariable("REMANENCE_UPLOAD_KEYSTORE").orNull
            if (keystore != null) {
                signingConfig = signingConfigs.create("playUpload") {
                    storeFile = file(keystore)
                    storePassword = providers.environmentVariable("REMANENCE_UPLOAD_STORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("REMANENCE_UPLOAD_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("REMANENCE_UPLOAD_KEY_PASSWORD").get()
                }
            }
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

androidComponents {
    beforeVariants(selector().withBuildType("beta")) { it.minSdk = 28 }
}

val verifyBetaEndpoint = tasks.register("verifyBetaEndpoint") {
    doLast {
        val endpoint = URI(remanenceApiBaseUrl("https://invalid.invalid/"))
        require(endpoint.scheme == "https" && !endpoint.host.isNullOrEmpty() &&
            endpoint.host != "invalid.invalid" && endpoint.host != "localhost" &&
            endpoint.host != "127.0.0.1" && endpoint.rawUserInfo == null &&
            endpoint.rawQuery == null && endpoint.rawFragment == null) {
            "Beta requires an explicit HTTPS remanence.apiBaseUrl without credentials/query/fragment"
        }
    }
}
tasks.matching { it.name == "preBetaBuild" }.configureEach { dependsOn(verifyBetaEndpoint) }

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:crypto"))
    implementation(project(":core:model"))
    implementation(project(":core:recognition"))
    // 4.10 shipped 4KB-aligned OpenCV and libc++; 4.12 adds Android 16KB support.
    implementation("org.opencv:opencv:4.12.0")
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp) // Dedicated consent-only telemetry client; never the authenticated API stack.
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime)
    // C2 generator wiring: real EXIF-upright decoder (GeneratorExifDecoder).
    implementation(libs.androidx.exifinterface)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver3)
    debugImplementation(libs.compose.ui.test.manifest)

    // Native-capability probe only (androidTest source set; never packaged
    // into the release APK or production runtime graph).
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
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
