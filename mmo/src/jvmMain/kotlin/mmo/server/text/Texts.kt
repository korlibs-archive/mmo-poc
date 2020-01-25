package mmo.server.text

import com.soywiz.korio.lang.*
import com.soywiz.korio.util.i18n.*

object Texts {
    private val textLines by lazy {
        ClassLoader.getSystemClassLoader().getResourceAsStream("texts.txt").readBytes().toString(UTF8).lines()
    }

    class LangTexts(val lang: Language) {
        val texts = LinkedHashMap<String, String>()
    }

    val langTexts = LinkedHashMap<Language, LangTexts>()
    fun langTexts(lang: Language) = langTexts.computeIfAbsent(lang) { LangTexts(it) }

    private val textRegex = Regex("<(\\w{2})>(.*)")

    val languages by lazy { langTexts.keys.toList() }

    init {
        var textId = ""
        val texts = LinkedHashMap<Language, String>()

        fun flush() {
            for ((lang, text) in texts) {
                langTexts(lang).texts[textId] = text
            }
        }

        for (line in textLines) {
            val match = textRegex.matchEntire(line)
            if (match != null) {
                val lang = Language[match.groupValues[1]]
                val rtext = match.groupValues[2]
                val text = rtext.replace("\\n", "\n")
                //println("$lang:$text")
                if (lang == Language.ENGLISH) {
                    flush()
                    textId = text
                    texts.clear()
                } else if (lang != null) {
                    texts[lang] = text
                }
            }
        }
        flush()
    }

    fun getText(text: String, language: Language = Language.CURRENT): String {
        return langTexts(language).texts[text] ?: text
    }
}
