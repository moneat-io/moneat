#!/usr/bin/env python3
import pathlib
root = pathlib.Path(__file__).resolve().parent
files = [
    "backend/src/test/kotlin/com/moneat/demo/DemoDataSeeder.kt",
    "backend/src/test/kotlin/com/moneat/services/LlmDashboardServiceTest.kt",
    "backend/src/test/kotlin/com/moneat/services/LlmServiceExtendedTest.kt",
    "backend/src/test/kotlin/com/moneat/services/TransactionServiceTest.kt",
]
for rel in files:
    p = root / rel
    print("===", rel)
    for i, line in enumerate(p.read_text().splitlines(), 1):
        if len(line) > 120:
            print(i, len(line))
