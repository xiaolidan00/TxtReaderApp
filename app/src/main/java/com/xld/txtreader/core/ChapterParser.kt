package com.xld.txtreader.core

data class Chapter(val title: String, val chapterIndex: Int, val content: String = "") {

}

/**
 * 章节解析（移植 JS 版分章逻辑）：
 * 1. 全文移除 `\r` 与 `\t` 后按 `\n` 切行（对应 JS 的 `txt.replace(/\r|\t/g, "").split("\n")`）
 * 2. 每行 trim 后用正则匹配判定标题：整行任意位置匹配、无 80 字符长度限制
 * 3. 仅当匹配到的标题与当前章节名不同时才切章；与当前章节同名的标题行直接丢弃（同 JS）
 * 4. 标题行保留为新章节正文首行（本 App 阅读页正文完全来自 chapterText，无独立标题头部）
 * 5. 未匹配到任何标题时整书为一章，章节名取 initialTitle（对应 JS 的 data.fileName）
 *
 * @param text 原始全文
 * @param regexSource 章节正则，空白表示不启用正则（整书一章）
 * @param initialTitle 初始章节名，通常传文件名
 * @return 章节列表，每章 content 为该章正文（含标题行）
 */
object ChapterParser {

    fun parse(text: String, regexSource: String, initialTitle: String = "正文"): List<Chapter> {
        if (text.isEmpty()) {
            return listOf(Chapter(initialTitle, 0, ""))
        }

        val lines = text.split("\n")
        var regexStr = regexSource;
        if (regexSource.isBlank()) {
            regexStr = RegexPresets.list[0].value
        }
        val regex = Regex(regexStr);
        val nullStrRegex = Regex("^\\s*$");


        val chapters = ArrayList<Chapter>()
        var title = initialTitle
        var newTitle = ""
        var chapterIndex = 0;
        val bodyLines = ArrayList<String>()

        lines.forEachIndexed { i, raw ->
            val r = raw.trim()
            if (!nullStrRegex.containsMatchIn(r)) {

                var tag = true

                if (regex.containsMatchIn(r)) {
                    newTitle = r
                    tag = false
                }
                if (newTitle.isNotEmpty() && title != newTitle) {

                    if (bodyLines.isNotEmpty()) {
                        chapters += Chapter(title, chapterIndex, bodyLines.joinToString("\n"))
                        bodyLines.clear()
                    }
                    chapterIndex++;
                    title = newTitle


                    bodyLines.add(raw)
                } else if (tag) {

                    bodyLines.add(raw)
                }

            }
        }

        if (bodyLines.isNotEmpty()) {
            chapters += Chapter(title, chapterIndex, bodyLines.joinToString("\n"))
        }
        // 兜底：text 非空时理论必有章节，防御性返回单章
        if (chapters.isEmpty()) {
            chapters += Chapter(initialTitle, chapterIndex, text)
        }
        return chapters
    }
}
