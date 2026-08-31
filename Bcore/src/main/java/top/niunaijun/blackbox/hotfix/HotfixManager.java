package top.niunaijun.blackbox.hotfix;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexClassLoader;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;

/**
 * 简易热修复（dexElements 前插）：
 * 分身应用的 PathClassLoader 由容器在 handleBindApplication 里创建，
 * 在 makeApplication 之前没有任何业务类被加载，此时把补丁 dex 装进
 * 临时 DexClassLoader，取出其 DexPathList.Element 数组前插到应用类加载器的
 * dexElements 头部，后续 loadClass 会先命中补丁里的类，实现类替换。
 */
public class HotfixManager {
    private static final String TAG = "HotfixManager";

    public static void inject(ClassLoader appClassLoader, String packageName, int userId) {
        if (!(appClassLoader instanceof BaseDexClassLoader)) {
            return;
        }
        try {
            File patch = BEnvironment.getHotfixPatchFile(userId, packageName);
            if (!patch.isFile()) {
                return;
            }
            File odexDir = BEnvironment.getHotfixOdexDir(userId, packageName);
            FileUtils.mkdirs(odexDir.getAbsolutePath());

            // parent 指向应用类加载器：补丁类运行期缺的依赖会顺着 parent 找回原 dex
            DexClassLoader patchLoader = new DexClassLoader(patch.getAbsolutePath(),
                    odexDir.getAbsolutePath(), patch.getParent(), appClassLoader);
            Object patchElements = getFieldValue(getFieldValue(patchLoader, "pathList"), "dexElements");
            int patchCount = patchElements == null ? 0 : Array.getLength(patchElements);
            if (patchCount == 0) {
                Slog.w(TAG, "hotfix dex invalid: " + patch.getAbsolutePath());
                return;
            }

            Object appPathList = getFieldValue(appClassLoader, "pathList");
            Object appElements = getFieldValue(appPathList, "dexElements");
            int appCount = Array.getLength(appElements);

            Object merged = Array.newInstance(appElements.getClass().getComponentType(), patchCount + appCount);
            System.arraycopy(patchElements, 0, merged, 0, patchCount);
            System.arraycopy(appElements, 0, merged, patchCount, appCount);
            setFieldValue(appPathList, "dexElements", merged);
            Slog.i(TAG, "hotfix injected " + patchCount + " element(s) before " + appCount
                    + " for " + packageName + ", user " + userId + ": " + patch.getAbsolutePath());
        } catch (Throwable t) {
            Slog.e(TAG, "hotfix inject failed: " + packageName, t);
        }
    }

    public static void clearPatch(String packageName, int userId) {
        FileUtils.deleteDir(BEnvironment.getHotfixOdexDir(userId, packageName));
        File patch = BEnvironment.getHotfixPatchFile(userId, packageName);
        if (patch.exists()) {
            FileUtils.deleteDir(patch);
        }
    }

    /**
     * 补丁文件被覆盖更新后调用：旧补丁编译出的 oat 缓存与新 dex 内容不匹配，
     * 必须清掉，否则新补丁的类可能解析不出来
     */
    public static void clearOdexCache(String packageName, int userId) {
        FileUtils.deleteDir(BEnvironment.getHotfixOdexDir(userId, packageName));
    }

    public static void clearPatchAllUsers(String packageName) {
        File[] userDirs = BEnvironment.getHotfixDir().listFiles();
        if (userDirs == null) {
            return;
        }
        for (File userDir : userDirs) {
            if (userDir.isDirectory()) {
                FileUtils.deleteDir(new File(userDir, packageName + ".dex"));
                FileUtils.deleteDir(new File(userDir, packageName + "_odex"));
            }
        }
    }

    private static Object getFieldValue(Object target, String name)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setFieldValue(Object target, String name, Object value)
            throws NoSuchFieldException, IllegalAccessException {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
