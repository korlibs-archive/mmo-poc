package mmo.server

import com.soywiz.klock.*
import com.soywiz.korge.tiled.*
import com.soywiz.korio.*
import com.soywiz.korio.async.*
import com.soywiz.korio.file.std.*
import com.soywiz.korio.lang.*
import com.soywiz.korio.util.i18n.*
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import mmo.protocol.*
import mmo.server.script.*
import mmo.server.storage.*
import mmo.server.util.*
import mmo.shared.*
import java.io.*
import java.io.IOException
import java.util.*
import java.util.Date
import java.util.zip.*
import javax.script.*
import kotlin.coroutines.*
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
    val distVfs = webFolder["../dist"].toVfs()

    println("webFolder:$webFolder")

    val tilemap = webVfs["library1.tmx"].readTiledMapData()
    println("Post TileMap")
    val mainScene = ServerScene("lobby", tilemap, gameContext)

    println("Building Princess NPC")
    Princess(mainScene).apply { start() }

    val ktsEngine = ScriptEngineManager().getEngineByExtension("kts")

    val scriptedNpcs =
        tilemap.objectLayers.flatMap { it.objects }.filter { it.info.type == "npc" && "script" in it.objprops }

    //println("Ignoring scriptedNpcs...")
    println("Loading scriptedNpcs...")
    val time = measureTime {
        for (scriptedNpc in scriptedNpcs) {
            try {
                KScriptNpc(ktsEngine, mainScene, scriptedNpc.name).apply { start() }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
    println("Ok (${time.seconds}s)")

    val redisHost = System.getenv("REDIS_HOST") ?: "127.0.0.1"

    println("Before Storage")
    println("Redis Host: $redisHost")

    val storage: Storage = try {
        //val redis = RedisClient(InetSocketAddress(redisHost, 6379))
        //redis.set("mmo", "running")
        //RedisStorage(redis, prefix = "mmo-")
        //InmemoryStorage()
        InmemoryStorage()
    } catch (e: IOException) {
        e.printStackTrace()
        println("Couldn't use REDIS storage")
        InmemoryStorage()
    }

    println("Storage: $storage")

    println("Packing app...")
    val bundleBytes = try {
        //webVfs["game.js"].miniWebpack().bundlePatches().toByteArray()
        webVfs["mmo.js"].miniWebpack().bundlePatches().toByteArray()
    } catch (e: Throwable) {
        byteArrayOf()
    }
    println("Compression packed app...")

    val bundleBytesGzip = bundleBytes?.gzipCompress()
    val indexHtmlString = webVfs["index.html"].readString()
    val patchedIndexHtmlString = indexHtmlString.replace(
        "<script data-main=\"game\" src=\"require.min.js\" type=\"text/javascript\"></script>",
        "<script src=\"require.min.js\" type=\"text/javascript\"></script><script src=\"bundle.js\" type=\"text/javascript\"></script>"
        //"<script src=\"bundle.js\" type=\"text/javascript\"></script>"
    )

    val indexHtmlBytes = indexHtmlString.toByteArray(UTF8)
    val patchedIndexHtmlBytes = patchedIndexHtmlString.toByteArray(UTF8)
    val builtTime = Date()

    println("Game assets ready")


    val bundleJsFile = File.createTempFile("mmo-poc", "bundle.js").apply { deleteOnExit() }
    bundleJsFile.writeBytes(bundleBytes)

    val server = embeddedServer(Netty, port = 8080) {
        // region Feature Installation
        install(WebSockets)
        install(ConditionalHeaders)
        install(Sessions) {
            cookie<MySession>("MMO_SESSION")
        }
        // endregion

        routing {
            get("/bundle.js") {
                //if (call.request.headers[HttpHeaders.AcceptEncoding]?.contains("gzip", ignoreCase = true) == true) {
                //    call.response.header("Content-Encoding", "gzip")
                //    call.respondBytes(bundleBytesGzip) {
                //        versions += LastModifiedVersion(builtTime)
                //    }
                //} else {
                call.respondFile(bundleJsFile)
                //call.respondBytes(bundleBytes) {
                //    versions += LastModifiedVersion(builtTime)
                //}
                //}
                finish() // @TODO: Move this to respondFile
            }
            get("/") {
                val userUid = call.getUserId()
                //call.respondBytes(patchedIndexHtmlBytes) {
                call.respondBytes(indexHtmlBytes) {
                    versions += LastModifiedVersion(builtTime)
                }
                finish() // @TODO: Move this to respondFile
            }
            webSocket("/ws") {
                val ws = this
                try {
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
                            this.lookAt(CharDirection.DOWN)
                            this.setPositionTo(4, 6)
                        }

                        websocketWriteProcess(gameContext, ws, user, sendQueue)

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
                } catch (e: Throwable) {
                    e.printStackTrace()
                    throw e
                }
            }

            static("/") {
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
    if (session?.uuid != userUuid) {
        sessions.set(MySession(userUuid))
    }
    return userUuid
}

fun websocketWriteProcess(
    coroutineContext: CoroutineContext,
    //ws: DefaultWebSocketServerSession,
    ws: WebSocketServerSession,
    user: User,
    sendChannel: Channel<ServerPacket>
) {
    launchImmediately(coroutineContext) {
        sendChannel.consumeEach { message ->
            //println("SENDING MESSAGE: $message to ${user.id}")
            ws.outgoing.sendPacket(message)
        }
    }
}

//suspend fun DefaultWebSocketServerSession.websocketReadProcess(user: User) {
suspend fun WebSocketServerSession.websocketReadProcess(user: User) {
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

fun ByteArray.gzipCompress(level: Int = 9): ByteArray = ByteArrayOutputStream(this.size).apply {
    MyGZIPOutputStream(this, level).use { zipStream -> zipStream.write(this@gzipCompress) }
}.toByteArray()

internal class MyGZIPOutputStream(out: OutputStream, level: Int) : GZIPOutputStream(out) {
    init {
        def.setLevel(level)
    }
}

fun String.bundlePatches(): String {
    //function isInheritanceFromInterface(ctor, iface) {
    //    if (ctor === iface)
    //        return true;
    //    var metadata = ctor.$metadata$;
    //    if (metadata != null) {
    //        var interfaces = metadata.interfaces;
    //        for (var i = 0; i < interfaces.length; i++) {
    //            if (isInheritanceFromInterface(interfaces[i], iface)) {
    //                return true;
    //            }
    //        }
    //    }
    //    var superPrototype = ctor.prototype != null ? Object.getPrototypeOf(ctor.prototype) : null;
    //    var superConstructor = superPrototype != null ? superPrototype.constructor : null;
    //    return superConstructor != null && isInheritanceFromInterface(superConstructor, iface);
    //}
    return Regex(
        "function isInheritanceFromInterface.*?return superConstructor.*?\\}",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
    ).replace(this) {
        println("*********** Patched isInheritanceFromInterface")
        // language=js
        """
            function getAllInterfaces(ctor) {
                if (ctor.${'$'}metadata${'$'} == null) ctor.${'$'}metadata${'$'} = {};
                var metadata = ctor.${'$'}metadata${'$'};
                if (metadata._allInterfaces === undefined) {
                    var allInterfaces = metadata._allInterfaces = [];

                    allInterfaces.push(ctor);

                    var interfaces = metadata.interfaces;
                    if (interfaces !== undefined) {
                        for (var i = 0; i < interfaces.length; i++) {
                            allInterfaces.push.apply(allInterfaces, getAllInterfaces(interfaces[i]));
                        }
                    }

                    var superPrototype = ctor.prototype != null ? Object.getPrototypeOf(ctor.prototype) : null;
                    var superConstructor = superPrototype != null ? superPrototype.constructor : null;
                    if (superConstructor != null) {
                        allInterfaces.push.apply(allInterfaces, getAllInterfaces(superConstructor));
                    }
                    // @TODO: fast remove duplicates
                    //console.log('getAllInterfaces', ctor.name, metadata._allInterfaces);
                    metadata._allInterfaces = metadata._allInterfaces.filter(function(item, pos, self) { return self.indexOf(item) === pos; });
                }
                return metadata._allInterfaces;
            }

            function isInheritanceFromInterface(ctor, iface) {
                if (ctor === iface) return true;
                return getAllInterfaces(ctor).indexOf(iface) >= 0;
            }
        """.trimIndent()
    }
}
