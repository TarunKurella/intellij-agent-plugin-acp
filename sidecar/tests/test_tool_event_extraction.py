from server import SidecarServer


def test_extract_tool_events_from_message_dict():
    msg = {
        "content": [
            {"type": "tool_use", "id": "tc_1", "name": "Read", "input": {"file_path": "a.txt"}},
            {"type": "tool_result", "tool_use_id": "tc_1", "is_error": False, "content": "ok"},
        ]
    }

    events = SidecarServer._extract_tool_events_from_message(msg)
    assert len(events) == 2
    assert events[0]["event"] == "tool.call"
    assert events[0]["data"]["toolCallId"] == "tc_1"
    assert events[1]["event"] == "tool.update"
    assert events[1]["data"]["status"] == "completed"


def test_extract_tool_events_handles_error_result():
    msg = {
        "content": [
            {"type": "tool_result", "tool_use_id": "tc_err", "is_error": True, "content": "boom"},
        ]
    }
    events = SidecarServer._extract_tool_events_from_message(msg)
    assert len(events) == 1
    assert events[0]["event"] == "tool.update"
    assert events[0]["data"]["status"] == "failed"
