package io.github.sweetpotato_digua.mbpu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 澎湃OS 柔光玻璃 —— 0.9.5-rc01 官方 Glass 组件版。
 *
 * 顶栏 GlassTopAppBar、圆钮 GlassIconButton、回顶 GlassFloatingActionButton
 * 全部改用 miuix-glass 官方组件（自带玻璃材质、描边与降级路径）；
 * 本文件只保留它们共用的 Backdrop 采集层（官方 Background Sampling Contract）
 * 与能力开关，旧的手工 textureBlur / EdgeStroke 实现已删除。
 */

object Glass {
    fun supported(): Boolean = runCatching { isRuntimeShaderSupported() }.getOrDefault(false)

    /** 玻璃外观恒开：仅在 shader 不支持的设备上由组件自动降级（无用户开关）。 */
    fun active(): Boolean = supported()
}

/**
 * 面板同款玻璃材质（dialogGlass + surfaceContainer @ 72%）。
 *
 * 官方默认的按钮材质 puredThinGlass（白柔光/强光层）在 surface 浅底上视觉平淡、
 * 弹层默认 popupViewGlass 同样偏实，观感都接近不透明；配置面板的 dialogGlass
 * 是用户认可的玻璃质感，按钮与弹层统一用它（直接引用面板默认材质，保证一致）。
 */
@Composable
fun dialogGlassMaterial(): GlassMaterial = GlassBottomSheetDefaults.material()

/** 给内容容器挂背景层（抓取真实内容，供顶栏 / 按钮玻璃材质采样）。 */
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
