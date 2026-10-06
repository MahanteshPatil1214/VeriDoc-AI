# ---- VeriDoc AI :: backend image ----
# Multi-stage: the JDK and Maven cache never reach the runtime image.

# ---------- build ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependency resolution is cached separately from the source, so a code-only
# change does not re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests -DskipITs

# ---------- runtime ----------
FROM eclipse-temurin:21-jre AS runtime

# curl is here only to serve HEALTHCHECK; the temurin JRE image ships without
# it. Removed with its apt lists so it costs a few MB, not a few hundred.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# An unprivileged user: a container that processes untrusted PDFs has no
# business running as root.
RUN groupadd --system --gid 1001 veridoc \
    && useradd --system --uid 1001 --gid veridoc --home /app veridoc

WORKDIR /app
COPY --from=build /workspace/target/veridoc-backend.jar /app/veridoc-backend.jar

# Mount point for uploaded PDFs. Owned by the same uid as the process so a
# bind-mounted host directory still works without chmod.
RUN mkdir -p /app/data/storage && chown -R veridoc:veridoc /app/data
VOLUME ["/app/data"]

USER veridoc

ENV SERVER_PORT=8080 \
    STORAGE_ROOT=/app/data/storage \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport -Duser.timezone=UTC -Dfile.encoding=UTF-8"

EXPOSE 8080

# Uses the same readiness signal a human would: the actuator health endpoint.
# Unauthenticated on purpose; it exposes no sensitive state.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "curl -fsS http://localhost:8080/actuator/health/readiness || exit 1"]

ENTRYPOINT ["java", "-jar", "/app/veridoc-backend.jar"]
