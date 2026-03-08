#!/bin/sh
set -e

JS_DIR=/usr/share/nginx/html

find "$JS_DIR" -name '*.js' -exec sed -i \
  -e "s|__MONEAT_BACKEND_URL__|${VITE_BACKEND_URL:-}|g" \
  -e "s|__MONEAT_API_URL__|${VITE_API_URL:-}|g" \
  -e "s|__MONEAT_SENTRY_DSN__|${VITE_SENTRY_DSN:-}|g" \
  -e "s|__MONEAT_SENTRY_ENVIRONMENT__|${VITE_SENTRY_ENVIRONMENT:-production}|g" \
  -e "s|__MONEAT_SENTRY_TRACES_SAMPLE_RATE__|${VITE_SENTRY_TRACES_SAMPLE_RATE:-0.1}|g" \
  -e "s|__MONEAT_ANALYTICS_KEY__|${VITE_ANALYTICS_KEY:-}|g" \
  -e "s|__MONEAT_DD_APPLICATION_ID__|${VITE_DD_APPLICATION_ID:-}|g" \
  -e "s|__MONEAT_DD_CLIENT_TOKEN__|${VITE_DD_CLIENT_TOKEN:-}|g" \
  -e "s|__MONEAT_DD_PROXY_URL__|${VITE_DD_PROXY_URL:-}|g" \
  -e "s|__MONEAT_DD_SERVICE__|${VITE_DD_SERVICE:-moneat-dashboard}|g" \
  -e "s|__MONEAT_DD_ENV__|${VITE_DD_ENV:-production}|g" \
  {} +

exec nginx -g 'daemon off;'
