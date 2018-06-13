package mmopoc

import io.ktor.application.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080) {
        install(WebSockets)
        routing {
            get("/") {
                call.respondText("HI")
            }
            webSocket("/") {
                println("WEBSOCKET")
            }
        }
    }.start(wait = true)
}
