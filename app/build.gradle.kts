import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val modelFileName = providers.gradleProperty("morrow.modelFile").getOrElse("functiongemma-270m-it-q8_0.gguf")

android {
    namespace = "dev.morrow"
    compileSdk = 37
    ndkVersion = "29.0.14033849"

    defaultConfig {
        applicationId = "dev.morrow"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            // 64-bit phones are the target (every Play-capable device since 2019);
            // x86_64 keeps the same APK runnable on emulators and Chromebooks.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DBUILD_SHARED_LIBS=OFF",
                )
                cppFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The GGUF must be byte-identical on disk so the SHA-256 check holds.
        noCompress += "gguf"
        localeFilters += setOf("en", "fr")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

/**
 * The model is part of the deliverable: `./gradlew assembleDebug` produces an APK
 * that works on a fresh phone with no download step. The file is not committed
 * to git; this task fetches it once and verifies the SHA-256 the app checks at
 * runtime.
 */
val modelDir = layout.projectDirectory.dir("src/main/assets/models")

/**
 * The model is part of the deliverable: `./gradlew assembleDebug` produces an APK
 * that works on a fresh phone with no download step. The file is not committed to
 * git; this task fetches it once and verifies the checksum the app checks at
 * runtime (ModelPack.Info.SHA256).
 */
val fetchModel = tasks.register<Exec>("fetchModel") {
    val script = rootProject.layout.projectDirectory.file("scripts/fetch_model.sh").asFile
    val target = modelDir.file(providers.gradleProperty("morrow.modelFile").getOrElse("functiongemma-270m-it-q8_0.gguf")).asFile
    workingDir = rootProject.projectDir
    // The verification stamp lives outside res/assets so it never ships.
    environment("MORROW_MODEL_STAMP", layout.buildDirectory.file("model-verified.stamp").get().asFile.absolutePath)
    commandLine("bash", script.absolutePath, target.absolutePath)
    inputs.file(script)
}

tasks.named("preBuild") { dependsOn(fetchModel) }
