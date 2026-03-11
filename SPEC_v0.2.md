# Intelli Agent Plugin — Spec v0.2

## 1) Protocol (JSON-RPC 2.0 over WebSocket)

Base envelope:

```json
{
  "jsonrpc": "2.0",
  "id": "req-123",
  "method": "session.create",
  "params": {}
}
```

Events are server -> client notifications:

```json
{
  "jsonrpc": "2.0",
  "method": "event",
  "params": {
    "type": "assistant.chunk",
    "sessionId": "s_123",
    "data": {"text": "..."}
  }
}
```

---

## 2) Core RPC methods

### initialize

Request:
```json
{
  "client": {"name": "intellij-plugin", "version": "0.1.0"},
  "workspacePath": "/path/to/project"
}
```

Response:
```json
{
  "server": {"name": "intelli-agent-sidecar", "version": "0.1.0"},
  "capabilities": {
    "sessions": true,
    "streaming": true,
    "tools": [
      "ide.get_current_file",
      "ide.get_selection",
      "ide.read_file",
      "ide.find_files",
      "ide.search_text",
      "ide.apply_patch_preview",
      "ide.apply_patch_commit",
      "ide.run_command",
      "ide.run_test"
    ]
  }
}
```

### session.create

Request:
```json
{
  "workspacePath": "/path/to/project",
  "title": "Default",
  "model": "claude-sonnet-4.5",
  "mode": "default"
}
```

Response:
```json
{"sessionId": "s_abc123", "status": "idle"}
```

### session.list
Response:
```json
{
  "sessions": [
    {
      "sessionId": "s_abc123",
      "title": "Default",
      "status": "idle",
      "updatedAt": "2026-03-11T13:10:00Z"
    }
  ]
}
```

### session.setModel
Request:
```json
{"sessionId":"s_abc123","model":"claude-sonnet-4.5"}
```

### session.setMode
Request:
```json
{"sessionId":"s_abc123","mode":"plan"}
```

Allowed mode values:
- default
- acceptEdits
- plan
- dontAsk
- bypassPermissions (feature-flagged)

### prompt.send
Request:
```json
{
  "sessionId": "s_abc123",
  "prompt": "Fix failing test in AuthService",
  "contextRefs": [
    {"type":"current_file"},
    {"type":"selection"}
  ]
}
```

Immediate response:
```json
{"accepted": true, "runId": "r_001"}
```

### prompt.cancel
Request:
```json
{"sessionId":"s_abc123","runId":"r_001"}
```

Response:
```json
{"cancelled": true}
```

---

## 3) Stream/Event types

### assistant.chunk
```json
{
  "type": "assistant.chunk",
  "sessionId": "s_abc123",
  "runId": "r_001",
  "data": {"text": "I found the issue in AuthService..."}
}
```

### tool.call
```json
{
  "type": "tool.call",
  "sessionId": "s_abc123",
  "runId": "r_001",
  "data": {
    "toolCallId": "tc_01",
    "name": "ide.read_file",
    "input": {"path":"src/AuthService.kt"},
    "status": "pending"
  }
}
```

### tool.update
```json
{
  "type": "tool.update",
  "sessionId": "s_abc123",
  "runId": "r_001",
  "data": {
    "toolCallId": "tc_01",
    "status": "completed",
    "output": {"summary":"Read 312 lines"}
  }
}
```

### patch.preview
```json
{
  "type": "patch.preview",
  "sessionId": "s_abc123",
  "runId": "r_001",
  "data": {
    "previewId": "pv_01",
    "path": "src/AuthService.kt",
    "diff": "@@ ..."
  }
}
```

### prompt.done
```json
{
  "type":"prompt.done",
  "sessionId":"s_abc123",
  "runId":"r_001",
  "data":{"stopReason":"end_turn"}
}
```

### prompt.error
```json
{
  "type":"prompt.error",
  "sessionId":"s_abc123",
  "runId":"r_001",
  "data":{"message":"Tool denied by user"}
}
```

---

## 4) IDE Tool contracts

### ide.get_current_file
Input:
```json
{}
```
Output:
```json
{
  "path":"src/AuthService.kt",
  "language":"kotlin",
  "content":"...",
  "cursorOffset": 1024
}
```

### ide.get_selection
Input:
```json
{}
```
Output:
```json
{
  "path":"src/AuthService.kt",
  "startOffset": 980,
  "endOffset": 1142,
  "text":"selected code"
}
```

### ide.read_file
Input:
```json
{"path":"src/AuthService.kt","fromLine":1,"toLine":120}
```
Output:
```json
{"content":"..."}
```

### ide.find_files
Input:
```json
{"glob":"src/**/*.kt"}
```
Output:
```json
{"paths":["src/AuthService.kt","src/UserRepo.kt"]}
```

