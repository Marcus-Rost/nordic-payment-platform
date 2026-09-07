# JRPay Client Web — multi-stage production image (mirrors admin-web).
#
# Stage 1 — install + build the Vite SPA on Node 20.
# Stage 2 — serve the static bundle through nginx 1.27-alpine, with the
#           reverse-proxy + CSP config from docker/client-web/nginx.conf.

FROM node:20-alpine AS build
WORKDIR /app

COPY client-web/package.json client-web/package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; \
    else npm install --no-audit --no-fund; fi

COPY client-web/ ./
RUN npm run build

FROM nginx:1.27-alpine AS runtime
RUN apk add --no-cache wget
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/client-web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost/healthz || exit 1
