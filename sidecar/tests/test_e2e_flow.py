import asyncio
import json
import pytest
import websockets

from server import SidecarServer


@pytest.mark.asyncio
async def test_e2e_happy_path_and_policy():
    server = SidecarServer()
    server.use_claude_live = False

    async def handler(ws):
        await server.handle(ws)

    async with websockets.serve(handler, "127.0.0.1", 8878):
        async with websockets.connect("ws://127.0.0.1:8878") as ws:
            async def call(req_id, method, params):
                await ws.send(json.dumps({"jsonrpc": "2.0", "id": req_id, "method": method, "params": params}))
                while True:
                    msg = json.loads(await ws.recv())
                    if msg.get("id") == req_id:
                        return msg

            init = await call(1, "initialize", {"client": {"name": "test", "version": "0.1"}, "workspacePath": "."})
            assert init["result"]["protocolVersion"] == "0.2"

            created = await call(2, "session.create", {"workspacePath": ".", "title": "e2e"})
            sid = created["result"]["sessionId"]

            sent = await call(3, "prompt.send", {"sessionId": sid, "prompt": "hello"})
            run_id = sent["result"]["runId"]
            assert run_id.startswith("r_")

            # consume stream until done
            done = False
            for _ in range(20):
                evt = json.loads(await ws.recv())
                if evt.get("method") != "event":
                    continue
                p = evt["params"]
                if p.get("runId") == run_id and p.get("type") == "prompt.done":
                    done = True
                    break
            assert done

            prev = await call(4, "ide.apply_patch_preview", {"path": "src/A.kt", "diff": "@@ -1 +1 @@\n-a\n+b"})
            preview_id = prev["result"]["previewId"]
            committed = await call(5, "ide.apply_patch_commit", {"previewId": preview_id, "confirm": True})
            assert committed["result"]["applied"] is False

            cmd_ok = await call(6, "ide.run_command", {"command": "pytest -q"})
            assert cmd_ok["result"]["started"] is True

            cmd_bad = await call(7, "ide.run_command", {"command": "ls -la"})
            assert cmd_bad["error"]["code"] == -32006
