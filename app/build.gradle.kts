import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------- release signing
// Resolution order: environment (CI secrets) -> signing.properties (local, git-ignored).
// Without credentials the release build is signed with the debug key so it still installs.
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("KEYSTORE_PATH", "storeFile") ?: "keystore/polylingua-release.p12"
val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias") ?: "polylingua"
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword") ?: releaseStorePassword
val hasReleaseSigning = releaseStorePassword != null && rootProject.file(releaseStorePath).exists()

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val appVersionCode = ciRunNumber ?: 1
val minifyRelease = (project.findProperty("polylingua.minify") as String?)?.toBoolean() ?: false

android {
    namespace = "com.polylingua.edge"
    compileSdk = 34
    ndkVersion = (project.findProperty("polylingua.ndkVersion") as String?) ?: "27.2.12479018"

    defaultConfig {
        applicationId = "com.polylingua.edge"
        minSdk = 30          // Android 11 (POCO M4 Pro 4G ships MIUI 13 on Android 11)
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // MediaTek Helio G96 is ARMv8.2-A 64-bit: ship arm64-v8a only.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_ARM_NEON=TRUE",
                    "-DCMAKE_BUILD_TYPE=Release",
                )
                cppFlags += listOf("-std=c++17", "-O3", "-march=armv8.2-a+dotprod+fp16", "-mtune=cortex-a76")
                cFlags += listOf("-O3", "-march=armv8.2-a+dotprod+fp16", "-mtune=cortex-a76")
                targets += "polylingua_native"
            }
        }
        buildConfigField("String", "SHERPA_ONNX_VERSION", "\"1.13.8\"")
        buildConfigField("String", "LLAMA_CPP_TAG", "\"b12000\"")
        buildConfigField("String", "GITHUB_REPO", "\"firzan04/PolyLingua\"")
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                storeType = "PKCS12"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            ndk { debugSymbolLevel = "NONE" }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        prefab = true   // Oboe C++ library via Prefab
    }

    packaging {
        jniLibs {
            // Compressed .so files keep the sideloaded download ~20 MB smaller.
            useLegacyPackaging = true
            // AGP may package the CMake IMPORTED prebuilts in addition to src/main/jniLibs.
            pickFirsts += listOf(
                "**/libonnxruntime.so",
                "**/libsherpa-onnx-c-api.so",
                "**/libc++_shared.so",
                "**/liboboe.so",
            )
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
                "/META-INF/*.kotlin_module",
            )
        }
    }

    androidResources {
        noCompress += listOf("onnx", "gguf")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":tarsosdsp"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    // Offline (bundled-model) face detection for the video interview's body-language report
    implementation(libs.mlkit.face.detection)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.oboe)
    implementation(libs.webrtc)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
