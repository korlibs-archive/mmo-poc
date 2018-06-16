package mmo.server

import com.soywiz.klock.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import mmo.client.*
import mmo.protocol.*
import mmo.shared.*
import java.util.concurrent.*
import kotlin.collections.set

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
    var lookDirection: CharDirection = CharDirection.UP
    var speed = 64.0 // 32 pixels per second
}

interface PacketSendChannel {
    fun send(packet: ServerPacket)
}

open class User(val sc: PacketSendChannel) : Actor(), PacketSendChannel by sc {
    val bag = LinkedHashMap<String, Int>()

    fun getItemAmount(kind: String): Int = bag[kind] ?: 0

    fun addItems(kind: String, amount: Int) {
        bag.getOrPut(kind) { 0 }
        val newValue = bag[kind]!! + amount
        bag[kind] = newValue
        sc.send(UserBagUpdate(kind, newValue))
    }
}

class NpcConversation(val npc: Npc, val user: User) {
    companion object {
        var lastId: Long = 1
    }

    val id = lastId++

    init {
        npc.conversationsById[id] = this
    }

    val onUserSelection = Channel<Int>()

    class Option<T>(val title: String, val callback: suspend NpcConversation.() -> T)

    init {
        user.send(ConversationStart(id, npc.id))
    }

    var mood: String = "normal"

    fun mood(mood: String) {
        this.mood = mood
        user.send(ConversationMoodSet(id, mood))
    }

    suspend fun mood(mood: String, callback: suspend () -> Unit) {
        val oldMood = this.mood
        mood(mood)
        try {
            callback()
        } finally {
            mood(oldMood)
        }
    }

    suspend fun say(text: String) {
        //user.send(ConversationText(id, text))
        //onUserSelection.waitOne()
        options(text, Option("Next") { })
    }

    suspend fun <T> options(text: String, vararg options: Option<T>): T {
        user.send(ConversationOptions(id, text, options.map { it.title }))
        val selection = onUserSelection.receive()
        return options.getOrNull(selection)?.callback?.invoke(this) ?: error("Invalid user selection")
    }

    suspend fun close() {
        user.send(ConversationClose(id))
        npc.conversationsById.remove(id)
    }
}

abstract class Npc() : Actor() {
    val conversationsById = LinkedHashMap<Long, NpcConversation>()

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

    suspend fun moveBy(dx: Number, dy: Number) = moveTo(getCurrentPosition() + Point2d(dx, dy))

    suspend fun moveTo(x: Number, y: Number) = moveTo(Point2d(x, y))

    fun getCurrentPosition(now: Long = System.currentTimeMillis()): Point2d {
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
        val currentSrc = getCurrentPosition(now)
        src = currentSrc.copy()
        timeStart = now
        timeEnd = now + (time.seconds).milliseconds
        dst = point.copy()
        container?.send(EntityMove(id, src.x, src.y, dst.x, dst.y, 0.0, time))
        wait(time.seconds)
        src = dst
    }

    fun say(text: String, vararg args: Any?) {
        val formattedText = try {
            text.format(*args)
        } catch (e: Throwable) {
            text
        }
        container?.send(EntitySay(id, formattedText))
    }

    fun lookAt(entity: Entity) {
        this.lookAt = entity
    }

    fun lookAt(direction: CharDirection) {
        this.lookDirection = direction
        container?.send(EntityLookDirection(this.id, direction))
    }

    suspend fun wait(time: TimeSpan) {
        delay(time.milliseconds.toLong(), TimeUnit.MILLISECONDS)
    }
}

class ChangeActionException(val action: suspend () -> Unit) : Exception()

fun PacketSendChannel.sendEntityAppear(entity: Entity) = entity.apply {
    this@sendEntityAppear.send(EntityAppear(id, src.x, src.y, skin))
}