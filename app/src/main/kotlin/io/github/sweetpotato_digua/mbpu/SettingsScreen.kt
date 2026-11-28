package io.github.sweetpotato_digua.mbpu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 设置列表页 */
@Composable
fun SettingsScreen(
    store: Store,
    onBack: () -> Unit,
    onConfigRestored: () -> Unit,
    onOpenTheme: (Boolean) -> Unit,
    onOpenLanguage: (Boolean) -> Unit,
    onOpenAbout: (Boolean) -> Unit,
    onOpenSponsor: (Boolean) -> Unit,
    onOpenDebug: (Boolean) -> Unit,
) {
    // 纯手势元数据不应驱动 Snapshot 重组；普通数组只保存最新触点与实际布局宽度。
    val lastTapX = remember { floatArrayOf(Float.NaN) }
    // screenWidthDp 是整块屏幕宽度，不是当前窗口/容器宽度：分屏、自由窗口、
    // 折叠屏展开时会把「从哪侧进入」判反。这里用实测布局宽度。
    val containerWidthPx = remember { floatArrayOf(Float.NaN) }

    fun fromRight(): Boolean {
        val x = lastTapX[0]
        val width = containerWidthPx[0]
        if (x.isNaN() || width.isNaN() || width <= 0f) return true
        return x > width / 2f
    }

    val glassBackdrop = rememberGlassBackdrop()
    // 顶栏玻璃跟随滚动：列表离开顶部才浮现（顶部=普通外观，官方语义）。
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    Scaffold(
        topBar = {
            SettingsTopBar(
                stringResource(R.string.settings),
                onBack,
                glassBackdrop,
                isContentScrolled = isScrolled,
            )
        },
    ) { innerPadding ->
        val top = innerPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding()
        // LazyListScope 不是 Composable 上下文，可见性要在组合期先算好。
        val showSponsor = sponsorVisible()

        LazyColumn(
            state = listState,
            modifier = Modifier
                .onSizeChanged { containerWidthPx[0] = it.width.toFloat() }
                .asGlassSource(glassBackdrop),
            contentPadding = PaddingValues(top = top, bottom = bottom + 12.dp),
        ) {
            item {
                SmallTitle(
                    text = stringResource(R.string.appearance),
                    insideMargin = PaddingValues(16.dp, 8.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.theme),
                        summary = stringResource(themeSummaryRes(AppThemeState.mode)),
                        modifier = Modifier.tapLocator { lastTapX[0] = it },
                        onClick = { onOpenTheme(fromRight()) },
                    )
                }
            }

            item {
                SmallTitle(
                    text = stringResource(R.string.language_section),
                    insideMargin = PaddingValues(16.dp, 8.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.app_language),
                        summary = if (AppThemeState.languageCode == Languages.SYSTEM) {
                            stringResource(R.string.language_system)
                        } else {
                            Languages.labelOf(AppThemeState.languageCode)
                        },
                        modifier = Modifier.tapLocator { lastTapX[0] = it },
                        onClick = { onOpenLanguage(fromRight()) },
                    )
                }
            }

            item {
                ConfigBackupSection(
                    store = store,
                    onConfigRestored = onConfigRestored,
                    backdrop = glassBackdrop,
                )
            }

            // 赞赏码是微信的，非中文界面扫不动，entry 只在中文下出现。
            if (showSponsor) {
                item {
                    SmallTitle(
                        text = stringResource(R.string.sponsor_section),
                        insideMargin = PaddingValues(16.dp, 8.dp),
                    )
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.sponsor),
                            summary = stringResource(R.string.sponsor_summary),
                            modifier = Modifier.tapLocator { lastTapX[0] = it },
                            onClick = { onOpenSponsor(fromRight()) },
                        )
                    }
                }
            }

            item {
                SmallTitle(
                    text = stringResource(R.string.about_section),
                    insideMargin = PaddingValues(16.dp, 8.dp),
                )
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(R.string.about_title),
                        summary = stringResource(R.string.about_summary),
                        modifier = Modifier.tapLocator { lastTapX[0] = it },
                        onClick = { onOpenAbout(fromRight()) },
                    )
                    HorizontalDivider()
                    ArrowPreference(
                        title = stringResource(R.string.debug_title),
                        summary = stringResource(R.string.debug_summary),
                        modifier = Modifier.tapLocator { lastTapX[0] = it },
                        onClick = { onOpenDebug(fromRight()) },
                    )
                }
            }
        }
    }
}

