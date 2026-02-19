# syntax=docker/dockerfile:1.7
FROM node:20-alpine AS docs-build
WORKDIR /app
COPY docs/package.json docs/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline --no-audit
COPY docs/ .
RUN npm run build

FROM node:20-alpine AS dashboard-build
WORKDIR /app
COPY dashboard/package.json dashboard/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --prefer-offline --no-audit
COPY dashboard/ .
# Copy enterprise routes and components (always included in the dashboard image)
COPY enterprise/dashboard/src/routes/ ./src/routes/
COPY enterprise/dashboard/src/components/ ./src/components-enterprise/
RUN cp -r ./src/components-enterprise/analytics/ ./src/components/analytics/ && \
    cp -r ./src/components-enterprise/on-call/ ./src/components/on-call/ && \
    cp ./src/components-enterprise/sso-settings.tsx ./src/components/sso-settings.tsx && \
    rm -rf ./src/components-enterprise
COPY --from=docs-build /app/build ./public/docs
ARG VITE_BACKEND_URL=""
ARG VITE_API_URL=""
ENV VITE_BACKEND_URL=$VITE_BACKEND_URL
ENV VITE_API_URL=$VITE_API_URL
# Routes already synced via COPY above; skip the sync script and run vite build directly
RUN npx vite build

FROM nginx:alpine
COPY --from=dashboard-build /app/dist /usr/share/nginx/html
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
