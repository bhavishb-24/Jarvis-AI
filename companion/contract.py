"""Versioned, typed boundary between the Android planner and desktop executors.

Agents dispatch only names in ACTIONS. Website/document text is never parsed as
an action here. Platform adapters own argument validation for each action.
"""
from typing import Any, Dict

PROTOCOL_VERSION = 1

ACTIONS = frozenset({
    "screen.capture", "screen.update",
    "input.move", "input.click", "input.scroll", "input.type", "input.hotkey",
    "window.list", "window.activate", "window.close",
    "app.launch", "app.close", "project.open",
    "file.search", "file.read", "file.create", "file.edit", "file.move",
    "file.organize", "file.summarize",
    "browser.open", "browser.navigate", "browser.click", "browser.type",
    "browser.extract", "browser.back", "browser.forward",
    "system.status", "system.apps",
})

SENSITIVE_ACTIONS = frozenset({
    "input.move", "input.click", "input.scroll", "input.type", "input.hotkey",
    "window.activate", "window.close", "app.close", "project.open",
    "file.create", "file.edit", "file.move", "file.organize",
    "browser.click", "browser.type",
})


def requires_approval(action: str) -> bool:
    """Return whether a human must approve this action before dispatch."""
    if action not in ACTIONS:
        raise ValueError("unknown action")
    return action in SENSITIVE_ACTIONS


def validate_step(step: Dict[str, Any]) -> Dict[str, Any]:
    """Reject unknown operations and malformed envelopes before any side effect."""
    if not isinstance(step, dict):
        raise ValueError("step must be an object")
    action = step.get("action")
    if not isinstance(action, str) or action not in ACTIONS:
        raise ValueError("unknown action")
    args = step.get("args", {})
    if not isinstance(args, dict):
        raise ValueError("args must be an object")
    if len(str(args)) > 16384:
        raise ValueError("arguments too large")
    return {"action": action, "args": args}
