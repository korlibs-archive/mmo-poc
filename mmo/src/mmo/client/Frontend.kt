package mmo.client

import com.soywiz.korge.*
import com.soywiz.korge.render.*
import com.soywiz.korge.resources.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.view.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.ws.*
import mmo.protocol.*
import kotlin.coroutines.experimental.*
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
    val ROWS = 4
    val COLS = 3
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

    suspend fun getSkin(skinName: String): CharacterSkin = queue {
        val texture = try { resourcesRoot["chara/$skinName.png"].readTexture(views.ag) } catch (e: Throwable) { views.transparentTexture }
        val skin = CharacterSkin(texture)
        skins[skinName] = skin
        skin
    }
}

class ClientEntity(val rm: ResourceManager, val coroutineContext: CoroutineContext, val id: Long, val views: Views) {
    val image = views.image(views.transparentTexture)
    val text = views.text("", textSize = 8.0)
    val view = views.container().apply {
        addChild(image)
        addChild(text)
    }
    var skin: CharacterSkin? = null

    fun setSkin(skinName: String) {
        launch(coroutineContext) {
            skin = rm.getSkin(skinName)
            image.tex = skin?.get(0, 0) ?: views.transparentTexture
        }
    }

    fun setPos(x: Double, y: Double) {
        view.x = x
        view.y = y
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
    val entityContainer by lazy {
        views.container().apply {
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
                        entity?.setPos(packet.dstX, packet.dstY)
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
    }
}

suspend fun <T : Any> WebSocketClient.sendPacket(obj: T, clazz: KClass<T>) {
    this.send(serializePacket(obj, clazz))
}

suspend fun WebSocketClient.receivePacket(): Any {
    return deserializePacket(this.onStringMessage.waitOne())
}
