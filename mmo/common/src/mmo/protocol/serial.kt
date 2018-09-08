package mmo.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.reflect.*

@Serializable
data class Packet(val type: String, val payload: String)

//val KClass<*>.serialName get() = this.simpleName
//val KClass<*>.serialName get() = serializer().serialClassDesc.name
val KClass<*>.serialName get() = serializer().descriptor.name

val typesByName = serializableClasses.associateBy { it.serialName }

fun <T : Any> serializePacket(obj: T, clazz: KClass<T>): String {
    return JSON.stringify(Packet(clazz.serialName, JSON.stringify(clazz.serializer(), obj)))
}

inline fun deserializePacket(str: String): BasePacket {
    val packet = JSON.parse(Packet::class.serializer(), str)
    val clazz = typesByName[packet.type]
            ?: error("Class '${packet.type}' is not available for serializing :: available=${typesByName.keys}")
    return JSON.parse(clazz.serializer(), packet.payload)
}
