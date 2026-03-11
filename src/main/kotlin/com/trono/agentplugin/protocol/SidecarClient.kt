package com.trono.agentplugin.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface SidecarClient {
    suspend fun initialize(req: InitializeReq): InitializeRes
    suspend fun createSession(req: CreateSessionReq): CreateSessionRes
    suspend fun deleteSession(req: DeleteSessionReq): DeleteSessionRes
    suspend fun listSessions(): ListSessionsRes
    suspend fun sendPrompt(req: PromptReq): PromptAccepted
    suspend fun cancelPrompt(req: CancelReq): CancelRes
    suspend fun setModel(req: SetModelReq)
    suspend fun setMode(req: SetModeReq)
    suspend fun patchPreview(req: PatchPreviewReq): PatchPreviewRes
    suspend fun patchCommit(req: PatchCommitReq): PatchCommitRes
    suspend fun patchRollback(req: PatchRollbackReq): PatchRollbackRes
    suspend fun patchPreviewGet(req: PatchPreviewGetReq): PatchPreviewGetRes
    suspend fun patchPreviewLast(req: PatchPreviewLastReq): PatchPreviewLastRes
    suspend fun health(req: HealthReq = HealthReq()): HealthRes
    suspend fun getCurrentFile(req: GetCurrentFileReq): GetCurrentFileRes
    suspend fun getSelection(req: GetSelectionReq): GetSelectionRes
    suspend fun readFile(req: ReadFileReq): ReadFileRes
    suspend fun writeFile(req: WriteFileReq): WriteFileRes
    suspend fun findFiles(req: FindFilesReq): FindFilesRes
    suspend fun searchText(req: SearchTextReq): SearchTextRes
    suspend fun runTest(req: RunTestReq): RunTestRes
    suspend fun getSymbolAtCursor(req: GetSymbolReq): GetSymbolRes
    suspend fun findUsages(req: FindUsagesReq): FindUsagesRes
    suspend fun getDiagnostics(req: GetDiagnosticsReq): GetDiagnosticsRes
}

