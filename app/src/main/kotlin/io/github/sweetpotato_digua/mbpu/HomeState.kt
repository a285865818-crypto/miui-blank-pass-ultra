package io.github.sweetpotato_digua.mbpu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 主页的持久状态。
 * <p>
 * 提升到页面栈之外，这样从设置返回主页时列表与模块状态不会重新加载，
 * 也就不会出现「回到主页闪一下、列表和状态消失」。
 */
/**
 * 某个应用的 Hook 注入统计。
 *
 * 之前这里存的是已经拼好的中文串（「已注入 3/9」），界面靠 `contains("部分")` 判断状态，
 * 一旦文案被翻译或调整，状态点就会判错。改成结构化数据，文案由界面层负责。
 */
internal data class HookStat(
    val ok: Int,
    val total: Int,
    /** 子进程（pkg:remote 之类）的统计；没有子进程注入记录时为 null。 */
    val child: HookStat? = null,
) {
    /** 只有主进程和子进程都全部成功才算「全部注入」。 */
    val complete: Boolean get() = ok >= total && (child?.complete ?: true)
}

internal class HomeState {
    var allApps by mutableStateOf(emptyList<AppEntry>())
    var masks by mutableStateOf(emptyMap<String, Int>())
    var statusText by mutableStateOf("")
    var rootStatus by mutableStateOf("")
    var loaded by mutableStateOf(false)
    var labelsLocale by mutableStateOf("")
    /** 刷新状态由 AppRoot 托管，离开主页后刷新继续执行。 */
    var refreshing by mutableStateOf(false)
    var loading by mutableStateOf(true)
    /** 目标进程回传的 Hook 统计：包名 -> 注入数量。 */
    var hookStats by mutableStateOf(emptyMap<String, HookStat>())
    /**
     * 本次 hookStats 是否真的读到了。
     *
     * false 表示读取失败（su 被拒、超时、日志文件还没生成）。这时 [hookStats] 里的 null
     * 只代表「没拿到数据」，不代表「目标应用没注入」——界面上必须区分这两种含义，
     * 否则一次读取失败就会把全部已启用应用的点标成「尚未注入」，让人误以为模块全线失效。
     */
    var hookStatsReadable by mutableStateOf(true)
    /**
     * 待展示的一次性错误提示。
     *
     * 加载/刷新失败发生在主页之外（AppRoot），之前直接弹系统 Toast，样式与主页的
     * Miuix 提示条完全不同，连续失败还会排队串行展示。改为投递到主页统一消费。
     */
    var toast by mutableStateOf<String?>(null)

    /**
     * 本次进程内是否已显示过使用说明。
     *
     * AppNav 用 takeLast(2) 渲染页面，Home 在「单独显示」和「被 Settings 覆盖」时
     * 处于不同的组合槽位，会被销毁后重建。showHelp 若只放在局部 remember，
     * 用户点过「知道了」再进一次设置页回来，说明弹窗又会冒出来。
     */
    var helpShown by mutableStateOf(false)
}
