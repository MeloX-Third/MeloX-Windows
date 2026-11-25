# 动态音源身份迁移验证

`87615d8` 插件生命周期管理器接入共享注册表，安装默认禁用、关闭保留启用意图、升级先验证候选和权限/发布者变化，账号变更先撤销旧 Provider 并关闭资源；账号操作与重新加载同时失败时保留主异常。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，10 秒，XML 15 项通过、0 失败/错误/跳过。新增三项管理器测试使用真实临时包仓库，验证 UI/播放注册表共享、恢复/卸载、拒绝升级保留旧包和实例、账号撤销及异常归属。首次测试编译因匿名 Provider 的 source 名称遮蔽失败，显式限定测试 SourceId 后通过。生产应用启动、隔离工厂、诊断和账号接线仍待完成。

`39e1bd3` 移植 Windows 插件包仓库：同步文件内容后同目录原子替换，事务日志关联已校验包摘要和发布者记录；恢复未完成决策或回滚，启用意图独立保存，卸载保留发布者记录，损坏包隔离。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，2 分 16 秒，12 项通过、0 失败/错误/跳过。新增真实临时目录测试覆盖安装默认禁用、版本升级及重载、候选准备失败、五个事务边界抛出 IO 故障后的旧包/发布者恢复、坏状态保留和坏包隔离；尚未执行独立进程强杀及签名轮换事务验收，不能将异常注入等同于进程死亡。

`4a078c9` 为所有共用歌词状态的主播放器/桌面侧栏/浮动歌词订阅共享注册表变更，取消旧界面加载、清空旧歌词并按新 generation 重新请求。`gradle :desktop:compileKotlinDesktop` 成功，4 分 11 秒；实际窗口启停插件后的歌词切换仍待插件生产加载接通后验收。

插件包协议前置迁移：`2c0d6e9` SemVer（大整数及预发布优先级）；`fcd9ea6` manifest v1、4 MiB 压缩/8 MiB 展开/64 文件限制、严格 UTF-8、完整性清单与 RSA-SHA256 发布者验证；`379ffa3` 权限扩张比较和 IPC token/方法/调用配额/错误白名单。命令 `gradle :shared:desktopTest --tests "melox.plugin.*" "-Pkotlin.incremental=false"` 成功，3 分 28 秒，XML 9 项通过、0 失败/错误/跳过。测试包括包篡改、额外文件、非法路径、无效 UTF-8、解压超限、发布者变更绑定具体包摘要。此前一次构建期间加入文件导致 main/test 输入集合不一致，编译失败；冻结输入后上述重新完整编译通过。尚未接生产安装、运行时或受控传输，不能据此宣称插件可用。

`02c71b3` 将音频引擎曲目键和歌词显示中的内置来源判断改用 SourceId。完整命令 `gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功，5 分 53 秒：共享 XML 520 项通过、0 失败/错误/跳过；桌面 16 项、10 通过/6 条件跳过、0 失败/错误；JAR 成功。此轮包含动态歌词和下载生命周期改动。先前 AutoMix 连续交接失败此轮未复现，原因仍未定位，不将本次通过表述为已修复偶发问题。PluginVersion 尚未纳入本轮编译与验证。

`5efda2f` 接通动态 Provider 歌词与绑定 SourceId，动态歌曲只请求当前来源，保留通用时间轴/语言处理；缓存键包含注册表 generation，Provider 调用完成后检查实例与 generation，启停后不复用旧缓存。绑定持久化保留有效未安装来源和原内置格式。命令 `gradle :shared:desktopTest --tests "melox.lyrics.*" :desktop:compileKotlinDesktop` 成功，2 分 40 秒；歌词 XML 59 项通过。新增动态 Provider 当前来源限定、禁用缓存失效、动态绑定往返及不同来源同 ID 隔离测试。真实插件歌词窗口和翻译账号仍未验收。

`bba0598` 接入生产 Provider 下载任务恢复、手动重试和失败记录移除，使用版本独立临时文件及原子完成文件替换；来源 generation/实例变化拒绝提交。取消会关闭 HTTP 请求，复制按块检查取消，移除等待传输所有者退出后再清理文件/索引，清理期间阻止重启。恢复账号摘要但账号系统未接通时阻止重试。`d40efe5` 补桌面下载任务、重试、移除和取消入口。

命令 `gradle :shared:desktopTest --tests "melox.download.*" :desktop:desktopTest :desktop:desktopJar` 成功，2 分 56 秒。XML 下载 12 项、0 失败/错误/跳过；桌面 16 项、10 通过/6 条件跳过、0 失败/错误；JAR 成功。生产测试覆盖来源缺失失败、重建 store 后等待手动重试、来源恢复后下载本地音频、完成索引和临时文件清理。真实插件账号/受控媒体传输及真实窗口任务操作仍待验证。

此前完整命令 `gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 运行共享层 518 项、1 失败：AutoMix 三曲连续交接应保留 1 个输出但实际为 2；桌面任务因共享层失败未继续。未修改 AutoMix 的单独复跑 `gradle :shared:desktopTest --tests "melox.playback.DesktopAutoMixIntegrationTest"` 成功，12 项通过，1 分 24 秒。偶发失败原因尚未定位，单独通过不视为完整回归通过。

