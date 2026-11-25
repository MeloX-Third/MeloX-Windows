# 动态音源身份迁移验证

Android 功能基线 `edc829eaffa716b69d08681817414f9633abefc7`。

`70ac17a` 迁移 `SourceId` 与 `SourceResourceUri`：有效命名空间不代表来源已安装；未知来源不会获得网易身份；新格式 URI 使用严格 UTF-8、规范百分号编码、4096 字节资源上限，拒绝替代编码和无配对代理字符。

命令 `gradle :shared:desktopTest --tests "melox.music.model.SourceIdTest"` 成功，57 秒。三项测试覆盖内置/未知命名空间、包含中文/斜线/百分号/emoji 的往返、非规范转义/非法 UTF-8 拒绝和 UTF-8 字节边界。

此提交只完成基础身份类型及 URI codec；`MusicResourceId`、Provider 注册/分发、收藏/推荐/下载/队列持久化和桌面动态音源入口仍须分别迁移验证，不能据此宣称插件功能已可用。

另：`69a36ca` 为存储设置页补暂停/继续/取消及暂停任务展示。命令 `gradle :shared:desktopTest --tests "melox.download.DownloadPauseResumeTest" :desktop:compileKotlinDesktop` 成功，9 秒；暂停专项两项通过（原时序测试与生产 start 排队等待信号量后的暂停/取消清理）。未发起真实网络传输，未替代指针输入验收。
