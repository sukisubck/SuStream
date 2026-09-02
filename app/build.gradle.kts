import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Local development configuration.
 *
 * `local.properties` is git-ignored and is the ONLY place a real credential may live during local
 * development. In production these values are not shipped in the APK at all: the app talks to the
 * backend, which holds the secrets (see docs/SECURITY.md).
 *
 * Resolution order: local.properties -> environment variable -> empty placeholder.
 * An empty value is legal: the app falls back to mock data and tells the user why.
 */
val localProperties: Properties = Properties().apply {
    val text = providers
        .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
        .asText
        .orNull
    if (text != null) load(text.reader())
}

fun devConfig(key: String, fallback: String = ""): String =
    localProperties.getProperty(key)
        ?: providers.environmentVariable(key).orNull
        ?: fallback

android {
    namespace = "com.sustream.tv"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sustream.tv"
        // API 24 is the floor set by androidx.navigation 2.10. It costs nothing on the target
        // devices: Fire OS 6 is Android 7.1 (API 25), Fire OS 7 is API 28 and Fire OS 8 is API 32,
        // so every Fire TV Stick from the 2nd generation onward is still in scope. Only
        // end-of-life Fire OS 5 (API 22) is excluded. java.time is available down to this level
        // through core library desugaring, configured below.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ---- Configuration, not secrets -------------------------------------
        buildConfigField("String", "TMDB_BASE_URL", "\"https://api.themoviedb.org/3/\"")
        buildConfigField("String", "TMDB_IMAGE_FALLBACK_BASE_URL", "\"https://image.tmdb.org/t/p/\"")
        buildConfigField("String", "TORBOX_BASE_URL", "\"https://api.torbox.app/v1/api/\"")
        buildConfigField(
            "String",
            "BACKEND_BASE_URL",
            "\"${devConfig("BACKEND_BASE_URL", "https://api.sustream.example/v1/")}\"",
        )

        // ---- Development-only credentials -----------------------------------
        // Empty in CI and in any checkout without a local.properties. Never read in release
        // builds: see core/config/AppConfig.kt, which ignores these unless BuildConfig.DEBUG.
        buildConfigField(
            "String",
            "DEV_TMDB_READ_TOKEN",
            "\"${devConfig("TMDB_READ_ACCESS_TOKEN")}\"",
        )
        buildConfigField("String", "DEV_TMDB_API_KEY", "\"${devConfig("TMDB_API_KEY")}\"")
        buildConfigField("String", "DEV_TORBOX_API_KEY", "\"${devConfig("TORBOX_API_KEY")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            optimization {
                enable = true
                keepRules {
                    // Consumer keep rules from libraries are honoured; ours live in
                    // src/main/keepRules/rules.keep and proguard-rules.pro.
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Release builds must be signed with a real keystore supplied out of band; see README.
            signingConfig = signingConfigs.findByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // Enables java.time, java.util.stream and friends down to minSdk 24.
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/DEPENDENCIES",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf(
            // Compose-for-TV intentionally uses non-Material3 components.
            "UnusedMaterial3ScaffoldPaddingParameter",
        )
        baseline = file("lint-baseline.xml")
    }
}

// Room schema history is checked in so migrations are reviewable.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ---- AndroidX core -----------------------------------------------------
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    // ---- Compose / Compose for TV ------------------------------------------
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // ---- Persistence -------------------------------------------------------
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // ---- Networking --------------------------------------------------------
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    debugImplementation(libs.okhttp.logging.interceptor)

    // ---- Playback ----------------------------------------------------------
    implementation(libs.media3.common)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    // ---- Unit tests --------------------------------------------------------
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.junit)

    // ---- Instrumented tests ------------------------------------------------
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.mockk.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
