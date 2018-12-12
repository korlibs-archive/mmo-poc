package mmo.server

import com.soywiz.klock.*
import com.soywiz.korge.tiled.*
import kotlinx.coroutines.*
import mmo.shared.*
import org.intellij.lang.annotations.*
import org.jetbrains.kotlin.script.jsr223.*
import javax.script.*
import kotlin.coroutines.*

//class KScriptNpc(val ktsEngine: KotlinJsr223JvmLocalScriptEngine, val scene: ServerScene, val npcName: String) : Npc() {
class KScriptNpc(val ktsEngine: ScriptEngine, val scene: ServerScene, val npcName: String) : Npc() {
    val map = scene.map
    val npcObject = map.getObjectByName(npcName) ?: error("Can't find npc with name '$npcName' in $map")
    val script = npcObject.objprops["script"]?.toString() ?: ""

    init {
        setPositionTo(npcObject.getPos(map))
        skinBody = npcObject.objprops["body"]?.let { Skins.Body[it.toString()] } ?: Skins.Body.chubby
        skinArmor = npcObject.objprops["armor"]?.let { Skins.Armor[it.toString()] } ?: Skins.Armor.none
        skinHead = npcObject.objprops["head"]?.let { Skins.Head[it.toString()] } ?: Skins.Head.none
        skinHair = npcObject.objprops["hair"]?.let { Skins.Hair[it.toString()] } ?: Skins.Hair.none
        name = npcObject.objprops["name"]?.toString() ?: npcObject.name
        scene.add(this)
        println("Instantiated '$npcName' : $skinBody, $skinArmor, $skinHead, $skinHair with script '$script'")
    }

    var running = true

    override suspend fun script() {
        if (!running) {
            delay(10_000)
            return
        }

        val script = """
            import ${KScriptNpc::class.java.`package`.name}.*
            import ${DateTime::class.java.`package`.name}.*
            import ${Continuation::class.java.`package`.name}.*
            import ${Deferred::class.java.`package`.name}.*
            launch {
                (bindings["npc"] as ${KScriptNpc::class.qualifiedName}).apply {
                    $script
                }
            }
        """.trimIndent()

        try {
            //val ktsEngine = ScriptEngineManager().getEngineByExtension("kts") as KotlinJsr223JvmLocalScriptEngine
            val ktsEngine = ScriptEngineManager().getEngineByExtension("kts")
            ktsEngine.put("npc", this)

            @Language("kotlin-script") // kotlin-script not available yet
            //println(script)
            val result = ktsEngine.eval(script)
            (result as Job).join()
        } catch (e: Throwable) {
            System.err.println("There was a problem with the script npcName=${this.npcName}:")
            System.err.println("// -------------")
            System.err.println(script)
            System.err.println("// -------------")
            e.printStackTrace()
            running = false
            delay(10_000)
        }
    }

    suspend fun move(place: String) {
        val pos = map.getObjectPosByName(place)
        if (pos != null) {
            moveTo(pos)
        } else {
            println("Couldn't find named position '$place'")
        }
    }

    suspend fun look(direction: String) {
        when (direction) {
            "up" -> lookAt(CharDirection.UP)
            "down" -> lookAt(CharDirection.DOWN)
            "right" -> lookAt(CharDirection.RIGHT)
            "left" -> lookAt(CharDirection.LEFT)
        }
    }
}
