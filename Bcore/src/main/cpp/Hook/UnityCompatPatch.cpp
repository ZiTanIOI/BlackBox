//
// Unity 兼容补丁：修掉 Unity 2021.3 / 2022.3（china_unity 分支）启动时的
// “安装位置校验”。
//
// 该校验在 UnityMain 早期用 dladdr 取 libunity.so 的加载路径，再拿一组前缀
// 做 starts_with 比对：命中 /data/data 或 /data/user 就打印
//     I/Unity: Error: <libunity.so 路径>
// 并调用 UnityPlayer.kill() 自杀（APK 路径命中的前缀集合多了 /storage、/sdcard）。
// 容器把分身的 so 解压在自己的私有目录，路径必然以 /data/data 开头，所以这类
// Unity 游戏在容器内开屏即退。
//
// 前缀表在 libunity.so 里是两个指针数组，由 R_*_RELATIVE 重定位在加载时填入，
// addend 分别指向 .rodata 里 "/data/data"、"/data/user"、"/storage"、"/sdcard"
// 四个字符串；这些字符串没有任何代码引用，只被这组重定位使用（实测 2021.3 与
// 2022.3 两个版本结构一致，字符串位置、是否连续都不影响判定）。
//
// 这里在文件层面把这几个重定位的 addend 改指到 "libunity.so"（SONAME，必然存在）
// ——校验前缀永远不可能命中，游戏正常继续；字符串内容原样不动，其它用到这些字符串
// 的代码不受影响。改的是容器自己解压出来的副本，运行时零 hook、零内存改写，因此与
// “按应用禁用 libc hook / 清空所有 hook 痕迹”的开关完全兼容。
//

#include "UnityCompatPatch.h"

#include <cstdint>
#include <cstddef>
#include <cstring>
#include <string>
#include <vector>

#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "../Log.h"

namespace {

constexpr uint64_t kRelative64 = R_AARCH64_RELATIVE; // 1027
constexpr uint32_t kRelative32 = R_ARM_RELATIVE;     // 23

const char *kCheckPrefixes[] = {"/data/data", "/data/user", "/storage", "/sdcard"};
const char kBenignPrefix[] = "libunity.so";

struct LoadSeg {
    uint64_t vaddr;
    uint64_t offset;
    uint64_t filesz;
};

struct ElfView {
    uint8_t *base = nullptr;
    uint64_t size = 0;
    bool is64 = true;
    std::vector<LoadSeg> segs;
    std::vector<uint64_t> prefixVas;

    bool vaToOff(uint64_t va, uint64_t &off) const {
        for (const auto &s: segs) {
            if (va >= s.vaddr && va < s.vaddr + s.filesz) {
                off = va - s.vaddr + s.offset;
                return true;
            }
        }
        return false;
    }

