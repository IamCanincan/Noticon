plugins {
    id("com.android.application")
    // AGP 9 内置 Kotlin，不需要再声明 org.jetbrains.kotlin.android；
    // 但 Compose 编译器是独立的 Kotlin 编译器插件，必须显式声明，版本跟随 Kotlin 版本。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}

android {
    namespace = "com.iamcanincan.noticon"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.iamcanincan.noticon"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // 开启 R8：裁掉未使用的 Kotlin 标准库，显著缩小 APK 并合并 dex
            isMinifyEnabled = true
            // 顺带裁掉用不到的资源（注意：只作用于 Android res，不影响 META-INF 下的 xposed 元数据）
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
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
    implementation("androidx.core:core-ktx:1.17.0")

    // 设置界面：Material 3 Expressive。
    // 注意版本：MD3E 的公开 API（MaterialExpressiveTheme / MotionScheme.expressive）
    // 在 1.4.0 稳定版里还是 internal，只有 1.5.0-alpha 起才对外公开，所以这里必须用 alpha。
    implementation("androidx.compose.material3:material3:1.5.0-alpha28")
    implementation("androidx.activity:activity-compose:1.13.0")
}

// AGP 9.4.1 给 release 构建准备 Compose mapping 时会去要
// org.jetbrains.kotlin:compose-group-mapping:2.2.10 —— 这个版本从未发布过
// （公开仓库里该构件只从 2.3.0 起存在），而且它只去 dl.google.com 找，
// 本机 DNS 解析不了那个域名，release 构建会直接失败。
// 强制到与 Kotlin 版本一致的 2.4.20 即可。
configurations.configureEach {
    if (name == "composeMappingProducerClasspath") {
        resolutionStrategy.force("org.jetbrains.kotlin:compose-group-mapping:2.4.20")
    }
}
