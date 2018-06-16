package mmo.server

import com.soywiz.klock.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.experimental.*
import mmo.protocol.*
import java.util.concurrent.*

open class Entity() {
    var container: EntityContainer? = null

    companion object {
        var lastId = 1L
    }

    var name = "unknown"
    var skin = "none"
    var id = lastId++
    var src = Point2d()
    var dst = Point2d()
    var timeStart = 0L
    var timeEnd = 0L
    val totalTime get() = timeEnd - timeStart
    var lookAt: Entity? = null
    var speed = 64.0 // 32 pixels per second
}

interface PacketSendChannel {
    fun send(packet: ServerPacket)
}

open class User(val sc: PacketSendChannel) : Actor(), PacketSendChannel by sc {
}

class NpcConversation(val npc: Npc, val user: User) {
    companion object {
        var lastId: Long = 1
    }

    val id = lastId++

    class Option<T>(val title: String, val callback: suspend NpcConversation.() -> T)

    init {
        user.send(ConversationStart(id, npc.id))
    }

    suspend fun mood(mood: String) {
        user.send(ConversationMoodSet(id, mood))
    }

    suspend fun say(text: String) {
        user.send(ConversationText(id, text))
    }

    suspend fun <T> options(text: String, vararg option: Option<T>): T {
        user.send(ConversationOptions(id, text, option.map { it.title }))
        TODO()
    }

    suspend fun close() {
        user.send(ConversationClose(id))
    }
}

abstract class Npc() : Actor() {
    suspend fun conversationWith(user: User, callback: suspend NpcConversation.() -> Unit) {
        val conversation = NpcConversation(this, user)
        callback(conversation)
    }

    abstract suspend fun script(): Unit

    open suspend fun onUserInterfaction(user: User) {
    }

    suspend fun run() {
        var myscript: suspend () -> Unit = { script() }
        while (true) {
            try {
                myscript()
                break
            } catch (e: ChangeActionException) {
                myscript = e.action
            }
        }
    }

    fun start() {
        launch {
            run()
        }
    }
}

abstract class Actor() : Entity() {
    fun changeTo(callback: suspend () -> Unit): Unit = throw ChangeActionException(callback)


    suspend fun speed(scale: Double = 64.0) {
        this.speed = scale
    }

    fun setPositionTo(x: Number, y: Number) {
        this.src.setTo(x, y)
        this.dst.setTo(x, y)
        this.timeStart = 0L
        this.timeEnd = 0L
    }


    suspend fun moveTo(x: Number, y: Number) = moveTo(Point2d(x, y))

    fun getInterpolatedPosition(now: Long): Point2d {
        if (now > timeEnd) return dst
        val elapsedTime = now - timeStart
        val ratio = elapsedTime.toDouble() / totalTime.toDouble()
        return Point2d(
            interpolate(src.x, dst.x, ratio),
            interpolate(src.y, dst.y, ratio)
        )
    }

    suspend fun moveTo(point: Point2d) {
        val now = System.currentTimeMillis()
        val dist = (src - point).length
        val time = dist / speed
        val currentSrc = getInterpolatedPosition(now)
        src = currentSrc.copy()
        timeStart = now
        timeEnd = now + (time.seconds).milliseconds
        dst = point.copy()
        container?.send(EntityMove(id, src.x, src.y, dst.x, dst.y, 0.0, time))
        wait(time.seconds)
        src = dst
    }

    suspend fun say(text: String) {
        container?.send(EntitySay(id, text))
    }

    suspend fun lookAt(entity: Entity) {
        this.lookAt = entity
    }

    suspend fun wait(time: TimeSpan) {
        delay(time.milliseconds.toLong(), TimeUnit.MILLISECONDS)
    }
}

class ChangeActionException(val action: suspend () -> Unit) : Exception()

fun PacketSendChannel.sendEntityAppear(entity: Entity) = entity.apply {
    this@sendEntityAppear.send(EntityAppear(id, src.x, src.y, skin))
}