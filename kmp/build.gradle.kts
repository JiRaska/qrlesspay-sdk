// SPDX-License-Identifier: Apache-2.0
plugins {
    kotlin("multiplatform") version "2.4.0"
}

kotlin {
    jvm()
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
        }
        commonTest.dependencies {
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
