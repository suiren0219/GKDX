# GKD 订阅引擎 (GKDX)

一个 LSPosed 模块：**在目标应用进程内直接运行 GKD 订阅规则**，跳过开屏广告和常见弹窗。

与 GKD 本体的区别：**完全不需要无障碍权限** —— 引擎内嵌 [gkd-kit 官方 selector 选择器引擎](https://github.com/gkd-kit/gkd/tree/main/gkd-selector)（38 个源文件直接编译进模块），通过 LSPosed 注入目标应用后，用应用自己的 View 树替代无障碍节点树做规则匹配，用 `performClick()`/合成点击事件替代无障碍手势。从系统设置到任何检测面，都看不到"无障碍服务"的存在。

## 与 GKDHide（同组织另一模块）的关系

| | GKDHide | GKDX（本模块） |
|---|---|---|
| 原理 | GKD 正常开无障碍 + 模块对外隐藏 | 不用无障碍，规则在目标应用进程内执行 |
| 规则来源 | GKD 本体自己的订阅 | 本模块管理的订阅源（同一套 GKD 订阅格式） |
| 规则覆盖率 | 100%（GKD 原生） | 原生 View 应用覆盖良好，WebView/Flutter 内部节点不可见 |
| 适用场景 | 检测只针对无障碍开关 | 检测环境彻底禁止无障碍 / 不想再装 GKD |

两者可以共存，也可以只用 GKDX。

## 订阅支持

- 使用 **GKD 原生订阅格式**（JSON5），直接吃 GKD 生态的订阅规则：
  - 默认预置官方订阅地址（注意：官方默认订阅已停止维护，仅含存量规则）
  - 可自行添加任意社区订阅 URL（如 Adpro-Team/GKD_subscription 等），每行一个
- 支持的规则字段（v1）：`matches` / `anyMatches` / `excludeMatches`、`activityIds` / `excludeActivityIds`、`quickFind`、`action`（click/clickNode/clickCenter/longClick/back）、`matchDelay`、`matchTime`、`actionMaximum`、`resetMatch`（app/activity）
- 规则组 `enable=false` 自动跳过；编译失败的选择器会跳过并记录日志

## 工作原理

```
目标应用进程（LSPosed 注入）
 ├─ 触发器：Activity.onResume(0/400/900/1500/2500ms 多轮)
 │          + TextView.setText / ViewGroup.addView（150ms 去抖）
 ├─ 快照：WindowManagerGlobal.mViews（含 Dialog 弹窗窗口）
 │        → ViewNodeAdapter 适配成 selector 引擎的 Node 接口
 ├─ 匹配：Selector.compile + querySelector（quickFind 走快速路径）
 └─ 执行：performClick / 合成 MotionEvent 点击 / performLongClick / onBackPressed
```

属性适配与 GKD 官方无障碍适配器对齐（`text`/`desc`/`vid`/`id`/`clickable`/`visibleToUser`/`childCount`/坐标等），短类名规则（`@ImageView`）与全名规则均能命中。

## 使用方法

1. 安装 APK，LSPosed 中启用模块；
2. 作用域勾选需要跳广告的应用（不要勾选模块自身）；
3. 强制停止目标应用后重开；
4. 打开"GKD订阅引擎"应用，点"更新订阅"下载规则（之后可随时更新，热生效，无需重启目标应用）。

排查：开启 LSPosed 日志过滤 `GKDX` 可看到订阅加载与规则执行情况。

## 已知局限

- **WebView / Flutter / Unity 内部节点不可见**：View 树里只有 WebView 容器，其内部 DOM 文本只有无障碍树能读到。开屏广告的"跳过"按钮大多是原生 TextView，覆盖良好；广告落地页内的弹窗规则可能失效。
- `position` 定位点击、`swipeArg` 滑动、`preKeys` 规则依赖、`actionMaximumKey` 共享计数、全局组（globalGroups）等高级语义暂未实现。
- 规则匹配在主线程执行，超大界面 + 大量规则的极端情况可能有轻微掉帧。
- 快照基于 View 树，语义上与无障碍节点树存在差异（例如部分自绘控件的文本拿不到）。

## 构建

`gradle assembleDebug`（JDK 17+，Android SDK 34）。CI：推送到 GitHub 后 Actions 自动构建。

内嵌引擎源码来自 gkd-kit/gkd 仓库 `gkd-selector` 模块（commonMain + jvmMain），更新引擎时重新拷贝即可。

## 致谢与许可

- [gkd-kit/gkd](https://github.com/gkd-kit/gkd) —— `gkd-selector` 模块源码内嵌于本仓库 `gkd-selector/` 目录，遵循其 **GPL-3.0** 许可证（见 LICENSE），本模块整体同样以 GPL-3.0 发布
- 本模块仅供个人设备上合法软件的正常使用场景
