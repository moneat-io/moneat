"""CLI entry point: `moneat-agent "fix issue #223"`."""

from __future__ import annotations

import argparse
import enum
import re
import shutil
import subprocess
import sys
from pathlib import Path

from moneat_agent import __version__, log
from moneat_agent import checks, cursor_agent, github, prompts, reviews, worktree


# ---------------------------------------------------------------------------
# Parsing helpers
# ---------------------------------------------------------------------------

_ISSUE_RE = re.compile(r"\bissue\s*#?(\d+)\b", re.IGNORECASE)
_PR_RE = re.compile(r"\bPR\s*#?(\d+)\b", re.IGNORECASE)
_NUM_RE = re.compile(r"#(\d+)\b")


def _parse_target(text: str) -> tuple[str, int]:
    """Return ("issue", N) or ("pr", N) from free-form text."""
    issue_m = _ISSUE_RE.search(text)
    pr_m = _PR_RE.search(text)

    if issue_m and pr_m:
        log.fatal(
            "Ambiguous: both issue and PR detected. "
            "Use --issue or --pr to disambiguate."
        )
    if issue_m:
        return "issue", int(issue_m.group(1))
    if pr_m:
        return "pr", int(pr_m.group(1))

    # Bare "#309" — assume issue
    bare = _NUM_RE.search(text)
    if bare:
        return "issue", int(bare.group(1))

    log.fatal(
        f"Could not find an issue or PR number in: {text!r}\n"
        "Examples: 'fix issue #223', 'finish PR #45', 'resume fixing #309'"
    )
    raise SystemExit(1)  # unreachable, keeps mypy happy


# ---------------------------------------------------------------------------
# Resume detection
# ---------------------------------------------------------------------------

class Phase(enum.Enum):
    """Pipeline phases — ordered so we can skip completed ones."""
    PLAN = "plan"
    PUSH = "push"
    PR = "pr"
    CODERABBIT = "coderabbit"
    SONAR = "sonar"


def _detect_resume_phase(
    wt: Path,
    repo: str,
    branch: str,
    base_branch: str,
) -> Phase:
    """Inspect worktree / GitHub state and return the phase to start from."""
    plan_file = wt / ".moneat-agent" / "PLAN.md"
    has_plan = plan_file.exists()
    has_diff = bool(worktree.get_diff(wt, base_branch).strip())
    pr_number = github.pr_exists(repo, branch)

    if pr_number is not None:
        log.system(f"Resuming — PR #{pr_number} already exists.")
        return Phase.CODERABBIT
    if has_diff:
        branch_pushed = _branch_pushed(wt, branch)
        if branch_pushed:
            log.system("Resuming — branch pushed but no PR yet.")
            return Phase.PR
        log.system("Resuming — changes exist, need to commit & push.")
        return Phase.PUSH
    if has_plan:
        log.system("Resuming — plan exists but no implementation diff yet.")
        return Phase.PLAN
    return Phase.PLAN


def _branch_pushed(wt: Path, branch: str) -> bool:
    result = subprocess.run(
        ["git", "ls-remote", "--heads", "origin", branch],
        cwd=wt,
        capture_output=True,
        text=True,
    )
    return bool(result.stdout.strip())


# ---------------------------------------------------------------------------
# Core helpers
# ---------------------------------------------------------------------------

def _read_plan(wt: Path) -> str:
    plan_file = wt / ".moneat-agent" / "PLAN.md"
    if not plan_file.exists():
        log.fatal(f"Plan file not found at {plan_file}")
    return plan_file.read_text()


def _read_delta_plan(wt: Path) -> str | None:
    delta = wt / ".moneat-agent" / "DELTA_PLAN.md"
    if delta.exists():
        return delta.read_text()
    return None


def _is_satisfied(verify_output: str) -> bool:
    for line in reversed(verify_output.splitlines()):
        stripped = line.strip().upper()
        if "VERDICT:" in stripped:
            return "SATISFIED" in stripped and "NOT_SATISFIED" not in stripped
    return False


# ---------------------------------------------------------------------------
# Pipeline
# ---------------------------------------------------------------------------

