package com.trono.agentplugin.protocol

data class InitializeReq(
    val client: ClientInfo,
    val workspacePath: String,
)

data class ClientInfo(
    val name: String,
    val version: String,
)

data class InitializeRes(
    val protocolVersion: String? = null,
    val server: ServerInfo,
    val capabilities: Capabilities,
)

data class ServerInfo(val name: String, val version: String)

data class Capabilities(
    val sessions: Boolean,
    val streaming: Boolean,
    val tools: List<String>,
)

data class CreateSessionReq(
    val workspacePath: String,
    val title: String = "Default",
    val model: String = "claude-sonnet-4.5",
    val mode: String = "default",
)

data class CreateSessionRes(val sessionId: String, val status: String)
data class DeleteSessionReq(val sessionId: String)
data class DeleteSessionRes(val deleted: Boolean)

data class SessionInfo(
    val sessionId: String,
    val title: String,
    val status: String,
    val updatedAt: String,
)

data class ListSessionsRes(val sessions: List<SessionInfo>)

data class PromptReq(
    val sessionId: String,
    val prompt: String,
    val contextRefs: List<Map<String, String>> = emptyList(),
)

data class PromptAccepted(val accepted: Boolean, val runId: String)

data class CancelReq(val sessionId: String, val runId: String? = null)

data class CancelRes(val cancelled: Boolean)

data class SetModelReq(val sessionId: String, val model: String)
data class SetModeReq(val sessionId: String, val mode: String)

data class PatchPreviewReq(val path: String, val diff: String)
data class PatchPreviewRes(
    val previewId: String,
    val renderedDiff: String,
    val applicable: Boolean = false,
    val reason: String? = null,
)
data class PatchCommitReq(val previewId: String, val confirm: Boolean, val sessionId: String? = null)
data class PatchCommitRes(val applied: Boolean, val backupId: String? = null, val reason: String? = null)
data class PatchRollbackReq(val backupId: String)
data class PatchRollbackRes(val rolledBack: Boolean)
data class PatchPreviewGetReq(val previewId: String)
data class PatchPreviewGetRes(
    val previewId: String,
    val path: String,
    val oldText: String? = null,
    val newText: String? = null,
    val diff: String? = null,
    val applicable: Boolean = false,
    val reason: String? = null,
)

data class PatchPreviewLastReq(val sessionId: String)
data class PatchPreviewLastRes(val previewId: String? = null, val applicable: Boolean = false, val reason: String? = null)

data class HealthReq(val ping: String = "ping")
data class HealthRes(
    val ok: Boolean,
    val mode: String,
    val activeRuns: Int,
    val sessions: Int,
)

data class GetCurrentFileReq(val sessionId: String, val path: String, val cursorOffset: Int = 0)
data class GetCurrentFileRes(val path: String, val language: String, val content: String, val cursorOffset: Int)

data class GetSelectionReq(val sessionId: String, val path: String, val startOffset: Int, val endOffset: Int)
data class GetSelectionRes(val path: String, val startOffset: Int, val endOffset: Int, val text: String)

data class ReadFileReq(val sessionId: String, val path: String, val fromLine: Int? = null, val toLine: Int? = null)
data class ReadFileRes(val content: String)

data class WriteFileReq(val sessionId: String, val path: String, val content: String)
data class WriteFileRes(val written: Boolean, val path: String)

data class FindFilesReq(val sessionId: String, val glob: String = "**/*", val limit: Int = 200)
data class FindFilesRes(val paths: List<String>)

data class SearchTextReq(
    val sessionId: String,
    val query: String,
    val glob: String = "**/*",
    val caseSensitive: Boolean = false,
    val maxMatches: Int = 200,
)
data class SearchMatch(val path: String, val line: Int, val text: String)
data class SearchTextRes(val matches: List<SearchMatch>)

data class RunTestReq(val sessionId: String, val target: String)
data class RunTestSummary(val started: Boolean, val execId: String)
data class RunTestRes(val summary: RunTestSummary, val failures: List<Map<String, String>>)

data class GetSymbolReq(val sessionId: String, val path: String, val cursorOffset: Int)
data class GetSymbolRes(val path: String, val symbol: String, val startOffset: Int, val endOffset: Int)

data class FindUsagesReq(val sessionId: String, val symbol: String, val glob: String = "**/*", val maxMatches: Int = 200)
data class FindUsagesRes(val matches: List<SearchMatch>)

data class GetDiagnosticsReq(val sessionId: String, val path: String)
data class DiagnosticItem(val line: Int, val severity: String, val message: String)
data class GetDiagnosticsRes(val path: String, val diagnostics: List<DiagnosticItem>)
