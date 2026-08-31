package dev.squeeze.plugin

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import dev.squeeze.core.WebpCodec
import java.io.File

/**
 * 把随插件分发的 cwebp 从 jar 里释放到可执行目录。
 *
 * jar 内不能直接执行，必须先落盘。落到 PathManager.getSystemPath() 下而不是临时目录，
 * 这样只释放一次，IDE 重启后仍可复用。
 *
 * Windows 之外的平台还要显式 setExecutable —— jar 不保留 POSIX 权限位，
 * 解出来默认是 0644，直接跑会 Permission denied。
 */
object CwebpProvider {
    private val log = logger<CwebpProvider>()

    private val binDir: File by lazy {
        File(PathManager.getSystemPath(), "asset-squeeze/bin/${WebpCodec.platformDirName()}")
    }

    /**
     * @param configuredPath 用户在设置里指定的 cwebp 路径，优先级最高
     * @throws IllegalStateException 三级回退都找不到时抛出，消息里带安装指引
     */
    fun codec(configuredPath: String? = null): WebpCodec =
        WebpCodec.locate(configuredPath?.takeIf { it.isNotBlank() }, extractIfNeeded())

    /** 返回释放后的目录；当前平台没有随包二进制时返回 null，交给 locate() 继续回退到 PATH */
    private fun extractIfNeeded(): File? {
        val platform = WebpCodec.platformDirName()
        val exeName = if (platform.startsWith("windows")) "cwebp.exe" else "cwebp"
        val target = File(binDir, exeName)
        if (target.canExecute()) return binDir

        val resource = "/bin/$platform/$exeName"
        val stream = javaClass.getResourceAsStream(resource) ?: run {
            log.warn("插件内没有 $platform 的 cwebp（$resource），将回退到 PATH")
            return null
        }
        return try {
            binDir.mkdirs()
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            // jar 不保留 POSIX 权限位，解出来是 0644，必须补上可执行位
            if (!target.setExecutable(true)) {
                log.warn("无法给 ${target.absolutePath} 加可执行权限")
            }
            log.info("已释放 cwebp 到 ${target.absolutePath}")
            binDir
        } catch (e: Exception) {
            log.warn("释放 cwebp 失败，回退到 PATH", e)
            null
        }
    }
}
