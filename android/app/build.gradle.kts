plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yg.engine"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yg.engine"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // rootfs 必须**不压缩**：aapt 压过的 asset 无法 openFd()，且解压时还要二次解压
    androidResources {
        noCompress += listOf("xz")
    }

    // proot 及依赖必须以 lib*.so 形式打包，安装时才会被解到 nativeLibraryDir 且可执行
    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/*.kotlin_module") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // 自用：debug 签名
            // 不想把 rootfs 打进 APK 时可以换成 assets 里不放（见 README）
        }
    }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("androidx.core:core-ktx:1.15.0")
}
