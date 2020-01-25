import com.soywiz.korge.tiled.*
import com.soywiz.korio.*
import com.soywiz.korio.file.*
import com.soywiz.korio.file.std.*

fun main(args: Array<String>) = Korio {
    val textFile = "mmo/src/jvmMain/resources/texts.txt".uniVfs
    val textLines = textFile.takeIf { it.exists() }?.readLines()?.toMutableList() ?: mutableListOf()

    fun processKotlinSource(filename: String, str: String) {
        println("Processing '$filename'...")
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

    val entryPoints = listOf(
        "mmo/src/jvmMain/kotlin",
        "mmo/src/commonMain/kotlin"
    )

    for (entryPoint in entryPoints) {
        for (file in entryPoint.uniVfs.listRecursive()) {
            if (file.extensionLC == "kt") {
                val str = file.readString()
                if (str.contains(":" + " Npc()")) {
                    processKotlinSource(file.baseName, str)
                }
            }
            if (file.extensionLC == "tmx") {
                val tiledmap = file.readTiledMapData()
                for (mapObj in tiledmap.objectLayers.flatMap { it.objects }) {
                    val script = mapObj.objprops["script"]?.toString()
                    if (script != null) {
                        processKotlinSource("${file.baseName}#${mapObj.name}", script)
                    }
                }
            }
        }
    }

    textFile.writeString(textLines.joinToString("\n"))
}