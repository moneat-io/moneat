#!/bin/bash

# Script to grant promotional credits to organizations
# Usage: ./grant-credits.sh <org_id> <bonus_gb> "<reason>"
# Example: ./grant-credits.sh 42 5.0 "Q1 2026 promotion - +5GB"

set -e

if [ "$#" -lt 3 ]; then
    echo "Usage: $0 <org_id> <bonus_gb> \"<reason>\""
    echo "Example: $0 42 5.0 \"Q1 2026 promotion - +5GB\""
    exit 1
fi

ORG_ID=$1
BONUS_GB=$2
REASON=$3

# Get API URL and admin token from environment or defaults
API_URL=${BACKEND_URL:-"https://api.moneat.io"}
ADMIN_TOKEN=${ADMIN_TOKEN:-""}

if [ -z "$ADMIN_TOKEN" ]; then
    echo "Error: ADMIN_TOKEN environment variable not set"
    echo "Please set it with: export ADMIN_TOKEN=<your_admin_jwt_token>"
    exit 1
fi

echo "Granting +${BONUS_GB}GB promotional credit to organization $ORG_ID"
echo "Reason: $REASON"
echo ""

# Make the API request
RESPONSE=$(curl -s -X POST "${API_URL}/v1/admin/billing/organizations/${ORG_ID}/promotional-credits" \
  -H "Authorization: Bearer ${ADMIN_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{
    \"bonusGb\": ${BONUS_GB},
    \"reason\": \"${REASON}\"
  }")

# Check if request was successful
if echo "$RESPONSE" | grep -q "organizationId"; then
    echo "✅ Success! Promotional credit granted."
    echo ""
    echo "$RESPONSE" | jq '.'
else
    echo "❌ Error granting promotional credit:"
    echo "$RESPONSE"
    exit 1
fi
