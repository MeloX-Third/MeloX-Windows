# 动态音源身份迁移验证

`2b41147` 接通桌面真实播放信号：周期采样要求队列与 AudioPlayer 完整身份一致且实际 isPlaying；一次播放开始、85% 完成、30 秒内已播放未完成的手动下一首分别记录。后台有界队列串行写入，存储锁内检查算法开关与个性化同意，清除共用锁。新增信号状态测试覆盖未播放/暂停不计数、不同来源相同资源 ID、完成去重及跳过阈值；推荐包与真实 DesktopAutoMixIntegrationTest 联合专项及 desktopJar 成功（2 分 59 秒）。此提交之后的训练键修复尚在验证，不属于先前 626 项完整回归。

覆盖代码至 `5026908` 的完整命令 `gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功（3 分 22 秒）：共享层 626 项，0 失败/错误/跳过；桌面 19 项中 13 通过、6 条件跳过，0 失败/错误；JAR 成功。包含账号捕获/刷新、生产初始化、管理 UI 编译、启动帧、播放/下载重解析与媒体租约。条件跳过及 fixture 不能作为真实窗口、公网 OAuth、安装包或整项迁移完成证据；AutoMix 本轮通过，历史偶发仍未定位。

`5026908` 下载首响应 401/403/410 后重新解析一次，重试前后检查取消和 Provider generation/实例，格式变化拒绝，第二次失败不循环。ProviderDownloadLifecycleTest 通过生产下载存储及宿主 transport fixture 验证首次 403 后准确落盘、连续拒绝最多两次解析/请求且无 part 文件残留；冻结代码后专项及 desktopJar 成功（4 秒）。真实公网传输及强制 OAuth 刷新仍未验收。

`1b80539` Provider 媒体 `credential:"active"` 接通宿主账号租约：解析前捕获不透明授权快照，生产捕获前执行过期刷新；播放私有输入与下载 broker 显式注入凭据，限定 manifest 认证域名，拒绝脚本重复认证头。脚本结果、普通 headers 与原生解码输入均不包含宿主 token。新增授权/来源切换测试及 Provider、私有输入、动态播放、下载联合专项与 desktopJar 成功（17 秒）。宿主 transport fixture 验证注入/域名/撤销；真实公网媒体、强制刷新、下载单次重解析及完整回归仍待补。

`2f8ec55` 私有播放输入遇首响应 HTTP 401/403/410 时调用同一 Provider 重新解析一次；第二次失败直接传播，其他状态不重试，已开始读取的流不重放。刷新前后保持 Provider 实例/generation 校验，迟到流关闭。输入/broker 专项及 JAR 成功（1 分 13 秒），DynamicProviderPlaybackTest 与真实 PluginMediaSocketTest 补充专项成功（4 秒）。当前仅覆盖播放打开重解析，下载重解析与强制 OAuth 刷新仍待接线。

`593b6bc` 将 worker 启动 envelope 单独限制为 1 MiB，以容纳 128 KiB 合法脚本经 JSON 转义后的体积；脚本保持 128 KiB、参数保持 128 KiB、账号保持 4 KiB，结果/宿主通信默认帧仍为 128 KiB。真实 JVM 执行恰好 128 KiB、含大量转义字符的脚本返回 42；超限参数在创建进程前拒绝。worker/IPC/AppContainer 联合专项及 desktopJar 成功（2 分 2 秒），并未扩大结果或网络配额。

`25fd768` 桌面“功能→插件管理”接通生产 Manager：导入/升级预览能力、域名、认证、发布者指纹与候选摘要；启停、卸载确认、损坏记录移除、诊断查看/清除，以及账号选择/删除/token 导入/OAuth 登录/重新授权/取消。弹窗内容可滚动，来源行状态按完整 SourceId 固定。桌面测试与 JAR 构建成功（44 秒）；这是编译及现有桌面回归证据，尚未执行该页面的真实窗口输入或真实公网 OAuth 验收。

`39bcfaf` 生产入口在窗口/播放初始化前恢复保存的启用插件，UI 与播放共享动态注册表；退出 finally 关闭 OAuth 与 PluginManager。首次启用准备独立 RX bundle，账号选择/移除/token 导入通过 Manager 的账号上下文替换，浏览器授权共用登录所有者。管理器/账号专项及 desktopJar 成功（20 秒），此前启动接线与 desktopTest 成功（58 秒）。打包安装版 classpath、真实窗口管理交互与公网 OAuth 尚需实际验收；每会话新 bundle 的版本复用/清理仍待完善。

`1b02dee` 非验证调用前刷新即将过期的 OAuth，宿主 token broker 交换并以 vault CAS 更新，保留未返回的新 refresh token；验证调用不刷新，刷新失败保留账号且过期 credential 仍显式拒绝。账号/worker 联合专项成功（56 秒），账号元数据收尾后账号专项与 desktopJar 再次成功（17 秒）。token 服务为 fixture，不构成真实公网授权验收。

`d406f84` 每次 worker 调用捕获账号会话、账号元数据和 HostCalls，存储按本地账号 ID 隔离，凭据仅留宿主 HTTP broker。真实 Windows DPAPI 与独立 JVM 回归验证两账号依次调用元数据/存储一致、无互读、切换撤销旧上下文、JSON 不含 token；两项账号专项及 desktopJar 成功（15 秒），前一轮账号/worker 联合专项成功（49 秒）。该增量晚于上方 618 项完整回归，媒体租约与生产初始化仍待补。

覆盖代码至 `643f3c7` 的最新完整回归：`gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功（3 分 30 秒）。共享 618 项通过、0 跳过；桌面 19 项中 13 通过、6 条件跳过；失败 0。该结果覆盖私有音频、LAN 转发、IPC、独立 worker、AppContainer 权限探针、bundle 与 Provider 工厂；不代表生产插件管理/账号 UI、媒体账号租约与重解析、手机 UI 或硬件验收完成。

