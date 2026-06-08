#!/usr/bin/env bash
# sign-license.sh — Issue a signed Moneat license key.
#
# Usage:
#   ./scripts/sign-license.sh --key <private.pem> --customer <name> \
#       [--plan <plan>] [--features <feature1,feature2>] [--expires <yyyy-MM-dd>]
#
# Output:
#   Prints the license key to stdout. Give this string to the customer as
#   their MONEAT_LICENSE_KEY value.
#
# Examples:
#   # All features, enterprise plan (defaults), expires end of 2027
#   ./scripts/sign-license.sh \
#       --key ~/moneat-license-private.pem \
#       --customer "Acme Corp" \
#       --expires 2027-12-31
#
#   # On-Call only, pro plan, no expiry
#   ./scripts/sign-license.sh \
#       --key ~/moneat-license-private.pem \
#       --customer "Startup Inc" \
#       --plan pro \
#       --features oncall
#
# Features:
#   sso                 — SAML/OIDC single sign-on
#   oncall              — On-call scheduling and incident management
#   advanced_rbac       — Fine-grained role-based access control
#   workflows_advanced  — Advanced workflow automation
#
# Keep the private key offline and secure. Never commit it to any repository.
set -euo pipefail

PRIVATE_KEY=""
CUSTOMER=""
PLAN=""
FEATURES=""
EXPIRES=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --key)       PRIVATE_KEY="$2"; shift 2 ;;
    --customer)  CUSTOMER="$2";    shift 2 ;;
    --plan)      PLAN="$2";        shift 2 ;;
    --features)  FEATURES="$2";    shift 2 ;;
    --expires)   EXPIRES="$2";     shift 2 ;;
    *) echo "Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Defaults
[[ -z "$PLAN" ]] && PLAN="enterprise"
[[ -z "$FEATURES" ]] && FEATURES="sso,oncall,advanced_rbac,workflows_advanced"

# Validate required args
missing=()
[[ -z "$PRIVATE_KEY" ]] && missing+=("--key")
[[ -z "$CUSTOMER" ]]    && missing+=("--customer")
if [[ ${#missing[@]} -gt 0 ]]; then
  echo "Missing required arguments: ${missing[*]}" >&2
  exit 1
fi

if [[ ! -f "$PRIVATE_KEY" ]]; then
  echo "Private key not found: $PRIVATE_KEY" >&2
  exit 1
fi

# Validate date format if provided
if [[ -n "$EXPIRES" ]] && ! date -j -f "%Y-%m-%d" "$EXPIRES" > /dev/null 2>&1; then
  # Try GNU date as fallback (Linux)
  if ! date -d "$EXPIRES" > /dev/null 2>&1; then
    echo "Invalid --expires date (expected yyyy-MM-dd): $EXPIRES" >&2
    exit 1
  fi
fi

# Build feature JSON array from comma-separated list
FEATURES_JSON=$(echo "$FEATURES" | tr ',' '\n' | sed 's/^[[:space:]]*//' | sed 's/[[:space:]]*$//' | jq -R . | jq -sc .)

ISSUED_AT=$(date -u +"%Y-%m-%d")

# Build payload JSON
if [[ -n "$EXPIRES" ]]; then
  PAYLOAD_JSON=$(jq -cn \
    --arg customer  "$CUSTOMER" \
    --arg plan      "$PLAN" \
    --arg issuedAt  "$ISSUED_AT" \
    --arg expiresAt "$EXPIRES" \
    --argjson features "$FEATURES_JSON" \
    '{customer: $customer, plan: $plan, features: $features, issuedAt: $issuedAt, expiresAt: $expiresAt}')
else
  PAYLOAD_JSON=$(jq -cn \
    --arg customer "$CUSTOMER" \
    --arg plan     "$PLAN" \
    --arg issuedAt "$ISSUED_AT" \
    --argjson features "$FEATURES_JSON" \
    '{customer: $customer, plan: $plan, features: $features, issuedAt: $issuedAt}')
fi

# base64url-encode the payload (no padding, url-safe)
ENCODED_PAYLOAD=$(printf '%s' "$PAYLOAD_JSON" | openssl base64 -A | tr '+/' '-_' | tr -d '=')

# Sign the encoded payload with RSA-SHA256
SIGNATURE=$(printf '%s' "$ENCODED_PAYLOAD" | openssl dgst -sha256 -sign "$PRIVATE_KEY" | openssl base64 -A | tr '+/' '-_' | tr -d '=')

LICENSE_KEY="${ENCODED_PAYLOAD}.${SIGNATURE}"

echo ""
echo "✅  License key for: $CUSTOMER ($PLAN) — features: $FEATURES${EXPIRES:+ — expires: $EXPIRES}"
echo ""
echo "$LICENSE_KEY"
echo ""
echo "Set this as MONEAT_LICENSE_KEY in the customer's environment."
