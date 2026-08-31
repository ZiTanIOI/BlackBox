//
// Created for Android 16 compat.
// 容器的路径重定向原来只覆盖两层：Java 层 OsStub 代理（libcore.io.Os）和
// java.io.UnixFileSystem 的 JNI 方法替换。native 代码（模块 .so、加固壳等）
// 直接调用 libc 的 fopen/open/mkdir 等会完全绕过重定向，落到真实路径后被
// Android 11+ 的沙盒拒绝（尤其 /storage/emulated/0/Android/data/<其他包>）。
//
// 这里通过 GOT/PLT hook 解决：遍历本进程加载的 /data/ 下的 ELF（应用自身
// 的库 + Xposed 模块的库），把 fopen/open/openat/mkdir/fstatat/access 等
// 导入表项（JUMP_SLOT / GLOB_DAT）改写为本文件的包装函数。包装函数对路径
// 参数做 IO::redirectPath 后再调真实函数。libc 内部调用与系统库不受影响。
//

#ifndef BLACKBOX_NATIVEIOHOOK_H
#define BLACKBOX_NATIVEIOHOOK_H

class NativeIOHook {
public:
    // 扫描当前已加载库并打补丁。可重复调用（新库加载后刷新），幂等。
    static void install();
};

#endif //BLACKBOX_NATIVEIOHOOK_H
