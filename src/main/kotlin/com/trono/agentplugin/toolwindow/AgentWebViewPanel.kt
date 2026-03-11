package com.trono.agentplugin.toolwindow

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.trono.agentplugin.protocol.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.JLabel
import javax.swing.JPanel

class AgentWebViewPanel(private val project: Project) : JPanel(BorderLayout()) {
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var browser: JBCefBrowser? = null
    private var sidecar: SidecarClient = StubSidecarClient()
    private var sessionId: String? = null
    private var lastRunId: String? = null
    private var lastPreviewId: String? = null
    private var diffDialog: DialogWrapper? = null
    private var diffDialogDisposable: Disposable? = null
    private val runBuffers = linkedMapOf<String, StringBuilder>()
    private val hiddenSessionIds = linkedSetOf<String>()

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

            val html = """
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8" />
                    <style>
                      :root { --bg:#2b2b2b; --panel:#3c3f41; --panel-dark:#313335; --line:#323232; --line-light:#4d5052; --text:#a9b7c6; --text-bright:#bababa; --muted:#6f737a; --accent:#4c9be8; --agent:#59a869; --warn:#a8a800; --err:#cc4444; }
                      html,body { margin:0; height:100%; background:var(--bg); color:var(--text); font-family: -apple-system, 'JetBrains Sans', 'Segoe UI', system-ui, sans-serif; font-size:12px; }
                      .app { display:grid; grid-template-columns:190px 1fr; height:100%; }
                      .rail { border-right:1px solid var(--line); padding:8px; display:flex; flex-direction:column; gap:8px; background:var(--panel-dark); }
                      .title { font-size:10.5px; letter-spacing:.08em; color:var(--muted); text-transform:uppercase; font-weight:600; }
                      .session { border:1px solid var(--line-light); border-radius:4px; padding:7px; background:var(--panel); font-family:'JetBrains Mono', Menlo, monospace; font-size:11px; display:flex; align-items:center; justify-content:space-between; gap:8px; }
                      .session-delete { opacity:0; border:none; background:transparent; color:var(--muted); cursor:pointer; font-size:11px; padding:0 2px; }
                      .session:hover .session-delete { opacity:1; }
                      .session-delete:hover { color:var(--err); }
                      .rail button { border:1px solid transparent; background:transparent; color:var(--text); border-radius:4px; padding:6px 8px; text-align:left; font-size:11px; }
                      .rail button:hover { background:#45494a; }

                      .main { display:grid; grid-template-rows:auto 1fr auto; }
                      .head { border-bottom:1px solid var(--line); padding:8px 10px; display:flex; align-items:center; justify-content:space-between; gap:8px; background:var(--panel-dark); }
                      .head h3 { margin:0; font-size:11px; letter-spacing:.08em; text-transform:uppercase; color:var(--text-bright); }
                      .head p { margin:2px 0 0; color:var(--muted); font-size:11px; }

                      .status-wrap { display:flex; align-items:center; gap:8px; }
                      .status-dot { width:6px; height:6px; border-radius:999px; background:#5c6b7a; }
                      .status-text { color:var(--muted); font-size:11px; max-width:380px; text-overflow:ellipsis; overflow:hidden; white-space:nowrap; }

                      .patch-actions { display:none; gap:4px; margin-top:4px; }
                      .patch-actions button { font-size:11px; padding:4px 8px; border-radius:4px; border:1px solid var(--line-light); background:#2f3438; color:var(--text-bright); }

                      .timeline { padding:10px 12px; overflow:auto; display:flex; flex-direction:column; gap:8px; }
                      .card { border:1px solid var(--line-light); border-radius:4px; padding:7px 9px; background:var(--panel); white-space:pre-wrap; line-height:1.55; box-shadow:none; max-width: 88%; }
                      .card.user { border-left:2px solid var(--accent); background:#2f3543; align-self:flex-start; }
                      .card.agent { border-left:2px solid var(--agent); background:#323232; align-self:flex-end; }
                      .card .md h1,.card .md h2,.card .md h3 { margin: 2px 0 8px; font-size: 13px; color: var(--text-bright); }
                      .card .md p { margin: 0 0 8px; }
                      .card .md ul,.card .md ol { margin: 0 0 8px 18px; }
                      .card .md li { margin: 2px 0; }
                      .card .md code { font-family:'JetBrains Mono', Menlo, monospace; font-size:11px; background:#262a2d; border:1px solid #444; border-radius:3px; padding:0 4px; }
                      .card .md pre { margin: 6px 0 10px; background:#202427; border:1px solid #45494a; border-radius:4px; padding:8px; overflow:auto; }
                      .card .md pre code { background:transparent; border:none; padding:0; }
                      .card .md a { color: var(--accent); text-decoration: none; }
                      .card .md a:hover { text-decoration: underline; }

                      .composer { border-top:1px solid var(--line); padding:8px; display:grid; grid-template-columns:1fr auto; gap:6px; background:var(--panel-dark); }
                      input { border:1px solid var(--line-light); background:#2b2b2b; color:var(--text); border-radius:4px; padding:8px 10px; font:inherit; }
                      button { border:1px solid var(--line-light); background:#3f4448; color:var(--text-bright); border-radius:4px; padding:0 10px; font:inherit; min-height:32px; }
                    </style>
                  </head>
                  <body>
                    <div class="app">
                      <aside class="rail">
                        <div class="title">Sessions</div>
                        <div id="sessionsList"></div>
                        <button onclick="uiInvoke('session.new', {})">➕ New Session</button>
                        <button onclick="uiInvoke('prompt.cancel', {})">🛑 Cancel Run</button>
                      </aside>

                      <main class="main">
                        <header class="head">
                          <div>
                            <h3>Intelli Agent</h3>
                            <p>Ask, edit, review and apply safely.</p>
                          </div>
                          <div>
                            <div class="status-wrap">
                              <div class="status-dot" id="statusDot"></div>
                              <div class="status-text" id="statusText">booting...</div>
                            </div>
                            <div class="patch-actions" id="patchActions">
                              <button onclick="uiInvoke('ide.patch_open_diff', {})">Open Diff</button>
                              <button onclick="uiInvoke('ide.patch_commit', {})">Apply</button>
                              <button onclick="uiInvoke('ide.patch_reject', {})">Reject</button>
                            </div>
                          </div>
                        </header>

                        <section class="timeline" id="timeline"></section>

                        <footer class="composer">
                          <input id="prompt" placeholder="Type prompt..." onkeydown="if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();sendPrompt();}" />
                          <button onclick="sendPrompt()">EXECUTE</button>
                        </footer>
                      </main>
                    </div>

                    <script>
                      const timeline = document.getElementById('timeline');
                      const sessionsList = document.getElementById('sessionsList');
                      const lastTextById = new Map();
                      const statusDot = document.getElementById('statusDot');
                      const statusText = document.getElementById('statusText');
                      const patchActions = document.getElementById('patchActions');
                      const messagesBySession = new Map();
                      let activeSessionId = null;

                      function renderTimeline(){
                        timeline.innerHTML = '';
                        const list = messagesBySession.get(activeSessionId) || [];
                        for (const m of list) {
                          const d = document.createElement('div');
                          d.className = 'card ' + m.role;
                          d.textContent = (m.role === 'user' ? 'USER' : 'AGENT') + '\n' + m.text;
                          timeline.appendChild(d);
                        }
                        timeline.scrollTop = timeline.scrollHeight;
                      }

                      function escapeHtml(str){
                        return String(str)
                          .replace(/&/g,'&amp;')
                          .replace(/</g,'&lt;')
                          .replace(/>/g,'&gt;')
                          .replace(/"/g,'&quot;')
                          .replace(/'/g,'&#39;');
                      }

                      function mdToHtml(md){
                        let s = escapeHtml(md || '');
                        s = s.replace(/```([\s\S]*?)```/g, (_, code) => `<pre><code>${'$'}{code}</code></pre>`);
                        s = s.replace(/^###\s+(.+)$/gm, '<h3>$1</h3>');
                        s = s.replace(/^##\s+(.+)$/gm, '<h2>$1</h2>');
                        s = s.replace(/^#\s+(.+)$/gm, '<h1>$1</h1>');
                        s = s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
                        s = s.replace(/\*(.+?)\*/g, '<em>$1</em>');
                        s = s.replace(/`([^`]+)`/g, '<code>$1</code>');
                        s = s.replace(/\[([^\]]+)\]\((https?:\/\/[^\)]+)\)/g, '<a href="$2">$1</a>');
                        s = s.replace(/(?:^|\n)-\s+(.+)(?=(\n[^-]|$))/g, (m) => {
                          const items = m.trim().split(/\n/).map(l => l.replace(/^[-]\s+/, '')).map(i => `<li>${'$'}{i}</li>`).join('');
                          return `\n<ul>${'$'}{items}</ul>`;
                        });
                        s = s.replace(/(?:^|\n)\d+\.\s+(.+)(?=(\n[^\d]|$))/g, (m) => {
                          const items = m.trim().split(/\n/).map(l => l.replace(/^\d+\.\s+/, '')).map(i => `<li>${'$'}{i}</li>`).join('');
                          return `\n<ol>${'$'}{items}</ol>`;
                        });
                        s = s.split(/\n{2,}/).map(block => {
                          if (/^\s*<(h\d|ul|ol|pre)/.test(block)) return block;
                          return `<p>${'$'}{block.replace(/\\n/g,'<br/>')}</p>`;
                        }).join('');
                        return `<div class="md">${'$'}{s}</div>`;
                      }

                      function add(role, text, id, sid){
                        if(role !== 'user' && role !== 'agent') return; // hide STATE in chat
                        if(id && lastTextById.get(id) === text) return;
                        if(id) lastTextById.set(id, text);
                        const sessionKey = sid || activeSessionId || 'default';
                        const list = messagesBySession.get(sessionKey) || [];
                        const existingIndex = id ? list.findIndex(x => x.id === id) : -1;
                        if (existingIndex >= 0) list[existingIndex].text = text;
                        else list.push({ role, text, id });
                        messagesBySession.set(sessionKey, list);
                        if (sessionKey === activeSessionId) renderTimeline();
                      }

                      function setStatus(kind, text){
                        const map = { idle:'#5c6b7a', running:'#ffd166', done:'#7cff8f', error:'#ff6b88' };
                        statusDot.style.background = map[kind] || map.idle;
                        statusText.textContent = text || '';
                      }

                      function setPatchVisible(v){
                        patchActions.style.display = v ? 'flex' : 'none';
                      }

                      function renderSessions(items){
                        sessionsList.innerHTML = '';
                        for (const s of items || []) {
                          const el = document.createElement('div');
                          el.className = 'session';
                          const label = document.createElement('span');
                          label.textContent = (s.sessionId === activeSessionId ? '◉ ' : '○ ') + s.sessionId;
                          label.style.cursor = 'pointer';
                          label.onclick = () => uiInvoke('session.switch', { sessionId: s.sessionId });

                          const del = document.createElement('button');
                          del.className = 'session-delete';
                          del.title = 'Delete session';
                          del.textContent = '✕';
                          del.onclick = (e) => { e.stopPropagation(); uiInvoke('session.delete', { sessionId: s.sessionId }); };

                          el.appendChild(label);
                          el.appendChild(del);
                          sessionsList.appendChild(el);
                        }
                      }

                      window.__onKotlinEvent = function(evt){
                        if(evt.type === 'session'){ activeSessionId = evt.sessionId; renderTimeline(); }
                        if(evt.type === 'sessions'){ renderSessions(evt.items || []); }
                        if(evt.type === 'message'){ add(evt.role || 'state', evt.text || '', evt.id, evt.sessionId); }
                        if(evt.type === 'status'){ setStatus(evt.kind || 'idle', evt.text || ''); }
                        if(evt.type === 'patchActions'){ setPatchVisible(!!evt.visible); }
                      }

                      function send(obj){
                        const payload = JSON.stringify(obj);
                        ${jsQuery.inject("payload")}
                      }
                      function uiInvoke(method, params){ send({ type:'ui.invoke', method, params }); }
                      function sendPrompt(){
                        const text = document.getElementById('prompt').value || '';
                        if(!text.trim()) return;
                        add('user', text, null, activeSessionId);
                        send({ type:'prompt.send', text });
                        document.getElementById('prompt').value = '';
                      }

                      send({ type:'ui.ready' });
                    </script>
                  </body>
                </html>
            """.trimIndent()

