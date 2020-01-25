package mmo.protocol

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.reflect.*

@Serializable
data class Packet(val type: String, val payload: String)

//val KClass<*>.serialName get() = this.simpleName
//val KClass<*>.serialName get() = serializer().serialClassDesc.name
@UseExperimental(ImplicitReflectionSerializer::class)
val KClass<*>.serialName get() = serializer().descriptor.name

val typesByName by lazy {
    serializableClasses.associateBy { it.serialName }
}

@UseExperimental(ImplicitReflectionSerializer::class)
fun <T : Any> serializePacket(obj: T, clazz: KClass<T>): String {
    return Json.stringify(Packet(clazz.serialName, Json.stringify(clazz.serializer(), obj)))
}

@UseExperimental(ImplicitReflectionSerializer::class)
inline fun deserializePacket(str: String): BasePacket {
    val packet = Json.parse(Packet::class.serializer(), str)
    val clazz = typesByName[packet.type]
            ?: error("Class '${packet.type}' is not available for serializing :: available=${typesByName.keys}")
    return Json.parse(clazz.serializer(), packet.payload)
}
