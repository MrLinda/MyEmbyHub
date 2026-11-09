package work.luxiaoxiao.tvprobe;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;

/**
 * 极简电视端「系统信息探针」。
 *
 * 目的：在电视上直接显示这台机器的 Android 版本、SDK、机型等信息，
 * 用来确认「APK 能不能装上、系统到底是什么版本」。
 *
 * 设计约束（为了兼容乐视 S50 Air 这类 Android 4.x~5.x 老电视）：
 *  - 不依赖任何 AndroidX / Compose，纯 framework API；
 *  - minSdk 16，所有跨版本字段一律走反射取，任何一个取不到都不影响其余内容；
 *  - 每个信息块独立 try/catch，局部失败不会让整个 App 崩掉；
 *  - 纯代码构建界面，不引入第三方资源。
 */
public class MainActivity extends Activity {

    private static final String TAG = "TvProbe";

    private TextView textView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            textView = new TextView(this);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            textView.setTextColor(Color.parseColor("#7CFF9B"));
            textView.setBackgroundColor(Color.BLACK);
            textView.setPadding(dp(24), dp(20), dp(24), dp(20));

            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(Color.BLACK);
            scroll.addView(textView, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            setContentView(scroll);
        } catch (Throwable t) {
            Log.e(TAG, "界面创建失败", t);
            // 兜底：至少把异常堆栈显示在屏幕上，别再变成「打开就闪退」
            try {
                TextView fallback = new TextView(this);
                fallback.setTextColor(Color.parseColor("#FF6B6B"));
                fallback.setBackgroundColor(Color.BLACK);
                fallback.setPadding(dp(24), dp(24), dp(24), dp(24));
                fallback.setText("界面创建失败：\n" + describe(t));
                setContentView(fallback);
                textView = fallback;
            } catch (Throwable ignored) {
            }
        }

        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ================= 主信息组装 =================

