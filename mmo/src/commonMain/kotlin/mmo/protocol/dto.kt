package mmo.protocol

import kotlinx.serialization.*
import mmo.shared.*

// @TODO: Would be nice if only @Serializable was needed here
interface BasePacket
interface ClientPacket : BasePacket
interface ServerPacket : BasePacket
enum class QuestStatus { NONE, NEW, UNCOMPLETE, COMPLETE }

// Client/Server Packets
@Serializable
data class Ping(val pingTime: Long) : ClientPacket, ServerPacket

@Serializable
data class Pong(val pingTime: Long) : ClientPacket, ServerPacket

// Client Packets
@Serializable
data class ClientSetLang(val lang: String) : ClientPacket

@Serializable
data class ClientSay(val text: String) : ClientPacket

@Serializable
data class ClientRequestMove(val x: Int, val y: Int) : ClientPacket

@Serializable
data class ClientRequestInteract(val entityId: Long) : ClientPacket

@Serializable
data class ClientInteractionResult(val entityId: Long, val interactionId: Long, var selection: Int) : ClientPacket

// Server Packets
@Serializable
data class UserSetId(val entityId: Long) : ServerPacket

@Serializable
data class UserBagUpdate(val item: String, val amount: Int) : ServerPacket

@Serializable
data class SkinInfo(
    val body: Int,
    val armor: Int,
    val head: Int,
    val hair: Int
)

@Serializable
data class QuestUpdate(
    val entityId: Long,
    val status: QuestStatus
) : ServerPacket {
}

@Serializable
data class EntityUpdates(
    val currentTime: Long,
    val updates: List<EntityUpdate>
) : ServerPacket {
    @Serializable
    data class EntityUpdate(
        val entityId: Long,
        val skin: SkinInfo,
        val srcX: Double, val srcY: Double, val srcTime: Long,
        val dstX: Double, val dstY: Double, val dstTime: Long,
        val direction: CharDirection
    )
}

@Serializable
data class EntityDisappear(val entityId: Long) : ServerPacket

@Serializable
data class EntitySay(val entityId: Long, val text: String) : ServerPacket

@Serializable
data class EntityLookDirection(
    val entityId: Long,
    val direction: CharDirection
) : ServerPacket

// Server Packets: Conversation

@Serializable
data class ConversationStart(val id: Long, val npcId: Long) : ServerPacket

@Serializable
data class ConversationClose(val id: Long) : ServerPacket

@Serializable
data class ConversationMoodSet(val id: Long, val mood: String) : ServerPacket

@Serializable
data class ConversationText(val id: Long, val text: String) : ServerPacket

@Serializable
data class ConversationImage(val id: Long, val image: String) : ServerPacket

@Serializable
data class ConversationOptions(val id: Long, val text: String, val options: List<String>) : ServerPacket

// @TODO: It is possible to list @Serializable classes?
val serializableClasses = listOf(
    // Client/Server
    Ping::class,
    Pong::class,

    // Client
    ClientSetLang::class,
    ClientSay::class,
    ClientRequestInteract::class,
    ClientRequestMove::class,
    ClientInteractionResult::class,

    // Server

    // To the user
    UserSetId::class,
    UserBagUpdate::class,

    // Entities
    EntityUpdates::class,
    EntityDisappear::class,
    EntitySay::class,
    EntityLookDirection::class,

    // Conversations
    ConversationStart::class,
    ConversationClose::class,
    ConversationMoodSet::class,
    ConversationText::class,
    ConversationImage::class,
    ConversationOptions::class,

    // Quests
    QuestUpdate::class
)
