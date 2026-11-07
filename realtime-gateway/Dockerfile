FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY pom.xml ./
COPY realtime-gateway/pom.xml realtime-gateway/
COPY realtime-gateway/src realtime-gateway/src
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -B -ntp -f realtime-gateway/pom.xml -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /workspace/realtime-gateway/target/realtime-gateway.jar app.jar
USER 10001:10001
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
