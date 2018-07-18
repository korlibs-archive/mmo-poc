package mmo.client

import com.soywiz.klock.*
import com.soywiz.klogger.*
import com.soywiz.kmem.*
import com.soywiz.korge.component.docking.*
import com.soywiz.korge.html.*
import com.soywiz.korge.input.*
import com.soywiz.korge.render.*
import com.soywiz.korge.resources.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.service.*
import com.soywiz.korge.tiled.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*
import com.soywiz.korge.view.tiles.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korim.format.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import com.soywiz.korio.i18n.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import com.soywiz.korui.light.*
import kotlinx.coroutines.experimental.*
import mmo.protocol.*
import mmo.shared.*
import kotlin.coroutines.experimental.*
import kotlin.math.*
import kotlin.reflect.*

data class ServerEndPoint(val endpoint: String)

open class MmoModule : Module() {
    override val mainScene = MmoMainScene::class
    override val size: SizeInt get() = SizeInt(1280, 720)
    override val windowSize: SizeInt get() = SizeInt(1280, 720)
    //override val quality: LightQuality = LightQuality.QUALITY
    override val quality: LightQuality = LightQuality.PERFORMANCE

    override suspend fun init(injector: AsyncInjector) {
        injector
            .mapPrototype { MmoMainScene(get(), get(), get()) }
            .mapSingleton { ResourceManager(get(), get()) }
            .mapSingleton { Browser(get()) }
        //.mapPrototype { MainScene(get()) }
        //.mapSingleton { ConnectionService() }
    }
}

class CharacterSkin(val base: TileSet) {
    companion object {
        val ROWS = 4
        val COLS = 3
    }

    val cellWidth = base.width / COLS
    val cellHeight = base.height / ROWS
    val items = (0 until ROWS).map { row ->
        (0 until COLS).map { column ->
            base[row * 3 + column] ?: base.views.transparentTexture
        }
    }

    operator fun get(row: Int, col: Int) = items[row][col]
}

// @TODO: Resource unloading, LCU and stuff
class ResourceManager(val resourcesRoot: ResourcesRoot, val views: Views) {
    private val queue = AsyncThread()
    private val skins = LinkedHashMap<String, CharacterSkin>()
    private val bitmaps = LinkedHashMap<String, Bitmap32>()
    private val textures = LinkedHashMap<String, Texture>()
    val emptySkin = CharacterSkin(TileSet(views, listOf(views.transparentTexture), 1, 1))

    suspend fun getBitmap(file: String): Bitmap32 {
        return bitmaps.getOrPut(file) {
            try {
                if (file == "none" || file == "") {
                    Bitmap32(1, 1)
                } else {
                    resourcesRoot[file].readBitmapOptimized(views.imageFormats).toBMP32()
                }
            } catch (e: Throwable) {
                Bitmap32(1, 1)
            }
        }
    }

    suspend fun getTexture(file: String): Texture {
        return textures.getOrPut(file) {
            views.texture(getBitmap(file))
        }
    }

    suspend fun getSkin(prefix: String, skinName: String): CharacterSkin = queue {
        skins.getOrPut(skinName) {
            val bitmap = getBitmap(if (skinName == Skins.Body.none.mname) "" else "chara/$prefix$skinName.png")
            val bitmaps = TileSet.extractBitmaps(bitmap, bitmap.width / 3, bitmap.height / 4, 3, 3 * 4)
            val tileset = TileSet.fromBitmaps(views, bitmap.width / 3, bitmap.height / 4, bitmaps)
            CharacterSkin(tileset)
        }
    }
}

interface ClientListener {
    fun updatedEntityCoords(entity: ClientEntity)
}

