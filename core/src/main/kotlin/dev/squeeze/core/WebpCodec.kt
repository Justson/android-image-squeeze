package dev.squeeze.core

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * WebP 编解码。
 *
 * 解码：走 ImageIO —— 依赖 TwelveMonkeys 的 imageio-webp（纯 Java，只读），
 *      在 classpath 上即自动注册，无需额外配置。
 *
 * 编码：JVM 上没有可用的纯 Java WebP 编码器，必须借助 libwebp。
 *      方案对比：
 *        a) JNI 封装(webp-imageio) —— 要为 win/mac-x64/mac-arm/linux 各打一份 native lib，
 *           且 Apple Silicon 上的签名/隔离问题很烦
 *        b) 调用 cwebp 进程 —— Google 官方发布、BSD-3 协议、体积约 1MB/平台
 *      选 b。插件把三平台的 cwebp 放进 resources/bin/<os>-<arch>/，首次使用时释放到
 *      临时目录并 chmod +x；也允许用户在设置里指定系统已装的 cwebp。
 *
 * ⚠️ alpha_quality 默认必须是 100。有损 alpha 会把平滑的 alpha 渐变量化成阶梯，
 *    产生肉眼可见的色带，而任何不产生色带的 alpha_quality 又省不了体积。详见 CompressionRoute。
 */
class WebpCodec(private val cwebpPath: String) {

    data class Options(
        val quality: Int = 90,
        /** 100 = alpha 无损。不要调低，见类注释 */
        val alphaQuality: Int = 100,
        val lossless: Boolean = false,
        /** cwebp 的 -m 参数，压缩耗时/体积权衡，6 最慢最小 */
        val method: Int = 6,
    )

    fun encode(image: BufferedImage, opt: Options): ByteArray {
        val tmpIn = Files.createTempFile("squeeze-in-", ".png").toFile()
        val tmpOut = Files.createTempFile("squeeze-out-", ".webp").toFile()
        try {
            // cwebp 读 PNG 最稳妥（保留 alpha）；这一步是无损的，不影响最终质量
            ImageIO.write(image, "png", tmpIn)
            val cmd = buildList {
                add(cwebpPath)
                if (opt.lossless) {
                    add("-lossless"); add("-z"); add("9")
                } else {
                    add("-q"); add(opt.quality.toString())
                    add("-alpha_q"); add(opt.alphaQuality.toString())
                }
                add("-m"); add(opt.method.toString())
                add("-quiet")
                add(tmpIn.absolutePath)
                add("-o"); add(tmpOut.absolutePath)
            }
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                error("cwebp 超时")
            }
            check(proc.exitValue() == 0) { "cwebp 失败(exit=${proc.exitValue()}): $log" }
            return tmpOut.readBytes()
        } finally {
            tmpIn.delete()
            tmpOut.delete()
        }
    }

    fun encodedSize(image: BufferedImage, opt: Options): Int = encode(image, opt).size

    companion object {
        /** 读取任意受支持的图片（PNG/JPG/WebP）。WebP 依赖 TwelveMonkeys 在 classpath 上。 */
        fun read(file: File): BufferedImage =
            ImageIO.read(file) ?: error("无法解码：${file.name}（若为 WebP，请确认 imageio-webp 在 classpath）")

        fun read(bytes: ByteArray): BufferedImage =
            ImageIO.read(bytes.inputStream()) ?: error("无法解码字节流")

        /**
         * 定位 cwebp：
         *  1) 显式配置（插件设置项）
         *  2) 随插件分发的二进制（resources/bin/<os>-<arch>/cwebp[.exe]）
         *  3) PATH 上的系统 cwebp
         */
        fun locate(configured: String?, bundledDir: File?): WebpCodec {
            configured?.takeIf { File(it).canExecute() }?.let { return WebpCodec(it) }
            bundledDir?.let { dir ->
                val exe = File(dir, if (isWindows()) "cwebp.exe" else "cwebp")
                if (exe.exists()) {
                    if (!isWindows()) exe.setExecutable(true)
                    return WebpCodec(exe.absolutePath)
                }
            }
            if (probe("cwebp")) return WebpCodec("cwebp")
            error("找不到 cwebp。请在 Settings > Tools > Image Squeeze 中指定路径，" +
                    "或安装 libwebp（brew install webp / apt install webp）")
        }

        fun platformDirName(): String {
            val os = System.getProperty("os.name").lowercase()
            val arch = System.getProperty("os.arch").lowercase()
            val o = when {
                os.contains("win") -> "windows"
                os.contains("mac") -> "macos"
                else -> "linux"
            }
            val a = if (arch.contains("aarch64") || arch.contains("arm")) "arm64" else "x64"
            return "$o-$a"
        }

        private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")

        private fun probe(exe: String): Boolean = runCatching {
            val p = ProcessBuilder(exe, "-version").redirectErrorStream(true).start()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
    }
}
