package io.github.sweetpotato_digua.mbpu

import android.content.Context
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置页的配置备份区块；文件选择直接挂宿主 Activity，兼容 AppTheme 的 Context 包装。 */
@Composable
internal fun ConfigBackupSection(
    store: Store,
    onConfigRestored: () -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.Backdrop? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Map<String, Int>?>(null) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    fun show(text: String, failed: Boolean = false) {
        message = text
        error = failed
    }

    LaunchedEffect(message) {
        if (message.isEmpty()) return@LaunchedEffect
        delay(5000)
        message = ""
    }

    // contract 必须 remember 固定：每次重组新建会导致 rememberSafLauncher 的
    // remember key 失效 → 同 key 重复注册 → IllegalArgumentException 或结果丢失。
    val exportContract = remember { ActivityResultContracts.CreateDocument(ConfigBackup.MIME_TYPE) }
    val export = rememberSafLauncher<String, Uri?>(
        key = "mbpu-config-export",
        contract = exportContract,
    ) { uri ->
        if (uri == null || busy) return@rememberSafLauncher
        busy = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    val json = ConfigBackup.export(store.snapshot())
                    ConfigBackup.write(context.contentResolver, uri, json)
                }.exceptionOrNull()
            }
            busy = false
            if (failure == null) show(context.getString(R.string.backup_export_success))
            else show(context.getString(R.string.backup_export_failed), true)
        }
    }
    val restoreContract = remember { ActivityResultContracts.OpenDocument() }
    val restore = rememberSafLauncher<Array<String>, Uri?>(
        key = "mbpu-config-restore",
        contract = restoreContract,
    ) { uri ->
        if (uri == null || busy) return@rememberSafLauncher
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ConfigBackup.parse(ConfigBackup.read(context.contentResolver, uri))
                }
            }
            busy = false
            result.onSuccess { pendingRestore = it }
                .onFailure {
                    val res = if (it is ConfigBackup.FormatException) {
                        R.string.backup_import_invalid
                    } else {
                        R.string.backup_import_read_failed
                    }
                    show(context.getString(res), true)
                }
        }
    }

    SmallTitle(
        text = stringResource(R.string.backup_section),
        insideMargin = PaddingValues(16.dp, 8.dp),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Column {
            ArrowPreference(
                title = stringResource(R.string.backup_export),
                summary = stringResource(R.string.backup_export_summary),
                enabled = !busy,
                onClick = {
                    if (!busy) {
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        runCatching { export?.launch("mbpu-config-$stamp.json") }
                            .onFailure { show(context.getString(R.string.backup_export_failed), true) }
                    }
                },
            )
            HorizontalDivider()
            ArrowPreference(
                title = stringResource(R.string.backup_restore),
                summary = stringResource(R.string.backup_restore_summary),
                enabled = !busy,
                onClick = {
                    if (!busy) {
                        runCatching {
                            restore?.launch(arrayOf("*/*"))
                        }.onFailure { show(context.getString(R.string.backup_import_read_failed), true) }
                    }
                },
            )
            if (message.isNotEmpty()) {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (error) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    GlassDialog(
        onDismissRequest = { if (!busy) pendingRestore = null },
        show = pendingRestore != null,
        backdrop = backdrop,
        title = stringResource(R.string.backup_restore_confirm_title),
        summary = stringResource(R.string.backup_restore_confirm_summary, pendingRestore?.size ?: 0),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                text = stringResource(R.string.cancel),
                enabled = !busy,
                modifier = Modifier.weight(1f),
                onClick = { pendingRestore = null },
            )
            Spacer(Modifier.height(4.dp))
            TextButton(
                text = stringResource(R.string.confirm),
                enabled = !busy,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    // busy 立即占用：enabled 依赖下一次重组，同帧二次点击会并发进入。
                    if (busy) return@TextButton
                    val configs = pendingRestore ?: return@TextButton
                    busy = true
                    scope.launch {
                        val failure = withContext(Dispatchers.IO) {
                            runCatching {
                                val before = store.snapshot()
                                store.replaceConfigs(configs)
                                // 有类别被移除的包需要重启才能卸载 hook。
                                // 不无条件 clearPendingStop：若包在本次恢复前已有待重启标记，
                                // 本次没新增移除也不能清掉旧标记。
                                for (pkg in ConfigBackup.hasRemovedCategory(before, configs)) {
                                    store.markPendingStop(pkg)
                                }
                            }.exceptionOrNull()
                        }
                        busy = false
                        if (failure == null) {
                            onConfigRestored()
                            pendingRestore = null
                            show(context.getString(R.string.backup_restore_success, configs.size))
                        } else {
                            show(context.getString(R.string.backup_restore_failed), true)
                        }
                    }
                },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun <I, O> rememberSafLauncher(
    key: String,
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ActivityResultLauncher<I>? {
    val view = LocalView.current
    val activity = remember(view) {
        var current: Context = view.context
        while (current is android.content.ContextWrapper && current !is ComponentActivity) {
            current = current.baseContext
        }
        current as? ComponentActivity
    } ?: return null
    val latest by rememberUpdatedState(onResult)
    val launcher = remember(activity, key, contract) {
        activity.activityResultRegistry.register(key, contract) { result -> latest(result) }
    }
    DisposableEffect(launcher) { onDispose { runCatching { launcher.unregister() } } }
    return launcher
}
