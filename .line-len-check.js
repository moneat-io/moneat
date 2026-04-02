const fs = require("fs");
const base =
  "/Users/aelder/.auto-agent/worktrees/Moneat/fix-issue-279/backend/src/main/kotlin/com/moneat";
const checks = [
  ["billing/services/PricingTierService.kt", [257, 423]],
  ["config/SentryConfig.kt", [40, 43, 47, 48]],
  ["dashboards/services/handlers/GraphiteHandler.kt", [120]],
  ["dashboards/services/handlers/JdbcHandler.kt", [124]],
  ["dashboards/services/handlers/MongoDBHandler.kt", [56, 75, 106]],
  ["datadog/decompression/ProcessAgentPayloadDecoder.kt", [117, 193]],
  ["datadog/security/SecurityQueryRoutes.kt", [245, 261]],
  ["datadog/services/TraceIngestionService.kt", null],
  ["events/routes/ApiRoutes.kt", [1258, 1325]],
  ["events/services/ProjectStatsService.kt", [64, 335, 338, 391]],
  ["notifications/services/NotificationService.kt", [447, 523]],
  ["org/services/AdminService.kt", [657]],
];
for (const [rel, nums] of checks) {
  const p = `${base}/${rel}`;
  const lines = fs.readFileSync(p, "utf8").split(/\r?\n/);
  if (nums == null) {
    const long = lines
      .map((L, i) => ({ n: i + 1, len: L.length }))
      .filter((x) => x.len > 120);
    console.log(
      rel,
      long.length ? long.map((x) => `${x.n}:${x.len}`).join(", ") : "no line >120"
    );
    continue;
  }
  for (const n of nums) {
    const L = lines[n - 1] ?? "";
    const pass = L.length <= 120 ? "PASS" : "FAIL";
    console.log(`${rel}\t${n}\t${L.length}\t${pass}`);
  }
}
