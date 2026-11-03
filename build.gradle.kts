/*
 * SPDX-FileCopyrightText: 2022-2026 Andrew Gunnerson
 * SPDX-FileCopyrightText: 2023 Patryk Miś
 * SPDX-License-Identifier: GPL-3.0-only
 */

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

if (providers.gradleProperty("suiteEnabled").orNull == "true") {
    mapOf<String, Any>(
        "compileSdkVersion" to 37, "targetSdkVersion" to 37, "minSdkVersion" to 28,
        "androidxActivityVersion" to "1.13.0", "androidxAppCompatVersion" to "1.7.1",
        "androidxCoordinatorLayoutVersion" to "1.3.0",
        "androidxCoreVersion" to "1.19.1", "androidxFragmentVersion" to "1.8.9",
        "androidxWebkitVersion" to "1.15.0", "junitVersion" to "4.13.2",
        "androidxJunitVersion" to "1.3.0", "androidxEspressoCoreVersion" to "3.7.0",
        "cordovaAndroidVersion" to "10.1.1", "kotlin_version" to "2.4.21"
    ).forEach { (key, value) -> extra[key] = value }
}
