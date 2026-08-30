package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.net.BRIConnectivityManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ProxyMethods;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.utils.MethodParameterUtils;

/**
 * Created by Milk on 4/12/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@ScanClass(VpnCommonProxy.class)
public class IConnectivityManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IConnectivityManagerProxy";

    public IConnectivityManagerProxy() {
        super(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIConnectivityManagerStub.get().asInterface(BRServiceManager.get().getService(Context.CONNECTIVITY_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CONNECTIVITY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * 现代系统服务会校验 attribution 包名是否属于调用 uid，虚拟包名 + 宿主 uid
     * 的组合抛 SecurityException（"Package xxx does not belong to xxx"）。
     * 调用真实服务前把参数中的 AttributionSource 改写为宿主身份。
     */
    @ProxyMethods({
            "getNetworkCapabilities",
            "getLinkProperties",
            "getNetworkInfo",
            "getAllNetworkInfo",
            "getActiveNetworkInfo",
            "requestNetwork",
            "registerNetworkCallback",
            "registerDefaultNetworkCallback",
            "reportNetworkConnectivity"
    })
    public static class FixAttributionSource extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceAllAppPkg(args);
            MethodParameterUtils.fixAttributionSourceArgs(args);
            return method.invoke(who, args);
        }
    }
}