def run_pipeline(
    *,
    repo: str,
    target_kind: str,
    target_number: int,
    max_verify_rounds: int,
    max_cr_rounds: int,
    max_sonar_rounds: int,
    dry_run: bool,
    plan_file: Path | None = None,
    plan_provider: str = "cursor",
) -> None:
    log.step(f"moneat-agent v{__version__}")
    log.system(f"Repo:   {repo}")
    log.system(f"Target: {target_kind} #{target_number}")

    github.check_auth()

    # ── Resolve target ────────────────────────────────────────────────
    if target_kind == "issue":
        issue = github.get_issue(repo, target_number)
        issue_title = issue.title
        issue_body = issue.body
        branch = f"fix/issue-{target_number}"
        pr_number: int | None = None
        log.system(f"Issue: {issue_title}")
    else:
        pr = github.get_pr(repo, target_number)
        issue_title = pr.title
        issue_body = pr.body
        branch = pr.head_branch
        pr_number = pr.number
        log.system(f"PR: {issue_title} (branch: {branch})")

    base_branch = github.get_default_branch(repo)
    log.system(f"Base branch: {base_branch}")

    if dry_run:
        log.system("──── DRY RUN ────")
        log.system(f"Would create worktree on branch: {branch}")
        if plan_file:
            log.system(f"Plan file:      {plan_file} (planning phase will be skipped)")
        log.system(f"Plan provider:  {plan_provider}")
        if plan_provider == "copilot":
            log.system(f"Copilot model:  {cursor_agent.copilot_opus_model()}")
        else:
            log.system(f"Opus model:     {cursor_agent.opus_model()}")
        log.system(f"Composer model: {cursor_agent.composer_model()}")
        return

    # ── Worktree ──────────────────────────────────────────────────────
    log.step("Setting up worktree")
    wt = worktree.create(branch, base_branch)
    (wt / ".moneat-agent").mkdir(exist_ok=True)

    # ── Seed external plan ────────────────────────────────────────────
    external_plan = plan_file is not None
    if external_plan:
        dest = wt / ".moneat-agent" / "PLAN.md"
        shutil.copyfile(plan_file, dest)
        log.system(f"Plan loaded from {plan_file} — Opus planning phase skipped.")

    # ── Detect resume point ───────────────────────────────────────────
    phase = _detect_resume_phase(wt, repo, branch, base_branch)

    # ── Plan → Implement → Verify loop ───────────────────────────────
    if phase == Phase.PLAN:
        prior_feedback = ""
        for verify_round in range(1, max_verify_rounds + 1):
            log.step(f"Plan / Implement / Verify — round {verify_round}/{max_verify_rounds}")

            if not external_plan:
                plan_prompt = prompts.plan(
                    issue_title=issue_title,
                    issue_body=issue_body,
                    extra_context=prior_feedback,
                )
                if plan_provider == "copilot":
                    cursor_agent.plan_with_copilot(wt, plan_prompt)
                else:
                    cursor_agent.plan_with_opus(wt, plan_prompt)

            plan_text = _read_plan(wt)

            # Implement
            impl_prompt = prompts.implement(
                plan_text=plan_text,
                issue_title=issue_title,
                issue_body=issue_body,
                prior_feedback=prior_feedback,
            )
            cursor_agent.implement_with_composer(wt, impl_prompt)

            # Verify
            diff = worktree.get_diff(wt, base_branch)
            verify_prompt = prompts.verify(plan_text=plan_text, diff=diff)
            verify_output = cursor_agent.verify_with_opus(wt, verify_prompt)

            if _is_satisfied(verify_output):
                log.success(f"Opus 4.6 is satisfied after round {verify_round}.")
                break

            log.warn(f"Opus 4.6 is NOT satisfied (round {verify_round}).")
            delta = _read_delta_plan(wt)
            if delta:
                prior_feedback = f"## Previous review feedback (delta plan)\n\n{delta}"
            else:
                prior_feedback = (
                    "## Previous review feedback\n\n"
                    "The reviewer was not satisfied. Re-read the diff carefully and "
                    "improve the implementation."
                )
        else:
            log.warn(f"Reached max verify rounds ({max_verify_rounds}) — proceeding anyway.")

    # ── Commit & push ─────────────────────────────────────────────────
    if phase.value in ("plan", "push"):
        log.step("Committing and pushing")
        worktree.commit_all(wt, f"fix: {issue_title} (automated)")

        if not worktree.rebase(wt, base_branch):
            log.warn("Rebase conflict — asking Composer to resolve...")
            conflict_prompt = prompts.rebase_conflict(
                conflict_files="(see git status)",
                base_branch=base_branch,
            )
            cursor_agent.implement_with_composer(wt, conflict_prompt)
            worktree.commit_all(wt, f"fix: resolve rebase conflicts for #{target_number}")

        github.push_branch(str(wt), branch)

    # ── Ensure PR exists ──────────────────────────────────────────────
    if phase.value in ("plan", "push", "pr"):
        if pr_number is None:
            pr_number = github.pr_exists(repo, branch)
        if pr_number is None:
            log.system("Creating pull request...")
            pr_number = github.create_pr(
                repo=repo,
                head=branch,
                title=issue_title,
                body=f"Closes #{target_number}\n\n_Automated by moneat-agent._",
                base=base_branch,
            )
            log.success(f"Created PR #{pr_number}")
        else:
            log.system(f"PR #{pr_number} already exists — pushed new commits.")

    # ── CodeRabbit feedback loop ──────────────────────────────────────
    if pr_number is None:
        pr_number = github.pr_exists(repo, branch)
    if pr_number is None:
        log.warn("No PR found — skipping CodeRabbit loop.")
    else:
        log.step("CodeRabbit review loop")
        prev_review = reviews.ReviewSnapshot()
        prev_issue = reviews.ReviewSnapshot()

        for cr_round in range(1, max_cr_rounds + 1):
            log.system(f"CodeRabbit round {cr_round}/{max_cr_rounds}")

            if not checks.wait_for_coderabbit(repo, pr_number):
                log.warn("CodeRabbit check not detected or timed out — skipping feedback loop.")
                break

            current_review = reviews.fetch_review_comments(repo, pr_number)
            current_issue = reviews.fetch_issue_comments(repo, pr_number)

            new_review = reviews.new_coderabbit_comments(current_review, prev_review)
            new_issue = reviews.new_coderabbit_comments(current_issue, prev_issue)
            all_new = new_review + new_issue

            if not all_new:
                log.success("No new CodeRabbit comments — we're done!")
                break

            log.system(f"Found {len(all_new)} new CodeRabbit comment(s) — addressing them.")

            formatted = reviews.format_for_prompt(all_new)
            feedback_prompt = prompts.coderabbit_feedback(
                comments=formatted,
                issue_title=issue_title,
            )
            cursor_agent.fix_feedback_with_composer(wt, feedback_prompt)

            worktree.commit_all(
                wt,
                f"fix: address CodeRabbit feedback (round {cr_round}) for #{target_number}",
            )
            github.push_branch(str(wt), branch)

            prev_review = current_review
            prev_issue = current_issue
        else:
            log.warn(f"Reached max CodeRabbit rounds ({max_cr_rounds}).")

    # ── Test / coverage gate ─────────────────────────────────────────
    if pr_number is None:
        pr_number = github.pr_exists(repo, branch)
    if pr_number is None:
        log.warn("No PR found — skipping test checks.")
    else:
        log.step("Test / coverage check gate")

        for ci_round in range(1, max_sonar_rounds + 1):
            log.system(f"CI check round {ci_round}/{max_sonar_rounds}")

            results = checks.wait_for_test_checks(repo, pr_number)
            if not results:
                log.warn("Test checks not found or timed out — skipping.")
                break

            failed = [r for r in results if not r.passed]
            if not failed:
                log.success("All test / coverage checks passed!")
                break

            failure_summary_parts: list[str] = []
            for r in failed:
                header = f"### {r.name} — {r.conclusion or 'unknown'}"
                detail = r.details if r.details else "(no details available)"
                failure_summary_parts.append(f"{header}\n\n{detail}")

            names = ", ".join(r.name for r in failed)
            log.warn(f"Failed checks: {names} — addressing them.")

            failure_text = "\n\n---\n\n".join(failure_summary_parts)
            ci_prompt = prompts.test_failure_feedback(
                failures=failure_text,
                issue_title=issue_title,
            )
            cursor_agent.fix_feedback_with_composer(wt, ci_prompt)

            worktree.commit_all(
                wt,
                f"fix: address CI failures ({names}) (round {ci_round}) for #{target_number}",
            )
            github.push_branch(str(wt), branch)
        else:
            log.warn(f"Reached max CI check rounds ({max_sonar_rounds}).")

    # ── SonarQube feedback loop ───────────────────────────────────────
    if pr_number is None:
        pr_number = github.pr_exists(repo, branch)
    if pr_number is None:
        log.warn("No PR found — skipping SonarQube loop.")
    else:
        log.step("SonarQube analysis loop")

        for sonar_round in range(1, max_sonar_rounds + 1):
            log.system(f"SonarQube round {sonar_round}/{max_sonar_rounds}")

            if not checks.wait_for_sonarqube(repo, pr_number):
                log.warn("SonarQube check not detected or timed out — skipping.")
                break

            issues = checks.fetch_sonar_issues(branch)
            if not issues:
                log.success("No SonarQube issues found — we're done!")
                break

            log.system("SonarQube reported issues — addressing them.")

            sonar_prompt = prompts.sonarqube_feedback(
                issues=issues,
                issue_title=issue_title,
            )
            cursor_agent.fix_feedback_with_composer(wt, sonar_prompt)

            worktree.commit_all(
                wt,
                f"fix: address SonarQube issues (round {sonar_round}) for #{target_number}",
            )
            github.push_branch(str(wt), branch)
        else:
            log.warn(f"Reached max SonarQube rounds ({max_sonar_rounds}).")

    log.step("Done")
    if pr_number:
        log.success(f"PR #{pr_number} is ready for human review.")
        log.system(f"https://github.com/{repo}/pull/{pr_number}")


