package mmo.server

import com.soywiz.korio.*
import io.ktor.application.*
import io.ktor.content.*
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
import java.io.*
import kotlin.coroutines.experimental.*
import kotlin.reflect.*

class MySession(val userId: String)

object Experiments {
    @JvmStatic fun main(args: Array<String>) {
        val packet = serializePacket(Say("HELLO"), Say::class)
        println(packet)
        println(deserializePacket(packet))
    }
}

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080) {
        install(WebSockets)
        install(Sessions) {
            cookie<MySession>("oauthSampleSessionId")
        }

        val webFolder =
            listOf(".", "..", "../..", "../../..").map { File(it).absoluteFile["web"] }.firstOrNull { it.exists() }

        println("webFolder:$webFolder")

        val mainScene = ServerScene("lobby")
        mainScene.add(Princess().apply { start() })

        routing {
            webSocket("/") {
                delay(100) // @TODO: Remove once client start receiving messages from websockets from the very beginning
                val sendQueue = Channel<ServerPacket>(Channel.UNLIMITED)

                val user = User(object : PacketSendChannel {
                    override fun send(packet: ServerPacket) {
                        println("OFFERING: $packet")
                        sendQueue.offer(packet)
                    }
                })

                websocketWriteProcess(coroutineContext, this, user, sendQueue)
                user.send(SetUserId(user.id))

                try {
                    mainScene.add(user)
                    user.sendAllEntities(user.container)
                    websocketReadProcess(user)
                } finally {
                    mainScene.remove(user)
                }

            }

            static("/") {
                if (webFolder != null) files(webFolder)
                resources()
                default("index.html")
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
            println("SENDING MESSAGE: $message to ${user.id}")
            ws.outgoing.sendPacket(message)
        }
    }
}

suspend fun DefaultWebSocketServerSession.websocketReadProcess(user: User) {
    while (true) {
        val packet =
            incoming.receivePacket() as? ClientPacket ?: error("Trying to send an invalid packet")
        when (packet) {
            is Say -> {
                // Everyone on the room will read the text
                user.container?.send(EntitySay(user.id, packet.text))
            }
        }
        println(packet)
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
