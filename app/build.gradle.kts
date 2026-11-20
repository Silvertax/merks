import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: environment variables (CI secrets) or a local, git-ignored keystore.properties
// with storeFile, storePassword, keyAlias and keyPassword.
val localKeys = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(env: String, key: String): String? = System.getenv(env) ?: localKeys.getProperty(key)
val ksPath: String? = secret("MERKS_KEYSTORE", "storeFile")

// F-Droid builds the app itself with `gradle -Pfdroid` and signs it with its own key. F-Droid may also
// want the text recognition (tesseract4android) built from its sources instead of taken from JitPack.
val fdroid = project.hasProperty("fdroid")

// Two versions of the app (flavors): "github", the APK on GitHub that updates itself, and "play", the
// bundle for Google Play (`gradle bundlePlayRelease`), which will get what only works there. Both have
// the same package name and are signed with the same key, so one installs over the other and the
// entries stay. A store brings the updates itself and accepts no app that installs its own: the play
// build (and the F-Droid one) leave out the update check against GitHub releases,
// REQUEST_INSTALL_PACKAGES and the "update" folder of the FileProvider (src/store). The provider itself
// stays: the camera writes the photo through it.

android {
    namespace = "de.silvertax.merks"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.silvertax.merks"
        minSdk = 26
        targetSdk = 36
        versionCode = 34
        versionName = "0.15.1"
        // Reading photos is native code. Phones need only these two; x86 devices run them translated.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "STORE", fdroid.toString())
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "STORE", "true")
        }
    }

    sourceSets {
        getByName("play") {
            manifest.srcFile("src/store/AndroidManifest.xml")
            res.srcDir("src/store/res")
        }
        if (fdroid) {
            maybeCreate("githubRelease").apply {
                manifest.srcFile("src/store/AndroidManifest.xml")
                res.srcDir("src/store/res")
            }
        }
    }

    // The app can switch its language itself (settings). Play would otherwise install only the
    // phone's language from the bundle, and the other one would be missing.
    bundle {
        language { enableSplit = false }
    }

    packaging {
        // Packed in the APK: about 7 MB less to download. Android unpacks them once when installing.
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures {
        buildConfig = true
    }

    // No encrypted block of dependency data for Google in the APK; F-Droid asks for this.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = rootProject.file(ksPath)
                storePassword = secret("MERKS_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = secret("MERKS_KEY_ALIAS", "keyAlias")
                keyPassword = secret("MERKS_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = when {
                ksPath != null -> signingConfigs.getByName("release")
                fdroid -> null // F-Droid expects an unsigned APK
                else -> signingConfigs.getByName("debug")
            }
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
        // Chromebooks with Intel chips run the ARM libraries translated; x86 copies would add 7 MB for everyone.
        disable += "ChromeOsAbiSupport"
    }
    testOptions {
        // Robolectric runs the app's screens and receivers on the JVM and needs the resources.
        unitTests.isIncludeAndroidResources = true
    }
}

// An APK signed with the debug key does not install over the real app. Say so loudly.
gradle.taskGraph.whenReady {
    if (ksPath == null && !fdroid && allTasks.any { it.path == ":app:assembleGithubRelease" || it.path == ":app:bundlePlayRelease" }) {
        logger.warn("WARNING: no release key found; app-release.apk is signed with the DEBUG key and will not update an installed Merks.")
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
    // Reads the text on a photo, on the phone: Tesseract (Apache 2.0) with Leptonica (BSD).
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // Must match the Robolectric version below and the sdk in src/test/resources/robolectric.properties.
    robolectricRuntime("org.robolectric:android-all-instrumented:15-robolectric-12650502-i7")
    testImplementation("junit:junit:4.13.2")
    // The Android stubs of org.json do nothing in unit tests; this is the real thing for them.
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
