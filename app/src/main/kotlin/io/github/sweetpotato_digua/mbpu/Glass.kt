package io.github.sweetpotato_digua.mbpu

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 澎湃OS 柔光玻璃 —— 干净的官方 Miuix 实现。
 *
 *   1. 顶部渐变模糊  —— progressiveTextureBlur + ProgressiveBlur.Top（柔和、顶深底浅）
 *   2. 顶栏两个圆钮  —— textureBlur + 官方 Highlight.GlassStroke 玻璃高光描边
 */

object Glass {
    /**
     * 方向光：主光在左上（最强），副光在右下（中等），
     * 于是高光沿左上→右下这条对角线走，右上与左下靠描边的固有衰减保留轻微提亮。
     * 全部参数基于 Miuix 官方 BloomStroke，只替换光源位置与强度。
     */
    fun highlight(dark: Boolean): Highlight {
        val base = if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight
        val style = base.style as? BloomStroke ?: return base
        val tuned = style.copy(
            // 描边本身只有 5% 白，在浅色背景上几乎看不见。
            // 深色主题靠提高白色到 30% 才能出高光；浅色主题上白描边叠白底依旧是白，
            // 必须换成极淡的黑边才勾得出玻璃轮廓。
            color = if (dark) {
                Color.White.copy(alpha = 0.30f)
            } else {
                Color.Black.copy(alpha = 0.10f)
            },
            innerBlurRadius = 1.6.dp,
            primaryLight = LightSource(
                position = LightPosition(0.2f, 0.2f, -0.5f),
                intensity = 1.2f,
            ),
            secondaryLight = LightSource(
                position = LightPosition(0.8f, 0.8f, -0.5f),
                intensity = 1.0f,
            ),
            dualPeak = false,
        )
        return Highlight(base.width, base.alpha, tuned)
    }

    fun supported(): Boolean = runCatching { isRuntimeShaderSupported() }.getOrDefault(false)
    fun active(): Boolean = AppThemeState.advancedMaterial && supported()

    @Composable
    fun barColor(): Color = if (active()) Color.Transparent else MiuixTheme.colorScheme.surface
}

/**
 * 顶部渐变模糊 —— 官方 progressiveTextureBlur + ProgressiveBlur.Top。
 * 半径与表面色不能太弱：blurRadius=8 配 surface@30% 时，顶栏下的卡片标题、图标仍然
 * 清晰可辨，会和顶栏自己的标题、副标题糊成一片（实测截图里副标题和卡片文字直接叠住）。
 * 这里把半径提到 20、表面色调提到 55%，滚动内容在顶栏下只剩色块，文字不再可读。
 */
@Composable
fun GlassTopBar(
    backdrop: LayerBackdrop?,
    content: @Composable () -> Unit,
) {
    if (backdrop == null || !Glass.active()) {
        Box(modifier = Modifier.background(MiuixTheme.colorScheme.surface)) { content() }
        return
    }
    Box {
        Box(
            modifier = Modifier
                .matchParentSize()
                .progressiveTextureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                    blurRadius = 20f,
                    noiseCoefficient = 0f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(
                            BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.55f)),
                        ),
                    ),
                ),
        )
        content()
    }
}

/**
 * 毛玻璃按钮 —— 官方 textureBlur + Highlight.GlassStroke 玻璃高光描边。
 * 小圆钮用 Small 系列预设，跟随主题深浅切换。
 */
@Composable
fun Modifier.glassButton(
    backdrop: LayerBackdrop?,
    tint: Color? = null,
    shape: Shape = CircleShape,
    tintAlpha: Float = 0.88f,
): Modifier {
    if (backdrop == null || !Glass.active()) {
        // 没有玻璃时用不透明底色，保证按钮始终可见（蓝色回顶按钮就是靠这里）。
        val fallback = tint ?: MiuixTheme.colorScheme.surfaceVariant
        return this.background(fallback, shape)
    }
    val dark = when (AppThemeState.mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // 只做极淡的染色，保留玻璃透视；真正的不透明来自调用方背景色。
    val colors = if (tint == null) {
        BlurDefaults.blurColors()
    } else {
        BlurDefaults.blurColors(
            blendColors = listOf(BlendColorEntry(color = tint.copy(alpha = tintAlpha))),
        )
    }
    // highlight() 每次调用都会新建 Highlight + 两个 LightSource：
    // 顶栏按钮随滚动、输入、提示条变化频繁重组，不缓存会让 textureBlur
    // 认为参数变了而重建模糊节点（shader 重编）。按明暗各缓存一份。
    val highlight = remember(dark) { Glass.highlight(dark) }
    return this.textureBlur(
        backdrop = backdrop,
        shape = shape,
        // 半径略降 + 噪点归零：减少模糊层自身的颗粒与涂抹感，
        // 采样更干净，玻璃背后的文字/图标边缘更清晰。
        blurRadius = 14f,
        noiseCoefficient = 0f,
        colors = colors,
        highlight = highlight,
    )
}

/** 给内容容器挂背景层（抓取真实内容，供顶栏 / 按钮模糊）。 */
fun Modifier.asGlassSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) this.layerBackdrop(backdrop) else this

/** 官方：抓取内容层。玻璃面在它之外读取。
 *  关键：先铺一层 surface 底色再画内容（同官方 rememberBlurBackdrop）。
 *  没有这层底色，backdrop 里未绘制区域是透明的，滚动内容在透明黑上被模糊
 *  摊开成生硬色块，观感就是"粗糙的块状晕圈"。 */
@Composable
fun rememberGlassBackdrop(): LayerBackdrop? {
    if (!Glass.active()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}
