import asyncio
import json
import pytest
import websockets

from server import SidecarServer


@pytest.mark.asyncio
async def test_cancel_stops_stream_before_done():
    server = SidecarServer()
    server.use_claude_live = False

    async def handler(ws):
        await server.handle(ws)

    async with websockets.serve(handler, "127.0.0.1", 8877):
        async with websockets.connect("ws://127.0.0.1:8877") as ws:
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "session.create", "params": {"workspacePath": "."}}))
            sid = json.loads(await ws.recv())["result"]["sessionId"]

            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 2, "method": "prompt.send", "params": {"sessionId": sid, "prompt": "cancel me"}}))
            run_id = json.loads(await ws.recv())["result"]["runId"]

            # cancel quickly
            await ws.send(json.dumps({"jsonrpc": "2.0", "id": 3, "method": "prompt.cancel", "params": {"sessionId": sid, "runId": run_id}}))
            # Drain until cancel response (events may interleave)
            while True:
                obj = json.loads(await ws.recv())
                if obj.get("id") == 3:
                    _ = obj["result"]
                    break

            got_done = False
            got_chunk = False
            # Observe for a short window; no prompt.done should arrive for cancelled run
            end = asyncio.get_event_loop().time() + 0.25
            while asyncio.get_event_loop().time() < end:
                try:
                    msg = await asyncio.wait_for(ws.recv(), timeout=0.05)
                except asyncio.TimeoutError:
                    continue
                obj = json.loads(msg)
                if obj.get("method") != "event":
                    continue
                p = obj["params"]
                if p.get("runId") != run_id:
                    continue
                if p.get("type") == "assistant.chunk":
                    got_chunk = True
                if p.get("type") == "prompt.done":
                    got_done = True
                    break

            assert got_done is False
            # chunk may or may not happen due to race timing; done must not happen
            assert isinstance(got_chunk, bool)