`9349aac` 将全量本地推荐分析的候选收集接到显式启用的动态白名单，保留内置候选来源范围；`31e9a3e` 将动态播放标准歌曲 JSON 接到 PluginJson 的 128 KiB/32 层解析前检查。命令 `gradle :shared:desktopTest --tests "melox.playback.DynamicProviderPlaybackTest" --tests "melox.recommendation.*" --tests "melox.plugin.PluginJsonTest"` 成功，57 秒。

`38910c1` 迁移 ProviderDownloadTaskStore、PluginJson 和受控 PluginErrors：任务只保存完整 track/音质/版本/错误码/账号摘要；pending 恢复为 DOWNLOAD_INTERRUPTED；按资源身份及版本删除，旧取消不会删除新重试。最多 100 条/1 MiB，JSON 最深 32 层；保留未知行，损坏文件不覆盖，Windows 使用临时文件及 ATOMIC_MOVE。

命令 `gradle :shared:desktopTest --tests "melox.download.ProviderDownloadTaskStoreTest" --tests "melox.plugin.PluginJsonTest"` 成功，54 秒。覆盖重建实例恢复、旧版本删除隔离、未知行保留、损坏文件保护、非法版本/错误码拒绝，以及 JSON 深度/UTF-8 字节边界。此提交只有存储基础，尚未接入生产下载生命周期。

`94cfbf5` 将桌面搜索/来源集合列表项键、播放器收藏、歌曲菜单、下载来源文案及音质弹层改为 SourceId；收藏与菜单查询观察共享注册表变更。命令 `gradle :desktop:desktopTest :desktop:desktopJar` 成功，47 秒。此验证仅覆盖现有桌面测试与构建，未加入真实插件窗口输入回放，尚不代表动态插件 UI 全部可用。

`1df68a6` 为动态来源新增独立播放解析路径：完整 track URI 往返、下载本地文件优先、缺失来源等待恢复入口、播放能力声明、12 秒解析超时、来源 generation/实例校验；动态 URL 不进入内置解析缓存或跨来源回退。桌面播放器及委托解析器接入本地下载查询，队列匹配/下载状态比较不再访问动态来源的旧枚举属性。

命令 `gradle :shared:desktopTest --tests "melox.playback.DynamicProviderPlaybackTest" --tests "melox.playback.PlaybackFallbackOrderTest" --tests "melox.playback.NeteasePlaybackRequestTest" :desktop:compileKotlinDesktop` 成功，2 分 18 秒。XML 动态播放 2 项、网易请求 7 项、回退顺序 9 项，共 18 项，0 失败/错误。覆盖中文/斜线/字面百分号资源和完整歌曲元数据往返、请求头传递、重复解析不缓存、解析期间禁用拒绝、来源缺失错误、本地下载优先。首轮新增测试编译错误已修正；实际插件运行时、账号凭据和受控媒体传输未实现，不能据此宣称插件播放全链路完成。

