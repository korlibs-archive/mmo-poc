package mmo.server

import com.soywiz.klock.*
import com.soywiz.korge.tiled.*
import kotlinx.coroutines.*
import mmo.shared.*
import org.intellij.lang.annotations.*
import javax.script.*
import kotlin.coroutines.*

class KScriptNpc(val ktsEngine: ScriptEngine, val scene: ServerScene, val npcName: String) : Npc() {
    val map = scene.map
    val npcObject: TiledMap.Object = map.getObjectByName(npcName) ?: error("Can't find npc with name '$npcName' in $map")
    val script = npcObject.properties["script"]?.toString() ?: ""


    init {
        setPositionTo(npcObject.getPos(map))
        skinBody = npcObject.properties["body"]?.let { Skins.Body[it.toString()] } ?: Skins.Body.chubby
        skinArmor = npcObject.properties["armor"]?.let { Skins.Armor[it.toString()] } ?: Skins.Armor.none
        skinHead = npcObject.properties["head"]?.let { Skins.Head[it.toString()] } ?: Skins.Head.none
        skinHair = npcObject.properties["hair"]?.let { Skins.Hair[it.toString()] } ?: Skins.Hair.none
        name = npcObject.properties["name"]?.toString() ?: npcObject.name
        scene.add(this)
        //println("Instantiated '$npcName' : $skinBody, $skinArmor, $skinHead, $skinHair with script '$script'")
    }

    var running = true

    override suspend fun script() {
        if (!running) {
            delay(10_000)
            return
        }

        @Language("kotlin-script") // kotlin-script not available yet
        val script = """
            import ${KScriptNpc::class.java.`package`.name}.*
            import ${DateTime::class.java.`package`.name}.*
            import ${Continuation::class.java.`package`.name}.*
            import ${Deferred::class.java.`package`.name}.*
            import com.soywiz.korio.async.*
            (bindings["scope"] as ${CoroutineScope::class.qualifiedName}).launchImmediately {
                (bindings["npc"] as ${KScriptNpc::class.qualifiedName}).apply {
                    $script
                }
            }
        """.trimIndent()


        try {
            //val ktsEngine = ScriptEngineManager().getEngineByExtension("kts")

            //println(script)
            coroutineScope {
                ktsEngine.put("npc", this@KScriptNpc)
                ktsEngine.put("scope", this@coroutineScope)
                val result = ktsEngine.eval(script)
                (result as Job).join()
            }
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
