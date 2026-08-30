package top.niunaijun.blackbox.utils;

import java.util.Arrays;
import java.util.HashSet;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;

public class MethodParameterUtils {

    /**
     * 把参数中所有 AttributionSource 的身份改写为宿主（包名 + uid）。
     * 现代系统服务（ConnectivityService 等）会校验 attribution 包名是否属于调用 uid，
     * 虚拟包名 + 宿主 uid 的组合会触发 SecurityException
     * （"Package xxx does not belong to xxx"），导致应用崩溃。
     */
    public static void fixAttributionSourceArgs(Object[] args) {
        if (args == null) {
            return;
        }
        int uid = BuildCompat.isUpsideDownCake() ? BlackBoxCore.getHostUid() : BActivityThread.getBUid();
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg != null && "android.content.AttributionSource".equals(arg.getClass().getName())) {
                ContextCompat.fixAttributionSourceState(arg, uid);
            }
        }
    }

    public static <T> T getFirstParam(Object[] args, Class<T> tClass) {
        if (args == null) {
            return null;
        }
        int index = ArrayUtils.indexOfFirst(args, tClass);
        if (index != -1) {
            return (T) args[index];
        }
        return null;
    }

    /**
     * Android 14 (API 34) 起 binder 层 PackageManager 的 flags 参数由 int 改为 long，
     * 反射拿到的是 java.lang.Long，直接强转 (int)/(Integer) 会抛 ClassCastException。
     * 统一用 Number 接口做安全转换，兼容 int/long 两种版本。
     */
    public static int toIntValue(Object arg) {
        return arg instanceof Number ? ((Number) arg).intValue() : 0;
    }

    /**
     * 取参数数组中第一个 Number 参数的 int 值（用于兼容 flags 位置在新旧系统上有差异的接口）。
     */
    public static int getFirstNumberValue(Object[] args) {
        if (args == null) {
            return 0;
        }
        for (Object arg : args) {
            if (arg instanceof Number) {
                return ((Number) arg).intValue();
            }
        }
        return 0;
    }

    public static String replaceFirstAppPkg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if (BlackBoxCore.get().isInstalled(value, BActivityThread.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                    return value;
                }
            }
        }
        return null;
    }

    public static void replaceAllAppPkg(Object[] args) {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null)
                continue;
            if (args[i] instanceof String) {
                String value = (String) args[i];
                if (BlackBoxCore.get().isInstalled(value, BActivityThread.getUserId())) {
                    args[i] = BlackBoxCore.getHostPkg();
                }
            }
        }
    }

    public static void replaceFirstUid(Object[] args) {
        if (args == null)
            return;
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int uid = (int) args[i];
                if (uid == BActivityThread.getBUid()) {
                    args[i] = BlackBoxCore.getHostUid();
                }
            }
        }
    }

    public static void replaceLastUid(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, Integer.class);
        if (index != -1) {
            int uid = (int) args[index];
            if (uid == BActivityThread.getBUid()) {
                args[index] = BlackBoxCore.getHostUid();
            }
        }
    }

    /**
     * 把参数中所有等于虚拟 uid 的 int 替换为宿主 uid。
     * 部分新版接口（如 checkPermissionForDevice(permission, pid, uid, deviceId)）
     * 的最后一个 int 参数是 deviceId 而非 uid，不能使用 replaceLastUid。
     */
    public static void replaceAppUid(Object[] args) {
        if (args == null) {
            return;
        }
        int uid = BActivityThread.getBUid();
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer && (Integer) args[i] == uid) {
                args[i] = BlackBoxCore.getHostUid();
            }
        }
    }

    public static String replaceLastAppPkg(Object[] args) {
        int index = ArrayUtils.indexOfLast(args, String.class);
        if (index != -1) {
            String pkg = (String) args[index];
            if (BlackBoxCore.get().isInstalled(pkg, BActivityThread.getUserId())) {
                args[index] = BlackBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static String replaceSequenceAppPkg(Object[] args, int sequence) {
        int index = ArrayUtils.indexOf(args, String.class, sequence);
        if (index != -1) {
            String pkg = (String) args[index];
            if (BlackBoxCore.get().isInstalled(pkg, BActivityThread.getUserId())) {
                args[index] = BlackBoxCore.getHostPkg();
            }
            return pkg;
        }
        return null;
    }

    public static int getParamsIndex(Class[] args, Class<?> type) {
        for (int i = 0; i < args.length; i++) {
            Class obj = args[i];
            if (obj.equals(type)) {
                return i;
            }
        }
        return -1;
    }

    public static int getIndex(Object[] args, Class<?> type) {
        return getIndex(args, type, 0);
    }

    public static int getIndex(Object[] args, Class<?> type, int start) {
        for (int i = start; i < args.length; i++) {
            Object obj = args[i];
            if (obj != null && obj.getClass() == type) {
                return i;
            }
            if (type.isInstance(obj)) {
                return i;
            }
        }
        return -1;
    }

    public static Class<?>[] getAllInterface(Class clazz) {
        HashSet<Class<?>> classes = new HashSet<>();
        getAllInterfaces(clazz, classes);
        Class<?>[] result = new Class[classes.size()];
        classes.toArray(result);
        return result;
    }


    public static void getAllInterfaces(Class clazz, HashSet<Class<?>> interfaceCollection) {
        Class<?>[] classes = clazz.getInterfaces();
        if (classes.length != 0) {
            interfaceCollection.addAll(Arrays.asList(classes));
        }
        if (clazz.getSuperclass() != Object.class) {
            getAllInterfaces(clazz.getSuperclass(), interfaceCollection);
        }
    }


}
