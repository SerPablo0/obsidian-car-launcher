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
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
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
}
