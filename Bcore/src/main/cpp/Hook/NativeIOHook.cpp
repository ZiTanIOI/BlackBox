//
// NativeIOHook 实现。
//
// 方案：通过 dl_iterate_phdr 枚举本进程加载的 ELF（应用自身的库 + Xposed
// 模块的库 + art apex 的 libart/libnativeloader），遍历其 .dynamic ->
// .rela.plt / .rela.dyn，命中导入符号表后改写 GOT 表项。相比 inline hook
// libc 无需做指令重定位，安全性高得多。
//
// 并发说明：dl_iterate_phdr 的回调全程持有 linker 锁，期间不可能有并发的
// dlopen/dlclose 改动映射，库列表与内存内容都是稳定快照；且 linker 只会
// 列出已完成加载的库，不会撞上加载到一半的半映射状态。
//

#include "NativeIOHook.h"

#include <cctype>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <android/dlext.h>
#include <dirent.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "../IO.h"
#include "../Log.h"

#include <errno.h>

#if defined(__aarch64__)

namespace {

// 诊断：被补丁库的路径类调用拿到 EACCES 时打印路径，便于定位漏挂/漏重定向
inline void logDenied(const char *symbol, const char *path) {
    if (path != nullptr) {
        ALOGD("NativeIOHook: %s EACCES for %s", symbol, path);
    }
}

// ---------------------------------------------------------------------------
// 真实函数指针与包装函数
// ---------------------------------------------------------------------------

int (*real_open)(const char *, int, ...);
int (*real_openat)(int, const char *, int, ...);
int (*real___open_2)(const char *, int);
int (*real___openat_2)(int, const char *, int);
FILE *(*real_fopen)(const char *, const char *);
int (*real_mkdir)(const char *, mode_t);
int (*real_mkdirat)(int, const char *, mode_t);
int (*real_access)(const char *, int);
int (*real_faccessat)(int, const char *, int, int);
int (*real_fstatat)(int, const char *, struct stat *, int);
int (*real_fstatat64)(int, const char *, struct stat64 *, int);
int (*real_unlink)(const char *);
int (*real_unlinkat)(int, const char *, int);
int (*real_rename)(const char *, const char *);
int (*real_renameat)(int, const char *, int, const char *);
int (*real_rmdir)(const char *);
int (*real_remove)(const char *);
int (*real_chmod)(const char *, mode_t);
int (*real_truncate)(const char *, off_t);
DIR *(*real_opendir)(const char *);
void *(*real_dlopen)(const char *, int);
void *(*real_android_dlopen_ext)(const char *, int, const android_dlextinfo *);
int (*real_open64)(const char *, int, ...);
int (*real_openat64)(int, const char *, int, ...);
int (*real_stat)(const char *, struct stat *);
int (*real_lstat)(const char *, struct stat *);
int (*real_stat64)(const char *, struct stat64 *);
int (*real_fstat)(int, struct stat *);
int (*real_fstat64)(int, struct stat64 *);
int (*real_faccessat2)(int, const char *, int, int);

// redirectPath 命中规则时返回 malloc 的新串，否则原样返回入参
inline const char *tryRedirect(const char *path) {
    if (path == nullptr) return nullptr;
    return IO::redirectPath(path);
}

inline void releaseRedirect(const char *orig, const char *redirected) {
    if (redirected != nullptr && redirected != orig) {
        free(const_cast<char *>(redirected));
    }
}

int my_open(const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    const char *redir = tryRedirect(path);
    int r = real_open(redir, flags, mode);
    if (r != 0 && errno == EACCES) logDenied("open", path);
    releaseRedirect(path, redir);
    return r;
}

int my_openat(int dirfd, const char *path, int flags, ...) {
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) {
        va_list ap;
        va_start(ap, flags);
        mode = static_cast<mode_t>(va_arg(ap, int));
        va_end(ap);
    }
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_openat(dirfd, path, flags, mode);
    }
    const char *redir = tryRedirect(path);
    int r = real_openat(dirfd, redir, flags, mode);
    if (r != 0 && errno == EACCES) logDenied("openat", path);
    releaseRedirect(path, redir);
    return r;
}

