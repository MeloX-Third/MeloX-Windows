# 下载迁移验证

2026-11-25，Android 基线 `edc829eaffa716b69d08681817414f9633abefc7`。

- `64f4305`：Provider 索引保留未知音源、未知字段、非对象行；显式删除压过旧更新，同 ID 不同音源相互独立。恢复时跳过未知音源，不能回退解释为网易身份；写入失败保留旧索引。
- `fdd0ee2`：NetEase 暂停保留歌曲、音质和歌单关联；继续下载从头开始，并等待旧任务释放临时文件所有权。桌面下载页提供暂停、继续和取消。

命令：`gradle :shared:desktopTest --tests "melox.download.*" :desktop:compileKotlinDesktop`，成功，耗时 2 分 20 秒。XML 汇总：

| 测试类 | 数量 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|
| AudioDownloadLimitTest | 2 | 0 | 0 | 0 |
| DownloadedLyricsPersistenceTest | 2 | 0 | 0 | 0 |
| DownloadPauseResumeTest | 1 | 0 | 0 | 0 |
| ProviderDownloadIndexCodecTest | 2 | 0 | 0 | 0 |

暂停专项使用受控协程时序验证：暂停后立即继续仍等待旧任务；等待期间取消，旧任务清理完成后不会重新启动。此项尚未覆盖真实 HTTP 成功重启、阻塞连接中断、桌面指针回放或 Provider 持久化重试。

前一轮构建因编译期间新增测试、主代码产物未包含新 API 而失败；固定输入后重跑成功。此次专项不能代替完整共享层/桌面回归；完整回归另行记录。
