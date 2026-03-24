"""Prompt templates for each stage of the automation loop.

Every template is a plain function that accepts context vars and returns a
ready-to-use prompt string for the Cursor Agent CLI.
"""

from __future__ import annotations

from textwrap import dedent


def plan(*, issue_title: str, issue_body: str, extra_context: str = "") -> str:
    return dedent(f"""\
        You are planning a code change for the Moneat project.

        IMPORTANT: Before you begin, read these files for project context:
        - AGENTS.md
        - .github/copilot-instructions.md

        ## Task

        **{issue_title}**

        {issue_body}

        {extra_context}

        ## Instructions

        1. Analyze the codebase to understand the relevant areas that need changes.
        2. Produce a clear, actionable implementation plan.
        3. List every file that needs to be created or modified, with a short
           description of the change.
        4. Note any migration, test, or lint steps that must pass.
        5. Call out risks or ambiguities.

        Write your plan to the file `.moneat-agent/PLAN.md` in the workspace root.
    """)


def implement(*, plan_text: str, issue_title: str, issue_body: str) -> str:
    return dedent(f"""\
        You are implementing a code change for the Moneat project.

        IMPORTANT: Before you begin, read these files for project context:
        - AGENTS.md
        - .github/copilot-instructions.md

        ## Task

        **{issue_title}**

        {issue_body}

        ## Implementation plan

        Follow this plan exactly. Do not deviate unless you find an error in the
        plan — if so, note the deviation clearly in a commit message.

        {plan_text}

        ## After making changes

        - Run the relevant lint and test commands from copilot-instructions.md
          for every area you changed (backend: `./gradlew build`;
          dashboard: `npm run lint`).
        - Fix any lint or test failures before finishing.
    """)


def verify(*, plan_text: str, diff: str) -> str:
    return dedent(f"""\
        You are a senior engineer reviewing a code change for the Moneat project.

        IMPORTANT: Before you begin, read these files for project context:
        - AGENTS.md
        - .github/copilot-instructions.md

        ## Original plan

        {plan_text}

        ## Diff of changes made

        ```diff
        {diff}
        ```

        ## Your task

        Evaluate whether the implementation correctly and completely fulfills the
        plan. Check for:
        - Correctness and completeness vs. the plan
        - Idiomatic Kotlin / React patterns per project conventions
        - Potential bugs, edge cases, or security issues
        - Missing tests or lint violations

        Respond with **exactly** this format at the end of your response:

        VERDICT: SATISFIED

        or

        VERDICT: NOT_SATISFIED

        If NOT_SATISFIED, list the specific gaps and write a short delta plan
        describing only what still needs to change. Write this delta plan to
        `.moneat-agent/DELTA_PLAN.md`.
    """)


def coderabbit_feedback(*, comments: str, issue_title: str) -> str:
    return dedent(f"""\
        You are addressing code review feedback for the Moneat project.

        IMPORTANT: Before you begin, read these files for project context:
        - AGENTS.md
        - .github/copilot-instructions.md

        ## Original task

        **{issue_title}**

        ## Review comments to address

        {comments}

        ## Instructions

        1. Address each review comment. Make the minimal change needed.
        2. If a comment is a style nit, fix it. If it requests a design change,
           implement it.
        3. If a comment is incorrect or not applicable, leave a brief note
           explaining why in a code comment or PR reply — but still make the
           fix if there is a reasonable interpretation.
        4. Run lint and test commands after changes.
    """)


def rebase_conflict(*, conflict_files: str, base_branch: str) -> str:
    return dedent(f"""\
        The branch has rebase conflicts against {base_branch}.

        Conflicting files:
        {conflict_files}

        Resolve these merge conflicts, keeping the intent of our branch's changes
        while incorporating upstream updates. After resolving, run
        `git add -A && git rebase --continue`.
    """)
