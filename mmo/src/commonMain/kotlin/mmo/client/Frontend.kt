package mmo.client

import com.soywiz.kds.*
import com.soywiz.klock.*
import com.soywiz.klogger.*
import com.soywiz.kmem.*
import com.soywiz.korge.bitmapfont.*
import com.soywiz.korge.component.docking.*
import com.soywiz.korge.html.*
import com.soywiz.korge.input.*
import com.soywiz.korge.resources.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.service.*
import com.soywiz.korge.tiled.*
import com.soywiz.korge.tween.*
import com.soywiz.korge.view.*
import com.soywiz.korge.view.tiles.*
import com.soywiz.korgw.*
import com.soywiz.korim.bitmap.*
import com.soywiz.korim.color.*
import com.soywiz.korim.font.*
import com.soywiz.korim.format.*
import com.soywiz.korim.vector.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.ws.*
import com.soywiz.korio.util.i18n.*
import com.soywiz.korma.geom.*
import com.soywiz.korma.interpolation.*
import kotlinx.coroutines.*
import mmo.protocol.*
import mmo.shared.*
import kotlin.coroutines.*
import kotlin.math.*
import kotlin.reflect.*

data class ServerEndPoint(val endpoint: String)

open class MmoModule : Module() {
    override val mainScene = MmoMainScene::class
    override val size: SizeInt get() = SizeInt(1280, 720)
    override val windowSize: SizeInt get() = SizeInt(1280, 720)
    //override val quality: LightQuality = LightQuality.QUALITY
    override val quality: GameWindow.Quality = GameWindow.Quality.PERFORMANCE

    override suspend fun init(injector: AsyncInjector) {
        //Logger("korui-application").level = Logger.Level.TRACE
        injector
            .mapPrototype { MmoMainScene(get(), get(), get()) }
            .mapSingleton { ResourceManager(get(), get(), get()) }
            .mapSingleton { Browser(get()) }
        //.mapPrototype { MainScene(get()) }
        //.mapSingleton { ConnectionService() }
    }
}

open class Browser(val injector: AsyncInjector) {
    fun prompt(msg: String, default: String): String {
        TODO()
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
            base[row * 3 + column] ?: Bitmaps.transparent
        }
    }

    operator fun get(row: Int, col: Int) = items[row][col]
}

// @TODO: Resource unloading, LCU and stuff
class ResourceManager(val resourcesRoot: ResourcesRoot, val coroutineContext: CoroutineContext,val views: Views) : AsyncDependency {
    private val queue = AsyncThread()
    private val skins = LinkedHashMap<String, CharacterSkin>()
    private val bitmaps = LinkedHashMap<String, Bitmap32>()
    private val bmpSlices = LinkedHashMap<String, BitmapSlice<Bitmap>>()
    val emptySkin = CharacterSkin(TileSet(listOf(Bitmaps.transparent), 1, 1))

    lateinit var bubbleChat: NinePatchBitmap32
    lateinit var font: BitmapFont

    override suspend fun init() {
        bubbleChat = resourcesRoot["bubble-chat.9.png"].readNinePatch()
        font = resourcesRoot["font1.fnt"].readBitmapFont()
    }

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

    suspend fun getBmpSlice(file: String): BmpSlice {
        return bmpSlices.getOrPut(file) {
            getBitmap(file).slice()
        }
    }

    suspend fun getSkin(prefix: String, skinName: String): CharacterSkin = queue {
        skins.getOrPut(skinName) {
            val bitmap = getBitmap(if (skinName == Skins.Body.none.fileName) "" else "chara/$prefix$skinName.png")
            val bitmaps = TileSet.extractBitmaps(bitmap, bitmap.width / 3, bitmap.height / 4, 3, 3 * 4)
            val tileset = TileSet.fromBitmaps(bitmap.width / 3, bitmap.height / 4, bitmaps)
            CharacterSkin(tileset)
        }
    }
}

interface ClientListener {
    fun updatedEntityCoords(entity: ClientEntity)
}

