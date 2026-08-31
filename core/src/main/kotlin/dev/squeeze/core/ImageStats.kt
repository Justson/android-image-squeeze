package dev.squeeze.core

import java.awt.Color
import java.awt.image.BufferedImage

/**
 * 图像度量。所有阈值都来自真实素材上的实测，注释里给了参考值，改阈值前先看 core 的单测。
 *
 * 三个指标各管一件事，缺一不可：
 *  - [noiseKind]  区分「抖动噪点」和「真实纹理」：决定能不能去噪 / 降采样
 *  - [banding]    检测量化色带：ΔRGB 抓不到的结构性失真
 *  - [deltaRgb]   逐像素误差：抓得到偏色，抓不到色带
 */
object ImageStats {

    /** alpha >= 该值视为完全不透明 */
    const val OPAQUE_THRESHOLD = 250

    /** 不透明像素占比超过它，就能只在不透明区测量，从而与宿主底色无关 */
    const val OPAQUE_RATIO_FOR_MASK = 0.05

    /** 半透明像素占比 <= 它，视为「硬边缘 alpha」，可走 KEEP_ALPHA 路线 */
    const val HARD_ALPHA_SEMI_RATIO = 0.02

    data class AlphaProfile(
        /** alpha < 250 的像素占比 */
        val transparentRatio: Double,
        /** alpha 在 1..249 的像素占比。这是选择压缩路线的唯一依据 */
        val semiRatio: Double,
        val opaqueRatio: Double,
    ) {
        val isHardEdged: Boolean get() = semiRatio <= HARD_ALPHA_SEMI_RATIO
    }

    /**
     * 色阶剖面。沿中轴线统计某通道的取值分布。
     *
     * 平滑渐变应为「色阶多、台阶小(1~2)、平台短」；被量化后变成「色阶少、台阶大、平台长」，
     * 那就是肉眼看到的条纹。实测某 1125x711 渐变背景图的 alpha 通道：
     *   原图         色阶 253  台阶  2  平台 11
     *   alpha_q=70   色阶  16  台阶 18  平台 81   <- 明显条纹
     */
    data class Banding(val levels: Int, val maxStep: Int, val longestRun: Int)

    enum class NoiseKind {
        /** 噪点 < 1.0，去噪没有意义 */
        CLEAN,

        /** 纯高频、像素间不相关；一模糊就消失且画面几乎不变。可去噪 */
        DITHER,

        /** 多尺度结构的真实细节；去噪和降采样都会毁图 */
        TEXTURE,
    }

    data class NoiseReport(
        val value: Double,
        val kind: NoiseKind,
        /**
         * true 表示该结论随宿主底色变化，需人工确认。
         * 只有「几乎全是半透明」的图才可能为 true —— 这类图无法在不知道底色的前提下定论。
         */
        val backgroundDependent: Boolean,
    )

    fun alphaProfile(img: BufferedImage): AlphaProfile {
        if (!img.colorModel.hasAlpha()) return AlphaProfile(0.0, 0.0, 1.0)
        var transparent = 0L
        var semi = 0L
        var total = 0L
        forEachPixel(img) { _, _, argb ->
            val a = (argb ushr 24) and 0xFF
            total++
            if (a < OPAQUE_THRESHOLD) transparent++
            if (a in 1 until OPAQUE_THRESHOLD) semi++
        }
        if (total == 0L) return AlphaProfile(0.0, 0.0, 1.0)
        val t = transparent.toDouble() / total
        return AlphaProfile(t, semi.toDouble() / total, 1.0 - t)
    }

    /**
     * 相邻像素平均绝对差分（取 RGB 三通道最大差）。平滑渐变趋近 0。
     *
     * mask 为 null 时统计全图；否则只统计 mask 中相邻两点都为 true 的像素对。
     *
     * ⚠️ 传入的必须是已合成到底色的图。直接拿带 alpha 的原图测会严重虚高：
     * PNG 透明区的 RGB 是未定义值（通常是随机噪声），不可见却会污染统计。
     * 实测某设计工具导出的 PNG：透明区变黑后测得 7.37，先合成白底再测只有 0.47。
     */
    fun noise(solid: BufferedImage, mask: BooleanArray? = null, samples: Int = 200): Double {
        val w = solid.width
        val h = solid.height
        val sx = maxOf(1, w / samples)
        val sy = maxOf(1, h / samples)
        var total = 0L
        var count = 0L
        var y = 0
        while (y < h - 1) {
            var x = 0
            while (x < w - 1) {
                if (mask == null || (mask[y * w + x] && mask[y * w + x + 1])) {
                    total += channelDiff(solid.getRGB(x, y), solid.getRGB(x + 1, y)).toLong()
                    count++
                }
                x += sx
            }
            y += sy
        }
        return if (count == 0L) 0.0 else total.toDouble() / count
    }

