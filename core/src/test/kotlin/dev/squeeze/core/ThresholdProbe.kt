package dev.squeeze.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.random.Random
import kotlin.test.Test

/**
 * 阈值标定用的探针，不是断言测试。
 * 打印合成样本 + 真实工程素材的 (噪点, 模糊后, 降幅, ΔRGB)，用来挑 DITHER/TEXTURE 的分界。
 * 运行: ./gradlew :core:test --tests "*ThresholdProbe*" -i
 */
class ThresholdProbe {

    private fun diagGradient(w: Int = 200, h: Int = 200, alphaTo0: Boolean = false): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) {
            val v = 255 - (x + y) * 80 / (w + h)
            val a = if (alphaTo0) (255 - (y * 255 / (h - 1))) else 255
            img.setRGB(x, y, (a shl 24) or (v shl 16) or (v shl 8) or 255)
        }
        return img
    }

    private fun withNoise(src: BufferedImage, amp: Int): BufferedImage {
        val rnd = Random(1)
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val p = src.getRGB(x, y)
            fun j(v: Int) = (v + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255)
            out.setRGB(x, y, (p and -0x1000000) or (j((p ushr 16) and 0xFF) shl 16) or
                    (j((p ushr 8) and 0xFF) shl 8) or j(p and 0xFF))
        }
        return out
    }

    private fun checker(cell: Int, lo: Int = 30, hi: Int = 220): BufferedImage {
        val img = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 200) for (x in 0 until 200) {
            val v = if ((x / cell + y / cell) % 2 == 0) lo else hi
            img.setRGB(x, y, (255 shl 24) or (v shl 16) or (v shl 8) or v)
        }
        return img
    }

    private fun probe(label: String, img: BufferedImage) {
        val solid = ImageStats.compositeOn(img, Color.WHITE)
        val mask = if (ImageStats.alphaProfile(img).opaqueRatio >= ImageStats.OPAQUE_RATIO_FOR_MASK)
            ImageStats.opaqueMask(img) else null
        val n0 = ImageStats.noise(solid, mask)
        val blurred = ImageStats.gaussianBlur(solid, 1.0)
        val n1 = ImageStats.noise(blurred, mask)
        val drop = if (n0 == 0.0) 0.0 else (n0 - n1) / n0
        val delta = ImageStats.deltaRgb(solid, blurred).mean
        println(
            "PROBE %-34s n0=%7.3f n1=%7.3f drop=%5.2f delta=%6.3f delta/n0=%5.2f"
                .format(label, n0, n1, drop, delta, if (n0 == 0.0) 0.0 else delta / n0)
        )
    }

    @Test
    fun probeSynthetic() {
        probe("干净斜向渐变", diagGradient())
        probe("渐变+抖动 amp=1", withNoise(diagGradient(), 1))
        probe("渐变+抖动 amp=3", withNoise(diagGradient(), 3))
        probe("渐变+抖动 amp=6", withNoise(diagGradient(), 6))
        probe("棋盘 cell=1(极高频)", checker(1))
        probe("棋盘 cell=3", checker(3))
        probe("棋盘 cell=8", checker(8))
    }

    @Test
    fun probeRealAssets() {
        val root = File(System.getProperty("squeeze.sampleRoot") ?: return)
        if (!root.isDirectory) return
        root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("png", "webp") }
            .filter { it.length() > 40 * 1024 && "build" !in it.path }
            .take(12)
            .forEach { f -> runCatching { probe(f.name.take(32), WebpCodec.read(f)) } }
    }
}
