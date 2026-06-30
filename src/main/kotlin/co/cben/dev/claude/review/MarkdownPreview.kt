package co.cben.dev.claude.review

import java.io.File

/**
 * Tiny dependency-free Markdown to HTML for the inline cards and previews.
 * Compact styling so notes stay small and unobtrusive in the editor.
 */
object MarkdownPreview {

    // No body font-size: the pane honors the component (UI label) font for size consistency
    // with the inline editor. Headings scale relatively.
    private const val STYLE =
        "<style>" +
            "body{margin:0;padding:0;line-height:1.3;}" +
            "h1{font-size:1.25em;margin:2px 0;font-weight:bold;}" +
            "h2{font-size:1.12em;margin:2px 0;font-weight:bold;}" +
            "h3{font-size:1.0em;margin:2px 0;font-weight:bold;}" +
            "p{margin:2px 0;}code{font-family:monospace;}" +
            "a{text-decoration:none;}img{max-width:260px;}" +
            "</style>"

    private val IMAGE = Regex("!\\[(.*?)]\\((.*?)\\)")
    private val LINK = Regex("\\[(.+?)]\\((.+?)\\)")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val ITALIC = Regex("\\*(.+?)\\*")
    private val CODE = Regex("`(.+?)`")

    fun toHtml(markdown: String, basePath: String?): String = wrap(toBody(markdown, basePath))

    fun wrap(bodyHtml: String): String = "<html><head>$STYLE</head><body>$bodyHtml</body></html>"

    fun toBody(markdown: String, basePath: String?): String {
        val body = StringBuilder()
        for (raw in markdown.lines()) {
            var line = escape(raw)
            line = IMAGE.replace(line) { m ->
                val alt = m.groupValues[1]
                val url = resolve(m.groupValues[2], basePath)
                "<img src=\"$url\" alt=\"$alt\"/>"
            }
            line = LINK.replace(line) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
            line = BOLD.replace(line) { "<b>${it.groupValues[1]}</b>" }
            line = ITALIC.replace(line) { "<i>${it.groupValues[1]}</i>" }
            line = CODE.replace(line) { "<code>${it.groupValues[1]}</code>" }
            body.append(
                when {
                    raw.startsWith("### ") -> "<h3>${line.removePrefix("### ")}</h3>"
                    raw.startsWith("## ") -> "<h2>${line.removePrefix("## ")}</h2>"
                    raw.startsWith("# ") -> "<h1>${line.removePrefix("# ")}</h1>"
                    raw.isBlank() -> "<div style=\"height:4px\"></div>"
                    else -> "$line<br/>"
                },
            )
        }
        return body.toString()
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun resolve(path: String, basePath: String?): String {
        if (path.startsWith("http")) return path
        val base = basePath ?: return path
        return File(base, path).toURI().toString()
    }
}
