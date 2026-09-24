# U26 容器化：两阶段构建。
#
# 阶段一用 Maven + JDK 编译；阶段二只放 JRE 与 jar。
# 为什么不一个阶段搞定：编译要 JDK、要完整的 m2 缓存（几百 MB），而运行只需要 JRE。
# 中间那些东西一旦进最终镜像，它就不只是"大了"——它让镜像里多出成千上万个文件，
# 每一个都是一次潜在的"从镜像里捞出来一个旧版本的库"。
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# 先只拷 POM：依赖层可以被缓存，改一行 Java 代码不必重下整个依赖树
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app

# 非 root：这个服务能执行 shell。以 root 跑等于"容器里拿到一个能改自己文件的 shell"。
# 仍然不是沙箱（真正的隔离在 Sandbox 那一层），但这是最低的一道门。
# healthcheck 要用 curl，而 JRE 基础镜像**不保证**带它——不显式装的话，
# 健康检查会以"command not found"失败，而那个失败看起来像"服务不健康"。
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -r -u 10001 -m aplat
COPY --from=build /src/target/agent-platform-*.jar /app/app.jar
RUN chown -R aplat:aplat /app
USER aplat

# 只监听回环**不是**容器里的默认行为（容器内必须听 0.0.0.0 才能被映射进来），
# 所以这里的隔离靠"不发布端口 + 由编排层决定暴露面"，见 docker-compose.yml 的注释。
ENV APLAT_HTTP_HOST=0.0.0.0 \
    APLAT_HTTP_PORT=8787 \
    APLAT_HITL=ask

EXPOSE 8787

# 健康检查直接打 /health：它包含 store 与 hitl 的真实状态。
# 用"端口通不通"当健康检查是不够的——服务可能已经起来但连不上数据库，
# 而那种状态在编排层看来是"健康"，于是流量照发。
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD ["sh", "-c", "curl -sf http://127.0.0.1:${APLAT_HTTP_PORT}/health > /dev/null || exit 1"]

# 子命令名（serve）必须与 com.aplat.run.Main 的白名单一致——
# ContainerAssetsTest 会在构建前把这件事查出来，而不是等起容器才发现"未知命令"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar", "serve"]
