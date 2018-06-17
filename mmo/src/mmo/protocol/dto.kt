package mmo.protocol

import kotlinx.serialization.*
import mmo.shared.*

// @TODO: Would be nice if only @Serializable was needed here
@Serializable
interface BasePacket

interface ClientPacket : BasePacket
interface ServerPacket : BasePacket

// Client Packets
@Serializable
data class ClientSay(val text: String) : ClientPacket

@Serializable
data class ClientRequestMove(val x: Double, val y: Double) : ClientPacket

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
data class EntityAppear(val entityId: Long, val x: Double, val y: Double, val skin: String, val direction: CharDirection) : ServerPacket

@Serializable
data class EntityDisappear(val entityId: Long) : ServerPacket

@Serializable
data class EntitySay(val entityId: Long, val text: String) : ServerPacket

@Serializable
data class EntityMove(
    val entityId: Long,
    val srcX: Double,
    val srcY: Double,
    val dstX: Double,
    val dstY: Double,
    val elapsedTime: Double,
    val totalTime: Double
) : ServerPacket

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
data class ConversationOptions(val id: Long, val text: String, val options: List<String>) : ServerPacket

// @TODO: It is possible to list @Serializable classes?
val serializableClasses = listOf(
    // Client
    ClientSay::class,
    ClientRequestInteract::class,
    ClientRequestMove::class,
    ClientInteractionResult::class,

    // Server

    // To the user
    UserSetId::class,
    UserBagUpdate::class,

    // Entities
    EntityAppear::class,
    EntityDisappear::class,
    EntitySay::class,
    EntityMove::class,
    EntityLookDirection::class,

    // Conversations
    ConversationStart::class,
    ConversationClose::class,
    ConversationMoodSet::class,
    ConversationText::class,
    ConversationOptions::class
)