    /**
     * 判定噪点类型，并说明结论是否依赖宿主底色。
     *
     * 底色问题的由来：合成结果 out = a*rgb + (1-a)*bg，其相邻差分含 (rgb-bg)*d(alpha) 一项，
     * 底色离图像颜色越远，alpha 的起伏被放大得越厉害，会被误判成噪点。
     * 实测某素材在白/灰底判为 CLEAN、黑底判为 TEXTURE。
     *
     * 对策：
     *  1) 不透明像素 >= 5%：只在这些像素上测。它们的 RGB 就是最终显示值，与底色无关，结论精确。
     *  2) 几乎全半透明（渐变遮罩类）：白/灰/黑三种底色各测一次取最坏值，结论不一致则置位
     *     backgroundDependent，提示人工确认。
     */
    fun noiseKind(img: BufferedImage, samples: Int = 200): NoiseReport {
        val profile = alphaProfile(img)
        if (profile.opaqueRatio >= OPAQUE_RATIO_FOR_MASK) {
            val mask = opaqueMask(img)
            val solid = compositeOn(img, Color.WHITE)   // 不透明处结果与底色无关
            val n = noise(solid, mask, samples)
            return NoiseReport(n, judge(n, solid, mask, samples), false)
        }
        val kinds = LinkedHashSet<NoiseKind>()
        var worstValue = 0.0
        var worstKind = NoiseKind.CLEAN
        for (bg in listOf(Color.WHITE, Color(128, 128, 128), Color.BLACK)) {
            val solid = compositeOn(img, bg)
            val n = noise(solid, null, samples)
            val k = judge(n, solid, null, samples)
            kinds += k
            if (n > worstValue) {
                worstValue = n
                worstKind = k
            }
        }
        return NoiseReport(worstValue, worstKind, kinds.size > 1)
    }

    /** 模糊后噪点降幅超过它才可能是抖动 */
    const val DITHER_DROP_MIN = 0.6

    /**
     * 模糊带来的画面平均变化上限。必须是**绝对**阈值。
     * 早期版本用的是相对阈值 delta < n0*0.5，实测在抖动和真实纹理之间严重重叠，
     * 会把 amp=3 的抖动误判成纹理。
     */
    const val DITHER_DELTA_MAX = 8.0

    /**
     * 阈值标定数据（见 ThresholdProbe，样本 = 合成图 + 真实项目素材）：
     *   抖动 amp=1/3/6      drop 0.66 / 0.80 / 0.84    delta 1.7 / 2.5 / 4.0
     *   真实素材 x12         drop -0.26 ~ 0.34          delta 0.7 ~ 6.1
     *   棋盘 cell=1(病态)    drop 1.00                  delta 95.0   <- 靠 delta 排除
     *   棋盘 cell=3          drop 0.60                  delta 69.0   <- 靠 delta 排除
     * drop 能干净分开抖动与真实纹理；delta 用来排除病态高频图案。
     */
    private fun judge(n0: Double, solid: BufferedImage, mask: BooleanArray?, samples: Int): NoiseKind {
        if (n0 < 1.0) return NoiseKind.CLEAN
        val blurred = gaussianBlur(solid, 1.0)
        val n1 = noise(blurred, mask, samples)
        val delta = deltaRgb(solid, blurred, samples).mean
        val drop = (n0 - n1) / n0
        // 抖动：一模糊就没了(降幅大)，且画面几乎没变(delta 小)
        return if (drop > DITHER_DROP_MIN && delta < DITHER_DELTA_MAX) NoiseKind.DITHER
        else NoiseKind.TEXTURE
    }

    /**
     * 沿中轴线统计通道色阶。band: 'A' / 'R' / 'G' / 'B'。
     * 对 alpha 用 'A'（与底色无关）；对 RGB 用 'G'（人眼最敏感的通道）。
     */
    fun banding(img: BufferedImage, band: Char = 'A'): Banding {
        val h = img.height
        val x = img.width / 2
        val col = IntArray(h) { y ->
            val argb = img.getRGB(x, it(y))
            when (band) {
                'A' -> (argb ushr 24) and 0xFF
                'R' -> (argb ushr 16) and 0xFF
                'B' -> argb and 0xFF
                else -> (argb ushr 8) and 0xFF
            }
        }
        val levels = col.toSortedSet().toIntArray()
        var maxStep = 0
        for (i in 0 until levels.size - 1) maxStep = maxOf(maxStep, levels[i + 1] - levels[i])
        var longest = 1
        var cur = 1
        for (i in 1 until col.size) {
            if (col[i] == col[i - 1]) cur++ else { longest = maxOf(longest, cur); cur = 1 }
        }
        return Banding(levels.size, maxStep, maxOf(longest, cur))
    }