class WsSidecarClient(
    private val url: String = "ws://127.0.0.1:8765",
    private val onEvent: ((JsonObject) -> Unit)? = null,
) : SidecarClient {
    private val gson = Gson()
    private val id = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val connected = CountDownLatch(1)

    private val webSocket: WebSocket = HttpClient.newHttpClient()
        .newWebSocketBuilder()
        .buildAsync(URI.create(url), object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                connected.countDown()
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*> {
                try {
                    val msg = gson.fromJson(data.toString(), JsonObject::class.java)
                    val msgId = if (msg.has("id")) msg.get("id").asLong else null
                    if (msgId != null) {
                        pending.remove(msgId)?.complete(msg)
                    } else if (msg.has("method") && msg.get("method").asString == "event") {
                        onEvent?.invoke(msg)
                    }
                } catch (_: Exception) {
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }
        }).join()

    private fun rpc(method: String, params: Any?): JsonObject {
        connected.await(2, TimeUnit.SECONDS)
        val reqId = id.getAndIncrement()
        val req = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", reqId)
            addProperty("method", method)
            if (params != null) add("params", gson.toJsonTree(params))
        }
        val future = CompletableFuture<JsonObject>()
        pending[reqId] = future
        webSocket.sendText(gson.toJson(req), true).join()
        val response = future.get(8, TimeUnit.SECONDS)
        if (response.has("error")) {
            throw IllegalStateException(response.getAsJsonObject("error").get("message").asString)
        }
        return response.getAsJsonObject("result")
    }

    override suspend fun initialize(req: InitializeReq): InitializeRes {
        val res = rpc("initialize", req)
        return gson.fromJson(res, InitializeRes::class.java)
    }

    override suspend fun createSession(req: CreateSessionReq): CreateSessionRes {
        val res = rpc("session.create", req)
        return gson.fromJson(res, CreateSessionRes::class.java)
    }

    override suspend fun deleteSession(req: DeleteSessionReq): DeleteSessionRes {
        val res = rpc("session.delete", req)
        return gson.fromJson(res, DeleteSessionRes::class.java)
    }

    override suspend fun listSessions(): ListSessionsRes {
        val res = rpc("session.list", emptyMap<String, String>())
        val type = object : TypeToken<ListSessionsRes>() {}.type
        return gson.fromJson(res, type)
    }

    override suspend fun sendPrompt(req: PromptReq): PromptAccepted {
        val res = rpc("prompt.send", req)
        return gson.fromJson(res, PromptAccepted::class.java)
    }

    override suspend fun cancelPrompt(req: CancelReq): CancelRes {
        val res = rpc("prompt.cancel", req)
        return gson.fromJson(res, CancelRes::class.java)
    }

    override suspend fun setModel(req: SetModelReq) {
        rpc("session.setModel", req)
    }

    override suspend fun setMode(req: SetModeReq) {
        rpc("session.setMode", req)
    }

    override suspend fun patchPreview(req: PatchPreviewReq): PatchPreviewRes {
        val res = rpc("ide.apply_patch_preview", req)
        return gson.fromJson(res, PatchPreviewRes::class.java)
    }

    override suspend fun patchCommit(req: PatchCommitReq): PatchCommitRes {
        val res = rpc("ide.apply_patch_commit", req)
        return gson.fromJson(res, PatchCommitRes::class.java)
    }

    override suspend fun patchRollback(req: PatchRollbackReq): PatchRollbackRes {
        val res = rpc("ide.patch_rollback", req)
        return gson.fromJson(res, PatchRollbackRes::class.java)
    }

    override suspend fun patchPreviewGet(req: PatchPreviewGetReq): PatchPreviewGetRes {
        val res = rpc("ide.patch_preview_get", req)
        return gson.fromJson(res, PatchPreviewGetRes::class.java)
    }

    override suspend fun patchPreviewLast(req: PatchPreviewLastReq): PatchPreviewLastRes {
        val res = rpc("ide.patch_preview_last", req)
        return gson.fromJson(res, PatchPreviewLastRes::class.java)
    }

    override suspend fun health(req: HealthReq): HealthRes {
        val res = rpc("health", req)
        return gson.fromJson(res, HealthRes::class.java)
    }

    override suspend fun getCurrentFile(req: GetCurrentFileReq): GetCurrentFileRes =
        gson.fromJson(rpc("ide.get_current_file", req), GetCurrentFileRes::class.java)

    override suspend fun getSelection(req: GetSelectionReq): GetSelectionRes =
        gson.fromJson(rpc("ide.get_selection", req), GetSelectionRes::class.java)

    override suspend fun readFile(req: ReadFileReq): ReadFileRes =
        gson.fromJson(rpc("ide.read_file", req), ReadFileRes::class.java)

    override suspend fun writeFile(req: WriteFileReq): WriteFileRes =
        gson.fromJson(rpc("ide.write_file", req), WriteFileRes::class.java)

    override suspend fun findFiles(req: FindFilesReq): FindFilesRes =
        gson.fromJson(rpc("ide.find_files", req), FindFilesRes::class.java)

    override suspend fun searchText(req: SearchTextReq): SearchTextRes =
        gson.fromJson(rpc("ide.search_text", req), SearchTextRes::class.java)

    override suspend fun runTest(req: RunTestReq): RunTestRes =
        gson.fromJson(rpc("ide.run_test", req), RunTestRes::class.java)

    override suspend fun getSymbolAtCursor(req: GetSymbolReq): GetSymbolRes =
        gson.fromJson(rpc("ide.get_symbol_at_cursor", req), GetSymbolRes::class.java)

    override suspend fun findUsages(req: FindUsagesReq): FindUsagesRes =
        gson.fromJson(rpc("ide.find_usages", req), FindUsagesRes::class.java)

    override suspend fun getDiagnostics(req: GetDiagnosticsReq): GetDiagnosticsRes =
        gson.fromJson(rpc("ide.get_diagnostics", req), GetDiagnosticsRes::class.java)
}

