import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// ============================================================================
// 在线更新的两个地址（必须先于 android{} 求值，defaultConfig 里要用）
//
// 换网络 / 换仓库时改这里，优先级：
//   ① 命令行 -PupdateRepo=owner/repo、-PupdateHost=host:port
//   ② 本机 local.properties 里的同名键（**已 gitignore**，真实地址不进仓库）
//   ③ 下面的默认值（占位符）
// ============================================================================
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val updateRepo = (project.findProperty("updateRepo") as String?)
    ?: localProps.getProperty("updateRepo")
    ?: ""
val updateHost = (project.findProperty("updateHost") as String?)
    ?: localProps.getProperty("updateHost")
    ?: "192.168.1.50:8765"

/**
 * 设备反馈服务器地址（如 `https://feedback.example.com`）。
 *
 * 反馈服务端**不在这个公开仓库里**（它是给作者自己部署的，放在平级目录
 * `MyEmbyHub-feedback`），所以这里默认空串 —— 公开构建出来的包没有反馈服务，
 * 界面上会提示「没有配置反馈服务器」，用户可以在设置里自己填。
 *
 * 自己用的时候写在 `local.properties` 里（已 gitignore）：
 * `feedbackUrl=https://你的域名`
 */
val feedbackUrl = (project.findProperty("feedbackUrl") as String?)
    ?: localProps.getProperty("feedbackUrl")
    ?: ""

/**
 * APK 的下载地址。
 *
 * `releases/latest/download/<资产名>` **不带 tag**，永远指向最新一个正式 Release
 * 里的同名资产 → 这个地址跨版本是固定的，客户端和清单都不用改。
 * 没有配仓库时退回本机静态服务，方便局域网调试。
 */
val apkDownloadUrl = if (updateRepo.isBlank()) {
    "http://$updateHost/myembyhub-debug.apk"
} else {
    "https://github.com/$updateRepo/releases/latest/download/myembyhub-debug.apk"
}

/**
 * 清单地址：配了仓库就走 GitHub Release 的固定资产地址，否则退回本机静态服务。
 *
 * 把整条 URL 烤进 BuildConfig，而不是在 Kotlin 里拼 ——
 * 这样 `updateHost` 的来源（命令行 / local.properties / 默认值）只有 Gradle 一处逻辑。
 */
val manifestUrl = if (updateRepo.isBlank()) {
    "http://$updateHost/version.json"
} else {
    "https://github.com/$updateRepo/releases/latest/download/version.json"
}

// ============================================================================
// 版本号：`1.0.yyyyMMdd.N`
//
// N 是**当天第几次打包**，从 1 起。一天要给电视推好几个版本是常态
// （改个焦点、调个间距都值得装一次看），只写到日期就没法区分了。
//
// 序号存在根目录的 `.version-seq`（已 gitignore），格式 `20261117=3`。
// 换台机器编号会从头开始 —— 无所谓，真正决定「谁更新」的是 versionCode（秒级时间戳），
// versionName 只是给人看的。发版脚本也拿它当 tag：`v` + versionName。
//
// ⚠️ 只有真的在打包时才推进序号：跑单元测试、IDE 同步都不该把当天的号吃掉。
// ============================================================================
val buildStamp = System.currentTimeMillis()
val versionDate = SimpleDateFormat("yyyyMMdd").format(Date(buildStamp))
val packaging = gradle.startParameter.taskNames.any { it.contains("assemble", ignoreCase = true) }
val seqFile = rootProject.file(".version-seq")
val seqPrev = (if (seqFile.exists()) seqFile.readText().trim() else "")
    .split("=")
    .let { if (it.size == 2 && it[0] == versionDate) it[1].toIntOrNull() ?: 0 else 0 }
val versionSeq = if (packaging) (seqPrev + 1).coerceAtLeast(1) else seqPrev.coerceAtLeast(1)
if (packaging) seqFile.writeText("$versionDate=$versionSeq")
val versionFull = "1.0.$versionDate.$versionSeq"

android {
    namespace = "work.luxiaoxiao.myembyhub"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "work.luxiaoxiao.myembyhub"
        // 目标电视是乐视 S50 Air：Android 4.3 / API 18 → minSdk 只能到 18
        minSdk = 18
        // targetSdk 保持 23：API 24+ 在 4.3 系统上会引入未预期的行为变化
        targetSdk = 23
        multiDexEnabled = true

        // GitHub 仓库（`owner/repo`），在线更新的主来源。
        // 取值的优先级见文件末尾 updateRepo 那段；空串表示「不用 GitHub」，
        // UpdateManager 会退回本机静态服务（局域网调试用）。
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        // 版本清单的完整地址（同样烤进来，避免 Kotlin 侧再拼一遍）
        buildConfigField("String", "UPDATE_MANIFEST", "\"$manifestUrl\"")

        // 设备反馈服务器（空 = 这个包没配反馈服务）
        buildConfigField("String", "FEEDBACK_URL", "\"$feedbackUrl\"")

        // versionCode 用**秒级时间戳**：
        // ① 永远递增 → 覆盖安装不会被系统以「版本号相同」拒绝；
        // ② 和在线更新清单里的 versionCode 直接可比，不用维护手工版本表。
        //
        // versionName 是给人看的 `1.0.yyyyMMdd.N`（N = 当天第几次打包），
        // 见文件开头 buildStamp / versionSeq 那段。
        versionCode = (buildStamp / 1000L).toInt()
        versionName = versionFull
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
        //    没有它 → `work.luxiaoxiao.myembyhub.BuildConfig` 运行时不存在 →
        //    反射读 VERSION_CODE 抛 ClassNotFoundException → 兜底返回 0 →
        //    在线更新会永远认为「有新版本」。
        buildConfig = true
    }

    testOptions {
        unitTests {
            // 单元测试跑在 JVM 上，android.jar 里的方法全是桩（抛
            // "not mocked" RuntimeException）。返回默认值而不是抛异常，
            // 纯逻辑代码（UrlKit 等）就能直接跑。
            isReturnDefaultValues = true
        }
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

    // 单元测试：目前只有 UrlKit 的回归测试（手写 URL / 重定向解析）
    testImplementation(libs.junit)
}

// ============================================================================
// 在线更新：打包后自动生成 dist/version.json
//
// 电视上的 App 请求这个文件，和自己的 versionCode 比较；
// 大于就下载 APK 并调起系统安装器。
//
// 两个地址怎么来的见文件开头 updateRepo / updateHost 那段。
// 发版流程：把 dist/myembyhub-debug.apk 和 dist/version.json **都**上传到
// 同一个 GitHub Release（资产名不能改），客户端就能在线更新了。
// 见 tools/publish-release.ps1。
// ============================================================================
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
              "url": "$apkDownloadUrl",
              "size": ${apk.length()},
              "notes": "TV 客户端：扫码登录 / 搜索 / 排序 / 在线更新"
            }
            """.trimIndent()
        )
        logger.lifecycle("已生成在线更新清单: ${out.absolutePath}（下载地址 $apkDownloadUrl）")
    }
}

kotlin {
    // AGP 9 内置 Kotlin 扩展，jvmTarget 跟随 compileOptions
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
