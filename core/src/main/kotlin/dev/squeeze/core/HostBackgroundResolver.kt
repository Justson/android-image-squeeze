package dev.squeeze.core

import java.awt.Color
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * 解析一张图「身后是什么颜色」——BAKE_BACKGROUND 路线的前置条件。
 *
 * 做法：扫描所有 layout xml，找到引用该资源的元素，沿父链向上找最近一个带 background 的节点，
 * 把 @color/xxx 解析成实际色值。所有引用点得到同一个纯色才允许烘焙。
 *
 * 踩过的坑，都编码进这里了：
 *  1) @mipmap/x 和 @drawable/x 是两个不同的命名空间。字节相同也不是同一个资源 ——
 *     实测：某基础库里是 drawable/foo，
 *     业务库里是 mipmap/foo，误当重复删掉会导致 AAPT 报 resource not found。
 *  2) 根元素没有 background 时，底色来自 Activity 主题的 windowBackground，
 *     这里解析不了，必须返回 Unresolved 而不是猜白色。
 *  3) 半透明背景色(#80xxxxxx)不能当纯色烘焙。
 */
class HostBackgroundResolver(private val projectRoot: File) {

    sealed interface Result {
        /** 所有引用点的身后底色一致且为纯色，可以烘焙 */
        data class Solid(val color: Color, val hosts: List<String>) : Result
        /** 引用点之间底色不一致，禁止烘焙 */
        data class Conflict(val colors: List<Color>, val hosts: List<String>) : Result
        /** 解析不到（主题背景 / 背后是图片 / 没有布局引用），需人工确认 */
        data class Unresolved(val reason: String, val hosts: List<String>) : Result
    }

    private val colorTable: Map<String, String> by lazy { loadColors() }

    fun resolve(resourceName: String, resourceType: String): Result {
        val hits = mutableListOf<Pair<String, Any?>>()   // 布局名 -> Color / "NONSOLID" / null
        forEachLayout { file, doc ->
            val refs = listOf("@$resourceType/$resourceName")
            val parents = HashMap<Node, Node>()
            indexParents(doc.documentElement, parents)
            visit(doc.documentElement) { el ->
                val used = ANDROID_IMAGE_ATTRS.any { attr -> el.getAttributeNS(ANDROID_NS, attr) in refs }
                if (used) hits += file.name to nearestBackground(el, parents)
            }
        }
        if (hits.isEmpty()) return Result.Unresolved("没有布局引用（可能在代码里用）", emptyList())

        val hostNames = hits.map { it.first }.distinct()
        val values = hits.map { it.second }.distinct()
        val solids = values.filterIsInstance<Color>()
        return when {
            values.size == 1 && solids.size == 1 -> Result.Solid(solids.single(), hostNames)
            solids.size > 1 -> Result.Conflict(solids, hostNames)
            values.any { it == NON_SOLID } -> Result.Unresolved("背后是图片/非纯色背景", hostNames)
            else -> Result.Unresolved("根元素无 background，底色来自 Activity 主题", hostNames)
        }
    }

    /** 沿父链找最近一个能解析成纯色的 background；找不到返回 null */
    private fun nearestBackground(el: Element, parents: Map<Node, Node>): Any? {
        var node: Node? = parents[el]
        while (node is Element) {
            val raw = node.getAttributeNS(ANDROID_NS, "background")
            if (raw.isNotEmpty()) return parseColor(raw)
            node = parents[node]
        }
        return null
    }

    private fun parseColor(raw: String): Any? {
        val v = raw.trim()
        if (v.startsWith("#")) return hexToColor(v)
        COLOR_REF.matchEntire(v)?.let { m ->
            return colorTable[m.groupValues[1]]?.let { hexToColor(it) }
        }
        // @drawable/... @mipmap/... => 背后是图片，不是纯色
        return NON_SOLID
    }

    private fun hexToColor(hex: String): Any? {
        var h = hex.removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length == 8) {
            // #AARRGGBB：半透明背景不能当纯色烘焙
            if (!h.substring(0, 2).equals("FF", true)) return NON_SOLID
            h = h.substring(2)
        }
        if (h.length != 6) return null
        return runCatching { Color(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16)) }
            .getOrNull()
    }

    private fun loadColors(): Map<String, String> {
        val map = HashMap<String, String>()
        projectRoot.walkTopDown()
            .onEnter { "build" !in it.name }
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .forEach { f ->
                runCatching {
                    val doc = builder().parse(f)
                    visit(doc.documentElement) { el ->
                        if (el.tagName == "color") {
                            val n = el.getAttribute("name")
                            val t = el.textContent.trim()
                            if (n.isNotEmpty() && t.startsWith("#")) map.putIfAbsent(n, t)
                        }
                    }
                }
            }
        return map
    }

    private inline fun forEachLayout(body: (File, org.w3c.dom.Document) -> Unit) {
        projectRoot.walkTopDown()
            .onEnter { "build" !in it.name && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("layout") }
            .forEach { f -> runCatching { body(f, builder().parse(f)) } }
    }

    private fun builder() = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()

    private fun indexParents(node: Node, into: MutableMap<Node, Node>) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c is Element) {
                into[c] = node
                indexParents(c, into)
            }
        }
    }

    private inline fun visit(root: Element, crossinline body: (Element) -> Unit) {
        val stack = ArrayDeque<Element>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            body(e)
            val cs = e.childNodes
            for (i in 0 until cs.length) (cs.item(i) as? Element)?.let { stack.add(it) }
        }
    }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        private const val NON_SOLID = "NONSOLID"
        private val COLOR_REF = Regex("@(?:android:)?color/(.+)")
        private val ANDROID_IMAGE_ATTRS = listOf("src", "background", "srcCompat")

        /** 资源类型由所在目录决定：drawable* -> drawable, mipmap* -> mipmap */
        fun resourceTypeOf(file: File): String =
            if (file.parentFile.name.startsWith("mipmap")) "mipmap" else "drawable"

        fun resourceNameOf(file: File): String = file.nameWithoutExtension
    }
}
