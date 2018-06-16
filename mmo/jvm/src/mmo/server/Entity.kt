package mmo.server

import com.soywiz.klock.*
import com.soywiz.korge.scene.*
import com.soywiz.korma.geom.*
import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.experimental.*
import mmo.protocol.*
import java.util.concurrent.*

class TimedPosition(val pos: Point2d = Point2d(), val time: Long = 0L)

open class Entity() {
    var container: EntityContainer? = null
    companion object {
        var lastId = 1L
    }
    var name = "unknown"
    var skin = "none"
    var id = lastId++
    var src = Point2d()
    var dst = TimedPosition()
    var lookAt: Entity? = null
    var speed = 32.0 // 32 pixels per second
}

interface PacketSendChannel {
    fun send(packet: ServerPacket)
}

open class User(val sc: PacketSendChannel) : Entity(), PacketSendChannel by sc {
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
}

abstract class Actor() : Entity() {
    fun changeTo(callback: suspend () -> Unit): Unit = throw ChangeActionException(callback)

    abstract suspend fun script(): Unit

    suspend fun speed(scale: Double = 1.0) {
        this.speed = scale
    }

    suspend fun moveTo(x: Number, y: Number) = moveTo(Point2d(x, y))

    suspend fun moveTo(point: Point2d) {
        val dist = (src - point).length
        val time = dist / speed
        dst = TimedPosition(point.copy(), System.currentTimeMillis() + (time.seconds).milliseconds)
        container?.send(EntityMove(id, src.x, src.y, dst.pos.x, dst.pos.y, 0.0, time))
        wait(time.seconds)
        src = dst.pos
    }

    suspend fun say(text: String) {
        container?.send(Said(id, text))
    }

    suspend fun lookAt(entity: Entity) {
        this.lookAt = entity
    }

    suspend fun wait(time: TimeSpan) {
        delay(time.milliseconds.toLong(), TimeUnit.MILLISECONDS)
    }

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

class ChangeActionException(val action: suspend () -> Unit) : Exception()

fun PacketSendChannel.sendEntityAppear(entity: Entity) = entity.apply {
    this@sendEntityAppear.send(EntityAppear(id, src.x, src.y, skin))
}