`634c025` 接通下载 Provider 的 SourceId 查询与索引完整 track 保存/恢复，拒绝嵌套歌曲与索引外层来源或 ID 冲突。旧索引仍按旧字段恢复，未知记录合并保留。命令 `gradle :shared:desktopTest --tests "melox.download.*"` 成功，1 分钟；XML 9 项、0 失败/错误。实际动态传输、任务持久化及暂停恢复尚待迁移。

Android 功能基线 `edc829eaffa716b69d08681817414f9633abefc7`。

`70ac17a` 迁移 `SourceId` 与 `SourceResourceUri`：有效命名空间不代表来源已安装；未知来源不会获得网易身份；新格式 URI 使用严格 UTF-8、规范百分号编码、4096 字节资源上限，拒绝替代编码和无配对代理字符。

命令 `gradle :shared:desktopTest --tests "melox.music.model.SourceIdTest"` 成功，57 秒。三项测试覆盖内置/未知命名空间、包含中文/斜线/百分号/emoji 的往返、非规范转义/非法 UTF-8 拒绝和 UTF-8 字节边界。

此提交只完成基础身份类型及 URI codec；`MusicResourceId`、Provider 注册/分发、收藏/推荐/下载/队列持久化和桌面动态音源入口仍须分别迁移验证，不能据此宣称插件功能已可用。

`fdd6d93` 将 `MusicResourceId` 的主身份改为 `SourceId`，保留 `MusicSource` 构造入口。旧 `source` 属性只允许内置来源，未知来源明确报错，不能静默变成网易。测试覆盖内置构造兼容、不同来源同 ID 的隔离、copy、空 ID 拒绝和未知来源旧 API 拒绝。

