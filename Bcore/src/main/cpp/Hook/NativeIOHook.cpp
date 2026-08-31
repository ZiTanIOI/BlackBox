//
// NativeIOHook 实现，见头文件说明。
// 方案：解析 /proc/self/maps 找到 /data/ 下所有以 ELF 头开头的映射基址，
// 在内存中遍历其 .dynamic -> .rela.plt / .rela.dyn，命中导入符号表后改写
// GOT 表项。相比 inline hook libc 无需做指令重定位，安全性高得多。
//

#include "NativeIOHook.h"

#include <cctype>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <vector>

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

#if defined(__aarch64__)

namespace {

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
    releaseRedirect(path, redir);
    return r;
}

int my___open_2(const char *path, int flags) {
    const char *redir = tryRedirect(path);
    int r = real___open_2(redir, flags);
    releaseRedirect(path, redir);
    return r;
}

int my___openat_2(int dirfd, const char *path, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real___openat_2(dirfd, path, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real___openat_2(dirfd, redir, flags);
    releaseRedirect(path, redir);
    return r;
}

FILE *my_fopen(const char *path, const char *mode) {
    const char *redir = tryRedirect(path);
    FILE *f = real_fopen(redir, mode);
    releaseRedirect(path, redir);
    return f;
}

int my_mkdir(const char *path, mode_t mode) {
    const char *redir = tryRedirect(path);
    int r = real_mkdir(redir, mode);
    releaseRedirect(path, redir);
    return r;
}

int my_mkdirat(int dirfd, const char *path, mode_t mode) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_mkdirat(dirfd, path, mode);
    }
    const char *redir = tryRedirect(path);
    int r = real_mkdirat(dirfd, redir, mode);
    releaseRedirect(path, redir);
    return r;
}

int my_access(const char *path, int mode) {
    const char *redir = tryRedirect(path);
    int r = real_access(redir, mode);
    releaseRedirect(path, redir);
    return r;
}

int my_faccessat(int dirfd, const char *path, int mode, int flag) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_faccessat(dirfd, path, mode, flag);
    }
    const char *redir = tryRedirect(path);
    int r = real_faccessat(dirfd, redir, mode, flag);
    releaseRedirect(path, redir);
    return r;
}

int my_fstatat(int dirfd, const char *path, struct stat *buf, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_fstatat(dirfd, path, buf, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_fstatat(dirfd, redir, buf, flags);
    releaseRedirect(path, redir);
    return r;
}

int my_fstatat64(int dirfd, const char *path, struct stat64 *buf, int flags) {
    if (dirfd != AT_FDCWD || path == nullptr) {
        return real_fstatat64(dirfd, path, buf, flags);
    }
    const char *redir = tryRedirect(path);
    int r = real_fstatat64(dirfd, redir, buf, flags);
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
    releaseRedirect(path, redir);
    return d;
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
// /proc/self/maps 解析与 GOT 补丁
// ---------------------------------------------------------------------------

struct MapRange {
    uintptr_t start;
    uintptr_t end;
    bool writable;
    char path[512];
};

std::vector<MapRange> readMaps() {
    std::vector<MapRange> out;
    FILE *f = fopen("/proc/self/maps", "r");
    if (f == nullptr) return out;
    char line[1024];
    while (fgets(line, sizeof(line), f) != nullptr) {
        MapRange r{};
        char path[512] = {0};
        unsigned long long s = 0, e = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s %*s %*s %*s %511[^\n]", &s, &e, perms, path) < 3) {
            continue;
        }
        r.start = static_cast<uintptr_t>(s);
        r.end = static_cast<uintptr_t>(e);
        r.writable = perms[1] == 'w';
        strncpy(r.path, path, sizeof(r.path) - 1);
        out.push_back(r);
    }
    fclose(f);
    return out;
}

bool isDataLibPath(const char *path) {
    // 只补丁应用/模块自己的库（都在 /data 下）；系统库保持原行为
    if (path == nullptr || path[0] != '/' || path[0] == '\0') return false;
    if (strncmp(path, "/data/", 6) != 0) return false;
    if (strstr(path, "libblackbox.so") != nullptr) return false;
    return true;
}

void writeGotEntry(void **got, void *value, bool wasWritable) {
    uintptr_t addr = reinterpret_cast<uintptr_t>(got);
    long pageSize = sysconf(_SC_PAGESIZE);
    uintptr_t page = addr & static_cast<uintptr_t>(~(pageSize - 1));
    bool protectDone = false;
    if (!wasWritable) {
        protectDone = mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize),
                               PROT_READ | PROT_WRITE) == 0;
    }
    *got = value;
    if (protectDone) {
        // RELRO 段恢复只读；GOT 在加载完成后不再由 linker 写入
        mprotect(reinterpret_cast<void *>(page), static_cast<size_t>(pageSize), PROT_READ);
    }
}

