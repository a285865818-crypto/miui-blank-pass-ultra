package com.sweetpotato.MBPU

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity

/**
 * 从任意包装 Context 里找到宿主 Activity。
 *
 * Compose 里拿到的 Context 可能是 ContextThemeWrapper / ContextWrapper 链中的一环，
 * 需要逐层解包才能取到真正的 ComponentActivity（用于生命周期、SAF 等）。
 */
internal fun Context.findHostActivity(): ComponentActivity? {
    var current: Context = this
    var depth = 0
    while (current is ContextWrapper && depth < 16) {
        if (current is ComponentActivity) return current
        current = current.baseContext
        depth++
    }
    return current as? ComponentActivity
}
