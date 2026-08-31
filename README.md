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
本项目为免费开源项目，日常维护耗费大量精力。如想赞助，请联系原作者 FBlackBox 团队。

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
