plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "postmark.core.crypto"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }

    sourceSets {
        getByName("test") {
            resources.srcDir(rootProject.file("../protocol/fixtures"))
        }
    }
}

dependencies {
    api(libs.tink.android)
    implementation(project(":core:model"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
