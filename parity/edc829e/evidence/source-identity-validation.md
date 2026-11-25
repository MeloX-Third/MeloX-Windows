# 动态音源身份迁移验证

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
