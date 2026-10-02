plugins {
    id("com.android.application")
    id("com.chaquo.python")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yingjie.app"
    compileSdk = 35
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.yingjie.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.8.8"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("yt-dlp")
            // 国内网络访问 PyPI 不稳定，固定用清华镜像
            options("--index-url", "https://pypi.tuna.tsinghua.edu.cn/simple")
        }
    }
}
