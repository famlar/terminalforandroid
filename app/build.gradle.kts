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
        viewBinding = true
    }

    packaging {
        // Pty4J 和 JNA 带来的重复文件处理
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // AndroidX 核心
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // JSch：SSH 连接库
    implementation("com.jcraft:jsch:0.1.55")

    // Pty4J：伪终端库
    // 注意：Pty4J 依赖 JNA，在纯 Android 环境可能受限
    // 备选方案见 LocalTerminalEmulator.kt 中的注释
    implementation("org.jetbrains.pty4j:pty4j:0.12.18")

    // JNA (Pty4J 的依赖)
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
