# 多阶段构建：先编前端 + 后端，再装一个精简运行镜像。
# 用法：
#   docker build -f deploy/Dockerfile -t mc-sync-server .
#   docker run -d --name mc-sync-server -p 38080:38080 \
#     -v /etc/mc-sync:/config:ro -v /var/lib/mc-sync:/data \
#     -v /srv/mc-sync/repo:/repo \
#     -v /opt/mcsmanager/daemon/data/InstanceData/<uuid>:/mcs-instance \
#     mc-sync-server

FROM node:20-alpine AS build
WORKDIR /src
COPY packages/server/package.json packages/server/package-lock.json* ./packages/server/
RUN npm --prefix packages/server ci --omit=dev=false || npm --prefix packages/server install
COPY packages/server ./packages/server
RUN npm --prefix packages/server run build

COPY packages/web/package.json packages/web/package-lock.json* ./packages/web/
RUN npm --prefix packages/web install
COPY packages/web ./packages/web
RUN npm --prefix packages/web run build

FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /src/packages/server/package.json ./package.json
COPY --from=build /src/packages/server/node_modules ./node_modules
COPY --from=build /src/packages/server/dist ./dist
COPY --from=build /src/packages/web/dist ./web
EXPOSE 38080
CMD ["node", "dist/server.js", "--config", "/config/config.json"]
