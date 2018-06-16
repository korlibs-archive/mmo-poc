package mmo.server.script

import com.soywiz.korma.geom.*
import mmo.server.*
import mmo.shared.*

class Lever(val lever: Int, x: Int, y: Int) : Npc() {
    companion object {
        val ON = CharDirection.UP
        val OFF = CharDirection.DOWN

    }
    init {
        src = Point2d(x, y)
        skin = "levers"
        name = "Lever$lever"
    }

    override suspend fun script() {
    }

    var on: Boolean
        get() = this.lookDirection == ON
        set(value) {
            lookAt(if (value) ON else OFF)
        }

    override suspend fun onUserInterfaction(user: User) {
        on = !on
    }
}