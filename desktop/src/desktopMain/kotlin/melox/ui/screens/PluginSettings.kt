package melox.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.swing.Swing
import melox.plugin.*
import melox.music.model.SourceId

/** Desktop management actions share the production plugin lifecycle with playback. */
@Composable
internal fun PluginSettings() {
    val scope = rememberCoroutineScope()
    var manager by remember { mutableStateOf<PluginManager?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<ByteArray, PluginArchiveReader.Package>?>(null) }
    var accountsFor by remember { mutableStateOf<SourceId?>(null) }
    var diagnostics by remember { mutableStateOf<String?>(null) }
    var diagnosticSource by remember { mutableStateOf<SourceId?>(null) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null
            try { withContext(Dispatchers.IO) { block() } }
            catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                error = PluginErrors.describe(failure)
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        try { manager = withContext(Dispatchers.IO) { DesktopPluginRuntime.manager() } }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = PluginErrors.describe(failure)
        }
    }
    val owner = manager
    Text("插件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("plugin-settings"))
    Text("导入可信的 MeloX 插件包。安装后默认禁用，启用后可在音源中选择。")
    Spacer(Modifier.height(10.dp))
    Button(enabled = !busy && owner != null, onClick = {
        scope.launch {
            val file = withContext(Dispatchers.Swing) {
                val chooser = javax.swing.JFileChooser().apply {
                    dialogTitle = "选择插件包"
                    fileFilter = javax.swing.filechooser.FileNameExtensionFilter("MeloX 插件包", "zip", "melox")
                }
                if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
            }
            if (file != null) action {
                require(file.length() in 1..4L * 1024 * 1024) { "INVALID_ARGUMENT" }
                val bytes = file.inputStream().use { it.readNBytes(4 * 1024 * 1024 + 1) }
                val candidate = PluginArchiveReader.read(bytes)
                withContext(Dispatchers.Swing) { pending = bytes to candidate }
            }
        }
    }, modifier = Modifier.testTag("plugin-import")) { Text("导入或升级插件") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("plugin-error")) }
    if (owner != null) {
        val entries by owner.plugins.collectAsState()
        val damaged by owner.damagedPackages.collectAsState()
        val recovery by owner.recoveryError.collectAsState()
        recovery?.let { message ->
            Text(message)
            TextButton(enabled = !busy, onClick = { action { owner.resetEnabledState() } }) { Text("重置为全部禁用") }
        }
        if (entries.isEmpty()) Text("尚未安装插件", Modifier.padding(vertical = 12.dp))
        entries.values.forEach { entry ->
            val id = entry.manifest.sourceId
            key(id) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("${entry.manifest.name} · ${entry.manifest.version}", style = MaterialTheme.typography.titleSmall)
            Text(id.value)
            Text(when (entry.status) { PluginManager.Status.Enabled -> "已启用"; PluginManager.Status.Disabled -> "已禁用"; PluginManager.Status.Failed -> "加载失败" })
            entry.error?.let { Text(PluginErrors.message(it), color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = { action {
                    if (entry.status == PluginManager.Status.Enabled) owner.disable(id) else owner.enable(id)
                } }) { Text(if (entry.status == PluginManager.Status.Enabled) "禁用" else "启用") }
                TextButton(enabled = !busy, onClick = { accountsFor = id }) { Text("账号") }
                TextButton(enabled = !busy, onClick = { action {
                    val lines = owner.diagnostics(id).joinToString("\n")
                    withContext(Dispatchers.Swing) { diagnosticSource = id; diagnostics = lines }
                } }) { Text("诊断") }
                var confirmRemove by remember(id) { mutableStateOf(false) }
                TextButton(enabled = !busy, onClick = { confirmRemove = true }) { Text("卸载") }
                if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("卸载 ${entry.manifest.name}？") },
                    text = { Text("插件将停止运行。账号与存储数据会保留，可在重新安装后管理。") },
                    confirmButton = { TextButton(onClick = { confirmRemove = false; action { owner.uninstall(id) } }) { Text("卸载") } },
                    dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消") } })
            }
            }
        }
        damaged.forEach { item ->
            Text("无法读取的插件包：${item.storageKey.orEmpty()}")
            TextButton(enabled = !busy, onClick = { action { owner.dismissDamaged(requireNotNull(item.storageKey)) } }) { Text("移除损坏记录") }
        }
        pending?.let { (bytes, candidate) ->
            val previous = entries[candidate.manifest.sourceId]
            AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text(if (previous == null) "安装插件" else "升级插件") },
                text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("${candidate.manifest.name} · ${candidate.manifest.version}")
                    Text(candidate.manifest.sourceId.value)
                    Text("能力：${candidate.manifest.capabilities.joinToString { it.wireName }}")
                    Text("网络域名：${candidate.manifest.networkDomains.joinToString()}")
                    Text("认证方式：${candidate.manifest.authentication?.type ?: "无"}")
                    Text("当前发布者：${previous?.publisher?.fingerprint ?: "未签名或尚未安装"}")
                    Text("候选发布者：${candidate.publisher?.fingerprint ?: "未签名"}")
                    Text("包摘要：${candidate.archiveSha256}")
                } }, confirmButton = { TextButton(enabled = !busy, onClick = { action {
                    if (previous == null) owner.install(bytes, approvedPublisherArchive = candidate.archiveSha256)
                    else owner.update(candidate.manifest.sourceId, bytes, approvedManifest = candidate.manifest,
                        approvedPublisherArchive = candidate.archiveSha256)
                    withContext(Dispatchers.Swing) { pending = null }
                } }) { Text("确认${if (previous == null) "安装" else "升级"}") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") } })
        }
        accountsFor?.let { id -> PluginAccountsDialog(id, owner, onDismiss = { accountsFor = null }) }
    }
    diagnostics?.let { value -> AlertDialog(onDismissRequest = { diagnostics = null }, title = { Text("插件诊断") },
        text = { Text(value, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { diagnostics = null }) { Text("关闭") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { action {
            owner?.clearDiagnostics(requireNotNull(diagnosticSource))
            withContext(Dispatchers.Swing) { diagnostics = null }
        } }) { Text("清除诊断") } }) }
}

