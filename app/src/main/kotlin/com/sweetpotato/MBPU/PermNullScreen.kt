package com.sweetpotato.MBPU

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.libxposed.service.XposedService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.anim.AccelerateEasing
import top.yukonga.miuix.kmp.anim.DecelerateEasing
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Snackbar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SnackbarData
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarVisuals
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 分组标题（已开启 / 未开启）的水平缩进。
 *
 * 卡片外链 margin 12dp + BasicComponentDefaults.InsideMargin 16dp = 28dp，
 * 正好落在 CardDefaults.CornerRadius（16dp）的圆角内侧，与卡片里的图标、标题左缘齐平。
 * CardDefaults.InsideMargin 是 0，内边距全部由 BasicComponent 提供。
 */
private val SECTION_INSET = 28.dp

/**
 * 弹窗里的并排按钮统一高度。
 *
 * Miuix 的 Button / TextButton 共用同一实现（ButtonDefaults.MinHeight = 40dp、
 * InsideMargin = 16×13dp），高度差只来自文案换行：中文一行、葡语/德语/俄语两行。
 *
 * 56dp 是按两行 13sp 文案算出来的：两行约 38dp + 上下内边距 6×2 = 50dp，留 6dp 余量。
 * 48dp 时葡语「Não mostrar novamente」放不下，会被省略成「Não mostrar no...」。
 */
private val DIALOG_BUTTON_HEIGHT = 56.dp

/** 重启计划：区分「hook 尚未装载」和「hook 待卸载」两类目标 */
private data class RestartPlan(
    val pendingInstall: List<String>,
    val pendingUninstall: List<String>,
    /** LSPosed 服务不可用，无法判断是否已注入。 */
    val unknown: Boolean = false,
) {
    val total: Int get() = pendingInstall.size + pendingUninstall.size
}

/** 一个已安装应用的展示数据。图标不进数据类，避免一次性解码几百个位图。 */
internal data class AppEntry(
    val packageName: String,
    val isSystem: Boolean,
    /** 安装时间；应用更新后取更新的时间，视为「新安装」 */
    val installedAt: Long,
    /** 在 IO 加载阶段一次解析，搜索、排序和行绘制均复用此字段。 */
    val label: String,
    /** 最近一次配置变更时间，0 表示从未修改。 */
    val changedAt: Long = 0L,
)

/** 列表排序方式 */
private enum class SortMode(val labelRes: Int) {
    INSTALL_TIME(R.string.sort_install_time),
    ALPHABET(R.string.sort_alphabet),
}

/** 排序方向 */
private enum class SortOrder(val labelRes: Int) {
    ASC(R.string.sort_asc),
    DESC(R.string.sort_desc),
}

/** 批量操作类型 */
private enum class BatchAction { ENABLE, DISABLE }

/** 屏蔽状态文案，跟随语言 */
@Composable
private fun summaryText(mask: Int): String =
    if (mask == 0) {
        stringResource(R.string.state_unblocked)
    } else {
        stringResource(R.string.state_blocked, Config.count(mask))
    }

