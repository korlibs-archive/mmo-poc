package mmo.server.script

import com.soywiz.korma.geom.*
import mmo.server.*
import mmo.shared.*

class Lever(container: EntityContainer, val lever: Int, val pos: IPoint2d) : Npc() {
    companion object {
        val ON = CharDirection.DOWN
        val OFF = CharDirection.UP

    }

    init {
        setPositionTo(pos)
        skinBody = Skins.Body.levers
        name = "Lever$lever"
        lookAt(OFF)
        container.add(this)
        //println("$name: $on")
    }

    override suspend fun script() {
    }

    var on: Boolean
        get() = this.lookDirection == ON
        set(value) {
            lookAt(if (value) ON else OFF)
            //println("$name: $on")
        }

    override suspend fun onUserInteraction(user: User) {
        on = !on
    }
}