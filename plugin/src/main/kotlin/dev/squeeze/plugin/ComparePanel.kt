package dev.squeeze.plugin

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import dev.squeeze.core.ImageStats
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JButton
import javax.swing.DefaultComboBoxModel
import javax.swing.JPanel

/**
 * 原图 / 压缩图 并排预览。
 *
 * ⚠️ 这个面板存在的首要理由：**IDE 自带的图片 Diff 会骗人**。
 * 它把带 alpha 的图渲染在深色主题背景上，于是原图看起来又浓又饱和，
 * 而「烘焙到白底」后的压缩图看起来惨白 —— 实际两者在真实宿主底色上是一样的。
 * 实测就因此误判过两次。
 *
 * 所以这里强制要求：两侧**始终用同一个底色**渲染，且底色可切换（白/宿主色/灰/黑/棋盘），
 * 默认选中解析出来的宿主底色。
 */
class ComparePanel : JBPanel<ComparePanel>(BorderLayout()) {

    private val leftView = ImageView("原图")
    private val rightView = ImageView("压缩后")
    private val bgCombo = ComboBox(arrayOf<BgOption>())
    private val customColor = JBTextField("#FFFFFF", 8)
    private val info = JBLabel()

    init {
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4))).apply {
            add(JBLabel("预览底色（仅影响预览）："))
            add(bgCombo)
            add(JBLabel("自定义："))
            add(customColor)
            add(JButton("预览").apply { addActionListener { applyCustomColor() } })
        }
        val top = JPanel(BorderLayout()).apply {
            add(controls, BorderLayout.CENTER)
            add(info.apply { border = JBUI.Borders.empty(0, 8, 4, 8) }, BorderLayout.SOUTH)
        }
        add(top, BorderLayout.NORTH)

        val split = com.intellij.ui.JBSplitter(false, 0.5f).apply {
            firstComponent = leftView
            secondComponent = rightView
        }
        add(split, BorderLayout.CENTER)

        bgCombo.addActionListener {
            (bgCombo.selectedItem as? BgOption)?.let { opt ->
                leftView.background2 = opt.color
                rightView.background2 = opt.color
                leftView.refresh(); rightView.refresh()
            }
        }
        customColor.addActionListener { applyCustomColor() }
    }

    data class BgOption(val label: String, val color: Color?, val custom: Boolean = false) {
        override fun toString() = label
    }

    private fun applyCustomColor() {
        val color = parseSolidColor(customColor.text) ?: run {
            customColor.putClientProperty("JComponent.outline", "error")
            customColor.toolTipText = "请输入 #RGB 或 #RRGGBB，例如 #F0F6FB"
            return
        }
        customColor.putClientProperty("JComponent.outline", null)
        customColor.toolTipText = null
        customColor.text = color.toHex()

        val model = bgCombo.model as DefaultComboBoxModel<BgOption>
        for (i in model.size - 1 downTo 0) {
            if (model.getElementAt(i).custom) model.removeElementAt(i)
        }
        val option = BgOption("自定义 ${color.toHex()}", color, custom = true)
        model.addElement(option)
        bgCombo.selectedItem = option
    }

    /**
     * @param hostBackground 解析出的宿主底色；为 null 时默认落到棋盘格。
     * @param hostBackgroundRelevant 当前路线是否依赖宿主底色；只有依赖且未解析时才警告。
     */
    fun show(
        original: BufferedImage,
        compressed: BufferedImage?,
        hostBackground: Color?,
        summary: String,
        hostBackgroundRelevant: Boolean = true,
        emptyMessage: String = "未生成压缩结果",
    ) {
        val options = buildList {
            hostBackground?.let { add(BgOption("宿主底色 ${it.toHex()}", it)) }
            if (hostBackground == null) add(BgOption("棋盘格（看 alpha 形状）", null))
            add(BgOption("白 #FFFFFF", Color.WHITE))
            add(BgOption("灰 #808080", Color(128, 128, 128)))
            add(BgOption("黑 #000000", Color.BLACK))
            if (hostBackground != null) add(BgOption("棋盘格（看 alpha 形状）", null))
        }
        bgCombo.model = javax.swing.DefaultComboBoxModel(options.toTypedArray())
        bgCombo.selectedIndex = 0
        customColor.text = hostBackground?.toHex() ?: "#FFFFFF"
        customColor.putClientProperty("JComponent.outline", null)
        customColor.toolTipText = null

        leftView.image = original
        rightView.image = compressed
        rightView.emptyMessage = if (compressed == null) emptyMessage else null
        leftView.background2 = options.first().color
        rightView.background2 = options.first().color
        info.text = if (hostBackground == null && hostBackgroundRelevant)
            "$summary ｜ ⚠ 宿主底色未解析出来，预览仅供参考"
        else summary
        leftView.refresh()
        rightView.refresh()
    }

    private fun Color.toHex() = "#%02X%02X%02X".format(red, green, blue)

    private fun parseSolidColor(raw: String): Color? {
        var hex = raw.trim().removePrefix("#")
        if (hex.length == 3) hex = hex.map { "$it$it" }.joinToString("")
        if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
        return Color(hex.substring(0, 2).toInt(16), hex.substring(2, 4).toInt(16), hex.substring(4, 6).toInt(16))
    }

    /** 单侧图像视图：等比缩放、可切底色、可显示棋盘格 */
    private class ImageView(private val title: String) : JPanel(BorderLayout()) {
        var image: BufferedImage? = null
        var emptyMessage: String? = null
        /** null = 棋盘格 */
        var background2: Color? = Color.WHITE

        private val canvas = object : JComponent() {
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                val bg = background2
                if (bg == null) paintChecker(g2) else { g2.color = bg; g2.fillRect(0, 0, width, height) }
                val img = image ?: run {
                    emptyMessage?.let { text ->
                        g2.color = JBColor.GRAY
                        val fm = g2.fontMetrics
                        g2.drawString(text, ((width - fm.stringWidth(text)) / 2).coerceAtLeast(0), height / 2)
                    }
                    return
                }
                val scale = minOf(width.toDouble() / img.width, height.toDouble() / img.height)
                    .coerceAtMost(1.0)
                val w = (img.width * scale).toInt()
                val h = (img.height * scale).toInt()
                g2.drawImage(img, (width - w) / 2, (height - h) / 2, w, h, null)
            }

            private fun paintChecker(g2: Graphics2D) {
                val s = JBUI.scale(8)
                for (y in 0 until height step s) for (x in 0 until width step s) {
                    g2.color = if ((x / s + y / s) % 2 == 0) JBColor.LIGHT_GRAY else JBColor.WHITE
                    g2.fillRect(x, y, s, s)
                }
            }
        }

        init {
            add(JBLabel(title).apply { border = JBUI.Borders.empty(2, 6) }, BorderLayout.NORTH)
            add(canvas, BorderLayout.CENTER)
            preferredSize = Dimension(JBUI.scale(320), JBUI.scale(320))
        }

        /**
         * 不覆写 repaint()：Swing 在父类构造期就会调用它，那时 canvas 还没初始化，
         * 覆写并访问 canvas 会 NPE。改由外部显式调用本方法。
         */
        fun refresh() {
            canvas.repaint()
        }
    }
}

/** 供 UI 展示的一行摘要，把关键判定压成一句话 */
fun summarize(
    route: String,
    currentBytes: Long,
    newBytes: Long,
    alphaPreserved: Boolean,
    bandingOk: Boolean,
    noise: ImageStats.NoiseReport,
): String = buildString {
    append(route)
    append(" ｜ ")
    append("%.0fKB → %.0fKB (%.1fx)".format(currentBytes / 1024.0, newBytes / 1024.0,
        currentBytes.toDouble() / newBytes.coerceAtLeast(1)))
    append(" ｜ alpha ").append(if (alphaPreserved) "逐像素一致" else "已丢弃")
    append(" ｜ ").append(if (bandingOk) "无新增色带" else "⚠ 出现色带")
    append(" ｜ 噪点 %.2f/%s".format(noise.value, noise.kind))
}
