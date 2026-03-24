"""Run the Cursor Agent CLI (`agent`) with structured logging."""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from moneat_agent import log

DEFAULT_OPUS = "claude-4.6-opus-high-thinking"
DEFAULT_COMPOSER = "composer-2"


def _model_id(env_key: str, default: str) -> str:
    return os.environ.get(env_key, default)


def opus_model() -> str:
    return _model_id("MONEAT_MODEL_OPUS", DEFAULT_OPUS)


def composer_model() -> str:
    return _model_id("MONEAT_MODEL_COMPOSER", DEFAULT_COMPOSER)


def _build_cmd(
    *,
    model: str,
    workspace: Path,
    prompt: str,
    mode: str | None = None,
    force: bool = False,
) -> list[str]:
    cmd = [
        "agent",
        "-p",
        "--model", model,
        "--workspace", str(workspace),
        "--trust",
        "--approve-mcps",
        "--output-format", "text",
    ]
    if mode:
        cmd += ["--mode", mode]
    if force:
        cmd.append("--force")
    cmd.append(prompt)
    return cmd


def run(
    *,
    model: str,
    workspace: Path,
    prompt: str,
    label: str,
    mode: str | None = None,
    force: bool = False,
) -> str:
    """Execute `agent` and return its stdout. Streams stderr live."""
    model_name = "Opus 4.6" if "opus" in model else "Composer 2"
    log_fn = log.opus if "opus" in model else log.composer

    log_fn(f"{label}...")

    cmd = _build_cmd(
        model=model,
        workspace=workspace,
        prompt=prompt,
        mode=mode,
        force=force,
    )

    proc = subprocess.Popen(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    stdout_lines: list[str] = []
    assert proc.stdout is not None
    for line in proc.stdout:
        stdout_lines.append(line)
        sys.stdout.write(f"  {model_name} │ {line}")
        sys.stdout.flush()

    proc.wait()

    if proc.returncode != 0:
        stderr = proc.stderr.read() if proc.stderr else ""
        log.error(f"Agent exited with code {proc.returncode}")
        if stderr.strip():
            log.error(stderr.strip())
        raise RuntimeError(
            f"agent ({model_name}) failed during: {label}\n{stderr}"
        )

    log.success(f"{label} — done")
    return "".join(stdout_lines)


def plan_with_opus(workspace: Path, prompt: str) -> str:
    return run(
        model=opus_model(),
        workspace=workspace,
        prompt=prompt,
        label="Creating implementation plan",
        force=True,
    )


def implement_with_composer(workspace: Path, prompt: str) -> str:
    return run(
        model=composer_model(),
        workspace=workspace,
        prompt=prompt,
        label="Implementing changes",
        force=True,
    )


def verify_with_opus(workspace: Path, prompt: str) -> str:
    return run(
        model=opus_model(),
        workspace=workspace,
        prompt=prompt,
        label="Reviewing implementation",
        mode="ask",
        force=False,
    )


def fix_feedback_with_composer(workspace: Path, prompt: str) -> str:
    return run(
        model=composer_model(),
        workspace=workspace,
        prompt=prompt,
        label="Addressing review feedback",
        force=True,
    )