WindowsPluginWorkerBundle 的部署→AppContainer→Provider 工厂→标准分页搜索贯穿专项与 desktopJar 成功（25 秒），四项启动器专项均通过。可信 JVM/classpath 复制到唯一 staging、预部署 native DLL、固定 SID RX、原子发布且拒绝覆盖已有目录；测试从发布目录真实启动 JVM/V8 并调用 Provider。该 bundle API 已具备部署能力，应用启动时如何选择/复用版本与生产初始化仍待接通。

`c5cc0bd` 使用可信 JVM 探针直接访问 OS：宿主私有文件读取、bundle 写入、本机 TCP 连接和创建子进程均被拒绝；宿主监听端 accept 超时，证明未收到连接。真实 V8/HostCalls 往返与探针联合启动器专项及 desktopJar 成功（15 秒）。`81c307c` Provider 工厂经独立运行时 __validate 校验声明方法，缺失方法拒绝并关闭运行时，合格 Provider 可执行标准分页搜索；进程专项 6 项与 desktopJar 成功（43 秒）。工厂默认生产初始化、安装/账号 UI 与网络 broker 公网验收仍未接通。

`fc90f7e` 将实际 AppContainer JVM/V8 测试接入 PluginWorkerChannel/PluginHostCalls，异步脚本通过 token 绑定 IPC 在容器外宿主存储写入/读取中文及 emoji，返回值与宿主磁盘存储一致；测试 bundle 仍仅 RX，无宿主存储目录授权。三项启动器专项及 desktopJar 成功（14 秒）。该证明覆盖宿主存储 broker 往返，HTTP 公网及账号路径仍待验证。

`6a9b8a6` 真实 JVM/V8 AppContainer 贯穿验证通过：独立测试 bundle 仅授予固定 AppContainer SID 的 RX，包含 JVM、必要 jar/class 和预部署 V8 DLL；以 custom 加载模式避免 worker 解包 native 文件。私有管道发送脚本，受限 JVM 执行并返回 invocation token/JSON 结果。启动器三项专项及 desktopJar 成功（19 秒）。测试 bundle 部署不是生产安装/更新实现；网络能力拒绝、实际 broker 往返及生产工厂仍需独立验证。

`9cc247c` 增加 Windows x64 AppContainer 启动器：零 capability、显式管道句柄继承列表、禁止创建子进程、最小系统环境。真实 cmd 子进程验证管道往返、私有临时文件内容不可读、创建子进程被系统阻止；与 PluginWorkerProcessTest/PluginWorkerChannelTest 联合专项及 desktopJar 成功（44 秒）。创建需要 LOCALAPPDATA；使用 DETACHED_PROCESS 避免控制台 DLL 初始化失败。该证据仅覆盖受限启动器与既有 worker 各自专项，实际 JVM/V8 在 AppContainer 中运行、网络拒绝、运行文件部署及生产工厂仍需贯穿验证。

`bca000f` 宿主通信通道将每条 worker 消息绑定 invocation token、严格整型序号及总消息上限，结束/失败/撤销统一关闭 HostCalls；`ef4f038` 独立 JVM 执行一次 V8 脚本调用，Promise、宿主 storage 往返及不可访问 Java/Node/原始桥对象通过真实进程测试。`77819d8` 增加宿主八秒超时/取消、每次新进程、V8 heap/stack 配置与独立看门狗；真实阻塞 stdin 的进程退出码 74，重复调用脚本全局计数始终 1，取消释放阻塞通道且拒绝迟到结果。最终进程/通道专项与桌面 JAR 成功（39 秒）。这些进程测试使用测试专用 ProcessBuilder，不能证明 OS 权限隔离；Windows AppContainer 启动器和生产工厂仍在实现。

`eab352f` 为独立 worker 增加长度前缀 UTF-8 帧；负数/零/超限长度在分配 payload 前拒绝，截断、非法 UTF-8、JSON 深度及输出上限有回归。`gradle :shared:desktopTest --tests "melox.plugin.PluginIpcFramesTest" :desktop:desktopJar` 成功（59 秒），3 项通过。仅证明通信基础边界，尚未接通 Windows 受限进程与生产工厂。