@Composable
private fun PluginAccountsDialog(source: SourceId, manager: PluginManager, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var accounts by remember(source) { mutableStateOf<List<PluginAccountVault.Account>>(emptyList()) }
    var selected by remember(source) { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loginJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val entry = manager.plugins.collectAsState().value[source]
    suspend fun refresh() {
        val values = withContext(Dispatchers.IO) { DesktopPluginRuntime.accounts(source) to DesktopPluginRuntime.selectedAccount(source) }
        accounts = values.first; selected = values.second
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        loginJob = scope.launch {
            busy = true; error = null
            try { withContext(Dispatchers.IO) { block() }; refresh() }
            catch (failure: Exception) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = PluginErrors.describe(failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(source) {
        try { refresh() }
        catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            error = PluginErrors.describe(failure)
        }
    }
    DisposableEffect(source) { onDispose { loginJob?.cancel(); token = "" } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("${entry?.manifest?.name ?: "插件"}账号") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        accounts.forEach { account ->
            Text(account.displayName + if (selected == account.id) "（当前）" else "")
            Row {
                TextButton(enabled = !busy, onClick = { action { DesktopPluginRuntime.selectAccount(source, account.id) } }) { Text("选择") }
                TextButton(enabled = !busy, onClick = { action { DesktopPluginRuntime.removeAccount(source, account.id) } }) { Text("删除") }
            }
        }
        TextButton(enabled = !busy && selected != null, onClick = { action { DesktopPluginRuntime.selectAccount(source, null) } }) { Text("退出当前账号") }
        OutlinedTextField(label, { label = it }, label = { Text("账号名称") }, singleLine = true)
        when (entry?.manifest?.authentication?.type) {
            "apiToken" -> {
                OutlinedTextField(token, { token = it }, label = { Text("访问令牌") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                TextButton(enabled = !busy && label.isNotBlank() && token.isNotBlank(), onClick = {
                    val value = token; token = ""
                    action { DesktopPluginRuntime.importToken(source, label, value) }
                }) { Text("导入并选择") }
            }
            "oauth2Pkce" -> {
                TextButton(enabled = !busy && label.isNotBlank() && entry.status == PluginManager.Status.Enabled, onClick = { action { DesktopPluginRuntime.login(source, label) } }) { Text("浏览器登录") }
                TextButton(enabled = !busy && selected != null && entry.status == PluginManager.Status.Enabled, onClick = { action { DesktopPluginRuntime.login(source, label, true) } }) { Text("重新授权当前账号") }
                if (entry.status != PluginManager.Status.Enabled) Text("启用插件后可登录或重新授权")
            }
            else -> Text("此插件无需账号授权")
        }
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = { loginJob?.cancel() }) { Text("取消操作") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    } }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
