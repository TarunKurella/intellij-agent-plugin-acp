import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_health_endpoint():
    s = SidecarServer()
    res = await s.dispatch({"method": "health", "params": {}})
    assert res["ok"] is True
    assert res["mode"] in {"simulated", "live"}
    assert isinstance(res["activeRuns"], int)
    assert isinstance(res["sessions"], int)
