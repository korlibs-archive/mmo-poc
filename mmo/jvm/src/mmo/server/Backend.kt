package mmo.server

import com.soywiz.korge.tiled.*
import com.soywiz.korio.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.i18n.Language
import com.soywiz.korio.ktor.*
import io.ktor.application.*
import io.ktor.content.*
import io.ktor.experimental.client.redis.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import mmo.protocol.*
import mmo.server.script.*
import mmo.server.storage.*
import mmo.shared.*
import org.jetbrains.kotlin.script.jsr223.*
import java.io.*
import java.io.IOException
import java.net.*
import java.util.*
import javax.script.*
import kotlin.coroutines.experimental.*
import kotlin.reflect.*

object Experiments {
    @JvmStatic
    fun main(args: Array<String>) {
        val packet = serializePacket(ClientSay("HELLO"), ClientSay::class)
        println(packet)
        println(deserializePacket(packet))
    }
}

val gameContext = newSingleThreadContext("MySingleThread")

class MySession(val uuid: String)

fun main(args: Array<String>) = Korio {
    val webFolder =
        listOf(".", "..", "../..", "../../..").map { File(it).absoluteFile["web"] }.firstOrNull { it.exists() }
                ?: error("Can't find 'web' folder")
    val webVfs = webFolder.toVfs()

    println("webFolder:$webFolder")

    val tilemap = webVfs["library1.tmx"].readTiledMapData()
    println("Post TileMap")
    val mainScene = ServerScene("lobby", tilemap, gameContext)

    println("Building Princess NPC")
    Princess(mainScene).apply { start() }

    val ktsEngine = ScriptEngineManager().getEngineByExtension("kts") as KotlinJsr223JvmLocalScriptEngine

    val scriptedNpcs =
        tilemap.objectLayers.flatMap { it.objects }.filter { it.info.type == "npc" && "script" in it.objprops }
    for (scriptedNpc in scriptedNpcs) {
        KScriptNpc(ktsEngine, mainScene, scriptedNpc.name).apply { start() }
    }

    val redisHost = System.getenv("REDIS_HOST") ?: "127.0.0.1"

    println("Before Storage")
    println("Redis Host: $redisHost")

    val storage: Storage = try {
        val redis = RedisClient(InetSocketAddress(redisHost, 6379))
        redis.set("mmo", "running")
        RedisStorage(redis, prefix = "mmo-")
        //InmemoryStorage()
    } catch (e: IOException) {
        e.printStackTrace()
        println("Couldn't use REDIS storage")
        InmemoryStorage()
    }

    println("Storage: $storage")

    val server = embeddedServer(Netty, port = 8080) {
        install(WebSockets)
        install(ConditionalHeaders)
        install(Sessions) {
            cookie<MySession>("MMO_SESSION")
        }

        //tilemapLog.level = Logger.Level.TRACE
        //Logger.defaultLevel = Logger.Level.TRACE

        println("Pre TileMap")


        println("Pre Routing")

        routing {
            static("/") {
                get {
                    val userUid = call.getUserId()
                    call.respondFile(webVfs["index.html"])
                    finish()
                }

                webSocket {
                    val userUid = call.getUserId()

                    launch(gameContext) {
                        //println(Thread.currentThread())
                        delay(100) // @TODO: Remove once client start receiving messages from websockets from the very beginning
                        val sendQueue = Channel<ServerPacket>(Channel.UNLIMITED)

                        val user = User(object : PacketSendChannel {
                            override fun send(packet: ServerPacket) {
                                //println("OFFERING: $packet")
                                sendQueue.offer(packet)
                            }
                        }, userUid, storage).apply {
                            this.skinBody = Skins.Body.chubby
                            this.skinArmor = Skins.Armor.armor1
                            this.skinHead = Skins.Head.elf1
                            this.skinHair = Skins.Hair.pelo1
                            this.setPositionTo(4, 4)
                        }

                        websocketWriteProcess(gameContext, this@webSocket, user, sendQueue)

                        try {
                            mainScene.addUser(user)
                            user.send(UserSetId(user.id))
                            user.sendInitialInfo()
                            user.userAppeared()
                            websocketReadProcess(user)
                        } finally {
                            mainScene.remove(user)
                        }
                    }.join()
                }

                files(webFolder)
            }
        }

        println("Post Routing")

        println("Post runBlocking")
    }
    println("Pre Server Start")
    server.start()
}

fun ApplicationCall.getUserId(): String {
    val session = sessions.get<MySession>()
    val userUuid = session?.uuid ?: UUID.randomUUID().toString()
    sessions.set(MySession(userUuid))
    return userUuid
}

fun websocketWriteProcess(
    coroutineContext: CoroutineContext,
    ws: DefaultWebSocketServerSession,
    user: User,
    sendChannel: Channel<ServerPacket>
) {
    kotlinx.coroutines.experimental.launch(coroutineContext) {
        sendChannel.consumeEach { message ->
            //println("SENDING MESSAGE: $message to ${user.id}")
            ws.outgoing.sendPacket(message)
        }
    }
}

suspend fun DefaultWebSocketServerSession.websocketReadProcess(user: User) {
    try {
        var moveJob: Job? = null
        var interfactionJob: Job? = null
        while (true) {
            val packet = incoming.receivePacket() as? ClientPacket ?: error("Trying to send an invalid packet")
            when (packet) {
                is ClientSetLang -> {
                    user.language = Language[packet.lang] ?: Language.ENGLISH
                }
                is ClientSay -> {
                    // Everyone on the room will read the text
                    user.container?.send(EntitySay(user.id, packet.text))
                }
                is ClientRequestMove -> {
                    moveJob?.cancel()
                    moveJob = launch(coroutineContext) {
                        user.moveTo(packet.x, packet.y)
                    }
                }
                is ClientRequestInteract -> {
                    val entity = user.container?.entities?.firstOrNull { it.id == packet.entityId }
                    if (entity is Npc) {
                        interfactionJob = launch(coroutineContext) {
                            entity.onUserInteraction(user)
                        }
                    }
                }
                is ClientInteractionResult -> {
                    val entity = user.container?.entities?.firstOrNull { it.id == packet.entityId }
                    if (entity is Npc) {
                        val conversation = entity.conversationsById[packet.interactionId]
                        if (conversation != null && conversation.user == user) {
                            conversation.onUserSelection.offer(packet.selection)
                        }
                    }
                }
                is Ping -> {
                    user.send(Pong(packet.pingTime))
                }
            }
            //println(packet)
        }
    } catch (e: ClosedReceiveChannelException) {
        // Do nothing!
    }
}

operator fun File.get(name: String): File = File(this, name)

suspend fun ReceiveChannel<Frame>.receivePacket(): BasePacket {
    val frame = this.receive() as Frame.Text
    val frameText = frame.readText()
    return deserializePacket(frameText)
}

suspend fun <T : BasePacket> SendChannel<Frame>.sendPacket(packet: T, clazz: KClass<T> = packet::class as KClass<T>) {
    send(Frame.Text(serializePacket(packet, clazz)))
}

//suspend inline fun <reified T : BasePacket> SendChannel<Frame>.sendPacket(packet: T) {
//    send(Frame.Text(serializePacket(packet, T::class)))
//}
