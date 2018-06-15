package mmo.server

import io.ktor.application.*
import io.ktor.content.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.experimental.channels.*
import mmo.protocol.*
import java.io.*

class MySession(val userId: String)

object Experiments {
    @JvmStatic fun main(args: Array<String>) {
        val packet = serializePacket(Say("HELLO"))
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

        val webFolder = listOf(".", "..", "../..", "../../..").map { File(it).absoluteFile["web"] }.firstOrNull { it.exists() }

        println("webFolder:$webFolder")

        routing {
            webSocket("/") {
                this.outgoing.sendPacket(Say("HELLO FROM SERVER"))
                while (true) {
                    val packet = incoming.receivePacket()
                    println(packet)
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

operator fun File.get(name: String): File = File(this, name)

suspend fun ReceiveChannel<Frame>.receivePacket(): Any {
    val frame = this.receive() as Frame.Text
    val frameText = frame.readText()
    return deserializePacket(frameText)
}

suspend inline fun <reified T : Any> SendChannel<Frame>.sendPacket(packet: T) {
    send(Frame.Text(serializePacket(packet)))
}
