# Test report

Generated on 22 November 2026 from the commit that contains this file. Run the tests yourself with:

```
(cd backend && ./mvnw clean verify)          # needs Docker: the tests start PostgreSQL 16.16 in a container
(cd frontend && npm ci && npm test)
```

## Backend: 273 tests, 0 failures, 0 errors, 0 skipped

`./mvnw clean verify` ended with `Tests run: 273, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS`.
The integration tests run against a real PostgreSQL started by Testcontainers (the image the deployment uses),
including parallel requests and two backend instances against one database.

| Test class | Tests | Failed | Skipped | Seconds |
|---|---|---|---|---|
| `MigrateMainTest` | 1 | 0 | 0 | 1.9 |
| `alerts.AlertCommandTest` | 18 | 0 | 0 | 3.1 |
| `alerts.AlertCreationTest` | 14 | 0 | 0 | 0.9 |
| `alerts.AuditTrailTest` | 11 | 0 | 0 | 4.5 |
| `alerts.domain.AlertRulesTest` | 11 | 0 | 0 | 0.0 |
| `api.ReadApiTest` | 21 | 0 | 0 | 2.5 |
| `api.TrackSourceTypeApiTest` | 2 | 0 | 0 | 0.3 |
| `api.ZoneAndRejectionApiTest` | 9 | 0 | 0 | 1.1 |
| `assets.SourceTypeTest` | 3 | 0 | 0 | 0.0 |
| `auth.AuthenticationTest` | 11 | 0 | 0 | 6.4 |
| `common.web.ProblemExceptionHandlerTest` | 4 | 0 | 0 | 0.1 |
| `common.web.RequestIdFilterTest` | 4 | 0 | 0 | 0.1 |
| `db.AlertSchemaTest` | 6 | 0 | 0 | 0.0 |
| `db.Cr1SqlStepsTest` | 1 | 0 | 0 | 0.7 |
| `db.IngestSchemaTest` | 4 | 0 | 0 | 0.0 |
| `health.HealthControllerTest` | 1 | 0 | 0 | 0.1 |
| `ingest.BatchFailurePolicyTest` | 8 | 0 | 0 | 2.3 |
| `ingest.DeadlockFreeIngestTest` | 1 | 0 | 0 | 0.5 |
| `ingest.IngestApiTest` | 17 | 0 | 0 | 1.3 |
| `ingest.RuleWiringTest` | 1 | 0 | 0 | 1.4 |
| `ingest.SourceTypeWriteTest` | 1 | 0 | 0 | 0.1 |
| `ingest.domain.LineRulePipelineTest` | 5 | 0 | 0 | 0.0 |
| `ingest.domain.LineValidatorTest` | 80 | 0 | 0 | 0.1 |
| `positions.PositionOrderTest` | 10 | 0 | 0 | 0.7 |
| `positions.ZoneMembershipTest` | 6 | 0 | 0 | 0.4 |
| `stream.AlertStreamTest` | 9 | 0 | 0 | 11.2 |
| `zones.adapter.JtsZoneCatalogTest` | 14 | 0 | 0 | 0.1 |

Which tests prove which rule is in the README ("The six rules"). Per-class reports with every test name are written to
`backend/target/surefire-reports/` by the command above.

## Console: 78 tests, 0 failures

```
✓ src/map/format.test.ts (3 tests) 2ms
 ✓ src/alerts/cache.test.ts (4 tests) 2ms
 ✓ src/alerts/sse.test.ts (8 tests) 2ms
 ✓ src/alerts/batcher.test.ts (4 tests) 3ms
 ✓ src/map/viewport.test.ts (11 tests) 3ms
 ✓ src/a11y/announce.test.ts (5 tests) 5ms
 ✓ src/api/client.test.ts (7 tests) 5ms
 ✓ src/alerts/explain.test.ts (3 tests) 2ms
 ✓ src/map/AssetDetail.test.tsx (3 tests) 47ms
 ✓ src/zones/ZonesView.test.tsx (5 tests) 67ms
 ✓ src/alerts/AlertRow.test.tsx (6 tests) 78ms
 ✓ src/state/session.test.tsx (8 tests) 145ms
 ✓ src/alerts/liveStream.test.ts (11 tests) 269ms
 Test Files  13 passed (13)
      Tests  78 passed (78)
   Duration  901ms (transform 526ms, setup 0ms, collect 1.16s, tests 631ms, environment 5.47s, prepare 521ms)
```

Type checking (`npx tsc --noEmit`, strict mode) reports no errors.
