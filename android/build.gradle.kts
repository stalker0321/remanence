plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // AGP 9 includes Kotlin for Android modules. Kotlin JVM is only for :core:model.
    alias(libs.plugins.kotlin.jvm) apply false
}
