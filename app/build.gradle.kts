plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.obsidian.carlauncher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.obsidian.carlauncher"
        // The head unit runs Android 10 (API 29) with a 32-bit userspace.
        minSdk = 29
        // Below 26 on purpose: Android only lets apps targeting pre-O use system-alert window
        // types other than TYPE_APPLICATION_OVERLAY, and the bar needs NWD's reserved type 2407.
        // The stock NWD bar does the same (targetSdkVersion 19).
        targetSdk = 25
        versionCode = 26
        versionName = "0.13.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs directly; this app is never published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        // NewPipeExtractor uses Java library APIs newer than Android 10 has.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // targetSdk 29 is intentional: it matches the device exactly.
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Pure-Kotlin ADB client, used to reach the head unit's root adbd over loopback.
    implementation("dev.mobile:dadb:2.0.0")
    // Searches YouTube Music and finds a song's audio stream for the Music screen (GPL-3.0).
    // Pinned to the same commit the NewPipe app ships, which keeps up with YouTube changes.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:e8f89145a830b4d6cae32412007f24ea8b36c73a")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