`8476d2a` LAN/DLNA 代理识别插件私有输入并经受控流转发；不透明标识和上游请求头不发送给接收端。HEAD 关闭输入，来源撤销拒绝打开，代理关闭释放活动私有流；未实现范围读取，因此返回完整 200 并声明 Accept-Ranges: none / DLNA.ORG_OP=00。`gradle :shared:desktopTest --tests "melox.lan.*" --tests "melox.dlna.*" :desktop:desktopJar` 成功（2 分 2 秒），21 项全部通过、0 跳过。真实 DLNA 设备、随机跳转与公网服务仍待验收。

`9c3593d` 插件播放解析改为进程内不透明输入，普通 FFmpeg 和独立 PCM 解码器通过 stdin 接受私有 broker 字节流，原生参数限制为 pipe 协议。覆盖重复打开/撤销/过期、命令不泄露凭据、真实 WAV PCM、跳转及本地 socket broker 贯穿。流媒体分析开关同样约束私有输入。播放专项 XML 140 项通过；最终解码器初始化收尾后再次运行私有输入/动态解析/普通与双解码专项和桌面 JAR 成功（1 分 30 秒）。真实公网、账号租约、401/403/410 重新解析及非顺序容器兼容仍待验收。

`ebca6f6` 桌面服务选择器列出已启用插件并保存 SourceId；根导航与侧栏消费动态选择，音乐库分类按插件能力显示，切换时清除上个来源的音乐库页面请求。插件选择不开放网易云盘/播客及心动启动逻辑。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（48 秒）。真实窗口选择交互及插件生产加载仍待验收。

`8075c58` 普通解码 PCM 能量发布改用完整 SourceId，新增真实 FFmpeg/受控音频输出回归验证插件身份不触发旧 `.source` 访问。普通解码专项与 JAR 构建成功；随后 `gradle :shared:desktopTest --tests "melox.player.DesktopStreamingAudioSessionTest" --tests "melox.playback.LxDynamicSourceBoundaryTest" :desktop:desktopJar` 成功（3 分 4 秒），XML 16 项全部通过、0 跳过，覆盖双解码继承回归。`c2cbddf` 拒绝动态来源进入 LX 播放/封面/歌词兼容入口，边界回归同轮通过。受控本地 WAV 验证不代表真实插件远程播放 broker 已完成。

本轮完整回归成功（3 分 2 秒）：`gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar`；共享 XML 592 项全部通过、0 跳过；桌面 XML 19 项中 13 通过/6 条件跳过，JAR 成功。编译阶段实际包含随后提交为 `5be06e0` 的桌面探索动态来源接线（保存 SourceId、generation 撤销、内容身份校验）；随后同命令的桌面部分检查 UP-TO-DATE。覆盖推荐池/首页种子、动态首页/排行榜、动态音乐库及专辑/歌手分页。真实插件生产、账号/硬件和视觉验收仍未完成，AutoMix 历史偶发未复现且未关闭。

`6de9bad` 搜索和音乐库专辑/歌手详情读取显式 hasMore/total 指示的后续页，100 条分页，校验返回 summary 完整身份、歌曲来源及每页 Provider 实例/generation，完整身份去重且重复页无新增时停止。新增回归覆盖多页合并、重复页、跨来源数据及停用。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（52 秒）。没有分页元数据时维持单页语义；真实服务分页与窗口输入验收仍待完成。

`8400662` 音乐库按完整 SourceId 路由动态来源，按声明能力提供歌单/专辑/歌手分类，动态来源使用独立桌面分类 picker；账号摘要、分页和详情检查来源与 registry generation，来源生命周期变化清空页面状态。插件未授权状态不导航到内置账号。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（46 秒）。插件账号生产授权 UI、真实窗口输入、详情完整分页仍需后续完成。

`54f897d` 首页消费保存的动态 SourceId，并按 registry generation 重建 feed/排行榜状态，检查首页资源来源，撤销迟到结果。排行榜改用完整 SourceId、100 条分页，逐页检查实例/generation、拒绝跨来源歌曲并完整身份去重。新增桌面回归覆盖重复分页、字面资源身份、来源撤销及混入其他来源。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（57 秒），新增回归通过。真实插件生产加载及窗口/视觉验收仍待完成。

`a5ec929` 首页种子接线：网易首页内容和最近播放、其他内置 Provider 首页新歌进入推荐池；种子刷新遵循基线的已有卡片稳定规则，不使用播放队列的新增优先逻辑。`gradle :shared:desktopTest --tests "melox.recommendation.*" :desktop:desktopTest :desktop:desktopJar` 成功（1 分 43 秒）；共享推荐专项 XML 6 项全部通过。卡片消费及动态首页后续接线仍待完成。

