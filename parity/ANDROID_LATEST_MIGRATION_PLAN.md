# Android 最新基线 → Windows 全量迁移计划

## 基线与规则

- Android 唯一功能基线：`D:\MeloX-Android` 当前 `main`，提交 `edc829eaffa716b69d08681817414f9633abefc7`（2026-11-25，MeloX 0.7.5+）。
- 本基线比原活跃基线 `53244b9` 前进 **255 个提交**；不能把旧矩阵中的 `IMPLEMENTED` 当作对最新源码的验收结论。
- 桌面 UI 另以 `parity/desktop-a41ea0e/MATRIX.md` 锁定的 MeloX Desktop `a41ea0e1e1fa69fb543d827b853fe3aff5420951` 为唯一桌面视觉/交互真源。Android 手机页面不得直接充当 Windows 桌面页面模板；业务、模型、持久化及服务可以复用。
- 手机 UI 在桌面完成并回归后，以 Android `edc829e` 的 Android 页面与交互为真源，独立实施和验收。
- Android 工作区存在用户未提交修改。只读比较提交内容，不覆盖、不暂存、不清理 Android 工作区。
- 原子提交：每完成一个独立功能或修改一个功能，验证后立即本地 commit；计划和文档另行提交，不混入无关功能。不自动 push。
- 本计划按“已实现 / 部分实现 / 缺失 / 平台不适用 / 受外部验收阻塞”逐项登记。代码存在不等于迁移完成；需要测试、桌面生产场景或设备证据的分别验证。

## 已知起点

- Windows 仓库：`D:\MeloX-Windows`，工作分支浅克隆；Windows 基线当前为 `8e4b47f`，与 Android 历史不共享 Git ancestry。
- 默认电脑布局已改为新安装/无设置时启用；显式存量偏好保持原值。
- 已迁移并通过 `:shared:compileKotlinDesktop`：循环模式持久化/恢复、上一首/下一首 300ms 防抖。
- 上述既有工作区改动是用户工作成果，后续保留。
- Android 最新提交本身修复 AutoMix 状态竞争和系统歌词旧 Job 泄漏；Windows 没有 Android `MediaSessionService`/`systemLyricsJob` 对应实现，需先检查 Windows PCM AutoMix 所有权和歌词加载取消生命周期，再决定等价修复，不能照搬 Android 锁代码。

## 执行阶段

### A. 桌面 UI 与桌面体验（最高优先）

1. 以固定 Desktop 真源矩阵和 Windows 最新 Android 基线差分重新盘点桌面页面、弹层、菜单、动画、控件、导航、键盘/鼠标/触屏、字体/图标/玻璃。
2. 独立完成桌面根框架、首页/探索/搜索/音乐库、专辑/歌手/歌曲/歌单详情、账户/消息/共同聆听、设置/关于/引导、播放器/歌词/队列/音频输出/桌面歌词窗口。
3. 按页面补生产入口与状态（loading/empty/error/登录态/分页/返回保持），优先修复不可达或错误路由，再做布局、控件和动态细节。
4. 每批运行相关桌面测试及 capture/reload 场景；能自动化的语义、真实指针、偏好重载、截图均记录。固定源同内容参考图和 Windows 实机输入独立标记。

### B. Android 0.7.5+ 共享业务与桌面平台适配

按依赖顺序审计和迁移：

1. 播放可靠性、provider fallback/质量协商、网络与媒体缓存、会话安全、下载与播放身份。
2. 音乐 Provider/统一收藏/聚合搜索/第三方 resolver/插件协议和隔离运行时；保留 Windows DPAPI、进程隔离、HTTP、文件选择器等安全边界，不能降低安全等级。
3. 歌词来源、自动选择/翻译语言/伴唱/日语 ruby/文件编码/系统歌词对等入口，以及 Windows 可用的桌面歌词呈现。
4. AutoMix/Smart Queue/分析缓存、播放器输出、LAN/DLNA/MiPlay、MCP/CLI、推荐与每日推荐历史、各服务/设置页。
5. 对 Android 专属功能明确分类：无 Windows 等价硬件/API的，记录 `PLATFORM_ADAPTATION_REQUIRED` 或 `NOT_APPLICABLE`，不伪称迁移；如用户可见且有桌面等价能力则实现等价入口。

### C. 桌面 UI 与业务整体验收

- `:shared:desktopTest`、`:desktop:desktopTest`、`:desktop:desktopJar`，再运行针对高风险播放器/provider/插件/歌词/设置的专项回归。
- 验证默认电脑 UI、设置即时切换与重启恢复；桌面路由不落入手机 split/detail 页面。
- 校验发行包资源、FFmpeg/本地音频、偏好独立 JVM 重载、生产页面输入回放。
- 真实账号、Windows 麦克风/扬声器/USB/DLNA 设备、DPI/触屏及固定 Android/桌面参考截图单列阻塞，不以软件仿真冒充。

