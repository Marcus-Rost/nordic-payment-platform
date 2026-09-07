# JRPay Admin Web — multi-stage production image.
#
# Stage 1 — install + build the Vite SPA on Node 20.
# Stage 2 — serve the static bundle through nginx 1.27-alpine, with the
#           reverse-proxy + CSP config from docker/admin-web/nginx.conf.
#
# Build context for compose is `..` so we can copy package.json before src
# and benefit from the layer cache when only application code changes.
FROM node:20-alpine AS build
WORKDIR /app

# Install deps using the lockfile when present, otherwise a plain install.
COPY admin-web/package.json admin-web/package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; \
    else npm install --no-audit --no-fund; fi

COPY admin-web/ ./
# Strip the prepare-script generated typecheck (already covered in CI) and
# build the SPA. tsc is invoked through `npm run build`.
RUN npm run build

FROM nginx:1.27-alpine AS runtime
RUN apk add --no-cache wget
COPY --from=build /app/dist /usr/share/nginx/html
COPY docker/admin-web/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD wget -qO- http://localhost/healthz || exit 1
