package dev.squeeze.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(name = "AssetSqueezeSettings", storages = [Storage("assetSqueeze.xml")])
class SqueezeSettings : PersistentStateComponent<SqueezeSettings> {

    /** 留空则用随包二进制，再没有就找 PATH */
    var cwebpPath: String = ""

    /** KEEP_ALPHA 路线的 RGB 质量。90 在实测样本上是体积/画质的平衡点 */
    var keepAlphaQuality: Int = 90

    /**
     * BAKE_BACKGROUND 路线的质量。用 98 而不是 92：
     * 实测 q92 会让某渐变背景的色阶台阶从 2 涨到 4，q98 才与原图持平。
     */
    var bakeQuality: Int = 98

    /** 小于该体积的图直接跳过，省得为 1KB 的收益折腾 */
    var minSizeKb: Int = 20

    override fun getState() = this
    override fun loadState(state: SqueezeSettings) = XmlSerializerUtil.copyBean(state, this)

    companion object {
        fun of(project: Project): SqueezeSettings = project.service()
    }
}

class SqueezeConfigurable(private val project: Project) :
    BoundConfigurable("Asset Squeeze") {

    override fun createPanel() = panel {
        val s = SqueezeSettings.of(project)
        group("cwebp") {
            row("可执行文件路径：") {
                textField()
                    .bindText(s::cwebpPath)
                    .comment(
                        "留空则依次尝试：随插件分发的二进制 → PATH 上的 cwebp。<br>" +
                            "JVM 上没有纯 Java 的 WebP 编码器，编码必须借助 libwebp。"
                    )
            }
        }
        group("压缩质量") {
            row("硬边缘 alpha（RGB 有损 + alpha 无损）：") {
                intTextField(80..100).bindIntText(s::keepAlphaQuality)
            }
            row("渐变型 alpha（烘焙底色）：") {
                intTextField(80..100).bindIntText(s::bakeQuality)
                    .comment("建议不低于 98：实测 q92 会让平滑渐变的色阶台阶翻倍，产生可见色带。")
            }
            row("忽略小于（KB）：") {
                intTextField(0..10240).bindIntText(s::minSizeKb)
            }
        }
    }
}
