plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// ─────────────────────────────────────────────────────────────────────
// Sync compiled penalty contract artifacts into the app's assets.
// Source of truth is contract/src/managed/penalty/, populated by
// `npm run compact` in the contract/ directory (requires compactc that
// matches the @midnight-ntwrk/compact-runtime version pinned in
// contract/package.json — currently 0.30.0 → runtime 0.15.0).
//
// We hook this into preBuild so any forgotten copy is caught at build
// time. We do NOT run the compactc step from Gradle — TS toolchain
// stays as the source of truth for that.
// ─────────────────────────────────────────────────────────────────────
val contractDir = rootProject.file("contract")
val contractManaged = file("$contractDir/src/managed/penalty")
val syncContractAssets = tasks.register<Copy>("syncContractAssets") {
    description = "Copy compiled penalty contract artifacts into app assets."
    group = "build"

    from("$contractManaged/contract") {
        include("index.js")
        rename { "penalty-contract.js" }
        into("runtime")
    }
    from("$contractManaged/keys") {
        include("*.prover", "*.verifier")
        into("keys")
    }
    from("$contractManaged/zkir") {
        include("*.bzkir")
        into("keys")
    }
    into("src/main/assets")

    // Build fails fast (rather than later in the runtime) if the contract
    // hasn't been compiled yet.
    doFirst {
        if (!contractManaged.exists()) {
            throw GradleException(
                "Contract not compiled at $contractManaged — run `npm run compact` in $contractDir first.",
            )
        }
    }
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

tasks.named("preBuild") { dependsOn(syncContractAssets) }

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

    // Kuira SDK — consumed as Maven artifacts published to mavenLocal by the
    // parent project (`./gradlew publishToMavenLocal`). POMs include all
    // transitive deps (zxing, bitcoinj, ktor, room, credentials, etc.) so
    // kicks no longer redeclares them. To pull in a new Kuira module, add a
    // single line below — no AAR copy, no transitive bookkeeping.
    implementation("io.github.kuiralabs:dapp-ui:0.1.0-alpha01")
    implementation("io.github.kuiralabs:midnight-sdk:0.1.0-alpha01")
    // Owns the one shared MidnightSdk + canonical WalletConfig. KicksActivity
    // is the config authority (it calls ensureSdk with the activity), MatchManager
    // is a follower (awaitSdk) — so Kicks runs a single SDK / single chain sync
    // instead of the panel + MatchManager each building their own.
    implementation("io.github.kuiralabs:wallet-runtime:0.1.0-alpha01")
    // Still declared directly so Hilt resolves MidnightSdkProvider's
    // WalletSeedSource constructor param at Kicks's compile time (AAR
    // implementation deps are runtime-scoped for consumers).
    implementation("io.github.kuiralabs:wallet-seed:0.1.0-alpha01")
    // SDK uses `implementation(project(":core:*"))` so those types aren't
    // exposed to its consumers transitively. Kicks references compact types
    // directly (MidnightContract, MidnightConfig, WitnessResult) and the
    // network enum, so declare them here.
    implementation("io.github.kuiralabs:compact-engine:0.1.0-alpha01")
    implementation("io.github.kuiralabs:network:0.1.0-alpha01")
    // Hilt's compile-time annotation processor needs the types referenced
    // by `dapp-ui`'s `DappUiModule` on the compile classpath. dapp-ui
    // declares these as `implementation` (runtime scope in the POM), so
    // they're available at runtime via transitive resolution but invisible
    // to the consumer's compile classpath. Declare them explicitly here.
    implementation("io.github.kuiralabs:identity:0.1.0-alpha01")
    implementation("io.github.kuiralabs:auth:0.1.0-alpha01")

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
