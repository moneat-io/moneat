#!/usr/bin/env bash
set -euo pipefail

# Script to generate SSL certificates and enable HTTPS
# Run this AFTER initial deployment completes successfully

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOMAIN="${1:-moneat.io}"

echo "Setting up SSL for domain: $DOMAIN"

# 1. Ensure nginx and certbot are running
cd "$REPO_ROOT"
docker compose -f docker-compose.prod.yml up -d nginx certbot

# 2. Request certificate
echo "Requesting SSL certificate from Let's Encrypt..."
docker compose -f docker-compose.prod.yml run --rm certbot certonly \
  --webroot \
  --webroot-path=/var/www/certbot \
  --email admin@${DOMAIN} \
  --agree-tos \
  --no-eff-email \
  -d ${DOMAIN} \
  -d www.${DOMAIN}

# 3. Verify certificate exists
if ! docker exec moneat-nginx test -f /etc/letsencrypt/live/${DOMAIN}/fullchain.pem; then
  echo "ERROR: Certificate generation failed"
  exit 1
fi

echo "Certificate generated successfully!"

# 4. Enable HTTPS in nginx config
echo "Enabling HTTPS..."
HTTPS_CONF="$REPO_ROOT/deploy/nginx/conf.d/https.conf"

cat > "$HTTPS_CONF" << 'EOF'
# HTTPS server
server {
    listen 443 ssl http2;
    server_name _;

    ssl_certificate /etc/letsencrypt/live/moneat.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/moneat.io/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_prefer_server_ciphers off;

    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-XSS-Protection "1; mode=block" always;

    location /api/ {
        limit_req zone=api_limit burst=20 nodelay;
        proxy_pass http://backend_upstream;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    location /auth/ {
        limit_req zone=api_limit burst=20 nodelay;
        proxy_pass http://backend_upstream;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_connect_timeout 60s;
        proxy_send_timeout 60s;
        proxy_read_timeout 60s;
    }

    location / {
        proxy_pass http://dashboard_upstream;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
EOF

# 5. Enable HTTP -> HTTPS redirect in main app.conf
sed -i 's|location / {|location / {\n        return 301 https://$host$request_uri;\n    }\n\n    location /_disabled_no_ssl {|' "$REPO_ROOT/deploy/nginx/conf.d/app.conf"

# 6. Test nginx config
echo "Testing nginx configuration..."
docker exec moneat-nginx nginx -t

# 7. Reload nginx
echo "Reloading nginx..."
docker exec moneat-nginx nginx -s reload

echo "✅ SSL setup complete! Your site is now available at https://${DOMAIN}"
