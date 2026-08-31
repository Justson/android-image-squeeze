package dev.squeeze.core

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File

/**
 * 压缩路线。alpha 的形态决定首选路线；宿主底色不可靠时必须回退到安全路线。
 *
 * 这是本工具的核心价值 —— Android Studio 自带的 "Convert to WebP" 只会无脑转，
 * 不会告诉你哪张图转了会出色带、哪张图不该丢 alpha。
 */
enum class CompressionRoute {
    /**
     * 硬边缘 alpha（alpha 只有 0/255）：RGB 有损 + alpha 无损。
     * 二值通道不存在量化色带问题，所以不需要知道宿主背景色，图也仍可复用。
     * 实测 4~21x。这是最安全、适用面最广的一条。
     */
    KEEP_ALPHA,

    /**
     * 渐变型 alpha：合成到宿主底色、丢掉 alpha。
     * 这类图体积大头通常在 alpha 平面上（有损 WebP 默认 alpha 无损编码，
     * 存一整片连续渐变代价较高）。烘焙实测 8~24x，但前提是这张图只在一种已知纯色
     * 背景上使用；底色不可靠且保留 alpha 仍有收益时，必须回退到 KEEP_ALPHA。
     */
    BAKE_BACKGROUND,

    /** 已经足够小或压不动，不值得动 */
    NONE,
}

data class AssetReport(
    val file: File,
    val width: Int,
    val height: Int,
    val currentBytes: Long,
    val alpha: ImageStats.AlphaProfile,
    val alphaBanding: ImageStats.Banding,
    val noise: ImageStats.NoiseReport,
    val route: CompressionRoute,
    /** 该路线下的预估体积 */
    val estimatedBytes: Long,
    /** 降采样一半再放大回来的最大通道差；<=5 才可能考虑降采样 */
    val downscaleMaxDelta: Int,
    val warnings: List<String>,
) {
    val ratio: Double get() = if (estimatedBytes <= 0) 1.0 else currentBytes.toDouble() / estimatedBytes
    val savedBytes: Long get() = (currentBytes - estimatedBytes).coerceAtLeast(0)
    /**
     * 纹理型素材禁止降采样：平均 ΔRGB 会被大片平坦区稀释，必须看最大值。
     * 且宿主 ImageView 若是 wrap_content + fitXY，显示高度取自位图固有尺寸，缩了会让布局变矮。
     */
    val downscaleSafe: Boolean
        get() = downscaleMaxDelta <= 5 && noise.kind != ImageStats.NoiseKind.TEXTURE
}

class Analyzer(private val codec: WebpCodec) {

    /** 这些目录下的文件不是 UI 图片，绝不能压 */
    fun isExcluded(file: File): Boolean {
        val p = file.path.replace(File.separatorChar, '/')
        return "/build/" in p ||
                // assets/ 下常放 SDK 直接读取的数据文件而非 UI 图片。
                // 实测踩过：美颜 SDK 的查找表(LUT)就是 PNG 且放在 assets 下，
                // 有损压缩会直接改变滤镜输出
                "/assets/" in p ||
                // 九宫格有拉伸标记，不能按普通图处理
                file.name.endsWith(".9.png", ignoreCase = true)
    }

