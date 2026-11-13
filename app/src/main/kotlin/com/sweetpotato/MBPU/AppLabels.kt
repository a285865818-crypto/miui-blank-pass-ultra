package com.sweetpotato.MBPU

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用名与图标的本地化读取。
 *
 * PackageManager.getApplicationLabel() 只返回系统语言下的名称，要跟随应用内语言，
 * 必须自己用目标应用的 Resources 在目标 Locale 下解析 labelRes。
 *
 * 首屏优化：解析结果按 pkg@locale@updatedAt 写入磁盘缓存，冷启动直接命中，
 * 只有新装/更新/切换语言时才重新解析。
 */
object AppLabels {

    private const val CACHE_FILE = "label-cache-v1.tsv"
    private const val MAX_CACHE_BYTES = 512 * 1024L

    private val labelCache = LruCache<String, String>(4096)

    /** 图标缓存：按可用内存自适应条数（约每张 9KB，最多占用 8MB）。 */
    val iconCache = LruCache<String, ImageBitmap>(iconCacheSize())

    @JvmStatic
    private fun iconCacheSize(): Int {
        val maxMb = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()
        // 低内存机器收紧，高端机放宽；上限 8MB 对应约 900 张 96px 图标。
        val budgetMb = (maxMb / 32).coerceIn(2, 8)
        return (budgetMb * 1024 * 1024 / (96 * 96 * 4)).coerceAtLeast(32)
    }

    private var diskLoaded = false
    /** 命中统计：内存 / 磁盘 / 未命中，供诊断页展示。
     *  用 AtomicInteger：多个 IO 协程并行解析应用名，++ 不是原子操作，会丢计数。 */
    private val hitMemory = java.util.concurrent.atomic.AtomicInteger()
    private val hitDisk = java.util.concurrent.atomic.AtomicInteger()
    private val missCount = java.util.concurrent.atomic.AtomicInteger()
    private val dirtyKeys = HashSet<String>()

    private fun cacheKey(packageName: String, locale: Locale, updatedAt: Long): String =
        packageName + "@" + locale.toLanguageTag() + "@" + updatedAt

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE)

    @Synchronized
    private fun ensureDiskLoaded(context: Context) {
        if (diskLoaded) return
        diskLoaded = true
        try {
            val file = cacheFile(context)
            if (!file.exists() || file.length() > MAX_CACHE_BYTES) return
            file.forEachLine { line ->
                if (line.isEmpty()) return@forEachLine
                val parts = line.split('\t')
                if (parts.size >= 4 && parts[3].isNotEmpty()) {
                    labelCache.put(parts[0] + "@" + parts[1] + "@" + parts[2], parts[3])
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    fun label(context: Context, packageName: String, updatedAt: Long = 0L): String {
        val locale = Locale.getDefault()
        val key = cacheKey(packageName, locale, updatedAt)
        labelCache.get(key)?.let {
            hitMemory.incrementAndGet()
            return it
        }
        val before = diskLoaded
        ensureDiskLoaded(context)
        labelCache.get(key)?.let {
            if (!before) hitDisk.incrementAndGet() else missCount.incrementAndGet()
            return it
        }
        missCount.incrementAndGet()

        val name = runCatching {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val base = pm.getResourcesForApplication(appInfo)

            val config = Configuration(base.configuration)
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            val localized = Resources(base.assets, base.displayMetrics, config)

            val labelRes = appInfo.labelRes
            if (labelRes != 0) {
                localized.getString(labelRes)
            } else {
                pm.getApplicationLabel(appInfo).toString()
            }
        }.getOrNull().orEmpty()

        if (name.isNotEmpty()) {
            labelCache.put(key, name)
            synchronized(labelCache) { dirtyKeys.add(key) }
        }
        return name
    }

    /** 首次加载结束后落盘；只写新增项，避免每次启动重写整份缓存。 */
    @Synchronized
    fun flush(context: Context) {
        val pending: List<String>
        synchronized(labelCache) {
            if (dirtyKeys.isEmpty()) return
            pending = dirtyKeys.toList()
            dirtyKeys.clear()
        }
        try {
            val file = cacheFile(context)
            if (file.length() > MAX_CACHE_BYTES) file.delete()
            val sb = StringBuilder()
            for (key in pending) {
                val value = labelCache.get(key) ?: continue
                val at = key.lastIndexOf('@')
                val at2 = if (at > 0) key.lastIndexOf('@', at - 1) else -1
                if (at2 <= 0 || at <= at2) continue
                sb.append(key, 0, at2).append('\t')
                    .append(key, at2 + 1, at).append('\t')
                    .append(key, at + 1, key.length).append('\t')
                    .append(value.replace('\n', ' ').replace('\t', ' ')).append('\n')
            }
            if (sb.isNotEmpty()) file.appendText(sb.toString())
        } catch (ignored: Throwable) {
            // 写盘失败必须把 dirtyKeys 放回去，否则这些键永远不会落盘。
            synchronized(labelCache) { dirtyKeys.addAll(pending) }
        }
    }

    /** 缓存统计：返回 "内存命中/磁盘命中/未命中" 及占用条数。 */
    fun stats(): String = "mem=${hitMemory.get()} disk=${hitDisk.get()} miss=${missCount.get()} size=${labelCache.size()}"

    fun resetStats() {
        hitMemory.set(0)
        hitDisk.set(0)
        missCount.set(0)
    }

    /** 应用内语言切换后必须清空，否则仍显示旧语言的名称。
     *  diskLoaded 置回 false：下次 ensureDiskLoaded 可以重新读盘。
     *  缓存 key 含 locale，旧语言条目不会匹配新 key，不会被误用。 */
    fun clear() {
        labelCache.evictAll()
        synchronized(labelCache) { dirtyKeys.clear() }
        diskLoaded = false
    }

    /**
     * 后台预热：把当前语言下所有已安装应用的名称解析并落盘。
     * 只在磁盘缓存缺失的条目上真正访问 PackageManager，已缓存项直接跳过。
     */
    fun warmUp(context: Context) {
        ensureDiskLoaded(context)
        val pm = context.packageManager
        val packages = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val locale = Locale.getDefault()
        for (pkg in packages) {
            val info = pkg.applicationInfo ?: continue
            val updatedAt = maxOf(pkg.firstInstallTime, pkg.lastUpdateTime)
            if (labelCache.get(cacheKey(info.packageName, locale, updatedAt)) != null) continue
            label(context, info.packageName, updatedAt)
        }
        flush(context)
    }

    /** 手动刷新：清内存与磁盘缓存，下次加载重新解析应用名。 */
    fun refresh(context: Context) {
        clear()
        iconCache.evictAll()
        synchronized(this) {
            diskLoaded = false
            try {
                cacheFile(context).delete()
            } catch (ignored: Throwable) {
            }
        }
    }
}

/** 按需异步加载应用图标 */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, installedAt: Long = 0L) {
    val context = LocalContext.current
    // 缓存 key 加 installedAt：应用更新后 produceState key 变化会重新加载，
    // 清缓存后也不会复用旧图标。
    val iconKey = "$packageName@$installedAt"
    val icon by produceState(
        initialValue = AppLabels.iconCache.get(iconKey),
        iconKey,
    ) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            AppLabels.iconCache.get(iconKey) ?: runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
                    .also { AppLabels.iconCache.put(iconKey, it) }
            }.getOrNull()
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    }
}
