package com.sweetpotato.MBPU

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ContentUris
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 日志区最多显示的行数；完整日志通过复制/导出获取。 */
private const val LOG_TAIL_LINES = 120
private const val EXPORT_KEEP_COUNT = 10

/** 诊断页：只读状态 + 模块日志。不提供会改变 Hook 行为的开关。 */
@Composable
fun DebugScreen(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val glassBackdrop = rememberGlassBackdrop()

    var message by remember { mutableStateOf("") }
    // 同一条消息通道既报成功也报失败，必须区分颜色，否则「导出失败」看起来和成功一样。
    var messageIsError by remember { mutableStateOf(false) }
    // 提示不自动消失的话会一直留在卡片底部，和后续操作结果混淆。
    LaunchedEffect(message) {
        if (message.isEmpty()) return@LaunchedEffect
        delay(4000)
        message = ""
    }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var logsTruncated by remember { mutableStateOf(false) }
    var level by remember { mutableStateOf(LogLevel.ALL) }
    var filterPackage by remember { mutableStateOf("") }
    var filterInput by remember { mutableStateOf("") }
    var logError by remember { mutableStateOf<String?>(null) }
    val logScroll = rememberScrollState()

    var exportDir by remember { mutableStateOf(store.exportTreeUri()) }

    // 目录选择走 Activity 自带的 ActivityResultRegistry：
    // AppTheme 覆盖了 LocalContext，rememberLauncherForActivityResult 拿不到 Owner 会崩。
    val pickExportDir = rememberTreePicker { uri: Uri? ->
        if (uri != null) {
            val resolver = context.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val persisted = runCatching {
                resolver.takePersistableUriPermission(uri, flags)
                true
            }.getOrDefault(false)
            if (persisted) {
                store.setExportTreeUri(uri.toString())
                exportDir = uri.toString()
                messageIsError = false
                message = context.getString(R.string.debug_dir_set)
            } else {
                messageIsError = true
                message = context.getString(R.string.debug_dir_denied)
            }
        }
    }

    // 一开始就用完整骨架，值先占位；收集完成只替换数值，避免卡片高度突跳。
    val snapshot by produceState(initialValue = DebugSnapshot.placeholder()) {
        value = withContext(Dispatchers.IO) { collectDebugSnapshot(context, store) }
    }
    // Hook 状态直接由真实日志推导：能反映"确实注入并装上了多少类别"，
    // 不依赖 hook 进程写回 RemotePreferences（该路径在目标进程里不可靠）。
    // Hook 状态从日志解析 HOOKSTAT 标记：目标进程以自身 UID 运行，
    // 无法写入模块私有目录，日志是唯一可靠的跨进程通道。
    // 单独扫描整个日志文件，避免注入记录被后续日志挤出 120 行窗口。
    var hookStats by remember { mutableStateOf(emptyMap<String, HookStat>()) }

    LaunchedEffect(filterInput) {
        delay(200)
        filterPackage = filterInput.trim()
    }

    // 日志正文和 Hook 统计来自同一个 modules_*.log，合并成一次 root 读取。
    //
    // 关键：必须在 Activity RESUMED 时才轮询。Compose 的 LaunchedEffect 只看组合，
    // 退到后台/息屏时组合仍然存在，while(isActive) 会一直成立——否则诊断页会在后台
    // 每 5 秒 fork 一次 su，是明确的耗电问题。
    // 和主页同一个坑：findHostActivity() 在这个 Composition 里返回 null，
    // 协程随即静默 return，日志区永远显示「暂无日志」。用 Compose 提供的
    // LifecycleOwner，它由 setContent 注入，一定存在。
    val hostOwner = LocalLifecycleOwner.current
    LaunchedEffect(hostOwner) {
        val owner = hostOwner
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                val bundle = withContext(Dispatchers.IO) {
                    runCatching { readLogBundle() }.getOrNull()
                }
                if (bundle == null) {
                    // readLogBundle 抛异常≠没 root：可能是没有日志文件（模块未被注入过），
                    // 也可能是真的 root 不可用。读一下 id -u 区分。
                    val isRoot = withContext(Dispatchers.IO) {
                        runCatching { RootShell.available() }.getOrDefault(false)
                    }
                    logError = context.getString(
                        if (isRoot) R.string.debug_log_no_root
                        else R.string.debug_log_empty,
                    )
                } else {
                    logError = null
                    logs = bundle.logs
                    logsTruncated = bundle.truncated
                    hookStats = parseHookStats(bundle.hookStatLines)
                }
                delay(5000)
            }
        }
    }

    // 只在用户已经贴着底部时自动跟随，避免打断向上翻阅。
    val visibleLogs = filteredLogs(logs, level, filterPackage)
    LaunchedEffect(visibleLogs) {
        if (logScroll.value >= logScroll.maxValue - 48) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }

    Scaffold(
        topBar = {
            SettingsTopBar(stringResource(R.string.debug_title), onBack, glassBackdrop)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .asGlassSource(glassBackdrop)
                .verticalScroll(rememberScrollState())
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
        ) {
            SmallTitle(
                text = stringResource(R.string.debug_runtime),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                DebugRow(stringResource(R.string.debug_version), snapshot.version)
                HorizontalDivider()
                DebugRow(stringResource(R.string.debug_lsposed), snapshot.lsposed)
                HorizontalDivider()
                DebugRow(stringResource(R.string.debug_root), snapshot.root)
                HorizontalDivider()
                DebugRow(stringResource(R.string.debug_configs), snapshot.configCount)
                HorizontalDivider()
                DebugRow(stringResource(R.string.debug_pending), snapshot.pendingCount)
                HorizontalDivider()
                DebugRow(stringResource(R.string.debug_android), snapshot.android)
                HorizontalDivider()
                DebugRow(stringResource(R.string.debug_abi), snapshot.abi)
                HorizontalDivider()
                DebugRow(
                    stringResource(R.string.debug_label_cache),
                    AppLabels.stats(),
                )
            }

            SmallTitle(
                text = run {
                    val visible = filteredLogs(logs, level, filterPackage).size
                    when {
                        filterPackage.isNotEmpty() || level != LogLevel.ALL ->
                            stringResource(R.string.debug_log_filtered, visible, LOG_TAIL_LINES)
                        logsTruncated -> stringResource(R.string.debug_log_truncated, LOG_TAIL_LINES)
                        else -> stringResource(R.string.debug_log)
                    }
                },
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                // 级别筛选：4 个等宽胶囊按钮。TabRow 的默认最小宽度会撑破卡片，
                // 这里用 weight 平分，保证任何屏宽都不溢出。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (option in LogLevel.entries) {
                        val selected = level == option
                        TextButton(
                            text = stringResource(option.labelRes),
                            onClick = { level = option },
                            // 48dp 是无障碍最小热区；selected 语义让读屏能报出当前筛选级别
                            // （原来只靠颜色区分，TalkBack 读不出来）。
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    this.selected = selected
                                    role = Role.Tab
                                },
                            minWidth = 0.dp,
                            minHeight = 48.dp,
                            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            textStyle = MiuixTheme.textStyles.footnote1,
                            colors = if (selected) {
                                ButtonDefaults.textButtonColorsPrimary()
                            } else {
                                // surfaceContainerHigh 与卡片同色会看不出边界，用 Highest。
                                ButtonDefaults.textButtonColors(
                                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                                    textColor = MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            },
                        )
                    }
                }
                InputField(
                    query = filterInput,
                    onQueryChange = { filterInput = it },
                    onSearch = {
                        filterInput = it
                        filterPackage = it.trim()
                    },
                    // expanded / onExpandedChange 是 InputField 的必填参数：
                    // 这里不需要「搜索时展开成全屏输入」，固定折叠并空实现即可。
                    expanded = false,
                    onExpandedChange = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 10.dp, bottom = 10.dp),
                    label = stringResource(R.string.debug_log_filter_hint),
                    color = MiuixTheme.colorScheme.surfaceContainerHighest,
                )
                // 空状态和日志正文分开：verticalScroll 会撑开测量高度，导致 align 失效。
                // 固定 180dp 在 fontScale 2.0 时只剩三四行日志；改成最小高度，
                // 让内容区按可用空间撑开，字也从 10sp 提到 12sp（10sp 低于可读下限）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 必须给出有限的 max：外层 Column 用了 verticalScroll，
                        // 高度上限是 Infinity，传到这里会让内层 verticalScroll
                        // 在测量时抛 IllegalStateException（诊断页闪退的原因）。
                        .heightIn(min = 220.dp, max = 420.dp)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        logError != null -> Text(
                            text = logError.orEmpty(),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            textAlign = TextAlign.Center,
                        )
                        visibleLogs.isEmpty() -> Text(
                            text = stringResource(
                                if (logs.isEmpty()) R.string.debug_log_empty
                                else R.string.debug_log_no_match,
                            ),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            textAlign = TextAlign.Center,
                        )
                        else -> Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(logScroll),
                        ) {
                            Column {
                                // 只上色处理 Error/Warn 行，其余用普通前景色，避免整块染色失去层次。
                                for (line in visibleLogs) {
                                    Text(
                                        text = line,
                                        style = MiuixTheme.textStyles.footnote2,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = when {
                                            line.startsWith("E ") -> MiuixTheme.colorScheme.error
                                            // 警告原本用主色（蓝），和错误红不是同一个语义系，
                                            // 深色底上蓝字对比度也不足；改用独立橙色。
                                            line.startsWith("W ") -> AppColors.warn
                                            else -> MiuixTheme.colorScheme.onBackground
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SmallTitle(
                text = stringResource(R.string.debug_hook_stat),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (hookStats.isEmpty()) {
                    DebugRow(
                        title = stringResource(R.string.debug_hook_stat_none),
                        value = stringResource(R.string.debug_hook_stat_hint),
                    )
                } else {
                    hookStats.entries.take(20).forEachIndexed { index, entry ->
                        if (index > 0) HorizontalDivider()
                        DebugRow(
                            title = entry.key,
                            value = hookStatText(entry.value),
                        )
                    }
                }
            }

            SmallTitle(
                text = stringResource(R.string.debug_export_dir),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                DebugRow(
                    title = stringResource(R.string.debug_export_dir_current),
                    value = if (exportDir.isEmpty()) {
                        stringResource(R.string.debug_export_dir_default)
                    } else {
                        displayTreeName(context, exportDir)
                    },
                )
                HorizontalDivider()
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            runCatching {
                                pickExportDir()
                            }.onFailure {
                                messageIsError = true
                                message = context.getString(R.string.debug_dir_denied)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.debug_choose_dir),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    if (exportDir.isNotEmpty()) {
                        Button(
                            onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(exportDir),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                    )
                                }
                                store.setExportTreeUri("")
                                exportDir = ""
                                messageIsError = false
                                message = context.getString(R.string.debug_dir_reset)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.debug_reset_dir),
                                style = MiuixTheme.textStyles.button,
                            )
                        }
                    }
                }
            }

            SmallTitle(
                text = stringResource(R.string.debug_actions),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(
                        onClick = {
                            val text = formatDebugSnapshot(snapshot)
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard?.setPrimaryClip(ClipData.newPlainText("MBPU debug", text))
                            messageIsError = false
                            message = context.getString(R.string.debug_copied)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = stringResource(R.string.debug_copy),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    if (filterPackage.isNotEmpty()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            filterLogText(readFullLog(), level, filterPackage)
                                        }.getOrNull()
                                    }
                                    if (result.isNullOrBlank()) {
                                        messageIsError = true
                                        message = context.getString(R.string.debug_log_no_match)
                                    } else {
                                        val clipboard =
                                            context.getSystemService(ClipboardManager::class.java)
                                        clipboard?.setPrimaryClip(
                                            ClipData.newPlainText("MBPU log $filterPackage", result),
                                        )
                                        messageIsError = false
                                        message = context.getString(
                                            R.string.debug_log_pkg_copied,
                                            filterPackage,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.debug_copy_pkg_log, filterPackage),
                                style = MiuixTheme.textStyles.button,
                            )
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        filterLogText(readFullLog(), level, filterPackage)
                                    }.getOrNull()
                                }
                                if (result == null) {
                                    messageIsError = true
                                    message = context.getString(R.string.debug_log_no_root)
                                } else {
                                    val clipboard =
                                        context.getSystemService(ClipboardManager::class.java)
                                    clipboard?.setPrimaryClip(
                                        ClipData.newPlainText("MBPU log", result),
                                    )
                                    messageIsError = false
                                    message = context.getString(R.string.debug_log_copied)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.debug_copy_log),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val exported = withContext(Dispatchers.IO) {
                                    runCatching {
                                        exportLog(context, exportDir, level, filterPackage)
                                    }.getOrNull()
                                }
                                messageIsError = exported == null
                                message = if (exported == null) {
                                    context.getString(R.string.debug_export_failed)
                                } else {
                                    context.getString(R.string.debug_exported, exported)
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            // 按钮文案跟随实际导出位置，不再写死「下载目录」。
                            text = stringResource(
                                if (exportDir.isEmpty()) R.string.debug_export_log_downloads
                                else R.string.debug_export_log_custom,
                            ),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val cacheOk = withContext(Dispatchers.IO) {
                                    runCatching {
                                        AppLabels.refresh(context)
                                        true
                                    }.getOrDefault(false)
                                }
                                messageIsError = !cacheOk
                                message = context.getString(
                                    if (cacheOk) R.string.debug_cache_cleared
                                    else R.string.debug_cache_failed,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.debug_clear_cache),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                    if (message.isNotEmpty()) {
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (messageIsError) {
                                MiuixTheme.colorScheme.error
                            } else {
                                MiuixTheme.colorScheme.primary
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}


/** 从任意包装 Context 里找到宿主 Activity。 */
private fun Context.findActivity(): ComponentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    return current as? ComponentActivity
}

/**
 * 用 ActivityResultRegistry 注册目录选择器。
 * 不依赖 LocalActivityResultRegistryOwner，避免 AppTheme 替换 LocalContext 后崩溃。
 */
@Composable
private fun rememberTreePicker(onPicked: (Uri?) -> Unit): () -> Unit {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    val latest by rememberUpdatedState(onPicked)
    var launch by remember { mutableStateOf<(() -> Unit)?>(null) }
    if (activity == null) return launch ?: {}
    val key = "mbpu-export-dir"
    DisposableEffect(activity) {
        val registry = activity.activityResultRegistry
        val callback = registry.register(
            key,
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri -> latest(uri) }
        launch = { runCatching { callback.launch(null) } }
        onDispose {
            launch = null
            runCatching { callback.unregister() }
        }
    }
    return launch ?: {}
}

private data class DebugSnapshot(
    val version: String,
    val lsposed: String,
    val root: String,
    val configCount: String,
    val pendingCount: String,
    val android: String,
    val abi: String,
) {
    companion object {
        fun placeholder() = DebugSnapshot(
            version = "…",
            lsposed = "…",
            root = "…",
            configCount = "…",
            pendingCount = "…",
            android = "…",
            abi = "…",
        )
    }
}

private fun collectDebugSnapshot(context: Context, store: Store): DebugSnapshot {
    val version = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrDefault("?")

    val service = App.service()
    val lsposed = if (service == null) {
        context.getString(R.string.lsposed_not_connected)
    } else {
        runCatching {
            context.getString(
                R.string.lsposed_connected,
                service.frameworkName,
                service.frameworkVersion,
                service.apiVersion,
            )
        }.getOrDefault(context.getString(R.string.lsposed_connected_short))
    }

    // 只统计当前已安装应用中配置非零的：
    // store.snapshot() 包含已卸载应用的残留条目，直接 count 会导致
    // 主页显示"已启用 0"而诊断页显示"已启用 2"（数据源不同步）。
    val installedPkgs = runCatching {
        context.packageManager.getInstalledPackages(0)
            .mapNotNull { it.packageName }
            .toSet()
    }.getOrDefault(emptySet())
    val configs = runCatching {
        store.snapshot().count { it.value != 0 && it.key in installedPkgs }
    }.getOrDefault(0)
    // pendingStops 含已卸载/从未注入的残留；只算已安装的
    val pending = runCatching {
        store.pendingStops().count { it in installedPkgs }
    }.getOrDefault(0)

    return DebugSnapshot(
        version = version,
        lsposed = lsposed,
        root = describeRoot(context),
        configCount = configs.toString(),
        pendingCount = pending.toString(),
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?",
    )
}

/** 日志级别筛选。 */
private enum class LogLevel(val labelRes: Int, val marker: String?) {
    ALL(R.string.debug_level_all, null),
    INFO(R.string.debug_level_info, "I"),
    WARN(R.string.debug_level_warn, "W"),
    ERROR(R.string.debug_level_error, "E"),
}

/** logcat 线程时间格式行首带级别字母，按 " I " 之类匹配即可。 */
private fun filteredLogs(
    lines: List<String>,
    level: LogLevel,
    pkg: String,
): List<String> {
    if (level == LogLevel.ALL && pkg.isEmpty()) return lines
    return lines.filter { line ->
        // 新格式行首就是级别字母（"I 消息"），用前缀判断，避免误匹配正文。
        val levelOk = level.marker == null || line.startsWith(level.marker + " ")
        val pkgOk = pkg.isEmpty() || line.contains(pkg, ignoreCase = true)
        levelOk && pkgOk
    }
}

/** 一次 root 读取的结果：日志尾部 + 尚未解析的 HOOKSTAT 行。 */
private class LogBundle(
    val logs: List<String>,
    val truncated: Boolean,
    val hookStatLines: List<String>,
)

/** 日志段与 HOOKSTAT 段之间的分隔标记；用不可能出现在日志正文里的串。 */
private const val LOG_SEPARATOR = "@@MBPU-LOG-SPLIT@@"

/**
 * 一次 root 调用取回日志尾部与 HOOKSTAT 行。
 *
 * libxposed 的 log() 由 LSPosed 写入 /data/adb/lspd/log/modules_*.log，
 * 不会出现在 Android logcat 里，所以这里直接读最新的 modules 日志文件。
 * 两段数据同源，合并读取还能避免两次读之间日志轮转导致内容不一致。
 */
private fun readLogBundle(): LogBundle {
    val script = buildString {
        append("f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1); ")
        append("[ -n \"\$f\" ] || exit 3; ")
        append("grep -F 'com.sweetpotato.MBPU' \"\$f\" | tail -n ")
        append(LOG_TAIL_LINES * 4)
        append("; echo ").append(LOG_SEPARATOR).append("; ")
        append("grep -F 'HOOKSTAT|' \"\$f\" | ")
        append("awk -F'|' '{last[\$2]=\$0} END {for (k in last) print last[k]}'")
    }
    val result = RootShell.exec(script)
    if (result.exitCode == 3) throw IllegalStateException("no log file")
    if (!result.ok()) throw IllegalStateException(result.output)
    val split = result.output.indexOf(LOG_SEPARATOR)
    val logPart = if (split >= 0) result.output.substring(0, split) else result.output
    val statPart = if (split >= 0) result.output.substring(split + LOG_SEPARATOR.length) else ""
    val lines = logPart.lineSequence()
        .filter { it.isNotBlank() }
        .map { trimLogLine(it) }
        .toList()
    val truncated = lines.size > LOG_TAIL_LINES
    return LogBundle(
        logs = if (truncated) lines.takeLast(LOG_TAIL_LINES) else lines,
        truncated = truncated,
        hookStatLines = statPart.lineSequence().filter { it.isNotBlank() }.toList(),
    )
}

/**
 * LSPosed 行格式：
 * [ 2026-11-13T13:14:52.034  10403: 29772: 29772 I/LSPosedFramework ] (pkg)[模块信息] 消息
 * 只保留时间与消息，去掉中间冗长的模块标识。
 */
private fun trimLogLine(raw: String): String {
    val level = Regex("""([VDIWEF])/LSPosedFramework""").find(raw)?.groupValues?.get(1) ?: ""
    // 去掉 "[" 时间 pid tid 级别/标签 ]" 前缀，以及 "(pkg)[模块标识] " 段，
    // 只保留 "级别 包名 消息"，避免一行塞满模块哈希串。
    val message = raw
        .replace(
            Regex("""^\[\s*\S+\s+\d+:\s*\d+:\s*\d+\s+[VDIWEF]/LSPosedFramework\s*\]\s*"""),
            "",
        )
        .replace(Regex("""\(\S+?\)\[com\.sweetpotato\.MBPU,[^\]]*\]\s*"""), "")
        .trim()
    return listOf(level, message).filter { it.isNotEmpty() }.joinToString(" ")
        .ifEmpty { raw }
}

/**
 * 从日志解析 Hook 统计标记：HOOKSTAT|进程名|成功数|总数。
 * 同一包名有子进程时优先显示主进程，并在值里附加子进程信息。
 */
private fun parseHookStats(lines: List<String>): Map<String, HookStat> {
    val mainStats = LinkedHashMap<String, HookStat>()
    val childStats = LinkedHashMap<String, HookStat>()
    val re = Regex("""HOOKSTAT\|([^|]+)\|(\d+)\|(\d+)""")
    for (line in lines) {
        val m = re.find(line) ?: continue
        val process = m.groupValues[1].trim()
        val ok = m.groupValues[2].toIntOrNull() ?: continue
        val total = m.groupValues[3].toIntOrNull() ?: continue
        val stat = HookStat(ok = ok, total = total)
        if (process.contains(':')) childStats[process.substringBefore(':')] = stat
        else mainStats[process] = stat
    }
    val out = LinkedHashMap<String, HookStat>()
    for (base in (mainStats.keys + childStats.keys)) {
        val main = mainStats[base]
        val child = childStats[base]
        out[base] = when {
            main != null && child != null -> main.copy(child = child)
            main != null -> main
            else -> child!!
        }
    }
    return out
}

private fun readFullLog(): String {
    val script = buildString {
        append("f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1); ")
        append("[ -n \"\$f\" ] || exit 3; ")
        append("grep -F 'com.sweetpotato.MBPU' \"\$f\" | tail -n 4000")
    }
    val result = RootShell.exec(script)
    if (result.exitCode == 3) throw IllegalStateException("no log file")
    if (!result.ok()) throw IllegalStateException(result.output)
    return result.output
}

/** Hook 注入统计的展示文案：3/9；有子进程时附带子进程数字。 */
@Composable
private fun hookStatText(stat: HookStat): String {
    val main = "${stat.ok}/${stat.total}"
    val child = stat.child ?: return main
    return stringResource(R.string.hook_child_process, main, "${child.ok}/${child.total}")
}

/** 诊断信息行：标题 + 值。 */
@Composable
private fun DebugRow(title: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
            // 值可能是 LSPosed 版本串、SAF 路径、缓存统计这类没有断词点的长串，
            // 窄屏上会溢出卡片；允许两行后截断。
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** SAF tree 的显示名：内部存储 / 具体目录名。 */
private fun displayTreeName(context: Context, tree: String): String {
    val internalLabel = context.getString(R.string.storage_internal)
    return runCatching {
        val uri = Uri.parse(tree)
        val id = DocumentsContract.getTreeDocumentId(uri)
        val path = id.substringAfter(':', "")
        when {
            path.isNotBlank() -> path.substringAfterLast('/').ifBlank { path }
            id.startsWith("primary:") -> internalLabel
            else -> id.removeSuffix(":").ifBlank { tree }
        }
    }.getOrDefault(tree)
}

/**
 * 完整日志按当前筛选条件过滤。
 *
 * 完整日志是 LSPosed 原始格式：
 *   [ 时间 pid tid 级别/LSPosedFramework ] (pkg)[模块] 消息
 * 页面显示用的 `I 消息` 是 trimLogLine() 处理后的结果。直接对原文做 startsWith("I ")
 * 永远匹配不上——所以之前按级别复制/导出只会得到空文件。
 * 这里先逐行规范化，再交给 filteredLogs()。
 */
private fun filterLogText(text: String, level: LogLevel, pkg: String): String =
    filteredLogs(
        text.lineSequence()
            .filter { it.isNotBlank() }
            .map { trimLogLine(it) }
            .filter { it.isNotBlank() }
            .toList(),
        level,
        pkg,
    ).joinToString("\n")

/** 复制到剪贴板的诊断摘要。 */
private fun formatDebugSnapshot(snapshot: DebugSnapshot): String = buildString {
    appendLine("MBPU debug")
    appendLine("time=" + System.currentTimeMillis())
    appendLine("uptime=" + SystemClock.elapsedRealtime())
    appendLine("version=" + snapshot.version)
    appendLine("lsposed=" + snapshot.lsposed)
    appendLine("root=" + snapshot.root)
    appendLine("enabled_configs=" + snapshot.configCount)
    appendLine("pending_restart=" + snapshot.pendingCount)
    appendLine("android=" + snapshot.android)
    appendLine("abi=" + snapshot.abi)
    appendLine("locale=" + AppThemeState.locale?.toLanguageTag().orEmpty())
}

/**
 * 导出日志：配置了自定义 SAF 目录就写入该目录，否则回落到系统下载目录。
 * 两种情况都只保留最近 10 个导出文件。
 */
private fun exportLog(
    context: Context,
    tree: String,
    level: LogLevel = LogLevel.ALL,
    pkg: String = "",
): String {
    val text = filterLogText(readFullLog(), level, pkg)
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = "mbpu-log-$stamp.txt"

    if (tree.isNotEmpty()) {
        val treeUri = Uri.parse(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val doc = DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "text/plain",
            name,
        ) ?: throw IllegalStateException("create failed")
        context.contentResolver.openOutputStream(doc)?.use { out ->
            out.write(text.toByteArray())
        } ?: throw IllegalStateException("open failed")
        pruneCustomExports(context, tree)
        return "${displayTreeName(context, tree)}/$name"
    }

    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, name)
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
    }
    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("insert failed")
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(text.toByteArray())
    } ?: throw IllegalStateException("open failed")
    pruneDownloadExports(context)
    return name
}

/** 下载目录只保留最近 10 个导出文件；删除失败不影响导出结果。 */
private fun pruneDownloadExports(context: Context) {
    runCatching {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
        )
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("mbpu-log-%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            var index = 0
            while (cursor.moveToNext()) {
                if (index++ >= EXPORT_KEEP_COUNT) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol),
                    )
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }
        }
    }
}

/** 自定义 SAF 目录同样只保留最近 10 个。 */
private fun pruneCustomExports(context: Context, tree: String) {
    runCatching {
        val treeUri = Uri.parse(tree)
        val parent = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val docs = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            parent,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val docName = cursor.getString(nameCol) ?: continue
                if (docName.startsWith("mbpu-log-")) docs.add(docId to docName)
            }
        }
        docs.sortedByDescending { it.second }.drop(EXPORT_KEEP_COUNT).forEach { (id, _) ->
            runCatching {
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                )
            }
        }
    }
}
