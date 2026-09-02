package dev.squeeze.plugin

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.squeeze.core.Analyzer
import dev.squeeze.core.ImageReport
import dev.squeeze.core.CompressionRoute
import dev.squeeze.core.HostBackgroundResolver
import dev.squeeze.core.WebpCodec
import java.awt.BorderLayout
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 单张图的分析 / 预览 / 替换对话框。
 *
 * 「应用」按钮的启用条件是有意收紧的：
 *  - 路线为 NONE          -> 禁用（收益不足，压了只是有损叠有损）
 *  - 压缩后引入了色带      -> 禁用（这是我们最想避免的失真）
 *  - 防御性检查：烘焙路线但底色没解析出 -> 禁用（正常分析会先回退 KEEP_ALPHA）
 * 宁可让人手动去确认，也不给一个「看起来能点」的危险按钮。
 */
class SqueezeDialog(
    private val project: Project,
    private val file: VirtualFile,
    initialReport: ImageReport,
    private val analyzer: Analyzer,
    private val hostBg: HostBackgroundResolver.Result,
) : DialogWrapper(project, true) {

    private val comparePanel = ComparePanel()
    private val detail = JBLabel()
    private var report: ImageReport = initialReport
    private var result: Analyzer.Result? = null
    private var blockedReason: String? = null

    /** 用户手动指定的底色。布局里查不到底色时，由人来断言这张图身后是什么颜色 */
    private var manualBg: Color? = null

    init {
        title = "Image Squeeze — ${file.name}"
        setOKButtonText("替换原文件")
        comparePanel.onManualBake = { color -> bakeManually(color) }
        init()
        load()
    }

    /**
     * 按用户给定的底色重新分析 + 编码。
     * 底色解析不出来时，之前只能干看着一片空白的右侧 —— 换预览底色不会产出压缩结果，
     * 必须真的用这个颜色跑一遍烘焙。
     */
    private fun bakeManually(color: Color) {
        val io = File(file.path)
        val outcome = runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<ImageReport, Exception>(
                { analyzer.analyze(io, hostBackground = color) },
                "按底色 ${color.toHexString()} 重新分析…", true, project,
            )
        }.getOrElse {
            Messages.showErrorDialog(project, it.message ?: it.toString(), "分析失败")
            return
        }
        manualBg = color
        report = outcome
        load()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        add(comparePanel, BorderLayout.CENTER)
        add(detail.apply { border = JBUI.Borders.empty(6) }, BorderLayout.SOUTH)
        preferredSize = JBUI.size(900, 560)
    }

    private fun bakeColor(): Color? =
        manualBg ?: (hostBg as? HostBackgroundResolver.Result.Solid)?.color

    /**
     * 硬边缘 alpha 走 KEEP_ALPHA 已是最优且安全，没有手动烘焙的理由；
     * 只有「渐变 alpha + 底色没解析出来」这种被卡住的情况才把决定权交给人。
     */
    private fun manualBakeOffered(): Boolean =
        !report.alpha.isHardEdged &&
            hostBg !is HostBackgroundResolver.Result.Solid

    private fun load() {
        val io = File(file.path)
        val original = WebpCodec.read(io)
        blockedReason = null
        isOKActionEnabled = true

        manualBg?.let { manual ->
            loadManualBake(io, original, manual)
            return
        }

        if (report.route == CompressionRoute.NONE) {
            blockedReason = if (!report.alpha.isHardEdged && bakeColor() == null) {
                "宿主底色无法确定，且安全的保留 alpha 路线收益不足"
            } else {
                "收益不足（预估 ${report.estimatedBytes / 1024}KB / 当前 " +
                    "${report.currentBytes / 1024}KB），不建议压缩"
            }
            comparePanel.show(
                original, null, bakeColor(), "路线：不压缩",
                hostBackgroundRelevant = !report.alpha.isHardEdged,
                emptyMessage = blockedReason!!,
                allowManualBake = manualBakeOffered(),
            )
            detail.text = describe(blockedReason!!)
            isOKActionEnabled = false
            return
        }

        if (report.route == CompressionRoute.BAKE_BACKGROUND && bakeColor() == null) {
            blockedReason = when (hostBg) {
                is HostBackgroundResolver.Result.Conflict ->
                    "这张图被 ${hostBg.hosts.size} 个布局引用，且身后底色不一致（" +
                        hostBg.colors.joinToString { it.toHexString() } + "），烘焙必然串色"
                is HostBackgroundResolver.Result.Unresolved ->
                    "宿主底色无法确定：${hostBg.reason}"
                else -> "宿主底色无法确定"
            }
            comparePanel.show(
                original, null, null, "路线：烘焙底色（受阻）",
                emptyMessage = blockedReason!!,
                allowManualBake = manualBakeOffered(),
            )
            detail.text = describe(blockedReason!!)
            isOKActionEnabled = false
            return
        }

        val settings = SqueezeSettings.of(project)
        val quality = if (report.route == CompressionRoute.KEEP_ALPHA)
            settings.keepAlphaQuality else settings.bakeQuality
        val r = analyzer.compress(io, report.route, bakeColor(), quality)
        result = r

        val compressed = WebpCodec.read(r.bytes)
        comparePanel.show(
            original, compressed, bakeColor(),
            summarize(
                route = routeLabel(report.route),
                currentBytes = report.currentBytes,
                newBytes = r.bytes.size.toLong(),
                alphaPreserved = r.alphaPreserved,
                bandingOk = r.bandingOk,
                noise = report.noise,
            ),
            hostBackgroundRelevant = report.route == CompressionRoute.BAKE_BACKGROUND,
            allowManualBake = manualBakeOffered(),
        )
        if (!r.bandingOk) {
            blockedReason = "压缩后出现色带。逐像素误差看不出这种失真，" +
                "但沿渐变方向的色阶统计能抓到 —— 建议调高质量后重试"
            isOKActionEnabled = false
        }
        detail.text = describe(blockedReason)
    }

    /**
     * 手动底色分支：无条件按该颜色烘焙一份出来给人看。
     * 这里不走 [ImageReport.route] 的判定 —— 用户已经明确说了「就按这个色烘焙」，
     * 收益够不够、有没有色带交给下面的门槛去拦，而不是直接不给预览。
     */
    private fun loadManualBake(io: File, original: BufferedImage, manual: Color) {
        val quality = SqueezeSettings.of(project).bakeQuality
        val r = runCatching {
            ProgressManager.getInstance().runProcessWithProgressSynchronously<Analyzer.Result, Exception>(
                { analyzer.compress(io, CompressionRoute.BAKE_BACKGROUND, manual, quality) },
                "按底色 ${manual.toHexString()} 烘焙…", true, project,
            )
        }.getOrElse {
            Messages.showErrorDialog(project, it.message ?: it.toString(), "烘焙失败")
            return
        }
        result = r

        val newBytes = r.bytes.size.toLong()
        comparePanel.show(
            original, WebpCodec.read(r.bytes), manual,
            summarize(
                route = "烘焙手动指定底色 ${manual.toHexString()}",
                currentBytes = report.currentBytes,
                newBytes = newBytes,
                alphaPreserved = r.alphaPreserved,
                bandingOk = r.bandingOk,
                noise = report.noise,
            ),
            allowManualBake = true,
            bgLabel = "手动底色",
        )
        blockedReason = when {
            !r.bandingOk -> "按该底色烘焙后出现色带，建议换路线或调高质量"
            newBytes >= report.currentBytes -> "烘焙后反而变大（${newBytes / 1024}KB / " +
                "${report.currentBytes / 1024}KB），不值得替换"
            else -> null
        }
        isOKActionEnabled = blockedReason == null
        detail.text = describe(blockedReason)
    }

    private fun describe(blocked: String?): String = buildString {
        append("<html>")
        append("尺寸 ${report.width}×${report.height}　")
        append("半透明 ${"%.0f".format(report.alpha.semiRatio * 100)}%　")
        append("alpha 色阶 ${report.alphaBanding.levels}/台阶 ${report.alphaBanding.maxStep}　")
        append("噪点 ${"%.2f".format(report.noise.value)}(${report.noise.kind})<br>")
        when (hostBg) {
            is HostBackgroundResolver.Result.Solid ->
                append("宿主底色 ${hostBg.color.toHexString()}（来自 ${hostBg.hosts.joinToString()}）<br>")
            is HostBackgroundResolver.Result.Conflict ->
                append("<b>宿主底色冲突</b>：${hostBg.hosts.size} 处引用，" +
                    "${hostBg.colors.joinToString { it.toHexString() }}<br>")
            is HostBackgroundResolver.Result.Unresolved ->
                append("宿主底色未解析：${hostBg.reason}<br>")
        }
        manualBg?.let {
            append("<b>手动指定底色 ${it.toHexString()}</b>：烘焙会丢掉 alpha，" +
                "替换前请确认这张图只用在该颜色之上<br>")
        }
        report.warnings.forEach { append("• $it<br>") }
        if (blocked != null) append("<b>无法应用：$blocked</b>")
        append("</html>")
    }

    private fun routeLabel(r: CompressionRoute) = when (r) {
        CompressionRoute.KEEP_ALPHA -> "RGB 有损 + alpha 无损"
        CompressionRoute.BAKE_BACKGROUND -> "烘焙宿主底色"
        CompressionRoute.NONE -> "不压缩"
    }

    override fun doOKAction() {
        val r = result ?: return
        when (val outcome = ApplyService(project).apply(file, r.bytes, r.renameToWebp)) {
            is ApplyService.Outcome.Skipped ->
                Messages.showWarningDialog(project, outcome.reason, "未替换")
            is ApplyService.Outcome.Replaced ->
                notifyDone("已替换 ${outcome.file.name}", outcome.savedBytes)
            is ApplyService.Outcome.Renamed ->
                notifyDone("${outcome.from} → ${outcome.to.name}", outcome.savedBytes)
        }
        super.doOKAction()
    }

    private fun notifyDone(what: String, saved: Long) {
        Messages.showInfoMessage(
            project,
            "$what\n省下 ${saved / 1024} KB。可用 Ctrl+Z 撤销。",
            "Image Squeeze"
        )
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, cancelAction)
}

private fun Color.toHexString() = "#%02X%02X%02X".format(red, green, blue)
