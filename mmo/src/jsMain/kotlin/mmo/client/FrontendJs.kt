package mmo.client

import com.soywiz.klogger.*
import com.soywiz.korge.*
import com.soywiz.korinject.*
import com.soywiz.korio.async.*
import kotlinx.coroutines.*
import kotlin.browser.*

fun main(args: Array<String>) {
    //Logger("tilemap").level = Logger.Level.TRACE
    val secure = document.location?.protocol == "https:"
    val protocol = if (secure) "wss" else "ws"
    val endpoint = ServerEndPoint("$protocol://${document.location?.host}/ws/")
    console.log("endpoint: $endpoint")

    launchImmediately(Dispatchers.Default) {
        Korge(Korge.Config(MmoModule(), debug = false, injector = AsyncInjector().apply {
            this.mapInstance(endpoint)
        }))
    }
}
