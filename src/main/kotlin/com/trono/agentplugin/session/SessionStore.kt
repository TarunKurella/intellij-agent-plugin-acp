package com.trono.agentplugin.session

data class SessionState(
    val sessionId: String,
    val title: String,
    var status: String,
)

class SessionStore {
    private val sessions = linkedMapOf<String, SessionState>()
    var activeSessionId: String? = null

    fun upsert(session: SessionState) {
        sessions[session.sessionId] = session
        if (activeSessionId == null) activeSessionId = session.sessionId
    }

    fun list(): List<SessionState> = sessions.values.toList()

    fun active(): SessionState? = activeSessionId?.let { sessions[it] }

    fun setActive(sessionId: String): Boolean {
        if (!sessions.containsKey(sessionId)) return false
        activeSessionId = sessionId
        return true
    }

    fun remove(sessionId: String): Boolean {
        val existed = sessions.remove(sessionId) != null
        if (!existed) return false
        if (activeSessionId == sessionId) {
            activeSessionId = sessions.keys.firstOrNull()
        }
        return true
    }

    fun firstSessionId(): String? = sessions.keys.firstOrNull()
}
