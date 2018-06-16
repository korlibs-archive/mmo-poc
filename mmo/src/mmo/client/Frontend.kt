package mmo.client

import com.soywiz.korge.*
import com.soywiz.korge.input.*
import com.soywiz.korge.scene.*
import com.soywiz.korge.view.*
import com.soywiz.korim.color.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.net.ws.*
import mmo.protocol.*
import kotlin.coroutines.experimental.*
import kotlin.reflect.*

fun main(args: Array<String>) = Korge(MmoModule())

open class MmoModule : Module() {
    override val mainScene = MainScene::class
    override suspend fun init(injector: AsyncInjector) {
        injector
            .mapPrototype { MainScene(get()) }
            .mapSingleton { ConnectionService() }
    }
}

class ConnectionService : AsyncDependency {
    var ws: WebSocketClient? = null

    override suspend fun init() {
        try {
            ws = WebSocketClient("ws://127.0.0.1:8080/")

            launch(coroutineContext) {
                while (ws != null) {
                    val packet = ws?.receivePacket()
                    println("CLIENT RECEIVED: $packet")
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    suspend inline fun <reified T : Any> send(packet: T) = run { ws?.sendPacket(packet, T::class) }
    suspend fun receive(): Any? = ws?.receivePacket()
}

class MainScene(
    val connection: ConnectionService
) : Scene() {
    override suspend fun sceneInit(sceneView: Container) {
        sceneView.addChild(views.solidRect(100, 100, Colors.RED).apply {
            alpha = 0.5
            mouse {
                onOver {
                    println("OVER")
                    alpha = 1.0
                }
                onOut {
                    println("OUT")
                    alpha = 0.5
                }
                onClick {
                    println("CLICK")
                }
            }
        })
        connection.send(Say("HELLO FROM CLIENT"))
    }
}

suspend fun <T : Any> WebSocketClient.sendPacket(obj: T, clazz: KClass<T>) {
    this.send(serializePacket(obj, clazz))
}

suspend fun WebSocketClient.receivePacket(): Any {
    return deserializePacket(this.onStringMessage.waitOne())
}
