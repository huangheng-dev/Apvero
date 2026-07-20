FROM node:24-alpine AS build
RUN corepack enable
WORKDIR /workspace
COPY package.json pnpm-workspace.yaml pnpm-lock.yaml ./
COPY apps/console/package.json apps/console/package.json
RUN pnpm install --frozen-lockfile
COPY apps/console apps/console
RUN pnpm --filter @apvero/console build

FROM nginx:1.29-alpine
LABEL org.opencontainers.image.title="Apvero Console"
LABEL org.opencontainers.image.source="https://github.com/huangheng-dev/Apvero"
LABEL org.opencontainers.image.licenses="Apache-2.0"
COPY deploy/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/apps/console/dist /usr/share/nginx/html
EXPOSE 3000
