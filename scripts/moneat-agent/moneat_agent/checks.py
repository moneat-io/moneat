"""Poll GitHub until external checks finish on a PR.

CodeRabbit does NOT create a GitHub check run. It signals completion by
posting a summary comment containing the marker
``<!-- This is an auto-generated comment: summarize by coderabbit.ai -->``.
We also check for a PR review submitted by ``coderabbitai[bot]``.

SonarQube runs as a GitHub Actions job (``Build and analyze`` from the
``SonarQube Analysis`` workflow).  We poll the check-runs API for that
job's status, then use ``sonar list issues`` to retrieve findings.
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

_SONAR_CHECK_NAME = "Build and analyze"
_SONAR_PROJECT_KEY = "moneat"


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


# ---------------------------------------------------------------------------
# SonarQube Analysis check
# ---------------------------------------------------------------------------

def wait_for_sonarqube(
    repo: str,
    pr_number: int,
    *,
    poll_interval: int = DEFAULT_POLL_INTERVAL,
    max_poll_time: int = MAX_POLL_TIME,
) -> bool:
    """Block until the SonarQube Analysis check has completed.

    Returns True when the check finishes (regardless of pass/fail — the
    caller inspects issues separately).
    """
    log.system(f"Waiting for SonarQube Analysis on PR #{pr_number}...")

    start = time.monotonic()
    attempt = 0
    while True:
        elapsed = time.monotonic() - start
        if elapsed > max_poll_time:
            log.warn(f"Timed out after {max_poll_time}s waiting for SonarQube.")
            return False

        attempt += 1
        status = _sonarqube_check_status(repo, pr_number)

        if status == "completed":
            log.success(f"SonarQube Analysis completed after {int(elapsed)}s")
            return True

        if status is None and attempt > 5:
            log.warn("SonarQube check run not found — skipping.")
            return False

        log.system(
            f"  Poll #{attempt}: SonarQube {status or 'not started'} ({int(elapsed)}s elapsed)"
        )
        time.sleep(poll_interval)


def _get_pr_head_sha(repo: str, pr_number: int) -> str | None:
    """Return the HEAD commit SHA on a PR's branch."""
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/pulls/{pr_number}",
                "--jq", ".head.sha",
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return raw or None
    except subprocess.CalledProcessError:
        return None


def _sonarqube_check_status(repo: str, pr_number: int) -> str | None:
    """Return ``'completed'``, ``'in_progress'``, ``'queued'``, or ``None``."""
    sha = _get_pr_head_sha(repo, pr_number)
    if not sha:
        return None

    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/commits/{sha}/check-runs",
                "--jq",
                f'.check_runs | map(select(.name == "{_SONAR_CHECK_NAME}")) | last | .status',
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        return raw if raw and raw != "null" else None
    except subprocess.CalledProcessError:
        return None


def fetch_sonar_issues(branch: str) -> str:
    """Run ``sonar list issues`` and return the raw output.

    Returns an empty string when no issues are reported or the command fails.
    """
    try:
        result = subprocess.run(
            [
                "sonar", "list", "issues",
                "-p", _SONAR_PROJECT_KEY,
                "--branch", branch,
            ],
            capture_output=True,
            text=True,
            timeout=120,
        )
        output = result.stdout.strip()
        if result.returncode != 0 and result.stderr.strip():
            log.warn(f"sonar list issues stderr: {result.stderr.strip()}")
        return output
    except FileNotFoundError:
        log.warn("'sonar' CLI not found on PATH — skipping issue fetch.")
        return ""
    except subprocess.TimeoutExpired:
        log.warn("sonar list issues timed out after 120s.")
        return ""
