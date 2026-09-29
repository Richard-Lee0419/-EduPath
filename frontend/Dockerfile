FROM node:22-alpine AS build

WORKDIR /app
ARG NPM_CONFIG_REGISTRY=https://registry.npmjs.org/
COPY package*.json ./
RUN npm ci --registry="${NPM_CONFIG_REGISTRY}"

COPY . .
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run test:real-api && npm run build

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
