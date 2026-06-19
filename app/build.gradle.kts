plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sshterminal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sshterminal"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = false
    }
}

dependencies {
    // AndroidX 核心
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // JSch：SSH 连接库
    implementation("com.jcraft:jsch:0.1.55")

    // Pty4J（伪终端库）通过反射加载，非编译期依赖。
    // 若设备支持（如 Termux 环境），只需将 pty4j + JNA 的 jar 放入 libs/ 即可，
    // LocalTerminalEmulator 会自动检测并使用。
    // 纯 Android 环境自动降级到纯 Java Pipe 方案。

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
