package cloud.jjoon.eolpoc.collection

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * HTML을 AI에 보낼 텍스트로 바꾼다. 표의 행은 "셀 | 셀 | 셀" 한 줄로 만들어
 * AI가 버전과 날짜의 대응을 읽고, 근거 문구로 행 전체를 인용할 수 있게 한다.
 */
object HtmlToText {
    fun convert(html: String, selector: String?): String {
        val doc = Jsoup.parse(html)
        doc.select("script, style, noscript, nav, header, footer").remove()
        doc.select("tr").forEach { tr ->
            tr.replaceWith(Element("p").text(tr.children().joinToString(" | ") { it.text() }))
        }
        val roots = if (selector.isNullOrBlank()) listOf(doc.body()) else doc.select(selector).toList()

        val out = StringBuilder()
        val visitor = object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> out.append(node.text())
                    node is Element && (node.isBlock || node.nameIs("br")) -> out.append('\n')
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.isBlock) out.append('\n')
            }
        }
        roots.forEach { NodeTraversor.traverse(visitor, it) }
        return out.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
