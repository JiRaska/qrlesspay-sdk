// SPDX-License-Identifier: Apache-2.0
rootProject.name = "qrlesspay-sdk"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":kmp")
