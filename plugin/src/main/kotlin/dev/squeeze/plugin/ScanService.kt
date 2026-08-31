package dev.squeeze.plugin

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileVisitor
import dev.squeeze.core.Analyzer
import dev.squeeze.core.ImageReport
import dev.squeeze.core.HostBackgroundResolver
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 一行扫描结果：分析报告 + 该资源的宿主底色 */
data class ScanRow(
    val file: VirtualFile,
    val report: ImageReport,
    val hostBackground: HostBackgroundResolver.Result,
)

class ScanService(private val project: Project) {

    private val supported = setOf("png", "webp", "jpg", "jpeg")

    fun scan(analyzer: Analyzer, indicator: ProgressIndicator): List<ScanRow> {
        val settings = SqueezeSettings.of(project)
        val minBytes = settings.minSizeKb * 1024L

        indicator.text = "收集候选文件…"
        val candidates = collect(analyzer, minBytes)
        if (candidates.isEmpty()) return emptyList()

        // 宿主底色索引必须一次性建好。逐个查是 O(资源数 × 布局数)，几百张图就卡死。
        indicator.text = "解析布局，建立宿主底色索引…"
        val root = project.basePath?.let(::File)
        val bgIndex = root?.let { HostBackgroundResolver(it).resolveAll() } ?: emptyMap()

        indicator.text = "分析素材…"
        return analyzeAll(analyzer, candidates, bgIndex, indicator)
    }

    private fun collect(analyzer: Analyzer, minBytes: Long): List<VirtualFile> {
        val out = mutableListOf<VirtualFile>()
        // project.baseDir 已废弃；用 basePath + LocalFileSystem 取工程根
        val base = project.basePath
            ?.let { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
            ?: return out
        VfsUtilCore.visitChildrenRecursively(base, object : VirtualFileVisitor<Any>() {
            override fun visitFile(file: VirtualFile): Boolean {
                if (file.isDirectory) {
                    // 提前剪枝，避免走进 build/.git 这类大目录
                    return file.name !in setOf("build", ".git", ".gradle", ".idea")
                }
                if (file.extension?.lowercase() in supported &&
                    file.length >= minBytes &&
                    !analyzer.isExcluded(File(file.path))
                ) out += file
                return true
            }
        })
        return out
    }

    /**
     * 并行分析。cwebp 是独立进程，天然可并行；线程数取 CPU 数与 8 的较小值，
     * 再多只会让磁盘和进程创建成为瓶颈。
     */
    private fun analyzeAll(
        analyzer: Analyzer,
        files: List<VirtualFile>,
        bgIndex: Map<HostBackgroundResolver.ResKey, HostBackgroundResolver.Result>,
        indicator: ProgressIndicator,
    ): List<ScanRow> {
        val threads = minOf(Runtime.getRuntime().availableProcessors(), 8).coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(threads)
        val done = AtomicInteger()
        try {
            val tasks = files.map { vf ->
                Callable {
                    if (indicator.isCanceled) return@Callable null
                    val io = File(vf.path)
                    val row = runCatching {
                        val key = HostBackgroundResolver.ResKey(
                            HostBackgroundResolver.resourceTypeOf(io),
                            HostBackgroundResolver.resourceNameOf(io),
                        )
                        val bg = bgIndex[key]
                            ?: HostBackgroundResolver.Result.Unresolved("没有布局引用（可能在代码里用）", emptyList())
                        val background = (bg as? HostBackgroundResolver.Result.Solid)?.color
                        val report = analyzer.analyze(io, hostBackground = background)
                        ScanRow(vf, report, bg)
                    }.getOrNull()
                    val n = done.incrementAndGet()
                    indicator.fraction = n.toDouble() / files.size
                    indicator.text2 = "$n / ${files.size}  ${vf.name}"
                    row
                }
            }
            val results = pool.invokeAll(tasks).mapNotNull {
                runCatching { it.get() }.getOrNull()
            }
            if (indicator.isCanceled) throw ProcessCanceledException()
            // 按可省字节倒序：先看收益最大的
            return results.sortedByDescending { it.report.savedBytes }
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
