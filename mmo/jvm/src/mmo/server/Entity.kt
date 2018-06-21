package mmo.server

import com.soywiz.klock.*
import com.soywiz.kmem.*
import com.soywiz.korio.i18n.Language
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import mmo.protocol.*
import mmo.server.text.*
import mmo.shared.*
import org.jetbrains.annotations.*
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
    var srcTime = 0L
    var dstTime = 0L
    val totalTime get() = dstTime - srcTime
    var lookAt: Entity? = null
    var lookDirection: CharDirection = CharDirection.UP
    var speed = 64.0 // 32 pixels per second
}

interface PacketSendChannel {
    fun send(packet: ServerPacket)
}

open class User(val sc: PacketSendChannel) : Actor(), PacketSendChannel by sc {
    var language = Language.ENGLISH
    val bag = LinkedHashMap<String, Int>()
    val flags = LinkedHashSet<String>()

    fun setFlag(flag: String, set: Boolean = true) = run { if (set) flags.add(flag) else flags.remove(flag) }
    fun unsetFlag(flag: String) = setFlag(flag, false)
    fun getFlag(flag: String): Boolean = flag in flags

    // @TODO: Transaction system here to prevent issues if the server crashes during the block!
    inline fun doOnce(flag: String, callback: () -> Unit) {
        if (!getFlag(flag)) {
            setFlag(flag)
            callback()
        }
    }

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
        user.send(ConversationOptions(
            id, Texts.getText(text, user.language),
            options.map { Texts.getText(it.title, user.language) }
        ))
        val selection = onUserSelection.receive()
        return options.getOrNull(selection)?.callback?.invoke(this) ?: error("Invalid user selection")
    }

    suspend fun close() {
        user.send(ConversationClose(id))
        npc.conversationsById.remove(id)
    }
}

class OptionsBuilder<T>(val conversation: NpcConversation) {
    internal val options = arrayListOf<NpcConversation.Option<T>>()
    fun option(@Nls text: String, block: suspend NpcConversation.() -> T) {
        options += NpcConversation.Option(text, block)
    }
}

suspend fun <T> NpcConversation.options(@Nls text: String, callback: OptionsBuilder<T>.() -> Unit): T {
    val builder = OptionsBuilder<T>(this).apply {
        callback()
    }
    return options(text, *builder.options.toTypedArray())
}

abstract class Npc : Actor() {
    val conversationsById = LinkedHashMap<Long, NpcConversation>()

    suspend fun conversationWith(user: User, callback: suspend NpcConversation.() -> Unit) {
        val conversation = NpcConversation(this, user)
        callback(conversation)
    }

    abstract suspend fun script(): Unit

    open suspend fun onUserInteraction(user: User) {
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
        this.srcTime = 0L
        this.dstTime = 0L
    }

    suspend fun moveBy(dx: Number, dy: Number) = moveTo(getCurrentPosition() + Point2d(dx, dy))

    suspend fun moveTo(x: Number, y: Number) = moveTo(Point2d(x, y))

    fun getCurrentPosition(now: Long = System.currentTimeMillis()): Point2d {
        if (now > dstTime) return dst
        val elapsedTime = now - srcTime
        val ratio = if (totalTime > 0) (elapsedTime.toDouble() / totalTime.toDouble()).clamp(0.0, 1.0) else 1.0
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

        srcTime = now
        dstTime = now + (time.seconds).milliseconds

        src = currentSrc.copy()
        dst = point.copy()

        container?.sendEntityAppear(this, now = now)
        wait(time.seconds)
        src = dst
        srcTime = dstTime
    }

    fun say(@Nls text: String, vararg args: Any?) {
        // @TODO: Do formatting at the client?
        val texts = Texts.languages.map { lang ->
            val ttext = Texts.getText(text, lang)
            lang to try { ttext.format(*args) } catch (e: Throwable) { text }
        }.toMap()
        for (user in container?.users ?: listOf()) {
            val rtext = texts[user.language] ?: texts[Language.ENGLISH] ?: try { text.format(*args) } catch (e: Throwable) { text }
            user.send(EntitySay(id, rtext))
        }
        //container?.send(EntitySay(id, formattedText))
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

fun PacketSendChannel.sendEntityAppear(vararg entities: Entity, now: Long = System.currentTimeMillis()) {
    this@sendEntityAppear.send(EntityUpdates(now, entities.map {
        it.run {
            EntityUpdates.EntityUpdate(id, skin, src.x, src.y, srcTime, dst.x, dst.y, dstTime, lookDirection)
        }
    }))
}
