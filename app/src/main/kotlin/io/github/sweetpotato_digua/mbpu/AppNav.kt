package io.github.sweetpotato_digua.mbpu

import top.yukonga.miuix.kmp.nav.core.NavKey

/** 应用内的页面路由，全部在同一个 Activity 内切换。实现官方 [NavKey] 供 NavBackStack 使用。 */
sealed interface Route : NavKey {
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
