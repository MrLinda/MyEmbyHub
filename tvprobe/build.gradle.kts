plugins {
    alias(libs.plugins.android.application)
}

// 零依赖模块：不引入任何 AndroidX / Compose，minSdk 尽量压低，
// 目的是让 Android 4.x ~ 6.x 的老电视（如乐视 S50 Air EUI8）也能安装。
android {
    namespace = "com.example.tvprobe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.tvprobe"
        // 压到 16（Android 4.0.3），覆盖乐视 EUI3/EUI8 这类 Android 4.x~6.x 老电视
        minSdk = 16
        // 默认 23；部分厂商安装器对 targetSdk 有额外校验时，用 -PtvTargetSdk=16 打包
        targetSdk = ((project.findProperty("tvTargetSdk") as String?)?.toInt() ?: 23)
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// 纯 Java 模块，不需要 Kotlin stdlib；去掉后 dex 从 2.3MB 降到几十 KB，
// 老电视解析 dex 更快，也避免不必要的类加载。
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
