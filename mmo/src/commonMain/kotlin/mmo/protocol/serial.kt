package mmo.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.reflect.*

@Serializable
data class Packet(val type: String, val payload: String)

//val KClass<*>.serialName get() = this.simpleName
//val KClass<*>.serialName get() = serializer().serialClassDesc.name
@OptIn(InternalSerializationApi::class)
val KClass<*>.serialName: String get() = serializer().descriptor.serialName

val typesByName by lazy {
    serializableClasses.associateBy { it.serialName }
}

@OptIn(InternalSerializationApi::class)
fun <T : Any> serializePacket(obj: T, clazz: KClass<T>): String {
    return Json.encodeToString(Packet(clazz.serialName, Json.encodeToString(clazz.serializer(), obj)))
}

@OptIn(InternalSerializationApi::class)
inline fun deserializePacket(str: String): BasePacket {
    val packet = Json.decodeFromString(Packet::class.serializer(), str)
    val clazz = typesByName[packet.type]
            ?: error("Class '${packet.type}' is not available for serializing :: available=${typesByName.keys}")
    return Json.decodeFromString(clazz.serializer(), packet.payload)
}