`e17ec01` 迁移 Android 进程内首页推荐池及普通/Provider 整队列贡献，按完整资源身份去重，保留原 track 元数据；新增队列优先更新最多五首，重复歌曲不改变已有展示顺序。两项回归覆盖插件间相同资源 ID、整队列输入、上限和稳定性。`gradle :shared:desktopTest --tests "melox.recommendation.*" --tests "melox.playback.*" :desktop:compileKotlinDesktop` 成功（1 分 56 秒）；XML 109 项全部通过、0 跳过。此时首页种子/卡片接线尚未完成。

`03a820d` 歌词绑定选择器对搜索、分页和预览分别应用 30 秒超时并显示可重试错误；外层页面关闭/来源切换取消仍向上传播。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（41 秒）。真实超时窗口输入验收仍待完成。

`d9fe705` 桌面搜索增加现有桌面 picker 控件：列出可见内置来源和具备搜索能力的已启用插件，通过完整 SourceId 导航保留关键词并保存来源。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（49 秒）。此变更晚于 590 项完整共享回归；窗口输入/视觉验收仍待完成。

截至 `4fcfe0a` 完整自动回归：`gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功（2 分 19 秒）。共享 XML 590 项、0 失败/错误/跳过；桌面 UP-TO-DATE 复用此前 11 通过/6 条件跳过，JAR UP-TO-DATE。覆盖随后歌词绑定、网络音质及搜索来源修复；不代表真实插件生产运行、账号/网络/硬件或像素验收完成。AutoMix 历史偶发仍待定位。

`4fcfe0a` 桌面搜索的网易账号音乐库范围仅在网易来源可见，并在查询分支再次校验来源；动态或其他内置来源不再通过该范围查询网易账号。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（47 秒）。此项没有真实窗口输入或真实账号请求验收。

`4a749e3` 桌面搜索导航保存完整 SourceId，重新进入搜索恢复动态来源；结果、目录详情、推荐与歌曲菜单按有效来源及 registry generation 重建，拒绝继续展示旧来源内容。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（48 秒）。真实插件服务和窗口导航输入验收仍待完成。

`15540f9` 网络音质：Windows INetworkCostManager 查询首选互联网连接计费状态；默认关闭，未知状态回退显式默认音质，按网络应用时不写回默认值。接入内置/Provider 队列、桌面设置和随应用退出撤销的 5 秒后台检查。`gradle :shared:desktopTest --tests "melox.audio.*" --tests "melox.playback.*" :desktop:desktopTest :desktop:desktopJar` 成功（2 分 7 秒）；XML 核实共享 104 项通过、0 跳过，桌面 17 项中 11 通过/6 条件跳过。专项包含暂停队列身份/进度及默认值保留。此结果不是完整共享回归或物理计费网络切换验收。

`740635c` 桌面歌词绑定搜索支持 hasMore 分页，沿用提交的关键词并按完整资源身份去重，失败保留已加载内容供重试；桌面测试与 JAR 成功（45 秒）。`b461cc7` 有效逐字强绑定停止后续自动 AMLL 升级，专项验证明确绑定不会被替换；歌词专项与桌面编译成功（9 秒，编译复用前一命令已完成的输出；前一命令因工具 60 秒上限中断，不计作成功证据）。

`402e76e` 桌面歌词选择支持在网易/QQ/Spotify/Apple Music 搜索结果上切换 AMLL 预览，以对应 AMLL query bindingValue 持久化绑定；切换预览模式清除旧选择，插件来源保留自身歌词能力。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（43 秒）。真实 AMLL 服务内容、窗口输入及同内容视觉验收仍未完成。

`e6afe5b` 桌面播放器更多菜单接入歌词来源选择：内置与已启用动态来源按搜索/歌词 capability 筛选，完整 SourceId 搜索和结果过滤，逐字预览后持久化强绑定，支持解除当前歌曲绑定；绑定 revision 触发播放器重新加载。查询 scope 随来源/generation 变化撤销。歌词专项及桌面测试/JAR 成功（1 分 42 秒）；最终动态列表与空结果提示后桌面测试/JAR 再次成功（45 秒）。真实账号/插件服务、窗口输入、同内容视觉与 AMLL 独立选择仍待验收或补齐。

`6e75097` 歌词强绑定加载补齐 capability、来源 generation 与 Provider 实例校验，拒绝请求中停用的来源返回内容；专项覆盖停用拒绝与重新启用后成功。`gradle :shared:desktopTest --tests "melox.lyrics.*" :desktop:compileKotlinDesktop` 成功（1 分 23 秒）。

截至 `c8cb341`，`gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功（2 分 13 秒）：共享 XML 586 项全部通过，0 失败/错误/跳过；桌面测试与 JAR 为 UP-TO-DATE。包含 `fa1f56d` 本地真实 TLS API 响应读取、已信任证书仍拒绝主机名不匹配，以及 `c8cb341` 内置搜索返回插件候选时跳过其播放能力并继续已配置第三方阶段。同期尚未提交的歌词绑定撤销检查未包含在该轮已编译输入内，需独立验证。完整自动回归不代表 UI/功能迁移完成。

