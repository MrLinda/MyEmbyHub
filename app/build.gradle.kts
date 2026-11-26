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

// ============================================================================
// 发布签名（release keystore）
//
// ⚠️ **不要用 debug.keystore 发版**。AGP 在 `~/.android/debug.keystore` 里随机生成
// 一个，换机器 / 重装系统 / 删掉它 → 新包签名就变了 → 电视上覆盖安装直接失败
// （INSTALL_FAILED_UPDATE_INCOMPATIBLE），只能卸载重装，登录态全丢。
// 而且 debug 签名必然带 `android:debuggable=true`，任何能 adb 连上电视的人
// 都能 `run-as <包名>` 读走 SharedPreferences 里的 Emby token。
//
// 密钥库位置：**仓库外的平级目录** `MyEmbyHub-keys/`（仓库是公开的，密钥不能进）。
// 四个值都从 local.properties 读（该文件已 gitignore）：
//   storeFile=...\myembyhub.jks
//   storePassword=...
//   keyAlias=myembyhub
//   keyPassword=...
//
// 读不到就退回 debug 签名并打警告 —— 让「克隆下来随手编译」仍然可用，
// 但发版脚本会因为这个警告 + 签名校验而拦住（见 tools/publish-release.ps1）。
// ============================================================================
// ⚠️ 变量名必须带前缀（rel*）：在 `signingConfigs { create("release") { ... } }`
// 里面，接收者自己有 `storeFile` / `storePassword` / `keyAlias` / `keyPassword`
// 四个属性，写成 `storePassword = storePassword` 会解析成**自赋值**，
// 结果全留在 null，构建报 `Keystore file not set for signing config release`。
val relStoreFile = (project.findProperty("storeFile") as String?)
    ?: localProps.getProperty("storeFile")
val relStorePassword = (project.findProperty("storePassword") as String?)
    ?: localProps.getProperty("storePassword")
val relKeyAlias = (project.findProperty("keyAlias") as String?)
    ?: localProps.getProperty("keyAlias")
val relKeyPassword = (project.findProperty("keyPassword") as String?)
    ?: localProps.getProperty("keyPassword")
val hasReleaseKey = !relStoreFile.isNullOrBlank()
    && !relStorePassword.isNullOrBlank()
    && !relKeyAlias.isNullOrBlank()
    && file(relStoreFile).exists()

/**
 * APK 的下载地址。
 *
 * `releases/latest/download/<资产名>` **不带 tag**，永远指向最新一个正式 Release
 * 里的同名资产 → 这个地址跨版本是固定的，客户端和清单都不用改。
 * 没有配仓库时退回本机静态服务，方便局域网调试。
 */
