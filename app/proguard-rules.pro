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
# 2. BouncyCastle —— 历史遗留的探测入口，现在已换成 Conscrypt
#
#   （TlsStack.kt 已不再引用 BC，这两条 -dontwarn 留着不影响任何东西，
#     避免旧依赖树里残留的引用触发 warning）
# ---------------------------------------------------------------------------
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn sun.security.**

# ---------------------------------------------------------------------------
# 2.1 Conscrypt —— **必须整包 keep**
#
# 它通过 JNI 和反射干活：
#   - `libconscrypt_jni.so` 按类名/方法名找 Java 侧实现；
#   - 4.3 上走 `PreKitKatPlatformOpenSSLSocketImplAdapter`，
#     这个类继承系统的 `org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl`，
#     是**按名字**由反射选出来实例化的（Platform.wrapSocketFactoryIfNeeded）；
#   - Provider 里的 Cipher / KeyAgreement / TrustManager 都由
#     `java.security` 框架按字符串名字反射构造。
# 一旦被 R8 改名或裁掉：运行时冒 UnsatisfiedLinkError / NoSuchAlgorithmException，
# 表现是「一开 App 就连不上服务器」，极难和网络问题区分。
# 官方 AAR 自带的 proguard.txt 就是 `-keep class org.conscrypt.** { *; }`，
# 这里重复写一份（AGP 会合并，不会冲突），以防依赖配置变化后规则丢失。
# ---------------------------------------------------------------------------
-keep class org.conscrypt.** { *; }
-keep interface org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-dontwarn dalvik.system.BlockGuard
-dontwarn dalvik.system.BlockGuard$Policy
-dontwarn dalvik.system.CloseGuard
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.xnet.provider.jsse.**

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