`c8809d5` 旧 PlaybackFallbackChain 对动态来源明确返回无回退，不再访问会抛错的旧枚举；内置跨来源候选按完整 SourceId 比对。`gradle :shared:desktopTest --tests "melox.playback.*" --tests "melox.plugin.*" --tests "melox.download.*" :desktop:desktopTest :desktop:desktopJar` 成功（2 分 16 秒），共享层 XML 174 项通过、0 失败/错误/跳过。此为专项回归，不等于完整迁移验收。

`90f9735` 插件下载在传输期间订阅注册表变化，generation/实例失效时主动关闭 broker，终止阻塞连接；传输结束取消监听。插件/下载专项及桌面编译成功（1 分 15 秒）。真实来源停用贯穿下载的窗口验收和账号撤销联动仍待完成。

`e1eda90` 使用本地真实 TLS 服务验证媒体读取：默认系统信任拒绝测试自签证书；仅测试构造器注入信任该证书的 trust manager 后完成 TLS 握手并读取音频体，服务端请求包含 handshake。未关闭主机名验证，生产默认客户端保持系统信任与公网 DNS 策略。插件/下载专项及桌面编译成功（1 分 19 秒）。本地 TLS 的内部路由/DNS 注入不等于公网 DNS、真实插件服务或账号媒体验收。

`ee1158a` 媒体流取消 API 的 5 秒整请求超时，保留连接超时与 15 秒读取超时；读取异常立即关闭响应并取消所属请求。真实本地 HTTP socket 测试读取完整媒体体，并观察读取线程进入底层阻塞 socket read 后关闭 broker，3 秒内中断读取。`gradle :shared:desktopTest --tests "melox.plugin.*" --tests "melox.download.*" :desktop:compileKotlinDesktop` 成功（1 分 6 秒）。测试使用内部路由与 DNS 注入，未代表公网 DNS/TLS 或真实插件媒体验收。

