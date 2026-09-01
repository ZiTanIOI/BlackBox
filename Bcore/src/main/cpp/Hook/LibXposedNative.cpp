#include "LibXposedNative.h"

#include <dlfcn.h>
#include <cstring>

#include <mutex>
#include <set>
#include <string>
#include <vector>

#include "../Log.h"
#include "NativeIOHook.h"

// 与 LSPosed wiki Native-Hook 的结构约定保持一致
typedef int (*HookFunType)(void *func, void *replace, void **backup);
typedef int (*UnhookFunType)(void *func);
typedef void (*NativeOnModuleLoaded)(const char *name, void *handle);

typedef struct {
    uint32_t version;
    HookFunType hook_func;
    UnhookFunType unhook_func;
} NativeAPIEntries;

typedef NativeOnModuleLoaded (*NativeInit)(const NativeAPIEntries *entries);

// LSPosed 下发给模块的 native API 版本；模块仅用于日志/特性判断，无强校验
static const uint32_t kNativeApiVersion = 100;

namespace {

std::mutex g_mutex;
std::set<std::string> g_pending;                    // 待加载注册的 so 名（basename）
std::vector<NativeOnModuleLoaded> g_callbacks;      // 已注册模块的库加载回调

int hookFunc(void *func, void *replace, void **backup) {
    return NativeIOHook::addNamedHook(func, replace, backup) ? 0 : -1;
}

int unhookFunc(void *func) {
    return NativeIOHook::removeNamedHook(func) ? 0 : -1;
}

} // namespace

void LibXposedNative::addPendingLib(const char *libName) {
    if (libName == nullptr || libName[0] == '\0') return;
    std::lock_guard<std::mutex> lock(g_mutex);
    g_pending.insert(libName);
}

void LibXposedNative::onDlopen(const char *filename, void *handle) {
    if (filename == nullptr || handle == nullptr) return;
    const char *slash = strrchr(filename, '/');
    const char *base = slash != nullptr ? slash + 1 : filename;

    bool registerNow = false;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        auto it = g_pending.find(base);
        if (it != g_pending.end()) {
            g_pending.erase(it);
            registerNow = true;
        }
    }

    if (registerNow) {
        auto init = reinterpret_cast<NativeInit>(dlsym(handle, "native_init"));
        if (init == nullptr) {
            ALOGD("LibXposedNative: %s has no native_init symbol", base);
        } else {
            NativeAPIEntries entries{kNativeApiVersion, hookFunc, unhookFunc};
            // 不能持有 g_mutex 调用：native_init 里会经 hook_func 请求加锁
            NativeOnModuleLoaded cb = init(&entries);
            if (cb != nullptr) {
                std::lock_guard<std::mutex> lock(g_mutex);
                g_callbacks.push_back(cb);
                ALOGD("LibXposedNative: native_init of %s registered (%zu callbacks)", base,
                      g_callbacks.size());
            } else {
                ALOGD("LibXposedNative: native_init of %s returned null callback", base);
            }
        }
    }

    // 广播给已注册模块。注册中的模块从下一个库开始收（与 LSPosed 语义一致），
    // 模块自身按库名过滤（如只关心 libil2cpp.so）。
    std::vector<NativeOnModuleLoaded> callbacks;
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        callbacks = g_callbacks;
    }
    for (auto cb : callbacks) {
        cb(filename, handle);
    }
}
