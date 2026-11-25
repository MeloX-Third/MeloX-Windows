# 每日历史桌面回放

基线 Android `edc829e`，桌面实现 `7e1630f`；2026-11-25，1280×800，density=1，en-US。

通过生产 `ImageComposeScene`、真实指针点击和语义断言验证：首页入口 → 有记录日期歌曲预览 → 无记录日期提示 → 今天恢复预览 → 打开歌单 → 返回月历。`preview-capture.json` 记录点击和断言；`steps.txt` / `expect.txt` 为回放输入。截图分别记录预览、无记录日期与歌单详情。

使用隔离 `user.home`，`.melox/melox_daily_history/<SHA256(anonymous) 前8字节十六进制>/2026-11-25.json` 写入一条归档 fixture，歌曲 ID `987654321`、标题 `History preview validation`、艺人 `Fixture artist`、专辑 `Fixture album`。没有点击播放或向该 ID 发起真实账号写入。该证据覆盖本地归档 UI，不代表账号接口、音频播放或与 Android 同内容像素一致。

复现需在独立 captureHome 创建该日期归档及完成引导/默认电脑布局偏好，并将系统当天日期适配到 fixture 与步骤。所有参数文件使用无 BOM UTF-8。执行 `gradle :desktop:captureUi -PcaptureHome=<隔离home> -PcaptureArgsFile=<参数文件>`，参数文件指定尺寸、语言、输出目录及步骤和断言文件。