`14edfaa` Provider 下载生产入口按动态身份分流到私有 PluginHttpBroker，连接前及最终提交检查 registry generation，任务取消关闭 broker；动态来源拒绝非 PluginMusicProvider 和本地 file URL 绕过。`gradle :shared:desktopTest --tests "melox.download.*" --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 最终重跑成功（11 秒），XML 71 项通过、0 失败/错误/跳过，包含动态文件拒绝与临时文件清理。尚缺实际插件远程下载成功、账号绑定与真实 TLS 证据。

`e6fb2ef` 私有 broker 新增渐进媒体输入流，连接前校验 manifest 域名/HTTPS/头部，拒绝重定向和 HLS/DASH MIME，声明长度和读取累计限制 512 MiB；每次 read/skip 检查撤销，流关闭取消请求并移除所有权。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功（1 分 11 秒）。媒体流测试为 interceptor fixture，未替代真实 TLS/socket、播放/下载生产链路与在途阻塞读取取消验收。

`99b9246` 每次调用独立的 PluginHostCalls 接通 HTTP broker 和账号作用域私有存储；验证 invocation token、调用 ID 去重、32 次调用/48 KiB 输入限额、账号撤销和关闭，异常只返回受控代码。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功（13 秒），XML 58 项通过、0 失败/错误/跳过。首轮新增测试缺少 manifest 参数而编译失败，补齐后重跑成功。进程 IPC 和生产工厂尚未接入，不能视为隔离运行时完成。

`e54fd19` 桌面服务页为已启用插件增加单来源搜索入口，通过 SourceId 导航总线打开空查询，后续输入只查询该插件；不自动改变聚合白名单。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（42 秒）。生产插件安装/启动仍待接入，未执行真实窗口点击验收。

`d964980` 来源撤销及返回身份错误校验置于请求异常处理范围内，失败显示页面可重试错误；协程取消继续传播。修正此前 onSuccess 内校验异常可能逃逸页面协程的问题。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（48 秒）。

`29922b9` 搜索打开的 Provider 专辑/歌手详情以完整资源 ID 区分状态，并订阅注册表 generation；来源被停用时清除旧内容、显示不可用，迟到结果必须通过来源/generation 校验。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（53 秒）。真实插件启停窗口验收仍待生产运行时接通。

`f94bae0` 按 Android `edc829e` 更新 SpotifyTrackMatcher：动态候选身份不访问旧枚举；无时长且完整歌手名单一致时补时长分；保留已知时长误差拒绝，汽水优先分与基线一致。Spotify Provider 的自动回退仍仅选择内置 Provider，并按完整身份查找候选来源。`gradle :shared:desktopTest --tests "melox.provider.spotify.*" --tests "melox.music.*" :desktop:compileKotlinDesktop` 成功（1 分 5 秒），XML 48 项通过、0 失败/错误/跳过；新增缺时长/歌手不完整/动态候选和时长误差回归。真实 Spotify 服务未验收。

`f2e4721` 当选择动态来源且不存在已保存的内置聚合白名单时，不再用旧内置选择填充查询；开启聚合也不隐式写入内置来源，插件仍需明确加入白名单。原有内置白名单保留。`gradle :shared:desktopTest --tests "melox.music.*" :desktop:compileKotlinDesktop` 成功（1 分 16 秒），包含默认无请求、开启聚合不增加内置来源和显式插件白名单回归。

`a03a130` 桌面目录搜索展示 Provider 歌单/专辑/歌手网格，卡片使用完整 MusicResourceId，点击进入对应 Provider 详情；保留桌面卡片布局和滚动适配。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（49 秒）。未执行真实插件目录查询或同内容像素验收。

`bd78218` 歌曲菜单的歌曲/歌手/专辑搜索使用完整 SourceId，经搜索导航总线指定插件单来源查询；插件未安装时显示该来源失败，不回退网易。桌面动态歌曲搜索结果接入歌曲菜单；切换来源或注册表变更时清空旧搜索结果，目录结果校验来源和 generation。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（48 秒）。生产插件运行时、目录结果窗口交互与插件实际查询尚待验收。

截至 `25a6c5c` 完整回归：`gradle :shared:desktopTest :desktop:desktopTest :desktop:desktopJar` 成功（2 分 16 秒），共享层 XML 577 项通过、0 失败/错误/跳过；桌面 XML 17 项中 11 通过、6 条件跳过，0 失败/错误，桌面测试和 JAR 为最近成功结果的 UP-TO-DATE 复用。包含插件协议/账号/OAuth/独立 JVM 事务恢复及 LAN/分页改动。AutoMix 先前偶发连续交接失败本轮未复现，未定位原因，继续保留待查；此回归不代表桌面全量迁移或插件生产运行时完成。

`0c8ff33` 管理器持久化异常专项：启用意图写入失败时注销未启用候选并关闭资源；停用意图写入失败时保留原 Provider、Enabled 状态和资源，恢复写入后可成功停用。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功（14 秒），新增边界测试通过；随后启动共享层完整回归，结果单独记录。

`b730c87` 独立 JVM 事务恢复：生成两个真实 RSA-2048 发布者签名包，显式批准候选摘要后执行发布者轮换，子进程在 decision/archive/publisher durable 三个边界调用 Runtime.halt(73)，不执行 catch/finally 回滚。父进程等待退出并重建仓库，验证决策写入后保留旧包、归档落盘后恢复新包及对应发布者指纹、清除事务日志、二次重启结果稳定。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功（27 秒），XML 55 项通过、0 失败/错误/跳过。此证据覆盖独立进程非正常终止，不等同磁盘断电/缓存损失，也未包含管理 UI 发布者批准交互。

`81cd272` 桌面音乐服务页展示当前启用的动态 Provider，并提供逐来源“参与搜索”开关，接入 SourceId 白名单持久化；订阅注册表和偏好 revision。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（47 秒）。当前生产插件加载仍待实现，因此未将控件编译成功视为插件安装/查询验收完成。

`f5c3644` 共享 SearchSong 的网易原生写入门控改为 SourceId 内置值检查，动态歌曲即使带正数旧 UI id 也不进入网易端点；歌单分页和喜爱查找按完整 SourceId 比较。新增插件两页加载、喜爱匹配、跨来源拒绝与旧 UI 正数 id 回归。`c880c92` AutoSync 保留内置旧缓存键，对动态歌曲使用完整来源，DLNA 当前歌曲通知改用完整来源。`gradle :shared:desktopTest --tests "melox.music.*" --tests "melox.lan.*" :desktop:compileKotlinDesktop` 成功（3 分 9 秒），XML 54 项通过、0 失败/错误/跳过。AutoSync 与 DLNA 本轮只验证编译，实际设备与动态歌曲同步未验收。

`39f5993` 桌面歌曲搜索使用与手机共用的显式 SourceId 聚合白名单，取消桌面绕过聚合的条件；目录/本地曲库范围保持独立，聚合部分来源失败在桌面单列展示，来源标题反映当前查询范围。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（1 分 17 秒）。尚需带真实插件的生产查询与窗口输入验收。

`52dac0a` LAN 队列改用 SourceId 往返及查找，保留 qq/youtube 旧别名；插件未注册时明确拒绝，解析期间注册表变化后拒绝旧结果。`55cfd57` 播放器、首页、搜索、收藏、推荐列表和歌单详情移除旧枚举身份访问；歌单详情与操作菜单订阅注册表变化，拒绝撤销后的加载结果。`gradle :shared:desktopTest --tests "melox.lan.*" :desktop:desktopTest :desktop:desktopJar` 成功：LAN 15 项通过，桌面 17 项中 11 通过、6 条件跳过，0 失败/错误，JAR 成功。后续重复运行全部相关编译/测试为 UP-TO-DATE。不代表共享全量回归、插件实际 LAN 传输或真实窗口验收完成。

`c68d289` 修复桌面旧枚举入口：CLI 接受 SourceId、等待插件恢复、按当前注册表查找并检查搜索结果来源与 generation；探索歌单/榜单卡片键和颜色使用 SourceId；插件歌曲分享不再访问抛错的旧 `.source`。`gradle :desktop:desktopTest :desktop:desktopJar` 成功（48 秒）。尚无 CLI 动态插件端到端输入回放；LAN 同类入口另行验证。

`1793416` 浏览器入口支持原账号重新授权；真实回环集成测试继续验证宿主刷新成功更新同一账号，以及交换期间取消选择后拒绝迟到 token、保留既有凭据。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功（16 秒）。token 服务为内部 fixture；真实浏览器、服务授权和生产 UI 接线仍未完成。

`29a4ab1` OAuth 重新授权与刷新：重新授权捕获旧账号租约并更新原槽位，刷新走固定 token 端点并以会话摘要比较交换写回；测试验证刷新保留 refresh token、重新授权不沿用旧 refresh token、策略替换及旧租约撤销。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功（23 秒），XML 54 项通过、0 失败/错误/跳过。新增真实 127.0.0.1 HTTP 回调测试：错误 state 返回 400，正确回调返回 200/no-store，管理器提交账号后恢复 Provider，监听端口关闭；token HTTPS 服务仍为内部 fixture，账号集成用测试保护器，真实 DPAPI 另由仓库专项覆盖。生产账号 UI、实际浏览器与服务授权仍待验收。

`dc346e3` OAuth 会话与 Windows 回环浏览器适配：随机 state/verifier、回调目标绑定、重复参数拒绝、交换前认领、防重放、十分钟单调时钟过期、取消及生命周期门控提交；新增管理器 OAuth 提交入口，避免提交前取消自身待处理会话。Windows 监听在打开浏览器前绑定 127.0.0.1 随机端口，回调检查 state/路径/方法并发送 no-store 文本响应，结束时关闭监听。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功（16 秒），XML 51 项通过、0 失败/错误/跳过。会话测试使用 HTTP fixture；浏览器入口目前仅有回调参数策略专项，尚无真实回环套接字/账号端到端证据，生产账号 UI 和插件启动待接通。

`dde35c3` OAuth 宿主交换基础：PKCE S256、Bearer token 严格字段/有效期解析及溢出拒绝、清单固定 token 端点、POST 表单、禁止重定向、16 KiB 响应限制和撤销检查。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功（1 分 7 秒），XML 47 项通过、0 失败/错误/跳过。HTTP 结果由内部 interceptor fixture 提供；浏览器、回调会话和账号落盘接线尚在实施，不代表端到端 OAuth 登录完成。

`ad20096` HTTP 宿主凭据引用：共享层 PluginCredentialLease 隔离 Windows 账号存储类型，来源/策略/域名/有效期检查后覆盖脚本同名 header，会话切换通过密文与选择摘要立即拒绝旧租约。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功（1 分 8 秒），XML 45 项通过、0 失败/错误/跳过。首次编译发现 commonMain 不可直接引用 desktopMain 仓库类型，抽取共享接口；随后接口属性智能转换错误用局部值修复。fixture 验证 header 注入、未授权域名拒绝、无账号拒绝、策略变化拒绝、跨插件租约拒绝和切换后拒绝。Provider 媒体 credential 引用、运行时账号和 UI 尚待接通。

`2350574` Windows DPAPI 插件账号仓库：每账号独立密文、来源/账号内嵌绑定、加密后解密核验、原子落盘、会话选择及密文摘要撤销、刷新比较交换、失败保留旧数据。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功（10 秒），XML 44 项通过、0 失败/错误/跳过；其中账号三项真实 Windows DPAPI 测试全部执行，验证重建读取、磁盘不含 token、切换后拒绝迟到刷新、损坏保留、跨来源复制拒绝和加密失败。尚未接通账号 UI、OAuth 浏览器流程及生产运行时。

`1a60ba6` 桌面插件封面接线验证：来源授权捕获、禁用/重新启用/替换后的旧授权拒绝；列表与播放器图片禁用插件缓存，调色板跳过插件缓存，图片通过独立 broker 加载并在解码前校验最长边 4096、总像素 8,000,000。共享插件专项 XML 41 项通过；`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:desktopTest :desktop:desktopJar` 成功（49 秒，共享专项沿用上一轮缓存结果）。首轮桌面编译的多行字符串加号错误已修复。额外图片尺寸专项与桌面 JAR 成功（35 秒），覆盖正常/边长/总像素/损坏图片。真实插件安装/启用与生产网络验收尚待运行时完成。

`55a1453` 增加独立 OkHttp 插件 HTTP/图片 broker：HTTPS/443/清单域名授权、逐跳重定向检查和跨域请求头清空、8 次请求配额、API 64 KiB/图片 2 MiB、图片 MIME 与重定向拒绝、调用关闭和来源撤销检查。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，5 分 16 秒，XML 40 项通过、0 失败/错误/跳过。响应测试用内部 interceptor 提供确定性 HTTP fixture，不代表真实 TLS/套接字验收；生产使用独立客户端和已验证的 DNS。credential 引用仍拒绝；凭据/OAuth、媒体流、运行时 host call 和桌面图片加载正在接通。

`5b822f8` 迁移插件 DNS 公网地址校验，每次解析检查全部回答，将同一地址列表直接交给 OkHttp；混合公私网、空回答和解析后重绑定均拒绝。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，1 分 31 秒，XML 35 项通过、0 失败/错误/跳过。证据为确定性解析器故障测试，尚不代表生产 HTTP/媒体传输或真实 DNS/套接字验收完成。

`e035e8a` 管理器增加持久诊断读取/清除回调和卸载清理，禁用来源可读取记录，损坏记录显示宿主固定提示并保留原文件，不转发解析异常中的内容。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，1 分 26 秒；新增管理器测试验证禁用状态读取、损坏提示、清除、卸载清理，不加载运行时。运行时日志与生产 UI 仍待接通。

`99fbebf` Windows 插件私有存储与宿主诊断记录：按 SourceId/账号目录摘要隔离，键数 64、单值 16 KiB、总量 64 KiB；诊断最多 100 条、32 KiB，仅宿主白名单方法/错误码及耗时；写入同步后原子替换。专项 `gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，1 分 27 秒，XML 31 项通过、0 失败/错误/跳过。验证重建读取、账号/来源定向清除、配额拒绝后字节不变、损坏数据保留、诊断截断与敏感输入不落盘。首次编译默认路径 String/File 不匹配已修正。无 Windows v1 数据，因此不引入 Android 旧目录迁移。运行时 host call、生命周期诊断与 UI 仍待接线。

