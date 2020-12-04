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
import com.soywiz.korim.paint.*
import com.soywiz.korim.text.*
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

	override suspend fun AsyncInjector.configure() {
		//Logger("korui-application").level = Logger.Level.TRACE
		mapPrototype { MmoMainScene(get(), get(), get()) }
		mapSingleton { ResourceManager(get(), get(), get()) }
		mapSingleton { Browser(get()) }
		//.mapPrototype { MainScene(get()) }
		//.mapSingleton { ConnectionService() }
	}
}

open class Browser(val gameWindow: GameWindow) {
    suspend fun prompt(msg: String, default: String): String {
        return gameWindow.prompt(msg, default)
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
class ResourceManager(val resourcesRoot: ResourcesRoot, val coroutineContext: CoroutineContext, val views: Views) : AsyncDependency {
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
            val bitmaps = TileSet.extractBitmaps(bitmap, bitmap.width / 3, bitmap.height / 4, 3, 3 * 4, 0, 0)
            val tileset = TileSet.fromBitmaps(bitmap.width / 3, bitmap.height / 4, bitmaps)
            CharacterSkin(tileset)
        }
    }
}

interface ClientListener {
    fun updatedEntityCoords(entity: ClientEntity)
}

//fun tilePosToSpriteCoords(x: Double, y: Double): IPoint2d = IPoint2d(x * 32.0 + 16.0, y * 32.0 + 32.0)
fun tilePosToSpriteCoords(x: Double, y: Double): Point = Point(x * 32.0 + 16.0, y * 32.0 + 16.0)

inline fun <T : View> T.enableMouse(): T = this.apply { mouseEnabled = false }
inline fun <T : View> T.disableMouse(): T = this.apply { mouseEnabled = false }
inline fun <T : View> T.alpha(value: Number): T = this.apply { alpha = value.toDouble() }

object Gradient {
    val buttonGradient = Bitmap32(16, 16).apply {
        val c1 = Colors["#b2d6e0"]
        val c2 = Colors["#73a7b6"]
        context2d {
            fillStyle(GradientPaint(
				GradientKind.LINEAR,
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
        val text = TextOld(title, textSize = 36.0, font = font)
        text.format = Html.Format(align = TextAlignment.MIDDLE_CENTER, size = 36, face = font)
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
