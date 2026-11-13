package com.sweetpotato.MBPU

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode

/**
 * 一个什么都不画的 indication。
 *
 * 用途：Miuix 的按下反馈是矩形（MiuixIndication），而 Card / Preference 内部的内容
 * Column 不会被裁剪到卡片圆角，点按时会溢出一块方角高亮。把子树里的 LocalIndication
 * 换成它，就彻底没有高亮块，按压手感交给 Card 自己的 pressFeedbackType（缩放）。
 */
internal object NoIndication : IndicationNodeFactory {

    private class NoIndicationNode : Modifier.Node(), DrawModifierNode {
        override fun ContentDrawScope.draw() = drawContent()
    }

    override fun create(interactionSource: InteractionSource): DelegatableNode = NoIndicationNode()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = 0
}
