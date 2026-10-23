import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.falseenvironment.jmapjolt"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }

    defaultConfig {
        applicationId = "com.falseenvironment.jmapjolt"
        minSdk = 24
        targetSdk = 36
        versionCode = 16
        versionName = "0.25"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // SQLCipher is the only native dependency; it ships .so files for every ABI below.
    // One APK per architecture instead of a universal one: each build carries a single
    // libsqlcipher.so, so adding 32-bit ARM support costs nothing to 64-bit users.
    // (ndk.abiFilters must stay unset — AGP rejects it alongside splits, and the
    // include list below already keeps x86/x86_64 out of the outputs.)
    splits {
        abi {
            isEnable = true
            reset()
            // -PemulatorAbi adds x86_64 for the screenshot emulator only; shipped
            // builds never pass it, so the released outputs stay ARM-only.
            if (project.hasProperty("emulatorAbi")) {
                include("arm64-v8a", "armeabi-v7a", "x86_64")
            } else {
                include("arm64-v8a", "armeabi-v7a")
            }
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/ASL2.0",
                "META-INF/*.kotlin_module"
            )
        }
    }

    testOptions {
        // Unit tests exercise WebPushKeys' crypto path, which logs via android.util.Log;
        // without this every stubbed Android call throws instead of returning a default.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.security:security-crypto:1.1.0")

    // Offline email cache: Room over an encrypted (SQLCipher) database.
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.1")

    implementation("rs.ltt.jmap:jmap-client:0.9.0")
    // jmap-client 0.9.x declares Guava as 'provided' (no longer transitive) but uses it
    // throughout at runtime; the -android flavor avoids JRE-only classes under R8.
    implementation("com.google.guava:guava:33.7.2-android")
    implementation("com.github.UnifiedPush:android-connector:3.0.10")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    // HTML parser behind the email body sanitizer (MIT).
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation("junit:junit:4.13.2")
    // The Android org.json stub is not implemented in local unit tests; use the real
    // implementation so payload parsers (EmailPush, ...) can be tested on the JVM.
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

// UnifiedPush pulls the JRE Tink, security-crypto pulls tink-android. Both ship the same
// classes, so keep only the Android variant (it also carries the Android Keystore
// integration security-crypto needs) and align both on the version UnifiedPush asks for.
configurations.configureEach {
    exclude(group = "com.google.crypto.tink", module = "tink")
    resolutionStrategy.force("com.google.crypto.tink:tink-android:1.17.0")
}
