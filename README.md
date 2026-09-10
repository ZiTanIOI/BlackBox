![xx](assets/banner.png)
### [English Version](README_EN.md)

# 虚拟引擎 · BlackBox
> The only people who have anything to fear from free software are those whose products are worth even less. 
>
> <p align="right">——David Emery</p>

![](https://img.shields.io/badge/language-java-brightgreen.svg)
![fork](https://img.shields.io/badge/forked%20from-FBlackBox%2FBlackBox-blue)

> **本仓库 Fork 自 [FBlackBox/BlackBox](https://github.com/FBlackBox/BlackBox)**，并在其基础上适配兼容到 Android 16。

黑盒BlackBox，是一款虚拟引擎，可以在Android上克隆、运行虚拟应用，拥有免安装运行能力。黑盒可以掌控被运行的虚拟应用，做任何想做的事情。

## 支持
暂不考虑4x，目前已兼容 5.0 ～ 16.0。

本 Fork 相比上游的主要改动（Android 16 适配）：
- 修复虚拟应用启动失败（`HCallbackProxy` 空指针与 Android 16 上 `LaunchActivityItem` 换入失效导致的启动死循环）
- 修复容器内 WebView 报 `net::ERR_CACHE_MISS`（`checkSelfPermission(INTERNET)` 误判 DENIED 导致 WebView 禁网，见 `checkPermissionForDevice` 等权限查询 hook）
- 修复容器内 Unity 2021.3/2022.3 游戏开屏即退（见下方「Unity 游戏兼容」）
- 新增热修复：长按应用可配置补丁 dex，分身启动前注入到类加载器（见下方「热修复」）

如果条件允许，降级targetSdkVersion到28或以下可以获得更好的兼容性。

***稳定性未经大量测试，仅供学习交流，请勿用于其他用途***

## 编译版本下载
稳定版与测试版下载
- 稳定版 由管理员手动发布经过验证稳定后的版本。[下载地址](https://github.com/FBlackBox/BlackBox/releases)
- 测试版 由机器自动编译最新的代码的版本，可体验最新体验也有可能存在问题。 [下载地址](https://github.com/AutoBlackBox/BlackBox/tags)

## 架构说明
本项目区分32位与64位，目前是2个不同的app，如在Demo已安装列表内无法找到需要开启的app说明不支持，请编译其他的架构。

## 如何使用
### Step 1.初始化，在Application中加入以下代码初始化

```java
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            BlackBoxCore.get().doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BlackBoxCore.get().doCreate();
    }
```

### Step 2.安装应用至黑盒内
```java
    // 已安装的应用可以提供包名
    BlackBoxCore.get().installPackageAsUser("com.tencent.mm", userId);
    
    // 未安装的应用可以提供路径
    BlackBoxCore.get().installPackageAsUser(new File("/sdcard/com.tencent.mm.apk"), userId);
```

### Step 2.运行黑盒内的应用
```java
   BlackBoxCore.get().launchApk("com.tencent.mm", userId);
```

### 多开应用操作
<img src="assets/multiw.gif" width="50%">

### 相关API
#### 获取黑盒内已安装的应用
```java
   // flgas与常规获取已安装应用保持一致即可
   BlackBoxCore.get().getInstalledApplications(flags, userId);
   
   BlackBoxCore.get().getInstalledPackages(flags, userId);
```

#### 获取黑盒内的User信息
```java
   List<BUserInfo> users = BlackBoxCore.get().getUsers();
```
更多其他操作看BlackBoxCore函数名大概就知道了。


#### Xposed相关
- 已支持使用XP模块
- Xposed已粗略过检测，[Xposed Checker](https://www.coolapk.com/apk/190247)、[XposedDetector](https://github.com/vvb2060/XposedDetector) 均无法检测

## Unity 游戏兼容

部分 Unity 版本（实测 2021.3、2022.3 的 china_unity 分支）在引擎初始化早期会做一次安装位置校验：用 `dladdr` 取 `libunity.so` 的加载路径，若以 `/data/data` 或 `/data/user` 开头，直接打一行日志并自杀（同一函数还会拿 APK 路径比对 `/data/data`、`/data/user`、`/storage`、`/sdcard`），logcat 里表现为

```
I/Unity   : MemoryManager: Using 'Dynamic Heap' Allocator.
I/Unity   : Error: /data/data/<宿主包名>/blackbox/data/app/<分身包名>/lib/libunity.so
I/Process : Process is going to kill itself!
```

容器把分身的 so 解压在自己的私有目录里，路径必然以 `/data/data` 开头，所以这类 Unity 游戏在容器内开屏即退。

修复走的是**文件级补丁**（`UnityCompatPatch`），在分身进程启动、应用加载 so 之前执行。识别方式是**结构特征**，不绑定 Unity 版本、不依赖任何固定偏移或函数地址：

1. 在文件里找以 NUL 结尾、且自身就是字符串起点的 `/data/data`、`/data/user`、`/storage`、`/sdcard`；
2. 找 addend 指向这些字符串的 `R_*_RELATIVE` 重定位，并按“连续一段重定位”分组；
3. 只补**同一组里同时出现 `/data/data` 与 `/data/user`** 的组——这正是校验那两张前缀表的特征（实测 2021.3 与 2022.3 分别是 6 条连续重定位，但字符串在 `.rodata` 里是否相邻完全不同）；孤立指向 `/storage` 等字符串的重定位不动，避免误伤别的用途；
4. 把这些重定位的 addend 改指到 SONAME `libunity.so`：前缀永远不可能命中，游戏正常继续，**字符串内容一个字都不动**。

要点与边界：

- 只改容器自己解压出来的副本，**运行时零 hook、零内存改写**，因此和「libc hook 按应用开关」完全兼容：勾选后照样能玩 Unity 游戏；
- 库里没有这几个字符串时（例如 2022.3 的 respin 分支实测就没有该校验）**完全不碰文件**；
- 已打过补丁的文件再次启动时不会重复改写（addend 已不指向那些字符串）；
- 若某版 Unity 把前缀写成内联常量、或改用别的机制，则补丁不会命中：日志里会打
  `UnityCompatPatch: check strings present but no matching relocations, skip`
  （字符串存在但重定位对不上）或干脆没有 `rewrote N install-location reloc(s)` 行，便于发现并适配。

## 代理 Intent 的目标传递（不再经过 system_server 解包）

容器启动一个分身 Activity 时，需要把「分身自己发起的那个 Intent」从发起进程带到真正实例化
Activity 的进程。老实现是把它当 Parcelable extra 塞进代理 Intent（`_B_|_target_`），而代理
Intent 必须先交给 system_server；部分 ROM（实测 HyperOS 的 "IntentRedirect Hardening"）会主动
**递归解包** nested extras，system_server 里没有分身 APK 的类，于是分身自定义的 Parcelable
被解成 null，目标 Activity 拿到残缺 Intent：

```
E/Parcel : Class not found when unmarshalling: <分身 SDK 自己的 Parcelable>
W/Bundle : android.os.BadParcelableException: ClassNotFoundException when unmarshalling: ...
```

从 2.3.2 起改成 **token + 容器内部 binder**：代理 Intent 里只放一个 String token，Intent 本体
暂存在容器服务端（`PendingTargetIntents`，带 TTL 与容量上限），由目标进程按 token 经容器
自己的 binder 取回。这样代理 Intent 中不再出现任何分身类，system_server 无法破坏它；Intent
本体经 binder 传递时 extras 始终是 raw bundle，直到真正回到分身进程（类加载器可用）才解包。
暂存失败时会回退到旧的 Parcelable 方式，保证不会完全起不来。

## PackageInfo 的组件回包体积

带组件标志查询 `getPackageInfo(pkg, GET_ACTIVITIES|GET_RECEIVERS|GET_SERVICES|GET_PROVIDERS|...)`
时，系统 PMS 给所有组件挂的是**同一个 `ApplicationInfo` 实例**，Parcel 序列化会按对象身份去重，
回包很小；容器早先给每个组件各 `new` 一份新实例，去重失效。manifest 重的应用（实测某客户端有
936 个 activity + 209 个 service + 150 个 provider + 27 个 receiver）回包会从 ~390KB 膨胀到
**~3.0MB**，直接超过 binder 事务上限（1MB），调用以
`FAILED BINDER TRANSACTION / DeadObjectException` 失败，客户端拿到 null：

```
E JavaBinder : !!! FAILED BINDER TRANSACTION !!! (parcel size = 176)
W System.err : android.os.DeadObjectException: Transaction failed on small parcel; ...
    at IBPackageManagerService$Stub$Proxy.getPackageInfo
```

后果是任何对该查询做完整性自检的应用都会认为「包不存在/被篡改」，进而拒绝继续（例如登录直接
中止）。修复：整包只生成一份 `ApplicationInfo` 并共享给全部组件，实测回包
3,191,204 → 404,428 字节，与系统 PMS 的 392,796 基本一致。

另外注意：`ComponentInfo.writeToParcel` 在 `applicationInfo == null` 时会抛 NPE，而服务端
**在写回复的过程中**抛异常会让整个 binder 事务中断（表现为上面的 "small parcel"）。所以组件
的 `applicationInfo` 永远不能为 null，共享同一实例同时也消除了这个隐患。

## WebView 渲染进程槽位

WebView provider 在 manifest 里声明了一池渲染服务 `org.chromium.content.app.SandboxedProcessService0 .. N`
（本机实测 40 个），而 Chromium 的 `ConnectionAllocator` 是在**自己的进程内**挑空闲索引的——每个
进程都从 0 号开始挑。容器里多个 guest 进程（各自是独立的宿主子进程）于是全都会 bind 到同一个
`SandboxedProcessService0`，而渲染进程里的 `ChildProcessService` 一次只服务一个客户端，第二个被
直接打回：

```
E/cr_ChildProcessService: Service is already bound by pid A, cannot bind for pid B
E/cr_ChildProcLauncher  : ChildProcessConnection.start failed, trying again   ← 无限重试
```

后果是「第二个需要 WebView 的 guest 进程」永远拿不到渲染进程，界面画不出来（登录页 / 内嵌网页
直接白屏或秒退）。

从 2.3.2 起在容器的 `bindService` 钩子里按 guest 进程给服务索引加偏移（`WebViewRendererSlots`）：
每个 guest 各占一段槽位。两个实现要点：

- 槽位必须用容器在 `:black` 里统一分配的 guest 进程编号 `bpid` 来算——容器框架类在每个 guest 进程里
  各有一份，statics 不共享，用自己的计数器会让每个进程都从 0 开始；
- 改写走的是 Intent **副本**：Chromium 渲染进程启动失败时会重用同一个 Intent 对象重试，就地改写会
  让索引一路 0→4→8→12 漂移。

实测修复后两个渲染进程可以并存（`:sandboxed_process0` 与 `:sandboxed_process4`），上述两条错误消失。

## 第三方应用直通（宿主直通）

有些分身应用需要通过**设备上真实安装的另一个 App** 完成功能，最典型的是「游戏内用第三方客户端一键登录」。这类客户端自己就带多进程、自研沙箱和动态插件，被导入容器后不一定能正常跑起来；而容器默认会把它当普通的「容器内跨应用跳转」处理——在自己的包管理器里解析（命中被导入的那份副本）并虚拟化启动。

**宿主直通（Host Pass-through）** 提供一个开关：被标记为直通的包，即使在容器里导入了副本，**也不在容器内虚拟化**——guest 对它的跨应用调用直接放行给真实系统，用设备上真正安装的那一份。

覆盖范围：

- `startActivity` / `startActivityForResult`：直通到宿主 Activity（含 `startActivityForResult` 回传）；
- `startService` / `bindService` / `getContentProvider`：直通到宿主 Service / Provider；
- `getPackageInfo` / `getServiceInfo` / `getActivityInfo` / `getProviderInfo` / `getReceiverInfo` / `getApplicationInfo`：在宿主 PM 查询，让 guest 侧 SDK 能正确判断「客户端已安装」。

判定依据有两处，命中任一即直通：Intent 自身显式指定的包（`setPackage` / `setComponent`），以及容器包管理器解析出来的目标包。

### ⚠️ 默认列表为空，这是有意为之

**需要校验调用方身份/签名的客户端不能直通。** 这类客户端的登录（SSO）会按 `getCallingPackage()`
/ 调用方签名去核对「是哪个应用在请求」：

- **直通到宿主**：客户端看到的是宿主包名，且调用方身份由 uid 决定、不可伪造——把 guest 包名
  当 `callingPackage` 传出去会被系统直接拒绝（`Permission Denial: package=<guest 包名> does not
  belong to uid=<宿主 uid>`）；
- **跑在容器内**：反而是自洽的。容器 hook 了 `getCallingPackage` / `getCallingActivity` 返回
  guest 包名，容器 PM 又原样保留 APK 里的真签名，客户端校验能过。

所以默认列表留空：需要身份校验的客户端应当**跑在容器内**；直通通道保留给那些「容器内实在跑
不起来、且不校验调用方身份」的客户端。

### 配置

编辑宿主的 `blackbox/system/host-apps.conf`（每行一个包名，`#` 开头为注释）：

```
# 加入直通
com.example.companion
# 以 '-' 开头表示从内置默认项里排除
-com.example.other
```

文件改动后**下次判断即生效**（按 mtime 自动重载），不需要重启容器。

### 注意

- 直通走的是真实系统，因此**分享的是宿主上那份客户端的数据与登录态**（容器内的副本不会参与）；
- 直通判断发生在容器解析之后，因此对显式组件 Intent 与隐式 Intent 同样有效。
- 部分客户端（自带沙箱 / 动态插件 / 反调试）在容器内运行需要额外放行：可以在「热修复配置」
  里把该应用的 libc hook 关掉，否则可能直接拖崩整个容器。

## 热修复
本 Fork 新增了简单的类替换式热修复，无需修改目标应用。

### 使用方法
1. 主页长按目标应用，选择「热修复配置」。
2. 点「选择补丁」，从文件选择器选择补丁文件（支持 `.dex` / `.apk` / `.jar`）。
3. 对该应用「停止运行」后重新打开分身即生效；对话框中会显示当前补丁状态，点「移除补丁」可清除。

### 原理
分身应用的类加载器由容器创建并管理。分身进程启动、Application 创建之前，容器把补丁 dex 装入临时 DexClassLoader，并将其 `DexPathList.Element` 前插到应用类加载器的 `dexElements` 头部；后续 `loadClass` 会先命中补丁里的类，实现类替换。补丁类由应用类加载器自己定义，缺的依赖会顺着原加载器找回原 dex。

### 限制与注意
- 仅替换 Java/Kotlin 类（dex 级），不含资源、SO 库与 Manifest。
- 补丁类与原类同包名同名即可覆盖，也可以在补丁中新增类。
- 补丁更新/移除后需「停止运行」再打开才生效；每个分身进程（含多进程应用的子进程）启动时都会注入。
- 卸载应用时会自动删除对应补丁；补丁保存于宿主私有目录 `blackbox/hotfix/` 下。
- 从 2.2.0 起提供。


## 如何参与开发？
### 应用分2个模块
- app模块，用户操作与UI模块
- Bcore模块，此模块为BlackBox的核心模块，负责完成整个黑盒的调度。

如需要参与开发请直接pr就可以了，相关教程请Google或者看 [如何在 GitHub 提交第一个 pull request](https://chinese.freecodecamp.org/news/how-to-make-your-first-pull-request-on-github/)
### PR须知
1. 中英文说明都可以，但是一定要详细说明问题
2. 请遵从原项目的代码风格、设计模式，请勿个性化。
3. PR不分大小，有问题随时欢迎提交。

## 计划
 - 更多的Service API 虚拟化（目前许多是使用系统API，只有少数已实现）
 - 提供更多接口给开发者（虚拟定位、应用注入等）

## 赞助
如想赞助，请联系原作者 FBlackBox 团队。

## 感谢
- [VirtualApp](https://github.com/asLody/VirtualApp)
- [VirtualAPK](https://github.com/didi/VirtualAPK)
- [BlackReflection](https://github.com/CodingGay/BlackReflection)
- [FreeReflection](https://github.com/tiann/FreeReflection)
- [Pine](https://github.com/canyie/pine)

### License

> ```
> Copyright 2022 BlackBox
>
> Licensed under the Apache License, Version 2.0 (the "License");
> you may not use this file except in compliance with the License.
> You may obtain a copy of the License at
>
>    http://www.apache.org/licenses/LICENSE-2.0
>
> Unless required by applicable law or agreed to in writing, software
> distributed under the License is distributed on an "AS IS" BASIS,
> WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
> See the License for the specific language governing permissions and
> limitations under the License.
> ```
