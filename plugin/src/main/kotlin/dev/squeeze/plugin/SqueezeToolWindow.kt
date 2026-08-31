package dev.squeeze.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.ui.ScrollPaneFactory
import com.intellij.util.ui.JBUI
import dev.squeeze.core.Analyzer
import dev.squeeze.core.CompressionRoute
import dev.squeeze.core.HostBackgroundResolver
import dev.squeeze.core.ImageStats
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class SqueezeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SqueezePanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

class SqueezePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = ScanTableModel()
    private val table = JBTable(model).apply {
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION)
        autoCreateRowSorter = true
    }
    private val status = JBLabel("尚未扫描").apply { border = JBUI.Borders.empty(4, 8) }

    init {
        val group = DefaultActionGroup().apply {
            add(ScanAction())
            add(ApplySelectedAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ImageSqueeze", group, true)
        toolbar.targetComponent = this
        add(toolbar.component, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
    }

    private fun codecOrWarn(): Analyzer? {
        val codec = runCatching { CwebpProvider.codec(SqueezeSettings.of(project).cwebpPath) }
            .getOrElse {
                Messages.showErrorDialog(project, it.message ?: "找不到 cwebp", "Image Squeeze")
                return null
            }
        return Analyzer(codec)
    }

    private inner class ScanAction : AnAction("扫描工程", "遍历工程列出可优化的素材", AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val analyzer = codecOrWarn() ?: return
            object : Task.Backgroundable(project, "Image Squeeze 扫描中", true) {
                private var rows: List<ScanRow> = emptyList()
                override fun run(indicator: ProgressIndicator) {
                    rows = ScanService(project).scan(analyzer, indicator)
                }
                override fun onSuccess() {
                    model.replace(rows)
                    val actionable = rows.count { it.report.route != CompressionRoute.NONE }
                    val saveable = rows.filter { it.report.route != CompressionRoute.NONE }
                        .sumOf { it.report.savedBytes }
                    status.text = "共 ${rows.size} 个素材，其中 $actionable 个可优化，" +
                        "合计可省 ${"%.1f".format(saveable / 1024.0 / 1024.0)} MB" +
                        "（仓库口径；同名文件散在多个 flavor 时单包收益更小）"
                }
                override fun onCancel() {
                    status.text = "扫描已取消"
                }
            }.queue()
        }
    }

    private inner class ApplySelectedAction :
        AnAction("应用选中项", "对选中的素材执行压缩并替换", AllIcons.Actions.Commit) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = table.selectedRowCount > 0
        }

        override fun actionPerformed(e: AnActionEvent) {
            val analyzer = codecOrWarn() ?: return
            val picked = table.selectedRows.map { model.rowAt(table.convertRowIndexToModel(it)) }
            val settings = SqueezeSettings.of(project)
            val applyService = ApplyService(project)

            var applied = 0
            var saved = 0L
            val skipped = mutableListOf<String>()

            for (row in picked) {
                val r = row.report
                if (r.route == CompressionRoute.NONE) {
                    skipped += "${row.file.name}：收益不足"
                    continue
                }
                val bg = (row.hostBackground as? HostBackgroundResolver.Result.Solid)?.color
                if (r.route == CompressionRoute.BAKE_BACKGROUND && bg == null) {
                    // 批量场景下更要守住这条：猜白色会在线上留下色差方块
                    skipped += "${row.file.name}：宿主底色未确定，不能烘焙"
                    continue
                }
                val quality = if (r.route == CompressionRoute.KEEP_ALPHA)
                    settings.keepAlphaQuality else settings.bakeQuality
                val out = runCatching {
                    analyzer.compress(File(row.file.path), r.route, bg, quality)
                }.getOrElse {
                    skipped += "${row.file.name}：${it.message}"
                    continue
                }
                if (!out.bandingOk) {
                    skipped += "${row.file.name}：压缩后出现色带"
                    continue
                }
                when (val res = applyService.apply(row.file, out.bytes, out.renameToWebp)) {
                    is ApplyService.Outcome.Skipped -> skipped += "${row.file.name}：${res.reason}"
                    is ApplyService.Outcome.Replaced -> { applied++; saved += res.savedBytes }
                    is ApplyService.Outcome.Renamed -> { applied++; saved += res.savedBytes }
                }
            }

            Messages.showInfoMessage(
                project,
                buildString {
                    append("已替换 $applied 个，省下 ${"%.1f".format(saved / 1024.0)} KB。可用 Ctrl+Z 撤销。")
                    if (skipped.isNotEmpty()) {
                        append("\n\n跳过 ${skipped.size} 个：\n")
                        skipped.take(15).forEach { append("• $it\n") }
                        if (skipped.size > 15) append("…以及另外 ${skipped.size - 15} 个")
                    }
                },
                "Image Squeeze"
            )
            // 已替换的文件体积变了，报告失效，提示重扫
            status.text = "已应用 $applied 个，建议重新扫描以刷新数据"
        }
    }
}

private class ScanTableModel : AbstractTableModel() {
    private var rows: List<ScanRow> = emptyList()

    private val columns = listOf(
        "文件", "当前KB", "半透%", "αVar", "噪点", "路线", "预估KB", "倍数", "宿主底色", "警告"
    )

    fun replace(newRows: List<ScanRow>) {
        rows = newRows
        fireTableDataChanged()
    }

    fun rowAt(i: Int) = rows[i]

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(c: Int) = columns[c]

    override fun getValueAt(r: Int, c: Int): Any {
        val row = rows[r]
        val rep = row.report
        return when (c) {
            0 -> row.file.name
            1 -> rep.currentBytes / 1024
            2 -> "%.0f%%".format(rep.alpha.semiRatio * 100)
            3 -> "${rep.alphaBanding.levels}/${rep.alphaBanding.maxStep}"
            4 -> "%.2f %s".format(rep.noise.value, kindLabel(rep.noise.kind)) +
                if (rep.noise.backgroundDependent) "?" else ""
            5 -> routeLabel(rep.route)
            6 -> rep.estimatedBytes / 1024
            7 -> "%.1fx".format(rep.ratio)
            8 -> when (val bg = row.hostBackground) {
                is HostBackgroundResolver.Result.Solid ->
                    "#%02X%02X%02X".format(bg.color.red, bg.color.green, bg.color.blue)
                is HostBackgroundResolver.Result.Conflict -> "冲突(${bg.colors.size} 种)"
                is HostBackgroundResolver.Result.Unresolved -> "未解析"
            }
            else -> rep.warnings.joinToString("；")
        }
    }

    private fun kindLabel(k: ImageStats.NoiseKind) = when (k) {
        ImageStats.NoiseKind.CLEAN -> "干净"
        ImageStats.NoiseKind.DITHER -> "抖动"
        ImageStats.NoiseKind.TEXTURE -> "纹理"
    }

    private fun routeLabel(r: CompressionRoute) = when (r) {
        CompressionRoute.KEEP_ALPHA -> "保留alpha"
        CompressionRoute.BAKE_BACKGROUND -> "烘焙底色"
        CompressionRoute.NONE -> "不压缩"
    }
}
