# Testing Strategy (Spec → Build → Test → Feedback → Repeat)

## Delivery Loop (non-negotiable)
1. **Spec**: define behavior and acceptance criteria before coding.
2. **Build**: implement smallest vertical slice.
3. **Test**: run automated + manual checks for that slice.
4. **Feedback**: review results, identify gaps/bugs/usability issues.
5. **Repeat**: update spec and ship next slice.

---

## Test Pyramid for this project

### A) Sidecar unit tests (fast)
- Validate RPC method behavior in isolation.
- No UI dependency.
- Examples:
  - initialize returns capabilities
  - session.create/list
  - prompt.send/cancel state transitions

### B) Contract tests (plugin ↔ sidecar)
- Verify JSON-RPC envelopes and schema compatibility.
- Golden payload tests for method request/response and events.
- Catch breaking protocol changes early.

### C) Plugin integration tests (IDE-level)
- Run IntelliJ test harness for:
  - session store behavior
  - tool window event rendering
  - patch preview/apply flow (write-safe)

### D) End-to-end smoke tests
- Start sidecar + plugin together.
- User flow:
  - create session
  - send prompt
  - receive stream
  - apply patch preview
  - run test command

---

## Quality gates per milestone

### Gate 1 (Protocol foundation)
- [ ] sidecar unit tests pass
- [ ] method contracts match SPEC_v0.2

### Gate 2 (Streaming)
- [x] assistant chunks render path wired (sidecar event -> plugin handler)
- [x] cancel reliably stops active run (`test_cancel.py`)
- [x] sidecar streaming contract test passes (`test_streaming.py`)

### Gate 3 (Patch safety)
- [x] no commit without explicit confirmation (`test_patch_safety.py`)
- [x] preview/commit path tested (`test_patch_safety.py`)

### Gate 4 (Command safety)
- [x] allowlist enforced (`test_command_safety.py`)
- [x] blocked patterns rejected (`test_command_safety.py`)

---

## Cadence
- Every milestone ends with:
  1) test report
  2) known issues list
  3) updated acceptance criteria for next slice

---

## Current implemented tests
- `sidecar/tests/test_server.py` (initialize + session lifecycle)
- `sidecar/tests/test_streaming.py` (assistant chunks + prompt.done)
- `sidecar/tests/test_cancel.py` (cancel stops completion)
- `sidecar/tests/test_patch_safety.py` (preview/commit safety)
- `sidecar/tests/test_command_safety.py` (allowlist + blocklist)
- `sidecar/tests/test_e2e_flow.py` (initialize→session→prompt→patch→command policy)
- `sidecar/tests/test_health.py` (health endpoint + mode visibility)
- `sidecar/tests/test_tool_event_extraction.py` (maps Claude content blocks to tool.call/tool.update)
- Manual smoke (live mode): `INTELLI_AGENT_USE_CLAUDE=1` + `ANTHROPIC_API_KEY` and verify prompt stream events
