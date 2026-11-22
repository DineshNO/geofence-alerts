# The console: the built static files served by a small non-root nginx, for linux/amd64.
# Build the files first (npm ci && npm run build), then from the repository root:
#   docker build -f frontend/Dockerfile -t geoalert/console:VERSION .
FROM --platform=linux/amd64 nginxinc/nginx-unprivileged:1.31.7-alpine3.24

COPY --chown=101:101 frontend/dist /usr/share/nginx/html
COPY --chown=101:101 frontend/nginx.conf /etc/nginx/conf.d/default.conf
