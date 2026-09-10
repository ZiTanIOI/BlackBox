package top.niunaijun.blackbox.core.system.pm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PermissionInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import black.android.content.pm.BRApplicationInfoL;
import black.android.content.pm.BRApplicationInfoN;
import black.android.content.pm.BRPackageParserSigningDetails;
import black.android.content.pm.BRSigningInfo;
import black.android.content.res.BRAssetManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.pm.InstallOption;
import top.niunaijun.blackbox.utils.ArrayUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.NativeUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/15/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@SuppressLint({"SdCardPath", "NewApi"})
public class PackageManagerCompat {

    public static PackageInfo generatePackageInfo(BPackageSettings ps, int flags, BPackageUserState state, int userId) {
        if (ps == null) {
            return null;
        }
        BPackage p = ps.pkg;
        if (p != null) {
            PackageInfo packageInfo = null;
            try {
                packageInfo = generatePackageInfo(p, flags, 0, 0, state, userId);
            } catch (Throwable ignored) {
            }
            return packageInfo;
        }
        return null;
    }

    public static PackageInfo generatePackageInfo(BPackage p, int flags, long firstInstallTime, long lastUpdateTime, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo)) {
            return null;
        }

        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.versionCode = p.mVersionCode;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        // 整包只生成一份 ApplicationInfo，所有组件共用同一实例：Parcel 序列化按对象
        // 身份去重，回包大小与系统 PMS 一致；若每个组件各 new 一份，上千组件的回包会
        // 膨胀到几 MB 并超过 binder 事务上限，导致客户端拿不到 PackageInfo。
        final ApplicationInfo sharedAppInfo = generateApplicationInfo(p, flags, state, userId);
        pi.applicationInfo = sharedAppInfo;

        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if (!p.requestedPermissions.isEmpty()) {
            String[] requestedPermissions = new String[p.requestedPermissions.size()];
            p.requestedPermissions.toArray(requestedPermissions);
            pi.requestedPermissions = requestedPermissions;
        }

        if ((flags & PackageManager.GET_GIDS) != 0) {
            pi.gids = new int[]{};
        }
        if ((flags & PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            pi.activities = null;
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.activities.get(i);
                    res[num++] = generateActivityInfo(a, flags, state, userId, sharedAppInfo);
                }
                pi.activities = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            pi.receivers = null;
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Activity a = p.receivers.get(i);
                    res[num++] = generateActivityInfo(a, flags, state, userId, sharedAppInfo);
                }
                pi.receivers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            pi.services = null;
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Service s = p.services.get(i);
                    res[num++] = generateServiceInfo(s, flags, state, userId, sharedAppInfo);
                }
                pi.services = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            pi.providers = null;
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final BPackage.Provider pr = p.providers.get(i);
                    ProviderInfo providerInfo = generateProviderInfo(pr, flags, state, userId, sharedAppInfo);
                    if (providerInfo != null) {
                        res[num++] = providerInfo;
                    }
                }
                pi.providers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_INSTRUMENTATION) != 0) {
            pi.instrumentation = null;
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags & PackageManager.GET_PERMISSIONS) != 0) {
            pi.permissions = null;
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i = 0; i < N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            pi.requestedPermissions = null;
            N = p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i = 0; i < N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                    // The notion of required permissions is deprecated but for compatibility.
//                    pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_REQUIRED;
//                    if (grantedPermissions != null && grantedPermissions.contains(perm)) {
//                        pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
//                    }
                }
            }
        }
        PackageInfo base = null;
        try {
            base = BlackBoxCore.getContext().getPackageManager().getPackageInfo(p.packageName, flags);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (base == null) {
                pi.signatures = p.mSignatures;
            } else {
                pi.signatures = base.signatures;
            }
        }
        if (BuildCompat.isPie()) {
            if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
                if (base == null) {
                    PackageParser.SigningDetails signingDetails = PackageParser.SigningDetails.UNKNOWN;
                    BRPackageParserSigningDetails.get(signingDetails)._set_signatures(p.mSigningDetails.signatures);
                    pi.signingInfo = BRSigningInfo.get()._new(signingDetails);
                } else {
                    pi.signingInfo = base.signingInfo;
                }
            }
        }
        return pi;
    }

    public static ActivityInfo generateActivityInfo(BPackage.Activity a, int flags, BPackageUserState state, int userId) {
        return generateActivityInfo(a, flags, state, userId, null);
    }

    /**
     * @param sharedApplicationInfo 由调用方预先算好的 ApplicationInfo。
     *        带组件标志查询时，系统 PMS 给所有组件挂的是<b>同一个实例</b>，
     *        Parcel 序列化会按对象身份去重，回包因此很小；容器如果给每个组件都新
     *        new 一份，去重失效，上千个组件的回包会膨胀到几 MB，直接超过 binder
     *        事务上限（1MB）导致调用失败。所以这里必须复用同一个实例。
     */
    public static ActivityInfo generateActivityInfo(BPackage.Activity a, int flags, BPackageUserState state,
                                                    int userId, ApplicationInfo sharedApplicationInfo) {
        if (!checkUseInstalledOrHidden(flags, state, a.info.applicationInfo)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo(a.info);
        ai.metaData = a.metaData;
        ai.processName = BPackageManagerService.fixProcessName(ai.packageName, ai.processName);
        ai.applicationInfo = sharedApplicationInfo != null
                ? sharedApplicationInfo : generateApplicationInfo(a.owner, flags, state, userId);
        return ai;
    }

    public static ServiceInfo generateServiceInfo(BPackage.Service s, int flags, BPackageUserState state, int userId) {
        return generateServiceInfo(s, flags, state, userId, null);
    }

    public static ServiceInfo generateServiceInfo(BPackage.Service s, int flags, BPackageUserState state,
                                                  int userId, ApplicationInfo sharedApplicationInfo) {
        if (!checkUseInstalledOrHidden(flags, state, s.info.applicationInfo)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo(s.info);
        si.metaData = s.metaData;
        si.processName = BPackageManagerService.fixProcessName(si.packageName, si.processName);
        si.applicationInfo = sharedApplicationInfo != null
                ? sharedApplicationInfo : generateApplicationInfo(s.owner, flags, state, userId);
        return si;
    }

    public static ProviderInfo generateProviderInfo(BPackage.Provider p, int flags, BPackageUserState state, int userId) {
        return generateProviderInfo(p, flags, state, userId, null);
    }

    public static ProviderInfo generateProviderInfo(BPackage.Provider p, int flags, BPackageUserState state,
                                                    int userId, ApplicationInfo sharedApplicationInfo) {
        if (!checkUseInstalledOrHidden(flags, state, p.info.applicationInfo)) {
            return null;
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo(p.info);
        if (pi.authority == null)
            return null;
        pi.metaData = p.metaData;
        pi.processName = BPackageManagerService.fixProcessName(pi.packageName, pi.processName);
        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = sharedApplicationInfo != null
                ? sharedApplicationInfo : generateApplicationInfo(p.owner, flags, state, userId);
        return pi;
    }

    public static PermissionInfo generatePermissionInfo(
            BPackage.Permission p, int flags) {
        if (p == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    public static InstrumentationInfo generateInstrumentationInfo(
            BPackage.Instrumentation i, int flags) {
        if (i == null) return null;
        if ((flags & PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    /**
     * 把设备的 base/split APK 里属于当前 ABI 的 so 解压进容器 lib 目录。
     * copyNativeLib 对没有 lib/ 条目的 apk 直接快速跳过，逐个扫不会互相干扰。
     */
    private static final Object sExtractLock = new Object();
    private static final String EXTRACTED_MARKER = ".libs_extracted";

    private static boolean isNativeLibsExtracted(File libDir, String sourceDir) {
        File marker = new File(libDir, EXTRACTED_MARKER);
        if (!marker.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(marker))) {
            return sourceDir.equals(reader.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    private static void markNativeLibsExtracted(File libDir, String sourceDir) throws Exception {
        FileWriter writer = new FileWriter(new File(libDir, EXTRACTED_MARKER));
        writer.write(sourceDir);
        writer.close();
    }

    private static final java.util.concurrent.ExecutorService sExtractExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.Set<String> sExtractScheduled =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static void scheduleNativeLibsExtract(String packageName, String sourceDir, File libDir) {
        String key = packageName + "@" + sourceDir;
        if (!sExtractScheduled.add(key)) return;
        sExtractExecutor.execute(() -> {
            try {
                synchronized (sExtractLock) {
                    if (!isNativeLibsExtracted(libDir, sourceDir)) {
                        extractNativeLibs(packageName, sourceDir, libDir);
                        markNativeLibsExtracted(libDir, sourceDir);
                        Slog.e("PackageManagerCompat", "native libs extracted for " + packageName);
                    }
                }
            } catch (Throwable t) {
                Slog.e("PackageManagerCompat", "extract native libs failed for " + packageName, t);
                sExtractScheduled.remove(key);
            }
        });
    }

    private static void extractNativeLibs(String packageName, String sourceDir, File libDir) {
        List<String> apks = new ArrayList<>();
        apks.add(sourceDir);
        try {
            ApplicationInfo real = BlackBoxCore.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
            if (real.sourceDir != null) {
                apks.add(real.sourceDir);
            }
            if (real.splitSourceDirs != null) {
                Collections.addAll(apks, real.splitSourceDirs);
            }
        } catch (Throwable ignored) {
        }
        for (String apk : apks) {
            try {
                NativeUtils.copyNativeLib(new File(apk), libDir);
            } catch (Throwable ignored) {
            }
        }
    }

    public static ApplicationInfo generateApplicationInfo(BPackage p, int flags, BPackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo)) {
            return null;
        }
        ApplicationInfo baseApplication;
        try {
            baseApplication = BlackBoxCore.getPackageManager().getApplicationInfo(BlackBoxCore.getHostPkg(), flags);
        } catch (Exception e) {
            return null;
        }
        String sourceDir = p.baseCodePath;
        if (p.applicationInfo == null) {
            p.applicationInfo = BlackBoxCore.getPackageManager()
                    .getPackageArchiveInfo(sourceDir, 0).applicationInfo;
        }
        ApplicationInfo ai = new ApplicationInfo(p.applicationInfo);
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = p.mAppMetaData;
        }
        ai.dataDir = BEnvironment.getDataDir(ai.packageName, userId).getAbsolutePath();
        // Xposed 模块即使按系统方式导入也需要指向容器内解压的 lib 目录，
        // 否则模块加载后 System.loadLibrary 找不到自带的 .so
        if (!p.installOption.isFlag(InstallOption.FLAG_SYSTEM) || p.installOption.isFlag(InstallOption.FLAG_XPOSED)) {
            ai.nativeLibraryDir = BEnvironment.getAppLibDir(ai.packageName).getAbsolutePath();
        } else {
            // 按系统方式导入的应用统一把 so 解压进容器 lib 目录并改指过去：
            // 1) extractNativeLibs=false 的应用系统不解压，原始 nativeLibraryDir 是空目录；
            // 2) 部分引擎（Unity 改包等）按 nativeLibraryDir 拼路径 dlopen，
            //    在容器的 linker namespace 里解析 /data/app 下的库可能失败；
            //    从容器自身路径加载则始终可用。
            // getPackageInfo 调用非常频繁，解压必须只做一次：完成后写标记
            // （内容为 sourceDir，应用更新路径变化时自动重新解压），
            // 并用全局锁防止并发 binder 线程重复拷贝同一个大文件。
            File libDir = BEnvironment.getAppLibDir(ai.packageName);
            // 解压绝不能在查询路径上做（会持有 mPackages 锁拷贝上百 MB，把整个
            // PM 服务堵死）：只看标记决定用哪个目录，未解压的丢给后台线程补，
            // 完成前的查询保持原始 nativeLibraryDir（与旧版行为一致）
            if (isNativeLibsExtracted(libDir, sourceDir)) {
                ai.nativeLibraryDir = libDir.getAbsolutePath();
            } else {
                scheduleNativeLibsExtract(ai.packageName, sourceDir, libDir);
            }
        }
        ai.processName = BPackageManagerService.fixProcessName(p.packageName, ai.packageName);
        ai.publicSourceDir = sourceDir;
        ai.sourceDir = sourceDir;
        ai.uid = p.mExtras.appId;
//        ai.uid = baseApplication.uid;

        if (BuildCompat.isL()) {
            BRApplicationInfoL.get(ai)._set_primaryCpuAbi(Build.CPU_ABI);
            BRApplicationInfoL.get(ai)._set_scanPublicSourceDir(BRApplicationInfoL.get(baseApplication).scanPublicSourceDir());
            BRApplicationInfoL.get(ai)._set_scanSourceDir(BRApplicationInfoL.get(baseApplication).scanSourceDir());
        }
        if (BuildCompat.isN()) {
            ai.deviceProtectedDataDir = BEnvironment.getDeDataDir(p.packageName, userId).getAbsolutePath();

            if (BRApplicationInfoN.get(ai)._check_deviceEncryptedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_deviceEncryptedDataDir(ai.deviceProtectedDataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_credentialEncryptedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_credentialEncryptedDataDir(ai.dataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_deviceProtectedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_deviceProtectedDataDir(ai.deviceProtectedDataDir);
            }
            if (BRApplicationInfoN.get(ai)._check_credentialProtectedDataDir() != null) {
                BRApplicationInfoN.get(ai)._set_credentialProtectedDataDir(ai.dataDir);
            }
        }
        fixJar(ai);
        return ai;
    }

    private static boolean checkUseInstalledOrHidden(int flags, BPackageUserState state,
                                                     ApplicationInfo appInfo) {
        if (AppSystemEnv.isBlackPackage(appInfo.packageName))
            return false;
        // Returns false if the package is hidden system app until installed.
        if (!state.installed || state.hidden) {
            return false;
        }
        return true;
    }

    private static void fixJar(ApplicationInfo info) {
        String APACHE_LEGACY_JAR = "/system/framework/org.apache.http.legacy.boot.jar";
        String APACHE_LEGACY_JAR_Q = "/system/framework/org.apache.http.legacy.jar";
        Set<String> sharedLibraryFileList = new HashSet<>();
        if (BuildCompat.isQ()) {
            if (!FileUtils.isExist(APACHE_LEGACY_JAR_Q)) {
                sharedLibraryFileList.add(APACHE_LEGACY_JAR);
            } else {
                sharedLibraryFileList.add(APACHE_LEGACY_JAR_Q);
            }
        } else {
            sharedLibraryFileList.add(APACHE_LEGACY_JAR);
        }
//        if (BXposedManagerService.get().isXPEnable()) {
//            ApplicationInfo base = BlackBoxCore.getContext().getApplicationInfo();
//            sharedLibraryFileList.add(base.sourceDir);
//        }
//        sharedLibraryFileList.add(BEnvironment.JUNIT_JAR.getAbsolutePath());
        info.sharedLibraryFiles = sharedLibraryFileList.toArray(new String[]{});
    }

    public static Resources getResources(Context context, ApplicationInfo appInfo) {
        BPackageSettings ps = BPackageManagerService.get().getBPackageSetting(appInfo.packageName);
        if (ps != null) {
            AssetManager assets = BRAssetManager.get()._new();
            BRAssetManager.get(assets).addAssetPath(ps.pkg.baseCodePath);
            Resources hostRes = context.getResources();
            return new Resources(assets, hostRes.getDisplayMetrics(), hostRes.getConfiguration());
        }
        return null;
    }
}
