# ============================================================================
# MyEmbyHub TV 客户端 —— R8 混淆/裁剪规则
#
# 目标设备：乐视 S50 Air（Android 4.3 / API 18）。
# 开 minify 的目的不是防反编译，而是**减少 dex 体积**：
# 这台机器只有 1GHz MStar，装机时 dalvik 要对每个 secondary dex 做
# unzip + dexopt（实测模拟器上 classes2 单独就要 unzip 317ms + rewrite 455ms，
# 真机更慢），dex 越小，首次启动和每次覆盖安装后的 dexopt 越快。
# ============================================================================

# ---------------------------------------------------------------------------
# 1. BuildConfig —— 被反射读取，R8 看不到这条引用
#
# UpdateManager.kt:188  Class.forName("work.luxiaoxiao.myembyhub.BuildConfig")
# UpdateManager.kt:189  cls.getField("VERSION_CODE")
# UpdateManager.kt:201  Class.forName("work.luxiaoxiao.myembyhub.BuildConfig")
# UpdateManager.kt:202  cls.getField("VERSION_NAME")
#
# 一旦被裁掉或改名：运行时 ClassNotFoundException / NoSuchFieldException →
# 兜底返回 0 → 在线更新**永远提示「有新版本」**（明明已经是最新）。
# 这个坑在 AGP 8.0 忘记开 buildConfig 时已经踩过一次，不能再来第二次。
#
# ⚠️ **改包名时这里必须同步改**（2026-11-09 实测）：
#    keep 规则写的是旧包名 → 新的 BuildConfig 根本没被 keep
#    → R8 优化掉 VERSION_NAME 字段 → NoSuchFieldException
#    → 「服务器信息」里的客户端版本显示为空。
# ---------------------------------------------------------------------------
-keep class work.luxiaoxiao.myembyhub.BuildConfig { *; }
-keepclassmembers class work.luxiaoxiao.myembyhub.BuildConfig {
    public static final int VERSION_CODE;
    public static final java.lang.String VERSION_NAME;
}

# ---------------------------------------------------------------------------
# 2. BouncyCastle —— 只在 TlsStack.kt:93 用 Class.forName 探测
#
#   Class.forName("org.bouncycastle.jsse.provider.BouncyCastleProvider")
#
# 这条依赖**根本没有打进 APK**（app 的 dependencies 里没有 bcprov/bctls），
# 反射必然失败并被 catch 住，属于正常的「探测失败」路径。
# 但 R8 会因为找不到这个类而报 warning，加 -dontwarn 静音即可。
# ---------------------------------------------------------------------------
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn sun.security.**

# ---------------------------------------------------------------------------
# 3. 反射 / JNI 敏感：老系统 + 布局里用全限定类名的控件
#
# activity_player.xml 里直接写了 <com.google.android.exoplayer2.ui.PlayerView>，
# AGP 会为布局引用生成 keep 规则；这里再兜一道，防止升级 AGP 后规则生成方式变化。
# ---------------------------------------------------------------------------
-keep class com.google.android.exoplayer2.ui.PlayerView { *; }
-keep class com.google.android.exoplayer2.ui.PlayerControlView { *; }

# ---------------------------------------------------------------------------
# 4. 保留行号与源文件名 —— 崩溃日志要能定位
#
# 这台电视的 logcat 是唯一可用的调试手段（screencap 输出 0 字节、无 DUMP 权限），
# 栈里只剩 a.a.a 就没法查了。mapping.txt 在 app/build/outputs/mapping/ 下。
# ---------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# 5. Kotlin 协程 / 反射元数据（ExoPlayer 与 Kotlin stdlib 需要）
# ---------------------------------------------------------------------------
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ---------------------------------------------------------------------------
# 6. 静音第三方库的可选依赖警告
#    （ExoPlayer 会尝试加载 HLS/DASH/RTMP 扩展，本项目只用 Progressive，没引这些）
# ---------------------------------------------------------------------------
-dontwarn com.google.android.exoplayer2.ext.**
-dontwarn com.google.android.exoplayer2.source.hls.**
-dontwarn com.google.android.exoplayer2.source.dash.**
-dontwarn com.google.android.exoplayer2.source.rtsp.**
