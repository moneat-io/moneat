#!/usr/bin/env python3
"""Validate Moneat migration version numbers.

With --base-ref, newly added migrations must continue from the target branch's
current maximum version. Existing migrations are immutable: modifying, deleting,
or renaming a migration that existed at the branch point fails the check. The
only exception is a collision repair that relocates one of several duplicate
versioned files without changing its SQL payload.

Use --fix with --base-ref to rename added migrations to the next available
versions while preserving their descriptions.
"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
MIGRATION_RE = re.compile(r"^V(?P<version>\d+)__(?P<description>.+)\.sql$")
MIGRATION_SETS = (
    ("PostgreSQL", Path("backend/src/main/resources/db/migration")),
    ("ClickHouse", Path("backend/src/main/resources/db/clickhouse_migration")),
)


@dataclass(frozen=True)
class Migration:
    path: Path
    version: int
    description: str


@dataclass(frozen=True)
class Problem:
    message: str
    path: Path | None = None
    title: str = "Migration version check failed"


@dataclass(frozen=True)
class MigrationAnalysis:
    name: str
    directory: Path
    base_max: int | None
    added: tuple[Migration, ...]


def run_git(args: list[str], check: bool = True) -> str:
    result = subprocess.run(
        ["git", "-C", str(REPO_ROOT), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if check and result.returncode != 0:
        command = "git " + " ".join(args)
        raise RuntimeError(f"{command} failed: {result.stderr.strip()}")
    return result.stdout


def parse_migration(path: Path) -> Migration | None:
    match = MIGRATION_RE.match(path.name)
    if match is None:
        return None

    return Migration(
        path=path,
        version=int(match.group("version")),
        description=match.group("description"),
    )


def migration_sort_key(migration: Migration) -> tuple[int, str]:
    return migration.version, migration.path.as_posix()


def current_sql_files(directory: Path) -> list[Path]:
    absolute_dir = REPO_ROOT / directory
    if not absolute_dir.exists():
        return []

    return sorted(
        directory / child.name
        for child in absolute_dir.iterdir()
        if child.is_file() and child.suffix == ".sql"
    )


def current_migrations(directory: Path) -> tuple[list[Migration], list[Path]]:
    migrations: list[Migration] = []
    invalid: list[Path] = []

    for path in current_sql_files(directory):
        migration = parse_migration(path)
        if migration is None:
            if path.name.startswith("V"):
                invalid.append(path)
            continue
        migrations.append(migration)

    return sorted(migrations, key=migration_sort_key), invalid


def base_migrations(base_ref: str, directory: Path) -> list[Migration]:
    output = run_git(["ls-tree", "-r", "--name-only", base_ref, "--", directory.as_posix()])
    migrations: list[Migration] = []

    for line in output.splitlines():
        path = Path(line)
        if path.suffix != ".sql":
            continue

        migration = parse_migration(path)
        if migration is not None:
            migrations.append(migration)

    return sorted(migrations, key=migration_sort_key)


def merge_base(base_ref: str) -> str:
    return run_git(["merge-base", base_ref, "HEAD"]).strip()


def migration_contents(path: Path) -> str:
    return (REPO_ROOT / path).read_text(encoding="utf-8")


def duplicate_repair_target(
    source: Path,
    candidates: list[Migration],
    base_paths: set[Path],
    base_max: int,
    branch_point: str,
) -> Migration | None:
    source_migration = parse_migration(source)
    if source_migration is None:
        return None

    base_contents = run_git(["show", f"{branch_point}:{source.as_posix()}"])
    matches: list[Migration] = []
    for candidate in candidates:
        if candidate.path in base_paths or candidate.version <= base_max:
            continue
        if candidate.description != source_migration.description:
            continue
        if migration_contents(candidate.path) == base_contents:
            matches.append(candidate)

    expected_version = base_max + 1
    return matches[0] if len(matches) == 1 and matches[0].version == expected_version else None


def changed_existing_migration_problems(base_ref: str, directory: Path, name: str) -> list[Problem]:
    branch_point = merge_base(base_ref)
    base = base_migrations(base_ref, directory)
    base_paths = {migration.path for migration in base}
    base_max = max((migration.version for migration in base), default=0)
    base_version_counts: dict[int, int] = {}
    for migration in base:
        base_version_counts[migration.version] = base_version_counts.get(migration.version, 0) + 1
    duplicate_paths = {
        migration.path for migration in base if base_version_counts[migration.version] > 1
    }
    current, _ = current_migrations(directory)
    output = run_git(["diff", "--name-status", "--find-renames", branch_point, "--", directory.as_posix()])
    problems: list[Problem] = []

    for line in output.splitlines():
        parts = line.split("\t")
        status = parts[0]
        paths = [Path(part) for part in parts[1:]]
        if status == "A":
            continue

        source = paths[0] if paths else None
        repair_target = (
            duplicate_repair_target(source, current, base_paths, base_max, branch_point)
            if source in duplicate_paths
            else None
        )
        if repair_target is not None:
            if status.startswith("R") and len(paths) > 1 and paths[1] == repair_target.path:
                continue
            if status == "D":
                continue

        migration_paths = [path for path in paths if parse_migration(path) is not None]
        if not migration_paths:
            continue

        action = {
            "M": "modified",
            "D": "deleted",
        }.get(status[0], "renamed or copied")
        path_list = ", ".join(path.as_posix() for path in migration_paths)
        problems.append(
            Problem(
                message=(
                    f"{name} migration {path_list} was {action}. Existing migrations are immutable; "
                    "add a new migration instead."
                ),
                path=migration_paths[-1],
                title=f"{name} migration was {action}",
            )
        )

    return problems


def duplicate_version_problems(name: str, migrations: list[Migration]) -> list[Problem]:
    by_version: dict[int, list[Migration]] = {}
    for migration in migrations:
        by_version.setdefault(migration.version, []).append(migration)

    problems: list[Problem] = []
    for version, matches in sorted(by_version.items()):
        if len(matches) < 2:
            continue

        files = ", ".join(migration.path.as_posix() for migration in matches)
        problems.append(
            Problem(
                message=f"{name} has duplicate migration version V{version}: {files}",
                path=matches[-1].path,
                title=f"Duplicate {name} migration version",
            )
        )

    return problems


def added_version_problems(
    name: str,
    base_ref: str,
    base_max: int,
    added: list[Migration],
) -> list[Problem]:
    if not added:
        return []

    expected_versions = list(range(base_max + 1, base_max + len(added) + 1))
    actual_versions = [migration.version for migration in added]
    if actual_versions == expected_versions:
        return []

    added_files = ", ".join(f"{migration.path.name} (V{migration.version})" for migration in added)
    expected = ", ".join(f"V{version}" for version in expected_versions)
    return [
        Problem(
            message=(
                f"{name} migration numbering is stale for {base_ref}. "
                f"The target branch is currently at V{base_max}; this change adds {added_files}. "
                f"Expected added versions: {expected}. "
                f"Run `python3 scripts/check-migration-versions.py --base-ref {base_ref} --fix` "
                "after fetching the target branch."
            ),
            path=added[0].path,
            title=f"Stale {name} migration version",
        )
    ]


def analyze_set(name: str, directory: Path, base_ref: str | None) -> tuple[MigrationAnalysis, list[Problem]]:
    migrations, invalid_files = current_migrations(directory)
    problems = [
        Problem(
            message=f"{path.as_posix()} does not match V<number>__description.sql",
            path=path,
            title=f"Invalid {name} migration name",
        )
        for path in invalid_files
    ]
    problems.extend(duplicate_version_problems(name, migrations))

    if base_ref is None:
        analysis = MigrationAnalysis(name=name, directory=directory, base_max=None, added=tuple())
        return analysis, problems

    base = base_migrations(base_ref, directory)
    base_paths = {migration.path for migration in base}
    base_max = max((migration.version for migration in base), default=0)
    added = sorted(
        [migration for migration in migrations if migration.path not in base_paths],
        key=migration_sort_key,
    )

    problems.extend(changed_existing_migration_problems(base_ref, directory, name))
    problems.extend(added_version_problems(name, base_ref, base_max, added))

    analysis = MigrationAnalysis(
        name=name,
        directory=directory,
        base_max=base_max,
        added=tuple(added),
    )
    return analysis, problems


def target_path_for(directory: Path, version: int, description: str) -> Path:
    return directory / f"V{version}__{description}.sql"


def rename_with_temp_files(renames: list[tuple[Path, Path]]) -> None:
    temp_renames: list[tuple[Path, Path, Path]] = []
    moving_sources = {source for source, target in renames if source != target}

    for index, (source, target) in enumerate(renames):
        source_abs = REPO_ROOT / source
        target_abs = REPO_ROOT / target
        if source == target:
            continue
        if target_abs.exists() and target not in moving_sources:
            raise RuntimeError(f"Cannot rename {source} to {target}: target already exists")

        temp = source_abs.with_name(f".migration-renumber-{os.getpid()}-{index}-{source_abs.name}")
        source_abs.rename(temp)
        temp_renames.append((temp, source_abs, target_abs))

    for temp, source_abs, target_abs in temp_renames:
        target_abs.parent.mkdir(parents=True, exist_ok=True)
        temp.rename(target_abs)
        print(f"Renamed {source_abs.relative_to(REPO_ROOT)} -> {target_abs.relative_to(REPO_ROOT)}")


def fix_set(analysis: MigrationAnalysis) -> None:
    if analysis.base_max is None or not analysis.added:
        return

    renames: list[tuple[Path, Path]] = []
    for offset, migration in enumerate(sorted(analysis.added, key=migration_sort_key), start=1):
        expected_version = analysis.base_max + offset
        target = target_path_for(analysis.directory, expected_version, migration.description)
        renames.append((migration.path, target))

    rename_with_temp_files(renames)


def escape_annotation_value(value: str) -> str:
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def print_problem(problem: Problem) -> None:
    print(f"ERROR: {problem.message}", file=sys.stderr)
    if os.getenv("GITHUB_ACTIONS") != "true":
        return

    title = escape_annotation_value(problem.title)
    message = escape_annotation_value(problem.message)
    if problem.path is None:
        print(f"::error title={title}::{message}", file=sys.stderr)
    else:
        print(f"::error file={problem.path.as_posix()},title={title}::{message}", file=sys.stderr)


def print_summary(analyses: list[MigrationAnalysis]) -> None:
    for analysis in analyses:
        if analysis.base_max is None:
            print(f"{analysis.name}: validated current migration filenames and duplicate versions")
            continue

        added_names = ", ".join(migration.path.name for migration in analysis.added) or "none"
        print(f"{analysis.name}: target max V{analysis.base_max}; added migrations: {added_names}")


def validate_base_ref(base_ref: str) -> None:
    run_git(["rev-parse", "--verify", f"{base_ref}^{{commit}}"])


def validate(base_ref: str | None) -> tuple[list[MigrationAnalysis], list[Problem]]:
    if base_ref is not None:
        validate_base_ref(base_ref)

    analyses: list[MigrationAnalysis] = []
    problems: list[Problem] = []
    for name, directory in MIGRATION_SETS:
        analysis, set_problems = analyze_set(name, directory, base_ref)
        analyses.append(analysis)
        problems.extend(set_problems)

    return analyses, problems


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check Moneat migration version numbering.")
    parser.add_argument(
        "--base-ref",
        default=None,
        help="Target branch/ref to compare against, for example origin/develop.",
    )
    parser.add_argument(
        "--fix",
        action="store_true",
        help="Rename added migrations to the next versions after --base-ref.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.fix and args.base_ref is None:
        print("ERROR: --fix requires --base-ref", file=sys.stderr)
        return 2

    try:
        analyses, problems = validate(args.base_ref)
        if args.fix:
            blocking = [
                problem
                for problem in problems
                if not problem.title.startswith("Duplicate ")
                and not problem.title.startswith("Stale ")
            ]
            if blocking:
                for problem in blocking:
                    print_problem(problem)
                return 1

            for analysis in analyses:
                fix_set(analysis)

            analyses, problems = validate(args.base_ref)

        print_summary(analyses)
        if problems:
            for problem in problems:
                print_problem(problem)
            return 1

        print("Migration version check passed.")
        return 0
    except RuntimeError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
