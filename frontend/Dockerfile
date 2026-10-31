# 前端：Node 构建 → Nginx 托管静态资源
ARG REGISTRY=docker.m.daocloud.io
FROM ${REGISTRY}/library/node:22-alpine AS build
WORKDIR /web
COPY package.json ./
# 依赖层缓存：只改业务代码时不会重装 node_modules
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

FROM ${REGISTRY}/library/nginx:1.27-alpine
# 时区同样要设，否则 Nginx 日志时间与业务时间差 8 小时，排查问题时会误判
ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /web/dist /usr/share/nginx/html
EXPOSE 80
