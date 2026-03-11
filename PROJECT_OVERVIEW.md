# Project Overview

## What This Project Is

Intelli Agent is a prototype IntelliJ plugin that embeds an agent workflow directly into the IDE. Instead of treating the IDE as an external editor, it gives the model controlled access to IntelliJ-aware operations such as reading files, getting selection context, searching the project, finding usages, running tests, and proposing patches for approval.

The system is split into two runtime pieces:

- an IntelliJ plugin written in Kotlin
- a local Python sidecar that talks to Claude and exposes IDE-backed tools

The result is an agent experience that looks like an IDE-native chat panel but routes all model work through explicit, inspectable tool calls and patch review.

## What It Does

At a functional level, the project provides:

- multi-session agent chat inside an IntelliJ tool window
- streamed assistant output
- visible tool-call transparency in the chat timeline
- native IntelliJ diff preview before applying code changes
- guarded patch apply and reject flow
- limited command execution through a safety policy
- IDE-aware semantic helpers such as symbol lookup, usages, and diagnostics

This makes the plugin useful for tasks like:

- asking questions about the current file or selection
- searching for code and following symbol usage chains
- generating controlled edits with explicit approval
- running tests from an allowlisted command surface

## High-Level Architecture

### 1. IntelliJ plugin

The IntelliJ plugin owns:

- the tool window
- the JCEF-based chat UI
- the native diff viewer integration
- the active editor/file context
- the WebSocket client connection to the sidecar

The main entry point for the current UI is [AgentWebViewPanel.kt](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/toolwindow/AgentWebViewPanel.kt).

### 2. Python sidecar

The sidecar owns:

- the JSON-RPC server
- session lifecycle
- Claude streaming
- translation of Claude SDK events into plugin-friendly event notifications
- patch preview state
- command safety checks

The main implementation is [server.py](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/sidecar/server.py).

### 3. Claude / MCP tool layer

The sidecar builds an MCP server that exposes IntelliJ-scoped tools to Claude. That means Claude does not read or modify the project through generic shell tools in normal operation; it uses the curated IDE-backed tool surface exposed by the sidecar.

### 4. WebSocket transport

The plugin and sidecar talk over local WebSocket JSON-RPC. Requests like `session.create`, `prompt.send`, `session.list`, and `session.delete` are standard RPC calls. Streaming output comes back as event notifications with `sessionId` and `runId`, which lets the plugin keep multiple sessions separated.

## Message / Run Flow

A typical prompt follows this sequence:

1. The user sends a prompt from the IntelliJ tool window.
2. The plugin sends `prompt.send` with the active `sessionId`.
3. The sidecar starts a run and streams `assistant.chunk`, `tool.call`, and `tool.update` events.
4. The plugin renders those events into the active session timeline.
5. If Claude proposes a patch, the sidecar creates a patch preview.
6. The plugin shows a patch card in chat.
7. The user opens native IntelliJ diff and either accepts or rejects the patch.
8. The plugin sends patch commit or reject behavior back through the sidecar path.

## Session Model

Sessions are first-class. Each session has:

- a `sessionId`
- a status
- a chat timeline in the plugin UI
- its own preview state

Important behavior:

- streamed events are keyed by both `sessionId` and `runId`
- session sidebar state reflects whether a session is idle, running, waiting for approval, or errored
- deleting a session now removes it from the live sidecar state instead of only hiding it in the UI

## Patch Flow

Patch handling is intentionally cautious:

- the agent creates a preview instead of directly modifying files
- the UI presents a patch card in chat
- the user opens IntelliJ’s native diff viewer
- the user accepts or rejects from the diff approval UI

Patch commits support:

- exact text replacement
- normalized whitespace fallback matching

If a preview is diff-only and not directly auto-applicable, the sidecar returns an explicit reason instead of pretending it succeeded.

## Safety Model

The project currently uses a constrained safety posture:

- sidecar command execution is allowlisted
- clearly dangerous shell patterns are blocked
- built-in filesystem and shell tools are restricted in the Claude tool configuration
- patch application requires explicit approval

This is a prototype, but the design direction is clear: prefer narrow, IDE-aware tools over unrestricted machine access.

## Repository Layout

- [src/main/kotlin/com/trono/agentplugin/toolwindow](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/toolwindow)
  IntelliJ UI integration and tool window code
- [src/main/kotlin/com/trono/agentplugin/protocol](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/src/main/kotlin/com/trono/agentplugin/protocol)
  Kotlin-side protocol models and WebSocket client
- [sidecar](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/sidecar)
  Python backend, tests, and requirements
- [SPEC_v0.2.md](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/SPEC_v0.2.md)
  protocol and behavior spec
- [DECISIONS.md](/Users/tarun-agentic/.openclaw/workspace/intellij-agent-plugin/DECISIONS.md)
  locked POC decisions

## Current Limitations

- sessions are in-memory, not persisted to a durable store
- Python tests depend on `pytest` being installed in the active environment
- searchable-options generation can fail if another IDEA instance is already running during plugin packaging

## Summary

This project is an IDE-native agent shell with a strict separation of concerns:

- IntelliJ owns UX, context, and native IDE affordances
- the Python sidecar owns agent orchestration and streaming
- Claude uses a curated IntelliJ tool surface instead of ad hoc file access

That split is the core architectural idea of the repository.
