package top.niunaijun.blackbox.core;

import java.io.File;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;

/**
 * 按应用禁用 libc GOT hook（NativeIOHook）的开关。
 * <p>
 * 配置是一个空标记文件：blackbox/hotfix/chook_disable/u&lt;userId&gt;/&lt;packageName&gt;，
 * 与热修复补丁同思路——同 uid 直接读文件，无需跨进程调用，进程每次启动都拿到最新值。
 * 部分带反作弊的应用会扫描 GOT/PLT 被改写的导入函数，勾选后该应用的进程
 * 不再对 so 做 GOT hook（Java 层重定向与 JniHook 不受影响）。
 */
public class LibcHookConfig {
    public static boolean isDisabled(int userId, String packageName) {
        try {
            return BEnvironment.getLibcHookDisableFile(userId, packageName).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setDisabled(int userId, String packageName, boolean disabled) {
        File file = BEnvironment.getLibcHookDisableFile(userId, packageName);
        try {
            if (disabled) {
                FileUtils.mkdirs(file.getParentFile().getAbsolutePath());
                if (!file.exists()) {
                    file.createNewFile();
                }
            } else if (file.exists()) {
                file.delete();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
