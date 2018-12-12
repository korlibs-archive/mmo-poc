package mmo.server.util

import com.soywiz.korio.file.*
import java.util.LinkedHashMap
import kotlin.collections.LinkedHashSet
import kotlin.collections.List
import kotlin.collections.arrayListOf
import kotlin.collections.filter
import kotlin.collections.first
import kotlin.collections.getOrPut
import kotlin.collections.isNotEmpty
import kotlin.collections.plusAssign

class MiniWebpack {
    private val requireRegex = Regex("require\\('(.*?)'\\)")

    fun String.extractRequires(): List<String> {
        val results = requireRegex.findAll(this)
        return results.map { it.groupValues[1] }.toList()
    }

    suspend fun VfsFile.extractRequires(): List<String> = this.readString().extractRequires()

    suspend fun miniWebpack(folder: VfsFile, module: String): String {
        val exploredModules = LinkedHashSet<String>()
        val validModules = LinkedHashSet<String>()
        val modulesToExplore = arrayListOf(module)
        val depEdges = arrayListOf<Pair<String, String>>()
        while (modulesToExplore.isNotEmpty()) {
            val current = modulesToExplore.removeAt(modulesToExplore.size - 1)
            exploredModules += current
            val deps = folder["$current.js"].takeIf { it.exists() }?.extractRequires() ?: continue
            validModules += current
            for (dep in deps) {
                depEdges += current to dep
                if (dep !in exploredModules) {
                    modulesToExplore += dep
                }
            }
        }

        // Simple packing for UMD packages
        return buildString {
            for (mod in topologySort(depEdges).filter { it in validModules }) {
                //println("- $mod")
                append(" // $mod.js\n")
                append(folder["$mod.js"].readString())
            }
        }
    }

    private fun topologySort(edges: List<Pair<String, String>>): List<String> {
        val graph = LinkedHashMap<String, LinkedHashSet<String>>()
        val out = arrayListOf<String>()
        for (edge in edges) {
            graph.getOrPut(edge.second) { LinkedHashSet() }
            graph.getOrPut(edge.first) { LinkedHashSet() }.add(edge.second)
        }
        while (graph.isNotEmpty()) {
            val emptyDep = graph.filter { it.value.isEmpty() }.keys.first()
            out += emptyDep
            graph.remove(emptyDep)
            for (set in graph.values) {
                set.remove(emptyDep)
            }
        }
        return out
    }
}


suspend fun VfsFile.miniWebpack(): String =
    MiniWebpack().miniWebpack(this.parent, this.basenameWithoutCompoundExtension)
