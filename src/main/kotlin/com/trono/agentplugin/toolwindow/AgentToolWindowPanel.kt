package com.trono.agentplugin.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.google.gson.JsonObject
import com.trono.agentplugin.protocol.InitializeReq
import com.trono.agentplugin.protocol.ClientInfo
import com.trono.agentplugin.protocol.CancelReq
import com.trono.agentplugin.protocol.CreateSessionReq
import com.trono.agentplugin.protocol.PatchCommitReq
import com.trono.agentplugin.protocol.PatchPreviewReq
import com.trono.agentplugin.protocol.PatchPreviewGetReq
import com.trono.agentplugin.protocol.PatchPreviewLastReq
import com.trono.agentplugin.protocol.PatchRollbackReq
import com.trono.agentplugin.protocol.PromptReq
import com.trono.agentplugin.protocol.GetCurrentFileReq
import com.trono.agentplugin.protocol.GetSelectionReq
import com.trono.agentplugin.protocol.ReadFileReq
import com.trono.agentplugin.protocol.FindFilesReq
import com.trono.agentplugin.protocol.SearchTextReq
import com.trono.agentplugin.protocol.RunTestReq
import com.trono.agentplugin.protocol.GetSymbolReq
import com.trono.agentplugin.protocol.FindUsagesReq
import com.trono.agentplugin.protocol.GetDiagnosticsReq
import com.trono.agentplugin.protocol.SidecarClient
import com.trono.agentplugin.protocol.StubSidecarClient
import com.trono.agentplugin.protocol.WsSidecarClient
import com.trono.agentplugin.session.MessageKind
import com.trono.agentplugin.session.MessageStore
import com.trono.agentplugin.session.SessionMessage
import com.trono.agentplugin.session.SessionState
import com.trono.agentplugin.session.SessionStore
import com.trono.agentplugin.session.ToolCallState
import com.trono.agentplugin.session.ToolCallStore
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JSplitPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import java.util.concurrent.atomic.AtomicInteger

class AgentToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val output = JBTextArea()
    private val toolCards = JBTextArea()
    private val commandOutput = JBTextArea()
    private val input = JBTextField()
    private val sessionListModel = DefaultListModel<String>()
    private val sessionList = JList(sessionListModel)
    private val sessions = SessionStore()
    private val messages = MessageStore()
    private val toolCalls = ToolCallStore()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var sidecar: SidecarClient = StubSidecarClient()
    private var lastRunId: String? = null
    private var lastPreviewId: String? = null
    private var lastBackupId: String? = null

    init {
        output.isEditable = false
        output.lineWrap = true
        output.wrapStyleWord = true

        toolCards.isEditable = false
        toolCards.lineWrap = true
        toolCards.wrapStyleWord = true

        commandOutput.isEditable = false
        commandOutput.lineWrap = true
        commandOutput.wrapStyleWord = true

        applyIndustrialNoirTheme()

        val send = JButton("EXECUTE")
        send.addActionListener { onSend() }

        val bottom = JPanel(BorderLayout())
        bottom.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        input.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(64, 74, 83), 1),
            BorderFactory.createEmptyBorder(8, 10, 8, 10),
        )
        bottom.add(input, BorderLayout.CENTER)
        bottom.add(send, BorderLayout.EAST)

        val chatPane = JBScrollPane(output).apply { border = BorderFactory.createTitledBorder("ACTION SPINE") }

        val left = JPanel(BorderLayout())
        left.add(JBScrollPane(sessionList).apply { border = BorderFactory.createTitledBorder("SESSIONS") }, BorderLayout.CENTER)
        left.preferredSize = Dimension(220, 600)

        val main = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, chatPane).apply {
            resizeWeight = 0.18
            dividerLocation = 220
        }

        add(main, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        sessions.upsert(SessionState("s_local_default", "Default", "idle"))
        refreshSessionRail()
        sessionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = sessionList.selectedValue ?: return@addListSelectionListener
                val sid = selected.substringBefore(" ")
                if (sessions.setActive(sid)) {
                    refreshSessionViews()
                    log("Switched active session: $sid")
                }
            }
        }

        log("Intelli Agent MVP ready. Active session: ${sessions.active()?.sessionId}")
        log("Try: summarize current file | insert hello")
        bootstrapSidecar()
    }

    private fun bootstrapSidecar() {
        scope.launch {
            try {
                val ws = WsSidecarClient(onEvent = { msg -> handleSidecarEvent(msg) })
                val init = ws.initialize(
                    InitializeReq(
                        client = ClientInfo("intellij-plugin", "0.1.0"),
                        workspacePath = project.basePath ?: ".",
                    ),
                )
                sidecar = ws
                val created = ws.createSession(
                    CreateSessionReq(
                        workspacePath = project.basePath ?: ".",
                        title = "Default",
                    ),
                )
                val health = ws.health()
                sessions.upsert(SessionState(created.sessionId, "Default", created.status))
                sessions.setActive(created.sessionId)
                refreshSessionRail()
                refreshSessionViews()
                log("Connected to sidecar: ${init.server.name} ${init.server.version} [mode=${health.mode}]")
                log("Active session: ${created.sessionId}")
            } catch (e: Exception) {
                log("Sidecar not reachable, using stub client. (${e.message})")
            }
        }
    }

    private fun sendPromptToSidecar(text: String) {
        scope.launch {
            try {
                val active = sessions.active()?.sessionId ?: return@launch

                val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
                val filePath = editor?.virtualFile?.path ?: ""
                val docText = editor?.document?.text ?: ""
                val selStart = editor?.selectionModel?.selectionStart ?: 0
                val selEnd = editor?.selectionModel?.selectionEnd ?: 0
                val selectedText = if (selEnd > selStart && selStart >= 0 && selEnd <= docText.length) {
                    docText.substring(selStart, selEnd)
                } else {
                    ""
                }
                val workspace = project.basePath ?: "."

                val contextPrefix = buildString {
                    appendLine("[INTELLIJ_CONTEXT]")
                    appendLine("workspace=$workspace")
                    if (filePath.isNotBlank()) appendLine("current_file=$filePath")
                    appendLine("selection_start=$selStart")
                    appendLine("selection_end=$selEnd")
                    if (selectedText.isNotBlank()) {
                        appendLine("selection_text_start")
                        appendLine(selectedText.take(4000))
                        appendLine("selection_text_end")
                    }
                    appendLine("[/INTELLIJ_CONTEXT]")
                }

                val mergedPrompt = "$contextPrefix\n$text"
                val accepted = sidecar.sendPrompt(PromptReq(sessionId = active, prompt = mergedPrompt))
                lastRunId = accepted.runId
                log("Run started: ${accepted.runId}")
            } catch (e: Exception) {
                log("Agent: failed to send prompt: ${e.message}")
            }
        }
    }

    private fun cancelCurrentRun() {
        scope.launch {
            try {
                val sessionId = sessions.active()?.sessionId ?: return@launch
                val runId = lastRunId
                if (runId == null) {
                    log("No active run to cancel.")
                    return@launch
                }
                sidecar.cancelPrompt(CancelReq(sessionId = sessionId, runId = runId))
                messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.SYSTEM, text = "Run cancelled"))
                log("Run cancelled: $runId")
            } catch (e: Exception) {
                log("Cancel failed: ${e.message}")
            }
        }
    }

    private fun createPatchPreview(text: String) {
        scope.launch {
            try {
                val diff = text.removePrefix("patch preview").trim().ifBlank { "@@ -1 +1 @@\n-old\n+new" }
                val res = sidecar.patchPreview(PatchPreviewReq(path = "src/Demo.kt", diff = diff))
                lastPreviewId = res.previewId
                log("Patch preview created: ${res.previewId}")
            } catch (e: Exception) {
                log("Patch preview failed: ${e.message}")
            }
        }
    }

    private fun commitPatch() {
        scope.launch {
            try {
                val previewId = lastPreviewId
                if (previewId == null) {
                    log("No preview to commit.")
                    return@launch
                }
                val choiceRef = AtomicInteger(Messages.NO)
                ApplicationManager.getApplication().invokeAndWait {
                    val choice = Messages.showYesNoDialog(
                        project,
                        "Apply patch preview $previewId?",
                        "Confirm Patch Apply",
                        "Apply",
                        "Cancel",
                        null,
                    )
                    choiceRef.set(choice)
                }
                if (choiceRef.get() != Messages.YES) {
                    log("Patch commit cancelled by user.")
                    return@launch
                }
                val sid = activeSessionIdOrNull()
                val res = sidecar.patchCommit(PatchCommitReq(previewId = previewId, confirm = true, sessionId = sid))
                log("Patch commit: ${if (res.applied) "applied" else "not applied"}")
                if (!res.applied && !res.reason.isNullOrBlank()) {
                    log("Patch reason: ${res.reason}")
                }
                if (res.applied) {
                    lastPreviewId = null
                    lastBackupId = res.backupId
                    if (res.backupId != null) log("Rollback available: ${res.backupId}. Use 'patch rollback'.")
                }
            } catch (e: Exception) {
                log("Patch commit failed: ${e.message}")
            }
        }
    }

    private fun handleSidecarEvent(msg: JsonObject) {
        val params = msg.getAsJsonObject("params") ?: return
        val type = params.get("type")?.asString ?: return
        val data = params.getAsJsonObject("data")
        val sessionId = params.get("sessionId")?.asString ?: return
        val runId = params.get("runId")?.asString
        when (type) {
            "assistant.chunk" -> {
                val text = data?.get("text")?.asString ?: ""
                messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.ASSISTANT, text = text))
                log("Agent: $text")
            }
            "tool.call" -> {
                val name = data?.get("name")?.asString ?: "tool"
                val toolCallId = data?.get("toolCallId")?.asString ?: ""
                val status = data?.get("status")?.asString ?: "pending"
                toolCalls.upsert(
                    sessionId,
                    ToolCallState(
                        sessionId = sessionId,
                        runId = runId,
                        toolCallId = toolCallId,
                        name = name,
                        status = status,
                    ),
                )
                val msgText = "[TOOL] $name  •  status=$status  •  id=$toolCallId"
                messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.TOOL, text = msgText))
                logToolCard(name, status, toolCallId)
            }
            "tool.update" -> {
                val toolCallId = data?.get("toolCallId")?.asString ?: ""
                val status = data?.get("status")?.asString ?: "updated"
                val existing = toolCalls.get(sessionId, toolCallId)

                // Structured parse first
                val outputObj = data?.getAsJsonObject("output")
                val resultObj = outputObj?.getAsJsonObject("result")
                val previewFromResult = resultObj?.get("previewId")?.asString
                val previewFromTop = data?.get("previewId")?.asString
                val outputRaw = data?.get("output")?.toString() ?: ""
                val previewRegex = Regex("\"previewId\"\\s*:\\s*\"(pv_[^\"]+)\"")
                val previewFromRegex = previewRegex.find(outputRaw)?.groupValues?.getOrNull(1)
                val previewId = previewFromResult ?: previewFromTop ?: previewFromRegex
                if (!previewId.isNullOrBlank()) {
                    lastPreviewId = previewId
                    log("Patch preview ready: $previewId. Type 'patch commit' to apply.")
                    scope.launch {
                        try {
                            val details = sidecar.patchPreviewGet(PatchPreviewGetReq(previewId))
                            showNativeDiff(details.path, details.oldText, details.newText, details.diff)
                        } catch (e: Exception) {
                            log("Could not open native diff: ${e.message}")
                        }
                    }
                }

                if (existing != null) {
                    toolCalls.updateStatus(sessionId, toolCallId, status)
                    val msgText = "[TOOL] ${existing.name}  •  status=$status  •  id=$toolCallId"
                    messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.TOOL, text = msgText))
                    logToolCard(existing.name, status, toolCallId)
                } else {
                    val msgText = "[TOOL] unknown  •  status=$status  •  id=$toolCallId"
                    messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.TOOL, text = msgText))
                    logToolCard("unknown", status, toolCallId)
                }
            }
            "command.output" -> {
                val execId = data?.get("execId")?.asString ?: ""
                val stream = data?.get("stream")?.asString ?: "stdout"
                val chunk = data?.get("chunk")?.asString ?: ""
                logCommand("[$execId][$stream] $chunk")
            }
            "command.done" -> {
                val execId = data?.get("execId")?.asString ?: ""
                val exit = data?.get("exitCode")?.asInt ?: -1
                logCommand("[$execId] done exitCode=$exit")
            }
            "patch.preview.ready" -> {
                val previewId = data?.get("previewId")?.asString
                val applicable = data?.get("applicable")?.asBoolean ?: false
                val reason = data?.get("reason")?.asString
                if (!previewId.isNullOrBlank()) {
                    lastPreviewId = previewId
                    if (applicable) {
                        log("Patch preview ready: $previewId")
                    } else {
                        log("Patch preview ready but not directly applicable: ${reason ?: "unknown"}")
                    }
                    scope.launch {
                        try {
                            val details = sidecar.patchPreviewGet(PatchPreviewGetReq(previewId))
                            showNativeDiff(details.path, details.oldText, details.newText, details.diff)
                        } catch (e: Exception) {
                            log("Could not open native diff: ${e.message}")
                        }
                    }
                }
            }
            "prompt.done" -> {
                messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.SYSTEM, text = "Run completed"))
                if (runId != null && runId == lastRunId) lastRunId = null
                if (lastPreviewId == null) {
                    scope.launch {
                        try {
                            val last = sidecar.patchPreviewLast(PatchPreviewLastReq(sessionId))
                            if (!last.previewId.isNullOrBlank()) {
                                lastPreviewId = last.previewId
                                log("Recovered preview from session state: ${last.previewId}")
                                val details = sidecar.patchPreviewGet(PatchPreviewGetReq(last.previewId))
                                showNativeDiff(details.path, details.oldText, details.newText, details.diff)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                log("Run completed.")
            }
            "prompt.error" -> {
                val err = data?.get("message")?.asString ?: "unknown"
                messages.append(SessionMessage(sessionId = sessionId, runId = runId, kind = MessageKind.SYSTEM, text = "Run error: $err"))
                if (runId != null && runId == lastRunId) lastRunId = null
                log("Run error: $err")
            }
        }
    }

    private fun onSend() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        sessions.active()?.sessionId?.let {
            messages.append(SessionMessage(it, kind = MessageKind.USER, text = text))
        }
        log("You: $text")

        when {
            text.contains("summarize", ignoreCase = true) -> summarizeCurrentFile()
            text.contains("insert hello", ignoreCase = true) -> insertHelloComment()
            text.contains("list sessions", ignoreCase = true) -> listSessions()
            text.equals("new session", ignoreCase = true) -> createNewSession()
            text.equals("switch session", ignoreCase = true) -> switchSessionInteractive()
            text.equals("cancel", ignoreCase = true) -> cancelCurrentRun()
            text.startsWith("patch preview", ignoreCase = true) -> createPatchPreview(text)
            text.equals("patch commit", ignoreCase = true) -> commitPatch()
            text.equals("patch rollback", ignoreCase = true) -> rollbackPatch()
            text.equals("get current file", ignoreCase = true) -> getCurrentFileViaSidecar()
            text.equals("get selection", ignoreCase = true) -> getSelectionViaSidecar()
            text.startsWith("read file", ignoreCase = true) -> readFileViaSidecar(text)
            text.startsWith("find files", ignoreCase = true) -> findFilesViaSidecar(text)
            text.startsWith("search text", ignoreCase = true) -> searchTextViaSidecar(text)
            text.startsWith("run test", ignoreCase = true) -> runTestViaSidecar(text)
            text.equals("symbol at cursor", ignoreCase = true) -> symbolAtCursorViaSidecar()
            text.startsWith("find usages", ignoreCase = true) -> findUsagesViaSidecar(text)
            text.equals("diagnostics", ignoreCase = true) -> diagnosticsViaSidecar()
            else -> sendPromptToSidecar(text)
        }
    }

    private fun createNewSession() {
        scope.launch {
            try {
                val title = "Session-${System.currentTimeMillis().toString().takeLast(4)}"
                val created = sidecar.createSession(
                    CreateSessionReq(
                        workspacePath = project.basePath ?: ".",
                        title = title,
                    ),
                )
                sessions.upsert(SessionState(created.sessionId, title, created.status))
                sessions.setActive(created.sessionId)
                refreshSessionRail()
                refreshSessionViews()
                log("Created and switched to session: ${created.sessionId}")
            } catch (e: Exception) {
                log("Create session failed: ${e.message}")
            }
        }
    }

    private fun activeSessionIdOrNull(): String? = sessions.active()?.sessionId

    private fun activeEditorPathOrNull(): String? {
        val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project } ?: return null
        return editor.virtualFile?.path
    }

    private fun getCurrentFileViaSidecar() {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val path = activeEditorPathOrNull() ?: return@launch log("No active editor file.")
                val res = sidecar.getCurrentFile(GetCurrentFileReq(sessionId = sid, path = path))
                log("Current file: ${res.path} (${res.language}) chars=${res.content.length}")
            } catch (e: Exception) {
                log("get_current_file failed: ${e.message}")
            }
        }
    }

    private fun getSelectionViaSidecar() {
        val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
        if (editor == null) {
            log("No active editor.")
            return
        }
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val path = editor.virtualFile?.path ?: return@launch
                val sel = editor.selectionModel
                val res = sidecar.getSelection(
                    GetSelectionReq(sessionId = sid, path = path, startOffset = sel.selectionStart, endOffset = sel.selectionEnd),
                )
                log("Selection len=${res.text.length}: ${res.text.take(120)}")
            } catch (e: Exception) {
                log("get_selection failed: ${e.message}")
            }
        }
    }

    private fun readFileViaSidecar(text: String) {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val path = text.removePrefix("read file").trim().ifBlank { activeEditorPathOrNull() ?: "" }
                if (path.isBlank()) return@launch log("Path required.")
                val res = sidecar.readFile(ReadFileReq(sessionId = sid, path = path))
                log("Read file ok, chars=${res.content.length}")
            } catch (e: Exception) {
                log("read_file failed: ${e.message}")
            }
        }
    }

    private fun findFilesViaSidecar(text: String) {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val glob = text.removePrefix("find files").trim().ifBlank { "**/*" }
                val res = sidecar.findFiles(FindFilesReq(sessionId = sid, glob = glob))
                log("find_files: ${res.paths.size} found")
            } catch (e: Exception) {
                log("find_files failed: ${e.message}")
            }
        }
    }

    private fun searchTextViaSidecar(text: String) {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val q = text.removePrefix("search text").trim()
                if (q.isBlank()) return@launch log("Query required.")
                val res = sidecar.searchText(SearchTextReq(sessionId = sid, query = q))
                log("search_text: ${res.matches.size} matches")
            } catch (e: Exception) {
                log("search_text failed: ${e.message}")
            }
        }
    }

    private fun runTestViaSidecar(text: String) {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val target = text.removePrefix("run test").trim().ifBlank { "pytest -q" }
                val res = sidecar.runTest(RunTestReq(sessionId = sid, target = target))
                log("run_test started=${res.summary.started} execId=${res.summary.execId}")
            } catch (e: Exception) {
                log("run_test failed: ${e.message}")
            }
        }
    }

    private fun symbolAtCursorViaSidecar() {
        val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
        if (editor == null) {
            log("No active editor.")
            return
        }
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val path = editor.virtualFile?.path ?: return@launch
                val offset = editor.caretModel.offset
                val res = sidecar.getSymbolAtCursor(GetSymbolReq(sessionId = sid, path = path, cursorOffset = offset))
                log("symbol_at_cursor: '${res.symbol}' [${res.startOffset}, ${res.endOffset}]")
            } catch (e: Exception) {
                log("symbol_at_cursor failed: ${e.message}")
            }
        }
    }

    private fun findUsagesViaSidecar(text: String) {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val symbol = text.removePrefix("find usages").trim()
                if (symbol.isBlank()) return@launch log("Symbol required.")
                val res = sidecar.findUsages(FindUsagesReq(sessionId = sid, symbol = symbol))
                log("find_usages: ${res.matches.size} matches")
            } catch (e: Exception) {
                log("find_usages failed: ${e.message}")
            }
        }
    }

    private fun diagnosticsViaSidecar() {
        scope.launch {
            try {
                val sid = activeSessionIdOrNull() ?: return@launch
                val path = activeEditorPathOrNull() ?: return@launch log("No active editor file.")
                val res = sidecar.getDiagnostics(GetDiagnosticsReq(sessionId = sid, path = path))
                log("diagnostics: ${res.diagnostics.size} items")
            } catch (e: Exception) {
                log("diagnostics failed: ${e.message}")
            }
        }
    }

    private fun rollbackPatch() {
        scope.launch {
            try {
                val bid = lastBackupId
                if (bid == null) {
                    log("No backup to rollback.")
                    return@launch
                }
                val res = sidecar.patchRollback(PatchRollbackReq(bid))
                if (res.rolledBack) {
                    log("Patch rollback successful: $bid")
                    lastBackupId = null
                } else {
                    log("Patch rollback failed: $bid")
                }
            } catch (e: Exception) {
                log("Patch rollback failed: ${e.message}")
            }
        }
    }

    private fun rejectPatchPreview() {
        val pid = lastPreviewId
        if (pid == null) {
            log("No preview to reject.")
            return
        }
        lastPreviewId = null
        log("Rejected preview: $pid")
    }

    private fun runToolsSmoke() {
        scope.launch {
            val sid = activeSessionIdOrNull()
            if (sid == null) {
                log("[SMOKE] FAIL: no active session")
                return@launch
            }

            var pass = 0
            var fail = 0

            fun ok(name: String, detail: String = "") {
                pass += 1
                log("[SMOKE] PASS $name ${if (detail.isNotBlank()) "- $detail" else ""}")
            }

            fun no(name: String, err: String) {
                fail += 1
                log("[SMOKE] FAIL $name - $err")
            }

            try {
                val path = activeEditorPathOrNull()
                if (!path.isNullOrBlank()) {
                    val cur = sidecar.getCurrentFile(GetCurrentFileReq(sessionId = sid, path = path))
                    ok("get_current_file", "chars=${cur.content.length}")
                } else {
                    no("get_current_file", "no active editor path")
                }
            } catch (e: Exception) {
                no("get_current_file", e.message ?: "error")
            }

            try {
                val path = activeEditorPathOrNull()
                if (!path.isNullOrBlank()) {
                    val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
                    val start = editor?.selectionModel?.selectionStart ?: 0
                    val end = editor?.selectionModel?.selectionEnd ?: 0
                    val sel = sidecar.getSelection(GetSelectionReq(sessionId = sid, path = path, startOffset = start, endOffset = end))
                    ok("get_selection", "len=${sel.text.length}")
                } else {
                    no("get_selection", "no active editor path")
                }
            } catch (e: Exception) {
                no("get_selection", e.message ?: "error")
            }

            try {
                val path = activeEditorPathOrNull()
                if (!path.isNullOrBlank()) {
                    val rd = sidecar.readFile(ReadFileReq(sessionId = sid, path = path))
                    ok("read_file", "chars=${rd.content.length}")
                } else {
                    no("read_file", "no active editor path")
                }
            } catch (e: Exception) {
                no("read_file", e.message ?: "error")
            }

            try {
                val ff = sidecar.findFiles(FindFilesReq(sessionId = sid, glob = "**/*"))
                ok("find_files", "count=${ff.paths.size}")
            } catch (e: Exception) {
                no("find_files", e.message ?: "error")
            }

            try {
                val st = sidecar.searchText(SearchTextReq(sessionId = sid, query = "class"))
                ok("search_text", "matches=${st.matches.size}")
            } catch (e: Exception) {
                no("search_text", e.message ?: "error")
            }

            try {
                val rt = sidecar.runTest(RunTestReq(sessionId = sid, target = "pytest -q"))
                ok("run_test", "execId=${rt.summary.execId}")
            } catch (e: Exception) {
                no("run_test", e.message ?: "error")
            }

            log("[SMOKE] DONE pass=$pass fail=$fail")
        }
    }

    private fun switchSessionInteractive() {
        val all = sessions.list()
        if (all.isEmpty()) {
            log("No sessions available.")
            return
        }
        val choices = all.map { "${it.sessionId} (${it.status})" }.toTypedArray()
        val selected = Messages.showEditableChooseDialog(
            "Pick session",
            "Switch Session",
            null,
            choices,
            choices.firstOrNull(),
            null,
        ) ?: return
        val sessionId = selected.substringBefore(" ")
        if (sessions.setActive(sessionId)) {
            refreshSessionRail()
            refreshSessionViews()
            log("Switched active session: $sessionId")
        } else {
            log("Session not found: $sessionId")
        }
    }

    private fun closeActiveSession() {
        val active = sessions.active()?.sessionId
        if (active == null) {
            log("No active session to close.")
            return
        }
        val ok = sessions.remove(active)
        if (ok) {
            refreshSessionRail()
            refreshSessionViews()
            log("Closed session: $active")
        } else {
            log("Failed to close session: $active")
        }
    }

    private fun listSessions() {
        scope.launch {
            try {
                val list = sidecar.listSessions()
                list.sessions.forEach { sessions.upsert(SessionState(it.sessionId, it.title, it.status)) }
                refreshSessionRail()
                val active = sessions.active()?.sessionId
                log(
                    "Sessions: " + if (list.sessions.isEmpty()) {
                        "none"
                    } else {
                        list.sessions.joinToString { s ->
                            val mark = if (s.sessionId == active) "*" else ""
                            "${s.sessionId}:${s.status}$mark"
                        }
                    },
                )
            } catch (e: Exception) {
                log("Agent: failed to list sessions: ${e.message}")
            }
        }
    }

    private fun summarizeCurrentFile() {
        val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
        if (editor == null) {
            log("Agent: No active editor found.")
            return
        }
        val content = editor.document.text
        val lines = content.lines().size
        val chars = content.length
        val preview = content.take(240).replace("\n", " ")
        log("Agent: Current file has $lines lines, $chars chars. Preview: $preview")
    }

    private fun insertHelloComment() {
        val editor = EditorFactory.getInstance().allEditors.firstOrNull { it.project == project }
        if (editor == null) {
            log("Agent: No active editor found.")
            return
        }

        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(0, "// Intelli Agent says hi\n")
        }
        log("Agent: Inserted hello comment at top of file.")
    }

    private fun applyIndustrialNoirTheme() {
        val bg = Color(17, 19, 23)
        val panel = Color(22, 25, 30)
        val text = Color(225, 229, 235)
        val muted = Color(153, 162, 173)
        val accent = Color(76, 203, 255)

        background = bg
        output.background = panel
        output.foreground = text
        output.caretColor = accent
        output.font = Font("JetBrains Mono", Font.PLAIN, 13)

        toolCards.background = panel
        toolCards.foreground = text
        toolCards.caretColor = accent
        toolCards.font = Font("JetBrains Mono", Font.PLAIN, 12)

        commandOutput.background = panel
        commandOutput.foreground = muted
        commandOutput.caretColor = accent
        commandOutput.font = Font("JetBrains Mono", Font.PLAIN, 12)

        input.background = Color(13, 15, 19)
        input.foreground = text
        input.caretColor = accent
        input.font = Font("JetBrains Mono", Font.PLAIN, 13)
        input.toolTipText = "State intent clearly. Approvals and diff are inline in timeline."

        sessionList.background = Color(12, 14, 18)
        sessionList.foreground = text
        sessionList.selectionBackground = Color(33, 42, 52)
        sessionList.selectionForeground = Color(229, 246, 255)
        sessionList.font = Font("JetBrains Mono", Font.PLAIN, 12)
    }

    private fun refreshSessionRail() {
        val active = sessions.active()?.sessionId
        ApplicationManager.getApplication().invokeLater {
            sessionListModel.clear()
            sessions.list().forEach { s ->
                val mark = if (s.sessionId == active) "◉" else "○"
                val status = when (s.status.lowercase()) {
                    "running" -> "RUN"
                    "error" -> "ERR"
                    "idle" -> "IDL"
                    else -> "UNK"
                }
                sessionListModel.addElement("$mark  ${s.sessionId.take(12)}  [$status]")
            }
        }
    }

    private fun refreshSessionViews() {
        val sessionId = sessions.active()?.sessionId
        val msgList = if (sessionId == null) emptyList() else messages.list(sessionId)
        val toolList = if (sessionId == null) emptyList() else toolCalls.list(sessionId)

        ApplicationManager.getApplication().invokeLater {
            output.text = ""
            toolCards.text = ""

            msgList.forEach { m ->
                when (m.kind) {
                    MessageKind.USER -> output.append("You: ${m.text}\n")
                    MessageKind.ASSISTANT -> output.append("Agent: ${m.text}\n")
                    MessageKind.SYSTEM -> output.append("${m.text}\n")
                    MessageKind.TOOL -> {}
                }
            }

            toolList.forEach { t ->
                val badge = when (t.status.lowercase()) {
                    "pending" -> "🟡"
                    "completed" -> "🟢"
                    "failed" -> "🔴"
                    else -> "🔵"
                }
                toolCards.append("$badge  ${t.name}\n   id: ${t.toolCallId}\n   status: ${t.status}\n\n")
            }

            output.caretPosition = output.document.length
            toolCards.caretPosition = toolCards.document.length
        }
    }

    private fun log(message: String) {
        ApplicationManager.getApplication().invokeLater {
            val line = when {
                message.startsWith("You:") -> "\n▌ USER\n${message.removePrefix("You:").trim()}\n"
                message.startsWith("Agent:") -> "\n▌ AGENT\n${message.removePrefix("Agent:").trim()}\n"
                message.startsWith("Run ") || message.startsWith("Patch ") || message.startsWith("[SMOKE]") -> "\n▌ STATE\n$message\n"
                else -> "$message\n"
            }
            output.append(line)
            output.caretPosition = output.document.length
        }
    }

    private fun logToolCard(name: String, status: String, toolCallId: String) {
        val badge = when (status.lowercase()) {
            "pending" -> "🟡"
            "completed" -> "🟢"
            "failed" -> "🔴"
            else -> "🔵"
        }
        ApplicationManager.getApplication().invokeLater {
            toolCards.append("$badge  $name\n   id: $toolCallId\n   status: $status\n\n")
            toolCards.caretPosition = toolCards.document.length
        }
    }

    private fun showNativeDiff(path: String, oldText: String?, newText: String?, diffText: String?) {
        ApplicationManager.getApplication().invokeLater {
            val factory = DiffContentFactory.getInstance()
            val left = factory.create(project, oldText ?: diffText ?: "")
            val right = factory.create(project, newText ?: "(No auto-applicable patch body available)")
            val title = if (path.isNotBlank()) "Patch Preview: $path" else "Patch Preview"
            val request = SimpleDiffRequest(title, left, right, "Before", "After")
            DiffManager.getInstance().showDiff(project, request)
        }
    }

    private fun logCommand(line: String) {
        ApplicationManager.getApplication().invokeLater {
            commandOutput.append(line + "\n")
            commandOutput.caretPosition = commandOutput.document.length
        }
    }
}
