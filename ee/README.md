# Moneat Enterprise (`ee/`)

This directory contains the enterprise modules for Moneat, licensed under the [Moneat Enterprise License](LICENSE). Source is visible but use requires a valid license key.

## Enterprise Features

| Module | License Feature | Description |
|--------|----------------|-------------|
| SAML SSO & Enforcement | `sso` | SAML 2.0 single sign-on and SSO enforcement (Require SSO) |
| On-Call | `oncall` | On-call scheduling, escalation policies, and incident management |

All other features (Datadog ingestion, AI assistant, Analytics, MCP core, Synthetics, OIDC SSO, etc.) are open-source and always available without a license key.

---

## License Keys

License keys are RSA-signed tokens. The app validates them **locally** using an embedded public key — no network call required. A valid key proves it was issued by Moneat (only Moneat holds the private key).

### Key format

```
<base64url(payload_json)>.<base64url(RSA-SHA256 signature)>
```

Payload fields:

```json
{
  "customer": "Acme Corp",
  "plan": "enterprise",
  "features": ["sso", "oncall"],
  "issuedAt": "2026-03-04",
  "expiresAt": "2027-12-31"
}
```

- `features` controls which licensed modules activate. Issue a key with only `["sso"]` to give a customer SSO without On-Call.
- `expiresAt` is optional — omit it for a perpetual license.

---

## Issuing a License Key

### Signing a key

```bash
./scripts/sign-license.sh \
  --key ~/moneat-license-private.pem \
  --customer "Acme Corp" \
  --expires 2027-12-31
```

**Options:**

| Flag | Required | Description |
|------|----------|-------------|
| `--key` | ✅ | Path to the private key PEM file |
| `--customer` | ✅ | Customer name (informational, embedded in key) |
| `--plan` | | Plan name. Defaults to `enterprise`. |
| `--features` | | Comma-separated feature names: `sso`, `oncall`, or `sso,oncall`. Defaults to all licensed features (`sso,oncall`). |
| `--expires` | | Expiry date `yyyy-MM-dd`. Omit for no expiry. |

Output is the license key string. Give it to the customer to set as:

```bash
MONEAT_LICENSE_KEY=eyJ...
```

### Revoking a key

There is no revocation list — once issued, a key is valid until its `expiresAt` date. To effectively revoke:

- Issue a **replacement key** with an earlier expiry date and have the customer update their `MONEAT_LICENSE_KEY`.
- If the key has no expiry, issue a new one expiring today and ask the customer to update.

For urgent cases (e.g. payment failure), issuing a short-lived replacement key and coordinating with the customer is the intended flow.

---

## Environment Variables

### Enterprise modules

| Variable | Module | Description |
|----------|--------|-------------|
| `MONEAT_LICENSE_KEY` | All | RSA-signed license key. Without this, SSO and On-Call are disabled. |

### SSO module

| Variable | Required | Description |
|----------|----------|-------------|
| `SSO_SP_ENTITY_ID` | SAML | Your service provider entity ID |
| `SSO_ACS_URL` | SAML | Assertion consumer service URL |

OIDC SSO variables (`OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_ISSUER_URL`) are now part of the open-source core and configured via the dashboard under Settings > SSO.

### On-Call module

| Variable | Required | Description |
|----------|----------|-------------|
| `TWILIO_ACCOUNT_SID` | SMS/Voice | Twilio account SID |
| `TWILIO_AUTH_TOKEN` | SMS/Voice | Twilio auth token |
| `TWILIO_FROM_NUMBER` | SMS/Voice | Twilio phone number for outbound alerts |
| `SLACK_CLIENT_ID` | Slack | Slack app client ID (for on-call Slack integration) |
| `SLACK_CLIENT_SECRET` | Slack | Slack app client secret |

---

## Development

The `ee/` directory is a Gradle subproject included by `backend/settings.gradle.kts`. It depends on the core `:` project and is included as a `runtimeOnly` dependency in the core build, so enterprise classes are available at runtime but not referenced at compile time from core (except for the `FeatureRegistry`/`EnterpriseModule` interface which is in core).

To build just the enterprise module:

```bash
cd backend
./gradlew :ee:jar
```

To run the full build including ee/:

```bash
cd backend
./gradlew shadowJar
```
