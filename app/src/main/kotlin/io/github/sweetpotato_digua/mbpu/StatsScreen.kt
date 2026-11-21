package io.github.sweetpotato_digua.mbpu

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.NumberFormat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val STATS_LOG_TAIL_BYTES = 2 * 1024 * 1024
private const val STATS_LOG_TAIL_LINES = 20_000
private const val STATS_ROOT_TIMEOUT_SECONDS = 15L
private const val STATS_RANKING_LIMIT = 20

/**
 * 当前模块日志中的 API 调用次数。进入诊断页及手动刷新时各读取一次，不轮询。
 * 这里只是诊断页内的一个可折叠分组，不另建 Theme 或 Scaffold。
 */
@Composable
fun BlockStatsSection() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var refreshVersion by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<BlockStatsUiState>(BlockStatsUiState.Loading) }

    LaunchedEffect(refreshVersion) {
        state = BlockStatsUiState.Loading
        state = withContext(Dispatchers.IO) {
            runCatching { loadBlockStats(context) }.getOrElse { BlockStatsUiState.Failure }
        }
    }

    SmallTitle(
        text = stringResource(R.string.stats_title),
        insideMargin = PaddingValues(16.dp, 8.dp),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onClick = { expanded = !expanded },
    ) {
        BasicComponent(
            title = stringResource(R.string.stats_title),
            summary = stringResource(R.string.stats_disclaimer),
            endActions = {
                Icon(
                    imageVector = MiuixIcons.ChevronForward,
                    contentDescription = null,
                    modifier = Modifier.rotate(if (expanded) 90f else 0f),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                HorizontalDivider()
                when (val current = state) {
                    BlockStatsUiState.Loading -> StatsMessage(
                        stringResource(R.string.stats_loading),
                    )
                    BlockStatsUiState.NoRoot -> StatsMessage(
                        stringResource(R.string.stats_no_root),
                        isError = true,
                    )
                    BlockStatsUiState.NoLog -> StatsMessage(
                        stringResource(R.string.stats_no_log),
                    )
                    BlockStatsUiState.NoRecords -> StatsMessage(
                        stringResource(R.string.stats_empty),
                    )
                    BlockStatsUiState.Failure -> StatsMessage(
                        stringResource(R.string.stats_failed),
                        isError = true,
                    )
                    is BlockStatsUiState.Loaded -> BlockStatsContent(current.snapshot)
                }
                Button(
                    onClick = {
                        if (state !is BlockStatsUiState.Loading) refreshVersion++
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.stats_refresh),
                        style = MiuixTheme.textStyles.button,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsMessage(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = if (isError) {
            MiuixTheme.colorScheme.error
        } else {
            MiuixTheme.colorScheme.onBackgroundVariant
        },
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun BlockStatsContent(snapshot: BlockStatsSnapshot) {
    StatsValueRow(
        title = stringResource(R.string.stats_total_calls),
        value = formatCount(snapshot.totalCalls),
    )
    HorizontalDivider()
    StatsValueRow(
        title = stringResource(R.string.stats_app_count),
        value = formatCount(snapshot.apps.size.toLong()),
    )
    HorizontalDivider()
    StatsValueRow(
        title = stringResource(R.string.stats_latest_time),
        value = snapshot.latestTime,
    )
    HorizontalDivider()
    SmallTitle(
        text = stringResource(R.string.stats_app_ranking),
        insideMargin = PaddingValues(16.dp, 12.dp, 16.dp, 4.dp),
    )
    snapshot.apps.take(STATS_RANKING_LIMIT).forEachIndexed { index, app ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.stats_ranked_app, index + 1, app.label),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (app.label != app.packageName) {
                    Text(
                        text = app.packageName,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                text = formatCount(app.calls),
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    HorizontalDivider()
    SmallTitle(
        text = stringResource(R.string.stats_category_totals),
        insideMargin = PaddingValues(16.dp, 12.dp, 16.dp, 4.dp),
    )
    snapshot.categories.forEach { category ->
        val nameRes = Config.CATS.firstOrNull { it.id == category.id }?.nameRes
        StatsValueRow(
            title = if (nameRes != null) {
                stringResource(nameRes)
            } else {
                stringResource(R.string.stats_unknown_category, category.id)
            },
            value = formatCount(category.calls),
        )
    }
}

@Composable
private fun StatsValueRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

private sealed interface BlockStatsUiState {
    data object Loading : BlockStatsUiState
    data object NoRoot : BlockStatsUiState
    data object NoLog : BlockStatsUiState
    data object NoRecords : BlockStatsUiState
    data object Failure : BlockStatsUiState
    data class Loaded(val snapshot: BlockStatsSnapshot) : BlockStatsUiState
}

private data class BlockStatsSnapshot(
    val totalCalls: Long,
    val apps: List<AppCalls>,
    val categories: List<CategoryCalls>,
    val latestTime: String,
)

private data class AppCalls(
    val packageName: String,
    val label: String,
    val calls: Long,
)

private data class CategoryCalls(val id: Int, val calls: Long)

private data class ParsedStats(
    val appCalls: Map<String, Long>,
    val categoryCalls: Map<Int, Long>,
    val latestTime: String,
)

private fun loadBlockStats(context: Context): BlockStatsUiState {
    val result = readLatestStatsLog()
    if (result.exitCode == STATS_EXIT_NO_ROOT) return BlockStatsUiState.NoRoot
    if (result.exitCode == STATS_EXIT_NO_LOG) return BlockStatsUiState.NoLog
    if (result.exitCode != 0) {
        return if (result.startFailed || looksLikeRootDenial(result.output)) {
            BlockStatsUiState.NoRoot
        } else {
            BlockStatsUiState.Failure
        }
    }
    // 截断不算失败：数据可能不完整但仍然有效，比显示错误页有用。
    // 只丢弃被截断的首行（可能从中间开始），其余正常解析。
    val rawLines = result.output.lineSequence().toList()
    val lines = if (result.truncated && rawLines.isNotEmpty()) {
        rawLines.drop(1)  // 首行可能不完整
    } else {
        rawLines
    }
    val text = if (lines.size > STATS_LOG_TAIL_LINES) {
        lines.takeLast(STATS_LOG_TAIL_LINES).joinToString("\n")
    } else {
        lines.joinToString("\n")
    }
    val parsed = parseBlockStats(text)
    if (parsed == null || parsed.appCalls.isEmpty()) return BlockStatsUiState.NoRecords

    val rankedApps = parsed.appCalls.entries
        .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .map { (packageName, calls) ->
            val updatedAt = runCatching {
                context.packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            }.getOrDefault(0L)
            val resolved = AppLabels.label(context, packageName, updatedAt)
            AppCalls(
                packageName = packageName,
                label = resolved.ifBlank { packageName },
                calls = calls,
            )
        }
    AppLabels.flush(context)
    return BlockStatsUiState.Loaded(
        BlockStatsSnapshot(
            totalCalls = parsed.appCalls.values.sum(),
            apps = rankedApps,
            categories = parsed.categoryCalls.entries
                .sortedBy { it.key }
                .map { CategoryCalls(it.key, it.value) },
            latestTime = parsed.latestTime,
        ),
    )
}

/**
 * 只接受完整的 LSPosedFramework 原始行，避免把正文里恰好含 BLOCKSTAT 的其他日志算入。
 * 时间戳直接来自日志；按固定宽度字符串比较即可得到最近一条有效记录。
 */
private val BLOCK_STAT_LINE = Regex(
    """^\[\s*(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3})\s+\d+:\s*\d+:\s*\d+\s+[VDIWEF]/LSPosedFramework\s*\]\s*\([^\r\n)]+\)\[[^\r\n\]]+\]\s+BLOCKSTAT\|([^|\r\n]+)\|(\d+)\|(\d+)\s*$""",
)

private fun parseBlockStats(text: String): ParsedStats? {
    val apps = LinkedHashMap<String, Long>()
    val categories = LinkedHashMap<Int, Long>()
    var latest: String? = null
    text.lineSequence().forEach { line ->
        val match = BLOCK_STAT_LINE.matchEntire(line) ?: return@forEach
        val timestamp = match.groupValues[1]
        val process = match.groupValues[2]
        val category = match.groupValues[3].toIntOrNull() ?: return@forEach
        val delta = match.groupValues[4].toLongOrNull() ?: return@forEach
        if (process.isBlank() || delta <= 0L) return@forEach
        val packageName = process.substringBefore(':')
        if (packageName.isBlank()) return@forEach
        val appTotal = apps[packageName] ?: 0L
        val categoryTotal = categories[category] ?: 0L
        if (Long.MAX_VALUE - appTotal < delta || Long.MAX_VALUE - categoryTotal < delta) {
            return@forEach
        }
        apps[packageName] = appTotal + delta
        categories[category] = categoryTotal + delta
        if (latest == null || timestamp > latest!!) latest = timestamp
    }
    val latestTime = latest ?: return null
    return ParsedStats(apps, categories, latestTime.replace('T', ' ').substringBefore('.'))
}

private const val STATS_EXIT_NO_LOG = 3
private const val STATS_EXIT_NO_ROOT = 4

private class StatsReadResult(
    val exitCode: Int,
    val output: String,
    val truncated: Boolean = false,
    val startFailed: Boolean = false,
)

/** RootShell 的通用输出上限只有 64 KiB；统计读取必须单独保留完整的 2 MiB 有界 tail。 */
private fun readLatestStatsLog(): StatsReadResult {
    val script = buildString {
        append("if [ \"\$(id -u 2>/dev/null)\" != \"0\" ]; then exit ")
        append(STATS_EXIT_NO_ROOT)
        append("; fi; ")
        append("f=\$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1); ")
        append("[ -n \"\$f\" ] || exit ")
        append(STATS_EXIT_NO_LOG)
        // 单个 tail 命令，退出码可靠；20000 行截断在 Kotlin 端做。
        append("tail -c ")
        append(STATS_LOG_TAIL_BYTES)
        append(" \"\$f\"")
    }

    var process: Process? = null
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MBPU-block-stats").apply { isDaemon = true }
    }
    var read: java.util.concurrent.Future<BoundedOutput>? = null
    try {
        val running = ProcessBuilder("su", "-c", script)
            .redirectErrorStream(true)
            .start()
        process = running
        read = executor.submit<BoundedOutput> {
            readBounded(running.inputStream, STATS_LOG_TAIL_BYTES)
        }
        if (!running.waitFor(STATS_ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return StatsReadResult(124, "command timed out")
        }
        val output = read.get(2, TimeUnit.SECONDS)
        return StatsReadResult(
            exitCode = running.exitValue(),
            output = output.bytes.toString(StandardCharsets.UTF_8),
            truncated = output.truncated,
        )
    } catch (e: java.io.IOException) {
        return StatsReadResult(-1, e.toString(), startFailed = true)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return StatsReadResult(-1, "interrupted")
    } catch (e: Exception) {
        return StatsReadResult(-1, e.toString())
    } finally {
        process?.let {
            runCatching { it.inputStream.close() }
            runCatching { it.outputStream.close() }
            runCatching { it.errorStream.close() }
            it.destroyForcibly()
        }
        read?.cancel(true)
        executor.shutdown()
        runCatching { executor.awaitTermination(3, TimeUnit.SECONDS) }
    }
}

private class BoundedOutput(val bytes: ByteArray, val truncated: Boolean)

/** 超界后继续丢弃输入，避免子进程因管道写满而卡住，同时保证内存有界。 */
private fun readBounded(input: java.io.InputStream, maxBytes: Int): BoundedOutput {
    val out = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(8192)
    var truncated = false
    input.use { stream ->
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            val remaining = maxBytes - out.size()
            if (remaining > 0) out.write(buffer, 0, minOf(remaining, count))
            if (count > remaining) truncated = true
        }
    }
    return BoundedOutput(out.toByteArray(), truncated)
}

private fun looksLikeRootDenial(output: String): Boolean {
    val lower = output.lowercase()
    return listOf("permission denied", "not allowed", "not found", "cannot execute", "unable")
        .any(lower::contains)
}
