package mmo.client

import com.soywiz.klock.*
import com.soywiz.kmem.*
import com.soywiz.korge.component.*
import com.soywiz.korge.html.*
import com.soywiz.korge.input.*
import com.soywiz.korge.render.*
import com.soywiz.korge.resources.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.service.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*
import com.soywiz.korim.color.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import mmo.protocol.*
import mmo.shared.*
import kotlin.coroutines.experimental.*
import kotlin.math.*
import kotlin.reflect.*

data class ServerEndPoint(val endpoint: String)

open class MmoModule : Module() {
    override val mainScene = MainScene::class
    override val size: SizeInt get() = SizeInt(1280, 720)
    override val windowSize: SizeInt get() = SizeInt(1280, 720)

    override suspend fun init(injector: AsyncInjector) {
        injector
            .mapPrototype { MainScene(get(), get()) }
            .mapSingleton { ResourceManager(get(), get()) }
            .mapSingleton { Browser(get()) }
        //.mapPrototype { MainScene(get()) }
        //.mapSingleton { ConnectionService() }
    }
}

class CharacterSkin(val base: Texture) {
    companion object {
        val ROWS = 4
        val COLS = 3
    }

    val cellWidth = base.width / COLS
    val cellHeight = base.height / ROWS
    val items = (0 until ROWS).map { row ->
        (0 until COLS).map { column ->
            base.slice(cellWidth * column, cellHeight * row, cellWidth, cellHeight)
        }
    }

    operator fun get(row: Int, col: Int) = items[row][col]
}

class ResourceManager(val resourcesRoot: ResourcesRoot, val views: Views) {
    val queue = AsyncThread()
    val skins = LinkedHashMap<String, CharacterSkin>()
    val emptySkin = CharacterSkin(views.transparentTexture)

    suspend fun getSkin(skinName: String): CharacterSkin = queue {
        val texture = try {
            resourcesRoot["chara/$skinName.png"].readTexture(views.ag)
        } catch (e: Throwable) {
            views.transparentTexture
        }
        val skin = CharacterSkin(texture)
        skins[skinName] = skin
        skin
    }
}


class ClientEntity(val rm: ResourceManager, val coroutineContext: CoroutineContext, val id: Long, val views: Views) {
    val image = views.image(views.transparentTexture).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val text = views.text("", textSize = 8.0).apply { autoSize = true }
    val view = views.container().apply {
        addChild(image)
        addChild(text)
    }
    var skin: CharacterSkin = rm.emptySkin

    fun setSkin(skinName: String) {
        launch(coroutineContext) {
            skin = rm.getSkin(skinName)
            image.tex = skin[direction.id, 0]
        }
    }

    fun setPos(x: Double, y: Double) {
        moving?.cancel()
        moving = null
        view.x = x
        view.y = y
    }

    var moving: Promise<Unit>? = null
    var direction = CharDirection.DOWN

    fun move(src: Point2d, dst: Point2d, totalTime: TimeSpan) {
        moving?.cancel()
        view.x = src.x
        view.y = src.y
        moving = launch(coroutineContext) {
            println("move $src, $dst, time=$totalTime")
            val dx = (dst.x - src.x).absoluteValue
            val dy = (dst.y - src.y).absoluteValue
            val horizontal = dx >= dy
            val direction = when {
                horizontal && dst.x >= src.x -> CharDirection.RIGHT
                horizontal && dst.x < src.x -> CharDirection.LEFT
                !horizontal && dst.y < src.y -> CharDirection.UP
                !horizontal && dst.y >= src.y -> CharDirection.DOWN
                else -> CharDirection.RIGHT
            }
            view.tween(view::x[src.x, dst.x], view::y[src.y, dst.y], time = totalTime) { step ->
                val elapsed = totalTime.seconds * step
                val frame = (elapsed / 0.1).toInt()
                image.tex = skin[direction.id, frame % CharacterSkin.COLS]
            }
            image.tex = skin[direction.id, 1]
        }
    }

    fun lookAt(direction: CharDirection) {
        this.direction = direction
        println("lookAt.DIRECTION[$this]: $direction")
        image.tex = skin[direction.id, 0]
    }

    var sayPromise: Promise<Unit>? = null
    fun say(text: String) {
        sayPromise?.cancel()
        this.text.text = text
        sayPromise = launch(coroutineContext) {
            sleepMs(2000)
            this.text.text = ""
        }
    }
}

class ClientNpcConversation(
    val overlay: Container,
    val npcId: Long,
    val conversationId: Long,
    val ws: WebSocketClient
) {
    val views = overlay.views

    fun setMood(mood: String) {
    }

    fun options(text: String, options: List<String>) {
        overlay.removeChildren()
        overlay += views.solidRect(1280, 720, RGBAf(0, 0, 0, 0.75).rgba)
        overlay += views.text(text, textSize = 48.0).apply { y = 128.0; autoSize = true }
        val referenceY = (720 - options.size * 96).toDouble()
        for ((index, option) in options.withIndex()) {
            overlay += views.simpleButton(1280, 96, option, {
                overlay.removeChildren()
                ws.sendPacket(ClientInteractionResult(npcId, conversationId, index))
            }).apply {
                y = referenceY + (index * 96).toDouble()
            }
        }
    }
}

