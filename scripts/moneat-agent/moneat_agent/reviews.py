"""Fetch and diff PR review comments for iterative CodeRabbit feedback."""

from __future__ import annotations

import json
import subprocess
from dataclasses import dataclass, field

from moneat_agent import log


@dataclass
class ReviewComment:
    id: int
    author: str
    body: str
    path: str
    line: int | None
    created_at: str


@dataclass
class ReviewSnapshot:
    comments: list[ReviewComment] = field(default_factory=list)

    @property
    def ids(self) -> set[int]:
        return {c.id for c in self.comments}


def fetch_review_comments(repo: str, pr_number: int) -> ReviewSnapshot:
    """Fetch all review comments on a PR."""
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/pulls/{pr_number}/comments",
                "--paginate",
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()

        if not raw:
            return ReviewSnapshot()

        data = json.loads(raw)
        comments = []
        for c in data:
            comments.append(ReviewComment(
                id=c["id"],
                author=c.get("user", {}).get("login", "unknown"),
                body=c.get("body", ""),
                path=c.get("path", ""),
                line=c.get("line") or c.get("original_line"),
                created_at=c.get("created_at", ""),
            ))
        return ReviewSnapshot(comments=comments)
    except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        log.warn(f"Failed to fetch review comments: {exc}")
        return ReviewSnapshot()


def fetch_issue_comments(repo: str, pr_number: int) -> ReviewSnapshot:
    """Fetch top-level issue/PR comments (CodeRabbit summary lives here)."""
    try:
        raw = subprocess.run(
            [
                "gh", "api",
                f"repos/{repo}/issues/{pr_number}/comments",
                "--paginate",
            ],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()

        if not raw:
            return ReviewSnapshot()

        data = json.loads(raw)
        comments = []
        for c in data:
            comments.append(ReviewComment(
                id=c["id"],
                author=c.get("user", {}).get("login", "unknown"),
                body=c.get("body", ""),
                path="",
                line=None,
                created_at=c.get("created_at", ""),
            ))
        return ReviewSnapshot(comments=comments)
    except (subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        log.warn(f"Failed to fetch issue comments: {exc}")
        return ReviewSnapshot()


def new_coderabbit_comments(
    current: ReviewSnapshot,
    previous: ReviewSnapshot,
) -> list[ReviewComment]:
    """Return CodeRabbit-authored comments in *current* not present in *previous*."""
    prev_ids = previous.ids
    return [
        c for c in current.comments
        if c.id not in prev_ids and _is_coderabbit(c.author)
    ]


def _is_coderabbit(author: str) -> bool:
    return "coderabbit" in author.lower() or author.lower() in (
        "coderabbitai[bot]",
        "coderabbitai",
    )


def format_for_prompt(comments: list[ReviewComment]) -> str:
    """Format review comments into a prompt-friendly string."""
    if not comments:
        return "(no review comments)"

    parts: list[str] = []
    for c in comments:
        header = f"**{c.author}**"
        if c.path:
            header += f" on `{c.path}`"
            if c.line:
                header += f" (line {c.line})"
        parts.append(f"{header}:\n{c.body}")

    return "\n\n---\n\n".join(parts)
