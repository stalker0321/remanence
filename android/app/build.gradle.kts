plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.postmark.memory"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "app.postmark.memory"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    buildTypes {
        named("debug") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(postmarkApiBaseUrl("http://127.0.0.1:8000/")),
            )
        }
        named("release") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoteBuildConfigString(postmarkApiBaseUrl("https://invalid.invalid/")),
            )
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

fun postmarkApiBaseUrl(default: String): String {
    val raw = (findProperty("postmark.apiBaseUrl") as? String) ?: default
    if (raw != raw.trim()) {
        throw GradleException("postmark.apiBaseUrl must not have surrounding whitespace")
    }
    if (!raw.endsWith("/")) {
        throw GradleException("postmark.apiBaseUrl must end with '/'")
    }
    if (raw.any { ch -> ch == '"' || ch == '\\' || ch.code < 32 || ch.code == 127 }) {
        throw GradleException("postmark.apiBaseUrl contains an illegal character")
    }
    return raw
}

fun quoteBuildConfigString(value: String): String = "\"$value\""