/** 主题页 */
@Composable
fun ThemePage(store: Store, onBack: () -> Unit) {
    // 读全局状态而不是本地 remember：这样开启后立刻生效，不需要重启应用
    val predictiveBack = AppThemeState.predictiveBack

    val glassBackdrop = rememberGlassBackdrop()
    val themeListState = rememberLazyListState()
    val isScrolledTheme by remember {
        derivedStateOf {
            themeListState.firstVisibleItemIndex > 0 ||
                themeListState.firstVisibleItemScrollOffset > 0
        }
    }
    Scaffold(
        topBar = {
            SettingsTopBar(
                stringResource(R.string.theme),
                onBack,
                glassBackdrop,
                // 内容不满一屏时列表滚不动，isScrolled 恒 false：
                // 固定页面顶栏永远是普通外观，不显示玻璃。
                isContentScrolled = isScrolledTheme,
            )
        },
    ) { innerPadding ->
        val top = innerPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding()

        LazyColumn(
            state = themeListState,
            modifier = Modifier.asGlassSource(glassBackdrop),
            contentPadding = PaddingValues(top = top, bottom = bottom + 12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    for (mode in ThemeMode.entries) {
                        RadioButtonPreference(
                            title = stringResource(themeSummaryRes(mode)),
                            selected = AppThemeState.mode == mode,
                            onClick = { AppThemeState.select(store, mode) },
                        )
                    }
                }
            }

            item {
                // 本文件其它卡片都是「左右 12dp + 底部 12dp」，这张漏了底部间距，
                // 滑到底时会比别的页面紧一格。
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    SwitchPreference(
                        title = stringResource(R.string.advanced_material),
                        summary = if (Glass.supported()) {
                            stringResource(R.string.advanced_material_desc)
                        } else {
                            stringResource(R.string.advanced_material_unsupported)
                        },
                        checked = AppThemeState.advancedMaterial,
                        enabled = Glass.supported(),
                        onCheckedChange = { AppThemeState.setAdvancedMaterial(store, it) },
                    )
                    HorizontalDivider()
                    SwitchPreference(
                        title = stringResource(R.string.predictive_back),
                        summary = stringResource(R.string.predictive_back_desc),
                        checked = predictiveBack,
                        onCheckedChange = { AppThemeState.setPredictiveBack(store, it) },
                    )
                }
            }
        }
    }
}

/** 语言页 */
@Composable
fun LanguagePage(store: Store, onBack: () -> Unit) {
    val codes = remember { Languages.codes() }
    val systemLabel = stringResource(R.string.language_system)
    // 语言名来自 Languages 的静态表，只有首项要换成本地化的「跟随系统」；
    // 用 remember 缓存，避免每次重组都重建一次 22 项列表。
    val labels = remember(codes, systemLabel) {
        Languages.labels().toMutableList().also { list ->
            if (list.isNotEmpty()) list[0] = systemLabel
        }
    }

    val glassBackdrop = rememberGlassBackdrop()
    val langListState = rememberLazyListState()
    val isScrolledLang by remember {
        derivedStateOf {
            langListState.firstVisibleItemIndex > 0 ||
                langListState.firstVisibleItemScrollOffset > 0
        }
    }
    Scaffold(
        topBar = {
            SettingsTopBar(
                stringResource(R.string.app_language),
                onBack,
                glassBackdrop,
                isContentScrolled = isScrolledLang,
            )
        },
    ) { innerPadding ->
        val top = innerPadding.calculateTopPadding()
        val bottom = innerPadding.calculateBottomPadding()

        LazyColumn(
            state = langListState,
            modifier = Modifier.asGlassSource(glassBackdrop),
            contentPadding = PaddingValues(top = top, bottom = bottom + 12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                ) {
                    for (i in codes.indices) {
                        RadioButtonPreference(
                            title = labels[i],
                            selected = AppThemeState.languageCode == codes[i],
                            onClick = { AppThemeState.selectLanguage(store, codes[i]) },
                        )
                    }
                }
            }
        }
    }
}

