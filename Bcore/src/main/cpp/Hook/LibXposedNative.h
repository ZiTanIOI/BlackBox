//
// libxposed 102 API 的 native 模块支持（对齐 LSPosed wiki Native-Hook 约定）。
//
// 模块在 META-INF/xposed/native_init.list 里列出带 native_init 导出符号的 so；
// 按 LSPosed 规范，模块 Java 入口自行 System.loadLibrary 加载它。该加载发生在
// 本进程被 GOT 补过的 dlopen 包装里（NativeIOHook 的 my_dlopen /
// my_android_dlopen_ext），onDlopen 按名单 dlsym("native_init") 调用并拿到
// NativeOnModuleLoaded 回调；此后每次 dlopen 成功都回调一次 (name, handle)，
// 模块据此对新加载的库做 native hook（ZTil2cppDumper 在 libil2cpp.so 加载时
// 启动 dump）。
//
// 选择"拦截模块自加载"而非框架直接 dlopen 的原因：模块 so 位于容器数据目录，
// 宿主 classloader-namespace 的可达路径不包含它，直接 dlopen 会被隔离命名空间
// 拒绝；模块自身经 ModuleClassLoader 的命名空间加载则天然可行。
//

#ifndef BLACKBOX_LIBXPOSEDNATIVE_H
#define BLACKBOX_LIBXPOSEDNATIVE_H

class LibXposedNative {
public:
    // Java 侧在跑模块 Java 入口之前，把该模块 native_init.list 里的 so 名登记进来
    static void addPendingLib(const char *libName);

    // 每个 dlopen 成功后由 NativeIOHook 的 dlopen 包装调用：
    // 命中待注册名单则调用其 native_init 完成注册，并向已注册模块广播库加载回调
    static void onDlopen(const char *filename, void *handle);
};

#endif //BLACKBOX_LIBXPOSEDNATIVE_H
