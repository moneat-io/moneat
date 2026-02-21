---
"moneat-backend": minor
"moneat-enterprise": minor
---

Migrate analytics ingest to enterprise module

- **Moneat (edn)**: Remove analytics ingest routes, GeoIpService, UserAgentParserService from core backend. Analytics ingestion is now an enterprise-only feature. Remove ua-parser and geoip2 dependencies from core.
- **Moneat Enterprise**: Analytics ingest was already in enterprise; added domain-based route `/api/{domain}/analytics/event` for SDK/script tag compatibility (lookup project by sentry_key). Extracted shared `processAndEnqueueEvent()` for both domain and projectId routes. The tracking script sends to this URL shape.
