# Intelli Agent Plugin

Intelli Agent is an IntelliJ plugin prototype that connects a local IntelliJ UI to Claude through a Python sidecar. It provides session-based chat, streamed tool transparency, native IntelliJ diff review, and guarded patch application for code-editing workflows inside the IDE.

For a higher-level explanation of the project, behavior, and architecture, see [PROJECT_OVERVIEW.md](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/PROJECT_OVERVIEW.md).

## What It Does

- Runs an IntelliJ tool window with a JCEF-based agent chat UI
- Streams assistant text and tool activity over WebSocket JSON-RPC
- Exposes IntelliJ-aware tools to Claude through an MCP-backed Python sidecar
- Creates patch previews and opens native IntelliJ diff review before apply
- Supports multiple sessions with per-session run state in the sidebar
- Allows real session deletion from the running sidecar

## Current Architecture

- IntelliJ plugin frontend: Kotlin + JCEF tool window
- IDE bridge: Kotlin RPC client and JS bridge
- Sidecar backend: Python WebSocket JSON-RPC server
- Agent runtime: `claude_agent_sdk`
- Transport: local WebSocket on `ws://127.0.0.1:8765`

## Main Components

- Plugin UI: [AgentWebViewPanel.kt](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/toolwindow/AgentWebViewPanel.kt)
- Tool window registration: [AgentToolWindowFactory.kt](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/toolwindow/AgentToolWindowFactory.kt)
- Protocol models: [ProtocolModels.kt](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/protocol/ProtocolModels.kt)
- WebSocket sidecar client: [SidecarClient.kt](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/protocol/SidecarClient.kt)
- Python sidecar: [server.py](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/sidecar/server.py)

## Implemented Sidecar Tool Surface

- `ide.get_current_file`
- `ide.get_selection`
- `ide.read_file`
- `ide.write_file`
- `ide.find_files`
- `ide.search_text`
- `ide.run_test`
- `ide.run_command`
- `ide.apply_patch_preview`
- `ide.apply_patch_commit`
- `ide.patch_rollback`
- `ide.patch_preview_get`
- `ide.patch_preview_last`
- `ide.get_symbol_at_cursor`
- `ide.find_usages`
- `ide.get_diagnostics`

## UX Behavior

- Chat messages are session-scoped and streamed by `sessionId` and `runId`
- Tool calls are rendered as visible timeline rows in the chat UI
- Patch previews stay in chat as trigger cards until the user opens the native diff
- Patch apply/reject happens through the native diff approval bar
- Session rail lights reflect idle, running, waiting, and error states

## Setup

### Sidecar

```bash
cd sidecar
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export ANTHROPIC_API_KEY=YOUR_KEY
python server.py
```

To force simulated mode:

```bash
export INTELLI_AGENT_USE_CLAUDE=0
```

### Plugin

```bash
./gradlew runIde
```

Requires JDK 17.

## Build / Export

Compile:

```bash
./gradlew compileKotlin
```

Build plugin ZIP:

```bash
./gradlew buildPlugin
```

If another IntelliJ instance prevents searchable options generation, this variant still produces the distributable ZIP:

```bash
./gradlew buildPlugin -x buildSearchableOptions
```

Current exported artifact path:

- [intellij-agent-plugin-0.1.0.zip](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/build/distributions/intellij-agent-plugin-0.1.0.zip)

## Testing

Kotlin compile check:

```bash
./gradlew compileKotlin
```

Python sidecar syntax check:

```bash
python3 -m py_compile sidecar/server.py
```

Python tests require `pytest` in the active Python environment:

```bash
cd sidecar
source .venv/bin/activate
pytest -q
```

## Notes

- Sessions currently live in sidecar memory, not durable storage.
- Deleting a session removes it from the running sidecar state.
- Patch commit supports exact and normalized-text fallback matching.
- Command execution is intentionally allowlisted in the prototype.
