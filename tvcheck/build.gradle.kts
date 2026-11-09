import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    // AGP 9 内置 Kotlin 支持，.kt 源码直接编译，无需再挂 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
}

// 可行性验证模块：minSdk 18（乐视 S50 Air 实测 Android 4.3 / API 18）
// 关键约束：这台机器没有 WebView、没有 GMS、只支持 TLS 1.x（需显式开启 1.1/1.2），
// 所以只能用 ExoPlayer 2.x（media3 要求 API 21）+ 原生 View，不能用 Compose。
android {
    namespace = "com.example.tvcheck"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.tvcheck"
        minSdk = 18
        targetSdk = 23
        multiDexEnabled = true

        // 每次打包自动带时间戳版本号，方便确认电视上装的是哪一版；
        // versionCode 用秒级时间戳，保证永远递增 → 覆盖安装不会被系统拦。
        val stamp = System.currentTimeMillis()
        versionCode = (stamp / 1000L).toInt()
        versionName = "1.0.${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(stamp))}"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        // 需要 BuildConfig 承载「默认服务器地址」
        buildConfig = true
    }

    testOptions {
        unitTests {
            // 让 android.util.Log 等在 JVM 单测里不报错
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// ============================================================================
// 服务器地址有三种填法，按优先级从高到低：
//   1) 电视上 /sdcard/tvcheck.conf 里的 server=...   （改完插 U 盘即可，不用重新打包）
//   2) 打包时命令行参数 -PtvServerUrl=https://xxx    （推荐，地址会烤进 APK）
//   3) 电视上手动输入                                （4.3 电视没有软键盘，最难用）
// 例：gradlew :tvcheck:assembleDebug -PtvServerUrl=https://demo.example.com
// ============================================================================
val defaultServerUrl = (project.findProperty("tvServerUrl") as String?) ?: ""

android {
    defaultConfig {
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$defaultServerUrl\"")
    }
}

kotlin {
    // AGP 9 内置 Kotlin 扩展：jvmTarget 跟随 compileOptions
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.multidex)
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.hls)
    implementation(libs.exoplayer.dash)
    implementation(libs.exoplayer.ui)

    // 注意：这里刻意**不引入** Bouncy Castle / Conscrypt。
    // 2026-11-03 真机实测：4.3 的系统 JSSE 用 TLSv1.2 + 内置锚点握手成功，
    // 系统栈完全够用；BC 在 API 18 上反而不可用（JSSE 类在 META-INF/versions/9/，D8 取不到）。

    testImplementation(libs.junit)
}