class StubSidecarClient : SidecarClient {
    private var defaultSession: SessionInfo? = null

    override suspend fun initialize(req: InitializeReq): InitializeRes {
        return InitializeRes(
            protocolVersion = "0.2",
            server = ServerInfo("intelli-agent-sidecar", "0.1.0"),
            capabilities = Capabilities(
                sessions = true,
                streaming = true,
                tools = listOf("ide.get_current_file", "ide.read_file", "ide.apply_patch_preview"),
            ),
        )
    }

    override suspend fun createSession(req: CreateSessionReq): CreateSessionRes {
        val s = SessionInfo(
            sessionId = "s_local_default",
            title = req.title,
            status = "idle",
            updatedAt = java.time.Instant.now().toString(),
        )
        defaultSession = s
        return CreateSessionRes(s.sessionId, s.status)
    }

    override suspend fun deleteSession(req: DeleteSessionReq): DeleteSessionRes {
        val deleted = defaultSession?.sessionId == req.sessionId
        if (deleted) defaultSession = null
        return DeleteSessionRes(deleted)
    }

    override suspend fun listSessions(): ListSessionsRes =
        ListSessionsRes(defaultSession?.let { listOf(it) } ?: emptyList())

    override suspend fun sendPrompt(req: PromptReq): PromptAccepted =
        PromptAccepted(true, "r_local")

    override suspend fun cancelPrompt(req: CancelReq): CancelRes = CancelRes(true)

    override suspend fun setModel(req: SetModelReq) {}

    override suspend fun setMode(req: SetModeReq) {}

    override suspend fun patchPreview(req: PatchPreviewReq): PatchPreviewRes =
        PatchPreviewRes("pv_local", req.diff)

    override suspend fun patchCommit(req: PatchCommitReq): PatchCommitRes =
        PatchCommitRes(req.confirm, null, null)

    override suspend fun patchRollback(req: PatchRollbackReq): PatchRollbackRes =
        PatchRollbackRes(true)

    override suspend fun patchPreviewGet(req: PatchPreviewGetReq): PatchPreviewGetRes =
        PatchPreviewGetRes(req.previewId, "", null, null, null, false, null)

    override suspend fun patchPreviewLast(req: PatchPreviewLastReq): PatchPreviewLastRes =
        PatchPreviewLastRes(null, false, "No preview for session")

    override suspend fun health(req: HealthReq): HealthRes =
        HealthRes(ok = true, mode = "simulated", activeRuns = 0, sessions = if (defaultSession == null) 0 else 1)

    override suspend fun getCurrentFile(req: GetCurrentFileReq): GetCurrentFileRes =
        GetCurrentFileRes(req.path, "txt", "", req.cursorOffset)

    override suspend fun getSelection(req: GetSelectionReq): GetSelectionRes =
        GetSelectionRes(req.path, req.startOffset, req.endOffset, "")

    override suspend fun readFile(req: ReadFileReq): ReadFileRes =
        ReadFileRes("")

    override suspend fun writeFile(req: WriteFileReq): WriteFileRes =
        WriteFileRes(true, req.path)

    override suspend fun findFiles(req: FindFilesReq): FindFilesRes =
        FindFilesRes(emptyList())

    override suspend fun searchText(req: SearchTextReq): SearchTextRes =
        SearchTextRes(emptyList())

    override suspend fun runTest(req: RunTestReq): RunTestRes =
        RunTestRes(RunTestSummary(started = true, execId = "ex_local"), emptyList())

    override suspend fun getSymbolAtCursor(req: GetSymbolReq): GetSymbolRes =
        GetSymbolRes(req.path, "", req.cursorOffset, req.cursorOffset)

    override suspend fun findUsages(req: FindUsagesReq): FindUsagesRes =
        FindUsagesRes(emptyList())

    override suspend fun getDiagnostics(req: GetDiagnosticsReq): GetDiagnosticsRes =
        GetDiagnosticsRes(req.path, emptyList())
}
