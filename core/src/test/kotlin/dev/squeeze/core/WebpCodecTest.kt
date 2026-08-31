package dev.squeeze.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * 端到端验证编码链路。需要能找到 cwebp，找不到就跳过（CI 上通过
 * -Dsqueeze.cwebp=/path/to/cwebp 指定，或让它在 PATH 上）。
 *
 * 这里验证的是「两条路线的核心承诺」是否真的成立，不是验证 cwebp 本身：
 *   KEEP_ALPHA      -> alpha 逐像素一致
 *   BAKE_BACKGROUND -> 在宿主底色上与原图几乎无差
 *   alpha_quality 调低 -> 必然出色带（这是我们拒绝这条路的依据）
 */
class WebpCodecTest {

    private fun codecOrSkip(): WebpCodec {
        val configured = System.getProperty("squeeze.cwebp")?.takeIf { it.isNotBlank() }
        val codec = runCatching { WebpCodec.locate(configured, null) }.getOrNull()
        assumeTrue(codec != null, "找不到 cwebp，跳过（用 -Dsqueeze.cwebp=... 指定）")
        return codec!!
    }

    /** 渐变 alpha + 斜向 RGB 渐变，尺寸够大才能体现 alpha 平面的编码代价 */
    private fun gradientWithAlpha(w: Int = 600, h: Int = 400): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) {
            val r = 255 - x * 120 / w
            val g = 200 - y * 100 / h
            val a = 255 - y * 255 / (h - 1)
            img.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or 255)
        }
        return img
    }

    /** 硬边缘 alpha：一个实心圆，圆外全透明 */
    private fun hardEdgedIcon(size: Int = 400): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val c = size / 2.0
        for (y in 0 until size) for (x in 0 until size) {
            val inside = (x - c) * (x - c) + (y - c) * (y - c) < (c * 0.8) * (c * 0.8)
            val v = 40 + (x * 180 / size)
            img.setRGB(x, y, if (inside) (255 shl 24) or (v shl 16) or (v shl 8) or 200 else 0)
        }
        return img
    }

    @Test
    fun `cwebp 可用且能编出合法 webp`() {
        val codec = codecOrSkip()
        val bytes = codec.encode(hardEdgedIcon(), WebpCodec.Options(quality = 90))
        assertTrue(bytes.size > 100, "编码结果过小：${bytes.size}")
        // RIFF....WEBP
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WEBP", String(bytes, 8, 4))
        val decoded = WebpCodec.read(bytes)
        assertEquals(400, decoded.width)
    }

    /**
     * KEEP_ALPHA 路线的核心承诺：alpha_quality=100 时 alpha 通道逐像素不变。
     * 这是它「不需要知道宿主底色、图仍可复用」的前提。
     */
    @Test
    fun `KEEP_ALPHA 路线保证 alpha 逐像素一致`() {
        val codec = codecOrSkip()
        val src = hardEdgedIcon()
        val bytes = codec.encode(src, WebpCodec.Options(quality = 85, alphaQuality = 100))
        val out = WebpCodec.read(bytes)
        var diff = 0
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val a1 = (src.getRGB(x, y) ushr 24) and 0xFF
            val a2 = (out.getRGB(x, y) ushr 24) and 0xFF
            if (a1 != a2) diff++
        }
        assertEquals(0, diff, "alpha 应逐像素一致，实际有 $diff 个像素不同")
    }

    /**
     * 拒绝有损 alpha 的依据：alpha_quality 调低必然把平滑 alpha 量化成阶梯。
     * 这个用例一旦变绿(即不再出色带)，说明 libwebp 行为变了，两条路线的划分要重新评估。
     */
    @Test
    fun `调低 alpha_quality 必然产生色带`() {
        val codec = codecOrSkip()
        val src = gradientWithAlpha()
        val before = ImageStats.banding(src, 'A')

        val lossyAlpha = WebpCodec.read(
            codec.encode(src, WebpCodec.Options(quality = 88, alphaQuality = 70))
        )
        val after = ImageStats.banding(lossyAlpha, 'A')

        assertTrue(
            !ImageStats.bandingOk(before, after),
            "alpha_q=70 应被判定为出色带；before=$before after=$after"
        )
        assertTrue(after.levels < before.levels, "色阶应塌陷：${before.levels} -> ${after.levels}")
    }

    /** BAKE_BACKGROUND 路线：在宿主底色上与原图几乎无差 */
    @Test
    fun `BAKE_BACKGROUND 路线在宿主底色上保真`() {
        val codec = codecOrSkip()
        val src = gradientWithAlpha()
        val host = Color(0xF0, 0xF6, 0xFB)
        val ref = ImageStats.compositeOn(src, host)
        val baked = WebpCodec.read(codec.encode(ref, WebpCodec.Options(quality = 98)))
        val d = ImageStats.deltaRgb(ref, baked)
        assertTrue(d.mean < 2.0, "烘焙后平均 ΔRGB 应很小，实际 ${d.mean}")
        assertTrue(d.max <= 8, "烘焙后最大 ΔRGB 应可控，实际 ${d.max}")
    }

    /**
     * Analyzer 的 route 同时编码了两件事：**该走哪条路线** 和 **值不值得压**。
     * 后者由 minSavingRatio 门限决定，省得不够多就返回 NONE。
     * 这里把门限放到 1.0 以隔离掉「值不值得」，单独验证分流是否正确。
     */
    @Test
    fun `Analyzer 根据 alpha 形态和宿主底色选择安全路线`() {
        val codec = codecOrSkip()
        val analyzer = Analyzer(codec)
        val dir = File(System.getProperty("java.io.tmpdir"), "squeeze-test").apply { mkdirs() }

        val iconFile = File(dir, "icon.png")
        javax.imageio.ImageIO.write(hardEdgedIcon(), "png", iconFile)
        val icon = analyzer.analyze(iconFile, minSavingRatio = 1.0)
        assertTrue(icon.alpha.isHardEdged, "实心圆图标应判为硬边缘 alpha")
        assertEquals(CompressionRoute.KEEP_ALPHA, icon.route)

        val gradFile = File(dir, "gradient.png")
        javax.imageio.ImageIO.write(gradientWithAlpha(), "png", gradFile)
        val safeGrad = analyzer.analyze(gradFile, minSavingRatio = 1.0)
        assertTrue(!safeGrad.alpha.isHardEdged, "渐变遮罩应判为渐变型 alpha")
        assertEquals(CompressionRoute.KEEP_ALPHA, safeGrad.route)
        assertTrue(
            safeGrad.warnings.any { "回退为保留 alpha" in it },
            "底色未知时应说明安全回退，实际 ${safeGrad.warnings}"
        )
        val safeOut = analyzer.compress(gradFile, safeGrad.route, background = null, quality = 90)
        assertTrue(safeOut.alphaPreserved, "安全回退必须逐像素保留 alpha")
        assertTrue(safeOut.bandingOk, "安全回退不应引入新增色带")

        val grad = analyzer.analyze(
            gradFile,
            minSavingRatio = 1.0,
            hostBackground = Color(0xF0, 0xF6, 0xFB),
        )
        assertTrue(!grad.alpha.isHardEdged, "渐变遮罩应判为渐变型 alpha")
        assertEquals(CompressionRoute.BAKE_BACKGROUND, grad.route)
        assertTrue(
            grad.warnings.any { "宿主底色" in it },
            "渐变型 alpha 必须带宿主底色警告，实际 ${grad.warnings}"
        )

        iconFile.delete(); gradFile.delete()
    }

    /** 省得不够多时应返回 NONE —— 避免为了 1.1x 的收益做有损叠有损 */
    @Test
    fun `收益不足时不建议压缩`() {
        val codec = codecOrSkip()
        val analyzer = Analyzer(codec)
        val dir = File(System.getProperty("java.io.tmpdir"), "squeeze-test").apply { mkdirs() }
        val f = File(dir, "small.png")
        javax.imageio.ImageIO.write(hardEdgedIcon(64), "png", f)
        // 一张已经很小很规整的图，要求 10x 收益必然达不到
        assertEquals(CompressionRoute.NONE, analyzer.analyze(f, minSavingRatio = 10.0).route)
        f.delete()
    }

    @Test
    fun `assets 与九宫格必须被排除`() {
        val analyzer = Analyzer(codecOrSkip())
        assertTrue(analyzer.isExcluded(File("app/src/main/assets/lut/face_white.png")))
        assertTrue(analyzer.isExcluded(File("app/src/main/res/drawable/bg.9.png")))
        assertTrue(analyzer.isExcluded(File("app/build/intermediates/foo.png")))
        assertTrue(!analyzer.isExcluded(File("app/src/main/res/drawable-xxhdpi/icon.png")))
    }
}
