#!/usr/bin/env bash
set -euo pipefail

required_tools=(
  actionlint
  gitleaks
  hadolint
  shellcheck
  shfmt
)

for tool in "${required_tools[@]}"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Missing required tool: $tool" >&2
    exit 127
  fi
done

read_lines() {
  while IFS= read -r line; do
    printf '%s\n' "$line"
  done
}

workflows=()
while IFS= read -r workflow; do
  workflows+=("$workflow")
done < <(git ls-files '.github/workflows/*.yml' '.github/workflows/*.yaml' | read_lines)
if ((${#workflows[@]} > 0)); then
  actionlint -shellcheck= "${workflows[@]}"
fi

gitleaks git --redact --no-banner --config .gitleaks.toml

shell_files=()
while IFS= read -r shell_file; do
  shell_files+=("$shell_file")
done < <(
  git ls-files |
    grep -E '(^|/)([^/]+[.](sh|bash)|[^/]*sh)$' |
    grep -v -E '(^|/)node_modules/' |
    read_lines
)

if ((${#shell_files[@]} > 0)); then
  shellcheck "${shell_files[@]}"
  shfmt -d -i 2 "${shell_files[@]}"
fi

dockerfiles=()
while IFS= read -r dockerfile; do
  dockerfiles+=("$dockerfile")
done < <(git ls-files 'Dockerfile' '**/Dockerfile' '**/Dockerfile.*' | read_lines)
if ((${#dockerfiles[@]} > 0)); then
  hadolint "${dockerfiles[@]}"
fi
