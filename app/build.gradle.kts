import java.util.Properties

/**
 * Release signing, read from keystore.properties at the repo root.
 *
 * That file and the keystore it points at are both outside git - the keystore
 * is not even inside the repo directory. A fresh clone of this public repo has
 * neither, so the release build there simply comes out unsigned instead of
 * failing with a missing-file error nobody can act on.
 */
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.flinxsl.fitlog"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.flinxsl.fitlog"
        minSdk = 31
        targetSdk = 37

        // versionCode must STRICTLY INCREASE on every release you hand out.
        // Android refuses to install an APK whose code is lower than the one
        // already on the phone, and the only way out is uninstalling - which
        // wipes that phone's fitlog.json. Bump it before every build.
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // v1 is the old JAR signature and is dead weight above API 24.
                // v3 is what carries proof-of-rotation, so it is the scheme that
                // would let this key ever be replaced without every friend
                // uninstalling; it costs nothing to turn on now.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off, which is why the APK is ~22 MB rather than the
            // ~4 MB it would shrink to: almost all of that is unused Compose
            // and Material3 code sitting in classes.dex.
            //
            // That is a bad trade for an app on Play and a fine one here. The
            // APK is sideloaded once per friend, nobody is paying for the
            // download, and the failure mode of a wrong keep rule is a crash on
            // a phone in a gym with an obfuscated stack trace - not a build
            // error on this machine. Turn it on later if the size ever matters.
            optimization {
                enable = false
            }
            // Null when keystore.properties is absent, which leaves the APK
            // unsigned rather than breaking the build.
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}