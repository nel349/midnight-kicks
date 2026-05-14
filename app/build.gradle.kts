plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.midnight.kicks"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.midnight.kicks"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
    }
    testOptions {
        unitTests {
            // Android stubs (e.g. android.util.Log) throw on JVM by default,
            // which makes any logging code unreachable in unit tests.
            // Returning default values lets pure-logic tests run without
            // Robolectric or static mocking.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Unity as a Library
    implementation(project(":unityLibrary"))

    // Kuira SDK — local AARs built from the Kuira project
    implementation(files("../libs/midnight-sdk-debug.aar"))
    implementation(files("../libs/compact-engine-debug.aar"))
    implementation(files("../libs/crypto-debug.aar"))
    implementation(files("../libs/identity-debug.aar"))
    implementation(files("../libs/network-debug.aar"))
    implementation(files("../libs/indexer-debug.aar"))
    implementation(files("../libs/ledger-debug.aar"))
    implementation(files("../libs/auth-debug.aar"))

    // SDK transitive dependencies
    implementation("io.ktor:ktor-client-core:2.3.13")
    implementation("io.ktor:ktor-client-okhttp:2.3.13")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.13")
    implementation("io.ktor:ktor-client-logging:2.3.13")
    implementation("io.ktor:ktor-client-websockets:2.3.13")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.13")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.github.dokar3:quickjs-kt:1.0.3")
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    implementation("org.bitcoinj:bitcoinj-core:0.15.10")
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jvm:0.18.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.credentials:credentials:1.5.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.5.0")
    implementation("com.google.android.gms:play-services-auth-blockstore:16.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Tests (JVM unit). MatchState / ContractStateSnapshot / pure helpers
    // live here. JSON parsing uses Android's org.json, so we need its
    // testing stub via robolectric OR we keep tests independent of org.json.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303") // shadow Android's org.json on JVM
}
