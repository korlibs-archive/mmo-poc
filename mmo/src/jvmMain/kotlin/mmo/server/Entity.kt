package mmo.server

import com.soywiz.klock.*
import com.soywiz.kmem.*
import com.soywiz.korio.async.*
import com.soywiz.korio.util.i18n.*
import com.soywiz.korma.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.delay
import mmo.protocol.*
import mmo.server.storage.*
import mmo.server.text.*
import mmo.shared.*
import org.jetbrains.annotations.*
import java.util.*
import java.util.concurrent.*
import kotlin.collections.set
import kotlin.coroutines.*

open class Entity() {
    var container: EntityContainer? = null

    companion object {
        var lastId = 1L
        //val DEFAULT_SPEED = 64.0 // 64 pixels per second
        val DEFAULT_SPEED = 2.0 // 2 tiles per second
    }

    var name = "unknown"
    var skinBody: Skins.Body = Skins.Body.none
    var skinArmor: Skins.Armor = Skins.Armor.none
    var skinHead: Skins.Head = Skins.Head.none
    var skinHair: Skins.Hair = Skins.Hair.none
    var id = lastId++
    var src = Point()
    var dst = Point()
    var srcTime = 0L
    var dstTime = 0L
    val totalTime get() = dstTime - srcTime
    var lookAt: Entity? = null
    var lookDirection: CharDirection = CharDirection.UP
    var speed = DEFAULT_SPEED
}

interface PacketSendChannel {
    fun send(packet: ServerPacket)
}

open class User(val sc: PacketSendChannel, val userUuid: String, val storage: Storage) : Actor(),
    PacketSendChannel by sc {

    var language = Language.ENGLISH
    val flags = storage.set("user-$userUuid-flags") // FLAGS: Set<String>
    val bag = storage.map("user-$userUuid-bag")     // BAG: Map<String, Int>
    val props = storage.map("user-$userUuid-props") // CUSTOM PROPS: Map<String, String>

    suspend fun setFlag(flag: String, set: Boolean = true) = run { if (set) flags.add(flag) else flags.remove(flag) }
    suspend fun unsetFlag(flag: String) = flags.remove(flag)
    suspend fun getFlag(flag: String): Boolean = flags.contains(flag)

    // @TODO: Transaction system here to prevent issues if the server crashes during the block!
    suspend inline fun doOnce(flag: String, callback: suspend () -> Unit) {
        if (!getFlag(flag)) {
            setFlag(flag)
            callback()
        }
    }

    suspend fun getItemAmount(kind: String): Int = (bag.get(kind)?.toLong() ?: 0L).toInt()

    suspend fun addItems(kind: String, amount: Int) {
        sc.send(UserBagUpdate(kind, bag.incr(kind, amount.toLong()).toInt()))
    }

    suspend fun sendAllBag() {
        for ((k, v) in bag.map()) {
            sc.send(UserBagUpdate(k, (v.toLongOrNull() ?: 0L).toInt()))
        }
    }

    suspend fun sendInitialInfo() {
        sendAllBag()
    }

    fun userAppeared() {
        container?.userAppeared(this)
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

    suspend fun image(image: String) {
        user.send(ConversationImage(id, image))
        //onUserSelection.waitOne()
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

suspend fun <T> NpcConversation.options(@Nls text: String, callback: suspend OptionsBuilder<T>.() -> Unit): T {
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

    fun changeMainScriptTo(callback: suspend () -> Unit): Unit {
        myscript = callback
        job?.cancel()
    }

    open suspend fun onUserInteraction(user: User) {
    }

    open fun onUserAppeared(user: User) {
    }

    var job: Job? = null
    var myscript: suspend () -> Unit = { script() }

    suspend fun run() {
        while (true) {
            try {
                job = launchImmediately(coroutineContext) { myscript() }
                job?.join()
            } catch (e: Throwable) {
                e.printStackTrace()
                delay(1000) // Wait 1 second before retrying to avoid spamming in the case of error at start
            }
        }
    }

    suspend fun start() {
        println("start[0]")
        //launch(coroutineContext) {
        kotlinx.coroutines.GlobalScope.launchImmediately {
            println("start[1]")
            run()
        }
    }
}

abstract class Actor : Entity() {
    suspend fun speed(scale: Double = DEFAULT_SPEED) {
        this.speed = scale
    }

    fun setPositionTo(x: Number, y: Number) {
        this.src.setTo(x, y)
        this.dst.setTo(x, y)
        this.srcTime = 0L
        this.dstTime = 0L
    }

    fun setPositionTo(pos: IPoint) = setPositionTo(pos.x, pos.y)

    suspend fun moveBy(dx: Number, dy: Number) = moveTo(getCurrentPosition() + Point(dx.toDouble(), dy.toDouble()))
    suspend fun moveTo(x: Number, y: Number) = moveTo(Point(x.toDouble(), y.toDouble()))

    suspend fun moveBy(delta: IPoint) = moveTo(getCurrentPosition() + Point(delta.x, delta.y))

    fun getCurrentPosition(now: Long = System.currentTimeMillis()): Point {
        if (now > dstTime) return dst
        val elapsedTime = now - srcTime
        val ratio = if (totalTime > 0) (elapsedTime.toDouble() / totalTime.toDouble()).clamp(0.0, 1.0) else 1.0

        return Point(
            ratio.interpolate(src.x, dst.x),
            ratio.interpolate(src.y, dst.y)
        )
    }

    suspend fun moveTo(point: IPoint) {
        val now = System.currentTimeMillis()
        val dist = (src - point).length
        val time = dist / speed
        val currentSrc = getCurrentPosition(now)

        srcTime = now
        dstTime = now + (time.seconds).millisecondsLong

        src = Point(currentSrc)
        dst = Point(point)

        container?.sendEntityAppear(this, now = now)
        wait(time.seconds)
        src = dst
        srcTime = dstTime
    }

    fun say(@Nls text: String, vararg args: Any?) {
        // @TODO: Do formatting at the client?
        val texts = Texts.languages.map { lang ->
            val ttext = Texts.getText(text, lang)
            lang to try {
                ttext.format(*args)
            } catch (e: Throwable) {
                text
            }
        }.toMap()
        for (user in container?.users ?: listOf()) {
            val rtext = texts[user.language] ?: texts[Language.ENGLISH] ?: try {
                text.format(*args)
            } catch (e: Throwable) {
                text
            }
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
        delay(time)
    }
}

class ChangeActionException(val action: suspend () -> Unit) : Exception()

fun PacketSendChannel.sendEntityAppear(vararg entities: Entity, now: Long = System.currentTimeMillis()) {
    this@sendEntityAppear.send(EntityUpdates(now, entities.map {
        it.run {
            EntityUpdates.EntityUpdate(
                entityId = id,
                skin = SkinInfo(skinBody.id, skinArmor.id, skinHead.id, skinHair.id),
                srcX = src.x,
                srcY = src.y,
                srcTime = srcTime,
                dstX = dst.x,
                dstY = dst.y,
                dstTime = dstTime,
                direction = lookDirection
            )
        }
    }))
}
