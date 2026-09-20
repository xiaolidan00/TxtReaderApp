package com.xld.txtreader.core

object RegexPresets {
    data class Preset(val name: String, val value: String)

    val list = listOf(
        Preset("第100章 标题", "\\s*第\\s*[0-9]+\\s*章"),
        Preset("第一百章 标题", "\\s*第\\s*[一二三四五六七八九十零百千万]+\\s*章"),
        Preset("第100节 标题", "\\s*第\\s*[0-9]+\\s*节"),
        Preset("第一百节 标题", "\\s*第\\s*[一二三四五六七八九十零百千万]+\\s*节"),
        Preset("第100回 标题", "\\s*第\\s*[0-9]+\\s*回"),
        Preset("第一百回 标题", "\\s*第\\s*[一二三四五六七八九十零百千万]+\\s*回"),
        Preset("100.标题", "\\s*[0-9]+\\."),
        Preset("一零零.标题", "\\s*[一二三四五六七八九十零百千万]+\\."),
        Preset("100、标题", "\\s*[0-9]+、"),
        Preset("一零零、标题", "\\s*[一二三四五六七八九十零百千万]+、"),
        Preset("100 标题", "\\s*[0-9]+"),
        Preset("一零零 标题", "\\s*[一二三四五六七八九十零百千万]+"),
        Preset("（100）标题", "\\s*[(|（][0-9]+[)|）]"),
        Preset("（一零零）标题", "\\s*[(|（][一二三四五六七八九十零百千万]+[)|）]"),
        Preset("# 标题", "^#+\\s*.+"),
        Preset("没有标点符号的标题", "^[0-9a-zA-Z\\u4e00-\\u9fa5]+$"),
        Preset("自定义", ""),
    )

    const val CUSTOM_INDEX = 16

    fun default(): Preset = list.first()
}

@Volatile
var BookContentHolder: BookContent? = null