FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY docs/contracts/schemas/contract.schema.json docs/contracts/schemas/contract.schema.json
COPY backend/src/main backend/src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -f backend/pom.xml -Dmaven.test.skip=true package

FROM node:24-bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless python3 python3-venv ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/*
COPY scripts/probes/requirements-sina.txt /tmp/requirements-sina.txt
RUN python3 -m venv /opt/decoder \
    && /opt/decoder/bin/pip install --no-cache-dir --no-deps -r /tmp/requirements-sina.txt \
    && rm /tmp/requirements-sina.txt
WORKDIR /app/backend
COPY --from=build /workspace/backend/target/backend-0.0.1-SNAPSHOT.jar app.jar
COPY scripts/market/sina_adapter.py /app/scripts/market/sina_adapter.py
COPY scripts/probes/probe.py /app/scripts/probes/probe.py
ENV PATH="/opt/decoder/bin:${PATH}" \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC -Dfile.encoding=UTF-8" \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080
USER node
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
