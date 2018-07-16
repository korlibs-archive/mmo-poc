import com.soywiz.korio.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*

fun main(args: Array<String>) = Korio {
    val textFile = "mmo/jvm/resources/texts.txt".uniVfs
    val textLines = textFile.takeIf { it.exists() }?.readLines()?.toMutableList() ?: mutableListOf()

    for (file in ".".uniVfs.listRecursive()) {
        if (file.extensionLC == "kt") {
            val str = file.readString()
            if (str.contains(":" + " Npc()")) {
                println(file)
                val parts = Regex("(?:say|option|options)(?:<.*>)?\\(\"(.*)\"").findAll(str)
                for (part in parts) {
                    val enLine = "<en>" + part.groupValues[1]
                    if (!textLines.contains(enLine)) {
                        println("New Text! $enLine")
                        textLines += enLine
                        textLines += "<es>"
                        textLines += ""
                    }
                }
            }
        }
    }

    //textFile.writeLines(textLines)
    textFile.writeString(textLines.joinToString("\n"))
}