package com.sweetpotato.MBPU

import androidx.compose.ui.graphics.Color

/**
 * 界面语义色。
 *
 * 之前「危险」这一语义在三个地方用了三种红（0xFFD32F2F / 0xFFE53935 / 0xFFFF3B30），
 * 「成功/失败」状态点又直接抄了 iOS 系统色，与 HyperOS 调色板不搭。这里统一收敛：
 * 同一语义只保留一个色值，深浅主题共用（三处都是实心色块，配白字/浅底均满足对比度）。
 */
internal object AppColors {
    /** 危险操作（重启作用域、不再提示）：深红 #D32F2F，白字对比度约 4.9:1。 */
    val danger = Color(0xFFD32F2F)

    /** Hook 全部注入：绿 #2E9E4F，在浅色底上对比度约 3.1:1，仅作状态点使用。 */
    val dotOk = Color(0xFF2E9E4F)

    /** Hook 部分注入失败：红 #D93A2B，与绿色在灰度下也有明度差。 */
    val dotPartial = Color(0xFFD93A2B)

    /** 日志警告行：橙 #B26A00，深浅底都可读且与错误红明显区分。 */
    val warn = Color(0xFFB26A00)
}
