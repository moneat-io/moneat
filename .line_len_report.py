#!/usr/bin/env python3
"""One-off: report lines > 120 chars with lengths."""
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent
FILES = [
    ROOT / "backend/src/main/kotlin/com/moneat/auth/services/OAuthService.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/billing/services/StripeService.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/config/DemoDataReseeder.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/events/services/EventService.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/logs/services/LogQueryParser.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/logs/services/LogService.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/org/routes/AdminRoutes.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/org/routes/IntegrationRoutes.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/plugins/HTTP.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/logging/MoneatLogAppender.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/logs/routes/LogRoutes.kt",
    ROOT / "backend/src/main/kotlin/com/moneat/config/EnvironmentValidator.kt",
    ROOT / "backend/src/test/kotlin/com/moneat/services/LlmDashboardServiceTest.kt",
    ROOT / "backend/src/test/kotlin/com/moneat/services/LlmServiceExtendedTest.kt",
    ROOT / "backend/src/test/kotlin/com/moneat/services/TransactionServiceTest.kt",
    ROOT / "backend/src/test/kotlin/com/moneat/services/StripeServiceWebhookTest.kt",
    ROOT / "backend/src/test/kotlin/com/moneat/services/PricingTierServiceFeatureFlagsTest.kt",
    ROOT / "backend/src/test/kotlin/com/moneat/demo/DemoDataSeeder.kt",
]


def kind(line: str) -> str:
    t = line.lstrip()
    if t.startswith("import "):
        return "import"
    if t.startswith("package "):
        return "package"
    if t.startswith("//"):
        return "line_comment"
    if t.startswith("/**") or t.startswith("/*") or (t.startswith("*") and not t.startswith("*.")):
        return "block_comment_or_kdoc"
    if '"""' in line or "'''" in line or (line.count('"') >= 2 and ('"""' in line or '"' in t)):
        if t.startswith('"') or t.startswith('"""'):
            return "multiline_string_content"
    if "'" in line and ("INSERT" in line or "SELECT" in line or "arrayElement" in line):
        return "string_literal (SQL or embedded DSL in quotes)"
    return "code / string mix"


def main() -> None:
    for p in FILES:
        if not p.exists():
            print(f"MISSING {p}")
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        hits = [(i, line) for i, line in enumerate(text.splitlines(), 1) if len(line) > 120]
        if not hits:
            continue
        print(f"\n## {p.relative_to(ROOT)} — {len(hits)} line(s)")
        for i, line in hits:
            print(f"  L{i}: {len(line)} chars — {kind(line)}")


if __name__ == "__main__":
    main()
