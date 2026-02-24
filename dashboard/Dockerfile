# syntax=docker/dockerfile:1.21
FROM node:24-alpine AS docs-build
WORKDIR /app
COPY docs/package.json docs/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline --no-audit
COPY docs/ .
RUN npm run build

FROM node:24-alpine AS dashboard-build
WORKDIR /app
COPY dashboard/package.json dashboard/package-lock.json dashboard/.npmrc ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline --no-audit
COPY dashboard/ .
# Overlay enterprise routes/components when present in build context (used by moneat-deploy CI/CD)
RUN --mount=type=bind,source=.,target=/repo,readonly \
    if [ -d /repo/enterprise/dashboard/src ]; then \
      for path in routes components/on-call components/sso-settings.tsx; do \
        src="/repo/enterprise/dashboard/src/${path}"; \
        dst="./src/${path}"; \
        case "${path}" in \
          *.tsx) \
            [ -f "${src}" ] && mkdir -p "$(dirname "${dst}")" && cp "${src}" "${dst}" ;; \
          *) \
            [ -d "${src}" ] && mkdir -p "${dst}" && cp -r "${src}/." "${dst}/" ;; \
        esac; \
      done; \
    fi
COPY --from=docs-build /app/build ./public/docs
ARG VITE_BACKEND_URL=""
ARG VITE_API_URL=""
ARG VITE_SENTRY_DSN=""
ARG VITE_SENTRY_ENVIRONMENT="production"
ARG VITE_SENTRY_TRACES_SAMPLE_RATE="0.1"
ARG VITE_ANALYTICS_KEY=""
ENV VITE_BACKEND_URL=$VITE_BACKEND_URL
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_SENTRY_DSN=$VITE_SENTRY_DSN
ENV VITE_SENTRY_ENVIRONMENT=$VITE_SENTRY_ENVIRONMENT
ENV VITE_SENTRY_TRACES_SAMPLE_RATE=$VITE_SENTRY_TRACES_SAMPLE_RATE
ENV VITE_ANALYTICS_KEY=$VITE_ANALYTICS_KEY
RUN npx vite build

FROM nginx:alpine
COPY --from=dashboard-build /app/dist /usr/share/nginx/html
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
