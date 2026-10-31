# ImeAutoSwitcher

<p align="center">
  <img src="https://raw.githubusercontent.com/Marukon/ImeAutoSwitcher/main/art/logo.png" width="100" height="100" alt="ImeAuto Logo" />
</p>

<p align="center">
  <b>Android 应用感知输入法自动无感切换模块</b><br>
  <sub>A modern, zero-overhead LSPosed module for per-app IME auto switching.</sub>
</p>

<p align="center">
  <a href="https://github.com/Xposed-Modules-Repo/io.github.marukon.imeauto/releases"><img src="https://img.shields.io/badge/Release-LSPosed%20Repo-blue?style=flat-square&logo=android" alt="Download Release" /></a>
  <a href="https://github.com/libxposed"><img src="https://img.shields.io/badge/LibXposed-API%20102-brightgreen?style=flat-square" alt="LibXposed API 102" /></a>
  <img src="https://img.shields.io/badge/Android-9.0--16%20(ColorOS%2015)-orange?style=flat-square" alt="Android Compatibility" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey?style=flat-square" alt="License" /></a>
</p>

---

### 🔗 快捷导航与入口

> 📥 **下载安装包 (LSPosed 官方仓库)**  
> 所有正式版本与 APK 安装包均在此发布：  
> 👉 [前往 LSPosed 官方 Release 页面下载](https://github.com/Xposed-Modules-Repo/io.github.marukon.imeauto/releases)

> 💬 **问题反馈与开发建议 (原始主仓库)**  
> 遇到兼容性问题、功能建议或提交 PR：  
> 👉 [前往主仓库提交 Issue](https://github.com/Marukon/ImeAutoSwitcher/issues)

> 💻 **项目源码仓库**  
> 主开发仓库：[Marukon/ImeAutoSwitcher](https://github.com/Marukon/ImeAutoSwitcher)  
> 官方分发仓库：[Xposed-Modules-Repo/io.github.marukon.imeauto](https://github.com/Xposed-Modules-Repo/io.github.marukon.imeauto)

---

### 🌟 核心特性

- ⚡ **零后台常驻、零功耗待机**  
  逻辑全量注入至系统服务骨干进程 `system_server` 中，纯系统事件驱动。无需后台服务保活，无需无障碍常驻，待机 CPU 占用率为绝对 0%。

- 🚀 **毫秒级无感切换**  
  挂钩底层 `TaskFragment.setResumedActivity` 与输入焦点判定。切换判定与应用转场动画并发完成，键盘拉起即为目标输入法。

- 🔄 **真正免重启热重载**  
  基于系统底层的 `Settings.Global` 配合 `ContentObserver` 实时监听。App 界面调整规则后即时热同步至内存字典，无需重启手机或系统服务。

- 🛡️ **严格特权隔离**  
  在系统层执行切换时使用 `Binder.clearCallingIdentity()` 恢复为 `system_server` (UID 1000) 最高特权，彻底消除 Android 14/15/16 定制 ROM 下的跨进程权限拒绝问题。

- 🎨 **现代 Material 3 界面**  
  动态枚举系统已启用输入法，支持模糊搜索应用、分类筛选（全部/用户/系统/已配置），智能隐藏无桌面图标组件。

---

### 📱 兼容性与运行环境

- **系统版本**：Android 9.0 ~ Android 16（深度兼容 ColorOS 15、OriginOS、HyperOS 等定制系统）
- **环境要求**：
  - Root 方案：KernelSU / APatch / Magisk
  - 框架版本：LSPosed（建议使用 LSPosed Modern 或支持 API 102 的最新版本）

---

### 🚀 安装与使用

1. 前往 [LSPosed 官方 Releases 页面](https://github.com/Xposed-Modules-Repo/io.github.marukon.imeauto/releases) 下载最新的 `app-release.apk` 并安装。
2. 打开 LSPosed 管理器启用模块（推荐作用域为默认的 `系统框架 (android)` 与 `ImeAutoSwitcher`）。
3. 重启设备激活模块（系统服务级 Hook 需在开机初期载入）。
4. 打开 **ImeAutoSwitcher** 应用：
   - 设定全局默认输入法（如常用的日常输入法）；
   - 在应用列表中为目标应用（如微信）分配专属输入法（如豆包输入法）；
   - 配置即刻生效，无需二次重启。

---

### 🛠️ 架构设计

```text
[ App 控制台 (Jetpack Compose) ]
             │
             │ 保存规则配置
             ▼
[ Settings.Global / 系统配置管道 ] (io_github_marukon_imeauto_config)
             │
             │ ContentObserver 实时热载入
             ▼
[ system_server (UID 1000) ]
  ├── 内存并发规则表 (ConcurrentHashMap)
  ├── 焦点监听: TaskFragment.setResumedActivity
  ├── 守卫复检: IMMS.startInputOrWindowGainedFocus
  └── 调度生效: Settings.Secure + 反射 IMMS 原生切换
```

---

### 📄 开源协议

本项目基于 [Apache License 2.0](LICENSE) 协议开源。
