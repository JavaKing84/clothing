# 多阶段构建：编译在带 Maven+JDK 的镜像里做，运行镜像只留 JRE
# 单阶段构建会让镜像里带上整个 Maven 仓库（~300MB），运维拉取时间全浪费在无用的 jar 上
ARG REGISTRY=docker.m.daocloud.io
FROM ${REGISTRY}/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 先只拷 pom：依赖层可以缓存，改一行 Java 代码不必重下所有依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM ${REGISTRY}/library/eclipse-temurin:21-jre
WORKDIR /app
# 时区必须显式设置：容器默认 UTC，业务里"今天的营业额"会算到昨天去
ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone
COPY --from=build /src/target/*.jar app.jar
EXPOSE 8080
# exec 形式让 JVM 成为 PID 1，docker stop 的 SIGTERM 才能触发优雅停机（等在途请求结束）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