    private void refresh() {
        if (textView == null) return;

        StringBuilder sb = new StringBuilder();
        try {
            // —— 最大字号：拍照/截图一眼就能看清版本 ——
            sb.append("Android 版本 : ").append(Build.VERSION.RELEASE).append('\n');
            sb.append("API Level    : ").append(Build.VERSION.SDK_INT).append('\n');
            sb.append('\n');

            sb.append("== 系统 ==\n");
            line(sb, "DISPLAY      ", Build.DISPLAY);
            line(sb, "BUILD_ID     ", Build.ID);
            line(sb, "INCREMENTAL  ", Build.VERSION.INCREMENTAL);
            line(sb, "TYPE         ", Build.TYPE);
            line(sb, "TAGS         ", Build.TAGS);
            line(sb, "FINGERPRINT  ", Build.FINGERPRINT);
            line(sb, "SECURITY_PATCH", reflect(Build.VERSION.class, "SECURITY_PATCH"));
            line(sb, "系统时间      ", Build.TIME);
            line(sb, "时区          ", java.util.TimeZone.getDefault().getID());
            line(sb, "语言          ", String.valueOf(Locale.getDefault()));
            sb.append('\n');

            sb.append("== 设备 ==\n");
            line(sb, "厂商 MANUFACTURER", Build.MANUFACTURER);
            line(sb, "品牌 BRAND      ", Build.BRAND);
            line(sb, "型号 MODEL       ", Build.MODEL);
            line(sb, "产品 PRODUCT    ", Build.PRODUCT);
            line(sb, "设备 DEVICE     ", Build.DEVICE);
            line(sb, "硬件 HARDWARE   ", Build.HARDWARE);
            line(sb, "主板 BOARD      ", Build.BOARD);
            line(sb, "序列号 SERIAL    ", reflect(Build.class, "SERIAL"));
            line(sb, "CPU ABI         ", abis());
            sb.append('\n');

            sb.append("== 屏幕 ==\n");
            DisplayMetrics dm = getResources().getDisplayMetrics();
            line(sb, "分辨率          ", dm.widthPixels + " x " + dm.heightPixels);
            line(sb, "密度 density    ", dm.density + " (" + densityDpi(dm) + " dpi)");
            float density = dm.density == 0 ? 1 : dm.density;
            line(sb, "尺寸 dp         ", (int) (dm.widthPixels / density) + " x "
                    + (int) (dm.heightPixels / density));
            line(sb, "刷新率          ", refreshRate());
            sb.append('\n');

            sb.append("== 资源 ==\n");
            line(sb, "运行时可用内存  ", (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long total = reflectLong(mi, "totalMem");
                line(sb, "系统内存        ", (total / (1024 * 1024)) + " MB");
                line(sb, "系统可用内存    ", (mi.availMem / (1024 * 1024)) + " MB");
            }
            line(sb, "内部存储可用    ", freeSpace(Environment.getDataDirectory()));
            line(sb, "外部存储可用    ", freeSpace(Environment.getExternalStorageDirectory()));
            line(sb, "/proc/meminfo  ", procMemTotal());
            sb.append('\n');

            sb.append("== 网络 ==\n");
            line(sb, "连接状态        ", networkType());
            sb.append('\n');

            sb.append("== 环境检查 ==\n");
            line(sb, "已安装应用数    ", countInstalledApps());
            line(sb, "Google Play 服务", hasPackage("com.google.android.gms"));
            line(sb, "Google Play 商店", hasPackage("com.android.vending"));
            line(sb, "WebView 可用    ", hasPackage("com.google.android.webview")
                    || hasPackage("com.android.webview")
                    || hasPackage("com.google.android.trichrolibrary"));
            line(sb, "存在启动器      ", hasLauncher());
            sb.append('\n');

            line(sb, "本应用包名      ", getPackageName());
            line(sb, "安装路径        ", getApplicationInfo().sourceDir);
            line(sb, "本 App minSdk   ", reflect(getApplicationInfo(), "minSdkVersion"));
            line(sb, "本 App targetSdk", reflect(getApplicationInfo(), "targetSdkVersion"));
        } catch (Throwable t) {
            Log.e(TAG, "信息采集异常", t);
            sb.append("\n\n[部分信息采集失败]\n").append(describe(t));
        }

        textView.setText(sb.toString());
    }

    private void line(StringBuilder sb, String label, Object value) {
        sb.append(label).append(": ").append(value == null ? "未知" : value).append('\n');
    }

    // ================= 反射工具：跨版本安全取字段 =================

    /** 取不到就返回 null，绝不抛异常（老系统缺字段时最常见的崩溃源）。 */
    private static Object reflect(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
            Field f = c.getField(fieldName);
            return f.get(target instanceof Class ? null : target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static long reflectLong(Object target, String fieldName) {
        Object v = reflect(target, fieldName);
        return (v instanceof Number) ? ((Number) v).longValue() : 0L;
    }

    /** DisplayMetrics.densityDpi 是 API 17 才有的，老系统上要靠算。 */
    private static String densityDpi(DisplayMetrics dm) {
        Object v = reflect(dm, "densityDpi");
        if (v instanceof Number) return String.valueOf(((Number) v).intValue());
        float density = dm.density == 0 ? 1 : dm.density;
        return String.valueOf((int) (density * 160));
    }

    // ================= 各信息项 =================

    private String abis() {
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                String[] list = Build.SUPPORTED_ABIS;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(list[i]);
                }
                return sb.toString();
            }
            return Build.CPU_ABI + " / " + Build.CPU_ABI2;
        } catch (Throwable t) {
            try {
                return Build.CPU_ABI;
            } catch (Throwable ignored) {
                return "未知";
            }
        }
    }

    private String refreshRate() {
        try {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            return String.format(Locale.US, "%.1f Hz", d.getRefreshRate());
        } catch (Throwable t) {
            return "未知";
        }
    }

    private String freeSpace(File dir) {
        try {
            if (dir == null || !dir.exists()) return "不可用";
            StatFs fs = new StatFs(dir.getAbsolutePath());
            long free = (long) fs.getAvailableBlocks() * fs.getBlockSize();
            return String.format(Locale.US, "%.1f GB", free / (1024.0 * 1024.0 * 1024.0));
        } catch (Throwable t) {
            return "不可用";
        }
    }

    private String networkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "未知";
            NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return "未连接";
            return info.getTypeName() + " / " + info.getSubtypeName()
                    + (info.getExtraInfo() != null ? " (" + info.getExtraInfo() + ")" : "");
        } catch (Throwable t) {
            return "未知";
        }
    }

    private int countInstalledApps() {
        try {
            return getPackageManager().getInstalledApplications(0).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean hasPackage(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean hasLauncher() {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            List<android.content.pm.ResolveInfo> list =
                    getPackageManager().queryIntentActivities(i, 0);
            return list != null && !list.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读取 /proc/meminfo 的总内存，作为 ActivityManager 之外的第二来源。 */
    private String procMemTotal() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader("/proc/meminfo"));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("MemTotal")) {
                    return line.trim();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) { }
            }
        }
        return "未知";
    }

    // ================= 杂项 =================

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static String describe(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 12; i++) {
            sb.append("  at ").append(st[i].toString()).append('\n');
        }
        if (t.getCause() != null) {
            sb.append("Caused by: ").append(t.getCause().toString()).append('\n');
        }
        return sb.toString();
    }
}
