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
from dataclasses import dataclass

from moneat_agent import log

DEFAULT_POLL_INTERVAL = 30  # seconds
MAX_POLL_TIME = 1800  # 30 minutes
_CR_SUMMARY_MARKER = "summarize by coderabbit.ai"
_CR_BOT_LOGIN = "coderabbitai[bot]"

_SONAR_CHECK_NAME = "Build and analyze"
_SONAR_PROJECT_KEY = "moneat"

_TEST_CHECK_NAMES = ("backend-unit", "frontend-unit", "coverage-check")


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
# Generic check-run helpers
# ---------------------------------------------------------------------------

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


def _check_run_info(
    repo: str,
    sha: str,
    check_name: str,
) -> tuple[str | None, str | None]:
    """Return ``(status, conclusion)`` for the latest check run matching *check_name*.

    *status* is ``'queued'``, ``'in_progress'``, or ``'completed'``.
    *conclusion* is ``'success'``, ``'failure'``, ``'neutral'``, etc. (only
    meaningful when status is ``completed``).

    Returns ``(None, None)`` when the check run is not found.
    """
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/commits/{sha}/check-runs",
                "--jq",
                (
                    f'.check_runs | map(select(.name == "{check_name}")) '
                    f'| last | [.status, .conclusion] | @tsv'
                ),
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        if not raw or raw == "null":
            return None, None
        parts = raw.split("\t")
        status = parts[0] if parts[0] and parts[0] != "null" else None
        conclusion = parts[1] if len(parts) > 1 and parts[1] and parts[1] != "null" else None
        return status, conclusion
    except subprocess.CalledProcessError:
        return None, None


def _get_check_failure_details(repo: str, sha: str, check_name: str) -> str:
    """Fetch the output summary and annotations from a failed check run."""
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/commits/{sha}/check-runs",
                "--jq",
                (
                    f'.check_runs | map(select(.name == "{check_name}")) | last '
                    f'| {{ summary: .output.summary, title: .output.title, '
                    f'annotations: [.output.annotations[]? | '
                    f'{{ path: .path, line: .start_line, message: .message, level: .annotation_level }}] }}'
                ),
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        if raw and raw != "null":
            return raw
    except subprocess.CalledProcessError:
        pass
    return ""


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
        sha = _get_pr_head_sha(repo, pr_number)
        status, _ = _check_run_info(repo, sha, _SONAR_CHECK_NAME) if sha else (None, None)

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


def fetch_sonar_issues(branch: str) -> str:
    """Run ``sonar list issues`` and return the raw output.

    Returns an empty string when no issues are reported or the command fails.
    If the output is JSON with an empty ``issues`` array, returns empty string.
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
        if not output:
            return ""
        try:
            data = json.loads(output)
            if isinstance(data, dict) and not data.get("issues"):
                return ""
        except (json.JSONDecodeError, TypeError):
            pass
        return output
    except FileNotFoundError:
        log.warn("'sonar' CLI not found on PATH — skipping issue fetch.")
        return ""
    except subprocess.TimeoutExpired:
        log.warn("sonar list issues timed out after 120s.")
        return ""


# ---------------------------------------------------------------------------
# Test / coverage-check gate
# ---------------------------------------------------------------------------

@dataclass
class CheckResult:
    name: str
    conclusion: str | None
    details: str

    @property
    def passed(self) -> bool:
        return self.conclusion == "success"


def wait_for_test_checks(
    repo: str,
    pr_number: int,
    *,
    poll_interval: int = DEFAULT_POLL_INTERVAL,
    max_poll_time: int = MAX_POLL_TIME,
) -> list[CheckResult]:
    """Block until all required test checks have completed.

    Returns a list of :class:`CheckResult` for each check in
    ``_TEST_CHECK_NAMES``.  An empty list means the checks were never found
    or we timed out.
    """
    log.system(f"Waiting for test / coverage checks on PR #{pr_number}...")

    start = time.monotonic()
    attempt = 0
    while True:
        elapsed = time.monotonic() - start
        if elapsed > max_poll_time:
            log.warn(f"Timed out after {max_poll_time}s waiting for test checks.")
            return []

        attempt += 1
        sha = _get_pr_head_sha(repo, pr_number)
        if not sha:
            if attempt > 5:
                log.warn("Could not resolve PR HEAD SHA — skipping test checks.")
                return []
            time.sleep(poll_interval)
            continue

        all_completed = True
        pending_names: list[str] = []
        for name in _TEST_CHECK_NAMES:
            status, _ = _check_run_info(repo, sha, name)
            if status != "completed":
                all_completed = False
                pending_names.append(name)

        if all_completed:
            log.success(f"All test checks completed after {int(elapsed)}s")
            results: list[CheckResult] = []
            for name in _TEST_CHECK_NAMES:
                _, conclusion = _check_run_info(repo, sha, name)
                details = ""
                if conclusion and conclusion != "success":
                    details = _get_check_failure_details(repo, sha, name)
                results.append(CheckResult(name=name, conclusion=conclusion, details=details))
            return results

        still_waiting = ", ".join(pending_names)
        if all(s is None for s, _ in [_check_run_info(repo, sha, n) for n in _TEST_CHECK_NAMES]) and attempt > 5:
            log.warn("Test check runs not found — skipping.")
            return []

        log.system(
            f"  Poll #{attempt}: waiting on [{still_waiting}] ({int(elapsed)}s elapsed)"
        )
        time.sleep(poll_interval)
