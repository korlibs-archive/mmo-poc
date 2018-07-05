package mmo.server

import com.soywiz.korge.tiled.*
import com.soywiz.korio.*
import com.soywiz.korio.async.*
import com.soywiz.korio.async.EventLoop
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

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080) {
        runBlocking {
            install(WebSockets)
            install(ConditionalHeaders)

            Thread {
                EventLoop.main {
                    globalEventLoop = this
                    while (true) {
                        sleepNextFrame()
                    }
                }
            }.start()

            val webFolder =
                listOf(".", "..", "../..", "../../..").map { File(it).absoluteFile["web"] }.firstOrNull { it.exists() }
                        ?: error("Can't find 'web' folder")
            val webVfs = webFolder.toVfs()

            println("webFolder:$webFolder")

            val mainScene = ServerScene("lobby", webVfs["library1.tmx"].readTiledMapData())
            Princess(mainScene).apply { start() }

            routing {
                static("/") {
                    webSocket {
                        delay(100) // @TODO: Remove once client start receiving messages from websockets from the very beginning
                        val sendQueue = Channel<ServerPacket>(Channel.UNLIMITED)

                        val user = User(object : PacketSendChannel {
                            override fun send(packet: ServerPacket) {
                                //println("OFFERING: $packet")
                                sendQueue.offer(packet)
                            }
                        }).apply {
                            this.skin = "user0"
                            this.setPositionTo(4, 4)
                        }

                        websocketWriteProcess(coroutineContext, this, user, sendQueue)
                        user.send(UserSetId(user.id))

                        try {
                            mainScene.add(user)
                            user.sendAllEntities(user.container)
                            websocketReadProcess(user)
                        } finally {
                            mainScene.remove(user)
                        }
                    }

                    default(File(webFolder, "index.html"))
                    files(webFolder)
                }
            }
        }
    }.start(wait = true)
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
                    moveJob = launch {
                        user.moveTo(packet.x, packet.y)
                    }
                }
                is ClientRequestInteract -> {
                    val entity = user.container?.entities?.firstOrNull { it.id == packet.entityId }
                    if (entity is Npc) {
                        interfactionJob = launch {
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
