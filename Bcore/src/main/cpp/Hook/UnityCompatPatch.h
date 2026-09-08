//
// Unity 安装位置校验的文件级补丁，见 UnityCompatPatch.cpp 顶部注释。
// 纯文件改写（不注入、不 hook），因此与“按应用禁用 libc hook”开关兼容。
//

#ifndef BLACKBOX_UNITYCOMPATPATCH_H
#define BLACKBOX_UNITYCOMPATPATCH_H

class UnityCompatPatch {
public:
    // 对 <libDir>/libunity.so 做补丁；已打过或没有该校验时无副作用。
    // 返回 true 表示本次真的改写了文件。
    static bool patchLibDir(const char *libDir);
};

#endif //BLACKBOX_UNITYCOMPATPATCH_H
