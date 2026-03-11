package com.trono.agentplugin.session

import java.time.Instant

enum class MessageKind {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
}

data class SessionMessage(
    val sessionId: String,
    val runId: String? = null,
    val kind: MessageKind,
    val text: String,
    val at: String = Instant.now().toString(),
)

class MessageStore {
    private val messages = mutableMapOf<String, MutableList<SessionMessage>>()

    fun append(message: SessionMessage) {
        val bucket = messages.getOrPut(message.sessionId) { mutableListOf() }
        bucket.add(message)
    }

    fun list(sessionId: String): List<SessionMessage> = messages[sessionId]?.toList() ?: emptyList()
}
