package com.sweetpotato.MBPU

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.text.layoutDirection
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.util.Locale

/** 主题模式 */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun of(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

/**
 * 全局主题 / 语言 / 手势开关状态。
 * <p>
 * 语言切换不重建 Activity：stringResource 读的是 LocalResources，
 * 换掉这个 CompositionLocal 就能让整个界面立即用新语言重组。
 */
object AppThemeState {

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** 生效的 Locale；null 表示跟随系统 */
    var locale by mutableStateOf<Locale?>(null)
        private set

    /** 保存的语言代码（含 system），供界面勾选 */
    var languageCode by mutableStateOf(Languages.SYSTEM)
        private set

    var predictiveBack by mutableStateOf(true)
        private set

    /** 高级材质：澎湃风格玻璃 / 渐变模糊 */
    var advancedMaterial by mutableStateOf(false)
        private set

    private var initialized = false

    @Synchronized
    fun init(store: Store) {
        if (initialized) return
        initialized = true
        mode = ThemeMode.of(store.darkMode())
        predictiveBack = store.predictiveBack()
        advancedMaterial = store.advancedMaterial()

        val code = store.language()
        languageCode = code
        locale = Languages.toLocale(code)
        Locale.setDefault(locale ?: systemLocale())
    }

    /**
     * 系统语言的权威来源。
     * <p>
     * 不能用 Locale.getDefault()：应用内切语言改的就是它，改过之后这个值已经被污染。
     * 也不能用应用自己的 resources.configuration：Activity 的 Resources 可能带着覆盖。
     * Resources.getSystem() 是纯系统资源，只反映设备语言，不受应用影响。
     */
    fun systemLocale(): Locale {
        return try {
            val cfg = Resources.getSystem().configuration
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val list = cfg.locales
                if (list.isEmpty()) Locale.getDefault() else list[0]
            } else {
                @Suppress("DEPRECATION")
                cfg.locale ?: Locale.getDefault()
            }
        } catch (t: Throwable) {
            Locale.getDefault()
        }
    }

    fun select(store: Store, next: ThemeMode) {
        if (mode == next) return
        mode = next
        store.setDarkMode(next.key)
    }

    fun setPredictiveBack(store: Store, enabled: Boolean) {
        predictiveBack = enabled
        store.setPredictiveBack(enabled)
    }

    fun setAdvancedMaterial(store: Store, enabled: Boolean) {
        advancedMaterial = enabled
        store.setAdvancedMaterial(enabled)
    }

    fun selectLanguage(store: Store, code: String) {
        if (!Languages.isSupported(code)) return
        store.saveLanguage(code)
        languageCode = code
        if (code == Languages.SYSTEM) {
            locale = null
            Locale.setDefault(systemLocale())
        } else {
            val next = Languages.toLocale(code)
            locale = next
            next?.let { Locale.setDefault(it) }
        }
        AppLabels.clear()
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (AppThemeState.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = remember(dark) { if (dark) darkColorScheme() else lightColorScheme() }

    val locale = AppThemeState.locale
    val context = LocalContext.current
    val baseResources = context.resources

    // 无条件走同一个 Provider：语言在 null / 非 null 之间切换时若改变组合结构，
    // 整棵子树会重建，页面栈就被重置（表现为回主页）。
    val localized = remember(locale, baseResources) {
        val target = locale ?: AppThemeState.systemLocale()
        val config = Configuration(baseResources.configuration)
        config.setLocale(target)
        @Suppress("DEPRECATION")
        Resources(baseResources.assets, baseResources.displayMetrics, config)
    }
    val config = remember(localized, dark) { localized.configuration }
    val localizedContext = remember(locale, context, config, dark) {
        context.createConfigurationContext(config)
    }
    val target = locale ?: AppThemeState.systemLocale()
    val direction = if (target.layoutDirection == 1) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(
        LocalResources provides localized,
        LocalConfiguration provides config,
        LocalContext provides localizedContext,
        LocalLayoutDirection provides direction,
    ) {
        MiuixTheme(colors = colors) { content() }
    }
}