int my___open_2(const char *path, int flags) {
    const char *redir = tryRedirect(path);
    int r = real___open_2(redir, flags);
    if (r != 0 && errno == EACCES) logDenied("__open_2", path);
    releaseRedirect(path, redir);
    return r;
}

int my___openat_2(int dirfd, const char *path, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real___openat_2(dirfd, path, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real___openat_2(dirfd, redir, flags);
    if (r != 0 && errno == EACCES) logDenied("__openat_2", path);
    releaseRedirect(path, redir);
    return r;
}

FILE *my_fopen(const char *path, const char *mode) {
    const char *redir = tryRedirect(path);
    FILE *f = real_fopen(redir, mode);
    if (f == nullptr && errno == EACCES) logDenied("fopen", path);
    releaseRedirect(path, redir);
    return f;
}

int my_mkdir(const char *path, mode_t mode) {
    const char *redir = tryRedirect(path);
    int r = real_mkdir(redir, mode);
    if (r != 0 && errno == EACCES) logDenied("mkdir", path);
    releaseRedirect(path, redir);
    return r;
}

int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_mkdirat(dirfd, path, mode);
    }
    const char *redir = tryRedirect(path);
    int r = real_mkdirat(dirfd, redir, mode);
    if (r != 0 && errno == EACCES) logDenied("mkdirat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_access(const char *path, int mode) {
    const char *redir = tryRedirect(path);
    int r = real_access(redir, mode);
    if (r != 0 && errno == EACCES) logDenied("access", path);
    releaseRedirect(path, redir);
    return r;
}

