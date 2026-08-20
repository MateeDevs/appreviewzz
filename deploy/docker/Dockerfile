# syntax=docker/dockerfile:1.7
# Build kontext = kořen repa: docker build -f deploy/docker/Dockerfile .

# Console (F3) se buildí do statických souborů, které servíruje Ktor ze stejného image —
# odpadá tím CloudFront, S3 i certifikát v us-east-1 (ADR 0008). Vlastní stage kvůli cache:
# změna v Kotlinu nepřestaví node_modules a naopak.
FROM node:22-alpine AS console
WORKDIR /console
COPY console/package.json console/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY console/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY server server
# Zbuilděná console jde do resources, odkud si ji Ktor bere z classpath.
COPY --from=console /console/dist server/app/src/main/resources/console
ARG GIT_SHA=unknown
ENV GIT_SHA=${GIT_SHA}
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --no-configuration-cache :server:app:installDist -x test -x ktlintCheck

FROM eclipse-temurin:21-jre AS runtime
# postgresql-client-17 kvůli zálohám (F1.8): pg_dump musí být stejně starý nebo novější než
# server, jinak zálohu odmítne udělat. Klient téhle verze v distribuci ještě není, proto
# oficiální repozitář PostgreSQL — klíč se ukládá rovnou jako signed-by, bez gnupg v image.
ARG PG_MAJOR=17
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && install -d /usr/share/postgresql-common/pgdg \
    && curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc \
        -o /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc \
    && codename="$(. /etc/os-release && echo "$VERSION_CODENAME")" \
    && printf '%s\n' \
        "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] http://apt.postgresql.org/pub/repos/apt ${codename}-pgdg main" \
        > /etc/apt/sources.list.d/pgdg.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends "postgresql-client-${PG_MAJOR}" \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --shell /usr/sbin/nologin appreviewzz \
    # Adresáře pro keyset a zálohy musí existovat už v image a patřit aplikaci — jinak je
    # Docker při prvním připojení volume založí rootovi a proces pod uid 10001 do nich nezapíše.
    && install -d -o appreviewzz -g appreviewzz /var/lib/appreviewzz /var/backups/appreviewzz \
    # Pebble je service manager zděděný z Ubuntu base image; nic ho tu nespouští
    # a tahá s sebou zranitelnosti Go runtime. Pryč s ním.
    && rm -rf /usr/bin/pebble /var/lib/pebble

WORKDIR /opt/appreviewzz
COPY --from=build /workspace/server/app/build/install/appreviewzz ./

USER appreviewzz
EXPOSE 8080

# CLI se v provozu pouští přes `docker compose exec api appreviewzz …` (README, docs/slack-app.md),
# takže spouštěč musí být na PATH — jinak si ho člověk musí pamatovat celou cestou.
ENV PATH="/opt/appreviewzz/bin:${PATH}" \
    APPREVIEWZZ_ROLE=api \
    SERVER_PORT=8080 \
    JAVA_OPTS="-Dlogback.configurationFile=logback-json.xml -XX:MaxRAMPercentage=75.0"

HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/health/live" || exit 1

# __cacert_entrypoint.sh z base image poskládá truststore ze systémových CA a pak nás spustí.
ENTRYPOINT ["/__cacert_entrypoint.sh", "/opt/appreviewzz/bin/appreviewzz"]
