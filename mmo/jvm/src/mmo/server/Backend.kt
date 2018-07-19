package mmo.server

import com.soywiz.korge.tiled.*
import com.soywiz.korio.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.i18n.Language
import io.ktor.application.*
import io.ktor.content.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.*
import mmo.protocol.*
import mmo.server.script.*
import mmo.shared.*
import java.io.*
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

fun main(args: Array<String>) = Korio {
    val webFolder =
        listOf(".", "..", "../..", "../../..").map { File(it).absoluteFile["web"] }.firstOrNull { it.exists() }
                ?: error("Can't find 'web' folder")
    val webVfs = webFolder.toVfs()

    println("webFolder:$webFolder")

    val tilemap = webVfs["library1.tmx"].readTiledMapData()
    println("Post TileMap")
    val mainScene = ServerScene("lobby", tilemap)

    println("Building Princess NPC")
    Princess(mainScene).apply { start() }

    val server = embeddedServer(Netty, port = 8080) {
        install(WebSockets)
        install(ConditionalHeaders)

        //tilemapLog.level = Logger.Level.TRACE
        //Logger.defaultLevel = Logger.Level.TRACE

        println("Pre TileMap")


        println("Pre Routing")

        routing {
            static("/") {
                webSocket {
                    launch(gameContext) {
                        //println(Thread.currentThread())
                        delay(100) // @TODO: Remove once client start receiving messages from websockets from the very beginning
                        val sendQueue = Channel<ServerPacket>(Channel.UNLIMITED)

                        val user = User(object : PacketSendChannel {
                            override fun send(packet: ServerPacket) {
                                //println("OFFERING: $packet")
                                sendQueue.offer(packet)
                            }
                        }).apply {
                            this.skinBody = Skins.Body.chubby
                            this.skinArmor = Skins.Armor.armor1
                            this.skinHead = Skins.Head.elf1
                            this.skinHair = Skins.Hair.pelo1
                            this.setPositionTo(4, 4)
                        }

                        websocketWriteProcess(coroutineContext, this@webSocket, user, sendQueue)

                        try {
                            mainScene.addUser(user)
                            user.send(UserSetId(user.id))
                            websocketReadProcess(user)
                        } finally {
                            mainScene.remove(user)
                        }
                    }.join()
                }

                default(File(webFolder, "index.html"))
                files(webFolder)
            }
        }

        println("Post Routing")

        println("Post runBlocking")
    }
    println("Pre Server Start")
    server.start(wait = true)
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
