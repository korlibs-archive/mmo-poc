package mmo.protocol

import kotlinx.serialization.*

@Serializable
data class Say(val text: String)
