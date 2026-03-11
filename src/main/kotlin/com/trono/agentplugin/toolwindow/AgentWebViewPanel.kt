package com.trono.agentplugin.toolwindow

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.trono.agentplugin.protocol.CancelReq
import com.trono.agentplugin.protocol.ClientInfo
import com.trono.agentplugin.protocol.CreateSessionReq
import com.trono.agentplugin.protocol.DeleteSessionReq
import com.trono.agentplugin.protocol.InitializeReq
import com.trono.agentplugin.protocol.PatchCommitReq
import com.trono.agentplugin.protocol.PatchPreviewGetReq
import com.trono.agentplugin.protocol.PatchPreviewGetRes
import com.trono.agentplugin.protocol.PatchPreviewLastReq
import com.trono.agentplugin.protocol.PromptReq
import com.trono.agentplugin.protocol.SidecarClient
import com.trono.agentplugin.protocol.StubSidecarClient
import com.trono.agentplugin.protocol.WsSidecarClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.time.Instant
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

private data class PatchPreviewUiState(
    val sessionId: String,
    val previewId: String,
    val path: String,
    val oldText: String?,
    val newText: String?,
    val diff: String?,
    val applicable: Boolean,
    val reason: String?,
    val line: Int,
    val additions: Int,
    val removals: Int,
    val hunks: Int,
)

class AgentWebViewPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var browser: JBCefBrowser? = null
    private var sidecar: SidecarClient = StubSidecarClient()
    private var sessionId: String? = null
    private var lastRunId: String? = null
    private var diffDialog: DialogWrapper? = null
    private var diffDialogDisposable: Disposable? = null
    private val panelDisposable = Disposer.newDisposable("intelli-agent-webview-panel")
    private val runBuffers = linkedMapOf<String, StringBuilder>()
    private val previewBySession = linkedMapOf<String, PatchPreviewUiState>()

    init {
        if (!JBCefApp.isSupported()) {
            add(JLabel("JCEF is not supported in this IDE runtime."), BorderLayout.CENTER)
        } else {
            val b = JBCefBrowser()
            browser = b
            add(b.component, BorderLayout.CENTER)

            val jsQuery = JBCefJSQuery.create(b)
            jsQuery.addHandler { payload ->
                handleUiEvent(payload)
                JBCefJSQuery.Response("ok")
            }

            b.loadHTML(buildHtml(jsQuery))
            installIdeListeners()
            connectSidecar()
        }
    }

    private fun installIdeListeners() {
        project.messageBus.connect(panelDisposable).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    emitUiContext()
                }
            },
        )
    }

    private fun connectSidecar() {
        scope.launch {
            try {
                val ws = WsSidecarClient(onEvent = { evt ->
                    val params = evt.getAsJsonObject("params") ?: return@WsSidecarClient
                    val type = params.get("type")?.asString ?: return@WsSidecarClient
                    val runId = params.get("runId")?.asString ?: ""
                    val eventSessionId = params.get("sessionId")?.asString ?: sessionId ?: return@WsSidecarClient
                    val data = params.getAsJsonObject("data")
                    when (type) {
                        "assistant.chunk" -> {
                            val buf = runBuffers.getOrPut(runId) { StringBuilder() }
                            buf.append(data?.get("text")?.asString ?: "")
                            emitUiMessage("agent", buf.toString(), "agent-$runId", eventSessionId)
                            emitUiStatus("thinking", "Streaming response…", eventSessionId)
                        }
                        "tool.call" -> {
                            emitUiToolCall(
                                eventSessionId,
                                runId,
                                data?.get("toolCallId")?.asString.orEmpty(),
                                data?.get("name")?.asString ?: "tool",
                                data?.get("status")?.asString ?: "pending",
                                data?.get("input"),
                                null,
                            )
                            emitUiStatus("patching", formatToolStatus(data?.get("name")?.asString, data?.get("input")), eventSessionId)
                        }
                        "tool.update" -> {
                            emitUiToolCall(
                                eventSessionId,
                                runId,
                                data?.get("toolCallId")?.asString.orEmpty(),
                                null,
                                data?.get("status")?.asString ?: "completed",
                                null,
                                data?.get("output"),
                            )
                        }
                        "prompt.done" -> {
                            runBuffers.remove(runId)
                            emitUiStatus("done", "Run completed", eventSessionId)
                            scope.launch { reconcilePatchForSession(eventSessionId) }
                        }
                        "prompt.error" -> {
                            runBuffers.remove(runId)
                            emitUiStatus("error", "Run error: ${data?.get("message")?.asString ?: "unknown"}", eventSessionId)
                        }
                        "patch.preview.ready" -> {
                            scope.launch {
                                val previewId = data?.get("previewId")?.asString ?: return@launch
                                val details = sidecar.patchPreviewGet(PatchPreviewGetReq(previewId))
                                val preview = buildPreviewState(eventSessionId, details)
                                previewBySession[eventSessionId] = preview
                                emitUiPatchPreview(preview)
                                emitUiStatus("waiting", "Patch ready. Review the diff and approve to apply.", eventSessionId)
                            }
                        }
                        "command.output" -> emitUiStatus("patching", (data?.get("chunk")?.asString ?: "").trim(), eventSessionId)
                        "command.done" -> emitUiStatus("done", "Command finished exit=${data?.get("exitCode")?.asInt ?: -1}", eventSessionId)
                    }
                })

                val init = ws.initialize(
                    InitializeReq(
                        ClientInfo("intellij-plugin", "0.4.0"),
                        project.basePath ?: ".",
                    ),
                )
                val created = ws.createSession(CreateSessionReq(workspacePath = project.basePath ?: ".", title = "Default"))
                sidecar = ws
                sessionId = created.sessionId
                emitUiSession(created.sessionId)
                emitSessions()
                emitUiStatus("idle", "Connected: ${init.server.name} ${init.server.version}", created.sessionId)
                emitUiContext()
                reconcilePatchForSession(created.sessionId)
            } catch (e: Exception) {
                val fallback = sidecar.initialize(InitializeReq(ClientInfo("intellij-plugin", "0.4.0"), project.basePath ?: "."))
                val local = sidecar.createSession(CreateSessionReq(workspacePath = project.basePath ?: ".", title = "Default"))
                sessionId = local.sessionId
                emitUiSession(local.sessionId)
                emitSessions()
                emitUiContext()
                emitUiStatus("error", "Sidecar fallback: ${e.message ?: "using stub"}", local.sessionId)
                emitUiMessage(
                    "agent",
                    "Live sidecar unavailable. UI is active in stub mode against ${fallback.server.name}.",
                    "stub-${Instant.now().toEpochMilli()}",
                    local.sessionId,
                )
            }
        }
    }

    private fun handleUiEvent(payload: String) {
        val obj = try {
            gson.fromJson(payload, JsonObject::class.java)
        } catch (_: Exception) {
            return
        }
        when (obj.get("type")?.asString ?: return) {
            "ui.ready" -> {
                emitUiStatus("idle", "Ready", sessionId)
                emitUiContext()
                scope.launch { emitSessions() }
            }
            "prompt.send" -> {
                val text = obj.get("text")?.asString ?: return
                val sid = sessionId ?: return
                scope.launch {
                    try {
                        val accepted = sidecar.sendPrompt(PromptReq(sessionId = sid, prompt = buildPromptWithContext(text)))
                        lastRunId = accepted.runId
                        emitUiStatus("thinking", "Thinking…", sid)
                    } catch (e: Exception) {
                        emitUiStatus("error", "Send failed: ${e.message}", sid)
                    }
                }
            }
            "ui.invoke" -> bridgeInvoke(obj.get("method")?.asString ?: return, obj.getAsJsonObject("params"))
        }
    }

    private fun bridgeInvoke(method: String, params: JsonObject?) {
        val sid = sessionId ?: return
        scope.launch {
            try {
                when (method) {
                    "session.new" -> {
                        val created = sidecar.createSession(CreateSessionReq(workspacePath = project.basePath ?: ".", title = "Session"))
                        sessionId = created.sessionId
                        emitUiSession(created.sessionId)
                        emitSessions()
                        emitUiContext()
                        emitUiStatus("idle", "Switched to ${created.sessionId}", created.sessionId)
                        closeDiffDialog()
                        reconcilePatchForSession(created.sessionId)
                    }
                    "session.switch" -> {
                        val target = params?.get("sessionId")?.asString
                        if (!target.isNullOrBlank()) {
                            sessionId = target
                            emitUiSession(target)
                            emitSessions()
                            emitUiContext()
                            emitUiStatus("idle", "Switched to $target", target)
                            closeDiffDialog()
                            reconcilePatchForSession(target)
                        }
                    }
                    "session.delete" -> {
                        val target = params?.get("sessionId")?.asString
                        if (!target.isNullOrBlank()) {
                            sidecar.deleteSession(DeleteSessionReq(target))
                            previewBySession.remove(target)
                            if (sessionId == target) {
                                val all = sidecar.listSessions().sessions.map { it.sessionId }
                                sessionId = all.firstOrNull()
                                sessionId?.let {
                                    emitUiSession(it)
                                    reconcilePatchForSession(it)
                                }
                            }
                            emitSessions()
                            emitUiStatus("idle", "Deleted session $target", sessionId)
                            closeDiffDialog()
                        }
                    }
                    "prompt.cancel" -> {
                        sidecar.cancelPrompt(CancelReq(sid, lastRunId))
                        emitUiStatus("idle", "Run cancelled", sid)
                    }
                    "ide.patch_open_diff" -> {
                        val preview = resolvePreview(params?.get("previewId")?.asString, sid)
                            ?: return@launch emitUiStatus("error", "No preview to open", sid)
                        showNativeDiff(preview)
                        emitUiStatus("waiting", "Awaiting approval", sid)
                    }
                    "ide.patch_commit" -> {
                        val preview = resolvePreview(params?.get("previewId")?.asString, sid)
                            ?: return@launch emitUiStatus("error", "No preview to commit", sid)
                        commitPreview(preview)
                    }
                    "ide.patch_reject" -> {
                        val preview = resolvePreview(params?.get("previewId")?.asString, sid)
                            ?: return@launch emitUiStatus("error", "No preview to reject", sid)
                        rejectPreview(preview)
                    }
                }
            } catch (e: Exception) {
                emitUiStatus("error", "Action failed: ${e.message}", sid)
            }
        }
    }

    private suspend fun reconcilePatchForSession(sidInput: String?) {
        val sid = sidInput ?: return
        try {
            val last = sidecar.patchPreviewLast(PatchPreviewLastReq(sid))
            if (!last.previewId.isNullOrBlank()) {
                val details = sidecar.patchPreviewGet(PatchPreviewGetReq(last.previewId))
                val preview = buildPreviewState(sid, details)
                previewBySession[sid] = preview
                emitUiPatchPreview(preview)
                emitUiStatus(
                    if (preview.applicable) "waiting" else "error",
                    if (preview.applicable) "Patch ready. Review the diff and approve to apply."
                    else "Patch preview not directly applicable: ${preview.reason ?: "unknown"}",
                    sid,
                )
            } else {
                previewBySession.remove(sid)
                emitUiPatchCleared(sid)
                emitUiStatus("done", "Run completed", sid)
            }
        } catch (_: Exception) {
            // Keep UI stable if reconcile fails.
        }
    }

    private suspend fun commitPreview(preview: PatchPreviewUiState) {
        val res = sidecar.patchCommit(
            PatchCommitReq(
                previewId = preview.previewId,
                confirm = true,
                sessionId = preview.sessionId,
            ),
        )
        if (res.applied) {
            previewBySession.remove(preview.sessionId)
            emitUiPatchResolved(preview.sessionId, preview.previewId, "accepted", "Applied to ${preview.path}")
            emitUiStatus("done", "Patch applied", preview.sessionId)
            emitUiMessage(
                "agent",
                "Patch applied. `${preview.path}` updated. Anything else?",
                "patch-applied-${preview.previewId}",
                preview.sessionId,
            )
            closeDiffDialog()
        } else {
            emitUiStatus("error", "Patch not applied: ${res.reason ?: "unknown"}", preview.sessionId)
        }
    }

    private fun rejectPreview(preview: PatchPreviewUiState) {
        previewBySession.remove(preview.sessionId)
        emitUiPatchResolved(preview.sessionId, preview.previewId, "rejected", "Rejected. Patch discarded.")
        emitUiStatus("done", "Patch discarded", preview.sessionId)
        emitUiMessage(
            "agent",
            "Patch discarded. What should I change instead?",
            "patch-rejected-${preview.previewId}",
            preview.sessionId,
        )
        closeDiffDialog()
    }

    private fun resolvePreview(previewId: String?, sid: String): PatchPreviewUiState? {
        if (!previewId.isNullOrBlank()) {
            return previewBySession.values.firstOrNull { it.previewId == previewId }
        }
        return previewBySession[sid]
    }

    private fun buildPromptWithContext(text: String): String {
        val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
        val filePath = editor?.virtualFile?.path ?: ""
        val docText = editor?.document?.text ?: ""
        val selStart = editor?.selectionModel?.selectionStart ?: 0
        val selEnd = editor?.selectionModel?.selectionEnd ?: 0
        val selected = if (selEnd > selStart && selEnd <= docText.length) docText.substring(selStart, selEnd) else ""
        val workspace = project.basePath ?: "."
        return buildString {
            appendLine("[INTELLIJ_CONTEXT]")
            appendLine("workspace=$workspace")
            if (filePath.isNotBlank()) appendLine("current_file=$filePath")
            appendLine("selection_start=$selStart")
            appendLine("selection_end=$selEnd")
            if (selected.isNotBlank()) {
                appendLine("selection_text_start")
                appendLine(selected.take(4000))
                appendLine("selection_text_end")
            }
            appendLine("[/INTELLIJ_CONTEXT]")
            appendLine(text)
        }
    }

    private fun showNativeDiff(preview: PatchPreviewUiState) {
        ApplicationManager.getApplication().invokeLater {
            closeDiffDialog()
            val factory = DiffContentFactory.getInstance()
            val left = factory.create(project, preview.oldText ?: preview.diff ?: "")
            val right = factory.create(project, preview.newText ?: "(No auto-applicable patch body available)")
            val title = if (preview.path.isNotBlank()) "Patch Preview: ${preview.path}" else "Patch Preview"
            val request = SimpleDiffRequest(title, left, right, "Before", "After")

            val disposable = Disposer.newDisposable("intelli-agent-diff")
            val panel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
            panel.setRequest(request)

            val dialog = object : DialogWrapper(project, true) {
                init {
                    setTitle(title)
                    init()
                }

                override fun createCenterPanel() = panel.component

                override fun createSouthPanel(): JPanel {
                    val root = JPanel(BorderLayout()).apply {
                        border = BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(1, 0, 0, 0, java.awt.Color(74, 77, 80)),
                            BorderFactory.createEmptyBorder(8, 12, 8, 12),
                        )
                        background = java.awt.Color(49, 51, 53)
                    }

                    val info = JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        isOpaque = false
                    }
                    val titleLabel = JLabel("Agent patch awaiting approval").apply {
                        foreground = java.awt.Color(187, 187, 187)
                    }
                    val additionsLabel = if (preview.additions > 0) "+${preview.additions}" else "+0"
                    val removalsLabel = if (preview.removals > 0) "-${preview.removals}" else "-0"
                    val subLabel = JLabel(
                        "$additionsLabel $removalsLabel " +
                            "· line ${preview.line} · ${preview.hunks} hunk · session ${preview.sessionId}",
                    ).apply {
                        foreground = java.awt.Color(111, 115, 122)
                    }
                    info.add(titleLabel)
                    info.add(Box.createVerticalStrut(2))
                    info.add(subLabel)

                    val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                        isOpaque = false
                    }
                    val rejectButton = JButton("Reject")
                    val acceptButton = JButton("Accept & Apply")
                    rejectButton.addActionListener { rejectPreview(preview) }
                    acceptButton.addActionListener {
                        rejectButton.isEnabled = false
                        acceptButton.isEnabled = false
                        scope.launch { commitPreview(preview) }
                    }
                    actions.add(rejectButton)
                    actions.add(acceptButton)

                    root.add(info, BorderLayout.CENTER)
                    root.add(actions, BorderLayout.EAST)
                    return root
                }
            }

            diffDialogDisposable = disposable
            diffDialog = dialog
            dialog.show()
        }
    }

    private fun closeDiffDialog() {
        ApplicationManager.getApplication().invokeLater {
            try {
                diffDialog?.close(DialogWrapper.CANCEL_EXIT_CODE)
            } catch (_: Exception) {
            }
            diffDialog = null
            diffDialogDisposable?.let { Disposer.dispose(it) }
            diffDialogDisposable = null
        }
    }

    private suspend fun emitSessions() {
        try {
            val list = sidecar.listSessions().sessions
                .map {
                    mapOf(
                        "sessionId" to it.sessionId,
                        "status" to it.status,
                        "updatedAt" to it.updatedAt,
                        "title" to it.title,
                    )
                }
            emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "sessions", "items" to list))});")
        } catch (_: Exception) {
        }
    }

    private fun emitUiContext() {
        val path = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path ?: ""
        emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "context", "path" to path))});")
    }

    private fun emitUiSession(sid: String) =
        emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "session", "sessionId" to sid))});")

    private fun emitUiMessage(role: String, text: String, id: String? = null, sid: String? = sessionId) {
        emitJs(
            "window.__onKotlinEvent?.(" +
                gson.toJson(mapOf("type" to "message", "role" to role, "text" to text, "id" to id, "sessionId" to sid)) +
                ");",
        )
    }

    private fun emitUiStatus(kind: String, text: String, sid: String? = sessionId) =
        emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "status", "kind" to kind, "text" to text, "sessionId" to sid))});")

    private fun emitUiToolCall(
        sid: String,
        runId: String,
        toolCallId: String,
        name: String?,
        status: String,
        input: Any?,
        output: Any?,
    ) {
        val payload = linkedMapOf<String, Any?>(
            "type" to "toolCall",
            "sessionId" to sid,
            "runId" to runId,
            "toolCallId" to toolCallId,
            "status" to status,
            "name" to name,
            "input" to input,
            "output" to output,
        )
        emitJs("window.__onKotlinEvent?.(${gson.toJson(payload)});")
    }

    private fun emitUiPatchPreview(preview: PatchPreviewUiState) {
        val payload = linkedMapOf<String, Any?>(
            "type" to "patchPreview",
            "sessionId" to preview.sessionId,
            "previewId" to preview.previewId,
            "path" to preview.path,
            "line" to preview.line,
            "additions" to preview.additions,
            "removals" to preview.removals,
            "hunks" to preview.hunks,
            "applicable" to preview.applicable,
            "reason" to preview.reason,
        )
        emitJs("window.__onKotlinEvent?.(${gson.toJson(payload)});")
    }

    private fun emitUiPatchResolved(sessionId: String, previewId: String, state: String, label: String) {
        val payload = linkedMapOf<String, Any?>(
            "type" to "patchResolved",
            "sessionId" to sessionId,
            "previewId" to previewId,
            "state" to state,
            "label" to label,
        )
        emitJs("window.__onKotlinEvent?.(${gson.toJson(payload)});")
    }

    private fun emitUiPatchCleared(sessionId: String) {
        emitJs(
            "window.__onKotlinEvent?.(" +
                gson.toJson(mapOf("type" to "patchCleared", "sessionId" to sessionId)) +
                ");",
        )
    }

    private fun emitJs(js: String) {
        ApplicationManager.getApplication().invokeLater {
            browser?.cefBrowser?.executeJavaScript(js, browser?.cefBrowser?.url ?: "about:blank", 0)
        }
    }

    override fun dispose() {
        closeDiffDialog()
        Disposer.dispose(panelDisposable)
        browser?.dispose()
        browser = null
    }

    private fun buildPreviewState(sessionId: String, details: PatchPreviewGetRes): PatchPreviewUiState {
        val oldText = details.oldText
        val newText = details.newText
        val line = firstChangedLine(oldText, newText)
        val additions = diffLineCount(oldText, newText, added = true)
        val removals = diffLineCount(oldText, newText, added = false)
        return PatchPreviewUiState(
            sessionId = sessionId,
            previewId = details.previewId,
            path = details.path,
            oldText = oldText,
            newText = newText,
            diff = details.diff,
            applicable = details.applicable,
            reason = details.reason,
            line = line,
            additions = additions,
            removals = removals,
            hunks = if (additions + removals > 0) 1 else 0,
        )
    }

    private fun firstChangedLine(oldText: String?, newText: String?): Int {
        val oldLines = oldText.orEmpty().lines()
        val newLines = newText.orEmpty().lines()
        val limit = minOf(oldLines.size, newLines.size)
        for (index in 0 until limit) {
            if (oldLines[index] != newLines[index]) return index + 1
        }
        return if (oldLines.size != newLines.size) limit + 1 else 1
    }

    private fun diffLineCount(oldText: String?, newText: String?, added: Boolean): Int {
        val oldLines = oldText.orEmpty().lines()
        val newLines = newText.orEmpty().lines()
        val limit = minOf(oldLines.size, newLines.size)
        var count = 0
        for (index in 0 until limit) {
            if (oldLines[index] != newLines[index]) count += 1
        }
        return count + if (added) (newLines.size - limit).coerceAtLeast(0) else (oldLines.size - limit).coerceAtLeast(0)
    }

    private fun formatToolStatus(name: String?, input: Any?): String {
        val toolName = name ?: return "Running tool…"
        val inputObj = input as? JsonObject
        val path = inputObj?.get("path")?.asString ?: inputObj?.get("file_path")?.asString
        val target = inputObj?.get("target")?.asString
        val command = inputObj?.get("command")?.asString
        return when (toolName.lowercase()) {
            "read_file" -> "Reading ${path ?: "file"}…"
            "write_file" -> "Writing ${path ?: "file"}…"
            "apply_patch" -> "Preparing patch for ${path ?: "file"}…"
            "find_files", "list_files" -> "Scanning project…"
            "search_text" -> "Searching code…"
            "run_test" -> "Running ${target ?: "tests"}…"
            "bash", "run_command" -> "Running: ${command ?: "command"}"
            else -> "Running ${toolName}…"
        }
    }

    private fun buildHtml(jsQuery: JBCefJSQuery): String {
        return """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width,initial-scale=1.0" />
                <style>
                  :root {
                    --bg:#2b2b2b;
                    --panel:#3c3f41;
                    --panel-dark:#313335;
                    --panel-mid:#393c3e;
                    --panel-hover:#45494a;
                    --border:#323232;
                    --border-light:#4a4d50;
                    --text:#a9b7c6;
                    --text-bright:#bbbbbb;
                    --text-muted:#6f737a;
                    --text-dim:#4a4e52;
                    --blue:#4c9be8;
                    --blue-dim:rgba(76,155,232,.13);
                    --blue-border:rgba(76,155,232,.25);
                    --green:#59a869;
                    --green-dim:rgba(89,168,105,.12);
                    --green-border:rgba(89,168,105,.25);
                    --red:#cc4444;
                    --red-dim:rgba(204,68,68,.1);
                    --red-border:rgba(204,68,68,.25);
                    --orange:#cc7832;
                    --font:-apple-system,"JetBrains Sans","Segoe UI",system-ui,sans-serif;
                    --mono:"JetBrains Mono","Consolas","Source Code Pro",monospace;
                    --r:4px;
                  }
                  *{box-sizing:border-box;margin:0;padding:0}
                  html,body{height:100%;overflow:hidden;background:var(--bg);color:var(--text);font-family:var(--font);font-size:12px}
                  body{display:flex;flex-direction:column}
                  @keyframes rise{from{opacity:0;transform:translateY(3px)}to{opacity:1;transform:translateY(0)}}
                  @keyframes pulse{0%,100%{opacity:1;transform:scale(1)}50%{opacity:.35;transform:scale(.75)}}
                  @keyframes dotbounce{0%,80%,100%{opacity:.3;transform:scale(.8)}40%{opacity:1;transform:scale(1)}}
                  @keyframes blink{0%,100%{opacity:1}50%{opacity:.35}}
                  @keyframes shimmer{0%{opacity:.5}50%{opacity:1}100%{opacity:.5}}

                  .titlebar{height:28px;background:var(--panel-dark);border-bottom:1px solid var(--border);display:flex;align-items:stretch;flex-shrink:0;user-select:none}
                  .tabs{display:flex;height:100%}
                  .t-tab{display:flex;align-items:center;gap:5px;padding:0 11px;font-size:11.5px;color:var(--text-muted);border-right:1px solid var(--border);border-bottom:2px solid transparent;white-space:nowrap}
                  .t-tab.active{color:var(--text-bright);border-bottom-color:var(--blue);background:var(--panel)}
                  .tab-pip{width:6px;height:6px;border-radius:50%;background:var(--green);box-shadow:0 0 4px var(--green);flex-shrink:0}
                  .tb-right{margin-left:auto;display:flex;align-items:center;gap:6px;padding:0 8px}
                  .status-chip{display:flex;align-items:center;gap:4px;border-radius:var(--r);padding:2px 7px 2px 5px;font-size:10.5px;font-weight:500;background:var(--green-dim);border:1px solid var(--green-border);color:var(--green)}
                  .status-chip.thinking{background:var(--blue-dim);border-color:var(--blue-border);color:var(--blue)}
                  .status-chip.waiting{background:rgba(204,170,0,.1);border-color:rgba(204,170,0,.25);color:#ccaa00}
                  .status-chip.error{background:var(--red-dim);border-color:var(--red-border);color:var(--red)}
                  .status-chip .dot{width:5px;height:5px;border-radius:50%;background:currentColor}
                  .status-chip.thinking .dot{animation:pulse 1s ease-in-out infinite}
                  .status-chip.waiting .dot{animation:blink 1.2s ease-in-out infinite}
                  .tb-icon{width:22px;height:22px;border-radius:var(--r);display:flex;align-items:center;justify-content:center;color:var(--text-muted)}

                  .layout{display:grid;grid-template-columns:178px 1fr;flex:1;overflow:hidden}
                  .sidebar{background:var(--panel-dark);border-right:1px solid var(--border);display:flex;flex-direction:column;overflow:hidden}
                  .sb-head{padding:5px 9px 4px;font-size:10px;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:var(--text-muted);border-bottom:1px solid var(--border);background:var(--panel-mid);flex-shrink:0}
                  .sess-list{flex:1;overflow-y:auto;padding:3px 0;scrollbar-width:thin;scrollbar-color:#4a4a4a transparent}
                  .sess-list::-webkit-scrollbar{width:6px}
                  .sess-list::-webkit-scrollbar-thumb{background:#4a4a4a;border-radius:3px}
                  .sess{padding:5px 10px 4px;cursor:pointer;border-left:2px solid transparent;transition:all .08s;position:relative}
                  .sess.active{background:var(--blue-dim);border-left-color:var(--blue)}
                  .sess:hover:not(.active){background:var(--panel-hover)}
                  .sess-id{font-family:var(--mono);font-size:11px;color:var(--text-muted);display:flex;align-items:center;gap:5px}
                  .sess.active .sess-id{color:var(--text-bright)}
                  .sess-pip{width:5px;height:5px;border-radius:50%;background:var(--text-dim);flex-shrink:0}
                  .sess-pip.idle{background:var(--green);box-shadow:0 0 4px var(--green)}
                  .sess-pip.busy{background:var(--orange);box-shadow:0 0 5px var(--orange);animation:pulse 1s ease-in-out infinite}
                  .sess-pip.waiting{background:#ccaa00;box-shadow:0 0 5px #ccaa00;animation:blink 1.2s ease-in-out infinite}
                  .sess-pip.error{background:var(--red);box-shadow:0 0 5px var(--red)}
                  .sess-preview,.sess-time{padding-left:10px}
                  .sess-preview{font-size:10.5px;color:var(--text-dim);margin-top:1px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
                  .sess.active .sess-preview{color:var(--text-muted)}
                  .sess-time{font-size:9.5px;color:var(--text-dim);margin-top:1px}
                  .sess-delete{position:absolute;top:6px;right:8px;width:16px;height:16px;border:none;background:transparent;color:var(--text-dim);border-radius:2px;display:flex;align-items:center;justify-content:center;opacity:0;cursor:pointer;transition:all .1s}
                  .sess:hover .sess-delete{opacity:1}
                  .sess-delete:hover{background:var(--red-dim);color:var(--red)}
                  .sb-foot{border-top:1px solid var(--border);padding:4px 6px;display:flex;flex-direction:column;gap:2px}
                  .ij-btn{display:flex;align-items:center;gap:5px;padding:4px 7px;border-radius:var(--r);font-size:11.5px;cursor:pointer;border:1px solid transparent;background:transparent;font-family:var(--font);color:var(--text);width:100%}
                  .ij-btn:hover{background:var(--panel-hover)}
                  .ij-btn.new{color:var(--blue)}
                  .ij-btn.new:hover{background:var(--blue-dim);border-color:var(--blue-border)}
                  .ij-btn.cancel{color:var(--red)}
                  .ij-btn.cancel:hover{background:var(--red-dim);border-color:var(--red-border)}

                  .chat{display:flex;flex-direction:column;overflow:hidden;background:var(--bg)}
                  .ctx-bar{display:flex;align-items:center;gap:6px;padding:4px 12px;background:var(--panel-dark);border-bottom:1px solid var(--border);flex-shrink:0}
                  .ctx-chip{display:flex;align-items:center;gap:4px;background:var(--panel);border:1px solid var(--border-light);border-radius:var(--r);padding:2px 7px;font-size:10.5px;color:var(--text-muted);font-family:var(--mono)}
                  .ctx-label{font-size:10px;color:var(--text-dim)}

                  .chat-scroll{flex:1;overflow-y:auto;padding:10px 12px;display:flex;flex-direction:column;gap:8px;scrollbar-width:thin;scrollbar-color:#4a4a4a transparent}
                  .chat-scroll::-webkit-scrollbar{width:6px}
                  .chat-scroll::-webkit-scrollbar-thumb{background:#4a4a4a;border-radius:3px}

                  .msg{display:flex;flex-direction:column;gap:3px;animation:rise .15s ease;width:100%}
                  .msg.user{align-items:flex-start}
                  .msg.agent{align-items:flex-end}
                  .msg-meta{display:flex;align-items:center;gap:5px;padding:0 2px}
                  .msg.agent .msg-meta{flex-direction:row-reverse}
                  .badge{font-size:9.5px;font-weight:700;letter-spacing:.07em;text-transform:uppercase;padding:1px 5px;border-radius:2px}
                  .badge.agent{background:var(--green-dim);color:var(--green);border:1px solid var(--green-border)}
                  .badge.user{background:var(--blue-dim);color:var(--blue);border:1px solid var(--blue-border)}
                  .msg-ts{font-size:9.5px;color:var(--text-dim);font-family:var(--mono)}
                  .bubble{padding:8px 11px;border-radius:var(--r);font-size:12px;line-height:1.65;max-width:93%;border:1px solid transparent;white-space:pre-wrap}
                  .msg.agent .bubble{background:#323232;border-color:var(--border-light);border-left:2px solid var(--green);color:var(--text-bright)}
                  .msg.user .bubble{background:#2d3548;border-color:rgba(76,155,232,.2);border-right:2px solid var(--blue);color:#b0ccec}
                  code{font-family:var(--mono);font-size:11px;padding:0 4px;border-radius:2px;background:rgba(255,255,255,.04)}

                  .tool-line{display:flex;align-items:center;gap:6px;padding:2px 10px;font-size:10.5px;color:var(--text-dim);font-family:var(--mono);border-left:2px solid var(--border-light);margin-left:4px;animation:rise .1s ease}
                  .tl-icon{opacity:.45;flex-shrink:0}
                  .tl-name{color:var(--orange);opacity:.85}
                  .tl-arg{color:var(--text-muted);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                  .tl-status{margin-left:auto;font-size:9.5px}
                  .tl-status.ok{color:var(--green)}
                  .tl-status.failed{color:var(--red)}
                  .tl-status.spin{color:var(--blue);animation:shimmer 1.2s infinite}

                  .thinking-row{display:flex;align-items:flex-start;animation:rise .15s ease}
                  .thinking-bubble{background:#323232;border:1px solid var(--border-light);border-left:2px solid var(--blue);border-radius:var(--r);padding:8px 12px;display:flex;align-items:center;gap:8px;font-size:11.5px;color:var(--text-muted)}
                  .dots{display:flex;gap:3px;align-items:center}
                  .dots span{width:4px;height:4px;border-radius:50%;background:var(--blue);opacity:.3;animation:dotbounce 1.2s ease-in-out infinite}
                  .dots span:nth-child(2){animation-delay:.2s}
                  .dots span:nth-child(3){animation-delay:.4s}

                  .diff-trigger{border:1px solid var(--border-light);border-radius:var(--r);overflow:hidden;max-width:93%;animation:rise .2s ease;background:var(--panel-mid)}
                  .diff-trigger-header{display:flex;align-items:center;gap:7px;padding:7px 10px;border-bottom:1px solid var(--border)}
                  .diff-trigger-title{flex:1;display:flex;align-items:center;gap:5px;font-size:11px;font-weight:600;color:var(--text-bright)}
                  .diff-file-chip{font-family:var(--mono);font-size:10.5px;color:var(--blue);background:var(--blue-dim);border:1px solid var(--blue-border);border-radius:2px;padding:0 5px}
                  .diff-meta-row{display:flex;align-items:center;gap:10px;padding:6px 10px;font-size:10.5px;color:var(--text-muted)}
                  .diff-stat{display:flex;align-items:center;gap:3px}
                  .diff-stat .add{color:var(--green);font-family:var(--mono)}
                  .diff-stat .rem{color:var(--red);font-family:var(--mono)}
                  .diff-sep{color:var(--text-dim)}
                  .view-diff-btn{display:flex;align-items:center;gap:5px;padding:4px 10px;border-radius:var(--r);font-size:11.5px;font-weight:600;cursor:pointer;background:var(--blue-dim);border:1px solid var(--blue-border);color:var(--blue);font-family:var(--font);margin-left:auto}
                  .view-diff-btn:hover{background:rgba(76,155,232,.22)}
                  .diff-trigger-result{display:flex;align-items:center;gap:5px;padding:5px 10px;font-size:11px;font-weight:500;border-top:1px solid var(--border)}
                  .diff-trigger-result.accepted{color:var(--green);background:var(--green-dim)}
                  .diff-trigger-result.rejected{color:var(--red);background:var(--red-dim)}
                  .diff-trigger-result.pending{color:#ccaa00;background:rgba(204,170,0,.08);animation:shimmer 2s infinite}

                  .input-area{border-top:1px solid var(--border);background:var(--panel-dark);padding:7px 10px 8px;flex-shrink:0}
                  .input-wrap{display:flex;align-items:flex-end;gap:6px;background:var(--bg);border:1px solid var(--border-light);border-radius:var(--r);padding:6px 8px}
                  .input-wrap:focus-within{border-color:var(--blue)}
                  textarea{flex:1;background:transparent;border:none;outline:none;resize:none;font-size:12px;font-family:var(--font);color:var(--text-bright);caret-color:var(--blue);min-height:18px;max-height:80px;line-height:1.5}
                  textarea::placeholder{color:var(--text-dim)}
                  .send-btn{width:26px;height:26px;border-radius:var(--r);background:var(--blue);border:none;cursor:pointer;display:flex;align-items:center;justify-content:center;align-self:flex-end}
                  .input-hint{display:flex;align-items:center;padding:3px 1px 0;font-size:9.5px;color:var(--text-dim);gap:8px}
                  kbd{background:var(--panel);border:1px solid var(--border-light);border-radius:2px;padding:0 4px;font-family:var(--mono);font-size:9.5px;color:var(--text-muted)}
                </style>
              </head>
              <body>
                <div class="titlebar">
                  <div class="tabs">
                    <div class="t-tab">Intelli Agent</div>
                    <div class="t-tab active"><span class="tab-pip"></span>Agent</div>
                  </div>
                  <div class="tb-right">
                    <div class="status-chip" id="statusChip">
                      <span class="dot"></span><span id="statusText">Booting…</span>
                    </div>
                    <div class="tb-icon">⋯</div>
                  </div>
                </div>

                <div class="layout">
                  <aside class="sidebar">
                    <div class="sb-head">Sessions</div>
                    <div class="sess-list" id="sessionsList"></div>
                    <div class="sb-foot">
                      <button class="ij-btn new" onclick="uiInvoke('session.new', {})">New Session</button>
                      <button class="ij-btn cancel" onclick="uiInvoke('prompt.cancel', {})">Cancel Run</button>
                    </div>
                  </aside>

                  <div class="chat">
                    <div class="ctx-bar">
                      <span class="ctx-label">Context:</span>
                      <div class="ctx-chip" id="contextChip">No active editor file</div>
                    </div>

                    <div class="chat-scroll" id="chatScroll"></div>

                    <div class="input-area">
                      <div class="input-wrap">
                        <textarea id="msgInput" rows="1" placeholder="Ask the agent… (Enter send, Shift+Enter newline)"></textarea>
                        <button class="send-btn" id="sendBtn">➜</button>
                      </div>
                      <div class="input-hint">
                        <kbd>Enter</kbd> send · <kbd>Shift+Enter</kbd> newline · <kbd>Cmd/Ctrl+K</kbd> focus · <kbd>Open Diff</kbd> to review patch
                      </div>
                    </div>
                  </div>
                </div>

                <script>
                  const state = {
                    activeSessionId: null,
                    sessions: [],
                    itemsBySession: new Map(),
                    status: { kind: 'idle', text: 'Ready' },
                    contextPath: ''
                  };

                  const chatScroll = document.getElementById('chatScroll');
                  const sessionsList = document.getElementById('sessionsList');
                  const msgInput = document.getElementById('msgInput');
                  const sendBtn = document.getElementById('sendBtn');
                  const statusChip = document.getElementById('statusChip');
                  const statusText = document.getElementById('statusText');
                  const contextChip = document.getElementById('contextChip');

                  function now(){
                    return new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false });
                  }

                  function ensureItems(sessionId){
                    if (!state.itemsBySession.has(sessionId)) state.itemsBySession.set(sessionId, []);
                    return state.itemsBySession.get(sessionId);
                  }

                  function escapeHtml(str){
                    return String(str || '')
                      .replace(/&/g,'&amp;')
                      .replace(/</g,'&lt;')
                      .replace(/>/g,'&gt;')
                      .replace(/"/g,'&quot;')
                      .replace(/'/g,'&#39;');
                  }

                  function renderStatus(){
                    statusChip.className = 'status-chip';
                    if (state.status.kind === 'thinking' || state.status.kind === 'patching') statusChip.classList.add('thinking');
                    if (state.status.kind === 'waiting') statusChip.classList.add('waiting');
                    if (state.status.kind === 'error') statusChip.classList.add('error');
                    statusText.textContent = state.status.text || 'Ready';
                  }

                  function upsertSessionStatus(sessionId, kind){
                    if (!sessionId) return;
                    const existing = state.sessions.find(session => session.sessionId === sessionId);
                    if (existing) {
                      existing.status = kind || existing.status || 'idle';
                    }
                  }

                  function renderContext(){
                    contextChip.textContent = state.contextPath || 'No active editor file';
                  }

                  function renderSessions(){
                    sessionsList.innerHTML = '';
                    for (const session of state.sessions) {
                      const pipClass = sessionPipClass(session.status);
                      const el = document.createElement('div');
                      el.className = 'sess' + (session.sessionId === state.activeSessionId ? ' active' : '');
                      el.innerHTML = `
                        <div class="sess-id"><span class="sess-pip __DL__{pipClass}"></span>__DL__{escapeHtml(session.sessionId)}</div>
                        <div class="sess-preview">__DL__{escapeHtml(session.title || 'Session')}</div>
                        <div class="sess-time">__DL__{escapeHtml(sessionLabel(session.status))}</div>
                        <button class="sess-delete" title="Delete session" aria-label="Delete session">×</button>
                      `;
                      const deleteBtn = el.querySelector('.sess-delete');
                      deleteBtn?.addEventListener('click', (event) => {
                        event.stopPropagation();
                        uiInvoke('session.delete', { sessionId: session.sessionId });
                      });
                      el.onclick = () => uiInvoke('session.switch', { sessionId: session.sessionId });
                      sessionsList.appendChild(el);
                    }
                  }

                  function sessionPipClass(status){
                    switch (status) {
                      case 'running':
                      case 'thinking':
                      case 'patching':
                        return 'busy';
                      case 'waiting':
                        return 'waiting';
                      case 'error':
                        return 'error';
                      case 'idle':
                      case 'done':
                      default:
                        return 'idle';
                    }
                  }

                  function sessionLabel(status){
                    switch (status) {
                      case 'running': return 'running';
                      case 'thinking': return 'thinking';
                      case 'patching': return 'running';
                      case 'waiting': return 'awaiting approval';
                      case 'error': return 'error';
                      case 'done':
                      case 'idle':
                      default: return 'idle';
                    }
                  }

                  function friendlyToolLabel(name, input){
                    const path = input?.path || input?.file_path;
                    const target = input?.target;
                    const command = input?.command;
                    switch ((name || '').toLowerCase()) {
                      case 'read_file': return `Reading __DL__{path || 'file'}…`;
                      case 'write_file': return `Writing __DL__{path || 'file'}…`;
                      case 'apply_patch': return `Preparing patch for __DL__{path || 'file'}…`;
                      case 'find_files':
                      case 'list_files': return 'Scanning project…';
                      case 'search_text': return 'Searching code…';
                      case 'run_test': return `Running __DL__{target || 'tests'}…`;
                      case 'bash':
                      case 'run_command': return `Running: __DL__{command || 'command'}`;
                      default: return `__DL__{name || 'tool'}…`;
                    }
                  }

                  function upsertMessage(evt){
                    const sessionId = evt.sessionId || state.activeSessionId || 'default';
                    const items = ensureItems(sessionId);
                    if (evt.id) {
                      const existing = items.find(item => item.type === 'message' && item.id === evt.id);
                      if (existing) {
                        existing.text = evt.text || '';
                        existing.time = now();
                        if (sessionId === state.activeSessionId) renderTimeline();
                        return;
                      }
                    }
                    items.push({
                      type: 'message',
                      id: evt.id || `m___DL__{Date.now()}___DL__{Math.random()}`,
                      role: evt.role || 'agent',
                      text: evt.text || '',
                      time: now()
                    });
                    if (sessionId === state.activeSessionId) renderTimeline();
                  }

                  function upsertToolCall(evt){
                    const sessionId = evt.sessionId || state.activeSessionId || 'default';
                    const items = ensureItems(sessionId);
                    const id = evt.toolCallId || `tool___DL__{Date.now()}___DL__{Math.random()}`;
                    let existing = items.find(item => item.type === 'tool' && item.toolCallId === id);
                    if (!existing) {
                      existing = {
                        type: 'tool',
                        toolCallId: id,
                        name: evt.name || 'tool',
                        status: evt.status || 'pending',
                        input: evt.input || null,
                        output: evt.output || null,
                        label: friendlyToolLabel(evt.name, evt.input)
                      };
                      items.push(existing);
                    } else {
                      if (evt.name) existing.name = evt.name;
                      if (evt.input) existing.input = evt.input;
                      if (evt.output !== undefined) existing.output = evt.output;
                      existing.status = evt.status || existing.status;
                      existing.label = friendlyToolLabel(existing.name, existing.input);
                    }
                    if (sessionId === state.activeSessionId) renderTimeline();
                  }

                  function upsertPatch(evt){
                    const sessionId = evt.sessionId || state.activeSessionId || 'default';
                    const items = ensureItems(sessionId);
                    let existing = items.find(item => item.type === 'patch' && item.previewId === evt.previewId);
                    if (!existing) {
                      existing = {
                        type: 'patch',
                        previewId: evt.previewId,
                        path: evt.path || 'file',
                        line: evt.line || 1,
                        additions: evt.additions || 0,
                        removals: evt.removals || 0,
                        hunks: evt.hunks || 1,
                        applicable: evt.applicable !== false,
                        reason: evt.reason || null,
                        state: 'pending',
                        label: 'Awaiting your decision…'
                      };
                      items.push(existing);
                    } else {
                      Object.assign(existing, evt);
                      existing.state = existing.state || 'pending';
                    }
                    if (sessionId === state.activeSessionId) renderTimeline();
                  }

                  function resolvePatch(evt){
                    const sessionId = evt.sessionId || state.activeSessionId || 'default';
                    const items = ensureItems(sessionId);
                    const existing = items.find(item => item.type === 'patch' && item.previewId === evt.previewId);
                    if (!existing) return;
                    existing.state = evt.state || 'pending';
                    existing.label = evt.label || existing.label;
                    if (sessionId === state.activeSessionId) renderTimeline();
                  }

                  function clearPatch(evt){
                    const sessionId = evt.sessionId || state.activeSessionId || 'default';
                    const items = ensureItems(sessionId);
                    state.itemsBySession.set(sessionId, items.filter(item => item.type !== 'patch' || item.state === 'accepted' || item.state === 'rejected'));
                    if (sessionId === state.activeSessionId) renderTimeline();
                  }

                  function renderTimeline(){
                    chatScroll.innerHTML = '';
                    const items = ensureItems(state.activeSessionId || 'default');
                    for (const item of items) {
                      if (item.type === 'message') {
                        const msg = document.createElement('div');
                        msg.className = `msg __DL__{item.role}`;
                        msg.innerHTML = `
                          <div class="msg-meta">
                            <span class="badge __DL__{item.role}">__DL__{item.role === 'user' ? 'You' : 'Agent'}</span>
                            <span class="msg-ts">__DL__{escapeHtml(item.time)}</span>
                          </div>
                          <div class="bubble">__DL__{escapeHtml(item.text)}</div>
                        `;
                        chatScroll.appendChild(msg);
                        continue;
                      }
                      if (item.type === 'tool') {
                        const tool = document.createElement('div');
                        const statusClass = item.status === 'completed' ? 'ok' : item.status === 'failed' ? 'failed' : 'spin';
                        const statusText = item.status === 'completed' ? '✓' : item.status === 'failed' ? 'failed' : '…';
                        const inputPreview = item.input?.path || item.input?.file_path || item.input?.command || item.input?.target || '';
                        tool.className = 'tool-line';
                        tool.innerHTML = `
                          <span class="tl-icon">⎿</span>
                          <span class="tl-name">__DL__{escapeHtml(item.name || 'tool')}</span>
                          <span class="tl-arg">__DL__{escapeHtml(inputPreview || item.label)}</span>
                          <span class="tl-status __DL__{statusClass}">__DL__{escapeHtml(statusText)}</span>
                        `;
                        chatScroll.appendChild(tool);
                        continue;
                      }
                      if (item.type === 'patch') {
                        const patch = document.createElement('div');
                        const stateClass = item.state === 'accepted' ? 'accepted' : item.state === 'rejected' ? 'rejected' : 'pending';
                        patch.className = 'diff-trigger';
                        patch.innerHTML = `
                          <div class="diff-trigger-header">
                            <div class="diff-trigger-title">
                              <span>Patch ready</span>
                              <span class="diff-file-chip">__DL__{escapeHtml(item.path)}</span>
                            </div>
                          </div>
                          <div class="diff-meta-row">
                            <div class="diff-stat"><span class="add">+__DL__{item.additions}</span>&nbsp;<span class="rem">-__DL__{item.removals}</span></div>
                            <span class="diff-sep">·</span>
                            <span>line __DL__{item.line} · __DL__{item.hunks} hunk</span>
                            <button class="view-diff-btn" __DL__{item.applicable ? '' : 'disabled'} data-preview-id="__DL__{escapeHtml(item.previewId)}">View Diff</button>
                          </div>
                          <div class="diff-trigger-result __DL__{stateClass}" style="display:flex">__DL__{escapeHtml(item.applicable ? item.label : (item.reason || 'Preview is not directly applicable'))}</div>
                        `;
                        const button = patch.querySelector('.view-diff-btn');
                        button?.addEventListener('click', () => uiInvoke('ide.patch_open_diff', { previewId: item.previewId }));
                        chatScroll.appendChild(patch);
                      }
                    }
                    if (state.status.kind === 'thinking' || state.status.kind === 'patching') {
                      const thinking = document.createElement('div');
                      thinking.className = 'thinking-row';
                      thinking.innerHTML = `
                        <div class="thinking-bubble">
                          <div class="dots"><span></span><span></span><span></span></div>
                          <span>__DL__{escapeHtml(state.status.text || 'Agent is thinking…')}</span>
                        </div>
                      `;
                      chatScroll.appendChild(thinking);
                    }
                    chatScroll.scrollTop = chatScroll.scrollHeight;
                  }

                  window.__onKotlinEvent = function(evt){
                    if (evt.type === 'session') {
                      state.activeSessionId = evt.sessionId;
                      renderSessions();
                      renderTimeline();
                      return;
                    }
                    if (evt.type === 'sessions') {
                      state.sessions = evt.items || [];
                      if (!state.activeSessionId && state.sessions.length) state.activeSessionId = state.sessions[0].sessionId;
                      renderSessions();
                      return;
                    }
                    if (evt.type === 'message') return upsertMessage(evt);
                    if (evt.type === 'toolCall') return upsertToolCall(evt);
                    if (evt.type === 'patchPreview') return upsertPatch(evt);
                    if (evt.type === 'patchResolved') return resolvePatch(evt);
                    if (evt.type === 'patchCleared') return clearPatch(evt);
                    if (evt.type === 'status') {
                      upsertSessionStatus(evt.sessionId, evt.kind || 'idle');
                      if (!evt.sessionId || evt.sessionId === state.activeSessionId) {
                        state.status = { kind: evt.kind || 'idle', text: evt.text || '' };
                      }
                      renderSessions();
                      renderStatus();
                      renderTimeline();
                      return;
                    }
                    if (evt.type === 'context') {
                      state.contextPath = evt.path || '';
                      renderContext();
                    }
                  };

                  function send(obj){
                    const payload = JSON.stringify(obj);
                    ${jsQuery.inject("payload")}
                  }

                  function uiInvoke(method, params){
                    send({ type: 'ui.invoke', method, params });
                  }

                  function sendPrompt(){
                    const text = msgInput.value.trim();
                    if (!text) return;
                    upsertMessage({ sessionId: state.activeSessionId, role: 'user', text });
                    send({ type: 'prompt.send', text });
                    msgInput.value = '';
                    msgInput.style.height = 'auto';
                  }

                  msgInput.addEventListener('input', () => {
                    msgInput.style.height = 'auto';
                    msgInput.style.height = Math.min(msgInput.scrollHeight, 80) + 'px';
                  });

                  msgInput.addEventListener('keydown', (event) => {
                    if (event.key === 'Enter' && !event.shiftKey) {
                      event.preventDefault();
                      sendPrompt();
                    }
                  });

                  document.addEventListener('keydown', (event) => {
                    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
                      event.preventDefault();
                      msgInput.focus();
                    }
                  });

                  sendBtn.addEventListener('click', sendPrompt);
                  renderStatus();
                  renderContext();
                  send({ type: 'ui.ready' });
                </script>
              </body>
            </html>
        """.trimIndent().replace("__DL__", "$")
    }
}
