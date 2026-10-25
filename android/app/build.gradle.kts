import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // T186: @Serializable queue DTO for the durable retirement outbox
    // (pure-Kotlin JSON, no Android stub on that path).
    alias(libs.plugins.kotlin.serialization)
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
// T417: isolated TEST candidate only. Never enabled by release/beta or by server errors.
val atomicRegistrationEnabled = providers.gradleProperty("remanence.atomicRegistration.enabled").orElse("false").get().also {
    require(it == "true" || it == "false")
    require(it != "true" || (googleSignInEnabled == "true" && recoveryEnabled == "true")) {
        "Atomic registration requires Google and recovery; no unprotected fallback"
    }
}
// T186: experimental pending-recipient journey. Default OFF everywhere;
// only the debug variant consumes this opt-in property. Release and beta
// are hard-disabled so an experimental build can never ride the live/beta
// pipeline. Debug enables the isolated .pendingtest package/label.
val pendingEnabled = (findProperty("remanence.pending.enabled") as? String ?: "false").lowercase().also {
    require(it == "true" || it == "false") { "remanence.pending.enabled must be true or false" }
}
// T186 M12b: root-selected out-of-band service pin (per-epoch PUBLIC
// deployment UUID + Ed25519 public keyset, standard base64). Supplied via
// `-Premanence.pending.serviceKeyId=<uuid> -Premanence.pending.serviceKeysetB64=<b64>`
// for pending debug builds only; empty (fail closed) unless root provides
// the generated epoch pair. Release/beta below hard-wire empty. Never an
// R-carried key, never the discarded-fixture pair at runtime.
val pendingServiceKeyId = (findProperty("remanence.pending.serviceKeyId") as? String ?: "").trim()
val pendingServiceKeysetB64 = (findProperty("remanence.pending.serviceKeysetB64") as? String ?: "").trim()
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
        // T186: launcher label placeholder. Default points at the existing
        // @string/app_name; the pending debug block below repoints it at the
        // generated @string/pending_app_name. No duplicated app_name value.
        manifestPlaceholders["pendingAppLabel"] = "@string/app_name"
    }

    buildTypes {
        named("debug") {
            if (recoveryEnabled == "true") {
                // Test installation must not replace the owner's current app/data.
                applicationIdSuffix = ".recoverytest"
                versionNameSuffix = "-recovery-test"
            }
            if (pendingEnabled == "true") {
                // T186 isolated experimental install: never overwrites beta.
                // Distinct identity does NOT mean disabled recovery: the
                // pending journey preserves mandatory PRF/Google readiness,
                // so recovery (and the normal Google config) stays ON.
                // No bypass: the standard recoveryEnabled/google checks above
                // still apply.
                check(recoveryEnabled == "true") {
                    "remanence.pending.enabled requires remanence.recovery.enabled=true (PRF/Google readiness preserved)"
                }
                applicationIdSuffix = ".pendingtest"
                versionNameSuffix = "-pending-test"
                // Distinct launcher label via a separate generated resource
                // (never a second app_name value): the main manifest reads
                // ${pendingAppLabel}, defaulting to @string/app_name.
                resValue("string", "pending_app_name", "Remanence Pending Test")
                manifestPlaceholders["pendingAppLabel"] = "@string/pending_app_name"
            }
            if (atomicRegistrationEnabled == "true") {
                applicationIdSuffix = ".atomicregistrationtest"
                versionNameSuffix = "-atomic-registration-test"
                resValue("string", "atomic_test_app_name", "Remanence Atomic Test")
                manifestPlaceholders["pendingAppLabel"] = "@string/atomic_test_app_name"
            }
            buildConfigField("boolean", "REMANENCE_ATOMIC_REGISTRATION", atomicRegistrationEnabled)
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("http://127.0.0.1:8000/")),
            )
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", v2LineLocalizationEnabled)
            buildConfigField("boolean", "REMANENCE_RECOVERY_ENABLED", recoveryEnabled)
            buildConfigField("boolean", "REMANENCE_GOOGLE_SIGN_IN_ENABLED", googleSignInEnabled)
            // T186: pending journey gate. Debug-only opt-in; release/beta below hard-OFF.
            buildConfigField("boolean", "REMANENCE_PENDING_ENABLED", pendingEnabled)
            // T186 M12b: out-of-band service pin (empty unless root passes
            // the per-epoch public pair for this pending debug build).
            buildConfigField("String", "REMANENCE_PENDING_SERVICE_KEY_ID", quoteBuildConfigString(pendingServiceKeyId))
            buildConfigField("String", "REMANENCE_PENDING_SERVICE_KEYSET_B64", quoteBuildConfigString(pendingServiceKeysetB64))
        }
        named("release") {
            buildConfigField("boolean", "REMANENCE_ATOMIC_REGISTRATION", "false")
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(remanenceApiBaseUrl("https://invalid.invalid/")),
            )
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", "false")
            buildConfigField("boolean", "REMANENCE_RECOVERY_ENABLED", "false")
            buildConfigField("boolean", "REMANENCE_GOOGLE_SIGN_IN_ENABLED", "false")
            // T186: pending journey is debug-only; release can never enable it.
            buildConfigField("boolean", "REMANENCE_PENDING_ENABLED", "false")
            // T186 M12b: no service pin outside pending debug (fail closed).
            buildConfigField("String", "REMANENCE_PENDING_SERVICE_KEY_ID", quoteBuildConfigString(""))
            buildConfigField("String", "REMANENCE_PENDING_SERVICE_KEYSET_B64", quoteBuildConfigString(""))
        }
        // Closed Play beta is explicit, non-debuggable and separate from the
        // legacy release defaults. Never publish the recoverytest debug APK.
        create("beta") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isDebuggable = false
            // The server in prod mode requires atomic registration; the older
            // onboarding paths are closed there.
            buildConfigField("boolean", "REMANENCE_ATOMIC_REGISTRATION", "true")
            buildConfigField("boolean", "REMANENCE_PLAY_BETA", "true")
            buildConfigField("boolean", "REMANENCE_RECOVERY_ENABLED", "true")
            buildConfigField("boolean", "REMANENCE_GOOGLE_SIGN_IN_ENABLED", "true")
            buildConfigField("boolean", "REMANENCE_V2_LINE_LOCALIZATION", "false")
            // T186: pending journey is debug-only; beta can never enable it.
            buildConfigField("boolean", "REMANENCE_PENDING_ENABLED", "false")
            // T186 M12b: no service pin outside pending debug (fail closed).
            buildConfigField("String", "REMANENCE_PENDING_SERVICE_KEY_ID", quoteBuildConfigString(""))
            buildConfigField("String", "REMANENCE_PENDING_SERVICE_KEYSET_B64", quoteBuildConfigString(""))
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
        // T186: the pending debug block generates @string/pending_app_name
        // via resValue; AGP requires the resValues feature for any build
        // type that declares custom resource values.
        resValues = true
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
