/*
 * SPDX-FileCopyrightText: 2022 Andrew Gunnerson
 * SPDX-License-Identifier: GPL-3.0-only
 */

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(if (providers.gradleProperty("suiteEnabled").orNull == "true") RepositoriesMode.PREFER_SETTINGS else RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "BCR"
include(":app")

if (providers.gradleProperty("suiteEnabled").orNull == "true") {
    val gui = file("external/bcr-gui")
    check(gui.resolve("package.json").exists()) {
        "Initialize the BCR-GUI submodule: git submodule update --init"
    }
    check(gui.resolve("node_modules/@capacitor/android/capacitor/build.gradle").exists()) {
        "Install GUI npm dependencies: (cd external/bcr-gui && npm ci)"
    }
    include(":bcrgui-bridge")
    project(":bcrgui-bridge").projectDir = file("integrations/bcrgui-bridge")
    include(":capacitor-android")
    project(":capacitor-android").projectDir = gui.resolve("node_modules/@capacitor/android/capacitor")
    include(":capacitor-community-contacts")
    project(":capacitor-community-contacts").projectDir = gui.resolve("node_modules/@capacitor-community/contacts/android")
    include(":capacitor-app")
    project(":capacitor-app").projectDir = gui.resolve("node_modules/@capacitor/app/android")
    include(":capacitor-clipboard")
    project(":capacitor-clipboard").projectDir = gui.resolve("node_modules/@capacitor/clipboard/android")
    include(":capacitor-device")
    project(":capacitor-device").projectDir = gui.resolve("node_modules/@capacitor/device/android")
    include(":capacitor-filesystem")
    project(":capacitor-filesystem").projectDir = gui.resolve("node_modules/@capacitor/filesystem/android")
    include(":capacitor-preferences")
    project(":capacitor-preferences").projectDir = gui.resolve("node_modules/@capacitor/preferences/android")
    include(":capacitor-share")
    project(":capacitor-share").projectDir = gui.resolve("node_modules/@capacitor/share/android")
    include(":capacitor-status-bar")
    project(":capacitor-status-bar").projectDir = gui.resolve("node_modules/@capacitor/status-bar/android")
    include(":capgo-capacitor-navigation-bar")
    project(":capgo-capacitor-navigation-bar").projectDir = gui.resolve("node_modules/@capgo/capacitor-navigation-bar/android")
    include(":kristianheljas-capacitor-app-restart")
    project(":kristianheljas-capacitor-app-restart").projectDir = gui.resolve("node_modules/@kristianheljas/capacitor-app-restart/android")
    include(":capacitor-native-settings")
    project(":capacitor-native-settings").projectDir = gui.resolve("node_modules/capacitor-native-settings/android")
}
