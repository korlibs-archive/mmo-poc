package mmo.server

import com.soywiz.korge.tiled.*
import mmo.protocol.*
import kotlin.coroutines.*

open class EntityContainer : PacketSendChannel {
    val entities = LinkedHashSet<Entity>()
    inline val npcs get() = entities.filterIsInstance<Npc>()
    inline val users get() = entities.filterIsInstance<User>()

    fun add(entity: Entity) {
        if (entity.container != null) {
            entity.container?.remove(entity)
        }
        entity.container = this
        entities += entity
        sendEntityAppear(entity)
    }

    fun remove(entity: Entity) {
        entities -= entity
        entity.container = null
        send(EntityDisappear(entity.id))
    }

    override fun send(packet: ServerPacket) {
        for (user in users) user.send(packet)
    }

    fun sendBut(but: Entity, packet: ServerPacket) {
        for (user in users) if (user != but) user.send(packet)
    }

    fun addUser(user: User) {
        add(user)
        user.sendAllEntities(user.container)
        userAppeared(user)
    }

    fun userAppeared(user: User) {
        for (npc in npcs) {
            npc.onUserAppeared(user)
        }
    }
}

fun PacketSendChannel.sendAllEntities(container: EntityContainer?) {
    for (entity in container?.entities?.toList() ?: listOf()) sendEntityAppear(entity)
}

open class ServerScene(val name: String, val map: TiledMapData, val coroutineContext: CoroutineContext) :
    EntityContainer() {
}
