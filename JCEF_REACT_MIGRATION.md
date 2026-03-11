# JCEF + React Migration (Hybrid IntelliJ UI)

## Goal
Move from Swing-heavy chat rendering to a modern React UI embedded in IntelliJ ToolWindow via JCEF, while keeping Kotlin for IDE APIs and sidecar transport.

## Architecture
- **Kotlin shell**: ToolWindow lifecycle, session state, sidecar RPC, Intelli APIs
- **JCEF webview**: React app renders chat timeline, composer, diff actions, session rail
- **Bridge**: JS ↔ Kotlin message bus using `JBCefJSQuery`

## Event Contract (v0)
From React to Kotlin:
- `ui.ready`
- `prompt.send { text }`
- `session.new`
- `session.switch { sessionId }`
- `patch.commit`
- `patch.reject`

From Kotlin to React:
- `state.snapshot { ... }`
- `event.message { role, text, runId }`
- `event.tool { ... }`
- `event.patchPreview { previewId, applicable, reason }`
- `event.command { execId, stream, chunk }`

## Implementation Phases
1. Embed JCEF panel and bootstrap JS bridge ✅ (this pass)
2. Scaffold React app (Vite) and static bundle pipeline ✅ (this pass)
3. Route prompt/session actions through bridge
4. Render timeline + markdown + role cards in React
5. Add patch action bar (Open Diff / Apply / Reject)

## Notes
- Keep Swing fallback when JCEF unsupported.
- Keep all safety checks in Kotlin/sidecar (never trust UI-only gating).
