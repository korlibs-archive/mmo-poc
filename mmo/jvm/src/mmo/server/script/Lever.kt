package mmo.server.script

import mmo.server.*
import mmo.shared.*

class Lever(container: EntityContainer, val lever: Int, x: Int, y: Int) : Npc() {
    companion object {
        val ON = CharDirection.DOWN
        val OFF = CharDirection.UP

    }

    init {
        setPositionTo(x, y)
        skin = "levers"
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

    override suspend fun onUserInterfaction(user: User) {
        on = !on
    }
}