    fun analyze(
        file: File,
        minSavingRatio: Double = 1.5,
        hostBackground: Color? = null,
    ): AssetReport {
        val img = WebpCodec.read(file)
        val cur = file.length()
        val alpha = ImageStats.alphaProfile(img)
        val alphaBanding = ImageStats.banding(img, 'A')
        val noise = ImageStats.noiseKind(img)
        val warnings = mutableListOf<String>()

        val keepAlphaBytes = codec.encodedSize(
            img, WebpCodec.Options(quality = 90, alphaQuality = 100)
        ).toLong()
        val bakedBytes = codec.encodedSize(
            ImageStats.compositeOn(img, hostBackground ?: Color.WHITE), WebpCodec.Options(quality = 98)
        ).toLong()
        val keepAlphaWorthwhile = cur.toDouble() / keepAlphaBytes >= minSavingRatio
        val bakeWorthwhile = cur.toDouble() / bakedBytes >= 3.0

        val route: CompressionRoute
        val estimated: Long
        if (alpha.isHardEdged) {
            route = if (keepAlphaWorthwhile)
                CompressionRoute.KEEP_ALPHA else CompressionRoute.NONE
            estimated = keepAlphaBytes
        } else {
            when {
                hostBackground != null && bakeWorthwhile -> {
                    route = CompressionRoute.BAKE_BACKGROUND
                    estimated = bakedBytes
                    warnings += "渐变型 alpha：已确认唯一宿主底色，可安全烘焙"
                }
                keepAlphaWorthwhile -> {
                    route = CompressionRoute.KEEP_ALPHA
                    estimated = keepAlphaBytes
                    warnings += if (hostBackground == null) {
                        "渐变型 alpha：宿主底色未确定，已回退为保留 alpha"
                    } else {
                        "渐变型 alpha：烘焙收益不足，已改用保留 alpha"
                    }
                }
                else -> {
                    route = CompressionRoute.NONE
                    estimated = keepAlphaBytes
                    if (hostBackground == null && bakeWorthwhile) {
                        warnings += "渐变型 alpha：宿主底色未确定，烘焙路线已禁用；保留 alpha 收益不足"
                    }
                }
            }
        }

        if (noise.backgroundDependent) {
            warnings += "噪点判定随宿主底色变化，需人工确认"
        }
        if (file.extension.equals("png", true)) {
            warnings += "PNG 源：替换时改名为 .webp 并删除原 .png（资源引用不含扩展名）"
        }

        val half = scale(img, img.width / 2, img.height / 2)
        val back = scale(half, img.width, img.height)
        val dmax = ImageStats.deltaRgb(
            ImageStats.compositeOn(img, Color.WHITE),
            ImageStats.compositeOn(back, Color.WHITE)
        ).max

        return AssetReport(
            file, img.width, img.height, cur, alpha, alphaBanding, noise,
            route, estimated, dmax, warnings
        )
    }

    /**
     * 按路线产出压缩结果。
     * @param background BAKE_BACKGROUND 路线必须给出宿主底色；KEEP_ALPHA 路线忽略该参数
     */
    fun compress(file: File, route: CompressionRoute, background: Color?, quality: Int): Result {
        val img = WebpCodec.read(file)
        return when (route) {
            CompressionRoute.KEEP_ALPHA -> {
                val before = ImageStats.banding(img, 'G')
                val bytes = codec.encode(img, WebpCodec.Options(quality = quality, alphaQuality = 100))
                val after = WebpCodec.read(bytes)
                Result(
                    bytes = bytes,
                    alphaPreserved = alphaBytes(img).contentEquals(alphaBytes(after)),
                    bandingOk = ImageStats.bandingOk(before, ImageStats.banding(after, 'G')),
                    renameToWebp = !file.extension.equals("webp", true),
                )
            }
            CompressionRoute.BAKE_BACKGROUND -> {
                val bg = requireNotNull(background) { "烘焙路线必须提供宿主底色" }
                val ref = ImageStats.compositeOn(img, bg)
                val before = ImageStats.banding(ref, 'G')
                val bytes = codec.encode(ref, WebpCodec.Options(quality = quality))
                val after = WebpCodec.read(bytes)
                Result(
                    bytes = bytes,
                    alphaPreserved = true,       // 本就要丢掉 alpha
                    bandingOk = ImageStats.bandingOk(before, ImageStats.banding(after, 'G')),
                    renameToWebp = !file.extension.equals("webp", true),
                )
            }
            CompressionRoute.NONE -> error("NONE 路线不应调用 compress")
        }
    }

    data class Result(
        val bytes: ByteArray,
        /** alpha 通道是否逐像素一致 */
        val alphaPreserved: Boolean,
        /** 是否没有引入新的色带 */
        val bandingOk: Boolean,
        val renameToWebp: Boolean,
    )

    private fun alphaBytes(img: BufferedImage): ByteArray {
        val out = ByteArray(img.width * img.height)
        var i = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            out[i++] = (((img.getRGB(x, y) ushr 24) and 0xFF)).toByte()
        }
        return out
    }

    private fun scale(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(maxOf(1, w), maxOf(1, h), BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.drawImage(src, 0, 0, out.width, out.height, null)
        g.dispose()
        return out
    }
}
