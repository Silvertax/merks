plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (set locally or as CI secrets).
val ksPath: String? = System.getenv("MERKS_KEYSTORE")

android {
    namespace = "de.silvertax.merks"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.silvertax.merks"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.1"
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("MERKS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MERKS_KEY_ALIAS")
                keyPassword = System.getenv("MERKS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (ksPath != null) "release" else "debug")
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
        checkReleaseBuilds = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The Android stubs of org.json do nothing in unit tests; this is the real thing for them.
    testImplementation("org.json:json:20240303")
}