class ClientEntity(
    val rm: ResourceManager,
    val coroutineContext: CoroutineContext,
    val id: Long,
    val views: Views,
    val listener: ClientListener
) {
    // @TODO: Remove code duplication related to layers
    val imageBody = views.image(views.transparentTexture).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val imageArmor = views.image(views.transparentTexture).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val imageHead = views.image(views.transparentTexture).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val imageHair = views.image(views.transparentTexture).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val quest = views.image(views.transparentTexture).apply {
        anchorY = 2.3
        anchorX = 0.5
    }
    val text = views.text("", textSize = 8.0)
    val rview = views.container().apply {
        name = "entity$id"
        addChild(imageBody)
        addChild(imageArmor)
        addChild(imageHead)
        addChild(imageHair)
        addChild(quest)
    }
    val view = views.container().apply {
        name = "entity$id"
        addChild(rview)
        addChild(text)
    }
    var skinBody: CharacterSkin = rm.emptySkin
    var skinArmor: CharacterSkin = rm.emptySkin
    var skinHead: CharacterSkin = rm.emptySkin
    var skinHair: CharacterSkin = rm.emptySkin

    fun setSkin(body: Skins.Body, armor: Skins.Armor, head: Skins.Head, hair: Skins.Hair) {
        launch(coroutineContext, start = CoroutineStart.UNDISPATCHED) {
            imageBody.tex = rm.getSkin(Skins.Body.prefix, body.mname).apply { skinBody = this }[direction.id, 0]
            imageArmor.tex = rm.getSkin(Skins.Armor.prefix, armor.mname).apply { skinArmor = this }[direction.id, 0]
            imageHead.tex = rm.getSkin(Skins.Head.prefix, head.mname).apply { skinHead = this }[direction.id, 0]
            imageHair.tex = rm.getSkin(Skins.Hair.prefix, hair.mname).apply { skinHair = this }[direction.id, 0]
        }
    }

    fun setPos(x: Double, y: Double) {
        moving?.cancel()
        moving = null
        view.x = x
        view.y = y
        listener.updatedEntityCoords(this)
    }

    var moving: Deferred<Unit>? = null
    var direction = CharDirection.DOWN

    fun setSkinTex(dir: Int, frame: Int) {
        imageBody.tex = skinBody[dir, frame]
        imageArmor.tex = skinArmor[dir, frame]
        imageHead.tex = skinHead[dir, frame]
        imageHair.tex = skinHair[dir, frame]
    }

    fun move(src: Point2d, dst: Point2d, totalTime: TimeSpan) {
        moving?.cancel()
        view.x = src.x
        view.y = src.y
        moving = async(coroutineContext) {
            //println("move $src, $dst, time=$totalTime")
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
                setSkinTex(direction.id, frame % CharacterSkin.COLS)
                listener.updatedEntityCoords(this@ClientEntity)
            }
            setSkinTex(direction.id, 1)
        }
    }

    fun lookAt(direction: CharDirection) {
        this.direction = direction
        //println("lookAt.DIRECTION[$this]: $direction")
        setSkinTex(direction.id, 0)
    }

    var sayPromise: Deferred<Unit>? = null
    fun say(text: String) {
        sayPromise?.cancel()
        this.text.text = text
        sayPromise = async(coroutineContext) {
            delay(2000)
            this@ClientEntity.text.text = ""
        }
    }

    fun setQuestSatus(status: QuestStatus) {
        println("QuestStatus = $status")
        launch(coroutineContext, start = CoroutineStart.UNDISPATCHED) {
            quest.tex = when (status) {
                QuestStatus.NONE -> rm.getTexture("")
                QuestStatus.NEW -> rm.getTexture("quest-availiable.png")
                QuestStatus.UNCOMPLETE -> rm.getTexture("quest.png")
                QuestStatus.COMPLETE -> rm.getTexture("quest-ready.png")
            }
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
        overlay += views.text(text, textSize = 48.0).apply { y = 128.0 }
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

//fun tilePosToSpriteCoords(x: Double, y: Double): IPoint2d = IPoint2d(x * 32.0 + 16.0, y * 32.0 + 32.0)
fun tilePosToSpriteCoords(x: Double, y: Double): Point2d = Point2d(x * 32.0 + 16.0, y * 32.0 + 16.0)

class MmoMainScene(
    val rm: ResourceManager,
    val browser: Browser,
    val module: Module
) : Scene(), ClientListener {
    val MAP_SCALE = 3.0
    var ws: WebSocketClient? = null
    val entitiesById = LinkedHashMap<Long, ClientEntity>()
    val background by lazy { views.solidRect(1280 / 3.0, 720 / 3.0, RGBA(0x1e, 0x28, 0x3c, 0xFF)) }
    val camera by lazy {
        views.camera().apply {
            scale = MAP_SCALE
            sceneView += this
        }
    }
    val entityContainer by lazy {
        views.container().apply {
            this += background
            camera += this
        }
    }
    val conversationOverlay by lazy {
        views.container().apply {
            sceneView += this
        }
    }
    val conversationsById = LinkedHashMap<Long, ClientNpcConversation>()

    var userId: Long = -1L

    override fun updatedEntityCoords(entity: ClientEntity) {
        if (entity.id == userId) {
            //camera.setTo(entity.view)
            //println("CAMERA(${camera.x}, ${camera.y})")
            //println("USER MOVED TO $entity")
            camera.x =
                    -(((entity.view.x - module.size.width.toDouble() / MAP_SCALE / 2) * MAP_SCALE).clamp(0.0, 5000.0))
            camera.y =
                    -(((entity.view.y - module.size.height.toDouble() / MAP_SCALE / 2) * MAP_SCALE).clamp(0.0, 5000.0))
        } else {
            //println("OTHER MOVED TO $entity")
        }
    }

    fun getOrCreateEntityById(id: Long): ClientEntity {
        return entitiesById.getOrPut(id) {
            ClientEntity(rm, coroutineContext, id, views, this@MmoMainScene).apply {
                rview.onClick {
                    ws?.sendPacket(ClientRequestInteract(id))
                }
                entityContainer.addChild(view)
                entitiesById[id] = this
            }
        }
    }

    suspend fun init() {
        try {
            ws = WebSocketClient((injector.getOrNull() ?: ServerEndPoint("ws://127.0.0.1:8080/")).endpoint)
            Console.error("Language: ${Language.CURRENT}")
            ws?.sendPacket(ClientSetLang(Language.CURRENT.iso6391))
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
                            val entity = getOrCreateEntityById(update.entityId)

                            entity.setSkin(
                                Skins.Body[update.skin.body]!!,
                                Skins.Armor[update.skin.armor]!!,
                                Skins.Head[update.skin.head]!!,
                                Skins.Hair[update.skin.hair]!!
                            )
                            entity.lookAt(update.direction)

                            val elapsed = (now - update.srcTime).toDouble()
                            val totalTime = (update.dstTime - update.srcTime).toDouble()

                            //println("Update: $update")

                            val src = tilePosToSpriteCoords(update.srcX, update.srcY)
                            val dst = tilePosToSpriteCoords(update.dstX, update.dstY)
                            val srcX = src.x
                            val srcY = src.y
                            val dstX = dst.x
                            val dstY = dst.y
                            if (totalTime > 0) {
                                val ratio = (if (totalTime > 0.0) elapsed / totalTime else 1.0).clamp(0.0, 1.0)

                                val currentX = interpolate(srcX, dstX, ratio)
                                val currentY = interpolate(srcY, dstY, ratio)

                                val remainingTime = totalTime - elapsed

                                entity.move(
                                    Point2d(currentX, currentY),
                                    Point2d(dstX, dstY),
                                    remainingTime.milliseconds
                                )
                            } else {
                                entity.setPos(dstX, dstY)
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
                        launch(coroutineContext) {
                            delay(5.seconds)
                            sendPing()
                        }
                    }
                    is UserSetId -> {
                        userId = packet.entityId
                        val user = entitiesById[userId]
                        if (user != null) {
                            this.updatedEntityCoords(user)
                            //user.view.mouse.dettach()
                            user.view.mouseEnabled = false
                        }
                    }
                    is QuestUpdate -> {
                        val entity = entitiesById[packet.entityId]
                        entity?.setQuestSatus(packet.status)
                    }
                    else -> {
                        Console.error("Unhandled packet from server", packet)
                    }
                }
            }
            sendPing()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun sendPing() {
        launch(coroutineContext) {
            ws?.sendPacket(Ping(Klock.currentTimeMillis()))
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
            ws?.sendPacket(ClientRequestMove((pos.x / 32.0).toInt(), (pos.y / 32.0).toInt()))
        }

        //entityContainer.addChild(views.tiledMap(resourcesRoot["tileset1.tsx"].readTiledMap(views)))
        //entityContainer.addChild(views.tiledMap(resourcesRoot["tilemap1.tmx"].readTiledMap(views)))
        entityContainer.addChild(views.tiledMap(resourcesRoot["library1.tmx"].readTiledMap(views)))
        entityContainer.keepChildrenSortedByY()
        entityContainer
        conversationOverlay
        sceneView.addChild(views.simpleButton(160, 80, "SAY") {
            val text = browser.prompt("What to say?", "")
            ws?.sendPacket(ClientSay(text))
        })
        sceneView.addChild(views.simpleButton(160, 80, "DEBUG") {
            views.debugViews = !views.debugViews
        }.apply { y = 80.0 })
        moneyText = views.text("", textSize = 48.0).apply {
            x = 256.0
            sceneView += this
            mouseEnabled = false
        }
        latencyText = views.text("", textSize = 48.0).apply {
            x = 800.0
            sceneView += this
            mouseEnabled = false
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
