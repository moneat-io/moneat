"""Poll GitHub until CodeRabbit has finished reviewing a PR.

CodeRabbit does NOT create a GitHub check run. It signals completion by
posting a summary comment containing the marker
``<!-- This is an auto-generated comment: summarize by coderabbit.ai -->``.
We also check for a PR review submitted by ``coderabbitai[bot]``.
"""

from __future__ import annotations

import json
import subprocess
import time

from moneat_agent import log

DEFAULT_POLL_INTERVAL = 30  # seconds
MAX_POLL_TIME = 1800  # 30 minutes
_CR_SUMMARY_MARKER = "summarize by coderabbit.ai"
_CR_BOT_LOGIN = "coderabbitai[bot]"


def wait_for_coderabbit(
    repo: str,
    pr_number: int,
    *,
    poll_interval: int = DEFAULT_POLL_INTERVAL,
    max_poll_time: int = MAX_POLL_TIME,
) -> bool:
    """Block until CodeRabbit has posted its review. Returns True when done."""
    log.system(f"Waiting for CodeRabbit review on PR #{pr_number}...")

    start = time.monotonic()
    attempt = 0
    while True:
        elapsed = time.monotonic() - start
        if elapsed > max_poll_time:
            log.warn(f"Timed out after {max_poll_time}s waiting for CodeRabbit.")
            return False

        attempt += 1

        if _coderabbit_finished(repo, pr_number):
            log.success(f"CodeRabbit review detected after {int(elapsed)}s")
            return True

        log.system(
            f"  Poll #{attempt}: CodeRabbit not finished yet ({int(elapsed)}s elapsed)"
        )
        time.sleep(poll_interval)


def _coderabbit_finished(repo: str, pr_number: int) -> bool:
    """Return True if CodeRabbit's summary comment or review exists."""
    return _has_summary_comment(repo, pr_number) or _has_review(repo, pr_number)


def _has_summary_comment(repo: str, pr_number: int) -> bool:
    """Check issue comments for CodeRabbit's walkthrough summary."""
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/issues/{pr_number}/comments",
                "--paginate",
                "--jq",
                f'[.[] | select(.user.login=="{_CR_BOT_LOGIN}")] | length',
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        if not raw or int(raw) == 0:
            return False

        # Confirm the body contains the summary marker
        body_raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/issues/{pr_number}/comments",
                "--paginate",
                "--jq",
                f'.[] | select(.user.login=="{_CR_BOT_LOGIN}") | .body',
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        return _CR_SUMMARY_MARKER in body_raw
    except (subprocess.CalledProcessError, ValueError):
        return False


def _has_review(repo: str, pr_number: int) -> bool:
    """Check PR reviews for a submitted CodeRabbit review."""
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/pulls/{pr_number}/reviews",
                "--jq",
                f'[.[] | select(.user.login=="{_CR_BOT_LOGIN}")] | length',
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return bool(raw) and int(raw) > 0
    except (subprocess.CalledProcessError, ValueError):
        return False
