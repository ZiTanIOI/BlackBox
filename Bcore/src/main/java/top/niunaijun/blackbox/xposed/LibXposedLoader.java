package top.niunaijun.blackbox.xposed;

import android.annotation.SuppressLint;
import android.app.AppComponentFactory;
import android.content.pm.ApplicationInfo;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.utils.Slog;

/**
 * 新版 libxposed API（io.github.libxposed.api，101+）模块加载器。
 * <p>
 * 模块入口列表在 APK 的 {@code META-INF/xposed/java_init.list}，元数据在
 * {@code META-INF/xposed/module.prop}（minApiVersion/exceptionMode 等）。
 * 入口类必须继承 {@link XposedModule}，由框架无参实例化后 {@code attachFramework} 注入实现，
 * 再按 onModuleLoaded → onPackageLoaded → onPackageReady 的顺序回调生命周期。
 * 与 {@code top.canyie.pine.xposed.PineXposed}（de.robv 旧 API）互不干扰：
 * 两个入口列表各读各的，一个入口类只会走其中一条通路。
 */
@SuppressLint("NewApi")
public class LibXposedLoader {
    private static final String TAG = "LibXposed";
    public static final String ENTRY_LIST = "META-INF/xposed/java_init.list";
    public static final String MODULE_PROP = "META-INF/xposed/module.prop";
    public static final String NATIVE_ENTRY_LIST = "META-INF/xposed/native_init.list";


