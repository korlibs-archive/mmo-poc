package mmo.protocol

import kotlinx.serialization.*

// @TODO: Would be nice if only @Serializable was needed here
@Serializable
interface BasePacket

interface ClientPacket : BasePacket
interface ServerPacket : BasePacket

// Client Packets
@Serializable data class Say(val text: String) : ClientPacket

// Server Packets
@Serializable data class SetUserId(val entityId: Long) : ServerPacket
@Serializable data class EntityAppear(val entityId: Long, val x: Double, val y: Double, val skin: String) : ServerPacket
@Serializable data class EntityDisappear(val entityId: Long) : ServerPacket

@Serializable data class EntitySay(val entityId: Long, val text: String) : ServerPacket
@Serializable data class EntityMove(
    val entityId: Long,
    val srcX: Double,
    val srcY: Double,
    val dstX: Double,
    val dstY: Double,
    val elapsedTime: Double,
    val totalTime: Double
) : ServerPacket

// Conversation

@Serializable data class ConversationStart(val id: Long, val npcId: Long) : ServerPacket
@Serializable data class ConversationClose(val id: Long) : ServerPacket
@Serializable data class ConversationMoodSet(val id: Long, val mood: String) : ServerPacket
@Serializable data class ConversationText(val id: Long, val text: String) : ServerPacket
@Serializable data class ConversationOptions(val id: Long, val text: String, val options: List<String>) : ServerPacket
