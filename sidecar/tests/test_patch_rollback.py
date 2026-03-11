from pathlib import Path

import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_patch_commit_and_rollback(tmp_path: Path):
    s = SidecarServer()
    created = await s.dispatch({"method": "session.create", "params": {"workspacePath": str(tmp_path)}})
    sid = created["sessionId"]

    f = tmp_path / "a.txt"
    f.write_text("hello old world")

    prev = await s.dispatch(
        {
            "method": "ide.apply_patch_preview",
            "params": {"sessionId": sid, "path": "a.txt", "oldText": "old", "newText": "new"},
        }
    )

    commit = await s.dispatch(
        {"method": "ide.apply_patch_commit", "params": {"sessionId": sid, "previewId": prev["previewId"], "confirm": True}}
    )
    assert commit["applied"] is True
    backup_id = commit.get("backupId")
    assert backup_id is not None
    assert f.read_text() == "hello new world"

    rb = await s.dispatch({"method": "ide.patch_rollback", "params": {"backupId": backup_id}})
    assert rb["rolledBack"] is True
    assert f.read_text() == "hello old world"