    public static boolean hasEntryList(ClassLoader moduleClassLoader) {
        try {
            return moduleClassLoader.getResourceAsStream(ENTRY_LIST) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void loadModule(String modulePath, ClassLoader moduleClassLoader, ApplicationInfo moduleAppInfo,
                                  String vPackageName, ApplicationInfo vAppInfo, boolean isFirstPackage,
                                  ClassLoader vClassLoader, String vProcessName) {
        try {
            List<String> entries;
            try (InputStream is = moduleClassLoader.getResourceAsStream(ENTRY_LIST)) {
                entries = readLines(is);
            }
            if (entries.isEmpty()) return;

            Properties prop = new Properties();
            try (InputStream is = moduleClassLoader.getResourceAsStream(MODULE_PROP)) {
                if (is != null) prop.load(is);
            } catch (Throwable ignored) {
            }
            int minApi = parseInt(prop.getProperty("minApiVersion"), 0);
            if (minApi > XposedInterface.LIB_API) {
                Slog.e(TAG, "Module " + modulePath + " requires API " + minApi
                        + " > supported " + XposedInterface.LIB_API + ", skipped");
                return;
            }
            String defaultMode = prop.getProperty("exceptionMode", "protective");

            // 102 API 的 native 模块：按 LSPosed 规范，模块 Java 入口自行
            // System.loadLibrary 加载 native_init.list 里的 so，框架在 dlopen
            // 包装里按名单拦截并调用其 native_init 注册库加载回调。这里先把
            // 名单交给 native 层，务必在 Java 入口回调之前完成。
            try {
                List<String> nativeEntries;
                try (InputStream is = moduleClassLoader.getResourceAsStream(NATIVE_ENTRY_LIST)) {
                    nativeEntries = readLines(is);
                }
                if (!nativeEntries.isEmpty()) {
                    NativeCore.addXposedNativeLibs(nativeEntries.toArray(new String[0]));
                    Slog.i(TAG, "Registered native entries " + nativeEntries + " of " + modulePath);
                }
            } catch (Throwable t) {
                Slog.e(TAG, "Failed to register native entries of " + modulePath, t);
            }

            XposedInterfaceImpl xposed = new XposedInterfaceImpl(moduleAppInfo.packageName, moduleAppInfo, defaultMode);

            List<Holder> holders = new ArrayList<>();
            for (String className : entries) {
                try {
                    Class<?> c = moduleClassLoader.loadClass(className);
                    if (!XposedModule.class.isAssignableFrom(c)) {
                        Slog.e(TAG, "Entry " + className + " of " + modulePath
                                + " does not extend io.github.libxposed.api.XposedModule, skipped");
                        continue;
                    }
                    Constructor<?> ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    XposedModule module = (XposedModule) ctor.newInstance();
                    Holder holder = new Holder(module);
                    module.attachFramework(xposed, holder::detach);
                    holders.add(holder);
                    Slog.i(TAG, "Loaded modern xposed module entry " + className + " from " + modulePath);
                } catch (Throwable t) {
                    Slog.e(TAG, "Failed to load entry " + className + " from " + modulePath, t);
                }
            }
            if (holders.isEmpty()) return;

            for (Holder h : holders) {
                if (h.detached) continue;
                try {
                    h.module.onModuleLoaded(new ModuleLoadedParamImpl(false, vProcessName));
                } catch (Throwable t) {
                    Slog.e(TAG, "onModuleLoaded threw in " + h.module.getClass().getName(), t);
                }
            }

            PackageLoadedParamImpl loadedParam = new PackageLoadedParamImpl(vPackageName, vAppInfo, isFirstPackage, vClassLoader);
            for (Holder h : holders) {
                if (h.detached) continue;
                try {
                    h.module.onPackageLoaded(loadedParam);
                } catch (Throwable t) {
                    Slog.e(TAG, "onPackageLoaded threw in " + h.module.getClass().getName(), t);
                }
            }
            PackageReadyParamImpl readyParam = new PackageReadyParamImpl(vPackageName, vAppInfo, isFirstPackage, vClassLoader);
            for (Holder h : holders) {
                if (h.detached) continue;
                try {
                    h.module.onPackageReady(readyParam);
                } catch (Throwable t) {
                    Slog.e(TAG, "onPackageReady threw in " + h.module.getClass().getName(), t);
                }
            }
        } catch (Throwable t) {
            Slog.e(TAG, "Failed to load modern module " + modulePath, t);
        }
    }

    private static List<String> readLines(InputStream is) throws Exception {
        if (is == null) return Collections.emptyList();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                lines.add(line);
            }
        }
        return lines;
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static final class Holder {
        final XposedModule module;
        volatile boolean detached;

        Holder(XposedModule module) {
            this.module = module;
        }

        void detach() {
            detached = true;
        }
    }

    private static final class ModuleLoadedParamImpl implements XposedModuleInterface.ModuleLoadedParam {
        private final boolean mIsSystemServer;
        private final String mProcessName;

        ModuleLoadedParamImpl(boolean isSystemServer, String processName) {
            mIsSystemServer = isSystemServer;
            mProcessName = processName;
        }

        @Override
        public boolean isSystemServer() {
            return mIsSystemServer;
        }

        @NonNull
        @Override
        public String getProcessName() {
            return mProcessName;
        }
    }

    private static class PackageLoadedParamImpl implements XposedModuleInterface.PackageLoadedParam {
        private final String mPackageName;
        private final ApplicationInfo mAppInfo;
        private final boolean mIsFirstPackage;
        private final ClassLoader mClassLoader;

        PackageLoadedParamImpl(String packageName, ApplicationInfo appInfo, boolean isFirstPackage, ClassLoader classLoader) {
            mPackageName = packageName;
            mAppInfo = appInfo;
            mIsFirstPackage = isFirstPackage;
            mClassLoader = classLoader;
        }

        @NonNull
        @Override
        public String getPackageName() {
            return mPackageName;
        }

        @NonNull
        @Override
        public ApplicationInfo getApplicationInfo() {
            return mAppInfo;
        }

        @Override
        public boolean isFirstPackage() {
            return mIsFirstPackage;
        }

        @NonNull
        @Override
        public ClassLoader getDefaultClassLoader() {
            return mClassLoader;
        }
    }

    private static final class PackageReadyParamImpl extends PackageLoadedParamImpl implements XposedModuleInterface.PackageReadyParam {
        PackageReadyParamImpl(String packageName, ApplicationInfo appInfo, boolean isFirstPackage, ClassLoader classLoader) {
            super(packageName, appInfo, isFirstPackage, classLoader);
        }

        @NonNull
        @Override
        public ClassLoader getClassLoader() {
            return getDefaultClassLoader();
        }

        @NonNull
        @Override
        public AppComponentFactory getAppComponentFactory() {
            // 容器没有走 AppComponentFactory 流程，返回一个基类实例兜底
            return AppComponentFactoryHolder.INSTANCE;
        }
    }

    private static final class AppComponentFactoryHolder {
        static final AppComponentFactory INSTANCE = create();

        static AppComponentFactory create() {
            try {
                return new AppComponentFactory();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