`35589f8` 接通 PluginMusicProvider 的 API v1 搜索、目录、首页、账号、认证、收藏、歌词、播放/下载/外部解析方法，全部返回先经 PluginWireContract 检查；补 RankingCatalogCapability 和专辑/歌手详情 hasMore。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功，2 分 4 秒，XML 28 项通过、0 失败/错误/跳过。首次运行测试类两个表达式测试方法推导非 Unit 返回值，JUnit 初始化失败，显式指定 Unit 后通过。测试覆盖目录分页/身份、越来源输入、缺能力不调用、收藏类型、歌词校验及原生媒体域名/解析外部 CDN。当前运行器仍为契约 fixture；隔离运行时、生产启动、账号/媒体 broker 与 UI 未接通，credential=active 暂拒绝消费，不宣称生产插件完整可用。

`4e64fc8` 目录 codec 映射歌单、专辑、歌手、榜单、账号及分页，保留 SourceId 与封面来源封装，检查返回分页与请求一致。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，1 分 54 秒；首次编译因 Windows 歌单模型没有 Android isEditable 字段失败，移除该字段引用后通过。插件仍只声明读取能力，不提供 PlaylistWrite。实际 Provider 接线及生产运行时待完成。

`165ab01` 移植标准歌曲结果解析与封面来源封装；插件歌曲、歌手和专辑 ID 保留 SourceId 和不透明资源字面值，封面仅接受清单允许的 HTTPS 域名并保存来源封装。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，2 分 7 秒，XML 22 项通过、0 失败/错误/跳过。新增验证覆盖百分号资源 ID、相关模型来源、上游字段不猜测、非法数值和封面 URL 精确往返/域名/端口/凭据/重复查询拒绝。实际封面网络请求、缓存撤销和 PluginMusicProvider 仍待接线。

