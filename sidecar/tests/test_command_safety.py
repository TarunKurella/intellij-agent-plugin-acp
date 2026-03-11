import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_run_command_allows_test_commands_only():
    s = SidecarServer()

    ok = await s.dispatch(
        {
            "method": "ide.run_command",
            "params": {"command": "pytest -q"},
        }
    )
    assert ok["started"] is True
    assert ok["execId"].startswith("ex_")

    with pytest.raises(ValueError):
        await s.dispatch(
            {
                "method": "ide.run_command",
                "params": {"command": "ls -la"},
            }
        )


@pytest.mark.asyncio
async def test_run_command_blocks_dangerous_patterns():
    s = SidecarServer()

    with pytest.raises(ValueError):
        await s.dispatch(
            {
                "method": "ide.run_command",
                "params": {"command": "pytest -q && rm -rf /"},
            }
        )

    with pytest.raises(ValueError):
        await s.dispatch(
            {
                "method": "ide.run_command",
                "params": {"command": "mvn test && git reset --hard"},
            }
        )
