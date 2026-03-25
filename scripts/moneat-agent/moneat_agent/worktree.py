"""Git worktree lifecycle management."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path

from moneat_agent import log


def repo_root() -> Path:
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        capture_output=True,
        text=True,
        check=True,
    )
    return Path(result.stdout.strip())


def create(branch: str, base_branch: str, base_dir: Path | None = None) -> Path:
    """Create a worktree for *branch* off *base_branch* and return its path."""
    root = repo_root()
    if base_dir is None:
        base_dir = root.parent / ".moneat-worktrees"
    base_dir.mkdir(parents=True, exist_ok=True)

    wt_path = base_dir / branch.replace("/", "-")
    if wt_path.exists():
        log.system(f"Reusing existing worktree at {wt_path}")
        return wt_path

    log.system(f"Fetching latest {base_branch}...")
    subprocess.run(["git", "fetch", "origin", base_branch], cwd=root, check=True)

    log.system(f"Creating worktree  {wt_path}  on branch  {branch}")
    subprocess.run(
        [
            "git", "worktree", "add",
            "-b", branch,
            str(wt_path),
            f"origin/{base_branch}",
        ],
        cwd=root,
        check=True,
    )

    _run_setup(wt_path)
    return wt_path


def _run_setup(wt_path: Path) -> None:
    """Run .cursor/worktrees.json setup commands in the new worktree."""
    import json as _json

    for candidate in (wt_path / ".cursor" / "worktrees.json", repo_root() / ".cursor" / "worktrees.json"):
        if candidate.exists():
            data = _json.loads(candidate.read_text())
            cmds = data.get("setup-worktree-unix") or data.get("setup-worktree", [])
            if isinstance(cmds, str):
                cmds = [cmds]
            env = {**os.environ, "ROOT_WORKTREE_PATH": str(repo_root())}
            for cmd in cmds:
                log.system(f"worktree setup: {cmd}")
                result = subprocess.run(
                    cmd,
                    shell=True,
                    cwd=wt_path,
                    capture_output=True,
                    text=True,
                    env=env,
                )
                if result.returncode != 0:
                    err = (result.stderr or result.stdout or "").strip()
                    log.warn(
                        f"worktree setup exited {result.returncode}: {cmd}\n{err}"
                    )
            return
    log.system("No worktrees.json found — skipping setup commands.")


def rebase(wt_path: Path, base_branch: str) -> bool:
    """Rebase the worktree onto origin/<base_branch>. Returns True if clean."""
    log.system(f"Rebasing onto origin/{base_branch}...")
    subprocess.run(
        ["git", "fetch", "origin", base_branch],
        cwd=wt_path,
        check=True,
    )
    result = subprocess.run(
        ["git", "rebase", f"origin/{base_branch}"],
        cwd=wt_path,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        log.warn("Rebase conflict detected — aborting rebase for manual resolution.")
        subprocess.run(["git", "rebase", "--abort"], cwd=wt_path)
        return False
    log.success("Rebase clean.")
    return True


def commit_all(wt_path: Path, message: str) -> bool:
    """Stage all and commit. Returns False if there was nothing to commit."""
    subprocess.run(["git", "add", "-A"], cwd=wt_path, check=True)
    result = subprocess.run(
        ["git", "diff", "--cached", "--quiet"],
        cwd=wt_path,
    )
    if result.returncode == 0:
        log.system("Nothing to commit.")
        return False
    subprocess.run(["git", "commit", "-m", message], cwd=wt_path, check=True)
    log.success(f"Committed: {message}")
    return True


def get_diff(wt_path: Path, base_branch: str) -> str:
    result = subprocess.run(
        ["git", "diff", f"origin/{base_branch}...HEAD"],
        cwd=wt_path,
        capture_output=True,
        text=True,
    )
    return result.stdout


def remove(wt_path: Path) -> None:
    root = repo_root()
    subprocess.run(
        ["git", "worktree", "remove", "--force", str(wt_path)],
        cwd=root,
    )
