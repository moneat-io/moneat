#!/usr/bin/env bash
set -euo pipefail

# Script to generate wildcard SSL certificate (*.moneat.io + moneat.io)
# Uses DigitalOcean DNS plugin for automatic validation and renewal

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOMAIN="${1:-moneat.io}"
DO_TOKEN="${DO_API_TOKEN:-}"

echo "🔐 WILDCARD CERTIFICATE SETUP (DigitalOcean DNS)"
echo "Setting up wildcard SSL for: *.${DOMAIN} and ${DOMAIN}"
echo ""

# Check for DigitalOcean API token
if [ -z "$DO_TOKEN" ]; then
  echo "⚠️  DigitalOcean API token required for automated DNS validation."
  echo ""
  echo "To get a token:"
  echo "  1. Go to https://cloud.digitalocean.com/account/api/tokens"
  echo "  2. Create a new token with write access"
  echo "  3. Export it: export DO_API_TOKEN='your-token-here'"
  echo ""
  read -p "Enter your DigitalOcean API token: " DO_TOKEN
  
  if [ -z "$DO_TOKEN" ]; then
    echo "ERROR: API token is required"
    exit 1
  fi
fi

# Create credentials directory and file
CREDS_DIR="$REPO_ROOT/deploy/certbot"
mkdir -p "$CREDS_DIR"
chmod 700 "$CREDS_DIR"

CREDS_FILE="$CREDS_DIR/digitalocean.ini"
cat > "$CREDS_FILE" << EOF
# DigitalOcean API credentials for Certbot DNS plugin
dns_digitalocean_token = $DO_TOKEN
EOF
chmod 600 "$CREDS_FILE"

echo "✅ Credentials file created: $CREDS_FILE"
echo ""

# Ensure nginx is running
cd "$REPO_ROOT"
docker compose -f docker-compose.prod.yml up -d nginx

# Request wildcard certificate using DigitalOcean DNS plugin
echo "Requesting wildcard SSL certificate from Let's Encrypt..."
echo "This will automatically validate via DigitalOcean DNS API..."
echo ""

docker run --rm \
  -v certbot_conf:/etc/letsencrypt \
  -v "$CREDS_FILE:/credentials.ini:ro" \
  certbot/dns-digitalocean:latest \
  certonly \
  --dns-digitalocean \
  --dns-digitalocean-credentials /credentials.ini \
  --dns-digitalocean-propagation-seconds 60 \
  --email admin@${DOMAIN} \
  --agree-tos \
  --no-eff-email \
  -d ${DOMAIN} \
  -d "*.${DOMAIN}"

# Verify certificate exists
if ! docker run --rm -v certbot_conf:/etc/letsencrypt:ro alpine test -f /etc/letsencrypt/live/${DOMAIN}/fullchain.pem; then
  echo "ERROR: Certificate generation failed"
  exit 1
fi

echo ""
echo "✅ Wildcard certificate generated successfully!"
echo ""
echo "Certificate will auto-renew via the certbot container."
echo "The credentials file is stored at: $CREDS_FILE"
echo ""
echo "Next steps:"
echo "  1. Update nginx config to use the wildcard cert for all subdomains"
echo "  2. Reload nginx: docker exec moneat-nginx nginx -s reload"
echo ""
echo "To manually renew: docker run --rm -v certbot_conf:/etc/letsencrypt -v $CREDS_FILE:/credentials.ini:ro certbot/dns-digitalocean renew"