@Composable
internal fun PermNullScreen(
    store: Store,
    home: HomeState,
    listState: LazyListState,
    pullToRefreshState: PullToRefreshState,
    onRefresh: () -> Unit,
    onOpenSettings: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 自己控制提示条的显示与文本：Miuix 的 SnackbarHost 用滑动进出且位置固定在
    // Scaffold 槽位，无法改成淡入淡出，所以这里自行托管可见性与续期。
    var snackbarText by remember { mutableStateOf("") }
    var snackbarVisible by remember { mutableStateOf(false) }
    var snackbarTick by remember { mutableIntStateOf(0) }
    val scrollBehavior = MiuixScrollBehavior()
    // 与 Miuix Switch 内部一致的振动来源（Compose 官方 LocalHapticFeedback）
    val haptic = LocalHapticFeedback.current

    fun hapticToggle(on: Boolean) {
        runCatching {
            haptic.performHapticFeedback(
                if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
            )
        }
    }

    // 列表与状态来自 home：离开页面再回来不会被清空重载
    var allApps by home::allApps
    var masks by home::masks
    // 搜索词、多选状态用 rememberSaveable：系统改字体大小或语言时会重建 Activity，
    // 而这些字段不属于「可重新计算」的数据，丢了会让用户以为页面被重置。
    var queryInput by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(store.searchHistory()) }
    var historyVisible by remember { mutableStateOf(true) }
    var statusText by home::statusText
    var rootStatus by home::rootStatus
    var showSystemApps by remember { mutableStateOf(store.showSystemApps()) }
    var sortMode by remember {
        mutableStateOf(
            runCatching { SortMode.valueOf(store.sortMode()) }.getOrDefault(SortMode.INSTALL_TIME),
        )
    }
    var sortOrder by remember {
        mutableStateOf(
            runCatching { SortOrder.valueOf(store.sortOrder()) }.getOrDefault(SortOrder.DESC),
        )
    }

    // 编辑中的目标应用与草稿勾选；null 表示底部弹层关闭
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var draft by remember { mutableStateOf(emptySet<Int>()) }
    var editBaseline by remember { mutableIntStateOf(0) }

    // 一键重启的确认弹窗与执行状态
    var showRestartDialog by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var restartPlan by remember { mutableStateOf<RestartPlan?>(null) }
    var showMenu by remember { mutableStateOf(false) }
    // 每次进入应用弹一次使用说明；"不再提示"后永久关闭。
    // 首次进入自动弹说明；是否已弹过记录在 HomeState，跨页面槽位切换不丢。
    var showHelp by remember { mutableStateOf(!store.helpDisabled() && !home.helpShown) }
    LaunchedEffect(showHelp) { if (showHelp) home.helpShown = true }
    // 长按多选：选择模式只维护包名集合，不复制应用列表。
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable(
        stateSaver = listSaver(
            save = { it.toList() },
            restore = { it.toSet() },
        ),
    ) { mutableStateOf(emptySet<String>()) }
    // 批量操作先确认，再执行；执行中禁止关闭弹窗。
    var pendingBatch by remember { mutableStateOf<BatchAction?>(null) }
    var batchRunning by remember { mutableStateOf(false) }
    var askRestartAfterBatch by remember { mutableStateOf(false) }

    // 从设置页返回或 Activity 重建后重新对齐持久化选项，避免页面状态与存储不一致。
    LaunchedEffect(home.loaded, home.labelsLocale) {
        showSystemApps = store.showSystemApps()
        sortMode = runCatching { SortMode.valueOf(store.sortMode()) }
            .getOrDefault(SortMode.INSTALL_TIME)
        sortOrder = runCatching { SortOrder.valueOf(store.sortOrder()) }
            .getOrDefault(SortOrder.DESC)
    }

    // 多选时返回键先退出选择，而不是直接离开模块界面。
    BackHandler(enabled = selecting) {
        selecting = false
        selected = emptySet()
    }

    /** 全量重建：仅在批次操作 / 服务重连时使用。 */
    fun reloadMasks() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) { store.snapshot() }
            masks = snapshot
        }
    }

    /** 增量更新单个应用，避免开关一次就重建 589 条快照。 */
    fun updateMask(pkg: String, mask: Int) {
        masks = if (mask == 0) masks - pkg else masks + (pkg to mask)
    }

    fun notify(message: String) {
        // 只保留最新一条：文本替换 + tick 递增，LaunchedEffect 重新计时。
        snackbarText = message
        snackbarVisible = true
        snackbarTick++
    }

    /** 记录一次搜索；去重、最新优先，最多 10 条。 */
    fun rememberSearch(keyword: String) {
        val value = keyword.trim()
        if (value.length < 2) return
        store.addSearchHistory(value)
        history = store.searchHistory()
    }

    // 输入框即时响应，列表过滤延迟 150ms，避免逐字全量过滤。
    LaunchedEffect(queryInput) {
        delay(150)
        query = queryInput
    }

    // 停止输入 800ms 后记一条历史：不要求有结果，符合"搜过就记得"的直觉。
    LaunchedEffect(queryInput) {
        val keyword = queryInput.trim()
        if (keyword.length < 2) return@LaunchedEffect
        delay(800)
        if (keyword == queryInput.trim()) rememberSearch(keyword)
    }


    /**
     * 批量开启。
     *
     * 必须是 suspend：之前内部又 scope.launch 一层，confirmBatch 的 finally 会在真正
     * 批处理开始前就把 batchRunning 复位、关掉确认框，用户可以重复提交同一批次，
     * 而且内层异常外层根本捕获不到。
     */
    suspend fun runBatchEnable(targets: List<String>) {
        if (targets.isEmpty()) return
        var all = 0
        for (cat in Config.CATS) all = all or Config.bit(cat.id)
        val failed = withContext(Dispatchers.IO) {
            targets.filter { pkg ->
                runCatching { store.put(pkg, all) }.isFailure
            }
        }
        // 重新开启的目标不再属于「待卸载」，否则重启计划会无谓强停已启用的应用。
        withContext(Dispatchers.IO) {
            store.clearPendingStop(targets.toSet())
        }
        reloadMasks()
        val ok = targets.size - failed.size
        notify(context.getString(R.string.batch_result_enabled, ok, failed.size))
        if (ok > 0) {
            requestScope(context, targets - failed.toSet(), scope, ::notify)
        }
        selecting = false
        selected = emptySet()
    }

    /** 批量关闭：逐个清配置并登记待重启，统计失败的包。 */
    suspend fun runBatchDisable(targets: List<String>) {
        if (targets.isEmpty()) return
        val failed = withContext(Dispatchers.IO) {
            targets.filter { pkg ->
                runCatching {
                    store.put(pkg, 0)
                    store.markPendingStop(pkg)
                }.isFailure
            }
        }
        reloadMasks()
        selecting = false
        selected = emptySet()
        // 未被安装（或已卸载）的目标不会再有进程，标记没有意义，直接清掉。
        val installed = withContext(Dispatchers.IO) {
            targets.filter { pkg ->
                runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess
            }
        }
        val stale = targets - installed.toSet()
        if (stale.isNotEmpty()) store.clearPendingStop(stale.toSet())
        notify(
            context.getString(
                R.string.batch_result_disabled,
                targets.size - failed.size,
                failed.size,
            ),
        )
        // 只有确实存在“已注入 hook 且仍在运行”的目标时才提示重启；
        // 从未重启过的应用没注入 hook，不需要强停。
        if (targets.size - failed.size > 0) {
            askRestartAfterBatch = withContext(Dispatchers.IO) {
                runCatching { computeRestartPlan(store).total > 0 }.getOrDefault(true)
            }
        }
    }

    fun confirmBatch(action: BatchAction) {
        val targets = selected.toList()
        if (targets.isEmpty()) return
        batchRunning = true
        scope.launch {
            try {
                when (action) {
                    BatchAction.ENABLE -> runBatchEnable(targets)
                    BatchAction.DISABLE -> runBatchDisable(targets)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消必须继续往外抛，吞掉会破坏结构化并发
                throw e
            } catch (t: Throwable) {
                notify(context.getString(R.string.toast_operation_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                batchRunning = false
                pendingBatch = null
            }
        }
    }

    /** 严格使用确认弹窗展示过的同一批目标，不在确认后扩大强停范围。 */
    fun doRestart(plan: RestartPlan?) {
        val targets = (plan?.pendingInstall.orEmpty() + plan?.pendingUninstall.orEmpty()).toSet()
        if (targets.isEmpty()) {
            notify(context.getString(R.string.toast_no_targets))
            return
        }
        restarting = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val running = RootShell.runningOf(targets)
                    if (running.isEmpty()) {
                        Triple(emptySet<String>(), emptySet<String>(), "none-running")
                    } else {
                        val exec = RootShell.forceStop(running)
                        if (!exec.ok()) {
                            Triple(running, emptySet(), "failed:${exec.output}")
                        } else {
                            val still = RootShell.runningOf(running)
                            Triple(running, still, "ok")
                        }
                    }
                }
                val (requested, stillRunning, status) = result
                rootStatus = withContext(Dispatchers.IO) { describeRoot(context) }
                when {
                    status == "none-running" ->
                        notify(context.getString(R.string.toast_none_running))
                    status.startsWith("failed") ->
                        notify(context.getString(R.string.toast_restart_failed, status.removePrefix("failed:")))
                    stillRunning.isNotEmpty() ->
                        notify(
                            context.getString(
                                R.string.toast_restart_partial,
                                requested.size - stillRunning.size,
                                stillRunning.size,
                            ),
                        )
                    else -> {
                        store.clearPendingStop(requested)
                        notify(context.getString(R.string.toast_restart_done, requested.size))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                notify(context.getString(R.string.toast_restart_failed, t.message ?: t.javaClass.simpleName))
            } finally {
                restarting = false
                showRestartDialog = false
            }
        }
    }

    DisposableEffect(Unit) {
        store.setRemote(App.remotePrefs())
        val listener = object : App.Listener {
            override fun onServiceChanged() {
                scope.launch {
                    store.setRemote(App.remotePrefs())
                    statusText = withContext(Dispatchers.IO) { describeService(context) }
                    reloadMasks()
                }
            }
        }
        App.addListener(listener)
        onDispose { App.removeListener(listener) }
    }

    // 主页之外（AppRoot 的加载/刷新）产生的错误，统一在这里落到同一条提示条上。
    LaunchedEffect(home.toast) {
        val text = home.toast ?: return@LaunchedEffect
        notify(text)
        home.toast = null
    }

    // 提示条自动隐藏；tick 变化即重新计时，实现"新消息顶掉旧消息"。
    LaunchedEffect(snackbarTick) {
        if (!snackbarVisible) return@LaunchedEffect
        delay(3200)
        snackbarVisible = false
    }

    LaunchedEffect(Unit) {
        if (rootStatus.isEmpty()) rootStatus = context.getString(R.string.root_checking)
    }

    // root 检测只在首次进入时做一次。
    // 之前挂在 showRestartDialog 上，每次页面重组都会先写「检测中…」再写结果，
    // 表现为回到主页时 root 文案闪一下。
    LaunchedEffect(Unit) {
        if (rootStatus.isEmpty() || rootStatus == context.getString(R.string.root_checking)) {
            rootStatus = withContext(Dispatchers.IO) { describeRoot(context) }
        }
    }

    // 只有真正打开重启弹窗时才统计目标
    LaunchedEffect(showRestartDialog) {
        restartPlan = if (showRestartDialog) {
            try {
                withContext(Dispatchers.IO) { computeRestartPlan(store) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                notify(context.getString(R.string.toast_operation_failed, t.message ?: t.javaClass.simpleName))
                // 查询失败不是"没有需要重启的应用"，用 unknown 状态区别开。
                RestartPlan(emptyList(), emptyList(), unknown = true)
            }
        } else {
            null
        }
    }

    // 服务状态是同步 IPC，首次进入时在后台取一次。
    LaunchedEffect(Unit) {
        if (statusText.isEmpty()) {
            statusText = withContext(Dispatchers.IO) { describeService(context) }
        }
    }

    // 列表已由 AppRoot 在语言变化/首次进入时加载；这里只负责排序和分组。
    val shown = remember(allApps, masks, query, showSystemApps, sortMode, sortOrder, home.labelsLocale) {
        val keyword = query.trim()
        val filtered = allApps.filter { app ->
            val matchesSystem = showSystemApps || !app.isSystem
            val matchesQuery = keyword.isEmpty() ||
                app.label.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true)
            matchesSystem && matchesQuery
        }
        val sorted = when (sortMode) {
            // 安装时间：installedAt 已取 max(首次安装, 最后更新)，更新过就算新安装
            SortMode.INSTALL_TIME -> filtered.sortedBy { it.installedAt }
            SortMode.ALPHABET -> filtered.sortedBy { it.label.lowercase() }
        }.let { if (sortOrder == SortOrder.DESC) it.reversed() else it }
        // 已启用应用自动置顶；两组内部都完整沿用当前排序，取消启用后自动归位。
        val (enabled, disabled) = sorted.partition { (masks[it.packageName] ?: 0) != 0 }
        enabled + disabled
    }

    // remember 的 key 覆盖了 shown 与 masks 的变化：分组/计数在重组时不再重复 O(n) 遍历。
    val (enabledShown, disabledShown, enabledCount) = remember(shown, masks) {
        val enabled = shown.filter { (masks[it.packageName] ?: 0) != 0 }
        Triple(enabled, shown - enabled.toSet(), allApps.count { (masks[it.packageName] ?: 0) != 0 })
    }

    // 点击回顶后立即隐藏按钮，不等待滚动动画结束。
    var backToTopRunning by remember { mutableStateOf(false) }

    // 只有在筛选/排序/启用状态刚变化时才启用项位移动画；稳定状态下关闭，
    // 避免滚动时每帧计算弹簧（589 项时开销明显）。
    // key 用 masks 的 hash 而不是 size：关一个开一个时数量不变，用 size 会漏掉这类变化。
    var motionWindow by remember { mutableStateOf(false) }
    val maskFingerprint = remember(masks) { masks.hashCode() }
    LaunchedEffect(showSystemApps, sortMode, sortOrder, query, maskFingerprint) {
        motionWindow = true
        delay(1200)
        motionWindow = false
    }
    val animateItems = motionWindow

    // 注入状态：读 LSPosed 日志里的 HOOKSTAT 标记。
    // 每次读取都要 fork 一个 root shell，所以：
    //   - 只在界面 RESUMED（真正可见）时轮询；退到后台/息屏后协程随之取消，
    //     否则 Activity 存活期会一直每 30 秒拉起一次 su，纯耗电；
    //   - 回到前台先读一次，之后 3s/6s/12s 递增退避，最长 30s；
    //   - 下拉刷新或点「刷新模块状态」时 refreshTick++，重新从快速档开始。
    // 目标进程以自身 UID 运行，无法写模块私有目录，日志是唯一可靠通道。
    var refreshTick by remember { mutableIntStateOf(0) }
    val hostActivity = remember(context) { context.findHostActivity() }
    LaunchedEffect(refreshTick, hostActivity) {
        val owner = hostActivity ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var interval = 3_000L
            while (true) {
                val stats = withContext(Dispatchers.IO) {
                    runCatching { readHookStats() }.getOrDefault(emptyMap())
                }
                // 成功读到空结果也要提交：日志清空/轮转后不该永远保留旧绿点/红点。
            home.hookStats = stats
                delay(interval)
                interval = (interval * 2).coerceAtMost(30_000L)
            }
        }
    }

    // 相对时间的共享时钟：每分钟推进一次。
    // 之前每张可见卡片各挂一个无限协程（589 项就有 589 个 timer），
    // 后台也继续跑；现在收敛到一个，且只在 RESUMED 时推进。
    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hostActivity) {
        val owner = hostActivity ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                clockTick = System.currentTimeMillis()
                delay(60_000)
            }
        }
    }

    // 列表离开顶部时显示回顶按钮
    val showBackToTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }

    // 高级材质：backdrop 录制页面内容，供顶栏做渐变模糊
    val glassBackdrop = rememberGlassBackdrop()

    Scaffold(
        topBar = {
            // 用 SmallTopAppBar 而非 TopAppBar：后者的展开态大标题会在顶部留出一大片空白，
            // 这里只需要一行紧凑标题。
            GlassTopBar(backdrop = glassBackdrop) {
                SmallTopAppBar(
                title = if (selecting) {
                    stringResource(R.string.batch_selected_count, selected.size)
                } else {
                    stringResource(R.string.app_name)
                },
                color = Glass.barColor(),
                subtitle = when {
                    selecting -> stringResource(R.string.batch_select_hint)
                    home.loading -> stringResource(R.string.loading_apps)
                    query.isNotBlank() ->
                        stringResource(R.string.search_match_count, shown.size, allApps.size)
                    else -> stringResource(R.string.enabled_count, enabledCount, allApps.size)
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    // 选择模式下左上角变成取消；正常时是设置入口。
                    IconButton(
                        onClick = {
                            if (selecting) {
                                selecting = false
                                selected = emptySet()
                            } else {
                                onOpenSettings(false)
                            }
                        },
                        modifier = Modifier.glassButton(glassBackdrop),
                    ) {
                        Icon(
                            imageVector = if (selecting) MiuixIcons.Close else MiuixIcons.Settings,
                            tint = MiuixTheme.colorScheme.onBackground,
                            contentDescription = stringResource(
                                if (selecting) R.string.cancel else R.string.settings,
                            ),
                        )
                    }
                },
                actions = {
                    // 用 OverlayListPopup 而不是 OverlayIconDropdownMenu：后者的
                    // DropdownItem 没有分隔线参数，无法做分组。
                    Box {
                        IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.glassButton(glassBackdrop),
                    ) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                tint = MiuixTheme.colorScheme.onBackground,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        OverlayListPopup(
                            show = showMenu,
                            alignment = PopupPositionProvider.Align.TopEnd,
                            onDismissRequest = { showMenu = false },
                        ) {
                            ListPopupColumn {
                                if (selecting) {
                                    val count = 5
                                    DropdownImpl(
                                        text = stringResource(R.string.batch_select_enabled_group),
                                        optionSize = count,
                                        isSelected = false,
                                        index = 0,
                                        onSelectedIndexChange = {
                                            showMenu = false
                                            hapticToggle(true)
                                            selected = selected + enabledShown.map { it.packageName }
                                        },
                                    )
                                    DropdownImpl(
                                        text = stringResource(R.string.batch_select_disabled_group),
                                        optionSize = count,
                                        isSelected = false,
                                        index = 1,
                                        onSelectedIndexChange = {
                                            showMenu = false
                                            hapticToggle(true)
                                            selected = selected + disabledShown.map { it.packageName }
                                        },
                                    )
                                    DropdownImpl(
                                        text = stringResource(R.string.batch_clear_selection),
                                        optionSize = count,
                                        isSelected = false,
                                        index = 2,
                                        onSelectedIndexChange = {
                                            showMenu = false
                                            selected = emptySet()
                                        },
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                    DropdownImpl(
                                        text = stringResource(R.string.batch_enable_selected_n, selected.size),
                                        optionSize = count,
                                        isSelected = false,
                                        index = 3,
                                        onSelectedIndexChange = {
                                            showMenu = false
                                            pendingBatch = BatchAction.ENABLE
                                        },
                                    )
                                    DropdownImpl(
                                        text = stringResource(R.string.batch_disable_selected_n, selected.size),
                                        optionSize = count,
                                        isSelected = false,
                                        index = 4,
                                        onSelectedIndexChange = {
                                            showMenu = false
                                            pendingBatch = BatchAction.DISABLE
                                        },
                                    )
                                } else {
                                    // 选项总数影响首尾圆角，固定为 5。
                                    val count = 5

                                    DropdownImpl(
                                        text = stringResource(R.string.menu_show_system),
                                        optionSize = count,
                                        isSelected = showSystemApps,
                                        index = 0,
                                        onSelectedIndexChange = {
                                            hapticToggle(!showSystemApps)
                                            showSystemApps = !showSystemApps
                                            store.setShowSystemApps(showSystemApps)
                                        },
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )

                                    DropdownImpl(
                                        text = stringResource(R.string.sort_install_time),
                                        optionSize = count,
                                        isSelected = sortMode == SortMode.INSTALL_TIME,
                                        index = 1,
                                        onSelectedIndexChange = {
                                            if (sortMode != SortMode.INSTALL_TIME) hapticToggle(true)
                                            sortMode = SortMode.INSTALL_TIME
                                            store.setSortMode(sortMode.name)
                                        },
                                    )
                                    DropdownImpl(
                                        text = stringResource(R.string.sort_alphabet),
                                        optionSize = count,
                                        isSelected = sortMode == SortMode.ALPHABET,
                                        index = 2,
                                        onSelectedIndexChange = {
                                            if (sortMode != SortMode.ALPHABET) hapticToggle(true)
                                            sortMode = SortMode.ALPHABET
                                            store.setSortMode(sortMode.name)
                                        },
                                    )

                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )

                                    DropdownImpl(
                                        text = stringResource(R.string.sort_asc),
                                        optionSize = count,
                                        isSelected = sortOrder == SortOrder.ASC,
                                        index = 3,
                                        onSelectedIndexChange = {
                                            if (sortOrder != SortOrder.ASC) hapticToggle(true)
                                            sortOrder = SortOrder.ASC
                                            store.setSortOrder(sortOrder.name)
                                        },
                                    )
                                    DropdownImpl(
                                        text = stringResource(R.string.sort_desc),
                                        optionSize = count,
                                        isSelected = sortOrder == SortOrder.DESC,
                                        index = 4,
                                        onSelectedIndexChange = {
                                            if (sortOrder == SortOrder.DESC) return@DropdownImpl
                                            hapticToggle(true)
                                            sortOrder = SortOrder.DESC
                                            store.setSortOrder(sortOrder.name)
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
            }
        },
    ) { innerPadding ->
        // 外层不录制，只做叠加层宿主；内层才挂 layerBackdrop。
        // 回顶按钮必须在外层：它自己也要 textureBlur 读 backdrop，
        // 若放在录制层内部会自引用，导致 RenderNode 递归过深、native 栈溢出闪退。
        Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().asGlassSource(glassBackdrop)) {
            // 下拉刷新：顶部内边距交给 PullToRefresh 以偏移指示器，
            // AppBar 的滚动通过 topAppBarScrollBehavior 协调（内部已接 nestedScroll）。
            PullToRefresh(
                isRefreshing = home.refreshing,
                onRefresh = {
                    refreshTick++   // 下拉刷新同时刷新圆点状态
                    onRefresh()
                },
                pullToRefreshState = pullToRefreshState,
                contentPadding = PaddingValues(top = innerPadding.calculateTopPadding()),
                topAppBarScrollBehavior = scrollBehavior,
                // 默认文案是硬编码英文，这里换成中文字符串资源
                refreshTexts = listOf(
                    stringResource(R.string.pull_down),
                    stringResource(R.string.release_refresh),
                    stringResource(R.string.refreshing),
                    stringResource(R.string.refreshed),
                ),
            ) {
                LazyColumn(
                    modifier = Modifier
                        .overScrollVertical()
                        .fillMaxSize(),
                    state = listState,
                    // PullToRefresh 接管了 overscroll，这里必须置空避免冲突
                    overscrollEffect = null,
                    // 顶部内边距必须同时给 LazyColumn：PullToRefresh 的 contentPadding
                    // 只用来偏移刷新指示器，不会给内容加内边距，漏掉就会被顶栏盖住。
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding(),
                        bottom = innerPadding.calculateBottomPadding() + 12.dp,
                    ),
                ) {
                item(key = "search") {
                    InputField(
                        query = queryInput,
                        onQueryChange = {
                            queryInput = it
                            // 有输入内容时也让历史可见，方便直接替换关键词。
                            historyVisible = true
                        },
                        onSearch = {
                            queryInput = it
                            query = it
                            rememberSearch(it)
                            historyVisible = false
                        },
                        // expanded / onExpandedChange 是 InputField 的必填参数：
                        // 主页搜索不需要展开成全屏输入，固定折叠并空实现。
                        expanded = false,
                        onExpandedChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                        label = stringResource(R.string.search_hint),
                    )
                    AnimatedVisibility(
                        visible = history.isNotEmpty() && historyVisible,
                        enter = expandVertically(
                            animationSpec = folmeSpring(damping = 0.9f, response = 0.3f),
                        ) + fadeIn(animationSpec = tween(160, easing = SinOutEasing)),
                        exit = shrinkVertically(
                            animationSpec = folmeSpring(damping = 0.95f, response = 0.28f),
                        ) + fadeOut(animationSpec = tween(120, easing = AccelerateEasing())),
                    ) {
                        // 对齐小米系统设置：左标题 + 右侧清除，下方紧凑胶囊左对齐换行。
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.search_history),
                                    style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    text = stringResource(R.string.search_history_clear),
                                    onClick = {
                                        store.clearSearchHistory()
                                        history = emptyList()
                                        historyVisible = false
                                    },
                                    minWidth = 0.dp,
                                    // 无障碍要求可点击区域不小于 48dp；这里靠 insideMargin 把
                                    // 视觉高度压回接近文本行高，触摸区仍是 48dp。
                                    minHeight = 48.dp,
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    textStyle = MiuixTheme.textStyles.footnote1,
                                    colors = ButtonDefaults.textButtonColors(
                                        color = Color.Transparent,
                                        textColor = MiuixTheme.colorScheme.primary,
                                    ),
                                )
                            }
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                for (item in history) {
                                    // 官方组件 TextButton 不支持长按，这里用同款胶囊外观 +
                                    // combinedClickable 提供"点按复用 / 长按删除单条"。
                                    val removeLabel = stringResource(R.string.search_history_remove)
                                    Box(
                                        modifier = Modifier
                                            .heightIn(min = 40.dp)
                                            .clip(CircleShape)
                                            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                                            .combinedClickable(
                                                role = Role.Button,
                                                onLongClickLabel = removeLabel,
                                                onClick = {
                                                    queryInput = item
                                                    query = item
                                                    rememberSearch(item)
                                                    historyVisible = false
                                                },
                                                onLongClick = {
                                                    store.removeSearchHistory(item)
                                                    history = store.searchHistory()
                                                },
                                            )
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = item,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                                            // 历史词是用户粘贴的任意文本，必须单行截断，
                                            // 否则 FlowRow 里一条长词会撑成两行、胶囊高度参差。
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "status") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp)
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.status_lsposed),
                            summary = statusText,
                        )
                        HorizontalDivider()
                        BasicComponent(
                            title = stringResource(R.string.status_root),
                            summary = rootStatus,
                        )
                    }
                }

                item(key = "refresh") {
                    // 刷新本身是毫秒级操作，做按钮动画只会闪一下，反而不如不做：
                    // 这里保持按钮可用，只更新状态文本。
                    Button(
                        onClick = {
                            store.setRemote(App.remotePrefs())
                            scope.launch {
                                store.setRemote(App.remotePrefs())
                                statusText = withContext(Dispatchers.IO) { describeService(context) }
                                reloadMasks()
                                refreshTick++
                                rootStatus = withContext(Dispatchers.IO) { describeRoot(context) }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            // 与卡片统一 8dp 节奏：分组标题的上下留白靠这一项才能对称。
                            .padding(bottom = 8.dp),
                        // 主色按钮：Miuix 默认蓝底白字
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = stringResource(R.string.btn_refresh_module_status),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }

                item(key = "restart") {
                    // 非纯色按钮：用红色背景白字，和「危险操作」的语义对应。
                    Button(
                        onClick = { if (!restarting) showRestartDialog = true },
                        enabled = !restarting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = AppColors.danger,
                            contentColor = Color.White,
                            disabledColor = AppColors.danger.copy(alpha = 0.40f),
                            disabledContentColor = Color.White.copy(alpha = 0.80f),
                        ),
                    ) {
                        Text(
                            text = if (restarting) stringResource(R.string.btn_restarting) else stringResource(R.string.btn_restart_scope),
                            style = MiuixTheme.textStyles.button,
                        )
                    }
                }

                if (enabledShown.isNotEmpty()) {
                    item(key = "section_enabled") {
                        // 纯标题行，高度贴住文字。
                        // 不能用 IconButton / clickable：Compose 会给它们套 48dp 最小交互尺寸，
                        // 实测把行高从 25dp 顶到 51dp。pointerInput 只做手势检测，不参与测量。
                        //
                        // 水平对齐卡片「内边距起点」而不是卡片外缘：
                        //   卡片外边距 12dp + BasicComponentDefaults.InsideMargin 16dp = 28dp
                        // （CardDefaults.InsideMargin 是 0，内边距全在 BasicComponent 里，
                        //   圆角是 CardDefaults.CornerRadius = 16dp，所以 28dp 正好落在圆角内侧）。
                        val helpLabel = stringResource(R.string.help_title)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) { detectTapGestures { showHelp = true } }
                                .semantics {
                                    role = Role.Button
                                    onClick(label = helpLabel) {
                                        showHelp = true
                                        true
                                    }
                                }
                                .padding(start = SECTION_INSET, end = SECTION_INSET, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.section_enabled_summary,
                                    enabledShown.size,
                                    allApps.size,
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier.weight(1f),
                            )
                            // 装饰图标：说明入口语义已挂在整行的 onClick 上。
                            // 不再额外加 end padding——行本身的 end = SECTION_INSET，
                            // 已经把图标右缘收到与卡片内容右边界同一条线上。
                            Icon(
                                imageVector = MiuixIcons.Info,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(17.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                    // 折叠时不渲染：由 animateItem 的 fadeOut/placement 完成"向上收起"，
                    // 避免 589 个并发高度动画把主线程绑死。
                    items(
                        items = enabledShown,
                        key = { it.packageName },
                        contentType = { "card" },
                    ) { app ->
                        val mask = masks[app.packageName] ?: 0
                        AppListCard(
                            app = app,
                            // 只在刚发生筛选/排序/开关变更的 1.2s 窗口内启用项位移动画，
                            // 稳定态滚动时不再逐帧计算弹簧（589 项时开销明显）。
                            modifier = if (animateItems) {
                                Modifier.animateItem(
                                    fadeInSpec = tween(200, easing = SinOutEasing),
                                    placementSpec = folmeSpring(damping = 0.92f, response = 0.3f),
                                    fadeOutSpec = tween(170, easing = AccelerateEasing()),
                                )
                            } else {
                                Modifier
                            },
                            mask = mask,
                            changedAt = app.changedAt,
                            hookStat = home.hookStats[app.packageName],
                            selecting = selecting,
                            selected = app.packageName in selected,
                            onLongPress = {
                                hapticToggle(true)
                                selecting = true
                                selected = selected + app.packageName
                            },
                            onToggleSelected = {
                                selected = if (app.packageName in selected) {
                                    selected - app.packageName
                                } else {
                                    selected + app.packageName
                                }
                            },
                            onToggleEnabled = { enabled ->
                                if (enabled) {
                                    var all = 0
                                    for (cat in Config.CATS) all = all or Config.bit(cat.id)
                                    store.put(app.packageName, all)
                                    store.clearPendingStop(setOf(app.packageName))
                                    updateMask(app.packageName, all)
                                    requestScope(context, app.packageName, scope, ::notify)
                                } else {
                                    store.put(app.packageName, 0)
                                    store.markPendingStop(app.packageName)
                                    updateMask(app.packageName, 0)
                                    notify(context.getString(R.string.toast_closed_need_restart))
                                }
                            },
                            onOpen = {
                                if (query.isNotBlank()) rememberSearch(query)
                                draft = Config.CATS
                                    .filter { Config.has(mask, it.id) }
                                    .map { it.id }
                                    .toSet()
                                editBaseline = mask
                                editing = app
                            },
                            now = clockTick,
                        )
                    }
                }
                if (disabledShown.isNotEmpty()) {
                    item(key = "section_disabled") {
                        // 说明入口：无论有没有启用应用都要能点到。
                        // section_enabled 里的 ⓘ 在 0 启用时不渲染，会把入口藏没。
                        val helpLabel2 = stringResource(R.string.help_title)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) { detectTapGestures { showHelp = true } }
                                .semantics {
                                    role = Role.Button
                                    onClick(label = helpLabel2) {
                                        showHelp = true
                                        true
                                    }
                                }
                                .padding(
                                    start = SECTION_INSET,
                                    end = SECTION_INSET,
                                    bottom = 8.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.section_disabled_summary,
                                    disabledShown.size,
                                    allApps.size,
                                ),
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = MiuixIcons.Info,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackgroundVariant,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(17.dp)
                                    .clearAndSetSemantics {},
                            )
                        }
                    }
                    items(
                        items = disabledShown,
                        key = { it.packageName },
                        contentType = { "card" },
                    ) { app ->
                        val mask = masks[app.packageName] ?: 0
                        AppListCard(
                            app = app,
                            // 只在刚发生筛选/排序/开关变更的 1.2s 窗口内启用项位移动画，
                            // 稳定态滚动时不再逐帧计算弹簧（589 项时开销明显）。
                            modifier = if (animateItems) {
                                Modifier.animateItem(
                                    fadeInSpec = tween(200, easing = SinOutEasing),
                                    placementSpec = folmeSpring(damping = 0.92f, response = 0.3f),
                                    fadeOutSpec = tween(170, easing = AccelerateEasing()),
                                )
                            } else {
                                Modifier
                            },
                            mask = mask,
                            changedAt = app.changedAt,
                            hookStat = home.hookStats[app.packageName],
                            selecting = selecting,
                            selected = app.packageName in selected,
                            onLongPress = {
                                hapticToggle(true)
                                selecting = true
                                selected = selected + app.packageName
                            },
                            onToggleSelected = {
                                selected = if (app.packageName in selected) {
                                    selected - app.packageName
                                } else {
                                    selected + app.packageName
                                }
                            },
                            onToggleEnabled = { enabled ->
                                if (enabled) {
                                    var all = 0
                                    for (cat in Config.CATS) all = all or Config.bit(cat.id)
                                    store.put(app.packageName, all)
                                    store.clearPendingStop(setOf(app.packageName))
                                    updateMask(app.packageName, all)
                                    requestScope(context, app.packageName, scope, ::notify)
                                } else {
                                    store.put(app.packageName, 0)
                                    store.markPendingStop(app.packageName)
                                    updateMask(app.packageName, 0)
                                    notify(context.getString(R.string.toast_closed_need_restart))
                                }
                            },
                            onOpen = {
                                if (query.isNotBlank()) rememberSearch(query)
                                draft = Config.CATS
                                    .filter { Config.has(mask, it.id) }
                                    .map { it.id }
                                    .toSet()
                                editBaseline = mask
                                editing = app
                            },
                            now = clockTick,
                        )
                    }
                }
                // 加载中不显示"什么也没有"：allApps 还没读完就是空列表，
                // 这时显示最终空态会让用户以为真的没有应用。
                if (shown.isEmpty() && home.loaded && !home.loading) {
                    item(key = "empty_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (query.isBlank()) {
                                    stringResource(R.string.empty_apps)
                                } else {
                                    stringResource(R.string.empty_search)
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onBackgroundVariant,
                            )
                        }
                    }
                }
                    }
                }
            val target = editing
            OverlayBottomSheet(
                title = target?.label ?: "",
                show = target != null,
                startAction = {
                    IconButton(onClick = { editing = null }) {
                        Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
                endAction = {
                    IconButton(
                        onClick = {
                            val app = target ?: return@IconButton
                            val before = masks[app.packageName] ?: 0
                            if (before != editBaseline) {
                                editing = null
                                reloadMasks()
                                notify(context.getString(R.string.toast_config_conflict))
                                return@IconButton
                            }
                            var mask = 0
                            for (id in draft) mask = mask or Config.bit(id)
                            store.put(app.packageName, mask)
                            if (mask != 0) {
                                store.clearPendingStop(setOf(app.packageName))
                            }
                            if (before != 0 && mask == 0) {
                                store.markPendingStop(app.packageName)
                            }
                            updateMask(app.packageName, mask)
                            editing = null
                            if (mask != 0) {
                                requestScope(context, app.packageName, scope, ::notify)
                            } else if (before != 0) {
                                notify(context.getString(R.string.toast_closed_need_restart))
                            }
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = stringResource(R.string.save),
                            tint = MiuixTheme.colorScheme.onBackground,
                        )
                    }
                },
                onDismissRequest = { editing = null },
            ) {
                if (target != null) {
                    // 官方 example 的弹层内容用 LazyColumn，靠弹层自身高度约束滚动。
                    // 用 NoIndication 覆盖整棵子树：按压高亮是矩形，Card 内部 Column
                    // 不裁剪圆角，会露出方角白块。
                    CompositionLocalProvider(LocalIndication provides NoIndication) {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            item {
                                // 规则统一，只在顶部说明一次，避免每行重复既占屏又难扫读。
                                SmallTitle(
                                    text = stringResource(R.string.section_rule),
                                    insideMargin = PaddingValues(16.dp, 8.dp),
                                )
                                Card(modifier = Modifier.padding(bottom = 16.dp)) {
                                    val allSelected = draft.size == Config.CATS.size
                                    CheckboxPreference(
                                        title = stringResource(R.string.check_all),
                                        summary = stringResource(R.string.selected_count, draft.size, Config.CATS.size),
                                        checked = allSelected,
                                        onCheckedChange = { checked ->
                                            hapticToggle(checked)
                                            draft = if (checked) {
                                                Config.CATS.map { it.id }.toSet()
                                            } else {
                                                emptySet()
                                            }
                                        },
                                    )
                                    HorizontalDivider()
                                    for (cat in Config.CATS) {
                                        CheckboxPreference(
                                            title = stringResource(cat.nameRes),
                                            checked = draft.contains(cat.id),
                                            onCheckedChange = { checked ->
                                                hapticToggle(checked)
                                                draft = if (checked) {
                                                    draft + cat.id
                                                } else {
                                                    draft - cat.id
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }

            // 回到顶部：Miuix FloatingActionButton + 官方 ArrowRight 旋转成向上箭头。
            AnimatedVisibility(
                visible = showBackToTop && !selecting && !backToTopRunning,
                enter = fadeIn(animationSpec = tween(240, easing = SinOutEasing)) +
                    scaleIn(
                        initialScale = 0.72f,
                        animationSpec = folmeSpring(damping = 0.72f, response = 0.42f),
                    ),
                exit = fadeOut(animationSpec = tween(180, easing = DecelerateEasing())) +
                    scaleOut(
                        targetScale = 0.86f,
                        animationSpec = folmeSpring(damping = 0.9f, response = 0.32f),
                    ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    // 84dp 是给旧版 FAB 预留的位置，现在下方没有悬浮元素，
                    // 只需要和提示条留出呼吸间距。
                    .padding(end = 20.dp, bottom = 24.dp),
            ) {
                // 不用 FloatingActionButton：它内部 Surface 走 squircle（8 段贝塞尔），
                // 满圆角时轮廓会变成八边形。这里直接用官方 textureBlur + CircleShape。
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .glassButton(
                            backdrop = glassBackdrop,
                            tint = MiuixTheme.colorScheme.primary,
                            // 0.30 的白箭头压在淡蓝玻璃上，浅色主题对比度只有 1.2:1；
                            // 提到 0.72 让底色足够饱和，白箭头在深浅主题都能看清。
                            tintAlpha = 0.72f,
                        )
                        .clickable(
                            role = Role.Button,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            backToTopRunning = true
                            scope.launch {
                                listState.animateScrollToItem(0)
                                backToTopRunning = false
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // ArrowRight 的 viewport 是 10x16 非正方形，旋转 90° 后视觉重心偏移；
                    // ChevronForward 是 1186.8x1186.8 正方形，旋转后严格居中。
                    Icon(
                        imageVector = MiuixIcons.ChevronForward,
                        contentDescription = stringResource(R.string.back_to_top),
                        // 白箭头：蓝底（玻璃态或纯色态）上都清晰。
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .rotate(-90f),
                    )
                }
            }

            // 提示条：自己托管，用淡入淡出替代 Miuix SnackbarHost 的滑动进出。
            // 放在内容层顶部对齐底部并避开系统导航区，不会被手势条压住。
            AnimatedVisibility(
                visible = snackbarVisible,
                enter = fadeIn(animationSpec = tween(durationMillis = 420)),
                exit = fadeOut(animationSpec = tween(durationMillis = 520)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                // 用 Miuix 默认配色（反色深底浅字），保证对比度。
                Snackbar(
                    data = remember(snackbarText) {
                        object : SnackbarData {
                            override val visuals = SnackbarVisuals(
                                message = snackbarText,
                                actionLabel = null,
                                withDismissAction = false,
                                duration = SnackbarDuration.Short,
                            )

                            override suspend fun dismiss() = Unit

                            override suspend fun performAction() = Unit
                        }
                    },
                )
            }

            // 说明弹窗：圆点含义 + 文件类屏蔽的风险提示。
            // OverlayDialog 内部把遮罩的 enter/exit 都设成 None，导致点空白/按钮时
            // 遮罩瞬间消失、内容还在播动画，观感就是"一下子掉下去"。
            // 这里在它外层再套一层 DialogLayout，让遮罩跟随内容做淡入淡出。
            SmoothDialog(show = showHelp) {
                OverlayDialog(
                show = showHelp,
                title = stringResource(R.string.help_title),
                onDismissRequest = { showHelp = false },
            ) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = stringResource(R.string.help_dots_title),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    HelpDotLine(
                        color = AppColors.dotOk,
                        text = stringResource(R.string.help_dot_green),
                    )
                    HelpDotLine(
                        color = AppColors.dotPartial,
                        text = stringResource(R.string.help_dot_red),
                    )
                    HelpDotLine(
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        text = stringResource(R.string.help_dot_gray),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.help_files_title),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.help_files_body),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.help_feedback_title),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 反馈入口指向仓库 issues 页，不再暴露个人联系方式。
                        Text(
                            text = "GitHub Issues",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { openIssuePage(context) }
                                .padding(vertical = 2.dp),
                        )
                        Text(
                            text = stringResource(R.string.help_feedback_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    // 两个按钮必须完全等高等宽。
                    // weight(1f) 只保证宽度；高度原本随文案换行数变化——中文「不再提示」
                    // 一行就够，德语 "Nicht mehr anzeigen"、俄语 "Больше не показывать"
                    // 会折成两行，于是两个按钮一个高一个矮。
                    // 这里直接给两边同一个固定高度（比 ButtonDefaults.MinHeight 40dp 略高，
                    // 容得下两行），任何语言下都严格等高，不依赖 intrinsic 测量。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 左：不再提示（红底白字），与右侧"知道了"对称等宽。
                        Button(
                            onClick = {
                                store.setHelpDisabled(true)
                                showHelp = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(DIALOG_BUTTON_HEIGHT),
                            // 默认 InsideMargin 是 16×13dp，13dp 的垂直内边距会把两行文案
                            // 顶出固定高度；压到 6dp 后 56dp 里可以完整容纳两行。
                            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                color = AppColors.danger,
                                contentColor = Color.White,
                            ),
                        ) {
                            Text(
                                text = stringResource(R.string.help_never_show),
                                style = MiuixTheme.textStyles.button,
                                textAlign = TextAlign.Center,
                                // 给到 3 行：两行是常态（葡语/德语/俄语），
                                // 藏语/维吾尔语这类更长的还有一行余量。
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            text = stringResource(R.string.help_got_it),
                            onClick = { showHelp = false },
                            modifier = Modifier
                                .weight(1f)
                                .height(DIALOG_BUTTON_HEIGHT),
                            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                }
                }
            }

            // 批量关闭后的重启提示：需要用户确认才进入重启确认弹窗。
            SmoothDialog(show = askRestartAfterBatch) {
            OverlayDialog(
                show = askRestartAfterBatch,
                title = stringResource(R.string.batch_restart_title),
                summary = stringResource(R.string.batch_restart_summary),
                onDismissRequest = { askRestartAfterBatch = false },
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { askRestartAfterBatch = false },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = stringResource(R.string.confirm_restart),
                        onClick = {
                            askRestartAfterBatch = false
                            showRestartDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            }

            // 批量操作确认：明确目标数量，避免误触直接关闭应用
            SmoothDialog(show = pendingBatch != null) {
            OverlayDialog(
                show = pendingBatch != null,
                title = when (pendingBatch) {
                    BatchAction.ENABLE -> stringResource(R.string.batch_confirm_enable_title)
                    BatchAction.DISABLE -> stringResource(R.string.batch_confirm_disable_title)
                    null -> ""
                },
                summary = when (pendingBatch) {
                    BatchAction.ENABLE -> stringResource(
                        R.string.batch_confirm_enable_summary,
                        selected.size,
                    )
                    BatchAction.DISABLE -> stringResource(
                        R.string.batch_confirm_disable_summary,
                        selected.size,
                    )
                    null -> ""
                },
                onDismissRequest = { if (!batchRunning) pendingBatch = null },
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = { pendingBatch = null },
                        enabled = !batchRunning,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(
                        text = if (batchRunning) {
                            stringResource(R.string.btn_restarting)
                        } else {
                            stringResource(R.string.confirm)
                        },
                        onClick = { pendingBatch?.let { confirmBatch(it) } },
                        enabled = !batchRunning && selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            }

            // 一键重启前的确认
            SmoothDialog(show = showRestartDialog) {
            OverlayDialog(
                show = showRestartDialog,
                title = stringResource(R.string.restart_dialog_title),
                summary = run {
                    val plan = restartPlan
                    when {
                        plan == null -> stringResource(R.string.restart_plan_computing)
                        plan.unknown -> stringResource(R.string.restart_plan_unknown)
                        plan.total == 0 -> stringResource(R.string.restart_nothing_running)
                        else -> stringResource(R.string.restart_will_stop, plan.total)
                    }
                },
                onDismissRequest = { if (!restarting) showRestartDialog = false },
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    val plan = restartPlan
                    val labelOf = remember(allApps) {
                        allApps.associate { it.packageName to it.label }
                    }
                    if (plan != null) {
                        if (plan.pendingInstall.isNotEmpty()) {
                            RestartGroup(
                                title = stringResource(
                                    R.string.pending_install_title,
                                    plan.pendingInstall.size,
                                ),
                                desc = stringResource(R.string.pending_install_desc),
                                names = plan.pendingInstall.map { labelOf[it] ?: it },
                            )
                        }
                        if (plan.pendingUninstall.isNotEmpty()) {
                            RestartGroup(
                                title = stringResource(
                                    R.string.pending_uninstall_title,
                                    plan.pendingUninstall.size,
                                ),
                                desc = stringResource(R.string.pending_uninstall_desc),
                                names = plan.pendingUninstall.map { labelOf[it] ?: it },
                            )
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showRestartDialog = false },
                            enabled = !restarting,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(12.dp))
                        TextButton(
                            text = if (restarting) {
                                stringResource(R.string.btn_restarting)
                            } else {
                                stringResource(R.string.confirm_restart)
                            },
                            onClick = { doRestart(plan) },
                            enabled = !restarting && (plan?.total ?: 0) > 0,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            }
        }
    }
}

/** 重启弹窗里的一组目标：标题 + 说明 + 可滚动的应用名列表 */
@Composable
private fun RestartGroup(title: String, desc: String, names: List<String>) {
    Column(modifier = Modifier.padding(bottom = 12.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
        )
        Text(
            text = desc,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
        )
        // 名字多的时候限高滚动，避免弹窗被撑爆。
        // 96dp 在 footnote1 下只放得下 4 行，应用名换行就更少；
        // 放到 160dp 并把每条限制成单行截断，保证「待重启目标」这个核心信息可读。
        LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
            // 不能用应用名当 key：两个同名应用会让 LazyColumn 抛 key 重复异常。
            items(names) { name ->
                Text(
                    text = "· " + name,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * 相对时间文案：刚刚 / N 分钟前 / N 小时前 / N 天前；超过 30 天返回 null（不再展示）。
 *
 * 之前这里是硬编码中文，而 [R.string.changed_ago] 的英文模板是 "· changed %1$s ago"，
 * 英文界面会渲染成「· changed 3 分钟前 ago」——文案和语序两头都错。
 * 现在单位词全部走资源，英文模板也同步去掉了尾部 ago。
 */
@Composable
private fun relativeTime(then: Long, now: Long): String? {
    if (then <= 0L) return null
    val diff = (now - then).coerceAtLeast(0L)
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> stringResource(R.string.relative_just_now)
        diff < hour -> stringResource(R.string.relative_minutes_ago, (diff / minute).toInt())
        diff < day -> stringResource(R.string.relative_hours_ago, (diff / hour).toInt())
        diff < 30 * day -> stringResource(R.string.relative_days_ago, (diff / day).toInt())
        else -> null
    }
}

/** 应用卡片：普通点击打开详情，长按进入多选；选择模式点击只切换选中。 */
@Composable
private fun AppListCard(
    app: AppEntry,
    modifier: Modifier = Modifier,
    mask: Int,
    changedAt: Long = 0L,
    /** 注入统计；null 表示尚未注入（进程没启动过）。 */
    hookStat: HookStat? = null,
    selecting: Boolean,
    selected: Boolean,
    onLongPress: () -> Unit,
    onToggleSelected: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpen: () -> Unit,
    /** 相对时间的当前时间戳；由 Screen 层统一推进，避免每张卡片一个计时协程。 */
    now: Long,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(CardDefaults.CornerRadius)),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = { if (selecting) onToggleSelected() else onOpen() },
        onLongPress = onLongPress,
    ) {
        BasicComponent(
            startAction = {
                AppIcon(
                    packageName = app.packageName,
                    installedAt = app.installedAt,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(40.dp),
                )
            },
            endActions = {
                if (selecting) {
                    // 与详情弹层一致：Miuix 官方圆形 Checkbox（蓝底白勾 / 灰色未选）
                    // Miuix Checkbox 自带宽 role 与 toggleable 状态，
                    // 这里只补应用名标签；不要再写 role，否则读屏会念「微信，微信，复选框」。
                    Checkbox(
                        state = if (selected) ToggleableState.On else ToggleableState.Off,
                        onClick = onToggleSelected,
                        modifier = Modifier.semantics { contentDescription = app.label },
                    )
                } else {
                    // 开关是独立焦点节点：读屏落上去只会念「开关，关」，
                    // 补上应用名才知道它在控制哪个应用。
                    Switch(
                        checked = mask != 0,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics { contentDescription = app.label },
                    )
                }
            },
        ) {
            Text(
                text = app.label,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = app.packageName,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 最近一次配置变更时间，常驻在包名行右侧。
                val relative = relativeTime(changedAt, now)
                if (relative != null && !selecting) {
                    Text(
                        text = stringResource(R.string.changed_ago, relative),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summaryText(mask),
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (mask == 0) {
                        MiuixTheme.colorScheme.onBackgroundVariant
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                    // 没有 weight 时它会在 Row 里独占宽度，把后面 weight(1f) 的
                    // 操作提示压到 0 宽（fontScale 2.0 时必现）。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (mask != 0) {
                    // 状态点：绿 = 主/子进程全部注入成功；红 = 有类别注入失败；
                    // 灰 = 进程还没启动过（不算异常）。
                    // 颜色只作辅助：异常时右侧同时给出数字，不用只靠红绿区分。
                    val stat = hookStat
                    val (dotColor, dotDesc) = when {
                        stat == null -> MiuixTheme.colorScheme.onBackgroundVariant to
                            stringResource(R.string.hook_state_none)
                        !stat.complete -> AppColors.dotPartial to
                            stringResource(R.string.hook_state_partial)
                        else -> AppColors.dotOk to stringResource(R.string.hook_state_ok)
                    }
                    Box(
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .size(6.dp)
                            .background(dotColor, CircleShape)
                            .clearAndSetSemantics { contentDescription = dotDesc },
                    )
                    if (stat != null && !stat.complete) {
                        Text(
                            text = stringResource(R.string.hook_ratio, stat.ok, stat.total),
                            style = MiuixTheme.textStyles.footnote2,
                            color = AppColors.dotPartial,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Text(
                    text = if (selecting) {
                        stringResource(R.string.batch_tap_to_select)
                    } else {
                        stringResource(R.string.hint_tap_card)
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * 只读应用名、包名和是否系统应用；图标由 [AppIcon] 在可见行内按需解码。
 *
 * 首屏优化：分块并行解析应用名（磁盘缓存命中时不访问 PackageManager 资源），
 * 完成后统一落盘。并行度固定为 4，避免低端机线程抖动。
 */
internal suspend fun loadInstalledApps(context: Context): List<AppEntry> = coroutineScope {
    val pm = context.packageManager
    // 用 getInstalledPackages 而不是 getInstalledApplications：只有 PackageInfo
    // 带 firstInstallTime / lastUpdateTime，排序需要这个时间戳。
    val packages = runCatching {
        pm.getInstalledPackages(0)
    }.getOrThrow()

    val entries = packages.chunked(48).map { chunk ->
        async(Dispatchers.IO) {
            chunk.mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                // 模块自己不在列表里出现：它永远不会被 hook 注入，
                // 允许开关只会产生永远消不掉的"待卸载"条目。
                if (info.packageName == Config.MODULE_PKG) return@mapNotNull null
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val installedAt = maxOf(pkg.firstInstallTime, pkg.lastUpdateTime)
                AppEntry(
                    packageName = info.packageName,
                    isSystem = isSystem,
                    installedAt = installedAt,
                    label = AppLabels.label(context, info.packageName, installedAt)
                        .ifEmpty { info.packageName },
                    changedAt = 0L,
                )
            }
        }
    }.awaitAll().flatten()

    AppLabels.flush(context)
    entries
}

/** 描述当前 LSPosed 服务连接状态。 */
private fun describeService(context: Context): String {
    val service = App.service() ?: return context.getString(R.string.lsposed_not_connected)
    return runCatching {
        context.getString(
            R.string.lsposed_connected,
            service.frameworkName,
            service.frameworkVersion,
            service.apiVersion,
        )
    }.getOrDefault(context.getString(R.string.lsposed_connected_short))
}

/** 检测本应用是否已获得 root。调用会阻塞，必须在 IO 线程执行。 */
/** root 检测结果缓存 10 秒：避免诊断页每 2 秒都 fork 一次 su。 */
private var rootCache: Pair<Long, String>? = null

internal fun describeRoot(context: Context): String {
    rootCache?.let { (at, value) ->
        if (SystemClock.elapsedRealtime() - at < 10_000) return value
    }
    return describeRootUncached(context).also {
        rootCache = SystemClock.elapsedRealtime() to it
    }
}

private fun describeRootUncached(context: Context): String {
    val result = RootShell.exec("id -u")
    return when {
        result.output.trim() == "0" -> context.getString(R.string.root_granted)
        result.exitCode == 0 ->
            context.getString(R.string.root_denied_code, result.output.trim())
        else -> context.getString(R.string.root_denied)
    }
}

/**
 * 统计重启会影响到哪些应用，分成两类：
 * <ul>
 *   <li>待安装：已启用、进程在跑，但 LSPosed 的 runningTargets 里没有它 —— hook 还没装载</li>
 *   <li>待卸载：已关闭总开关，进程还在跑 —— hook 还挂在这个进程里</li>
 * </ul>
 * 只统计正在运行的进程，没在跑的应用下次启动自然会应用配置，无需重启。
 * 阻塞调用，需在 IO 线程执行。
 */
private fun computeRestartPlan(store: Store): RestartPlan {
    // 双保险：模块自己永远不在重启目标里。
    val enabled = store.snapshot().filterKeys { it != Config.MODULE_PKG }
        .filterValues { it != 0 }.keys
    val pendingStop = store.pendingStops() - Config.MODULE_PKG
    val targets = enabled + pendingStop
    if (targets.isEmpty()) return RestartPlan(emptyList(), emptyList())

    val running = RootShell.runningOf(targets)
    if (running.isEmpty()) {
        // 没有进程在跑：这些标记下次启动自然生效，不必强停，顺手清掉。
        if (pendingStop.isNotEmpty()) store.clearPendingStop(pendingStop)
        return RestartPlan(emptyList(), emptyList())
    }

    // 已装载 hook 的进程以 LSPosed runningTargets 为准；进程名可能是 pkg:sub。
    val service = App.service()
    val hooked: Set<String>? = runCatching {
        service?.runningTargets
            ?.mapNotNull { it.processName.substringBefore(':') }
            ?.toSet()
    }.getOrNull()

    // 服务不可用时不猜测：既不列出待卸载（避免把历史标记全算成"要重启"），
    // 也不清理标记（避免信息丢失）。提示里会说明无法判断。
    if (service == null || hooked == null) {
        return RestartPlan(emptyList(), emptyList(), unknown = true)
    }

    val toInstall = running.filter { it in enabled && it !in hooked }

    // 只有真正注入过 hook 且仍在运行的进程才需要重启来卸载。
    // 从未注入的目标直接清标记，不强制停止。
    val toUninstall = running.filter {
        it in pendingStop && it in hooked && it !in enabled
    }
    val cleanup = pendingStop - hooked
    if (cleanup.isNotEmpty()) store.clearPendingStop(cleanup)
    return RestartPlan(toInstall.sorted(), toUninstall.sorted())
}

/** 让 LSPosed 弹出作用域授权通知。 */
private fun requestScope(
    context: Context,
    packageName: String,
    scope: CoroutineScope,
    notify: (String) -> Unit,
) = requestScope(context, listOf(packageName), scope, notify)

private fun requestScope(
    context: Context,
    packageNames: Collection<String>,
    scope: CoroutineScope,
    notify: (String) -> Unit,
) {
    if (packageNames.isEmpty()) return
    scope.launch {
        val service = App.service()
        if (service == null) {
            notify(context.getString(R.string.toast_scope_no_service))
            return@launch
        }
        val currentScope = withContext(Dispatchers.IO) {
            runCatching { service.scope.orEmpty().toSet() }.getOrDefault(emptySet())
        }
        val pending = packageNames.filterNot { it in currentScope }
        if (pending.isEmpty()) {
            notify(context.getString(R.string.toast_scope_already))
            return@launch
        }
        withContext(Dispatchers.IO) {
            runCatching {
                service.requestScope(
                    pending,
                    object : XposedService.OnScopeEventListener {
                        override fun onScopeRequestApproved(approved: List<String>) {
                            scope.launch {
                                val approvedCount = pending.count { it in approved }
                                if (approvedCount > 0) {
                                    notify(context.getString(R.string.toast_scope_added))
                                } else {
                                    notify(context.getString(R.string.toast_scope_failed, "not approved"))
                                }
                            }
                        }

                        override fun onScopeRequestFailed(message: String) {
                            scope.launch { notify(context.getString(R.string.toast_scope_failed, message)) }
                        }
                    },
                )
            }.onSuccess {
                withContext(Dispatchers.Main) { notify(context.getString(R.string.toast_scope_requested)) }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    notify(context.getString(R.string.toast_scope_failed, it.toString()))
                }
            }
        }
    }
}

/** 从 LSPosed 模块日志读取 HOOKSTAT 标记，返回 包名 -> 注入统计。 */
private fun readHookStats(): Map<String, HookStat> {
    // 在 root 侧用 awk 完成"按进程名保留最后一条"，只回传最终结果。
    // 好处：1) 不受日志长度影响，旧记录被挤出窗口也能命中；
    //      2) 回传量从数百行降到一个包一行，减少 Binder/管道开销。
    val script = buildString {
        append("f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1); ")
        append("[ -n \"\$f\" ] || exit 3; ")
        append("grep -F 'HOOKSTAT|' \"\$f\" | ")
        append("sed 's/.*HOOKSTAT|/HOOKSTAT|/' | ")
        append("awk -F'|' '{last[$2]=$0} END {for (k in last) print last[k]}'")
    }
    val result = RootShell.exec(script)
    if (!result.ok()) return emptyMap()
    // 主进程与子进程分开记录：同一个包可能同时有 pkg 和 pkg:remote 两份注入。
    val mainStats = LinkedHashMap<String, HookStat>()
    val childStats = LinkedHashMap<String, HookStat>()
    val re = Regex("""HOOKSTAT\|([^|]+)\|(\d+)\|(\d+)""")
    for (line in result.output.lineSequence()) {
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

/** 说明弹窗里的一行：彩色圆点 + 文字。 */
@Composable
private fun HelpDotLine(color: Color, text: String) {
    Row(
        modifier = Modifier.padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/** 问题反馈地址；点击后交给系统浏览器 / GitHub 客户端打开。 */
private const val ISSUE_URL = "https://github.com/a285865818-crypto/miui-blank-pass-ultra/issues"

/**
 * 打开 issue 页面。
 * 没有可处理 ACTION_VIEW 的应用时静默忽略——反馈入口不是主功能，
 * 没必要为此弹一个错误提示。
 */
private fun openIssuePage(context: Context) {
    try {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(ISSUE_URL),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (t: Throwable) {
        android.util.Log.w("MBPU", "no activity for issue page: " + t.message)
    }
}

/**
 * 给 Miuix 弹窗补一层带淡入淡出的遮罩。
 * OverlayDialog 内部固定用 EnterTransition.None / ExitTransition.None 渲染遮罩，
 * 关闭时遮罩瞬间消失、内容还在播退出动画，观感是"硬掉下去"。
 * 这里只接管遮罩层动画，弹窗内容与布局完全不受影响。
 */
@Composable
private fun SmoothDialog(
    show: Boolean,
    content: @Composable () -> Unit,
) {
    // visible 必须是稳定的 MutableState：DialogLayout 内部按引用监听它的变化来驱动
    // 淡入淡出，若每次组合都传新实例，退场动画会被打断。这里 remember 一次后只改值。
    val visibleState = remember { mutableStateOf(false) }
    // SideEffect 每次重组都会执行一遍赋值，属于用副作用驱动动画状态；
    // 改成按 show 变化触发，避免与 DialogLayout 自身的退场动画抢时序。
    LaunchedEffect(show) { visibleState.value = show }
    top.yukonga.miuix.kmp.utils.MiuixPopupUtils.DialogLayout(
        visible = visibleState,
        enterTransition = null,
        exitTransition = null,
        enableWindowDim = true,
        enableAutoLargeScreen = false,
        dimEnterTransition = fadeIn(animationSpec = tween(200)),
        dimExitTransition = fadeOut(animationSpec = tween(220)),
    ) {
        content()
    }
}
