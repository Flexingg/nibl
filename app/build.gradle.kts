plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

import java.util.Properties

// The release signing key is never in the repository: CI gets it from GitHub secrets (as environment variables),
// a developer's machine from an untracked keystore.properties next to this project
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}
fun signing(env: String, prop: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProperties.getProperty(prop)
val releaseStoreFile: File? = signing("NIBL_KEYSTORE_FILE", "STORE_FILE")?.let { project.file(it) }
    ?: rootProject.file("keystore/release.jks").takeIf { it.exists() }
val hasReleaseKey = releaseStoreFile?.exists() == true && signing("NIBL_STORE_PASSWORD", "STORE_PASSWORD") != null

android {
    namespace = "com.bytelog.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bytelog.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 68
        versionName = "1.39.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = releaseStoreFile
            storePassword = signing("NIBL_STORE_PASSWORD", "STORE_PASSWORD")
            keyAlias = signing("NIBL_KEY_ALIAS", "KEY_ALIAS")
            keyPassword = signing("NIBL_KEY_PASSWORD", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 removes the library code the app never uses (most of the APK); the app's own classes are kept whole
            isMinifyEnabled = true
            isShrinkResources = true
            // Without the key (a fork, a fresh clone) the release build is unsigned rather than failing
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // Signed like releases when the key is here, so a debug build updates in place; else Android's debug key
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric tests (Room migration, Compose screens) need resources and the merged manifest.
            isIncludeAndroidResources = true
            all { test ->
                // ./gradlew testDebugUnitTest -Pbytelog.screenshots=/some/dir saves screen renders as PNGs.
                project.findProperty("bytelog.screenshots")?.let { test.systemProperty("bytelog.screenshots", it) }
                // Optional live MCP server for McpClientTest (-Pbytelog.mcp.url=… -Pbytelog.mcp.token=…).
                project.findProperty("bytelog.mcp.url")?.let { test.systemProperty("bytelog.mcp.url", it) }
                project.findProperty("bytelog.mcp.token")?.let { test.systemProperty("bytelog.mcp.token", it) }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.haze)
    implementation(libs.androidx.health.connect)
    debugImplementation(libs.androidx.ui.tooling)

    // Coroutines & Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // CameraX & ML Kit
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.mlkit.text)
    implementation(libs.guava)

    // DataStore & WorkManager
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)

    // Recipe import (Schema.org JSON-LD / microdata)
    implementation(libs.jsoup)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
