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
COPY --from=docs-build /app/build ./public/docs
ARG VITE_BACKEND_URL=""
ARG VITE_API_URL=""
ENV VITE_BACKEND_URL=$VITE_BACKEND_URL
ENV VITE_API_URL=$VITE_API_URL
RUN npx vite build

FROM nginx:alpine
COPY --from=dashboard-build /app/dist /usr/share/nginx/html
COPY dashboard/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
