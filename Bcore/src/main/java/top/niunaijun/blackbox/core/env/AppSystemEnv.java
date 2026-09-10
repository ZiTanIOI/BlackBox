package top.niunaijun.blackbox.core.env;

import android.content.ComponentName;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/21/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class AppSystemEnv {
    private static final String TAG = "AppSystemEnv";

    private static final List<String> sSystemPackages = new ArrayList<>();
    private static final List<String> sSuPackages = new ArrayList<>();
    private static final List<String> sXposedPackages = new ArrayList<>();
    private static final List<String> sPreInstallPackages = new ArrayList<>();

    /**
     * 宿主直通（Host Pass-through）应用。
     *
     * <p>这些应用即使被导入容器，也不在容器内虚拟化运行：guest 对它们的跨应用调用
     * （startActivity / startService / bindService / getContentProvider）会直接放行到真实系统，
     * 也就是使用设备上真实安装的那一份。</p>
     *
     * <p>典型场景：游戏内通过第三方客户端登录。这类客户端自身进程模型复杂
     * （多进程 + 自研沙箱 + 动态插件），放进容器后无法正常工作；而且 guest 传给它的 Intent
     * 里常带有只存在于游戏 APK 中的 Parcelable（例如 SDK 的 LoginRequest），跨容器传递时
     * 反序列化必然失败。直通宿主可以完全绕开这些问题。</p>
     *
     * <p>内置默认列表见 {@link #DEFAULT_HOST_PACKAGES}，用户可在
     * {@code blackbox/system/host-apps.conf} 中每行追加一个包名（# 开头为注释）。</p>
     */
    private static final Set<String> sHostPackages = new HashSet<>();
    private static final Object sHostLock = new Object();
    private static volatile boolean sHostLoaded = false;
    private static volatile long sHostConfLastModified = -1L;

    private static final String[] DEFAULT_HOST_PACKAGES = new String[]{
            // 默认留空：实测「需要校验调用方身份/签名的第三方客户端」只有跑在容器内
            // 才能通过校验——容器会 hook getCallingPackage/getCallingActivity
            // 返回 guest 包名，容器 PM 又保留真签名；一旦直通到宿主，客户端看到的调用方是
            // 宿主包名（uid 决定，不可伪造），校验必然失败。
            //
            // 需要直通的包请写进 blackbox/system/host-apps.conf（每行一个包名，
            // 以 '-' 开头表示从内置默认项里排除）。
    };

    static {
        sSystemPackages.add("android");
        sSystemPackages.add("com.google.android.webview");
        sSystemPackages.add("com.google.android.webview.dev");
        sSystemPackages.add("com.google.android.webview.beta");
        sSystemPackages.add("com.google.android.webview.canary");
        sSystemPackages.add("com.android.webview");
        sSystemPackages.add("com.android.camera");
        sSystemPackages.add("com.android.talkback");
        sSystemPackages.add("com.miui.gallery");

        // google Gboard
        sSystemPackages.add("com.google.android.inputmethod.latin");
        // sSystemPackages.add(BlackBoxCore.getHostPkg());

        // 华为
        sSystemPackages.add("com.huawei.webview");

        // oppo
        sSystemPackages.add("com.coloros.safecenter");

        // su
        sSuPackages.add("com.noshufou.android.su");
        sSuPackages.add("com.noshufou.android.su.elite");
        sSuPackages.add("eu.chainfire.supersu");
        sSuPackages.add("com.koushikdutta.superuser");
        sSuPackages.add("com.thirdparty.superuser");
        sSuPackages.add("com.yellowes.su");

        sXposedPackages.add("de.robv.android.xposed.installer");

        // sPreInstallPackages.add("com.huawei.hwid");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && Build.VERSION.SDK_INT < 29){
            //解决Android 9三星浏览器闪退问题
        }else{

        }

        for (String pkg : DEFAULT_HOST_PACKAGES) {
            sHostPackages.add(pkg);
        }
    }

    public static boolean isOpenPackage(String packageName) {
        return sSystemPackages.contains(packageName);
    }

    public static boolean isOpenPackage(ComponentName componentName) {
        return componentName != null && isOpenPackage(componentName.getPackageName());
    }

    /**
     * 该包是否应「在宿主侧查询/放行」：系统内置可见包，或用户配置的直通应用。
     * 用于 PackageManager 的 getPackageInfo / getServiceInfo 等查询接口——
     * 直通应用必须在宿主 PM 里查，否则 guest 的 SDK 会认为它没有安装。
     */
    public static boolean isHostVisiblePackage(String packageName) {
        return isOpenPackage(packageName) || isHostPackage(packageName);
    }

    public static boolean isHostVisiblePackage(ComponentName componentName) {
        return componentName != null && isHostVisiblePackage(componentName.getPackageName());
    }

    /**
     * 是否为「宿主直通」应用：这类包在容器内不虚拟化，跨应用调用直接走真实系统。
     */
    public static boolean isHostPackage(String packageName) {
        if (packageName == null) {
            return false;
        }
        ensureHostPackagesLoaded();
        synchronized (sHostLock) {
            return sHostPackages.contains(packageName);
        }
    }

    public static boolean isHostPackage(ComponentName componentName) {
        return componentName != null && isHostPackage(componentName.getPackageName());
    }

    public static List<String> getHostPackages() {
        ensureHostPackagesLoaded();
        synchronized (sHostLock) {
            return new ArrayList<>(sHostPackages);
        }
    }

    /**
     * 热更新直通列表：下次判断时重新读取 conf 文件。
     */
    public static void reloadHostPackages() {
        synchronized (sHostLock) {
            sHostLoaded = false;
            sHostConfLastModified = -1L;
        }
        ensureHostPackagesLoaded();
    }

    private static void ensureHostPackagesLoaded() {
        File conf = BEnvironment.getHostAppsConf();
        long lastModified = conf.exists() ? conf.lastModified() : 0L;
        if (sHostLoaded && lastModified == sHostConfLastModified) {
            return;
        }
        synchronized (sHostLock) {
            if (sHostLoaded && lastModified == sHostConfLastModified) {
                return;
            }
            // 重新载入时保留内置默认项
            sHostPackages.clear();
            for (String pkg : DEFAULT_HOST_PACKAGES) {
                sHostPackages.add(pkg);
            }
            try {
                if (conf.exists()) {
                    BufferedReader reader = new BufferedReader(new FileReader(conf));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        // 以 '-' 开头表示「排除」：可以从内置默认列表里去掉某个包，
                        // 例如 "-com.example.client" 会把它恢复成容器内虚拟化运行。
                        if (line.startsWith("-")) {
                            String remove = line.substring(1).trim();
                            if (!remove.isEmpty()) {
                                sHostPackages.remove(remove);
                            }
                            continue;
                        }
                        sHostPackages.add(line);
                    }
                    reader.close();
                }
                Slog.d(TAG, "host pass-through packages: " + sHostPackages);
            } catch (Throwable e) {
                Slog.d(TAG, "load host-apps.conf failed: " + e);
            }
            sHostLoaded = true;
            sHostConfLastModified = lastModified;
        }
    }

    public static boolean isBlackPackage(String packageName) {
        if (BlackBoxCore.get().isHideRoot() && sSuPackages.contains(packageName)) {
            return true;
        } else if (BlackBoxCore.get().isHideXposed() && sXposedPackages.contains(packageName)) {
            return true;
        }
        return false;
    }

    public static List<String> getPreInstallPackages() {
        return sPreInstallPackages;
    }
}
