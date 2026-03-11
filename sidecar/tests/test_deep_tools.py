from pathlib import Path

import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_symbol_usages_diagnostics(tmp_path: Path):
    s = SidecarServer()
    created = await s.dispatch({"method": "session.create", "params": {"workspacePath": str(tmp_path)}})
    sid = created["sessionId"]

    f = tmp_path / "Demo.kt"
    f.write_text("""fun greet(name: String): String {
    // TODO improve
    return name
}
fun caller() = greet(\"x\")
""")

    txt = f.read_text()
    cursor = txt.index("greet") + 2

    sym = await s.dispatch({"method": "ide.get_symbol_at_cursor", "params": {"sessionId": sid, "path": "Demo.kt", "cursorOffset": cursor}})
    assert sym["symbol"] == "greet"

    usages = await s.dispatch({"method": "ide.find_usages", "params": {"sessionId": sid, "symbol": "greet", "glob": "**/*.kt"}})
    assert len(usages["matches"]) >= 2

    diags = await s.dispatch({"method": "ide.get_diagnostics", "params": {"sessionId": sid, "path": "Demo.kt"}})
    assert any(d["message"] == "TODO present" for d in diags["diagnostics"])
