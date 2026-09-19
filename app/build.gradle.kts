plugins {
    id("com.android.application")
}

android {
    namespace = "com.iamcanincan.noticon"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.iamcanincan.noticon"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // 开启 R8：裁掉未使用的 Kotlin 标准库，显著缩小 APK 并合并 dex
            isMinifyEnabled = true
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
}

dependencies {
    // LibXposed 现代 API（API 102），仅编译期依赖，运行时由支持该 API 的框架提供
    compileOnly("io.github.libxposed:api:102.0.0")
    // 自身打包进 APK 的第三方库（框架不提供）
    implementation("androidx.core:core:1.12.0")
    compileOnly("androidx.annotation:annotation:1.7.1")
}
