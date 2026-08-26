import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.hryshyn.remanence.core.recognition"
    compileSdk = libs.versions.compileSdk.get().toInt()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jdk.get())
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Normative protocol/fingerprint protobuf classes are generated once here
    // (:core:model owns ../protocol/proto) so app-level dex merging never sees
    // duplicates; javalite runtime arrives transitively as api().
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.exifinterface)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.vintage.engine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    // OpenCV Java bindings: compiled/tested against the desktop 4.10 jar on
    // this VPS (identical org.opencv API); the official Android AAR carrying
    // device natives is packaged at the :app level.
    val opencvDesktopJar = file("/usr/share/java/opencv4/opencv-4100.jar")
    require(opencvDesktopJar.exists()) {
        "OpenCV desktop jar missing at $opencvDesktopJar; install libopencv-java as documented in docs/development.md"
    }
    compileOnly(files(opencvDesktopJar))
    testImplementation(files(opencvDesktopJar))
    tasks.withType<Test>().configureEach {
        // Debian's desktop OpenCV ships Java-25 bytecode; only the test JVM
        // needs the newer runtime, the Android build stays on JDK 17.
        javaLauncher.set(
            project.javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            },
        )
        jvmArgs("-Djava.library.path=/usr/lib/jni")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
