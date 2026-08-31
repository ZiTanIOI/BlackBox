package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.app.BRILocaleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * LocaleManager 的 setApplicationLocales 需要 CHANGE_CONFIGURATION 系统权限，
 * 虚拟包名会被真实 LocaleManagerService 拒绝（SecurityException），并把调用方
 * （如哔哩哔哩 Localization 类初始化）拖入 ExceptionInInitializerError →
 * NoClassDefFoundError 连锁崩溃。
 * 容器暂不支持 per-app 语言设置，这里吞掉该调用保证应用不崩溃。
 */
public class ILocaleManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocaleManagerProxy";

    public ILocaleManagerProxy() {
        super(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected Object getWho() {
        return BRILocaleManagerStub.get().asInterface(BRServiceManager.get().getService("locale"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("locale");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("setApplicationLocales")
    public static class SetApplicationLocales extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // 忽略语言设置请求，避免 SecurityException 引发调用方初始化失败
            return null;
        }
    }
}
