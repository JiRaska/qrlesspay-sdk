// SPDX-License-Identifier: Apache-2.0
plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.android.library") version "8.10.0"
}

// AGP 8.10.0 is the ceiling that still runs on Gradle 8.11.1; 8.11+ needs Gradle 8.13, which is
// the Gradle-9 line. Pinned deliberately, same as the app this core was extracted from.
android {
    namespace = "tech.qrlesspay.sdk"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvm()
    androidTarget()
    // iOS targets are declared because the profile has to link there; they are not built in CI
    // yet, and the README says so rather than implying an artifact that has never been produced.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Pure-Kotlin Ed25519 and SHA-256, so the core links on iOS and Android alike with no
            // JNI. The CBOR codec is hand-rolled in this module — see Cbor.kt for why.
            implementation("io.github.andreypfau:curve25519-kotlin:0.0.8")
            implementation("org.kotlincrypto.hash:sha2:0.8.0")
            // HMAC-SHA256, for the HKDF the spec names for SAS derivation (§4). Keeping the
            // spec's primitive rather than substituting a bare hash: deviating from a stated
            // construction is a decision for the spec, not for an implementation of it.
            implementation("org.kotlincrypto.macs:hmac-sha2:0.8.0")
            // The transport's fetchBundle is suspend, and the Android implementation bridges
            // GATT callbacks with suspendCancellableCoroutine.
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            // Optional at runtime for the host app: UWB hardware is a minority, and the base SDK
            // must not force its binary weight on a bank that skips ranging.
            implementation("androidx.core.uwb:uwb:1.0.0-alpha08")
        }
        androidUnitTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Test-only, never in the published artifact: the negative corpus is JSON, and a
            // hand-rolled reader for it is one more thing that can be wrong while looking right.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
    }
}

// Gradle does not forward -D to the test JVM. Without this the generator runs, reports success and
// writes nothing — an exit code of 0 for work that never happened.
tasks.withType<Test>().configureEach {
    listOf("qrlesspay.writeVectors", "qrlesspay.vectorsDir").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