class ClientEntity(
    val resourceManager: ResourceManager,
    val coroutineContext: CoroutineContext,
    val id: Long,
    val views: Views,
    val listener: ClientListener
) {
    // @TODO: Remove code duplication related to layers
    val imageBody = Image(Bitmaps.transparent).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val imageArmor = Image(Bitmaps.transparent).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val imageHead = Image(Bitmaps.transparent).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val imageHair = Image(Bitmaps.transparent).apply {
        anchorX = 0.5
        anchorY = 1.0
    }
    val quest = Image(Bitmaps.transparent).apply {
        anchorY = 2.3
        anchorX = 0.5
    }
    val bubbleChatBg = NinePatchEx(resourceManager.bubbleChat, width = 64.0, height = 64.0).disableMouse().alpha(0.75)
    val bubbleChatText = Text("", textSize = 8.0, font = resourceManager.font).apply {
        format = format.copy(color = Colors.BLACK)
    }
    val bubbleChat = Container().apply {
        this += bubbleChatBg
        this += bubbleChatText
        visible = false
    }
    val rview = Container().apply {
        name = "entity$id"
        addChild(imageBody)
        addChild(imageArmor)
        addChild(imageHead)
        addChild(imageHair)
        addChild(quest)
    }
    val view = Container().apply {
        name = "entity$id"
        addChild(rview)
        addChild(bubbleChat)
    }
    var skinBody: CharacterSkin = resourceManager.emptySkin
    var skinArmor: CharacterSkin = resourceManager.emptySkin
    var skinHead: CharacterSkin = resourceManager.emptySkin
    var skinHair: CharacterSkin = resourceManager.emptySkin

    fun setSkin(body: Skins.Body, armor: Skins.Armor, head: Skins.Head, hair: Skins.Hair) {
        launchImmediately(coroutineContext) {
            imageBody.bitmap = resourceManager.getSkin(Skins.Body.prefix, body.fileName).apply { skinBody = this }[direction.id, 0]
            imageArmor.bitmap = resourceManager.getSkin(Skins.Armor.prefix, armor.fileName).apply { skinArmor = this }[direction.id, 0]
            imageHead.bitmap = resourceManager.getSkin(Skins.Head.prefix, head.fileName).apply { skinHead = this }[direction.id, 0]
            imageHair.bitmap = resourceManager.getSkin(Skins.Hair.prefix, hair.fileName).apply { skinHair = this }[direction.id, 0]
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
        imageBody.bitmap = skinBody[dir, frame]
        imageArmor.bitmap = skinArmor[dir, frame]
        imageHead.bitmap = skinHead[dir, frame]
        imageHair.bitmap = skinHair[dir, frame]
    }

    fun move(src: Point, dst: Point, totalTime: TimeSpan) {
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
        this.bubbleChatText.text = text
        // @TODO: Do not hardcode constants here, let's get from 9-patch content info
        bubbleChatBg.width = this.bubbleChatText.width + 16
        bubbleChatBg.height = this.bubbleChatText.height * 2 + 16
        //println("bubbleChatBg.localMatrix=${bubbleChatBg.localMatrix}")
        bubbleChat.visible = true
        bubbleChatText.x = 8.0
        bubbleChatText.y = 8.0
        bubbleChat.y = -bubbleChatBg.height - imageBody.height + 32
        bubbleChat.x -imageBody.width / 2 - 24.0
        sayPromise = async(coroutineContext) {
            delay(2000)
            this@ClientEntity.bubbleChatText.text = ""
            bubbleChat.visible = false
        }
    }

    fun setQuestSatus(status: QuestStatus) {
        //views.ninePatch()
        println("QuestStatus = $status")
        launchImmediately(coroutineContext) {
            quest.bitmap = when (status) {
                QuestStatus.NONE -> resourceManager.getBmpSlice("")
                QuestStatus.NEW -> resourceManager.getBmpSlice("quest-availiable.png")
                QuestStatus.UNCOMPLETE -> resourceManager.getBmpSlice("quest.png")
                QuestStatus.COMPLETE -> resourceManager.getBmpSlice("quest-ready.png")
            }
        }
    }
}

class ClientNpcConversation(
    val resourceManager: ResourceManager,
    val overlay: Container,
    val npcId: Long,
    val conversationId: Long,
    val ws: WebSocketClient,
    val scope: CoroutineScope
) {
    val coroutineContext = resourceManager.coroutineContext

    fun setMood(mood: String) {
    }

    private var bgimageString: String? = null
    private var bgimageBitmap: BmpSlice? = null

    fun setImage(image: String) {
        bgimageString = image
    }

    fun options(text: String, options: List<String>) {
        val imagePlaceholder = Container()

        if (bgimageBitmap == null && bgimageString != null) {
            launchImmediately(coroutineContext) {
                bgimageBitmap = resourceManager.resourcesRoot[bgimageString!!].readBitmapOptimized().slice()
                val image = Image(bgimageBitmap ?: Bitmaps.transparent).apply {
                    position(96, -128)
                }
                imagePlaceholder += image
                image.tween(image::alpha[0.0, 1.0], easing = Easing.EASE_IN_OUT_QUAD, time = 0.25.seconds)
            }
        } else {
            imagePlaceholder += Image(bgimageBitmap ?: Bitmaps.transparent).apply {
                position(96, -128)
            }
        }

        overlay.removeChildren()
        overlay += SolidRect(1280.0, 720.0, RGBAf(0, 0, 0, 0.75).rgba)

        overlay += imagePlaceholder

        //val heightSize = 96
        //val textSize = 48.0
        val heightSize = 64
        val textSize = 32.0
        val padding = 2

        val heightWithPadding = heightSize + padding

        overlay.alpha = 0.0
        overlay.enableMouse()
        val mainText = overlay.container {
            this += Text(text, textSize = 64.0, font = resourceManager.font).apply { y = 128.0 }
            alpha = 0.0
            x = -160.0
            scaleX = 0.75
        }
        val buttonsContainer = overlay.container {
        }
        val referenceY = (720 - options.size * heightWithPadding).toDouble()
        val buttons = arrayListOf<Container>()
        for ((index, option) in options.withIndex()) {
            val button = Container().apply {
                this += simpleButton(1280, heightSize, option, resourceManager.font, scope) {
                    overlay.disableMouse()
                    overlay.removeChildren()
                    ws.sendPacket(ClientInteractionResult(npcId, conversationId, index))
                }.apply {
                    y = referenceY + (index * heightWithPadding).toDouble()
                }
                x = 250.0 - index * 32.0
                alpha = 0.0 + index * 0.1
            }
            buttonsContainer += button
            buttons += button
        }
        launchImmediately(coroutineContext) {

            overlay.tween(
                overlay::alpha[1.0].easeOutQuad(),
                *buttons.map { it::x[0.0].easeOutQuad() }.toTypedArray(),
                *buttons.map { it::alpha[1.0].easeOutQuad() }.toTypedArray(),
                mainText::alpha[1.0].easeOutQuad(),
                mainText::x[0.0].easeOutQuad(),
                mainText::scaleX[1.0].easeOutQuad(),
                time = 300.milliseconds
            )
        }
    }
}

//fun tilePosToSpriteCoords(x: Double, y: Double): IPoint2d = IPoint2d(x * 32.0 + 16.0, y * 32.0 + 32.0)
fun tilePosToSpriteCoords(x: Double, y: Double): Point = Point(x * 32.0 + 16.0, y * 32.0 + 16.0)

class MmoMainScene(
    val resourceManager: ResourceManager,
    val browser: Browser,
    val module: Module
) : Scene(), ClientListener {
    val scope = this
    val MAP_SCALE = 3.0
    var ws: WebSocketClient? = null
    val entitiesById = LinkedHashMap<Long, ClientEntity>()
    val background by lazy { SolidRect(1280 / 3.0, 720 / 3.0, RGBA(0x1e, 0x28, 0x3c, 0xFF)) }
    val camera by lazy {
        Camera().apply {
            scale = MAP_SCALE
            sceneView += this
        }
    }
    val entityContainer by lazy {
        Container().apply {
            this += background
            camera += this
        }
    }
    val conversationOverlay by lazy {
        Container().apply {
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

    private val COLOR_TRANSFORM_NPC_OVER = ColorTransform(1, 1, 1, 1, 32, 32, 32, 0)
    private val COLOR_TRANSFORM_NPC_OUT = ColorTransform()

    fun getOrCreateEntityById(id: Long): ClientEntity {
        return entitiesById.getOrPut(id) {
            ClientEntity(resourceManager, coroutineContext, id, views, this@MmoMainScene).apply {
                rview.onClick {
                    launchImmediately {
                        ws?.sendPacket(ClientRequestInteract(id))
                    }
                }
                rview.onOver { rview.colorTransform = COLOR_TRANSFORM_NPC_OVER }
                rview.onOut { rview.colorTransform = COLOR_TRANSFORM_NPC_OUT }
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

                //println("CLIENT RECEIVED: $packet")

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

                                val currentX = ratio.interpolate(srcX, dstX)
                                val currentY = ratio.interpolate(srcY, dstY)

                                val remainingTime = totalTime - elapsed

                                entity.move(
                                    Point(currentX, currentY),
                                    Point(dstX, dstY),
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
                                ClientNpcConversation(resourceManager, conversationOverlay, packet.npcId, packet.id, ws!!, scope)
                    }
                    is ConversationClose -> {
                        conversationsById.remove(packet.id)
                    }
                    is ConversationMoodSet -> {
                        val conversation = conversationsById[packet.id]
                        conversation?.setMood(packet.mood)
                    }
                    is ConversationImage -> {
                        val conversation = conversationsById[packet.id]
                        conversation?.setImage(packet.image)
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
                        latency = DateTime.nowUnixLong() - packet.pingTime
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
            ws?.sendPacket(Ping(DateTime.nowUnixLong()))
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

    override suspend fun Container.sceneInit() {
        val sceneView = this
        init()
        entityContainer.onClick {
            val pos = it.currentPos.copy()
            launchImmediately {
                //println("CLICK")
                ws?.sendPacket(ClientRequestMove((pos.x / 32.0).toInt(), (pos.y / 32.0).toInt()))
            }
        }

        //entityContainer.addChild(views.tiledMap(resourcesRoot["tileset1.tsx"].readTiledMap(views)))
        //entityContainer.addChild(views.tiledMap(resourcesRoot["tilemap1.tmx"].readTiledMap(views)))
        entityContainer.addChild(resourcesRoot["library1.tmx"].readTiledMap().createView())
        entityContainer.keepChildrenSortedByY()
        entityContainer
        conversationOverlay
        sceneView.addChild(simpleButton(160, 80, "SAY", resourceManager.font, scope) {
            val text = browser.prompt("What to say?", "")
            ws?.sendPacket(ClientSay(text))
        })
        sceneView.addChild(simpleButton(160, 80, "DEBUG", resourceManager.font, scope) {
            views.debugViews = !views.debugViews
        }.apply { y = 82.0 })
        moneyText = Text("", textSize = 48.0, font = resourceManager.font).apply {
            x = 256.0
            sceneView += this
            mouseEnabled = false
        }
        latencyText = Text("", textSize = 48.0, font = resourceManager.font).apply {
            x = 800.0
            sceneView += this
            mouseEnabled = false
        }
        bagUpdated()
    }
}

inline fun <T : View> T.enableMouse(): T = this.apply { mouseEnabled = false }
inline fun <T : View> T.disableMouse(): T = this.apply { mouseEnabled = false }
inline fun <T : View> T.alpha(value: Number): T = this.apply { alpha = value.toDouble() }

object Gradient {
    val buttonGradient = Bitmap32(16, 16).apply {
        val c1 = Colors["#b2d6e0"]
        val c2 = Colors["#73a7b6"]
        context2d {
            fillStyle(Context2d.Gradient(
                Context2d.Gradient.Kind.LINEAR,
                0.0, 0.0, 0.0,
                0.0, 16.0, 0.0,
                stops = doubleArrayListOf(0.0, 0.2, 0.3, 1.0),
                colors = IntArrayList(c2.value, c1.value, c1.value, c2.value)
            )) {
                fillRect(0, 0, 16, 16)
            }
        }
    }.slice()
}

fun simpleButton(width: Int, height: Int, title: String, font: BitmapFont, scope: CoroutineScope, click: suspend () -> Unit): Container {
    val out = Container().apply {
        val text = Text(title, textSize = 52.0, font = font)
        text.format = Html.Format(align = Html.Alignment.MIDDLE_CENTER, size = 52, face = Html.FontFace.Bitmap(font))
        text.textBounds.setTo(0, 0, width, height)
        //addChild(SolidRect(width.toDouble(), height.toDouble(), RGBA(0xa0, 0xa0, 0xff, 0x7f)))
        addChild(Image(Gradient.buttonGradient).apply {
            this.width = width.toDouble()
            this.height = height.toDouble()
            this.alpha = 0.75
        })
        addChild(text)
        val outAlpha = 0.75
        alpha = outAlpha
        onClick {
            scope.launchImmediately {
                click()
            }
        }
        onOver { alpha = 1.0 }
        onOut { alpha = outAlpha }
    }
    return out
}

suspend fun <T : Any> WebSocketClient.sendPacket(obj: T, clazz: KClass<T> = obj::class as KClass<T>) {
    this.send(serializePacket(obj, clazz))
}

suspend fun WebSocketClient.receivePacket(): Any {
    return deserializePacket(this.onStringMessage.waitOne())
}
