import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_initialize():
    s = SidecarServer()
    res = await s.dispatch({"method": "initialize", "params": {}})
    assert res["server"]["name"] == "intelli-agent-sidecar"
    assert res["capabilities"]["sessions"] is True


@pytest.mark.asyncio
async def test_session_lifecycle():
    s = SidecarServer()

    created = await s.dispatch(
        {
            "method": "session.create",
            "params": {"workspacePath": "/tmp/proj", "title": "Default"},
        }
    )
    sid = created["sessionId"]
    assert sid.startswith("s_")

    listed = await s.dispatch({"method": "session.list", "params": {}})
    assert len(listed["sessions"]) == 1
    assert listed["sessions"][0]["sessionId"] == sid

    accepted = await s.dispatch(
        {
            "method": "prompt.send",
            "params": {"sessionId": sid, "prompt": "hello"},
        }
    )
    assert accepted["accepted"] is True
    assert accepted["runId"].startswith("r_")

    cancelled = await s.dispatch(
        {"method": "prompt.cancel", "params": {"sessionId": sid, "runId": accepted["runId"]}}
    )
    assert cancelled["cancelled"] is True