`6b1d8ae` 将 MusicAccountSummary 主身份迁移到 SourceId，保留内置来源构造兼容；插件账号旧 source 访问明确报错。`gradle :shared:desktopTest --tests "melox.plugin.*" :desktop:compileKotlinDesktop` 成功，3 分 16 秒。新增账号测试验证同账号 ID 不同插件来源隔离、copy 保留来源、内置来源构造和认证状态。此项为公共账号模型，凭据仓库及插件账号 UI 尚未完成。

`f2241a4` 移植全部 22 个 API v1 方法的返回结构校验，覆盖分页、嵌套目录模型、账号认证、收藏确认、媒体和歌词。专项 `gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，1 分 52 秒，XML 19 项通过、0 失败/错误/跳过；遍历所有已填充字段，以不同 JSON 类型进行超过 1000 次变异并断言拒绝，另验证歌词乱序、分页范围、请求头换行和 DRM 声明。校验器尚待实际 PluginMusicProvider 调用，不能视为生产插件协议链路已完成。

`8412162` 增加 Android Authentication/Download 能力声明、认证状态模型和动态注册表接口校验，同时移植渐进音频格式限制。`gradle :shared:desktopTest --tests "melox.plugin.*"` 成功，2 分 13 秒；新增测试覆盖声明能力却缺实现时拒绝注册，HLS/DASH/DRM 及 MIME 声明拒绝。实际 Provider 与受控媒体请求仍待接通。随后的 PluginWireContract 独立构建验证中，未计入本轮结论。

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
