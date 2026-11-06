# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- build ------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build

ENV MAVEN_OPTS="-XX:TieredStopAtLevel=1 -XX:+UseSerialGC"

COPY pom.xml .
COPY src ./src

RUN --mount=type=cache,id=angatu-maven,target=/root/.m2 \
    mvn -B -q -Dmaven.test.skip=true \
        -Daether.dependencyCollector.impl=bf \
        -Daether.syncContext.named.factory=file-lock \
        -Daether.syncContext.named.nameMapper=file-hgav \
        package \
 && mkdir -p /out/lib \
 && cp "$(ls -S target/*.jar | head -n1)" /out/app.jar \
 && if [ -d target/lib ]; then cp target/lib/*.jar /out/lib/; fi

# -------------------------------------------------------------- runtime ------
FROM eclipse-temurin:25-jre

# The template, tuned for the least memory: one collector thread, no optimizing compiler, small stacks, a capped direct buffer pool.
ENV TZ=America/Sao_Paulo \
    LANG=C.UTF-8 \
    ANGATU_ENV=production \
    ANGATU_DB_PATH=/data/database.db \
    PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss256k -XX:MaxDirectMemorySize=32m -XX:+ExitOnOutOfMemoryError -XX:+UseCompactObjectHeaders -Djava.awt.headless=true"

RUN useradd --system --uid 10001 --create-home app \
 && mkdir -p /data \
 && chown app:app /data

COPY --from=busybox:1.37-musl /bin/busybox /usr/local/bin/wget

COPY --from=build --chown=app:app /out/lib/ /opt/app/lib/
COPY --from=build --chown=app:app /out/app.jar /opt/app/app.jar

USER app
WORKDIR /data
EXPOSE 8080

HEALTHCHECK --interval=5s --timeout=3s --start-period=5s --start-interval=1s --retries=24 \
  CMD wget -q -T 2 -O /dev/null "http://127.0.0.1:${PORT}/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /opt/app/app.jar"]
