package mmo.server

import com.soywiz.korge.tiled.*
import kotlinx.coroutines.experimental.*
import mmo.shared.*
import org.intellij.lang.annotations.*
import org.jetbrains.kotlin.script.jsr223.*

class KScriptNpc(val ktsEngine: KotlinJsr223JvmLocalScriptEngine, val scene: ServerScene, val npcName: String) : Npc() {
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

    override suspend fun script() {
        ktsEngine.put("npc", this)

        @Language("kotlin-script") // kotlin-script not available yet
        val result = ktsEngine.eval(
            """
                import mmo.server.*
                import com.soywiz.klock.*
                import kotlinx.coroutines.experimental.*
                launch {
                    (bindings["npc"] as mmo.server.KScriptNpc).apply {
                        $script
                    }
                }
            """
        )
        (result as Job).join()
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
