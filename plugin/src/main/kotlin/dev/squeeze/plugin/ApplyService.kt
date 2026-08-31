package dev.squeeze.plugin

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil

/**
 * 把压缩结果写回工程。
 *
 * 必须走 WriteCommandAction：
 *  - 进 IDE 的 undo 栈，用户 Ctrl+Z 能整体回退
 *  - 自动刷新 VFS，Project View 立即更新
 *  - 与 Local History 集成，出事能翻旧版本
 *
 * PNG -> WebP 的改名规则（实测踩过坑，务必照做）：
 *  1) Android 资源引用不含扩展名（@drawable/foo），所以把 foo.png 改成 foo.webp
 *     不需要改任何代码。minSdk 21 完整支持带 alpha 的 WebP。
 *  2) 但绝不能把 WebP 字节写进 .png 文件名 —— 曾经这么干过，AAPT 会按 PNG 处理它。
 *     所以这里强制「先建新文件，再删旧文件」，不做原地覆盖。
 *  3) 改名前必须检查同目录是否已存在同名 .webp，否则会静默覆盖别的资源。
 *  4) @mipmap/x 和 @drawable/x 是不同命名空间，本操作不跨目录搬文件，天然安全。
 */
class ApplyService(private val project: Project) {

    sealed interface Outcome {
        data class Replaced(val file: VirtualFile, val savedBytes: Long) : Outcome
        data class Renamed(val from: String, val to: VirtualFile, val savedBytes: Long) : Outcome
        data class Skipped(val file: VirtualFile, val reason: String) : Outcome
    }

    fun apply(target: VirtualFile, newBytes: ByteArray, renameToWebp: Boolean): Outcome {
        val oldSize = target.length
        if (newBytes.size >= oldSize) {
            return Outcome.Skipped(target, "压缩后反而更大，通常说明这张图已经压过了，再压是有损叠有损")
        }
        if (!renameToWebp) {
            WriteCommandAction.runWriteCommandAction(project, "Squeeze: Replace Asset", null, {
                target.setBinaryContent(newBytes)
            })
            return Outcome.Replaced(target, oldSize - newBytes.size)
        }

        val dir = target.parent
        val webpName = target.nameWithoutExtension + ".webp"
        dir.findChild(webpName)?.let {
            return Outcome.Skipped(target, "同目录已存在 $webpName，改名会覆盖它")
        }

        var created: VirtualFile? = null
        WriteCommandAction.runWriteCommandAction(project, "Squeeze: Convert To WebP", null, {
            val f = dir.createChildData(this, webpName)
            f.setBinaryContent(newBytes)
            created = f
            target.delete(this)          // 旧 .png 删除，引用按资源名解析，不受影响
        })
        VfsUtil.markDirtyAndRefresh(true, false, false, dir)
        return Outcome.Renamed(target.name, created!!, oldSize - newBytes.size)
    }
}
