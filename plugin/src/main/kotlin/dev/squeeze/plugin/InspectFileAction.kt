package dev.squeeze.plugin

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.squeeze.core.Analyzer
import dev.squeeze.core.AssetReport
import dev.squeeze.core.HostBackgroundResolver
import java.io.File

/** 右键单张图片 -> 分析 -> 预览 -> 替换 */
class InspectFileAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = file != null && isSupported(file)
    }

    private fun isSupported(f: VirtualFile) =
        !f.isDirectory && f.extension?.lowercase() in setOf("png", "webp", "jpg", "jpeg")

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val io = File(file.path)

        val codec = runCatching { CwebpProvider.codec(SqueezeSettings.of(project).cwebpPath) }
            .getOrElse {
                Messages.showErrorDialog(project, it.message ?: "找不到 cwebp", "Asset Squeeze")
                return
            }
        val analyzer = Analyzer(codec)
        if (analyzer.isExcluded(io)) {
            Messages.showWarningDialog(
                project,
                "该文件被排除在压缩范围外。\n\n" +
                    "assets/ 下常放 SDK 直接读取的数据文件（例如美颜滤镜的查找表），" +
                    "有损压缩会改变程序行为；.9.png 有拉伸标记，不能按普通图处理。",
                "跳过：${file.name}"
            )
            return
        }

        // 分析要跑两次 cwebp，必须离开 EDT
        object : Task.Backgroundable(project, "分析 ${file.name}", true) {
            private var report: AssetReport? = null
            private var hostBg: HostBackgroundResolver.Result? = null
            private var failure: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                runCatching {
                    indicator.text = "编码试算…"
                    report = analyzer.analyze(io, minSavingRatio = 1.5)
                    indicator.checkCanceled()
                    indicator.text = "解析宿主底色…"
                    hostBg = resolveHost(project, io)
                }.onFailure { failure = it }
            }

            override fun onSuccess() {
                failure?.let {
                    Messages.showErrorDialog(project, it.message ?: it.toString(), "分析失败")
                    return
                }
                SqueezeDialog(project, file, report!!, analyzer, hostBg!!).show()
            }
        }.queue()
    }

    private fun resolveHost(project: Project, io: File): HostBackgroundResolver.Result {
        val root = project.basePath?.let(::File)
            ?: return HostBackgroundResolver.Result.Unresolved("无法确定工程根目录", emptyList())
        return HostBackgroundResolver(root).resolve(
            HostBackgroundResolver.resourceNameOf(io),
            HostBackgroundResolver.resourceTypeOf(io),
        )
    }
}
