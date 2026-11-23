package io.github.sweetpotato_digua.mbpu

import androidx.activity.BackEventCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 应用内的页面路由，全部在同一个 Activity 内切换。 */
sealed interface Route {
    val key: String

    data object Home : Route { override val key = "home" }
    data object Settings : Route { override val key = "settings" }
    data object Theme : Route { override val key = "theme" }
    data object Language : Route { override val key = "language" }
    data object About : Route { override val key = "about" }
    data object Sponsor : Route { override val key = "sponsor" }
    data object Debug : Route { override val key = "debug" }

    companion object {
        fun of(key: String): Route = when (key) {
            Settings.key -> Settings
            Theme.key -> Theme
            Language.key -> Language
            About.key -> About
            Sponsor.key -> Sponsor
            Debug.key -> Debug
            else -> Home
        }
    }
}

/**
 * 页面栈 + 出入场进度。
 * progress：0 = 顶层完全就位，1 = 顶层完全移出。
 * 所有出栈事务绑定启动时的 route，旧动画不得删除后来加入的页面。
 */
class NavStackState {

    val stack: SnapshotStateList<Route> = mutableListOf<Route>(Route.Home).toMutableStateList()

    var progress by mutableFloatStateOf(0f)
        private set

    var edge by mutableStateOf(BackEventCompat.EDGE_LEFT)

    var slideSign by mutableStateOf(-1f)
        private set

    var gestureActive by mutableStateOf(false)
        private set

    var entering by mutableStateOf(false)
        private set

    private var settling = false
    internal var scope: CoroutineScope? = null
    private var animJob: Job? = null

    val current: Route get() = stack.last()
    val canPop: Boolean get() = stack.size > 1
    fun keys(): List<String> = stack.map { it.key }

    private fun cancelAnimation() {
        animJob?.cancel()
        animJob = null
    }

    fun restore(keys: List<String>) {
        if (keys.isEmpty()) return
        cancelAnimation()
        Snapshot.withMutableSnapshot {
            stack.clear()
            keys.forEach { stack.add(Route.of(it)) }
            progress = 0f
            gestureActive = false
            entering = false
            settling = false
        }
    }

    fun dragTo(value: Float) {
        if (!canPop || settling) return
        cancelAnimation()
        gestureActive = true
        entering = false
        slideSign = if (edge == BackEventCompat.EDGE_RIGHT) 1f else -1f
        progress = value.coerceIn(0f, 1f)
    }

    fun cancelDrag(animated: Boolean = true) {
        settling = false
        entering = false
        if (!animated) {
            cancelAnimation()
            gestureActive = false
            progress = 0f
            return
        }
        animateTo(0f, 200) { gestureActive = false }
    }

    /** scope 离开组合时解除强引用；不得保留已取消的 Recomposer 上下文。 */
    fun detachScope(expected: CoroutineScope) {
        if (scope !== expected) return
        cancelAnimation()
        scope = null
        settling = false
        gestureActive = false
        entering = false
        progress = 0f
    }

    private fun animateTo(target: Float, durationMillis: Int, onDone: (() -> Unit)? = null) {
        val s = scope
        cancelAnimation()
        if (s == null || !s.isActive) {
            Snapshot.withMutableSnapshot {
                progress = target
                onDone?.invoke()
            }
            return
        }
        val job = s.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                animate(progress, target, animationSpec = tween(durationMillis)) { value, _ ->
                    progress = value
                }
                onDone?.invoke()
            } finally {
                if (animJob === coroutineContext[Job]) animJob = null
            }
        }
        animJob = job
    }

    /** 进入下一页。fromRight 决定新页面的进入方向。 */
    fun push(route: Route, fromRight: Boolean = true) {
        if (stack.lastOrNull() == route || gestureActive || settling) return
        cancelAnimation()
        slideSign = if (fromRight) 1f else -1f
        Snapshot.withMutableSnapshot {
            progress = 1f
            stack.add(route)
        }
        gestureActive = false
        entering = true
        animateTo(0f, 260) { entering = false }
    }

    /** 按钮返回：固定向左退；手势和出栈结算期间不重复启动新事务。 */
    fun pop() {
        if (!canPop || gestureActive || settling) return
        val targetRoute = current
        edge = BackEventCompat.EDGE_LEFT
        slideSign = -1f
        gestureActive = false
        entering = false
        settling = true
        animateTo(1f, 240) { commitPop(targetRoute) }
    }

    /** 手势完成：补齐剩余位移后提交同一个 route。 */
    fun settlePop() {
        if (!canPop || settling) return
        val targetRoute = current
        slideSign = if (edge == BackEventCompat.EDGE_RIGHT) 1f else -1f
        gestureActive = false
        entering = false
        settling = true
        val remaining = ((1f - progress) * 220f).toInt().coerceIn(60, 220)
        animateTo(1f, remaining) { commitPop(targetRoute) }
    }

    private fun commitPop(targetRoute: Route) {
        Snapshot.withMutableSnapshot {
            if (stack.lastOrNull() == targetRoute && stack.size > 1) {
                stack.removeAt(stack.lastIndex)
            }
            progress = 0f
            gestureActive = false
            entering = false
            settling = false
        }
    }
}

/** 页面容器。只让顶层位移，下层固定不动，容器统一裁剪。 */
@Composable
fun AppNavHost(
    nav: NavStackState,
    modifier: Modifier = Modifier,
    content: @Composable (Route) -> Unit,
) {
    // screenWidthDp 是整块屏幕宽度，而 NavHost 实际画在容器里（分屏、自由窗口、
    // 折叠屏展开时更窄）。用实测宽度算位移，否则手势中途松手会露出下层页面的错误边缘。
    // 用 State 存：graphicsLayer 的 lambda 里读，只触发重绘不触发重组。
    var widthPx by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    DisposableEffect(nav, scope) {
        nav.scope = scope
        onDispose { nav.detachScope(scope) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat() }
            .clipToBounds(),
    ) {
        val visible = nav.stack.takeLast(2)
        if (visible.size > 1) {
            key(visible.first().key) {
                Box(modifier = Modifier.fillMaxSize()) { content(visible.first()) }
            }
        }
        key(visible.last().key) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // 在绘制 lambda 内读逐帧状态，避免每帧重组整棵页面树。
                        translationX = nav.slideSign * nav.progress * widthPx
                    },
            ) {
                content(visible.last())
            }
        }
    }
}

/** 统一的返回处理；key(enabled) 保证系统回调类型被完整重建。 */
@Composable
fun PredictiveBackHost(
    nav: NavStackState,
    enabled: Boolean,
    onExitApp: () -> Unit,
) {
    key(enabled) {
        if (enabled) {
            if (nav.canPop) {
                PredictiveBackHandler { flow ->
                    try {
                        flow.collect { event ->
                            nav.edge = event.swipeEdge
                            nav.dragTo(event.progress)
                        }
                        nav.settlePop()
                    } catch (e: CancellationException) {
                        nav.cancelDrag(animated = false)
                        throw e
                    }
                }
            } else {
                BackHandler(enabled = true) { onExitApp() }
            }
        } else {
            // 系统仍启用 OnBackInvokedCallback，不能再叠加一个自研边缘返回手势。
            BackHandler(enabled = true) {
                if (nav.canPop) nav.pop() else onExitApp()
            }
        }
    }
    DisposableEffect(enabled) {
        onDispose { nav.cancelDrag(animated = false) }
    }
}