    /** 台阶没明显变高、色阶没塌掉，才算没引入色带。 */
    fun bandingOk(before: Banding, after: Banding, stepSlack: Int = 1, levelRatio: Double = 0.7): Boolean =
        after.maxStep <= before.maxStep + stepSlack && after.levels >= before.levels * levelRatio

    data class Delta(val mean: Double, val max: Int)

    /**
     * 逐像素最大通道差。
     * ⚠️ 它检测不到色带 —— 色带每级台阶只差几个 level，均值/最大值都很小，
     * 但在空间上形成大片等高线，人眼极敏感。必须配合 [banding] 一起看。
     */
    fun deltaRgb(a: BufferedImage, b: BufferedImage, samples: Int = 150): Delta {
        require(a.width == b.width && a.height == b.height) { "尺寸不一致，无法逐像素比较" }
        val sx = maxOf(1, a.width / samples)
        val sy = maxOf(1, a.height / samples)
        var total = 0L
        var count = 0L
        var max = 0
        var y = 0
        while (y < a.height) {
            var x = 0
            while (x < a.width) {
                val d = channelDiff(a.getRGB(x, y), b.getRGB(x, y))
                total += d
                count++
                if (d > max) max = d
                x += sx
            }
            y += sy
        }
        return Delta(if (count == 0L) 0.0 else total.toDouble() / count, max)
    }

    fun compositeOn(img: BufferedImage, bg: Color): BufferedImage {
        val out = BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.color = bg
        g.fillRect(0, 0, img.width, img.height)
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return out
    }

    fun opaqueMask(img: BufferedImage): BooleanArray {
        val mask = BooleanArray(img.width * img.height)
        forEachPixel(img) { x, y, argb ->
            mask[y * img.width + x] = ((argb ushr 24) and 0xFF) >= OPAQUE_THRESHOLD
        }
        return mask
    }

    /** 可分离高斯模糊，只用于判定，不追求极致性能。 */
    fun gaussianBlur(src: BufferedImage, sigma: Double): BufferedImage {
        val radius = maxOf(1, Math.ceil(sigma * 3).toInt())
        val kernel = DoubleArray(radius * 2 + 1)
        var sum = 0.0
        for (i in kernel.indices) {
            val d = (i - radius).toDouble()
            kernel[i] = Math.exp(-d * d / (2 * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum
        return blurPass(blurPass(src, kernel, radius, true), kernel, radius, false)
    }

    private fun blurPass(src: BufferedImage, k: DoubleArray, r: Int, horizontal: Boolean): BufferedImage {
        val w = src.width
        val h = src.height
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rr = 0.0; var gg = 0.0; var bb = 0.0
                for (i in k.indices) {
                    val o = i - r
                    val sxp = if (horizontal) (x + o).coerceIn(0, w - 1) else x
                    val syp = if (horizontal) y else (y + o).coerceIn(0, h - 1)
                    val p = src.getRGB(sxp, syp)
                    rr += k[i] * ((p ushr 16) and 0xFF)
                    gg += k[i] * ((p ushr 8) and 0xFF)
                    bb += k[i] * (p and 0xFF)
                }
                out.setRGB(x, y, (rr.toInt().coerceIn(0, 255) shl 16) or
                        (gg.toInt().coerceIn(0, 255) shl 8) or bb.toInt().coerceIn(0, 255))
            }
        }
        return out
    }

    private inline fun it(v: Int) = v

    private fun channelDiff(p1: Int, p2: Int): Int {
        val dr = Math.abs(((p1 ushr 16) and 0xFF) - ((p2 ushr 16) and 0xFF))
        val dg = Math.abs(((p1 ushr 8) and 0xFF) - ((p2 ushr 8) and 0xFF))
        val db = Math.abs((p1 and 0xFF) - (p2 and 0xFF))
        return maxOf(dr, dg, db)
    }

    private inline fun forEachPixel(img: BufferedImage, body: (Int, Int, Int) -> Unit) {
        for (y in 0 until img.height) for (x in 0 until img.width) body(x, y, img.getRGB(x, y))
    }
}
