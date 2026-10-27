# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp verify -Dcollector.enabled=false

FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 routiva \
    && useradd --uid 10001 --gid routiva --create-home routiva
RUN mkdir -p /run/routiva \
    && chown routiva:routiva /run/routiva

WORKDIR /app
COPY --from=build --chown=routiva:routiva /build/target/scriptping-1.0.0.jar app.jar

USER routiva
ENV SERVER_ADDRESS=0.0.0.0 \
    PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/actuator/health > /dev/null || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
