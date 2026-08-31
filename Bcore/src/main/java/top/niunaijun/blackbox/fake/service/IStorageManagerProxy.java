package top.niunaijun.blackbox.fake.service;

import android.os.IInterface;
import android.os.storage.StorageVolume;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.android.os.mount.BRIMountServiceStub;
import black.android.os.storage.BRIStorageManagerStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/10/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IStorageManagerProxy extends BinderInvocationStub {

    public static final String TAG = "IStorageManagerProxy";

    public IStorageManagerProxy() {
        super(BRServiceManager.get().getService("mount"));
    }

    @Override
    protected Object getWho() {
        IInterface mount;
        if (BuildCompat.isOreo()) {
            mount = BRIStorageManagerStub.get().asInterface(BRServiceManager.get().getService("mount"));
        } else {
            mount = BRIMountServiceStub.get().asInterface(BRServiceManager.get().getService("mount"));
        }
        return mount;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("mount");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getVolumeList")
    public static class GetVolumeList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            StorageVolume[] proxied = queryProxiedVolumeList(who, method, args);
            // 新版 Environment/StorageManager 按卷目录做前缀匹配，只返回被改写为
            // 虚拟目录的卷会让查询真实路径（/storage/emulated/0）的调用抛
            // IllegalArgumentException: Failed to find storage device。
            // 因此把宿主真实卷一并返回：真实路径命中真实卷，虚拟路径命中改写卷。
            try {
                StorageVolume[] real = queryRealVolumeList(who, method, args);
                Slog.d(TAG, "getVolumeList args=" + java.util.Arrays.toString(args)
                        + " proxied=" + (proxied == null ? -1 : proxied.length)
                        + " real=" + (real == null ? -1 : real.length));
                if (real == null || real.length == 0) {
                    return proxied;
                }
                if (proxied == null || proxied.length == 0) {
                    return real;
                }
                StorageVolume[] merged = new StorageVolume[real.length + proxied.length];
                System.arraycopy(real, 0, merged, 0, real.length);
                System.arraycopy(proxied, 0, merged, real.length, proxied.length);
                return merged;
            } catch (Throwable t) {
                Slog.w(TAG, "getVolumeList merge failed", t);
                return proxied;
            }
        }

        private StorageVolume[] queryProxiedVolumeList(Object who, Method method, Object[] args) throws Throwable {
            if (args == null) {
                StorageVolume[] volumeList = BlackBoxCore.getBStorageManager().getVolumeList(BActivityThread.getBUid(), null, 0, BActivityThread.getUserId());
                if (volumeList == null) {
                    return (StorageVolume[]) method.invoke(who, args);
                }
                return volumeList;
            }
            try {
                int uid = (int) args[0];
                String packageName = (String) args[1];
                int flags = (int) args[2];
                StorageVolume[] volumeList = BlackBoxCore.getBStorageManager().getVolumeList(uid, packageName, flags, BActivityThread.getUserId());
                if (volumeList == null) {
                    return (StorageVolume[]) method.invoke(who, args);
                }
                return volumeList;
            } catch (Throwable t) {
                return (StorageVolume[]) method.invoke(who, args);
            }
        }

        private StorageVolume[] queryRealVolumeList(Object who, Method method, Object[] args) throws Throwable {
            if (args == null) {
                return null;
            }
            // getVolumeList 首参是 userId（调用方 user），直接透传即可拿到
            // 宿主视角的真实卷列表（真实路径），不要替换成 uid
            return (StorageVolume[]) method.invoke(who, args);
        }
    }

    @ProxyMethod("mkdirs")
    public static class mkdirs extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