void patchLibrary(uintptr_t base, const std::vector<MapRange> &maps) {
    auto *ehdr = reinterpret_cast<ElfW(Ehdr) *>(base);
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) return;
    if (ehdr->e_type != ET_DYN) return;

    auto *phdr = reinterpret_cast<ElfW(Phdr) *>(base + ehdr->e_phoff);
    ElfW(Dyn) *dyn = nullptr;
    for (int i = 0; i < ehdr->e_phnum; ++i) {
        if (phdr[i].p_type == PT_DYNAMIC) {
            dyn = reinterpret_cast<ElfW(Dyn) *>(base + phdr[i].p_vaddr);
            break;
        }
    }
    if (dyn == nullptr) return;

    ElfW(Addr) jmprel = 0, rela = 0, symtab = 0, strtab = 0;
    size_t pltrelsz = 0, relasz = 0;
    for (ElfW(Dyn) *d = dyn; d->d_tag != DT_NULL; ++d) {
        switch (d->d_tag) {
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
            default:
                break;
        }
    }
    if ((jmprel == 0 && rela == 0) || symtab == 0 || strtab == 0) return;

    // bionic linker 会把 .dynamic 里的 d_ptr 重定位为绝对地址；老设备上
    // 可能仍是相对 vaddr，按"小于 base 则加 bias"兜底
    auto abs = [&](ElfW(Addr) v) -> uintptr_t {
        return v >= base ? static_cast<uintptr_t>(v) : base + static_cast<uintptr_t>(v);
    };

    auto *syms = reinterpret_cast<ElfW(Sym) *>(abs(symtab));
    auto *strs = reinterpret_cast<const char *>(abs(strtab));

    auto scan = [&](uintptr_t relAddr, size_t totalBytes) {
        size_t count = totalBytes / sizeof(ElfW(Rela));
        auto *entries = reinterpret_cast<ElfW(Rela) *>(relAddr);
        for (size_t i = 0; i < count; ++i) {
            uint32_t type = ELF64_R_TYPE(entries[i].r_info);
            if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
            uint32_t symIdx = ELF64_R_SYM(entries[i].r_info);
            const char *nm = strs + syms[symIdx].st_name;
            for (auto &e: g_entries) {
                if (strcmp(nm, e.name) != 0) continue;
                void **got = reinterpret_cast<void **>(base + entries[i].r_offset);
                if (*got == e.hook) break; // 已打过
                if (*e.real == nullptr && *got != nullptr) {
                    *e.real = *got;
                }
                bool writable = false;
                for (auto &m: maps) {
                    if (m.start <= reinterpret_cast<uintptr_t>(got) &&
                        reinterpret_cast<uintptr_t>(got) < m.end) {
                        writable = m.writable;
                        break;
                    }
                }
                writeGotEntry(got, e.hook, writable);
                break;
            }
        }
    };

    if (jmprel != 0 && pltrelsz > 0) scan(abs(jmprel), pltrelsz);
    if (rela != 0 && relasz > 0) scan(abs(rela), relasz);
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

    std::vector<MapRange> maps = readMaps();
    if (maps.empty()) return;

    // 找出 /data/ 下所有以 ELF 头开头的映射起点（无论库是从 .so 文件还是
    // 未压缩的 base.apk 直接映射的），作为待补丁库
    std::vector<uintptr_t> bases;
    for (auto &m: maps) {
        if (m.path[0] == '\0' || !isDataLibPath(m.path)) continue;
        if (m.start >= m.end) continue;
        auto *ehdr = reinterpret_cast<ElfW(Ehdr) *>(m.start);
        if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0) continue;
        bool seen = false;
        for (uintptr_t b: bases) if (b == m.start) seen = true;
        if (!seen) bases.push_back(m.start);
    }

    int patched = 0;
    for (uintptr_t base: bases) {
        patchLibrary(base, maps);
        patched++;
    }
    ALOGD("NativeIOHook: patched %d libraries in /data", patched);
}

#else // !__aarch64__

void NativeIOHook::install() {
    // 仅实现了 arm64 的 GOT 补丁，其他架构保持原行为
    ALOGD("NativeIOHook: not supported on this arch, skipped");
}

#endif // __aarch64__
