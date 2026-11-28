package io.github.sweetpotato_digua.mbpu

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = App.store()
        setContent {
            AppTheme {
                AppRoot(store, onExit = { finish() })
            }
        }
    }
}

/**
 * 应用页面壳：主页 / 设置 / 主题 / 语言 / 关于都在同一个 Activity 内。
 * 这样跨页面返回也能用同一套预测性返回手势。
 */
@Composable
fun AppRoot(store: Store, onExit: () -> Unit) {
    // 官方 miuix-nav 页面栈：NavBackStack + NavDisplay（Expansion 展开转场、
    // 系统/预测返回由 NavDisplay 的 onBack 统一处理）。
    val backStack = remember { navBackStackOf(Route.Home) }

    // 页面栈持久化：配置变更或 Activity 重建后不会莫名跳回主页
    var savedKeys by rememberSaveable { mutableStateOf(listOf(Route.Home.key)) }
    LaunchedEffect(Unit) {
        if (savedKeys.size > 1) {
            backStack.clear()
            savedKeys.map { Route.of(it) }.forEach { backStack.add(it) }
        }
        snapshotFlow { backStack.map { (it as Route).key } }.collect { savedKeys = it }
    }

    // 主页状态提升到这里：从设置返回时保留列表与模块状态。
    // 大列表不塞进 Bundle；配置变更后的重建会在 IO 线程重新读取。
    val home = remember { HomeState() }
    val context = LocalContext.current
    val refreshScope = rememberCoroutineScope()
    // 列表滚动、下拉刷新和刷新作业都必须在页面栈之外：
    // Home 离开组合后刷新不能取消，返回时指示器状态也不能重置。
    val homeListState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    // 加载代际：只有最新一次请求才允许提交结果。
    // 下拉刷新（refreshScope）和语言切换（LaunchedEffect）可能并行，
    // 旧刷新晚于新加载完成时会把列表覆盖回旧数据/旧语言。
    val loadGeneration = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    suspend fun loadHome() {
        val generation = loadGeneration.incrementAndGet()
        // 加载开始时固定目标语言：解析过程中切语言，不会得到一半新一半旧的混合列表。
        val targetLocale = AppThemeState.locale?.toLanguageTag()
            ?: AppThemeState.systemLocale().toLanguageTag()
        val apps = withContext(Dispatchers.IO) {
            // 变更时间在加载阶段一次性读取，避免每张卡片重组时都访问 SharedPreferences。
            loadInstalledApps(context).map { it.copy(changedAt = store.changedAt(it.packageName)) }
        }
        // snapshot() 走 RemotePreferences.getAll()，是同步 Binder IPC，必须在 IO 线程，
        // 不能在主线程的 Store 全局锁里调用。
        val masks = withContext(Dispatchers.IO) { store.snapshot() }
        val isLatest = generation == loadGeneration.get()
        if (!isLatest) return
        home.allApps = apps
        home.masks = masks
        home.loaded = true
        home.labelsLocale = targetLocale
    }

    fun refreshHome() {
        if (home.refreshing) return
        home.refreshing = true
        refreshScope.launch {
            val started = SystemClock.elapsedRealtime()
            try {
                // refresh() 内部会删除磁盘缓存文件，属于 IO 操作，不能放主线程。
                withContext(Dispatchers.IO) { AppLabels.refresh(context) }
                loadHome()
                val elapsed = SystemClock.elapsedRealtime() - started
                if (elapsed < 400) delay(400 - elapsed)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                // 统一走主页的 Miuix 提示条，不再弹系统 Toast（两套样式、还会排队）。
                home.toast = context.getString(
                    R.string.toast_operation_failed,
                    t.message ?: t.javaClass.simpleName,
                )
            } finally {
                home.refreshing = false
            }
        }
    }

    val localeTag = AppThemeState.locale?.toLanguageTag() ?: AppThemeState.systemLocale().toLanguageTag()
    LaunchedEffect(localeTag) {
        try {
            if (!home.loaded || home.labelsLocale != localeTag) {
                home.loading = true
                loadHome()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // 统一走主页的 Miuix 提示条，不再弹系统 Toast（两套样式、还会排队）。
            home.toast = context.getString(
                R.string.toast_operation_failed,
                t.message ?: t.javaClass.simpleName,
            )
        } finally {
            home.loading = false
        }
    }

    NavDisplay(
        backStack = backStack,
        transition = NavTransitions.Expansion,
        onBack = { if (backStack.size > 1) backStack.removeLastOrNull() else onExit() },
    ) {
        entry<Route.Home> {
            PermNullScreen(
                store = store,
                home = home,
                listState = homeListState,
                pullToRefreshState = pullToRefreshState,
                onRefresh = ::refreshHome,
                onOpenSettings = { backStack.add(Route.Settings) },
                // L2：把「当前栈顶是否 Home」传给主页，被覆盖时停掉 root 轮询。
                isHomeTop = { backStack.lastOrNull() == Route.Home },
            )
        }

        entry<Route.Settings> {
            SettingsScreen(
                store = store,
                onBack = { backStack.removeLastOrNull() },
                onConfigRestored = {
                    // 让仍在进行的旧加载失效；恢复刷新不走 refreshing guard，必须真正启动新加载。
                    loadGeneration.incrementAndGet()
                    refreshScope.launch {
                        try {
                            loadHome()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            home.toast = context.getString(
                                R.string.toast_operation_failed,
                                t.message ?: t.javaClass.simpleName,
                            )
                        }
                    }
                },
                onOpenTheme = { backStack.add(Route.Theme) },
                onOpenLanguage = { backStack.add(Route.Language) },
                onOpenAbout = { backStack.add(Route.About) },
                onOpenSponsor = { backStack.add(Route.Sponsor) },
                onOpenDebug = { backStack.add(Route.Debug) },
            )
        }

        entry<Route.Theme> {
            ThemePage(store = store, onBack = { backStack.removeLastOrNull() })
        }
        entry<Route.Language> {
            LanguagePage(store = store, onBack = { backStack.removeLastOrNull() })
        }
        entry<Route.About> {
            AboutScreen(store = store, onBack = { backStack.removeLastOrNull() })
        }
        entry<Route.Sponsor> {
            SponsorScreen(onBack = { backStack.removeLastOrNull() })
        }
        entry<Route.Debug> {
            DebugScreen(store = store, onBack = { backStack.removeLastOrNull() })
        }
    }
}
