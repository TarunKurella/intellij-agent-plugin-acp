import pytest

from server import SidecarServer


@pytest.mark.asyncio
async def test_patch_commit_requires_preview_and_confirm():
    s = SidecarServer()

    # commit without preview should fail
    with pytest.raises(ValueError):
        await s.dispatch({"method": "ide.apply_patch_commit", "params": {"previewId": "pv_missing", "confirm": True}})

    prev = await s.dispatch(
        {
            "method": "ide.apply_patch_preview",
            "params": {"path": "src/A.kt", "diff": "@@ -1 +1 @@\n-old\n+new"},
        }
    )
    preview_id = prev["previewId"]

    # commit without confirm should fail
    with pytest.raises(ValueError):
        await s.dispatch(
            {"method": "ide.apply_patch_commit", "params": {"previewId": preview_id, "confirm": False}}
        )

    # diff-only preview should not auto-apply
    res = await s.dispatch(
        {"method": "ide.apply_patch_commit", "params": {"previewId": preview_id, "confirm": True}}
    )
    assert res["applied"] is False

    # old/new preview is applicable
    prev2 = await s.dispatch(
        {
            "method": "ide.apply_patch_preview",
            "params": {"path": "a.txt", "oldText": "old", "newText": "new"},
        }
    )
    # without real file context this may still fail safely, but should return structured result
    res2 = await s.dispatch(
        {"method": "ide.apply_patch_commit", "params": {"previewId": prev2["previewId"], "confirm": True}}
    )
    assert "applied" in res2