    bool offToVa(uint64_t off, uint64_t &va) const {
        for (const auto &s: segs) {
            if (off >= s.offset && off < s.offset + s.filesz) {
                va = off - s.offset + s.vaddr;
                return true;
            }
        }
        return false;
    }
};

// 找出以 needle 开头、以 NUL 结尾、且自身就是字符串起点的位置（vaddr）
std::vector<uint64_t> findStrings(const ElfView &v, const char *needle) {
    std::vector<uint64_t> res;
    size_t len = strlen(needle);
    if (len == 0 || len + 1 > v.size) return res;
    const uint8_t *p = v.base;
    for (uint64_t i = 0; i + len < v.size; ++i) {
        if (p[i] != static_cast<uint8_t>(needle[0])) continue;
        if (memcmp(p + i, needle, len) != 0) continue;
        if (p[i + len] != '\0') continue;
        if (i != 0 && p[i - 1] != '\0') continue;
        uint64_t va = 0;
        if (v.offToVa(i, va)) res.push_back(va);
    }
    return res;
}

bool readCStr(const ElfView &v, uint64_t va, std::string &out) {
    uint64_t off = 0;
    if (!v.vaToOff(va, off) || off >= v.size) return false;
    uint64_t end = off;
    while (end < v.size && v.base[end] != '\0' && end - off < 64) ++end;
    if (end >= v.size || v.base[end] != '\0') return false;
    out.assign(reinterpret_cast<const char *>(v.base + off), end - off);
    return true;
}

bool parseProgramHeaders(ElfView &v, uint64_t &dynVaddr, uint64_t &dynSize) {
    uint64_t phoff = 0;
    uint16_t phnum = 0, phentsize = 0;
    if (v.is64) {
        auto *eh = reinterpret_cast<const Elf64_Ehdr *>(v.base);
        phoff = eh->e_phoff;
        phnum = eh->e_phnum;
        phentsize = eh->e_phentsize;
        if (phentsize < sizeof(Elf64_Phdr)) return false;
    } else {
        auto *eh = reinterpret_cast<const Elf32_Ehdr *>(v.base);
        phoff = eh->e_phoff;
        phnum = eh->e_phnum;
        phentsize = eh->e_phentsize;
        if (phentsize < sizeof(Elf32_Phdr)) return false;
    }
    if (phnum == 0 || phnum > 128) return false;
    if (phoff + static_cast<uint64_t>(phnum) * phentsize > v.size) return false;

    for (int i = 0; i < phnum; ++i) {
        const uint8_t *ph = v.base + phoff + static_cast<uint64_t>(i) * phentsize;
        if (v.is64) {
            auto *p = reinterpret_cast<const Elf64_Phdr *>(ph);
            if (p->p_type == PT_LOAD && p->p_filesz > 0) {
                v.segs.push_back({p->p_vaddr, p->p_offset, p->p_filesz});
            } else if (p->p_type == PT_DYNAMIC) {
                dynVaddr = p->p_vaddr;
                dynSize = p->p_filesz;
            }
        } else {
            auto *p = reinterpret_cast<const Elf32_Phdr *>(ph);
            if (p->p_type == PT_LOAD && p->p_filesz > 0) {
                v.segs.push_back({p->p_vaddr, p->p_offset, p->p_filesz});
            } else if (p->p_type == PT_DYNAMIC) {
                dynVaddr = p->p_vaddr;
                dynSize = p->p_filesz;
            }
        }
    }
    return !v.segs.empty() && dynVaddr != 0 && dynSize != 0;
}

bool isPrefixVa(const ElfView &v, uint64_t va) {
    for (uint64_t p: v.prefixVas) {
        if (p == va) return true;
    }
    return false;
}

struct PendingPatch {
    uint64_t fileOff; // 要写的位置
    uint64_t value;   // 新值
};

// 返回 true 表示文件被修改
bool patchImage(ElfView &v, uint64_t dynVaddr, uint64_t dynSize) {
    // 先确认存在校验用的前缀字符串，否则不是带该校验的 libunity.so（直接跳过）
    for (const char *needle: kCheckPrefixes) {
        auto r = findStrings(v, needle);
        v.prefixVas.insert(v.prefixVas.end(), r.begin(), r.end());
    }
    if (v.prefixVas.empty()) return false;

    // 良性替换目标：SONAME
    auto benign = findStrings(v, kBenignPrefix);
    if (benign.empty()) return false;
    uint64_t benignVa = benign.front();

    uint64_t dynOff = 0;
    if (!v.vaToOff(dynVaddr, dynOff) || dynOff + dynSize > v.size) return false;

    uint64_t relaVa = 0, relaSz = 0, relVa = 0, relSz = 0;
    if (v.is64) {
        for (uint64_t o = 0; o + sizeof(Elf64_Dyn) <= dynSize; o += sizeof(Elf64_Dyn)) {
            auto *d = reinterpret_cast<const Elf64_Dyn *>(v.base + dynOff + o);
            if (d->d_tag == DT_NULL) break;
            if (d->d_tag == DT_RELA) relaVa = d->d_un.d_ptr;
            else if (d->d_tag == DT_RELASZ) relaSz = d->d_un.d_val;
        }
    } else {
        for (uint64_t o = 0; o + sizeof(Elf32_Dyn) <= dynSize; o += sizeof(Elf32_Dyn)) {
            auto *d = reinterpret_cast<const Elf32_Dyn *>(v.base + dynOff + o);
            if (d->d_tag == DT_NULL) break;
            if (d->d_tag == DT_REL) relVa = d->d_un.d_ptr;
            else if (d->d_tag == DT_RELSZ) relSz = d->d_un.d_val;
        }
    }

    // 收集“addend 指向前缀字符串”的相对重定位。只按结构特征识别，不依赖
    // 任何固定偏移/函数地址，因此不同 Unity 版本、不同 .rodata 布局都适用。
    struct Candidate {
        uint64_t index;   // 重定位序号，用于判断是否连续
        uint64_t fileOff; // addend 在文件里的位置
        uint64_t addendVa;
    };
    std::vector<Candidate> cands;
    if (v.is64 && relaVa != 0 && relaSz != 0) {
        uint64_t relOff = 0;
        if (v.vaToOff(relaVa, relOff) && relOff + relaSz <= v.size) {
            for (uint64_t o = 0; o + sizeof(Elf64_Rela) <= relaSz; o += sizeof(Elf64_Rela)) {
                auto *r = reinterpret_cast<const Elf64_Rela *>(v.base + relOff + o);
                if ((r->r_info & 0xffffffffULL) != kRelative64) continue;
                uint64_t addend = static_cast<uint64_t>(r->r_addend);
                if (!isPrefixVa(v, addend)) continue;
                cands.push_back({o / sizeof(Elf64_Rela),
                                 relOff + o + offsetof(Elf64_Rela, r_addend), addend});
            }
        }
    } else if (!v.is64 && relVa != 0 && relSz != 0) {
        uint64_t relOff = 0;
        if (v.vaToOff(relVa, relOff) && relOff + relSz <= v.size) {
            for (uint64_t o = 0; o + sizeof(Elf32_Rel) <= relSz; o += sizeof(Elf32_Rel)) {
                auto *r = reinterpret_cast<const Elf32_Rel *>(v.base + relOff + o);
                if ((r->r_info & 0xffU) != kRelative32) continue;
                uint64_t targetOff = 0;
                if (!v.vaToOff(r->r_offset, targetOff) || targetOff + 4 > v.size) continue;
                uint32_t addend = 0;
                memcpy(&addend, v.base + targetOff, 4);
                if (!isPrefixVa(v, addend)) continue;
                cands.push_back({o / sizeof(Elf32_Rel), targetOff, addend});
            }
        }
    }

    if (cands.empty()) {
        // 有前缀字符串但没有指向它们的重定位：要么这个文件已经打过补丁（addend
        // 已改指 SONAME），要么这一版校验不是指针数组实现、文件补丁覆盖不到。
        // 两种情况都无需改动，留一行日志便于排查
        ALOGD("UnityCompatPatch: no install-location relocations (already patched or different layout)");
        return false;
    }

    // 只补“连续一段重定位”里同时出现 /data/data 与 /data/user 的组——这正是
    // 校验用的那两张前缀表的特征；孤立指向 /storage 等字符串的重定位不动，
    // 避免误伤其它用途
    std::vector<PendingPatch> pending;
    size_t i = 0;
    while (i < cands.size()) {
        size_t j = i;
        bool hasData = false, hasUser = false;
        std::string str;
        while (true) {
            if (readCStr(v, cands[j].addendVa, str)) {
                if (str == "/data/data") hasData = true;
                else if (str == "/data/user") hasUser = true;
            }
            if (j + 1 < cands.size() && cands[j + 1].index == cands[j].index + 1) {
                ++j;
            } else {
                break;
            }
        }
        if (hasData && hasUser) {
            for (size_t k = i; k <= j; ++k) {
                pending.push_back({cands[k].fileOff, benignVa});
            }
        }
        i = j + 1;
    }

    for (const auto &pp: pending) {
        if (v.is64) {
            memcpy(v.base + pp.fileOff, &pp.value, 8);
        } else {
            uint32_t val32 = static_cast<uint32_t>(pp.value);
            memcpy(v.base + pp.fileOff, &val32, 4);
        }
    }
    if (!pending.empty()) {
        ALOGD("UnityCompatPatch: rewrote %zu install-location reloc(s) -> %s",
              pending.size(), kBenignPrefix);
    }
    return !pending.empty();
}

bool patchMappedFile(const char *path) {
    int fd = open(path, O_RDWR);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || st.st_size < 4096) {
        close(fd);
        return false;
    }
    uint64_t size = static_cast<uint64_t>(st.st_size);
    void *map = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        close(fd);
        return false;
    }

    bool changed = false;
    ElfView v;
    v.base = static_cast<uint8_t *>(map);
    v.size = size;

    if (memcmp(v.base, ELFMAG, SELFMAG) == 0) {
        v.is64 = v.base[EI_CLASS] == ELFCLASS64;
        uint64_t dynVaddr = 0, dynSize = 0;
        if (parseProgramHeaders(v, dynVaddr, dynSize)) {
            changed = patchImage(v, dynVaddr, dynSize);
        }
    }

    if (changed) msync(map, size, MS_SYNC);
    munmap(map, size);
    close(fd);
    return changed;
}

} // namespace

bool UnityCompatPatch::patchLibDir(const char *libDir) {
    if (libDir == nullptr || libDir[0] == '\0') return false;
    std::string lib = std::string(libDir) + "/libunity.so";
    if (access(lib.c_str(), F_OK) != 0) return false;
    bool changed = false;
    try {
        changed = patchMappedFile(lib.c_str());
    } catch (...) {
        return false;
    }
    return changed;
}