            b.loadHTML(html)
            connectSidecar()
        }
    }

    private fun connectSidecar() {
        scope.launch {
            try {
                val ws = WsSidecarClient(onEvent = { evt ->
                    val params = evt.getAsJsonObject("params") ?: return@WsSidecarClient
                    val type = params.get("type")?.asString ?: return@WsSidecarClient
                    val runId = params.get("runId")?.asString ?: ""
                    val data = params.getAsJsonObject("data")
                    when (type) {
                        "assistant.chunk" -> {
                            val buf = runBuffers.getOrPut(runId) { StringBuilder() }
                            buf.append(data?.get("text")?.asString ?: "")
                            emitUiMessage("agent", buf.toString(), "agent-$runId")
                            emitUiStatus("running", "Streaming response…")
                        }
                        "prompt.done" -> {
                            runBuffers.remove(runId)
                            emitUiStatus("done", "Run completed")
                            scope.launch {
                                reconcilePatchForSession(params.get("sessionId")?.asString ?: sessionId)
                            }
                        }
                        "prompt.error" -> {
                            runBuffers.remove(runId)
                            emitUiStatus("error", "Run error: ${data?.get("message")?.asString ?: "unknown"}")
                        }
                        "patch.preview.ready" -> {
                            lastPreviewId = data?.get("previewId")?.asString
                            emitUiStatus("done", "Patch preview ready${if (lastPreviewId != null) ": $lastPreviewId" else ""}")
                            emitPatchActionsVisible(true)
                            lastPreviewId?.let { pid ->
                                scope.launch {
                                    try {
                                        val details = sidecar.patchPreviewGet(PatchPreviewGetReq(pid))
                                        showNativeDiff(details.path, details.oldText, details.newText, details.diff)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        "command.output" -> emitUiStatus("running", (data?.get("chunk")?.asString ?: "").trim())
                        "command.done" -> emitUiStatus("done", "Command finished exit=${data?.get("exitCode")?.asInt ?: -1}")
                    }
                })

                val init = ws.initialize(InitializeReq(ClientInfo("intellij-plugin", "0.4.0"), project.basePath ?: "."))
                val created = ws.createSession(CreateSessionReq(workspacePath = project.basePath ?: ".", title = "Default"))
                sidecar = ws
                sessionId = created.sessionId
                emitUiSession(created.sessionId)
                emitSessions()
                emitUiStatus("idle", "Connected: ${init.server.name} ${init.server.version}")
                reconcilePatchForSession(created.sessionId)
            } catch (e: Exception) {
                emitUiStatus("error", "Sidecar fallback: ${e.message}")
            }
        }
    }

    private fun handleUiEvent(payload: String) {
        val obj = try { gson.fromJson(payload, JsonObject::class.java) } catch (_: Exception) { return }
        when (obj.get("type")?.asString ?: return) {
            "ui.ready" -> {
                emitUiStatus("idle", "Ready")
                scope.launch { emitSessions() }
            }
            "prompt.send" -> {
                val text = obj.get("text")?.asString ?: return
                val sid = sessionId ?: return
                scope.launch {
                    try {
                        val accepted = sidecar.sendPrompt(PromptReq(sessionId = sid, prompt = buildPromptWithContext(text)))
                        lastRunId = accepted.runId
                        emitUiStatus("running", "Run started: ${accepted.runId}")
                    } catch (e: Exception) {
                        emitUiStatus("error", "Send failed: ${e.message}")
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
                        emitUiStatus("idle", "Switched to ${created.sessionId}")
                        emitPatchActionsVisible(false)
                        closeDiffDialog()
                        reconcilePatchForSession(created.sessionId)
                    }
                    "session.switch" -> {
                        val target = params?.get("sessionId")?.asString
                        if (!target.isNullOrBlank()) {
                            sessionId = target
                            emitUiSession(target)
                            emitSessions()
                            emitUiStatus("idle", "Switched to $target")
                            emitPatchActionsVisible(false)
                            closeDiffDialog()
                            reconcilePatchForSession(target)
                        }
                    }
                    "session.delete" -> {
                        val target = params?.get("sessionId")?.asString
                        if (!target.isNullOrBlank()) {
                            hiddenSessionIds.add(target)
                            if (sessionId == target) {
                                val all = sidecar.listSessions().sessions.map { it.sessionId }.filter { !hiddenSessionIds.contains(it) }
                                sessionId = all.firstOrNull()
                                sessionId?.let { emitUiSession(it) }
                            }
                            emitSessions()
                            emitUiStatus("idle", "Hidden session $target")
                            emitPatchActionsVisible(false)
                        }
                    }
                    "prompt.cancel" -> {
                        sidecar.cancelPrompt(CancelReq(sid, lastRunId))
                        emitUiStatus("idle", "Run cancelled")
                    }
                    "ide.patch_open_diff" -> {
                        val pid = lastPreviewId ?: return@launch emitUiStatus("error", "No preview to open")
                        val details = sidecar.patchPreviewGet(PatchPreviewGetReq(pid))
                        showNativeDiff(details.path, details.oldText, details.newText, details.diff)
                    }
                    "ide.patch_commit" -> {
                        val pid = lastPreviewId ?: return@launch emitUiStatus("error", "No preview to commit")
                        val res = sidecar.patchCommit(PatchCommitReq(previewId = pid, confirm = true, sessionId = sid))
                        if (res.applied) {
                            emitUiStatus("done", "Patch applied")
                            lastPreviewId = null
                            emitPatchActionsVisible(false)
                            closeDiffDialog()
                        } else {
                            emitUiStatus("error", "Patch not applied: ${res.reason ?: "unknown"}")
                        }
                    }
                    "ide.patch_reject" -> {
                        lastPreviewId = null
                        emitPatchActionsVisible(false)
                        closeDiffDialog()
                        emitUiStatus("idle", "Patch rejected")
                    }
                }
            } catch (e: Exception) {
                emitUiStatus("error", "Action failed: ${e.message}")
            }
        }
    }

    private suspend fun reconcilePatchForSession(sidInput: String?) {
        val sid = sidInput ?: return
        try {
            val last = sidecar.patchPreviewLast(PatchPreviewLastReq(sid))
            if (!last.previewId.isNullOrBlank()) {
                lastPreviewId = last.previewId
                emitPatchActionsVisible(true)
                emitUiStatus(
                    if (last.applicable) "done" else "error",
                    if (last.applicable) "Patch preview ready: ${last.previewId}" else "Patch preview not directly applicable: ${last.reason ?: "unknown"}",
                )
                try {
                    val details = sidecar.patchPreviewGet(PatchPreviewGetReq(last.previewId))
                    showNativeDiff(details.path, details.oldText, details.newText, details.diff)
                } catch (_: Exception) {
                }
            } else {
                lastPreviewId = null
                emitPatchActionsVisible(false)
            }
        } catch (_: Exception) {
            // keep UI stable if preview reconcile fails
        }
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

    private fun showNativeDiff(path: String, oldText: String?, newText: String?, diffText: String?) {
        ApplicationManager.getApplication().invokeLater {
            closeDiffDialog()
            val factory = DiffContentFactory.getInstance()
            val left = factory.create(project, oldText ?: diffText ?: "")
            val right = factory.create(project, newText ?: "(No auto-applicable patch body available)")
            val title = if (path.isNotBlank()) "Patch Preview: $path" else "Patch Preview"
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
                override fun createActions() = arrayOf(okAction, cancelAction)
            }
            diffDialogDisposable = disposable
            diffDialog = dialog
            dialog.show()
        }
    }

    private fun closeDiffDialog() {
        ApplicationManager.getApplication().invokeLater {
            try {
                diffDialog?.close(DialogWrapper.OK_EXIT_CODE)
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
                .filter { !hiddenSessionIds.contains(it.sessionId) }
                .map { mapOf("sessionId" to it.sessionId, "status" to it.status) }
            emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "sessions", "items" to list))});")
        } catch (_: Exception) {
        }
    }

    private fun emitUiSession(sid: String) = emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "session", "sessionId" to sid))});")
    private fun emitUiMessage(role: String, text: String, id: String? = null) =
        emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "message", "role" to role, "text" to text, "id" to id, "sessionId" to sessionId))});")
    private fun emitUiStatus(kind: String, text: String) = emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "status", "kind" to kind, "text" to text))});")
    private fun emitPatchActionsVisible(visible: Boolean) = emitJs("window.__onKotlinEvent?.(${gson.toJson(mapOf("type" to "patchActions", "visible" to visible))});")

    private fun emitJs(js: String) {
        ApplicationManager.getApplication().invokeLater {
            browser?.cefBrowser?.executeJavaScript(js, browser?.cefBrowser?.url ?: "about:blank", 0)
        }
    }
}
