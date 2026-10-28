# GKD 订阅引擎 (GKDX)

一个 LSPosed 模块，**三种用法可自由组合**，全程不需要 ADB/Shizuku：

| 模式 | 原理 | 需要无障碍 | 需要勾作用域 | 需要 root |
|---|---|---|---|---|
| **Xposed 模式** | 引擎注入目标应用进程，用应用自己的 View 树匹配订阅规则并点击 | ❌ | ✅ | ❌ |
| **Root 模式** | 引擎跑在模块自己的 root 进程里，`uiautomator dump` 读屏 + `input tap` 点击 | ❌ | ❌ **完全不注入任何应用** | ✅ |
| **无障碍隐藏** | 同时使用 GKD 本体时，在作用域应用里隐藏其无障碍状态 | （GKD 需要） | ✅ | ❌ |

规则来自 **GKD 原生订阅格式**，引擎内嵌 [gkd-kit 官方 selector 选择器引擎](https://github.com/gkd-kit/gkd/tree/main/gkd-selector)。

## 关于“要不要勾作用域 / 进程注入太多”

两种思路，按需选：

- **不想注入任何应用** → 用 **Root 模式**：LSPosed 作用域一个都不用勾（模块照样需要被 LSPosed 启用吗？不需要——Root 模式是模块自己进程里的普通代码，甚至可以在无 LSPosed 的 root 机上运行）。代价：轮询读屏约 0.5-1.2 秒一次，比进程内慢一点；好处：**能看到 WebView 内部节点**，这是 Xposed 模式和无障碍都拿不到的。
- **想最快地跳过开屏** → 用 **Xposed 模式**：必须勾作用域（这是 Xposed 的机制，界面上的按钮只有该应用进程内才存在）。不过本模块做了减负优化：**下载订阅时记录所有规则覆盖的包名，进程启动时若该应用没有规则，一个 Hook 都不装**——所以把作用域勾得多也不会让无关应用变卡。

框架（`android`/system_server）作用域对本模块没有意义：广告按钮不在系统进程里，必须进目标应用进程才能点它。

## 内置订阅源（实测可用，可自行增删）

```
https://registry.npmmirror.com/@ganlinte/gkd-subscription/latest/files/dist/ganlin_gkd.json5   # 甘霖（维护中）
https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5 # AIsouler
https://registry.npmmirror.com/@adpro/gkd_subscription/latest/files/dist/Adpro_gkd.json5       # Adpro
https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5               # 官方默认（已停更）
```

四个源合计约 290 万字符、上万条规则、覆盖数千个应用。欢迎提交你所在地区的订阅地址（配置页每行一个 URL 即可，也欢迎开 issue 收罗更多可用订阅）。

## 使用方法

### Xposed 模式
1. 安装 APK → LSPosed 启用模块 → 作用域勾选目标应用；
2. **强制停止**目标应用后重开；
3. 打开「GKD订阅引擎」→ 点「更新订阅」。

### Root 模式
1. 安装 APK → 在 root 管理器（Magisk/KernelSU/APatch）中授予本应用 root；
2. 打开模块 → 「更新订阅」；
3. 点「启动 Root 模式」（会显示常驻通知，通知上可停止）。

### 隐藏 GKD 无障碍（配合 GKD 本体）
配置页底部开关保持开启即可，默认隐藏 `li.songe.gkd`。

## 支持的订阅字段

`matches` / `anyMatches` / `excludeMatches`、`activityIds` / `excludeActivityIds`（支持 `.*` 前缀）、`quickFind`、`action`（click / clickNode / clickCenter / longClick / back）、`matchDelay`、`matchTime`、`actionMaximum`、`resetMatch`、组级 `enable`。

暂未实现：`position` 定位点击、`swipeArg` 滑动、`preKeys` 规则依赖、`actionMaximumKey`/`actionCdKey` 共享计数、`globalGroups` 全局组。

## 已知局限

- Xposed 模式：WebView / Flutter / Unity 内部节点在 View 树里不可见（Root 模式反而能覆盖 WebView，两者互补）。
- Root 模式：轮询式，开屏广告如果只有 <0.5 秒的窗口可能来不及；`uiautomator dump` 在部分机型上偏慢。
- 检测 LSPosed/root 本身属于 Shamiko/DenyList 的范畴；检测“是否安装 GKD”需 Hide My Applist 类模块。

## 关于 / 原作者

- **GKD 原作者：lisonge（GitHub [@lisonge](https://github.com/lisonge)）**，项目 <https://github.com/gkd-kit/gkd>，订阅数据 <https://github.com/gkd-kit/subscription>
- **选择器引擎**：取自 gkd-kit/gkd 的 `gkd-selector` 模块，原样内嵌于本仓库 `gkd-selector/`，遵循其 **GPL-3.0** 许可证（见 [LICENSE](LICENSE)），本模块整体同样以 GPL-3.0 发布
- **内置社区订阅**：甘霖（@ganlinte）、AIsouler（@AIsouler）、Adpro（@Adpro-Team）—— 规则版权归原作者
- **无障碍隐藏思路参考**：[AoEiuV020/IAmNotDisabled](https://github.com/AoEiuV020/IAmNotDisabled)
- 本模块是第三方实现，与 GKD 官方无任何关联；跳广告的功劳属于 GKD 与各位订阅作者。

## 构建

`gradle assembleDebug`（JDK 17+，Android SDK 34）。推送 `v*` 标签会自动构建并把 APK 挂到 GitHub Release。