/** 关于页 */
@Composable
fun AboutScreen(store: Store, onBack: () -> Unit) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    // 版本号用带前缀的形式展示，和「关于」里的产品定位一致
    val version = remember {
        val raw = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        // versionName 自带 -beta 后缀，不再重复拼接
        if (raw.startsWith("v")) raw else "v" + raw
    }

    val glassBackdrop = rememberGlassBackdrop()
    val aboutScroll = rememberScrollState()
    val isScrolledAbout by remember { derivedStateOf { aboutScroll.value > 0 } }
    Scaffold(
        topBar = {
            SettingsTopBar(
                stringResource(R.string.about),
                onBack,
                glassBackdrop,
                isContentScrolled = isScrolledAbout,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .asGlassSource(glassBackdrop)
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding(),
                )
                .verticalScroll(aboutScroll),
        ) {
            SmallTitle(
                text = stringResource(R.string.about_app),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                InfoRow(stringResource(R.string.about_name), appName)
                InfoRow(stringResource(R.string.about_version), version)
                InfoRow(stringResource(R.string.about_author), stringResource(R.string.about_author_value))
                // 包名跟 BuildConfig 绑定，换渠道/改包时要跟着变；同页的版本号已是动态读取，
                // 这里写死会出现「版本对、包名错」的不一致。
                InfoRow(stringResource(R.string.about_package), context.packageName)
            }

            // 更新日志：版本号与上方「版本」一行同源，标题行和正文行左对齐在同一列。
            SmallTitle(
                text = stringResource(R.string.about_changelog_section),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.about_changelog_title, version),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                    // 显式分行：aapt2 对字符串资源中 \n 的处理在不同版本不一致，
                    // 兼容两种情况：已编译为 0x0A 的换行符，或仍为字面量 \n。
                    val changelogLines = stringResource(R.string.about_changelog_body)
                        .replace("\\n", "\n")  // 字面量 \n → 真换行
                        .split(Regex("\\r?\\n"))
                        .filter { it.isNotBlank() }
                    changelogLines.forEach { line ->
                        Text(
                            text = line,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onBackgroundVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            SmallTitle(
                text = stringResource(R.string.about_desc_section),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_desc),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onBackgroundVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }

            SmallTitle(
                text = stringResource(R.string.about_ui_section),
                insideMargin = PaddingValues(16.dp, 8.dp),
            )
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                InfoRow(
                    stringResource(R.string.about_ui_framework),
                    stringResource(R.string.about_ui_framework_value),
                )
                InfoRow(stringResource(R.string.about_module_api), "libxposed api 102")
            }
        }
    }
}

@Composable
internal fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop? = null,
    // 显式滚动状态：页面在顶部/不可滚动的固定页面恒 false，
    // 按钮保持普通外观、无渐变带（官方语义）；滚动离开顶部才浮现玻璃。
    isContentScrolled: Boolean = false,
) {
    GlassTopAppBar(
        title = title,
        expandable = false,
        backdrop = backdrop,
        isContentScrolled = isContentScrolled,
        navigationIcon = {
            GlassIconButton(onClick = onBack) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.back),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    )
}

private fun themeSummaryRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.LIGHT -> R.string.theme_light
}

@Composable
private fun InfoRow(title: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 记录按下时的横坐标，供「从哪一侧弹出」判断。
 * <p>
 * 用 awaitPointerEvent(PointerEventPass.Initial)：Initial 阶段先于子节点的 clickable，
 * 所以能稳定拿到坐标。detectTapGestures(onPress) 挂在同一节点上会和内部 clickable 抢事件，
 * 导致 onClick 触发时坐标还是旧值。
 */
private fun Modifier.tapLocator(onX: (Float) -> Unit): Modifier =
    this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.pressed } ?: continue
                onX(change.position.x)
            }
        }
    }
