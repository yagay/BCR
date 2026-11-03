plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.github.nicorac.bcrgui"
    compileSdk = 37
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            // Compile the original fork's sources, never a copied fork.
            java.srcDir("../../external/bcr-gui/android/app/src/main/java")
            res.srcDir("../../external/bcr-gui/android/app/src/main/res")
            assets.srcDir("../../external/bcr-gui/android/app/src/main/assets")
        }
    }
}

dependencies {
    implementation(project(":capacitor-android"))
    implementation(project(":capacitor-community-contacts"))
    implementation(project(":capacitor-app"))
    implementation(project(":capacitor-clipboard"))
    implementation(project(":capacitor-device"))
    implementation(project(":capacitor-filesystem"))
    implementation(project(":capacitor-preferences"))
    implementation(project(":capacitor-share"))
    implementation(project(":capacitor-status-bar"))
    implementation(project(":capgo-capacitor-navigation-bar"))
    implementation(project(":kristianheljas-capacitor-app-restart"))
    implementation(project(":capacitor-native-settings"))
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
}
