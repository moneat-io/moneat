# syntax=docker/dockerfile:1.22
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
COPY --from=docs-build /app/build ./public/docs
ARG VITE_BACKEND_URL=""
ARG VITE_API_URL=""
ARG VITE_SENTRY_DSN=""
ARG VITE_SENTRY_ENVIRONMENT="production"
ARG VITE_SENTRY_TRACES_SAMPLE_RATE="0.1"
ARG VITE_ANALYTICS_KEY=""
ARG VITE_DD_APPLICATION_ID=""
ARG VITE_DD_CLIENT_TOKEN=""
ARG VITE_DD_PROXY_URL=""
ARG VITE_DD_SERVICE="moneat-dashboard"
ARG VITE_DD_ENV="production"
ENV VITE_BACKEND_URL=$VITE_BACKEND_URL
ENV VITE_API_URL=$VITE_API_URL
ENV VITE_SENTRY_DSN=$VITE_SENTRY_DSN
ENV VITE_SENTRY_ENVIRONMENT=$VITE_SENTRY_ENVIRONMENT
ENV VITE_SENTRY_TRACES_SAMPLE_RATE=$VITE_SENTRY_TRACES_SAMPLE_RATE
ENV VITE_ANALYTICS_KEY=$VITE_ANALYTICS_KEY
ENV VITE_DD_APPLICATION_ID=$VITE_DD_APPLICATION_ID
ENV VITE_DD_CLIENT_TOKEN=$VITE_DD_CLIENT_TOKEN
ENV VITE_DD_PROXY_URL=$VITE_DD_PROXY_URL
ENV VITE_DD_SERVICE=$VITE_DD_SERVICE
ENV VITE_DD_ENV=$VITE_DD_ENV
RUN npx vite build

FROM nginx:alpine
COPY --from=dashboard-build /app/dist /usr/share/nginx/html
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
