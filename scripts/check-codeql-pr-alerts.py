#!/usr/bin/env python3
"""Fail a PR when CodeQL reports alerts on changed lines.

GitHub code scanning keeps a default-branch alert backlog. This check keeps that
backlog out of unrelated PRs while still making newly touched CodeQL findings
visible in the PR check output.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse


REPO_ROOT = Path(__file__).resolve().parents[1]
HUNK_RE = re.compile(r"^@@ -\d+(?:,\d+)? \+(?P<start>\d+)(?:,(?P<count>\d+))? @@")
MAX_SUMMARY_ROWS = 50


@dataclass(frozen=True)
class SarifRule:
    title: str
    help_uri: str | None
    level: str | None


@dataclass(frozen=True)
class SarifFinding:
    path: Path
    start_line: int
    end_line: int
    rule_id: str
    title: str
    message: str
    level: str
    category: str
    help_uri: str | None


def run_git(args: list[str]) -> str:
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if result.returncode != 0:
        command = "git " + " ".join(args)
        raise RuntimeError(f"{command} failed: {result.stderr.strip()}")
    return result.stdout


def decode_git_path(path: str) -> Path:
    if path == "/dev/null":
        return Path(path)
    if path.startswith('"') and path.endswith('"'):
        path = bytes(path[1:-1], "utf-8").decode("unicode_escape")
    if path.startswith("b/"):
        path = path[2:]
    return Path(path)


def changed_lines(base_ref: str, head_ref: str) -> dict[Path, set[int]]:
    diff = run_git(["diff", "--unified=0", "--no-ext-diff", base_ref, head_ref, "--"])
    lines_by_path: dict[Path, set[int]] = {}
    current_path: Path | None = None

    for line in diff.splitlines():
        if line.startswith("+++ "):
            current_path = decode_git_path(line.removeprefix("+++ ").strip())
            if current_path.as_posix() != "/dev/null":
                lines_by_path.setdefault(current_path, set())
            continue

        match = HUNK_RE.match(line)
        if match is None or current_path is None or current_path.as_posix() == "/dev/null":
            continue

        start = int(match.group("start"))
        count = int(match.group("count") or "1")
        if count == 0:
            continue

        lines_by_path.setdefault(current_path, set()).update(range(start, start + count))

    return lines_by_path


def sarif_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    if not path.exists():
        raise FileNotFoundError(f"SARIF path does not exist: {path}")

    files = sorted(
        child
        for child in path.rglob("*")
        if child.is_file() and child.name.endswith((".sarif", ".sarif.json"))
    )
    if not files:
        raise FileNotFoundError(f"No SARIF files found under: {path}")
    return files


def message_text(message: dict[str, Any] | None, fallback: str) -> str:
    if not message:
        return fallback
    return str(message.get("text") or message.get("markdown") or fallback)


def rule_title(rule: dict[str, Any], rule_id: str) -> str:
    return message_text(
        rule.get("shortDescription") or rule.get("fullDescription"),
        rule_id,
    )


def rule_level(rule: dict[str, Any]) -> str | None:
    default_config = rule.get("defaultConfiguration")
    if isinstance(default_config, dict) and default_config.get("level"):
        return str(default_config["level"])
    return None


def rules_by_id(run: dict[str, Any]) -> dict[str, SarifRule]:
    rules: dict[str, SarifRule] = {}
    tool = run.get("tool", {})
    components = [tool.get("driver", {})]
    components.extend(tool.get("extensions", []))

    for component in components:
        for rule in component.get("rules", []):
            rule_id = str(rule.get("id") or "")
            if not rule_id:
                continue
            rules[rule_id] = SarifRule(
                title=rule_title(rule, rule_id),
                help_uri=rule.get("helpUri"),
                level=rule_level(rule),
            )

    return rules


def run_category(run: dict[str, Any], sarif_file: Path) -> str:
    automation = run.get("automationDetails")
    if isinstance(automation, dict) and automation.get("id"):
        return str(automation["id"])

    tool = run.get("tool", {})
    driver = tool.get("driver", {})
    if driver.get("name"):
        return str(driver["name"])

    return sarif_file.name


def artifact_path(uri: str) -> Path | None:
    if not uri:
        return None

    if uri.startswith("file://"):
        parsed = urlparse(uri)
        absolute_path = Path(unquote(parsed.path))
        try:
            return absolute_path.resolve().relative_to(REPO_ROOT)
        except ValueError:
            parts = absolute_path.parts
            repo_parts = REPO_ROOT.parts
            for index in range(len(parts)):
                if parts[index : index + len(repo_parts)] == repo_parts:
                    return Path(*parts[index + len(repo_parts) :])
            return None

    path = Path(unquote(uri))
    if path.is_absolute():
        try:
            return path.resolve().relative_to(REPO_ROOT)
        except ValueError:
            return None
    return path


def primary_location(result: dict[str, Any]) -> tuple[Path, int, int] | None:
    for location in result.get("locations", []):
        physical = location.get("physicalLocation")
        if not isinstance(physical, dict):
            continue

        artifact = physical.get("artifactLocation")
        region = physical.get("region")
        if not isinstance(artifact, dict) or not isinstance(region, dict):
            continue

        path = artifact_path(str(artifact.get("uri") or ""))
        start_line = region.get("startLine")
        if path is None or not isinstance(start_line, int):
            continue

        end_line = region.get("endLine")
        if not isinstance(end_line, int) or end_line < start_line:
            end_line = start_line

        return path, start_line, end_line

    return None


def parse_sarif(path: Path) -> list[SarifFinding]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    findings: list[SarifFinding] = []

    for run in payload.get("runs", []):
        rules = rules_by_id(run)
        category = run_category(run, path)

        for result in run.get("results", []):
            location = primary_location(result)
            if location is None:
                continue

            result_rule_id = str(result.get("ruleId") or "")
            rule = rules.get(result_rule_id)
            path_value, start_line, end_line = location
            findings.append(
                SarifFinding(
                    path=path_value,
                    start_line=start_line,
                    end_line=end_line,
                    rule_id=result_rule_id,
                    title=rule.title if rule is not None else result_rule_id or "CodeQL alert",
                    message=message_text(result.get("message"), "CodeQL alert"),
                    level=str(result.get("level") or (rule.level if rule is not None else None) or "warning"),
                    category=category,
                    help_uri=rule.help_uri if rule is not None else None,
                )
            )

    return findings


def intersects_changed_line(finding: SarifFinding, changed: dict[Path, set[int]]) -> bool:
    changed_for_file = changed.get(finding.path)
    if not changed_for_file:
        return False

    return any(line in changed_for_file for line in range(finding.start_line, finding.end_line + 1))


def escape_command(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(value: str) -> str:
    return escape_command(value).replace(":", "%3A").replace(",", "%2C")


def annotation_level(level: str) -> str:
    return "error" if level == "error" else "warning"


def emit_annotation(finding: SarifFinding) -> None:
    command = annotation_level(finding.level)
    title = f"CodeQL: {finding.title}"
    message = finding.message
    if finding.help_uri:
        message = f"{message} ({finding.help_uri})"

    print(
        f"::{command} "
        f"file={escape_property(finding.path.as_posix())},"
        f"line={finding.start_line},"
        f"title={escape_property(title)}::"
        f"{escape_command(message)}"
    )


def summary_location(finding: SarifFinding) -> str:
    return f"{finding.path.as_posix()}:{finding.start_line}"


def markdown_cell(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def append_step_summary(findings: list[SarifFinding]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return

    with Path(summary_path).open("a", encoding="utf-8") as summary:
        summary.write("## CodeQL PR guard\n\n")
        if not findings:
            summary.write("No CodeQL findings were reported on changed PR lines.\n")
            return

        summary.write(f"Found {len(findings)} CodeQL finding(s) on changed PR lines.\n\n")
        summary.write("| Level | Rule | Location | Message |\n")
        summary.write("| --- | --- | --- | --- |\n")
        for finding in findings[:MAX_SUMMARY_ROWS]:
            summary.write(
                "| "
                f"{markdown_cell(finding.level)} | "
                f"{markdown_cell(finding.title)} | "
                f"`{markdown_cell(summary_location(finding))}` | "
                f"{markdown_cell(finding.message)} |\n"
            )

        remaining = len(findings) - MAX_SUMMARY_ROWS
        if remaining > 0:
            summary.write(f"\nOmitted {remaining} additional finding(s) from this summary.\n")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sarif-dir", required=True, type=Path, help="CodeQL SARIF output file or directory.")
    parser.add_argument("--base-ref", required=True, help="Base git ref or SHA for the PR target.")
    parser.add_argument("--head-ref", required=True, help="Head git ref or SHA to compare against the base.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    changed = changed_lines(args.base_ref, args.head_ref)
    findings = [
        finding
        for sarif_file in sarif_files(args.sarif_dir)
        for finding in parse_sarif(sarif_file)
        if intersects_changed_line(finding, changed)
    ]

    findings.sort(key=lambda finding: (finding.path.as_posix(), finding.start_line, finding.rule_id))
    for finding in findings:
        emit_annotation(finding)

    append_step_summary(findings)

    if not findings:
        print("No CodeQL findings on changed PR lines.")
        return 0

    print(f"CodeQL found {len(findings)} finding(s) on changed PR lines.")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"::error title=CodeQL PR guard failed::{escape_command(str(error))}")
        sys.exit(2)
