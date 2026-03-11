import asyncio
import json
import os
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Optional

import websockets


@dataclass
class Session:
    session_id: str
    cwd: str = "."
    title: str = "Default"
    status: str = "idle"
    model: str = "claude-sonnet-4.5"
    mode: str = "default"
    updated_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())


class SidecarServer:
    ALLOWED_COMMAND_PREFIXES = (
        "./gradlew test",
        "gradle test",
        "mvn test",
        "pytest",
        "npm test",
        "pnpm test",
        "yarn test",
    )
    BLOCKED_PATTERNS = (
        "rm -rf",
        "git reset --hard",
        "git clean -fdx",
        ":(){ :|:& };:",
    )

    def __init__(self) -> None:
        self.sessions: Dict[str, Session] = {}
        self.active_runs: Dict[str, str] = {}  # run_id -> session_id
        self.patch_previews: Dict[str, Dict[str, Any]] = {}  # preview_id -> patch payload
        self.patch_backups: Dict[str, Dict[str, str]] = {}  # backup_id -> {path,content}
        self.last_preview_by_session: Dict[str, Dict[str, Any]] = {}  # session_id -> preview state
        # Default to live Claude mode unless explicitly disabled.
        self.use_claude_live = os.getenv("INTELLI_AGENT_USE_CLAUDE", "1") != "0"
        self.session_sockets: Dict[str, Any] = {}

    def _session_has_active_runs(self, session_id: str) -> bool:
        return any(active_session_id == session_id for active_session_id in self.active_runs.values())

    def _set_session_status(self, session_id: str, status: Optional[str] = None) -> None:
        session = self.sessions.get(session_id)
        if session is None:
            return
        session.status = status if status is not None else ("running" if self._session_has_active_runs(session_id) else "idle")
        session.updated_at = datetime.now(timezone.utc).isoformat()

    async def handle(self, ws):
        async for raw in ws:
            req = {}
            try:
                req = json.loads(raw)
                if req.get("method") == "event.tool_result":
                    await self._handle_notification(req)
                    continue

                resp = await self.dispatch(req, ws)
                if req.get("id") is not None:
                    await ws.send(json.dumps({"jsonrpc": "2.0", "id": req.get("id"), "result": resp}))
            except Exception as e:
                if isinstance(req, dict) and req.get("id") is not None:
                    code, message = self._error_payload(e)
                    await ws.send(
                        json.dumps(
                            {
                                "jsonrpc": "2.0",
                                "id": req.get("id"),
                                "error": {"code": code, "message": message},
                            }
                        )
                    )

    @staticmethod
    def _error_payload(err: Exception) -> tuple[int, str]:
        msg = str(err)
        mapping = {
            "Session not found": (-32001, msg),
            "Preview not found": (-32002, msg),
            "Confirmation required": (-32003, msg),
            "Command required": (-32004, msg),
            "Command blocked by safety policy": (-32005, msg),
            "Command not in allowlist": (-32006, msg),
            "Current file path required": (-32007, msg),
            "File not found": (-32008, msg),
            "Path required": (-32009, msg),
            "Query required": (-32010, msg),
            "Target required": (-32011, msg),
            "Backup not found": (-32012, msg),
        }
        if msg in mapping:
            return mapping[msg]
        if msg.startswith("Unknown method"):
            return -32601, msg
        return -32000, msg

    @staticmethod
    def _safe_resolve(base: str, rel_or_abs: str) -> Path:
        p = Path(rel_or_abs)
        if not p.is_absolute():
            p = Path(base) / p
        return p.resolve()

    async def dispatch(self, req: Dict[str, Any], ws=None) -> Dict[str, Any]:
        method = req.get("method")
        params = req.get("params", {})

        if method == "initialize":
            return {
                "protocolVersion": "0.2",
                "server": {"name": "intelli-agent-sidecar", "version": "0.1.0"},
                "capabilities": {
                    "sessions": True,
                    "streaming": True,
                    "tools": [
                        "ide.get_current_file",
                        "ide.get_selection",
                        "ide.read_file",
                        "ide.find_files",
                        "ide.search_text",
                        "ide.apply_patch_preview",
                        "ide.apply_patch_commit",
                        "ide.run_command",
                        "ide.run_test",
                        "ide.get_symbol_at_cursor",
                        "ide.find_usages",
                        "ide.get_diagnostics",
                    ],
                },
            }

        if method == "health":
            return {
                "ok": True,
                "mode": "live" if self.use_claude_live else "simulated",
                "activeRuns": len(self.active_runs),
                "sessions": len(self.sessions),
            }

        if method == "session.create":
            sid = f"s_{uuid.uuid4().hex[:8]}"
            s = Session(
                session_id=sid,
                cwd=params.get("workspacePath", "."),
                title=params.get("title", "Default"),
                model=params.get("model", "claude-sonnet-4.5"),
                mode=params.get("mode", "default"),
            )
            self.sessions[sid] = s
            return {"sessionId": sid, "status": s.status}

        if method == "session.delete":
            session_id = params["sessionId"]
            if session_id not in self.sessions:
                raise ValueError("Session not found")
            for run_id, active_session_id in list(self.active_runs.items()):
                if active_session_id == session_id:
                    del self.active_runs[run_id]
            for preview_id, preview in list(self.patch_previews.items()):
                if str(preview.get("sessionId", "")) == session_id:
                    del self.patch_previews[preview_id]
            for backup_id, backup in list(self.patch_backups.items()):
                if str(backup.get("sessionId", "")) == session_id:
                    del self.patch_backups[backup_id]
            self.last_preview_by_session.pop(session_id, None)
            self.session_sockets.pop(session_id, None)
            del self.sessions[session_id]
            return {"deleted": True}

        if method == "session.list":
            return {
                "sessions": [
                    {
                        "sessionId": s.session_id,
                        "title": s.title,
                        "status": s.status,
                        "updatedAt": s.updated_at,
                    }
                    for s in self.sessions.values()
                ]
            }

        if method == "prompt.send":
            session_id = params["sessionId"]
            if session_id not in self.sessions:
                raise ValueError("Session not found")
            run_id = f"r_{uuid.uuid4().hex[:6]}"
            self.active_runs[run_id] = session_id
            self._set_session_status(session_id, "running")

            if ws is not None:
                self.session_sockets[session_id] = ws
                prompt = params.get("prompt", "")
                if self.use_claude_live:
                    asyncio.create_task(self._stream_from_claude(ws, session_id, run_id, prompt))
                else:
                    asyncio.create_task(self._simulate_stream(ws, session_id, run_id, prompt))

            return {"accepted": True, "runId": run_id}

        if method == "prompt.cancel":
            session_id = params["sessionId"]
            run_id = params.get("runId")
            if run_id and run_id in self.active_runs:
                del self.active_runs[run_id]
            elif session_id in self.sessions:
                for active_run_id, active_session_id in list(self.active_runs.items()):
                    if active_session_id == session_id:
                        del self.active_runs[active_run_id]
            self._set_session_status(session_id)
            return {"cancelled": True}

        if method == "session.setModel":
            s = self.sessions.get(params["sessionId"])
            if not s:
                raise ValueError("Session not found")
            s.model = params["model"]
            s.updated_at = datetime.now(timezone.utc).isoformat()
            return {"ok": True}

        if method == "session.setMode":
            s = self.sessions.get(params["sessionId"])
            if not s:
                raise ValueError("Session not found")
            s.mode = params["mode"]
            s.updated_at = datetime.now(timezone.utc).isoformat()
            return {"ok": True}

        if method == "ide.get_current_file":
            path = params.get("path") or params.get("currentFilePath")
            if not path:
                raise ValueError("Current file path required")
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, str(path))
            if not file_path.exists():
                raise ValueError("File not found")
            content = file_path.read_text(errors="ignore")
            return {
                "path": str(file_path),
                "language": file_path.suffix.lstrip("."),
                "content": content,
                "cursorOffset": int(params.get("cursorOffset", 0)),
            }

        if method == "ide.get_selection":
            path = params.get("path") or params.get("currentFilePath")
            if not path:
                raise ValueError("Current file path required")
            start = int(params.get("startOffset", 0))
            end = int(params.get("endOffset", start))
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, str(path))
            if not file_path.exists():
                raise ValueError("File not found")
            content = file_path.read_text(errors="ignore")
            start = max(0, min(start, len(content)))
            end = max(start, min(end, len(content)))
            return {
                "path": str(file_path),
                "startOffset": start,
                "endOffset": end,
                "text": content[start:end],
            }

        if method == "ide.read_file":
            path = params.get("path")
            if not path:
                raise ValueError("Path required")
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, str(path))
            if not file_path.exists():
                raise ValueError("File not found")
            content = file_path.read_text(errors="ignore")
            from_line = params.get("fromLine")
            to_line = params.get("toLine")
            if from_line is not None or to_line is not None:
                lines = content.splitlines()
                s = max(1, int(from_line or 1))
                e = min(len(lines), int(to_line or len(lines)))
                content = "\n".join(lines[s - 1 : e])
            return {"content": content}

        if method == "ide.write_file":
            path = params.get("path")
            if not path:
                raise ValueError("Path required")
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, str(path))
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.write_text(str(params.get("content", "")))
            return {"written": True, "path": str(file_path)}

        if method == "ide.find_files":
            pattern = str(params.get("glob", "**/*"))
            session_id = params.get("sessionId")
            base = Path(self.sessions.get(session_id).cwd if session_id in self.sessions else ".")
            paths = [str(p) for p in base.glob(pattern) if p.is_file()][: int(params.get("limit", 200))]
            return {"paths": paths}

        if method == "ide.search_text":
            query = str(params.get("query", "")).strip()
            if not query:
                raise ValueError("Query required")
            session_id = params.get("sessionId")
            base = Path(self.sessions.get(session_id).cwd if session_id in self.sessions else ".")
            pattern = str(params.get("glob", "**/*"))
            case_sensitive = bool(params.get("caseSensitive", False))
            needle = query if case_sensitive else query.lower()
            out = []
            max_matches = int(params.get("maxMatches", 200))
            for f in base.glob(pattern):
                if not f.is_file():
                    continue
                try:
                    txt = f.read_text(errors="ignore")
                except Exception:
                    continue
                for idx, line in enumerate(txt.splitlines(), start=1):
                    hay = line if case_sensitive else line.lower()
                    if needle in hay:
                        out.append({"path": str(f), "line": idx, "text": line[:400]})
                        if len(out) >= max_matches:
                            return {"matches": out}
            return {"matches": out}

        if method == "ide.run_test":
            target = str(params.get("target", "")).strip()
            if not target:
                raise ValueError("Target required")
            # Route to existing command policy.
            command = target if any(target.startswith(p) for p in self.ALLOWED_COMMAND_PREFIXES) else f"pytest -q {target}"
            result = await self.dispatch({"method": "ide.run_command", "params": {"sessionId": params.get("sessionId"), "runId": params.get("runId", ""), "command": command}}, ws)
            return {"summary": {"started": result.get("started", False), "execId": result.get("execId", "")}, "failures": []}

        if method == "ide.get_symbol_at_cursor":
            path = params.get("path")
            offset = int(params.get("cursorOffset", 0))
            if not path:
                raise ValueError("Path required")
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, str(path))
            if not file_path.exists():
                raise ValueError("File not found")
            txt = file_path.read_text(errors="ignore")
            offset = max(0, min(offset, len(txt)))
            left = offset
            while left > 0 and (txt[left - 1].isalnum() or txt[left - 1] == "_"):
                left -= 1
            right = offset
            while right < len(txt) and (txt[right].isalnum() or txt[right] == "_"):
                right += 1
            symbol = txt[left:right]
            return {"path": str(file_path), "symbol": symbol, "startOffset": left, "endOffset": right}

        if method == "ide.find_usages":
            symbol = str(params.get("symbol", "")).strip()
            if not symbol:
                raise ValueError("Query required")
            return await self.dispatch({"method": "ide.search_text", "params": {"sessionId": params.get("sessionId"), "query": symbol, "glob": params.get("glob", "**/*"), "maxMatches": int(params.get("maxMatches", 200))}}, ws)

        if method == "ide.get_diagnostics":
            path = params.get("path")
            if not path:
                raise ValueError("Path required")
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, str(path))
            if not file_path.exists():
                raise ValueError("File not found")
            txt = file_path.read_text(errors="ignore")
            diagnostics = []
            for i, line in enumerate(txt.splitlines(), start=1):
                if "TODO" in line:
                    diagnostics.append({"line": i, "severity": "info", "message": "TODO present"})
                if "FIXME" in line:
                    diagnostics.append({"line": i, "severity": "warning", "message": "FIXME present"})
            return {"path": str(file_path), "diagnostics": diagnostics}

        if method == "ide.apply_patch_preview":
            preview_id = f"pv_{uuid.uuid4().hex[:8]}"
            patch_path = params.get("path", "")
            patch_diff = params.get("diff", "")
            old_text = params.get("oldText")
            new_text = params.get("newText")
            session_id = params.get("sessionId")
            applicable = old_text is not None and new_text is not None
            reason = None if applicable else "Preview is diff-only and cannot be auto-applied yet"
            self.patch_previews[preview_id] = {
                "path": patch_path,
                "diff": patch_diff,
                "oldText": old_text,
                "newText": new_text,
                "sessionId": session_id,
                "applicable": applicable,
                "reason": reason,
            }
            if session_id:
                self.last_preview_by_session[str(session_id)] = {
                    "previewId": preview_id,
                    "applicable": applicable,
                    "reason": reason,
                }
            rendered = patch_diff
            if not rendered and old_text is not None and new_text is not None:
                rendered = f"--- old\n+++ new\n- {str(old_text)[:120]}\n+ {str(new_text)[:120]}"
            if ws is not None and session_id:
                await self._emit_event(
                    ws,
                    "patch.preview.ready",
                    str(session_id),
                    str(params.get("runId", "")),
                    {
                        "previewId": preview_id,
                        "path": patch_path,
                        "applicable": applicable,
                        "reason": reason,
                    },
                )
            return {"previewId": preview_id, "renderedDiff": rendered, "applicable": applicable, "reason": reason}

        if method == "ide.apply_patch_commit":
            preview_id = params.get("previewId")
            confirm = bool(params.get("confirm", False))
            if preview_id not in self.patch_previews:
                raise ValueError("Preview not found")
            if not confirm:
                raise ValueError("Confirmation required")
            patch = self.patch_previews[preview_id]
            patch_path = str(patch.get("path", ""))
            session_id = params.get("sessionId")
            base = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            file_path = self._safe_resolve(base, patch_path) if patch_path else None
            backup_id = None

            old_text = patch.get("oldText")
            new_text = patch.get("newText")
            if old_text is None or new_text is None:
                del self.patch_previews[preview_id]
                return {"applied": False, "backupId": None, "reason": "Preview is diff-only and cannot be auto-applied yet"}

            if file_path is None or not file_path.exists():
                del self.patch_previews[preview_id]
                return {"applied": False, "backupId": None, "reason": "Target file not found"}

            original = file_path.read_text(errors="ignore")

            def _apply_with_fallback(src: str, old_s: str, new_s: str):
                # 1) exact
                if old_s in src:
                    return src.replace(old_s, new_s, 1), "exact"

                # 2) normalized whitespace fallback
                import re
                old_norm = re.sub(r"\s+", " ", old_s).strip()
                if old_norm:
                    # Try to locate a similarly normalized window.
                    lines = src.splitlines(keepends=True)
                    for i in range(len(lines)):
                        for j in range(i + 1, min(len(lines), i + 60) + 1):
                            chunk = "".join(lines[i:j])
                            chunk_norm = re.sub(r"\s+", " ", chunk).strip()
                            if chunk_norm == old_norm:
                                replaced = src.replace(chunk, new_s, 1)
                                return replaced, "normalized"

                # 3) no safe match
                return None, None

            applied_res, mode = _apply_with_fallback(original, str(old_text), str(new_text))
            if applied_res is None:
                del self.patch_previews[preview_id]
                return {
                    "applied": False,
                    "backupId": None,
                    "reason": "oldText not found in current file (exact/normalized)",
                }

            backup_id = f"bk_{uuid.uuid4().hex[:8]}"
            self.patch_backups[backup_id] = {"path": str(file_path), "content": original, "sessionId": str(session_id or "")}
            file_path.write_text(applied_res)
            del self.patch_previews[preview_id]
            return {"applied": True, "backupId": backup_id, "applyMode": mode}

        if method == "ide.patch_rollback":
            backup_id = params.get("backupId")
            if backup_id not in self.patch_backups:
                raise ValueError("Backup not found")
            b = self.patch_backups[backup_id]
            path = Path(b["path"])
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(b["content"])
            del self.patch_backups[backup_id]
            return {"rolledBack": True}

        if method == "ide.patch_preview_get":
            preview_id = params.get("previewId")
            if preview_id not in self.patch_previews:
                raise ValueError("Preview not found")
            p = self.patch_previews[preview_id]
            return {
                "previewId": preview_id,
                "path": p.get("path", ""),
                "oldText": p.get("oldText"),
                "newText": p.get("newText"),
                "diff": p.get("diff", ""),
                "applicable": p.get("applicable", False),
                "reason": p.get("reason"),
            }

        if method == "ide.patch_preview_last":
            session_id = str(params.get("sessionId", ""))
            if not session_id:
                raise ValueError("Session not found")
            return self.last_preview_by_session.get(session_id, {"previewId": None, "applicable": False, "reason": "No preview for session"})

        if method == "ide.run_command":
            command = str(params.get("command", "")).strip()
            if not command:
                raise ValueError("Command required")
            lowered = command.lower()
            if any(pattern in lowered for pattern in self.BLOCKED_PATTERNS):
                raise ValueError("Command blocked by safety policy")
            if not any(command.startswith(prefix) for prefix in self.ALLOWED_COMMAND_PREFIXES):
                raise ValueError("Command not in allowlist")

            exec_id = f"ex_{uuid.uuid4().hex[:6]}"
            session_id = params.get("sessionId")
            run_id = params.get("runId", "")
            ws = self.session_sockets.get(session_id) if session_id else None
            if ws is not None and session_id:
                cwd = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
                asyncio.create_task(self._stream_command(ws, session_id, str(run_id), exec_id, command, cwd))
            return {"started": True, "execId": exec_id, "policy": "allowlist"}

        raise ValueError(f"Unknown method: {method}")

    async def _emit_event(self, ws, event_type: str, session_id: str, run_id: str, data: Dict[str, Any]):
        payload = {
            "jsonrpc": "2.0",
            "method": "event",
            "params": {
                "type": event_type,
                "sessionId": session_id,
                "runId": run_id,
                "data": data,
            },
        }
        await ws.send(json.dumps(payload))

    @staticmethod
    def _message_to_raw(message: Any) -> Dict[str, Any]:
        if hasattr(message, "model_dump"):
            raw = message.model_dump()
            if isinstance(raw, dict):
                return raw
        if isinstance(message, dict):
            return message
        return {}

    @staticmethod
    def _extract_text_from_message(message: Any) -> str:
        try:
            content = getattr(message, "content", None)
            if isinstance(content, list):
                texts = []
                for block in content:
                    text = getattr(block, "text", None)
                    if text:
                        texts.append(str(text))
                    elif isinstance(block, dict) and block.get("type") == "text":
                        texts.append(str(block.get("text", "")))
                return "\n".join([t for t in texts if t]).strip()
            if isinstance(content, str):
                return content.strip()
            result = getattr(message, "result", None)
            if isinstance(result, str):
                return result.strip()

            raw = SidecarServer._message_to_raw(message)
            raw_content = raw.get("content")
            if isinstance(raw_content, list):
                texts = [str(b.get("text", "")).strip() for b in raw_content if isinstance(b, dict) and b.get("type") == "text"]
                merged = "\n".join([t for t in texts if t]).strip()
                if merged:
                    return merged
            if isinstance(raw.get("result"), str):
                return raw["result"].strip()
            return ""
        except Exception:
            return ""

    @staticmethod
    def _extract_tool_events_from_message(message: Any) -> list[Dict[str, Any]]:
        events: list[Dict[str, Any]] = []
        raw = SidecarServer._message_to_raw(message)
        content = raw.get("content")
        if not isinstance(content, list):
            return events

        for block in content:
            if not isinstance(block, dict):
                continue
            btype = block.get("type")
            if btype in {"tool_use", "server_tool_use", "mcp_tool_use"}:
                tcid = str(block.get("id", ""))
                events.append(
                    {
                        "event": "tool.call",
                        "data": {
                            "toolCallId": tcid,
                            "name": str(block.get("name", "tool")),
                            "status": "pending",
                            "input": block.get("input", {}),
                        },
                    }
                )
            elif btype in {
                "tool_result",
                "tool_search_tool_result",
                "web_fetch_tool_result",
                "web_search_tool_result",
                "code_execution_tool_result",
                "bash_code_execution_tool_result",
                "text_editor_code_execution_tool_result",
                "mcp_tool_result",
            }:
                tcid = str(block.get("tool_use_id", ""))
                events.append(
                    {
                        "event": "tool.update",
                        "data": {
                            "toolCallId": tcid,
                            "status": "failed" if bool(block.get("is_error", False)) else "completed",
                            "output": block.get("content", ""),
                        },
                    }
                )
        return events

    def _build_intellij_mcp_server(self, session_id: str):
        from claude_agent_sdk import create_sdk_mcp_server, tool

        @tool("get_current_file", "Get current editor file content", {"path": str, "cursorOffset": int})
        async def t_get_current_file(args):
            res = await self.dispatch({"method": "ide.get_current_file", "params": {"sessionId": session_id, "path": args.get("path", ""), "cursorOffset": int(args.get("cursorOffset", 0))}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("get_selection", "Get selected text range", {"path": str, "startOffset": int, "endOffset": int})
        async def t_get_selection(args):
            res = await self.dispatch({"method": "ide.get_selection", "params": {"sessionId": session_id, "path": args.get("path", ""), "startOffset": int(args.get("startOffset", 0)), "endOffset": int(args.get("endOffset", 0))}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("read_file", "Read a file", {"path": str})
        async def t_read_file(args):
            res = await self.dispatch({"method": "ide.read_file", "params": {"sessionId": session_id, "path": args.get("path", "")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("find_files", "Find files by glob", {"glob": str})
        async def t_find_files(args):
            res = await self.dispatch({"method": "ide.find_files", "params": {"sessionId": session_id, "glob": args.get("glob", "**/*")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("search_text", "Search text in files", {"query": str, "glob": str})
        async def t_search_text(args):
            res = await self.dispatch({"method": "ide.search_text", "params": {"sessionId": session_id, "query": args.get("query", ""), "glob": args.get("glob", "**/*")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("write_file", "Write full file content", {"path": str, "content": str})
        async def t_write_file(args):
            res = await self.dispatch({"method": "ide.write_file", "params": {"sessionId": session_id, "path": args.get("path", ""), "content": args.get("content", "")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("apply_patch", "Create patch preview from exact text replacement", {"path": str, "oldText": str, "newText": str})
        async def t_apply_patch(args):
            path = str(args.get("path", ""))
            old_text = str(args.get("oldText", ""))
            new_text = str(args.get("newText", ""))
            read_res = await self.dispatch({"method": "ide.read_file", "params": {"sessionId": session_id, "path": path}})
            content = str(read_res.get("content", ""))
            if old_text and old_text not in content:
                return {"content": [{"type": "text", "text": json.dumps({"previewCreated": False, "reason": "oldText not found"})}]}
            preview_res = await self.dispatch({"method": "ide.apply_patch_preview", "params": {"sessionId": session_id, "path": path, "oldText": old_text, "newText": new_text}})
            return {"content": [{"type": "text", "text": json.dumps({"previewCreated": True, **preview_res})}]}

        @tool("run_test", "Run test command or target", {"target": str})
        async def t_run_test(args):
            res = await self.dispatch({"method": "ide.run_test", "params": {"sessionId": session_id, "target": args.get("target", "pytest -q")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("get_symbol_at_cursor", "Get symbol at cursor offset", {"path": str, "cursorOffset": int})
        async def t_get_symbol(args):
            res = await self.dispatch({"method": "ide.get_symbol_at_cursor", "params": {"sessionId": session_id, "path": args.get("path", ""), "cursorOffset": int(args.get("cursorOffset", 0))}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("find_usages", "Find symbol usages", {"symbol": str, "glob": str})
        async def t_find_usages(args):
            res = await self.dispatch({"method": "ide.find_usages", "params": {"sessionId": session_id, "symbol": args.get("symbol", ""), "glob": args.get("glob", "**/*")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        @tool("get_diagnostics", "Get diagnostics for file", {"path": str})
        async def t_get_diagnostics(args):
            res = await self.dispatch({"method": "ide.get_diagnostics", "params": {"sessionId": session_id, "path": args.get("path", "")}})
            return {"content": [{"type": "text", "text": json.dumps(res)}]}

        return create_sdk_mcp_server(
            name="intellij",
            version="1.0.0",
            tools=[
                t_get_current_file,
                t_get_selection,
                t_read_file,
                t_find_files,
                t_search_text,
                t_write_file,
                t_apply_patch,
                t_run_test,
                t_get_symbol,
                t_find_usages,
                t_get_diagnostics,
            ],
        )

    async def _stream_from_claude(self, ws, session_id: str, run_id: str, prompt: str):
        try:
            from claude_agent_sdk import ClaudeAgentOptions, query

            session_cwd = self.sessions.get(session_id).cwd if session_id in self.sessions else "."
            intellij_mcp = self._build_intellij_mcp_server(session_id)
            options = ClaudeAgentOptions(
                cwd=session_cwd,
                permission_mode="default",
                mcp_servers={"intellij": intellij_mcp},
                allowed_tools=[
                    "mcp__intellij__get_current_file",
                    "mcp__intellij__get_selection",
                    "mcp__intellij__read_file",
                    "mcp__intellij__find_files",
                    "mcp__intellij__search_text",
                    "mcp__intellij__write_file",
                    "mcp__intellij__apply_patch",
                    "mcp__intellij__run_test",
                    "mcp__intellij__get_symbol_at_cursor",
                    "mcp__intellij__find_usages",
                    "mcp__intellij__get_diagnostics",
                ],
                disallowed_tools=["Read", "Write", "Edit", "Glob", "Grep", "Bash"],
                system_prompt=(
                    "Use IntelliJ MCP tools for project access and edits (get_current_file/get_selection/read_file/find_files/search_text/write_file/apply_patch/run_test/get_symbol_at_cursor/find_usages/get_diagnostics). "
                    "Do not use built-in filesystem/shell tools."
                ),
            )

            async for message in query(prompt=prompt, options=options):
                if run_id not in self.active_runs:
                    return

                for ev in self._extract_tool_events_from_message(message):
                    await self._emit_event(ws, ev["event"], session_id, run_id, ev["data"])

                text = self._extract_text_from_message(message)
                if text:
                    await self._emit_event(ws, "assistant.chunk", session_id, run_id, {"text": text})

            if run_id not in self.active_runs:
                return

            await self._emit_event(ws, "prompt.done", session_id, run_id, {"stopReason": "end_turn"})
            self.active_runs.pop(run_id, None)
            self._set_session_status(session_id)
        except Exception as e:
            await self._emit_event(ws, "prompt.error", session_id, run_id, {"message": f"claude stream failure: {e}"})
            self.active_runs.pop(run_id, None)
            self._set_session_status(session_id, "error")

    async def _stream_command(self, ws, session_id: str, run_id: str, exec_id: str, command: str, cwd: str):
        try:
            proc = await asyncio.create_subprocess_shell(
                command,
                cwd=cwd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )

            async def pump(stream, stream_name: str):
                while True:
                    line = await stream.readline()
                    if not line:
                        break
                    await self._emit_event(
                        ws,
                        "command.output",
                        session_id,
                        run_id,
                        {"execId": exec_id, "stream": stream_name, "chunk": line.decode(errors="ignore")[:2000]},
                    )

            await asyncio.gather(pump(proc.stdout, "stdout"), pump(proc.stderr, "stderr"))
            code = await proc.wait()
            await self._emit_event(
                ws,
                "command.done",
                session_id,
                run_id,
                {"execId": exec_id, "exitCode": int(code)},
            )
        except Exception as e:
            await self._emit_event(
                ws,
                "command.done",
                session_id,
                run_id,
                {"execId": exec_id, "exitCode": -1, "error": str(e)},
            )

    async def _simulate_stream(self, ws, session_id: str, run_id: str, prompt: str):
        try:
            tool_call_id = f"tc_{uuid.uuid4().hex[:6]}"
            await self._emit_event(
                ws,
                "tool.call",
                session_id,
                run_id,
                {
                    "toolCallId": tool_call_id,
                    "name": "ide.search_text",
                    "status": "pending",
                    "input": {"query": prompt[:60]},
                },
            )
            await asyncio.sleep(0.03)
            await self._emit_event(
                ws,
                "tool.update",
                session_id,
                run_id,
                {
                    "toolCallId": tool_call_id,
                    "status": "completed",
                    "output": {"summary": "Simulated search complete"},
                },
            )

            chunks = [
                "Got it. I am analyzing your request. ",
                f"Prompt received: {prompt[:120]}. ",
                "Next milestone will wire Claude SDK live stream.",
            ]
            for chunk in chunks:
                if run_id not in self.active_runs:
                    return
                await self._emit_event(ws, "assistant.chunk", session_id, run_id, {"text": chunk})
                await asyncio.sleep(0.05)

            if run_id not in self.active_runs:
                return

            await self._emit_event(
                ws,
                "prompt.done",
                session_id,
                run_id,
                {"stopReason": "end_turn"},
            )
            self.active_runs.pop(run_id, None)
            self._set_session_status(session_id)
        except Exception:
            await self._emit_event(
                ws,
                "prompt.error",
                session_id,
                run_id,
                {"message": "stream failure"},
            )
            self.active_runs.pop(run_id, None)
            self._set_session_status(session_id, "error")

    async def _handle_notification(self, req: Dict[str, Any]) -> None:
        _ = req
        return


async def main() -> None:
    server = SidecarServer()
    async with websockets.serve(server.handle, "127.0.0.1", 8765, max_size=10_000_000):
        print("intelli-agent sidecar listening on ws://127.0.0.1:8765")
        await asyncio.Future()


if __name__ == "__main__":
    asyncio.run(main())
