# Intelli Agent Sidecar (Python)

POC sidecar for IntelliJ plugin protocol.

## Run

```bash
cd sidecar
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python server.py
```

Server listens on:

`ws://127.0.0.1:8765`

## Current status

- JSON-RPC skeleton implemented
- Methods: initialize, session.create, session.list, prompt.send, prompt.cancel, session.setModel, session.setMode
- Streaming implemented (live Claude by default)
- Optional simulated mode for deterministic tests

## Runtime mode

By default, sidecar uses Claude Agent SDK live streaming.

```bash
export ANTHROPIC_API_KEY=your_key_here
python server.py
```

To force simulated mode (for deterministic testing):

```bash
export INTELLI_AGENT_USE_CLAUDE=0
python server.py
```

If live mode fails, sidecar emits `prompt.error` with the failure reason.
