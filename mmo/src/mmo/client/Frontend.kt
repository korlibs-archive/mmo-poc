package mmo.client

import com.soywiz.klock.*
import com.soywiz.korge.component.*
import com.soywiz.korge.input.*
import com.soywiz.korge.render.*
import com.soywiz.korge.resources.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*
import com.soywiz.korim.color.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korma.geom.*
import mmo.protocol.*
import kotlin.coroutines.experimental.*
import kotlin.math.*
import kotlin.reflect.*

data class ServerEndPoint(val endpoint: String)

open class MmoModule : Module() {
    override val mainScene = MainScene::class
    override suspend fun init(injector: AsyncInjector) {
        injector
            .mapPrototype { MainScene(get()) }
            .mapSingleton { ResourceManager(get(), get()) }
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
        val texture = try { resourcesRoot["chara/$skinName.png"].readTexture(views.ag) } catch (e: Throwable) { views.transparentTexture }
        val skin = CharacterSkin(texture)
        skins[skinName] = skin
        skin
    }
}

enum class CharDirection(val id: Int) {
    UP(0), RIGHT(1), DOWN(2), LEFT(3)
}

class ClientEntity(val rm: ResourceManager, val coroutineContext: CoroutineContext, val id: Long, val views: Views) {
    val image = views.image(views.transparentTexture).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val text = views.text("", textSize = 8.0)
    val view = views.container().apply {
        addChild(image)
        addChild(text)
    }
    var skin: CharacterSkin = rm.emptySkin

    fun setSkin(skinName: String) {
        launch(coroutineContext) {
            skin = rm.getSkin(skinName)
            image.tex = skin[0, 0]
        }
    }

    fun setPos(x: Double, y: Double) {
        moving?.cancel()
        view.x = x
        view.y = y
    }

    var moving: Promise<Unit>? = null

    fun move(src: Point2d, dst: Point2d, totalTime: TimeSpan) {
        moving?.cancel()
        view.x = src.x
        view.y = src.y
        moving = launch(coroutineContext) {
            println("move $src, $dst, time=$totalTime")
            view.tween(view::x[src.x, dst.x], view::y[src.y, dst.y], time = totalTime) { step ->
                val elapsed = totalTime.seconds * step
                val frame = (elapsed / 0.1).toInt()
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
                image.tex = skin[direction.id, frame % CharacterSkin.COLS]
            }
        }
    }

    var sayPromise: Promise<Unit>? = null
    fun say(text: String) {
        sayPromise?.cancel()
        this.text.text = text
        sayPromise = launch(coroutineContext) {
            sleepMs(1000)
            this.text.text = ""
        }
    }
}

class MainScene(
    val rm: ResourceManager
) : Scene() {
    var ws: WebSocketClient? = null
    val entitiesById = LinkedHashMap<Long, ClientEntity>()
    val background by lazy { views.solidRect(1280, 720, RGBA(0x1e, 0x28, 0x3c, 0xFF)) }
    val entityContainer by lazy {
        views.container().apply {
            this += background
            scale = 2.0
            sceneView += this
        }
    }

    suspend fun init() {
        try {
            ws = WebSocketClient((injector.getOrNull<ServerEndPoint>() ?: ServerEndPoint("ws://127.0.0.1:8080/")).endpoint)
            ws?.onStringMessage?.invoke { str ->
                val packet = deserializePacket(str)
                println("CLIENT RECEIVED: $packet")
                when (packet) {
                    is EntityAppear -> {
                        ClientEntity(rm, coroutineContext, packet.entityId, views).apply {
                            setSkin(packet.skin)
                            setPos(packet.x, packet.y)
                            entityContainer.addChild(view)
                            entitiesById[id] = this
                        }
                    }
                    is EntityDisappear -> {
                        val entity = entitiesById.remove(packet.entityId)
                        entity?.view?.removeFromParent()
                    }
                    is EntityMove -> {
                        val entity = entitiesById[packet.entityId]
                        entity?.move(Point2d(packet.srcX, packet.srcY), Point2d(packet.dstX, packet.dstY), packet.totalTime.seconds)
                    }
                    is EntitySay -> {
                        val entity = entitiesById[packet.entityId]
                        entity?.say(packet.text)
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    suspend inline fun <reified T : Any> send(packet: T) = run { ws?.sendPacket(packet, T::class) }
    suspend fun receive(): Any? = ws?.receivePacket()

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
    }
}

suspend fun <T : Any> WebSocketClient.sendPacket(obj: T, clazz: KClass<T> = obj::class as KClass<T>) {
    this.send(serializePacket(obj, clazz))
}

suspend fun WebSocketClient.receivePacket(): Any {
    return deserializePacket(this.onStringMessage.waitOne())
}
