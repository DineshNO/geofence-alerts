# geofence-alerts

Geofence alerting service with an operator map console, delivered as an offline bundle.

Work in progress. The architecture, rule table, data findings and evidence will be added here as the system is built.

## Layout

| Folder | Purpose |
|---|---|
| `contract/` | the fixed API contract (OpenAPI) |
| `data/` | reference data (zones) |
| `tools/` | feed replay tool |
| `backend/` | Java and Spring Boot service |
| `frontend/` | React and TypeScript console |
| `deploy/` | compose file, load balancer settings, config templates, backfill SQL, bundle scripts |
| `docs/` | change record, runbook, design notes |