# ---------------------------------------------------------------------------
# argparse
# ---------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="moneat-agent",
        description="Automated issue/PR fixer for Moneat.",
    )
    p.add_argument(
        "prompt",
        help=(
            'Free-form instruction, e.g. "fix issue #223", "finish PR #45", '
            '"resume fixing #309"'
        ),
    )
    p.add_argument(
        "--repo",
        default=None,
        help="GitHub owner/repo (auto-detected from git remote if omitted)",
    )
    p.add_argument(
        "--issue",
        type=int,
        default=None,
        help="Explicit issue number (overrides parsing from prompt)",
    )
    p.add_argument(
        "--pr",
        type=int,
        default=None,
        help="Explicit PR number (overrides parsing from prompt)",
    )
    p.add_argument(
        "--max-verify-rounds",
        type=int,
        default=3,
        help="Max plan/implement/verify iterations (default: 3)",
    )
    p.add_argument(
        "--max-cr-rounds",
        type=int,
        default=3,
        help="Max CodeRabbit feedback rounds (default: 3)",
    )
    p.add_argument(
        "--max-sonar-rounds",
        type=int,
        default=2,
        help="Max SonarQube fix rounds (default: 2)",
    )
    p.add_argument(
        "--plan-file",
        type=Path,
        default=None,
        metavar="PATH",
        help=(
            "Path to a pre-written markdown plan file. "
            "Copied into the worktree and used as-is; Opus planning is skipped. "
            "Re-running with --plan-file overwrites any existing PLAN.md in the worktree."
        ),
    )
    p.add_argument(
        "--plan-provider",
        choices=cursor_agent.VALID_PLAN_PROVIDERS,
        default="cursor",
        help=(
            "Which agent CLI to use for the planning phase. "
            '"cursor" uses the Cursor Agent CLI with Opus (default); '
            '"copilot" uses the GitHub Copilot CLI. '
            "Implementation always uses Cursor Composer 2."
        ),
    )
    p.add_argument(
        "--dry-run",
        action="store_true",
        help="Parse and resolve target, then print what would happen without executing",
    )
    p.add_argument(
        "--version",
        action="version",
        version=f"%(prog)s {__version__}",
    )
    return p


def main(argv: list[str] | None = None) -> None:
    parser = build_parser()
    args = parser.parse_args(argv)

    # Resolve target
    if args.issue:
        target_kind, target_number = "issue", args.issue
    elif args.pr:
        target_kind, target_number = "pr", args.pr
    else:
        target_kind, target_number = _parse_target(args.prompt)

    repo = args.repo or github.detect_repo()

    # Validate --plan-file early, before any side effects
    plan_file: Path | None = None
    if args.plan_file is not None:
        plan_file = args.plan_file.resolve()
        if not plan_file.exists() or not plan_file.is_file():
            log.fatal(f"--plan-file path does not exist or is not a file: {plan_file}")

    try:
        run_pipeline(
            repo=repo,
            target_kind=target_kind,
            target_number=target_number,
            max_verify_rounds=args.max_verify_rounds,
            max_cr_rounds=args.max_cr_rounds,
            max_sonar_rounds=args.max_sonar_rounds,
            dry_run=args.dry_run,
            plan_file=plan_file,
            plan_provider=args.plan_provider,
        )
    except KeyboardInterrupt:
        log.warn("\nInterrupted by user.")
        sys.exit(130)
    except RuntimeError as exc:
        log.error(str(exc))
        sys.exit(1)
