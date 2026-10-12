# ---- VeriDoc AI :: frontend image ----
# Multi-stage: node:22 builds the Vite bundle, nginx serves the static assets
# and proxies /api to the backend so the browser only ever talks to one origin.

# ---------- build ----------
FROM node:22-alpine AS build
WORKDIR /app

# Dependency install is cached separately from the source: a code-only change
# does not re-download the world.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------- runtime ----------
FROM nginx:1.27-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# nginx:alpine ships busybox wget; it only verifies the SPA is being served.
# 127.0.0.1 avoids busybox wget resolving localhost to IPv6 ::1 first.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]