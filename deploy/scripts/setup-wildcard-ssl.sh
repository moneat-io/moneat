#!/usr/bin/env bash
set -euo pipefail

# Script to generate wildcard SSL certificate (*.moneat.io + moneat.io)
# Requires DNS challenge via Certbot DNS plugin

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOMAIN="${1:-moneat.io}"

echo "⚠️  WILDCARD CERTIFICATE SETUP"
echo "This requires DNS validation. You'll need to:"
echo "  1. Add TXT records to your DNS when prompted"
echo "  2. Wait for DNS propagation before continuing"
echo ""
echo "Setting up wildcard SSL for: *.${DOMAIN} and ${DOMAIN}"
echo ""
read -p "Press Enter to continue or Ctrl+C to cancel..."

# Ensure nginx and certbot are running
cd "$REPO_ROOT"
docker compose -f docker-compose.prod.yml up -d nginx certbot

# Request wildcard certificate using manual DNS challenge
echo "Requesting wildcard SSL certificate from Let's Encrypt..."
echo "⚠️  You will be asked to create DNS TXT records. Follow the instructions carefully."
echo ""

docker compose -f docker-compose.prod.yml run --rm certbot certonly \
  --manual \
  --preferred-challenges dns \
  --email admin@${DOMAIN} \
  --agree-tos \
  --no-eff-email \
  -d ${DOMAIN} \
  -d "*.${DOMAIN}"

# Verify certificate exists
if ! docker exec moneat-nginx test -f /etc/letsencrypt/live/${DOMAIN}/fullchain.pem; then
  echo "ERROR: Certificate generation failed"
  exit 1
fi

echo ""
echo "✅ Wildcard certificate generated successfully!"
echo ""
echo "Next steps:"
echo "  1. Update nginx config to use the wildcard cert for all subdomains"
echo "  2. Reload nginx: docker exec moneat-nginx nginx -s reload"
echo ""
echo "⚠️  IMPORTANT: Wildcard certs via manual DNS challenge cannot auto-renew."
echo "    Consider using a DNS plugin (e.g., certbot-dns-digitalocean) for automation."
