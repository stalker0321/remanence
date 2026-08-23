import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins.maybeCreate("java").option("lite")
        }
    }
}

android {
    namespace = "postmark.core.recognition"
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
        getByName("main") {
            proto {
                srcDir(rootProject.file("../protocol/proto"))
            }
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    api(libs.protobuf.javalite)
    testImplementation(kotlin("test-junit5"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
