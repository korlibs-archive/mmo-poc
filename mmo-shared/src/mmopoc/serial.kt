package mmopoc

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlin.reflect.*

@Serializable
data class Packet(val type: String, val payload: String)

//val KClass<*>.serialName get() = this.simpleName
val KClass<*>.serialName get() = serializer().serialClassDesc.name

val typesByName = types.associateBy { it.serialName }

inline fun <reified T : Any> serializePacket(obj: T): String {
    return JSON.stringify(Packet(obj::class.serialName!!, JSON.stringify(obj)))
}

inline fun deserializePacket(str: String): Any {
    val packet = JSON.parse(Packet::class.serializer(), str)
    val clazz = typesByName[packet.type]
            ?: error("Class '${packet.type}' is not available for serializing :: available=${typesByName.keys}")
    return JSON.parse(clazz.serializer(), packet.payload)
}