### D. 手机 UI（桌面阶段关闭后开始）

- 逐页面对照 Android `edc829e`：主导航/首页/搜索/探索/音乐库/详情/设置/登录/播放器/歌词/队列/弹窗。
- 复用 Windows 已完成的业务层，手机布局分支独立实现；不可回退或覆盖电脑 UI。
- 对 Android 专属通知、系统歌词、悬浮窗、MediaProjection、前台服务等，只实现 Windows 手机形态可支持的等价物，或明确不适用。
- 每页补状态恢复、真实输入/动画测试、截图；最终运行全套 Windows 构建与回归。

## 验收口径

- 功能迁移：真实生产路径可达；成功/失败/取消/重试/持久化边界有测试；provider/network 逻辑使用真实协议 fixture，不注入伪造生产状态。
- UI 迁移：独立桌面/手机实现，覆盖正常/空/加载/错误/弹层与返回；捕获尺寸、交互和动效差异。没有固定同内容源帧时保持 `PARTIAL`，不宣称像素一致。
- 回归：测试通过；工作区 diff 无格式错误；构建产物可打包。失败及环境限制照实记录。
- 安全：插件/脚本/账号/HTTP/更新迁移不得放宽签名、权限、隔离、超时、大小限制或秘密存储。

## 进度记录

| 阶段/范围 | 状态 | 证据/备注 |
|---|---|---|
| 基线锁定 | DONE | Android `edc829e`；255 commits after `53244b9`；旧矩阵只作历史证据 |
| 默认电脑 UI | DONE | `MeloXSettingsRuntime.kt` 两个缺省读取均为 `true`；显式偏好不覆盖 |
| repeat 持久化 + 切歌防抖 | DONE | `MeloXPlaybackModePreferences.kt`、`PlaybackCommands.kt`；桌面编译成功 |
| 最新提交 AutoMix race / system lyrics job | AUDIT | 先按 Windows PCM/歌词任务模型做生命周期审计，再落等价修复和测试 |
| AutoMix 状态读改写竞争 | IMPLEMENTED | `a812694` 使用 StateFlow 原子更新；10,000 轮 status/handoff 并发回归及完整快照测试通过；不代表所有 PCM 生命周期竞争已关闭 |
| 桌面音量条显示开关 | IMPLEMENTED | `084544e` 对应 Android `c82ac33` / `6b26dc3`；保存/即时状态/桌面完整播放器和底部播放器接线；偏好重载测试及 `:desktop:compileKotlinDesktop` 通过，实际 UI 输入验收待补 |
| 桌面 UI 全量差分 | IN_PROGRESS | 逐页盘点和实现，完成后才进入手机 UI |
| 首页推荐桌面详情 | IMPLEMENTED | `9bf5ce6` 将每日推荐/私人漫游/相似歌曲从手机列表分流至独立桌面 collection，接通播放/随机播放/歌曲菜单；桌面编译通过，实际输入验收待补 |
| 播放全量回归 | VALIDATED | 2026-11-25 最新完整运行共享层 474 项，0 失败/0 错误/0 跳过；包含 `02af178`。历史：首轮上一首测试受防抖影响，`6911501` 隔离场景；第二轮 PCM 缓冲静音等待超时，随后修复引擎启动暂停/恢复竞争。单轮通过不等于所有并发问题已关闭 |
| 桌面测试和 JAR | VALIDATED | 同轮 `:desktop:desktopTest` 16 项，10 项通过、6 项按环境条件跳过，0 失败/0 错误；`:desktop:desktopJar` 成功。跳过项不能当作真实窗口输入验收，JAR 构建不等于 MSI/EXE 安装包验收 |
| 双解码引擎启动暂停/恢复竞争 | IMPLEMENTED | `02af178` 将引擎发布和暂停状态读取、引擎暂停/恢复调用纳入同一 playbackLock；AutoMix 12 项专项通过，包含 20 次真实 FFmpeg 启动恢复/PCM 写入；随后共享层完整 474 项回归通过 |
| Android 0.7.5+ 255 提交全量差分 | IN_PROGRESS | 逐 subsystem 比对；不按提交数机械复制 |
| 手机 UI 全量差分 | BLOCKED_BY_PHASE_A | 桌面阶段结束后执行 |
| 真实设备/账号/像素验收 | EXTERNAL_ACCEPTANCE | 代码与自动化可先完成；无设备/账户/固定源帧的项保持 PARTIAL/BLOCKED |

## 变更日志

- 初版：锁定 Android 最新提交 `edc829e` 和独立 Desktop UI 真源；纠正旧计划仍以 0.6.5 为活跃 Android 基线的问题。
- 原子提交约定已落地：计划 `4bb6237`、默认桌面布局 `30f35c9`、循环模式持久化 `9df75fe`、切歌防抖 `12cc1bb`、AutoMix 状态原子更新 `a812694`。原合并播放提交已拆分为两项独立提交。
