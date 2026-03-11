from pathlib import Path

import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_ide_file_tools(tmp_path: Path):
    s = SidecarServer()
    created = await s.dispatch({"method": "session.create", "params": {"workspacePath": str(tmp_path)}})
    sid = created["sessionId"]

    f = tmp_path / "src" / "Demo.kt"
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text("fun x() = 1\nfun y() = 2\n")

    cur = await s.dispatch({"method": "ide.get_current_file", "params": {"sessionId": sid, "path": "src/Demo.kt"}})
    assert "fun x" in cur["content"]

    sel = await s.dispatch({"method": "ide.get_selection", "params": {"sessionId": sid, "path": "src/Demo.kt", "startOffset": 0, "endOffset": 5}})
    assert sel["text"] == "fun x"

    r = await s.dispatch({"method": "ide.read_file", "params": {"sessionId": sid, "path": "src/Demo.kt", "fromLine": 2, "toLine": 2}})
    assert "fun y" in r["content"]

    w = await s.dispatch({"method": "ide.write_file", "params": {"sessionId": sid, "path": "src/New.kt", "content": "class New"}})
    assert w["written"] is True
    assert (tmp_path / "src" / "New.kt").exists()


@pytest.mark.asyncio
async def test_ide_search_and_find_and_run_test(tmp_path: Path):
    s = SidecarServer()
    created = await s.dispatch({"method": "session.create", "params": {"workspacePath": str(tmp_path)}})
    sid = created["sessionId"]

    (tmp_path / "a.txt").write_text("hello world\n")
    (tmp_path / "b.txt").write_text("bye\nhello again\n")

    found = await s.dispatch({"method": "ide.find_files", "params": {"sessionId": sid, "glob": "**/*.txt"}})
    assert len(found["paths"]) >= 2

    matches = await s.dispatch({"method": "ide.search_text", "params": {"sessionId": sid, "query": "hello", "glob": "**/*.txt"}})
    assert len(matches["matches"]) >= 2

    run = await s.dispatch({"method": "ide.run_test", "params": {"sessionId": sid, "target": "pytest -q"}})
    assert run["summary"]["started"] is True
