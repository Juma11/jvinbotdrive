plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vintechafrica.jvinbuddy"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.vintechafrica.jvinbuddy"
        minSdk = 26
        targetSdk = 34
        // GitHub build number, so every build is newer than the last and shows on screen.
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = run
        versionName = "9.$run"
    }

    // Fixed debug key so every GitHub build installs over the last one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // MediaPipe reads its model straight from the APK; keep it uncompressed.
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Offline always-on ears. The model is downloaded by the GitHub workflow into assets.
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")

    // Offline hand gestures. The model is downloaded by the GitHub workflow into assets.
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}

// Android Studio builds: download the two models the GitHub workflow fetches.
// Skips when they already exist, so GitHub builds are unchanged.
val fetchModels by tasks.registering {
    doLast {
        val assets = file("src/main/assets").apply { mkdirs() }
        fun download(url: String, to: File) {
            val part = File(to.path + ".part")
            uri(url).toURL().openStream().use { i -> part.outputStream().use { i.copyTo(it) } }
            part.renameTo(to)
        }
        val gesture = File(assets, "gesture_recognizer.task")
        if (!gesture.exists()) {
            download("https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task", gesture)
        }
        val model = File(assets, "model-en-us")
        if (!File(model, "uuid").exists()) {
            val zip = layout.buildDirectory.file("vosk-model.zip").get().asFile.apply { parentFile.mkdirs() }
            if (!zip.exists()) download("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", zip)
            delete(model)
            copy {
                from(zipTree(zip)) { eachFile { path = path.substringAfter("/") } }
                includeEmptyDirs = false
                into(model)
            }
            File(model, "uuid").writeText("small-en-us-0.15\n")
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchModels) }