命令 `gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功，4 分 10 秒。提交前完整运行 XML：共享层 498 项，0 失败/0 错误/0 跳过；桌面 16 项，10 通过/6 条件跳过，0 失败/0 错误；JAR 成功。此结果验证已有内置来源路径的回归，不代表动态 Provider 已接通。

另：`69a36ca` 为存储设置页补暂停/继续/取消及暂停任务展示。命令 `gradle :shared:desktopTest --tests "melox.download.DownloadPauseResumeTest" :desktop:compileKotlinDesktop` 成功，9 秒；暂停专项两项通过（原时序测试与生产 start 排队等待信号量后的暂停/取消清理）。未发起真实网络传输，未替代指针输入验收。

`e01cc49` 将动态来源的播放 ID 接到严格 SourceResourceUri；旧 Windows `melox:<内置来源>:<资源ID>` 保持字面百分号语义，避免将存量 `%2F` 等内容重新解释。动态来源的数字 ID 不会被当作网易数字 ID。

命令 `gradle :shared:desktopTest --tests "melox.playback.PlaybackDynamicIdentityTest" --tests "melox.music.model.SourceIdTest"` 成功，1 分 1 秒；播放身份两项、SourceId 四项测试通过。覆盖插件身份往返、非规范编码拒绝、数字身份隔离和所有内置来源旧 ID 往返。与 Android 内置来源 Uri.encode/decode 的差异为 Windows 存量兼容适配，动态来源使用相同规范 URI。

`56b111f` 将完整 track JSON 的曲目/歌手/专辑身份和旧 UI 桥接哈希接到 SourceId。命令 `gradle :shared:desktopTest --tests "melox.music.model.*" --tests "melox.music.UnifiedFavoritesTest" --tests "melox.playback.PlaybackDynamicIdentityTest"` 成功，53 秒；新增两项动态 JSON 测试覆盖完整身份往返、来源相同 ID 隔离、有效但未安装来源保留及非法命名空间拒绝。已有内置 metadata/收藏专项同轮通过；动态收藏的比较、聚合和队列接线仍须继续，不能据此宣称动态收藏全链路完成。

`8152237` 接通队列 JSON 的动态身份，拒绝损坏嵌套 track、外层来源/ID 与完整 track 不一致，以及旧格式非法来源。有效但未安装来源保持其自身身份，不能回退网易。无 provider 字段的原始网易行仍可读取。

命令 `gradle :shared:desktopTest --tests "melox.playback.DynamicQueuePersistenceTest" --tests "melox.music.UnifiedFavoritesTest" --tests "melox.playback.DesktopPlaybackStateTest"` 成功，55 秒。新增两项队列专项及既有收藏/桌面播放状态同轮通过；此处只验证身份持久化，不代表缺失 Provider 可以播放。

`dca69fe` 使用完整 sourceId 比较收藏来源，聚合评分对动态来源使用中性来源加分及动态显示名，初始化导入继续严格核对内置来源。命令 `gradle :shared:desktopTest --tests "melox.music.UnifiedFavoritesTest" --tests "*TrackAggregation*"` 成功，56 秒。XML：收藏 8 项、聚合质量 9 项，共 17 项，0 失败/0 错误/0 跳过；新增场景覆盖动态收藏重载、跨来源低音质不会替换已有高音质、再次初始化聚合及删除。

`9973ebc` 将推荐候选 JSON 的外层来源改为 SourceId，保留有效未安装来源；完整 track 与外层来源/资源 ID 必须一致。命令 `gradle :shared:desktopTest --tests "melox.recommendation.*"` 成功，51 秒。新增动态候选测试覆盖完整身份往返、跨来源冲突拒绝及旧格式动态身份保留。推荐训练来源枚举、播放统计键和动态 Provider 候选收集仍待接通。

`64ab50f` 迁移动态 Provider 注册表：动态来源使用 SourceId；内置 providers 列表保持内置语义；共享动态集合支持注册、启停、卸载、保留启用状态的原子替换及 generation/StateFlow 变更通知。仅插件命名空间可注册，声明搜索/播放/歌词/最终解析能力必须实现相应接口。

命令 `gradle :shared:desktopTest --tests "melox.music.DynamicProviderRegistryTest" :desktop:compileKotlinDesktop` 成功，1 分 28 秒。两项注册表测试覆盖跨注册表查询及启停/替换/移除可见性、无效能力和重复注册不改变状态、未知来源不回退内置。此提交未包含实际插件恢复、搜索分发或播放解析接线。

`33c0a90` 让应用 UI/播放注册表共享动态集合，聚合搜索提供 SourceId 入口及动态失败身份。查询结束时检查注册表 generation 和 provider 实例，来源变化时丢弃结果；保留 Windows 既有超时、父任务取消、去重和异源结果过滤行为。搜索失败文案使用动态来源显示名。

命令 `gradle :shared:desktopTest --tests "melox.music.DynamicProviderRegistryTest" --tests "melox.music.UnifiedSearchLifecycleTest" :desktop:compileKotlinDesktop` 成功，57 秒。新增动态查询场景覆盖同来源去重、冒用网易身份过滤、查询期间禁用后结果丢弃及禁用来源不再可查。实际插件加载及搜索 UI 的动态选择仍待接通。

`c5e059d` 增加独立的 SourceId 选择值及动态聚合白名单，保留旧内置来源选择接口；切换内置来源同步清除过期动态选中身份。插件参与聚合须显式加入白名单，动态集合最多 32 项，拒绝将内置来源写入插件白名单；设置变更触发 revision。

命令 `gradle :shared:desktopTest --tests "melox.music.DynamicSourceSelectionTest" :desktop:compileKotlinDesktop` 成功，1 分 6 秒；XML 1 项通过、0 失败/错误/跳过。覆盖动态选中持久化、默认不加入聚合、显式增删白名单、切回内置来源后选中值同步及内置身份拒绝。测试结束恢复原偏好。桌面来源选择控件和实际插件管理仍未完成。

`1d3d5d1` 将桌面聚合搜索接到动态白名单和注册表变更，推荐候选收集支持 SourceId，要求声明 HomeRecommendations 能力，并拒绝异源歌曲及来源变化后的结果。新增测试覆盖动态推荐去重、异源拒绝和收集期间禁用。

命令 `gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功，3 分 32 秒；完整 XML：共享层 511 项，0 失败/0 错误/0 跳过；桌面 16 项，10 通过/6 条件跳过，0 失败/0 错误；JAR 成功。实际插件加载与动态来源管理控件、动态播放和下载解析仍未完成，条件跳过不能算设备/真实窗口输入验收。
