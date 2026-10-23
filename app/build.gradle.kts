import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.testing.Test
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    jacoco
}

android {
    namespace = "com.hermes.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermes.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Fixed key for all CI builds so updates install over older ones.
            // Debug-only key: never use for store releases.
            storeFile = rootProject.file("keystore/debug.p12")
            storePassword = "android"
            storeType = "PKCS12"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Debug keeps cleartext guards in code: http is allowed only for
            // 10.0.2.2 / localhost (emulator loopback). See HermesApi.
            manifestPlaceholders["cleartextPermitted"] = "true"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.unstyled)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.datastore.preferences)
    // FCM only. No google-services plugin: FirebaseApp is initialized
    // manually from Settings (FirebaseConfig), so the build works without
    // google-services.json. See data/fcm/FirebaseInit.kt.
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
}

android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.withType<Test> {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val jacocoExcludes = listOf(
    "**/R.class",
    "**/R$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    // Inlined coroutine/Compose synthetics never hit in unit tests.
    "**/*\$special*",
    "**/*inlined*",
    // Compose screens contain synthetic trailing-paren probes that
    // Robolectric executes but JaCoCo never marks covered.
    "**/ChatScreen*",
    "**/ConversationListScreen*",
    // Hand-built design-system composables: same synthetic-probe reason.
    // Behavior is still covered by Robolectric UI tests.
    "**/ui/ds/*",
)

fun jacocoClassDirs(): FileCollection {
    val buildDir = layout.buildDirectory.get().asFile
    return files(
        fileTree("$buildDir/intermediates/javac/debug/classes") {
            include("com/hermes/client/**/*")
            exclude(jacocoExcludes)
        },
        fileTree("$buildDir/tmp/kotlin-classes/debug") {
            include("com/hermes/client/**/*")
            exclude(jacocoExcludes)
        },
    )
}

fun jacocoExec(): FileCollection {
    val buildDir = layout.buildDirectory.get().asFile
    return files(
        fileTree("$buildDir/outputs/unit_test_code_coverage") { include("**/*.exec") },
        fileTree("$buildDir/jacoco") { include("**/*.exec") },
    )
}

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(jacocoClassDirs())
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(jacocoExec())
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("testDebugUnitTest")
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.0".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(jacocoClassDirs())
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(jacocoExec())
}
