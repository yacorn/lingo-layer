plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "dev.personal.screen_translator"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion
    testBuildType = if (project.findProperty("benchmark-release") == "true") "release" else "debug"
    project.findProperty("test-assets")?.let {
        sourceSets.getByName("androidTest").assets.setSrcDirs(listOf(file(it.toString())))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "dev.personal.screen_translator"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        testInstrumentationRunner = if (project.findProperty("release-service-check") == "true")
            "dev.personal.screen_translator.ReleaseServiceInstrumentation" else "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += if (project.findProperty("target-platform") == "android-x64") listOf("x86_64") else listOf("arm64-v8a") }
        externalNativeBuild {
            cmake { arguments += "-DANDROID_STL=c++_shared"; cppFlags += "-std=c++17"; targets += "screen_translate" }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources {
        noCompress += listOf("bin", "dic", "onnx", "onnx_data")
        // Exclude comparison weights if a benchmark downloads them again.
        ignoreAssetsPattern = "small100-int8:*.gguf:*.part"
    }

    buildTypes {
        release {
            proguardFiles("proguard-rules.pro")
            // R8 removes app-library classes needed only by instrumentation (e.g. androidx.tracing).
            if (project.findProperty("benchmark-release") == "true") {
                isMinifyEnabled = false
                isShrinkResources = false
            }
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
