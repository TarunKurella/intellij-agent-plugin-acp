# Intelli Agent Plugin (IntelliJ + Python Claude Sidecar)

An ACP-inspired IntelliJ plugin prototype that integrates Claude Agent SDK through a local Python sidecar, with IntelliJ-aware tools for code context, search, patch preview/commit, and safe command execution.

## Current Status

This repository contains an active prototype with:

- IntelliJ plugin (Kotlin) tool window
- Python sidecar (WebSocket JSON-RPC)
- Session-based prompting + streaming
- MCP-based Intelli tools exposed to Claude
- Patch preview/commit + rollback flow
- Native IntelliJ diff preview integration
- Safety policies for command execution
- Sidecar test suite

## Architecture

- **Frontend shell:** IntelliJ plugin (Kotlin/Swing, moving toward richer UX)
- **Agent backend:** Python sidecar (`sidecar/server.py`)
- **Transport:** WebSocket JSON-RPC (`ws://127.0.0.1:8765`)
- **LLM runtime:** `claude-agent-sdk`

## Implemented Tool Surface

Core tools currently wired in sidecar:

- `ide.get_current_file`
- `ide.get_selection`
- `ide.read_file`
- `ide.write_file`
- `ide.find_files`
- `ide.search_text`
- `ide.run_test`
- `ide.run_command` (allowlist/blocklist policy)
- `ide.apply_patch_preview`
- `ide.apply_patch_commit`
- `ide.patch_rollback`
- `ide.patch_preview_get`
- `ide.patch_preview_last`
- Deep tools phase-1:
  - `ide.get_symbol_at_cursor`
  - `ide.find_usages`
  - `ide.get_diagnostics`

## Setup

### 1) Sidecar

```bash
cd sidecar
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
export ANTHROPIC_API_KEY=YOUR_KEY
python server.py
```

Live Claude mode is default. To force simulated mode:

```bash
export INTELLI_AGENT_USE_CLAUDE=0
```

### 2) IntelliJ plugin

```bash
cd ..
./gradlew runIde
```

> Requires JDK 17.

## Testing

Run sidecar tests:

```bash
cd sidecar
source .venv/bin/activate
pytest -q
```

Current suite includes lifecycle, streaming, cancel, command safety, patch safety/rollback, deep tools, and end-to-end flow.

## Notable Design/Behavior Notes

- Patch previews may be applicable (`oldText/newText`) or diff-only.
- Commit now returns explicit reasons when preview cannot be auto-applied.
- Native diff view opens from preview metadata.
- Session context includes workspace/current file/selection metadata in prompt prefix.

## Next Planned Work

- UI modernization (likely JCEF/React hybrid)
- Better action affordances for patch approve/reject
- Stream dedupe + stronger role rendering
- Rich markdown rendering in chat timeline

## Repository Hygiene

Ignored from source control:

- `.gradle/`, `build/`
- sidecar virtualenv/cache
- IDE and OS artifacts
