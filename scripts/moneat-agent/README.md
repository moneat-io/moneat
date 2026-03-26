# moneat-agent

Automated issue/PR fixer for Moneat. Orchestrates Cursor Agent CLI with
**Opus 4.6** (planning & review) and **Composer 2** (implementation), manages
git worktrees, and loops on CodeRabbit feedback until the PR is clean.

## Prerequisites

| Tool | Purpose | Install |
|------|---------|---------|
| `agent` (Cursor CLI) | AI coding agent | `curl https://cursor.com/install -fsS \| bash` |
| `gh` (GitHub CLI) | Issues, PRs, checks | `brew install gh` then `gh auth login` |
| `git` | Worktrees, branching | (included with Xcode CLT) |
| Python 3.11+ | Runs the orchestrator | `brew install python@3.12` |

You also need a `CURSOR_API_KEY` environment variable set for headless use:

```bash
export CURSOR_API_KEY=your_key_here
```

## Install

**macOS Homebrew Python** marks the interpreter as [externally managed](https://peps.python.org/pep-0668/) (`externally-managed-environment`). Do not install into it; use a **virtual environment** (recommended):

```bash
cd /path/to/Moneat
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -e ./scripts/moneat-agent
```

After `activate`, `moneat-agent` is on your PATH for that shell.

**Optional — global CLI via pipx** (isolates the app in its own venv; good if you do not want to `activate` each time):

```bash
brew install pipx && pipx ensurepath
# from repo root — reinstall after changing the tool if you need updates
pipx install ./scripts/moneat-agent
```

For **editable** development (your edits to this repo are picked up immediately), use the `.venv` flow above, not pipx.

**Without installing** (no venv, no `moneat-agent` on PATH):

```bash
cd scripts/moneat-agent && python3 -m moneat_agent --help
```

From the repo root:

```bash
PYTHONPATH=scripts/moneat-agent python3 -m moneat_agent --help
```

## Usage

```bash
# Fix a GitHub issue
moneat-agent "fix issue #223"

# Continue work on an existing PR
moneat-agent "finish PR #45"

# Explicit overrides
moneat-agent "implement the new auth flow" --issue 223
moneat-agent "address the remaining feedback" --pr 45

# Use a pre-written plan file — skips Opus planning entirely
moneat-agent "fix issue #223" --plan-file ./my-plan.md

# Dry run (parse target + print what would happen)
moneat-agent "fix issue #223" --dry-run

# Adjust iteration caps
moneat-agent "fix issue #10" --max-verify-rounds 5 --max-cr-rounds 4
```

## How it works

```
┌─────────────────────────────────────────────────┐
│  1. Parse target (issue or PR) from your prompt │
│  2. Create git worktree + branch                │
│  3. Opus 4.6: create implementation plan        │
│  4. Composer 2: implement the plan              │
│  5. Opus 4.6: verify — satisfied?               │
│     └─ No → go to 3 (with delta feedback)       │
│  6. Commit, rebase, push                        │
│  7. Ensure PR exists (create if needed)         │
│  8. Wait for CodeRabbit check to complete       │
│  9. Fetch new CodeRabbit comments               │
│     └─ If new comments: Composer fixes → push   │
│        → go to 8                                │
│ 10. Done — PR ready for human review            │
└─────────────────────────────────────────────────┘
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `CURSOR_API_KEY` | _(required)_ | API key for Cursor CLI headless mode |
| `MONEAT_MODEL_OPUS` | `claude-4.6-opus-high-thinking` | Model ID for planning & review steps |
| `MONEAT_MODEL_COMPOSER` | `composer-2` | Model ID for implementation steps |

To see all available model IDs: `agent models`

## Flags

| Flag | Default | Description |
|------|---------|-------------|
| `--repo OWNER/REPO` | auto-detected | GitHub repository |
| `--issue N` | parsed from prompt | Explicit issue number |
| `--pr N` | parsed from prompt | Explicit PR number |
| `--plan-file PATH` | none | Path to a pre-written markdown plan; Opus planning is skipped. Re-running with this flag overwrites the existing `PLAN.md` in the worktree. |
| `--max-verify-rounds N` | 3 | Max plan/implement/verify iterations |
| `--max-cr-rounds N` | 3 | Max CodeRabbit feedback rounds |
| `--dry-run` | off | Print resolved target and exit |
