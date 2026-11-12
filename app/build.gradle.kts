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
        versionCode = 8
        versionName = "0.4.0"
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
    testOptions {
        // Robolectric runs the app's screens and receivers on the JVM and needs the resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// Robolectric needs a large Android jar. Gradle fetches it like any dependency (cached, with retries)
// instead of Robolectric downloading it during the tests, which can fail a release build.
val robolectricRuntime: Configuration by configurations.creating
val robolectricDeps = layout.buildDirectory.dir("robolectric-deps")
val copyRobolectricDeps by tasks.registering(Copy::class) {
    from(robolectricRuntime)
    into(robolectricDeps)
}
tasks.withType<Test>().configureEach {
    dependsOn(copyRobolectricDeps)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDeps.get().asFile.absolutePath)
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    // Must match the Robolectric version below and the sdk in src/test/resources/robolectric.properties.
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation("junit:junit:4.13.2")
    // The Android stubs of org.json do nothing in unit tests; this is the real thing for them.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
