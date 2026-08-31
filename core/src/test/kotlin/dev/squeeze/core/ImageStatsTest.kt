package dev.squeeze.core

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 这些用例都是真实踩过的坑，别删。每个用例的注释写了当初错在哪。
 */
class ImageStatsTest {

    // ---------- 合成图工具 ----------

    /**
     * 斜向渐变。注意必须是斜的：noise() 测的是**水平**相邻像素差分，
     * 纯竖向渐变的横向差分恒为 0，会让用例假通过。
     * alphaGradient=true 时 alpha 从 255 线性降到 0（含真正的全透明行）。
     */
    private fun gradient(w: Int = 200, h: Int = 200, alphaGradient: Boolean = false): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until h) for (x in 0 until w) {
            val v = 255 - (x + y) * 80 / (w + h)
            val a = if (alphaGradient) (255 - y * 255 / (h - 1)) else 255
            img.setRGB(x, y, (a shl 24) or (v shl 16) or (v shl 8) or 255)
        }
        return img
    }

    /** 模拟被量化成 N 级的 alpha —— 也就是有损 alpha 编码产生的色带 */
    private fun quantizeAlpha(src: BufferedImage, levels: Int): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        val step = 255.0 / (levels - 1)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val p = src.getRGB(x, y)
            val a = ((p ushr 24) and 0xFF)
            val qa = ((a / step).roundToInt() * step).roundToInt().coerceIn(0, 255)
            out.setRGB(x, y, (qa shl 24) or (p and 0xFFFFFF))
        }
        return out
    }

    private fun withNoise(src: BufferedImage, amp: Int, seed: Int = 1): BufferedImage {
        val rnd = Random(seed)
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val p = src.getRGB(x, y)
            fun j(v: Int) = (v + rnd.nextInt(-amp, amp + 1)).coerceIn(0, 255)
            out.setRGB(
                x, y,
                (p and -0x1000000) or (j((p ushr 16) and 0xFF) shl 16) or
                        (j((p ushr 8) and 0xFF) shl 8) or j(p and 0xFF)
            )
        }
        return out
    }

    // ---------- 用例 ----------

    /**
     * 坑 1：有损 alpha 会把平滑 alpha 渐变量化成阶梯 -> 色带。
     * 当初误判为「对渐变型 alpha 安全」，正好说反了。banding 必须能抓到。
     */
    @Test
    fun `色带检测能抓到 alpha 被量化`() {
        val src = gradient(alphaGradient = true)
        val before = ImageStats.banding(src, 'A')
        val quantized = quantizeAlpha(src, 16)
        val after = ImageStats.banding(quantized, 'A')

        assertTrue(before.levels > 100, "原图 alpha 应有丰富色阶，实际 ${before.levels}")
        assertTrue(after.levels <= 20, "量化后色阶应塌陷，实际 ${after.levels}")
        assertTrue(after.maxStep > before.maxStep * 3, "台阶应显著变高")
        assertFalse(ImageStats.bandingOk(before, after), "bandingOk 必须判定为不通过")
    }

    /**
     * 坑 2：逐像素 ΔRGB 检测不到色带。
     * 当初就是因为只看 ΔRGB(平均 0.46/最大 9)，误以为没问题就合入了。
     * 这个用例固化「ΔRGB 会漏判」这个事实，提醒两个指标必须并用。
     */
    @Test
    fun `逐像素误差检测不到色带 所以必须配合 banding`() {
        val src = gradient(alphaGradient = true)
        val quantized = quantizeAlpha(src, 16)
        val white = Color.WHITE
        val d = ImageStats.deltaRgb(
            ImageStats.compositeOn(src, white),
            ImageStats.compositeOn(quantized, white)
        )
        // ΔRGB 看起来很小，但上一个用例证明色带是明显的
        assertTrue(d.mean < 10, "ΔRGB 均值本来就小，实际 ${d.mean}")
        assertFalse(
            ImageStats.bandingOk(ImageStats.banding(src, 'A'), ImageStats.banding(quantized, 'A')),
            "同一组数据 banding 必须判定失败"
        )
    }

    /**
     * 坑 3：透明区的 RGB 是未定义的垃圾值，不合成底色就测噪点会严重虚高。
     * 实测同一张 Figma 导出图：透明区变黑测得 7.37，合成白底只有 0.47。
     */
    @Test
    fun `噪点必须在合成底色后测量`() {
        val clean = gradient(alphaGradient = true)
        // 下 1/3 完全透明，且 RGB 填随机垃圾 —— 真实 PNG 就长这样。
        // 注意透明区必须有一定面积：noise() 逐行采样且不含最后一行，
        // 只让最后一行透明的话根本采不到，用例会假通过。
        val dirty = BufferedImage(clean.width, clean.height, BufferedImage.TYPE_INT_ARGB)
        val rnd = Random(7)
        val transparentFrom = clean.height * 2 / 3
        for (y in 0 until clean.height) for (x in 0 until clean.width) {
            val p = clean.getRGB(x, y)
            dirty.setRGB(x, y, if (y >= transparentFrom) rnd.nextInt() and 0x00FFFFFF else p)
        }
        val naive = ImageStats.noise(stripAlpha(dirty))                        // 错误做法
        val correct = ImageStats.noise(ImageStats.compositeOn(dirty, Color.WHITE))  // 正确做法
        assertTrue(naive > correct * 2, "不合成时噪点应明显虚高：naive=$naive correct=$correct")
    }

    /** 抖动噪点应判为 DITHER；真实纹理应判为 TEXTURE */
    @Test
    fun `能区分抖动噪点和真实纹理`() {
        val dithered = withNoise(gradient(), amp = 3)
        assertEquals(ImageStats.NoiseKind.DITHER, ImageStats.noiseKind(dithered).kind)

        // 真实纹理：高频且高幅度的棋盘/条纹结构，模糊后画面显著改变
        val texture = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 200) for (x in 0 until 200) {
            val v = if ((x / 3 + y / 3) % 2 == 0) 30 else 220
            texture.setRGB(x, y, (255 shl 24) or (v shl 16) or (v shl 8) or v)
        }
        assertEquals(ImageStats.NoiseKind.TEXTURE, ImageStats.noiseKind(texture).kind)
    }

    /** 干净渐变不应被误判成有噪点 */
    @Test
    fun `干净渐变判定为 CLEAN`() {
        assertEquals(ImageStats.NoiseKind.CLEAN, ImageStats.noiseKind(gradient()).kind)
    }

    /**
     * 坑 4：噪点结论会随宿主底色变化。
     * 不透明像素足够多时必须走 mask 分支，结论与底色无关(backgroundDependent=false)。
     */
    @Test
    fun `不透明像素足够时结论与底色无关`() {
        val opaque = gradient(alphaGradient = false)
        val r = ImageStats.noiseKind(opaque)
        assertFalse(r.backgroundDependent, "全不透明的图不该标记为依赖底色")
    }

    /** 硬边缘 alpha 与渐变型 alpha 的判定 */
    @Test
    fun `alpha 形态判定决定压缩路线`() {
        assertTrue(ImageStats.alphaProfile(gradient(alphaGradient = false)).isHardEdged)
        assertFalse(ImageStats.alphaProfile(gradient(alphaGradient = true)).isHardEdged)
    }

    private fun stripAlpha(src: BufferedImage): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until src.height) for (x in 0 until src.width) {
            out.setRGB(x, y, src.getRGB(x, y) and 0xFFFFFF)
        }
        return out
    }
}
