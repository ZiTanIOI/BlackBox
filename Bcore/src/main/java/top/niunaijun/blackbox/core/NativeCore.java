package top.niunaijun.blackbox.core;


import android.os.Process;

import androidx.annotation.Keep;

import java.io.File;
import java.util.List;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;

import static top.niunaijun.blackbox.core.env.BEnvironment.EMPTY_JAR;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class NativeCore {
    public static final String TAG = "NativeCore";

    static {
        new File("");
        System.loadLibrary("blackbox");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    /**
     * 是否对已加载的 so 做 libc GOT hook（NativeIOHook）。
     * 反作弊类应用可能扫描被改写的 GOT 表项，可在 bindApplication 阶段按包名关掉。
     */
    public static native void enableLibcHook(boolean enabled);

    public static native void hideXposed();

    /**
     * 应用或 Xposed 模块在运行期加载了新的 native 库后调用，
     * 重新扫描并为新库的 GOT 打上 IO 重定向补丁。
     */
    public static native void rescanIOHook();

    /**
     * 对容器内解压的 libunity.so 打“安装位置校验”补丁（见 UnityCompatPatch）。
     * 纯文件改写，不注入也不 hook，所以不受 libc hook 开关影响。
     * 返回 true 表示本次真的改写了文件。
     */
    public static native boolean patchUnityCompat(String libDir);

    /**
     * 登记 libxposed 102 模块 native_init.list 里的 so 名。模块 Java 入口
     * System.loadLibrary 这些 so 时，native 层的 dlopen 包装按名单拦截，
     * 调用 so 导出的 native_init 完成回调注册，此后每次 dlopen 成功都会
     * 通知模块（libil2cpp.so 加载即 dump 的触发源）。
     */
    public static native void addXposedNativeLibs(String[] libNames);

    public static void dumpDex(ClassLoader classLoader, String packageName) {
        List<Long> cookies = DexFileCompat.getCookies(classLoader);
        for (Long cookie : cookies) {
            if (cookie == 0)
                continue;
//            File file = new File(BlackBoxCore.get().getDexDumpDir(), packageName);
//            FileUtils.mkdirs(file);
//            dumpDex(cookie, file.getAbsolutePath());
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
        // 系统uid
        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
            return origCallingUid;
        // 非用户应用
        if (origCallingUid > Process.LAST_APPLICATION_UID)
            return origCallingUid;

        if (origCallingUid == BlackBoxCore.getHostUid()) {
//            Log.d(TAG, "origCallingUid: " + origCallingUid + " => " + BActivityThread.getCallingBUid());
            return BActivityThread.getCallingBUid();
        }
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static long[] loadEmptyDex() {
        try {
            DexFile dexFile = new DexFile(EMPTY_JAR);
            List<Long> cookies = DexFileCompat.getCookies(dexFile);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            return longs;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new long[]{};
    }
}