val apkDownloadUrl = if (updateRepo.isBlank()) {
    "http://$updateHost/myembyhub.apk"
} else {
    "https://github.com/$updateRepo/releases/latest/download/myembyhub.apk"
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
        // targetSdk 34：**一个包同时喂老电视和新电视**。
        //
        // ⚠️ 关于「targetSdk 提上去会不会搞坏 4.3」（2026-11-20 定的）：
        //    系统的兼容性判断一律是 `if (targetSdkVersion >= N)` 形式，而 API 18 的
        //    ROM 里压根没有 N=24/26/30/34 这些分支（那些代码是后来的系统才加的）。
        //    所以在 4.3 上 targetSdk=23 和 targetSdk=34 走**同一条路径**，逐字节一致。
        //    真正会变的只有「API >= N 的系统」，也就是新电视。
        //
        //    也正因为如此，targetSdk 千万别拆成两个 flavor：
        //    4.3 看不出来差别，拆了只会变成两条发布线（两个包互相覆盖 / 两套
        //    在线更新与反馈 / 用户装错），纯亏。见 AGENTS.md 的「新电视兼容」一节。
        //
        //    版本相关的行为差异全部收敛在代码里用 Build.VERSION.SDK_INT 分支：
        //    存储（私有目录 + FileProvider）、安装（REQUEST_INSTALL_PACKAGES）、
        //    TLS（系统栈 / Conscrypt）、SNI（SSLParameters / 隐藏 API）。
        targetSdk = 34
        multiDexEnabled = true

        // 保留三种 ABI：
        //
        //   armeabi-v7a —— 乐视 S50 Air（MStar 方案）本来就是 32 位，
        //                  Conscrypt 的原生库也只有这份在老电视上真正用得上；
        //   arm64-v8a   —— 新电视 / 新盒子。**少了它，纯 64 位设备直接
        //                  INSTALL_FAILED_NO_MATCHING_ABIS 装不上**，
        //                  这是「新电视支持」里唯一一条装不上的硬门槛。
        //   x86         —— API 18 模拟器（LeTV AVD）用，调试要靠它。
        //
        // x86_64 在 `packaging.jniLibs.excludes` 里排掉：没有 x86_64 的电视，
        // 留着纯占 2.5MB。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86")
        }

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

    // ========================================================================
    // Lint
    //
    // 这里只放**这台机器注定满足不了**的检查项，其余一律照常报错 ——
    // lint 是真能抓出崩机的（比如 `InvalidSetHasFixedSize` 曾经让 release 打包失败，
    // 但那条本身就是真问题：horizontal RecyclerView 的 height=wrap_content，
    // 高度随集数变，`setHasFixedSize(true)` 是在骗框架）。
    //
    // ExpiredTargetSdkVersion：Google Play 要求 targetSdk ≥ 35。本 App 从来不上
    // Play（目标设备连 GMS 都没有），而且 targetSdk 定在 34 是「老电视零行为变化」
    // 和「新电视能过安装门槛」的交点 —— 再往上提就得牵动前台服务、广播这些
    // 新限制，对这个一万行的自用客户端不值得。这条检查在这里永远不适用。
    // ========================================================================
    lint {
        // 发版不能被「Google Play 上架要求」卡住，但真错误必须拦。
        abortOnError = true
        checkReleaseBuilds = true
        disable += "ExpiredTargetSdkVersion"
    }

    buildTypes {
        // ====================================================================
        // 交付物是 **release** 包（`app-release.apk` → `dist/myembyhub.apk`）。
        //
        // 历史：早期一直发 debug 包，因为那时候只有 debug 能装上。代价是
        // ① `android:debuggable=true`（adb 能 run-as 读走 token）
        // ② 签名来自 ~/.android/debug.keystore（换机器就断更）
        // ③ R8 的优化/混淆对 debuggable 构建整个关掉（构建日志会明确提示）
        // 现在有固定 keystore 了，改回 release。
        //
        // debug 构建保留原样（debug 签名 + debuggable），只给开发期用 ——
        // `adb logcat` / `run-as` 调试要靠它。
        // ====================================================================
        val minify = !project.hasProperty("noMinify")

        if (hasReleaseKey) {
            signingConfigs {
                create("release") {
                    storeFile = file(relStoreFile!!)
                    storePassword = relStorePassword
                    keyAlias = relKeyAlias
                    keyPassword = relKeyPassword ?: relStorePassword
                    // v1 必须开：Android 4.3 只认 JAR 签名（v2 是 API 24 才有的）
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        }

        release {
            isMinifyEnabled = minify
            isShrinkResources = minify
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "⚠️ local.properties 里没有 storeFile/storePassword/keyAlias，" +
                        "release 包将退回 debug 签名（不可用于发版）。"
                )
            }
        }
        debug {
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Conscrypt 的 AAR 带 4 份原生库（共 ~8.1MB）。我们只要三份：
    // armeabi-v7a（老电视）、arm64-v8a（新电视）、x86（模拟器）。
    // x86_64 没有任何目标设备，排掉省 2.5MB。
    packaging {
        jniLibs {
            excludes += "lib/x86_64/**"
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

    // Conscrypt：**唯一为 4.3 补出现代 TLS 套件的方案**。
    //
    // 背景（实测见 net/TlsStack.kt 注释）：4.3 的系统 JSSE 由 OpenSSL 1.0.1 驱动，
    // 只有 CBC 套件、没有任何 GCM/AEAD 实现。服务端如果只开 GCM
    // （现代 nginx / Cloudflare 的默认配置），握手直接 alert 40 handshake_failure，
    // 客户端再怎么抬 enabledProtocols 也没用 —— 那是**实现里没有**，不是配置问题。
    //
    // Conscrypt 把一套 BoringSSL 塞进 APK（JNI），从此 GCM / ChaCha20 / TLS1.3 全都有，
    // 官方声明支持 API 9+，AAR 里带 armeabi-v7a 原生库（1.28MB）。
    //
    // ⚠️ 体积代价：只保留 armeabi-v7a 后大约 +1.5MB（classes + so），
    //    电视 app 堆 96MB、APK 原本 2.2MB，这个涨幅可以接受；
    //    不裁 ABI 的话 AAR 里还有 arm64-v8a 2.1MB + x86 2.25MB + x86_64 2.5MB 白搭进去。
    implementation(libs.conscrypt.android)

    // 单元测试：目前只有 UrlKit 的回归测试（手写 URL / 重定向解析）
    testImplementation(libs.junit)
    // org.json 的**纯 Java 实现**，只进测试类路径。
    //
    // 为什么需要：`org.json` 在 Android 上是系统自带的，但单元测试跑在 JVM 上，
    // android.jar 里的 `JSONObject` / `JSONArray` 全是抛 "Stub!" 的桩
    // （`isReturnDefaultValues = true` 只让它们返回默认值，等于什么都没做）。
    // 凡是编解码 JSON 的纯逻辑（EpisodeRef、UpdateManager 的清单解析）在测试里
    // 就会 NPE / 断言失败，而不是代码有问题。
    // 打包进 APK 的是系统实现，这份依赖只在 testImplementation 里，不影响产物。
    testImplementation("org.json:json:20240303")
}

// ============================================================================
// 在线更新：打包后自动生成 dist/version.json
//
// 电视上的 App 请求这个文件，和自己的 versionCode 比较；
// 大于就下载 APK 并调起系统安装器。
//
// 两个地址怎么来的见文件开头 updateRepo / updateHost 那段。
// 发版流程：把 dist/myembyhub.apk 和 dist/version.json **都**上传到
// 同一个 GitHub Release（资产名不能改），客户端就能在线更新了。
// 见 tools/publish-release.ps1。
//
// ⚠️ 这里挂的是 **assembleRelease**（历史上挂的是 assembleDebug）。
//    早期只有 debug 包能装，交付物就是 `myembyhub-debug.apk`；
//    现在有固定 keystore 了，交付物是签名过的 release 包，资产名 `myembyhub.apk`。
//    老版本 App 里烤的是 `.../download/myembyhub-debug.apk`，所以发版脚本
//    会**额外上传一份同名旧资产**，让它们至少还能下载到（虽然签名变了、
//    装不上去，要卸载重装 —— 见 MyEmbyHub-keys/README.txt）。
// ============================================================================
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
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
              "notes": "播放协议对齐标准 Emby：起播与切集改走 POST PlaybackInfo 协商（支持 DirectStreamUrl / smartstrm 302 媒体源），修复部分服务端串片问题；规范媒体库/搜索接口路径并补齐下一集兜底与 HTTP 登录兼容。\\n\\n继续观看菜单：在「继续观看」项按菜单键（或长按确认键）可添加到收藏夹/取消收藏、标记为已观看、标记为未观看或移除观看记录。\\n\\n登录与 401 体验修复：登录悬浮窗支持关闭，未登录或登录态过期时可在首页打开登录或直接进设置检查更新；补齐播放页/详情页/翻页 401 自动触发重登与遥控器焦点恢复。"
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

