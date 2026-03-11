import asyncio
import json
import pytest
import websockets

from server import SidecarServer


@pytest.mark.asyncio
async def test_prompt_stream_emits_chunks_and_done():
    server = SidecarServer()
    server.use_claude_live = False

    async def handler(ws):
        await server.handle(ws)

    async with websockets.serve(handler, "127.0.0.1", 8876):
        async with websockets.connect("ws://127.0.0.1:8876") as ws:
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.create", "params": {"workspacePath": "."}}))
            created = json.loads(await ws.recv())["result"]
            sid = created["sessionId"]

            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "prompt.send", "params": {"sessionId": sid, "prompt": "hello"}}))
            accepted = json.loads(await ws.recv())["result"]
            run_id = accepted["runId"]

            chunk_count = 0
            seen_done = False
            seen_tool_call = False
            seen_tool_update = False
            for _ in range(20):
                msg = json.loads(await ws.recv())
                if msg.get("method") != "event":
                    continue
                p = msg["params"]
                if p.get("runId") != run_id:
                    continue
                if p["type"] == "assistant.chunk":
                    chunk_count += 1
                elif p["type"] == "tool.call":
                    seen_tool_call = True
                elif p["type"] == "tool.update":
                    seen_tool_update = True
                elif p["type"] == "prompt.done":
                    seen_done = True
                    break

            assert seen_tool_call is True
            assert seen_tool_update is True
            assert chunk_count >= 1
            assert seen_done is True


@pytest.mark.asyncio
async def test_parallel_session_streams_do_not_intermix():
    server = SidecarServer()
    server.use_claude_live = False

    async def handler(ws):
        await server.handle(ws)

    async with websockets.serve(handler, "127.0.0.1", 8877):
        async with websockets.connect("ws://127.0.0.1:8877") as ws:
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.create", "params": {"workspacePath": ".", "title": "A"}}))
            sid_a = json.loads(await ws.recv())["result"]["sessionId"]
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "session.create", "params": {"workspacePath": ".", "title": "B"}}))
            sid_b = json.loads(await ws.recv())["result"]["sessionId"]

            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "prompt.send", "params": {"sessionId": sid_a, "prompt": "alpha"}}))
            run_a = json.loads(await ws.recv())["result"]["runId"]
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 4, "method": "prompt.send", "params": {"sessionId": sid_b, "prompt": "beta"}}))
            run_b = json.loads(await ws.recv())["result"]["runId"]

            seen: dict[str, list[str]] = {run_a: [], run_b: []}
            done = set()
            for _ in range(40):
                msg = json.loads(await ws.recv())
                if msg.get("method") != "event":
                    continue
                params = msg["params"]
                run_id = params["runId"]
                session_id = params["sessionId"]
                if run_id == run_a:
                    assert session_id == sid_a
                    seen[run_a].append(params["type"])
                elif run_id == run_b:
                    assert session_id == sid_b
                    seen[run_b].append(params["type"])
                if params["type"] == "prompt.done":
                    done.add(run_id)
                if len(done) == 2:
                    break

            assert run_a in done
            assert run_b in done
            assert "assistant.chunk" in seen[run_a]
            assert "assistant.chunk" in seen[run_b]

            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 5, "method": "session.list", "params": {}}))
            sessions = json.loads(await ws.recv())["result"]["sessions"]
            by_id = {session["sessionId"]: session for session in sessions}
            assert by_id[sid_a]["status"] == "idle"
            assert by_id[sid_b]["status"] == "idle"
