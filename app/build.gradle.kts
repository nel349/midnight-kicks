plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    // Syncs compiled penalty contract artifacts into the app's assets.
    // Source of truth is contract/src/managed/penalty/, populated by
    // `npm run compact` in contract/ (requires compactc matching the
    // @midnight-ntwrk/compact-runtime version pinned in
    // contract/package.json — currently 0.30.0 → runtime 0.15.0).
    // The plugin wires the sync task into preBuild + fails fast if the
    // contract hasn't been compiled.
    id("com.midnight.kuira.contract") version "0.1.0-alpha01"
}

kuiraContract {
    source.set("../contract/src/managed/penalty")
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
        // Instrumented tests run via `./gradlew :app:connectedDebugAndroidTest`
        // against a connected device/emulator. AndroidX's standard JUnit
        // runner suffices — no Hilt test runner needed because the only
        // currently-instrumented surface (MatchStore + ResumeScreen)
        // doesn't depend on the Hilt graph.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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
            // Real Android system resources for Robolectric tests — MatchStore
            // uses EncryptedSharedPreferences which reads from res/values
            // during master-key construction.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // Unity as a Library
    implementation(project(":unityLibrary"))
    // KicksMatchActivity subclasses UnityPlayerGameActivity, which
    // extends GameActivity → AppCompatActivity AND implements
    // IUnityPlayer{Lifecycle,Support,Permission}* interfaces. The
    // unityLibrary AAR declares these (games-activity, appcompat, and
    // unity-classes.jar) as `implementation` (private), so the app
    // module has to re-expose them here for the compiler to see the
    // full supertype chain.
    //
    // - appcompat / games-activity: pinned to match
    //   ../unityLibrary/build.gradle (don't drift; the Unity-generated
    //   GameActivity assumes a specific games-activity ABI).
    // - unity-classes.jar: contains the IUnityPlayer* interfaces.
    //   Referenced through the same fileTree the unityLibrary module
    //   reads; one source of truth, re-importable when Unity re-exports.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.games:games-activity:4.4.0")
    implementation(files("../unityLibrary/libs/unity-classes.jar"))

    // Kuira SDK — one line. `dapp-ui` is the panel entry; it `api`-exposes the
    // SDK surface Kicks touches directly (compact-engine, identity, network,
    // wallet-runtime, wallet-seed, auth, ledger, …) so the types are on Kicks's
    // compile classpath transitively. No per-module redeclaration needed.
    implementation("io.github.kuiralabs:dapp-ui:0.1.0-alpha01")

    // AndroidX directly used by Kicks's own code (FragmentActivity host,
    // Compose). Things Kuira pulls in transitively (biometric, credentials,
    // room, etc.) come through the SDK/common POMs and don't need to be here.
    implementation("androidx.fragment:fragment-ktx:1.8.4")  // FragmentActivity for biometric prompts
    implementation(platform("androidx.compose:compose-bom:2026.03.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX Security — EncryptedSharedPreferences for MatchVault
    // (persists active-match witnesses across process kills). Backs onto
    // a Keystore-bound master key; no biometric per access because the
    // protected data is game-cheat sensitive, not fund-theft sensitive
    // (witness leak only reveals one match's picks, never a wallet seed).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Hilt DI — required because `dapp-ui`'s sigil/wallet panels are
    // `@HiltViewModel` and resolved through `hiltViewModel()`. Without
    // the Hilt plugin + compiler the consumer graph isn't generated
    // and the panels crash at composition with a missing-entrypoint
    // error. Versions match parent libs.versions.toml.
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Tests (JVM unit). MatchState / ContractStateSnapshot / pure helpers
    // live here. JSON parsing uses Android's org.json, so we need its
    // testing stub via robolectric OR we keep tests independent of org.json.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303") // shadow Android's org.json on JVM
    // Robolectric for MatchStore tests — needs a real Context for
    // EncryptedSharedPreferences (Keystore master key + SharedPrefs
    // backing). Pinned to the same version core:auth uses.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    // ── Instrumented tests (androidTest) ──
    //
    // Run via `./gradlew :app:connectedDebugAndroidTest` against a
    // connected device/emulator. The current suite covers:
    //  - MatchStore with REAL EncryptedSharedPreferences + Android
    //    Keystore (the Robolectric unit tests use plain SharedPrefs;
    //    this confirms the crypto round-trips on actual hardware).
    //  - ResumeScreen Compose UI behavior — empty/single/multi row
    //    rendering + tap routing + back navigation.
    //
    // Compose UI tests use `createComposeRule()` (no Activity host
    // required) so we don't need to stand up Hilt for them.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Compose UI testing — `createComposeRule()`, `onNodeWithText`,
    // `performClick`, etc. Same Compose BOM as the main classpath.
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.03.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
