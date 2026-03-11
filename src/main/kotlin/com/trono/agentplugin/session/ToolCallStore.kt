package com.trono.agentplugin.session

import java.time.Instant

data class ToolCallState(
    val sessionId: String,
    val runId: String?,
    val toolCallId: String,
    val name: String,
    var status: String,
    var lastUpdatedAt: String = Instant.now().toString(),
)

class ToolCallStore {
    private val bySession = mutableMapOf<String, MutableMap<String, ToolCallState>>()

    fun upsert(sessionId: String, state: ToolCallState) {
        val bucket = bySession.getOrPut(sessionId) { mutableMapOf() }
        bucket[state.toolCallId] = state
    }

    fun updateStatus(sessionId: String, toolCallId: String, status: String): ToolCallState? {
        val bucket = bySession[sessionId] ?: return null
        val state = bucket[toolCallId] ?: return null
        state.status = status
        state.lastUpdatedAt = Instant.now().toString()
        return state
    }

    fun get(sessionId: String, toolCallId: String): ToolCallState? = bySession[sessionId]?.get(toolCallId)

    fun list(sessionId: String): List<ToolCallState> = bySession[sessionId]?.values?.toList() ?: emptyList()
}
