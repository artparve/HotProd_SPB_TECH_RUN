# syntax=docker/dockerfile:1
ARG NODE_IMAGE=node:22-alpine
ARG NGINX_IMAGE=nginx:1.28-alpine
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-fund
COPY frontend .
ARG VITE_DATA_MODE=api
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_DATA_MODE=$VITE_DATA_MODE VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM ${NGINX_IMAGE} AS production
COPY docker/nginx/nginx.conf /etc/nginx/nginx.conf
COPY docker/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
# envsubst writes into writable tmpfs; static files and root filesystem stay read-only.
ENV NGINX_ENVSUBST_OUTPUT_DIR=/tmp NGINX_ENVSUBST_FILTER="^(API_UPSTREAM|FRONTEND_PORT|API_CONNECT_TIMEOUT|API_READ_TIMEOUT|API_MAX_BODY_SIZE|DNS_RESOLVER)$" API_UPSTREAM=http://127.0.0.1:9 FRONTEND_PORT=8080 API_CONNECT_TIMEOUT=3s API_READ_TIMEOUT=1265s API_MAX_BODY_SIZE=12m DNS_RESOLVER=127.0.0.11
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
CMD ["nginx", "-g", "daemon off;"]
