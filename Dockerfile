# --- build stage ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# cache dependencies first
COPY pom.xml .
RUN mvn -B -q -e -DskipTests dependency:go-offline

# now build the jar
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && cp target/foodfinder-api-*.jar /workspace/app.jar

# --- runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

ENV JAVA_OPTS="" \
    PORT=8080 \
    FOODFINDER_STORAGE_DIR=/data/foodfinder

RUN useradd --system --uid 10001 --home /app foodfinder \
 && mkdir -p /data/foodfinder \
 && chown -R foodfinder:foodfinder /app /data/foodfinder

COPY --from=build /workspace/app.jar /app/app.jar
USER foodfinder

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD bash -lc 'exec 3<>/dev/tcp/127.0.0.1/${PORT}; printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3; grep -q 200 <&3 || exit 1'

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT}"]
