package mmo.client

import com.soywiz.korge.*
import com.soywiz.korinject.*
import kotlin.browser.*

fun main(args: Array<String>) {
    val secure = document.location?.protocol == "https:"
    val protocol = if (secure) "wss" else "ws"
    val endpoint = ServerEndPoint("$protocol://${document.location?.host}/")
    console.log("endpoint: $endpoint")

    Korge(MmoModule(), injector = AsyncInjector().apply {
        this.mapInstance(endpoint)
    })
}