### ide.search_text
Input:
```json
{"query":"TODO","regex":false,"caseSensitive":false}
```
Output:
```json
{
  "matches":[
    {"path":"src/AuthService.kt","line":44,"text":"// TODO: validate token"}
  ]
}
```

### ide.apply_patch_preview
Input:
```json
{
  "path":"src/AuthService.kt",
  "diff":"@@ -40,7 +40,9 @@ ..."
}
```
Output:
```json
{
  "previewId":"pv_01",
  "renderedDiff":"@@ ..."
}
```

### ide.apply_patch_commit
Input:
```json
{"previewId":"pv_01"}
```
Output:
```json
{"applied":true}
```

### ide.run_command
Input:
```json
{"command":"./gradlew test --tests AuthServiceTest","timeoutSec":120}
```
Output:
```json
{"started":true,"execId":"ex_77"}
```

Command stream event:
```json
{
  "type":"command.output",
  "sessionId":"s_abc123",
  "runId":"r_001",
  "data":{"execId":"ex_77","stream":"stdout","chunk":"..."}
}
```

Command end event:
```json
{
  "type":"command.done",
  "sessionId":"s_abc123",
  "runId":"r_001",
  "data":{"execId":"ex_77","exitCode":0}
}
```

### ide.run_test
Input:
```json
{"target":"AuthServiceTest"}
```
Output:
```json
{
  "summary": {
    "passed": 12,
    "failed": 1,
    "skipped": 0,
    "durationMs": 18432
  },
  "failures": [
    {"name":"AuthServiceTest.invalidToken","message":"Expected 401"}
  ]
}
```

---

## 5) Permissions/safety

Default policy:
- `ide.apply_patch_commit` => always requires explicit user confirmation in UI
- `ide.run_command` => requires command allowlist + per-call approval
- `ide.run_test` => auto-allowed if target is test-only command

Denied command patterns (POC):
- `rm -rf`
- `:(){ :|:& };:`
- destructive git ops (`git reset --hard`, `git clean -fdx`) unless explicitly approved

---

## 6) State model

```text
SessionState {
  sessionId: string
  title: string
  status: idle|running|waiting|error
  model: string
  mode: string
  messages: Message[]
  pendingRunId?: string
  updatedAt: string
}
```

Message types:
- user
- assistant_chunk
- tool_call
- tool_update
- patch_preview
- command_output
- system

---

## 7) Kotlin interfaces (plugin side)

```kotlin
interface SidecarClient {
  suspend fun initialize(req: InitializeReq): InitializeRes
  suspend fun createSession(req: CreateSessionReq): CreateSessionRes
  suspend fun listSessions(): ListSessionsRes
  suspend fun sendPrompt(req: PromptReq): PromptAccepted
  suspend fun cancelPrompt(req: CancelReq): CancelRes
  suspend fun setModel(req: SetModelReq)
  suspend fun setMode(req: SetModeReq)
}

interface IdeToolExecutor {
  suspend fun getCurrentFile(): CurrentFileRes
  suspend fun getSelection(): SelectionRes
  suspend fun readFile(req: ReadFileReq): ReadFileRes
  suspend fun findFiles(req: FindFilesReq): FindFilesRes
  suspend fun searchText(req: SearchTextReq): SearchTextRes
  suspend fun applyPatchPreview(req: PatchPreviewReq): PatchPreviewRes
  suspend fun applyPatchCommit(req: PatchCommitReq): PatchCommitRes
  suspend fun runCommand(req: RunCommandReq): RunCommandRes
  suspend fun runTest(req: RunTestReq): RunTestRes
}
```

---

## 8) Python interfaces (sidecar)

```python
class SessionManager:
    def create_session(self, workspace_path: str, model: str, mode: str) -> str: ...
    def list_sessions(self) -> list[dict]: ...
    async def send_prompt(self, session_id: str, prompt: str, context_refs: list[dict]) -> str: ...
    async def cancel_prompt(self, session_id: str, run_id: str) -> bool: ...

class EventEmitter:
    async def emit(self, event_type: str, session_id: str, run_id: str, data: dict): ...
```

Claude SDK integration:
- `claude_agent_sdk.query(...)` for streaming (MVP)
- hooks for PreToolUse/PostToolUse permission/event plumbing

---

## 9) Milestone implementation order

1. protocol client/server handshake
2. session create/list + UI state map
3. prompt streaming in chat pane
4. read/search tools
5. patch preview/commit flow
6. run test/command flow
7. safety rules + persistence

---

## 10) Open decisions (need your sign-off)

1. Transport final: WebSocket only vs WebSocket + HTTP fallback
2. POC model default: Sonnet vs Opus
3. Command allowlist strictness in MVP
4. Whether `bypassPermissions` mode is exposed in UI