int my_faccessat(int dirfd, const char *path, int mode, int flag) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_faccessat(dirfd, path, mode, flag);
    }
    const char *redir = tryRedirect(path);
    int r = real_faccessat(dirfd, redir, mode, flag);
    if (r != 0 && errno == EACCES) logDenied("faccessat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_fstatat(dirfd, path, buf, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_fstatat(dirfd, redir, buf, flags);
    if (r != 0 && errno == EACCES) logDenied("fstatat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_fstatat64(dirfd, path, buf, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_fstatat64(dirfd, redir, buf, flags);
    if (r != 0 && errno == EACCES) logDenied("fstatat64", path);
    releaseRedirect(path, redir);
    return r;
}

int my_unlink(const char *path) {
    const char *redir = tryRedirect(path);
    int r = real_unlink(redir);
    releaseRedirect(path, redir);
    return r;
}

int my_unlinkat(int dirfd, const char *path, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_unlinkat(dirfd, path, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_unlinkat(dirfd, redir, flags);
    releaseRedirect(path, redir);
    return r;
}

int my_rename(const char *oldp, const char *newp) {
    const char *ro = tryRedirect(oldp);
    const char *rn = tryRedirect(newp);
    int r = real_rename(ro, rn);
    releaseRedirect(oldp, ro);
    releaseRedirect(newp, rn);
    return r;
}

int my_renameat(int olddirfd, const char *oldp, int newdirfd, const char *newp) {
    if (olddirfd == AT_FDCWD || newdirfd == AT_FDCWD) {
        const char *ro = olddirfd == AT_FDCWD ? tryRedirect(oldp) : oldp;
        const char *rn = newdirfd == AT_FDCWD ? tryRedirect(newp) : newp;
        int r = real_renameat(olddirfd, ro, newdirfd, rn);
        if (ro != oldp) releaseRedirect(oldp, ro);
        if (rn != newp) releaseRedirect(newp, rn);
        return r;
    }
    return real_renameat(olddirfd, oldp, newdirfd, newp);
}

int my_rmdir(const char *path) {
    const char *redir = tryRedirect(path);
    int r = real_rmdir(redir);
    releaseRedirect(path, redir);
    return r;
}

int my_remove(const char *path) {
    const char *redir = tryRedirect(path);
    int r = real_remove(redir);
    releaseRedirect(path, redir);
    return r;
}

int my_chmod(const char *path, mode_t mode) {
    const char *redir = tryRedirect(path);
    int r = real_chmod(redir, mode);
    releaseRedirect(path, redir);
    return r;
}

int my_truncate(const char *path, off_t length) {
    const char *redir = tryRedirect(path);
    int r = real_truncate(redir, length);
    releaseRedirect(path, redir);
    return r;
}

DIR *my_opendir(const char *path) {
    const char *redir = tryRedirect(path);
    DIR *d = real_opendir(redir);
    if (d == nullptr && errno == EACCES) logDenied("opendir", path);
    releaseRedirect(path, redir);
    return d;
}

int my_stat(const char *path, struct stat *buf) {
    const char *redir = tryRedirect(path);
    int r = real_stat(redir, buf);
    if (r != 0 && errno == EACCES) logDenied("stat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_lstat(const char *path, struct stat *buf) {
    const char *redir = tryRedirect(path);
    int r = real_lstat(redir, buf);
    if (r != 0 && errno == EACCES) logDenied("lstat", path);
    releaseRedirect(path, redir);
    return r;
}

int my_stat64(const char *path, struct stat64 *buf) {
    const char *redir = tryRedirect(path);
    int r = real_stat64(redir, buf);
    if (r != 0 && errno == EACCES) logDenied("stat64", path);
    releaseRedirect(path, redir);
    return r;
}

// fd 版本无路径可重定向，仅做 EACCES 观测
int my_fstat(int fd, struct stat *buf) {
    int r = real_fstat(fd, buf);
    if (r != 0 && errno == EACCES) ALOGD("NativeIOHook: fstat EACCES for fd=%d", fd);
    return r;
}

int my_fstat64(int fd, struct stat64 *buf) {
    int r = real_fstat64(fd, buf);
    if (r != 0 && errno == EACCES) ALOGD("NativeIOHook: fstat64 EACCES for fd=%d", fd);
    return r;
}

int my_faccessat2(int dirfd, const char *path, int mode, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_faccessat2(dirfd, path, mode, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_faccessat2(dirfd, redir, mode, flags);
    if (r != 0 && errno == EACCES) logDenied("faccessat2", path);
    releaseRedirect(path, redir);
    return r;
}

// 运行期动态加载的库（QQ 的插件 so、librealm-jni 等）在加载完成那一刻还
// 没有被补丁；已被补丁的库再 dlopen 新库时会经过这里，立即对新库补丁。
// 包装必须等 real 返回后才触发重扫，否则会在 linker 加载中途扫描半成品映射
void *my_dlopen(const char *filename, int flags) {
    void *handle = real_dlopen(filename, flags);
    if (handle != nullptr) {
        NativeIOHook::install();
    }
    return handle;
}

void *my_android_dlopen_ext(const char *filename, int flags, const android_dlextinfo *info) {
    void *handle = real_android_dlopen_ext(filename, flags, info);
    if (handle != nullptr) {
        NativeIOHook::install();
    }
    return handle;
}

struct HookEntry {
    const char *name;
    void *hook;
    void **real;
};

HookEntry g_entries[] = {
        {"open",         (void *) my_open,         (void **) &real_open},
        {"openat",       (void *) my_openat,       (void **) &real_openat},
        {"__open_2",     (void *) my___open_2,     (void **) &real___open_2},
        {"__openat_2",   (void *) my___openat_2,   (void **) &real___openat_2},
        {"fopen",        (void *) my_fopen,        (void **) &real_fopen},
        {"mkdir",        (void *) my_mkdir,        (void **) &real_mkdir},
        {"mkdirat",      (void *) my_mkdirat,      (void **) &real_mkdirat},
        {"access",       (void *) my_access,       (void **) &real_access},
        {"faccessat",    (void *) my_faccessat,    (void **) &real_faccessat},
        {"fstatat",      (void *) my_fstatat,      (void **) &real_fstatat},
        {"fstatat64",    (void *) my_fstatat64,    (void **) &real_fstatat64},
        {"unlink",       (void *) my_unlink,       (void **) &real_unlink},
        {"unlinkat",     (void *) my_unlinkat,     (void **) &real_unlinkat},
        {"rename",       (void *) my_rename,       (void **) &real_rename},
        {"renameat",     (void *) my_renameat,     (void **) &real_renameat},
        {"rmdir",        (void *) my_rmdir,        (void **) &real_rmdir},
        {"remove",       (void *) my_remove,       (void **) &real_remove},
        {"chmod",        (void *) my_chmod,        (void **) &real_chmod},
        {"truncate",     (void *) my_truncate,     (void **) &real_truncate},
        {"opendir",      (void *) my_opendir,      (void **) &real_opendir},
        {"dlopen",           (void *) my_dlopen,             (void **) &real_dlopen},
        {"android_dlopen_ext", (void *) my_android_dlopen_ext, (void **) &real_android_dlopen_ext},
        {"open64",           (void *) my_open,               (void **) &real_open64},
        {"openat64",         (void *) my_openat,             (void **) &real_openat64},
        {"stat",             (void *) my_stat,               (void **) &real_stat},
        {"lstat",            (void *) my_lstat,              (void **) &real_lstat},
        {"stat64",           (void *) my_stat64,             (void **) &real_stat64},
        {"fstat",            (void *) my_fstat,              (void **) &real_fstat},
        {"fstat64",          (void *) my_fstat64,            (void **) &real_fstat64},
        {"faccessat2",       (void *) my_faccessat2,         (void **) &real_faccessat2},
};

// resolveRemaining: 通过 dlsym 兜底填充仍未解析的 real 指针
void resolveRealsViaDlsym() {
    for (auto &e: g_entries) {
        if (*e.real == nullptr) {
            *e.real = dlsym(RTLD_DEFAULT, e.name);
        }
    }
}

// ---------------------------------------------------------------------------
// 已处理库缓存。重扫（每次 dlopen 后触发）必须只解析新出现的库，否则大应用
// 启动期会成百次重复解析全部库。base+path 命中且哨兵 GOT 槽仍指向包装函数
// 时跳过；库被 dlclose 后重定位会把 GOT 还原，此时哨兵失效，自然触发重补。
// ---------------------------------------------------------------------------

struct PatchedLib {
    uintptr_t base;
    std::string path;
    uintptr_t sentinelGot;    // 第一个被改写的 GOT 槽地址；0 表示该库无命中符号
    void *sentinelValue;      // 写入哨兵槽的包装函数指针
};

std::vector<PatchedLib> g_patchedLibs;

PatchedLib *findPatched(uintptr_t base) {
    for (auto &p: g_patchedLibs) {
        if (p.base == base) return &p;
    }
    return nullptr;
}

void upsertPatched(uintptr_t base, const std::string &path, uintptr_t sentinelGot, void *sentinelValue) {
    PatchedLib *p = findPatched(base);
    if (p == nullptr) {
        PatchedLib n{};
        n.base = base;
        n.path = path;
        n.sentinelGot = sentinelGot;
        n.sentinelValue = sentinelValue;
        g_patchedLibs.push_back(n);
        return;
    }
    p->path = path;
    p->sentinelGot = sentinelGot;
    p->sentinelValue = sentinelValue;
}

// ---------------------------------------------------------------------------
// 目标库筛选
// ---------------------------------------------------------------------------

bool isDataLibPath(const char *path) {
    // 只补丁应用/模块自己的库（都在 /data 下）；系统库保持原行为
    if (path == nullptr || path[0] == '\0') return false;
    if (strncmp(path, "/data/", 6) != 0) return false;
    if (strstr(path, "libblackbox.so") != nullptr) return false;
    return true;
}

// System.loadLibrary 的实际 dlopen 发生在 libart/libnativeloader 里（不在
// /data 下，上面的全量补丁不会碰它们）。对这两个库只补 dlopen 家族符号，
// 让每次库加载完成都触发一次对新库的补丁。注意不能在 Java 层挂
// System.loadLibrary：Runtime.loadLibrary0 按调用者类解析库名空间，挂掉
// 之后库会被放进 BOOT 名空间导致 dlopen failed。
bool isDlopenOnlyLibPath(const char *path) {
    if (path == nullptr || path[0] == '\0') return false;
    if (strncmp(path, "/apex/com.android.art/", 22) != 0) return false;
    return strstr(path, "/libart.so") != nullptr ||
           strstr(path, "/libnativeloader.so") != nullptr;
}

// ---------------------------------------------------------------------------
// dl_iterate_phdr 遍历补丁
// ---------------------------------------------------------------------------

// GOT 页改写后不再恢复 RELRO 只读：恢复纯属加固，而判断原页属性需要解析
// maps；保持可写对功能无影响（bionic 默认 BIND_NOW，没有延迟绑定回写）
void writeGotEntry(void **got, void *hook) {
    long pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t page = reinterpret_cast<uintptr_t>(got) & ~static_cast<uintptr_t>(pageSize - 1);
    mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE);
    *got = hook;
}

struct ScanCounters {
    int processed;
    int newLibs;
};

// dl_iterate_phdr 回调，全程持有 linker 锁，可安全读写目标库内存
int hookCallback(struct dl_phdr_info *info, size_t, void *data) {
    const char *path = info->dlpi_name;
    bool dlopenOnly = isDlopenOnlyLibPath(path);
    if (!dlopenOnly && !isDataLibPath(path)) return 0;

    auto *counters = static_cast<ScanCounters *>(data);
    counters->processed++;
    uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);

    PatchedLib *cached = findPatched(base);
    if (cached != nullptr && cached->path == path) {
        bool stillHooked;
        if (cached->sentinelGot == 0) {
            stillHooked = true; // 库里本来就没有目标符号，重扫不会有效果
        } else {
            stillHooked = *reinterpret_cast<void **>(cached->sentinelGot) == cached->sentinelValue;
        }
        if (stillHooked) return 0;
    }
    counters->newLibs++;

    ElfW(Dyn) *dyn = nullptr;
    size_t dynMaxEntries = 0;
    for (int i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr) &ph = info->dlpi_phdr[i];
        if (ph.p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<ElfW(Dyn) *>(base + ph.p_vaddr);
            dynMaxEntries = ph.p_memsz / sizeof(ElfW(Dyn));
            break;
        }
    }
    if (dyn == nullptr || dynMaxEntries == 0 || dynMaxEntries > 65536) return 0;

    ElfW(Addr) jmprel = 0, rela = 0, symtab = 0, strtab = 0;
    size_t pltrelsz = 0, relasz = 0, strsz = 0;
    size_t scanned = 0;
    for (ElfW(Dyn) *d = dyn; scanned < dynMaxEntries; ++d, ++scanned) {
        ElfW(Sxword) tag = d->d_tag;
        if (tag == DT_NULL) break;
        switch (tag) {
            case DT_JMPREL:
                jmprel = d->d_un.d_ptr;
                break;
            case DT_PLTRELSZ:
                pltrelsz = d->d_un.d_val;
                break;
            case DT_RELA:
                rela = d->d_un.d_ptr;
                break;
            case DT_RELASZ:
                relasz = d->d_un.d_val;
                break;
            case DT_SYMTAB:
                symtab = d->d_un.d_ptr;
                break;
            case DT_STRTAB:
                strtab = d->d_un.d_ptr;
                break;
            case DT_STRSZ:
                strsz = d->d_un.d_val;
                break;
            default:
                break;
        }
    }
    if ((jmprel == 0 && rela == 0) || symtab == 0 || strtab == 0) return 0;

    // bionic 已把 .dynamic 的 d_ptr 重定位为绝对地址；兜底按"小于 base 加 bias"
    auto abs = [&](ElfW(Addr) v) -> uintptr_t {
        return v >= base ? static_cast<uintptr_t>(v) : base + static_cast<uintptr_t>(v);
    };
    auto *syms = reinterpret_cast<ElfW(Sym) *>(abs(symtab));
    auto *strs = reinterpret_cast<const char *>(abs(strtab));

    uintptr_t firstGot = 0;
    void *firstVal = nullptr;
    auto scan = [&](uintptr_t relAddr, size_t totalBytes) -> bool {
        if (totalBytes == 0 || totalBytes > 4 * 1024 * 1024) return true;
        size_t count = totalBytes / sizeof(ElfW(Rela));
        auto *entries = reinterpret_cast<ElfW(Rela) *>(relAddr);
        for (size_t i = 0; i < count; ++i) {
            uint32_t type = ELF64_R_TYPE(entries[i].r_info);
            if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
            uint32_t symIdx = ELF64_R_SYM(entries[i].r_info);
            // symtab 无独立长度，用 strsz 界定合理范围（每个符号至少对应一个
            // 名字字节），防止损坏的符号索引越界读
            uint64_t symOff = static_cast<uint64_t>(symIdx) * sizeof(ElfW(Sym));
            if (strsz == 0 || symOff >= strsz) continue;
            if (syms[symIdx].st_name >= strsz) continue;
            const char *nm = strs + syms[symIdx].st_name;
            for (auto &e: g_entries) {
                if (dlopenOnly && strcmp(e.name, "dlopen") != 0 &&
                    strcmp(e.name, "android_dlopen_ext") != 0) {
                    continue;
                }
                if (strcmp(nm, e.name) != 0) continue;
                void **got = reinterpret_cast<void **>(base + entries[i].r_offset);
                if (*got == e.hook) break; // 已打过
                if (*e.real == nullptr && *got != nullptr) {
                    *e.real = *got;
                }
                writeGotEntry(got, e.hook);
                if (firstGot == 0) {
                    firstGot = reinterpret_cast<uintptr_t>(got);
                    firstVal = e.hook;
                }
                break;
            }
        }
        return true;
    };

    bool ok = true;
    if (jmprel != 0 && pltrelsz > 0) ok = scan(abs(jmprel), pltrelsz);
    if (ok && rela != 0 && relasz > 0) scan(abs(rela), relasz);
    if (ok) {
        // 重定位表不完整时（异常库）不入缓存，下次重扫补全
        upsertPatched(base, path, firstGot, firstVal);
    }
    return 0;
}

} // namespace

void NativeIOHook::install() {
    static std::mutex installMutex;
    std::lock_guard<std::mutex> lock(installMutex);

    if (real_fopen == nullptr) {
        resolveRealsViaDlsym();
        if (real_fopen == nullptr || real_open == nullptr) {
            ALOGE("NativeIOHook: resolve real libc functions failed");
            return;
        }
    }

    ScanCounters counters{0, 0};
    dl_iterate_phdr(hookCallback, &counters);
    ALOGD("NativeIOHook: scanned %d libraries (%d new)", counters.processed, counters.newLibs);
}

#else // !__aarch64__

void NativeIOHook::install() {
    // 仅实现了 arm64 的 GOT 补丁，其他架构保持原行为
    ALOGD("NativeIOHook: not supported on this arch, skipped");
}

#endif // __aarch64__
