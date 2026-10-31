# ImeAutoSwitcher (IME 自动切换)

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="ImeAuto Logo" />
</p>

<p align="center">
  <b>现代 LSPosed 体系应用感知输入法自动无感切换模块</b><br>
  <i>A modern, zero-overhead LSPosed module for automatically switching Android IMEs based on foreground applications.</i>
</p>

<p align="center">
  <a href="https://github.com/Marukon/ImeAutoSwitcher/releases"><img src="https://img.shields.io/github/v/release/Marukon/ImeAutoSwitcher?color=blue&label=Release" alt="GitHub release" /></a>
  <a href="https://github.com/libxposed"><img src="https://img.shields.io/badge/LibXposed-API%20102-brightgreen" alt="LibXposed API 102" /></a>
  <img src="https://img.shields.io/badge/Android-9.0--16%20(ColorOS%2015)-orange" alt="Android Compatibility" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License" />
</p>

---

## 🌟 核心特性 (Features)

- ⚡ **零后台常驻、零功耗待机 (Zero Background / Zero Polling)**
  核心逻辑全量注入至系统服务骨干进程 `system_server` 中，采用系统事件驱动（Event-Driven）设计。无需开启常驻前台服务，无需无障碍辅助功能保活，待机状态 CPU 占用率绝对为 0%。
- 🚀 **毫秒级无感切换 (Instant & Seamless)**
  挂钩系统底层 `TaskFragment.setResumedActivity` 与 `InputMethodManagerService` 焦点捕获点。切换判定与 Activity 转场动画并发完成，键盘拉起即为目标输入法。
- 🔄 **真正免重启热重载 (Settings.Global & ContentObserver)**
  基于 Android 内部 `Settings.Global` 键值系统构建实时同步管道，并辅以系统级文件 `/data/system/imeauto_config.json` 强保障兜底。在 App 控制台保存规则后，系统端即刻热更新内存字典，规则变更无需重启手机或软重启系统。
- 🛡️ **严格特权隔离与多用户兼容**
  在系统层执行切换时严格调度 `Binder.clearCallingIdentity()` 恢复为 `system_server` (UID 1000) 最高执行特权，彻底解决 Android 14/15/16 定制 ROM 下的跨进程权限拒绝问题。
- 🎨 **现代化 Jetpack Compose (Material 3) 界面**
  支持动态获取当前设备启用的所有输入法、模糊搜索已安装应用、分类筛选（全部/用户/系统/已配置）、智能隐藏无桌面图标组件。

---

## 🏗️ 架构与技术原理 (Architecture)

```
[ App UI (Jetpack Compose) ]
             │
             │ 1. 保存规则与设置
             ▼
[ Settings.Global / /data/system/ ] (io_github_marukon_imeauto_config)
             │
             │ 2. ContentObserver 实时触发
             ▼
[ system_server (UID 1000) ]
  ├── SystemServerHook (In-Memory ConcurrentHashMap)
  ├── Hook: TaskFragment.setResumedActivity (应用切换监听)
  ├── Hook: IMMS.startInputOrWindowGainedFocus (焦点输入守卫)
  └── applyImeSwitch (Settings.Secure + 反射 IMMS 调度)
```

1. **规范包名与 LSPosed 仓库准入**：
   包名遵循 Maven Central / LSPosed 规范全小写设计：`io.github.marukon.imeauto`。
2. **现代 LibXposed 规范**：
   使用现代 `io.github.libxposed:api:102.0.0`，完整配置 `META-INF/xposed/module.prop`、`scope.list` 与 `java_init.list`。

---

## 📦 兼容性与运行要求 (Requirements)

- **Android 版本**：Android 9.0 ~ Android 16（包含 ColorOS 15、OriginOS、HyperOS 等定制系统）
- **Root 与框架要求**：
  - KernelSU / APatch / Magisk
  - LSPosed（推荐使用 LSPosed Modern 或支持 API 102 的最新发布版本）

---

## 🚀 安装与使用指南 (Usage)

1. 从 [Releases 页面](https://github.com/Marukon/ImeAutoSwitcher/releases) 下载最新的 `app-release.apk` 并安装。
2. 打开 LSPosed 管理器：
   - 启用本模块。
   - 作用域系统已自动勾选：`系统框架 (android)` 与 `ImeAutoSwitcher`。
3. 重启设备激活模块（系统级服务 Hook 需在开机初始化时载入）。
4. 打开 **IME 自动切换** App：
   - 设置全局默认输入法（例如常用的全键盘输入法）。
   - 在应用列表中找到特定应用（如“微信”），为其指定目标输入法（如“豆包输入法”）。
   - 配置实时保存并即刻生效，无需二次重启。

---

## 🛠️ 本地构建 (Building)

本项目使用 Gradle 构建。在本地克隆代码后直接执行：

```bash
# 构建 Release 版本 (已启用 R8 混淆优化与签名)
./gradlew assembleRelease

# 输出路径: app/build/outputs/apk/release/app-release.apk
```

---

## 📄 开源协议 (License)

本项目采用 [Apache License 2.0](LICENSE) 协议开源。
