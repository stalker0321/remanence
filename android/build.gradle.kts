plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 includes Kotlin for Android modules. Kotlin JVM is only for :core:model.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Run unit tests for all Android modules."
    dependsOn(
        ":core:model:test",
        ":core:data:testDebugUnitTest",
        ":core:crypto:testDebugUnitTest",
        ":core:recognition:testDebugUnitTest",
        ":app:testDebugUnitTest",
    )
}