class MainScene(
    val rm: ResourceManager,
    val browser: Browser
) : Scene() {
    var ws: WebSocketClient? = null
    val entitiesById = LinkedHashMap<Long, ClientEntity>()
    val background by lazy { views.solidRect(1280, 720, RGBA(0x1e, 0x28, 0x3c, 0xFF)) }
    val entityContainer by lazy {
        views.container().apply {
            this += background
            scale = 3.0
            sceneView += this
        }
    }
    val conversationOverlay by lazy {
        views.container().apply {
            sceneView += this
        }
    }
    val conversationsById = LinkedHashMap<Long, ClientNpcConversation>()

    suspend fun init() {
        try {
            ws = WebSocketClient((injector.getOrNull() ?: ServerEndPoint("ws://127.0.0.1:8080/")).endpoint)
            ws?.onStringMessage?.invoke { str ->
                val packet = deserializePacket(str)

                println("CLIENT RECEIVED: $packet")

                when (packet) {
                    is EntityDisappear -> {
                        val entity = entitiesById.remove(packet.entityId)
                        entity?.view?.removeFromParent()
                    }
                    is EntityUpdates -> {
                        val now = packet.currentTime
                        for (update in packet.updates) {
                            val entity = entitiesById.getOrPut(update.entityId) {
                                ClientEntity(rm, coroutineContext, update.entityId, views).apply {
                                    view.onClick {
                                        ws?.sendPacket(ClientRequestInteract(id))
                                    }
                                    entityContainer.addChild(view)
                                    entitiesById[id] = this
                                }
                            }

                            entity.setSkin(update.skin)
                            entity.lookAt(update.direction)

                            val elapsed = (now - update.srcTime).toDouble()
                            val totalTime = (update.dstTime - update.srcTime).toDouble()

                            //println("Update: $update")

                            if (totalTime > 0) {
                                val ratio = (if (totalTime > 0.0) elapsed / totalTime else 1.0).clamp(0.0, 1.0)

                                val currentX = interpolate(update.srcX, update.dstX, ratio)
                                val currentY = interpolate(update.srcY, update.dstY, ratio)

                                val remainingTime = totalTime - elapsed

                                entity.move(
                                    Point2d(currentX, currentY),
                                    Point2d(update.dstX, update.dstY),
                                    remainingTime.milliseconds
                                )
                            } else {
                                entity.setPos(update.dstX, update.dstY)
                            }
                        }
                    }
                    is EntitySay -> {
                        val entity = entitiesById[packet.entityId]
                        entity?.say(packet.text)
                    }
                    is ConversationStart -> {
                        conversationsById[packet.id] =
                                ClientNpcConversation(conversationOverlay, packet.npcId, packet.id, ws!!)
                    }
                    is ConversationClose -> {
                        conversationsById.remove(packet.id)
                    }
                    is ConversationMoodSet -> {
                        val conversation = conversationsById[packet.id]
                        conversation?.setMood(packet.mood)
                    }
                    is ConversationOptions -> {
                        val conversation = conversationsById[packet.id]
                        conversation?.options(packet.text, packet.options)
                    }
                    is UserBagUpdate -> {
                        bag[packet.item] = packet.amount
                        bagUpdated()
                    }
                    is EntityLookDirection -> {
                        val entity = entitiesById[packet.entityId]
                        entity?.lookAt(packet.direction)
                    }
                    is Ping -> {
                        launch(coroutineContext) {
                            ws?.sendPacket(Pong(packet.pingTime))
                        }
                    }
                    is Pong -> {
                        latency = Klock.currentTimeMillis() - packet.pingTime
                    }
                }
            }
            coroutineContext.eventLoop.setInterval(1000) {
                launch(coroutineContext) {
                    ws?.sendPacket(Ping(Klock.currentTimeMillis()))
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    var latency: Long = 0L
        set(value) {
            field = value
            latencyText?.text = "Latency: $value"
        }

    val bag = LinkedHashMap<String, Int>()

    fun bagUpdated() {
        val gold = bag["gold"] ?: 0
        moneyText.text = "Gold: $gold"
    }

    suspend inline fun <reified T : Any> send(packet: T) = run { ws?.sendPacket(packet, T::class) }
    suspend fun receive(): Any? = ws?.receivePacket()

    lateinit var moneyText: Text
    var latencyText: Text? = null

    override suspend fun sceneInit(sceneView: Container) {
        init()
        entityContainer.onClick {
            val pos = it.currentPos
            println("CLICK")
            ws?.sendPacket(ClientRequestMove(pos.x, pos.y))
        }
        entityContainer.addComponent(object : Component(entityContainer) {
            override fun update(dtMs: Int) {
                entityContainer.children.sortBy { it.y }
            }
        })
        entityContainer
        conversationOverlay
        sceneView.addChild(views.simpleButton(128, 96, "SAY") {
            val text = browser.prompt("What to say?", "")
            ws?.sendPacket(ClientSay(text))
        })
        moneyText = views.text("", textSize = 48.0).apply {
            autoSize = true
            x = 256.0
            sceneView += this
        }
        latencyText = views.text("", textSize = 48.0).apply {
            autoSize = true
            x = 800.0
            sceneView += this
        }
        bagUpdated()
    }
}

fun Views.simpleButton(width: Int, height: Int, title: String, click: suspend () -> Unit): Container {
    val out = container().apply {
        val text = text(title, textSize = 52.0)
        text.format = Html.Format(align = Html.Alignment.MIDDLE_CENTER, size = 52)
        text.textBounds.setTo(0, 0, width, height)
        addChild(views.solidRect(width, height, RGBA(0xa0, 0xa0, 0xff, 0x7f)))
        addChild(text)
        onClick {
            click()
        }
    }
    return out
}

suspend fun <T : Any> WebSocketClient.sendPacket(obj: T, clazz: KClass<T> = obj::class as KClass<T>) {
    this.send(serializePacket(obj, clazz))
}

suspend fun WebSocketClient.receivePacket(): Any {
    return deserializePacket(this.onStringMessage.waitOne())
}
