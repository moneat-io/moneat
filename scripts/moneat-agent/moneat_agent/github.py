"""Thin wrappers around the `gh` CLI for GitHub operations."""

from __future__ import annotations

import json
import re
import subprocess
from dataclasses import dataclass, field

from moneat_agent import log


@dataclass
class Issue:
    number: int
    title: str
    body: str
    labels: list[str] = field(default_factory=list)


@dataclass
class PullRequest:
    number: int
    title: str
    body: str
    head_branch: str
    base_branch: str
    labels: list[str] = field(default_factory=list)


def _run_gh(*args: str, repo: str | None = None) -> str:
    """Run ``gh`` with optional *repo* (``owner/name``).

    ``gh`` does not accept ``--repo`` as a global flag before subcommands
    (see `gh` 2.87+). Subcommands inherit ``-R`` / ``--repo``; ``repo view``
    takes the repository as a positional argument instead.
    """
    args_list = list(args)
    if repo is None:
        cmd = ["gh", *args_list]
    elif len(args_list) >= 2 and args_list[0] == "repo" and args_list[1] == "view":
        cmd = ["gh", "repo", "view", repo, *args_list[2:]]
    else:
        cmd = ["gh", *args_list, "-R", repo]
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(
            f"`{' '.join(cmd)}` failed (exit {result.returncode}):\n{result.stderr.strip()}"
        )
    return result.stdout.strip()


def check_auth() -> None:
    try:
        _run_gh("auth", "status")
    except RuntimeError as exc:
        log.fatal(f"GitHub CLI not authenticated. Run `gh auth login` first.\n{exc}")


def detect_repo() -> str:
    """Derive owner/repo from the current git remote."""
    try:
        url = subprocess.run(
            ["git", "remote", "get-url", "origin"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
    except subprocess.CalledProcessError:
        log.fatal("Could not detect repo from git remote 'origin'.")
    m = re.search(r"[:/]([^/]+/[^/.]+?)(?:\.git)?$", url)
    if not m:
        log.fatal(f"Could not parse owner/repo from remote URL: {url}")
    return m.group(1)


def get_default_branch(repo: str) -> str:
    raw = _run_gh("repo", "view", "--json", "defaultBranchRef", repo=repo)
    data = json.loads(raw)
    return data["defaultBranchRef"]["name"]


def get_issue(repo: str, number: int) -> Issue:
    raw = _run_gh(
        "issue", "view", str(number),
        "--json", "number,title,body,labels",
        repo=repo,
    )
    d = json.loads(raw)
    return Issue(
        number=d["number"],
        title=d["title"],
        body=d.get("body") or "",
        labels=[l["name"] for l in d.get("labels", [])],
    )


def get_pr(repo: str, number: int) -> PullRequest:
    raw = _run_gh(
        "pr", "view", str(number),
        "--json", "number,title,body,headRefName,baseRefName,labels",
        repo=repo,
    )
    d = json.loads(raw)
    return PullRequest(
        number=d["number"],
        title=d["title"],
        body=d.get("body") or "",
        head_branch=d["headRefName"],
        base_branch=d["baseRefName"],
        labels=[l["name"] for l in d.get("labels", [])],
    )


def create_pr(
    repo: str,
    head: str,
    title: str,
    body: str,
    base: str | None = None,
) -> int:
    """Create a PR and return the PR number.

    ``gh pr create`` prints the new PR URL to stdout (no ``--json`` support).
    We parse the number from ``https://github.com/<owner>/<repo>/pull/<N>``.
    """
    args = ["pr", "create", "--title", title, "--body", body, "--head", head]
    if base:
        args += ["--base", base]
    raw = _run_gh(*args, repo=repo)
    m = re.search(r"/pull/(\d+)", raw)
    if m:
        return int(m.group(1))
    # Fallback: look up the PR we just created
    found = pr_exists(repo, head)
    if found:
        return found
    raise RuntimeError(f"Created PR but could not determine number from output: {raw}")


def push_branch(worktree: str, branch: str) -> None:
    subprocess.run(
        ["git", "push", "-u", "origin", branch],
        cwd=worktree,
        check=True,
    )


def pr_exists(repo: str, branch: str) -> int | None:
    """Return the PR number if one already exists for *branch*, else None."""
    try:
        raw = _run_gh(
            "pr", "view", branch,
            "--json", "number",
            repo=repo,
        )
        return json.loads(raw)["number"]
    except RuntimeError:
        return None
