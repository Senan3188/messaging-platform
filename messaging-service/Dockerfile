FROM eclipse-temurin:27-jdk AS build
WORKDIR /workspace
COPY pom.xml ./
COPY messaging-service/pom.xml messaging-service/
COPY messaging-service/src messaging-service/src
RUN --mount=type=cache,target=/root/.m2 \
    apt-get update && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/* \
    && mvn -B -ntp -f messaging-service/pom.xml -DskipTests package

FROM eclipse-temurin:27-jre
WORKDIR /app
COPY --from=build /workspace/messaging-service/target/messaging-service.jar app.jar
USER 10001:10001
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
