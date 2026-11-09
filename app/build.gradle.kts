import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myembyhub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myembyhub"
        // 目标电视是乐视 S50 Air：Android 4.3 / API 18 → minSdk 只能到 18
        minSdk = 18
        // targetSdk 保持 23：API 24+ 在 4.3 系统上会引入未预期的行为变化
        targetSdk = 23
        multiDexEnabled = true

        // versionCode 用**秒级时间戳**：
        // ① 永远递增 → 覆盖安装不会被系统以「版本号相同」拒绝；
        // ② 和在线更新清单里的 versionCode 直接可比，不用维护手工版本表。
        val stamp = System.currentTimeMillis()
        versionCode = (stamp / 1000L).toInt()
        versionName = "1.0.${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date(stamp))}"
    }

    buildTypes {
        // ====================================================================
        // 为什么要给 **debug** 也开 minify
        //
        // 这个项目的交付物是 `dist/myembyhub-debug.apk`（在线更新清单里写的就是
        // 这个名字），也就是说装到电视上的就是 debug 包。只给 release 开混淆，
        // 对电视上真正跑的那个 APK 一点用都没有。
        //
        // 开 minify 在这里的目的不是防反编译，而是**压 dex 体积**：
        // 未混淆时 APK 里有 3 个 dex（3.41MB / 9.58MB / 2.39MB），
        // 装机后 dalvik 要对每个 secondary dex 做 unzip + dexopt ——
        // 模拟器上 classes2 单个就要 unzip 317ms + rewrite 455ms，真机更慢。
        // 这直接影响「首次启动」和「每次覆盖安装后的第一次启动」。
        //
        // 需要快速迭代、不想等 R8 时：gradlew :app:assembleDebug -PnoMinify
        // ====================================================================
        val minify = !project.hasProperty("noMinify")

        debug {
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        // ⚠️ 必须显式开启：**AGP 8.0 起 BuildConfig 不再自动生成**。
        //    没有它 → `com.example.myembyhub.BuildConfig` 运行时不存在 →
        //    反射读 VERSION_CODE 抛 ClassNotFoundException → 兜底返回 0 →
        //    在线更新会永远认为「有新版本」。
        buildConfig = true
    }
}

dependencies {
    // UI 只能走 XML/Views：Compose 要求 minSdk 21，这台电视装不了。
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.material)
    implementation(libs.androidx.multidex)

    // 播放：ExoPlayer 2.19.1 是最后一个支持 API 18 的版本（media3 要求 21）
    implementation(libs.exoplayer.core)
    implementation(libs.exoplayer.ui)

    // 二维码生成（纯 Java 库，API 18 可用）
    implementation(libs.zxing.core)
}

// ============================================================================
// 在线更新：打包后自动生成 dist/version.json
//
// 电视上的 App 请求这个文件，和自己的 versionCode 比较；
// 大于就下载 APK 并调起系统安装器。
// 电脑上用 `python -m http.server 8765` 把 dist/ 目录发出去即可。
//
// 换网络时改这里的 updateHost（或用 -PupdateHost=host:port 覆盖）：
//   gradlew :app:assembleDebug -PupdateHost=192.168.1.20:8765
// ============================================================================
val updateHost = (project.findProperty("updateHost") as String?) ?: "192.168.1.50:8765"

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apk.exists()) return@doLast
        val code = android.defaultConfig.versionCode
        val name = android.defaultConfig.versionName
        val out = rootProject.file("dist/version.json")
        out.parentFile.mkdirs()
        out.writeText(
            """
            {
              "versionCode": $code,
              "versionName": "$name",
              "url": "http://$updateHost/myembyhub-debug.apk",
              "size": ${apk.length()},
              "notes": "TV 客户端：扫码登录 / 搜索 / 排序 / 在线更新"
            }
            """.trimIndent()
        )
        logger.lifecycle("已生成在线更新清单: ${out.absolutePath}")
    }
}

kotlin {
    // AGP 9 内置 Kotlin 扩展，jvmTarget 跟随 compileOptions
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
