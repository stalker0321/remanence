import com.google.protobuf.gradle.proto

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

sourceSets {
    named("main") {
        proto {
            srcDir(rootProject.file("../protocol/proto"))
        }
    }
    named("test") {
        resources.srcDir(rootProject.file("../protocol/fixtures"))
    }
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

dependencies {
    api(libs.protobuf.javalite)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnitPlatform()
}
