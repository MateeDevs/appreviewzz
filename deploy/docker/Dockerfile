# syntax=docker/dockerfile:1.7
# Build kontext = kořen repa: docker build -f deploy/docker/Dockerfile .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY server server
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --no-configuration-cache :server:app:installDist -x test -x ktlintCheck

FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin appreviewzz

WORKDIR /opt/appreviewzz
COPY --from=build /workspace/server/app/build/install/appreviewzz ./

USER appreviewzz
EXPOSE 8080

ENV APPREVIEWZZ_ROLE=api \
    SERVER_PORT=8080 \
    JAVA_OPTS="-Dlogback.configurationFile=logback-json.xml -XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/health/live" || exit 1

ENTRYPOINT ["/opt/appreviewzz/bin/appreviewzz"]
